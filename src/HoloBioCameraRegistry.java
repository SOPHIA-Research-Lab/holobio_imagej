import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers {@link HoloBioCameraBackend} instances and probes Fiji for known DHM camera stacks.
 */
public class HoloBioCameraRegistry {

    private final Map<HoloBioCameraKind, HoloBioCameraBackend> backends = new LinkedHashMap<>();

    public HoloBioCameraRegistry() {
        register(new HoloBioActiveImageBackend());
        register(new HoloBioMicroManagerBackend());
        register(new HoloBioStubCameraBackend(HoloBioCameraKind.IDS_U3));
        register(new HoloBioStubCameraBackend(HoloBioCameraKind.BASLER));
        register(new HoloBioStubCameraBackend(HoloBioCameraKind.HAMAMATSU));
        register(new HoloBioStubCameraBackend(HoloBioCameraKind.ANDOR));
        register(new HoloBioStubCameraBackend(HoloBioCameraKind.PCO));
        register(new HoloBioStubCameraBackend(HoloBioCameraKind.UVC_WEBCAM));
    }

    public void register(HoloBioCameraBackend backend) {
        if (backend != null) {
            backends.put(backend.getKind(), backend);
        }
    }

    public HoloBioCameraBackend get(HoloBioCameraKind kind) {
        return backends.get(kind != null ? kind : HoloBioCameraKind.ACTIVE_IMAGE);
    }

    public List<HoloBioCameraKind> listKinds() {
        return Collections.unmodifiableList(new ArrayList<>(backends.keySet()));
    }

    public HoloBioCameraAvailability probe(HoloBioCameraKind kind) {
        HoloBioCameraBackend backend = get(kind);
        if (backend == null) {
            return new HoloBioCameraAvailability(
                HoloBioCameraAvailability.Status.ERROR, "Unknown backend", null);
        }
        return backend.probe();
    }

    /** Human-readable probe summary for the acquisition dialog. */
    public String formatProbeReport() {
        StringBuilder sb = new StringBuilder();
        for (HoloBioCameraKind kind : listKinds()) {
            HoloBioCameraAvailability av = probe(kind);
            sb.append(kind.getDisplayName())
                .append(": ")
                .append(av.getStatus())
                .append(" — ")
                .append(av.getMessage());
            if (av.getSetupHint() != null && !av.getSetupHint().isEmpty()) {
                sb.append(" [").append(av.getSetupHint()).append("]");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
