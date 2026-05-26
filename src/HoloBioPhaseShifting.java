import de.xypron.jcobyla.Cobyla;
import ij.IJ;
import ij.process.FloatProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Blind phase-shifting reconstruction (BPS2 / BPS3) aligned with HoloBio Python {@code phaseShifting.py}.
 * <p>
 * Frames are center-cropped to a square whose side is the largest power of two (see {@link #toPow2Square}).
 * COBYLA uses eight random starts and keeps the lowest-cost solution ({@link #minimizeMultiStart}).
 * Spatial filtering and peak search follow the same half-plane convention as Python / ImageJ parity notes.
 * FFT stages use {@code double[]} ({@link HoloBioRectFft}); {@link Result} is raw complex (no extra scaling).
 * Phase display uses {@code Math.atan2(im, re)} in {@link HoloBio_DHM_Plugin#phaseToDisplay}.
 */
public final class HoloBioPhaseShifting {

    private static final int COBYLA_STARTS = 8;
    private static final int COBYLA_MAX_ITER_BPS2 = 2500;
    private static final int COBYLA_MAX_ITER_BPS3 = 4000;
    /** Same as Python {@code _COBYLA_RNG = RandomState(42)} for comparable multi-start angles. */
    private static final Random COBYLA_RNG = new Random(42);

    private HoloBioPhaseShifting() {
    }

    public static final class Result {
        public final float[] re;
        public final float[] im;
        public final int width;
        public final int height;

        public Result(float[] re, float[] im, int width, int height) {
            this.re = re;
            this.im = im;
            this.width = width;
            this.height = height;
        }
    }

    public static Result run(String method, List<FloatProcessor> frames,
                             double wavelengthUm, double dxUm, double dyUm) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int N = frames.get(0).getWidth();
        int M = frames.get(0).getHeight();
        for (FloatProcessor fp : frames) {
            if (fp.getWidth() != N || fp.getHeight() != M) {
                IJ.showMessage("HoloBio", "All phase-shift frames must have the same size.");
                return null;
            }
        }

        List<FloatProcessor> cropped;
        try {
            cropped = cropFramesToPow2Square(frames);
        } catch (IllegalArgumentException ex) {
            IJ.showMessage("HoloBio", ex.getMessage());
            return null;
        }
        int cropW = cropped.get(0).getWidth();
        int cropH = cropped.get(0).getHeight();
        if (cropW != cropH || !HoloBioRectFft.isPowerOfTwo(cropW)) {
            IJ.showMessage("HoloBio", "Internal error: BPS crop size must be a power-of-two square.");
            return null;
        }
        if (cropW != N || cropH != M) {
            IJ.showStatus(String.format(
                    "HoloBio: BPS using center %dx%d crop (power of 2) from %dx%d frames.",
                    cropW, cropH, N, M));
        }

        try {
            switch (method) {
                case "BPS3":
                    if (frames.size() != 3) {
                        return null;
                    }
                    return bps3(cropped, cropH, cropW, wavelengthUm, dxUm, dyUm);
                case "BPS2":
                    if (frames.size() != 2) {
                        return null;
                    }
                    return bps2(cropped, cropH, cropW, wavelengthUm, dxUm, dyUm);
                default:
                    IJ.showMessage("HoloBio", "Unsupported phase shifting method (only BPS3 and BPS2 are available).");
                    return null;
            }
        } catch (IllegalArgumentException ex) {
            IJ.showMessage("HoloBio", ex.getMessage());
            return null;
        }
    }

    /** Center-crop each frame to a square, then to the largest power-of-two side (Python {@code _to_pow2_square}). */
    private static List<FloatProcessor> cropFramesToPow2Square(List<FloatProcessor> frames) {
        List<FloatProcessor> out = new ArrayList<>(frames.size());
        int expectW = -1;
        int expectH = -1;
        for (FloatProcessor fp : frames) {
            FloatProcessor c = toPow2Square(fp);
            if (expectW < 0) {
                expectW = c.getWidth();
                expectH = c.getHeight();
            } else if (c.getWidth() != expectW || c.getHeight() != expectH) {
                throw new IllegalArgumentException("All phase-shift frames must crop to the same size.");
            }
            out.add(c);
        }
        return out;
    }

    private static FloatProcessor toPow2Square(FloatProcessor fp) {
        int M = fp.getHeight();
        int N = fp.getWidth();
        int side = Math.min(M, N);
        if (side < 32) {
            throw new IllegalArgumentException(
                    "Hologram too small for BPS (need at least 32 pixels after square crop).");
        }
        int dm = (M - side) / 2;
        int dn = (N - side) / 2;
        int p2 = Integer.highestOneBit(side);
        p2 = Math.max(32, Math.min(p2, side));
        int off = (side - p2) / 2;
        int x0 = dn + off;
        int y0 = dm + off;
        FloatProcessor out = new FloatProcessor(p2, p2);
        float[] src = (float[]) fp.getPixels();
        float[] dst = (float[]) out.getPixels();
        for (int row = 0; row < p2; row++) {
            System.arraycopy(src, (y0 + row) * N + x0, dst, row * p2, p2);
        }
        return out;
    }

    private static Result bps2(List<FloatProcessor> frames, int M, int N,
                               double wavelengthUm, double dxUm, double dyUm) {
        double[] h0 = toDoubleMeanSubtracted(pixels(frames.get(0)));
        double[] h1 = toDoubleMeanSubtracted(pixels(frames.get(1)));

        double[] pow0 = powerSpectrumLog(h0, M, N);
        int[] peak1 = maxMaskedHalf(pow0, M, N, true);
        int[] peak2 = maxMaskedHalf(pow0, M, N, false);
        int fy1 = peak1[0], fx1 = peak1[1];
        int fy2 = peak2[0], fx2 = peak2[1];

        double[] x = minimizeMultiStart(2, COBYLA_MAX_ITER_BPS2, (xx) ->
                costBps2(h0, h1, M, N, fy1, fx1, fy2, fx2, xx[0], xx[1]));
        if (x == null) {
            throw new IllegalArgumentException("BPS2 optimization failed; check off-axis frames and parameters.");
        }
        double bestT1 = x[0];
        double bestT2 = x[1];
        if (!isBps2WellConditioned(bestT1, bestT2)) {
            throw new IllegalArgumentException(
                    "BPS2 phase solution is ill-conditioned (θ1 ≈ θ2). Try BPS3 or recapture frames.");
        }

        double[] d2re = new double[M * N];
        double[] d2im = new double[M * N];
        applyComplexRowLinearCombo(bps2MinvRow1(bestT1, bestT2), h0, h1, null, d2re, d2im);

        HoloBioRectFft.fft2dForward(d2re, d2im, M, N);
        HoloBioRectFft.fftShift2d(d2re, d2im, M, N);

        SpatialFilterResult sf = spatialFiltering(d2re, d2im, M, N, fx1, fy1);

        double[] refRe = new double[M * N];
        double[] refIm = new double[M * N];
        referenceWave(refRe, refIm, M, N, wavelengthUm, dxUm, dyUm, sf.fxMax, sf.fyMax);

        double[] outRe = new double[M * N];
        double[] outIm = new double[M * N];
        multiplyComplex(outRe, outIm, sf.spatialRe, sf.spatialIm, refRe, refIm);
        return toResult(outRe, outIm, N, M);
    }

    private static Result bps3(List<FloatProcessor> frames, int M, int N,
                               double wavelengthUm, double dxUm, double dyUm) {
        double[] h0 = toDoubleMeanSubtracted(pixels(frames.get(0)));
        double[] h1 = toDoubleMeanSubtracted(pixels(frames.get(1)));
        double[] h2 = toDoubleMeanSubtracted(pixels(frames.get(2)));

        double[] pow0 = powerSpectrumLog(h0, M, N);
        int[] peak1 = maxMaskedHalf(pow0, M, N, true);
        int[] peak2 = maxMaskedHalf(pow0, M, N, false);
        int fy1 = peak1[0], fx1 = peak1[1];
        int fy2 = peak2[0], fx2 = peak2[1];

        double[] x = minimizeMultiStart(3, COBYLA_MAX_ITER_BPS3, (xx) ->
                costBps3(h0, h1, h2, M, N, fy1, fx1, fy2, fx2, xx[0], xx[1], xx[2]));
        double bestT1 = x[0];
        double bestT2 = x[1];
        double bestT3 = x[2];

        double[] dre = new double[M * N];
        double[] dim = new double[M * N];
        applyComplexRowLinearCombo(bps3MinvRow1(bestT1, bestT2, bestT3), h0, h1, h2, dre, dim);

        HoloBioRectFft.fft2dForward(dre, dim, M, N);
        HoloBioRectFft.fftShift2d(dre, dim, M, N);

        SpatialFilterResult sf = spatialFilteringBps3(dre, dim, M, N);

        double[] refRe = new double[M * N];
        double[] refIm = new double[M * N];
        referenceWave(refRe, refIm, M, N, wavelengthUm, dxUm, dyUm, sf.fxMax, sf.fyMax);

        double[] outRe = new double[M * N];
        double[] outIm = new double[M * N];
        multiplyComplex(outRe, outIm, sf.spatialRe, sf.spatialIm, refRe, refIm);
        return toResult(outRe, outIm, N, M);
    }

    /** Python costFunction2: FT of d1 = Minv[0,:]·[h0,h1], then fftshift; magnitude at two peaks. */
    private static double costBps2(double[] h0, double[] h1, int M, int N,
                                   int fy1, int fx1, int fy2, int fx2, double t1, double t2) {
        double[] d1re = new double[M * N];
        double[] d1im = new double[M * N];
        applyComplexRowLinearCombo(bps2MinvRow0(t1, t2), h0, h1, null, d1re, d1im);
        HoloBioRectFft.fft2dForward(d1re, d1im, M, N);
        HoloBioRectFft.fftShift2d(d1re, d1im, M, N);
        double m1 = magAt(d1re, d1im, fy1, fx1, N);
        double m2 = magAt(d1re, d1im, fy2, fx2, N);
        double denom = m2 + m1;
        if (denom < 1e-12) {
            return 1.0;
        }
        return 1.0 - (m1 - m2) / denom;
    }

    private static double costBps3(double[] h0, double[] h1, double[] h2, int M, int N,
                                    int fy1, int fx1, int fy2, int fx2, double t1, double t2, double t3) {
        double[] d3re = new double[M * N];
        double[] d3im = new double[M * N];
        applyComplexRowLinearCombo(bps3MinvRow2(t1, t2, t3), h0, h1, h2, d3re, d3im);
        HoloBioRectFft.fft2dForward(d3re, d3im, M, N);
        HoloBioRectFft.fftShift2d(d3re, d3im, M, N);
        // BPS3 COBYLA peak indexing unchanged from pre–BPS2-tuning build (Python FTd3[fx, fy] naming).
        double a = magAt(d3re, d3im, fx2, fy2, N);
        double b = magAt(d3re, d3im, fx1, fy1, N);
        double denom = a + b;
        if (denom < 1e-12) {
            return 1.0;
        }
        return 1.0 + (1.0 - (a - b) / denom);
    }

    @FunctionalInterface
    private interface CobylaCost {
        double eval(double[] x);
    }

    /** Multi-start COBYLA (Python {@code _minimize_cobyla}, eight seeds). */
    private static double[] minimizeMultiStart(int nParams, int maxIter, CobylaCost cost) {
        double[] bestX = null;
        double bestVal = Double.POSITIVE_INFINITY;
        for (int start = 0; start < COBYLA_STARTS; start++) {
            double[] x = new double[nParams];
            for (int i = 0; i < nParams; i++) {
                x[i] = COBYLA_RNG.nextInt(360) * (Math.PI / 180.0);
            }
            if (isAllNearZero(x)) {
                x[0] = 1e-4;
            }
            Cobyla.findMinimum(
                    (n, m, xx, con) -> cost.eval(xx),
                    nParams, 0, x, 0.5, 1e-6, Cobyla.IPRINT_NONE, maxIter);
            double val = cost.eval(x);
            if (val < bestVal) {
                bestVal = val;
                bestX = Arrays.copyOf(x, nParams);
            }
        }
        return bestX;
    }

    private static boolean isAllNearZero(double[] x) {
        for (double v : x) {
            if (Math.abs(v) >= 1e-15) {
                return false;
            }
        }
        return true;
    }

    /** Magnitude at spectrum sample (row, col) with row-major index row * N + col. */
    private static double magAt(double[] re, double[] im, int row, int col, int N) {
        int idx = row * N + col;
        return Math.hypot(re[idx], im[idx]);
    }

    /**
     * Row 0 of Minv for [[1, exp(i t1)], [1, exp(i t2)]]^{-1} = 1/det * [[e2, -e1], [-1, 1]].
     * Returns [re(c0), im(c0), re(c1), im(c1)] for (c0+ic0)*h0 + (c1+ic1)*h1.
     */
    private static double[] bps2MinvRow0(double t1, double t2) {
        double e1r = Math.cos(t1), e1i = Math.sin(t1);
        double e2r = Math.cos(t2), e2i = Math.sin(t2);
        double detr = e2r - e1r;
        double deti = e2i - e1i;
        double[] invDet = invComplex(detr, deti);
        double ir = invDet[0];
        double ii = invDet[1];
        double c0r = cmulRe(e2r, e2i, ir, ii);
        double c0i = cmulIm(e2r, e2i, ir, ii);
        double c1r = cmulRe(-e1r, -e1i, ir, ii);
        double c1i = cmulIm(-e1r, -e1i, ir, ii);
        return new double[] {c0r, c0i, c1r, c1i};
    }

    /** Row 1 of same Minv: (-1/det, 1/det) → coefficients for invDet*(h1-h0). */
    private static double[] bps2MinvRow1(double t1, double t2) {
        double e1r = Math.cos(t1), e1i = Math.sin(t1);
        double e2r = Math.cos(t2), e2i = Math.sin(t2);
        double detr = e2r - e1r;
        double deti = e2i - e1i;
        double[] invDet = invComplex(detr, deti);
        double ir = invDet[0];
        double ii = invDet[1];
        return new double[] {-ir, -ii, ir, ii};
    }

    private static double[] bps3MinvRow1(double t1, double t2, double t3) {
        double[][] inv = invert3x3ComplexBps(t1, t2, t3);
        return new double[] {
            inv[1][0], inv[1][1], inv[1][2], inv[1][3], inv[1][4], inv[1][5]
        };
    }

    private static double[] bps3MinvRow2(double t1, double t2, double t3) {
        double[][] inv = invert3x3ComplexBps(t1, t2, t3);
        return new double[] {
            inv[2][0], inv[2][1], inv[2][2], inv[2][3], inv[2][4], inv[2][5]
        };
    }

    /** Minv rows as [re0,im0,re1,im1,re2,im2] per row for 3×3 complex matrix from phaseShifting.BPS3. */
    private static double[][] invert3x3ComplexBps(double t1, double t2, double t3) {
        double e1r = Math.cos(t1), e1i = Math.sin(t1);
        double em1r = Math.cos(t1), em1i = -Math.sin(t1);
        double e2r = Math.cos(t2), e2i = Math.sin(t2);
        double em2r = Math.cos(t2), em2i = -Math.sin(t2);
        double e3r = Math.cos(t3), e3i = Math.sin(t3);
        double em3r = Math.cos(t3), em3i = -Math.sin(t3);
        double[][] a = new double[3][6];
        a[0][0] = 1;
        a[0][2] = e1r;
        a[0][3] = e1i;
        a[0][4] = em1r;
        a[0][5] = em1i;
        a[1][0] = 1;
        a[1][2] = e2r;
        a[1][3] = e2i;
        a[1][4] = em2r;
        a[1][5] = em2i;
        a[2][0] = 1;
        a[2][2] = e3r;
        a[2][3] = e3i;
        a[2][4] = em3r;
        a[2][5] = em3i;
        return invert3x3ComplexToRows(invert6x6FromComplex3x3(a));
    }

    /** Build 6×6 real block matrix from 3×3 complex (row-major pairs per column). */
    private static double[][] build6x6FromComplex3x3(double[][] a) {
        double[][] b = new double[6][6];
        for (int j = 0; j < 3; j++) {
            int c0 = 2 * j;
            int c1 = 2 * j + 1;
            for (int i = 0; i < 3; i++) {
                double ar = a[i][2 * j];
                double ai = a[i][2 * j + 1];
                int r0 = 2 * i;
                int r1 = 2 * i + 1;
                b[r0][c0] = ar;
                b[r0][c1] = -ai;
                b[r1][c0] = ai;
                b[r1][c1] = ar;
            }
        }
        return b;
    }

    private static double[][] invert6x6FromComplex3x3(double[][] a) {
        double[][] m = build6x6FromComplex3x3(a);
        int n = 6;
        double[][] aug = new double[n][12];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double best = Math.abs(aug[pivot][col]);
            for (int r = col + 1; r < n; r++) {
                double v = Math.abs(aug[r][col]);
                if (v > best) {
                    best = v;
                    pivot = r;
                }
            }
            if (best < 1e-18) {
                break;
            }
            double[] tmp = aug[col];
            aug[col] = aug[pivot];
            aug[pivot] = tmp;
            double div = aug[col][col];
            for (int j = col; j < 12; j++) {
                aug[col][j] /= div;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = aug[r][col];
                for (int j = col; j < 12; j++) {
                    aug[r][j] -= f * aug[col][j];
                }
            }
        }
        double[][] inv6 = new double[6][6];
        for (int i = 0; i < 6; i++) {
            System.arraycopy(aug[i], 6, inv6[i], 0, 6);
        }
        return inv6;
    }

    /** Map 6×6 inverse of the real embedding back to complex Minv rows (re,im per column). */
    private static double[][] invert3x3ComplexToRows(double[][] inv6) {
        double[][] out = new double[3][6];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][2 * j] = inv6[2 * i][2 * j];
                out[i][2 * j + 1] = inv6[2 * i + 1][2 * j];
            }
        }
        return out;
    }

    private static final class SpatialFilterResult {
        final double[] spatialRe;
        final double[] spatialIm;
        final int fxMax;
        final int fyMax;

        SpatialFilterResult(double[] spatialRe, double[] spatialIm, int fxMax, int fyMax) {
            this.spatialRe = spatialRe;
            this.spatialIm = spatialIm;
            this.fxMax = fxMax;
            this.fyMax = fyMax;
        }
    }

    private static final int SPATIAL_FILTER_RADIUS = 100;
    /** Ignore peaks within this many pixels of the array border (reduces corner splashes). */
    private static final int SPATIAL_PEAK_BORDER = 4;
    /** Reject DC and immediate neighbors when searching the +1 order. */
    private static final int SPATIAL_DC_GUARD = 8;

    /**
     * Peak indices: {@code fxMax} = column, {@code fyMax} = row.
     * Uses hint from initial spectrum peak; falls back if the local maximum is on the border.
     */
    private static SpatialFilterResult spatialFiltering(double[] inpRe, double[] inpIm, int M, int N,
                                                        int hintCol, int hintRow) {
        int heightHalf = Math.round(M / 2f);
        int dcRow = M / 2;
        int dcCol = N / 2;
        double bestMag = Double.NEGATIVE_INFINITY;
        int fyMax = hintRow;
        int fxMax = hintCol;
        for (int row = 0; row < Math.max(0, heightHalf - 1); row++) {
            for (int col = 0; col < N; col++) {
                if (row < SPATIAL_PEAK_BORDER || col < SPATIAL_PEAK_BORDER
                        || row >= M - SPATIAL_PEAK_BORDER || col >= N - SPATIAL_PEAK_BORDER) {
                    continue;
                }
                if (Math.hypot(col - dcCol, row - dcRow) < SPATIAL_DC_GUARD) {
                    continue;
                }
                int idx = row * N + col;
                double m = Math.hypot(inpRe[idx], inpIm[idx]);
                if (m > bestMag) {
                    bestMag = m;
                    fyMax = row;
                    fxMax = col;
                }
            }
        }
        if (bestMag == Double.NEGATIVE_INFINITY) {
            fyMax = hintRow;
            fxMax = hintCol;
        } else if (isNearArrayBorder(fyMax, fxMax, M, N)) {
            fyMax = hintRow;
            fxMax = hintCol;
        }
        int radius = SPATIAL_FILTER_RADIUS;
        double[] fre = new double[M * N];
        double[] fim = new double[M * N];
        for (int row = 0; row < M; row++) {
            for (int col = 0; col < N; col++) {
                double dist = Math.hypot(col - fxMax, row - fyMax);
                if (dist < radius) {
                    int idx = row * N + col;
                    fre[idx] = inpRe[idx];
                    fim[idx] = inpIm[idx];
                }
            }
        }
        HoloBioRectFft.ifftShift2d(fre, fim, M, N);
        HoloBioRectFft.ifft2d(fre, fim, M, N);
        return new SpatialFilterResult(fre, fim, fxMax, fyMax);
    }

    /**
     * BPS3-only spatial filter (simple upper-half peak search, no hint/border guards).
     * BPS2 keeps {@link #spatialFiltering} with hint + DC/border rejection.
     */
    private static SpatialFilterResult spatialFilteringBps3(double[] inpRe, double[] inpIm, int M, int N) {
        int heightHalf = Math.round(M / 2f);
        double bestMag = -1;
        int fyMax = 0;
        int fxMax = 0;
        for (int row = 0; row < Math.max(0, heightHalf - 1); row++) {
            for (int col = 0; col < N; col++) {
                int idx = row * N + col;
                double m = Math.hypot(inpRe[idx], inpIm[idx]);
                if (m > bestMag) {
                    bestMag = m;
                    fyMax = row;
                    fxMax = col;
                }
            }
        }
        int radius = SPATIAL_FILTER_RADIUS;
        double[] fre = new double[M * N];
        double[] fim = new double[M * N];
        for (int row = 0; row < M; row++) {
            for (int col = 0; col < N; col++) {
                double dist = Math.hypot(col - fxMax, row - fyMax);
                if (dist < radius) {
                    int idx = row * N + col;
                    fre[idx] = inpRe[idx];
                    fim[idx] = inpIm[idx];
                }
            }
        }
        HoloBioRectFft.ifftShift2d(fre, fim, M, N);
        HoloBioRectFft.ifft2d(fre, fim, M, N);
        return new SpatialFilterResult(fre, fim, fxMax, fyMax);
    }

    private static boolean isNearArrayBorder(int row, int col, int M, int N) {
        return row < SPATIAL_PEAK_BORDER || col < SPATIAL_PEAK_BORDER
                || row >= M - SPATIAL_PEAK_BORDER || col >= N - SPATIAL_PEAK_BORDER;
    }

    private static boolean isBps2WellConditioned(double t1, double t2) {
        double detr = Math.cos(t2) - Math.cos(t1);
        double deti = Math.sin(t2) - Math.sin(t1);
        return Math.hypot(detr, deti) > 1e-3;
    }

    /**
     * Reference wave matching Python {@code referenceWave} after peak-index fix.
     * {@code spatialFiltering} returns {@code fxMax} = column (0…N-1), {@code fyMax} = row (0…M-1).
     * θ_x uses row peak and M·dx; θ_y uses column peak and N·dy (X = row−M/2, Y = col−N/2).
     */
    private static void referenceWave(double[] outRe, double[] outIm, int M, int N,
                                      double wavelengthUm, double dxUm, double dyUm, int fxMax, int fyMax) {
        double fx0 = N / 2.0;
        double fy0 = M / 2.0;
        int peakCol = fxMax;
        int peakRow = fyMax;
        double k = (2.0 * Math.PI) / wavelengthUm;
        double thetaX = Math.asin(clampAsin((fy0 - peakRow) * wavelengthUm / (M * dxUm)));
        double thetaY = Math.asin(clampAsin((fx0 - peakCol) * wavelengthUm / (N * dyUm)));
        for (int row = 0; row < M; row++) {
            double x = row - M / 2.0;
            for (int col = 0; col < N; col++) {
                double y = col - N / 2.0;
                double phase = k * (Math.sin(thetaX) * x * dxUm + Math.sin(thetaY) * y * dyUm);
                int idx = row * N + col;
                outRe[idx] = Math.cos(phase);
                outIm[idx] = Math.sin(phase);
            }
        }
    }

    private static double[] powerSpectrumLog(double[] h, int M, int N) {
        double[] re = Arrays.copyOf(h, M * N);
        double[] im = new double[M * N];
        HoloBioRectFft.fft2dForward(re, im, M, N);
        HoloBioRectFft.fftShift2d(re, im, M, N);
        double[] pow = new double[M * N];
        for (int i = 0; i < M * N; i++) {
            double mag2 = re[i] * re[i] + im[i] * im[i];
            pow[i] = 20.0 * Math.log(mag2 + 1e-30);
        }
        return pow;
    }

    private static double[] toDoubleMeanSubtracted(float[] src) {
        double sum = 0.0;
        for (float v : src) {
            sum += v;
        }
        double mean = sum / src.length;
        double[] out = new double[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] - mean;
        }
        return out;
    }

    /** rowCoeffs: [re0,im0,re1,im1,(re2,im2)]; h2 may be null for two-frame combo. */
    private static void applyComplexRowLinearCombo(double[] rowCoeffs, double[] h0, double[] h1, double[] h2,
                                                   double[] outRe, double[] outIm) {
        for (int i = 0; i < outRe.length; i++) {
            double re = rowCoeffs[0] * h0[i] + rowCoeffs[2] * h1[i];
            double im = rowCoeffs[1] * h0[i] + rowCoeffs[3] * h1[i];
            if (h2 != null && rowCoeffs.length >= 6) {
                re += rowCoeffs[4] * h2[i];
                im += rowCoeffs[5] * h2[i];
            }
            outRe[i] = re;
            outIm[i] = im;
        }
    }

    private static void multiplyComplex(double[] outRe, double[] outIm,
                                        double[] aRe, double[] aIm, double[] bRe, double[] bIm) {
        for (int i = 0; i < outRe.length; i++) {
            outRe[i] = aRe[i] * bRe[i] - aIm[i] * bIm[i];
            outIm[i] = aRe[i] * bIm[i] + aIm[i] * bRe[i];
        }
    }

    /** Raw complex field for downstream use (Python {@code comp_phase}); display scaling is separate. */
    private static Result toResult(double[] re, double[] im, int width, int height) {
        int n = re.length;
        float[] outRe = new float[n];
        float[] outIm = new float[n];
        for (int i = 0; i < n; i++) {
            outRe[i] = (float) re[i];
            outIm[i] = (float) im[i];
        }
        return new Result(outRe, outIm, width, height);
    }

    private static int[] maxMaskedHalf(double[] pow, int M, int N, boolean upper) {
        int heightHalf = Math.round(M / 2f);
        double best = Double.NEGATIVE_INFINITY;
        int br = 0, bc = 0;
        if (upper) {
            for (int row = 0; row < Math.max(0, heightHalf - 1); row++) {
                for (int col = 0; col < N; col++) {
                    double v = pow[row * N + col];
                    if (v > best) {
                        best = v;
                        br = row;
                        bc = col;
                    }
                }
            }
        } else {
            for (int row = heightHalf + 1; row < M; row++) {
                for (int col = 0; col < N; col++) {
                    double v = pow[row * N + col];
                    if (v > best) {
                        best = v;
                        br = row;
                        bc = col;
                    }
                }
            }
        }
        if (best == Double.NEGATIVE_INFINITY) {
            String half = upper ? "upper" : "lower";
            throw new IllegalArgumentException(
                    "No diffraction order found in the " + half + " half of the spectrum. "
                            + "Use off-axis holograms and check that frames are loaded correctly.");
        }
        return new int[] {br, bc};
    }

    private static double[] invComplex(double re, double im) {
        double d = re * re + im * im + 1e-30;
        return new double[] {re / d, -im / d};
    }

    private static double cmulRe(double ar, double ai, double br, double bi) {
        return ar * br - ai * bi;
    }

    private static double cmulIm(double ar, double ai, double br, double bi) {
        return ar * bi + ai * br;
    }

    private static float[] pixels(FloatProcessor fp) {
        return (float[]) fp.getPixels();
    }

    private static double clampAsin(double v) {
        if (v > 1.0) {
            return 1.0;
        }
        if (v < -1.0) {
            return -1.0;
        }
        return v;
    }
}
