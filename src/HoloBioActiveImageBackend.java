import ij.ImagePlus;
import ij.WindowManager;
import ij.process.ImageProcessor;

/** Always-available backend: current Fiji image window. */
public class HoloBioActiveImageBackend implements HoloBioCameraBackend {

    @Override
    public HoloBioCameraKind getKind() {
        return HoloBioCameraKind.ACTIVE_IMAGE;
    }

    @Override
    public HoloBioCameraAvailability probe() {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            return HoloBioCameraAvailability.ready("No active image — open a hologram first.");
        }
        return HoloBioCameraAvailability.ready("Active image: " + imp.getTitle());
    }

    @Override
    public void connect(HoloBioAcquisitionSettings settings) {
        // no device
    }

    @Override
    public void disconnect() {
        // no device
    }

    @Override
    public boolean isConnected() {
        return WindowManager.getCurrentImage() != null;
    }

    @Override
    public ImagePlus snapFrame(HoloBioAcquisitionSettings settings) {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            return null;
        }
        ImagePlus copy = imp.duplicate();
        if (copy.getStackSize() > 1) {
            if (copy.isHyperStack()) {
                copy.setPosition(1, 1, 1);
            } else {
                copy.setSlice(1);
            }
        }
        ImageProcessor proc = copy.getProcessor().convertToFloatProcessor();
        ImagePlus gray = new ImagePlus("HoloBio Hologram", proc);
        if (settings != null) {
            settings.setWidthPx(gray.getWidth());
            settings.setHeightPx(gray.getHeight());
        }
        return gray;
    }
}
