import ij.ImagePlus;
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
     * After SPP on the complex field: updates the shown field and display channel.
     */
    void onSpeckleSppResult(float[] fieldRe, float[] fieldIm, int w, int h,
                            boolean amplitudeChannel, FloatProcessor display0255);

    /** Mutable speckle comparison / iteration state owned by the plugin. */
    HoloBioSpeckleState getSpeckleState();

    /**
     * Inputs for the reconstruction the user is working on now (the image clicked last in Fiji),
     * read when a tool button is pressed rather than when the dialog opened. Null = use the
     * snapshot the dialog was opened with.
     */
    default HoloBioToolInputs currentInputs() {
        return null;
    }

    /** The open amplitude or phase output window, where ROIs are drawn; null when there is none. */
    default ImagePlus outputImage(boolean amplitude) {
        return null;
    }

    /** Phase window QPI measures for the image clicked last; null = none. */
    default ImagePlus qpiPhaseImage() {
        return null;
    }

    /** Wavelength (µm) of the reconstruction {@code phaseImage} comes from; NaN when unknown. */
    default double wavelengthUmFor(ImagePlus phaseImage) {
        return Double.NaN;
    }
}
