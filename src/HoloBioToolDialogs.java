import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.Line;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * QPI and Speckle tool dialogs aligned with HoloBio Python {@code functions_GUI} and {@code tools_GUI}.
 * Fiji uses the ROI Manager and {@link ResultsTable} instead of matplotlib.
 */
public final class HoloBioToolDialogs {

    private HoloBioToolDialogs() {}

    /**
     * Combined Bio-Analysis dialog: QPI Measurements + Microstructure Metrics (Python {@code init_bio_analysis_frame}).
     */
    public static void showBioAnalysisDialog(Frame owner, HoloBioToolInputs inputs) {
        JOptionPane.showMessageDialog(owner,
            "Bio-Analysis (QPI + microstructure) is staged for a future release.\nUse Tools → Speckle.",
            "HoloBio", JOptionPane.INFORMATION_MESSAGE);
        return;
        /*
        JDialog dlg = new JDialog(owner, "HoloBio — Bio-Analysis", false);
        dlg.setLayout(new BorderLayout(8, 8));

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel title = HoloBioUiStyle.mainTitle("Bio-Analysis");
        title.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(4));
        JLabel workflow = HoloBioUiStyle.workflowSteps("QPI profile  →  Microstructure metrics");
        workflow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        root.add(workflow);
        root.add(Box.createVerticalStrut(6));

        JLabel qpiTitle = HoloBioUiStyle.sectionTitle("QPI Measurements");
        qpiTitle.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        root.add(qpiTitle);
        root.add(Box.createVerticalStrut(4));

        JPanel roiRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        roiRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JRadioButton rbLinear = new JRadioButton("ROI Lineal", true);
        JRadioButton rbCircular = new JRadioButton("ROI Circular");
        ButtonGroup roiGroup = new ButtonGroup();
        roiGroup.add(rbLinear);
        roiGroup.add(rbCircular);
        roiRow.add(rbLinear);
        roiRow.add(rbCircular);
        root.add(roiRow);

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        modeRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        modeRow.add(new JLabel("Mode:"));
        JComboBox<String> modeCombo = new JComboBox<>(new String[] {"Thickness", "Index"});
        modeCombo.setSelectedItem("Thickness");
        modeRow.add(modeCombo);
        root.add(modeRow);

        JPanel meas = new JPanel(new GridLayout(2, 4, 6, 6));
        meas.setBorder(BorderFactory.createTitledBorder("Parameters"));
        meas.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JTextField tfZones = new JTextField("1", 6);
        JTextField tfIndSample = new JTextField("1.33", 6);
        JTextField tfIndMedium = new JTextField("1.00", 6);
        JTextField tfThickness = new JTextField("10.0", 6);
        meas.add(new JLabel("Zones"));
        meas.add(new JLabel("Ind. Sample"));
        meas.add(new JLabel("Ref. Medium"));
        meas.add(new JLabel("Thickness (µm)"));
        meas.add(tfZones);
        meas.add(tfIndSample);
        meas.add(tfIndMedium);
        meas.add(tfThickness);
        root.add(meas);

        JPanel scale = new JPanel(new GridLayout(2, 2, 6, 6));
        scale.setBorder(BorderFactory.createTitledBorder("Scale (µm/px = pixel size / M)"));
        scale.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JTextField tfPixelUm = new JTextField(
            inputs != null && inputs.defaultPixelUm > 0 ? String.format("%.4g", inputs.defaultPixelUm) : "2.40", 6);
        JTextField tfMag = new JTextField(
            inputs != null && inputs.defaultMag > 0 ? String.format("%.4g", inputs.defaultMag) : "40", 6);
        scale.add(new JLabel("Pixel size (µm)"));
        scale.add(tfPixelUm);
        scale.add(new JLabel("Lateral magnification M"));
        scale.add(tfMag);
        root.add(scale);

        Runnable syncFields = () -> {
            boolean thicknessMode = "Thickness".equals(modeCombo.getSelectedItem());
            tfIndSample.setEnabled(thicknessMode);
            tfIndMedium.setEnabled(thicknessMode);
            tfThickness.setEnabled(!thicknessMode);
        };
        modeCombo.addActionListener(e -> syncFields.run());
        syncFields.run();

        JLabel hint = new JLabel("<html><p style='width:440px'>After <b>Apply QPI</b>: a phase image opens for ROI picking. "
            + "For each zone, draw a <b>Straight Line</b> &mdash; linear: along the profile; circular: "
            + "first point = center, second = rim. Press <b>t</b> after each line, then <b>Done</b> in the helper. "
            + "Wavelength comes from the reconstruction panel (µm).</p></html>");
        hint.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        root.add(Box.createVerticalStrut(6));
        root.add(hint);
        root.add(Box.createVerticalStrut(12));

        JLabel msTitle = HoloBioUiStyle.sectionTitle("Microstructure Metrics");
        msTitle.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        root.add(msTitle);
        root.add(Box.createVerticalStrut(4));

        JPanel msSrc = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        msSrc.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JRadioButton msAmp = new JRadioButton("Amplitude", true);
        JRadioButton msPhs = new JRadioButton("Phase");
        ButtonGroup msSrcG = new ButtonGroup();
        msSrcG.add(msAmp);
        msSrcG.add(msPhs);
        msSrc.add(msAmp);
        msSrc.add(msPhs);
        root.add(msSrc);

        JPanel threshRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        threshRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JRadioButton rbOtsu = new JRadioButton("Otsu", true);
        JRadioButton rbManual = new JRadioButton("Manual");
        JRadioButton rbAdapt = new JRadioButton("Adaptive");
        ButtonGroup threshG = new ButtonGroup();
        threshG.add(rbOtsu);
        threshG.add(rbManual);
        threshG.add(rbAdapt);
        threshRow.add(rbOtsu);
        threshRow.add(rbManual);
        threshRow.add(rbAdapt);
        root.add(threshRow);

        JPanel manualThreshRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        manualThreshRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        manualThreshRow.add(new JLabel("Threshold (0–255):"));
        JTextField tfManualThresh = new JTextField("128", 5);
        manualThreshRow.add(tfManualThresh);
        manualThreshRow.setVisible(false);
        Runnable threshUi = () -> manualThreshRow.setVisible(rbManual.isSelected());
        rbOtsu.addActionListener(e -> threshUi.run());
        rbManual.addActionListener(e -> threshUi.run());
        rbAdapt.addActionListener(e -> threshUi.run());
        root.add(manualThreshRow);

        JPanel areaPanel = new JPanel();
        areaPanel.setLayout(new BoxLayout(areaPanel, BoxLayout.Y_AXIS));
        areaPanel.setBorder(BorderFactory.createTitledBorder("Particle area filter (px²)"));
        areaPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        JLabel lblMinArea = new JLabel("Min area: 100 px²");
        lblMinArea.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JSlider minAreaSlider = new JSlider(1, 8000, 100);
        minAreaSlider.setMajorTickSpacing(2000);
        minAreaSlider.setPaintTicks(true);
        minAreaSlider.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        minAreaSlider.setMaximumSize(new Dimension(400, 48));

        JLabel lblMaxArea = new JLabel("Max area: 10000 px²");
        lblMaxArea.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JSlider maxAreaSlider = new JSlider(50, 80000, 10000);
        maxAreaSlider.setMajorTickSpacing(20000);
        maxAreaSlider.setPaintTicks(true);
        maxAreaSlider.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        maxAreaSlider.setMaximumSize(new Dimension(400, 48));

        JLabel lblParticlePreview = new JLabel("Particles in range: — (move sliders, preview updates)");
        lblParticlePreview.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        Runnable syncAreaLabels = () -> {
            int mn = minAreaSlider.getValue();
            int mx = maxAreaSlider.getValue();
            if (mx < mn) {
                maxAreaSlider.setValue(mn);
                mx = mn;
            }
            lblMinArea.setText("Min area: " + mn + " px²");
            lblMaxArea.setText("Max area: " + mx + " px²");
        };

        JPanel msGrid = new JPanel(new GridLayout(2, 2, 6, 6));
        msGrid.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JTextField tfMsIndS = new JTextField("1.33", 6);
        JTextField tfMsIndM = new JTextField("1.00", 6);
        msGrid.add(new JLabel("Ind. Sample"));
        msGrid.add(new JLabel("Ind. Medium"));
        msGrid.add(tfMsIndS);
        msGrid.add(tfMsIndM);

        areaPanel.add(lblMinArea);
        areaPanel.add(minAreaSlider);
        areaPanel.add(lblMaxArea);
        areaPanel.add(maxAreaSlider);
        areaPanel.add(lblParticlePreview);
        areaPanel.add(msGrid);
        root.add(areaPanel);

        JCheckBox ckAutoProf = new JCheckBox("Automatic Phase Profile");
        ckAutoProf.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        JCheckBox ckThickness = new JCheckBox("Thickness Estimation");
        ckThickness.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        root.add(ckAutoProf);
        root.add(ckThickness);

        ChangeListener areaPreviewListener = e -> {
            if (minAreaSlider.getValueIsAdjusting() || maxAreaSlider.getValueIsAdjusting()) {
                return;
            }
            syncAreaLabels.run();
            if (inputs != null) {
                previewAreaFilter(owner, inputs, msAmp.isSelected(), rbManual.isSelected(),
                    rbAdapt.isSelected(), tfManualThresh, minAreaSlider.getValue(),
                    maxAreaSlider.getValue(), lblParticlePreview);
            }
        };
        minAreaSlider.addChangeListener(areaPreviewListener);
        maxAreaSlider.addChangeListener(areaPreviewListener);
        syncAreaLabels.run();

        JButton applyMs = new JButton("Apply Microstructure");
        applyMs.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        applyMs.addActionListener(e -> {
            if (inputs == null) {
                JOptionPane.showMessageDialog(dlg, "No reconstruction data.", "HoloBio Bio-Analysis",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!ckAutoProf.isSelected() && !ckThickness.isSelected()) {
                JOptionPane.showMessageDialog(dlg, "Select Automatic Phase Profile and/or Thickness Estimation.",
                    "HoloBio Bio-Analysis", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                double pxS = Double.parseDouble(tfPixelUm.getText().trim().replace(',', '.'));
                double magS = Double.parseDouble(tfMag.getText().trim().replace(',', '.'));
                int minA = minAreaSlider.getValue();
                int maxA = maxAreaSlider.getValue();
                double nS = Double.parseDouble(tfMsIndS.getText().trim().replace(',', '.'));
                double nM = Double.parseDouble(tfMsIndM.getText().trim().replace(',', '.'));
                if (!(pxS > 0) || !(magS > 1e-6) || minA < 1 || maxA < minA) {
                    throw new NumberFormatException();
                }
                if (ckThickness.isSelected() && msAmp.isSelected()) {
                    JOptionPane.showMessageDialog(dlg,
                        "Thickness estimation requires Phase (not Amplitude).", "HoloBio Bio-Analysis",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (ckThickness.isSelected() && Math.abs(nS - nM) < 1e-9) {
                    JOptionPane.showMessageDialog(dlg, "Ind. Sample and Ind. Medium must differ for thickness.",
                        "HoloBio Bio-Analysis", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                String method = rbManual.isSelected() ? "manual" : (rbAdapt.isSelected() ? "adaptive" : "otsu");
                final boolean ampMs = msAmp.isSelected();
                final boolean doProf = ckAutoProf.isSelected();
                final boolean doThk = ckThickness.isSelected();
                final double pxF = pxS;
                final double magF = magS;
                final int minAF = minA;
                final int maxAF = maxA;
                final String methF = method;
                double thrVal = 128;
                if (rbManual.isSelected()) {
                    thrVal = Double.parseDouble(tfManualThresh.getText().trim().replace(',', '.'));
                }
                final double thrF = thrVal;
                final double nSF = nS;
                final double nMF = nM;
                final HoloBioToolInputs inCopy = inputs;
                dlg.dispose();
                SwingUtilities.invokeLater(() -> runMicrostructure(owner, inCopy, ampMs, methF, thrF,
                    minAF, maxAF, nSF, nMF, pxF, magF, doProf, doThk));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Check area limits and refractive indices.", "HoloBio Bio-Analysis",
                    JOptionPane.WARNING_MESSAGE);
            }
        });
        root.add(applyMs);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton close = new JButton("Close");
        JButton applyQpi = new JButton("Apply QPI");
        close.addActionListener(ev -> dlg.dispose());
        applyQpi.addActionListener(e -> {
            if (inputs == null || !inputs.hasPhaseField()) {
                JOptionPane.showMessageDialog(dlg,
                    "No reconstructed phase field. Run phase compensation or phase shifting first.",
                    "HoloBio QPI", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (inputs.wavelengthUm <= 1e-12) {
                JOptionPane.showMessageDialog(dlg, "Wavelength (µm) must be set in the reconstruction parameters.",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                int zones = Integer.parseInt(tfZones.getText().trim());
                if (zones < 1 || zones > 20) {
                    JOptionPane.showMessageDialog(dlg, "Zones must be between 1 and 20.", "HoloBio QPI",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                double px = Double.parseDouble(tfPixelUm.getText().trim().replace(',', '.'));
                double mag = Double.parseDouble(tfMag.getText().trim().replace(',', '.'));
                if (!(px > 0) || !(mag > 1e-6)) {
                    JOptionPane.showMessageDialog(dlg, "Enter valid pixel size (µm) and magnification M.",
                        "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                double nS = 1.33;
                double nM = 1.0;
                double dKnown = 10.0;
                if ("Thickness".equals(modeCombo.getSelectedItem())) {
                    nS = Double.parseDouble(tfIndSample.getText().trim().replace(',', '.'));
                    nM = Double.parseDouble(tfIndMedium.getText().trim().replace(',', '.'));
                } else {
                    dKnown = Double.parseDouble(tfThickness.getText().trim().replace(',', '.'));
                }
                final int zonesF = zones;
                final boolean circularF = rbCircular.isSelected();
                final boolean thicknessModeF = "Thickness".equals(modeCombo.getSelectedItem());
                final double pxF = px;
                final double magF = mag;
                final double nSF = nS;
                final double nMF = nM;
                final double dKnownF = dKnown;
                final HoloBioToolInputs inCopy = inputs;
                final Window hideDlg = dlg;
                SwingUtilities.invokeLater(() -> runQpiWithRoiManager(hideDlg, inCopy, zonesF, circularF, thicknessModeF,
                    nSF, nMF, dKnownF, pxF, magF));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Invalid numeric input.", "HoloBio QPI",
                    JOptionPane.WARNING_MESSAGE);
            }
        });
        buttons.add(close);
        buttons.add(applyQpi);

        dlg.add(new JScrollPane(root), BorderLayout.CENTER);
        dlg.add(buttons, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(500, 720));
        dlg.setLocationRelativeTo(owner);
        if (inputs != null && (inputs.hasAmplitudeDisplay0255() || inputs.hasPhaseDisplay0255())) {
            SwingUtilities.invokeLater(() -> previewAreaFilter(owner, inputs, msAmp.isSelected(),
                rbManual.isSelected(), rbAdapt.isSelected(), tfManualThresh,
                minAreaSlider.getValue(), maxAreaSlider.getValue(), lblParticlePreview));
        }
        dlg.setVisible(true);
        */
    }

