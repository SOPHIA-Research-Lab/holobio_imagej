import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ItemEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * QPI and Speckle tool dialogs aligned with HoloBio Python {@code functions_GUI} and {@code tools_GUI}.
 * Fiji uses the ROI Manager and {@link ResultsTable} instead of matplotlib.
 */
public final class HoloBioToolDialogs {

    private HoloBioToolDialogs() {}

    /** Reused so Minimize / Close (hide) can be restored from the menu. */
    private static JDialog qpiDialog;
    private static final java.util.concurrent.atomic.AtomicReference<HoloBioToolInputs> qpiInputsRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * QPI Measurements dialog. Microstructure UI is disabled (code kept in
     * {@link HoloBioMicrostructureMath} / unused tab builders below for possible revival).
     */
    public static void showBioAnalysisDialog(Frame owner, HoloBioToolInputs inputs) {
        qpiInputsRef.set(inputs);
        if (qpiDialog != null && qpiDialog.isDisplayable()) {
            bringWindowFront(qpiDialog);
            return;
        }

        JDialog dlg = new JDialog(owner, "HoloBio — QPI", false);
        qpiDialog = dlg;
        dlg.setDefaultCloseOperation(javax.swing.WindowConstants.HIDE_ON_CLOSE);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (qpiDialog == dlg) {
                    qpiDialog = null;
                }
            }
        });
        dlg.setLayout(new BorderLayout(0, 0));

        String defPx = inputs != null && inputs.defaultPixelUm > 0
            ? String.format("%.4g", inputs.defaultPixelUm) : "2.40";
        String defMag = inputs != null && inputs.defaultMag > 0
            ? HoloBioUiStyle.formatMag(inputs.defaultMag) : HoloBioUiStyle.formatMag(40.0);

        JTextField tfPixelUm = HoloBioUiStyle.numericField(defPx);
        JTextField tfMag = HoloBioUiStyle.numericField(defMag);
        JComboBox<String> srcCombo = new JComboBox<>(new String[]{"Phase", "Amplitude", "Hologram"});
        JComboBox<String> roiTypeCombo = new JComboBox<>(new String[]{"Line (profile)", "Circle (oval)"});
        JComboBox<String> modeCombo = new JComboBox<>(new String[]{"Thickness", "Index"});
        JTextField tfIndSample = HoloBioUiStyle.numericField("1.00");
        JTextField tfIndMedium = HoloBioUiStyle.numericField("1.33");
        JTextField tfThicknessKnown = HoloBioUiStyle.numericField("10.0");
        JTextField tfZones = HoloBioUiStyle.numericField("1");

        tfPixelUm.setToolTipText("Camera pixel size in micrometres.");
        tfMag.setToolTipText("Objective magnification M, e.g. 40 or 40x. Scale = pixel size / M.");
        srcCombo.setToolTipText("Image used to draw or load ROIs.");
        roiTypeCombo.setToolTipText("Line = straight profile; Circle = oval ROI.");
        modeCombo.setToolTipText("Thickness from Δφ and indices, or index from a known thickness.");
        tfIndSample.setToolTipText("Sample refractive index n_s. Used in Thickness mode.");
        tfIndMedium.setToolTipText("Medium refractive index n_m. Used in Thickness mode.");
        tfThicknessKnown.setToolTipText("Known thickness in micrometres. Used in Index mode.");
        tfZones.setToolTipText("Number of ROIs to draw (integer ≥ 1).");

        JPanel dim = HoloBioUiStyle.formSection("Dimensions");
        GridBagConstraints dimC = HoloBioUiStyle.formGbc();
        HoloBioUiStyle.addLabelField(dim, dimC, "Pixel size (µm)", tfPixelUm);
        HoloBioUiStyle.addLabelField(dim, dimC, HoloBioUiStyle.MAG_LABEL, tfMag);
        HoloBioUiStyle.addLabelField(dim, dimC, "Draw on", srcCombo);

        JPanel meas = HoloBioUiStyle.formSection("QPI measurements");
        GridBagConstraints measC = HoloBioUiStyle.formGbc();
        HoloBioUiStyle.addLabelField(meas, measC, "ROI type", roiTypeCombo);
        HoloBioUiStyle.addLabelField(meas, measC, "Measurement mode", modeCombo);
        HoloBioUiStyle.addLabelField(meas, measC, "Zones", tfZones,
            "Number of ROIs to draw. Each zone is measured separately (integer ≥ 1).");

        JPanel refr = HoloBioUiStyle.formSection("Refractive parameters");
        GridBagConstraints refrC = HoloBioUiStyle.formGbc();
        JLabel lblNs = HoloBioUiStyle.addLabelField(refr, refrC, "Sample index n_s", tfIndSample,
            "Sample refractive index n_s. Used when Measurement mode = Thickness.");
        JLabel lblNm = HoloBioUiStyle.addLabelField(refr, refrC, "Medium index n_m", tfIndMedium,
            "Medium refractive index n_m. Used when Measurement mode = Thickness.");
        JLabel lblThick = HoloBioUiStyle.addLabelField(refr, refrC, "Known thickness (µm)", tfThicknessKnown);
        JLabel thickHelp = HoloBioUiStyle.helperText("Enabled when Measurement mode = Index.");
        refrC.gridx = 0;
        refrC.gridwidth = 2;
        refrC.insets = new java.awt.Insets(0, 0, 0, 0);
        refr.add(thickHelp, refrC);

        Runnable syncQpiMode = () -> {
            boolean thm = "Thickness".equals(modeCombo.getSelectedItem());
            tfIndSample.setEnabled(thm);
            tfIndMedium.setEnabled(thm);
            lblNs.setEnabled(thm);
            lblNm.setEnabled(thm);
            tfThicknessKnown.setEnabled(!thm);
            lblThick.setEnabled(!thm);
            thickHelp.setForeground(thm ? HoloBioUiStyle.TEXT_MUTED : HoloBioUiStyle.TEXT);
        };
        modeCombo.addActionListener(e -> syncQpiMode.run());
        syncQpiMode.run();

        JButton applyQpi = HoloBioUiStyle.primaryButton("Apply QPI");
        JButton loadRoiFile = HoloBioUiStyle.secondaryButton("Load ROI file…");
        loadRoiFile.setToolTipText("Load line/rect coords from rois_*.txt and run QPI without drawing");

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(HoloBioUiStyle.contentPad());
        HoloBioUiStyle.stackSections(body, dim, meas, refr);

        JButton close = HoloBioUiStyle.tertiaryButton("Close");
        close.addActionListener(ev -> dlg.setVisible(false));

        dlg.add(HoloBioUiStyle.buildHeader("QPI", null, null), BorderLayout.NORTH);
        dlg.add(body, BorderLayout.CENTER);
        dlg.add(HoloBioUiStyle.buildActionBar(close, loadRoiFile, applyQpi), BorderLayout.SOUTH);

        applyQpi.addActionListener(ev -> HoloBioUiStyle.runBusy(applyQpi, "Working…", () -> {
            HoloBioToolInputs in = qpiInputsRef.get();
            if (in == null || !in.hasPhaseField()) {
                JOptionPane.showMessageDialog(dlg, "Reconstruct phase first.",
                        "HoloBio QPI", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            double pixelUm = HoloBioRtUtil.parseDouble(tfPixelUm, 3.75);
            double mag = HoloBioRtUtil.parseDouble(tfMag, 40.0);
            if (!(mag > 1e-12)) mag = 1.0;
            int zones;
            try { zones = Integer.parseInt(tfZones.getText().trim()); }
            catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Zones must be an integer ≥ 1.",
                        "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (zones < 1) {
                JOptionPane.showMessageDialog(dlg, "Zones must be ≥ 1.",
                        "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean thicknessMode = "Thickness".equals(modeCombo.getSelectedItem());
            double nSample = HoloBioRtUtil.parseDouble(tfIndSample, 1.33);
            double nMedium = HoloBioRtUtil.parseDouble(tfIndMedium, 1.0);
            double thicknessUm = HoloBioRtUtil.parseDouble(tfThicknessKnown, 10.0);
            boolean circleRoi = String.valueOf(roiTypeCombo.getSelectedItem()).startsWith("Circle");
            String source = String.valueOf(srcCombo.getSelectedItem());
            dlg.setVisible(false);
            runQpiWithRoiManager(dlg, in, zones, source, circleRoi, thicknessMode,
                    nSample, nMedium, thicknessUm, pixelUm, mag);
        }));

        loadRoiFile.addActionListener(ev -> {
            HoloBioToolInputs in = qpiInputsRef.get();
            if (in == null || !in.hasPhaseField()) {
                JOptionPane.showMessageDialog(dlg, "Reconstruct phase first.",
                        "HoloBio QPI", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JFileChooser fc = new JFileChooser(
                    new File(System.getProperty("user.home"),
                            "Documents/DECIMO SEMESTRE/AVANZADO 2/sample images/benchmark"));
            fc.setDialogTitle("Load shared ROIs (rois_*.txt)");
            if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            double pixelUm = HoloBioRtUtil.parseDouble(tfPixelUm, 3.75);
            double mag = HoloBioRtUtil.parseDouble(tfMag, 40.0);
            if (!(mag > 1e-12)) mag = 1.0;
            boolean thicknessMode = "Thickness".equals(modeCombo.getSelectedItem());
            double nSample = HoloBioRtUtil.parseDouble(tfIndSample, 1.33);
            double nMedium = HoloBioRtUtil.parseDouble(tfIndMedium, 1.0);
            double thicknessUm = HoloBioRtUtil.parseDouble(tfThicknessKnown, 10.0);
            String source = String.valueOf(srcCombo.getSelectedItem());
            try {
                runQpiFromRoiFile(dlg, in, fc.getSelectedFile(), source, thicknessMode,
                        nSample, nMedium, thicknessUm, pixelUm, mag);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "Cannot load ROIs: " + ex.getMessage(),
                        "HoloBio QPI", JOptionPane.ERROR_MESSAGE);
            }
        });

        HoloBioUiStyle.polish(dlg);
        HoloBioUiStyle.packTight(dlg, 440, 560);
        dlg.setLocationRelativeTo(owner);
        bringWindowFront(dlg);
    }

    /** Restore a hidden / iconified / buried tool window. */
    private static void bringWindowFront(Window w) {
        if (w == null) {
            return;
        }
        if (w instanceof Frame) {
            Frame f = (Frame) w;
            f.setExtendedState(f.getExtendedState() & ~Frame.ICONIFIED);
        }
        w.setVisible(true);
        // Always-on-top pulse helps when Windows leaves a minimized dialog stuck behind Fiji.
        try {
            w.setAlwaysOnTop(true);
            w.toFront();
            w.requestFocus();
        } finally {
            w.setAlwaysOnTop(false);
        }
    }

    /** @deprecated use {@link #showBioAnalysisDialog}; kept for compatibility. */
    public static void showQpiDialog(Frame owner, HoloBioToolInputs inputs) {
        showBioAnalysisDialog(owner, inputs);
    }

    private static ImagePlus areaFilterPreviewImp;

    private static void previewAreaFilter(HoloBioToolInputs in,
                                          boolean manualThresh, boolean adaptiveThresh,
                                          JTextField tfManualThresh, int minArea, int maxArea,
                                          JLabel statusLabel) {
        float[] display = cyclicPhaseDisplay0255(in);
        if (display == null) {
            statusLabel.setText("Particles: — (reconstruct first)");
            return;
        }
        int w = in.fieldWidth, h = in.fieldHeight;
        byte[] gray = HoloBioMicrostructureMath.toByteGray(display, w, h);
        String method = manualThresh ? "manual" : (adaptiveThresh ? "adaptive" : "otsu");
        double thr = 128;
        if (manualThresh) {
            try {
                thr = Double.parseDouble(tfManualThresh.getText().trim().replace(',', '.'));
            } catch (NumberFormatException ex) { thr = 128; }
        }
        int n = HoloBioMicrostructureMath.particleCountForAreaRange(gray, w, h, method, thr, minArea, maxArea);
        statusLabel.setText("Particles in range: " + n + "  (area " + minArea + "–" + maxArea + " px²)");
        ImagePlus fresh = HoloBioMicrostructureMath.showAreaFilterPreview(gray, w, h, method, thr, minArea, maxArea);
        if (fresh == null) return;
        if (areaFilterPreviewImp != null && areaFilterPreviewImp.isVisible()) {
            // Update the existing window in-place — no flicker, no extra window
            areaFilterPreviewImp.setProcessor(fresh.getProcessor());
            areaFilterPreviewImp.updateAndDraw();
        } else {
            if (areaFilterPreviewImp != null) { areaFilterPreviewImp.changes = false; areaFilterPreviewImp.close(); }
            areaFilterPreviewImp = fresh;
            HoloBioFijiUi.showImagePlus(areaFilterPreviewImp);
        }
    }

    private static void runMicrostructure(Frame owner, HoloBioToolInputs in,
                                         String method, double manualThreshold, int minArea, int maxArea,
                                         double nSample, double nMedium, double pixelUm, double mag,
                                         boolean autoProfile, boolean thicknessEst,
                                         boolean countParticles, boolean areaReport, boolean sampleWhite) {
        int w = in.fieldWidth, h = in.fieldHeight;
        // Prefer cyclic phase→8-bit for thresholding (matches Python RT arrays). Percentile
        // stretch is only a visual preview and shifts Otsu/manual thresholds.
        float[] display = cyclicPhaseDisplay0255(in);
        if (display == null) {
            JOptionPane.showMessageDialog(owner, "No phase display. Reconstruct first.",
                "HoloBio Microstructure", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        byte[] gray = HoloBioMicrostructureMath.toByteGray(display, w, h);
        float[] phaseRad = in.phaseRadians != null ? in.phaseRadians
            : HoloBioMicrostructureMath.displayToPhaseRad(display);

        // Step 1 — process particles (thresholding + watershed + cleaning + detection)
        HoloBioMicrostructureMath.ProcessResult proc = HoloBioMicrostructureMath.processParticles(
            gray, w, h, method, manualThreshold, minArea, maxArea, sampleWhite);
        HoloBioFijiUi.log("[HoloBio Microstructure] particles=" + proc.particles.size()
            + " method=" + method + " white=" + sampleWhite);

        // Step 2 — show detection overlay (circles on mask, like Python visualize_detection_step)
        HoloBioMicrostructureMath.showDetectionOverlay(proc, w, h);

        double umPerPx = pixelUm / mag;
        if (countParticles || areaReport) {
            HoloBioMicrostructureMath.showParticleReports(proc, umPerPx, countParticles, areaReport);
        }
        if (autoProfile) {
            HoloBioMicrostructureMath.runAutomaticPhaseProfiles(gray, w, h, proc, phaseRad, umPerPx);
        }
        if (thicknessEst) {
            if (in.wavelengthUm <= 1e-12) {
                JOptionPane.showMessageDialog(owner, "Set wavelength (µm) in reconstruction parameters.",
                    "HoloBio Microstructure", JOptionPane.WARNING_MESSAGE);
                return;
            }
            HoloBioMicrostructureMath.runThicknessEstimation(gray, w, h, method, manualThreshold,
                sampleWhite, phaseRad, in.wavelengthUm, nSample, nMedium);
        }
        IJ.showStatus("HoloBio: microstructure analysis finished.");
    }

    /** Cyclic (φ+π)/(2π)·255 when raw radians exist; else the provided display buffer. */
    private static float[] cyclicPhaseDisplay0255(HoloBioToolInputs in) {
        if (in.phaseRadians != null && in.fieldWidth > 0 && in.fieldHeight > 0) {
            float[] out = new float[in.fieldWidth * in.fieldHeight];
            float k = (float) (255.0 / (2.0 * Math.PI));
            for (int i = 0; i < out.length; i++) {
                float p = (float) Math.IEEEremainder(in.phaseRadians[i], 2.0 * Math.PI);
                float v = (p + (float) Math.PI) * k;
                out[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
            }
            return out;
        }
        return in.phaseDisplay0255;
    }

    /**
     * Phase in [0, 2π] matching Python QPI:
     * {@code phase_8bit.astype(float) / 255 * 2π} from the cyclic display map.
     */
    private static float[] phaseForQpiPythonParity(HoloBioToolInputs in) {
        float[] disp = cyclicPhaseDisplay0255(in);
        if (disp == null || disp.length == 0) {
            return null;
        }
        float[] out = new float[disp.length];
        for (int i = 0; i < disp.length; i++) {
            out[i] = (float) (disp[i] / 255.0 * 2.0 * Math.PI);
        }
        return out;
    }

    private static void runQpiWithRoiManager(Window hideDlg, HoloBioToolInputs in, int zones, String source,
                                           boolean circleRoi, boolean thicknessMode, double nSample, double nMedium,
                                           double thicknessUm, double pixelUm, double mag) {
        float[] phaseForQpi = phaseForQpiPythonParity(in);

        // Build display ByteProcessor from the user-selected source image
        ByteProcessor bp = buildDisplayBp(in, source, phaseForQpi);
        if (bp == null) {
            JOptionPane.showMessageDialog(hideDlg,
                "No image available for source \"" + source + "\". Reconstruct first.",
                "HoloBio QPI", JOptionPane.ERROR_MESSAGE);
            return;
        }

        RoiManager rm = HoloBioFijiUi.roiManagerForPicking();
        rm.reset();

        String roiLabel = circleRoi ? "circle/oval" : "straight-line";
        String ijTool   = circleRoi ? "oval" : "line";
        ImagePlus imp = new ImagePlus(
            "HoloBio QPI — draw " + roiLabel + " ROIs on " + source + " (" + zones + ")", bp);
        String instr = circleRoi
            ? "Draw " + zones + " oval/circle ROI(s). Press t after each. Then Done."
            : "Draw " + zones + " straight line(s). Press t after each. Then Done.";

        runRoiPickSession(imp, instr, zones, hideDlg, ijTool, roiLabel, ok -> {
            if (!ok) return;
            RoiManager rmDone = RoiManager.getInstance();
            if (rmDone == null || rmDone.getCount() < zones) {
                JOptionPane.showMessageDialog(hideDlg,
                    "Expected " + zones + " " + roiLabel + " ROI(s), found "
                        + (rmDone != null ? rmDone.getCount() : 0) + ".",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            computeAndShowQpiResults(hideDlg, in, phaseForQpi, zones, circleRoi, thicknessMode,
                nSample, nMedium, thicknessUm, pixelUm, mag, rmDone);
        });
    }

    /**
     * Run QPI using shared {@code rois_*.txt} (same file as RT DHM Load ROIs / Python).
     * Lines become straight profiles; rects use the horizontal midline for the plot curve
     * and all pixels inside for Δφ stats via a temporary line ROI for the RoiManager path…
     * Actually rects are converted to midline Lines for the standard QPI plot path.
     */
    private static void runQpiFromRoiFile(Window parent, HoloBioToolInputs in, File roiFile,
                                         String source, boolean thicknessMode,
                                         double nSample, double nMedium, double thicknessUm,
                                         double pixelUm, double mag) throws Exception {
        float[] phaseForQpi = phaseForQpiPythonParity(in);
        if (phaseForQpi == null) {
            JOptionPane.showMessageDialog(parent, "No phase data.",
                    "HoloBio QPI", JOptionPane.ERROR_MESSAGE);
            return;
        }

        HoloBioRtRoiFile.Bundle b = HoloBioRtRoiFile.load(roiFile);
        if (b.rois.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No ROIs in file.",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
            return;
        }

        double sx = 1.0, sy = 1.0;
        if (b.width > 0 && b.height > 0
                && (in.fieldWidth != b.width || in.fieldHeight != b.height)) {
            sx = in.fieldWidth  / (double) b.width;
            sy = in.fieldHeight / (double) b.height;
        }

        RoiManager rm = HoloBioFijiUi.roiManagerForPicking();
        rm.reset();
        int nLines = 0;
        for (HoloBioRtRoiFile.Roi r : b.rois) {
            if (r.rect) {
                // Midline of the rectangle (same convention as RT live plot)
                double x1 = r.x1 * sx, y1 = (r.y1 + 0.5 * r.y2) * sy;
                double x2 = (r.x1 + r.x2) * sx, y2 = y1;
                Line ln = new Line(x1, y1, x2, y2);
                ln.setName(r.name);
                rm.addRoi(ln);
                nLines++;
            } else {
                Line ln = new Line(r.x1 * sx, r.y1 * sy, r.x2 * sx, r.y2 * sy);
                ln.setName(r.name);
                rm.addRoi(ln);
                nLines++;
            }
        }

        // Overlay on the phase pick image so the user sees the shared ROIs
        ByteProcessor bp = buildDisplayBp(in, source, phaseForQpi);
        if (bp != null) {
            ImagePlus imp = new ImagePlus("HoloBio QPI — ROIs from " + roiFile.getName(), bp);
            Overlay ov = new Overlay();
            for (int i = 0; i < rm.getCount(); i++) {
                Roi roi = rm.getRoi(i);
                roi.setStrokeColor(java.awt.Color.YELLOW);
                ov.add(roi);
            }
            imp.setOverlay(ov);
            HoloBioFijiUi.showImagePlus(imp);
        }

        computeAndShowQpiResults(parent, in, phaseForQpi, nLines, false, thicknessMode,
                nSample, nMedium, thicknessUm, pixelUm, mag, rm);
        HoloBioFijiUi.log("[HoloBio QPI] Loaded " + nLines + " ROI(s) from " + roiFile.getName()
                + (sx != 1.0 || sy != 1.0
                    ? String.format(" (scaled ×%.3g/×%.3g to %d×%d)", sx, sy, in.fieldWidth, in.fieldHeight)
                    : ""));
    }

    /** Build a ByteProcessor from the selected display source (Phase / Amplitude / Hologram). */
    private static ByteProcessor buildDisplayBp(HoloBioToolInputs in, String source, float[] phaseForQpi) {
        if ("Amplitude".equals(source) && in.hasAmplitudeDisplay0255()) {
            float[] amp = in.amplitudeDisplay0255;
            byte[] gray = new byte[amp.length];
            for (int i = 0; i < gray.length; i++) gray[i] = (byte) Math.min(255, Math.max(0, (int) amp[i]));
            return new ByteProcessor(in.fieldWidth, in.fieldHeight, gray, null);
        }
        if ("Hologram".equals(source) && in.hasHologram()) {
            // Always resample to the reconstruction field size so ROIs map 1:1 onto phase.
            byte[] gray = resampleHologramToField(in);
            return new ByteProcessor(in.fieldWidth, in.fieldHeight, gray, null);
        }
        // Default: Phase
        return HoloBioToolInputs.phaseToBytePickImage(
            phaseForQpi != null ? phaseForQpi : in.phaseRadians,
            in.fieldWidth, in.fieldHeight);
    }

    /** Nearest-neighbour resize of the hologram onto the reconstructed field grid. */
    private static byte[] resampleHologramToField(HoloBioToolInputs in) {
        float[] holo = in.hologram;
        int hw = in.holoWidth, hh = in.holoHeight;
        int fw = in.fieldWidth, fh = in.fieldHeight;
        float mn = holo[0], mx = holo[0];
        for (float v : holo) { if (v < mn) mn = v; if (v > mx) mx = v; }
        float inv = mx > mn + 1e-12f ? 255f / (mx - mn) : 0f;
        byte[] out = new byte[fw * fh];
        for (int y = 0; y < fh; y++) {
            int sy = Math.min(hh - 1, y * hh / fh);
            for (int x = 0; x < fw; x++) {
                int sx = Math.min(hw - 1, x * hw / fw);
                float v = (holo[sy * hw + sx] - mn) * inv;
                out[y * fw + x] = (byte) Math.min(255, Math.max(0, (int) v));
            }
        }
        return out;
    }

    private static void computeAndShowQpiResults(Window parent, HoloBioToolInputs in, float[] phaseForQpi,
                                                 int zones, boolean circleRoi, boolean thicknessMode,
                                                 double nSample, double nMedium, double thicknessUm,
                                                 double pixelUm, double mag, RoiManager rm) {
        int w = in.fieldWidth;
        int h = in.fieldHeight;
        float[] ph = phaseForQpi;
        double lam = in.wavelengthUm;
        double umPerPx = pixelUm / mag;

        ResultsTable rt = new ResultsTable();
        double[] dphis = new double[zones];
        java.awt.Color[] plotColors = {
            java.awt.Color.BLACK,   java.awt.Color.RED,
            java.awt.Color.BLUE,    new java.awt.Color(0, 140, 0),
            java.awt.Color.MAGENTA, java.awt.Color.ORANGE
        };

        Plot plot = new Plot("HoloBio QPI — Phase Profiles", "Distance (µm)", "Phase (rad)");
        double globMin = Double.POSITIVE_INFINITY, globMax = Double.NEGATIVE_INFINITY;
        double globXMax = 0;
        StringBuilder legend = new StringBuilder();

        for (int zi = 0; zi < zones; zi++) {
            Roi roi = rm.getRoi(zi);
            double[] prof;
            double lenPx;
            if (circleRoi) {
                if (!(roi instanceof OvalRoi)) {
                    JOptionPane.showMessageDialog(parent,
                        "ROI #" + (zi + 1) + " must be an Oval/Circle (use the oval tool).", "HoloBio QPI",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                OvalRoi ov = (OvalRoi) roi;
                double cx = ov.getXBase() + ov.getFloatWidth()  / 2.0;
                double cy = ov.getYBase() + ov.getFloatHeight() / 2.0;
                double r  = 0.25 * (ov.getFloatWidth() + ov.getFloatHeight());
                prof = HoloBioQpiSpeckleMath.profileAlongCircle(ph, w, h, cx, cy, r);
                lenPx = 2.0 * Math.PI * Math.max(1.0, r);
            } else {
                if (!(roi instanceof Line)) {
                    JOptionPane.showMessageDialog(parent,
                        "ROI #" + (zi + 1) + " must be a Straight Line (use the line tool).", "HoloBio QPI",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                Line ln = (Line) roi;
                double x1 = ln.x1, y1 = ln.y1, x2 = ln.x2, y2 = ln.y2;
                prof = HoloBioQpiSpeckleMath.profileAlongLine(ph, w, h, x1, y1, x2, y2);
                lenPx = Math.hypot(x2 - x1, y2 - y1);
            }

            // phaseForQpi is already [0, 2π] (Python QPI parity) — no extra +π
            double[] st = HoloBioQpiSpeckleMath.phaseStats(prof);
            double low = st[0], high = st[1], dphi = st[2];
            dphis[zi] = dphi;

            // Python QPI: dist = np.arange(len(prof)) * μm_per_px
            double[] xs = new double[prof.length];
            double stepUm = umPerPx;
            for (int k = 0; k < prof.length; k++) {
                xs[k] = k * stepUm;
                if (prof[k] < globMin) globMin = prof[k];
                if (prof[k] > globMax) globMax = prof[k];
            }
            if (xs.length > 0) {
                globXMax = Math.max(globXMax, xs[xs.length - 1]);
            }

            java.awt.Color zc = plotColors[zi % plotColors.length];
            plot.setColor(zc);
            plot.setLineWidth(2);
            plot.addPoints(xs, prof, Plot.LINE);
            if (xs.length > 0 && !Double.isNaN(low) && !Double.isNaN(high)) {
                plot.setLineWidth(1);
                plot.setColor(zc);
                plot.drawDottedLine(xs[0], low, xs[xs.length - 1], low, 2);
                plot.drawDottedLine(xs[0], high, xs[xs.length - 1], high, 2);
            }

            String extra = "";
            if (thicknessMode && !Double.isNaN(dphi)) {
                double th = Math.abs(dphi) * lam
                        / (2.0 * Math.PI * Math.max(1e-12, Math.abs(nSample - nMedium)));
                extra = String.format("  t=%.3f µm", th);
            }
            legend.append(String.format("P%d  Δφ=%.3f%s", zi + 1, dphi, extra));
            if (zi < zones - 1) legend.append("\n");

            rt.incrementCounter();
            rt.addLabel("P" + (zi + 1));
            rt.addValue("Zone", zi + 1);
            rt.addValue("φ_low (rad)", low);
            rt.addValue("φ_high (rad)", high);
            rt.addValue("Δφ (rad)", dphi);
            if (thicknessMode) {
                double thickness = Math.abs(dphi) * lam / (2.0 * Math.PI * Math.max(1e-12, Math.abs(nSample - nMedium)));
                rt.addValue("Thickness (µm)", thickness);
            } else {
                double nRel = 2.0 * Math.PI * thicknessUm / (lam * (Math.abs(dphi) > 1e-12 ? dphi : Double.NaN));
                rt.addValue("n_rel", nRel);
            }
        }

        if (zones > 1) {
            double meanD = 0;
            for (double v : dphis) meanD += v;
            meanD /= zones;
            double var = 0;
            for (double v : dphis) { double d = v - meanD; var += d * d; }
            double sd = Math.sqrt(var / Math.max(1, zones - 1));
            HoloBioFijiUi.log(String.format("[HoloBio QPI] Mean Δφ: %.4f ± %.4f rad over %d zones", meanD, sd, zones));
        }

        if (globMax > globMin && !Double.isInfinite(globMin)) {
            double pad = Math.max(0.05, 0.12 * (globMax - globMin));
            plot.setLimits(0, Math.max(globXMax, 1e-6),
                    Math.max(0, globMin - pad), Math.min(2 * Math.PI, globMax + pad));
        }
        plot.setColor(java.awt.Color.BLACK);
        plot.setLineWidth(1);
        plot.addLegend(legend.toString());
        plot.show();

        rt.show("HoloBio QPI");
        HoloBioFijiUi.log("[HoloBio QPI] zones=" + zones + " thicknessMode=" + thicknessMode
            + " lambda_um=" + lam + " um_per_px=" + umPerPx);
        IJ.showStatus("HoloBio: QPI results in table and phase profile plot.");
    }

    private static void addWest(JPanel parent, Component child, GridBagConstraints c) {
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        parent.add(child, c);
        c.gridy++;
    }

    private static JPanel flowLeft() {
        return HoloBioUiStyle.flowLeft();
    }

    private static JPanel compactTabPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(HoloBioUiStyle.contentPad());
        return p;
    }

    private static void addTabSectionTitle(JPanel tab, String title) {
        JLabel section = HoloBioUiStyle.sectionTitle(title);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        tab.add(section);
        tab.add(Box.createVerticalStrut(6));
    }

    /** Speckle dialog — compact filter / compare / measure tabs. */
    public static void showSpeckleDialog(Frame owner, HoloBioToolInputs inputs, HoloBioToolCallbacks callbacks) {
        JDialog dlg = new JDialog(owner, "HoloBio — Speckle", false);
        dlg.setLayout(new BorderLayout(0, 0));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(HoloBioUiStyle.emptyPad(
            HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_4, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_4));
        JLabel dlgTitle = HoloBioUiStyle.mainTitle("Speckle");
        dlgTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(dlgTitle);
        JLabel dlgSteps = HoloBioUiStyle.workflowSteps("Filter  →  Compare  →  Measure");
        dlgSteps.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(dlgSteps);
        JLabel statusLabel = HoloBioUiStyle.statusHtml(" ");
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_1));
        header.add(statusLabel);

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);

        // —— Filter ——
        JPanel tabFilter = compactTabPanel();
        addTabSectionTitle(tabFilter, "Reduce noise");
        JRadioButton filtAmp = new JRadioButton("Amplitude", true);
        JRadioButton filtPhs = new JRadioButton("Phase");
        ButtonGroup filtDim = new ButtonGroup();
        filtDim.add(filtAmp);
        filtDim.add(filtPhs);
        JPanel dimRow = flowLeft();
        dimRow.add(HoloBioUiStyle.fieldLabel("Channel"));
        dimRow.add(filtAmp);
        dimRow.add(filtPhs);
        tabFilter.add(dimRow);

        JRadioButton rbHmf = new JRadioButton("HMF", true);
        JRadioButton rbSpp = new JRadioButton("SPP");
        ButtonGroup filtMethod = new ButtonGroup();
        filtMethod.add(rbHmf);
        filtMethod.add(rbSpp);
        JPanel methodRow = flowLeft();
        methodRow.add(HoloBioUiStyle.fieldLabel("Method"));
        methodRow.add(rbHmf);
        methodRow.add(rbSpp);
        tabFilter.add(methodRow);

        JPanel iterRow = flowLeft();
        JTextField tfIterations = new JTextField(String.valueOf(HoloBioSpeckleDefaults.FILTER_ITERATIONS), 4);
        HoloBioUiStyle.styleField(tfIterations);
        tfIterations.setToolTipText("Integer ≥ 1.");
        iterRow.add(HoloBioUiStyle.fieldLabel("Iterations"));
        iterRow.add(tfIterations);
        tabFilter.add(iterRow);

        JButton applyFilt = HoloBioUiStyle.primaryButton("Apply filter");
        JPanel filtBtnRow = flowLeft();
        filtBtnRow.add(applyFilt);
        tabFilter.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        tabFilter.add(filtBtnRow);
        tabs.addTab("Filter", tabFilter);

        // —— Compare ——
        JPanel tabCompare = compactTabPanel();
        addTabSectionTitle(tabCompare, "Comparison");
        JCheckBox ckSide = new JCheckBox("Side by side", true);
        JCheckBox ckPlot = new JCheckBox("Speckle plot", false);
        JCheckBox ckProf = new JCheckBox("Line profile", false);
        tabCompare.add(ckSide);
        tabCompare.add(ckPlot);
        tabCompare.add(ckProf);
        JButton applyCmp = HoloBioUiStyle.primaryButton("Apply");
        applyCmp.setEnabled(false);
        JPanel cmpBtnRow = flowLeft();
        cmpBtnRow.add(applyCmp);
        tabCompare.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        tabCompare.add(cmpBtnRow);
        tabs.addTab("Compare", tabCompare);

        // —— Measure (on Filter-tab channel, after Apply filter) ——
        JPanel tabMeasure = compactTabPanel();
        addTabSectionTitle(tabMeasure, "Contrast measure");
        JLabel measureHint = HoloBioUiStyle.hintHtml(
            "Uses the <b>filtered</b> image from the Filter tab (Amplitude or Phase), not the raw reconstruction.",
            280);
        measureHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabMeasure.add(measureHint);
        tabMeasure.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));

        JPanel grid = new JPanel(new GridLayout(2, 3, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1));
        JTextField tfZones = new JTextField(String.valueOf(HoloBioSpeckleDefaults.ZONES), 3);
        JTextField tfRows = new JTextField(String.valueOf(HoloBioSpeckleDefaults.ROWS), 3);
        JTextField tfCols = new JTextField(String.valueOf(HoloBioSpeckleDefaults.COLS), 3);
        HoloBioUiStyle.styleField(tfZones);
        HoloBioUiStyle.styleField(tfRows);
        HoloBioUiStyle.styleField(tfCols);
        tfZones.setToolTipText("Integer ≥ 1.");
        tfRows.setToolTipText("Integer ≥ 1.");
        tfCols.setToolTipText("Integer ≥ 1.");
        grid.add(HoloBioUiStyle.fieldLabel("Zones"));
        grid.add(HoloBioUiStyle.fieldLabel("Rows"));
        grid.add(HoloBioUiStyle.fieldLabel("Cols"));
        grid.add(tfZones);
        grid.add(tfRows);
        grid.add(tfCols);
        tabMeasure.add(grid);

        JButton applyMeas = HoloBioUiStyle.primaryButton("Apply");
        applyMeas.setEnabled(false);
        JPanel measBtnRow = flowLeft();
        measBtnRow.add(applyMeas);
        tabMeasure.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        tabMeasure.add(measBtnRow);
        tabs.addTab("Measure", tabMeasure);

        Runnable refreshStatus = () -> {
            boolean ampCh = filtAmp.isSelected();
            HoloBioSpeckleState st = callbacks != null ? callbacks.getSpeckleState() : null;
            boolean filtered = st != null && st.hasFiltered(ampCh);
            applyCmp.setEnabled(filtered);
            applyMeas.setEnabled(filtered);
            if (inputs == null || !inputs.hasAmplitudeDisplay0255()) {
                statusLabel.setText("<html>Reconstruct first, then apply a filter.</html>");
            } else if (filtered) {
                statusLabel.setText("<html>Filter applied — open <b>Compare</b> or <b>Measure</b>.</html>");
            } else {
                statusLabel.setText("<html>Ready — start on <b>Filter</b>.</html>");
            }
        };

        filtAmp.addActionListener(e -> refreshStatus.run());
        filtPhs.addActionListener(e -> refreshStatus.run());

        applyFilt.addActionListener(e -> {
            if (callbacks == null || inputs == null) {
                JOptionPane.showMessageDialog(dlg, "No reconstruction data.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean ampCh = filtAmp.isSelected();
            if (ampCh && !inputs.hasAmplitudeDisplay0255()) {
                JOptionPane.showMessageDialog(dlg, "Reconstruct first (amplitude).", "HoloBio Speckle",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!ampCh && !inputs.hasPhaseDisplay0255()) {
                JOptionPane.showMessageDialog(dlg, "Reconstruct first (phase).", "HoloBio Speckle",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int active = rbSpp.isSelected() ? 1 : 0;
            if (active == 1 && !inputs.hasComplexField()) {
                JOptionPane.showMessageDialog(dlg, "SPP needs a complex field — reconstruct first.",
                    "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            try {
                int p = Integer.parseInt(tfIterations.getText().trim());
                if (p < 1) {
                    throw new NumberFormatException();
                }
                final int activeF = active;
                final int pF = p;
                final boolean ampChF = ampCh;
                SwingUtilities.invokeLater(() -> {
                    applySpeckleFilter(owner, inputs, callbacks, ampChF, activeF, pF);
                    refreshStatus.run();
                    tabs.setSelectedIndex(1);
                });
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Iterations must be an integer ≥ 1.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
            }
        });

        applyCmp.addActionListener(e -> {
            if (callbacks == null || inputs == null) {
                return;
            }
            boolean ampCh = filtAmp.isSelected();
            boolean side = ckSide.isSelected();
            boolean plot = ckPlot.isSelected();
            boolean prof = ckProf.isSelected();
            if (!side && !plot && !prof) {
                JOptionPane.showMessageDialog(dlg, "Select at least one option.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            SwingUtilities.invokeLater(() -> runSpeckleComparison(dlg, inputs, callbacks, ampCh, side, plot, false, prof));
        });

        applyMeas.addActionListener(e -> {
            if (callbacks == null) {
                return;
            }
            boolean ampCh = filtAmp.isSelected();
            HoloBioSpeckleState st = callbacks.getSpeckleState();
            if (st == null || !st.hasFiltered(ampCh)) {
                JOptionPane.showMessageDialog(dlg,
                    "Apply a speckle filter on the Filter tab first (same Amplitude/Phase channel).",
                    "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            try {
                int z = Integer.parseInt(tfZones.getText().trim());
                int r = Integer.parseInt(tfRows.getText().trim());
                int c = Integer.parseInt(tfCols.getText().trim());
                if (z < 1 || r < 1 || c < 1) {
                    throw new NumberFormatException();
                }
                final boolean ampChF = ampCh;
                SwingUtilities.invokeLater(() -> runSpeckleMeasurements(dlg, callbacks, ampChF, z, r, c));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Zones, Rows, Cols must be integers ≥ 1.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
            }
        });

        JButton close = HoloBioUiStyle.tertiaryButton("Close");
        close.addActionListener(e -> dlg.dispose());
        JPanel bottom = HoloBioUiStyle.buildFooter(
            "Filter first, then Compare or Measure.", close);

        dlg.add(header, BorderLayout.NORTH);
        dlg.add(tabs, BorderLayout.CENTER);
        dlg.add(bottom, BorderLayout.SOUTH);
        refreshStatus.run();
        dlg.pack();
        HoloBioUiStyle.polish(dlg);
        Dimension d = dlg.getSize();
        int w = Math.min(380, Math.max(320, d.width));
        int h = Math.min(400, Math.max(320, d.height));
        dlg.setSize(w, h);
        dlg.setMinimumSize(new Dimension(280, 300));
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    private static void applySpeckleFilter(Frame owner, HoloBioToolInputs in, HoloBioToolCallbacks cb,
                                           boolean amplitudeChannel, int method, int iterations) {
        HoloBioSpeckleState st = cb.getSpeckleState();
        if (st != null) {
            st.clearIterations();
        }
        int w = in.fieldWidth;
        int h = in.fieldHeight;
        if (st != null) {
            st.setFieldSize(w, h);
        }

        if (method == 1) {
            float[] re;
            float[] im;
            if (st != null && st.getOriginalFieldRe() != null && st.getOriginalFieldIm() != null) {
                re = st.getOriginalFieldRe().clone();
                im = st.getOriginalFieldIm().clone();
            } else {
                re = in.fieldRe.clone();
                im = in.fieldIm.clone();
                if (st != null) {
                    st.setOriginalField(re, im);
                }
            }
            HoloBioQpiSpeckleMath.SppIterations spp =
                HoloBioQpiSpeckleMath.sppFilter(re, im, w, h, iterations);
            int last = spp.rePerIteration.size() - 1;
            float[] fre = spp.rePerIteration.get(last);
            float[] fim = spp.imPerIteration.get(last);
            float[] origBase = HoloBioToolInputs.speckleChannelBase0255(in, amplitudeChannel);
            if (st != null) {
                if (amplitudeChannel) {
                    st.ensureOriginalAmplitude(origBase);
                } else {
                    st.ensureOriginalPhase(origBase);
                }
                st.setSppIterations(spp.rePerIteration, spp.imPerIteration);
            }
            float[] display = amplitudeChannel
                ? HoloBioQpiSpeckleMath.complexToAmplitudeMinMax0255(fre, fim)
                : HoloBioQpiSpeckleMath.complexToPhaseMinMax0255(fre, fim);
            FloatProcessor fp = HoloBioQpiSpeckleMath.toFloatProcessor0255(display, w, h);
            cb.onSpeckleSppResult(fre, fim, w, h, amplitudeChannel, fp);
            HoloBioFijiUi.log("[HoloBio Speckle filter] SPP iterations=" + iterations + " channel="
                + (amplitudeChannel ? "amplitude" : "phase"));
        } else {
            float[] base = HoloBioToolInputs.speckleChannelBase0255(in, amplitudeChannel);
            if (base == null) {
                JOptionPane.showMessageDialog(owner, "No reconstruction for speckle filter.",
                    "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            base = base.clone();
            if (st != null) {
                if (amplitudeChannel) {
                    st.ensureOriginalAmplitude(base);
                } else {
                    st.ensureOriginalPhase(base);
                }
            }
            List<float[]> iters = HoloBioQpiSpeckleMath.hybridMedianMeanIterations(base, w, h, iterations);
            float[] out = iters.get(iters.size() - 1);
            if (st != null) {
                st.setHmfIterations(iters, amplitudeChannel);
            }
            FloatProcessor fp = HoloBioQpiSpeckleMath.toFloatProcessor0255(out, w, h);
            cb.onSpeckleFilterResult(amplitudeChannel, fp);
            HoloBioFijiUi.log("[HoloBio Speckle filter] HMF iterations=" + iterations + " channel="
                + (amplitudeChannel ? "amplitude" : "phase"));
        }
        IJ.showStatus("HoloBio: speckle filter applied.");
    }

    private static ImagePlus bytePickImage(float[] display0255, int w, int h, String title) {
        ByteProcessor bp = new ByteProcessor(w, h);
        for (int i = 0; i < display0255.length; i++) {
            int v = (int) Math.round(Math.max(0, Math.min(255, display0255[i])));
            bp.putPixel(i % w, i / w, v);
        }
        return new ImagePlus(title, bp);
    }

    /**
     * Non-blocking ROI pick: shows image + ROI Manager, helper with Done/Cancel.
     * Hides {@code hideWhilePicking} so clicks reach the image (not blocked by modal dialogs).
     */
    private static void runRoiPickSession(ImagePlus imp, String instructions, int minRoiCount,
                                          Window hideWhilePicking, Consumer<Boolean> whenFinished) {
        runRoiPickSession(imp, instructions, minRoiCount, hideWhilePicking, "rectangle", "rectangle", whenFinished);
    }

    private static void runRoiPickSession(ImagePlus imp, String instructions, int minRoiCount,
                                          Window hideWhilePicking, String ijTool, String roiKindLabel,
                                          Consumer<Boolean> whenFinished) {
        if (imp == null) {
            whenFinished.accept(false);
            return;
        }
        if (minRoiCount <= 1) {
            runSingleRoiPickSession(imp, instructions, hideWhilePicking, ijTool, whenFinished);
            return;
        }
        if (hideWhilePicking != null) {
            hideWhilePicking.setVisible(false);
        }
        RoiManager rm = HoloBioFijiUi.roiManagerForPicking();
        rm.reset();

        HoloBioFijiUi.showImagePlus(imp);
        IJ.selectWindow(imp.getTitle());
        IJ.setTool(ijTool != null ? ijTool : "rectangle");
        Window iw = imp.getWindow();
        if (iw != null) {
            iw.toFront();
            iw.requestFocus();
        }

        String helperTitle = "line".equals(ijTool) ? "HoloBio — QPI line ROIs" : "HoloBio — ROI selection";
        JDialog helper = new JDialog((Frame) null, helperTitle, false);
        helper.setAlwaysOnTop(true);
        helper.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2));
        JLabel lab = new JLabel("<html><body style='width:300px'>" + instructions + "</body></html>");
        lab.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3));
        helper.add(lab, BorderLayout.CENTER);

        JLabel countLabel = new JLabel("ROIs added: 0 / " + minRoiCount);
        countLabel.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3));
        helper.add(countLabel, BorderLayout.NORTH);

        JButton done = HoloBioUiStyle.primaryButton("Done");
        JButton cancel = HoloBioUiStyle.secondaryButton("Cancel");
        helper.add(HoloBioUiStyle.buildFooter("Press t after each ROI.", cancel, done), BorderLayout.SOUTH);

        // Poll ROI Manager every 250 ms; log and update label each time a new ROI is added
        final int[] lastCount = {0};
        javax.swing.Timer pollTimer = new javax.swing.Timer(250, ev -> {
            RoiManager rmNow = RoiManager.getInstance();
            int n = rmNow != null ? rmNow.getCount() : 0;
            if (n != lastCount[0]) {
                lastCount[0] = n;
                countLabel.setText("ROIs added: " + n + " / " + minRoiCount);
                if (n > 0) {
                    HoloBioFijiUi.log("[HoloBio QPI] Line ROI " + n + " added  (need " + minRoiCount + ").");
                    IJ.showStatus("HoloBio QPI: " + n + " / " + minRoiCount + " ROI(s) added.");
                }
            }
        });
        pollTimer.start();

        Runnable closePick = () -> {
            pollTimer.stop();
            helper.dispose();
            imp.changes = false;
            imp.close();
            HoloBioFijiUi.hideRoiManager();
            if (hideWhilePicking != null) {
                hideWhilePicking.setVisible(true);
                hideWhilePicking.toFront();
            }
        };

        done.addActionListener(e -> {
            RoiManager rmDone = RoiManager.getInstance();
            boolean ok = rmDone != null && rmDone.getCount() >= minRoiCount;
            if (!ok) {
                String kind = roiKindLabel != null ? roiKindLabel : "ROI";
                JOptionPane.showMessageDialog(helper,
                    "Add at least " + minRoiCount + " " + kind + "(s) on the image (press \"t\" after each), then Done.",
                    helperTitle, JOptionPane.WARNING_MESSAGE);
                return;
            }
            closePick.run();
            whenFinished.accept(true);
        });
        cancel.addActionListener(e -> {
            closePick.run();
            whenFinished.accept(false);
        });

        helper.pack();
        if (iw != null) {
            helper.setLocation(iw.getX() + 20, iw.getY() + iw.getHeight() - helper.getHeight() - 20);
        } else {
            helper.setLocationRelativeTo(null);
        }
        helper.setVisible(true);
        IJ.showStatus("HoloBio: draw ROI on the image window, then click Done in the helper.");
    }

    /** One ROI on the image — no ROI Manager window. */
    private static void runSingleRoiPickSession(ImagePlus imp, String instructions, Window hideWhilePicking,
                                                String ijTool, Consumer<Boolean> whenFinished) {
        if (hideWhilePicking != null) {
            hideWhilePicking.setVisible(false);
        }
        HoloBioFijiUi.showImagePlus(imp);
        IJ.selectWindow(imp.getTitle());
        IJ.setTool(ijTool != null ? ijTool : "rectangle");
        Window iw = imp.getWindow();
        if (iw != null) {
            iw.toFront();
            iw.requestFocus();
        }

        JDialog helper = new JDialog((Frame) null, "HoloBio — ROI selection", false);
        helper.setAlwaysOnTop(true);
        helper.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2));
        JLabel lab = new JLabel("<html><body style='width:300px'>" + instructions + "</body></html>");
        lab.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3));
        helper.add(lab, BorderLayout.CENTER);
        JButton done = HoloBioUiStyle.primaryButton("Done");
        JButton cancel = HoloBioUiStyle.secondaryButton("Cancel");
        helper.add(HoloBioUiStyle.buildFooter("Draw on the image, then Done.", cancel, done), BorderLayout.SOUTH);

        Runnable closePick = () -> {
            helper.dispose();
            imp.changes = false;
            imp.close();
            HoloBioFijiUi.hideRoiManager();
            if (hideWhilePicking != null) {
                hideWhilePicking.setVisible(true);
                hideWhilePicking.toFront();
            }
        };

        done.addActionListener(e -> {
            Roi roi = imp.getRoi();
            if (roi == null) {
                JOptionPane.showMessageDialog(helper,
                    "Draw a region on the image first, then Done.", "HoloBio — ROI selection",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            HoloBioFijiUi.roiManagerForPicking().reset();
            HoloBioFijiUi.roiManagerForPicking().addRoi(roi);
            closePick.run();
            whenFinished.accept(true);
        });
        cancel.addActionListener(e -> {
            closePick.run();
            whenFinished.accept(false);
        });

        helper.pack();
        if (iw != null) {
            helper.setLocation(iw.getX() + 20, iw.getY() + iw.getHeight() - helper.getHeight() - 20);
        } else {
            helper.setLocationRelativeTo(null);
        }
        helper.setVisible(true);
        IJ.showStatus("HoloBio: draw on the image, then click Done.");
    }

    private static void runSpeckleComparison(Window hideDlg, HoloBioToolInputs in, HoloBioToolCallbacks cb,
                                             boolean amplitudeChannel, boolean sideBySide,
                                             boolean specklePlot, boolean repickPlotRegion, boolean profile) {
        HoloBioSpeckleState st = cb.getSpeckleState();
        float[] original = st.getOriginal(amplitudeChannel);
        float[] filtered = st.getFiltered(amplitudeChannel);
        if (original == null && in != null) {
            original = HoloBioToolInputs.speckleChannelBase0255(in, amplitudeChannel);
        }
        if (original == null || filtered == null) {
            JOptionPane.showMessageDialog(hideDlg, "Missing original or filtered image for comparison.",
                "HoloBio Speckle", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int w = in.fieldWidth;
        int h = in.fieldHeight;

        if (sideBySide) {
            showSideBySide(original, filtered, w, h);
        }
        if (profile) {
            pickRegionAndShowProfile(hideDlg, original, filtered, w, h);
        }
        if (specklePlot) {
            if (!st.hasIterations()) {
                JOptionPane.showMessageDialog(hideDlg,
                    "No iteration data. Apply HMF or SPP first.", "HoloBio Speckle",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            pickRegionForSpecklePlot(hideDlg, in, st, w, h, amplitudeChannel, repickPlotRegion);
        }
    }

    private static void showSideBySide(float[] original, float[] filtered, int w, int h) {
        FloatProcessor left = HoloBioQpiSpeckleMath.toFloatProcessor0255(original, w, h);
        FloatProcessor right = HoloBioQpiSpeckleMath.toFloatProcessor0255(filtered, w, h);
        ImageProcessor wide = new FloatProcessor(2 * w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                wide.setf(x, y, left.getf(x, y));
                wide.setf(x + w, y, right.getf(x, y));
            }
        }
        ImagePlus imp = new ImagePlus("Speckle — Original | Filtered", wide);
        imp.setDisplayRange(0, 255);
        HoloBioFijiUi.showImagePlus(imp);
        HoloBioFijiUi.log("[HoloBio Speckle] Side-by-side: left=original, right=filtered.");
    }

    private static void pickRegionForSpecklePlot(Window hideDlg, HoloBioToolInputs in,
                                                 HoloBioSpeckleState st, int w, int h,
                                                 boolean amplitudeChannel, boolean forceRepick) {
        if (!forceRepick && st.hasPlotRegion()) {
            showSpeckleContrastPlot(st);
            return;
        }
        float[] pick = st.getOriginal(amplitudeChannel);
        if (pick == null) {
            pick = HoloBioToolInputs.speckleChannelBase0255(in, amplitudeChannel);
        }
        if (pick == null) {
            JOptionPane.showMessageDialog(hideDlg,
                "No " + (amplitudeChannel ? "amplitude" : "phase") + " image for plot region.",
                "HoloBio Speckle", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runSpecklePlotRegionPick(hideDlg, st, pick, w, h);
    }

    /**
     * Python {@code select_speckle_region} + {@code compare_speckle_plot_var}: one rectangle on the image.
     */
    private static void runSpecklePlotRegionPick(Window hideDlg, HoloBioSpeckleState st,
                                               float[] display0255, int w, int h) {
        if (hideDlg != null) {
            hideDlg.setVisible(false);
        }
        ImagePlus imp = bytePickImage(display0255, w, h,
            "Speckle plot — draw 1 rectangle (" + st.getIterationsType().toUpperCase() + ")");
        HoloBioFijiUi.showImagePlus(imp);
        IJ.selectWindow(imp.getTitle());
        IJ.setTool("rectangle");

        JDialog helper = new JDialog((Frame) null, "HoloBio — Plot region", false);
        helper.setAlwaysOnTop(true);
        helper.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_2));
        JLabel lab = new JLabel("Draw one rectangle, then Plot.");
        lab.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3));
        helper.add(lab, BorderLayout.CENTER);
        JButton useRegion = HoloBioUiStyle.primaryButton("Plot");
        JButton cancel = HoloBioUiStyle.secondaryButton("Cancel");
        helper.add(HoloBioUiStyle.buildFooter("A rectangle of at least 2×2 px is required.", cancel, useRegion), BorderLayout.SOUTH);

        Runnable closePick = () -> {
            helper.dispose();
            imp.changes = false;
            imp.close();
            if (hideDlg != null) {
                hideDlg.setVisible(true);
                hideDlg.toFront();
            }
        };

        useRegion.addActionListener(e -> {
            Roi roi = imp.getRoi();
            if (roi == null || roi.getBounds().width < 2 || roi.getBounds().height < 2) {
                JOptionPane.showMessageDialog(helper,
                    "Draw a rectangle on the speckle image first.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            Rectangle b = roi.getBounds();
            st.setPlotRegion(clamp(b.x, 0, w), clamp(b.x + b.width, 0, w),
                clamp(b.y, 0, h), clamp(b.y + b.height, 0, h));
            closePick.run();
            showSpeckleContrastPlot(st);
        });
        cancel.addActionListener(e -> closePick.run());

        helper.pack();
        Window iw = imp.getWindow();
        if (iw != null) {
            helper.setLocation(iw.getX() + 20, iw.getY() + 20);
        }
        helper.setVisible(true);
    }

    private static void showSpeckleContrastPlot(HoloBioSpeckleState st) {
        int x1 = st.getPlotXStart();
        int x2 = st.getPlotXEnd();
        int y1 = st.getPlotYStart();
        int y2 = st.getPlotYEnd();
        List<Double> rawContrasts = computeIterationContrasts(st, x1, y1, x2, y2);
        if (rawContrasts.isEmpty()) {
            HoloBioFijiUi.log("[HoloBio Speckle] No contrasts computed for plot.");
            return;
        }
        String type = st.getIterationsType() != null ? st.getIterationsType().toUpperCase() : "?";
        double c0 = rawContrasts.get(0);
        if (c0 <= 0) {
            HoloBioFijiUi.log("[HoloBio Speckle] Warning: first-iteration contrast is zero; normalization skipped.");
        }
        double[] xIter = new double[rawContrasts.size()];
        double[] yNorm = new double[rawContrasts.size()];
        ResultsTable rt = new ResultsTable();
        for (int i = 0; i < rawContrasts.size(); i++) {
            double raw = rawContrasts.get(i);
            double norm = c0 > 0 ? raw / c0 : raw;
            xIter[i] = i + 1;
            yNorm[i] = norm;
            rt.incrementCounter();
            rt.addValue("Iteration", i + 1);
            rt.addValue("Contrast_raw", raw);
            rt.addValue("Contrast_norm", norm);
        }
        rt.show("HoloBio Speckle Plot Data");

        Plot plot = new Plot("Normalized Speckle Contrast (" + type + ")",
            "Iteration", "Normalized contrast (÷ iter. 1)", xIter, yNorm);
        plot.setLimits(1, rawContrasts.size(), 0, Math.max(1.05, maxOf(yNorm) * 1.1));
        plot.setFrameSize(560, 380);
        plot.show();
        HoloBioFijiUi.log(String.format("[HoloBio Speckle] Plot %s: region X(%d-%d) Y(%d-%d), %d iterations",
            type, x1, x2, y1, y2, rawContrasts.size()));
        IJ.showStatus("HoloBio: speckle contrast plot + table \"HoloBio Speckle Plot Data\".");
    }

    /** Per-iteration contrast in the plot ROI (Python {@code compare_speckle_plot_var}). */
    private static List<Double> computeIterationContrasts(HoloBioSpeckleState st,
                                                          int x1, int y1, int x2, int y2) {
        List<Double> contrasts = new ArrayList<>();
        String type = st.getIterationsType();
        int w = st.getFieldW();
        if (x2 <= x1 || y2 <= y1 || w <= 0) {
            return contrasts;
        }
        if ("hmf".equals(type)) {
            // Python: std/mean on intensity in ROI for each HMF snapshot (incl. iteration 0 = input).
            for (float[] img : st.getHmfIterations()) {
                contrasts.add(HoloBioQpiSpeckleMath.hmfRegionContrast(img, w, x1, y1, x2, y2));
            }
        } else if ("spp".equals(type)) {
            // Python calc_speckle_contrast: std(|U|²)/mean(|U|²) on complex field per SPP iteration.
            List<float[]> reList = st.getSppReIterations();
            List<float[]> imList = st.getSppImIterations();
            for (int i = 0; i < reList.size(); i++) {
                contrasts.add(HoloBioQpiSpeckleMath.sppRegionContrast(
                    reList.get(i), imList.get(i), w, x1, y1, x2, y2));
            }
        }
        return contrasts;
    }

    private static double maxOf(double[] a) {
        double m = 0;
        for (double v : a) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    private static void pickRegionAndShowProfile(Window hideDlg, float[] original, float[] filtered, int w, int h) {
        ImagePlus imp = bytePickImage(original, w, h, "Speckle profile — draw 1 rectangle ROI");
        String instr = "Draw one rectangle, then Done.";
        runRoiPickSession(imp, instr, 1, hideDlg, ok -> {
            if (!ok) {
                return;
            }
            RoiManager rm = RoiManager.getInstance();
            if (rm == null || rm.getCount() < 1) {
                return;
            }
            Rectangle b = rm.getRoi(0).getBounds();
            int x1 = clamp(b.x, 0, w);
            int x2 = clamp(b.x + b.width, 0, w);
            int y1 = clamp(b.y, 0, h);
            int y2 = clamp(b.y + b.height, 0, h);
            if (x2 <= x1 || y2 <= y1) {
                return;
            }
            int len = x2 - x1;
            double[] xPos = new double[len];
            double[] profOrig = new double[len];
            double[] profFilt = new double[len];
            for (int col = 0; col < len; col++) {
                int xx = x1 + col;
                xPos[col] = xx;
                double sO = 0;
                double sF = 0;
                for (int yy = y1; yy < y2; yy++) {
                    int idx = yy * w + xx;
                    sO += original[idx];
                    sF += filtered[idx];
                }
                profOrig[col] = sO;
                profFilt[col] = sF;
            }
            Plot p1 = new Plot("Original — vertical sum profile", "Pixel", "Sum", xPos, profOrig);
            p1.show();
            Plot p2 = new Plot("Filtered — vertical sum profile", "Pixel", "Sum", xPos, profFilt);
            p2.show();
            ResultsTable rt = new ResultsTable();
            for (int i = 0; i < len; i++) {
                rt.incrementCounter();
                rt.addValue("Pixel", xPos[i]);
                rt.addValue("Original", profOrig[i]);
                rt.addValue("Filtered", profFilt[i]);
            }
            rt.show("HoloBio Speckle Profile");
        });
    }

    /**
     * Zone contrast on the speckle-filtered 0–255 image for the Filter-tab channel (HMF/SPP output).
     */
    private static void runSpeckleMeasurements(Window hideDlg, HoloBioToolCallbacks cb, boolean amplitudeChannel,
                                               int zones, int rows, int cols) {
        HoloBioSpeckleState st = cb != null ? cb.getSpeckleState() : null;
        if (st == null || !st.hasFiltered(amplitudeChannel)) {
            JOptionPane.showMessageDialog(hideDlg,
                "No filtered image — apply HMF or SPP on the Filter tab first.",
                "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        float[] filtered = st.getFiltered(amplitudeChannel);
        int w = st.getFieldW();
        int h = st.getFieldH();
        if (filtered == null || w <= 0 || h <= 0 || filtered.length != w * h) {
            JOptionPane.showMessageDialog(hideDlg, "Filtered image size mismatch — re-apply the filter.",
                "HoloBio Speckle", JOptionPane.WARNING_MESSAGE);
            return;
        }
        float[] data = filtered.clone();
        String title = amplitudeChannel ? "Speckle — Filtered amplitude" : "Speckle — Filtered phase";
        runSpeckleZonePicker(hideDlg, data, data.clone(), w, h, title, zones, rows, cols);
    }

    /**
     * Python {@code apply_speckle}: draw each zone rectangle on the image; subdivide into rows×cols automatically.
     */
    private static void runSpeckleZonePicker(Window hideDlg, float[] rawData, float[] display0255,
                                           int w, int h, String titleBase, int zoneCount, int rows, int cols) {
        if (rawData == null || display0255 == null || w <= 0 || h <= 0) {
            return;
        }
        if (hideDlg != null) {
            hideDlg.setVisible(false);
        }

        ImagePlus imp = bytePickImage(display0255, w, h, titleBase + " — zone 1/" + zoneCount);
        Overlay overlay = new Overlay();
        imp.setOverlay(overlay);
        HoloBioFijiUi.showImagePlus(imp);
        IJ.selectWindow(imp.getTitle());
        IJ.setTool("rectangle");

        final List<Double> contrasts = new ArrayList<>();
        final int[] nextLabel = {1};
        final int[] zonesDone = {0};

        JDialog helper = new JDialog((Frame) null, "HoloBio — Speckle zones", false);
        helper.setAlwaysOnTop(true);
        helper.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2));
        JLabel status = new JLabel("Zone 1 / " + zoneCount + " — draw rectangle, then Record");
        status.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3));
        helper.add(status, BorderLayout.NORTH);
        JButton record = HoloBioUiStyle.primaryButton("Record");
        JButton finish = HoloBioUiStyle.secondaryButton("Results");
        finish.setEnabled(false);
        JButton cancel = HoloBioUiStyle.tertiaryButton("Cancel");
        helper.add(HoloBioUiStyle.buildFooter("Each zone is split into " + rows + "×" + cols + " cells.",
            cancel, finish, record), BorderLayout.SOUTH);

        Runnable restoreParent = () -> {
            helper.dispose();
            imp.changes = false;
            imp.close();
            if (hideDlg != null) {
                hideDlg.setVisible(true);
                hideDlg.toFront();
            }
        };

        record.addActionListener(e -> {
            if (zonesDone[0] >= zoneCount) {
                return;
            }
            Roi roi = imp.getRoi();
            if (roi == null || roi.getBounds().width < 2 || roi.getBounds().height < 2) {
                JOptionPane.showMessageDialog(helper,
                    "Draw a rectangle on the speckle image first (rectangle tool).",
                    "HoloBio Speckle", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Rectangle b = roi.getBounds();
            int x1 = clamp(b.x, 0, w);
            int y1 = clamp(b.y, 0, h);
            int x2 = clamp(b.x + b.width, 0, w);
            int y2 = clamp(b.y + b.height, 0, h);
            if (x2 - x1 < 2 || y2 - y1 < 2) {
                JOptionPane.showMessageDialog(helper, "ROI too small.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            double[] cellSc = HoloBioQpiSpeckleMath.subdivideZoneContrasts(rawData, w, h, x1, y1, x2, y2, rows, cols);
            if (cellSc.length == 0) {
                JOptionPane.showMessageDialog(helper,
                    "Zone too small for " + rows + "×" + cols + " subdivision.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            int wSub = (x2 - x1) / cols;
            int hSub = (y2 - y1) / rows;
            for (int r = 0; r <= rows; r++) {
                int yLine = y1 + r * hSub;
                overlay.add(new Line(x1, yLine, x2, yLine));
            }
            for (int c = 0; c <= cols; c++) {
                int xLine = x1 + c * wSub;
                overlay.add(new Line(xLine, y1, xLine, y2));
            }
            for (int rr = 0; rr < rows; rr++) {
                for (int cc = 0; cc < cols; cc++) {
                    int xx1 = x1 + cc * wSub;
                    int yy1 = y1 + rr * hSub;
                    int xx2 = Math.min(xx1 + wSub, w);
                    int yy2 = Math.min(yy1 + hSub, h);
                    contrasts.add(cellSc[rr * cols + cc]);
                    int cx = (xx1 + xx2) / 2;
                    int cy = (yy1 + yy2) / 2;
                    TextRoi tr = new TextRoi(cx, cy, String.valueOf(nextLabel[0]++));
                    tr.setStrokeColor(java.awt.Color.RED);
                    overlay.add(tr);
                }
            }
            imp.setOverlay(overlay);
            imp.draw();
            imp.deleteRoi();
            zonesDone[0]++;
            int zd = zonesDone[0];
            if (zd < zoneCount) {
                imp.setTitle(titleBase + " — zone " + (zd + 1) + "/" + zoneCount);
                status.setText("Zone " + (zd + 1) + " / " + zoneCount + " — draw rectangle, then Record");
            } else {
                status.setText("Done — click Results");
                finish.setEnabled(true);
                record.setEnabled(false);
            }
            IJ.showStatus("HoloBio: zone " + zd + "/" + zoneCount + " recorded (" + cellSc.length + " sub-cells).");
        });

        finish.addActionListener(e -> {
            if (contrasts.isEmpty()) {
                JOptionPane.showMessageDialog(helper, "No zones recorded.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            restoreParent.run();
            showSpeckleContrastTable(contrasts);
        });

        cancel.addActionListener(e -> restoreParent.run());

        helper.pack();
        Window iw = imp.getWindow();
        if (iw != null) {
            helper.setLocation(iw.getX() + 20, iw.getY() + 20);
        } else {
            helper.setLocationRelativeTo(null);
        }
        helper.setVisible(true);
        IJ.showStatus("HoloBio: draw rectangle zones; each is split into " + rows + "×" + cols + " automatically.");
    }

    private static void showSpeckleContrastTable(List<Double> rawList) {
        double maxRaw = 0.0;
        for (double v : rawList) {
            if (v > maxRaw) {
                maxRaw = v;
            }
        }
        ResultsTable rt = new ResultsTable();
        int id = 1;
        for (double scRaw : rawList) {
            rt.incrementCounter();
            rt.addLabel("z" + id);
            rt.addValue("Zone", id++);
            rt.addValue("Speckle_Contrast", scRaw);
            rt.addValue("Speckle_Contrast_norm", scRaw / (maxRaw + 1e-9));
        }
        double sumR = 0;
        double sumN = 0;
        for (double scRaw : rawList) {
            sumR += scRaw;
            sumN += scRaw / (maxRaw + 1e-9);
        }
        int n = rawList.size();
        if (n > 0) {
            rt.incrementCounter();
            rt.addLabel("Average");
            rt.addValue("Zone", "Average");
            rt.addValue("Speckle_Contrast", sumR / n);
            rt.addValue("Speckle_Contrast_norm", sumN / n);
        }
        rt.show("HoloBio Speckle");
        HoloBioFijiUi.log("[HoloBio Speckle] sub-cells=" + n + " max_contrast=" + maxRaw);
        IJ.showStatus("HoloBio: speckle results in table \"HoloBio Speckle\".");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Frame dialogOwnerOrNull() {
        ImageJ ij = IJ.getInstance();
        return ij instanceof Frame ? (Frame) ij : null;
    }
}
