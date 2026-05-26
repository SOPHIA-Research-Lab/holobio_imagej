/**
 * Standalone checks for {@link HoloBioRectFft} (run: {@code java -cp build HoloBioRectFftVerify}).
 */
public final class HoloBioRectFftVerify {

    private HoloBioRectFftVerify() {
    }

    public static void main(String[] args) {
        int failures = 0;
        failures += checkShiftRoundtrip(4, 4);
        failures += checkShiftRoundtrip(8, 8);
        failures += checkShiftRoundtrip(11, 11);
        failures += checkShiftRoundtrip(551, 551);
        failures += checkRealShiftRoundtrip(11, 11);
        failures += checkRealShiftRoundtrip(551, 551);
        failures += checkIfft2Ones(4, 4);
        failures += checkIfft2Ones(32, 32);
        failures += checkFftRoundtrip(8, 8);
        failures += checkSpatialIfftPath(4, 4);
        if (failures > 0) {
            System.err.println("FAILED " + failures + " check(s).");
            System.exit(1);
        }
        System.out.println("HoloBioRectFftVerify: all checks passed.");
    }

    /** fftshift then ifftshift restores original (numpy; odd sizes differ from double fftshift). */
    private static int checkShiftRoundtrip(int rows, int cols) {
        double[] re = new double[rows * cols];
        double[] im = new double[rows * cols];
        for (int i = 0; i < re.length; i++) {
            re[i] = i + 1;
            im[i] = -i * 0.25;
        }
        double[] origRe = re.clone();
        double[] origIm = im.clone();
        HoloBioRectFft.fftShift2d(re, im, rows, cols);
        HoloBioRectFft.ifftShift2d(re, im, rows, cols);
        return reportMaxErr("shift roundtrip " + rows + "x" + cols, origRe, origIm, re, im, 1e-12);
    }

    private static int checkRealShiftRoundtrip(int rows, int cols) {
        double[] data = new double[rows * cols];
        for (int i = 0; i < data.length; i++) {
            data[i] = i + 1;
        }
        double[] orig = data.clone();
        HoloBioRectFft.fftShift2dReal(data, rows, cols);
        HoloBioRectFft.ifftShift2dReal(data, rows, cols);
        double max = 0.0;
        for (int i = 0; i < data.length; i++) {
            max = Math.max(max, Math.abs(data[i] - orig[i]));
        }
        if (max > 1e-12) {
            System.err.printf("real shift roundtrip %dx%d: max error %g%n", rows, cols, max);
            return 1;
        }
        return 0;
    }

    /** ifft2(freq=ones) is an impulse at (0,0) with value 1 (numpy ifft2). */
    private static int checkIfft2Ones(int rows, int cols) {
        double[] re = new double[rows * cols];
        double[] im = new double[rows * cols];
        ArraysFill(re, 1.0);
        HoloBioRectFft.ifft2d(re, im, rows, cols);
        if (Math.abs(re[0] - 1.0) > 1e-9 || Math.abs(im[0]) > 1e-9) {
            System.err.printf("ifft2(ones) %dx%d: [0] = (%g,%g), expected (1,0)%n", rows, cols, re[0], im[0]);
            return 1;
        }
        for (int i = 1; i < re.length; i++) {
            if (Math.abs(re[i]) > 1e-9 || Math.abs(im[i]) > 1e-9) {
                System.err.printf("ifft2(ones) %dx%d: [%d] = (%g,%g), expected (0,0)%n", rows, cols, i, re[i], im[i]);
                return 1;
            }
        }
        return 0;
    }

    /** ifft2(fft2(x)) ≈ x. */
    private static int checkFftRoundtrip(int rows, int cols) {
        double[] re = new double[rows * cols];
        double[] im = new double[rows * cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                re[idx] = Math.sin(row * 0.7 + col * 0.3);
                im[idx] = Math.cos(row * 0.2 - col * 0.5);
            }
        }
        double[] origRe = re.clone();
        double[] origIm = im.clone();
        HoloBioRectFft.fft2dForward(re, im, rows, cols);
        HoloBioRectFft.ifft2d(re, im, rows, cols);
        return reportMaxErr("fft roundtrip " + rows + "x" + cols, origRe, origIm, re, im, 1e-9);
    }

    /** Masked fftshift spectrum → ifftshift → ifft2 (BPS spatialFiltering path). */
    private static int checkSpatialIfftPath(int rows, int cols) {
        double[] re = new double[rows * cols];
        double[] im = new double[rows * cols];
        re[1 * cols + 2] = 3.0;
        im[1 * cols + 2] = -1.0;
        HoloBioRectFft.fft2dForward(re, im, rows, cols);
        HoloBioRectFft.fftShift2d(re, im, rows, cols);
        HoloBioRectFft.ifftShift2d(re, im, rows, cols);
        HoloBioRectFft.ifft2d(re, im, rows, cols);
        double energy = 0.0;
        for (int i = 0; i < re.length; i++) {
            energy += re[i] * re[i] + im[i] * im[i];
        }
        if (energy < 1e-12) {
            System.err.println("spatial IFFT path produced zero field");
            return 1;
        }
        return 0;
    }

    private static int reportMaxErr(String label, double[] expRe, double[] expIm, double[] gotRe, double[] gotIm, double tol) {
        double max = 0.0;
        for (int i = 0; i < expRe.length; i++) {
            max = Math.max(max, Math.hypot(gotRe[i] - expRe[i], gotIm[i] - expIm[i]));
        }
        if (max > tol) {
            System.err.printf("%s: max error %g (tol %g)%n", label, max, tol);
            return 1;
        }
        return 0;
    }

    private static void ArraysFill(double[] a, double v) {
        for (int i = 0; i < a.length; i++) {
            a[i] = v;
        }
    }
}

