import ij.ImagePlus;

/**
 * Pluggable camera / acquisition source for HoloBio DHM.
 * Implementations must not require vendor SDKs at compile time when possible
 * (use reflection for Micro-Manager, etc.).
 */
public interface HoloBioCameraBackend {

    HoloBioCameraKind getKind();

    HoloBioCameraAvailability probe();

    /** Open device or session; no-op for {@link HoloBioCameraKind#ACTIVE_IMAGE}. */
    void connect(HoloBioAcquisitionSettings settings) throws Exception;

    void disconnect();

    boolean isConnected();

    /**
     * Grab one frame into a new {@link ImagePlus} (caller owns display lifecycle).
     * May return null if live capture is not implemented yet.
     */
    ImagePlus snapFrame(HoloBioAcquisitionSettings settings) throws Exception;

    /** Optional: start live preview loop (not implemented on most stubs). */
    default void startLive(HoloBioAcquisitionSettings settings, HoloBioCameraFrameListener listener) {
        throw new UnsupportedOperationException("Live preview not implemented for " + getKind().getDisplayName());
    }

    default void stopLive() {
        // no-op
    }
}
