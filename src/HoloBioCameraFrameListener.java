import ij.ImagePlus;

/** Callback when a new hologram frame is available from acquisition. */
public interface HoloBioCameraFrameListener {

    void onFrame(ImagePlus frame, HoloBioAcquisitionSettings settings);

    void onAcquisitionLog(String line);
}
