import ij.IJ;
import ij.ImagePlus;

/**
 * Micro-Manager bridge (backbone only).
 * <p>
 * Does not link against mmcorej at compile time. Full snap/live will use
 * {@code mmc.snapImage()} once MM is loaded in the same JVM.
 */
public class HoloBioMicroManagerBackend implements HoloBioCameraBackend {

    private static final String MMCORE_CLASS = "mmcorej.CMMCore";
    private static final String MM_STUDIO_CLASS = "org.micromanager.Studio";

    private boolean connected;

    @Override
    public HoloBioCameraKind getKind() {
        return HoloBioCameraKind.MICRO_MANAGER;
    }

    @Override
    public HoloBioCameraAvailability probe() {
        if (classOnClasspath(MMCORE_CLASS)) {
            return HoloBioCameraAvailability.notImplemented(
                "Micro-Manager libraries detected.",
                getKind().getFijiMenuHint() + " — configure your camera, then use MM Live or Snap.");
        }
        return HoloBioCameraAvailability.pluginMissing(
            "Micro-Manager (mmcorej) not found in this Fiji.",
            "Install Micro-Manager for Fiji, then add your camera in a hardware configuration.");
    }

    @Override
    public void connect(HoloBioAcquisitionSettings settings) throws Exception {
        if (!classOnClasspath(MMCORE_CLASS)) {
            throw new IllegalStateException("Micro-Manager is not installed.");
        }
        connected = true;
        HoloBioFijiUi.log("[HoloBio] Micro-Manager connect stub — open MM and load a config before live capture.");
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public ImagePlus snapFrame(HoloBioAcquisitionSettings settings) throws Exception {
        if (!classOnClasspath(MMCORE_CLASS)) {
            throw new IllegalStateException("Micro-Manager is not installed.");
        }
        // TODO: obtain Studio / CMMCore via MMPlugin or MMService, snapImage(), getTaggedImage as ImagePlus
        throw new UnsupportedOperationException(
            "MM snap not implemented yet. Snap in Micro-Manager, then use Active Image or Use Active Image.");
    }

    /**
     * Future: {@code IJ.runMacro("run(\"Snap Image\");");} or reflective mmc API.
     */
    public static boolean isMicroManagerPresent() {
        return classOnClasspath(MMCORE_CLASS) || classOnClasspath(MM_STUDIO_CLASS);
    }

    private static boolean classOnClasspath(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
