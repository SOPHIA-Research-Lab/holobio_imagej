import java.util.Arrays;

/**
 * DHM phase-compensation algorithms aligned with HoloBio Python {@code pyDHM_methods} Used by {@link HoloBio_DHM_Plugin} phase compensation.
 */
public final class HoloBioCompensationAlgorithms {

    private HoloBioCompensationAlgorithms() {}

    public static final class ComplexField {
        public final int width;
        public final int height;
        public final double[] re;
        public final double[] im;

        public ComplexField(int width, int height) {
            this.width = width;
            this.height = height;
            this.re = new double[width * height];
            this.im = new double[width * height];
        }

        public ComplexField copy() {
            ComplexField out = new ComplexField(width, height);
            System.arraycopy(re, 0, out.re, 0, re.length);
            System.arraycopy(im, 0, out.im, 0, im.length);
            return out;
        }
    }

    public static final class RoiRect {
        public final int x1;
        public final int y1;
        public final int x2;
        public final int y2;

        public RoiRect(int x1, int y1, int x2, int y2) {
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
        }
    }

    public static final class CompensationResult {
        public final ComplexField field;
        public final double fx;
        public final double fy;

        public CompensationResult(ComplexField field, double fx, double fy) {
            this.field = field;
            this.fx = fx;
            this.fy = fy;
        }
    }

    static final class FilteredOrder {
        public final ComplexField field;
        public final double fx;
        public final double fy;

        private FilteredOrder(ComplexField field, double fx, double fy) {
            this.field = field;
            this.fx = fx;
            this.fy = fy;
        }
    }

    /**
     * Automatic +1 order ROI in <strong>fftshifted</strong> spectrum coordinates, matching
     * {@code pyDHM_methods.spatialFilteringCF} defaults (DC cleared, peak in upper half-plane, radius ≈ d/3).
     */
    public static RoiRect autoFirstOrderSpectrumRoi(double[] inp, int width, int height) {
        double[] mag = fftMagnitudeShifted(inp, width, height);
        int cx = width / 2;
        int cy = height / 2;
        int dcHalf = Math.min(20, Math.min(width, height) / 8);
        for (int y = Math.max(0, cy - dcHalf); y <= Math.min(height - 1, cy + dcHalf); y++) {
            for (int x = Math.max(0, cx - dcHalf); x <= Math.min(width - 1, cx + dcHalf); x++) {
                mag[y * width + x] = -1.0;
            }
        }
        double best = -1.0;
        int bx = cx;
        int by = 0;
        int topHalfEnd = height / 2;
        for (int y = 0; y < topHalfEnd; y++) {
            for (int x = 0; x < width; x++) {
                double v = mag[y * width + x];
                if (v > best) {
                    best = v;
                    bx = x;
                    by = y;
                }
            }
        }
        if (best < 0) {
            return new RoiRect(cx - 5, cy - 5, cx + 6, cy + 6);
        }
        double dist = Math.hypot(by - height / 2.0, bx - width / 2.0);
        int rad = Math.max(1, (int) Math.round(dist / 3.0));
        int x1 = clampInt(bx - rad, 0, width - 1);
        int y1 = clampInt(by - rad, 0, height - 1);
        int x2 = Math.min(width, bx + rad + 1);
        int y2 = Math.min(height, by + rad + 1);
        if (x2 <= x1) {
            x2 = Math.min(width, x1 + 1);
        }
        if (y2 <= y1) {
            y2 = Math.min(height, y1 + 1);
        }
        return new RoiRect(x1, y1, x2, y2);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

//ERS
    public static CompensationResult ers(double[] inp, int width, int height,
                                         double wavelengthUm, double dxUm, double dyUm,
                                         RoiRect roi, int s, double step) {
        double[] pre = meanRemovedCopy(inp);
        FilteredOrder fo = filterAndExtractOrderCircular(pre, width, height, roi, true);
        return searchReferenceByBinaryMetric(fo.field, wavelengthUm, dxUm, dyUm, fo.fx, fo.fy, s, step);
    }

//CFS
    public static CompensationResult cfs(double[] inp, int width, int height,
                                         double wavelengthUm, double dxUm, double dyUm,
                                         RoiRect roi, double stepWindow, int gridSize) {
        double[] pre = meanRemovedCopy(inp);
        FilteredOrder fo = filterAndExtractOrderCircular(pre, width, height, roi, true);
        ComplexField holoFilter = fo.field;
        double bestFy = fo.fy;
        double bestFx = fo.fx;
        double bestCost = Double.POSITIVE_INFINITY;

        double minFy = bestFy - stepWindow;
        double maxFy = bestFy + stepWindow;
        double minFx = bestFx - stepWindow;
        double maxFx = bestFx + stepWindow;
        int g = Math.max(5, gridSize);

        for (int yi = 0; yi < g; yi++) {
            for (int xi = 0; xi < g; xi++) {
                double fy = minFy + (maxFy - minFy) * yi / (g - 1.0);
                double fx = minFx + (maxFx - minFx) * xi / (g - 1.0);
                // Match Python CFS costFunction: average pitch dxy in angles and ref phase during optimization.
                ComplexField candidate = applyReferenceWaveCfsCost(holoFilter, wavelengthUm, dxUm, dyUm, fx, fy);
                double[] phase = angle(candidate);
                double cost = cfsCost(phase);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestFx = fx;
                    bestFy = fy;
                }
            }
        }

        // Local multi-pass refinement around the best grid point (bridges part of the gap to Python continuous search).
        double[] refined = refineCfsLocally(holoFilter, wavelengthUm, dxUm, dyUm, bestFx, bestFy, stepWindow);
        bestFx = refined[0];
        bestFy = refined[1];

        ComplexField out = applyReferenceWave(holoFilter, wavelengthUm, dxUm, dyUm, bestFx, bestFy);
        return new CompensationResult(out, bestFx, bestFy);
    }

