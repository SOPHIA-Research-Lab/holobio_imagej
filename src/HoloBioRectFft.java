/**

 * Row–column radix-2 complex FFT for rectangular grids (M rows × N columns).

 * Both M and N must be powers of two. Indexing: row i, column j → {@code i * N + j}.

 * <p>

 * Forward {@link #fft2dForward}: no scaling (matches {@code numpy.fft.fft2}).

 * Inverse {@link #ifft2d}: single factor {@code 1/(M·N)} after separable passes (matches {@code numpy.fft.ifft2}).

 */

public final class HoloBioRectFft {



    private HoloBioRectFft() {

    }



    public static boolean isPowerOfTwo(int x) {

        return x > 0 && (x & (x - 1)) == 0;

    }



    public static int nextPowerOfTwo(int value) {

        int n = 1;

        while (n < value) {

            n <<= 1;

        }

        return n;

    }



    /** fftshift for an M×N complex array (row-major). Self-inverse when rows and cols are even. */

    public static void fftShift2d(float[] re, float[] im, int rows, int cols) {

        fftShift2dCore(re, im, rows, cols);

    }



    public static void fftShift2d(double[] re, double[] im, int rows, int cols) {

        fftShift2dCore(re, im, rows, cols);

    }



    /** Undo prior {@link #fftShift2d} (numpy {@code ifftshift}; differs from fftshift when a dimension is odd). */

    public static void ifftShift2d(float[] re, float[] im, int rows, int cols) {

        ifftShift2dCore(re, im, rows, cols);

    }



    public static void ifftShift2d(double[] re, double[] im, int rows, int cols) {

        ifftShift2dCore(re, im, rows, cols);

    }



    /** Real 2D fftshift ({@code numpy.fft.fftshift}). */

    public static void fftShift2dReal(double[] data, int rows, int cols) {

        shift2dReal(data, rows, cols, false);

    }



    /** Real 2D ifftshift ({@code numpy.fft.ifftshift}). */

    public static void ifftShift2dReal(double[] data, int rows, int cols) {

        shift2dReal(data, rows, cols, true);

    }



    /** Forward 2D FFT, no normalization (numpy fft2). Order: rows, then columns. */

    public static void fft2dForward(float[] re, float[] im, int rows, int cols) {

        fftRows(re, im, rows, cols, false);

        fftCols(re, im, rows, cols, false);

    }



    public static void fft2dForward(double[] re, double[] im, int rows, int cols) {

        fftRows(re, im, rows, cols, false);

        fftCols(re, im, rows, cols, false);

    }



    /**

     * Inverse 2D FFT with exactly {@code 1/(rows·cols)} scaling (numpy ifft2).

     * Order: inverse rows, then inverse columns (numpy axes -2, -1).

     */

    public static void ifft2d(float[] re, float[] im, int rows, int cols) {

        fftRows(re, im, rows, cols, true);

        fftCols(re, im, rows, cols, true);

        float inv = 1.0f / ((float) rows * (float) cols);

        for (int i = 0; i < rows * cols; i++) {

            re[i] *= inv;

            im[i] *= inv;

        }

    }



    public static void ifft2d(double[] re, double[] im, int rows, int cols) {

        fftRows(re, im, rows, cols, true);

        fftCols(re, im, rows, cols, true);

        double inv = 1.0 / ((double) rows * (double) cols);

        for (int i = 0; i < rows * cols; i++) {

            re[i] *= inv;

            im[i] *= inv;

        }

    }



    private static void requirePow2(int rows, int cols) {

        if (!isPowerOfTwo(rows) || !isPowerOfTwo(cols)) {

            throw new IllegalArgumentException("FFT dimensions must be powers of two.");

        }

    }



    private static void fftShift2dCore(float[] re, float[] im, int rows, int cols) {

        shift2dCore(re, im, rows, cols, false);

    }



    private static void ifftShift2dCore(float[] re, float[] im, int rows, int cols) {

        shift2dCore(re, im, rows, cols, true);

    }



    private static void fftShift2dCore(double[] re, double[] im, int rows, int cols) {

        shift2dCore(re, im, rows, cols, false);

    }



    private static void ifftShift2dCore(double[] re, double[] im, int rows, int cols) {

        shift2dCore(re, im, rows, cols, true);

    }



    private static void shift2dCore(float[] re, float[] im, int rows, int cols, boolean inverse) {

        shiftAxis(re, im, rows, cols, true, inverse);

        shiftAxis(re, im, rows, cols, false, inverse);

    }



    private static void shift2dCore(double[] re, double[] im, int rows, int cols, boolean inverse) {

        shiftAxis(re, im, rows, cols, true, inverse);

        shiftAxis(re, im, rows, cols, false, inverse);

    }



