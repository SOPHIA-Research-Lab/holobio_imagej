import java.util.Arrays;

/**
 * Legendre background removal as done by the EAFIT MATLAB live tool
 * ({@code DHM.COMPENSATION.VORTEX_LEGENDRE.vortexLegendreCompensator} with
 * {@code useLegendre = true}, {@code NoPistonCompensation = true}, {@code UsePCA = false}).
 *
 * <p>Two things set it apart from the Python/offline port, and both matter on real data:
 * <ol>
 *   <li><b>Curvature is removed.</b> Orders 2–6 are subtracted — tilt x, tilt y and the three
 *       quadratic terms — so the spherical wave of the microscope objective goes, not only the
 *       residual tilt.</li>
 *   <li><b>Full resolution is kept.</b> The low-passed square (spectrum cropped to ±limit) is
 *       used only to <em>fit</em> the coefficients; the surface is then evaluated on the full
 *       field and divided out. The Python port instead returns that low-passed square, resized
 *       up, so the phase carries only 2·limit samples across.</li>
 * </ol>
 *
 * <p>Steps, mirroring {@code LegendreCompensation.m} (compensateSquare = false) and
 * {@code applyLegendreCoeffs.m}:
 * spectrum of the field → crop ±limit around DC → inverse transform → unwrap → project onto
 * square Legendre polynomials,
 * each normalised on its grid ({@code computeLegenderePolynomialsVector.m}) → evaluate orders
 * 2–6 on the full grid → subtract.
 *
 * <p><b>One departure:</b> the unwrap is the reliability-sorted algorithm Python HoloBio uses
 * ({@link #unwrapReliability}), not MATLAB's least-squares {@code phase_unwrap}. On the lab
 * holograms of 6 and 15 Oct 2026 the least-squares unwrap left 4–20 rad of false background;
 * the reliability unwrap left 0.1–0.8 rad.
 */
final class HoloBioLegendreLive {

    /** MATLAB live default: {@code limit = 256/2}. */
    static final int DEFAULT_LIMIT = 128;

    /** Orders removed from the field, 1-based as in MATLAB ({@code applyLegendreCoeffs(..., 2:6)}). */
    private static final int FIRST = 2, LAST = 6;

    private HoloBioLegendreLive() {
    }

    /**
     * Fit orders 2–6 to the phase of an {@code n × n} field.
     *
     * @return coefficients for orders 2…6 (index 0 = order 2), in radians, for basis functions
     *         normalised to unit energy over [−1, 1)²; null if the field is too small
     */
    static double[] fit(float[] fieldRe, float[] fieldIm, int n, int limit) {
        if (n < 8) return null;
        // Square side 2·L: power of two (the DCT unwrapper needs one) and inside the spectrum.
        int L = Integer.highestOneBit(Math.max(2, Math.min(limit, n / 2)));
        int s = 2 * L;

        float[] re = Arrays.copyOf(fieldRe, n * n);
        float[] im = Arrays.copyOf(fieldIm, n * n);
        HoloBioRectFft.fft2dForward(re, im, n, n);

        // Crop k ∈ [−L, L) in both axes, kept in unshifted order so the inverse needs no shift.
        // Sample m of the result lies at field pixel m·n/s — the same mapping MATLAB's
        // fftshift/ifftshift pairs produce.
        float[] sr = new float[s * s], si = new float[s * s];
        for (int kr = -L; kr < L; kr++) {
            int srcRow = ((kr % n) + n) % n, dstRow = ((kr % s) + s) % s;
            for (int kc = -L; kc < L; kc++) {
                int srcCol = ((kc % n) + n) % n, dstCol = ((kc % s) + s) % s;
                sr[dstRow * s + dstCol] = re[srcRow * n + srcCol];
                si[dstRow * s + dstCol] = im[srcRow * n + srcCol];
            }
        }
        HoloBioRectFft.ifft2d(sr, si, s, s);

        double[] wrapped = new double[s * s];
        for (int i = 0; i < wrapped.length; i++) wrapped[i] = Math.atan2(si[i], sr[i]);
        // Reliability-sorted unwrap (as Python HoloBio's skimage unwrap_phase), not MATLAB's
        // least-squares solve: on lab holograms the low-pass square carries phase singularities
        // in dark regions, and the least-squares solution spreads them into a false tilt and
        // curvature of up to ~20 rad, which the subtraction then writes into the phase map.
        double[] phi = unwrapReliability(wrapped, s, s);

        // Projection onto each normalised polynomial: c = Σ φ·P/‖P‖ · dA (fitPhaseToPolynomialsCoeff).
        double dA = (2.0 / s) * (2.0 / s);
        double[] x = grid(s);
        double[] c = new double[LAST - FIRST + 1];
        for (int t = FIRST; t <= LAST; t++) {
            double dot = 0, energy = 0;
            for (int r = 0; r < s; r++) {
                for (int col = 0; col < s; col++) {
                    double p = term(t, x[col], x[r]);
                    dot += phi[r * s + col] * p;
                    energy += p * p;
                }
            }
            double norm = Math.sqrt(Math.max(1e-30, energy * dA));
            c[t - FIRST] = dot * dA / norm;
        }
        return c;
    }

    /** {@code phase[i] −= W(x, y)} with W evaluated on the {@code n × n} grid from {@link #fit}. */
    static void subtract(float[] phase, int n, double[] c) {
        if (c == null) return;
        double[] x = grid(n);
        double[] norm = norms(n, x);
        double[] scale = new double[LAST - FIRST + 1];
        for (int t = FIRST; t <= LAST; t++) scale[t - FIRST] = c[t - FIRST] / norm[t - FIRST];
        // Orders 2–6 are x, y, (3x²−1)/2, xy, (3y²−1)/2: one pass, no per-term arrays.
        double a2 = scale[0], a3 = scale[1], a4 = scale[2], a5 = scale[3], a6 = scale[4];
        for (int r = 0; r < n; r++) {
            double y = x[r];
            double rowConst = a3 * y + a6 * (3 * y * y - 1) / 2;
            double rowX = a2 + a5 * y;
            int base = r * n;
            for (int col = 0; col < n; col++) {
                double xv = x[col];
                phase[base + col] -= (float) (rowConst + rowX * xv + a4 * (3 * xv * xv - 1) / 2);
            }
        }
    }

