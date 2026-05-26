/**
 * DHM-oriented acquisition parameters shared across camera backends.
 * Pixel pitch fields align with {@link HoloBioDhmParams} (µm).
 */
public class HoloBioAcquisitionSettings {

    private HoloBioCameraKind backend = HoloBioCameraKind.ACTIVE_IMAGE;
    private int widthPx;
    private int heightPx;
    private double exposureMs = 10.0;
    private double pixelPitchXUm = 2.40;
    private double pixelPitchYUm = 2.40;
    private boolean livePreview;

    public HoloBioCameraKind getBackend() {
        return backend;
    }

    public void setBackend(HoloBioCameraKind backend) {
        this.backend = backend != null ? backend : HoloBioCameraKind.ACTIVE_IMAGE;
    }

    public int getWidthPx() {
        return widthPx;
    }

    public void setWidthPx(int widthPx) {
        this.widthPx = Math.max(0, widthPx);
    }

    public int getHeightPx() {
        return heightPx;
    }

    public void setHeightPx(int heightPx) {
        this.heightPx = Math.max(0, heightPx);
    }

    public double getExposureMs() {
        return exposureMs;
    }

    public void setExposureMs(double exposureMs) {
        this.exposureMs = exposureMs;
    }

    public double getPixelPitchXUm() {
        return pixelPitchXUm;
    }

    public void setPixelPitchXUm(double pixelPitchXUm) {
        this.pixelPitchXUm = pixelPitchXUm;
    }

    public double getPixelPitchYUm() {
        return pixelPitchYUm;
    }

    public void setPixelPitchYUm(double pixelPitchYUm) {
        this.pixelPitchYUm = pixelPitchYUm;
    }

    public boolean isLivePreview() {
        return livePreview;
    }

    public void setLivePreview(boolean livePreview) {
        this.livePreview = livePreview;
    }

    public void applyTo(HoloBioDhmParams params) {
        if (params == null) {
            return;
        }
        params.setPixelPitchXUm(pixelPitchXUm);
        params.setPixelPitchYUm(pixelPitchYUm);
    }

    public void copyFrom(HoloBioDhmParams params) {
        if (params == null) {
            return;
        }
        pixelPitchXUm = params.getPixelPitchXUm();
        pixelPitchYUm = params.getPixelPitchYUm();
    }
}
