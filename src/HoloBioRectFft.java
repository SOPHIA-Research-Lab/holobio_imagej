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

        requirePow2(rows, cols);

        fftRows(re, im, rows, cols, false);

        fftCols(re, im, rows, cols, false);

    }



    public static void fft2dForward(double[] re, double[] im, int rows, int cols) {

        requirePow2(rows, cols);

        fftRows(re, im, rows, cols, false);

        fftCols(re, im, rows, cols, false);

    }



    /**

     * Inverse 2D FFT with exactly {@code 1/(rows·cols)} scaling (numpy ifft2).

     * Order: inverse rows, then inverse columns (numpy axes -2, -1).

     */

    public static void ifft2d(float[] re, float[] im, int rows, int cols) {

        requirePow2(rows, cols);

        fftRows(re, im, rows, cols, true);

        fftCols(re, im, rows, cols, true);

        float inv = 1.0f / ((float) rows * (float) cols);

        for (int i = 0; i < rows * cols; i++) {

            re[i] *= inv;

            im[i] *= inv;

        }

    }



    public static void ifft2d(double[] re, double[] im, int rows, int cols) {

        requirePow2(rows, cols);

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

        int len = alongRows ? rows : cols;

        int p2 = inverse ? len - (len + 1) / 2 : (len + 1) / 2;

        float[] tmpRe = new float[len];

        float[] tmpIm = new float[len];

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

        float[] bufRe = new float[cols];

        float[] bufIm = new float[cols];

        for (int y = 0; y < rows; y++) {

            int base = y * cols;

            System.arraycopy(re, base, bufRe, 0, cols);

            System.arraycopy(im, base, bufIm, 0, cols);

            fftRadix2InPlace(bufRe, bufIm, inverse);

            System.arraycopy(bufRe, 0, re, base, cols);

            System.arraycopy(bufIm, 0, im, base, cols);

        }

    }



    private static void fftCols(float[] re, float[] im, int rows, int cols, boolean inverse) {

        float[] bufRe = new float[rows];

        float[] bufIm = new float[rows];

        for (int x = 0; x < cols; x++) {

            for (int y = 0; y < rows; y++) {

                int idx = y * cols + x;

                bufRe[y] = re[idx];

                bufIm[y] = im[idx];

            }

            fftRadix2InPlace(bufRe, bufIm, inverse);

            for (int y = 0; y < rows; y++) {

                int idx = y * cols + x;

                re[idx] = bufRe[y];

                im[idx] = bufIm[y];

            }

        }

    }



    private static void fftRows(double[] re, double[] im, int rows, int cols, boolean inverse) {

        double[] bufRe = new double[cols];

        double[] bufIm = new double[cols];

        for (int y = 0; y < rows; y++) {

            int base = y * cols;

            System.arraycopy(re, base, bufRe, 0, cols);

            System.arraycopy(im, base, bufIm, 0, cols);

            fftRadix2InPlace(bufRe, bufIm, inverse);

            System.arraycopy(bufRe, 0, re, base, cols);

            System.arraycopy(bufIm, 0, im, base, cols);

        }

    }



    private static void fftCols(double[] re, double[] im, int rows, int cols, boolean inverse) {

        double[] bufRe = new double[rows];

        double[] bufIm = new double[rows];

        for (int x = 0; x < cols; x++) {

            for (int y = 0; y < rows; y++) {

                int idx = y * cols + x;

                bufRe[y] = re[idx];

                bufIm[y] = im[idx];

            }

            fftRadix2InPlace(bufRe, bufIm, inverse);

            for (int y = 0; y < rows; y++) {

                int idx = y * cols + x;

                re[idx] = bufRe[y];

                im[idx] = bufIm[y];

            }

        }

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