    public static CompensationResult cnt(double[] inp, int width, int height,
                                         double wavelengthUm, double dxUm, double dyUm,
                                         RoiRect roi) {
        return cnt(inp, width, height, wavelengthUm, dxUm, dyUm, roi, 5, 0.2);
    }

    /**
     * Backward-compatible overload. ERS arguments are ignored by the parity-oriented CNT flow.
     */
    public static CompensationResult cnt(double[] inp, int width, int height,
                                         double wavelengthUm, double dxUm, double dyUm,
                                         RoiRect roi, int ersSearchSize, double ersStep) {
        // ersSearchSize/ersStep kept for API compatibility; Python CNT path does not use ERS.
        double[] pre = meanRemovedCopy(inp);
        ComplexField holoFilter = filterAndExtractOrderRect(pre, width, height, roi);
        double fxPeak = 0.5 * (roi.x1 + roi.x2);
        double fyPeak = 0.5 * (roi.y1 + roi.y2);
        ComplexField comp = applyReferenceWave(holoFilter, wavelengthUm, dxUm, dyUm, fxPeak, fyPeak);
        double[] ph = angle(comp);
        double[] norm = normalize(ph);

        // Approximate ROI-based curvature initialization, then coarse search.
        int m = Math.max(1, roi.y2 - roi.y1);
        int n = Math.max(1, roi.x2 - roi.x1);
        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double cx = Math.pow(height * dx, 2.0) / (wavelength * m);
        double cy = Math.pow(width * dy, 2.0) / (wavelength * n);
        double curv = 0.5 * (cx + cy);

        double bestCurv = curv;
        double bestOx = width / 2.0;
        double bestOy = height / 2.0;
        double bestScore = -1.0;

        // Coarse stage: wider center offsets and curvature neighborhood.
        for (int ci = 0; ci < 8; ci++) {
            double c = curv * (0.6 + 0.1 * ci);
            for (int oy = -100; oy <= 100; oy += 50) {
                for (int ox = -100; ox <= 100; ox += 50) {
                    double score = cntScore(norm, width, height, c, width / 2.0 + ox, height / 2.0 + oy, wavelength, dx, dy);
                    if (score > bestScore) {
                        bestScore = score;
                        bestCurv = c;
                        bestOx = width / 2.0 + ox;
                        bestOy = height / 2.0 + oy;
                    }
                }
            }
        }

        // Fine stage around coarse optimum.
        double cLo = Math.max(1e-9, bestCurv * 0.9);
        double cHi = Math.max(cLo + 1e-9, bestCurv * 1.1);
        double cStep = 0.01;
        int maxCurvSteps = 800;
        double rawSteps = (cHi - cLo) / cStep;
        if (rawSteps > maxCurvSteps) {
            cStep = (cHi - cLo) / maxCurvSteps;
        }
        for (double c = cLo; c <= cHi + 1e-12; c += cStep) {
            for (int oy = (int) Math.round(bestOy - 10); oy <= (int) Math.round(bestOy + 10); oy += 2) {
                for (int ox = (int) Math.round(bestOx - 10); ox <= (int) Math.round(bestOx + 10); ox += 2) {
                    double score = cntScore(norm, width, height, c, ox, oy, wavelength, dx, dy);
                    if (score > bestScore) {
                        bestScore = score;
                        bestCurv = c;
                        bestOx = ox;
                        bestOy = oy;
                    }
                }
            }
        }

        applySphericalCorrection(comp, width, height, bestCurv, bestOx, bestOy, wavelength, dx, dy);
        return new CompensationResult(comp, fxPeak, fyPeak);
    }

