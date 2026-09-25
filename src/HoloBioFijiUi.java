import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.frame.RoiManager;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
     * Upper bound for display zoom (0.5 = 50%). The actual zoom applied by
     * {@link #showImagePlus(ImagePlus)} is the smaller of this value and whatever
     * fits the image within {@link #FIT_FRACTION} of the screen, so large holograms
     * never open bigger than the monitor.
     */
    public static double defaultImageMagnification = 0.5;

    /** Fraction of screen each image window may occupy (width or height). */
    private static final double FIT_FRACTION = 0.42;

    private static Consumer<String> messageSink;

    private HoloBioFijiUi() {
    }

    public static void setMessageSink(Consumer<String> sink) {
        messageSink = sink;
    }

    /**
     * Show an informational dialog using Swing ({@link JOptionPane}). ImageJ's
     * {@code IJ.showMessage} uses a heavyweight AWT {@code MultiLineLabel} that can fail to paint
     * its text (blank body, only the OK button visible) when spawned from our lightweight
     * FlatLaf/Swing windows. A Swing dialog renders reliably and respects HiDPI scaling.
     */
    public static void message(String title, String msg) {
        showSwingDialog(title, msg, javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    public static void message(String msg) {
        message("HoloBio", msg);
    }

    public static void error(String title, String msg) {
        showSwingDialog(title, msg, javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    public static void error(String msg) {
        error("HoloBio", msg);
    }

    private static void showSwingDialog(String title, String msg, int type) {
        String safeTitle = (title == null || title.isEmpty()) ? "HoloBio" : title;
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            IJ.log(safeTitle + ": " + msg);
            return;
        }
        Runnable r = () -> {
            java.awt.Window parent = null;
            try {
                parent = javax.swing.FocusManager.getCurrentManager().getActiveWindow();
            } catch (Throwable ignored) {
            }
            javax.swing.JOptionPane.showMessageDialog(parent, msg, safeTitle, type);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
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

    /**
     * Compute a magnification that keeps the image within {@link #FIT_FRACTION} of
     * the screen, but never exceeds {@link #defaultImageMagnification}.
     */
    public static double computeFitMagnification(int imageWidth, int imageHeight) {
        try {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            double magW = (screen.width  * FIT_FRACTION) / Math.max(1, imageWidth);
            double magH = (screen.height * FIT_FRACTION) / Math.max(1, imageHeight);
            double fit  = Math.min(magW, magH);
            return Math.min(defaultImageMagnification, Math.max(0.0625, fit));
        } catch (Exception e) {
            return defaultImageMagnification;
        }
    }

    /**
     * Show an {@link ImagePlus} the same way File ▸ Open does: a normal Fiji window.
     * The {@code magnification} argument is accepted for source compatibility but the
     * window opens at Fiji's default zoom.
     */
    public static void showImagePlus(ImagePlus imp) {
        showImagePlus(imp, 0);
    }

    public static void showImagePlus(ImagePlus imp, double magnification) {
        if (imp == null || imp.getProcessor() == null) {
            return;
        }
        imp.getProcessor().resetMinAndMax();
        final String title = (imp.getTitle() == null || imp.getTitle().isEmpty())
                ? "HoloBio" : imp.getTitle();
        final ImagePlus toShow = imp;

        Runnable show = () -> {
            ImagePlus existing = WindowManager.getImage(title);
            if (existing != null && existing != toShow) {
                existing.changes = false;
                existing.close();
            }
            toShow.setTitle(title);
            toShow.show();
            toShow.changes = false;
        };

        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    /**
     * Open QPI / Speckle from the plugin JAR even when Fiji defined the caller
     * on the application class loader (which cannot see {@code plugins/*.jar}).
     */
    public static void showQpiDialog(Frame owner, HoloBioToolInputs inputs) {
        invokeToolDialog("showBioAnalysisDialog",
            new Class[] { Frame.class, HoloBioToolInputs.class },
            new Object[] { owner, inputs },
            "QPI");
    }

    public static void showSpeckleDialog(Frame owner, HoloBioToolInputs inputs,
            HoloBioToolCallbacks callbacks) {
        invokeToolDialog("showSpeckleDialog",
            new Class[] { Frame.class, HoloBioToolInputs.class, HoloBioToolCallbacks.class },
            new Object[] { owner, inputs, callbacks },
            "Speckle");
    }

    private static Class<?> toolDialogsClass;
    private static URLClassLoader toolJarLoader;

    private static void invokeToolDialog(String methodName, Class<?>[] types, Object[] args,
            String toolLabel) {
        try {
            Class<?> c = loadToolDialogs();
            Method m = c.getMethod(methodName, types);
            m.invoke(null, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            IJ.error("HoloBio", "Could not open " + toolLabel + ".\n"
                + "Quit Fiji completely (File → Quit) and reopen it after installing HoloBio_.jar.\n\n"
                + cause);
        }
    }

    private static synchronized Class<?> loadToolDialogs() throws ClassNotFoundException {
        if (toolDialogsClass != null) {
            return toolDialogsClass;
        }
        String name = "HoloBioToolDialogs";
        ClassLoader[] loaders = new ClassLoader[] {
            IJ.getClassLoader(),
            HoloBioFijiUi.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            jarLoaderBeside(HoloBioFijiUi.class)
        };
        ClassNotFoundException last = null;
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] == null) {
                continue;
            }
            try {
                toolDialogsClass = loaders[i].loadClass(name);
                return toolDialogsClass;
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ClassNotFoundException(name);
    }

    /** Child loader whose URLs include the JAR that defined {@code peer}. */
    private static ClassLoader jarLoaderBeside(Class<?> peer) {
        if (toolJarLoader != null) {
            return toolJarLoader;
        }
        try {
            URL loc = peer.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            ClassLoader parent = peer.getClassLoader();
            toolJarLoader = new URLClassLoader(new URL[] { loc }, parent);
            return toolJarLoader;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
