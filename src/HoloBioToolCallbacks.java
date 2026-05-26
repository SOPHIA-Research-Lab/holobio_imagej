import ij.process.FloatProcessor;

/** Callbacks from tool dialogs back into the plugin (update reconstructions). */
public interface HoloBioToolCallbacks {

    /**
     * After a speckle filter on amplitude (0–255 float) or phase display channel.
     *
     * @param amplitudeChannel true = amplitude image, false = phase image
     * @param filtered0255     values in [0,255] as float processor
     */
    void onSpeckleFilterResult(boolean amplitudeChannel, FloatProcessor filtered0255);

    /**
     * After SPP on the complex field: updates {@code currentFieldRe/Im} and display channel.
     */
    void onSpeckleSppResult(float[] fieldRe, float[] fieldIm, int w, int h,
                            boolean amplitudeChannel, FloatProcessor display0255);

    /** Mutable speckle comparison / iteration state owned by the plugin. */
    HoloBioSpeckleState getSpeckleState();
}
