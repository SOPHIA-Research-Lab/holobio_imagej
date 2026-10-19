import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AIA test module: loads the matching images from a folder and runs AIA on them.
 */
public class HoloBio_AIA_Plugin implements PlugIn {

    private static final String GUESS_PCA = "PCA (SVD)";
    private static final String GUESS_RAMP = "Linear ramp";
    private static final String GUESS_CUSTOM = "Custom (enter below)";

    // Fixed parameters (same as readInterferogramsV2.m)
    private static final double NORM_RADIUS = 5;
    private static final double PRECISION = 1e-15;

    // Remembered for the session
    private static String lastDir = "";
    private static String lastFilter = "";
    private static boolean lastRunDown = false;
    private static String lastDownDir = "";
    private static String lastDownFilter = "";
    private static int lastCount = 0;
    private static String lastGuess = GUESS_PCA;
    private static String lastCustomGuess = "1.5708";
    private static int lastMaxIter = 100;

    @Override
    public void run(String arg) {
        // ---- Dialog
        GenericDialog gd = new GenericDialog("HoloBio AIA");
        gd.addDirectoryField("UP folder", lastDir);
        gd.addStringField("UP filename contains", lastFilter, 20);
        gd.addCheckbox("Also run DOWN (reference) set", lastRunDown);
        gd.addDirectoryField("DOWN folder", lastDownDir);
        gd.addStringField("DOWN filename contains", lastDownFilter, 20);
        gd.addNumericField("Number of images (0 = all)", lastCount, 0);
        gd.addChoice("Initial guess", new String[] {GUESS_PCA, GUESS_RAMP, GUESS_CUSTOM}, lastGuess);
        gd.addStringField("Custom initial guess (rad)", lastCustomGuess, 25);
        gd.addMessage("Custom initial guess: one number = phase step between images (e.g. 1.5708 = π/2),\n"
            + "or one phase shift per image separated by commas.");
        gd.addNumericField("Max iterations", lastMaxIter, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        String dir = gd.getNextString();
        String filter = gd.getNextString();
        boolean runDown = gd.getNextBoolean();
        String downDir = gd.getNextString();
        String downFilter = gd.getNextString();
        int count = (int) gd.getNextNumber();
        String guess = gd.getNextChoice();
        String customGuess = gd.getNextString();
        int maxIter = (int) gd.getNextNumber();
        lastDir = dir;
        lastFilter = filter;
        lastRunDown = runDown;
        lastDownDir = downDir;
        lastDownFilter = downFilter;
        lastCount = count;
        lastGuess = guess;
        lastCustomGuess = customGuess;
        lastMaxIter = maxIter;

        // ---- Run AIA on each set
        if (!runSet("UP", dir, filter, count, guess, customGuess, maxIter)) {
            return;
        }
        if (runDown) {
            runSet("DOWN", downDir, downFilter, count, guess, customGuess, maxIter);
        }
    }

    /** Loads one set (UP or DOWN), runs AIA, shows its wrapped phase. False if something failed. */
    private static boolean runSet(String label, String dir, String filter, int count,
                                  String guess, String customGuess, int maxIter) {
        // ---- Matching files
        File folder = new File(dir);
        String[] all = folder.list();
        if (all == null) {
            IJ.error("HoloBio AIA", label + ": not a folder: " + dir);
            return false;
        }
        Arrays.sort(all);
        List<String> names = new ArrayList<String>();
        for (String name : all) {
            String low = name.toLowerCase();
            boolean image = low.endsWith(".bmp") || low.endsWith(".tif") || low.endsWith(".tiff")
                || low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg");
            if (image && !name.startsWith("._") && name.contains(filter)) {
                names.add(name);
            }
        }
        int total = names.size();
        if (count == 0) {
            count = total;
        }
        if (count < 3 || count > total) {
            IJ.error("HoloBio AIA", label + ": found " + total + " matching images; number of images must be between 3 and "
                + total + ".");
            return false;
        }

        double[] initialShifts = null;   // null = PCA
        if (GUESS_RAMP.equals(guess)) {
            initialShifts = HoloBioAiaMath.rampGuess(count);
        } else if (GUESS_CUSTOM.equals(guess)) {
            try {
                initialShifts = HoloBioAiaMath.parseGuess(customGuess, count);
            } catch (IllegalArgumentException e) {
                IJ.error("HoloBio AIA", e.getMessage());
                return false;
            }
        }

        // ---- Load, gray, normalize the first `count` files
        double[][] frames = new double[count][];
        int width = 0, height = 0;
        for (int i = 0; i < count; i++) {
            IJ.showStatus("AIA " + label + ": loading " + (i + 1) + "/" + count);
            IJ.showProgress(i, count);
            ImagePlus imp = IJ.openImage(new File(folder, names.get(i)).getPath());
            if (imp == null) {
                IJ.error("HoloBio AIA", "Could not open " + names.get(i));
                return false;
            }
            if (i == 0) {
                width = imp.getWidth();
                height = imp.getHeight();
            } else if (imp.getWidth() != width || imp.getHeight() != height) {
                IJ.error("HoloBio AIA", names.get(i) + " has a different size than " + names.get(0) + ".");
                return false;
            }
            double[] gray = toGray(imp.getProcessor());
            frames[i] = HoloBioAiaMath.normalizeFringes(gray, width, height, NORM_RADIUS);
        }

        // ---- AIA
        HoloBioAiaMath.Result res;
        try {
            res = HoloBioAiaMath.run(HoloBioAiaMath.fringesVector(frames), initialShifts,
                maxIter, PRECISION, (it, max, err) -> {
                    IJ.showStatus("AIA " + label + ": iteration " + it + "/" + max);
                    IJ.showProgress(it, max);
                });
        } catch (RuntimeException e) {
            IJ.error("HoloBio AIA", label + ": AIA failed: " + e.getMessage());
            return false;
        }
        IJ.showProgress(1.0);
        IJ.showStatus("AIA " + label + ": done after " + res.iterations + " iterations");

        ImagePlus phase = new ImagePlus("AIA wrapped phase (" + label + ")",
            new FloatProcessor(width, height, res.phase));
        phase.resetDisplayRange();
        phase.show();
        return true;
    }

    /** like MATLAB rgb2gray (rounded to uint8) for RGB; raw values otherwise. */
    private static double[] toGray(ImageProcessor ip) {
        int n = ip.getWidth() * ip.getHeight();
        double[] out = new double[n];
        if (ip instanceof ColorProcessor) {
            int[] px = (int[]) ip.getPixels();
            for (int p = 0; p < n; p++) {
                int c = px[p];
                double g = 0.298936021293775 * ((c >> 16) & 0xff)
                    + 0.587043074451121 * ((c >> 8) & 0xff)
                    + 0.114020904255103 * (c & 0xff);
                out[p] = Math.round(g);
            }
        } else {
            for (int p = 0; p < n; p++) {
                out[p] = ip.getf(p);
            }
        }
        return out;
    }
}
