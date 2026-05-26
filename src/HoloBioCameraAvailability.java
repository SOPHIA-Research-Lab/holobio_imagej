/** Result of probing whether a {@link HoloBioCameraBackend} can be used in this Fiji session. */
public class HoloBioCameraAvailability {

    public enum Status {
        READY,
        PLUGIN_MISSING,
        NOT_IMPLEMENTED,
        ERROR
    }

    private final Status status;
    private final String message;
    private final String setupHint;

    public HoloBioCameraAvailability(Status status, String message, String setupHint) {
        this.status = status != null ? status : Status.ERROR;
        this.message = message != null ? message : "";
        this.setupHint = setupHint;
    }

    public static HoloBioCameraAvailability ready(String message) {
        return new HoloBioCameraAvailability(Status.READY, message, null);
    }

    public static HoloBioCameraAvailability notImplemented(String message, String setupHint) {
        return new HoloBioCameraAvailability(Status.NOT_IMPLEMENTED, message, setupHint);
    }

    public static HoloBioCameraAvailability pluginMissing(String message, String setupHint) {
        return new HoloBioCameraAvailability(Status.PLUGIN_MISSING, message, setupHint);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getSetupHint() {
        return setupHint;
    }

    public boolean isReady() {
        return status == Status.READY;
    }
}