    public static CompensationResult vortexLegendre(double[] inp, int width, int height,
                                                    double wavelengthUm, double dxUm, double dyUm,
                                                    RoiRect roi, int legendreLimit) {
        return vortexLegendre(inp, width, height, wavelengthUm, dxUm, dyUm, roi, legendreLimit, 5, 0.2, false, false);
    }

    /**
     * Vortex–Legendre compensation ({@code pyDHM_methods.vortexLegendre}).
     * {@code legendreLimit} is the Python {@code limit} (spectral crop half-size), not a polynomial order.
     */
    public static CompensationResult vortexLegendre(double[] inp, int width, int height,
                                                    double wavelengthUm, double dxUm, double dyUm,
                                                    RoiRect roi, int legendreLimit, int ersSearchSize, double ersStep,
                                                    boolean piston, boolean usePca) {
        return HoloBioVortexLegendre.run(inp, width, height, wavelengthUm, dxUm, dyUm, legendreLimit, piston, usePca);
    }

    private static double mean(double[] a) {
        double s = 0.0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }

    /**
     * ERS reference search matching {@code pyDHM_methods.ERS}: iterative window with
     * {@code linspace((fx ± step*G)*10, ...)} / 10 subpixel grid, binary metric on phase normalized by
     * global min/max per candidate, threshold {@code > 0.2}, stop when (fx,fy) stabilizes.
     */
    private static CompensationResult searchReferenceByBinaryMetric(ComplexField holoFilter, double wavelengthUm, double dxUm, double dyUm,
                                                                    double fxSeed, double fySeed, int s, double step) {
        double fx = fxSeed;
        double fy = fySeed;
        int gTemp = s;
        double xMaxOut = fx;
        double yMaxOut = fy;

        int guard = 0;
        final int maxIter = 256;
        while (guard++ < maxIter) {
            int nLin = (int) Math.floor(10.0 * step);
            if (nLin < 2) {
                nLin = 2;
            }
            double[] arrayX = linspaceDouble((fx - step * gTemp) * 10.0, (fx + step * gTemp) * 10.0, nLin);
            double[] arrayY = linspaceDouble((fy - step * gTemp) * 10.0, (fy + step * gTemp) * 10.0, nLin);

            double sumMax = -1.0;
            double bestFxLocal = xMaxOut;
            double bestFyLocal = yMaxOut;

            for (double fxTemp : arrayX) {
                double fxTmp = fxTemp / 10.0;
                for (double fyTemp : arrayY) {
                    double fyTmp = fyTemp / 10.0;
                    ComplexField cand = applyReferenceWave(holoFilter, wavelengthUm, dxUm, dyUm, fxTmp, fyTmp);
                    double[] ph = angle(cand);
                    double minVal = arrayMin(ph);
                    double maxVal = arrayMax(ph);
                    if (Math.abs(maxVal - minVal) < 1e-9) {
                        continue;
                    }
                    double inv = 1.0 / (maxVal - minVal);
                    double summ = 0.0;
                    for (double p : ph) {
                        double phaseSca = (p - minVal) * inv;
                        if (phaseSca > 0.2) {
                            summ += 1.0;
                        }
                    }
                    if (summ > sumMax) {
                        sumMax = summ;
                        bestFxLocal = fxTmp;
                        bestFyLocal = fyTmp;
                    }
                }
            }

            xMaxOut = bestFxLocal;
            yMaxOut = bestFyLocal;

            if (Math.abs(xMaxOut - fx) < 1e-5 && Math.abs(yMaxOut - fy) < 1e-5) {
                break;
            }
            fx = xMaxOut;
            fy = yMaxOut;
            gTemp -= 1;
        }

        return new CompensationResult(
                applyReferenceWave(holoFilter, wavelengthUm, dxUm, dyUm, xMaxOut, yMaxOut),
                xMaxOut, yMaxOut);
    }

