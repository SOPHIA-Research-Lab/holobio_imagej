import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * HoloBio DLHM Fiji plugin — Diffractive Lensless Holographic Microscopy.
 *
 * Geometry: a point source illuminates the sample at distance Z (sample-to-source, µm).
 * The camera records the in-line hologram at distance L (camera-to-source, µm).
 * The reconstruction distance is r = L − Z.
 *
 * Three algorithms are available (see HoloBioDlhmMath):
 *   Angular Spectrum (AS) — scaled propagation matching Python propagate() with scale_factor = L/Z
 *   Kreuzer (KR)          — cosine-apodized spherical prep + 3-FFT convolution (kreuzer3F)
 *   DLHM-rec (DL)         — propagation kernel exp(j·r·√(k²−4π²(fx²+fy²))) with radial undistort
 */
public class HoloBio_DLHM_Plugin implements PlugIn, HoloBioToolCallbacks {

    private static final int FORM_LABEL_W = HoloBioUiStyle.FORM_LABEL_W;
    private static final int FORM_FIELD_W = HoloBioUiStyle.FORM_FIELD_W;
    private static final int SLIDER_TICKS = 1000;

    // -------------------------------------------------------------------------
    // Model / state
    // -------------------------------------------------------------------------
    private final HoloBioDlhmParams   params       = new HoloBioDlhmParams();
    private final HoloBioSpeckleState speckleState = new HoloBioSpeckleState();

    private ImagePlus holoImage;
    private int       holoW, holoH;
    private float[]   holoPixels;

    private float[]   referencePixels;
    private String    referenceTitle;

    private float[]   currentFieldRe, currentFieldIm;
    private int       fieldW, fieldH;
    private ImagePlus ampImage, phaseImage;

    // -------------------------------------------------------------------------
    // UI components
    // -------------------------------------------------------------------------
    private JFrame   frame;
    private JLabel   statusLabel;

    private JTextField tfWavelength, tfPixelPitch;
    private JSlider    sliderL, sliderZ, sliderR;
    private JTextField tfL, tfZ, tfR;
    private JCheckBox  cbFixR;
    private JLabel     lblMag;
    private JRadioButton rbAS, rbKR, rbDL;
    private JTextField tfMinL, tfMaxL, tfMinZ, tfMaxZ, tfMinR, tfMaxR;
    private JPanel     secDistances, secLimits;
    private JComboBox<String> cbDistanceUnit;
    private JButton    btnReconstruct;

    /** Display unit for L/Z/r/limits. Internal model stays in µm. */
    private String distanceUnit = "µm";

    private volatile boolean updatingUi = false;

