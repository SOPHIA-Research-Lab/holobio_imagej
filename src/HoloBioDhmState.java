import ij.ImagePlus;

public class HoloBioDhmState {
    private ImagePlus hologramImage;
    private ImagePlus fourierImage;
    private ImagePlus amplitudeImage;
    private ImagePlus phaseImage;

    public ImagePlus getHologramImage() {
        return hologramImage;
    }

    public void setHologramImage(ImagePlus hologramImage) {
        this.hologramImage = hologramImage;
    }

    public ImagePlus getFourierImage() {
        return fourierImage;
    }

    public void setFourierImage(ImagePlus fourierImage) {
        this.fourierImage = fourierImage;
    }

    public ImagePlus getAmplitudeImage() {
        return amplitudeImage;
    }

    public void setAmplitudeImage(ImagePlus amplitudeImage) {
        this.amplitudeImage = amplitudeImage;
    }

    public ImagePlus getPhaseImage() {
        return phaseImage;
    }

    public void setPhaseImage(ImagePlus phaseImage) {
        this.phaseImage = phaseImage;
    }

    public boolean hasHologram() {
        return hologramImage != null;
    }
}
