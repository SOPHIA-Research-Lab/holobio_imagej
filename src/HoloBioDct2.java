/**

 * Separable 2D DCT-II / inverse DCT-III (matches {@code scipy.fft.dctn} / {@code idctn}, norm=none).

 */

final class HoloBioDct2 {



    private HoloBioDct2() {

    }



    static void dct2InPlace(double[] data, int width, int height) {

        for (int y = 0; y < height; y++) {

            dct1InPlace(data, y * width, width);

        }

        double[] col = new double[height];

        for (int x = 0; x < width; x++) {

            for (int y = 0; y < height; y++) {

                col[y] = data[y * width + x];

            }

            dct1InPlace(col, 0, height);

            for (int y = 0; y < height; y++) {

                data[y * width + x] = col[y];

            }

        }

    }



    static void idct2InPlace(double[] data, int width, int height) {

        double[] col = new double[height];

        for (int x = 0; x < width; x++) {

            for (int y = 0; y < height; y++) {

                col[y] = data[y * width + x];

            }

            idct1InPlace(col, 0, height);

            for (int y = 0; y < height; y++) {

                data[y * width + x] = col[y];

            }

        }

        for (int y = 0; y < height; y++) {

            idct1InPlace(data, y * width, width);

        }

    }



  /** Forward DCT-II via length {@code 2n} real FFT. */

    private static void dct1InPlace(double[] data, int off, int n) {

        int n2 = n * 2;

        double[] re = new double[n2];

        double[] im = new double[n2];

        for (int i = 0; i < n; i++) {

            re[i] = data[off + i];

        }

        for (int i = 0; i < n; i++) {

            re[n2 - 1 - i] = data[off + i];

        }

        fft1d(re, im, false);

        for (int k = 0; k < n; k++) {

            double ang = -Math.PI * k / (2.0 * n);

            double c = Math.cos(ang);

            double s = Math.sin(ang);

            data[off + k] = re[k] * c - im[k] * s;

        }

    }



    /**
     * Inverse of {@link #dct1InPlace} ({@code scipy.fft.idct}, norm=none):
     * {@code x[j] = (X[0] + 2·Σ_{k≥1} X[k]·cos(πk(2j+1)/2n)) / 2n}.
     *
     * <p>Via one length-{@code 2n} inverse FFT of {@code w[k]·X[k]·e^{iπk/2n}} (w[0] = ½),
     * whose real part is that sum divided by 2n·½. The previous version dropped both the ½ on
     * the k = 0 term and the factor 2 on the rest, and then overwrote half the output from the
     * mirrored end of the buffer, so idct(dct(x)) ≠ x. Every DCT Poisson solve — the phase
     * unwrapper behind the Legendre fits — inherited the error.
     */
    private static void idct1InPlace(double[] data, int off, int n) {

        int n2 = n * 2;

        double[] re = new double[n2];

        double[] im = new double[n2];

        for (int k = 0; k < n; k++) {

            double ang = Math.PI * k / (2.0 * n);

            double w = k == 0 ? 0.5 : 1.0;

            re[k] = w * data[off + k] * Math.cos(ang);

            im[k] = w * data[off + k] * Math.sin(ang);

        }

        fft1d(re, im, true);

        for (int i = 0; i < n; i++) {

            data[off + i] = 2.0 * re[i];

        }

    }



    private static void fft1d(double[] re, double[] im, boolean inverse) {

        int n = re.length;

        bitReverse(re, im);

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

                    re[u] += tRe;

                    im[u] += tIm;

                    double nwRe = wRe * wlenRe - wIm * wlenIm;

                    double nwIm = wRe * wlenIm + wIm * wlenRe;

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

                double tr = re[i];

                re[i] = re[j];

                re[j] = tr;

                double ti = im[i];

                im[i] = im[j];

                im[j] = ti;

            }

        }

    }

}


