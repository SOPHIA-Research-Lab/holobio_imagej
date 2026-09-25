import java.util.Arrays;

/**
 * Real-time DHM reconstruction — off-axis sideband demodulation.
 *
 * <p>One forward FFT serves both products. Per frame:
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
 *   <li>IFFT, then cancel the off-axis carrier with a fitted ramp, leaving the object field.</li>
 *   <li>amplitude = |field|, phase = angle(field); only the requested one is evaluated.</li>
 * </ol>
 *
 * <p><b>On the carrier fit.</b> Python builds a reference wave from the peak bin as
 * exp(i·k·dx·sinθx·m) with sinθx = (fx0 − fxmax)·λ/(M·dx). The arcsin and sin cancel, as do λ
 * and dx, so that wave is exactly exp(i·2π·Δ·m/M) for a bin offset Δ — purely geometric, with
 * the physical parameters playing no part. Its weakness is that Δ is an integer: the true
 * carrier lies between bins, and a residual of one bin leaves a full 2π of tilt across the
 * field. Amplitude cannot see that, but phase <em>is</em> that tilt, so the phase map comes out
 * as diagonal fringes. Python patches this with {@code vortex_compensation}, interpolating the
 * spectrum 55× around the peak to place the carrier to sub-pixel accuracy.
 *
 * <p>This implementation fits the carrier from the field instead of the spectrum. Summing
 * neighbour·conj(centre) over the field and taking the argument gives the amplitude-weighted
 * circular mean of the per-pixel phase step, which is the carrier in radians per pixel — a
 * continuous estimate with no interpolation and no search, weighted towards bright pixels
 * where the phase is trustworthy. Two multiply-accumulates per pixel replace the 55× resampled
 * grid, and the accuracy is limited by noise rather than by any lattice.
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
    private static int     ftRows, ftCols;

    // ── Output buffers ────────────────────────────────────────────────────────
    private static float[] ampBuf, phaseBuf, phaseRadBuf;
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
    /** Coefficients of the last fitted background surface: x, y, x², y², xy. */
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
            sbRow    = wrapIndex(filterCyPad + gRows / 2, gRows);
            sbCol    = wrapIndex(filterCxPad + gCols / 2, gCols);
            sbRadius = filterRadius;
            sbValid  = true;
        } else {
            if (!sbValid) {
                detectSideband(re, im, gRows, gCols);
                sbValid = true;
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

        HoloBioRectFft.ifft2d(re, im, gRows, gCols);

        // ── Carrier, to sub-pixel precision ───────────────────────────────────
        // Measured off the field itself rather than from the peak bin. See estimateCarrier.
        double gx = 0, gy = 0;
        if (wantPhase || wantAmplitude) {
            double sxRe = 0, sxIm = 0, syRe = 0, syIm = 0;
            for (int r = 0; r < srcRows - 1; r++) {
                int base = (r + pad) * gCols + pad, next = base + gCols;
                for (int c = 0; c < srcCols - 1; c++) {
                    float ar = re[base + c], ai = im[base + c];
                    // neighbour · conj(centre), accumulated as a complex sum
                    float xr = re[base + c + 1], xi = im[base + c + 1];
                    sxRe += (double) xr * ar + (double) xi * ai;
                    sxIm += (double) xi * ar - (double) xr * ai;
                    float yr = re[next + c], yi = im[next + c];
                    syRe += (double) yr * ar + (double) yi * ai;
                    syIm += (double) yi * ar - (double) yr * ai;
                }
            }
            if (sxRe * sxRe + sxIm * sxIm > 1e-30) gx = Math.atan2(sxIm, sxRe);
            if (syRe * syRe + syIm * syIm > 1e-30) gy = Math.atan2(syIm, syRe);
        }
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

        float aMin = Float.MAX_VALUE, aMax = -Float.MAX_VALUE;

        for (int r = 0; r < srcRows; r++) {
            float cosY = rampCosY[r], sinY = rampSinY[r];
            int si = (r + pad) * gCols + pad, di = r * srcCols;
            for (int c = 0; c < srcCols; c++, si++, di++) {
                    float fRe = re[si], fIm = im[si];
                float rCos = rampCosX[c] * cosY - rampSinX[c] * sinY;
                float rSin = rampSinX[c] * cosY + rampCosX[c] * sinY;
                    float oRe = fRe * rCos - fIm * rSin;
                    float oIm = fRe * rSin + fIm * rCos;
                fieldReBuf[di] = oRe;
                fieldImBuf[di] = oIm;
                if (amp != null) {
                    // Math.hypot guards against intermediate overflow at roughly 20× the
                    // cost of sqrt; these magnitudes are nowhere near the float limits.
                    float a = (float) Math.sqrt((double) oRe * oRe + (double) oIm * oIm);
                    amp[di] = a;
                    if (a < aMin) aMin = a;
                    if (a > aMax) aMax = a;
                }
                if (phase != null) {
                    phase[di] = (float) Math.atan2(oIm, oRe);
                }
            }
        }

        if (amp != null) stretch(amp, aMin, aMax);

        if (phase != null) {
            if (flattenPhase) removeSmoothBackground(phase, re, im, srcRows, srcCols, gCols, pad);

            // Centre on the *background* circular mean, not the whole field. USAF bars (and
            // any high-φ object) pull a full-field mean up by ~0.2 rad, so subtracting it
            // parked every live profile that much below Python QPI. Python VL zeros the
            // Legendre constant on the spectral crop (background-dominated); this is the
            // fast equivalent. Still required: without any piston the wrap seam cuts the map.
            float piston = backgroundPiston(phase, srcRows, srcCols);
            float k = (float) (255.0 / (2.0 * Math.PI));
            for (int i = 0, n = srcRows * srcCols; i < n; i++) {
                float p = (float) Math.IEEEremainder(phase[i] - piston, 2 * Math.PI);
                phaseRad[i] = p;
                float v = (p + (float) Math.PI) * k;
                phase[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
            }
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
        for (int i = 0; i < n; i += 2) {
            if (Math.IEEEremainder(phase[i] - p0, 2 * Math.PI) > 0.25) continue;
            cRe += Math.cos(phase[i]);
            cIm += Math.sin(phase[i]);
            count++;
        }
        if (count < 16 || cRe * cRe + cIm * cIm <= 1e-30) return (float) p0;
        return (float) Math.atan2(cIm, cRe);
    }

    /**
     * Subtract residual tilt. Quadratic terms were eating USAF bar contrast and the
     * mid-line secondary peak that Python QPI (Legendre on a spectral crop) keeps.
     * Carrier demodulation already removed the main off-axis ramp; this is only the leftover.
     */
    private static void removeSmoothBackground(float[] phase, float[] re, float[] im,
                                               int rows, int cols, int gCols, int pad) {
        if (rows < 8 || cols < 8) return;

        double sx = 2.0 / (cols - 1), sy = 2.0 / (rows - 1);
        double m00 = 0, m01 = 0, m11 = 0, r0 = 0, r1 = 0;

        double maxMag2 = 0;
        for (int r = 0; r < rows; r++) {
            int si = (r + pad) * gCols + pad;
            for (int c = 0; c < cols; c++) {
                float fr = re[si + c], fi = im[si + c];
                double m2 = (double) fr * fr + (double) fi * fi;
                if (m2 > maxMag2) maxMag2 = m2;
            }
        }
        double minMag2 = 0.01 * maxMag2;
        if (maxMag2 <= 0) return;

        for (int r = 0; r < rows - 1; r++) {
            int si = (r + pad) * gCols + pad, di = r * cols;
            for (int c = 0; c < cols - 1; c++) {
                int i = si + c, d = di + c;
                float fr = re[i], fi = im[i];
                if ((double) fr * fr + (double) fi * fi < minMag2) continue;
                double gxp = wrapPi(phase[d + 1] - phase[d]) / sx;
                double gyp = wrapPi(phase[d + cols] - phase[d]) / sy;
                // ∇(a x + b y) = (a, b) in normalised coords
                m00 += 1;
                m01 += 0;
                m11 += 1;
                r0 += gxp;
                r1 += gyp;
            }
        }
        int n = (int) m00;
        if (n < 16) return;
        double a = r0 / m00;
        double b = r1 / m11;
        lastFit = new double[] {a, b};

        for (int r = 0; r < rows; r++) {
            double y = r * sy - 1.0;
            int d = r * cols;
            for (int c = 0; c < cols; c++) {
                double x = c * sx - 1.0;
                phase[d + c] -= (float) (a * x + b * y);
            }
        }
    }

    private static double wrapPi(double v) {
        if (v >  Math.PI) return v - 2 * Math.PI;
        if (v < -Math.PI) return v + 2 * Math.PI;
        return v;
    }

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
        float[] out = ftBuf;
        int halfR = gRows / 2, halfC = gCols / 2;
        float max = 0f, min = Float.MAX_VALUE;

        for (int dr = 0; dr < outRows; dr++) {
            int sr0 = (int) ((long) dr * gRows / outRows);
            int sr1 = (int) ((long) (dr + 1) * gRows / outRows);
            if (sr1 <= sr0) sr1 = sr0 + 1;
            if (sr1 > gRows) sr1 = gRows;
            int rowBase = dr * outCols;

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
                if (v > max) max = v;
                if (v < min) min = v;
            }
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
