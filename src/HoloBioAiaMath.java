import Jama.Matrix;

/**
 * Advanced iterative algorithm (AIA) for randomly phase-shifted interferograms.
 * Matlab version Rene Restrepo
 * Java version Nancy Burgos
 */
public final class HoloBioAiaMath {

    private static final double TWO_PI = 2.0 * Math.PI;

    private HoloBioAiaMath() {
    }

    public interface Progress {
        void iteration(int iteration, int maxIterations, double error);
    }

    public static final class Result {
        public final float[] phase;
        public final float[] background;
        public final float[] modulation;
        // Relative to image 0, in [0, 2pi) //
        public final double[] phaseShifts;
        public final double[] backgroundPerImage;
        public final double[] modulationPerImage;
        public final int iterations;
        public final double lastError;

        Result(float[] phase, float[] background, float[] modulation, double[] phaseShifts,
               double[] backgroundPerImage, double[] modulationPerImage, int iterations, double lastError) {
            this.phase = phase;
            this.background = background;
            this.modulation = modulation;
            this.phaseShifts = phaseShifts;
            this.backgroundPerImage = backgroundPerImage;
            this.modulationPerImage = modulationPerImage;
            this.iterations = iterations;
            this.lastError = lastError;
        }
    }

    /** One row per pixel, one column per image (in MATLAB fringesVector). */
    public static Matrix fringesVector(double[][] frames) {
        int n = frames.length;
        int nPix = frames[0].length;
        double[][] a = new double[nPix][n];
        for (int k = 0; k < n; k++) {
            for (int p = 0; p < nPix; p++) {
                a[p][k] = frames[k][p];
            }
        }
        return new Matrix(a, nPix, n);
    }

    /**
     * MATLAB GetPhaseAdvancedIterativeAlgorithm: alternate step 1 (shifts -> phase) and
     * step 2 (phase -> shifts) until the shifts stop changing.
     * initialShifts: starting guess (rad), one per image; null = start from the PCA phase.
     */
    public static Result run(Matrix fringesVector, double[] initialShifts, int maxIterations,
                             double precision, Progress progress) {
        int nImages = fringesVector.getColumnDimension();
        if (nImages < 3) {
            throw new IllegalArgumentException("Needs at least 3 images.");
        }
        double[] shifts;
        if (initialShifts == null) {
            shifts = step2(pcaPhase(fringesVector), fringesVector).phase;
        } else if (initialShifts.length != nImages) {
            throw new IllegalArgumentException("Initial guess has " + initialShifts.length
                + " values but there are " + nImages + " images.");
        } else {
            shifts = initialShifts.clone();
        }

        Fit s1 = null;
        Fit s2 = null;
        int it = 0;
        double err = Double.NaN;
        while (it < maxIterations) {
            it++;
            s1 = step1(shifts, fringesVector);
            s2 = step2(s1.phase, fringesVector);
            err = shiftChange(shifts, s2.phase);
            shifts = s2.phase;
            if (progress != null) {
                progress.iteration(it, maxIterations, err);
            }
            if (err < precision) {
                break;
            }
        }

        double[] rel = new double[nImages];
        for (int k = 0; k < nImages; k++) {
            rel[k] = mod2pi(shifts[k] - shifts[0]);
        }
        return new Result(toFloat(s1.phase), toFloat(s1.background), toFloat(s1.modulation), rel,
            s2.background, s2.modulation, it, err);
    }

    private static double shiftChange(double[] prev, double[] cur) {
        double sum = 0;
        for (int l = 1; l < cur.length; l++) {
            sum += Math.abs(mod2pi(cur[l] - cur[0]) - mod2pi(prev[l] - prev[0]));
        }
        return sum / (cur.length - 1);
    }

    private static double mod2pi(double x) {
        double r = x % TWO_PI;
        return r < 0 ? r + TWO_PI : r;
    }

    /*
     * Both steps fit I = a + b*cos(theta) + c*sin(theta) by least squares:
     *   step 1: theta = image shifts (known), solve a, b, c per pixel
     *   step 2: theta =pixel phase (known),  solve a, b, c per image
     * then phase = atan2(-c, b), modulation = sqrt(b^2 + c^2).
     */

    private static final class Fit {
        final double[] background;
        final double[] phase;
        final double[] modulation;