    private static void shift2dReal(double[] data, int rows, int cols, boolean inverse) {

        shiftAxisReal(data, rows, cols, true, inverse);

        shiftAxisReal(data, rows, cols, false, inverse);

    }



  /** Numpy {@code fftshift} / {@code ifftshift} along one axis of a row-major 2D array. */

    private static void shiftAxis(float[] re, float[] im, int rows, int cols, boolean alongRows, boolean inverse) {
        final int len = alongRows ? rows : cols;
        final int p2 = inverse ? len - (len + 1) / 2 : (len + 1) / 2;
        if (alongRows) {
            HoloBioParallel.forEachLine(cols, (from, to) -> {
                float[] tmpRe = new float[len];
                float[] tmpIm = new float[len];
                for (int x = from; x < to; x++) {
                    for (int y = 0; y < len; y++) {
                        int si = ((y + p2) % len) * cols + x;
                        tmpRe[y] = re[si];
                        tmpIm[y] = im[si];
                    }
                    for (int y = 0; y < len; y++) {
                        int di = y * cols + x;
                        re[di] = tmpRe[y];
                        im[di] = tmpIm[y];
                    }
                }
            });
        } else {
            HoloBioParallel.forEachLine(rows, (from, to) -> {
                float[] tmpRe = new float[len];
                float[] tmpIm = new float[len];
                for (int y = from; y < to; y++) {
                    int base = y * cols;
                    for (int x = 0; x < len; x++) {
                        int src = (x + p2) % len;
                        tmpRe[x] = re[base + src];
                        tmpIm[x] = im[base + src];
                    }
                    System.arraycopy(tmpRe, 0, re, base, len);
                    System.arraycopy(tmpIm, 0, im, base, len);
                }
            });
        }
    }

    private static void shiftAxis(double[] re, double[] im, int rows, int cols, boolean alongRows, boolean inverse) {

        int len = alongRows ? rows : cols;

        int p2 = inverse ? len - (len + 1) / 2 : (len + 1) / 2;

        double[] tmpRe = new double[len];

        double[] tmpIm = new double[len];

        if (alongRows) {

            for (int x = 0; x < cols; x++) {

                for (int y = 0; y < len; y++) {

                    int src = (y + p2) % len;

                    int si = src * cols + x;

                    tmpRe[y] = re[si];

                    tmpIm[y] = im[si];

                }

                for (int y = 0; y < len; y++) {

                    int di = y * cols + x;

                    re[di] = tmpRe[y];

                    im[di] = tmpIm[y];

                }

            }

        } else {

            for (int y = 0; y < rows; y++) {

                int base = y * cols;

                for (int x = 0; x < len; x++) {

                    int src = (x + p2) % len;

                    tmpRe[x] = re[base + src];

                    tmpIm[x] = im[base + src];

                }

                System.arraycopy(tmpRe, 0, re, base, len);

                System.arraycopy(tmpIm, 0, im, base, len);

            }

        }

    }



    private static void shiftAxisReal(double[] data, int rows, int cols, boolean alongRows, boolean inverse) {

        int len = alongRows ? rows : cols;

        int p2 = inverse ? len - (len + 1) / 2 : (len + 1) / 2;

        double[] tmp = new double[len];

        if (alongRows) {

            for (int x = 0; x < cols; x++) {

                for (int y = 0; y < len; y++) {

                    int src = (y + p2) % len;

                    tmp[y] = data[src * cols + x];

                }

                for (int y = 0; y < len; y++) {

                    data[y * cols + x] = tmp[y];

                }

            }

        } else {

            for (int y = 0; y < rows; y++) {

                int base = y * cols;

                for (int x = 0; x < len; x++) {

                    int src = (x + p2) % len;

                    tmp[x] = data[base + src];

                }

                System.arraycopy(tmp, 0, data, base, len);

            }

        }

    }



    private static void fftRows(float[] re, float[] im, int rows, int cols, boolean inverse) {
        HoloBioParallel.forEachLine(rows, (from, to) -> {
            float[] bufRe = new float[cols];
            float[] bufIm = new float[cols];
            for (int y = from; y < to; y++) {
                int base = y * cols;
                System.arraycopy(re, base, bufRe, 0, cols);
                System.arraycopy(im, base, bufIm, 0, cols);
                fft1d(bufRe, bufIm, inverse);
                System.arraycopy(bufRe, 0, re, base, cols);
                System.arraycopy(bufIm, 0, im, base, cols);
            }
        });
    }

