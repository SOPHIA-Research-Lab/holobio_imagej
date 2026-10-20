import java.util.Arrays;

/**
 * Real-time DHM reconstruction — off-axis sideband demodulation.
 *
 * One forward FFT serves both products. Per frame:
 * <ol>
 *   <li>Fit the frame onto a power-of-two grid and fill the unused margin with the frame mean.
 *       Because a constant over the whole grid transforms to a single DC spike, mean-filling
 *       adds nothing but DC — unlike zero-padding, which puts a step at the pad boundary and
 *       smears a bright cross across the spectrum.</li>
 *   <li>Forward FFT (left unshifted; every later step does its own index wrapping, which is
 *       far cheaper than two full fftshift passes over the grid).</li>
 *   <li>FT display: magnitude → resample to the frame aspect → optional log1p → /max → 0…255.
 *       Matches HoloBio Python {@code _fourier_display_from_hologram}.</li>
 *   <li>Circular mask isolates the +1 diffraction order (auto-detected once, or user-supplied).</li>
 *   <li>IFFT, then Vortex–Legendre compensation:
 *       <ol>
 *         <li><b>Vortex:</b> Python {@code vortex_compensation} (shared with Offline DHM) places
 *             the carrier to sub-pixel accuracy from the phase vortex of the Hilbert-transformed
 *             log spectrum, 55× interpolated around the peak;</li>
 *         <li><b>reference wave</b> from that carrier, exp(−i·2π·Δ·m/M) — Python's
 *             exp(i·k·dx·sinθ·m) with the arcsin, λ and dx cancelled;</li>
 *         <li><b>Legendre</b> (Vortex Legendre mode only): orders 2–6 fitted on a ±128 spectral
 *             crop and removed at full resolution, as the EAFIT MATLAB live tool, with the
 *             reliability-sorted unwrap Python uses — see {@link HoloBioLegendreLive}.</li>
 *       </ol></li>
 *   <li>amplitude = |field|, phase = angle(field); only the requested one is evaluated.</li>
 * </ol>
 *
 * <p><b>Where this departs from the reference implementations, and why.</b>
 * <ul>
 *   <li>Latency: the vortex search runs when the order is (re)detected — Start, Re-detect, a
 *       change of mask or grid — and is reused, as Python RT caches its model (MATLAB re-runs
 *       it per frame); the Legendre fit is refreshed every fourth frame and on detection.</li>
 *   <li>Latency: power-of-two grid with the margin mean-filled (MATLAB and Python: native size).</li>
 *   <li>Robustness: the +1 order is found by prominence over the radial falloff rather than
 *       the brightest bin ({@link #detectSideband}). On the 15 Oct lab recordings the brightest
 *       bin is a second, weaker-object order; prominence finds the one that reconstructs.</li>
 *   <li>Robustness: a vortex result more than 1.5 bins from the peak is rejected.</li>
 *   <li>Robustness: no MATLAB {@code compResidualRamp} (Python RT has none either); it added
 *       background tilt on lab data.</li>
 *   <li>Robustness: reliability unwrap in the Legendre fit instead of MATLAB's least squares,
 *       which left 4–21 rad of false background on lab data.</li>
 * </ul>
 *
 * <p>All buffers are reused between frames and the class keeps static state, so a single
 * processing thread must own it.
 */
public final class HoloBioRtDhmMath {

    public static final int FILTER_NONE   = 0;
    public static final int FILTER_CIRCLE = 1;

    // ── Spectrum grid ─────────────────────────────────────────────────────────
    private static float[] specRe, specIm;
    private static int     gridRows, gridCols;

    // ── FT display buffer ─────────────────────────────────────────────────────
    private static float[] ftBuf;
    private static float[] ftRowMin, ftRowMax;
    private static int     ftRows, ftCols;

    // ── Output buffers ────────────────────────────────────────────────────────
    private static float[] ampBuf, phaseBuf, phaseRadBuf;
    /** Per-row amplitude min/max from the parallel demodulation pass. */
    private static float[] rowMin, rowMax;
    /** Demodulated +1-order field of the last frame (before phase flattening). */
    private static float[] fieldReBuf, fieldImBuf;
    private static int     fieldRows, fieldCols;
    private static int     outRowsC, outColsC;
    /** Full-frame display buffers (recon crop embedded at original aspect). */
    private static float[] ampDispBuf, phaseDispBuf, phaseRadDispBuf;
    private static int     dispRows, dispCols;

    // ── Sideband location, held across frames ─────────────────────────────────
    // The carrier is fixed by the optical setup, so re-searching the spectrum every frame
    // costs a full grid scan and buys nothing. Python likewise builds its model once.
    private static boolean sbValid;
    private static int     sbRow, sbCol, sbRadius;
    /** Vortex-refined carrier (bins from DC, sub-bin), refreshed whenever the order is re-found. */
    private static boolean vortexValid;
    private static double  vortexDx, vortexDy;

    /** Legendre coefficients are refitted this often (frames), and on every detection. */
    private static final int LEGENDRE_REFIT_EVERY = 4;
    private static int     legendreAge, legendreN;
    private static boolean legendreFresh;

    // ── Carrier-cancelling ramp, refitted each frame ──────────────────────────
    private static float[] rampCosX, rampSinX, rampCosY, rampSinY;
    private static double  carrierX, carrierY; // radians per pixel

    /** Whether to subtract the fitted low-order phase background (the Legendre stage). */
    private static volatile boolean flattenPhase = true;

