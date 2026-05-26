import ij.IJ;
import ij.ImagePlus;

/**
 * Orchestrates camera backend selection and single-frame capture into the DHM pipeline.
 */
public class HoloBioAcquisitionService {

    private final HoloBioCameraRegistry registry = new HoloBioCameraRegistry();
    private final HoloBioAcquisitionSettings settings = new HoloBioAcquisitionSettings();
    private HoloBioCameraBackend activeBackend;
    private HoloBioCameraFrameListener frameListener;

    public HoloBioCameraRegistry getRegistry() {
        return registry;
    }

    public HoloBioAcquisitionSettings getSettings() {
        return settings;
    }

    public void setFrameListener(HoloBioCameraFrameListener frameListener) {
        this.frameListener = frameListener;
    }

    public void selectBackend(HoloBioCameraKind kind) {
        settings.setBackend(kind);
        activeBackend = registry.get(kind);
    }

    public HoloBioCameraAvailability probeSelected() {
        ensureBackend();
        return activeBackend.probe();
    }

    public void connect() throws Exception {
        ensureBackend();
        log("Connect: " + activeBackend.getKind().getDisplayName());
        activeBackend.connect(settings);
    }

    public void disconnect() {
        if (activeBackend != null) {
            activeBackend.disconnect();
            log("Disconnected.");
        }
    }

    /**
     * Snap one frame and notify {@link HoloBioCameraFrameListener} if set.
     *
     * @return captured image or null
     */
    public ImagePlus snapAndDeliver() throws Exception {
        ensureBackend();
        log("Snap: " + activeBackend.getKind().getDisplayName());
        ImagePlus frame = activeBackend.snapFrame(settings);
        if (frame == null) {
            log("Snap returned no image.");
            return null;
        }
        if (frameListener != null) {
            frameListener.onFrame(frame, settings);
        }
        IJ.showStatus("HoloBio: frame from " + activeBackend.getKind().getDisplayName());
        return frame;
    }

    public String buildProbeReport() {
        return registry.formatProbeReport();
    }

    private void ensureBackend() {
        if (activeBackend == null || activeBackend.getKind() != settings.getBackend()) {
            activeBackend = registry.get(settings.getBackend());
        }
    }

    private void log(String line) {
        HoloBioFijiUi.log("[HoloBio Acquisition] " + line);
        if (frameListener != null) {
            frameListener.onAcquisitionLog(line);
        }
    }
}
