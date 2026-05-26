import ij.ImagePlus;

/**
 * Placeholder backend for vendor-specific Fiji plugins (IDS, Basler, …).
 * Probes optional class names; capture remains unimplemented until a bridge is added.
 */
public class HoloBioStubCameraBackend implements HoloBioCameraBackend {

    private final HoloBioCameraKind kind;
    private final String[] extraProbeClasses;

    public HoloBioStubCameraBackend(HoloBioCameraKind kind, String... extraProbeClasses) {
        this.kind = kind;
        this.extraProbeClasses = extraProbeClasses != null ? extraProbeClasses : new String[0];
    }

    @Override
    public HoloBioCameraKind getKind() {
        return kind;
    }

    @Override
    public HoloBioCameraAvailability probe() {
        String primary = kind.getProbeClassName();
        if (primary != null && classPresent(primary)) {
            return HoloBioCameraAvailability.notImplemented(
                kind.getDisplayName() + " plugin classes found.",
                kind.getFijiMenuHint());
        }
        for (String cls : extraProbeClasses) {
            if (classPresent(cls)) {
                return HoloBioCameraAvailability.notImplemented(
                    kind.getDisplayName() + " related classes found.",
                    kind.getFijiMenuHint());
            }
        }
        return HoloBioCameraAvailability.pluginMissing(
            kind.getDisplayName() + " bridge not installed.",
            kind.getFijiMenuHint() != null ? kind.getFijiMenuHint() : "See project CAMERA_ACQUISITION.md");
    }

    @Override
    public void connect(HoloBioAcquisitionSettings settings) throws Exception {
        HoloBioCameraAvailability av = probe();
        if (av.getStatus() == HoloBioCameraAvailability.Status.PLUGIN_MISSING) {
            throw new IllegalStateException(av.getMessage());
        }
        throw new UnsupportedOperationException(av.getMessage() + " — capture bridge pending.");
    }

    @Override
    public void disconnect() {
        // no-op
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public ImagePlus snapFrame(HoloBioAcquisitionSettings settings) throws Exception {
        throw new UnsupportedOperationException(
            kind.getDisplayName() + " snap not implemented. Use Micro-Manager or Active Image for now.");
    }

    private static boolean classPresent(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
