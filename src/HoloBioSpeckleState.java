import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Speckle filter and comparison state (Python {@code speckle_iterations},
 * {@code filtered_amp_array}, {@code speckle_region_coords_*}).
 */
public final class HoloBioSpeckleState {

    private float[] originalFieldRe;
    private float[] originalFieldIm;
    private float[] originalAmplitude0255;
    private float[] originalPhase0255;
    private float[] filteredAmplitude0255;
    private float[] filteredPhase0255;

    /** {@code "hmf"} or {@code "spp"}; null if no iterative filter applied. */
    private String iterationsType;
    /** HMF: one float[] per iteration (includes index 0 = copy before loop). */
    private List<float[]> hmfIterations = Collections.emptyList();
    /** SPP: complex field per iteration (re/im same length as field). */
    private List<float[]> sppReIterations = Collections.emptyList();
    private List<float[]> sppImIterations = Collections.emptyList();

    private int fieldW;
    private int fieldH;

    /** ROI for speckle contrast plot: xStart, xEnd, yStart, yEnd (exclusive end). */
    private int plotXStart = -1;
    private int plotXEnd = -1;
    private int plotYStart = -1;
    private int plotYEnd = -1;

    public void setFieldSize(int w, int h) {
        fieldW = w;
        fieldH = h;
    }

    public int getFieldW() { return fieldW; }
    public int getFieldH() { return fieldH; }

    public void clear() {
        fieldW = 0;
        fieldH = 0;
        originalFieldRe = null;
        originalFieldIm = null;
        originalAmplitude0255 = null;
        originalPhase0255 = null;
        filteredAmplitude0255 = null;
        filteredPhase0255 = null;
        iterationsType = null;
        hmfIterations = Collections.emptyList();
        sppReIterations = Collections.emptyList();
        sppImIterations = Collections.emptyList();
        plotXStart = plotXEnd = plotYStart = plotYEnd = -1;
    }

    public void clearIterations() {
        iterationsType = null;
        hmfIterations = Collections.emptyList();
        sppReIterations = Collections.emptyList();
        sppImIterations = Collections.emptyList();
        plotXStart = plotXEnd = plotYStart = plotYEnd = -1;
    }

    public void setOriginalField(float[] re, float[] im) {
        if (re != null && im != null) {
            originalFieldRe = re.clone();
            originalFieldIm = im.clone();
        }
    }

    public float[] getOriginalFieldRe() { return originalFieldRe; }
    public float[] getOriginalFieldIm() { return originalFieldIm; }

    public void ensureOriginalAmplitude(float[] snapshot) {
        if (originalAmplitude0255 == null && snapshot != null) {
            originalAmplitude0255 = snapshot.clone();
        }
    }

    public void ensureOriginalPhase(float[] snapshot) {
        if (originalPhase0255 == null && snapshot != null) {
            originalPhase0255 = snapshot.clone();
        }
    }

    public void setFilteredAmplitude(float[] filtered) {
        filteredAmplitude0255 = filtered != null ? filtered.clone() : null;
    }

    public void setFilteredPhase(float[] filtered) {
        filteredPhase0255 = filtered != null ? filtered.clone() : null;
    }

    public void setHmfIterations(List<float[]> iterations, boolean amplitudeChannel) {
        iterationsType = "hmf";
        hmfIterations = iterations != null ? new ArrayList<>(iterations) : Collections.emptyList();
        sppReIterations = Collections.emptyList();
        sppImIterations = Collections.emptyList();
        plotXStart = plotXEnd = plotYStart = plotYEnd = -1;
    }

    public void setSppIterations(List<float[]> reList, List<float[]> imList) {
        iterationsType = "spp";
        hmfIterations = Collections.emptyList();
        sppReIterations = reList != null ? new ArrayList<>(reList) : Collections.emptyList();
        sppImIterations = imList != null ? new ArrayList<>(imList) : Collections.emptyList();
        plotXStart = plotXEnd = plotYStart = plotYEnd = -1;
    }

    public void setPlotRegion(int xStart, int xEnd, int yStart, int yEnd) {
        plotXStart = xStart;
        plotXEnd = xEnd;
        plotYStart = yStart;
        plotYEnd = yEnd;
    }

    public boolean hasPlotRegion() {
        return plotXEnd > plotXStart && plotYEnd > plotYStart;
    }

    public int getPlotXStart() { return plotXStart; }
    public int getPlotXEnd() { return plotXEnd; }
    public int getPlotYStart() { return plotYStart; }
    public int getPlotYEnd() { return plotYEnd; }

    public String getIterationsType() { return iterationsType; }

    public List<float[]> getHmfIterations() { return hmfIterations; }

    public List<float[]> getSppReIterations() { return sppReIterations; }
    public List<float[]> getSppImIterations() { return sppImIterations; }

    public boolean hasIterations() {
        return "hmf".equals(iterationsType) && !hmfIterations.isEmpty()
            || "spp".equals(iterationsType) && !sppReIterations.isEmpty();
    }

    public float[] getOriginal(boolean amplitudeChannel) {
        return amplitudeChannel ? originalAmplitude0255 : originalPhase0255;
    }

    public float[] getFiltered(boolean amplitudeChannel) {
        return amplitudeChannel ? filteredAmplitude0255 : filteredPhase0255;
    }

    public boolean hasFiltered(boolean amplitudeChannel) {
        return getFiltered(amplitudeChannel) != null;
    }
}
