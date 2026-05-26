import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

/**
 * FFT magnitude display matching HoloBio Python {@code main_DHM_PP._generate_ft_display}
 * when log scale is enabled:
 * {@code mag = log1p(|fftshift(fft2(float32(holo)))|)} then normalize by max and scale to uint8.
 */
public final class HoloBioFftDisplay {

    private HoloBioFftDisplay() {
    }

    /**
     * Square convenience wrapper; delegates to {@link #generateFftLogDisplayRect(FloatProcessor, int, int)}.
     */
    public static ByteProcessor generateFftLogDisplay(FloatProcessor realSquare) {
        int n = realSquare.getWidth();
        if (n != realSquare.getHeight()) {
            throw new IllegalArgumentException("FFT display requires a square image.");
        }
        if (!HoloBioRectFft.isPowerOfTwo(n)) {
            throw new IllegalArgumentException("FFT display requires power-of-two side length, got " + n);
        }
        return generateFftLogDisplayRect(realSquare, n, n);
    }

    /**
     * Log-magnitude of {@code fftshift(fft2(real))} on an M×N real grid (powers of two), same layout as
     * {@link HoloBioCompensationAlgorithms} / Python {@code np.fft.fftshift(np.fft.fft2(...))}.
     * Use this instead of {@link ij.plugin.FFT#forward} for ROI selection so dimensions match the compensation canvas.
     */
    public static ByteProcessor generateFftLogDisplayRect(FloatProcessor realField, int width, int height) {
        if (realField.getWidth() != width || realField.getHeight() != height) {
            throw new IllegalArgumentException("Processor size must match width x height.");
        }
        if (!HoloBioRectFft.isPowerOfTwo(width) || !HoloBioRectFft.isPowerOfTwo(height)) {
            throw new IllegalArgumentException("FFT display requires power-of-two width and height.");
        }
        int len = width * height;
        float[] re = new float[len];
        float[] im = new float[len];
        float[] pix = (float[]) realField.getPixels();
        System.arraycopy(pix, 0, re, 0, len);

        HoloBioRectFft.fft2dForward(re, im, height, width);
        HoloBioRectFft.fftShift2d(re, im, height, width);

        float[] magLog = new float[len];
        float maxVal = 0f;
        for (int i = 0; i < len; i++) {
            double m = Math.hypot(re[i], im[i]);
            float v = (float) Math.log1p(m);
            magLog[i] = v;
            if (v > maxVal) {
                maxVal = v;
            }
        }

        float denom = maxVal + 1e-12f;
        ByteProcessor bp = new ByteProcessor(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int v = Math.round(255f * (magLog[i] / denom));
                if (v < 0) {
                    v = 0;
                }
                if (v > 255) {
                    v = 255;
                }
                bp.set(x, y, v);
            }
        }
        return bp;
    }
}