    /**
     * Auto-detected +1-order mask shape. Python RT {@code spatial_filter} defaults to a
     * circle of radius {@code dist/factor}; the rectangular box is an optional faster cut.
     */
    private static volatile boolean circularMask = true;

    /** Half-size of the spectral crop the Legendre fit uses; MATLAB live default 128. */
    private static volatile int legendreLimit = HoloBioLegendreLive.DEFAULT_LIMIT;

    public static void setLegendreLimit(int limit) { legendreLimit = Math.max(4, limit); }

    public static void setFlattenPhase(boolean on) { flattenPhase = on; }
    public static boolean isFlattenPhase() { return flattenPhase; }

    public static void setCircularMask(boolean on) {
        if (circularMask != on) {
            circularMask = on;
            sbValid = false;
        }
    }
    public static boolean isCircularMask() { return circularMask; }

    /**
     * Python QPI {@code spatialFilteringCF}: {@code radius = dist(DC,+1) / factor}, default factor 3.
     * {@code manualRadiusPx <= 0} keeps that auto radius.
     */
    private static volatile double radiusFactor = 3.0;
    private static volatile int    manualRadiusPx;

    public static void setRadiusFactor(double factor) {
        double f = factor < 1.0 ? 1.0 : factor;
        if (radiusFactor != f) radiusFactor = f;
    }

    public static void setManualRadiusPx(int px) {
        manualRadiusPx = Math.max(0, px);
    }

    public static double radiusFactor() { return radiusFactor; }

    /**
     * Whether the FT view shows the spectrum after the mask rather than before it.
     * Python offers both through its {@code ft_display_var}.
     */
    private static volatile boolean ftFiltered;

    public static void setFtFiltered(boolean on) { ftFiltered = on; }
    public static boolean isFtFiltered() { return ftFiltered; }

    private static double[] lastFit;
    /** Legendre coefficients (orders 2–6: x, y, (3x²−1)/2, xy, (3y²−1)/2) of the last frame. */
    public static double[] lastBackgroundFit() { return lastFit; }

    private HoloBioRtDhmMath() {}

    /**
     * One processed frame. {@code amplitude} and {@code phase} are {@code null} when not
     * requested; {@code ft} is square (the transform runs on a centred square crop) and is
     * {@code null} when skipped, so it does not share the reconstruction's dimensions.
     * All arrays are internal buffers, valid only until the next {@link #process} call.
     */
    public static final class Frame {
        /** Magnitude spectrum in 0…255, {@code ftWidth × ftHeight}, or {@code null}. */
        public final float[] ft;
        public final int     ftWidth, ftHeight;
        /** Reconstruction products in 0…255, {@code width × height}, or {@code null}. */
        public final float[] amplitude, phase;
        /**
         * Phase in radians, wrapped to (−π, π], same geometry as {@link #phase}. Letterbox
         * pixels outside the reconstructed region are {@code NaN} so quantitative readers
         * (profiles, stats) can tell "no data" from "phase of zero". {@code null} when phase
         * was not requested.
         */
        public final float[] phaseRadians;
        public final int     width, height;

        Frame(float[] ft, int ftWidth, int ftHeight,
              float[] amplitude, float[] phase, float[] phaseRadians, int width, int height) {
            this.ft           = ft;
            this.ftWidth      = ftWidth;
            this.ftHeight     = ftHeight;
            this.amplitude    = amplitude;
            this.phase        = phase;
            this.phaseRadians = phaseRadians;
            this.width        = width;
            this.height       = height;
        }
    }

