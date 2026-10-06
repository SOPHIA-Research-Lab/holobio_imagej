import com.github.sarxos.webcam.Webcam;
import ij.ImagePlus;
import ij.process.ColorProcessor;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * UVC / webcam backend using the Sarxos webcam-capture library.
 * Replaces for {@link HoloBioCameraKind#UVC_WEBCAM} in the registry.
 */
public class HoloBioWebcamBackend implements HoloBioCameraBackend {

    private volatile Webcam webcam;
    private volatile boolean liveRunning;
    private Thread liveThread;

    @Override
    public HoloBioCameraKind getKind() {
        return HoloBioCameraKind.UVC_WEBCAM;
    }

    @Override
    public HoloBioCameraAvailability probe() {
        try {
            List<Webcam> cams = Webcam.getWebcams(2000);
            if (cams.isEmpty()) {
                return HoloBioCameraAvailability.notImplemented(
                        "No UVC webcams detected on this system.", null);
            }
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < cams.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(cams.get(i).getName());
            }
            return HoloBioCameraAvailability.ready(
                    cams.size() + " webcam(s): " + names);
        } catch (Exception e) {
            return new HoloBioCameraAvailability(
                    HoloBioCameraAvailability.Status.ERROR,
                    "Webcam failed: " + e.getMessage(), null);
        }
    }

    @Override
    public synchronized void connect(HoloBioAcquisitionSettings settings) throws Exception {
        if (webcam != null && webcam.isOpen()) return;
        webcam = Webcam.getDefault();
        if (webcam == null) throw new Exception("No webcam found");
        if (settings != null && settings.getWidthPx() > 0) {
            Dimension d = new Dimension(settings.getWidthPx(), settings.getHeightPx());
            webcam.setCustomViewSizes(d);
            webcam.setViewSize(d);
        }
        webcam.open();
    }

    @Override
    public synchronized void disconnect() {
        stopLive();
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
        }
        webcam = null;
    }

    @Override
    public synchronized boolean isConnected() {
        return webcam != null && webcam.isOpen();
    }

    @Override
    public synchronized ImagePlus snapFrame(HoloBioAcquisitionSettings settings) throws Exception {
        if (!isConnected()) connect(settings);
        BufferedImage img = webcam.getImage();
        if (img == null) return null;
        return new ImagePlus("Webcam Snap", new ColorProcessor(img));
    }

    @Override
    public void startLive(HoloBioAcquisitionSettings settings, HoloBioCameraFrameListener listener) {
        try {
            if (!isConnected()) connect(settings);
        } catch (Exception e) {
            listener.onAcquisitionLog("Connect failed: " + e.getMessage());
            return;
        }
        liveRunning = true;
        liveThread = new Thread(() -> {
            while (liveRunning) {
                try {
                    BufferedImage img = webcam.getImage();
                    if (img != null) {
                        ImagePlus frame = new ImagePlus("Live", new ColorProcessor(img));
                        listener.onFrame(frame, settings);
                    }
                    Thread.sleep(33);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    listener.onAcquisitionLog("Frame: " + ex.getMessage());
                }
            }
        }, "HoloBio-webcam-live");
        liveThread.setDaemon(true);
        liveThread.start();
    }

    @Override
    public void stopLive() {
        liveRunning = false;
        if (liveThread != null) {
            liveThread.interrupt();
            try { liveThread.join(500); } catch (InterruptedException ignored) {}
            liveThread = null;
        }
    }

    /** Returns available webcam devices for a device-picker UI. */
    public List<Webcam> getAvailableWebcams() {
        try {
            return Webcam.getWebcams(2000);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Open a specific named webcam (by index in the device list).
     * Used by {@link HoloBioCameraTestWindow} device selector.
     */
    public synchronized void connectIndex(int index) throws Exception {
        connectIndex(index, null);
    }

    /**
     * Open webcam {@code index} at {@code size}, or at the library default when null.
     *
     * <p>Sarxos opens at a small standard size (often 640×480) unless told otherwise, even on a
     * 1280×960 sensor. For holography that is not cosmetic: the camera skips or bins pixels,
     * the fringes alias, and the +1 order disappears. Callers should pass the sensor's mode.
     */
    public synchronized void connectIndex(int index, Dimension size) throws Exception {
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
            webcam = null;
        }
        List<Webcam> cams = Webcam.getWebcams(2000);
        if (cams.isEmpty()) throw new Exception("No webcams found.");
        webcam = cams.get(Math.min(index, cams.size() - 1));
        if (size != null) {
            webcam.setCustomViewSizes(size);   // sarxos only accepts sizes it was told about
            webcam.setViewSize(size);
        }
        webcam.open();
    }

    /** Grab the latest frame as a BufferedImage (for direct UI use). */
    public BufferedImage grabImage() {
        Webcam w = webcam;
        if (w == null || !w.isOpen()) return null;
        return w.getImage();
    }

    /**
     * Nominal capture rate of the open device, or {@code fallback} when the driver does not
     * report a usable one. Sarxos only measures a rate once frames have been pulled, so this
     * reads as 0 immediately after opening.
     */
    public double getNominalFps(double fallback) {
        Webcam w = webcam;
        if (w == null || !w.isOpen()) return fallback;
        try {
            double fps = w.getFPS();
            if (fps > 0 && fps <= 240) return fps;
        } catch (Exception ignored) {
            // Some drivers do not implement the query at all
        }
        return fallback;
    }

    /** Webcam image size as opened, or null if not connected. */
    public Dimension getOpenedSize() {
        Webcam w = webcam;
        if (w == null || !w.isOpen()) return null;
        return w.getViewSize();
    }
}
