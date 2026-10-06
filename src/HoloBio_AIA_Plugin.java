import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Everything kept from one AIA run (UP or DOWN set). */
    private static final class SetResult {
        String label;
        List<String> names;
        HoloBioAiaMath.Result res;
        double[] errors;
    }

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
        List<SetResult> sets = new ArrayList<SetResult>();
        SetResult up = runSet("UP", dir, filter, count, guess, customGuess, maxIter);
        if (up == null) {
            return;
        }
        sets.add(up);
        if (runDown) {
            SetResult down = runSet("DOWN", downDir, downFilter, count, guess, customGuess, maxIter);
            if (down == null) {
                return;
            }
            sets.add(down);
        }

        // ---- MATLAB figure(3): blue UP, red DOWN, green -unwrap(mod(UP + DOWN, 2π))
        double[] x = grayLevels(up.names, up.res.phaseShifts.length);
        boolean byGray = x != null;
        if (!byGray) {
            x = new double[up.res.phaseShifts.length];
            for (int k = 0; k < x.length; k++) {
                x[k] = k;
            }
        }
        Plot plot = new Plot("Gray level shift", byGray ? "Gray level" : "Image", "Phase shift (rad)");
        StringBuilder legend = new StringBuilder("UP");
        plot.setColor(Color.BLUE);
        plot.add("connected circle", x, unwrap(up.res.phaseShifts));
        if (sets.size() > 1) {
            double[] u = up.res.phaseShifts;
            double[] d = sets.get(1).res.phaseShifts;
            if (d.length == u.length) {
                plot.setColor(Color.RED);
                plot.add("connected circle", x, unwrap(d));
                double[] sum = new double[u.length];
                for (int k = 0; k < u.length; k++) {
                    sum[k] = mod2pi(u[k] + d[k]);
                }
                sum = unwrap(sum);
                for (int k = 0; k < sum.length; k++) {
                    sum[k] = -sum[k];
                }
                plot.setColor(new Color(0, 160, 0));
                plot.add("connected circle", x, sum);
                legend.append("\nDOWN\n-(UP + DOWN)");
            } else {
                IJ.log("HoloBio AIA: UP has " + u.length + " images and DOWN has " + d.length
                    + "; DOWN curve not plotted.");
            }
        }
        plot.setColor(Color.BLACK);
        plot.addLegend(legend.toString());
        plot.show();

        // ---- Convergence, one curve per set
        Plot conv = new Plot("AIA convergence", "Iteration", "log10(phase-shift change)");
        Color[] colors = {Color.BLUE, Color.RED};
        StringBuilder convLegend = new StringBuilder();
        for (int s = 0; s < sets.size(); s++) {
            SetResult r = sets.get(s);
            double[] its = new double[r.res.iterations];
            double[] logErr = new double[r.res.iterations];
            for (int i = 0; i < its.length; i++) {
                its[i] = i + 1;
                logErr[i] = Math.log10(Math.max(r.errors[i], 1e-300));
            }
            conv.setColor(colors[s]);
            conv.add("connected circle", its, logErr);
            convLegend.append(s > 0 ? "\n" : "").append(r.label);
        }
        conv.setColor(Color.BLACK);
        conv.addLegend(convLegend.toString());
        conv.show();
        IJ.showStatus("AIA done");
    }

    /** Loads one set (UP or DOWN), runs AIA, shows its wrapped phase. Null if something failed. */
    private static SetResult runSet(String label, String dir, String filter, int count,
                                    String guess, String customGuess, int maxIter) {
        // ---- Matching files
        File folder = new File(dir);
        String[] all = folder.list();
        if (all == null) {
            IJ.error("HoloBio AIA", label + ": not a folder: " + dir);
            return null;
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
            return null;
        }

        double[] initialShifts;   // null = PCA
        if (GUESS_RAMP.equals(guess)) {
            initialShifts = new double[count];
            for (int k = 0; k < count; k++) {
                initialShifts[k] = 0.1 * k / (count - 1);   // MATLAB 0.1*(0:1/(n-1):1)
            }
        } else if (GUESS_CUSTOM.equals(guess)) {
            initialShifts = parseShifts(customGuess, count);
            if (initialShifts == null) {
                return null;
            }
        } else {
            initialShifts = null;
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
                return null;
            }
            if (i == 0) {
                width = imp.getWidth();
                height = imp.getHeight();
            } else if (imp.getWidth() != width || imp.getHeight() != height) {
                IJ.error("HoloBio AIA", names.get(i) + " has a different size than " + names.get(0) + ".");
                return null;
            }
            double[] gray = toGray(imp.getProcessor());
            frames[i] = HoloBioAiaMath.normalizeFringes(gray, width, height, NORM_RADIUS);
        }

        // ---- AIA
        final double[] errors = new double[Math.max(1, maxIter)];
        HoloBioAiaMath.Result res;
        try {
            res = HoloBioAiaMath.run(HoloBioAiaMath.fringesVector(frames), initialShifts,
                maxIter, PRECISION, (it, max, err) -> {
                    errors[it - 1] = err;
                    IJ.showStatus("AIA " + label + ": iteration " + it + "/" + max);
                    IJ.showProgress(it, max);
                });
        } catch (RuntimeException e) {
            IJ.error("HoloBio AIA", label + ": AIA failed: " + e.getMessage());
            return null;
        }
        IJ.showProgress(1.0);

        ImagePlus phase = new ImagePlus("AIA wrapped phase (" + label + ")",
            new FloatProcessor(width, height, res.phase));
        phase.resetDisplayRange();
        phase.show();

        SetResult out = new SetResult();
        out.label = label;
        out.names = names;
        out.res = res;
        out.errors = errors;
        return out;
    }

    /** Gray level = first number in each filename (GR_050_2_UP.bmp -> 50); null if a name has none. */
    private static double[] grayLevels(List<String> names, int n) {
        double[] x = new double[n];
        Pattern digits = Pattern.compile("\\d+");
        for (int k = 0; k < n; k++) {
            Matcher m = digits.matcher(names.get(k));
            if (!m.find()) {
                return null;
            }
            x[k] = Integer.parseInt(m.group());
        }
        return x;
    }

    private static double mod2pi(double v) {
        double r = v % (2 * Math.PI);
        return r < 0 ? r + 2 * Math.PI : r;
    }

    /** One number = constant step (0, s, 2s, ...); a list = one shift per image. Null on bad input. */
    private static double[] parseShifts(String text, int count) {
        String[] parts = text.trim().split("[,;\\s]+");
        double[] values = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                values[i] = Double.parseDouble(parts[i]);
            }
        } catch (NumberFormatException e) {
            IJ.error("HoloBio AIA", "Custom initial guess must be numbers in radians: " + text);
            return null;
        }
        if (values.length == 1) {
            double[] shifts = new double[count];
            for (int k = 0; k < count; k++) {
                shifts[k] = k * values[0];
            }
            return shifts;
        }
        if (values.length != count) {
            IJ.error("HoloBio AIA", "Custom initial guess: got " + values.length + " values for " + count
                + " images. Give one value per image, or a single step.");
            return null;
        }
        return values;
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

    /** MATLAB unwrap (tolerance pi */
    private static double[] unwrap(double[] a) {
        double[] out = a.clone();
        double offset = 0;
        for (int k = 1; k < a.length; k++) {
            double d = a[k] - a[k - 1];
            if (d > Math.PI) {
                offset -= 2 * Math.PI * Math.ceil((d - Math.PI) / (2 * Math.PI));
            } else if (d < -Math.PI) {
                offset += 2 * Math.PI * Math.ceil((-d - Math.PI) / (2 * Math.PI));
            }
            out[k] = a[k] + offset;
        }
        return out;
    }
}
