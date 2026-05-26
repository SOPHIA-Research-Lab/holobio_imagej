/**
 * Power-of-two FFT and angular-spectrum / Fresnel propagation (square and rectangular grids) for the DHM plugin.
 */
public final class HoloBioPropagationMath {

    private HoloBioPropagationMath() {
    }

    public static void propagateAngularSpectrumInPlace(float[] re, float[] im, int n, double zUm,
                                                       double wavelengthUm, double dxUm, double dyUm) {
        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double z = zUm * 1e-6;

        // Match holobio Python pyDHM_methods.angularSpectrum:
        // fftshift(field) -> fft2 -> fftshift; * exp(i*z*2*pi*kz) with complex kz; ifftshift -> ifft2 -> ifftshift
        fftShift2d(re, im, n);
        fft2d(re, im, n, false);
        fftShift2d(re, im, n);
        for (int y = 0; y < n; y++) {
            double fy = (y - n / 2.0) / (dy * n);
            for (int x = 0; x < n; x++) {
                double fx = (x - n / 2.0) / (dx * n);
                double val = (1.0 / (wavelength * wavelength)) - (fx * fx + fy * fy);
                int idx = y * n + x;
                double kzRe;
                double kzIm;
                if (val >= 0.0) {
                    kzRe = Math.sqrt(val);
                    kzIm = 0.0;
                } else {
                    kzRe = 0.0;
                    kzIm = Math.sqrt(-val);
                }
                double phaseOsc = 2.0 * Math.PI * z * kzRe;
                double damp = Math.exp(-2.0 * Math.PI * z * kzIm);
                double c = damp * Math.cos(phaseOsc);
                double s = damp * Math.sin(phaseOsc);
                float rr = re[idx];
                float ii = im[idx];
                re[idx] = (float) (rr * c - ii * s);
                im[idx] = (float) (rr * s + ii * c);
            }
        }
        fftShift2d(re, im, n);
        fft2d(re, im, n, true);
        fftShift2d(re, im, n);
    }

    public static void propagateFresnelInPlace(float[] re, float[] im, int n, double zUm,
                                               double wavelengthUm, double dxUm, double dyUm) {
        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double z = zUm * 1e-6;

        // Match holobio Python pyDHM_methods.fresnel (single forward FFT, chirp factors; no inverse FFT).
        double dxOut = (wavelength * z) / (n * dx);
        double dyOut = (wavelength * z) / (n * dy);
        double invLambdaZ = 1.0 / (wavelength * z);
        double thetaZ = 2.0 * Math.PI * z / wavelength;
        // z_phase = exp(i*thetaZ) / (i * wavelength * z)  ->  (sin(thetaZ) - i*cos(thetaZ)) / (wavelength*z)
        double zPhRe = Math.sin(thetaZ) * invLambdaZ;
        double zPhIm = -Math.cos(thetaZ) * invLambdaZ;

        for (int y = 0; y < n; y++) {
            double yc = y - n / 2.0;
            for (int x = 0; x < n; x++) {
                double xc = x - n / 2.0;
                double phiIn = Math.PI * invLambdaZ * (xc * xc * dx * dx + yc * yc * dy * dy);
                double ci = Math.cos(phiIn);
                double si = Math.sin(phiIn);
                int idx = y * n + x;
                float rr = re[idx];
                float ii = im[idx];
                re[idx] = (float) (rr * ci - ii * si);
                im[idx] = (float) (rr * si + ii * ci);
            }
        }

        fftShift2d(re, im, n);
        fft2d(re, im, n, false);
        fftShift2d(re, im, n);

        double scaleDxDy = dx * dy;
        for (int y = 0; y < n; y++) {
            double yc = y - n / 2.0;
            for (int x = 0; x < n; x++) {
                double xc = x - n / 2.0;
                double phiOut = Math.PI * invLambdaZ * (xc * xc * dxOut * dxOut + yc * yc * dyOut * dyOut);
                double co = Math.cos(phiOut);
                double so = Math.sin(phiOut);
                // (zPhRe + i*zPhIm) * (co + i*so) * dx*dy
                double tr = scaleDxDy * (zPhRe * co - zPhIm * so);
                double ti = scaleDxDy * (zPhRe * so + zPhIm * co);
                int idx = y * n + x;
                float rr = re[idx];
                float ii = im[idx];
                re[idx] = (float) (rr * tr - ii * ti);
                im[idx] = (float) (rr * ti + ii * tr);
            }
        }
    }

    /**
     * Angular spectrum on an M×N grid (row-major: index = y * width + x). M and N must be powers of two.
     * Matches {@code pyDHM_methods.angularSpectrum} with {@code dfx = 1/(dx*N)}, {@code dfy = 1/(dy*M)}.
     */
    public static void propagateAngularSpectrumInPlaceRect(float[] re, float[] im, int height, int width, double zUm,
                                                             double wavelengthUm, double dxUm, double dyUm) {
        if (!HoloBioRectFft.isPowerOfTwo(height) || !HoloBioRectFft.isPowerOfTwo(width)) {
            throw new IllegalArgumentException("Angular spectrum (rect) requires power-of-two height and width.");
        }
        int m = height;
        int n = width;
        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double z = zUm * 1e-6;

        HoloBioRectFft.fftShift2d(re, im, m, n);
        HoloBioRectFft.fft2dForward(re, im, m, n);
        HoloBioRectFft.fftShift2d(re, im, m, n);

        for (int y = 0; y < m; y++) {
            double fy = (y - m / 2.0) / (dy * m);
            for (int x = 0; x < n; x++) {
                double fx = (x - n / 2.0) / (dx * n);
                double val = (1.0 / (wavelength * wavelength)) - (fx * fx + fy * fy);
                int idx = y * n + x;
                double kzRe;
                double kzIm;
                if (val >= 0.0) {
                    kzRe = Math.sqrt(val);
                    kzIm = 0.0;
                } else {
                    kzRe = 0.0;
                    kzIm = Math.sqrt(-val);
                }
                double phaseOsc = 2.0 * Math.PI * z * kzRe;
                double damp = Math.exp(-2.0 * Math.PI * z * kzIm);
                double c = damp * Math.cos(phaseOsc);
                double s = damp * Math.sin(phaseOsc);
                float rr = re[idx];
                float ii = im[idx];
                re[idx] = (float) (rr * c - ii * s);
                im[idx] = (float) (rr * s + ii * c);
            }
        }

        HoloBioRectFft.fftShift2d(re, im, m, n);
        HoloBioRectFft.ifft2d(re, im, m, n);
        HoloBioRectFft.fftShift2d(re, im, m, n);
    }

