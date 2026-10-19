import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Builds synthetic off-axis holograms, runs the RT pipeline, and reports whether the
 * reconstructed amplitude still carries the carrier fringes (which means the spatial filter
 * failed to isolate a single diffraction order).
 */
public class FtDump {

    /**
     * Off-axis hologram: |R + O|² with a smooth object and a tilted reference.
     * {@code fx},{@code fy} are carrier frequencies in cycles per pixel.
     */
    static float[] hologram(int rows, int cols, double fx, double fy) {
        return hologram(rows, cols, fx, fy, 0);
    }

    /**
     * Off-axis hologram: |R + O|² with a smooth object and a tilted reference.
     * {@code fx},{@code fy} are carrier frequencies in cycles per pixel.
     *
     * <p>{@code checker} adds a near-Nyquist pattern at 0.47 cyc/px on both axes, standing in
     * for the MJPEG blocking / demosaic artifact a webcam puts into every frame. Sitting just
     * below Nyquist rather than exactly on it, it appears as four separate bright spots near
     * the corners of the centred spectrum — which is what the live FT shows.
     */
    static float[] hologram(int rows, int cols, double fx, double fy, double checker) {
        float[] h = new float[rows * cols];
        double sig = rows / 5.0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double rr = (r - rows / 2.0) / sig, cc = (c - cols / 2.0) / sig;
                double env = Math.exp(-(rr * rr + cc * cc) / 2);
                double objPhase = 2.5 * env * Math.cos(3 * rr) ;
                double objAmp = 0.35 + 0.5 * env;
                double carrier = 2 * Math.PI * (fx * c + fy * r);
                double v = 1 + objAmp * objAmp + 2 * objAmp * Math.cos(carrier - objPhase);
                v += checker * Math.cos(2 * Math.PI * 0.47 * (c + r));
                h[r * cols + c] = (float) (40 * v);
            }
        }
        return h;
    }

    /** Ground-truth object phase, matching the {@code objPhase} term used to build the hologram. */
    static float[] objectPhase(int rows, int cols) {
        float[] p = new float[rows * cols];
        double sig = rows / 5.0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double rr = (r - rows / 2.0) / sig, cc = (c - cols / 2.0) / sig;
                double env = Math.exp(-(rr * rr + cc * cc) / 2);
                p[r * cols + c] = (float) (2.5 * env * Math.cos(3 * rr));
            }
        }
        return p;
    }

    /**
     * Pearson correlation between the reconstructed phase and the truth, over the centre-cropped
     * region the pipeline returns. Scale-free, so the 0-255 display stretch does not matter;
     * the conjugate order simply flips the sign, hence the caller takes the absolute value.
     */
    static double phaseCorrelation(float[] recon, int w, int h, int srcRows, int srcCols,
                                   float[] truth, int tw) {
        int oy = (srcRows - h) / 2 + (truth.length / tw - srcRows) / 2;
        int ox = (tw - w) / 2;
        double sa = 0, sb = 0;
        int n = w * h;
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                sa += recon[r * w + c];
                sb += truth[(r + oy) * tw + c + ox];
            }
        double ma = sa / n, mb = sb / n;
        double num = 0, va = 0, vb = 0;
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                double da = recon[r * w + c] - ma;
                double db = truth[(r + oy) * tw + c + ox] - mb;
                num += da * db; va += da * da; vb += db * db;
            }
        return num / (Math.sqrt(va * vb) + 1e-12);
    }

    /**
     * Fringe contrast of a field, measured as the energy fraction sitting away from DC.
     * A cleanly filtered amplitude is smooth, so this stays small; leftover carrier pushes it up.
     */
    static double fringeIndex(float[] img, int w, int h) {
        double mean = 0;
        for (int i = 0; i < w * h; i++) mean += img[i];
        mean /= (w * h);
        // Energy in pixel-to-pixel differences relative to overall spread
        double diff = 0, var = 0;
        for (int r = 1; r < h; r++) {
            for (int c = 1; c < w; c++) {
                double d1 = img[r * w + c] - img[r * w + c - 1];
                double d2 = img[r * w + c] - img[(r - 1) * w + c];
                diff += d1 * d1 + d2 * d2;
            }
        }
        for (int i = 0; i < w * h; i++) { double d = img[i] - mean; var += d * d; }
        return Math.sqrt(diff / (2.0 * var + 1e-12));
    }

    static void run(String tag, int rows, int cols, double fx, double fy) throws Exception {
        run(tag, rows, cols, fx, fy, 0);
    }

    /** A per-pixel phase-step error, expressed as whole fringes of ramp across {@code span} px. */
    static double tiltFringes(double radPerPx, int span) {
        double wrapped = Math.IEEEremainder(radPerPx, 2 * Math.PI);
        return Math.abs(wrapped) * span / (2 * Math.PI);
    }

    static void run(String tag, int rows, int cols, double fx, double fy, double checker)
            throws Exception {
        float[] holo = hologram(rows, cols, fx, fy, checker);
        HoloBioRtDhmMath.resetSideband();
        HoloBioRtDhmMath.Frame f = HoloBioRtDhmMath.process(
                holo, rows, cols, 0.532, 5.2, 5.2,
                HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, true, true, true, true);

        int gR = HoloBioRtDhmMath.fitPowerOfTwo(rows), gC = HoloBioRtDhmMath.fitPowerOfTwo(cols);
        // The order carried by exp(i·2π·f·x) lands at bin f·N, measured from DC at N/2.
        int expCol = (int) Math.round(gC / 2.0 + fx * gC);
        int expRow = (int) Math.round(gR / 2.0 + fy * gR);
        int gotCol = HoloBioRtDhmMath.sidebandCol(), gotRow = HoloBioRtDhmMath.sidebandRow();
        double err = Math.hypot(gotCol - expCol, gotRow - expRow);

        double fi = fringeIndex(f.amplitude, f.width, f.height);

        // Residual tilt: how far the fitted carrier misses the true one, expressed as whole
        // fringes of leftover ramp across the field. This is the quantity that wrecks phase,
        // and the integer-bin reference wave could not drive it below ~1.
        double trueGx = 2 * Math.PI * fx, trueGy = 2 * Math.PI * fy;
        double resid = Math.min(
                tiltFringes(HoloBioRtDhmMath.carrierX() - trueGx, f.width)
                        + tiltFringes(HoloBioRtDhmMath.carrierY() - trueGy, f.height),
                tiltFringes(HoloBioRtDhmMath.carrierX() + trueGx, f.width)
                        + tiltFringes(HoloBioRtDhmMath.carrierY() + trueGy, f.height));

        double corr = Math.abs(phaseCorrelation(f.phase, f.width, f.height, rows, cols,
                objectPhase(rows, cols), cols));

        System.out.printf("%-11s (%3d,%3d) got (%3d,%3d) err %4.1f | r %2d | prom %5.1fx | "
                        + "fringe %.2f->%.2f %-7s | tilt %5.2f fr | phase r=%.3f %s%n",
                tag, expCol, expRow, gotCol, gotRow, err,
                HoloBioRtDhmMath.sidebandRadius(),
                HoloBioRtDhmMath.sidebandProminence(),
                fringeIndex(holo, cols, rows), fi,
                fi < 0.25 ? "SMOOTH" : "FRINGED",
                resid, corr,
                corr > 0.9 ? "GOOD" : "BAD <--");

        write(f.ft, f.ftWidth, f.ftHeight, "bench/ft_" + tag + ".png");
        write(f.amplitude, f.width, f.height, "bench/amp_" + tag + ".png");
        write(f.phase, f.width, f.height, "bench/phase_" + tag + ".png");
    }

    public static void main(String[] args) throws Exception {
        // Negative fx puts the order in the left half-plane, which is where Python looks.
        run("coarse8px", 480, 640, -0.088, -0.088);  // ~8 px diagonal fringes, like the screenshot
        run("medium",    480, 640, -0.18,  -0.11);
        run("fine",      480, 640, -0.30,  -0.20);
        run("lowcarrier",480, 640, -0.055, -0.055);  // ~13 px fringes, close to the DC guard
        // Tilt the other way: the conjugate order is picked, which is equally valid.
        run("mirrored",  480, 640, +0.088, +0.088);
        // Sensor/codec checkerboard at Nyquist, increasingly strong relative to the fringes.
        run("checker02", 480, 640, -0.088, -0.088, 0.02);
        run("checker10", 480, 640, -0.088, -0.088, 0.10);
        run("checker30", 480, 640, -0.088, -0.088, 0.30);
        // No carrier at all — an ordinary camera view. Detection must own up to it rather
        // than latching onto the shoulder of the DC lobe.
        runScene("plainscene", 480, 640);
        // A rough sample: the object spectrum is broadband and fills the mask instead of
        // sitting compactly at its centre, which is the case the smooth objects above miss.
        runSpeckle("speckle", 480, 640, -0.18, -0.11);
        // Residual ramp and curvature riding under the sample, as the optics produce. This is
        // the banded-stripe failure; the fit must flatten it without eating the sample.
        runAberrated("aberrated", 480, 640, -0.18, -0.11);
        runFtViews("ftview", 480, 640, -0.18, -0.11);
    }

    /** Unfiltered vs filtered spectrum views, checking neither degenerates into flat grey. */
    static void runFtViews(String tag, int rows, int cols, double fx, double fy)
            throws Exception {
        float[] holo = hologram(rows, cols, fx, fy);
        for (boolean filtered : new boolean[] { false, true }) {
            HoloBioRtDhmMath.setFtFiltered(filtered);
            HoloBioRtDhmMath.resetSideband();
            HoloBioRtDhmMath.Frame f = HoloBioRtDhmMath.process(holo, rows, cols, 0.532, 2, 2,
                    HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, true, true, false, false);

            double mean = 0, lo = 1e9, hi = -1e9;
            int nonZero = 0;
            for (float v : f.ft) {
                mean += v;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
                if (v > 8) nonZero++;
            }
            mean /= f.ft.length;
            double frac = 100.0 * nonZero / f.ft.length;
            System.out.printf("%-11s %-10s | range %5.1f..%5.1f mean %5.1f | %5.1f%% above 8  %s%n",
                    tag, filtered ? "filtered" : "unfiltered", lo, hi, mean, frac,
                    (hi - lo) > 100 ? "OK" : "FLAT <-- bad");
            write(f.ft, f.ftWidth, f.ftHeight,
                    "bench/ft_" + tag + (filtered ? "_filt" : "_unfilt") + ".png");
        }
        HoloBioRtDhmMath.setFtFiltered(false);
    }

    /**
     * Hologram whose object phase sits on a strong tilt plus curvature — several fringes of
     * smooth background under the real structure.
     */
    static void runAberrated(String tag, int rows, int cols, double fx, double fy)
            throws Exception {
        float[] h = new float[rows * cols];
        float[] truth = objectPhase(rows, cols);
        double sig = rows / 5.0;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                double rr = (r - rows / 2.0) / sig, cc = (c - cols / 2.0) / sig;
                double env = Math.exp(-(rr * rr + cc * cc) / 2);
                double x = 2.0 * c / (cols - 1) - 1, y = 2.0 * r / (rows - 1) - 1;
                // ~2.5 fringes of tilt plus a bowl, the shape seen in the report
                double bg = 2 * Math.PI * (1.2 * x + 0.9 * y) + 4.0 * (x * x + y * y);
                double objPhase = truth[r * cols + c] + bg;
                double objAmp = 0.35 + 0.5 * env;
                double carrier = 2 * Math.PI * (fx * c + fy * r);
                h[r * cols + c] = (float) (40 * (1 + objAmp * objAmp
                        + 2 * objAmp * Math.cos(carrier - objPhase)));
            }

        for (boolean flatten : new boolean[] { false, true }) {
            HoloBioRtDhmMath.setFlattenPhase(flatten);
            HoloBioRtDhmMath.resetSideband();
            HoloBioRtDhmMath.Frame f = HoloBioRtDhmMath.process(h, rows, cols, 0.532, 5.2, 5.2,
                    HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, false, true, false, true);
            double corr = Math.abs(phaseCorrelation(f.phase, f.width, f.height, rows, cols,
                    truth, cols));
            double[] k = HoloBioRtDhmMath.lastBackgroundFit();
            System.out.printf("%-11s flatten %-5s | phase r=%.3f %-8s | fit %s%n",
                    tag, flatten, corr, corr > 0.9 ? "GOOD" : "BAD <--",
                    (flatten && k != null)
                        ? String.format("x=%.2f y=%.2f xx=%.2f yy=%.2f xy=%.2f",
                                        k[0], k[1], k[2], k[3], k[4]) : "-");
            write(f.phase, f.width, f.height,
                    "bench/phase_" + tag + (flatten ? "_flat" : "_raw") + ".png");
        }
        HoloBioRtDhmMath.setFlattenPhase(true);
    }

    /**
     * Hologram of a rough surface. The object carries fine random structure, so its spectrum
     * is broad rather than a compact lobe around the carrier — the situation a real sample
     * produces and the smooth synthetic objects do not.
     */
    static void runSpeckle(String tag, int rows, int cols, double fx, double fy) throws Exception {
        java.util.Random rnd = new java.util.Random(7);
        float[] rough = new float[rows * cols];
        for (int i = 0; i < rough.length; i++) rough[i] = (float) rnd.nextGaussian();
        // Mild smoothing keeps it broadband but correlated over a couple of pixels
        float[] sm = new float[rows * cols];
        for (int r = 1; r < rows - 1; r++)
            for (int c = 1; c < cols - 1; c++) {
                float a = 0;
                for (int dr = -1; dr <= 1; dr++)
                    for (int dc = -1; dc <= 1; dc++) a += rough[(r + dr) * cols + c + dc];
                sm[r * cols + c] = a / 9f;
            }

        float[] h = new float[rows * cols];
        double sig = rows / 5.0;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                double rr = (r - rows / 2.0) / sig, cc = (c - cols / 2.0) / sig;
                double env = Math.exp(-(rr * rr + cc * cc) / 2);
                double objPhase = 2.5 * env * Math.cos(3 * rr) + 1.2 * sm[r * cols + c];
                double objAmp = 0.35 + 0.5 * env + 0.15 * sm[r * cols + c];
                double carrier = 2 * Math.PI * (fx * c + fy * r);
                h[r * cols + c] = (float) (40 * (1 + objAmp * objAmp
                        + 2 * objAmp * Math.cos(carrier - objPhase)));
            }

        HoloBioRtDhmMath.resetSideband();
        HoloBioRtDhmMath.Frame f = HoloBioRtDhmMath.process(h, rows, cols, 0.532, 5.2, 5.2,
                HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, true, true, false, true);

        double resid = tiltFringes(HoloBioRtDhmMath.carrierX() - 2 * Math.PI * fx, f.width)
                     + tiltFringes(HoloBioRtDhmMath.carrierY() - 2 * Math.PI * fy, f.height);
        System.out.printf("%-11s rough sample, broadband object spectrum                    "
                        + "  | r %2d | tilt %5.2f fr  %s%n",
                tag, HoloBioRtDhmMath.sidebandRadius(), resid,
                resid < 0.5 ? "OK" : "RESIDUAL RAMP <-- reproduces the report");
        write(f.phase, f.width, f.height, "bench/phase_" + tag + ".png");
    }

    /** A textured, fringe-free scene: what a webcam sees when it is not looking at a hologram. */
    static void runScene(String tag, int rows, int cols) throws Exception {
        float[] h = new float[rows * cols];
        java.util.Random rnd = new java.util.Random(11);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double blobs = 0;
                for (int k = 1; k <= 6; k++) {
                    blobs += Math.sin(2 * Math.PI * k * c / (double) cols + k)
                           * Math.cos(2 * Math.PI * k * r / (double) rows - k) / k;
                }
                h[r * cols + c] = (float) Math.max(0, Math.min(255,
                        128 + 45 * blobs + 6 * rnd.nextGaussian()));
            }
        }
        HoloBioRtDhmMath.resetSideband();
        HoloBioRtDhmMath.process(h, rows, cols, 0.532, 5.2, 5.2,
                HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0, true, true, true, true);
        System.out.printf("%-12s no carrier present                                    "
                        + "        | r %2d | prom %5.1fx %-9s | %s%n",
                tag, HoloBioRtDhmMath.sidebandRadius(),
                HoloBioRtDhmMath.sidebandProminence(),
                HoloBioRtDhmMath.sidebandConfident() ? "CONFIDENT" : "no-carrier",
                HoloBioRtDhmMath.sidebandConfident()
                        ? "claimed a carrier <-- bad" : "correctly reported none");
    }

    static void write(float[] data, int w, int h, String path) throws Exception {
        if (data == null) return;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] px = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length && i < data.length; i++)
            px[i] = (byte) Math.max(0, Math.min(255, Math.round(data[i])));
        ImageIO.write(img, "png", new File(path));
    }
}