    private static void fftCols(float[] re, float[] im, int rows, int cols, boolean inverse) {
        HoloBioParallel.forEachLine(cols, (from, to) -> {
            float[] bufRe = new float[rows];
            float[] bufIm = new float[rows];
            for (int x = from; x < to; x++) {
                for (int y = 0; y < rows; y++) {
                    int idx = y * cols + x;
                    bufRe[y] = re[idx];
                    bufIm[y] = im[idx];
                }
                fft1d(bufRe, bufIm, inverse);
                for (int y = 0; y < rows; y++) {
                    int idx = y * cols + x;
                    re[idx] = bufRe[y];
                    im[idx] = bufIm[y];
                }
            }
        });
    }

    private static void fftRows(double[] re, double[] im, int rows, int cols, boolean inverse) {
        HoloBioParallel.forEachLine(rows, (from, to) -> {
            double[] bufRe = new double[cols];
            double[] bufIm = new double[cols];
            for (int y = from; y < to; y++) {
                int base = y * cols;
                System.arraycopy(re, base, bufRe, 0, cols);
                System.arraycopy(im, base, bufIm, 0, cols);
                fft1d(bufRe, bufIm, inverse);
                System.arraycopy(bufRe, 0, re, base, cols);
                System.arraycopy(bufIm, 0, im, base, cols);
            }
        });
    }

    private static void fftCols(double[] re, double[] im, int rows, int cols, boolean inverse) {
        HoloBioParallel.forEachLine(cols, (from, to) -> {
            double[] bufRe = new double[rows];
            double[] bufIm = new double[rows];
            for (int x = from; x < to; x++) {
                for (int y = 0; y < rows; y++) {
                    int idx = y * cols + x;
                    bufRe[y] = re[idx];
                    bufIm[y] = im[idx];
                }
                fft1d(bufRe, bufIm, inverse);
                for (int y = 0; y < rows; y++) {
                    int idx = y * cols + x;
                    re[idx] = bufRe[y];
                    im[idx] = bufIm[y];
                }
            }
        });
    }

    private static void swap(float[] re, float[] im, int i, int j) {

        float tr = re[i];

        re[i] = re[j];

        re[j] = tr;

        float ti = im[i];

        im[i] = im[j];

        im[j] = ti;

    }



    private static void swap(double[] re, double[] im, int i, int j) {

        double tr = re[i];

        re[i] = re[j];

        re[j] = tr;

        double ti = im[i];

        im[i] = im[j];

        im[j] = ti;

    }



    /** 1D radix-2 FFT; inverse passes do not scale (2D ifft2d applies 1/(M·N) once). */

    // ---------------------------------------------------------------------
    // Arbitrary-length transforms (Bluestein)
    // ---------------------------------------------------------------------
    // numpy transforms at the frame's native size. Zero- or mean-padding up to a power
    // of two is NOT equivalent — the padded border convolves into the spectrum and
    // visibly corrupts the reconstruction (measured ~9% peak amplitude error on a
    // 320x240 frame). Bluestein expresses a length-n DFT as a convolution of length
    // m >= 2n-1, which can be a power of two, so any n is exact.

    /** Chirp tables for one (n, direction); rebuilt only when the pass length changes. */
    private static final class Chirp {
        final int n;
        final int m;
        final boolean inverse;
        final double[] cos;
        final double[] sin;
        final double[] bRe;
        final double[] bIm;

        Chirp(int n, boolean inverse) {
            this.n = n;
            this.inverse = inverse;
            this.m = nextPowerOfTwo(2 * n + 1);
            this.cos = new double[n];
            this.sin = new double[n];
            double sign = inverse ? 1.0 : -1.0;
            for (int i = 0; i < n; i++) {
                // exp(i*pi*k^2/n) repeats every 2n in k^2, so reducing first keeps the
                // angle small: k*k overflows and loses precision for large n otherwise.
                long k2 = (long) i * i % (2L * n);
                double ang = sign * Math.PI * k2 / n;
                cos[i] = Math.cos(ang);
                sin[i] = Math.sin(ang);
            }
            this.bRe = new double[m];
            this.bIm = new double[m];
            bRe[0] = cos[0];
            bIm[0] = -sin[0];
            for (int i = 1; i < n; i++) {
                bRe[i] = bRe[m - i] = cos[i];
                bIm[i] = bIm[m - i] = -sin[i];
            }
            fftRadix2InPlace(bRe, bIm, false);
        }
    }

    /** Single-entry cache: every line in a pass shares the same length and direction. */
    private static volatile Chirp chirpCache;

    private static Chirp chirpFor(int n, boolean inverse) {
        Chirp c = chirpCache;
        if (c != null && c.n == n && c.inverse == inverse) {
            return c;
        }
        Chirp built = new Chirp(n, inverse);
        chirpCache = built;   // benign race: two threads may build the same tables
        return built;
    }

