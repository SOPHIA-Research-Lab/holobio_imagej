/**
 * DLHM reconstruction parameters.
 *
 * Geometry: point source illuminates sample at distance Z (sample-to-source),
 * and the camera captures at distance L (camera-to-source). The reconstruction
 * distance r = L − Z propagates from camera plane to sample plane.
 *
 * <p>All three are stored, and any one of them can be typed: the other two are kept
 * consistent exactly as HoloBio Python does it ({@code main_DLHM_PP.update_L/Z/r}).
 * {@code fixR} does not decide whether r is editable — it decides which value moves
 * when something else changes:
 *
 * <ul>
 *   <li><b>fixR off</b> — r follows L and Z. Editing r moves Z (L is kept).</li>
 *   <li><b>fixR on</b> — r is held. Editing L moves Z; editing Z or r moves L.</li>
 * </ul>
 *
 * <p>Z is allowed to go negative (the sample plane crossing the source), which happens
 * whenever r &gt; L. Python permits it and the magnification goes negative with it, so
 * neither is clamped here.
 */
public final class HoloBioDlhmParams {

    /** Python {@code settings.MIN_DISTANCE} — stands in for Z when it is exactly zero. */
    private static final double MIN_DISTANCE_UM = 0.001;

    private double wavelengthUm = 0.532;
    private double pixelPitchUm = 2.40;

    private double L = 10000.0;
    private double Z = 5000.0;
    private double r = 5000.0;

    private boolean fixR = false;

    private String algorithm = "AS";
    private double cosinePeriod = 100.0;

    private double minL = 0;    private double maxL = 20000;
    private double minZ = 0;    private double maxZ = 20000;
    private double minR = 0;    private double maxR = 20000;

    // --- Getters / setters ---

    public double getWavelengthUm() { return wavelengthUm; }
    public void setWavelengthUm(double v) { wavelengthUm = v; }

    public double getPixelPitchUm() { return pixelPitchUm; }
    public void setPixelPitchUm(double v) { pixelPitchUm = v; }

    public double getL() { return L; }
    public double getZ() { return Z; }
    public double getR() { return r; }

    /** Set L. If fixR: Z becomes L − r. Otherwise Z is capped at L and r re-derives. */
    public void setL(double newL) {
        L = newL;
        if (fixR) {
            Z = L - r;
        } else {
            if (L <= Z) {
                Z = L;
            }
            r = L - Z;
        }
    }

    /** Set Z. If fixR: L becomes Z + r. Otherwise L is raised to Z and r re-derives. */
    public void setZ(double newZ) {
        Z = newZ;
        if (fixR) {
            L = Z + r;
        } else {
            if (Z >= L) {
                L = Z;
            }
            r = L - Z;
        }
    }

    /**
     * Set r. If fixR: L becomes Z + r, because a fixed r means Z is the value the user
     * trusts. Otherwise Z becomes L − r, which may be negative.
     */
    public void setR(double newR) {
        r = newR;
        if (fixR) {
            L = Z + r;
        } else {
            Z = L - r;
        }
    }

    public boolean isFixR() { return fixR; }

    /** Only latches which value moves next; r already tracks L − Z when unfixed. */
    public void setFixR(boolean v) { fixR = v; }

    /** Magnification = L / Z. Negative when Z is, matching Python's {@code scale_factor}. */
    public double getMagnification() {
        return Z != 0.0 ? L / Z : L / MIN_DISTANCE_UM;
    }

    /** Angular-Spectrum scale factor = L / Z. */
    public double getScaleFactor() { return getMagnification(); }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String v) { algorithm = v; }

    public double getCosinePeriod() { return cosinePeriod; }
    public void setCosinePeriod(double v) { cosinePeriod = v; }

    public double getMinL() { return minL; }    public void setMinL(double v) { minL = v; }
    public double getMaxL() { return maxL; }    public void setMaxL(double v) { maxL = v; }
    public double getMinZ() { return minZ; }    public void setMinZ(double v) { minZ = v; }
    public double getMaxZ() { return maxZ; }    public void setMaxZ(double v) { maxZ = v; }
    public double getMinR() { return minR; }    public void setMinR(double v) { minR = v; }
    public double getMaxR() { return maxR; }    public void setMaxR(double v) { maxR = v; }
}
