import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.plugin.frame.RoiManager;

import java.awt.Rectangle;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Keeps noisy Fiji windows (Log, ROI Manager) out of the way unless explicitly enabled.
 */
public final class HoloBioFijiUi {

    /** When false, {@link #log} does not call {@link IJ#log} (avoids opening the Log window). */
    public static boolean showFijiLogWindow = false;

    /** When false, ROI pick sessions use a hidden {@link RoiManager} (no manager dialog). */
    public static boolean showRoiManagerWindow = false;

    /**
     * Display zoom for images HoloBio opens in Fiji (0.5 = 50%). Fiji otherwise auto-sizes
     * large windows to ~75% of the screen.
     */
    public static double defaultImageMagnification = 0.5;

    private static Consumer<String> messageSink;

    private HoloBioFijiUi() {
    }

    public static void setMessageSink(Consumer<String> sink) {
        messageSink = sink;
    }

    public static void log(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (messageSink != null) {
            messageSink.accept(message);
        }
        if (showFijiLogWindow) {
            IJ.log(message);
        }
    }

    /** Hidden ROI Manager for multi-ROI pick (press {@code t} after each shape). */
    public static RoiManager roiManagerForPicking() {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            rm = new RoiManager();
        }
        if (!showRoiManagerWindow) {
            rm.setVisible(false);
        }
        return rm;
    }

    public static void hideRoiManager() {
        RoiManager rm = RoiManager.getInstance();
        if (rm != null && !showRoiManagerWindow) {
            rm.setVisible(false);
        }
    }

    /** Opens an image window at {@link #defaultImageMagnification} (50% by default). */
    public static void showImagePlus(ImagePlus imp) {
        showImagePlus(imp, defaultImageMagnification);
    }

    public static void showImagePlus(ImagePlus imp, double magnification) {
        if (imp == null) {
            return;
        }
        imp.show();
        if (!(magnification > 0) || Double.isNaN(magnification)) {
            return;
        }
        SwingUtilities.invokeLater(() -> setDisplayMagnification(imp, magnification));
    }

    /** After {@link ImagePlus#show()}, override Fiji's auto-fit zoom. */
    public static void setDisplayMagnification(ImagePlus imp, double magnification) {
        if (imp == null || !(magnification > 0) || Double.isNaN(magnification)) {
            return;
        }
        java.awt.Window w = imp.getWindow();
        if (!(w instanceof ImageWindow)) {
            return;
        }
        ImageWindow win = (ImageWindow) w;
        ImageCanvas ic = win.getCanvas();
        if (ic == null) {
            return;
        }
        int iw = imp.getWidth();
        int ih = imp.getHeight();
        ic.setSourceRect(new Rectangle(0, 0, iw, ih));
        ic.setMagnification(magnification);
        int cw = (int) Math.round(iw * magnification);
        int ch = (int) Math.round(ih * magnification);
        ic.setSize(Math.max(1, cw), Math.max(1, ch));
        win.pack();
        imp.updateAndDraw();
    }
}