    private static double[] linspaceDouble(double a, double b, int n) {
        if (n < 2) {
            n = 2;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = a + (b - a) * i / (n - 1.0);
        }
        return out;
    }

    private static double arrayMin(double[] a) {
        double m = a[0];
        for (int i = 1; i < a.length; i++) {
            if (a[i] < m) {
                m = a[i];
            }
        }
        return m;
    }

    private static double arrayMax(double[] a) {
        double m = a[0];
        for (int i = 1; i < a.length; i++) {
            if (a[i] > m) {
                m = a[i];
            }
        }
        return m;
    }

    private static double[] orderPeakFrequency(double[] inp, int width, int height, RoiRect roi) {
        double[] spec = fftMagnitudeShifted(inp, width, height);
        int bestX = width / 2;
        int bestY = height / 2;
        double best = -1.0;
        for (int y = roi.y1; y < roi.y2; y++) {
            for (int x = roi.x1; x < roi.x2; x++) {
                double v = spec[y * width + x];
                if (v > best) {
                    best = v;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new double[] {bestX, bestY};
    }

    /**
     * ROI indices are in <strong>fftshifted</strong> spectrum space (same as the log-FFT display and Python
     * {@code spatialFilteringCF} after {@code np.fft.fftshift}).
     */
    /**
     * {@code pyDHM_methods.spatialFilteringCF} (Circular mask, +1 peak in upper half).
     */
    static FilteredOrder spatialFilteringCf(double[] inp, int width, int height) {
        RoiRect upperHalf = new RoiRect(0, 0, width, height / 2);
        return filterAndExtractOrderCircular(inp, width, height, upperHalf, true);
    }

    private static ComplexField filterAndExtractOrder(double[] inp, int width, int height, RoiRect roi) {
        ComplexField spectrum = fft2(inp, width, height);
        HoloBioRectFft.fftShift2d(spectrum.re, spectrum.im, height, width);
        ComplexField filtered = new ComplexField(width, height);
        for (int y = roi.y1; y < roi.y2; y++) {
            for (int x = roi.x1; x < roi.x2; x++) {
                int i = y * width + x;
                filtered.re[i] = spectrum.re[i];
                filtered.im[i] = spectrum.im[i];
            }
        }
        HoloBioRectFft.ifftShift2d(filtered.re, filtered.im, height, width);
        return ifft2(filtered);
    }

    private static ComplexField filterAndExtractOrderRect(double[] inp, int width, int height, RoiRect roi) {
        return filterAndExtractOrder(inp, width, height, roi);
    }

    private static FilteredOrder filterAndExtractOrderCircular(double[] inp, int width, int height, RoiRect roi, boolean suppressDc) {
        ComplexField spectrum = fft2(inp, width, height);
        HoloBioRectFft.fftShift2d(spectrum.re, spectrum.im, height, width);
        if (suppressDc) {
            zeroDcNeighborhoodInShiftedSpectrum(spectrum, width, height, 20);
        }

        double[] peak = orderPeakFrequencyFromShiftedSpectrum(spectrum, width, height, roi);
        double fx = peak[0];
        double fy = peak[1];
        double cx = width / 2.0;
        double cy = height / 2.0;
        double radius = Math.max(1.0, Math.hypot(fy - cy, fx - cx) / 3.0);
        double r2 = radius * radius;

        ComplexField filtered = new ComplexField(width, height);
        for (int y = 0; y < height; y++) {
            double dy = y - fy;
            for (int x = 0; x < width; x++) {
                double dx = x - fx;
                if (dx * dx + dy * dy <= r2) {
                    int i = y * width + x;
                    filtered.re[i] = spectrum.re[i];
                    filtered.im[i] = spectrum.im[i];
                }
            }
        }
        HoloBioRectFft.ifftShift2d(filtered.re, filtered.im, height, width);
        return new FilteredOrder(ifft2(filtered), fx, fy);
    }

    private static void zeroDcNeighborhoodInShiftedSpectrum(ComplexField spectrum, int width, int height, int halfWindow) {
        int cx = width / 2;
        int cy = height / 2;
        // Python: ft[cy-20:cy+20, cx-20:cx+20] (exclusive upper bound → 40×40)
        int x1 = Math.max(0, cx - halfWindow);
        int x2 = Math.min(width, cx + halfWindow);
        int y1 = Math.max(0, cy - halfWindow);
        int y2 = Math.min(height, cy + halfWindow);
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int i = y * width + x;
                spectrum.re[i] = 0.0;
                spectrum.im[i] = 0.0;
            }
        }
    }

    private static double[] orderPeakFrequencyFromShiftedSpectrum(ComplexField shiftedSpectrum, int width, int height, RoiRect roi) {
        int bestX = width / 2;
        int bestY = height / 2;
        double best = -1.0;
        for (int y = roi.y1; y < roi.y2; y++) {
            for (int x = roi.x1; x < roi.x2; x++) {
                int i = y * width + x;
                double v = Math.hypot(shiftedSpectrum.re[i], shiftedSpectrum.im[i]);
                if (v > best) {
                    best = v;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new double[] {bestX, bestY};
    }

    private static double[] meanRemovedCopy(double[] inp) {
        double mean = 0.0;
        for (double v : inp) {
            mean += v;
        }
        mean /= Math.max(1, inp.length);
        double[] out = new double[inp.length];
        for (int i = 0; i < inp.length; i++) {
            out[i] = inp[i] - mean;
        }
        return out;
    }

    private static double[] refineCfsLocally(ComplexField holoFilter, double wavelengthUm, double dxUm, double dyUm,
                                             double bestFx, double bestFy, double stepWindow) {
        double fx = bestFx;
        double fy = bestFy;
        double window = Math.max(1e-6, stepWindow * 0.5);
        for (int level = 0; level < 3; level++) {
            double bestCost = Double.POSITIVE_INFINITY;
            double localFx = fx;
            double localFy = fy;
            for (int yi = 0; yi < 5; yi++) {
                for (int xi = 0; xi < 5; xi++) {
                    double tfy = (fy - window) + yi * (2.0 * window) / 4.0;
                    double tfx = (fx - window) + xi * (2.0 * window) / 4.0;
                    ComplexField cand = applyReferenceWaveCfsCost(holoFilter, wavelengthUm, dxUm, dyUm, tfx, tfy);
                    double cost = cfsCost(angle(cand));
                    if (cost < bestCost) {
                        bestCost = cost;
                        localFx = tfx;
                        localFy = tfy;
                    }
                }
            }
            fx = localFx;
            fy = localFy;
            window *= 0.4;
        }
        return new double[] {fx, fy};
    }

    static void fftShift2dInPlace(ComplexField f) {
        int w = f.width;
        int h = f.height;
        int h2 = h / 2;
        for (int y = 0; y < h2; y++) {
            for (int x = 0; x < w; x++) {
                int i1 = y * w + x;
                int i2 = (y + h2) * w + x;
                swapComplex(f, i1, i2);
            }
        }
        int w2 = w / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w2; x++) {
                int i1 = y * w + x;
                int i2 = y * w + (x + w2);
                swapComplex(f, i1, i2);
            }
        }
    }

    private static void swapComplex(ComplexField f, int i, int j) {
        double tr = f.re[i];
        f.re[i] = f.re[j];
        f.re[j] = tr;
        double ti = f.im[i];
        f.im[i] = f.im[j];
        f.im[j] = ti;
    }

    private static ComplexField applyReferenceWave(ComplexField field, double wavelengthUm, double dxUm, double dyUm, double fx, double fy) {
        int w = field.width;
        int h = field.height;
        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double fx0 = w / 2.0;
        double fy0 = h / 2.0;
        double k = 2.0 * Math.PI / wavelength;
        double thetaX = Math.asin(clampAsin((fx0 - fx) * wavelength / (w * dx)));
        double thetaY = Math.asin(clampAsin((fy0 - fy) * wavelength / (h * dy)));
        ComplexField out = new ComplexField(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double X = x - w / 2.0;
                double Y = y - h / 2.0;
                double ph = k * (Math.sin(thetaX) * X * dx + Math.sin(thetaY) * Y * dy);
                double c = Math.cos(ph);
                double s = Math.sin(ph);
                out.re[i] = field.re[i] * c - field.im[i] * s;
                out.im[i] = field.re[i] * s + field.im[i] * c;
            }
        }
        return out;
    }

    /**
     * CFS grid cost only: matches Python {@code costFunction} using {@code dxy = 0.5*(dx+dy)} for both axes
     * in {@code asin} arguments and in the reference phase factor.
     */
    private static ComplexField applyReferenceWaveCfsCost(ComplexField field, double wavelengthUm, double dxUm, double dyUm, double fx, double fy) {
        int w = field.width;
        int h = field.height;
        double wavelength = wavelengthUm * 1e-6;
        double dxy = 0.5 * (dxUm + dyUm) * 1e-6;
        double fx0 = w / 2.0;
        double fy0 = h / 2.0;
        double k = 2.0 * Math.PI / wavelength;
        double thetaX = Math.asin(clampAsin((fx0 - fx) * wavelength / (w * dxy)));
        double thetaY = Math.asin(clampAsin((fy0 - fy) * wavelength / (h * dxy)));
        ComplexField out = new ComplexField(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double X = x - w / 2.0;
                double Y = y - h / 2.0;
                double ph = k * (Math.sin(thetaX) * X * dxy + Math.sin(thetaY) * Y * dxy);
                double c = Math.cos(ph);
                double s = Math.sin(ph);
                out.re[i] = field.re[i] * c - field.im[i] * s;
                out.im[i] = field.re[i] * s + field.im[i] * c;
            }
        }
        return out;
    }