    /**
     * Two-dimensional unwrap by reliability sorting along a non-continuous path
     * (Herráez, Burton, Lalor &amp; Gdeisat, Appl. Opt. 41, 7437 (2002)) — the algorithm behind
     * {@code skimage.restoration.unwrap_phase}, which HoloBio Python uses.
     *
     * <p>Each interior pixel's reliability is the energy of its wrapped second differences
     * (horizontal, vertical, both diagonals); border pixels are least reliable. Every edge
     * between neighbours gets the sum of its two pixels' values, edges are processed from most
     * to least reliable, and each joins two groups by shifting the smaller one by the multiple
     * of 2π that makes the edge continuous. Noisy, singular regions are therefore unwrapped
     * last and cannot distort the rest — unlike a least-squares solve, which spreads them.
     */
    static double[] unwrapReliability(double[] psi, int w, int h) {
        int n = w * h;
        double[] rel = new double[n];
        java.util.Arrays.fill(rel, 1e7);                          // borders: least reliable
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double hh = wrap(psi[i - 1] - psi[i]) - wrap(psi[i] - psi[i + 1]);
                double vv = wrap(psi[i - w] - psi[i]) - wrap(psi[i] - psi[i + w]);
                double d1 = wrap(psi[i - w - 1] - psi[i]) - wrap(psi[i] - psi[i + w + 1]);
                double d2 = wrap(psi[i - w + 1] - psi[i]) - wrap(psi[i] - psi[i + w - 1]);
                rel[i] = hh * hh + vv * vv + d1 * d1 + d2 * d2;
            }
        }
        // Edges: horizontal (i, i+1) then vertical (i, i+w); sort keys pack reliability and id.
        int nh = h * (w - 1), nv = (h - 1) * w;
        long[] keys = new long[nh + nv];
        int e = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w - 1; x++, e++) {
                int i = y * w + x;
                keys[e] = ((long) Float.floatToIntBits((float) (rel[i] + rel[i + 1])) << 32) | e;
            }
        }
        for (int y = 0; y < h - 1; y++) {
            for (int x = 0; x < w; x++, e++) {
                int i = y * w + x;
                keys[e] = ((long) Float.floatToIntBits((float) (rel[i] + rel[i + w])) << 32) | e;
            }
        }
        java.util.Arrays.sort(keys);   // non-negative floats order like their bit patterns

        // Groups as linked lists; u holds the unwrapped value of every pixel.
        double[] u = psi.clone();
        int[] head = new int[n], next = new int[n], tail = new int[n], size = new int[n];
        for (int i = 0; i < n; i++) { head[i] = i; next[i] = -1; tail[i] = i; size[i] = 1; }
        double twoPi = 2 * Math.PI;
        for (long key : keys) {
            int id = (int) (key & 0xffffffffL);
            int p, q;
            if (id < nh) { int y = id / (w - 1), x = id % (w - 1); p = y * w + x; q = p + 1; }
            else { int k = id - nh; p = k; q = k + w; }
            int gp = head[p], gq = head[q];
            if (gp == gq) continue;
            // Shift the smaller group so the edge p–q becomes continuous.
            boolean moveQ = size[gq] <= size[gp];
            int small = moveQ ? gq : gp, big = moveQ ? gp : gq;
            double k = Math.rint((moveQ ? u[p] - u[q] : u[q] - u[p]) / twoPi);
            double shift = k * twoPi;
            for (int i = small; i >= 0; i = next[i]) {
                u[i] += shift;
                head[i] = big;
            }
            next[tail[big]] = small;
            tail[big] = tail[small];
            size[big] += size[small];
        }
        return u;
    }

    private static double wrap(double a) {
        return a - 2 * Math.PI * Math.floor((a + Math.PI) / (2 * Math.PI));
    }

    private static int normsN = -1;
    private static double[] normsCache;

    /**
     * ‖P_t‖ over the n × n grid for orders 2–6 (computeLegenderePolynomialsVector's per-term
     * normalisation). Depends only on n, so it is computed once per size, not per frame.
     */
    private static synchronized double[] norms(int n, double[] x) {
        if (n == normsN) return normsCache;
        double dA = (2.0 / n) * (2.0 / n);
        double[] out = new double[LAST - FIRST + 1];
        for (int t = FIRST; t <= LAST; t++) {
            double energy = 0;
            for (int r = 0; r < n; r++) {
                for (int col = 0; col < n; col++) {
                    double p = term(t, x[col], x[r]);
                    energy += p * p;
                }
            }
            out[t - FIRST] = Math.sqrt(Math.max(1e-30, energy * dA));
        }
        normsCache = out;
        normsN = n;
        return out;
    }

    /** MATLAB {@code meshgrid(-1 : 2/n : 1 - 2/n)}. */
    private static double[] grid(int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = -1.0 + 2.0 * i / n;
        return x;
    }

    /** Square Legendre polynomial {@code order} (1-based), as squareLegendrefitting.m. */
    private static double term(int order, double x, double y) {
        switch (order) {
            case 2: return x;
            case 3: return y;
            case 4: return (3 * x * x - 1) / 2;
            case 5: return x * y;
            case 6: return (3 * y * y - 1) / 2;
            default: return 0;
        }
    }
}
