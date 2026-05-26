/**
 * User-tunable phase-compensation options aligned with HoloBio Python
 * {@code settingsCompensation.create_compensation_settings} (Semi-Heuristic, Tu-DHM, Vortex Legendre).
 */
public final class HoloBioCompensationSettings implements Cloneable {

    /** ERS / inner-ERS search span (Python: odd positive integer). */
    private int ersSearchSize = 5;
    /** ERS refinement step in frequency units (Python: strictly between 0 and 1). */
    private double ersStep = 0.2;
    /** CFS bounds half-width in FFT pixels (Python Tu-DHM "step"). */
    private double cfsStepWindow = 2.0;
    /** CFS grid samples per axis (Java grid search; Python uses SciPy on a bounded region). */
    private int cfsGridSize = 17;
    /** Stored for parity with Python UI; Java CFS does not call SciPy optimizers. */
    private String cfsOptimizerLabel = "TNC";
    /** Legendre spectral crop half-size ({@code pyDHM_methods.legendre_compensation} limit). */
    private int vlLimit = 256;
    /** Matches Python compensation settings dialog default ({@code init_piston=True}). */
    private boolean vlPiston = true;
    /** Matches Python PP run ({@code getattr(self, "vl_pca", True)}). */
    private boolean vlPca = true;

    public int getErsSearchSize() {
        return ersSearchSize;
    }

    public void setErsSearchSize(int ersSearchSize) {
        this.ersSearchSize = ersSearchSize;
    }

    public double getErsStep() {
        return ersStep;
    }

    public void setErsStep(double ersStep) {
        this.ersStep = ersStep;
    }

    public double getCfsStepWindow() {
        return cfsStepWindow;
    }

    public void setCfsStepWindow(double cfsStepWindow) {
        this.cfsStepWindow = cfsStepWindow;
    }

    public int getCfsGridSize() {
        return cfsGridSize;
    }

    public void setCfsGridSize(int cfsGridSize) {
        this.cfsGridSize = cfsGridSize;
    }

    public String getCfsOptimizerLabel() {
        return cfsOptimizerLabel;
    }

    public void setCfsOptimizerLabel(String cfsOptimizerLabel) {
        this.cfsOptimizerLabel = cfsOptimizerLabel != null ? cfsOptimizerLabel : "TNC";
    }

    public int getVlLimit() {
        return vlLimit;
    }

    public void setVlLimit(int vlLimit) {
        this.vlLimit = vlLimit;
    }

    public boolean isVlPiston() {
        return vlPiston;
    }

    public void setVlPiston(boolean vlPiston) {
        this.vlPiston = vlPiston;
    }

    public boolean isVlPca() {
        return vlPca;
    }

    public void setVlPca(boolean vlPca) {
        this.vlPca = vlPca;
    }

    /**
     * Maps Python "Limit" dropdown (64…1024) to Java standby Legendre-like surface order (2…6).
     */
    public static int vlLimitToPolyOrder(int limit) {
        if (limit <= 64) {
            return 2;
        }
        if (limit <= 128) {
            return 3;
        }
        if (limit <= 256) {
            return 4;
        }
        if (limit <= 512) {
            return 5;
        }
        return 6;
    }

    @Override
    public HoloBioCompensationSettings clone() {
        try {
            return (HoloBioCompensationSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