    // =========================================================================
    // PlugIn entry point
    // =========================================================================
    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(this::createAndShowGUI);
    }

    // =========================================================================
    // GUI construction
    // =========================================================================
    private void createAndShowGUI() {
        frame = new JFrame("HoloBio — Offline DLHM");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(0, 0));

        frame.add(buildTopBar(), BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setBorder(HoloBioUiStyle.contentPad());
        content.add(HoloBioUiStyle.hugNorth(buildParamsPanel()), BorderLayout.CENTER);

        JButton btnReconstructLocal = HoloBioUiStyle.primaryButton("Reconstruct");
        btnReconstruct = btnReconstructLocal;
        btnReconstruct.setToolTipText("Reconstruct amplitude and phase from the linked hologram.");
        btnReconstruct.addActionListener(e -> onReconstruct());

        JButton btnReset = HoloBioUiStyle.tertiaryButton("Reset");
        btnReset.setToolTipText("Restore default distances, limits, and optical parameters.");
        btnReset.addActionListener(e -> onResetModule());

        JButton btnSave = HoloBioUiStyle.secondaryButton("Save");
        btnSave.setToolTipText("Save Fourier transform, phase, amplitude, or the complex field (.npy).");
        btnSave.addActionListener(e -> onSave());

        JPanel south = new JPanel(new BorderLayout());
        south.add(HoloBioUiStyle.buildActionBar(btnReset, btnSave, btnReconstruct), BorderLayout.NORTH);
        south.add(buildStatusBar(), BorderLayout.SOUTH);
        frame.add(content, BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);

        refreshDistanceUi();
        HoloBioUiStyle.polish(frame);
        HoloBioUiStyle.packTight(frame, 0, 500, 695, HoloBioUiStyle.SPACE_2);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        setStatus(String.format("Ready — L=%.0f µm, Z=%.0f µm, Mag=%.2f×",
                params.getL(), params.getZ(), params.getMagnification()));
    }

    // ---- Top bar ------------------------------------------------------------
    private JPanel buildTopBar() {
        JButton btnLoad = HoloBioUiStyle.secondaryButton("Use Active Image");
        btnLoad.setToolTipText("Link the front-most Fiji image as the hologram.");
        btnLoad.addActionListener(e -> onLoadHologram());

        JButton btnRef = HoloBioUiStyle.secondaryButton("Select Reference");
        btnRef.setToolTipText("Use the active Fiji image as a reference hologram (mid-gray difference).");
        btnRef.addActionListener(e -> onLoadReference());

        JButton btnClearRef = HoloBioUiStyle.tertiaryButton("Clear Reference");
        btnClearRef.setToolTipText("Remove the reference hologram.");
        btnClearRef.addActionListener(e -> {
            if (referencePixels != null && !HoloBioUiStyle.confirm(frame, "Clear reference",
                    "Remove the current reference hologram?")) {
                return;
            }
            referencePixels = null;
            referenceTitle = null;
            setStatus("Reference cleared.");
            log("Reference cleared.");
        });

        JComboBox<String> toolsMenu = new JComboBox<>(new String[]{"Analysis tools", "Speckle", "QPI"});
        toolsMenu.setToolTipText("Open Speckle or QPI on the current reconstruction.");
        HoloBioUiStyle.styleField(toolsMenu);
        HoloBioUiStyle.sizeField(toolsMenu, 120);
        toolsMenu.addActionListener(e -> {
            String sel = (String) toolsMenu.getSelectedItem();
            if (sel != null && !"Analysis tools".equals(sel) && !"Tools".equals(sel)) {
                onToolSelected(sel);
                toolsMenu.setSelectedIndex(0);
            }
        });

        JPanel north = new JPanel(new BorderLayout());
        north.add(HoloBioUiStyle.buildHeader("Offline DLHM", null, null, toolsMenu), BorderLayout.NORTH);

        JPanel actions = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 0);
        actions.setBorder(HoloBioUiStyle.emptyPad(
            HoloBioUiStyle.SPACE_1, HoloBioUiStyle.CONTENT_MARGIN,
            HoloBioUiStyle.SPACE_1, HoloBioUiStyle.CONTENT_MARGIN));
        actions.add(btnLoad);
        actions.add(btnRef);
        actions.add(btnClearRef);
        north.add(actions, BorderLayout.SOUTH);
        return north;
    }

    // ---- Parameters panel (scrollable) --------------------------------------
    private JPanel buildParamsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(null);

        HoloBioUiStyle.stackSections(panel,
            buildOpticalSection(),
            buildDistancesSection(),
            buildAlgorithmSection(),
            buildLimitsSection());
        return panel;
    }

    private JPanel buildOpticalSection() {
        JPanel sec = HoloBioUiStyle.formSection("Optical parameters");
        GridBagConstraints c = HoloBioUiStyle.formGbc();

        tfWavelength = field(String.format("%.4f", params.getWavelengthUm()));
        tfPixelPitch = field(String.format("%.3f", params.getPixelPitchUm()));

        tfWavelength.addActionListener(e -> {
            double v = parseOr(tfWavelength, params.getWavelengthUm());
            if (v > 0) params.setWavelengthUm(v);
        });
        tfPixelPitch.addActionListener(e -> {
            double v = parseOr(tfPixelPitch, params.getPixelPitchUm());
            if (v > 0) params.setPixelPitchUm(v);
        });

        HoloBioUiStyle.addLabelField(sec, c, "Wavelength (µm)", tfWavelength);
        HoloBioUiStyle.addLabelField(sec, c, "Pixel pitch (µm)", tfPixelPitch);
        return sec;
    }

    private JPanel buildDistancesSection() {
        secDistances = vbox(HoloBioUiStyle.sectionPad("Distances (" + distanceUnit + ")"));

        cbFixR = new JCheckBox("Fix r");
        cbFixR.setSelected(params.isFixR());
        cbFixR.setToolTipText("When on, r is independent; L and Z no longer keep r = L − Z automatically.");
        lblMag = new JLabel("Geometric mag (L/Z) = ???");
        cbDistanceUnit = new JComboBox<>(HoloBioUiStyle.DISTANCE_UNITS);
        cbDistanceUnit.setSelectedItem(distanceUnit);
        cbDistanceUnit.setToolTipText("Unit for L, Z, r and distance limits (model stays in µm)");
        cbDistanceUnit.addActionListener(e -> {
            if (updatingUi) return;
            String next = (String) cbDistanceUnit.getSelectedItem();
            if (next == null || next.equals(distanceUnit)) return;
            distanceUnit = next;
            refreshDistanceUi();
        });

        JPanel topRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        topRow.add(cbFixR);
        topRow.add(HoloBioUiStyle.infoButton("Fix r",
            "When on, reconstruction distance r is independent. L and Z no longer keep r = L − Z automatically."));
        topRow.add(HoloBioUiStyle.hgap(HoloBioUiStyle.SPACE_2));
        topRow.add(HoloBioUiStyle.fieldLabel("Unit"));
        topRow.add(cbDistanceUnit);
        topRow.add(HoloBioUiStyle.hgap(HoloBioUiStyle.SPACE_2));
        topRow.add(lblMag);
        secDistances.add(topRow);

        cbFixR.addActionListener(e -> {
            params.setFixR(cbFixR.isSelected());
            refreshDistanceUi();
        });

        sliderL = slider();  tfL = field(""); JButton btnSetL = setBtn();
        sliderZ = slider();  tfZ = field(""); JButton btnSetZ = setBtn();
        sliderR = slider();  tfR = field(""); JButton btnSetR = setBtn();

        secDistances.add(HoloBioUiStyle.sliderFieldRow("L (camera–source):", sliderL, tfL, btnSetL));
        secDistances.add(HoloBioUiStyle.sliderFieldRow("Z (sample–source):", sliderZ, tfZ, btnSetZ));
        secDistances.add(HoloBioUiStyle.sliderFieldRow("r  =  L − Z:", sliderR, tfR, btnSetR));

        sliderL.addChangeListener(e -> { if (!updatingUi) onLChanged(sliderToVal(sliderL.getValue(), params.getMinL(), params.getMaxL())); });
        sliderZ.addChangeListener(e -> { if (!updatingUi) onZChanged(sliderToVal(sliderZ.getValue(), params.getMinZ(), params.getMaxZ())); });
        sliderR.addChangeListener(e -> { if (!updatingUi) onRChanged(sliderToVal(sliderR.getValue(), params.getMinR(), params.getMaxR())); });

        tfL.addActionListener(e -> onLChanged(parseDistanceField(tfL, params.getL())));
        tfZ.addActionListener(e -> onZChanged(parseDistanceField(tfZ, params.getZ())));
        tfR.addActionListener(e -> onRChanged(parseDistanceField(tfR, params.getR())));

        btnSetL.addActionListener(e -> onLChanged(parseDistanceField(tfL, params.getL())));
        btnSetZ.addActionListener(e -> onZChanged(parseDistanceField(tfZ, params.getZ())));
        btnSetR.addActionListener(e -> onRChanged(parseDistanceField(tfR, params.getR())));

        return secDistances;
    }

    private JPanel buildAlgorithmSection() {
        JPanel sec = vbox(HoloBioUiStyle.sectionPad("Algorithm"));

        rbAS = new JRadioButton("Angular Spectrum", true);
        rbKR = new JRadioButton("Kreuzer method", false);
        rbDL = new JRadioButton("DLHM", false);
        ButtonGroup grp = new ButtonGroup();
        grp.add(rbAS); grp.add(rbKR); grp.add(rbDL);

        JPanel algoRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 0);
        algoRow.add(HoloBioUiStyle.radioWithInfo(rbDL, "DLHM",
            "Lensless in-line reconstruction with radial undistort (DLHM-rec)."));
        algoRow.add(HoloBioUiStyle.radioWithInfo(rbAS, "Angular Spectrum",
            "Scaled angular-spectrum propagation with geometric magnification L/Z."));
        algoRow.add(HoloBioUiStyle.radioWithInfo(rbKR, "Kreuzer method",
            "Cosine-apodized spherical preparation and 3-FFT convolution (kreuzer3F). "
            + "Cosine period is 100."));
        sec.add(algoRow);

        ActionListener algoListener = e ->
            params.setAlgorithm(rbAS.isSelected() ? "AS" : rbKR.isSelected() ? "KR" : "DL");
        rbAS.addActionListener(algoListener);
        rbKR.addActionListener(algoListener);
        rbDL.addActionListener(algoListener);

        return sec;
    }

    private JPanel buildLimitsSection() {
        secLimits = HoloBioUiStyle.formSection("Distance limits (" + distanceUnit + ")");
        GridBagConstraints c = HoloBioUiStyle.formGbc();

        tfMinL = field(""); tfMaxL = field("");
        tfMinZ = field(""); tfMaxZ = field("");
        tfMinR = field(""); tfMaxR = field("");

        HoloBioUiStyle.addMinMaxRow(secLimits, c, "L", tfMinL, tfMaxL);
        HoloBioUiStyle.addMinMaxRow(secLimits, c, "Z", tfMinZ, tfMaxZ);
        HoloBioUiStyle.addMinMaxRow(secLimits, c, "r", tfMinR, tfMaxR);

        JPanel btnRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        JButton btnApply = HoloBioUiStyle.secondaryButton("Set Limits");
        JButton btnReset = HoloBioUiStyle.tertiaryButton("Reset");
        btnApply.setToolTipText("Apply the min/max ranges to the L, Z, and r sliders.");
        btnReset.setToolTipText("Restore default distance ranges.");
        btnApply.addActionListener(e -> applyLimits());
        btnReset.addActionListener(e -> resetLimits());
        btnRow.add(btnApply); btnRow.add(btnReset);
        c.gridx = 0;
        c.gridwidth = 5;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(HoloBioUiStyle.SPACE_1, 0, 0, 0);
        secLimits.add(btnRow, c);
        return secLimits;
    }

    private JPanel buildStatusBar() {
        statusLabel = new JLabel("Ready — load a hologram to begin.");
        return HoloBioUiStyle.statusBar(statusLabel);
    }

    // =========================================================================
    // Distance logic
    // =========================================================================
    private void onLChanged(double newL) {
        params.setL(clamp(newL, params.getMinL(), params.getMaxL()));
        refreshDistanceUi();
    }

    private void onZChanged(double newZ) {
        params.setZ(clamp(newZ, params.getMinZ(), params.getMaxZ()));
        refreshDistanceUi();
    }

    private void onRChanged(double newR) {
        params.setR(clamp(newR, params.getMinR(), params.getMaxR()));
        refreshDistanceUi();
    }

    private void refreshDistanceUi() {
        if (frame == null) return;
        updatingUi = true;
        try {
            double L = params.getL(), Z = params.getZ(), r = params.getR();
            sliderL.setValue(valToSlider(L, params.getMinL(), params.getMaxL()));
            sliderZ.setValue(valToSlider(Z, params.getMinZ(), params.getMaxZ()));
            sliderR.setValue(valToSlider(r, params.getMinR(), params.getMaxR()));
            tfL.setText(fmtDistance(L));
            tfZ.setText(fmtDistance(Z));
            tfR.setText(fmtDistance(r));
            if (tfMinL != null) {
                tfMinL.setText(fmtDistance(params.getMinL()));
                tfMaxL.setText(fmtDistance(params.getMaxL()));
                tfMinZ.setText(fmtDistance(params.getMinZ()));
                tfMaxZ.setText(fmtDistance(params.getMaxZ()));
                tfMinR.setText(fmtDistance(params.getMinR()));
                tfMaxR.setText(fmtDistance(params.getMaxR()));
            }
            setSectionTitle(secDistances, "Distances (" + distanceUnit + ")");
            setSectionTitle(secLimits, "Distance Limits (" + distanceUnit + ")");
            lblMag.setText(String.format("Geometric mag (L/Z) = %.2f×", params.getMagnification()));
        } finally {
            updatingUi = false;
        }
    }

    /** µm per selected distance display unit. */
    private double umPerDistanceUnit() {
        return HoloBioUiStyle.umPerDistanceUnit(distanceUnit);
    }

    private double toDisplayDistance(double um) {
        return um / umPerDistanceUnit();
    }

    private double fromDisplayDistance(double display) {
        return display * umPerDistanceUnit();
    }

    private double parseDistanceField(JTextField tf, double fallbackUm) {
        return fromDisplayDistance(parseOr(tf, toDisplayDistance(fallbackUm)));
    }

    private String fmtDistance(double um) {
        double v = toDisplayDistance(um);
        return Math.abs(v) >= 100 ? fmt0(v) : fmt1(v);
    }

    private static void setSectionTitle(JPanel sec, String title) {
        if (sec == null) return;
        javax.swing.border.TitledBorder tb = findTitled(sec.getBorder());
        if (tb != null) {
            tb.setTitle(title);
            sec.repaint();
        }
    }

    private static javax.swing.border.TitledBorder findTitled(javax.swing.border.Border b) {
        if (b instanceof javax.swing.border.TitledBorder) {
            return (javax.swing.border.TitledBorder) b;
        }
        if (b instanceof javax.swing.border.CompoundBorder) {
            javax.swing.border.CompoundBorder cb = (javax.swing.border.CompoundBorder) b;
            javax.swing.border.TitledBorder inner = findTitled(cb.getInsideBorder());
            return inner != null ? inner : findTitled(cb.getOutsideBorder());
        }
        return null;
    }

    // =========================================================================
    // Load hologram
    // =========================================================================
    private void onLoadHologram() {
        ImagePlus active = WindowManager.getCurrentImage();
        if (active == null) {
            setStatus("No active image in Fiji — open a hologram image first.");
            log("Load: no active Fiji image found.");
            return;
        }
        log("Load: using active image \"" + active.getTitle() + "\".");
        loadHologramFromImagePlus(active);
    }

    private void loadHologramFromImagePlus(ImagePlus imp) {
        try {
            ImagePlus dup = imp.duplicate();
            FloatProcessor fp = (FloatProcessor) dup.getProcessor().convertToFloat();
            holoW = fp.getWidth();
            holoH = fp.getHeight();
            holoPixels = (float[]) fp.getPixels();
            holoImage = new ImagePlus("DLHM Hologram", fp.duplicate());
            currentFieldRe = null;
            currentFieldIm = null;
            fieldW = 0; fieldH = 0;
            ampImage = null; phaseImage = null;
            showInFiji(holoImage, "DLHM Hologram");
            setStatus("Hologram loaded: " + holoW + "×" + holoH + " px  |  " + imp.getTitle());
            log("Loaded: " + imp.getTitle() + " (" + holoW + "×" + holoH + ")");
        } catch (Exception ex) {
            log("Load error: " + ex);
            setStatus("Failed to load hologram: " + ex.getMessage());
        }
    }

    private void onLoadReference() {
        ImagePlus active = WindowManager.getCurrentImage();
        if (active == null) {
            setStatus("No active image — open a reference hologram in Fiji first.");
            return;
        }
        if (holoPixels == null) {
            setStatus("Load a hologram before selecting a reference.");
            return;
        }
        FloatProcessor fp = (FloatProcessor) active.getProcessor().convertToFloat();
        if (fp.getWidth() != holoW || fp.getHeight() != holoH) {
            setStatus(String.format("Reference size %d×%d does not match hologram %d×%d.",
                    fp.getWidth(), fp.getHeight(), holoW, holoH));
            return;
        }
        referencePixels = (float[]) fp.getPixelsCopy();
        referenceTitle = active.getTitle();
        setStatus("Reference set: " + referenceTitle);
        log("Reference: " + referenceTitle);
    }

    /**
     * Build the array the reconstruction actually sees.
     *
     * <p>Matches Python {@code _prepare_worker_image}: with no reference the hologram is used
     * as-is; with a reference the signed difference is mapped onto a mid-grey 128 bias so both
     * positive and negative fringe changes survive into the FFT.
     */
    private float[] prepareWorkerHologram() {
        if (referencePixels == null) return holoPixels;
        int n = holoW * holoH;
        float[] out = new float[n];
        double maxAbs = 0;
        for (int i = 0; i < n; i++) {
            double d = Math.abs(holoPixels[i] - referencePixels[i]);
            if (d > maxAbs) maxAbs = d;
        }
        if (maxAbs < 1e-12) {
            Arrays.fill(out, 128f);
            return out;
        }
        for (int i = 0; i < n; i++) {
            double d = holoPixels[i] - referencePixels[i];
            out[i] = (float) Math.max(0, Math.min(255, 128.0 + 127.0 * (d / maxAbs)));
        }
        return out;
    }

    // =========================================================================
    // Reconstruction
    // =========================================================================
    private void onReconstruct() {
        if (holoPixels == null) {
            ImagePlus active = WindowManager.getCurrentImage();
            if (active != null) {
                loadHologramFromImagePlus(active);
                log("Using active Fiji image as hologram.");
            } else {
                setStatus("No hologram loaded. Use 'Load Hologram' or open an image in Fiji.");
                return;
            }
        }

        // Read optical / algorithm param fields
        double wl = parseOr(tfWavelength, params.getWavelengthUm());
        double px = parseOr(tfPixelPitch, params.getPixelPitchUm());
        if (wl > 0) params.setWavelengthUm(wl);
        if (px > 0) params.setPixelPitchUm(px);
        params.setCosinePeriod(100.0);
        HoloBioDlhmParams snap = snapshotParams();
        final float[] holo = prepareWorkerHologram();
        final int w = holoW, h = holoH;

        setStatus("Reconstructing…");
        log(String.format("Reconstruct: alg=%s  L=%.1f  Z=%.1f  r=%.1f µm  λ=%.4f µm%s",
                snap.getAlgorithm(), snap.getL(), snap.getZ(), snap.getR(), snap.getWavelengthUm(),
                referencePixels != null ? "  +ref" : ""));

        if (btnReconstruct != null) {
            btnReconstruct.setEnabled(false);
            btnReconstruct.setText("Working…");
        }

        new SwingWorker<float[][], Void>() {
            @Override
            protected float[][] doInBackground() {
                float[] re = new float[w * h];
                float[] im = new float[w * h];
                HoloBioDlhmMath.reconstruct(holo, h, w, snap, re, im);
                return new float[][]{re, im};
            }

            @Override
            protected void done() {
                try {
                    float[][] result = get();
                    currentFieldRe = result[0];
                    currentFieldIm = result[1];
                    fieldW = w;
                    fieldH = h;
                    showResults();
                    setStatus(String.format("Done — L=%.0f  Z=%.0f  r=%.0f µm  |  %s",
                            snap.getL(), snap.getZ(), snap.getR(), snap.getAlgorithm()));
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log("Reconstruction error: " + cause.getMessage());
                    setStatus("Reconstruction failed: " + cause.getMessage());
                } finally {
                    if (btnReconstruct != null) {
                        btnReconstruct.setText("Reconstruct");
                        btnReconstruct.setEnabled(true);
                    }
                }
            }
        }.execute();
    }

    // =========================================================================
    // Save — same four options as Offline DHM
    // =========================================================================
    private void onSave() {
        String[] options = {"Save FT", "Save Phase", "Save Amplitude", "Save Complex Field (.npy)"};
        String choice = (String) JOptionPane.showInputDialog(frame, "Choose what to save",
                "HoloBio Save", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == null) return;
        switch (choice) {
            case "Save FT":
                saveTiff(computeFtImage(), "FT", "No hologram loaded — use Active Image first.");
                break;
            case "Save Phase":
                saveTiff(phaseImage, "Phase", "No phase yet — press Reconstruct first.");
                break;
            case "Save Amplitude":
                saveTiff(ampImage, "Amplitude", "No amplitude yet — press Reconstruct first.");
                break;
            case "Save Complex Field (.npy)":
                // The DL method stores conj(Uz), matching Python's np.angle(np.conj(Uz)),
                // so np.angle() of the saved array is the displayed phase.
                HoloBioNpy.saveComplexDialog("complex_field", currentFieldRe, currentFieldIm, fieldW, fieldH);
                break;
            default:
                break;
        }
    }

    /**
     * Log-magnitude spectrum of the hologram the reconstruction sees (reference already
     * subtracted), at native size: Python {@code normalize(log1p(|_compute_spectrum|), 255)}.
     * The spatial pre-shift Python applies only changes phase, so it is omitted.
     */
    private ImagePlus computeFtImage() {
        if (holoPixels == null || holoW <= 0 || holoH <= 0) return null;
        float[] re = prepareWorkerHologram().clone();
        float[] im = new float[re.length];
        HoloBioRectFft.fft2dForward(re, im, holoH, holoW);
        HoloBioRectFft.fftShift2d(re, im, holoH, holoW);
        float[] mag = new float[re.length];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < mag.length; i++) {
            float v = (float) Math.log1p(Math.hypot(re[i], im[i]));
            mag[i] = v;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        float span = hi > lo ? hi - lo : 1f;
        for (int i = 0; i < mag.length; i++) mag[i] = 255f * (mag[i] - lo) / span;
        return new ImagePlus("DLHM FT", new FloatProcessor(holoW, holoH, mag, null));
    }

    private void saveTiff(ImagePlus image, String name, String emptyMessage) {
        if (image == null) {
            HoloBioFijiUi.message("HoloBio", emptyMessage);
            return;
        }
        ij.io.SaveDialog sd = new ij.io.SaveDialog("Save " + name, name, ".tif");
        if (sd.getDirectory() == null || sd.getFileName() == null) return;
        String path = sd.getDirectory() + sd.getFileName();
        ImagePlus out = image.duplicate();
        out.deleteRoi();
        if (new ij.io.FileSaver(out).saveAsTiff(path)) {
            setStatus("Saved " + name + " → " + path);
        } else {
            HoloBioFijiUi.message("HoloBio", "Failed to save image: " + path);
        }
    }

    private void showResults() {
        if (currentFieldRe == null) return;
        int n = fieldW * fieldH;
        float[] amp = new float[n], phase = new float[n];
        float ampMax = 0f;
        for (int i = 0; i < n; i++) {
            amp[i] = (float) Math.sqrt(
                    (double) currentFieldRe[i] * currentFieldRe[i]
                    + (double) currentFieldIm[i] * currentFieldIm[i]);
            if (amp[i] > ampMax) ampMax = amp[i];
            // Keep wrapped phase in (-π, π] before the display stretch — matches Python
            // normalize((phase+π)%(2π)−π, 255). atan2 already returns that range; the wrap
            // guards any residual from later field edits (e.g. Speckle SPP).
            phase[i] = (float) Math.IEEEremainder(
                    Math.atan2(currentFieldIm[i], currentFieldRe[i]), 2.0 * Math.PI);
        }

        FloatProcessor ampFp;
        FloatProcessor phaseFp;
        if ("KR".equals(params.getAlgorithm())) {
            // Python shows the (s−1)×(s−1) crop only. Low-amp pixels in the remapped FOV have
            // near-random phase that spans ±π and will crush a full-frame min–max stretch —
            // amplitude still looks fine because those pixels just become dark.
            float thr = Math.max(1e-12f, 0.05f * ampMax);
            int[] box = activeBBox(amp, fieldW, fieldH, thr);
            int x0 = box[0], y0 = box[1], cw = box[2], ch = box[3];
            float[] ampCrop = new float[cw * ch];
            float[] phCrop  = new float[cw * ch];
            for (int r = 0; r < ch; r++) {
                int src = (y0 + r) * fieldW + x0;
                System.arraycopy(amp, src, ampCrop, r * cw, cw);
                System.arraycopy(phase, src, phCrop, r * cw, cw);
            }
            ampFp   = new FloatProcessor(cw, ch, minMaxMasked0255(ampCrop, ampCrop, thr), null);
            phaseFp = new FloatProcessor(cw, ch, minMaxMasked0255(phCrop, ampCrop, thr), null);
            log(String.format("KR display crop: %d×%d (amp ≥ %.1f%% of max)", cw, ch, 5.0));
        } else {
            ampFp   = new FloatProcessor(fieldW, fieldH, minMax0255(amp),   null);
            phaseFp = new FloatProcessor(fieldW, fieldH, minMax0255(phase), null);
        }

        ampImage   = new ImagePlus("DLHM Amplitude", ampFp);
        phaseImage = new ImagePlus("DLHM Phase",     phaseFp);

        showInFiji(ampImage,   "DLHM Amplitude");
        showInFiji(phaseImage, "DLHM Phase");
        log("Amplitude and phase windows opened.");
    }

    /** Axis-aligned box of pixels with {@code mask > thr}: {x0, y0, width, height}. */
    private static int[] activeBBox(float[] mask, int w, int h, float thr) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                if (mask[r * w + c] <= thr) continue;
                if (c < x0) x0 = c;
                if (r < y0) y0 = r;
                if (c > x1) x1 = c;
                if (r > y1) y1 = r;
            }
        }
        if (x1 < x0) return new int[] { 0, 0, w, h };
        return new int[] { x0, y0, x1 - x0 + 1, y1 - y0 + 1 };
    }

    // =========================================================================
    // Tools
    // =========================================================================
    private void onToolSelected(String tool) {
        HoloBioToolInputs inputs = buildToolInputs();
        if ("Speckle".equals(tool)) {
            HoloBioFijiUi.showSpeckleDialog(frame, inputs, this);
        } else if ("QPI".equals(tool) || "Bio-Analysis".equals(tool)) {
            HoloBioFijiUi.showQpiDialog(frame, inputs);
        }
    }

    private HoloBioToolInputs buildToolInputs() {
        if (currentFieldRe == null || fieldW == 0) return null;
        int n = fieldW * fieldH;
        float[] amp = new float[n], phase = new float[n];
        for (int i = 0; i < n; i++) {
            amp[i]   = (float) Math.hypot(currentFieldRe[i], currentFieldIm[i]);
            phase[i] = (float) Math.atan2(currentFieldIm[i], currentFieldRe[i]);
        }
        return new HoloBioToolInputs(
                fieldW, fieldH,
                amp, phase,
                currentFieldRe.clone(), currentFieldIm.clone(),
                minMax0255(amp), pctStretch0255(phase),
                holoPixels != null ? holoPixels.clone() : null, holoW, holoH,
                params.getWavelengthUm(), params.getPixelPitchUm(), params.getMagnification());
    }

    // =========================================================================
    // HoloBioToolCallbacks
    // =========================================================================
    @Override
    public void onSpeckleFilterResult(boolean amplitudeChannel, FloatProcessor filtered0255) {
        if (filtered0255 == null) return;
        String title = amplitudeChannel ? "DLHM Amplitude" : "DLHM Phase";
        ImagePlus out = new ImagePlus(title, filtered0255);
        showInFiji(out, title);
        if (amplitudeChannel) ampImage = out; else phaseImage = out;
    }

    @Override
    public void onSpeckleSppResult(float[] fieldRe, float[] fieldIm, int w, int h,
                                   boolean amplitudeChannel, FloatProcessor display0255) {
        currentFieldRe = fieldRe;
        currentFieldIm = fieldIm;
        fieldW = w; fieldH = h;
        onSpeckleFilterResult(amplitudeChannel, display0255);
    }

    @Override
    public HoloBioSpeckleState getSpeckleState() { return speckleState; }

    // =========================================================================
    // Helpers
    // =========================================================================

    private HoloBioDlhmParams snapshotParams() {
        HoloBioDlhmParams snap = new HoloBioDlhmParams();
        snap.setWavelengthUm(params.getWavelengthUm());
        snap.setPixelPitchUm(params.getPixelPitchUm());
        // fixR=false on snap, so setL/setZ are independent
        snap.setL(params.getL());
        snap.setZ(params.getZ());
        snap.setAlgorithm(params.getAlgorithm());
        snap.setCosinePeriod(params.getCosinePeriod());
        return snap;
    }

    private void showInFiji(ImagePlus imp, String title) {
        ImagePlus existing = WindowManager.getImage(title);
        if (existing != null) { existing.changes = false; existing.close(); }
        ImagePlus out = imp.duplicate();
        out.setTitle(title);
        HoloBioFijiUi.showImagePlus(out);
    }

    private void applyLimits() {
        params.setMinL(parseDistanceField(tfMinL, params.getMinL()));
        params.setMaxL(parseDistanceField(tfMaxL, params.getMaxL()));
        params.setMinZ(parseDistanceField(tfMinZ, params.getMinZ()));
        params.setMaxZ(parseDistanceField(tfMaxZ, params.getMaxZ()));
        params.setMinR(parseDistanceField(tfMinR, params.getMinR()));
        params.setMaxR(parseDistanceField(tfMaxR, params.getMaxR()));
        refreshDistanceUi();
        log("Limits updated (" + distanceUnit + ").");
    }

    private void onResetModule() {
        if (!HoloBioUiStyle.confirm(frame, "Reset DLHM",
                "Restore default distances, limits, and optical parameters?")) {
            return;
        }
        params.setWavelengthUm(0.532);
        params.setPixelPitchUm(2.40);
        params.setL(10000.0);
        params.setZ(5000.0);
        params.setFixR(false);
        params.setAlgorithm("AS");
        params.setCosinePeriod(100.0);
        params.setMinL(0); params.setMaxL(20000);
        params.setMinZ(0); params.setMaxZ(20000);
        params.setMinR(0); params.setMaxR(20000);
        if (tfWavelength != null) tfWavelength.setText(String.format("%.4f", params.getWavelengthUm()));
        if (tfPixelPitch != null) tfPixelPitch.setText(String.format("%.3f", params.getPixelPitchUm()));
        if (rbAS != null) rbAS.setSelected(true);
        if (cbFixR != null) cbFixR.setSelected(false);
        refreshDistanceUi();
        setStatus("Parameters reset to defaults.");
    }

    private void resetLimits() {
        if (!HoloBioUiStyle.confirm(frame, "Reset limits",
                "Restore the default L, Z, and r ranges?")) {
            return;
        }
        params.setMinL(0); params.setMaxL(20000);
        params.setMinZ(0); params.setMaxZ(20000);
        params.setMinR(0); params.setMaxR(20000);
        refreshDistanceUi();
    }

    private void log(String msg) {
        setStatus(msg);
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> { if (statusLabel != null) statusLabel.setText(msg); });
    }

    // ---- image math ----------------------------------------------------------

    private static float[] minMax0255(float[] data) {
        float mn = data[0], mx = data[0];
        for (float v : data) { if (v < mn) mn = v; if (v > mx) mx = v; }
        if (mx <= mn + 1e-12f) return new float[data.length];
        float inv = 255f / (mx - mn);
        float[] out = new float[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (data[i] - mn) * inv;
        return out;
    }

    /** Min–max stretch using only samples where {@code mask[i] > threshold}. */
    private static float[] minMaxMasked0255(float[] data, float[] mask, float threshold) {
        float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
        boolean any = false;
        for (int i = 0; i < data.length; i++) {
            if (mask[i] <= threshold) continue;
            float v = data[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
            any = true;
        }
        float[] out = new float[data.length];
        if (!any || mx <= mn + 1e-12f) return out;
        float inv = 255f / (mx - mn);
        for (int i = 0; i < data.length; i++) {
            if (mask[i] <= threshold) continue;
            out[i] = (data[i] - mn) * inv;
        }
        return out;
    }

    private static float[] pctStretch0255(float[] data) {
        int n = data.length;
        double[] sorted = new double[n];
        for (int i = 0; i < n; i++) sorted[i] = data[i];
        Arrays.sort(sorted);
        double lo = sorted[Math.max(0, (int) (n * 0.02))];
        double hi = sorted[Math.min(n - 1, (int) (n * 0.98))];
        float[] out = new float[n];
        if (hi <= lo + 1e-9) {
            for (int i = 0; i < n; i++) out[i] = (float) (255.0 * ((data[i] + Math.PI) / (2.0 * Math.PI)));
            return out;
        }
        double inv = 255.0 / (hi - lo);
        for (int i = 0; i < n; i++)
            out[i] = (float) Math.max(0, Math.min(255, (data[i] - lo) * inv));
        return out;
    }

    // ---- slider/value helpers ------------------------------------------------

    private static int valToSlider(double val, double min, double max) {
        if (max <= min) return 0;
        return (int) Math.round(Math.max(0, Math.min(1, (val - min) / (max - min))) * SLIDER_TICKS);
    }

    private static double sliderToVal(int tick, double min, double max) {
        return min + (max - min) * tick / (double) SLIDER_TICKS;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double parseOr(JTextField tf, double fallback) {
        try { return Double.parseDouble(tf.getText().trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    // ---- UI component factories ---------------------------------------------

    private JPanel vbox(javax.swing.border.Border border) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        if (border != null) p.setBorder(border);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static Component vgap(int h) { return Box.createVerticalStrut(h); }

    private JTextField field(String text) {
        return HoloBioUiStyle.numericField(text);
    }

    private static JSlider slider() {
        JSlider s = new JSlider(0, SLIDER_TICKS, 0);
        s.setPreferredSize(new Dimension(160, HoloBioUiStyle.CONTROL_H));
        return s;
    }

    private static JButton setBtn() {
        return HoloBioUiStyle.secondaryButton("Set");
    }

    private JPanel makeRow(String label, Component input) {
        if (input instanceof JComponent) {
            return HoloBioUiStyle.formRow(label, (JComponent) input, FORM_LABEL_W);
        }
        JPanel row = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        JLabel lbl = HoloBioUiStyle.fieldLabel(label);
        lbl.setPreferredSize(new Dimension(FORM_LABEL_W, HoloBioUiStyle.CONTROL_H));
        row.add(lbl); row.add(input);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H + HoloBioUiStyle.SPACE_2));
        return row;
    }

    private JPanel makeSliderRow(String label, JSlider slider, JTextField tf, JButton btn) {
        JPanel row = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_1);
        JLabel lbl = HoloBioUiStyle.fieldLabel(label);
        lbl.setPreferredSize(new Dimension(FORM_LABEL_W, HoloBioUiStyle.CONTROL_H));
        HoloBioUiStyle.sizeField(tf, FORM_FIELD_W);
        row.add(lbl); row.add(slider); row.add(tf); row.add(btn);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H + HoloBioUiStyle.SPACE_2));
        return row;
    }

    private JPanel makeMinMaxRow(String label, JTextField minF, JTextField maxF) {
        JPanel row = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_1);
        JLabel lbl = HoloBioUiStyle.fieldLabel(label);
        lbl.setPreferredSize(new Dimension(28, HoloBioUiStyle.CONTROL_H));
        row.add(lbl);
        row.add(HoloBioUiStyle.fieldLabel("min"));
        HoloBioUiStyle.sizeField(minF, FORM_FIELD_W);
        row.add(minF);
        row.add(HoloBioUiStyle.hgap(HoloBioUiStyle.SPACE_2));
        row.add(HoloBioUiStyle.fieldLabel("max"));
        HoloBioUiStyle.sizeField(maxF, FORM_FIELD_W);
        row.add(maxF);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H + HoloBioUiStyle.SPACE_2));
        return row;
    }

    private static String fmt1(double v) { return String.format("%.1f", v); }
    private static String fmt0(double v) { return String.format("%.0f", v); }
}
