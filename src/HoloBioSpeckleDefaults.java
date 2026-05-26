/**
 * Default speckle tool parameters (HoloBio Manual §7.3 — speckle measurements & filters).
 */
public final class HoloBioSpeckleDefaults {

    /** Number of macro-zones (rectangles) to draw on the image. */
    public static final int ZONES = 1;
    /** Subdivision rows inside each zone. */
    public static final int ROWS = 2;
    /** Subdivision columns inside each zone. */
    public static final int COLS = 2;
    /** Default HMF / SPP iteration count in the filter tab. */
    public static final int FILTER_ITERATIONS = 3;

    private HoloBioSpeckleDefaults() {}
}
