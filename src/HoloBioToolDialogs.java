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
import ij.process.ColorProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
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
    private static JFrame qpiDialog;
    private static JFrame speckleWindow;
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

        JFrame dlg = new JFrame("HoloBio — QPI");
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
        JComboBox<String> roiTypeCombo = new JComboBox<>(new String[]{
            "Line (profile)", "Circle (oval)", "Two regions (\u0394\u03c6)", "Two points (\u0394\u03c6)"});
        JComboBox<String> modeCombo = new JComboBox<>(new String[]{"Thickness", "Index"});
        JTextField tfIndSample = HoloBioUiStyle.numericField("1.00");
        JTextField tfIndMedium = HoloBioUiStyle.numericField("1.33");
        JTextField tfThicknessKnown = HoloBioUiStyle.numericField("10.0");
        JTextField tfZones = HoloBioUiStyle.numericField("1");

        tfPixelUm.setToolTipText("Camera pixel size in micrometres.");
        tfMag.setToolTipText("Objective magnification M, e.g. 40 or 40x. Scale = pixel size / M.");
        srcCombo.setToolTipText("Image used to draw or load ROIs.");
        roiTypeCombo.setToolTipText("Line = phase profile; Circle = profile around an oval; "
            + "Two regions / Two points = phase difference between them.");
        modeCombo.setToolTipText("Thickness from Δφ and indices, or index from a known thickness.");
        tfIndSample.setToolTipText("Sample refractive index n_s. Used in Thickness mode.");
        tfIndMedium.setToolTipText("Medium refractive index n_m. Thickness mode: t = λΔφ / (2π|n_s − n_m|). "
            + "Index mode: n_s = n_m + λΔφ / (2π t).");
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
            tfIndMedium.setEnabled(true);
            lblNs.setEnabled(thm);
            lblNm.setEnabled(true);
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
            String roiType = String.valueOf(roiTypeCombo.getSelectedItem());
            boolean circleRoi = roiType.startsWith("Circle");
            String source = String.valueOf(srcCombo.getSelectedItem());
            dlg.setVisible(false);
            if (roiType.startsWith("Two")) {
                runQpiPairs(dlg, in, source, roiType.startsWith("Two points"), thicknessMode,
                        nSample, nMedium, thicknessUm);
                return;
            }
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

    /**
     * Closing a ROI helper with its title-bar X acts as Cancel, so the tool window hidden while
     * picking is shown again instead of staying hidden.
     */
    private static void cancelOnClose(JDialog helper, JButton cancel) {
        helper.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        helper.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancel.doClick();
            }
        });
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
        // Default: Phase. phaseForQpi is already in [0, 2π] (Python's phase_8bit / 255 · 2π), so it maps
        // straight back to 0–255; phaseToBytePickImage expects [-π, π] and would clip half to white.
        if (phaseForQpi != null && phaseForQpi.length == in.fieldWidth * in.fieldHeight) {
            byte[] gray = new byte[phaseForQpi.length];
            for (int i = 0; i < gray.length; i++) {
                int v = (int) Math.round(phaseForQpi[i] / (2.0 * Math.PI) * 255.0);
                gray[i] = (byte) Math.min(255, Math.max(0, v));
            }
            return new ByteProcessor(in.fieldWidth, in.fieldHeight, gray, null);
        }
        return HoloBioToolInputs.phaseToBytePickImage(in.phaseRadians, in.fieldWidth, in.fieldHeight);
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
        // Profiles in the shared CSV layout (region,index,position_um,phase_rad) of RT DHM and
        // HoloBio Python, so File > Save As writes the same file both programs read.
        ResultsTable profRt = new ResultsTable();
        double[] dphis = new double[zones];
        java.awt.Color[] plotColors = QPI_COLORS;

        Plot plot = new Plot("Phase profiles", "Distance [\u00b5m]", "Phase [rad]");
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
            // Dotted lines at the mean of the lowest / highest 5 % of the profile (φ_low, φ_high).
            if (xs.length > 0 && !Double.isNaN(low) && !Double.isNaN(high)) {
                plot.setLineWidth(1);
                plot.drawDottedLine(xs[0], low, xs[xs.length - 1], low, 2);
                plot.drawDottedLine(xs[0], high, xs[xs.length - 1], high, 2);
            }

            String extra = "";
            if (thicknessMode && !Double.isNaN(dphi)) {
                double th = Math.abs(dphi) * lam
                        / (2.0 * Math.PI * Math.max(1e-12, Math.abs(nSample - nMedium)));
                extra = String.format("  t=%.3f µm", th);
            }
            // Names given by the user or a ROI file are kept; RoiManager's automatic names
            // ("0510-0550") are replaced by "Profile N", as in HoloBio Python.
            String rn = roi.getName() != null ? roi.getName().trim() : "";
            String roiName = rn.isEmpty() || rn.matches("[0-9]{4}-[0-9]{4}(-[0-9]{4})?") ? "Profile " + (zi + 1) : rn;
            legend.append(roiName);
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
                // Δφ = 2π·t·Δn / λ  →  Δn = λ·|Δφ| / (2π·t);  n_s = n_m + Δn.
                // (HoloBio Python reports n_rel = 2π·t / (λ·Δφ), which is 1/Δn, not an index.)
                double dn = lam * Math.abs(dphi) / (2.0 * Math.PI * Math.max(1e-12, thicknessUm));
                rt.addValue("\u0394n", dn);
                rt.addValue("n_sample (n_m + \u0394n)", nMedium + dn);
            }
            for (int k = 0; k < prof.length; k++) {
                profRt.incrementCounter();
                profRt.addValue("region", "P" + (zi + 1));
                profRt.addValue("index", k);
                profRt.addValue("position_um", xs[k]);
                profRt.addValue("phase_rad", prof[k]);
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
        showPlotReplacing(plot);

        rt.show("HoloBio QPI");
        profRt.show("HoloBio QPI profiles");
        lastQpiTable = rt;
        lastQpiProfiles = profRt;
        HoloBioFijiUi.log("[HoloBio QPI] zones=" + zones + " thicknessMode=" + thicknessMode
            + " lambda_um=" + lam + " um_per_px=" + umPerPx);
        IJ.showStatus("HoloBio: QPI results in table and phase profile plot.");
    }

    /** Last QPI tables, for tests. */
    static ResultsTable lastQpiTable;
    static ResultsTable lastQpiProfiles;

    /**
     * Phase difference between two regions or two points (fix 13). The user draws two
     * rectangles/ovals, or places two points; Δφ = φ(second) − φ(first), using the circular mean
     * of the phase inside each region and wrapped to (−π, π].
     */
    private static void runQpiPairs(Window hideDlg, HoloBioToolInputs in, String source, boolean points,
                                    boolean thicknessMode, double nSample, double nMedium, double thicknessUm) {
        float[] phaseForQpi = phaseForQpiPythonParity(in);
        ByteProcessor bp = buildDisplayBp(in, source, phaseForQpi);
        if (bp == null || phaseForQpi == null) {
            JOptionPane.showMessageDialog(hideDlg, "No phase available. Reconstruct first.",
                "HoloBio QPI", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String what = points ? "points" : "regions";
        ImagePlus imp = new ImagePlus("HoloBio QPI \u2014 pick 2 " + what + " on " + source, bp);
        String instr = points
            ? "Click the first point, press t; click the second point, press t. Then Done."
            : "Draw the first region (rectangle or oval), press t; draw the second, press t. Then Done.";
        runRoiPickSession(imp, instr, 2, hideDlg, points ? "point" : "rectangle", what, ok -> {
            if (!ok) return;
            RoiManager rm = RoiManager.getInstance();
            if (rm == null || rm.getCount() < 2) {
                JOptionPane.showMessageDialog(hideDlg, "Two " + what + " are needed.",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            showDeltaPhiTable(in, phaseForQpi, rm.getRoi(0), rm.getRoi(1), thicknessMode,
                nSample, nMedium, thicknessUm);
        });
    }

    static ResultsTable showDeltaPhiTable(HoloBioToolInputs in, float[] phase, Roi first, Roi second,
                                          boolean thicknessMode, double nSample, double nMedium, double thicknessUm) {
        double[] d = deltaPhiBetween(phase, in.fieldWidth, in.fieldHeight, first, second);
        double lam = in.wavelengthUm;
        ResultsTable rt = new ResultsTable();
        rt.incrementCounter();
        rt.addValue("\u03c6_1 (rad)", d[0]);
        rt.addValue("\u03c6_2 (rad)", d[1]);
        rt.addValue("\u0394\u03c6 = \u03c6_2 \u2212 \u03c6_1 (rad)", d[2]);
        rt.addValue("pixels_1", d[3]);
        rt.addValue("pixels_2", d[4]);
        if (thicknessMode) {
            rt.addValue("Thickness (\u00b5m)", Math.abs(d[2]) * lam
                / (2.0 * Math.PI * Math.max(1e-12, Math.abs(nSample - nMedium))));
        } else {
            double dn = lam * Math.abs(d[2]) / (2.0 * Math.PI * Math.max(1e-12, thicknessUm));
            rt.addValue("\u0394n", dn);
            rt.addValue("n_sample (n_m + \u0394n)", nMedium + dn);
        }
        rt.show("HoloBio QPI \u0394\u03c6");
        lastQpiTable = rt;
        IJ.showStatus(String.format("HoloBio QPI: \u0394\u03c6 = %.4f rad", d[2]));
        return rt;
    }

    /**
     * {φ1, φ2, Δφ, n1, n2}: circular mean of the phase inside each ROI (a point ROI samples its
     * pixel), and Δφ = φ2 − φ1 wrapped to (−π, π].
     */
    static double[] deltaPhiBetween(float[] phase, int w, int h, Roi a, Roi b) {
        double[] ma = circularMeanIn(phase, w, h, a);
        double[] mb = circularMeanIn(phase, w, h, b);
        double dphi = Math.atan2(Math.sin(mb[0] - ma[0]), Math.cos(mb[0] - ma[0]));
        return new double[] {ma[0], mb[0], dphi, ma[1], mb[1]};
    }

    private static double[] circularMeanIn(float[] phase, int w, int h, Roi roi) {
        double s = 0, c = 0;
        int n = 0;
        if (roi.getType() == Roi.POINT) {
            java.awt.Polygon pts = roi.getPolygon();
            for (int i = 0; i < pts.npoints; i++) {
                int x = Math.max(0, Math.min(w - 1, pts.xpoints[i]));
                int y = Math.max(0, Math.min(h - 1, pts.ypoints[i]));
                double v = phase[y * w + x];
                s += Math.sin(v);
                c += Math.cos(v);
                n++;
            }
        } else {
            Rectangle r = roi.getBounds();
            for (int y = Math.max(0, r.y); y < Math.min(h, r.y + r.height); y++) {
                for (int x = Math.max(0, r.x); x < Math.min(w, r.x + r.width); x++) {
                    if (!roi.contains(x, y)) continue;
                    double v = phase[y * w + x];
                    s += Math.sin(v);
                    c += Math.cos(v);
                    n++;
                }
            }
        }
        double mean = n > 0 ? Math.atan2(s / n, c / n) : Double.NaN;
        if (mean < 0) mean += 2.0 * Math.PI;       // same [0, 2π) range as the QPI phase
        return new double[] {mean, n};
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
        if (speckleWindow != null && speckleWindow.isDisplayable()) {
            speckleWindow.dispose();         // reopening rebuilds it with the current field
        }
        JFrame dlg = new JFrame("HoloBio — Speckle");
        speckleWindow = dlg;
        dlg.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
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
                showAddedRois(imp, rmNow);
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

        cancelOnClose(helper, cancel);
        helper.pack();
        if (iw != null) {
            helper.setLocation(iw.getX() + 20, iw.getY() + iw.getHeight() - helper.getHeight() - 20);
        } else {
            helper.setLocationRelativeTo(null);
        }
        helper.setVisible(true);
        IJ.showStatus("HoloBio: draw ROI on the image window, then click Done in the helper.");
    }

    /** Matplotlib's default colour cycle (tab10), used by HoloBio Python for QPI lines and profiles. */
    static final java.awt.Color[] QPI_COLORS = {
        new java.awt.Color(0x1f77b4), new java.awt.Color(0xff7f0e), new java.awt.Color(0x2ca02c),
        new java.awt.Color(0xd62728), new java.awt.Color(0x9467bd), new java.awt.Color(0x8c564b),
        new java.awt.Color(0xe377c2), new java.awt.Color(0x7f7f7f), new java.awt.Color(0xbcbd22),
        new java.awt.Color(0x17becf)
    };

    /**
     * Keep every ROI added with "t" drawn on the picking image, numbered and in the colour of its
     * profile, as HoloBio Python leaves the drawn lines on the figure.
     */
    private static void showAddedRois(ImagePlus imp, RoiManager rm) {
        if (imp == null) {
            return;
        }
        Overlay ov = new Overlay();
        Roi[] rois = rm != null ? rm.getRoisAsArray() : new Roi[0];
        int fontPx = Math.max(18, Math.max(imp.getWidth(), imp.getHeight()) / 45);
        for (int i = 0; i < rois.length; i++) {
            Roi r = (Roi) rois[i].clone();
            java.awt.Color c = QPI_COLORS[i % QPI_COLORS.length];   // same colour as its profile
            r.setStrokeColor(c);
            r.setStrokeWidth(Math.max(2, fontPx / 8));
            ov.add(r);
            java.awt.Rectangle b = r.getBounds();
            TextRoi label = new TextRoi(String.valueOf(i + 1), b.x + b.width + 4, b.y - fontPx,
                new java.awt.Font("SansSerif", java.awt.Font.BOLD, fontPx));
            label.setStrokeColor(c);
            label.setAntiAlias(true);
            ov.add(label);
        }
        imp.setOverlay(ov);
        imp.deleteRoi();
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

        cancelOnClose(helper, cancel);
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

    /**
     * Fix 12: both images are normalised to [0, 1] (each by its own min and max) before they are
     * placed side by side, so a filter that only changes the grey range does not look like an
     * improvement, and their speckle contrast C = std(I)/mean(I) with I = (normalised value)^2
     * (HoloBio Python {@code calc_speckle_contrast}) is reported for both.
     */
    private static void showSideBySide(float[] original, float[] filtered, int w, int h) {
        float[] o = normalized01(original);
        float[] f = normalized01(filtered);
        // Laid out as HoloBio Python's figure: suptitle "Speckle Filter Result", panels "Original" |
        // "Filtered" in grey, no axes. Each panel is normalised to [0, 1] (shown as 0-255).
        ImagePlus imp = new ImagePlus("Speckle Filter Result", sideBySideFigure(o, f, w, h,
            "Speckle Filter Result", "Original", "Filtered"));
        HoloBioFijiUi.showImagePlus(imp);
        lastSideBySide = imp;
        double cO = speckleContrastNormalized(o);
        double cF = speckleContrastNormalized(f);
        ResultsTable rt = new ResultsTable();
        rt.incrementCounter();
        rt.addLabel("Original");
        rt.addValue("Speckle contrast C", cO);
        rt.incrementCounter();
        rt.addLabel("Filtered");
        rt.addValue("Speckle contrast C", cF);
        rt.incrementCounter();
        rt.addLabel("Reduction (%)");
        rt.addValue("Speckle contrast C", cO > 0 ? 100.0 * (cO - cF) / cO : Double.NaN);
        rt.show("HoloBio Speckle Comparison");
        lastSpeckleComparison = new double[] {cO, cF};
        HoloBioFijiUi.log(String.format("[HoloBio Speckle] Side-by-side (normalised): C original=%.4f, filtered=%.4f", cO, cF));
    }

    /** Last side-by-side figure; for tests. */
    static ImagePlus lastSideBySide;

    /** Show {@code plot}, redrawing an open plot window of the same title instead of adding another. */
    static void showPlotReplacing(Plot plot) {
        ImagePlus old = ij.WindowManager.getImage(plot.getTitle());
        if (old != null && old.getWindow() instanceof ij.gui.PlotWindow) {
            ((ij.gui.PlotWindow) old.getWindow()).drawPlot(plot);
            old.getWindow().toFront();
        } else {
            plot.show();
        }
    }

    /** White figure with a centred title and two grey panels with their own titles (Python layout). */
    static ColorProcessor sideBySideFigure(float[] a01, float[] b01, int w, int h,
                                           String title, String titleA, String titleB) {
        int gap = Math.max(24, w / 24);
        int top = Math.max(80, h / 9);
        int W = 2 * w + 3 * gap;
        int H = h + top + gap;
        ColorProcessor cp = new ColorProcessor(W, H);
        cp.setColor(java.awt.Color.WHITE);
        cp.fill();
        for (int k = 0; k < 2; k++) {
            float[] v = k == 0 ? a01 : b01;
            int x0 = gap + k * (w + gap);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int g = Math.round(255f * Math.max(0f, Math.min(1f, v[y * w + x])));
                    cp.set(x0 + x, top + y, (g << 16) | (g << 8) | g);
                }
            }
        }
        cp.setColor(java.awt.Color.BLACK);
        cp.setAntialiasedText(true);
        cp.setJustification(ImageProcessor.CENTER_JUSTIFY);
        cp.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, Math.max(22, h / 20)));
        cp.drawString(title, W / 2, (int) (top * 0.45));
        cp.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, Math.max(18, h / 26)));
        cp.drawString(titleA, gap + w / 2, top - 10);
        cp.drawString(titleB, 2 * gap + w + w / 2, top - 10);
        return cp;
    }

    /** Last side-by-side speckle contrasts {original, filtered}; for tests. */
    static double[] lastSpeckleComparison;

    /** (v - min) / (max - min); constant images map to 0. */
    static float[] normalized01(float[] v) {
        float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
        for (float x : v) {
            if (x < mn) mn = x;
            if (x > mx) mx = x;
        }
        float span = mx - mn;
        float[] out = new float[v.length];
        if (span <= 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (v[i] - mn) / span;
        return out;
    }

    /** Python calc_speckle_contrast on a normalised image: std(|v|^2) / (mean(|v|^2) + 1e-9). */
    static double speckleContrastNormalized(float[] v) {
        double mean = 0;
        for (float x : v) mean += (double) x * x;
        mean /= v.length;
        double acc = 0;
        for (float x : v) {
            double d = (double) x * x - mean;
            acc += d * d;
        }
        return Math.sqrt(acc / v.length) / (mean + 1e-9);
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

        cancelOnClose(helper, cancel);
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

        Plot plot = new Plot("Normalized Speckle Contrast over Iterations (" + type + ")",
            "Iteration", "Normalized Contrast");
        plot.setLimits(1, Math.max(2, rawContrasts.size()), 0, Math.max(1.05, maxOf(yNorm) * 1.1));
        plot.setColor(java.awt.Color.BLUE);
        plot.setLineWidth(2);
        plot.addPoints(xIter, yNorm, Plot.LINE);
        plot.addPoints(xIter, yNorm, Plot.CIRCLE);
        plot.setColor(java.awt.Color.BLACK);
        plot.setLineWidth(1);
        plot.addLabel(0.02, 0.07, String.format("Region: X(%d-%d) Y(%d-%d)", x1, x2, y1, y2));
        plot.setFrameSize(640, 384);
        showPlotReplacing(plot);
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
            // Fix 12: one plot, both profiles divided by their own mean, so the speckle fluctuations
            // are compared on the same scale instead of in two plots with different ranges.
            double mO = 0, mF = 0;
            for (int i = 0; i < len; i++) { mO += profOrig[i]; mF += profFilt[i]; }
            mO /= len; mF /= len;
            double[] nO = new double[len], nF = new double[len];
            for (int i = 0; i < len; i++) {
                nO[i] = mO != 0 ? profOrig[i] / mO : 0;
                nF[i] = mF != 0 ? profFilt[i] / mF : 0;
            }
            // Python's "Line Profile Comparison": Original (blue) and Filtered (orange) panels side
            // by side. Both profiles are divided by their mean and share the y range, so the
            // remaining speckle fluctuation is compared on the same scale.
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < len; i++) {
                lo = Math.min(lo, Math.min(nO[i], nF[i]));
                hi = Math.max(hi, Math.max(nO[i], nF[i]));
            }
            double padY = (hi - lo) * 0.05 + 1e-9;
            ImageProcessor[] panels = new ImageProcessor[2];
            for (int k = 0; k < 2; k++) {
                Plot pk = new Plot(k == 0 ? "Original Image Profile" : "Filtered Image Profile",
                    "Pixel Position", "Vertical Sum (normalised to mean = 1)");
                pk.setLimits(xPos[0], xPos[len - 1], lo - padY, hi + padY);
                pk.setColor(k == 0 ? java.awt.Color.BLUE : java.awt.Color.ORANGE);
                pk.setLineWidth(2);
                pk.addPoints(xPos, k == 0 ? nO : nF, Plot.LINE);
                pk.setFrameSize(480, 320);
                panels[k] = pk.getProcessor().convertToRGB();
            }
            int pw = panels[0].getWidth(), ph = panels[0].getHeight(), titleH = 44;
            ColorProcessor fig = new ColorProcessor(pw + panels[1].getWidth(), ph + titleH);
            fig.setColor(java.awt.Color.WHITE);
            fig.fill();
            fig.insert(panels[0], 0, titleH);
            fig.insert(panels[1], pw, titleH);
            fig.setColor(java.awt.Color.BLACK);
            fig.setAntialiasedText(true);
            fig.setJustification(ImageProcessor.CENTER_JUSTIFY);
            fig.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18));
            fig.drawString("Line Profile Comparison", fig.getWidth() / 2, 30);
            HoloBioFijiUi.showImagePlus(new ImagePlus("Line Profile Comparison", fig));
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
                    // Overlay text scales with the zoom, so size it for ~32 px on screen at the
                    // current magnification (large images open zoomed out), capped to the cell.
                    double mag = imp.getCanvas() != null ? imp.getCanvas().getMagnification() : 1.0;
                    int fontPx = (int) Math.ceil(32.0 / Math.max(mag, 1e-3));
                    fontPx = Math.max(fontPx, Math.max(w, h) / 30);
                    fontPx = Math.min(fontPx, Math.max(12, (int) (0.8 * Math.min(wSub, hSub))));
                    TextRoi tr = new TextRoi(String.valueOf(nextLabel[0]++), cx, cy,
                        new java.awt.Font("SansSerif", java.awt.Font.BOLD, fontPx));
                    tr.setJustification(TextRoi.CENTER);
                    tr.setAntiAlias(true);
                    java.awt.Rectangle tb = tr.getBounds();
                    tr.setLocation(cx - tb.width / 2.0, cy - tb.height / 2.0);
                    tr.setStrokeColor(java.awt.Color.RED);
                    // White box behind the number so red stays readable on speckle.
                    tr.setFillColor(new java.awt.Color(255, 255, 255, 200));
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

        cancelOnClose(helper, cancel);
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