    private static double clampAsin(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double cfsCost(double[] phase) {
        int count = 0;
        double mean = 0.0;
        double m2 = 0.0;
        for (double v : phase) {
            count++;
            double delta = v - mean;
            mean += delta / count;
            m2 += delta * (v - mean);
        }
        double std = count > 1 ? Math.sqrt(m2 / (count - 1)) : 0.0;
        double bin = binaryContentScore(phase, 0.2);
        return (phase.length - bin) + std;
    }

    private static double binaryContentScore(double[] phase, double threshold) {
        double[] n = normalize(phase);
        double s = 0.0;
        for (double v : n) {
            if (v > threshold) s += 1.0;
        }
        return s;
    }

    private static double[] normalize(double[] arr) {
        double min = Arrays.stream(arr).min().orElse(0.0);
        double max = Arrays.stream(arr).max().orElse(1.0);
        double d = Math.max(1e-12, max - min);
        double[] out = new double[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = (arr[i] - min) / d;
        return out;
    }

    private static double[] angle(ComplexField f) {
        double[] out = new double[f.re.length];
        for (int i = 0; i < out.length; i++) out[i] = Math.atan2(f.im[i], f.re[i]);
        return out;
    }

    private static double cntScore(double[] phaseNorm, int width, int height, double curv, double ox, double oy,
                                   double wavelength, double dx, double dy) {
        double score = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                double X = x - ox;
                double Y = y - oy;
                double sph = (Math.PI / wavelength) * ((X * X * dx * dx) / curv + (Y * Y * dy * dy) / curv);
                double corrected = phaseNorm[i] - sph;
                if (corrected > 0.2) score += 1.0;
            }
        }
        return score;
    }

