/** Throwaway timing harness for the RT DHM pipeline. Not part of the plugin build. */
public class RtBench {

    static float[] synthHologram(int rows, int cols) {
        float[] h = new float[rows * cols];
        double kx = 2 * Math.PI * 0.18, ky = 2 * Math.PI * 0.11;
        java.util.Random rnd = new java.util.Random(7);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double obj = Math.exp(-(Math.pow(r - rows / 2.0, 2) + Math.pow(c - cols / 2.0, 2))
                        / (2 * Math.pow(rows / 6.0, 2)));
                double v = 128 + 60 * obj * Math.cos(kx * c + ky * r) + 4 * rnd.nextGaussian();
                h[r * cols + c] = (float) Math.max(0, Math.min(255, v));
            }
        }
        return h;
    }

    static double time(String label, float[] holo, int rows, int cols,
                       boolean ft, boolean amp, boolean phase, int iters) {
        for (int i = 0; i < 15; i++)
            HoloBioRtDhmMath.process(holo, rows, cols, 0.532, 5.2, 5.2,
                    HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, ft, true, amp, phase);
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++)
            HoloBioRtDhmMath.process(holo, rows, cols, 0.532, 5.2, 5.2,
                    HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, ft, true, amp, phase);
        double ms = (System.nanoTime() - t0) / 1e6 / iters;
        System.out.printf("  %-34s %7.2f ms  -> %5.1f fps%n", label, ms, 1000.0 / ms);
        return ms;
    }

    public static void main(String[] args) {
        int[][] sizes = { {480, 640}, {720, 1280} };
        for (int[] s : sizes) {
            int rows = s[0], cols = s[1];
            float[] holo = synthHologram(rows, cols);
            HoloBioRtDhmMath.resetSideband();
            System.out.printf("%nFrame %d x %d  ->  FFT grid %d x %d, field %d x %d%n",
                    cols, rows,
                    HoloBioRtDhmMath.fitPowerOfTwo(cols), HoloBioRtDhmMath.fitPowerOfTwo(rows),
                    HoloBioRtDhmMath.outputCols(cols), HoloBioRtDhmMath.outputRows(rows));
            int iters = rows > 600 ? 20 : 40;
            time("FT + phase   (default view)", holo, rows, cols, true,  false, true,  iters);
            time("FT + amplitude",              holo, rows, cols, true,  true,  false, iters);
            time("hologram + phase (no FT)",    holo, rows, cols, false, false, true,  iters);
        }

        // Cost of the old habits, measured in isolation.
        int n = 480 * 640;
        float[] a = new float[n], b = new float[n];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < n; i++) { a[i] = rnd.nextFloat(); b[i] = rnd.nextFloat(); }

        double sink = 0;
        for (int i = 0; i < n; i++) sink += Math.hypot(a[i], b[i]);
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sink += Math.hypot(a[i], b[i]);
        double hy = (System.nanoTime() - t0) / 1e6;

        for (int i = 0; i < n; i++) sink += Math.sqrt(a[i] * a[i] + b[i] * b[i]);
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sink += Math.sqrt(a[i] * a[i] + b[i] * b[i]);
        double sq = (System.nanoTime() - t0) / 1e6;

        System.out.printf("%nPer-pixel magnitude over %d px (ignore %.1f)%n", n, sink);
        System.out.printf("  Math.hypot                       %7.2f ms%n", hy);
        System.out.printf("  Math.sqrt(x*x+y*y)               %7.2f ms   (%.1fx faster)%n", sq, hy / sq);
    }
}
