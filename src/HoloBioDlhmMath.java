/**
 * DLHM reconstruction algorithms.
 *
 * All distance and wavelength parameters are in µm. Works in µm-based frequency space
 * (no SI conversion needed; kz in µm⁻¹, z * kz is dimensionless phase).
 *
 * The three algorithms implemented here correspond to Python's parallel_rc.py:
 *   "AS"  → propagate()  with scale_factor = L/Z
 *   "DL"  → dlhm_rec()   with propagation kernel exp(j·r·√(k²−4π²(fx²+fy²)))
 *   "KR"  → kreuzer3F()  (cosine apodization + spherical prep + 3-FFT convolution)
 */
public final class HoloBioDlhmMath {

    private HoloBioDlhmMath() {}

    /**
     * Dispatch to the algorithm selected in {@code p.getAlgorithm()}.
     *
     * @param holo   hologram pixel values (rows × cols, row-major)
     * @param rows   image height (need not be power of two; padded internally)
     * @param cols   image width
     * @param p      DLHM parameters
     * @param outRe  output real part of reconstructed field (pre-allocated, size rows*cols)
     * @param outIm  output imaginary part
     */
    public static void reconstruct(float[] holo, int rows, int cols,
                                   HoloBioDlhmParams p, float[] outRe, float[] outIm) {
        switch (p.getAlgorithm()) {
            case "KR":
                reconstructKreuzer(holo, rows, cols, p, outRe, outIm);
                break;
            case "DL":
                reconstructDlhm(holo, rows, cols, p, outRe, outIm);
                break;
            default:
                reconstructAS(holo, rows, cols, p, outRe, outIm);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Algorithm 1: Angular Spectrum with scale_factor (Python propagate())
    // Python: dfx = 1/(dx*N); phase = exp(j*r*sf*2π*kz); sf = L/Z
    // -------------------------------------------------------------------------
    public static void reconstructAS(float[] holo, int rows, int cols,
                                     HoloBioDlhmParams p, float[] outRe, float[] outIm) {
        double lambda = p.getWavelengthUm();
        double dx     = p.getPixelPitchUm();
        double r      = p.getR();            // L − Z  (propagation distance)
        double sf     = p.getScaleFactor();  // L / Z  (magnification)

        // Transform at the frame's native size, exactly like Python's propagate().
        // Padding up to a power of two is NOT equivalent: the padded border convolves
        // into the spectrum, which measured ~9% peak amplitude error on a 320x240 frame.
        // HoloBioRectFft falls back to Bluestein when the length is not a power of two.
        final int pRows = rows;
        final int pCols = cols;
        float[] re = holo.clone();
        float[] im = new float[pRows * pCols];

        // Python: fftshift → fft2 → fftshift
        HoloBioRectFft.fftShift2d(re, im, pRows, pCols);
        HoloBioRectFft.fft2dForward(re, im, pRows, pCols);
        HoloBioRectFft.fftShift2d(re, im, pRows, pCols);

        double dfx = 1.0 / (dx * pCols);
        double dfy = 1.0 / (dx * pRows);

        // fx depends only on the column, so square it once per column rather than once
        // per pixel — the inner loop runs pRows times over the same values.
        final double[] fx2 = new double[pCols];
        for (int col = 0; col < pCols; col++) {
            double fx = (col - pCols / 2.0) * dfx;
            fx2[col] = fx * fx;
        }
        final double invLambda2 = 1.0 / (lambda * lambda);
        final double k = r * sf * 2.0 * Math.PI;
        final float[] fRe = re;
        final float[] fIm = im;
        final int fCols = pCols;
        final int fRows = pRows;
        final double fDfy = dfy;

        // Every pixel's kernel is independent of every other, so rows split across cores.
        HoloBioParallel.forEachLine(pRows, (from, to) -> {
            for (int row = from; row < to; row++) {
                double fy = (row - fRows / 2.0) * fDfy;
                double fy2 = fy * fy;
                int base = row * fCols;
                for (int col = 0; col < fCols; col++) {
                    // Same operand order as the original per-pixel expression: float
                    // subtraction does not associate, so folding fy2 in first would
                    // shift the last bits away from the Python parity fixtures.
                    double arg = invLambda2 - fx2[col] - fy2;
                    int    idx = base + col;
                    double kr, ki;
                    if (arg >= 0) {
                        // Python: phase = z * scale_factor * 2π * kz
                        double phase = k * Math.sqrt(arg);
                        kr = Math.cos(phase);
                        ki = Math.sin(phase);
                    } else {
                        kr = Math.exp(-k * Math.sqrt(-arg));
                        ki = 0.0;
                    }
                    float r0 = fRe[idx], i0 = fIm[idx];
                    fRe[idx] = (float) (r0 * kr - i0 * ki);
                    fIm[idx] = (float) (r0 * ki + i0 * kr);
                }
            }
        });

        // Python: ifftshift → ifft2 → ifftshift
        HoloBioRectFft.ifftShift2d(re, im, pRows, pCols);
        HoloBioRectFft.ifft2d(re, im, pRows, pCols);
        HoloBioRectFft.ifftShift2d(re, im, pRows, pCols);

        System.arraycopy(re, 0, outRe, 0, rows * cols);
        System.arraycopy(im, 0, outIm, 0, rows * cols);
    }

    // -------------------------------------------------------------------------
    // Algorithm 2: DLHM propagation kernel (Python dlhm_rec())
    //
    // Differences from AS:
    //   1. Radial-distortion pre-correction (Python cv2.undistort).
    //   2. Sample-plane frequency grid: dfx = Mag/(dx*N).
    //   3. Kernel: exp(j*r*√(k²−4π²(fx²+fy²))) — no scale_factor in phase.
    //   4. Python returns angle(conj(Uz)); we store conj(Uz) so atan2(im,re)
    //      matches automatically.
    // -------------------------------------------------------------------------
    public static void reconstructDlhm(float[] holo, int rows, int cols,
                                       HoloBioDlhmParams p, float[] outRe, float[] outIm) {
        double lambda = p.getWavelengthUm();
        double dx     = p.getPixelPitchUm();
        double L      = p.getL();
        double Z      = p.getZ();
        double r      = L - Z;
        double Mag    = Z > 1e-9 ? L / Z : 1.0;

        // Step 1: radial distortion correction (Python: cv2.undistort)
        double W_c     = dx * cols;
        double MagMax  = Math.sqrt(W_c * W_c / 2.0 + L * L) / Math.max(Z, 1e-12);
        double distMax = Math.abs(MagMax - Mag);
        double k1      = distMax / (2.0 * Math.max(Mag, 1e-12));
        float[] holoU  = dlhmUndistort(holo, rows, cols, k1);

        // Native size, like Python's dlhm_rec. Python fts = ifftshift(fft2(fftshift(.)));
        // for even lengths ifftshift is fftshift, so: fftshift → fft2 → fftshift.
        // (Padding to a power of two is not equivalent — see reconstructAS.)
        final int pRows = rows;
        final int pCols = cols;
        float[] re = holoU.clone();
        float[] im = new float[pRows * pCols];

        HoloBioRectFft.fftShift2d(re, im, pRows, pCols);
        HoloBioRectFft.fft2dForward(re, im, pRows, pCols);
        HoloBioRectFft.fftShift2d(re, im, pRows, pCols);

        // Mag-scaled frequency grid on the padded length (same Nyquist, denser bins)
        double dfx = Mag / (dx * pCols);
        double dfy = Mag / (dx * pRows);
        double k   = 2.0 * Math.PI / lambda;

        // fx depends only on the column; squaring it once per column keeps the exact
        // operand order of the original per-pixel expression (float subtraction and
        // addition do not associate, and the parity fixtures depend on the last bits).
        final double[] fx2 = new double[pCols];
        for (int col = 0; col < pCols; col++) {
            double fx = (col - pCols / 2.0) * dfx;
            fx2[col] = fx * fx;
        }
        final float[] fRe = re;
        final float[] fIm = im;
        final int fRows = pRows;
        final int fCols = pCols;
        final double fDfy = dfy, fK = k, fR = r;

        HoloBioParallel.forEachLine(pRows, (from, to) -> {
            for (int row = from; row < to; row++) {
                double fy = (row - fRows / 2.0) * fDfy;
                double fy2 = fy * fy;
                int base = row * fCols;
                for (int col = 0; col < fCols; col++) {
                    double arg = fK * fK - 4.0 * Math.PI * Math.PI * (fx2[col] + fy2);
                    int    idx = base + col;
                    double kr, ki;
                    if (arg >= 0) {
                        // One sqrt instead of two — same value, so bit-identical.
                        double q = fR * Math.sqrt(arg);
                        kr = Math.cos(q);
                        ki = Math.sin(q);
                    } else {
                        kr = Math.exp(-fR * Math.sqrt(-arg));
                        ki = 0.0;
                    }
                    float r0 = fRe[idx], i0 = fIm[idx];
                    fRe[idx] = (float) (r0 * kr - i0 * ki);
                    fIm[idx] = (float) (r0 * ki + i0 * kr);
                }
            }
        });

        // Python ifts = ifftshift(ifft2(fftshift(.))) ≡ fftshift → ifft2 → fftshift (even N)
        HoloBioRectFft.ifftShift2d(re, im, pRows, pCols);
        HoloBioRectFft.ifft2d(re, im, pRows, pCols);
        HoloBioRectFft.ifftShift2d(re, im, pRows, pCols);

        System.arraycopy(re, 0, outRe, 0, rows * cols);
        System.arraycopy(im, 0, outIm, 0, rows * cols);
        // Store conj(Uz) so atan2(im,re) = angle(conj(Uz))
        for (int i = 0; i < outIm.length; i++) outIm[i] = -outIm[i];
    }

    /**
     * Radial distortion correction matching OpenCV cv2.undistort with
     * camera matrix [[cols,0,cols/2],[0,rows,rows/2],[0,0,1]] and
     * distortion coefficients [k1, 0, 0, 0, 0].
     *
     * For each output pixel, compute its normalised undistorted position,
     * apply the radial model to get the distorted source position, then
     * bilinear-interpolate from the input.
     */
    private static float[] dlhmUndistort(float[] src, int rows, int cols, double k1) {
        final float[] dst = new float[rows * cols];
        final double  cx  = cols / 2.0;
        final double  cy  = rows / 2.0;
        final int fRows = rows, fCols = cols;
        final double fK1 = k1;
        // Every output pixel reads the source read-only, so rows split across cores.
        HoloBioParallel.forEachLine(rows, (from, to) -> {
            for (int r = from; r < to; r++) {
                double yn = (r - cy) / fRows;   // normalised with fy = rows
                for (int c = 0; c < fCols; c++) {
                    double xn  = (c - cx) / fCols;  // normalised with fx = cols
                    double r2  = xn * xn + yn * yn;
                    double fac = 1.0 + fK1 * r2;
                    double xs  = fac * xn * fCols + cx;   // source pixel in distorted image
                    double ys  = fac * yn * fRows + cy;
                    dst[r * fCols + c] = bilinearSample(src, fRows, fCols, xs, ys);
                }
            }
        });
        return dst;
    }

    private static float bilinearSample(float[] img, int rows, int cols, double x, double y) {
        int    x0 = (int) Math.floor(x),  x1 = x0 + 1;
        int    y0 = (int) Math.floor(y),  y1 = y0 + 1;
        double tx = x - x0,               ty = y - y0;
        float  v00 = safeGet(img, rows, cols, y0, x0);
        float  v10 = safeGet(img, rows, cols, y0, x1);
        float  v01 = safeGet(img, rows, cols, y1, x0);
        float  v11 = safeGet(img, rows, cols, y1, x1);
        return (float) ((1 - ty) * ((1 - tx) * v00 + tx * v10)
                      +      ty  * ((1 - tx) * v01 + tx * v11));
    }

    private static float safeGet(float[] img, int rows, int cols, int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return 0f;
        return img[r * cols + c];
    }

    // -------------------------------------------------------------------------
    // Algorithm 3: Kreuzer 3-step (Python kreuzer3F + prepairholoF + filtcosenoF)
    //
    // Steps:
    //   1. Crop hologram to square (min(rows,cols)).
    //   2. Build cosine² apodization window FC (Python filtcosenoF).
    //   3. Project each hologram pixel from flat detector onto the reference sphere
    //      and remap to planar coordinates via bilinear scatter (Python prepairholoF).
    //   4. Multiply by spherical reference-wave phase (L/Rp)^4 * exp(...).
    //   5. Three FFT convolution steps (T1, T2, K = ifts(T2*T1)) with zero-padding.
    //   6. Crop result; place in output image.
    // -------------------------------------------------------------------------
    public static void reconstructKreuzer(float[] holo, int rows, int cols,
                                          HoloBioDlhmParams p, float[] outRe, float[] outIm) {
        double lambda = p.getWavelengthUm();
        double dx     = p.getPixelPitchUm();
        double L      = p.getL();
        double Z      = p.getZ();
        double dX     = Z * dx / L;                              // sample-plane pixel pitch
        int    cpar   = Math.max(1, (int) Math.round(p.getCosinePeriod()));

        // ---- 1. Centre-crop to square, then mean-pad to a power of two ----
        // Python pads each side by s/2 → work size 2s, then FFTs at that size. Our FFT is
        // radix-2 only, so s itself must be a power of two (then 2s is too). Mean-padding the
        // square up to the next power of two keeps the FOV instead of throwing pixels away.
        int s0 = Math.min(rows, cols);
        int y0 = (rows - s0) / 2;
        int x0 = (cols - s0) / 2;
        float[] sq0 = new float[s0 * s0];
        for (int r = 0; r < s0; r++)
            System.arraycopy(holo, (r + y0) * cols + x0, sq0, r * s0, s0);

        int s = HoloBioRectFft.nextPowerOfTwo(s0);
        // Must be centred: Kreuzer geometry assumes the optical axis at (s/2,s/2).
        // Top-left mean-pad (used by AS/DL) shifts the hologram off-axis and destroys phase
        // while |field| can still look vaguely plausible.
        float[] sq = (s == s0) ? sq0 : padMeanCentered(sq0, s0, s0, s, s);

        // ---- 2. Cosine² apodization (Python filtcosenoF) ----
        float[] FC = kreuzerCosineFilter(cpar, s);

        // ---- 3. Geometry constants ----
        double k    = 2.0 * Math.PI / lambda;
        double W    = dx * s;
        double half = s / 2.0;
        double xo   = -W / 2.0;
        double xop  = xo * L / Math.sqrt(L * L + xo * xo);   // projected corner
        double yop  = xop;                                      // square pixels: yo = xo
        double dxp  = xop / (-half);                           // prepared-holo pixel pitch
        double Xo   = -dX * s / 2.0;
        double Yo   = Xo;

        // ---- 4. Spherical projection Xp, Yp (Python 1-indexed X/Y via linspace(1,row)) ----
        double[] Xpv = new double[s * s];
        double[] Ypv = new double[s * s];
        for (int i = 0; i < s; i++) {
            double Yv = i + 1.0;
            double cy = dx * (Yv - half);
            for (int j = 0; j < s; j++) {
                double Xv = j + 1.0;
                double cx = dx * (Xv - half);
                double dn = Math.sqrt(L * L + cx * cx + cy * cy);
                Xpv[i * s + j] = cx * L / dn;
                Ypv[i * s + j] = cy * L / dn;
            }
        }

        // ---- 5. Prepared hologram — spherical→planar bilinear scatter ----
        float[] CHpRe = kreuzerPrepareHolo(sq, s, xop, yop, Xpv, Ypv);
        float[] CHpIm = new float[s * s];

        // ---- 6. Multiply CHp by (L/Rp)^4 · exp(-j·0.5·k·(r²−2·Z·L)·Rp/L²) ----
        for (int i = 0; i < s; i++) {
            double Yv  = i + 1.0;
            double dcy = Yv - half;
            for (int j = 0; j < s; j++) {
                double Xv  = j + 1.0;
                double dcx = Xv - half;
                double rpA = L * L
                        - Math.pow(dxp * Xv + xop, 2)
                        - Math.pow(dxp * Yv + yop, 2);
                double Rp  = rpA > 0 ? Math.sqrt(rpA) : 1e-12;
                double rr  = Math.sqrt(dX * dX * (dcx * dcx + dcy * dcy) + Z * Z);
                double ang = -0.5 * k * (rr * rr - 2.0 * Z * L) * Rp / (L * L);
                double amp = Math.pow(L / Rp, 4);
                int    idx = i * s + j;
                float  re0 = CHpRe[idx];
                CHpRe[idx] = (float) (amp * re0 * Math.cos(ang));
                CHpIm[idx] = (float) (amp * re0 * Math.sin(ang));
            }
        }

        // ---- 7. Three FFT steps at exactly 2s×2s (Python np.pad(..., pad)) ----
        int pad  = s / 2;
        int fftN = 2 * s;   // guaranteed power-of-two because s is

        float[] T1re = new float[fftN * fftN];
        float[] T1im = new float[fftN * fftN];
        for (int i = 0; i < s; i++) {
            double Yv = i + 1.0;
            for (int j = 0; j < s; j++) {
                double Xv  = j + 1.0;
                double ang = (k / (2.0 * L)) * (
                        2.0 * Xo * Xv * dxp
                        + 2.0 * Yo * Yv * dxp
                        + Xv * Xv * dxp * dX
                        + Yv * Yv * dxp * dX);
                double cosA = Math.cos(ang), sinA = Math.sin(ang);
                int    src  = i * s + j;
                double tre  = CHpRe[src] * cosA - CHpIm[src] * sinA;
                double tim  = CHpRe[src] * sinA + CHpIm[src] * cosA;
                double fcv  = FC[src];
                int    dst  = (i + pad) * fftN + (j + pad);
                T1re[dst] = (float) (tre * fcv);
                T1im[dst] = (float) (tim * fcv);
            }
        }
        kreuzerFts(T1re, T1im, fftN);

        float[] T2re = new float[fftN * fftN];
        float[] T2im = new float[fftN * fftN];
        for (int i = 0; i < s; i++) {
            double Yv  = i + 1.0;
            double dcy = Yv - half;
            for (int j = 0; j < s; j++) {
                double Xv  = j + 1.0;
                double dcx = Xv - half;
                double ang = -(k / (2.0 * L)) * (
                        dcx * dcx * dxp * dX + dcy * dcy * dxp * dX);
                double fcv = FC[i * s + j];
                int    dst = (i + pad) * fftN + (j + pad);
                T2re[dst] = (float) (Math.cos(ang) * fcv);
                T2im[dst] = (float) (Math.sin(ang) * fcv);
            }
        }
        kreuzerFts(T2re, T2im, fftN);

        float[] Kre = new float[fftN * fftN];
        float[] Kim = new float[fftN * fftN];
        for (int idx = 0; idx < fftN * fftN; idx++) {
            float r1 = T1re[idx], i1 = T1im[idx];
            float r2 = T2re[idx], i2 = T2im[idx];
            Kre[idx] = r1 * r2 - i1 * i2;
            Kim[idx] = r1 * i2 + i1 * r2;
        }
        kreuzerIfts(Kre, Kim, fftN);

        // ---- 8. Crop like Python K[pad+1:pad+s] → (s-1)×(s-1), place centred ----
        // Leaving a hard zero border over the full camera canvas crushed phase contrast
        // (zeros dominate the min–max stretch). Write into a tight centred block instead.
        int cropStart = pad + 1;
        int cropLen   = s - 1;
        // If we mean-padded the square, keep only the original s0×s0 footprint
        int outLen = Math.min(cropLen, s0);
        int srcOff = (cropLen - outLen) / 2;
        int offR = (rows - outLen) / 2;
        int offC = (cols - outLen) / 2;
        java.util.Arrays.fill(outRe, 0f);
        java.util.Arrays.fill(outIm, 0f);
        for (int r = 0; r < outLen; r++) {
            int dR = offR + r;
            int sR = cropStart + srcOff + r;
            for (int c = 0; c < outLen; c++) {
                int dC = offC + c;
                int src = sR * fftN + (cropStart + srcOff + c);
                outRe[dR * cols + dC] = Kre[src];
                outIm[dR * cols + dC] = Kim[src];
            }
        }
    }

    // ---- Kreuzer helpers ----

    /** fts: fftshift → fft2 → fftshift on an N×N square array (matches Python fts). */
    private static void kreuzerFts(float[] re, float[] im, int N) {
        HoloBioRectFft.fftShift2d(re, im, N, N);
        HoloBioRectFft.fft2dForward(re, im, N, N);
        HoloBioRectFft.fftShift2d(re, im, N, N);
    }

    /** ifts: fftshift → ifft2 → fftshift on an N×N square array (matches Python ifts). */
    private static void kreuzerIfts(float[] re, float[] im, int N) {
        HoloBioRectFft.fftShift2d(re, im, N, N);
        HoloBioRectFft.ifft2d(re, im, N, N);
        HoloBioRectFft.fftShift2d(re, im, N, N);
    }

    /**
     * Cosine² × cosine² apodization window (Python filtcosenoF).
     * par controls the roll-off: par=2 → full window (zeros at edges);
     * par=100 → nearly flat (minimal windowing, matches Python's default).
     */
    private static float[] kreuzerCosineFilter(int par, int size) {
        float[] FC   = new float[size * size];
        double  half = size / 2.0;
        // Python filtcosenoF: cos(Xfc·π/par/Xfc.max())², then (FC>0)*FC / max
        double  scale = Math.PI / (par * half);
        float   max   = 0f;
        for (int r = 0; r < size; r++) {
            double Yfc = half - r * (double) size / Math.max(1, size - 1);
            double fc2 = Math.cos(Yfc * scale);
            fc2 = fc2 * fc2;
            for (int c = 0; c < size; c++) {
                double Xfc = -half + c * (double) size / Math.max(1, size - 1);
                double fc1 = Math.cos(Xfc * scale);
                fc1 = fc1 * fc1;
                // (FC1 > 0) * FC1 * (FC2 > 0) * FC2
                float v = (fc1 > 0 && fc2 > 0) ? (float) (fc1 * fc2) : 0f;
                FC[r * size + c] = v;
                if (v > max) max = v;
            }
        }
        if (max > 0f) {
            float inv = 1f / max;
            for (int i = 0; i < FC.length; i++) FC[i] *= inv;
        }
        return FC;
    }

    /**
     * Bilinear scatter: remap hologram pixels from spherical detector coords
     * to planar coordinates (Python prepairholoF).
     * Output is a square (s×s) real array.
     */
    private static float[] kreuzerPrepareHolo(float[] holo, int s,
                                              double xop, double yop,
                                              double[] Xpv, double[] Ypv) {
        float[]  CHp   = new float[s * s];
        double   pixSz = -2.0 * xop / s;   // = (-2*xop)/row

        for (int it = 0; it < s - 2; it++) {
            for (int jt = 0; jt < s - 2; jt++) {
                int    src    = it * s + jt;
                float  val    = holo[src];
                double xcoord = (Xpv[src] - xop) / pixSz;
                double ycoord = (Ypv[src] - yop) / pixSz;
                int    iX     = (int) Math.floor(xcoord);
                int    iY     = (int) Math.floor(ycoord);
                if (iX == 0) iX = 1;   // Python: iXcoord[iXcoord==0] = 1
                if (iY == 0) iY = 1;
                double x1f = iX + 1.0 - xcoord;
                double x2f = 1.0 - x1f;
                double y1f = iY + 1.0 - ycoord;
                double y2f = 1.0 - y1f;
                if (iY >= 0 && iY + 1 < s && iX >= 0 && iX + 1 < s) {
                    CHp[ iY      * s + iX    ] += (float) (x1f * y1f * val);
                    CHp[ iY      * s + iX + 1] += (float) (x2f * y1f * val);
                    CHp[(iY + 1) * s + iX    ] += (float) (x1f * y2f * val);
                    CHp[(iY + 1) * s + iX + 1] += (float) (x2f * y2f * val);
                }
            }
        }
        return CHp;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Pad hologram into a pRows×pCols array (top-left), filling the margin with the frame
     * mean. A constant pad transforms to a single DC bin, so it does not smear a cross across
     * the spectrum the way a hard zero edge does.
     */
    private static float[] padMean(float[] src, int rows, int cols, int pRows, int pCols) {
        double sum = 0;
        int n = rows * cols;
        for (int i = 0; i < n; i++) sum += src[i];
        float mean = n > 0 ? (float) (sum / n) : 0f;
        float[] out = new float[pRows * pCols];
        java.util.Arrays.fill(out, mean);
        for (int r = 0; r < rows; r++) {
            System.arraycopy(src, r * cols, out, r * pCols, cols);
        }
        return out;
    }

    /** Same as {@link #padMean} but centres the content (required by Kreuzer geometry). */
    private static float[] padMeanCentered(float[] src, int rows, int cols, int pRows, int pCols) {
        double sum = 0;
        int n = rows * cols;
        for (int i = 0; i < n; i++) sum += src[i];
        float mean = n > 0 ? (float) (sum / n) : 0f;
        float[] out = new float[pRows * pCols];
        java.util.Arrays.fill(out, mean);
        int offR = (pRows - rows) / 2;
        int offC = (pCols - cols) / 2;
        for (int r = 0; r < rows; r++) {
            System.arraycopy(src, r * cols, out, (r + offR) * pCols + offC, cols);
        }
        return out;
    }

    /** Crop the padded (pRows×pCols) result back to the original (rows×cols). */
    private static void crop(float[] re, float[] im, int pRows, int pCols,
                             float[] outRe, float[] outIm, int rows, int cols) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                outRe[r * cols + c] = re[r * pCols + c];
                outIm[r * cols + c] = im[r * pCols + c];
            }
        }
    }
}