    /** Unscaled DFT of any length, matching {@link #fftRadix2InPlace}'s conventions. */
    private static void bluestein(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        Chirp c = chirpFor(n, inverse);
        int m = c.m;

        double[] ar = new double[m];
        double[] ai = new double[m];
        for (int i = 0; i < n; i++) {
            ar[i] = re[i] * c.cos[i] - im[i] * c.sin[i];
            ai[i] = re[i] * c.sin[i] + im[i] * c.cos[i];
        }
        fftRadix2InPlace(ar, ai, false);
        for (int i = 0; i < m; i++) {
            double t = ar[i] * c.bRe[i] - ai[i] * c.bIm[i];
            ai[i] = ar[i] * c.bIm[i] + ai[i] * c.bRe[i];
            ar[i] = t;
        }
        fftRadix2InPlace(ar, ai, true);   // radix-2 inverse leaves scaling to the caller
        double inv = 1.0 / m;
        for (int i = 0; i < n; i++) {
            double cr = ar[i] * inv;
            double ci = ai[i] * inv;
            re[i] = cr * c.cos[i] - ci * c.sin[i];
            im[i] = cr * c.sin[i] + ci * c.cos[i];
        }
    }

    /** Radix-2 when the length allows it, Bluestein otherwise. */
    private static void fft1d(double[] re, double[] im, boolean inverse) {
        if (isPowerOfTwo(re.length)) {
            fftRadix2InPlace(re, im, inverse);
        } else {
            bluestein(re, im, inverse);
        }
    }

    private static void fft1d(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        if (isPowerOfTwo(n)) {
            fftRadix2InPlace(re, im, inverse);
            return;
        }
        double[] dr = new double[n];
        double[] di = new double[n];
        for (int i = 0; i < n; i++) {
            dr[i] = re[i];
            di[i] = im[i];
        }
        bluestein(dr, di, inverse);
        for (int i = 0; i < n; i++) {
            re[i] = (float) dr[i];
            im[i] = (float) di[i];
        }
    }

    private static void fftRadix2InPlace(float[] re, float[] im, boolean inverse) {

        int n = re.length;

        bitReversePermutation(re, im, n);

        for (int len = 2; len <= n; len <<= 1) {

            double ang = (inverse ? 2.0 : -2.0) * Math.PI / len;

            double wlenRe = Math.cos(ang);

            double wlenIm = Math.sin(ang);

            int half = len >>> 1;

            for (int i = 0; i < n; i += len) {

                double wRe = 1.0;

                double wIm = 0.0;

                for (int j = 0; j < half; j++) {

                    int u = i + j;

                    int v = u + half;

                    double tRe = wRe * re[v] - wIm * im[v];

                    double tIm = wRe * im[v] + wIm * re[v];

                    re[v] = (float) (re[u] - tRe);

                    im[v] = (float) (im[u] - tIm);

                    re[u] = (float) (re[u] + tRe);

                    im[u] = (float) (im[u] + tIm);

                    double nwRe = wRe * wlenRe - wIm * wlenIm;

                    double nwIm = wRe * wlenIm + wIm * wlenRe;

                    wRe = nwRe;

                    wIm = nwIm;

                }

            }

        }

    }



    private static void fftRadix2InPlace(double[] re, double[] im, boolean inverse) {

        int n = re.length;

        bitReversePermutation(re, im, n);

        for (int len = 2; len <= n; len <<= 1) {

            double ang = (inverse ? 2.0 : -2.0) * Math.PI / len;

            double wlenRe = Math.cos(ang);

            double wlenIm = Math.sin(ang);

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

                    re[u] = re[u] + tRe;

                    im[u] = im[u] + tIm;

                    double nwRe = wRe * wlenRe - wIm * wlenIm;

                    double nwIm = wRe * wlenIm + wIm * wlenRe;

                    wRe = nwRe;

                    wIm = nwIm;

                }

            }

        }

    }



    private static void bitReversePermutation(float[] re, float[] im, int n) {

        int j = 0;

        for (int i = 1; i < n; i++) {

            int bit = n >>> 1;

            for (; (j & bit) != 0; j ^= bit, bit >>>= 1) {

                // advance

            }

            j ^= bit;

            if (i < j) {

                swap(re, im, i, j);

            }

        }

    }



    private static void bitReversePermutation(double[] re, double[] im, int n) {

        int j = 0;

        for (int i = 1; i < n; i++) {

            int bit = n >>> 1;

            for (; (j & bit) != 0; j ^= bit, bit >>>= 1) {

                // advance

            }

            j ^= bit;

            if (i < j) {

                swap(re, im, i, j);

            }

        }

    }

}


