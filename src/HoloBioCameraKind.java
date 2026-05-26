/**
 * Supported acquisition backends for DHM / DLHM in Fiji.
 * <p>
 * {@link #MICRO_MANAGER} is the primary integration path (Hamamatsu, Andor, PCO, Basler, etc.).
 * Vendor-specific ImageJ plugins are listed for optional direct bridges later.
 */
public enum HoloBioCameraKind {
    ACTIVE_IMAGE(
        "Active Image",
        "Use the current Fiji window as the hologram source (offline / file workflow).",
        null,
        null),
    MICRO_MANAGER(
        "Micro-Manager",
        "Scientific cameras via MMCore (Hamamatsu DCAM, Andor, PCO, Basler, …).",
        "org.micromanager.plugin.MMPlugin",
        "Micro-Manager > Open Micro-Manager"),
    IDS_U3(
        "IDS uEye (HF_IDS_Cam)",
        "IDS Imaging cameras (common in DLHM setups); HF_IDS_Cam Fiji plugin.",
        null,
        "Plugins > HF_IDS_Cam"),
    BASLER(
        "Basler",
        "Basler cameras via Basler pylon / Micro-Manager Basler adapter.",
        null,
        "Micro-Manager (Basler device) or Basler pylon plugin"),
    HAMAMATSU(
        "Hamamatsu",
        "Orca / Flash series via HamamatsuHam (DCAM-API) in Micro-Manager.",
        null,
        "Micro-Manager > Hardware Configuration (HamamatsuHam)"),
    ANDOR(
        "Andor",
        "Andor EMCCD / sCMOS via Micro-Manager Andor adapter.",
        null,
        "Micro-Manager > Hardware Configuration (Andor)"),
    PCO(
        "PCO",
        "PCO.cam via Micro-Manager PCO adapter.",
        null,
        "Micro-Manager > Hardware Configuration (PCO)"),
    UVC_WEBCAM(
        "UVC / Webcam",
        "USB cameras (OpenCV-style); Python HoloBio realtime fallback.",
        null,
        "Image > Capture > … (platform) — bridge not implemented yet");

    private final String displayName;
    private final String description;
    /** Optional class used to detect an installed Fiji/Micro-Manager component. */
    private final String probeClassName;
    private final String fijiMenuHint;

    HoloBioCameraKind(String displayName, String description, String probeClassName, String fijiMenuHint) {
        this.displayName = displayName;
        this.description = description;
        this.probeClassName = probeClassName;
        this.fijiMenuHint = fijiMenuHint;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getProbeClassName() {
        return probeClassName;
    }

    public String getFijiMenuHint() {
        return fijiMenuHint;
    }
}