    /**
     * Display/profile products from a reconstructed complex field (Vortex–Legendre),
     * using Python's cyclic 8-bit phase map: {@code (angle+π)/(2π)·255}.
     */
    public static Frame fromComplexField(HoloBioCompensationAlgorithms.ComplexField field,
                                         float[] ft, int ftWidth, int ftHeight,
                                         boolean wantAmplitude, boolean wantPhase) {
        int w = field.width, h = field.height, n = w * h;
        float[] amp = wantAmplitude ? new float[n] : null;
        float[] phase = wantPhase ? new float[n] : null;
        float[] phaseRad = wantPhase ? new float[n] : null;
        float aMin = Float.MAX_VALUE, aMax = -Float.MAX_VALUE;
        float k = (float) (255.0 / (2.0 * Math.PI));
        for (int i = 0; i < n; i++) {
            double re = field.re[i], im = field.im[i];
            if (amp != null) {
                float a = (float) Math.sqrt(re * re + im * im);
                amp[i] = a;
                if (a < aMin) aMin = a;
                if (a > aMax) aMax = a;
            }
            if (phase != null) {
                float p = (float) Math.atan2(im, re);
                phaseRad[i] = p;
                float v = (p + (float) Math.PI) * k;
                phase[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
            }
        }
        if (amp != null) stretch(amp, aMin, aMax);
        return new Frame(ft, ftWidth, ftHeight, amp, phase, phaseRad, w, h);
    }

    /** Forget the cached sideband so the next frame re-searches for the carrier. */
    public static void resetSideband() {
        sbValid = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process one hologram frame.
     *
     * @param holo          grey-level pixels, row-major, length = rows × cols
     * @param rows          frame height
     * @param cols          frame width
     * @param lambdaUm      illumination wavelength in µm  (e.g. 0.532)
     * @param dxUm          pixel pitch X in µm            (e.g. 5.20)
     * @param dyUm          pixel pitch Y in µm            (e.g. 5.20)
     * @param filterMode    {@link #FILTER_NONE} (auto-detect) or {@link #FILTER_CIRCLE}
     * @param filterCxPad   +1-order column in the centred spectrum; DC sits at gridCols/2
     * @param filterCyPad   +1-order row    in the centred spectrum; DC sits at gridRows/2
     * @param filterRadius  mask half-width in spectrum pixels
     * @param computeFt     skip the spectrum entirely when false
     * @param ftLogScale    log1p magnitude when true, linear magnitude when false
     * @param wantAmplitude evaluate |field| (one sqrt per pixel)
     * @param wantPhase     evaluate arg(field) (one atan2 per pixel)
     */
    /**
     * Copy of the demodulated complex field (carrier removed, before Legendre/background
     * flattening, which only shapes the displayed phase) from the last {@link #process}
     * call, cropped to the reconstructed square. Null before the first frame.
     */
    public static synchronized HoloBioNpy.Snapshot lastField() {
        if (fieldReBuf == null || fieldRows <= 0 || fieldCols <= 0) {
            return null;
        }
        int n = fieldRows * fieldCols;
        return new HoloBioNpy.Snapshot(java.util.Arrays.copyOf(fieldReBuf, n),
                java.util.Arrays.copyOf(fieldImBuf, n), fieldCols, fieldRows);
    }

    public static synchronized Frame process(float[] holo, int rows, int cols,
                                    double lambdaUm, double dxUm, double dyUm,
                                    int filterMode, int filterCxPad, int filterCyPad,
                                int filterRadius,
                                boolean computeFt, boolean ftLogScale,
                                boolean wantAmplitude, boolean wantPhase) {

        // Match Python RT: centred even square crop, then pad (never crop) to the next
        // power-of-two so the radix-2 FFT can run. 1280×960 → 960×960 at (160,0), FFT 1024².
        int side = Math.min(rows, cols);
        if ((side & 1) != 0) side--;
        side = Math.max(2, side);
        int ox = Math.max(0, (cols - side) / 2);
        int oy = Math.max(0, (rows - side) / 2);
        int srcRows = side;
        int srcCols = side;
        int gRows = HoloBioRectFft.nextPowerOfTwo(side);
        int gCols = gRows;
        int pad = (gRows - side) / 2;

        if (gridRows != gRows || gridCols != gCols) {
            specRe   = new float[gRows * gCols];
            specIm   = new float[gRows * gCols];
            gridRows = gRows; gridCols = gCols;
            sbValid  = false;
        }
        float[] re = specRe, im = specIm;

        // ── Load the frame onto the grid, margin at the frame mean ────────────
        // The margin is where this differs from Python, which transforms the frame at its
        // native size. Whatever fills it, a band of gRows−srcRows rows that does not carry the
        // fringes convolves the spectrum with that band's transform and rules a ladder of
        // stripes across it, spaced gRows/(gRows−srcRows) bins apart. A ramp bridging the wrap
        // was tried and removes no more of it than a constant does, while leaking slightly
        // more into the mask. Only a transform at the native frame size avoids it outright.
        double sum = 0;
        for (int r = 0; r < srcRows; r++) {
            int base = (r + oy) * cols + ox;
            for (int c = 0; c < srcCols; c++) sum += holo[base + c];
        }
        float mean = (float) (sum / ((double) srcRows * srcCols));
        Arrays.fill(re, mean);
        Arrays.fill(im, 0f);
        for (int r = 0; r < srcRows; r++) {
            System.arraycopy(holo, (r + oy) * cols + ox, re, (r + pad) * gCols + pad, srcCols);
        }

        HoloBioRectFft.fft2dForward(re, im, gRows, gCols);

        // ── FT display, unfiltered: taken before the mask consumes the spectrum ───
        // Rendered square, matching the square transform grid. Resampling onto the frame's
        // rectangular aspect instead would squash the sideband lobe and the circular mask
        // into ovals.
        float[] ftDisplay = null;
        boolean wantFiltered = ftFiltered;
        if (computeFt && !wantFiltered) {
            ftDisplay = spectrumDisplay(re, im, gRows, gCols, side, side, ftLogScale, false);
        }

        // ── Locate the +1 diffraction order ───────────────────────────────────
        if (filterMode == FILTER_CIRCLE && filterRadius > 0) {
            int row = wrapIndex(filterCyPad + gRows / 2, gRows);
            int col = wrapIndex(filterCxPad + gCols / 2, gCols);
            if (row != sbRow || col != sbCol || !sbValid) vortexValid = false;
            sbRow    = row;
            sbCol    = col;
            sbRadius = filterRadius;
            sbValid  = true;
        } else {
            if (!sbValid) {
                detectSideband(re, im, gRows, gCols);
                sbValid = true;
                vortexValid = false;
            }
            applyMaskRadius(gRows, gCols);
        }

        // ── +1-order mask in unshifted coordinates ────────────────────────────
        // Circular matches Python QPI {@code spatialFilteringCF} (radius = dist / factor).
        // Rectangular is the axis-aligned box of the same half-width.
        int halfR = gRows / 2, halfC = gCols / 2;
        int rad = sbRadius;
        long rad2 = (long) rad * (long) rad;
        boolean circle = circularMask;
        for (int r = 0; r < gRows; r++) {
            int dr = r - sbRow;
            if (dr >  halfR) dr -= gRows;
            if (dr < -halfR) dr += gRows;
            int base = r * gCols;
            if (dr > rad || dr < -rad) {
                Arrays.fill(re, base, base + gCols, 0f);
                Arrays.fill(im, base, base + gCols, 0f);
                continue;
            }
            for (int c = 0; c < gCols; c++) {
                int dc = c - sbCol;
                if (dc >  halfC) dc -= gCols;
                if (dc < -halfC) dc += gCols;
                boolean kill = circle
                        ? ((long) dr * dr + (long) dc * dc > rad2)
                        : (dc > rad || dc < -rad);
                if (kill) {
                    re[base + c] = 0f;
                    im[base + c] = 0f;
                }
            }
        }

        // ── FT display, filtered: what actually survives the mask ─────────────
        // Min-max scaled rather than max-only, matching Python's _vl_log_display, which routes
        // this view through _normalize_to_uint8. It matters here: the mask zeroes most of the
        // grid, so log1p of the surviving lobe sits well above zero and max-only scaling would
        // render the whole thing as a washed-out grey block.
        if (computeFt && wantFiltered) {
            ftDisplay = spectrumDisplay(re, im, gRows, gCols, side, side, ftLogScale, true);
        }

        // ── Vortex: sub-pixel carrier from the filtered spectrum ──────────────
        // Python vortex_compensation (shared with Offline DHM), run when the order is
        // (re)detected and cached — as Python RT caches its model — not on every frame.
        if (!vortexValid) {
            refineCarrierWithVortex(re, im, gRows, gCols);
            vortexValid = true;
            legendreFresh = false;     // new carrier → refit the background on this frame
        }

        HoloBioRectFft.ifft2d(re, im, gRows, gCols);

        // ── Reference wave from the vortex carrier, in radians per pixel ───────
        // No residual-ramp step (MATLAB compResidualRamp). Python RT has none, and on lab
        // holograms it added tilt: the mean neighbour phase step over the filtered field is
        // dominated by the bright, asymmetric object halo inside the mask, not the background
        // (15 Oct recordings: 0.3 fringes of background tilt before it, 1.8 after). Residual
        // tilt is left to the Legendre stage, which fits it on the low-passed background.
        double gx = 2 * Math.PI * vortexDx / gCols;
        double gy = 2 * Math.PI * vortexDy / gRows;
        carrierX = gx;
        carrierY = gy;

        // Separable ramp tables. Tabulating beats an incremental rotor here: a recurrence
        // drifts in both angle and modulus over several hundred steps, and the resulting
        // error is itself a slow ramp, which is exactly the artefact being removed.
        if (rampCosX == null || rampCosX.length < srcCols) {
            rampCosX = new float[srcCols]; rampSinX = new float[srcCols];
        }
        if (rampCosY == null || rampCosY.length < srcRows) {
            rampCosY = new float[srcRows]; rampSinY = new float[srcRows];
        }
        for (int c = 0; c < srcCols; c++) {
            double a = -gx * c;
            rampCosX[c] = (float) Math.cos(a);
            rampSinX[c] = (float) Math.sin(a);
        }
        for (int r = 0; r < srcRows; r++) {
            double a = -gy * r;
            rampCosY[r] = (float) Math.cos(a);
            rampSinY[r] = (float) Math.sin(a);
        }

        // ── Demodulate and evaluate the requested products ────────────────────
        if (outRowsC != srcRows || outColsC != srcCols) {
            ampBuf      = new float[srcRows * srcCols];
            phaseBuf    = new float[srcRows * srcCols];
            phaseRadBuf = new float[srcRows * srcCols];
            outRowsC = srcRows; outColsC = srcCols;
        }
        if (fieldReBuf == null || fieldReBuf.length != srcRows * srcCols) {
            fieldReBuf = new float[srcRows * srcCols];
            fieldImBuf = new float[srcRows * srcCols];
        }
        fieldRows = srcRows;
        fieldCols = srcCols;
        float[] amp      = wantAmplitude ? ampBuf      : null;
        float[] phase    = wantPhase     ? phaseBuf    : null;
        float[] phaseRad = wantPhase     ? phaseRadBuf : null;

        // Rows are independent, so the per-pixel pass (atan2 dominates: it is StrictMath on
        // Java 8) is split across cores. Each row keeps its own min/max and they are reduced
        // afterwards — min/max do not depend on order, so the output is bit-identical.
        if (rowMin == null || rowMin.length < srcRows) {
            rowMin = new float[srcRows];
            rowMax = new float[srcRows];
        }
        final float[] fRe0 = re, fIm0 = im, fieldRe = fieldReBuf, fieldIm = fieldImBuf;
        final float[] cX = rampCosX, sX = rampSinX, cY = rampCosY, sY = rampSinY;
        final float[] ampW = amp, phaseW = phase;
        final int gColsF = gCols, padF = pad, srcColsF = srcCols;
        HoloBioParallel.forEachLine(srcRows, (from, to) -> {
            for (int r = from; r < to; r++) {
                float cosY = cY[r], sinY = sY[r];
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                int si = (r + padF) * gColsF + padF, di = r * srcColsF;
                for (int c = 0; c < srcColsF; c++, si++, di++) {
                    float fRe = fRe0[si], fIm = fIm0[si];
                    float rCos = cX[c] * cosY - sX[c] * sinY;
                    float rSin = sX[c] * cosY + cX[c] * sinY;
                    float oRe = fRe * rCos - fIm * rSin;
                    float oIm = fRe * rSin + fIm * rCos;
                    fieldRe[di] = oRe;
                    fieldIm[di] = oIm;
                    if (ampW != null) {
                        // Math.hypot guards against intermediate overflow at roughly 20× the
                        // cost of sqrt; these magnitudes are nowhere near the float limits.
                        float a = (float) Math.sqrt((double) oRe * oRe + (double) oIm * oIm);
                        ampW[di] = a;
                        if (a < lo) lo = a;
                        if (a > hi) hi = a;
                    }
                    if (phaseW != null) {
                        phaseW[di] = (float) Math.atan2(oIm, oRe);
                    }
                }
                rowMin[r] = lo;
                rowMax[r] = hi;
            }
        });

        if (amp != null) {
            float aMin = Float.MAX_VALUE, aMax = -Float.MAX_VALUE;
            for (int r = 0; r < srcRows; r++) {
                if (rowMin[r] < aMin) aMin = rowMin[r];
                if (rowMax[r] > aMax) aMax = rowMax[r];
            }
            stretch(amp, aMin, aMax);
        }

        if (phase != null) {
            if (flattenPhase) {
                // MATLAB live Vortex–Legendre: fit orders 2–6 on the ±limit spectral crop of
                // the demodulated field, remove them at full resolution. The old tilt-only fit
                // (removeSmoothBackground) left the objective's curvature wrapping across the map.
                // The fit (one native-size FFT + unwrap, ~90 ms at 960²) is refreshed every
                // LEGENDRE_REFIT_EVERY frames and on every detection; the polynomial itself is
                // removed from every frame. MATLAB refits per frame; Python RT fits once per
                // model refresh. The background drifts slowly, so this sits between the two.
                if (lastFit == null || !legendreFresh || ++legendreAge >= LEGENDRE_REFIT_EVERY
                        || legendreN != srcRows) {
                    lastFit = HoloBioLegendreLive.fit(fieldReBuf, fieldImBuf, srcRows, legendreLimit);
                    legendreAge = 0;
                    legendreN = srcRows;
                    legendreFresh = true;
                }
                HoloBioLegendreLive.subtract(phase, srcRows, lastFit);
            }

            // Centre on the *background* circular mean, not the whole field. USAF bars (and
            // any high-φ object) pull a full-field mean up by ~0.2 rad, so subtracting it
            // parked every live profile that much below Python QPI. Python VL zeros the
            // Legendre constant on the spectral crop (background-dominated); this is the
            // fast equivalent. Still required: without any piston the wrap seam cuts the map.
            final float piston = backgroundPiston(phase, srcRows, srcCols);
            final float k = (float) (255.0 / (2.0 * Math.PI));
            final float[] ph = phase, phR = phaseRad;
            final int cols0 = srcCols;
            HoloBioParallel.forEachLine(srcRows, (from, to) -> {
                for (int i = from * cols0, n = to * cols0; i < n; i++) {
                    float p = (float) Math.IEEEremainder(ph[i] - piston, 2 * Math.PI);
                    phR[i] = p;
                    float v = (p + (float) Math.PI) * k;
                    ph[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
                }
            });
        }

        // Embed the recon crop into the native frame size. Independent power-of-two crops
        // (e.g. 640→512 while 480 stays 480) change the aspect ratio vs hologram/FT and the
        // display panel then looks anisotropically stretched even though the algorithm is fine.
        float[] ampOut      = amp      != null ? embedAt(amp,      srcCols, srcRows, cols, rows, ox, oy, EMBED_AMP)       : null;
        float[] phaseOut    = phase    != null ? embedAt(phase,    srcCols, srcRows, cols, rows, ox, oy, EMBED_PHASE)     : null;
        float[] phaseRadOut = phaseRad != null ? embedAt(phaseRad, srcCols, srcRows, cols, rows, ox, oy, EMBED_PHASE_RAD) : null;
        // FT is square (side × side), unlike the reconstruction which is embedded back into
        // the native frame size — see spectrumDisplay.
        return new Frame(ftDisplay, side, side, ampOut, phaseOut, phaseRadOut, cols, rows);
    }

    private static final int EMBED_AMP = 0, EMBED_PHASE = 1, EMBED_PHASE_RAD = 2;

    /**
     * Place a {@code sw×sh} image centred in a {@code dw×dh} canvas.
     * Amplitude letterbox is black; phase letterbox is mid-grey (neutral on the cyclic map);
     * radian letterbox is NaN, meaning "outside the reconstruction".
     */
    private static float[] embedAt(float[] src, int sw, int sh, int dw, int dh,
                                   int x0, int y0, int kind) {
        if (sw == dw && sh == dh && x0 == 0 && y0 == 0) return src;
        if (dispRows != dh || dispCols != dw) {
            ampDispBuf      = new float[dw * dh];
            phaseDispBuf    = new float[dw * dh];
            phaseRadDispBuf = new float[dw * dh];
            dispRows = dh; dispCols = dw;
        }
        float[] out  = kind == EMBED_AMP ? ampDispBuf
                     : kind == EMBED_PHASE ? phaseDispBuf : phaseRadDispBuf;
        float   fill = kind == EMBED_AMP ? 0f
                     : kind == EMBED_PHASE ? 127.5f : Float.NaN;
        Arrays.fill(out, fill);
        for (int r = 0; r < sh; r++) {
            int dy = y0 + r;
            if (dy < 0 || dy >= dh) continue;
            int sx = 0, dx = x0, n = sw;
            if (dx < 0) { sx = -dx; n -= sx; dx = 0; }
            if (dx + n > dw) n = dw - dx;
            if (n > 0) System.arraycopy(src, r * sw + sx, out, dy * dw + dx, n);
        }
        return out;
    }

    /**
     * Circular-mean piston of the background, so high-φ object pixels do not drag the
     * whole map down. First estimate from a subsample, then re-average pixels within
     * 0.4 rad of that estimate (the troughs), ignoring the bars.
     */
    private static float backgroundPiston(float[] phase, int rows, int cols) {
        int n = rows * cols;
        double cRe = 0, cIm = 0;
        for (int i = 0; i < n; i += 4) {
            cRe += Math.cos(phase[i]);
            cIm += Math.sin(phase[i]);
        }
        if (cRe * cRe + cIm * cIm <= 1e-30) return 0f;
        double p0 = Math.atan2(cIm, cRe);
        cRe = 0;
        cIm = 0;
        int count = 0;
        final double twoPi = 2 * Math.PI, invTwoPi = 1.0 / twoPi;
        for (int i = 0; i < n; i += 2) {
            // Same test as IEEEremainder(d, 2π) > 0.25, without StrictMath's per-call cost
            // (this loop is serial and ran ~15 ms per 1280×960 frame).
            double d = phase[i] - p0;
            if (d - twoPi * Math.rint(d * invTwoPi) > 0.25) continue;
            cRe += Math.cos(phase[i]);
            cIm += Math.sin(phase[i]);
            count++;
        }
        if (count < 16 || cRe * cRe + cIm * cIm <= 1e-30) return (float) p0;
        return (float) Math.atan2(cIm, cRe);
    }

    /** Largest move from the peak bin a sub-pixel refinement may make before it is rejected. */
    private static final double VORTEX_MAX_SHIFT = 1.5;

    /** Whether the last vortex search was accepted (false: the peak bin was used). */
    private static boolean vortexAccepted;
    public static boolean vortexAccepted() { return vortexAccepted; }

    /**
     * Python {@code vortex_compensation} on the masked spectrum: log power, fftshifted, then
     * the shared vortex search seeded at the strongest bin within ±3 of the detected order.
     *
     * <p>A sub-pixel refinement cannot legitimately land more than about a bin from the peak,
     * so a result further than {@link #VORTEX_MAX_SHIFT} bins is rejected and the peak bin is
     * kept. Python RT accepts anything within max(radius, 8) bins; on the 15 Oct lab
     * recordings the search jumped 4.5 bins, which leaves 4.5 fringes of tilt in Vortex mode.
     */
    private static void refineCarrierWithVortex(float[] re, float[] im, int gRows, int gCols) {
        int halfR = gRows / 2, halfC = gCols / 2;
        double[] logPow = new double[gRows * gCols];
        for (int r = 0; r < gRows; r++) {
            int ur = r + halfR; if (ur >= gRows) ur -= gRows;      // shifted row r ← unshifted ur
            for (int c = 0; c < gCols; c++) {
                int uc = c + halfC; if (uc >= gCols) uc -= gCols;
                int i = ur * gCols + uc;
                double m2 = (double) re[i] * re[i] + (double) im[i] * im[i];
                logPow[r * gCols + c] = 10.0 * Math.log10(m2 + 1e-6);
            }
        }
        // Peak bin: the detector scores 3×3 means against the ring, so it can sit a bin or two
        // off the actual maximum of the order.
        int sx = sidebandCol(), sy = sidebandRow(), bx = sx, by = sy;
        double best = Double.NEGATIVE_INFINITY;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int r = sy + dy, c = sx + dx;
                if (r < 0 || c < 0 || r >= gRows || c >= gCols) continue;
                double v = logPow[r * gCols + c];
                if (v > best) { best = v; bx = c; by = r; }
            }
        }
        double px = bx, py = by;
        vortexAccepted = false;
        try {
            double[] pos = HoloBioVortexLegendre.vortexCompensation(logPow, gRows, gCols, bx, by);
            if (Double.isFinite(pos[0]) && Double.isFinite(pos[1])
                    && Math.abs(pos[0] - bx) <= VORTEX_MAX_SHIFT
                    && Math.abs(pos[1] - by) <= VORTEX_MAX_SHIFT) {
                px = pos[0];
                py = pos[1];
                vortexAccepted = true;
            }
        } catch (RuntimeException ex) {
            // keep the peak bin
        }
        vortexDx = px - halfC;
        vortexDy = py - halfR;
    }

    /** Vortex-refined carrier offset from DC, in spectrum bins (sub-bin), cached per detection. */
    public static double vortexCarrierCol() { return vortexDx; }
    public static double vortexCarrierRow() { return vortexDy; }

    /** Fitted carrier in radians per pixel along X, from the last processed frame. */
    public static double carrierX() { return carrierX; }

    /** Fitted carrier in radians per pixel along Y, from the last processed frame. */
    public static double carrierY() { return carrierY; }

    /** Convenience overload — no physical parameters, auto-detect sideband, log-scaled FT. */
    public static Frame process(float[] holo, int rows, int cols) {
        return process(holo, rows, cols, 0, 0, 0, FILTER_NONE, 0, 0, 0, true, true, true, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FT display
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Magnitude spectrum resampled from the {@code gRows × gCols} grid onto an
     * {@code outRows × outCols} display buffer, normalised to 0…255.
     *
     * <p>Callers pass a square output because the transform runs on a centred square crop:
     * both axes span ±1/(2·pitch) over the same number of bins, so the spectrum is
     * intrinsically square. Stretching it to the frame's aspect ratio would turn the
     * circular sideband lobe — and the circular mask — into ovals. (Python transforms the
     * frame at its native size, where carrying the frame aspect is the correct choice.)
     *
     * <p>Downsampling averages over the source bins that fall in each output pixel. Picking a
     * single bin instead would alias, and a sideband narrower than the sampling step could
     * disappear from the display entirely.
     *
     * <p>The fftshift is folded into the index mapping rather than run as a separate pass.
     */
    private static float[] spectrumDisplay(float[] re, float[] im, int gRows, int gCols,
                                           int outRows, int outCols, boolean logScale,
                                           boolean minMax) {
        int len = outRows * outCols;
        if (ftRows != outRows || ftCols != outCols) {
            ftBuf  = new float[len];
            ftRows = outRows; ftCols = outCols;
        }
        final float[] out = ftBuf;
        final int halfR = gRows / 2, halfC = gCols / 2;
        if (ftRowMin == null || ftRowMin.length < outRows) {
            ftRowMin = new float[outRows];
            ftRowMax = new float[outRows];
        }
        final float[] rMin = ftRowMin, rMax = ftRowMax;

        // Output rows are independent (log1p is StrictMath on Java 8 and dominated the frame
        // when it ran serially); per-row min/max reduce afterwards, so output is unchanged.
        HoloBioParallel.forEachLine(outRows, (from, to) -> {
            for (int dr = from; dr < to; dr++) {
                int sr0 = (int) ((long) dr * gRows / outRows);
                int sr1 = (int) ((long) (dr + 1) * gRows / outRows);
                if (sr1 <= sr0) sr1 = sr0 + 1;
                if (sr1 > gRows) sr1 = gRows;
                int rowBase = dr * outCols;
                float rowHi = 0f, rowLo = Float.MAX_VALUE;

                for (int dc = 0; dc < outCols; dc++) {
                    int sc0 = (int) ((long) dc * gCols / outCols);
                    int sc1 = (int) ((long) (dc + 1) * gCols / outCols);
                    if (sc1 <= sc0) sc1 = sc0 + 1;
                    if (sc1 > gCols) sc1 = gCols;

                    double acc = 0;
                    int n = 0;
                    for (int sr = sr0; sr < sr1; sr++) {
                        int ur = sr + halfR;
                        if (ur >= gRows) ur -= gRows;
                        int base = ur * gCols;
                        for (int sc = sc0; sc < sc1; sc++) {
                            int uc = sc + halfC;
                            if (uc >= gCols) uc -= gCols;
                            int i = base + uc;
                            double a = re[i], b = im[i];
                            acc += Math.sqrt(a * a + b * b);
                            n++;
                        }
                    }
                    float mag = (float) (acc / n);
                    float v = logScale ? (float) Math.log1p(mag) : mag;
                    out[rowBase + dc] = v;
                    if (v > rowHi) rowHi = v;
                    if (v < rowLo) rowLo = v;
                }
                rMin[dr] = rowLo;
                rMax[dr] = rowHi;
            }
        });
        float max = 0f, min = Float.MAX_VALUE;
        for (int dr = 0; dr < outRows; dr++) {
            if (rMax[dr] > max) max = rMax[dr];
            if (rMin[dr] < min) min = rMin[dr];
        }

        float lo = minMax ? min : 0f;
        float span = max - lo;
        if (span <= 1e-12f) {
            Arrays.fill(out, 0f);
            return out;
        }
        float inv = 255f / span;
        for (int i = 0; i < len; i++) {
            float v = (out[i] - lo) * inv;
            out[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sideband search
    // ─────────────────────────────────────────────────────────────────────────

    /** Half-width in bins of the DC block skipped by the sideband search. */
    private static final int DC_GUARD = 20;

    /**
     * Fraction of Nyquist above which the search ignores peaks.
     *
     * <p>A carrier at 0.85 of Nyquist means fringes about 2.4 px apart, which is far too coarse
     * a sampling to reconstruct from — usable off-axis setups sit nearer half of Nyquist, at
     * roughly 4 px per fringe. Anything up at the spectrum corners is therefore not a carrier:
     * it is sensor or codec structure, typically MJPEG block edges or demosaic checkerboard,
     * and it can easily outshine the real order.
     */
    private static final double NYQUIST_GUARD = 0.85;

    /**
     * How far a candidate must stand above the typical magnitude at its own radius before it
     * is accepted as a carrier. The two populations are far apart and the threshold sits in
     * the empty space between them: synthetic off-axis holograms score 50-120x, while a
     * textured but fringe-free camera view tops out near 4x.
     */
    private static final double MIN_PROMINENCE = 12.0;

    /** Whether the last search found something that actually looks like a carrier. */
    private static boolean sbConfident;
    private static double  sbProminence;

    /**
     * Locate the +1 order as the most prominent local excess over the spectrum's own radial
     * falloff, within negative horizontal frequencies.
     *
     * <p>Ranking bins by raw magnitude does not work, because the DC lobe outweighs everything
     * else and decays smoothly outward: the brightest bin outside the DC block is then just a
     * point on that lobe's shoulder, a couple of dozen bins out. The mask that follows is tiny
     * and centred almost on DC, which is a severe low-pass and turns the reconstruction into
     * smooth blobs.
     *
     * <p>Dividing each bin by the mean magnitude at its radius removes that falloff, so a
     * genuine order — a bump that is bright <em>relative to its ring</em> — wins regardless of
     * how the DC lobe compares in absolute terms. Candidates are averaged over 3×3 first so a
     * single hot pixel cannot win on its own.
     *
     * <p>Restricting to one half-plane picks the +1 order specifically rather than whichever
     * of the conjugate pair happened to be marginally brighter; a real hologram has Hermitian
     * symmetry, so the two carry identical information.
     */
    private static void detectSideband(float[] re, float[] im, int gRows, int gCols) {
        int halfR = gRows / 2, halfC = gCols / 2;
        int maxR = (int) (halfR * NYQUIST_GUARD);
        int maxC = (int) (halfC * NYQUIST_GUARD);
        int minDist = Math.max(DC_GUARD, Math.min(gRows, gCols) / 24);

        // Magnitude, plus its mean as a function of distance from DC.
        float[] mag = new float[gRows * gCols];
        int nBuckets = (int) Math.ceil(Math.sqrt((double) halfR * halfR + (double) halfC * halfC)) + 2;
        double[] ringSum = new double[nBuckets];
        int[]    ringCnt = new int[nBuckets];

        for (int r = 0; r < gRows; r++) {
            int sr = r <= halfR ? r : r - gRows;
            int base = r * gCols;
            for (int c = 0; c < gCols; c++) {
                int sc = c <= halfC ? c : c - gCols;
                int i = base + c;
                float m = (float) Math.sqrt((double) re[i] * re[i] + (double) im[i] * im[i]);
                mag[i] = m;
                int b = (int) Math.sqrt((double) sr * sr + (double) sc * sc);
                ringSum[b] += m;
                ringCnt[b]++;
            }
        }
        for (int b = 0; b < nBuckets; b++) {
            ringSum[b] = ringCnt[b] > 0 ? ringSum[b] / ringCnt[b] : 0;
        }

        double best = 0;
        int bestRow = 0, bestCol = gCols - Math.max(minDist, 1);
        boolean found = false;

        for (int r = 0; r < gRows; r++) {
            int sr = r <= halfR ? r : r - gRows;
            if (sr > maxR || sr < -maxR) continue;
            int cStart = Math.max(halfC + 1, gCols - maxC);
            for (int c = cStart; c < gCols; c++) {
                int sc = c - gCols;                       // negative half-plane only
                double d = Math.sqrt((double) sr * sr + (double) sc * sc);
                if (d < minDist) continue;

                // 3x3 mean suppresses isolated hot pixels
                double acc = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    int rr = wrapIndex(r + dr, gRows);
                    int b2 = rr * gCols;
                    for (int dc = -1; dc <= 1; dc++) {
                        acc += mag[b2 + wrapIndex(c + dc, gCols)];
                    }
                }
                double local = acc / 9.0;
                double ring  = ringSum[(int) d] + 1e-12;
                double score = local / ring;
                if (score > best) { best = score; bestRow = r; bestCol = c; found = true; }
            }
        }

        sbRow = bestRow;
        sbCol = bestCol;
        sbProminence = found ? best : 0;
        sbConfident  = found && best >= MIN_PROMINENCE;
    }

    /** Python: {@code radius = max(1, hypot(peak-DC) / factor)} unless a manual pixel radius is set. */
    private static void applyMaskRadius(int gRows, int gCols) {
        if (manualRadiusPx > 0) {
            sbRadius = manualRadiusPx;
            return;
        }
        int halfR = gRows / 2, halfC = gCols / 2;
        int dr = sbRow <= halfR ? sbRow : sbRow - gRows;
        int dc = sbCol <= halfC ? sbCol : sbCol - gCols;
        double dist = Math.sqrt((double) dr * dr + (double) dc * dc);
        double radius = dist > 1e-9
                ? dist / radiusFactor
                : Math.max(10.0, 0.08 * Math.min(gRows, gCols));
        sbRadius = Math.max(1, (int) Math.round(radius));
    }

    /** False when the search found no convincing carrier and the mask is a guess. */
    public static boolean sidebandConfident() { return sbConfident; }

    /** How far the chosen bin stood above the typical magnitude at its radius. */
    public static double sidebandProminence() { return sbProminence; }

    /** Detected carrier row in centred-spectrum coordinates, DC at gridRows/2. */
    public static int sidebandRow() { return wrapIndex(sbRow + gridRows / 2, gridRows); }

    /** Detected carrier column in centred-spectrum coordinates, DC at gridCols/2. */
    public static int sidebandCol() { return wrapIndex(sbCol + gridCols / 2, gridCols); }

    /** Half-width of the rectangular mask, in spectrum bins. */
    public static int sidebandRadius() { return sbRadius; }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Power of two nearest {@code n} on a log scale, so a frame is cropped slightly rather
     * than padded to double the transform. A 640-wide frame maps to 512 (20% narrower) instead
     * of 1024, which halves the FFT work; a 480-tall frame still maps up to 512.
     */
    public static int fitPowerOfTwo(int n) {
        if (n < 2) return 1;
        int next = HoloBioRectFft.nextPowerOfTwo(n);
        if (next == n) return n;
        int prev = next >> 1;
        return ((long) n * n < 2L * prev * prev) ? prev : next;
    }

    /** Processed field height for a frame of {@code rows} rows. */
    public static int outputRows(int rows) { return Math.min(rows, fitPowerOfTwo(rows)); }

    /** Processed field width for a frame of {@code cols} columns. */
    public static int outputCols(int cols) { return Math.min(cols, fitPowerOfTwo(cols)); }

    private static int wrapIndex(int i, int n) {
        int v = i % n;
        return v < 0 ? v + n : v;
    }

    private static void stretch(float[] data, float min, float max) {
        float range = max - min;
        if (range <= 1e-10f) return;
        float scale = 255f / range;
        for (int i = 0; i < data.length; i++) data[i] = (data[i] - min) * scale;
    }
}
