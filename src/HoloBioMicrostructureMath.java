import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.OvalRoi;
import ij.gui.Plot;
import ij.gui.TextRoi;
import ij.measure.ResultsTable;
import ij.plugin.filter.ParticleAnalyzer;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Microstructure metrics (Python {@code tools_microstructure}): thresholding, watershed
 * separation, particle detection, count/area reports, automatic phase profiles, thickness maps.
 */
public final class HoloBioMicrostructureMath {

    private HoloBioMicrostructureMath() {}

    public static final class Particle {
        public final double centerX;
        public final double centerY;
        public final double diameter;
        public final double area;
        public final int label;

        Particle(double centerX, double centerY, double diameter, double area, int label) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.diameter = diameter;
            this.area = area;
            this.label = label;
        }
    }

    public static final class ProcessResult {
        public final boolean[] mask;
        public final int width;
        public final int height;
        public final List<Particle> particles;
        public final double thresholdUsed;
        public final boolean sampleIsWhite;

        ProcessResult(boolean[] mask, int w, int h, List<Particle> particles,
                      double thresholdUsed, boolean sampleIsWhite) {
            this.mask = mask;
            this.width = w;
            this.height = h;
            this.particles = particles;
            this.thresholdUsed = thresholdUsed;
            this.sampleIsWhite = sampleIsWhite;
        }
    }

    /** 8-bit grayscale image to boolean foreground mask (inverted like Python cv2 bitwise_not). */
    public static boolean[] createBinaryMask(byte[] gray, int w, int h, String method, double manualThreshold) {
        int n = w * h;
        double thresh;
        boolean[] mask;
        if ("manual".equalsIgnoreCase(method)) {
            thresh = manualThreshold;
            mask = new boolean[n];
            for (int i = 0; i < n; i++) mask[i] = (gray[i] & 0xff) <= thresh;
        } else if ("adaptive".equalsIgnoreCase(method)) {
            mask = adaptiveThresholdMask(gray, w, h);
        } else {
            thresh = otsuHistogram(gray);
            mask = new boolean[n];
            for (int i = 0; i < n; i++) mask[i] = (gray[i] & 0xff) <= thresh;
        }
        // Separate touching blobs (Python separate_touching_samples / ImageJ Watershed).
        return applyWatershedSeparation(mask, w, h);
    }

    /**
     * ImageJ binary Watershed on the foreground mask — splits touching particles along
     * distance ridges so ParticleAnalyzer sees them as separate components.
     */
    private static boolean[] applyWatershedSeparation(boolean[] mask, int w, int h) {
        int fg = 0;
        for (boolean b : mask) if (b) fg++;
        if (fg < 8) return mask;
        ByteProcessor bp = maskToByte(mask, w, h);
        ImagePlus tmp = new ImagePlus("ws", bp);
        try {
            IJ.run(tmp, "Watershed", "");
        } catch (Throwable ignored) {
            return mask;
        }
        ImageProcessor ip = tmp.getProcessor();
        boolean[] out = new boolean[w * h];
        for (int i = 0; i < out.length; i++) out[i] = ip.get(i) > 0;
        return out;
    }

    private static double otsuHistogram(byte[] gray) {
        int[] hist = new int[256];
        for (byte b : gray) {
            hist[b & 0xff]++;
        }
        int total = gray.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * hist[i];
        }
        double sumB = 0;
        int wB = 0;
        double maxVar = 0;
        int best = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            int wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += t * hist[t];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double var = wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) {
                maxVar = var;
                best = t;
            }
        }
        return best;
    }

    private static boolean[] adaptiveThresholdMask(byte[] gray, int w, int h) {
        int block = 15;
        int half = block / 2;
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long sum = 0;
                int cnt = 0;
                for (int dy = -half; dy <= half; dy++) {
                    for (int dx = -half; dx <= half; dx++) {
                        int xx = x + dx;
                        int yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                            sum += gray[yy * w + xx] & 0xff;
                            cnt++;
                        }
                    }
                }
                double mean = sum / (double) cnt;
                int v = gray[y * w + x] & 0xff;
                mask[y * w + x] = v <= mean - 5;  // matches Python bitwise_not: True = below local mean
            }
        }
        return mask;
    }

    public static ProcessResult processParticles(byte[] gray, int w, int h, String method,
                                                 double manualThreshold, int minArea, int maxArea,
                                                 boolean sampleIsWhite) {
        boolean[] binary = createBinaryMask(gray, w, h, method, manualThreshold);
        double threshUsed = "manual".equalsIgnoreCase(method) ? manualThreshold : otsuHistogram(gray);

        boolean[] cleaned = sampleIsWhite
            ? removeSmallObjects(binary, w, h, minArea, true)
            : invert(removeSmallObjects(invert(binary, w, h), w, h, minArea, true), w, h);
        cleaned = fillSmallHoles(cleaned, w, h, 50);

        boolean[] analysis = sampleIsWhite ? cleaned : invert(cleaned, w, h);

        ByteProcessor bp = maskToByte(analysis, w, h);
        ImagePlus imp = new ImagePlus("HoloBio mask", bp);
        ResultsTable rt = new ResultsTable();
        int measurements = ParticleAnalyzer.AREA + ParticleAnalyzer.CENTER_OF_MASS;
        ParticleAnalyzer pa = new ParticleAnalyzer(
            ParticleAnalyzer.SHOW_NONE, measurements, rt, minArea, maxArea);
        pa.analyze(imp);

        List<Particle> particles = new ArrayList<>();
        for (int i = 0; i < rt.size(); i++) {
            double area = rt.getValue("Area", i);
            double cx = rt.getValue("X", i);
            double cy = rt.getValue("Y", i);
            double diam = 2.0 * Math.sqrt(area / Math.PI);
            particles.add(new Particle(cx, cy, diam, area, i + 1));
        }
        return new ProcessResult(cleaned, w, h, particles, threshUsed, sampleIsWhite);
    }

    /**
     * Quick particle count on threshold mask for area-slider preview (no polarity cleanup).
     */
    public static int particleCountForAreaRange(byte[] gray, int w, int h, String method,
                                                double manualThreshold, int minArea, int maxArea) {
        boolean[] mask = createBinaryMask(gray, w, h, method, manualThreshold);
        ByteProcessor bp = maskToByte(mask, w, h);
        ImagePlus imp = new ImagePlus("preview", bp);
        ResultsTable rt = new ResultsTable();
        int measurements = ParticleAnalyzer.AREA + ParticleAnalyzer.CENTER_OF_MASS;
        ParticleAnalyzer pa = new ParticleAnalyzer(
            ParticleAnalyzer.SHOW_NONE, measurements, rt, minArea, maxArea);
        pa.analyze(imp);
        return rt.size();
    }

    /**
     * Builds the binary mask image for the area-filter preview (caller is responsible for showing it).
     * Uses SHOW_NONE to avoid Fiji creating a second "Drawing of..." window.
     */
    public static ImagePlus showAreaFilterPreview(byte[] gray, int w, int h, String method,
                                                  double manualThreshold, int minArea, int maxArea) {
        boolean[] mask = createBinaryMask(gray, w, h, method, manualThreshold);
        ByteProcessor bp = maskToByte(mask, w, h);
        return new ImagePlus("HoloBio — Segmentation preview", bp);
    }

    public static byte[] toByteGray(float[] display0255, int w, int h) {
        byte[] o = new byte[w * h];
        for (int i = 0; i < o.length; i++) {
            int v = (int) Math.round(Math.max(0, Math.min(255, display0255[i])));
            o[i] = (byte) v;
        }
        return o;
    }

    public static float[] displayToPhaseRad(float[] display0255) {
        float[] ph = new float[display0255.length];
        for (int i = 0; i < ph.length; i++) {
            ph[i] = (float) ((display0255[i] / 255.0) * (2.0 * Math.PI) - Math.PI);
        }
        return ph;
    }

    /** Color palette shared between detection overlay and combined profile plot. */
    private static final Color[] PROFILE_COLORS = {
        new Color(231, 76,  60),   // red
        new Color(52,  152, 219),  // blue
        new Color(46,  204, 113),  // green
        new Color(155, 89,  182),  // purple
        new Color(241, 196, 15),   // yellow
        new Color(26,  188, 156),  // teal
    };

    /**
     * Show detected particles as white circles over the segmentation mask.
     * Call this after {@link #processParticles} so the user can verify detection before analysis.
     */
    /**
     * Count / area reports matching Python {@code apply_count_particles} /
     * {@code apply_area_particles}.
     */
    public static void showParticleReports(ProcessResult proc, double umPerPx,
                                           boolean count, boolean area) {
        if (proc.particles.isEmpty()) {
            HoloBioFijiUi.message("HoloBio", "No particles detected for count/area report.");
            return;
        }
        if (count) {
            StringBuilder sb = new StringBuilder();
            sb.append("Particles accepted (area filter): ").append(proc.particles.size()).append('\n');
            sb.append(String.format("Threshold used: %.1f%n", proc.thresholdUsed));
            sb.append("Sample polarity: ").append(proc.sampleIsWhite ? "white" : "black");
            HoloBioFijiUi.message("HoloBio — Particle count", sb.toString());
            HoloBioFijiUi.log("[HoloBio Microstructure] count=" + proc.particles.size());
        }
        if (area) {
            double u2 = umPerPx > 0 ? umPerPx * umPerPx : 1.0;
            ResultsTable rt = new ResultsTable();
            double sum = 0, sum2 = 0, amin = Double.POSITIVE_INFINITY, amax = 0;
            for (int i = 0; i < proc.particles.size(); i++) {
                Particle p = proc.particles.get(i);
                double aUm2 = p.area * u2;
                sum += aUm2;
                sum2 += aUm2 * aUm2;
                if (aUm2 < amin) amin = aUm2;
                if (aUm2 > amax) amax = aUm2;
                rt.incrementCounter();
                rt.addValue("Particle", i + 1);
                rt.addValue("Area (px²)", p.area);
                rt.addValue("Area (µm²)", aUm2);
                rt.addValue("Diameter (px)", p.diameter);
                rt.addValue("X", p.centerX);
                rt.addValue("Y", p.centerY);
            }
            int n = proc.particles.size();
            double mean = sum / n;
            double sd = n > 1 ? Math.sqrt(Math.max(0, (sum2 - sum * sum / n) / (n - 1))) : 0;
            rt.show("HoloBio — Particle areas");
            HoloBioFijiUi.message("HoloBio — Area summary",
                String.format("n=%d%nMean area: %.2f ± %.2f µm²%nRange: %.2f – %.2f µm²",
                    n, mean, sd, amin, amax));
        }
    }

    public static void showDetectionOverlay(ProcessResult proc, int w, int h) {
        if (proc.particles.isEmpty()) {
            HoloBioFijiUi.message("HoloBio", "No particles detected in the given area range.");
            return;
        }
        ImagePlus detImp = new ImagePlus(
            "HoloBio — " + proc.particles.size() + " particle(s) detected",
            maskToByte(proc.mask, w, h));
        Overlay ov = new Overlay();
        for (int i = 0; i < proc.particles.size(); i++) {
            Particle p   = proc.particles.get(i);
            double   r   = p.diameter / 2.0;
            Color    col = PROFILE_COLORS[i % PROFILE_COLORS.length];

            OvalRoi oval = new OvalRoi(p.centerX - r, p.centerY - r, p.diameter, p.diameter);
            oval.setStrokeColor(col);
            oval.setStrokeWidth(2f);
            ov.add(oval);

            TextRoi lbl = new TextRoi((int)(p.centerX + r + 2), (int)(p.centerY - 4),
                String.valueOf(i + 1), new Font("SansSerif", Font.BOLD, 11));
            lbl.setStrokeColor(col);
            ov.add(lbl);
        }
        detImp.setOverlay(ov);
        HoloBioFijiUi.showImagePlus(detImp);
    }

    /** Automatic phase profiles across detected particles (Python {@code automaticProfile}). */
    public static void runAutomaticPhaseProfiles(byte[] gray, int w, int h, ProcessResult proc,
                                                 float[] phaseRad, double umPerPx) {
        if (proc.particles.isEmpty()) {
            HoloBioFijiUi.message("HoloBio", "No particles found for automatic phase profiles.");
            return;
        }
        int[] labels = labelMask(proc.sampleIsWhite ? proc.mask : invert(proc.mask, w, h), w, h);

        // Phase image (Fire LUT) with overlay — circles + colored profile lines
        FloatProcessor phFp = new FloatProcessor(w, h, phaseRad.clone());
        phFp.setMinAndMax(-Math.PI, Math.PI);
        ImagePlus phaseImp = new ImagePlus("HoloBio — Phase profiles", phFp);
        IJ.run(phaseImp, "Fire", "");
        Overlay overlay = new Overlay();

        // White outline circles for every detected particle
        for (Particle p : proc.particles) {
            double r    = p.diameter / 2.0;
            OvalRoi oval = new OvalRoi(p.centerX - r, p.centerY - r, p.diameter, p.diameter);
            oval.setStrokeColor(Color.WHITE);
            oval.setStrokeWidth(1.5f);
            overlay.add(oval);
        }

        // Gather valid profiles
        ResultsTable rt = new ResultsTable();
        List<double[]> allDist  = new ArrayList<>();
        List<double[]> allProf  = new ArrayList<>();
        List<double[]> allStats = new ArrayList<>();
        List<Integer>  sampleNr = new ArrayList<>();
        int colorIdx = 0;

        for (int si = 0; si < proc.particles.size(); si++) {
            Particle s  = proc.particles.get(si);
            LineEndpoints ep = findProfileEndpoints(proc.mask, labels, w, h, s, proc.sampleIsWhite);
            if (ep == null) continue;

            int n = Math.max(2, (int) Math.hypot(ep.x2 - ep.x1, ep.y2 - ep.y1));
            double[] dist = new double[n];
            double[] prof = new double[n];
            for (int k = 0; k < n; k++) {
                double t = k / (double) (n - 1);
                double x = ep.x1 + (ep.x2 - ep.x1) * t;
                double y = ep.y1 + (ep.y2 - ep.y1) * t;
                double v = HoloBioQpiSpeckleMath.sampleBilinear(phaseRad, w, h, x, y);
                if (!Double.isNaN(v)) v = (v + Math.PI) % (2 * Math.PI) - Math.PI;
                prof[k] = Double.isNaN(v) ? 0 : v;
                dist[k] = umPerPx > 0 ? k * umPerPx : k;
            }
            double[] stats  = profileDeltaPhi(prof);
            double phiLow   = stats[0], phiHigh = stats[1], delta = stats[2];

            rt.incrementCounter();
            rt.addValue("Sample",        si + 1);
            rt.addValue("Delta_phi_rad", delta);
            rt.addValue("phi_low_5pct",  phiLow);
            rt.addValue("phi_high_5pct", phiHigh);
            rt.addValue("Center_X",      s.centerX);
            rt.addValue("Center_Y",      s.centerY);

            // Colored profile line on the phase image overlay
            Color lineCol = PROFILE_COLORS[colorIdx % PROFILE_COLORS.length];
            ij.gui.Line lineRoi = new ij.gui.Line(ep.x1, ep.y1, ep.x2, ep.y2);
            lineRoi.setStrokeColor(lineCol);
            lineRoi.setStrokeWidth(2f);
            overlay.add(lineRoi);

            // Small numbered label at the line start
            TextRoi nr = new TextRoi((int) ep.x1, (int) ep.y1,
                String.valueOf(si + 1), new Font("SansSerif", Font.BOLD, 10));
            nr.setStrokeColor(lineCol);
            overlay.add(nr);

            allDist .add(dist);
            allProf .add(prof);
            allStats.add(stats);
            sampleNr.add(si + 1);
            colorIdx++;
        }

        phaseImp.setOverlay(overlay);
        HoloBioFijiUi.showImagePlus(phaseImp);

        // Single combined profile plot — all samples, one window
        if (!allProf.isEmpty()) {
            String xLabel = umPerPx > 0 ? "Distance (µm)" : "Pixel";
            Plot plot = new Plot(
                "HoloBio — Phase profiles (" + allProf.size() + " samples)", xLabel, "Phase (rad)");
            StringBuilder legend = new StringBuilder();
            for (int i = 0; i < allProf.size(); i++) {
                Color      c    = PROFILE_COLORS[i % PROFILE_COLORS.length];
                double[]   xs   = allDist.get(i);
                double[]   ps   = allProf.get(i);
                double[]   st   = allStats.get(i);
                plot.setColor(c);
                plot.add("line", xs, ps);

                legend.append(String.format("S%d  Δφ=%.3f  (5%%lo=%.3f  hi=%.3f rad)",
                    sampleNr.get(i), st[2], st[0], st[1]));
                if (i < allProf.size() - 1) legend.append("\n");
            }
            plot.setColor(Color.BLACK);
            plot.addLegend(legend.toString());
            plot.show();
        }

        rt.show("HoloBio Phase Profiles");
        HoloBioFijiUi.log("[HoloBio] Phase profiles: " + rt.size() + " / " + proc.particles.size()
            + " sample(s) valid.");
    }

    public static void runThicknessEstimation(byte[] gray, int w, int h, String method,
                                              double manualThreshold, boolean sampleIsWhite,
                                              float[] phaseRad, double wavelengthUm,
                                              double nSample, double nMedium) {
        boolean[] binary = createBinaryMask(gray, w, h, method, manualThreshold);
        boolean[] background = sampleIsWhite ? invert(binary, w, h) : binary;
        boolean[] sampleMask = sampleIsWhite ? binary : invert(binary, w, h);

        double sumBg = 0;
        long cntBg = 0;
        for (int i = 0; i < phaseRad.length; i++) {
            if (background[i]) {
                sumBg += phaseRad[i];
                cntBg++;
            }
        }
        double avgBg = cntBg > 0 ? sumBg / cntBg : 0;

        float[] deltaPhi = new float[phaseRad.length];
        float[] thickness = new float[phaseRad.length];
        double nDiff = nSample - nMedium;
        for (int i = 0; i < phaseRad.length; i++) {
            deltaPhi[i] = (float) Math.abs(phaseRad[i] - avgBg);
            if (sampleMask[i] && Math.abs(nDiff) > 1e-12) {
                thickness[i] = (float) (deltaPhi[i] * wavelengthUm / (2.0 * Math.PI * nDiff));
            }
        }

        showFloatMap("HoloBio — Delta phase", deltaPhi, w, h);
        showFloatMap("HoloBio — Thickness (µm)", thickness, w, h);
        HoloBioFijiUi.log("[HoloBio] Thickness map: λ=" + wavelengthUm + " µm, Δn=" + nDiff);
    }

    private static void showFloatMap(String title, float[] data, int w, int h) {
        FloatProcessor fp = new FloatProcessor(w, h, data, null);
        ImagePlus imp = new ImagePlus(title, fp);
        imp.resetDisplayRange();
        HoloBioFijiUi.showImagePlus(imp);
    }

    private static ByteProcessor phaseOverlayByte(float[] phaseRad, int w, int h) {
        ByteProcessor bp = new ByteProcessor(w, h);
        for (int i = 0; i < phaseRad.length; i++) {
            double t = (phaseRad[i] + Math.PI) / (2.0 * Math.PI);
            bp.set(i % w, i / w, (int) Math.round(Math.max(0, Math.min(255, t * 255))));
        }
        return bp;
    }

    private static double[] profileDeltaPhi(double[] prof) {
        double[] p = Arrays.stream(prof).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (p.length < 4) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        int n5 = Math.max(1, (int) Math.floor(0.05 * p.length));
        double low = 0;
        double high = 0;
        for (int i = 0; i < n5; i++) {
            low  += p[i];
            high += p[p.length - 1 - i];
        }
        low /= n5; high /= n5;
        return new double[]{low, high, high - low};
    }

    private static final class LineEndpoints {
        final double x1, y1, x2, y2;
        LineEndpoints(double x1, double y1, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    private static LineEndpoints findProfileEndpoints(boolean[] finalMask, int[] labels,
                                                    int w, int h, Particle s, boolean sampleIsWhite) {
        double cx = s.centerX;
        double cy = s.centerY;
        double minExt = (s.diameter / 2.0) * 1.25;
        double maxExt = (s.diameter / 2.0) * 1.5;
        int labelExcl = findLabelAt(labels, w, h, (int) Math.round(cx), (int) Math.round(cy));

        for (int ai = 0; ai < 36; ai++) {
            double ang = Math.PI * ai / 35.0;
            for (int ei = 0; ei < 8; ei++) {
                double ext = minExt + (maxExt - minExt) * ei / 7.0;
                double dx = ext * Math.cos(ang);
                double dy = ext * Math.sin(ang);
                double x1 = cx - dx;
                double y1 = cy - dy;
                double x2 = cx + dx;
                double y2 = cy + dy;
                // Bounds check (Python: skip if endpoints out of image)
                if (x1 < 0 || y1 < 0 || x1 >= w || y1 >= h
                        || x2 < 0 || y2 < 0 || x2 >= w || y2 >= h) continue;
                // Endpoints must be outside the sample (like Python: v1/v2 not inside)
                if (!endpointsOutsideSample(finalMask, w, h, x1, y1, x2, y2, sampleIsWhite)) {
                    continue;
                }
                // Line must not cross a different sample
                if (segmentCrossesOtherLabel(labels, w, h, x1, y1, x2, y2, labelExcl)) {
                    continue;
                }
                return new LineEndpoints(x1, y1, x2, y2);
            }
        }
        return null;
    }

    private static int findLabelAt(int[] labels, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return 0;
        }
        return labels[y * w + x];
    }

    /**
     * Returns true only if BOTH endpoints are outside the sample.
     * Matches Python find_profile_endpoints: only checks v1/v2 at the two endpoints,
     * not the whole segment (the line is expected to cross through the sample).
     */
    private static boolean endpointsOutsideSample(boolean[] mask, int w, int h,
                                                  double x1, double y1, double x2, double y2,
                                                  boolean sampleIsWhite) {
        int xi1 = clamp((int) Math.round(x1), 0, w - 1);
        int yi1 = clamp((int) Math.round(y1), 0, h - 1);
        int xi2 = clamp((int) Math.round(x2), 0, w - 1);
        int yi2 = clamp((int) Math.round(y2), 0, h - 1);
        boolean in1 = mask[yi1 * w + xi1];
        boolean in2 = mask[yi2 * w + xi2];
        if (!sampleIsWhite) { in1 = !in1; in2 = !in2; }
        return !in1 && !in2;
    }

    private static boolean segmentCrossesOtherLabel(int[] labels, int w, int h,
                                                    double x1, double y1, double x2, double y2,
                                                    int labelExcl) {
        int n = Math.max(2, (int) Math.hypot(x2 - x1, y2 - y1));
        for (int k = 0; k < n; k++) {
            double t = k / (double) (n - 1);
            int x = clamp((int) Math.round(x1 + (x2 - x1) * t), 0, w - 1);
            int y = clamp((int) Math.round(y1 + (y2 - y1) * t), 0, h - 1);
            int lab = labels[y * w + x];
            if (lab != 0 && lab != labelExcl) {
                return true;
            }
        }
        return false;
    }

    private static int[] labelMask(boolean[] mask, int w, int h) {
        int[] labels = new int[w * h];
        int next = 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!mask[i] || labels[i] != 0) {
                    continue;
                }
                floodLabel(mask, labels, w, h, x, y, next++);
            }
        }
        return labels;
    }

    private static void floodLabel(boolean[] mask, int[] labels, int w, int h, int sx, int sy, int lab) {
        int[] stackX = new int[w * h];
        int[] stackY = new int[w * h];
        int sp = 0;
        stackX[sp] = sx;
        stackY[sp] = sy;
        sp++;
        labels[sy * w + sx] = lab;
        while (sp > 0) {
            sp--;
            int x = stackX[sp];
            int y = stackY[sp];
            int[][] nbr = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
            for (int[] d : nbr) {
                int nx = d[0];
                int ny = d[1];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                int ni = ny * w + nx;
                if (mask[ni] && labels[ni] == 0) {
                    labels[ni] = lab;
                    stackX[sp] = nx;
                    stackY[sp] = ny;
                    sp++;
                }
            }
        }
    }

    private static boolean[] removeSmallObjects(boolean[] mask, int w, int h, int minSize, boolean foreground) {
        int[] labels = labelMask(mask, w, h);
        int maxLab = 0;
        for (int l : labels) {
            if (l > maxLab) {
                maxLab = l;
            }
        }
        int[] areas = new int[maxLab + 1];
        for (int l : labels) {
            if (l > 0) {
                areas[l]++;
            }
        }
        boolean[] out = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) {
            int l = labels[i];
            out[i] = l > 0 && areas[l] >= minSize;
        }
        return out;
    }

    private static boolean[] fillSmallHoles(boolean[] mask, int w, int h, int maxHole) {
        boolean[] inv = invert(mask, w, h);
        boolean[] holesCleaned = removeSmallObjects(inv, w, h, maxHole, true);
        return invert(holesCleaned, w, h);
    }

    private static boolean[] invert(boolean[] mask, int w, int h) {
        boolean[] o = new boolean[mask.length];
        for (int i = 0; i < mask.length; i++) {
            o[i] = !mask[i];
        }
        return o;
    }

    public static ByteProcessor maskToByte(boolean[] mask, int w, int h) {
        ByteProcessor bp = new ByteProcessor(w, h);
        for (int i = 0; i < mask.length; i++) {
            bp.set(i % w, i / w, mask[i] ? 255 : 0);
        }
        return bp;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
