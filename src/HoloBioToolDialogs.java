import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
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

    /** QPI output (item 17): Δφ only, or Δφ converted to a thickness or a refractive index. */
    static final String QPI_OUT_DPHI = "Δφ only";
    static final String QPI_OUT_THICKNESS = "Thickness";
    static final String QPI_OUT_INDEX = "Index";

    /** Plugin that opened QPI; resolves the phase image of the Fiji image clicked last. */
    private static volatile HoloBioToolCallbacks qpiCallbacks;
    /** Refreshes the "Measures:" line of the open QPI dialog. */
    private static Runnable qpiRefreshTarget;
    /** Values typed in this session; nothing is filled in silently (item 16). */
    private static String lastNs = "";
    private static String lastNm = "";
    private static String lastThickness = "";

    /** What QPI computes, read and checked when Apply is pressed. */
    static final class QpiSettings {
        String output = QPI_OUT_DPHI;
        double nSample = Double.NaN;
        double nMedium = Double.NaN;
        double thicknessUm = Double.NaN;
        double lambdaUm = Double.NaN;
    }

    public static void showBioAnalysisDialog(Frame owner, HoloBioToolInputs inputs) {
        showBioAnalysisDialog(owner, inputs, null);
    }

    /**
     * QPI on the phase image of the reconstruction the user clicked last (item 15: phase only).
     * The µm scale comes from that image (Analysis tools ▸ Set scale), so QPI has no dimension
     * fields of its own (item 14); λ comes from the reconstruction (item 13). ROIs are drawn on
     * the phase window itself and stay there afterwards (item 18).
     */
    public static void showBioAnalysisDialog(Frame owner, HoloBioToolInputs inputs, HoloBioToolCallbacks callbacks) {
        qpiInputsRef.set(inputs);
        qpiCallbacks = callbacks;
        if (qpiDialog != null && qpiDialog.isDisplayable()) {
            if (qpiRefreshTarget != null) {
                qpiRefreshTarget.run();
            }
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

        JLabel targetLabel = HoloBioUiStyle.hintHtml(" ", 300);
        targetLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // The description is wrapped at a fixed width and the window grows if it needs more
        // lines, so the text is never squeezed into a narrow, clipped column.
        Runnable refreshTarget = () -> {
            targetLabel.setText(describeQpiTarget(resolveQpiTarget()));
            targetLabel.revalidate();
            if (dlg.isShowing() && targetLabel.getHeight() < targetLabel.getPreferredSize().height) {
                Dimension now = dlg.getSize();
                dlg.pack();
                dlg.setSize(Math.max(now.width, dlg.getWidth()), Math.max(now.height, dlg.getHeight()));
            }
        };
        qpiRefreshTarget = refreshTarget;
        JButton refreshBtn = HoloBioUiStyle.compactButton("Refresh");
        refreshBtn.setToolTipText("Click a phase image in Fiji, then Refresh (Apply always uses the image clicked last).");
        refreshBtn.addActionListener(e -> refreshTarget.run());
        JPanel imageSec = HoloBioUiStyle.formSection("Phase image");
        GridBagConstraints imgC = HoloBioUiStyle.formGbc();
        imgC.gridx = 0;
        imgC.gridwidth = 2;
        imgC.fill = GridBagConstraints.HORIZONTAL;
        imgC.weightx = 1.0;
        imageSec.add(targetLabel, imgC);
        imgC.gridy++;
        JPanel refreshRow = flowLeft();
        refreshRow.add(refreshBtn);
        imageSec.add(refreshRow, imgC);

        JComboBox<String> roiTypeCombo = new JComboBox<>(new String[]{
            "Line", "Circle"});
        JComboBox<String> outputCombo = new JComboBox<>(new String[]{
            QPI_OUT_DPHI, QPI_OUT_THICKNESS, QPI_OUT_INDEX});
        outputCombo.setSelectedItem(QPI_OUT_THICKNESS);
        JTextField tfIndSample = HoloBioUiStyle.numericField(lastNs);
        JTextField tfIndMedium = HoloBioUiStyle.numericField(lastNm);
        JTextField tfThicknessKnown = HoloBioUiStyle.numericField(lastThickness);
        JTextField tfZones = HoloBioUiStyle.numericField("1");

        roiTypeCombo.setToolTipText("Line = phase profile; Circle = profile around a circle.");
        outputCombo.setToolTipText("Δφ only, or Δφ converted to a thickness (needs n_s, n_m) "
            + "or to the sample index (needs n_m and a known thickness).");
        tfIndSample.setToolTipText("Sample refractive index n_s (Thickness).");
        tfIndMedium.setToolTipText("Medium refractive index n_m. Thickness: t = λΔφ / (2π|n_s − n_m|). "
            + "Index: n_s = n_m + λΔφ / (2π t).");
        tfThicknessKnown.setToolTipText("Known sample thickness in micrometres (Index).");
        tfZones.setToolTipText("Number of ROIs to draw (integer ≥ 1).");

        JPanel meas = HoloBioUiStyle.formSection("QPI measurements");
        GridBagConstraints measC = HoloBioUiStyle.formGbc();
        HoloBioUiStyle.addLabelField(meas, measC, "ROI type", roiTypeCombo);
        HoloBioUiStyle.addLabelField(meas, measC, "Output", outputCombo);
        HoloBioUiStyle.addLabelField(meas, measC, "Zones", tfZones,
            "Number of ROIs to draw. Each zone is measured separately (integer ≥ 1).");

        JPanel refr = HoloBioUiStyle.formSection("Refractive parameters");
        GridBagConstraints refrC = HoloBioUiStyle.formGbc();
        JLabel lblNs = HoloBioUiStyle.addLabelField(refr, refrC, "Sample index n_s", tfIndSample);
        JLabel lblNm = HoloBioUiStyle.addLabelField(refr, refrC, "Medium index n_m", tfIndMedium);
        JLabel lblThick = HoloBioUiStyle.addLabelField(refr, refrC, "Known thickness (µm)", tfThicknessKnown);
        JLabel refrHelp = HoloBioUiStyle.helperText(" ");
        refrC.gridx = 0;
        refrC.gridwidth = 2;
        refrC.insets = new java.awt.Insets(0, 0, 0, 0);
        refr.add(refrHelp, refrC);

        Runnable syncQpiMode = () -> {
            String out = String.valueOf(outputCombo.getSelectedItem());
            boolean thick = QPI_OUT_THICKNESS.equals(out);
            boolean index = QPI_OUT_INDEX.equals(out);
            tfIndSample.setEnabled(thick);
            lblNs.setEnabled(thick);
            tfIndMedium.setEnabled(thick || index);
            lblNm.setEnabled(thick || index);
            tfThicknessKnown.setEnabled(index);
            lblThick.setEnabled(index);
            refrHelp.setText(thick ? "Thickness needs n_s and n_m."
                : index ? "Index needs n_m and the known thickness." : "Δφ only needs no parameters.");
        };
        outputCombo.addActionListener(e -> syncQpiMode.run());
        syncQpiMode.run();

        JButton applyQpi = HoloBioUiStyle.primaryButton("Apply QPI");
        JButton loadRoiFile = HoloBioUiStyle.secondaryButton("Load ROI file…");
        loadRoiFile.setToolTipText("Load line/rect coords from rois_*.txt and run QPI without drawing");

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(HoloBioUiStyle.contentPad());
        HoloBioUiStyle.stackSections(body, imageSec, meas, refr);

        JButton close = HoloBioUiStyle.tertiaryButton("Close");
        close.addActionListener(ev -> dlg.setVisible(false));

        dlg.add(HoloBioUiStyle.buildHeader("QPI", null, null), BorderLayout.NORTH);
        dlg.add(body, BorderLayout.CENTER);
        dlg.add(HoloBioUiStyle.buildActionBar(close, loadRoiFile, applyQpi), BorderLayout.SOUTH);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowActivated(java.awt.event.WindowEvent e) {
                refreshTarget.run();
            }
        });

        applyQpi.addActionListener(ev -> {
            ImagePlus target = resolveQpiTarget();
            refreshTarget.run();
            if (target == null) {
                JOptionPane.showMessageDialog(dlg, "Click a phase image in Fiji first (reconstruct one if needed).",
                        "HoloBio QPI", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            QpiSettings s = readQpiSettings(dlg, target, outputCombo, tfIndSample, tfIndMedium, tfThicknessKnown);
            if (s == null) {
                return;
            }
            int zones;
            try {
                zones = Integer.parseInt(tfZones.getText().trim());
            } catch (NumberFormatException ex) {
                zones = 0;
            }
            if (zones < 1) {
                JOptionPane.showMessageDialog(dlg, "Zones must be an integer ≥ 1.",
                        "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String roiType = String.valueOf(roiTypeCombo.getSelectedItem());
            runQpiOnImage(dlg, target, zones, roiType.startsWith("Circle"), s);
        });

        loadRoiFile.addActionListener(ev -> {
            ImagePlus target = resolveQpiTarget();
            if (target == null) {
                JOptionPane.showMessageDialog(dlg, "Click a phase image in Fiji first (reconstruct one if needed).",
                        "HoloBio QPI", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            QpiSettings s = readQpiSettings(dlg, target, outputCombo, tfIndSample, tfIndMedium, tfThicknessKnown);
            if (s == null) {
                return;
            }
            JFileChooser fc = new JFileChooser(
                    new File(System.getProperty("user.home"),
                            "Documents/DECIMO SEMESTRE/AVANZADO 2/sample images/benchmark"));
            fc.setDialogTitle("Load shared ROIs (rois_*.txt)");
            if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            try {
                runQpiFromRoiFile(dlg, target, fc.getSelectedFile(), s);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "Cannot load ROIs: " + ex.getMessage(),
                        "HoloBio QPI", JOptionPane.ERROR_MESSAGE);
            }
        });

        refreshTarget.run();               // fill the text before sizing the window
        HoloBioUiStyle.polish(dlg);
        HoloBioUiStyle.packTight(dlg, 440, 560);
        dlg.setLocationRelativeTo(owner);
        bringWindowFront(dlg);
    }

    /** The phase image QPI measures: what the plugin resolves, else the image clicked last. */
    private static ImagePlus resolveQpiTarget() {
        HoloBioToolCallbacks cb = qpiCallbacks;
        ImagePlus t;
        if (cb != null) {
            t = cb.qpiPhaseImage();
        } else {
            t = ij.WindowManager.getCurrentImage();
            if (t != null && t.getWindow() instanceof ij.gui.PlotWindow) {
                t = null;
            }
        }
        return t != null && t.getWindow() != null ? t : null;
    }

    private static double qpiWavelengthFor(ImagePlus target) {
        HoloBioToolCallbacks cb = qpiCallbacks;
        double lam = cb != null ? cb.wavelengthUmFor(target) : Double.NaN;
        if (!(lam > 0)) {
            HoloBioToolInputs in = qpiInputsRef.get();
            lam = in != null ? in.wavelengthUm : Double.NaN;
        }
        return lam;
    }

    /** "Measures: <title> · 0.0938 µm/px · λ = 0.633 µm" for the dialog. */
    private static String describeQpiTarget(ImagePlus t) {
        if (t == null) {
            return "<html><p style='width:260px'>No phase image. Click one in Fiji, then <b>Refresh</b>.</p></html>";
        }
        ij.measure.Calibration cal = t.getCalibration();
        String scale = cal.scaled()
            ? String.format(java.util.Locale.US, "%.4g %s/px", cal.pixelWidth, cal.getUnit())
            : "no scale (pixels): use Analysis tools ▸ Set scale";
        double lam = qpiWavelengthFor(t);
        String lamText = lam > 0 ? String.format(java.util.Locale.US, "λ = %.4g µm", lam) : "λ unknown";
        return "<html><p style='width:260px'>Measures: <b>" + escapeHtml(t.getTitle()) + "</b><br>"
            + scale + "  ·  " + lamText + "</p></html>";
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Parameters for the chosen output, checked: an empty or invalid value is reported instead
     * of being replaced by a hidden default (item 16). Null after a message.
     */
    private static QpiSettings readQpiSettings(Window parent, ImagePlus target, JComboBox<String> outputCombo,
                                               JTextField tfNs, JTextField tfNm, JTextField tfThick) {
        QpiSettings s = new QpiSettings();
        s.output = String.valueOf(outputCombo.getSelectedItem());
        lastNs = tfNs.getText().trim();
        lastNm = tfNm.getText().trim();
        lastThickness = tfThick.getText().trim();
        if (QPI_OUT_DPHI.equals(s.output)) {
            return s;
        }
        s.lambdaUm = qpiWavelengthFor(target);
        if (!(s.lambdaUm > 0)) {
            JOptionPane.showMessageDialog(parent, "The wavelength of this image is unknown; reconstruct it in "
                + "HoloBio first, or choose Δφ only.", "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        s.nMedium = requiredNumber(parent, tfNm, "Medium index n_m");
        if (Double.isNaN(s.nMedium)) {
            return null;
        }
        if (QPI_OUT_THICKNESS.equals(s.output)) {
            s.nSample = requiredNumber(parent, tfNs, "Sample index n_s");
            if (Double.isNaN(s.nSample)) {
                return null;
            }
            if (Math.abs(s.nSample - s.nMedium) < 1e-9) {
                JOptionPane.showMessageDialog(parent, "n_s and n_m must differ to compute a thickness.",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return null;
            }
        } else {
            s.thicknessUm = requiredNumber(parent, tfThick, "Known thickness (µm)");
            if (Double.isNaN(s.thicknessUm)) {
                return null;
            }
            if (s.thicknessUm <= 0) {
                JOptionPane.showMessageDialog(parent, "The known thickness must be greater than zero.",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return null;
            }
        }
        return s;
    }

    /** A number typed in {@code tf}; NaN (after a message) when empty or not a number. */
    private static double requiredNumber(Window parent, JTextField tf, String what) {
        String t = tf.getText().trim().replace(',', '.');
        if (!t.isEmpty()) {
            try {
                double v = Double.parseDouble(t);
                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // reported below
            }
        }
        JOptionPane.showMessageDialog(parent, what + " is required for this output; enter a number.",
            "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
        tf.requestFocusInWindow();
        return Double.NaN;
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

    /** Line or circle profiles drawn on the phase window itself (items 15, 18, 19). */
    private static void runQpiOnImage(Window hideDlg, ImagePlus target, int zones, boolean circle, QpiSettings s) {
        String roiLabel = circle ? "circle" : "straight line";
        String instr = circle
            ? "Draw " + zones + " circle(s) on the phase (oval tool; hold Shift for a circle). Press t after each, then Done."
            : "Draw " + zones + " straight line(s) on the phase. Press t after each, then Done.";
        runRoiPickOnImage(target, instr, zones, hideDlg, circle ? "oval" : "line", roiLabel, ok -> {
            if (!ok) return;
            RoiManager rm = RoiManager.getInstance();
            List<Roi> rois = new ArrayList<>();
            for (int i = 0; rm != null && i < rm.getCount() && rois.size() < zones; i++) {
                Roi r = rm.getRoi(i);
                if (circle && r instanceof OvalRoi) {
                    rois.add(asCircle((OvalRoi) r));
                } else if (!circle && r instanceof Line) {
                    rois.add(r);
                }
            }
            if (rois.size() < zones) {
                JOptionPane.showMessageDialog(hideDlg,
                    "Expected " + zones + " " + roiLabel + "(s) drawn with the "
                        + (circle ? "oval" : "straight line") + " tool, found " + rois.size() + ".",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            computeQpiResults(hideDlg, target, rois, circle, s);
        });
    }

    /** The circle with an oval's centre and mean radius (QPI uses circles only, item 19). */
    static OvalRoi asCircle(OvalRoi ov) {
        double cx = ov.getXBase() + ov.getFloatWidth() / 2.0;
        double cy = ov.getYBase() + ov.getFloatHeight() / 2.0;
        double r = 0.25 * (ov.getFloatWidth() + ov.getFloatHeight());
        OvalRoi c = new OvalRoi(cx - r, cy - r, 2 * r, 2 * r);
        c.setName(ov.getName());
        return c;
    }

    /**
     * Run QPI on shared {@code rois_*.txt} lines (same file as RT DHM Load ROIs / Python); a
     * rectangle becomes its horizontal midline. The ROIs are drawn on the phase window.
     */
    private static void runQpiFromRoiFile(Window parent, ImagePlus target, File roiFile, QpiSettings s) throws Exception {
        HoloBioRtRoiFile.Bundle b = HoloBioRtRoiFile.load(roiFile);
        if (b.rois.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No ROIs in file.", "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int w = target.getWidth();
        int h = target.getHeight();
        double sx = 1.0, sy = 1.0;
        if (b.width > 0 && b.height > 0 && (w != b.width || h != b.height)) {
            sx = w / (double) b.width;
            sy = h / (double) b.height;
        }
        List<Roi> rois = new ArrayList<>();
        for (HoloBioRtRoiFile.Roi r : b.rois) {
            Line ln = r.rect
                ? new Line(r.x1 * sx, (r.y1 + 0.5 * r.y2) * sy, (r.x1 + r.x2) * sx, (r.y1 + 0.5 * r.y2) * sy)
                : new Line(r.x1 * sx, r.y1 * sy, r.x2 * sx, r.y2 * sy);
            ln.setName(r.name);
            rois.add(ln);
        }
        computeQpiResults(parent, target, rois, false, s);
        HoloBioFijiUi.log("[HoloBio QPI] Loaded " + rois.size() + " ROI(s) from " + roiFile.getName()
                + (sx != 1.0 || sy != 1.0
                    ? String.format(" (scaled ×%.3g/×%.3g to %d×%d)", sx, sy, w, h)
                    : ""));
    }

    /**
     * Phase in [0, 2π] from the image QPI measures (current slice). HoloBio outputs and other
     * 8-bit / 0–255 images map 0–255 to 0–2π, as HoloBio Python's {@code phase_8bit / 255 · 2π};
     * a 32-bit image already in radians is used as it is ([−π, π] is shifted by π).
     */
    static float[] phase0to2pi(ImagePlus imp) {
        ImageProcessor ip = imp.getProcessor();
        float[] v = (float[]) ip.convertToFloatProcessor().getPixels();
        float[] out = v == ip.getPixels() ? v.clone() : v;
        float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
        for (float x : out) {
            if (x < mn) mn = x;
            if (x > mx) mx = x;
        }
        double pi = Math.PI;
        if (imp.getBitDepth() == 32 && mn >= -pi - 1e-3 && mx <= pi + 1e-3 && mn < 0) {
            for (int i = 0; i < out.length; i++) out[i] += (float) pi;
        } else if (imp.getBitDepth() == 32 && mn >= -1e-3 && mx <= 2 * pi + 1e-3) {
            // already 0–2π radians
        } else {
            double full = imp.getBitDepth() == 16 ? 65535.0 : 255.0;
            for (int i = 0; i < out.length; i++) out[i] = (float) (out[i] / full * 2.0 * pi);
        }
        return out;
    }

    /**
     * Profiles, φ_low / φ_high (means of the lowest / highest 5 %), Δφ and the chosen output for
     * each ROI; plot and table as before. Distances use the image's scale. The numbered ROIs are
     * left on the phase window (item 18).
     */
    private static void computeQpiResults(Window parent, ImagePlus target, List<Roi> rois, boolean circle,
                                          QpiSettings s) {
        int w = target.getWidth();
        int h = target.getHeight();
        float[] ph = phase0to2pi(target);
        ij.measure.Calibration cal = target.getCalibration();
        boolean scaled = cal.scaled();
        double perPx = scaled ? cal.pixelWidth : 1.0;
        String unit = scaled ? cal.getUnit() : "px";
        int zones = rois.size();

        ResultsTable rt = new ResultsTable();
        // Profiles in the shared CSV layout (region,index,position_um,phase_rad) of RT DHM and
        // HoloBio Python, so File > Save As writes the same file both programs read.
        ResultsTable profRt = new ResultsTable();
        double[] dphis = new double[zones];
        Plot plot = new Plot("Phase profiles", "Distance [" + unit + "]", "Phase [rad]");
        double globMin = Double.POSITIVE_INFINITY, globMax = Double.NEGATIVE_INFINITY;
        double globXMax = 0;
        StringBuilder legend = new StringBuilder();

        for (int zi = 0; zi < zones; zi++) {
            Roi roi = rois.get(zi);
            double[] prof;
            if (circle) {
                OvalRoi ov = (OvalRoi) roi;
                double cx = ov.getXBase() + ov.getFloatWidth() / 2.0;
                double cy = ov.getYBase() + ov.getFloatHeight() / 2.0;
                prof = HoloBioQpiSpeckleMath.profileAlongCircle(ph, w, h, cx, cy, ov.getFloatWidth() / 2.0);
            } else {
                Line ln = (Line) roi;
                prof = HoloBioQpiSpeckleMath.profileAlongLine(ph, w, h, ln.x1d, ln.y1d, ln.x2d, ln.y2d);
            }
            double[] st = HoloBioQpiSpeckleMath.phaseStats(prof);
            double low = st[0], high = st[1], dphi = st[2];
            dphis[zi] = dphi;

            // Python QPI: dist = np.arange(len(prof)) * μm_per_px
            double[] xs = new double[prof.length];
            for (int k = 0; k < prof.length; k++) {
                xs[k] = k * perPx;
                if (prof[k] < globMin) globMin = prof[k];
                if (prof[k] > globMax) globMax = prof[k];
            }
            if (xs.length > 0) {
                globXMax = Math.max(globXMax, xs[xs.length - 1]);
            }
            java.awt.Color zc = QPI_COLORS[zi % QPI_COLORS.length];
            plot.setColor(zc);
            plot.setLineWidth(2);
            plot.addPoints(xs, prof, Plot.LINE);
            // Dotted lines at the mean of the lowest / highest 5 % of the profile (φ_low, φ_high).
            if (xs.length > 0 && !Double.isNaN(low) && !Double.isNaN(high)) {
                plot.setLineWidth(1);
                plot.drawDottedLine(xs[0], low, xs[xs.length - 1], low, 2);
                plot.drawDottedLine(xs[0], high, xs[xs.length - 1], high, 2);
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
            addQpiOutputColumns(rt, Math.abs(dphi), s);
            for (int k = 0; k < prof.length; k++) {
                profRt.incrementCounter();
                profRt.addValue("region", "P" + (zi + 1));
                profRt.addValue("index", k);
                profRt.addValue(scaled ? "position_" + ("µm".equals(unit) ? "um" : unit) : "position_px", xs[k]);
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

        drawNumberedRois(target, rois.toArray(new Roi[0]));
        rt.show("HoloBio QPI");
        profRt.show("HoloBio QPI profiles");
        lastQpiTable = rt;
        lastQpiProfiles = profRt;
        HoloBioFijiUi.log("[HoloBio QPI] " + target.getTitle() + ": zones=" + zones + " output=" + s.output
            + " lambda_um=" + s.lambdaUm + " scale=" + perPx + " " + unit + "/px");
        IJ.showStatus("HoloBio: QPI results in table and phase profile plot.");
    }

    /** Thickness or index columns for |Δφ|, as chosen (nothing extra for Δφ only). */
    private static void addQpiOutputColumns(ResultsTable rt, double absDphi, QpiSettings s) {
        if (QPI_OUT_THICKNESS.equals(s.output)) {
            // Δφ = 2π·t·|n_s − n_m| / λ  →  t = λ·|Δφ| / (2π·|n_s − n_m|)
            rt.addValue("Thickness (µm)", absDphi * s.lambdaUm / (2.0 * Math.PI * Math.abs(s.nSample - s.nMedium)));
        } else if (QPI_OUT_INDEX.equals(s.output)) {
            // Δφ = 2π·t·Δn / λ  →  Δn = λ·|Δφ| / (2π·t);  n_s = n_m + Δn.
            double dn = s.lambdaUm * absDphi / (2.0 * Math.PI * s.thicknessUm);
            rt.addValue("Δn", dn);
            rt.addValue("n_sample (n_m + Δn)", s.nMedium + dn);
        }
    }

    /** Last QPI tables, for tests. */
    static ResultsTable lastQpiTable;
    static ResultsTable lastQpiProfiles;

    /**
     * Pick ROIs directly on an open image (the phase window, or the image a speckle measurement
     * uses): no window is opened or closed (item 20), the numbered ROIs stay on it after Done
     * (item 18), and Cancel restores what was drawn before. A ROI still selected when Done is
     * pressed counts even if t was not pressed for it.
     */
    private static void runRoiPickOnImage(ImagePlus imp, String instructions, int minRoiCount,
                                          Window hideWhilePicking, String ijTool, String roiKindLabel,
                                          Consumer<Boolean> whenFinished) {
        if (imp == null || imp.getWindow() == null) {
            whenFinished.accept(false);
            return;
        }
        if (hideWhilePicking != null) {
            hideWhilePicking.setVisible(false);
        }
        Overlay before = imp.getOverlay() != null ? imp.getOverlay().duplicate() : null;
        imp.setOverlay(null);
        imp.deleteRoi();
        RoiManager rm = HoloBioFijiUi.roiManagerForPicking();
        rm.reset();
        ij.WindowManager.setCurrentWindow(imp.getWindow());
        imp.getWindow().toFront();
        IJ.setTool(ijTool != null ? ijTool : "rectangle");

        JDialog helper = new JDialog((Frame) null, "HoloBio — ROI selection", false);
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
        helper.add(HoloBioUiStyle.buildFooter("On \"" + imp.getTitle() + "\"", cancel, done), BorderLayout.SOUTH);

        final int[] lastCount = {0};
        javax.swing.Timer pollTimer = new javax.swing.Timer(250, ev -> {
            RoiManager rmNow = RoiManager.getInstance();
            int n = rmNow != null ? rmNow.getCount() : 0;
            if (n != lastCount[0]) {
                lastCount[0] = n;
                countLabel.setText("ROIs added: " + n + " / " + minRoiCount);
                showAddedRois(imp, rmNow);
            }
        });
        pollTimer.start();

        Runnable closePick = () -> {
            pollTimer.stop();
            helper.dispose();
            HoloBioFijiUi.hideRoiManager();
            if (hideWhilePicking != null) {
                hideWhilePicking.setVisible(true);
                hideWhilePicking.toFront();
            }
        };
        done.addActionListener(e -> {
            RoiManager rmDone = HoloBioFijiUi.roiManagerForPicking();
            if (rmDone.getCount() < minRoiCount && imp.getRoi() != null) {
                rmDone.addRoi(imp.getRoi());
            }
            if (rmDone.getCount() < minRoiCount) {
                String kind = roiKindLabel != null ? roiKindLabel : "ROI";
                JOptionPane.showMessageDialog(helper,
                    "Add at least " + minRoiCount + " " + kind + "(s) on the image (press \"t\" after each), then Done.",
                    "HoloBio", JOptionPane.WARNING_MESSAGE);
                return;
            }
            imp.deleteRoi();
            closePick.run();
            whenFinished.accept(true);
        });
        cancel.addActionListener(e -> {
            imp.deleteRoi();
            imp.setOverlay(before);
            closePick.run();
            whenFinished.accept(false);
        });
        cancelOnClose(helper, cancel);
        helper.pack();
        Window iw = imp.getWindow();
        helper.setLocation(iw.getX() + 20, iw.getY() + iw.getHeight() - helper.getHeight() - 20);
        helper.setVisible(true);
        IJ.showStatus("HoloBio: draw on \"" + imp.getTitle() + "\", then click Done in the helper.");
    }

    /** ROIs left on {@code imp}, numbered and in their profile colours. */
    static void drawNumberedRois(ImagePlus imp, Roi[] rois) {
        if (imp == null) {
            return;
        }
        Overlay ov = new Overlay();
        int fontPx = Math.max(18, Math.max(imp.getWidth(), imp.getHeight()) / 45);
        for (int i = 0; i < rois.length; i++) {
            Roi r = (Roi) rois[i].clone();
            java.awt.Color c = QPI_COLORS[i % QPI_COLORS.length];
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

    /**
     * Speckle dialog: Filter, then Measure &amp; Compare (items 7–12). Zones are drawn once on the
     * channel image and every output (contrast table, speckle plot, line profile) uses them, one
     * after the other; side by side shows the whole image. Iterations = 0 restores the original.
     */
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
        JLabel dlgSteps = HoloBioUiStyle.workflowSteps("Filter  →  Measure & Compare");
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
        tfIterations.setToolTipText("Integer ≥ 0. 0 = back to the original (unfiltered) image.");
        iterRow.add(HoloBioUiStyle.fieldLabel("Iterations"));
        iterRow.add(tfIterations);
        iterRow.add(HoloBioUiStyle.helperText("0 = original"));
        tabFilter.add(iterRow);

        JButton applyFilt = HoloBioUiStyle.primaryButton("Apply filter");
        JPanel filtBtnRow = flowLeft();
        filtBtnRow.add(applyFilt);
        tabFilter.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        tabFilter.add(filtBtnRow);
        tabs.addTab("Filter", tabFilter);

        // —— Measure & Compare (item 12) ——
        JPanel tabMeasure = compactTabPanel();
        addTabSectionTitle(tabMeasure, "Zones");
        JLabel measureHint = HoloBioUiStyle.hintHtml(
            "Draw the zones once on the channel image; each is split into Rows × Cols cells. "
                + "The outputs below all use these zones.", 280);
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
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabMeasure.add(grid);
        tabMeasure.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        addTabSectionTitle(tabMeasure, "Outputs");
        JCheckBox ckTable = new JCheckBox("Contrast table (each cell)", true);
        JCheckBox ckPlot = new JCheckBox("Speckle plot (contrast per iteration, each zone)", false);
        JCheckBox ckProf = new JCheckBox("Line profile (each zone)", false);
        JCheckBox ckSide = new JCheckBox("Side by side (whole image)", false);
        for (JCheckBox ck : new JCheckBox[] {ckTable, ckPlot, ckProf, ckSide}) {
            ck.setAlignmentX(Component.LEFT_ALIGNMENT);
            tabMeasure.add(ck);
        }
        JButton applyMeas = HoloBioUiStyle.primaryButton("Draw zones & run");
        JPanel measBtnRow = flowLeft();
        measBtnRow.add(applyMeas);
        tabMeasure.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        tabMeasure.add(measBtnRow);
        tabs.addTab("Measure & Compare", tabMeasure);

        // Inputs and state of the reconstruction clicked last, read when a button is pressed.
        java.util.function.Supplier<HoloBioToolInputs> inputsNow = () -> {
            HoloBioToolInputs now = callbacks != null ? callbacks.currentInputs() : null;
            return now != null ? now : inputs;
        };

        Runnable refreshStatus = () -> {
            boolean ampCh = filtAmp.isSelected();
            HoloBioSpeckleState st = callbacks != null ? callbacks.getSpeckleState() : null;
            String desc = st != null && st.hasFiltered(ampCh) ? st.getFilterDescription(ampCh) : null;
            String ch = ampCh ? "amplitude" : "phase";
            if (inputs == null && (callbacks == null || callbacks.currentInputs() == null)) {
                statusLabel.setText("<html>Reconstruct first, then apply a filter.</html>");
            } else if (desc != null) {
                statusLabel.setText("<html>Filtered " + ch + ": <b>" + desc + "</b>. Open <b>Measure &amp; Compare</b>.</html>");
            } else {
                statusLabel.setText("<html>" + ch.substring(0, 1).toUpperCase() + ch.substring(1)
                    + " not filtered: measure it as it is, or start on <b>Filter</b>.</html>");
            }
        };
        filtAmp.addActionListener(e -> refreshStatus.run());
        filtPhs.addActionListener(e -> refreshStatus.run());
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowActivated(java.awt.event.WindowEvent e) {
                refreshStatus.run();
            }
        });

        applyFilt.addActionListener(e -> {
            HoloBioToolInputs in = inputsNow.get();
            if (callbacks == null || in == null) {
                JOptionPane.showMessageDialog(dlg, "No reconstruction data.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean ampCh = filtAmp.isSelected();
            if (ampCh ? !in.hasAmplitudeField() : !in.hasPhaseField()) {
                JOptionPane.showMessageDialog(dlg, "Reconstruct first (" + (ampCh ? "amplitude" : "phase") + ").",
                    "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int p;
            try {
                p = Integer.parseInt(tfIterations.getText().trim());
            } catch (NumberFormatException ex) {
                p = -1;
            }
            if (p < 0) {
                JOptionPane.showMessageDialog(dlg, "Iterations must be an integer ≥ 0 (0 = original image).",
                    "HoloBio Speckle", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (p == 0) {
                restoreSpeckleOriginal(callbacks, in, ampCh);
                refreshStatus.run();
                return;
            }
            boolean spp = rbSpp.isSelected();
            if (spp && !in.hasComplexField()) {
                JOptionPane.showMessageDialog(dlg, "SPP needs a complex field — reconstruct first.",
                    "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            final HoloBioSpeckleState st = callbacks.getSpeckleState();
            final int pF = p;
            final SpeckleFilterRun[] run = new SpeckleFilterRun[1];
            // Filtering runs off the event thread so the button shows Working… and Fiji's
            // progress bar moves (item 8); the result is applied back on the event thread.
            HoloBioUiStyle.runBusyInBackground(applyFilt, "Working…",
                () -> run[0] = computeSpeckleFilter(in, st, ampCh, spp, pF),
                () -> {
                    if (run[0] != null) {
                        applySpeckleFilterRun(callbacks, st, run[0]);
                    }
                    refreshStatus.run();
                    tabs.setSelectedIndex(1);
                });
        });

        applyMeas.addActionListener(e -> {
            HoloBioToolInputs in = inputsNow.get();
            if (callbacks == null || in == null) {
                return;
            }
            boolean ampCh = filtAmp.isSelected();
            HoloBioSpeckleState st = callbacks.getSpeckleState();
            int z, r, c;
            try {
                z = Integer.parseInt(tfZones.getText().trim());
                r = Integer.parseInt(tfRows.getText().trim());
                c = Integer.parseInt(tfCols.getText().trim());
            } catch (NumberFormatException ex) {
                z = r = c = 0;
            }
            if (z < 1 || r < 1 || c < 1) {
                JOptionPane.showMessageDialog(dlg, "Zones, Rows, Cols must be integers ≥ 1.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean table = ckTable.isSelected();
            boolean plot = ckPlot.isSelected();
            boolean prof = ckProf.isSelected();
            boolean side = ckSide.isSelected();
            if (!table && !plot && !prof && !side) {
                JOptionPane.showMessageDialog(dlg, "Select at least one output.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean filtered = st != null && st.hasFiltered(ampCh);
            if ((plot || prof || side) && !filtered) {
                JOptionPane.showMessageDialog(dlg, "Speckle plot, line profile and side by side compare the "
                    + "original with the filtered image: apply a filter on the Filter tab first.\n"
                    + "The contrast table works without one.", "HoloBio Speckle", JOptionPane.INFORMATION_MESSAGE);
                if (!table) {
                    return;
                }
                plot = prof = side = false;
            }
            if (plot && !st.hasIterations()) {
                plot = false;
            }
            int w = in.fieldWidth;
            int h = in.fieldHeight;
            float[] original = st != null && st.getOriginal(ampCh) != null ? st.getOriginal(ampCh)
                : HoloBioToolInputs.speckleChannelBase0255(in, ampCh);
            float[] measured = filtered ? st.getFiltered(ampCh) : original;
            if (measured == null || measured.length != w * h) {
                JOptionPane.showMessageDialog(dlg, "No " + (ampCh ? "amplitude" : "phase") + " image of the "
                    + "current reconstruction; reconstruct (and filter) again.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            String desc = filtered ? st.getFilterDescription(ampCh) : null;
            if (side && !table && !plot && !prof) {
                showSideBySide(original, measured, w, h, desc);
                return;
            }
            ImagePlus onto = callbacks.outputImage(ampCh);
            if (onto != null && (onto.getWidth() != w || onto.getHeight() != h)) {
                onto = null;            // e.g. the cropped DLHM Kreuzer display: use a picking window
            }
            final boolean tableF = table, plotF = plot, profF = prof, sideF = side;
            final float[] originalF = original;
            String title = (filtered ? "Speckle — Filtered " : "Speckle — ") + (ampCh ? "amplitude" : "phase");
            runSpeckleZonePicker(dlg, measured, w, h, onto, title, z, r, c, (zoneRects, cells) -> {
                if (tableF) {
                    showSpeckleContrastTable(cells);
                }
                if (plotF) {
                    showSpeckleContrastPlot(st, zoneRects);
                }
                if (profF) {
                    showLineProfiles(originalF, measured, w, zoneRects, desc);
                }
                if (sideF) {
                    showSideBySide(originalF, measured, w, h, desc);
                }
            });
        });

        JButton close = HoloBioUiStyle.tertiaryButton("Close");
        close.addActionListener(e -> dlg.dispose());
        JPanel bottom = HoloBioUiStyle.buildFooter(
            "Filter first, then Measure & Compare.", close);

        dlg.add(header, BorderLayout.NORTH);
        dlg.add(tabs, BorderLayout.CENTER);
        dlg.add(bottom, BorderLayout.SOUTH);
        refreshStatus.run();
        dlg.pack();
        HoloBioUiStyle.polish(dlg);
        Dimension d = dlg.getSize();
        int w = Math.min(420, Math.max(340, d.width));
        int h = Math.min(480, Math.max(340, d.height));
        dlg.setSize(w, h);
        dlg.setMinimumSize(new Dimension(300, 320));
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /** A speckle filter computed off the event thread, applied on it. */
    private static final class SpeckleFilterRun {
        boolean amplitude;
        boolean spp;
        int iterations;
        int w, h;
        float[] originalBase;
        float[] fieldRe, fieldIm;           // SPP input field (the original)
        List<float[]> hmfIterations;
        HoloBioQpiSpeckleMath.SppIterations sppIterations;
        float[] display;
    }

    /**
     * HMF on the unfiltered channel image, or SPP on the unfiltered complex field (both as
     * HoloBio Python). Runs off the event thread and only reads {@code st}.
     */
    private static SpeckleFilterRun computeSpeckleFilter(HoloBioToolInputs in, HoloBioSpeckleState st,
                                                         boolean amplitude, boolean spp, int iterations) {
        SpeckleFilterRun r = new SpeckleFilterRun();
        r.amplitude = amplitude;
        r.spp = spp;
        r.iterations = iterations;
        r.w = in.fieldWidth;
        r.h = in.fieldHeight;
        r.originalBase = HoloBioToolInputs.speckleChannelBase0255(in, amplitude);
        String name = spp ? "SPP" : "HMF";
        java.util.function.IntConsumer progress = it -> {
            IJ.showStatus("HoloBio: " + name + " iteration " + it + "/" + iterations + "…");
            IJ.showProgress(it, iterations);
        };
        IJ.showStatus("HoloBio: " + name + " filtering…");
        if (spp) {
            boolean haveOriginal = st != null && st.getOriginalFieldRe() != null
                && st.getOriginalFieldRe().length == r.w * r.h;
            r.fieldRe = haveOriginal ? st.getOriginalFieldRe().clone() : in.fieldRe.clone();
            r.fieldIm = haveOriginal ? st.getOriginalFieldIm().clone() : in.fieldIm.clone();
            r.sppIterations = HoloBioQpiSpeckleMath.sppFilter(r.fieldRe, r.fieldIm, r.w, r.h, iterations, progress);
            int last = r.sppIterations.rePerIteration.size() - 1;
            float[] fre = r.sppIterations.rePerIteration.get(last);
            float[] fim = r.sppIterations.imPerIteration.get(last);
            r.display = amplitude
                ? HoloBioQpiSpeckleMath.complexToAmplitudeMinMax0255(fre, fim)
                : HoloBioQpiSpeckleMath.complexToPhaseMinMax0255(fre, fim);
        } else {
            if (r.originalBase == null) {
                return null;
            }
            r.hmfIterations = HoloBioQpiSpeckleMath.hybridMedianMeanIterations(
                r.originalBase.clone(), r.w, r.h, iterations, progress);
            r.display = r.hmfIterations.get(r.hmfIterations.size() - 1);
        }
        IJ.showProgress(1.0);
        return r;
    }

    private static void applySpeckleFilterRun(HoloBioToolCallbacks cb, HoloBioSpeckleState st, SpeckleFilterRun r) {
        if (st != null) {
            st.clearIterations();
            st.setFieldSize(r.w, r.h);
            if (r.amplitude) {
                st.ensureOriginalAmplitude(r.originalBase);
            } else {
                st.ensureOriginalPhase(r.originalBase);
            }
        }
        FloatProcessor fp = HoloBioQpiSpeckleMath.toFloatProcessor0255(r.display, r.w, r.h);
        if (r.spp) {
            if (st != null) {
                if (st.getOriginalFieldRe() == null) {
                    st.setOriginalField(r.fieldRe, r.fieldIm);
                }
                st.setSppIterations(r.sppIterations.rePerIteration, r.sppIterations.imPerIteration);
            }
            int last = r.sppIterations.rePerIteration.size() - 1;
            cb.onSpeckleSppResult(r.sppIterations.rePerIteration.get(last), r.sppIterations.imPerIteration.get(last),
                r.w, r.h, r.amplitude, fp);
        } else {
            if (st != null) {
                st.setHmfIterations(r.hmfIterations, r.amplitude);
            }
            cb.onSpeckleFilterResult(r.amplitude, fp);
        }
        if (st != null) {
            st.setFilterDescription(r.amplitude,
                (r.spp ? "SPP" : "HMF") + ", " + r.iterations + " iteration" + (r.iterations == 1 ? "" : "s"));
        }
        HoloBioFijiUi.log("[HoloBio Speckle filter] " + (r.spp ? "SPP" : "HMF") + " iterations=" + r.iterations
            + " channel=" + (r.amplitude ? "amplitude" : "phase"));
        IJ.showStatus("HoloBio: speckle filter applied.");
    }

    /** Iterations = 0: show the unfiltered reconstruction again and forget the filter (item 7). */
    private static void restoreSpeckleOriginal(HoloBioToolCallbacks cb, HoloBioToolInputs in, boolean amplitude) {
        HoloBioSpeckleState st = cb.getSpeckleState();
        int w = in.fieldWidth;
        int h = in.fieldHeight;
        float[] re = st != null ? st.getOriginalFieldRe() : null;
        float[] im = st != null ? st.getOriginalFieldIm() : null;
        if (re != null && im != null && re.length == w * h) {
            float[] disp = amplitude
                ? HoloBioQpiSpeckleMath.complexToAmplitudeMinMax0255(re, im)
                : HoloBioQpiSpeckleMath.complexToPhaseMinMax0255(re, im);
            cb.onSpeckleSppResult(re.clone(), im.clone(), w, h, amplitude,
                HoloBioQpiSpeckleMath.toFloatProcessor0255(disp, w, h));
        } else {
            float[] orig = st != null && st.getOriginal(amplitude) != null ? st.getOriginal(amplitude)
                : HoloBioToolInputs.speckleChannelBase0255(in, amplitude);
            if (orig != null) {
                cb.onSpeckleFilterResult(amplitude, HoloBioQpiSpeckleMath.toFloatProcessor0255(orig, w, h));
            }
        }
        if (st != null) {
            st.clearFiltered();
        }
        IJ.showStatus("HoloBio: original (unfiltered) image restored.");
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

    /**
     * Both images normalised to [0, 1] (each by its own min and max) and placed side by side, so
     * a filter that only changes the grey range does not look like an improvement (fix 12). The
     * filtered panel says how it was filtered; there is no contrast table any more (item 9).
     */
    private static void showSideBySide(float[] original, float[] filtered, int w, int h, String filterDesc) {
        float[] o = normalized01(original);
        float[] f = normalized01(filtered);
        String filteredTitle = filterDesc != null ? "Filtered (" + filterDesc + ")" : "Filtered";
        ImagePlus imp = new ImagePlus("Speckle Filter Result", sideBySideFigure(o, f, w, h,
            "Speckle Filter Result", "Original", filteredTitle));
        lastSideBySide = HoloBioFijiUi.showReusing(imp);
        lastSpeckleComparison = new double[] {speckleContrastNormalized(o), speckleContrastNormalized(f)};
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

    /**
     * Normalised speckle contrast per iteration, one curve per zone (Python
     * {@code compare_speckle_plot_var} on each zone). Table columns speckle_contrast and
     * speckle_contrast_norm (item 10). HMF iteration 0 is the unfiltered image.
     */
    private static void showSpeckleContrastPlot(HoloBioSpeckleState st, List<int[]> zones) {
        String type = st.getIterationsType() != null ? st.getIterationsType().toUpperCase() : "?";
        boolean hmf = "HMF".equals(type);
        Plot plot = new Plot("Normalized Speckle Contrast over Iterations (" + type + ")",
            "Iteration", "Normalized Contrast");
        ResultsTable rt = new ResultsTable();
        StringBuilder legend = new StringBuilder();
        double yMax = 1.05;
        int maxIt = 1;
        for (int k = 0; k < zones.size(); k++) {
            int[] z = zones.get(k);
            List<Double> raw = computeIterationContrasts(st, z[0], z[1], z[2], z[3]);
            if (raw.isEmpty()) {
                continue;
            }
            double c0 = raw.get(0);
            double[] xs = new double[raw.size()];
            double[] ys = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                xs[i] = hmf ? i : i + 1;
                ys[i] = c0 > 0 ? raw.get(i) / c0 : raw.get(i);
                rt.incrementCounter();
                rt.addValue("Zone", k + 1);
                rt.addValue("Iteration", xs[i]);
                rt.addValue("speckle_contrast", raw.get(i));
                rt.addValue("speckle_contrast_norm", ys[i]);
            }
            yMax = Math.max(yMax, maxOf(ys) * 1.1);
            maxIt = Math.max(maxIt, (int) xs[xs.length - 1]);
            java.awt.Color c = QPI_COLORS[k % QPI_COLORS.length];
            plot.setColor(c);
            plot.setLineWidth(2);
            plot.addPoints(xs, ys, Plot.LINE);
            plot.addPoints(xs, ys, Plot.CIRCLE);
            if (legend.length() > 0) {
                legend.append('\n');
            }
            legend.append("Zone ").append(k + 1);
        }
        plot.setLimits(hmf ? 0 : 1, Math.max(hmf ? 1 : 2, maxIt), 0, yMax);
        plot.setColor(java.awt.Color.BLACK);
        plot.setLineWidth(1);
        plot.addLegend(legend.toString());
        plot.setFrameSize(640, 384);
        showPlotReplacing(plot);
        rt.show("HoloBio Speckle Plot Data");
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

    /**
     * Line profile of each zone: column means of the original and the filtered image, side by
     * side on the same scale, each with its mean drawn and written on the graph. No table
     * (item 11). One window: a stack with a slice per zone.
     */
    private static void showLineProfiles(float[] original, float[] filtered, int w, List<int[]> zones,
                                         String filterDesc) {
        ImageStack stack = null;
        for (int k = 0; k < zones.size(); k++) {
            int[] z = zones.get(k);
            int x1 = z[0], y1 = z[1], x2 = z[2], y2 = z[3];
            int len = x2 - x1;
            if (len < 2 || y2 <= y1) {
                continue;
            }
            double[] xPos = new double[len];
            double[] pO = new double[len];
            double[] pF = new double[len];
            double mO = 0, mF = 0;
            for (int col = 0; col < len; col++) {
                int xx = x1 + col;
                xPos[col] = xx;
                double sO = 0, sF = 0;
                for (int yy = y1; yy < y2; yy++) {
                    sO += original[yy * w + xx];
                    sF += filtered[yy * w + xx];
                }
                pO[col] = sO / (y2 - y1);
                pF[col] = sF / (y2 - y1);
                mO += pO[col];
                mF += pF[col];
            }
            mO /= len;
            mF /= len;
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < len; i++) {
                lo = Math.min(lo, Math.min(pO[i], pF[i]));
                hi = Math.max(hi, Math.max(pO[i], pF[i]));
            }
            double padY = (hi - lo) * 0.05 + 1e-9;
            ImageProcessor[] panels = new ImageProcessor[2];
            for (int p = 0; p < 2; p++) {
                boolean orig = p == 0;
                double mean = orig ? mO : mF;
                Plot pk = new Plot(orig ? "Original" : (filterDesc != null ? "Filtered (" + filterDesc + ")" : "Filtered"),
                    "Pixel position", "Column mean (0–255)");
                pk.setLimits(xPos[0], xPos[len - 1], lo - padY, hi + padY);
                pk.setColor(orig ? java.awt.Color.BLUE : java.awt.Color.ORANGE);
                pk.setLineWidth(2);
                pk.addPoints(xPos, orig ? pO : pF, Plot.LINE);
                pk.setColor(java.awt.Color.DARK_GRAY);
                pk.setLineWidth(1);
                pk.drawDottedLine(xPos[0], mean, xPos[len - 1], mean, 3);
                pk.setColor(java.awt.Color.BLACK);
                pk.addLabel(0.02, 0.08, String.format(java.util.Locale.US, "mean = %.2f", mean));
                pk.setFrameSize(480, 320);
                panels[p] = pk.getProcessor().convertToRGB();
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
            fig.drawString("Line Profile Comparison — Zone " + (k + 1), fig.getWidth() / 2, 30);
            if (stack == null) {
                stack = new ImageStack(fig.getWidth(), fig.getHeight());
            }
            if (fig.getWidth() == stack.getWidth() && fig.getHeight() == stack.getHeight()) {
                stack.addSlice("Zone " + (k + 1), fig);
            }
        }
        if (stack != null && stack.getSize() > 0) {
            HoloBioFijiUi.showReusing(new ImagePlus("Line Profile Comparison", stack));
        }
    }

    /** Receives the zones drawn ({x1, y1, x2, y2} each) and the contrast of every cell. */
    private interface SpeckleZonesDone {
        void accept(List<int[]> zones, List<Double> cellContrasts);
    }

    /**
     * Python {@code apply_speckle}: draw each zone rectangle; it is split into rows × cols cells
     * whose contrast is measured on {@code data}. Drawn on {@code onto} (the channel's output
     * window, where the numbered grid stays afterwards) or, when null, on a picking window.
     */
    private static void runSpeckleZonePicker(Window hideDlg, float[] data, int w, int h, ImagePlus onto,
                                             String titleBase, int zoneCount, int rows, int cols,
                                             SpeckleZonesDone whenDone) {
        if (data == null || w <= 0 || h <= 0) {
            return;
        }
        if (hideDlg != null) {
            hideDlg.setVisible(false);
        }
        boolean temp = onto == null || onto.getWindow() == null;
        ImagePlus imp = temp ? bytePickImage(data, w, h, titleBase + " — zone 1/" + zoneCount) : onto;
        Overlay before = !temp && onto.getOverlay() != null ? onto.getOverlay().duplicate() : null;
        Overlay overlay = new Overlay();
        imp.setOverlay(overlay);
        imp.deleteRoi();
        if (temp) {
            HoloBioFijiUi.showImagePlus(imp);
            IJ.selectWindow(imp.getTitle());
        } else {
            ij.WindowManager.setCurrentWindow(imp.getWindow());
            imp.getWindow().toFront();
        }
        IJ.setTool("rectangle");

        final List<Double> contrasts = new ArrayList<>();
        final List<int[]> zoneRects = new ArrayList<>();
        final int[] nextLabel = {1};
        final int[] zonesDone = {0};

        JDialog helper = new JDialog((Frame) null, "HoloBio — Speckle zones", false);
        helper.setAlwaysOnTop(true);
        helper.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2));
        JLabel status = new JLabel("Zone 1 / " + zoneCount + " — draw a rectangle, then Record");
        status.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3));
        helper.add(status, BorderLayout.NORTH);
        JButton record = HoloBioUiStyle.primaryButton("Record");
        JButton finish = HoloBioUiStyle.secondaryButton("Results");
        finish.setEnabled(false);
        JButton cancel = HoloBioUiStyle.tertiaryButton("Cancel");
        helper.add(HoloBioUiStyle.buildFooter("On \"" + imp.getTitle() + "\"; each zone is split into "
            + rows + "×" + cols + " cells.", cancel, finish, record), BorderLayout.SOUTH);

        Runnable restoreParent = () -> {
            helper.dispose();
            if (temp) {
                imp.changes = false;
                imp.close();
            }
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
                    "Draw a rectangle on the image first (rectangle tool).",
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
            double[] cellSc = HoloBioQpiSpeckleMath.subdivideZoneContrasts(data, w, h, x1, y1, x2, y2, rows, cols);
            if (cellSc.length == 0) {
                JOptionPane.showMessageDialog(helper,
                    "Zone too small for " + rows + "×" + cols + " subdivision.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            zoneRects.add(new int[] {x1, y1, x2, y2});
            int wSub = (x2 - x1) / cols;
            int hSub = (y2 - y1) / rows;
            java.awt.Color zc = QPI_COLORS[zonesDone[0] % QPI_COLORS.length];
            for (int r = 0; r <= rows; r++) {
                Line ln = new Line(x1, y1 + r * hSub, x2, y1 + r * hSub);
                ln.setStrokeColor(zc);
                overlay.add(ln);
            }
            for (int c = 0; c <= cols; c++) {
                Line ln = new Line(x1 + c * wSub, y1, x1 + c * wSub, y2);
                ln.setStrokeColor(zc);
                overlay.add(ln);
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
                if (temp) {
                    imp.setTitle(titleBase + " — zone " + (zd + 1) + "/" + zoneCount);
                }
                status.setText("Zone " + (zd + 1) + " / " + zoneCount + " — draw a rectangle, then Record");
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
            whenDone.accept(zoneRects, contrasts);
        });

        cancel.addActionListener(e -> {
            if (!temp) {
                imp.deleteRoi();
                imp.setOverlay(before);
            }
            restoreParent.run();
        });

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
            rt.addValue("speckle_contrast", scRaw);
            rt.addValue("speckle_contrast_norm", scRaw / (maxRaw + 1e-9));
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
            rt.addValue("speckle_contrast", sumR / n);
            rt.addValue("speckle_contrast_norm", sumN / n);
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