    /**
     * Fresnel propagation on M×N (powers of two), matching {@code pyDHM_methods.fresnel} indexing.
     */
    public static void propagateFresnelInPlaceRect(float[] re, float[] im, int height, int width, double zUm,
                                                   double wavelengthUm, double dxUm, double dyUm) {
        if (!HoloBioRectFft.isPowerOfTwo(height) || !HoloBioRectFft.isPowerOfTwo(width)) {
            throw new IllegalArgumentException("Fresnel (rect) requires power-of-two height and width.");
        }
        int m = height;
        int n = width;
        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double z = zUm * 1e-6;

        double dxOut = (wavelength * z) / (n * dx);
        double dyOut = (wavelength * z) / (m * dy);
        double invLambdaZ = 1.0 / (wavelength * z);
        double thetaZ = 2.0 * Math.PI * z / wavelength;
        double zPhRe = Math.sin(thetaZ) * invLambdaZ;
        double zPhIm = -Math.cos(thetaZ) * invLambdaZ;

        for (int y = 0; y < m; y++) {
            double yc = y - m / 2.0;
            for (int x = 0; x < n; x++) {
                double xc = x - n / 2.0;
                double phiIn = Math.PI * invLambdaZ * (xc * xc * dx * dx + yc * yc * dy * dy);
                double ci = Math.cos(phiIn);
                double si = Math.sin(phiIn);
                int idx = y * n + x;
                float rr = re[idx];
                float ii = im[idx];
                re[idx] = (float) (rr * ci - ii * si);
                im[idx] = (float) (rr * si + ii * ci);
            }
        }

        HoloBioRectFft.fftShift2d(re, im, m, n);
        HoloBioRectFft.fft2dForward(re, im, m, n);
        HoloBioRectFft.fftShift2d(re, im, m, n);

        double scaleDxDy = dx * dy;
        for (int y = 0; y < m; y++) {
            double yc = y - m / 2.0;
            for (int x = 0; x < n; x++) {
                double xc = x - n / 2.0;
                double phiOut = Math.PI * invLambdaZ * (xc * xc * dxOut * dxOut + yc * yc * dyOut * dyOut);
                double co = Math.cos(phiOut);
                double so = Math.sin(phiOut);
                double tr = scaleDxDy * (zPhRe * co - zPhIm * so);
                double ti = scaleDxDy * (zPhRe * so + zPhIm * co);
                int idx = y * n + x;
                float rr = re[idx];
                float ii = im[idx];
                re[idx] = (float) (rr * tr - ii * ti);
                im[idx] = (float) (rr * ti + ii * tr);
            }
        }
    }

    public static void fft2d(float[] re, float[] im, int n, boolean inverse) {
        float[] rowRe = new float[n];
        float[] rowIm = new float[n];
        for (int y = 0; y < n; y++) {
            int base = y * n;
            System.arraycopy(re, base, rowRe, 0, n);
            System.arraycopy(im, base, rowIm, 0, n);
            fftRadix2InPlace(rowRe, rowIm, inverse);
            System.arraycopy(rowRe, 0, re, base, n);
            System.arraycopy(rowIm, 0, im, base, n);
        }

        float[] colRe = new float[n];
        float[] colIm = new float[n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                int idx = y * n + x;
                colRe[y] = re[idx];
                colIm[y] = im[idx];
            }
            fftRadix2InPlace(colRe, colIm, inverse);
            for (int y = 0; y < n; y++) {
                int idx = y * n + x;
                re[idx] = colRe[y];
                im[idx] = colIm[y];
            }
        }
    }

    public static void fftShift2d(float[] re, float[] im, int n) {
        int h2 = n / 2;
        for (int y = 0; y < h2; y++) {
            for (int x = 0; x < n; x++) {
                int i1 = y * n + x;
                int i2 = (y + h2) * n + x;
                float tr = re[i1];
                re[i1] = re[i2];
                re[i2] = tr;
                float ti = im[i1];
                im[i1] = im[i2];
                im[i2] = ti;
            }
        }
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < h2; x++) {
                int i1 = y * n + x;
                int i2 = y * n + (x + h2);
                float tr = re[i1];
                re[i1] = re[i2];
                re[i2] = tr;
                float ti = im[i1];
                im[i1] = im[i2];
                im[i2] = ti;
            }
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
        if (inverse) {
            float inv = 1.0f / n;
            for (int i = 0; i < n; i++) {
                re[i] *= inv;
                im[i] *= inv;
            }
        }
    }

    private static void bitReversePermutation(float[] re, float[] im, int n) {
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >>> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>>= 1;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
    }
}
