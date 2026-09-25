/**
 * Thin real-time wrapper around {@link HoloBioDlhmMath#reconstruct}.
 *
 * <p>Dispatches on {@link HoloBioDlhmParams#getAlgorithm()} exactly like the offline
 * module, so live and offline run the same reconstruction for a given method.
 * Work arrays are cached so the capture loop does not allocate every frame.
 */
public final class HoloBioRtDlhmMath {

    public static final class Frame {
        public final float[] amplitude;
        public final float[] phase;
        public final int     width;
        public final int     height;

        Frame(float[] amplitude, float[] phase, int width, int height) {
            this.amplitude = amplitude;
            this.phase     = phase;
            this.width     = width;
            this.height    = height;
        }
    }

    private static float[] workRe, workIm, ampBuf, phaseBuf;
    private static int     workRows, workCols;

    private HoloBioRtDlhmMath() {}

    /**
     * Reconstruct one frame with whichever method the params carry (AS / DL / KR).
     *
     * @param wantAmplitude skip |field| when false
     * @param wantPhase     skip arg(field) when false
     */
    /**
     * Copy of the complex field from the last {@link #process} call, or null before the
     * first frame. Synchronized with {@code process}, so it never sees a half-written frame.
     * Note the DL method stores {@code conj(Uz)}, matching Python's
     * {@code np.angle(np.conj(Uz))} — the saved field's angle is the displayed phase.
     */
    public static synchronized HoloBioNpy.Snapshot lastField() {
        if (workRe == null || workRows <= 0 || workCols <= 0) {
            return null;
        }
        int n = workRows * workCols;
        return new HoloBioNpy.Snapshot(java.util.Arrays.copyOf(workRe, n),
                java.util.Arrays.copyOf(workIm, n), workCols, workRows);
    }

    public static synchronized Frame process(float[] holo, int rows, int cols, HoloBioDlhmParams p,
                                boolean wantAmplitude, boolean wantPhase) {
        ensureWork(rows, cols);
        HoloBioDlhmMath.reconstruct(holo, rows, cols, p, workRe, workIm);

        float[] amp = null, phase = null;
        int n = rows * cols;

        if (wantAmplitude || wantPhase) {
            if (ampBuf == null || ampBuf.length != n) ampBuf = new float[n];
            for (int i = 0; i < n; i++) {
                double a = workRe[i], b = workIm[i];
                ampBuf[i] = (float) Math.sqrt(a * a + b * b);
            }
        }

        if (wantPhase) {
            if (phaseBuf == null || phaseBuf.length != n) phaseBuf = new float[n];
            phase = phaseBuf;
            float k = (float) (255.0 / (2.0 * Math.PI));
            for (int i = 0; i < n; i++) {
                float v = ((float) Math.atan2(workIm[i], workRe[i]) + (float) Math.PI) * k;
                phase[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
            }
        }

        if (wantAmplitude) {
            amp = ampBuf;
            HoloBioRtUtil.stretchTo255(amp, n);
        }

        return new Frame(amp, phase, cols, rows);
    }

    private static void ensureWork(int rows, int cols) {
        int n = rows * cols;
        if (workRows != rows || workCols != cols || workRe == null || workRe.length != n) {
            workRe = new float[n];
            workIm = new float[n];
            workRows = rows;
            workCols = cols;
            ampBuf = phaseBuf = null;
        }
    }
}