    private static void applySphericalCorrection(ComplexField field, int width, int height, double curv, double ox, double oy,
                                                 double wavelength, double dx, double dy) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                double X = x - ox;
                double Y = y - oy;
                double sph = -((Math.PI / wavelength) * ((X * X * dx * dx) / curv + (Y * Y * dy * dy) / curv));
                double c = Math.cos(sph);
                double s = Math.sin(sph);
                double r = field.re[i];
                double im = field.im[i];
                field.re[i] = r * c - im * s;
                field.im[i] = r * s + im * c;
            }
        }
    }

    private static double[] unwrapSimple2D(double[] phase, int width, int height) {
        double[] out = phase.clone();
        for (int y = 0; y < height; y++) {
            for (int x = 1; x < width; x++) {
                int i = y * width + x;
                int p = y * width + (x - 1);
                out[i] = out[p] + wrapToPi(out[i] - out[p]);
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 1; y < height; y++) {
                int i = y * width + x;
                int p = (y - 1) * width + x;
                out[i] = out[p] + wrapToPi(out[i] - out[p]);
            }
        }
        return out;
    }

    private static double[] fitLegendreLikeSurface(double[] phase, int width, int height, int order) {
        // Phase-1 parity upgrade: Legendre-basis projection inspired by Python legendre_compensation.
        // "order" is interpreted as number of basis terms to keep (clamped to available square terms).
        int terms = Math.max(2, Math.min(10, order));
        int len = width * height;
        double[] out = new double[len];

        // Normalized grid in [-1, 1) with the same spacing style used by Python.
        double[] xNorm = new double[width];
        double[] yNorm = new double[height];
        for (int x = 0; x < width; x++) {
            xNorm[x] = -1.0 + (2.0 * x) / width;
        }
        for (int y = 0; y < height; y++) {
            yNorm[y] = -1.0 + (2.0 * y) / height;
        }

        double dA = (2.0 / width) * (2.0 / height);
        double[][] basis = new double[terms][len];
        double[] normConst = new double[terms];

        for (int t = 0; t < terms; t++) {
            int termIndex = t + 1; // Python square_legendre_fitting terms are 1-based.
            double energy = 0.0;
            for (int y = 0; y < height; y++) {
                double yn = yNorm[y];
                for (int x = 0; x < width; x++) {
                    double xn = xNorm[x];
                    int i = y * width + x;
                    double v = squareLegendreTerm(termIndex, xn, yn);
                    basis[t][i] = v;
                    energy += v * v;
                }
            }
            double zProd = Math.max(1e-12, energy * dA);
            double invNorm = 1.0 / Math.sqrt(zProd);
            for (int i = 0; i < len; i++) {
                basis[t][i] *= invNorm;
            }
            double nrm = 0.0;
            for (int i = 0; i < len; i++) {
                nrm += basis[t][i] * basis[t][i];
            }
            normConst[t] = Math.max(1e-12, nrm * dA);
        }

        // Coefficients by projection.
        double[] coeff = new double[terms];
        for (int t = 0; t < terms; t++) {
            double c = 0.0;
            for (int i = 0; i < len; i++) {
                c += basis[t][i] * phase[i];
            }
            coeff[t] = c * dA;
        }

        // Keep piston by default in this trend estimate; caller handles optional explicit piston behavior.
        for (int t = 0; t < terms; t++) {
            double cNorm = coeff[t] / Math.sqrt(normConst[t]);
            for (int i = 0; i < len; i++) {
                out[i] += cNorm * basis[t][i];
            }
        }
        return out;
    }

    private static double squareLegendreTerm(int termIndex, double x, double y) {
        switch (termIndex) {
            case 1:  return 1.0;
            case 2:  return x;
            case 3:  return y;
            case 4:  return 0.5 * (3.0 * x * x - 1.0);
            case 5:  return x * y;
            case 6:  return 0.5 * (3.0 * y * y - 1.0);
            case 7:  return 0.5 * x * (5.0 * x * x - 3.0);
            case 8:  return 0.5 * y * (3.0 * x * x - 1.0);
            case 9:  return 0.5 * x * (3.0 * y * y - 1.0);
            case 10: return 0.5 * y * (5.0 * y * y - 3.0);
            default: return 0.0;
        }
    }

    private static double wrapToPi(double a) {
        double t = (a + Math.PI) % (2.0 * Math.PI);
        if (t < 0.0) {
            t += 2.0 * Math.PI;
        }
        return t - Math.PI;
    }

    private static double[] fftMagnitudeShifted(double[] inp, int width, int height) {
        ComplexField spec = fft2(inp, width, height);
        fftShift2dInPlace(spec);
        double[] out = new double[width * height];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.hypot(spec.re[i], spec.im[i]);
        }
        return out;
    }

    static ComplexField fft2(double[] real, int width, int height) {
        ComplexField out = new ComplexField(width, height);
        System.arraycopy(real, 0, out.re, 0, out.re.length);
        fftRowsCols(out.re, out.im, width, height, false);
        return out;
    }

    /** Forward FFT of a complex spatial field (numpy {@code fft2} on complex). */
    static ComplexField fft2Complex(ComplexField field) {
        ComplexField out = field.copy();
        fftRowsCols(out.re, out.im, field.width, field.height, false);
        return out;
    }

    /** {@code ifft2(ifftshift(spectrum))} for an fftshifted spectrum crop. */
    static ComplexField ifft2FromShiftedSpectrum(ComplexField fftShiftedSpectrum) {
        ComplexField out = fftShiftedSpectrum.copy();
        HoloBioRectFft.ifftShift2d(out.re, out.im, out.height, out.width);
        fftRowsCols(out.re, out.im, out.width, out.height, true);
        return out;
    }

    static ComplexField ifft2(ComplexField in) {
        ComplexField out = in.copy();
        fftRowsCols(out.re, out.im, in.width, in.height, true);
        return out;
    }

    private static void fftRowsCols(double[] re, double[] im, int width, int height, boolean inverse) {
        double[] rowRe = new double[width];
        double[] rowIm = new double[width];
        for (int y = 0; y < height; y++) {
            int o = y * width;
            System.arraycopy(re, o, rowRe, 0, width);
            System.arraycopy(im, o, rowIm, 0, width);
            fft1d(rowRe, rowIm, inverse);
            System.arraycopy(rowRe, 0, re, o, width);
            System.arraycopy(rowIm, 0, im, o, width);
        }

        double[] colRe = new double[height];
        double[] colIm = new double[height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                colRe[y] = re[i];
                colIm[y] = im[i];
            }
            fft1d(colRe, colIm, inverse);
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                re[i] = colRe[y];
                im[i] = colIm[y];
            }
        }
    }

    private static void fft1d(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if ((n & (n - 1)) != 0) throw new IllegalArgumentException("FFT length must be power of two.");
        bitReverse(re, im);
        for (int len = 2; len <= n; len <<= 1) {
            double ang = (inverse ? 2.0 : -2.0) * Math.PI / len;
            double wLenRe = Math.cos(ang);
            double wLenIm = Math.sin(ang);
            int half = len >>> 1;
            for (int i = 0; i < n; i += len) {
                double wRe = 1.0;
                double wIm = 0.0;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    double tRe = wRe * re[v] - wIm * im[v];
                    double tIm = wRe * im[v] + wIm * re[v];
                    re[v] = re[u] - tRe;
                    im[v] = im[u] - tIm;
                    re[u] += tRe;
                    im[u] += tIm;
                    double nwRe = wRe * wLenRe - wIm * wLenIm;
                    double nwIm = wRe * wLenIm + wIm * wLenRe;
                    wRe = nwRe;
                    wIm = nwIm;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    private static void bitReverse(double[] re, double[] im) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >>> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
    }
}