    /** @deprecated use {@link #showBioAnalysisDialog}; kept for compatibility. */
    public static void showQpiDialog(Frame owner, HoloBioToolInputs inputs) {
        showBioAnalysisDialog(owner, inputs);
    }

    private static ImagePlus areaFilterPreviewImp;

    private static void previewAreaFilter(Frame owner, HoloBioToolInputs in, boolean amplitudeChannel,
                                          boolean manualThresh, boolean adaptiveThresh,
                                          JTextField tfManualThresh, int minArea, int maxArea,
                                          JLabel statusLabel) {
        float[] display = amplitudeChannel ? in.amplitudeDisplay0255 : in.phaseDisplay0255;
        if (display == null) {
            statusLabel.setText("Particles in range: — (reconstruct first)");
            return;
        }
        int w = in.fieldWidth;
        int h = in.fieldHeight;
        byte[] gray = HoloBioMicrostructureMath.toByteGray(display, w, h);
        String method = manualThresh ? "manual" : (adaptiveThresh ? "adaptive" : "otsu");
        double thr = 128;
        if (manualThresh) {
            try {
                thr = Double.parseDouble(tfManualThresh.getText().trim().replace(',', '.'));
            } catch (NumberFormatException ex) {
                thr = 128;
            }
        }
        int n = HoloBioMicrostructureMath.particleCountForAreaRange(gray, w, h, method, thr, minArea, maxArea);
        statusLabel.setText("Particles in range: " + n + "  (area " + minArea + " – " + maxArea + " px²)");
        if (areaFilterPreviewImp != null) {
            areaFilterPreviewImp.changes = false;
            areaFilterPreviewImp.close();
            areaFilterPreviewImp = null;
        }
        areaFilterPreviewImp = HoloBioMicrostructureMath.showAreaFilterPreview(
            gray, w, h, method, thr, minArea, maxArea);
    }

