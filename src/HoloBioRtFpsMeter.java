/**
 * Frame counter over a fixed time window.
 *
 * <p>Counted over a window rather than smoothed per frame, matching Python's
 * {@code _mark_*_frame_displayed}: a plain count over elapsed time is what "frames per second"
 * means, and unlike an exponential average it does not lag when the rate steps.
 */
public final class HoloBioRtFpsMeter {

    private static final double WINDOW_S = 0.5;

    private int    count;
    private long   windowStartNs = System.nanoTime();
    private volatile double value;

    /** @return true when the window closed and {@link #value()} changed */
    public boolean tick() {
        count++;
        long now = System.nanoTime();
        double elapsed = (now - windowStartNs) / 1_000_000_000.0;
        if (elapsed < WINDOW_S) return false;
        value = count / elapsed;
        count = 0;
        windowStartNs = now;
        return true;
    }

    public double value() { return value; }

    public void reset() {
        count = 0;
        value = 0;
        windowStartNs = System.nanoTime();
    }
}