        /** x has one row [a b c] per pixel (step 1) or per image (step 2). */
        Fit(Matrix x) {
            int n = x.getRowDimension();
            background = new double[n];
            phase = new double[n];
            modulation = new double[n];
            double[][] rows = x.getArray();
            for (int i = 0; i < n; i++) {
                double b = rows[i][1], c = rows[i][2];
                background[i] = rows[i][0];
                phase[i] = Math.atan2(-c, b);
                modulation[i] = Math.sqrt(b * b + c * c);
            }
        }
    }

    private static Fit step1(double[] phaseShifts, Matrix fringesVector) {
        Matrix basis = basis(phaseShifts);
        Matrix ai = basis.transpose().times(basis).inverse();    // inv(A)
        Matrix b = fringesVector.times(basis);                   // B
        return new Fit(b.times(ai.transpose()));
    }

    /**AIAStep2: phase known -> shift per image */
    private static Fit step2(double[] phase, Matrix fringesVector) {
        Matrix basis = basis(phase);
        Matrix ai = basis.transpose().times(basis).inverse();    // inv(A)
        Matrix b = fringesVector.transpose().times(basis);       // B
        return new Fit(b.times(ai.transpose()));
    }

    /** Rows [1, cos(theta), sin(theta)]. */
    private static Matrix basis(double[] theta) {
        Matrix m = new Matrix(theta.length, 3);
        for (int i = 0; i < theta.length; i++) {
            m.set(i, 0, 1);
            m.set(i, 1, Math.cos(theta[i]));
            m.set(i, 2, Math.sin(theta[i]));
        }
        return m;
    }


    public static double[] pcaPhase(Matrix fringesVector) {
        int nPix = fringesVector.getRowDimension();
        int n = fringesVector.getColumnDimension();
        double mean = 0;
        for (double[] row : fringesVector.getArray()) {
            for (double v : row) {
                mean += v;
            }
        }
        mean /= (double) nPix * n;
        Matrix insb = fringesVector.minus(new Matrix(nPix, n, mean));
        Matrix v = insb.transpose().times(insb).svd().getV();
        Matrix y = insb.times(v.getMatrix(0, n - 1, 0, 1));     // [Y1 Y2]
        double[] phase = new double[nPix];
        for (int p = 0; p < nPix; p++) {
            phase[p] = Math.atan2(y.get(p, 1), y.get(p, 0));
        }
        return phase;
    }

    /**
     * MATLAB IgramNorm: remove the background (Gaussian high-pass, radius r), get the sin
     * fringes with the spiral phase transform, and return the normalized cos(phase)
     */
    public static double[] normalizeFringes(double[] img, int width, int height, double r) {
        int nPix = width * height;
        double[] re = img.clone();
        double[] im = new double[nPix];
        HoloBioRectFft.fft2dForward(re, im, height, width);
        double twoR2 = 2 * r * r;
        for (int i = 0; i < height; i++) {
            double v = ((i + height / 2) % height) - height / 2;   // literally MATLAB ifftshift
            for (int j = 0; j < width; j++) {
                double u = ((j + width / 2) % width) - width / 2;
                double h = 1 - Math.exp(-(u * u + v * v) / twoR2);
                re[i * width + j] *= h;
                im[i * width + j] *= h;
            }
        }
        HoloBioRectFft.ifft2d(re, im, height, width);
        double[] ch = re;   // background removed

        double[] sre = ch.clone();
        double[] sim = new double[nPix];
        HoloBioRectFft.fft2dForward(sre, sim, height, width);
        for (int i = 0; i < height; i++) {
            double y = -1 + 2.0 * ((i + height / 2) % height) / height;
            for (int j = 0; j < width; j++) {
                double x = -1 + 2.0 * ((j + width / 2) % width) / width;
                double th = Math.atan2(y, x);
                double hr = Math.cos(th), hi = -Math.sin(th);
                int p = i * width + j;
                double a = sre[p], b = sim[p];
                sre[p] = a * hr - b * hi;
                sim[p] = a * hi + b * hr;
            }
        }
        HoloBioRectFft.ifft2d(sre, sim, height, width);

        double[] out = new double[nPix];
        for (int p = 0; p < nPix; p++) {
            double s = Math.sqrt(sre[p] * sre[p] + sim[p] * sim[p]);
            out[p] = Math.cos(Math.atan2(s, ch[p]));
        }
        return out;
    }

    private static float[] toFloat(double[] d) {
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) {
            f[i] = (float) d[i];
        }
        return f;
    }
}

// should work,, TODO: lab test, debugign