    private static void runMicrostructure(Frame owner, HoloBioToolInputs in, boolean amplitudeChannel,
                                         String method, double manualThreshold, int minArea, int maxArea,
                                         double nSample, double nMedium, double pixelUm, double mag,
                                         boolean autoProfile, boolean thicknessEst) {
        int w = in.fieldWidth;
        int h = in.fieldHeight;
        float[] display = amplitudeChannel ? in.amplitudeDisplay0255 : in.phaseDisplay0255;
        if (display == null) {
            JOptionPane.showMessageDialog(owner,
                "No " + (amplitudeChannel ? "amplitude" : "phase") + " display. Reconstruct first.",
                "HoloBio Bio-Analysis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        byte[] gray = HoloBioMicrostructureMath.toByteGray(display, w, h);
        float[] phaseRad = in.phaseDisplay0255 != null
            ? HoloBioMicrostructureMath.displayToPhaseRad(in.phaseDisplay0255)
            : HoloBioMicrostructureMath.displayToPhaseRad(display);

        boolean[] preview = HoloBioMicrostructureMath.createBinaryMask(gray, w, h, method, manualThreshold);
        ImagePlus maskPreview = new ImagePlus("HoloBio — threshold preview",
            HoloBioMicrostructureMath.maskToByte(preview, w, h));
        HoloBioFijiUi.showImagePlus(maskPreview);
        String pol = JOptionPane.showInputDialog(owner,
            "Sample polarity: type w if sample is white, b if black:", "w");
        if (pol == null) {
            maskPreview.close();
            return;
        }
        boolean sampleWhite = !"b".equalsIgnoreCase(pol.trim());
        maskPreview.close();

        HoloBioMicrostructureMath.ProcessResult proc = HoloBioMicrostructureMath.processParticles(
            gray, w, h, method, manualThreshold, minArea, maxArea, sampleWhite);
        HoloBioFijiUi.log("[HoloBio Microstructure] particles=" + proc.particles.size()
            + " method=" + method + " white=" + sampleWhite);

        double umPerPx = pixelUm / mag;
        if (autoProfile) {
            HoloBioMicrostructureMath.runAutomaticPhaseProfiles(gray, w, h, proc, phaseRad, umPerPx);
        }
        if (thicknessEst) {
            if (in.wavelengthUm <= 1e-12) {
                JOptionPane.showMessageDialog(owner, "Set wavelength (µm) in reconstruction parameters.",
                    "HoloBio Bio-Analysis", JOptionPane.WARNING_MESSAGE);
                return;
            }
            HoloBioMicrostructureMath.runThicknessEstimation(gray, w, h, method, manualThreshold,
                sampleWhite, phaseRad, in.wavelengthUm, nSample, nMedium);
        }
        IJ.showStatus("HoloBio: microstructure analysis finished.");
    }

    private static void runQpiWithRoiManager(Window hideDlg, HoloBioToolInputs in, int zones, boolean circular,
                                           boolean thicknessMode, double nSample, double nMedium,
                                           double thicknessUm, double pixelUm, double mag) {
        float[] phaseForQpi = in.phaseDisplay0255 != null
            ? HoloBioMicrostructureMath.displayToPhaseRad(in.phaseDisplay0255)
            : in.phaseRadians;
        ByteProcessor bp = HoloBioToolInputs.phaseToBytePickImage(phaseForQpi, in.fieldWidth, in.fieldHeight);
        if (bp == null) {
            JOptionPane.showMessageDialog(hideDlg, "Internal error: phase buffer missing.", "HoloBio QPI",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        RoiManager rm = HoloBioFijiUi.roiManagerForPicking();
        rm.reset();

        ImagePlus imp = new ImagePlus("HoloBio QPI — draw line ROIs (" + zones + ")", bp);
        String profileHint = circular
            ? "Circular: first point = center, second = rim."
            : "Linear: line along the integration path.";
        String instr = "Draw " + zones + " line(s). Press t after each. Then Done.";

        runRoiPickSession(imp, instr, zones, hideDlg, "line", "straight-line", ok -> {
            if (!ok) {
                return;
            }
            RoiManager rmDone = RoiManager.getInstance();
            if (rmDone == null || rmDone.getCount() < zones) {
                JOptionPane.showMessageDialog(hideDlg,
                    "Expected " + zones + " straight-line ROI(s), found "
                        + (rmDone != null ? rmDone.getCount() : 0) + ".",
                    "HoloBio QPI", JOptionPane.WARNING_MESSAGE);
                return;
            }
            computeAndShowQpiResults(hideDlg, in, phaseForQpi, zones, circular, thicknessMode,
                nSample, nMedium, thicknessUm, pixelUm, mag, rmDone);
        });
    }

    private static void computeAndShowQpiResults(Window parent, HoloBioToolInputs in, float[] phaseForQpi,
                                                 int zones, boolean circular, boolean thicknessMode,
                                                 double nSample, double nMedium, double thicknessUm,
                                                 double pixelUm, double mag, RoiManager rm) {
        int w = in.fieldWidth;
        int h = in.fieldHeight;
        float[] ph = phaseForQpi;
        double lam = in.wavelengthUm;
        double umPerPx = pixelUm / mag;

        ResultsTable rt = new ResultsTable();
        double[] dphis = new double[zones];
        for (int zi = 0; zi < zones; zi++) {
            Roi roi = rm.getRoi(zi);
            if (!(roi instanceof Line)) {
                JOptionPane.showMessageDialog(parent,
                    "ROI #" + (zi + 1) + " must be a Straight Line (use the line tool).", "HoloBio QPI",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            Line ln = (Line) roi;
            double x1 = ln.x1;
            double y1 = ln.y1;
            double x2 = ln.x2;
            double y2 = ln.y2;
            double[] prof = circular
                ? HoloBioQpiSpeckleMath.profileAlongCircle(ph, w, h, x1, y1, Math.hypot(x2 - x1, y2 - y1))
                : HoloBioQpiSpeckleMath.profileAlongLine(ph, w, h, x1, y1, x2, y2);
            double[] st = HoloBioQpiSpeckleMath.phaseStats(prof);
            double low = st[0];
            double high = st[1];
            double dphi = st[2];
            dphis[zi] = dphi;

            rt.incrementCounter();
            rt.addLabel("P" + (zi + 1));
            rt.addValue("Zone", zi + 1);
            rt.addValue("phi_low_rad", low);
            rt.addValue("phi_high_rad", high);
            rt.addValue("Delta_phi_rad", dphi);
            if (thicknessMode) {
                double thickness = Math.abs(dphi) * lam / (2.0 * Math.PI * Math.max(1e-12, Math.abs(nSample - nMedium)));
                rt.addValue("Thickness_um", thickness);
            } else {
                double nRel = 2.0 * Math.PI * thicknessUm / (lam * (Math.abs(dphi) > 1e-12 ? dphi : Double.NaN));
                rt.addValue("n_rel", nRel);
            }
        }

        if (zones > 1) {
            double meanD = 0;
            for (double v : dphis) {
                meanD += v;
            }
            meanD /= zones;
            double var = 0;
            for (double v : dphis) {
                double d = v - meanD;
                var += d * d;
            }
            double sd = Math.sqrt(var / Math.max(1, zones - 1));
            HoloBioFijiUi.log(String.format("[HoloBio QPI] Mean Delta_phi: %.4f ± %.4f rad (over %d zones)", meanD, sd, zones));
        }

        rt.show("HoloBio QPI");
        HoloBioFijiUi.log("[HoloBio QPI] zones=" + zones + " circular=" + circular + " thicknessMode=" + thicknessMode
            + " lambda_um=" + lam + " um_per_px=" + umPerPx);
        IJ.showStatus("HoloBio: QPI results in table \"HoloBio QPI\".");
    }

    private static void addWest(JPanel parent, Component child, GridBagConstraints c) {
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        parent.add(child, c);
        c.gridy++;
    }

    private static JPanel flowLeft() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel compactTabPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
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
        dlg.setLayout(new BorderLayout(6, 6));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        JLabel dlgTitle = HoloBioUiStyle.mainTitle("Speckle");
        dlgTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(dlgTitle);
        JLabel dlgSteps = HoloBioUiStyle.workflowSteps("Filter  →  Compare  →  Measure");
        dlgSteps.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(dlgSteps);
        JLabel statusLabel = HoloBioUiStyle.statusHtml(" ");
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(4));
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
        dimRow.add(filtAmp);
        dimRow.add(filtPhs);
        tabFilter.add(dimRow);

        JRadioButton rbHmf = new JRadioButton("HMF", true);
        JRadioButton rbSpp = new JRadioButton("SPP");
        ButtonGroup filtMethod = new ButtonGroup();
        filtMethod.add(rbHmf);
        filtMethod.add(rbSpp);
        JPanel methodRow = flowLeft();
        methodRow.add(rbHmf);
        methodRow.add(rbSpp);
        tabFilter.add(methodRow);

        JPanel iterRow = flowLeft();
        JTextField tfIterations = new JTextField(String.valueOf(HoloBioSpeckleDefaults.FILTER_ITERATIONS), 4);
        iterRow.add(new JLabel("Iterations"));
        iterRow.add(tfIterations);
        tabFilter.add(iterRow);

        JButton applyFilt = new JButton("Apply filter");
        JPanel filtBtnRow = flowLeft();
        filtBtnRow.add(applyFilt);
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
        JButton applyCmp = new JButton("Apply");
        applyCmp.setEnabled(false);
        JPanel cmpBtnRow = flowLeft();
        cmpBtnRow.add(applyCmp);
        tabCompare.add(cmpBtnRow);
        tabs.addTab("Compare", tabCompare);

        // —— Measure ——
        JPanel tabMeasure = compactTabPanel();
        addTabSectionTitle(tabMeasure, "Contrast measure");
        JRadioButton rbHol = new JRadioButton("Hologram", true);
        JRadioButton rbAmp = new JRadioButton("Amplitude");
        JRadioButton rbPhs = new JRadioButton("Phase");
        ButtonGroup srcGroup = new ButtonGroup();
        srcGroup.add(rbHol);
        srcGroup.add(rbAmp);
        srcGroup.add(rbPhs);
        JPanel srcRow = flowLeft();
        srcRow.add(rbHol);
        srcRow.add(rbAmp);
        srcRow.add(rbPhs);
        tabMeasure.add(srcRow);

        JPanel grid = new JPanel(new GridLayout(2, 3, 6, 2));
        JTextField tfZones = new JTextField(String.valueOf(HoloBioSpeckleDefaults.ZONES), 3);
        JTextField tfRows = new JTextField(String.valueOf(HoloBioSpeckleDefaults.ROWS), 3);
        JTextField tfCols = new JTextField(String.valueOf(HoloBioSpeckleDefaults.COLS), 3);
        grid.add(new JLabel("Zones"));
        grid.add(new JLabel("Rows"));
        grid.add(new JLabel("Cols"));
        grid.add(tfZones);
        grid.add(tfRows);
        grid.add(tfCols);
        tabMeasure.add(grid);

        JButton applyMeas = new JButton("Apply");
        JPanel measBtnRow = flowLeft();
        measBtnRow.add(applyMeas);
        tabMeasure.add(measBtnRow);
        tabs.addTab("Measure", tabMeasure);

        Runnable refreshStatus = () -> {
            boolean ampCh = filtAmp.isSelected();
            HoloBioSpeckleState st = callbacks != null ? callbacks.getSpeckleState() : null;
            boolean filtered = st != null && st.hasFiltered(ampCh);
            applyCmp.setEnabled(filtered);
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
            try {
                int z = Integer.parseInt(tfZones.getText().trim());
                int r = Integer.parseInt(tfRows.getText().trim());
                int c = Integer.parseInt(tfCols.getText().trim());
                if (z < 1 || r < 1 || c < 1) {
                    throw new NumberFormatException();
                }
                int src = rbAmp.isSelected() ? 1 : (rbPhs.isSelected() ? 2 : 0);
                if (inputs == null) {
                    JOptionPane.showMessageDialog(dlg, "No reconstruction data.", "HoloBio Speckle",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                SwingUtilities.invokeLater(() -> runSpeckleMeasurements(dlg, inputs, src, z, r, c));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Zones, Rows, Cols must be integers ≥ 1.", "HoloBio Speckle",
                    JOptionPane.WARNING_MESSAGE);
            }
        });

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dlg.dispose());
        bottom.add(close);

        dlg.add(header, BorderLayout.NORTH);
        dlg.add(tabs, BorderLayout.CENTER);
        dlg.add(bottom, BorderLayout.SOUTH);
        refreshStatus.run();
        dlg.pack();
        Dimension d = dlg.getSize();
        int w = Math.min(340, Math.max(280, d.width));
        int h = Math.min(360, Math.max(280, d.height));
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
            if (st != null) {
                if (in.amplitudeDisplay0255 != null) {
                    st.ensureOriginalAmplitude(in.amplitudeDisplay0255);
                }
                if (in.phaseDisplay0255 != null) {
                    st.ensureOriginalPhase(in.phaseDisplay0255);
                }
                st.setSppIterations(spp.rePerIteration, spp.imPerIteration);
            }
            float[] display = amplitudeChannel
                ? HoloBioQpiSpeckleMath.complexToAmplitude0255(fre, fim)
                : HoloBioQpiSpeckleMath.complexToPhase0255(fre, fim);
            FloatProcessor fp = HoloBioQpiSpeckleMath.toFloatProcessor0255(display, w, h);
            cb.onSpeckleSppResult(fre, fim, w, h, amplitudeChannel, fp);
            HoloBioFijiUi.log("[HoloBio Speckle filter] SPP iterations=" + iterations + " channel="
                + (amplitudeChannel ? "amplitude" : "phase"));
        } else {
            float[] base = amplitudeChannel ? in.amplitudeDisplay0255.clone() : in.phaseDisplay0255.clone();
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
        helper.setLayout(new BorderLayout(10, 10));
        JLabel lab = new JLabel("<html><body style='width:300px'>" + instructions + "</body></html>");
        lab.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        helper.add(lab, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton done = new JButton("Done");
        JButton cancel = new JButton("Cancel");
        btns.add(done);
        btns.add(cancel);
        helper.add(btns, BorderLayout.SOUTH);

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
        helper.setLayout(new BorderLayout(10, 10));
        JLabel lab = new JLabel("<html><body style='width:300px'>" + instructions + "</body></html>");
        lab.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        helper.add(lab, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton done = new JButton("Done");
        JButton cancel = new JButton("Cancel");
        btns.add(done);
        btns.add(cancel);
        helper.add(btns, BorderLayout.SOUTH);

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
            original = amplitudeChannel ? in.amplitudeDisplay0255 : in.phaseDisplay0255;
            if (original != null) {
                original = original.clone();
            }
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
        float[] pick = amplitudeChannel ? in.amplitudeDisplay0255 : in.phaseDisplay0255;
        if (pick == null) {
            JOptionPane.showMessageDialog(hideDlg,
                "No " + (amplitudeChannel ? "amplitude" : "phase") + " display for plot region.",
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
        helper.setLayout(new BorderLayout(8, 8));
        JLabel lab = new JLabel("Draw one rectangle, then:");
        lab.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        helper.add(lab, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton useRegion = new JButton("Plot");
        JButton cancel = new JButton("Cancel");
        btns.add(useRegion);
        btns.add(cancel);
        helper.add(btns, BorderLayout.SOUTH);

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

    private static void runSpeckleMeasurements(Window hideDlg, HoloBioToolInputs in, int source, int zones,
                                               int rows, int cols) {
        float[] raw;
        float[] display;
        int w;
        int h;
        String title;
        if (source == 0) {
            if (!in.hasHologram()) {
                JOptionPane.showMessageDialog(hideDlg, "No hologram loaded.", "HoloBio Speckle",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            raw = in.hologram.clone();
            display = HoloBioToolInputs.minMax0255(raw);
            w = in.holoWidth;
            h = in.holoHeight;
            title = "Speckle — Hologram";
        } else if (source == 1) {
            if (!in.hasAmplitudeField()) {
                JOptionPane.showMessageDialog(hideDlg, "No amplitude reconstruction.", "HoloBio Speckle",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            // Python apply_speckle uses amplitude_arrays (min–max → 0–255), not raw |U|.
            raw = HoloBioToolInputs.minMax0255(in.amplitudeFromField);
            display = raw.clone();
            w = in.fieldWidth;
            h = in.fieldHeight;
            title = "Speckle — Amplitude";
        } else {
            if (!in.hasPhaseField()) {
                JOptionPane.showMessageDialog(hideDlg, "No phase reconstruction.", "HoloBio Speckle",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            // Python phase_arrays: (φ+π)/(2π)·255 — not percentile-stretched preview.
            raw = HoloBioToolInputs.phasePythonSpeckle0255(in.phaseRadians);
            display = raw.clone();
            w = in.fieldWidth;
            h = in.fieldHeight;
            title = "Speckle — Phase";
        }

        runSpeckleZonePicker(hideDlg, raw, display, w, h, title, zones, rows, cols);
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
        helper.setLayout(new BorderLayout(10, 10));
        JLabel status = new JLabel("Zone 1 / " + zoneCount + " — draw rectangle, then Record");
        status.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        helper.add(status, BorderLayout.NORTH);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton record = new JButton("Record");
        JButton finish = new JButton("Results");
        finish.setEnabled(false);
        JButton cancel = new JButton("Cancel");
        btns.add(record);
        btns.add(finish);
        btns.add(cancel);
        helper.add(btns, BorderLayout.SOUTH);

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
