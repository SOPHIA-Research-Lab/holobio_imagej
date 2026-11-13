import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.ImageListener;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.io.SaveDialog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.BoxLayout;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HoloBio_DHM_Plugin implements PlugIn {
    private static final int FIELD_COLS = 5;
    private static final int FORM_LABEL_W = HoloBioUiStyle.FORM_LABEL_W;
    private static final int FORM_FIELD_W = HoloBioUiStyle.FORM_FIELD_W;
    private static final int FORM_COMBO_W = HoloBioUiStyle.FORM_COMBO_W;
    private static final int WINDOW_MAX_H = 680;

    private static final String AIA_METHOD = "AIA";
    private static final String AIA_GUESS_PCA = "PCA";
    private static final String AIA_GUESS_RAMP = "Linear ramp";
    private static final String AIA_GUESS_CUSTOM = "Custom";
    private static final String SRC_STACK = "Active stack";
    private static final String SRC_OPEN_STACK = "Open stack";
    private static final String SRC_FOLDER = "Folder";
    /** Fixed AIA settings, same as the validated MATLAB script (readInterferogramsV2.m). */
    private static final double AIA_NORM_RADIUS = 5;
    private static final double AIA_PRECISION = 1e-15;

    private final HoloBioDhmState state = new HoloBioDhmState();
    private final HoloBioDhmParams params = new HoloBioDhmParams();
    /** Mirrors HoloBio Python compensation settings dialog. */
    private final HoloBioCompensationSettings compSettings = new HoloBioCompensationSettings();
    /** Speckle state of the current reconstruction (each reconstruction keeps its own). */
    private HoloBioSpeckleState speckleState = new HoloBioSpeckleState();
    /**
     * Speckle filter output. Filters change only what is shown (the propagated field and its
     * images), never the reconstructed field, so a later propagation or QPI starts from clean data.
     */
    private final HoloBioToolCallbacks toolCallbacks = new HoloBioToolCallbacks() {
        @Override
        public void onSpeckleFilterResult(boolean amplitudeChannel, FloatProcessor filtered0255) {
            float[] px = (float[]) filtered0255.getPixels();
            if (amplitudeChannel) {
                speckleState.setFilteredAmplitude(px);
                state.setAmplitudeImage(new ImagePlus("Amplitude", filtered0255));
            } else {
                speckleState.setFilteredPhase(px);
                state.setPhaseImage(new ImagePlus("Phase", filtered0255));
            }
            update_right_view();
        }

        @Override
        public void onSpeckleSppResult(float[] fieldRe, float[] fieldIm, int w, int h,
                                       boolean amplitudeChannel, FloatProcessor display0255) {
            if (w != currentFieldW || h != currentFieldH) {
                return;
            }
            shownFieldRe = fieldRe.clone();
            shownFieldIm = fieldIm.clone();
            renderShownField();
            float[] disp = (float[]) display0255.getPixels();
            if (amplitudeChannel) {
                speckleState.setFilteredAmplitude(disp);
            } else {
                speckleState.setFilteredPhase(disp);
            }
            update_right_view();
        }

        @Override
        public HoloBioSpeckleState getSpeckleState() {
            return speckleState;
        }

        @Override
        public HoloBioToolInputs currentInputs() {
            activateFromFiji(false);
            return createToolInputs();
        }

        @Override
        public ImagePlus outputImage(boolean amplitude) {
            ImagePlus imp = WindowManager.getImage(outputTitle(amplitude ? "Amplitude" : "Phase"));
            return imp != null && imp.getWindow() != null ? imp : null;
        }

        @Override
        public ImagePlus qpiPhaseImage() {
            return phaseImageForActive();
        }

        @Override
        public double wavelengthUmFor(ImagePlus phaseImage) {
            Recon r = phaseImage != null ? reconOfTitle(phaseImage.getTitle()) : null;
            if (r != null && r.lambda > 0) {
                return r.lambda;
            }
            return params.getWavelengthUm() > 0 ? params.getWavelengthUm() : parseDoubleOr(tfPcLambda, Double.NaN);
        }
    };

    /** Phase compensation reconstruction parameters (wavelength, pitches, object distance z). */
    private JTextField tfPcLambda;
    private JTextField tfPcPitchX;
    private JTextField tfPcPitchY;
    /** Phase shifting physical parameters (independent from phase compensation UI). */
    private JTextField tfPsLambda;
    private JTextField tfPsPitchX;
    private JTextField tfPsPitchY;
    /** Numerical propagation module physical parameters. */
    private JTextField tfNpLambda;
    private JTextField tfNpPitchX;
    private JTextField tfNpPitchY;
    private JRadioButton rbPcMethodSemi;
    private JRadioButton rbPcMethodTu;
    private JRadioButton rbPcMethodVl;
    private JComboBox<String> phaseShiftMethodBox;
    /** AIA options, shown only when Method = AIA. */
    private JPanel aiaOptions;
    private JComboBox<String> aiaGuessBox;
    private JTextField tfAiaCustomGuess;
    private JTextField tfAiaMaxIter;
    private JCheckBox cbAiaReference;
    private JPanel aiaRefRows;
    private JComboBox<String> aiaRefSourceBox;
    private JComboBox<String> aiaRefStackBox;
    private JPanel aiaRefStackRow;
    private JPanel aiaRefFolderRows;
    private JTextField tfAiaRefFolder;
    private JTextField tfAiaRefFilter;
    /** Where phase-shifting frames come from (active stack or a folder). */
    private JComboBox<String> psSourceBox;
    private JPanel psFolderRows;
    private JTextField tfPsFolder;
    private JTextField tfPsFilter;
    /** Propagation algorithm choice (Angular / Fresnel) — only on the Numerical Propagation card. */
    private JComboBox<String> npPropagationMethodBox;
    private JCheckBox cbPsAutoPropagate;
    private PropagationUi propOptionsPc;
    private PropagationUi propOptionsPs;
    private PropagationUi propOptionsNp;
    private JRadioButton rbModulePhaseComp;
    private JRadioButton rbModulePhaseShift;
    private JRadioButton rbModuleNumericalProp;
    private JPanel moduleCards;
    private JButton btnCompensate;
    private JButton btnApply;
    private int srcWidth;
    private int srcHeight;
    private int padSize;
    private int padOffsetX;
    private int padOffsetY;
    private ImagePlus activeSourceImage;
    private float[] currentFieldRe;
    private float[] currentFieldIm;
    /** Width and height of {@link #currentFieldRe} / {@link #currentFieldIm} (powers of two after compensation). */
    private int currentFieldW;
    private int currentFieldH;
    /**
     * The field as it is shown: the reconstructed field after the last propagation (and any
     * speckle filter). QPI and Speckle measure this, not the field before propagation.
     */
    private float[] shownFieldRe;
    private float[] shownFieldIm;
    /** Base name of the linked hologram (title without extension); prefixes every output title. */
    private String holoBaseName = "Hologram";
    /** The Fiji image linked with Use Active Image (or picked by clicking it). */
    private ImagePlus linkedImage;
    private ImageListener linkedImageListener;
    /** Spatial filter controls, as in the Real-Time DHM panel (items 1–2). */
    private static final String ORDER_AUTO = "Automatic";
    private static final String ORDER_PICKED = "Picked";
    private JComboBox<String> cbMaskShape;
    private JComboBox<String> cbOrderMode;
    private JComboBox<String> cbFilterView;
    private JTextField tfMaskFactor;
    private JTextField tfMaskCx;
    private JTextField tfMaskCy;
    private JTextField tfMaskR;
    private JTextField tfMaskW;
    private JTextField tfMaskH;
    private JPanel maskCircleRows;
    private JPanel maskRectRows;
    private JPanel maskCentreRows;
    private javax.swing.JToggleButton btnPickOrder;
    /** Mouse listener on the spatial filter window while "Pick +1" is on. */
    private java.awt.event.MouseListener orderPicker;
    private ij.gui.ImageCanvas orderPickerCanvas;
    /** Last spatial filter applied and the grid it was applied on, for the filter visualization. */
    private HoloBioCompensationAlgorithms.FilteredOrder lastMask;
    private FloatProcessor lastMaskGrid;

    /**
     * One linked hologram and what was reconstructed from it. Every hologram linked in this
     * session keeps its record while any of its windows is open, so the buttons and tools act on
     * whichever image was clicked last in Fiji (item 6), and closing the hologram keeps its
     * reconstructions usable.
     */
    private static final class Recon {
        String base;
        ImagePlus source;
        ImagePlus hologram;
        int srcW, srcH;
        float[] fieldRe, fieldIm, shownRe, shownIm;
        int fieldW, fieldH;
        double lambda = Double.NaN, dx = Double.NaN, dy = Double.NaN;
        /** Lateral magnification M of the image scale (pixel = pitch / M); NaN = not set. */
        double scaleMag = Double.NaN;
        /** Pixel pitch used for the image scale (Set scale); NaN = the reconstruction pitch. */
        double scalePitchX = Double.NaN, scalePitchY = Double.NaN;
        HoloBioSpeckleState speckle = new HoloBioSpeckleState();
        HoloBioCompensationAlgorithms.FilteredOrder lastMask;
        FloatProcessor lastMaskGrid;
    }

    private final Map<String, Recon> recons = new LinkedHashMap<>();
    private Recon cur;
    /** Lateral magnification of the image scale for the current reconstruction (NaN = from pitch only). */
    private double scaleMag = Double.NaN;

    @Override
    public void run(String arg) {
        HoloBioClassPreload.preloadAll();
        SwingUtilities.invokeLater(this::createAndShowGUI);
    }

    private void createAndShowGUI() {
        JFrame frame = new JFrame("HoloBio — Offline DHM");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(0, 0));

        frame.add(buildTopBar(), BorderLayout.NORTH);
        frame.add(buildCenterPanel(), BorderLayout.CENTER);
        frame.add(buildBottomBar(), BorderLayout.SOUTH);

        HoloBioUiStyle.polish(frame);
        packWindow(frame);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildTopBar() {
        JButton useActiveButton = HoloBioUiStyle.secondaryButton("Use Active Image");
        useActiveButton.setToolTipText("Link the front-most Fiji image as the hologram.");
        useActiveButton.addActionListener(e -> onUseActiveImage());

        JComboBox<String> toolsMenu = new JComboBox<>(new String[] {"Analysis tools", "Set scale", "Speckle", "QPI"});
        toolsMenu.setToolTipText("Set the µm scale, or open Speckle or QPI, on the image clicked last in Fiji.");
        HoloBioUiStyle.styleField(toolsMenu);
        HoloBioUiStyle.sizeField(toolsMenu, 120);
        toolsMenu.addActionListener(e -> {
            String option = (String) toolsMenu.getSelectedItem();
            if (option != null && !"Analysis tools".equals(option)) {
                _on_tools_select(option);
                toolsMenu.setSelectedIndex(0);
            }
        });

        return HoloBioUiStyle.buildHeader(
            "Offline DHM",
            null,
            null,
            useActiveButton, toolsMenu);
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(0, 0));
        // Scrolls when a tall card (e.g. AIA with folder + reference) exceeds the window height cap.
        JScrollPane scroll = new JScrollPane(buildParametersPanel(),
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        HoloBioUiStyle.styleScroll(scroll);
        center.add(scroll, BorderLayout.CENTER);
        return center;
    }

    /** Fit the window to its content, at most 500 × WINDOW_MAX_H (taller cards scroll). */
    private static void packWindow(java.awt.Window w) {
        HoloBioUiStyle.packTight(w, 0, 500, WINDOW_MAX_H, HoloBioUiStyle.SPACE_1);
    }

    private JPanel buildParametersPanel() {
        JPanel left = new JPanel(new BorderLayout(0, HoloBioUiStyle.SPACE_1));
        left.setBorder(HoloBioUiStyle.contentPad());

        JPanel methodSelector = new JPanel(new BorderLayout());
        methodSelector.setBorder(HoloBioUiStyle.sectionPad("Submodule"));
        rbModulePhaseComp = new JRadioButton("Phase Compensation", true);
        rbModulePhaseShift = new JRadioButton("Phase Shifting", false);
        rbModuleNumericalProp = new JRadioButton("Numerical Propagation", false);
        ButtonGroup moduleGroup = new ButtonGroup();
        moduleGroup.add(rbModulePhaseComp);
        moduleGroup.add(rbModulePhaseShift);
        moduleGroup.add(rbModuleNumericalProp);
        methodSelector.add(HoloBioUiStyle.choiceGrid(3,
            rbModulePhaseComp, rbModulePhaseShift, rbModuleNumericalProp), BorderLayout.WEST);

        left.add(methodSelector, BorderLayout.NORTH);

        CardLayout moduleLayout = new CardLayout();
        moduleCards = HoloBioUiStyle.huggingCardPanel(moduleLayout);
        moduleCards.add(init_phase_compensation_frame(), "PHASE_COMP");
        moduleCards.add(init_phase_shifting_frame(), "PHASE_SHIFT");
        moduleCards.add(init_numerical_propagation_frame(), "NUM_PROP");
        left.add(HoloBioUiStyle.hugNorth(moduleCards), BorderLayout.CENTER);

        rbModulePhaseComp.addActionListener(e -> showModuleCard("PHASE_COMP"));
        rbModulePhaseShift.addActionListener(e -> showModuleCard("PHASE_SHIFT"));
        rbModuleNumericalProp.addActionListener(e -> showModuleCard("NUM_PROP"));
        return left;
    }

    private void showModuleCard(String name) {
        if ("NUM_PROP".equals(name) && tfNpLambda != null && tfNpLambda.getText().trim().isEmpty()) {
            tfNpLambda.setText(tfPcLambda.getText().trim());
            tfNpPitchX.setText(tfPcPitchX.getText().trim());
            tfNpPitchY.setText(tfPcPitchY.getText().trim());
        }
        CardLayout layout = (CardLayout) moduleCards.getLayout();
        layout.show(moduleCards, name);
        moduleCards.revalidate();
        java.awt.Window w = SwingUtilities.getWindowAncestor(moduleCards);
        if (w != null) {
            packWindow(w);
        }
    }

    private String moduleNameFromCard(String name) {
        if ("PHASE_SHIFT".equals(name)) {
            return "Phase Shifting";
        }
        if ("NUM_PROP".equals(name)) {
            return "Numerical Propagation";
        }
        return "Phase Compensation";
    }

    private boolean isModuleNumericalProp() {
        return rbModuleNumericalProp != null && rbModuleNumericalProp.isSelected();
    }

    private boolean isModulePhaseShift() {
        return rbModulePhaseShift != null && rbModulePhaseShift.isSelected();
    }

    private void setFixedControlSize(Component control, int w) {
        HoloBioUiStyle.setControlSize(control, w);
    }

    private JPanel compactRow(String labelText, Component input) {
        if (input instanceof JComponent) {
            return HoloBioUiStyle.formRow(labelText, (JComponent) input, FORM_LABEL_W);
        }
        JPanel row = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        JLabel label = HoloBioUiStyle.fieldLabel(labelText);
        label.setPreferredSize(new Dimension(FORM_LABEL_W, HoloBioUiStyle.CONTROL_H));
        row.add(label);
        row.add(input);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H + HoloBioUiStyle.SPACE_2));
        return row;
    }

    private JPanel compactInfoRow(String labelText, String valueText) {
        JPanel row = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        JLabel label = HoloBioUiStyle.fieldLabel(labelText);
        JLabel value = HoloBioUiStyle.footerNote(valueText);
        label.setPreferredSize(new Dimension(FORM_LABEL_W, HoloBioUiStyle.CONTROL_H));
        row.add(label);
        row.add(value);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H + HoloBioUiStyle.SPACE_2));
        return row;
    }

    private JPanel init_phase_compensation_frame() {
        JPanel panel = new JPanel(new BorderLayout(0, HoloBioUiStyle.SPACE_1));
        panel.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_1, 0, 0, 0));

        JPanel content = new JPanel();
        content.setLayout(new GridBagLayout());
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, HoloBioUiStyle.SPACE_1, 0);

        JPanel methodPanel = new JPanel(new BorderLayout());
        methodPanel.setBorder(HoloBioUiStyle.sectionPad("Method"));
        methodPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rbPcMethodSemi = new JRadioButton("Semi-Heuristic", true);
        rbPcMethodTu = new JRadioButton("Tu-DHM");
        rbPcMethodVl = new JRadioButton("Vortex Legendre");
        ButtonGroup pcMethodGroup = new ButtonGroup();
        pcMethodGroup.add(rbPcMethodSemi);
        pcMethodGroup.add(rbPcMethodTu);
        pcMethodGroup.add(rbPcMethodVl);
        JButton openSettingsButton = HoloBioUiStyle.gearButton(
            "Search size, grid, and Vortex–Legendre options.",
            e -> open_compensation_settings());
        methodPanel.add(HoloBioUiStyle.choiceGrid(4,
            HoloBioUiStyle.radioWithInfo(rbPcMethodSemi, "Semi-Heuristic",
                "ERS search that recentres the first-order carrier in the Fourier plane."),
            HoloBioUiStyle.radioWithInfo(rbPcMethodTu, "Tu-DHM",
                "CFS / Tu-DHM coarse-to-fine search for the first-order spectrum."),
            HoloBioUiStyle.radioWithInfo(rbPcMethodVl, "Vortex Legendre",
                "Vortex extraction plus Legendre polynomial flattening of residual tilt and curvature."),
            openSettingsButton), BorderLayout.WEST);
        content.add(methodPanel, gbc);
        gbc.gridy++;

        JPanel paramsPanel = HoloBioUiStyle.formSection("Physical parameters");
        GridBagConstraints pcC = HoloBioUiStyle.formGbc();
        tfPcLambda = new JTextField("0.633", FIELD_COLS);
        tfPcPitchX = new JTextField("3.75", FIELD_COLS);
        tfPcPitchY = new JTextField("3.75", FIELD_COLS);
        tfPcLambda.setToolTipText("Illumination wavelength in micrometres, e.g. 0.633.");
        String pitchTip = "<html>Camera <b>sensor</b> pixel size (µm), same as RT DHM.<br>"
                + "Do <b>not</b> divide by magnification M (e.g. use 3.75, not 3.75/40).<br>"
                + "Set M under Propagation for object-plane scaling.</html>";
        tfPcPitchX.setToolTipText(pitchTip);
        tfPcPitchY.setToolTipText(pitchTip);
        HoloBioUiStyle.addLabelField(paramsPanel, pcC, "Wavelength (µm)", tfPcLambda);
        HoloBioUiStyle.addLabelField(paramsPanel, pcC, "Sensor pitch X (µm)", tfPcPitchX);
        HoloBioUiStyle.addLabelField(paramsPanel, pcC, "Sensor pitch Y (µm)", tfPcPitchY);
        content.add(paramsPanel, gbc);
        gbc.gridy++;

        content.add(buildSpatialFilterPanel(), gbc);
        gbc.gridy++;

        // Compensate sits on the right, where the primary action of each panel is (fix 7).
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 48));
        btnCompensate = HoloBioUiStyle.primaryButton("Compensate");
        btnCompensate.setToolTipText("Run the selected compensation method on the linked hologram.");
        btnCompensate.addActionListener(e -> HoloBioUiStyle.runBusy(btnCompensate, "Working…", () -> {
            activateFromFiji(true);
            run_phase_compensation();
        }));
        actions.add(btnCompensate);
        content.add(actions, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 0, 0);

        propOptionsPc = new PropagationUi();
        panel.add(content, BorderLayout.NORTH);
        panel.add(propOptionsPc.root, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Spatial filter, set up as in the Real-Time DHM panel: mask shape; +1 order found
     * automatically or picked by clicking the spectrum; size from Factor (half-size = distance
     * from DC to the +1 order / Factor, Python default 3) unless a radius or width/height is
     * typed. Pixel values refer to the hologram's centred spectrum. The visualization button and
     * its Unfiltered / Filtered view live here, next to the settings they show (items 1–2).
     */
    private JPanel buildSpatialFilterPanel() {
        JPanel sec = HoloBioUiStyle.formSection("Spatial filter");
        GridBagConstraints c = HoloBioUiStyle.formGbc();
        cbMaskShape = new JComboBox<>(new String[] {"Circular", "Rectangular"});
        cbOrderMode = new JComboBox<>(new String[] {ORDER_AUTO, ORDER_PICKED});
        cbMaskShape.setLightWeightPopupEnabled(false);
        cbOrderMode.setLightWeightPopupEnabled(false);
        setFixedControlSize(cbMaskShape, FORM_COMBO_W + 18);
        setFixedControlSize(cbOrderMode, FORM_COMBO_W + 18);
        cbMaskShape.setToolTipText("Shape of the mask that isolates the +1 order.");
        cbOrderMode.setToolTipText("Automatic: the +1 order is searched in the spectrum. "
                + "Picked: the centre below, set by clicking the spectrum with Pick +1 or typed.");
        HoloBioUiStyle.addLabelField(sec, c, "Mask shape", cbMaskShape);
        HoloBioUiStyle.addLabelField(sec, c, "+1 order", cbOrderMode);

        tfMaskFactor = new JTextField("3", FIELD_COLS);
        HoloBioUiStyle.addLabelField(sec, c, "Factor", tfMaskFactor,
            "Automatic size: half-size = distance from DC to the +1 order / Factor (default 3, as HoloBio Python).");
        tfMaskCx = new JTextField("", FIELD_COLS);
        tfMaskCy = new JTextField("", FIELD_COLS);
        tfMaskR = new JTextField("", FIELD_COLS);
        tfMaskW = new JTextField("", FIELD_COLS);
        tfMaskH = new JTextField("", FIELD_COLS);
        tfMaskR.setToolTipText("Mask radius in spectrum pixels. Empty = automatic (distance / Factor).");
        tfMaskW.setToolTipText("Mask width in spectrum pixels. Empty = automatic (2 × distance / Factor).");
        tfMaskH.setToolTipText("Mask height in spectrum pixels. Empty = automatic (2 × distance / Factor).");
        maskCentreRows = new JPanel(new GridBagLayout());
        GridBagConstraints cc = HoloBioUiStyle.formGbc();
        HoloBioUiStyle.addLabelField(maskCentreRows, cc, "Centre X (px)", tfMaskCx);
        HoloBioUiStyle.addLabelField(maskCentreRows, cc, "Centre Y (px)", tfMaskCy);
        maskCircleRows = new JPanel(new GridBagLayout());
        GridBagConstraints rc = HoloBioUiStyle.formGbc();
        HoloBioUiStyle.addLabelField(maskCircleRows, rc, "Radius (px)", tfMaskR);
        maskRectRows = new JPanel(new GridBagLayout());
        GridBagConstraints wc = HoloBioUiStyle.formGbc();
        HoloBioUiStyle.addLabelField(maskRectRows, wc, "Width (px)", tfMaskW);
        HoloBioUiStyle.addLabelField(maskRectRows, wc, "Height (px)", tfMaskH);
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        sec.add(maskCentreRows, c);
        c.gridy++;
        sec.add(maskCircleRows, c);
        c.gridy++;
        sec.add(maskRectRows, c);
        c.gridy++;

        cbFilterView = new JComboBox<>(new String[] {"Unfiltered", "Filtered"});
        cbFilterView.setLightWeightPopupEnabled(false);
        cbFilterView.setToolTipText("Show the whole spectrum, or only what the mask keeps.");
        setFixedControlSize(cbFilterView, FORM_COMBO_W);
        cbFilterView.addActionListener(e -> refreshOpenSpatialFilterWindow());
        JButton showFilter = HoloBioUiStyle.compactButton("Spatial Filter Visualization");
        showFilter.setToolTipText("Show the hologram spectrum with the mask the current settings use.");
        showFilter.addActionListener(e -> showSpatialFilterVisualization());
        btnPickOrder = HoloBioUiStyle.toggleButton("Pick +1");
        btnPickOrder.setToolTipText("Click the +1 order on the spectrum to set the mask centre. "
                + "Set +1 order back to Automatic to search it again.");
        btnPickOrder.addActionListener(e -> onPickOrderToggled());
        JPanel viewRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        viewRow.add(showFilter);
        viewRow.add(cbFilterView);
        viewRow.add(btnPickOrder);
        sec.add(viewRow, c);

        cbMaskShape.addActionListener(e -> {
            syncMaskRows();
            refreshOpenSpatialFilterWindow();
        });
        cbOrderMode.addActionListener(e -> {
            prefillCentreFromLast();
            syncMaskRows();
            refreshOpenSpatialFilterWindow();
        });
        syncMaskRows();
        return sec;
    }

    private void syncMaskRows() {
        boolean picked = ORDER_PICKED.equals(cbOrderMode.getSelectedItem());
        boolean circle = "Circular".equals(cbMaskShape.getSelectedItem());
        maskCentreRows.setVisible(picked);
        maskCircleRows.setVisible(circle);
        maskRectRows.setVisible(!circle);
        java.awt.Window w = SwingUtilities.getWindowAncestor(maskCentreRows);
        if (w != null) {
            maskCentreRows.getParent().revalidate();
            packWindow(w);
        }
    }

    /** When switching to Picked, start from the centre of the mask last used (hologram-spectrum pixels). */
    private void prefillCentreFromLast() {
        if (!ORDER_PICKED.equals(cbOrderMode.getSelectedItem()) || lastMask == null || srcWidth <= 0
                || !tfMaskCx.getText().trim().isEmpty()) {
            return;
        }
        double sx = srcWidth / (double) lastMask.gridW;
        double sy = srcHeight / (double) lastMask.gridH;
        tfMaskCx.setText(String.format(java.util.Locale.US, "%.1f", srcWidth / 2.0 + (lastMask.fx - lastMask.gridW / 2.0) * sx));
        tfMaskCy.setText(String.format(java.util.Locale.US, "%.1f", srcHeight / 2.0 + (lastMask.fy - lastMask.gridH / 2.0) * sy));
    }

    /** Optional positive number in {@code tf}: NaN when empty, -1 (after a message) when invalid. */
    private static double optionalPositive(JTextField tf, String what) {
        String t = tf.getText().trim();
        if (t.isEmpty()) {
            return Double.NaN;
        }
        try {
            double v = Double.parseDouble(t.replace(',', '.'));
            if (v > 0 && !Double.isInfinite(v)) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        HoloBioFijiUi.message("HoloBio", "Spatial filter: " + what + " must be a number greater than zero, or empty.");
        return -1;
    }

    /**
     * The mask chosen in the panel, for a hologram of {@code w×h}. Pixel values are in that
     * hologram's centred spectrum. Returns null (after a message) when a value is invalid.
     */
    private HoloBioCompensationAlgorithms.MaskSpec readMaskSpec(int w, int h) {
        boolean circle = cbMaskShape == null || "Circular".equals(cbMaskShape.getSelectedItem());
        if (cbOrderMode == null) {
            return HoloBioCompensationAlgorithms.MaskSpec.auto(circle);
        }
        double factor = optionalPositive(tfMaskFactor, "Factor");
        if (factor < 0) {
            return null;
        }
        if (Double.isNaN(factor)) {
            factor = HoloBioCompensationAlgorithms.MaskSpec.DEFAULT_FACTOR;
        }
        double rx;
        double ry;
        if (circle) {
            rx = optionalPositive(tfMaskR, "Radius");
            ry = rx;
        } else {
            rx = optionalPositive(tfMaskW, "Width");
            ry = optionalPositive(tfMaskH, "Height");
            rx = rx > 0 ? rx / 2.0 : rx;
            ry = ry > 0 ? ry / 2.0 : ry;
        }
        if (rx < 0 || ry < 0) {
            return null;
        }
        boolean picked = ORDER_PICKED.equals(cbOrderMode.getSelectedItem());
        double cx = 0;
        double cy = 0;
        if (picked) {
            try {
                cx = Double.parseDouble(tfMaskCx.getText().trim());
                cy = Double.parseDouble(tfMaskCy.getText().trim());
            } catch (NumberFormatException ex) {
                HoloBioFijiUi.message("HoloBio", "Spatial filter: +1 order is Picked; click it on the spectrum with "
                        + "Pick +1, or type its centre X and Y.");
                return null;
            }
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) {
                HoloBioFijiUi.message("HoloBio", "Spatial filter: the centre must lie inside the "
                        + w + "x" + h + " spectrum.");
                return null;
            }
        }
        return HoloBioCompensationAlgorithms.MaskSpec.of(circle, picked, cx, cy, factor,
            Double.isNaN(rx) ? 0 : rx, Double.isNaN(ry) ? 0 : ry, w, h);
    }

    /** Pick +1: the next click on the spatial filter window sets the mask centre. */
    private void onPickOrderToggled() {
        if (!btnPickOrder.isSelected()) {
            stopOrderPicking();
            return;
        }
        if (!state.hasHologram()) {
            btnPickOrder.setSelected(false);
            HoloBioFijiUi.message("HoloBio", "Link a hologram first (Use Active Image, or click it in Fiji).");
            return;
        }
        showSpatialFilterVisualization();
        ImagePlus vis = WindowManager.getImage(outputTitle("Spatial filter"));
        if (vis == null || vis.getCanvas() == null) {
            btnPickOrder.setSelected(false);
            return;
        }
        stopOrderPicking();
        orderPickerCanvas = vis.getCanvas();
        orderPicker = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent ev) {
                ij.gui.ImageCanvas cv = orderPickerCanvas;
                if (cv == null) {
                    return;
                }
                double gx = cv.offScreenXD(ev.getX());
                double gy = cv.offScreenYD(ev.getY());
                SwingUtilities.invokeLater(() -> onOrderPicked(gx, gy));
            }
        };
        orderPickerCanvas.addMouseListener(orderPicker);
        WindowManager.setCurrentWindow(vis.getWindow());
        vis.getWindow().toFront();
        IJ.showStatus("HoloBio: click the centre of the +1 order on the spectrum.");
    }

    private void stopOrderPicking() {
        if (orderPickerCanvas != null && orderPicker != null) {
            orderPickerCanvas.removeMouseListener(orderPicker);
        }
        orderPicker = null;
        orderPickerCanvas = null;
    }

    /** A click on the spectrum grid (gx, gy): set Picked with that centre, in hologram-spectrum pixels. */
    private void onOrderPicked(double gx, double gy) {
        stopOrderPicking();
        btnPickOrder.setSelected(false);
        if (lastMaskGrid == null || srcWidth <= 0) {
            return;
        }
        int gw = lastMaskGrid.getWidth();
        int gh = lastMaskGrid.getHeight();
        if (Math.hypot(gx - gw / 2.0, gy - gh / 2.0) < 4) {
            HoloBioFijiUi.message("HoloBio", "That is the zero order (centre of the spectrum). Click the +1 order.");
            return;
        }
        double cx = srcWidth / 2.0 + (gx - gw / 2.0) * srcWidth / (double) gw;
        double cy = srcHeight / 2.0 + (gy - gh / 2.0) * srcHeight / (double) gh;
        tfMaskCx.setText(String.format(java.util.Locale.US, "%.1f", cx));
        tfMaskCy.setText(String.format(java.util.Locale.US, "%.1f", cy));
        cbOrderMode.setSelectedItem(ORDER_PICKED);
        syncMaskRows();
        showSpatialFilterVisualization();
        IJ.showStatus(String.format(java.util.Locale.US,
            "HoloBio: +1 order picked at (%.1f, %.1f); Compensate uses it.", cx, cy));
    }

    private JPanel init_phase_shifting_frame() {
        JPanel panel = new JPanel(new BorderLayout(0, HoloBioUiStyle.SPACE_1));
        panel.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_1, 0, 0, 0));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(HoloBioUiStyle.emptyPad(
            HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_1));

        JPanel methodRow = new JPanel();
        methodRow.setLayout(new BoxLayout(methodRow, BoxLayout.Y_AXIS));
        phaseShiftMethodBox = new JComboBox<>(new String[] {"BPS3", "BPS2", AIA_METHOD});
        phaseShiftMethodBox.setLightWeightPopupEnabled(false);
        setFixedControlSize(phaseShiftMethodBox, FORM_COMBO_W);
        phaseShiftMethodBox.addActionListener(e -> update_shifting_params());
        methodRow.add(compactRow("Method:", phaseShiftMethodBox));
        psSourceBox = new JComboBox<>(new String[] {SRC_STACK, SRC_FOLDER});
        psSourceBox.setLightWeightPopupEnabled(false);
        setFixedControlSize(psSourceBox, FORM_COMBO_W);
        psSourceBox.setToolTipText("Active stack: the linked image stack. Folder: images in a folder, sorted by name.");
        psSourceBox.addActionListener(e -> syncPhaseShiftRows());
        methodRow.add(compactRow("Frames source:", psSourceBox));
        tfPsFolder = new JTextField("", FIELD_COLS);
        tfPsFilter = new JTextField("", FIELD_COLS);
        psFolderRows = folderRows(tfPsFolder, tfPsFilter);
        methodRow.add(psFolderRows);
        methodRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(methodRow);

        aiaOptions = new JPanel();
        aiaOptions.setLayout(new BoxLayout(aiaOptions, BoxLayout.Y_AXIS));
        aiaOptions.setBorder(HoloBioUiStyle.sectionPad("AIA options"));
        aiaOptions.setAlignmentX(Component.LEFT_ALIGNMENT);
        aiaGuessBox = new JComboBox<>(new String[] {AIA_GUESS_PCA, AIA_GUESS_RAMP, AIA_GUESS_CUSTOM});
        aiaGuessBox.setLightWeightPopupEnabled(false);
        setFixedControlSize(aiaGuessBox, FORM_COMBO_W);
        aiaGuessBox.setToolTipText("Starting phase shifts for the iteration.");
        aiaGuessBox.addActionListener(e -> syncAiaGuessField());
        JPanel guessRow = compactRow("Initial guess:", aiaGuessBox);
        guessRow.add(HoloBioUiStyle.infoButton("Initial guess",
            "PCA: estimates the starting phase from the frames (recommended).\n"
            + "Linear ramp: small shifts 0 to 0.1 rad, the MATLAB default.\n"
            + "Custom: type the starting shifts in radians, either one number (the step between\n"
            + "frames, e.g. 1.5708 = π/2) or one value per frame separated by commas.\n\n"
            + "AIA cannot tell a result from its mirror image (phase and shifts with the opposite\n"
            + "sign); the initial guess decides which of the two you get."));
        aiaOptions.add(guessRow);
        tfAiaCustomGuess = new JTextField("1.5708", FIELD_COLS);
        setFixedControlSize(tfAiaCustomGuess, FORM_COMBO_W);
        HoloBioUiStyle.styleField(tfAiaCustomGuess);
        tfAiaCustomGuess.setToolTipText("One step for all frames, or one shift per frame separated by commas (rad).");
        aiaOptions.add(compactRow("Custom guess (rad):", tfAiaCustomGuess));
        tfAiaMaxIter = new JTextField("100", FIELD_COLS);
        setFixedControlSize(tfAiaMaxIter, FORM_FIELD_W);
        HoloBioUiStyle.styleField(tfAiaMaxIter);
        tfAiaMaxIter.setToolTipText("Upper limit; AIA stops earlier once the shifts stop changing.");
        aiaOptions.add(compactRow("Max iterations:", tfAiaMaxIter));

        cbAiaReference = new JCheckBox("Subtract reference", false);
        cbAiaReference.setToolTipText("Remove phase shifts caused by external movement, measured on a reference set.");
        cbAiaReference.addActionListener(e -> syncPhaseShiftRows());
        JPanel refToggleRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1);
        refToggleRow.add(cbAiaReference);
        refToggleRow.add(HoloBioUiStyle.infoButton("Subtract reference",
            "Runs AIA on a reference set (same number of frames, recorded at the same time\n"
            + "without the sample effect) and subtracts its phase shifts, i.e. the external\n"
            + "movement, from the object's shifts before computing the object phase.\n\n"
            + "Unchecked: the reference shifts are 0, so the result does not change."));
        refToggleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H + HoloBioUiStyle.SPACE_2));
        refToggleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        aiaOptions.add(refToggleRow);

        aiaRefRows = new JPanel();
        aiaRefRows.setLayout(new BoxLayout(aiaRefRows, BoxLayout.Y_AXIS));
        aiaRefRows.setAlignmentX(Component.LEFT_ALIGNMENT);
        aiaRefSourceBox = new JComboBox<>(new String[] {SRC_OPEN_STACK, SRC_FOLDER});
        aiaRefSourceBox.setLightWeightPopupEnabled(false);
        setFixedControlSize(aiaRefSourceBox, FORM_COMBO_W);
        aiaRefSourceBox.addActionListener(e -> syncPhaseShiftRows());
        aiaRefRows.add(compactRow("Reference source:", aiaRefSourceBox));
        aiaRefStackBox = new JComboBox<>();
        aiaRefStackBox.setLightWeightPopupEnabled(false);
        setFixedControlSize(aiaRefStackBox, FORM_COMBO_W);
        aiaRefStackBox.setToolTipText("Open image stack with the reference frames.");
        aiaRefStackBox.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                refreshReferenceStacks();
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        aiaRefStackRow = compactRow("Reference stack:", aiaRefStackBox);
        aiaRefRows.add(aiaRefStackRow);
        tfAiaRefFolder = new JTextField("", FIELD_COLS);
        tfAiaRefFilter = new JTextField("", FIELD_COLS);
        aiaRefFolderRows = folderRows(tfAiaRefFolder, tfAiaRefFilter);
        aiaRefRows.add(aiaRefFolderRows);
        aiaOptions.add(aiaRefRows);

        syncAiaGuessField();
        form.add(aiaOptions);

        JPanel psParams = new JPanel();
        psParams.setLayout(new BoxLayout(psParams, BoxLayout.Y_AXIS));
        psParams.setBorder(HoloBioUiStyle.sectionPad("Physical parameters"));
        psParams.setAlignmentX(Component.LEFT_ALIGNMENT);
        tfPsLambda = new JTextField("0.532", FIELD_COLS);
        tfPsPitchX = new JTextField("2.40", FIELD_COLS);
        tfPsPitchY = new JTextField("2.40", FIELD_COLS);
        setFixedControlSize(tfPsLambda, FORM_FIELD_W);
        setFixedControlSize(tfPsPitchX, FORM_FIELD_W);
        setFixedControlSize(tfPsPitchY, FORM_FIELD_W);
        HoloBioUiStyle.styleField(tfPsLambda);
        HoloBioUiStyle.styleField(tfPsPitchX);
        HoloBioUiStyle.styleField(tfPsPitchY);
        tfPsLambda.setToolTipText("Illumination wavelength in micrometres, e.g. 0.532.");
        tfPsPitchX.setToolTipText("Sensor pixel pitch X in micrometres.");
        tfPsPitchY.setToolTipText("Sensor pixel pitch Y in micrometres.");
        psParams.add(compactRow("Wavelength (µm):", tfPsLambda));
        psParams.add(compactRow("Pitch X (µm):", tfPsPitchX));
        psParams.add(compactRow("Pitch Y (µm):", tfPsPitchY));
        form.add(psParams);

        cbPsAutoPropagate = new JCheckBox("Auto-propagate after phase shifting", false);
        cbPsAutoPropagate.setToolTipText("Disable for strict parity checks against Python phase-shifting output.");
        cbPsAutoPropagate.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(cbPsAutoPropagate);

        propOptionsPs = new PropagationUi();
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(form);
        stack.add(propOptionsPs.root);
        panel.add(stack, BorderLayout.NORTH);
        syncPhaseShiftRows();
        return panel;
    }

    private JPanel init_numerical_propagation_frame() {
        JPanel panel = new JPanel(new BorderLayout(0, HoloBioUiStyle.SPACE_1));
        panel.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_1, 0, 0, 0));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(HoloBioUiStyle.sectionPad("Method"));
        npPropagationMethodBox = new JComboBox<>(new String[] {"Angular Spectrum", "Fresnel"});
        npPropagationMethodBox.setLightWeightPopupEnabled(false);
        npPropagationMethodBox.setToolTipText("Angular Spectrum: Fourier-domain propagator. Fresnel: paraxial kernel.");
        setFixedControlSize(npPropagationMethodBox, FORM_COMBO_W);
        JPanel propMethodRow = compactRow("Propagation method:", npPropagationMethodBox);
        propMethodRow.add(HoloBioUiStyle.infoButton("Angular Spectrum",
            "Angular Spectrum propagates the complex field in the Fourier domain. "
            + "Fresnel uses the paraxial quadratic-phase kernel."));
        top.add(propMethodRow);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel paramsPanel = new JPanel();
        paramsPanel.setLayout(new BoxLayout(paramsPanel, BoxLayout.Y_AXIS));
        paramsPanel.setBorder(HoloBioUiStyle.sectionPad("Physical parameters"));
        tfNpLambda = new JTextField("", FIELD_COLS);
        tfNpPitchX = new JTextField("", FIELD_COLS);
        tfNpPitchY = new JTextField("", FIELD_COLS);
        setFixedControlSize(tfNpLambda, FORM_FIELD_W);
        setFixedControlSize(tfNpPitchX, FORM_FIELD_W);
        setFixedControlSize(tfNpPitchY, FORM_FIELD_W);
        HoloBioUiStyle.styleField(tfNpLambda);
        HoloBioUiStyle.styleField(tfNpPitchX);
        HoloBioUiStyle.styleField(tfNpPitchY);
        tfNpLambda.setToolTipText("Illumination wavelength in micrometres.");
        tfNpPitchX.setToolTipText("Sensor pixel pitch X in micrometres.");
        tfNpPitchY.setToolTipText("Sensor pixel pitch Y in micrometres.");
        paramsPanel.add(compactRow("Wavelength (µm):", tfNpLambda));
        paramsPanel.add(compactRow("Pitch X (µm):", tfNpPitchX));
        paramsPanel.add(compactRow("Pitch Y (µm):", tfNpPitchY));
        paramsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(top, BorderLayout.NORTH);
        center.add(paramsPanel, BorderLayout.CENTER);
        center.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        propOptionsNp = new PropagationUi(false);
        bottom.add(propOptionsNp.root, BorderLayout.CENTER);
        JButton applyNP = HoloBioUiStyle.primaryButton("Apply");
        applyNP.setToolTipText("Propagate the current field with these parameters.");
        applyNP.addActionListener(e -> HoloBioUiStyle.runBusy(applyNP, "Working…", () -> {
            activateFromFiji(true);
            if (!readParamsFromNumericalPanel()) {
                return;
            }
            ensureCurrentFieldFromHologram(true);
            _apply_propagation(propOptionsNp, selectedNumericalPropagationMethod());
        }));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.add(applyNP);
        bottom.add(btnRow, BorderLayout.SOUTH);
        bottom.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(center);
        stack.add(bottom);
        panel.add(stack, BorderLayout.NORTH);
        return panel;
    }

    private String selectedNumericalPropagationMethod() {
        if (npPropagationMethodBox == null) {
            return "Angular Spectrum";
        }
        String m = (String) npPropagationMethodBox.getSelectedItem();
        return m != null ? m : "Angular Spectrum";
    }

    private JPanel buildBottomBar() {
        JButton resetButton = HoloBioUiStyle.tertiaryButton("Reset");
        JButton saveButton = HoloBioUiStyle.secondaryButton("Save…");
        btnApply = HoloBioUiStyle.primaryButton("Apply");

        resetButton.setToolTipText("Clear the linked holograms and reconstructed outputs.");
        saveButton.setToolTipText("Save Fourier transform, phase, amplitude, or the complex field (.npy).");
        btnApply.setToolTipText("Run the current submodule (compensate, shift, or propagate).");

        saveButton.addActionListener(e -> onSave());
        btnApply.addActionListener(e -> {
            if (isAiaSelected()) {
                startAiaRun();
            } else {
                HoloBioUiStyle.runBusy(btnApply, "Working…", this::onApplyMvp);
            }
        });
        resetButton.addActionListener(e -> onReset());

        return HoloBioUiStyle.buildActionBar(resetButton, saveButton, btnApply);
    }

    private void onUseActiveImage() {
        debugLog("Use Active Image clicked.");
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            HoloBioFijiUi.message("HoloBio", "Open an image in Fiji first, then click Use Active Image.");
            return;
        }
        loadHologramFromImagePlus(imp, "Active Hologram");
    }

    /**
     * Link {@code imp} as the hologram: its first plane is kept as a float image, outputs are
     * named after it (fix 1), and it gets its own reconstruction record. The Fiji image itself is
     * referenced, not duplicated, so a long stack is not held twice in memory (item 20); phase
     * shifting reads its planes when it runs.
     */
    private void loadHologramFromImagePlus(ImagePlus imp, String hologramTitle) {
        if (imp == null) {
            return;
        }
        saveCurrentRecon();
        activeSourceImage = imp;
        ImageProcessor first = imp.getStackSize() > 1 ? imp.getStack().getProcessor(1) : imp.getProcessor();
        FloatProcessor gray = (FloatProcessor) first.convertToFloatProcessor().duplicate();
        state.setHologramImage(new ImagePlus(hologramTitle, gray));
        srcWidth = gray.getWidth();
        srcHeight = gray.getHeight();
        padOffsetX = 0;
        padOffsetY = 0;
        holoBaseName = uniqueBaseFor(imp);
        state.setFourierImage(null);
        state.setAmplitudeImage(null);
        state.setPhaseImage(null);
        currentFieldRe = null;
        currentFieldIm = null;
        shownFieldRe = null;
        shownFieldIm = null;
        currentFieldW = 0;
        currentFieldH = 0;
        lastMask = null;
        lastMaskGrid = null;
        scaleMag = Double.NaN;
        speckleState = new HoloBioSpeckleState();
        linkTo(imp);
        Recon r = new Recon();
        r.base = holoBaseName;
        recons.put(r.base, r);
        cur = r;
        saveCurrentRecon();
        IJ.showStatus("HoloBio: hologram linked (" + holoBaseName + ").");
    }

    /** Output-title base for {@code imp}: its title without extension, made unique among linked holograms. */
    private String uniqueBaseFor(ImagePlus imp) {
        String base = baseNameOf(imp.getTitle());
        Recon existing = recons.get(base);
        if (existing == null || existing.source == imp || existing.source == null) {
            return base;
        }
        int k = 2;
        while (recons.containsKey(base + " (" + k + ")")) {
            k++;
        }
        return base + " (" + k + ")";
    }

    /** Copy the working state into the current reconstruction record. */
    private void saveCurrentRecon() {
        Recon r = cur;
        if (r == null) {
            return;
        }
        r.source = linkedImage;
        r.hologram = state.getHologramImage();
        r.srcW = srcWidth;
        r.srcH = srcHeight;
        r.fieldRe = currentFieldRe;
        r.fieldIm = currentFieldIm;
        r.fieldW = currentFieldW;
        r.fieldH = currentFieldH;
        r.shownRe = shownFieldRe;
        r.shownIm = shownFieldIm;
        r.speckle = speckleState;
        r.lastMask = lastMask;
        r.lastMaskGrid = lastMaskGrid;
        r.scaleMag = scaleMag;
    }

    /** Make {@code r} the reconstruction the buttons and tools work on. */
    private void switchToRecon(Recon r) {
        if (r == null || r == cur) {
            return;
        }
        saveCurrentRecon();
        cur = r;
        holoBaseName = r.base;
        linkedImage = r.source;
        activeSourceImage = r.source;
        state.setHologramImage(r.hologram);
        srcWidth = r.srcW;
        srcHeight = r.srcH;
        currentFieldRe = r.fieldRe;
        currentFieldIm = r.fieldIm;
        currentFieldW = r.fieldW;
        currentFieldH = r.fieldH;
        shownFieldRe = r.shownRe;
        shownFieldIm = r.shownIm;
        speckleState = r.speckle;
        lastMask = r.lastMask;
        lastMaskGrid = r.lastMaskGrid;
        scaleMag = r.scaleMag;
        state.setFourierImage(null);
        state.setAmplitudeImage(null);
        state.setPhaseImage(null);
        ImagePlus a = WindowManager.getImage(outputTitle("Amplitude"));
        ImagePlus p = WindowManager.getImage(outputTitle("Phase"));
        if (a != null) {
            state.setAmplitudeImage(new ImagePlus("Amplitude", a.getProcessor().duplicate()));
        }
        if (p != null) {
            state.setPhaseImage(new ImagePlus("Phase", p.getProcessor().duplicate()));
        }
        // The physical parameters of this reconstruction carry into Numerical Propagation.
        if (r.lambda > 0 && tfNpLambda != null) {
            tfNpLambda.setText(fmtParam(r.lambda));
            tfNpPitchX.setText(fmtParam(r.dx));
            tfNpPitchY.setText(fmtParam(r.dy));
        }
        IJ.showStatus("HoloBio: working on " + r.base + ".");
    }

    /** The reconstruction an output window belongs to ("<base> – Phase" → base), or null. */
    private Recon reconOfTitle(String title) {
        if (title == null) {
            return null;
        }
        int k = title.lastIndexOf(" – ");
        if (k > 0) {
            Recon r = recons.get(title.substring(0, k));
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private Recon reconOfSource(ImagePlus imp) {
        for (Recon r : recons.values()) {
            if (r.source == imp) {
                return r;
            }
        }
        return null;
    }

    /**
     * Act on the Fiji image clicked last, as Fiji's own commands do (item 6). A HoloBio output
     * or a linked hologram switches to its reconstruction. Any other image is linked as a new
     * hologram when {@code linkNew} is true (Compensate, Phase Shifting, Numerical Propagation).
     */
    private void activateFromFiji(boolean linkNew) {
        ImagePlus a = WindowManager.getCurrentImage();
        if (a == null) {
            return;
        }
        Recon r = reconOfTitle(a.getTitle());
        if (r == null) {
            r = reconOfSource(a);
        }
        if (r != null) {
            switchToRecon(r);
            return;
        }
        if (linkNew && !isHoloBioWindow(a) && a != linkedImage) {
            loadHologramFromImagePlus(a, "Active Hologram");
        }
    }

    /** Plots, figures and pick windows HoloBio made; they are never linked as holograms. */
    private static boolean isHoloBioWindow(ImagePlus imp) {
        if (imp.getWindow() instanceof ij.gui.PlotWindow) {
            return true;
        }
        String t = imp.getTitle();
        return t == null || t.startsWith("HoloBio") || t.startsWith("Speckle") || t.startsWith("Line Profile")
            || t.startsWith("Phase profiles") || t.startsWith("AIA ") || t.contains("Focus Curve");
    }

    /**
     * Phase window QPI measures: the Phase of the reconstruction clicked last (its Amplitude or
     * hologram counts too). Any other image is measured as it is.
     */
    private ImagePlus phaseImageForActive() {
        ImagePlus a = WindowManager.getCurrentImage();
        Recon r = a != null ? reconOfTitle(a.getTitle()) : null;
        if (r == null && a != null) {
            r = reconOfSource(a);
        }
        if (r == null && (a == null || isHoloBioWindow(a))) {
            r = cur;
        }
        if (a != null && r != null && a.getTitle().endsWith("Phase (Z-scan)")) {
            return a;
        }
        if (r != null) {
            ImagePlus p = WindowManager.getImage(r.base + " – Phase");
            if (p != null && p.getWindow() != null) {
                return p;
            }
            return a != null && r == reconOfTitle(a.getTitle()) ? a : null;
        }
        return a;
    }

    /** Image title without its file extension ("holoStar.tif" -> "holoStar"). */
    static String baseNameOf(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "Hologram";
        }
        String t = title.trim();
        int dot = t.lastIndexOf('.');
        if (dot > 0 && t.length() - dot <= 6 && t.substring(dot + 1).matches("[A-Za-z0-9]+")) {
            t = t.substring(0, dot);
        }
        return t;
    }

    /** Title of an output window: "<hologram> – Amplitude", "<hologram> – Phase", ... (fix 1). */
    private String outputTitle(String role) {
        return holoBaseName + " \u2013 " + role;
    }

    private void linkTo(ImagePlus imp) {
        linkedImage = imp;
        if (linkedImageListener == null) {
            linkedImageListener = new ImageListener() {
                @Override
                public void imageOpened(ImagePlus i) {
                }

                @Override
                public void imageUpdated(ImagePlus i) {
                }

                @Override
                public void imageClosed(ImagePlus i) {
                    if (i != null) {
                        SwingUtilities.invokeLater(() -> onImageClosed(i));
                    }
                }
            };
            ImagePlus.addImageListener(linkedImageListener);
        }
    }

    /**
     * A closed hologram leaves its reconstruction usable: its first plane was copied when it was
     * linked, and its outputs stay open (item 6). A record is released only when neither its
     * hologram nor any of its output windows is open any more.
     */
    private void onImageClosed(ImagePlus closed) {
        saveCurrentRecon();
        for (Recon r : new ArrayList<>(recons.values())) {
            if (r.source == closed) {
                r.source = null;
                if (r == cur) {
                    linkedImage = null;
                    activeSourceImage = null;
                }
            }
        }
        for (Recon r : new ArrayList<>(recons.values())) {
            if (r.source == null && !hasOpenOutputs(r.base, closed)) {
                recons.remove(r.base);
                if (r == cur) {
                    cur = null;
                    clearAllState();
                    if (!recons.isEmpty()) {
                        Recon last = null;
                        for (Recon x : recons.values()) {
                            last = x;
                        }
                        switchToRecon(last);
                    }
                }
            }
        }
    }

    private static boolean hasOpenOutputs(String base, ImagePlus closing) {
        int[] ids = WindowManager.getIDList();
        if (ids == null) {
            return false;
        }
        String prefix = base + " – ";
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp != null && imp != closing && imp.getTitle() != null && imp.getTitle().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Drop the hologram and everything computed from it. */
    private void clearAllState() {
        state.setHologramImage(null);
        state.setFourierImage(null);
        state.setAmplitudeImage(null);
        state.setPhaseImage(null);
        activeSourceImage = null;
        linkedImage = null;
        currentFieldRe = null;
        currentFieldIm = null;
        shownFieldRe = null;
        shownFieldIm = null;
        currentFieldW = 0;
        currentFieldH = 0;
        lastMask = null;
        lastMaskGrid = null;
        srcWidth = 0;
        srcHeight = 0;
        scaleMag = Double.NaN;
        speckleState = new HoloBioSpeckleState();
        holoBaseName = "Hologram";
    }

    /** Compensation needs a hologram; one that was closed still counts (its first plane was kept). */
    private boolean requireOpenHologram() {
        if (!state.hasHologram()) {
            HoloBioFijiUi.message("HoloBio", "No hologram is linked. Click a hologram in Fiji (or use Use Active Image).");
            return false;
        }
        return true;
    }
    private void onApplyMvp() {
        debugLog("Apply clicked.");
        debugLogSelectionSnapshot("Apply snapshot");
        activateFromFiji(!(isModulePhaseShift() && SRC_FOLDER.equals(psSourceBox.getSelectedItem())));
        if (!_update_physical_params()) {
            return;
        }

        if (isModuleNumericalProp()) {
            debugLog("Module: Numerical Propagation.");
            ensureCurrentFieldFromHologram(true);
            _apply_propagation(propOptionsNp, selectedNumericalPropagationMethod());
        } else if (isModulePhaseShift()) {
            debugLog("Module: Phase Shifting.");
            run_phase_shifting_method();
        } else {
            // Phase Compensation: Apply propagates the compensated field (Python PP style). If there is
            // no field yet (nothing compensated, or a new hologram was just linked), compensate first
            // instead of failing with "No reconstructed field" (fix 3). With no distance entered,
            // Apply is just compensation; it no longer propagates a hidden default distance.
            if (currentFieldRe == null || currentFieldIm == null) {
                run_phase_compensation();
                if (currentFieldRe == null) {
                    return;
                }
            }
            if (propOptionsPc != null && "Fixed".equals(propOptionsPc.mode())
                    && Double.isNaN(propOptionsPc.effectiveZFixedUm())) {
                return;
            }
            _apply_propagation(propOptionsPc, "Angular Spectrum");
        }
    }

    private void onReset() {
        if (!HoloBioUiStyle.confirm(null, "Reset DHM",
                "Clear the linked holograms and all reconstructed outputs?")) {
            return;
        }
        recons.clear();
        cur = null;
        clearAllState();
    }
    /** Python-like save menu for FT / phase / amplitude outputs. */
    private void onSave() {
        debugLog("Save clicked.");
        String[] options = {"Save FT", "Save Phase", "Save Amplitude", "Save Complex Field (.npy)"};
        String choice = (String) JOptionPane.showInputDialog(
            null,
            "Choose what to save",
            "HoloBio Save",
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]
        );
        if (choice == null) {
            debugLog("Save canceled.");
            return;
        }
        debugLog("Save option selected: " + choice);

        switch (choice) {
            case "Save FT":
                saveFtImage();
                break;
            case "Save Phase":
                saveStateImage(state.getPhaseImage(), "Phase", "No phase image to save. Run compensation/shifting first.");
                break;
            case "Save Amplitude":
                saveStateImage(state.getAmplitudeImage(), "Amplitude", "No amplitude image to save. Run compensation/shifting first.");
                break;
            case "Save Complex Field (.npy)":
                saveComplexField();
                break;
            default:
                break;
        }
    }

    /** Save the current complex field as complex64 .npy, at the dimensions of the reconstruction. */
    private void saveComplexField() {
        if (currentFieldRe == null || currentFieldIm == null || currentFieldW <= 0 || currentFieldH <= 0) {
            HoloBioNpy.saveComplexDialog(holoBaseName + "_complex_field", null);
            return;
        }
        HoloBioNpy.saveComplexDialog(holoBaseName + "_complex_field", currentFieldRe, currentFieldIm,
            currentFieldW, currentFieldH);
    }
    private void openAllOutputsInFiji() {
        debugLog("Open All in Fiji clicked.");
        int opened = 0;
        opened += openOutputImageInFiji(state.getAmplitudeImage(), "Amplitude") ? 1 : 0;
        opened += openOutputImageInFiji(state.getPhaseImage(), "Phase") ? 1 : 0;
        opened += openOutputImageInFiji(state.getFourierImage(), "FT") ? 1 : 0;
        if (opened == 0) {
            HoloBioFijiUi.message("HoloBio", "No outputs available yet. Run compensation/shifting/propagation first.");
            debugLog("Open All in Fiji result: no outputs.");
        } else {
            IJ.showStatus("HoloBio: Opened " + opened + " output image(s) as Fiji windows.");
            debugLog("Open All in Fiji result: opened=" + opened);
        }
    }

    /**
     * Show {@code image} as "<hologram> – role". An open window is reused, also when the size
     * changes (its content is swapped in place): destroying and creating native windows is what
     * crashes the Intel graphics driver (item 20). Amplitude and phase carry the µm scale, and
     * keep the ROIs drawn on them (QPI, speckle zones) while their size stays the same.
     */
    private boolean openOutputImageInFiji(ImagePlus image, String role) {
        if (image == null) {
            return false;
        }
        String title = outputTitle(role);
        boolean scaled = "Amplitude".equals(role) || "Phase".equals(role);
        ImagePlus existing = WindowManager.getImage(title);
        if (existing != null && existing.getWindow() != null) {
            boolean sameSize = existing.getStackSize() == 1
                && existing.getWidth() == image.getWidth() && existing.getHeight() == image.getHeight();
            Overlay keep = image.getOverlay() != null ? image.getOverlay().duplicate()
                : (sameSize && scaled ? existing.getOverlay() : null);
            if (sameSize) {
                existing.setProcessor(image.getProcessor().duplicate());
            } else {
                ImagePlus fresh = new ImagePlus(title, image.getProcessor().duplicate());
                existing.setImage(fresh);
                existing.setTitle(title);
            }
            if (scaled) {
                applyOutputScale(existing);
            }
            existing.setOverlay(keep);
            existing.resetDisplayRange();
            existing.updateAndDraw();
            existing.changes = false;
            return true;
        }
        ImagePlus out = new ImagePlus(title, image.getProcessor().duplicate());
        if (image.getOverlay() != null) {
            out.setOverlay(image.getOverlay().duplicate());
        }
        if (scaled) {
            applyOutputScale(out);
        }
        HoloBioFijiUi.showImagePlus(out);
        return true;
    }
    private void saveFtImage() {
        ImagePlus ft = state.getFourierImage();
        if (ft == null && state.hasHologram() && previewMask()) {
            ft = buildSpatialFilterImage();
            state.setFourierImage(ft);
        }
        saveStateImage(ft, holoBaseName + "_spatial_filter", "No spectrum to save. Use an active image first.");
    }
    private void saveStateImage(ImagePlus image, String defaultName, String emptyMessage) {
        if (image == null) {
            HoloBioFijiUi.message("HoloBio", emptyMessage);
            return;
        }
        SaveDialog sd = new SaveDialog("Save " + defaultName, defaultName, ".tif");
        String dir = sd.getDirectory();
        String file = sd.getFileName();
        if (dir == null || file == null) {
            return;
        }

        ImagePlus out = image.duplicate();
        out.deleteRoi();
        String path = dir + file;
        boolean ok = new FileSaver(out).saveAsTiff(path);
        if (ok) {
            IJ.showStatus("HoloBio: Saved " + defaultName + " -> " + path);
        } else {
            HoloBioFijiUi.message("HoloBio", "Failed to save image: " + path);
        }
    }

    private boolean readParamsFromPhaseCompPanel() {
        try {
            params.setWavelengthUm(Double.parseDouble(tfPcLambda.getText().trim()));
            params.setPixelPitchXUm(Double.parseDouble(tfPcPitchX.getText().trim()));
            params.setPixelPitchYUm(Double.parseDouble(tfPcPitchY.getText().trim()));
            if (!validatePositivePhysicalParams(false)) {
                HoloBioFijiUi.message("HoloBio", "Wavelength, Pitch X and Pitch Y must be greater than zero.");
                return false;
            }
            return confirmSensorPitchForCarrier();
        } catch (NumberFormatException ex) {
            HoloBioFijiUi.message("HoloBio", "Invalid numeric parameter (phase compensation).");
            return false;
        }
    }

    /**
     * Carrier compensation uses sensor-plane pitch. If pitch &lt; λ/2, even Nyquist spatial
     * frequency makes |sin θ| &gt; 1 and the reference wave clips (noisy amp / black phase patches).
     * That usually means object-plane pitch (sensor/M) was entered by mistake.
     */
    private boolean confirmSensorPitchForCarrier() {
        double lam = params.getWavelengthUm();
        double dx = params.getPixelPitchXUm();
        double dy = params.getPixelPitchYUm();
        double maxSinX = lam / (2.0 * dx);
        double maxSinY = lam / (2.0 * dy);
        if (maxSinX <= 1.0 && maxSinY <= 1.0) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(
                IJ.getInstance(),
                String.format(
                        "<html>Pixel pitch looks too small for carrier compensation "
                                + "(λ/(2·pitch) = %.2f / %.2f &gt; 1).<br><br>"
                                + "Use the <b>camera sensor</b> pitch (e.g. 3.75 µm), not sensor/M "
                                + "(e.g. not 0.09375).<br>"
                                + "Set magnification M under Propagation for µm/px profiles.<br><br>"
                                + "Continue anyway?</html>",
                        maxSinX, maxSinY),
                "HoloBio — check sensor pitch",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private boolean readParamsFromPhaseShiftPanel() {
        try {
            params.setWavelengthUm(Double.parseDouble(tfPsLambda.getText().trim()));
            params.setPixelPitchXUm(Double.parseDouble(tfPsPitchX.getText().trim()));
            params.setPixelPitchYUm(Double.parseDouble(tfPsPitchY.getText().trim()));
            if (!validatePositivePhysicalParams(false)) {
                HoloBioFijiUi.message("HoloBio", "Wavelength, Pitch X and Pitch Y must be greater than zero.");
                return false;
            }
            return true;
        } catch (NumberFormatException ex) {
            HoloBioFijiUi.message("HoloBio", "Invalid numeric parameter (phase shifting).");
            return false;
        }
    }

    private boolean readParamsFromNumericalPanel() {
        try {
            params.setWavelengthUm(Double.parseDouble(tfNpLambda.getText().trim()));
            params.setPixelPitchXUm(Double.parseDouble(tfNpPitchX.getText().trim()));
            params.setPixelPitchYUm(Double.parseDouble(tfNpPitchY.getText().trim()));
            if (!validatePositivePhysicalParams(false)) {
                HoloBioFijiUi.message("HoloBio", "Wavelength, Pitch X and Pitch Y must be greater than zero.");
                return false;
            }
            return true;
        } catch (NumberFormatException ex) {
            HoloBioFijiUi.message("HoloBio", "Invalid numeric parameter (numerical propagation).");
            return false;
        }
    }

    private boolean validatePositivePhysicalParams(boolean includeDistanceZ) {
        if (params.getWavelengthUm() <= 0.0 || params.getPixelPitchXUm() <= 0.0 || params.getPixelPitchYUm() <= 0.0) {
            return false;
        }
        return !includeDistanceZ || params.getDistanceZUm() > 0.0;
    }

    private void open_compensation_settings() {
        Frame owner = IJ.getInstance();
        JDialog dlg = new JDialog(owner, "Compensation Method Settings", true);
        dlg.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_2));

        JTabbedPane tabs = new JTabbedPane();
        JTextField tfErsS = new JTextField(String.valueOf(compSettings.getErsSearchSize()), FIELD_COLS);
        JTextField tfErsStep = new JTextField(String.valueOf(compSettings.getErsStep()), FIELD_COLS);
        HoloBioUiStyle.styleField(tfErsS);
        HoloBioUiStyle.styleField(tfErsStep);
        tfErsS.setToolTipText("Odd integer search window, e.g. 5 or 7.");
        tfErsStep.setToolTipText("Step size strictly between 0 and 1, e.g. 0.2.");
        JPanel tabSemi = new JPanel(new GridLayout(2, 2, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_2));
        tabSemi.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3));
        tabSemi.add(HoloBioUiStyle.fieldLabel("Size search (odd int)"));
        tabSemi.add(tfErsS);
        tabSemi.add(HoloBioUiStyle.fieldLabel("Step (0–1)"));
        tabSemi.add(tfErsStep);
        tabs.addTab("Semi-Heuristic", tabSemi);

        JTextField tfCfsStep = new JTextField(String.valueOf(compSettings.getCfsStepWindow()), FIELD_COLS);
        JTextField tfCfsGrid = new JTextField(String.valueOf(compSettings.getCfsGridSize()), FIELD_COLS);
        JPanel tabTu = new JPanel();
        tabTu.setLayout(new BoxLayout(tabTu, BoxLayout.Y_AXIS));
        tabTu.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Step (FFT px half-width):"));
        row1.add(tfCfsStep);
        tabTu.add(row1);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(new JLabel("Grid size (≥5):"));
        row2.add(tfCfsGrid);
        tabTu.add(row2);
        tabTu.add(new JLabel("<html><p style='width:260px'>Fiji CFS uses a bounded grid search (not SciPy). "
            + "Optimizer name from HoloBio Python is stored for reference only.</p></html>"));
        String[] optim = {"TNC", "L-BFGS-B", "Powell", "Nelder-Mead", "COBYLA", "SLSQP", "trust-constr"};
        JComboBox<String> cbOpt = new JComboBox<>(optim);
        cbOpt.setSelectedItem(compSettings.getCfsOptimizerLabel());
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row3.add(new JLabel("Optimizer (label):"));
        row3.add(cbOpt);
        tabTu.add(row3);
        tabs.addTab("Tu-DHM", tabTu);

        JComboBox<String> cbVlLimit = new JComboBox<>(new String[] {"64", "128", "256", "512", "1024"});
        cbVlLimit.setSelectedItem(String.valueOf(compSettings.getVlLimit()));
        JCheckBox ckPiston = new JCheckBox("Piston compensation", compSettings.isVlPiston());
        JCheckBox ckPca = new JCheckBox("PCA (dominant mode + unwrap)", compSettings.isVlPca());
        JPanel tabVl = new JPanel();
        tabVl.setLayout(new BoxLayout(tabVl, BoxLayout.Y_AXIS));
        tabVl.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_3));
        JPanel vlRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        vlRow.add(new JLabel("Limit:"));
        vlRow.add(cbVlLimit);
        tabVl.add(vlRow);
        tabVl.add(ckPiston);
        tabVl.add(ckPca);
        tabs.addTab("Vortex Legendre", tabVl);

        dlg.add(tabs, BorderLayout.CENTER);

        JButton ok = HoloBioUiStyle.primaryButton("Save");
        JButton cancel = HoloBioUiStyle.secondaryButton("Cancel");
        dlg.add(HoloBioUiStyle.buildFooter("Saved values apply to the next Compensate.", cancel, ok), BorderLayout.SOUTH);

        ok.addActionListener(e -> {
            try {
                int s = Integer.parseInt(tfErsS.getText().trim().replace(',', '.'));
                if (s <= 0 || (s % 2 == 0)) {
                    JOptionPane.showMessageDialog(dlg, "Size search must be a positive odd integer.", "HoloBio", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                double st = Double.parseDouble(tfErsStep.getText().trim().replace(',', '.'));
                if (!(st > 0 && st < 1) || Double.isNaN(st) || Double.isInfinite(st)) {
                    JOptionPane.showMessageDialog(dlg, "ERS step must be strictly between 0 and 1.", "HoloBio", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                double cfsW = Double.parseDouble(tfCfsStep.getText().trim().replace(',', '.'));
                if (!(cfsW > 0) || Double.isNaN(cfsW) || Double.isInfinite(cfsW)) {
                    JOptionPane.showMessageDialog(dlg, "Tu-DHM step must be a positive finite number.", "HoloBio", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                int grid = Integer.parseInt(tfCfsGrid.getText().trim());
                if (grid < 5) {
                    JOptionPane.showMessageDialog(dlg, "Grid size must be at least 5.", "HoloBio", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                int vlLim = Integer.parseInt(((String) cbVlLimit.getSelectedItem()).trim());

                compSettings.setErsSearchSize(s);
                compSettings.setErsStep(st);
                compSettings.setCfsStepWindow(cfsW);
                compSettings.setCfsGridSize(grid);
                compSettings.setCfsOptimizerLabel((String) cbOpt.getSelectedItem());
                compSettings.setVlLimit(vlLim);
                compSettings.setVlPiston(ckPiston.isSelected());
                compSettings.setVlPca(ckPca.isSelected());
                dlg.dispose();
                IJ.showStatus("HoloBio: Compensation settings saved.");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Invalid numeric input.", "HoloBio", JOptionPane.WARNING_MESSAGE);
            }
        });
        cancel.addActionListener(e -> dlg.dispose());

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    // ---------------- Phase shifting controls ----------------
    private void update_shifting_params() {
        String method = (String) phaseShiftMethodBox.getSelectedItem();
        debugLog("Phase shifting method selected: " + method);
        IJ.showStatus("HoloBio: Phase shifting method -> " + method);
        syncPhaseShiftRows();
    }

    /** Show only the rows that apply to the current method / source / reference choices. */
    private void syncPhaseShiftRows() {
        if (aiaOptions == null || psFolderRows == null) {
            return;
        }
        psFolderRows.setVisible(SRC_FOLDER.equals(psSourceBox.getSelectedItem()));
        aiaOptions.setVisible(AIA_METHOD.equals(phaseShiftMethodBox.getSelectedItem()));
        boolean ref = cbAiaReference.isSelected();
        boolean refFolder = SRC_FOLDER.equals(aiaRefSourceBox.getSelectedItem());
        aiaRefRows.setVisible(ref);
        aiaRefStackRow.setVisible(!refFolder);
        aiaRefFolderRows.setVisible(refFolder);
        if (ref && !refFolder) {
            refreshReferenceStacks();
        }
        aiaOptions.revalidate();
        psFolderRows.revalidate();
        java.awt.Window w = SwingUtilities.getWindowAncestor(aiaOptions);
        if (w != null) {
            packWindow(w);
        }
    }

    /** Fill the reference-stack dropdown with the open images, keeping the current pick. */
    private void refreshReferenceStacks() {
        Object current = aiaRefStackBox.getSelectedItem();
        aiaRefStackBox.removeAllItems();
        String[] titles = WindowManager.getImageTitles();
        for (String t : titles) {
            aiaRefStackBox.addItem(t);
        }
        if (current != null) {
            aiaRefStackBox.setSelectedItem(current);
        }
    }

    /** "Folder:" field + Browse… button, and "Filename contains:" field. */
    private JPanel folderRows(JTextField folderField, JTextField filterField) {
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        setFixedControlSize(folderField, FORM_COMBO_W);
        HoloBioUiStyle.styleField(folderField);
        folderField.setToolTipText("Folder with the frames (images are sorted by name).");
        JPanel folderRow = compactRow("Folder:", folderField);
        JButton browse = HoloBioUiStyle.compactButton("Browse…");
        browse.addActionListener(e -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser(folderField.getText().trim());
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(SwingUtilities.getWindowAncestor(browse))
                    == javax.swing.JFileChooser.APPROVE_OPTION) {
                folderField.setText(fc.getSelectedFile().getPath());
                folderField.setToolTipText(fc.getSelectedFile().getPath());
            }
        });
        folderRow.add(browse);
        rows.add(folderRow);
        setFixedControlSize(filterField, FORM_COMBO_W);
        HoloBioUiStyle.styleField(filterField);
        filterField.setToolTipText("Only files whose name contains this text, e.g. _2_UP. Empty = all images.");
        rows.add(compactRow("Filename contains:", filterField));
        JPanel hintRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 0);
        hintRow.add(Box.createHorizontalStrut(FORM_LABEL_W));
        hintRow.add(HoloBioUiStyle.footerNote("⚠ AIA uses every matching image in the folder."));
        hintRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H));
        hintRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.add(hintRow);
        return rows;
    }

    /** Custom guess field is only editable when Initial guess = Custom. */
    private void syncAiaGuessField() {
        tfAiaCustomGuess.setEnabled(AIA_GUESS_CUSTOM.equals(aiaGuessBox.getSelectedItem()));
    }

    // ---------------- Shared execution entrypoints ----------------
    /**
     * Phase compensation on the linked hologram. ERS and Tu-DHM run on the hologram at its own
     * size (no power-of-two zero padding, as HoloBio Python), so the reconstruction has the
     * hologram's dimensions and no zero-filled border. Vortex-Legendre reconstructs the centred
     * square it processes, so its outputs are min(W,H) × min(W,H) (fix 6).
     */
    private void run_phase_compensation() {
        if (!requireOpenHologram()) {
            return;
        }
        if (!readParamsFromPhaseCompPanel()) {
            return;
        }
        FloatProcessor src = state.getHologramImage().getProcessor().convertToFloatProcessor();
        int w = src.getWidth();
        int h = src.getHeight();
        srcWidth = w;
        srcHeight = h;
        padOffsetX = 0;
        padOffsetY = 0;
        float[] px = (float[]) src.getPixels();
        HoloBioCompensationAlgorithms.MaskSpec mask = readMaskSpec(w, h);
        if (mask == null) {
            return;
        }
        double lam = params.getWavelengthUm();
        double dx = params.getPixelPitchXUm();
        double dy = params.getPixelPitchYUm();
        boolean vl = rbPcMethodVl != null && rbPcMethodVl.isSelected();
        boolean tu = rbPcMethodTu != null && rbPcMethodTu.isSelected();

        HoloBioCompensationAlgorithms.CompensationResult result;
        try {
            if (vl) {
                // Python vortexLegendre uses arr_hologram as-is (no mean subtraction).
                result = HoloBioCompensationAlgorithms.vortexLegendre(floatRegionToDouble(px), w, h, lam, dx, dy,
                    compSettings.getVlLimit(), compSettings.isVlPiston(), compSettings.isVlPca(), mask);
            } else {
                double[] inp = meanRemovedDouble(px, px.length);
                HoloBioCompensationAlgorithms.RoiRect roiRect =
                    HoloBioCompensationAlgorithms.autoFirstOrderSpectrumRoi(inp, w, h);
                result = tu
                    ? HoloBioCompensationAlgorithms.cfs(inp, w, h, lam, dx, dy, roiRect,
                        compSettings.getCfsStepWindow(), compSettings.getCfsGridSize(), mask)
                    : HoloBioCompensationAlgorithms.ers(inp, w, h, lam, dx, dy, roiRect,
                        compSettings.getErsSearchSize(), compSettings.getErsStep(), mask);
            }
        } catch (RuntimeException ex) {
            HoloBioFijiUi.message("HoloBio", "Phase compensation failed: " + ex.getMessage());
            IJ.handleException(ex);
            return;
        }

        HoloBioCompensationAlgorithms.ComplexField f = result.field;
        int flen = f.width * f.height;
        float[] re = new float[flen];
        float[] im = new float[flen];
        for (int i = 0; i < flen; i++) {
            re[i] = (float) f.re[i];
            im[i] = (float) f.im[i];
        }
        setCurrentField(re, im, f.width, f.height);
        renderCurrentFieldWithoutPropagation();
        lastMask = result.mask;
        lastMaskGrid = vl ? squareCropLikeVortexLegendre(src) : src;
        state.setFourierImage(buildSpatialFilterImage());
        copyParamsToNumericalPanel();
        recordReconParams();
        update_right_view();
        refreshOpenSpatialFilterWindow();
        IJ.showStatus(String.format("HoloBio: phase compensation completed (%dx%d, fx=%.1f, fy=%.1f).",
            f.width, f.height, result.fx, result.fy));
    }

    /** The centred min(W,H) square that Vortex-Legendre processes (HoloBioVortexLegendre.cropToSquare). */
    private static FloatProcessor squareCropLikeVortexLegendre(FloatProcessor src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w == h) {
            return src;
        }
        int side = Math.min(w, h);
        int x0 = w > h ? (w - h) / 2 : 0;
        int y0 = h > w ? (h - w) / 2 : 0;
        src.setRoi(x0, y0, side, side);
        FloatProcessor out = (FloatProcessor) src.crop();
        src.resetRoi();
        return out;
    }

    /** Fix 2: the parameters that produced the current field are carried into Numerical Propagation. */
    private void copyParamsToNumericalPanel() {
        if (tfNpLambda == null) {
            return;
        }
        tfNpLambda.setText(fmtParam(params.getWavelengthUm()));
        tfNpPitchX.setText(fmtParam(params.getPixelPitchXUm()));
        tfNpPitchY.setText(fmtParam(params.getPixelPitchYUm()));
    }

    private static String fmtParam(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** A distance in µm written in the largest unit that keeps it ≥ 1 (µm, mm, cm or m). */
    static String formatUmAuto(double um) {
        double a = Math.abs(um);
        if (a >= 1e6) {
            return String.format(java.util.Locale.US, "%.4g m", um / 1e6);
        }
        if (a >= 1e4) {
            return String.format(java.util.Locale.US, "%.4g cm", um / 1e4);
        }
        if (a >= 1e3) {
            return String.format(java.util.Locale.US, "%.4g mm", um / 1e3);
        }
        return String.format(java.util.Locale.US, "%.4g µm", um);
    }

    /**
     * Fix 5/10: log spectrum of the grid the compensation processes (the hologram, or for
     * Vortex-Legendre its square crop), at that grid's own size, with the mask outlined.
     */
    private ImagePlus buildSpatialFilterImage() {
        if (lastMask == null || lastMaskGrid == null) {
            return null;
        }
        FloatProcessor g = (FloatProcessor) lastMaskGrid.duplicate();
        float[] gp = (float[]) g.getPixels();
        subtractMeanInPlace(gp, gp.length);
        ByteProcessor bp = HoloBioFftDisplay.generateFftLogDisplayRect(g, g.getWidth(), g.getHeight());
        double rx = lastMask.maskRx;
        double ry = lastMask.maskRy;
        if (cbFilterView != null && "Filtered".equals(cbFilterView.getSelectedItem())) {
            // What the mask keeps (Real-Time DHM "Filtered" view): everything outside it is black.
            int gw = bp.getWidth();
            int gh = bp.getHeight();
            for (int y = 0; y < gh; y++) {
                double dy = y - lastMask.fy;
                for (int x = 0; x < gw; x++) {
                    double dx = x - lastMask.fx;
                    boolean keep = lastMask.maskCircular
                        ? (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) <= 1.0
                        : Math.abs(dx) <= rx && Math.abs(dy) <= ry;
                    if (!keep) {
                        bp.set(x, y, 0);
                    }
                }
            }
        }
        ImagePlus imp = new ImagePlus("Spatial filter", bp);
        Roi outline = lastMask.maskCircular
            ? new OvalRoi(lastMask.fx - rx, lastMask.fy - ry, 2 * rx, 2 * ry)
            : new Roi(lastMask.fx - rx, lastMask.fy - ry, 2 * rx, 2 * ry);
        outline.setStrokeColor(Color.YELLOW);
        outline.setStrokeWidth(2);
        imp.setOverlay(new Overlay(outline));
        return imp;
    }

    /** Mask for the current settings on the grid the selected method processes (no compensation). */
    private boolean previewMask() {
        if (!state.hasHologram()) {
            return false;
        }
        FloatProcessor src = state.getHologramImage().getProcessor().convertToFloatProcessor();
        HoloBioCompensationAlgorithms.MaskSpec spec = readMaskSpec(src.getWidth(), src.getHeight());
        if (spec == null) {
            return false;
        }
        boolean vl = rbPcMethodVl != null && rbPcMethodVl.isSelected();
        FloatProcessor grid = vl ? squareCropLikeVortexLegendre(src) : src;
        int gw = grid.getWidth();
        int gh = grid.getHeight();
        float[] gp = (float[]) grid.getPixels();
        if (vl && spec.isPythonAutoCircle()) {
            lastMask = HoloBioCompensationAlgorithms.spatialFilteringCf(floatRegionToDouble(gp), gw, gh);
        } else {
            double[] mr = meanRemovedDouble(gp, gp.length);
            HoloBioCompensationAlgorithms.RoiRect roi = vl
                ? new HoloBioCompensationAlgorithms.RoiRect(0, 0, gw, gh / 2)
                : HoloBioCompensationAlgorithms.autoFirstOrderSpectrumRoi(mr, gw, gh);
            lastMask = HoloBioCompensationAlgorithms.filterOrder(mr, gw, gh, roi, spec, true);
        }
        lastMaskGrid = grid;
        return true;
    }

    /** "Spatial Filter Visualization" (fix 5): the mask the current settings use, at the processed size. */
    private void showSpatialFilterVisualization() {
        activateFromFiji(true);
        if (!state.hasHologram()) {
            HoloBioFijiUi.message("HoloBio", "Link a hologram first (click it in Fiji, or Use Active Image).");
            return;
        }
        if (!previewMask()) {
            return;
        }
        ImagePlus img = buildSpatialFilterImage();
        state.setFourierImage(img);
        openOutputImageInFiji(img, "Spatial filter");
    }

    /** Redraw an open spatial filter window after a setting changed (no new window). */
    private void refreshOpenSpatialFilterWindow() {
        ImagePlus open = WindowManager.getImage(outputTitle("Spatial filter"));
        if (open == null || open.getWindow() == null || !state.hasHologram()) {
            return;
        }
        String pickedX = tfMaskCx != null ? tfMaskCx.getText().trim() : "";
        if (ORDER_PICKED.equals(cbOrderMode.getSelectedItem()) && pickedX.isEmpty()) {
            return;     // nothing to show until a centre is picked
        }
        if (previewMask()) {
            ImagePlus img = buildSpatialFilterImage();
            state.setFourierImage(img);
            openOutputImageInFiji(img, "Spatial filter");
        }
    }

    /** Remove global mean so FFT matches {@code pyDHM_methods.ERS} / {@link HoloBioCompensationAlgorithms} input. */
    private static void subtractMeanInPlace(float[] px, int len) {
        double mean = 0.0;
        for (int i = 0; i < len; i++) {
            mean += px[i];
        }
        mean /= len;
        float m = (float) mean;
        for (int i = 0; i < len; i++) {
            px[i] -= m;
        }
    }

    private static double[] meanRemovedDouble(float[] src, int len) {
        double mean = 0.0;
        for (int i = 0; i < len; i++) {
            mean += src[i];
        }
        mean /= len;
        double[] out = new double[len];
        for (int i = 0; i < len; i++) {
            out[i] = src[i] - mean;
        }
        return out;
    }

    private void run_phase_shifting_method() {
        debugLog("Phase-shifting run clicked.");
        String method = (String) phaseShiftMethodBox.getSelectedItem();
        if (!readParamsFromPhaseShiftPanel()) {
            return;
        }

        int requiredFrames;
        switch (method) {
            case "BPS3":
                requiredFrames = 3;
                break;
            case "BPS2":
                requiredFrames = 2;
                break;
            default:
                HoloBioFijiUi.message("HoloBio", "Unsupported phase shifting method (use BPS3, BPS2 or AIA).");
                return;
        }

        List<FloatProcessor> frames = collectPhaseShiftFrames(requiredFrames);
        if (frames == null) {
            return;
        }

        HoloBioPhaseShifting.Result ps = HoloBioPhaseShifting.run(
            method,
            frames,
            params.getWavelengthUm(),
            params.getPixelPitchXUm(),
            params.getPixelPitchYUm()
        );
        if (ps == null) {
            return;
        }
        applyPhaseShiftResult(ps, method);
    }

    private void applyPhaseShiftResult(HoloBioPhaseShifting.Result ps, String method) {
        setCurrentField(ps.re, ps.im, ps.width, ps.height);
        srcWidth = ps.width;
        srcHeight = ps.height;
        copyParamsToNumericalPanel();
        recordReconParams();
        if (cbPsAutoPropagate != null && cbPsAutoPropagate.isSelected()) {
            _propagate_current_field(propOptionsPs, "Angular Spectrum");
        } else {
            renderCurrentFieldWithoutPropagation();
            debugLogOutputs("After phase shifting");
        }
        update_right_view();
        IJ.showStatus("HoloBio: Phase shifting run completed (" + method + ").");
    }

    private boolean isAiaSelected() {
        return isModulePhaseShift() && AIA_METHOD.equals(phaseShiftMethodBox.getSelectedItem());
    }

    /** AIA settings read from the panel on the UI thread, so the run itself can go to the background. */
    private static final class AiaJob {
        boolean folder;
        String dir;
        String filter;
        ImagePlus stack;
        int maxIter;
        Object guess;
        String customGuess;
        boolean useRef;
        boolean refFolder;
        String refDir;
        String refFilter;
        ImagePlus refStack;
    }

    /** What the background run hands back: the field, plus the shifts for the plot. */
    private static final class AiaOutput {
        HoloBioPhaseShifting.Result field;
        double[] objectShifts;
        double[] referenceShifts;   // null when no reference
        double[] correctedShifts;
        List<String> names;         // file names (folder source), else null
    }

    /** Apply in AIA mode: runs in the background so the progress bar updates. */
    private void startAiaRun() {
        activateFromFiji(!SRC_FOLDER.equals(psSourceBox.getSelectedItem()));
        if (!readParamsFromPhaseShiftPanel()) {
            return;
        }
        AiaJob job = readAiaJob();
        if (job == null) {
            return;
        }
        final AiaOutput[] out = new AiaOutput[1];
        HoloBioUiStyle.runBusyInBackground(btnApply, "Working…", () -> out[0] = computeAia(job), () -> {
            if (out[0] == null) {
                return;
            }
            applyPhaseShiftResult(out[0].field, AIA_METHOD);
            showAiaShiftPlot(out[0]);
        });
    }

    private AiaJob readAiaJob() {
        AiaJob job = new AiaJob();
        try {
            job.maxIter = Integer.parseInt(tfAiaMaxIter.getText().trim());
        } catch (NumberFormatException ex) {
            job.maxIter = -1;
        }
        if (job.maxIter < 1) {
            HoloBioFijiUi.message("HoloBio", "Max iterations must be a whole number of at least 1.");
            return null;
        }
        job.guess = aiaGuessBox.getSelectedItem();
        job.customGuess = tfAiaCustomGuess.getText();
        job.folder = SRC_FOLDER.equals(psSourceBox.getSelectedItem());
        job.dir = tfPsFolder.getText();
        job.filter = tfPsFilter.getText();
        if (!job.folder) {
            if (!sourceStackOpen()) {
                return null;
            }
            job.stack = activeSourceImage;
        }
        job.useRef = cbAiaReference.isSelected();
        job.refFolder = SRC_FOLDER.equals(aiaRefSourceBox.getSelectedItem());
        job.refDir = tfAiaRefFolder.getText();
        job.refFilter = tfAiaRefFilter.getText();
        if (job.useRef && !job.refFolder) {
            Object title = aiaRefStackBox.getSelectedItem();
            job.refStack = title == null ? null : WindowManager.getImage(title.toString());
            if (job.refStack == null) {
                HoloBioFijiUi.message("HoloBio", "Pick an open reference stack, or set Reference source to Folder.");
                return null;
            }
        }
        return job;
    }

    /**
     * Background part of AIA: load and normalize the frames, iterate, subtract the reference
     * shifts (0 without reference), and build the object field modulation·exp(i·phase).
     * Null (after a message) on bad input.
     */
    private static AiaOutput computeAia(AiaJob job) {
        AiaOutput out = new AiaOutput();
        List<FloatProcessor> frames;
        if (job.folder) {
            out.names = new ArrayList<>();
            frames = folderFrames(job.dir, job.filter, 0, "Frames", out.names);
        } else {
            frames = stackFrames(job.stack, 0, "Frames");
        }
        if (frames == null) {
            return null;
        }
        int n = frames.size();
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();

        double[] initialShifts = null;   // null = PCA
        if (AIA_GUESS_RAMP.equals(job.guess)) {
            initialShifts = HoloBioAiaMath.rampGuess(n);
        } else if (AIA_GUESS_CUSTOM.equals(job.guess)) {
            try {
                initialShifts = HoloBioAiaMath.parseGuess(job.customGuess, n);
            } catch (IllegalArgumentException ex) {
                HoloBioFijiUi.message("HoloBio", ex.getMessage());
                return null;
            }
        }

        Jama.Matrix object = normalizedFringes(frames, "object");
        HoloBioAiaMath.Result objRes = aiaIterate(object, initialShifts, job.maxIter, "object");
        if (objRes == null) {
            return null;
        }
        out.objectShifts = objRes.phaseShifts;

        // No reference: shifts of 0, so the object shifts are used as they are.
        double[] refShifts = new double[n];
        if (job.useRef) {
            List<FloatProcessor> refFrames = job.refFolder
                ? folderFrames(job.refDir, job.refFilter, 0, "Reference", null)
                : stackFrames(job.refStack, 0, "Reference");
            if (refFrames == null) {
                return null;
            }
            if (refFrames.size() != n || refFrames.get(0).getWidth() != w || refFrames.get(0).getHeight() != h) {
                HoloBioFijiUi.message("HoloBio", "Reference must have the same number of frames (" + n
                    + ") and size (" + w + "x" + h + ") as the object; it has " + refFrames.size() + " frames of "
                    + refFrames.get(0).getWidth() + "x" + refFrames.get(0).getHeight() + ".");
                return null;
            }
            HoloBioAiaMath.Result refRes = aiaIterate(normalizedFringes(refFrames, "reference"),
                initialShifts, job.maxIter, "reference");
            if (refRes == null) {
                return null;
            }
            refShifts = refRes.phaseShifts;
            out.referenceShifts = refShifts;
        }
        double[] corrected = new double[n];
        for (int k = 0; k < n; k++) {
            corrected[k] = out.objectShifts[k] - refShifts[k];
        }
        out.correctedShifts = corrected;

        HoloBioAiaMath.Result res;
        try {
            res = HoloBioAiaMath.fromShifts(object, corrected);
        } catch (RuntimeException ex) {
            HoloBioFijiUi.message("HoloBio", "AIA: the shifts left after subtracting the reference are all the same,\n"
                + "so the phase cannot be computed. Check that the reference is not the object set itself.");
            return null;
        }
        float[] re = new float[w * h];
        float[] im = new float[w * h];
        for (int p = 0; p < re.length; p++) {
            re[p] = (float) (res.modulation[p] * Math.cos(res.phase[p]));
            im[p] = (float) (res.modulation[p] * Math.sin(res.phase[p]));
        }
        out.field = new HoloBioPhaseShifting.Result(re, im, w, h);
        return out;
    }

    /**
     * "AIA phase shifts" plot (as in the MATLAB script): unwrapped shift per frame for the object
     * (blue) and, with a reference, the reference (red) and the corrected object − reference (green).
     * x = gray level from the file names (GR_050_... → 50) when they all have a number, else frame number.
     */
    private static void showAiaShiftPlot(AiaOutput out) {
        int n = out.objectShifts.length;
        double[] x = out.names != null ? grayLevelsFromNames(out.names) : null;
        boolean byGray = x != null;
        if (!byGray) {
            x = new double[n];
            for (int k = 0; k < n; k++) {
                x[k] = k + 1;
            }
        }
        ij.gui.Plot plot = new ij.gui.Plot("AIA phase shifts", byGray ? "Gray level" : "Frame", "Phase shift (rad)");
        plot.setColor(Color.BLUE);
        plot.add("connected circle", x, unwrap(out.objectShifts));
        String legend = "Object";
        if (out.referenceShifts != null) {
            plot.setColor(Color.RED);
            plot.add("connected circle", x, unwrap(out.referenceShifts));
            plot.setColor(new Color(0, 160, 0));
            plot.add("connected circle", x, unwrap(out.correctedShifts));
            legend += "\nReference\nObject − reference";
        }
        plot.setColor(Color.BLACK);
        plot.addLegend(legend);
        plot.show();
    }

    /**
     * Gray level per file: the first number group in the names that strictly increases from
     * file to file (GR_000_2_UP, GR_005_2_UP, ... → 0, 5, ...). Null if no group does, e.g. when
     * every name shares the same numbers, so the plot falls back to frame number.
     */
    private static double[] grayLevelsFromNames(List<String> names) {
        java.util.regex.Pattern digits = java.util.regex.Pattern.compile("\\d+");
        List<List<Long>> groups = new ArrayList<>();
        int minGroups = Integer.MAX_VALUE;
        for (String name : names) {
            List<Long> g = new ArrayList<>();
            java.util.regex.Matcher m = digits.matcher(name);
            while (m.find()) {
                String d = m.group();
                g.add(d.length() > 18 ? Long.MAX_VALUE : Long.parseLong(d));
            }
            groups.add(g);
            minGroups = Math.min(minGroups, g.size());
        }
        for (int gi = 0; gi < minGroups; gi++) {
            double[] x = new double[names.size()];
            boolean increasing = true;
            for (int k = 0; k < names.size() && increasing; k++) {
                x[k] = groups.get(k).get(gi);
                increasing = k == 0 || x[k] > x[k - 1];
            }
            if (increasing) {
                return x;
            }
        }
        return null;
    }

    /** MATLAB unwrap (tolerance π). */
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

    /** IgramNorm on every frame, packed as the nPixels × nFrames AIA matrix. */
    private static Jama.Matrix normalizedFringes(List<FloatProcessor> frames, String what) {
        int n = frames.size();
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        double[][] normalized = new double[n][];
        for (int i = 0; i < n; i++) {
            IJ.showStatus("HoloBio AIA: normalizing " + what + " frame " + (i + 1) + "/" + n);
            IJ.showProgress(i, n);
            float[] px = (float[]) frames.get(i).getPixels();
            double[] d = new double[px.length];
            for (int p = 0; p < px.length; p++) {
                d[p] = px[p];
            }
            normalized[i] = HoloBioAiaMath.normalizeFringes(d, w, h, AIA_NORM_RADIUS);
        }
        return HoloBioAiaMath.fringesVector(normalized);
    }

    /** Runs the AIA iteration with status updates; null after a message if it fails. */
    private static HoloBioAiaMath.Result aiaIterate(Jama.Matrix fringes, double[] initialShifts,
                                                    int maxIter, String what) {
        HoloBioAiaMath.Result res;
        try {
            res = HoloBioAiaMath.run(fringes, initialShifts, maxIter, AIA_PRECISION, (it, max, err) -> {
                IJ.showStatus("HoloBio AIA (" + what + "): iteration " + it + "/" + max);
                IJ.showProgress(it, max);
            });
        } catch (RuntimeException ex) {
            HoloBioFijiUi.message("HoloBio", "AIA failed (" + what + "): " + ex.getMessage());
            return null;
        }
        IJ.showProgress(1.0);
        return res;
    }

    // ---------------- Propagation / params / output ----------------
    private void _apply_propagation(PropagationUi propUi, String method) {
        debugLog("Apply propagation: " + method + " | mode=" + (propUi != null ? propUi.mode() : "n/a"));
        if (propUi != null) {
            debugLog("Propagation params | lambda=" + params.getWavelengthUm()
                + "um dx=" + params.getPixelPitchXUm()
                + "um dy=" + params.getPixelPitchYUm()
                + "um | fixedZ=" + propUi.effectiveZFixedUm()
                + "um zScanStart=" + propUi.effectiveZScanStartUm()
                + "um af=[" + propUi.autofocusZMinUm() + "," + propUi.autofocusZMaxUm() + "]um");
        }
        _propagate_current_field(propUi, method);
        // Ensure Fiji output windows are refreshed after propagation from any module.
        update_right_view();
    }

    private void debugLog(String message) {}

    private void debugLogOutputs(String prefix) {}

    private void debugLogSelectionSnapshot(String prefix) {}

    /** Amplitude and phase of the current field, at the field's own size (fix 6). */
    private void renderCurrentFieldWithoutPropagation() {
        if (currentFieldRe == null || currentFieldIm == null || currentFieldW <= 0 || currentFieldH <= 0) {
            return;
        }
        setShownField(currentFieldRe, currentFieldIm);
    }

    /**
     * {@code re/im} (field size) becomes what the outputs show and what QPI and Speckle measure.
     * The speckle state restarts from it, so a filter is never applied to an outdated image.
     */
    private void setShownField(float[] re, float[] im) {
        shownFieldRe = re;
        shownFieldIm = im;
        speckleState = new HoloBioSpeckleState();
        speckleState.setOriginalField(re, im);
        renderShownField();
    }

    private void renderShownField() {
        if (shownFieldRe == null || shownFieldIm == null || currentFieldW <= 0 || currentFieldH <= 0) {
            return;
        }
        state.setAmplitudeImage(new ImagePlus("Amplitude",
            amplitudeToDisplay(shownFieldRe, shownFieldIm, currentFieldW, currentFieldH)));
        state.setPhaseImage(new ImagePlus("Phase",
            phaseToDisplay(shownFieldRe, shownFieldIm, currentFieldW, currentFieldH)));
    }

    /** Parameters that produced the current field, kept with its reconstruction. */
    private void recordReconParams() {
        if (cur == null) {
            return;
        }
        cur.lambda = params.getWavelengthUm();
        cur.dx = params.getPixelPitchXUm();
        cur.dy = params.getPixelPitchYUm();
        if (!(scaleMag > 0)) {
            // The image scale inherits M from the propagation panel of the module that reconstructed it.
            PropagationUi ui = isModulePhaseShift() ? propOptionsPs : (isModuleNumericalProp() ? null : propOptionsPc);
            if (ui != null) {
                scaleMag = ui.getLateralMagnification();
            }
        }
        saveCurrentRecon();
    }

    /** µm per pixel (x, y) of the outputs: sensor pitch / M; NaN when unknown. */
    private double[] outputScaleUm() {
        double dx = cur != null && cur.dx > 0 ? cur.dx : params.getPixelPitchXUm();
        double dy = cur != null && cur.dy > 0 ? cur.dy : params.getPixelPitchYUm();
        if (cur != null && cur.scalePitchX > 0) {
            dx = cur.scalePitchX;
            dy = cur.scalePitchY;
        }
        double m = scaleMag > 0 ? scaleMag : 1.0;
        if (!(dx > 0) || !(dy > 0)) {
            return new double[] {Double.NaN, Double.NaN};
        }
        return new double[] {dx / m, dy / m};
    }

    /** Fiji scale of amplitude, phase and Z-scan outputs (Analyze ▸ Measure, ROIs, QPI use it). */
    private void applyOutputScale(ImagePlus imp) {
        double[] s = outputScaleUm();
        if (imp == null || Double.isNaN(s[0])) {
            return;
        }
        ij.measure.Calibration cal = imp.getCalibration().copy();
        cal.pixelWidth = s[0];
        cal.pixelHeight = s[1];
        cal.setUnit("µm");
        imp.setCalibration(cal);
    }
    private static double[] floatRegionToDouble(float[] region) {
        double[] out = new double[region.length];
        for (int i = 0; i < region.length; i++) {
            out[i] = region[i];
        }
        return out;
    }

    private void ensureCurrentFieldFromHologram(boolean phaseOnlyField) {
        if (currentFieldRe != null && currentFieldIm != null && currentFieldW > 0 && currentFieldH > 0) {
            return;
        }
        if (!state.hasHologram()) {
            return;
        }
        FloatProcessor src = state.getHologramImage().getProcessor().convertToFloatProcessor();
        int w = src.getWidth();
        int h = src.getHeight();
        srcWidth = w;
        srcHeight = h;
        float[] px = (float[]) src.getPixels();
        float[] re = new float[w * h];
        float[] im = new float[w * h];
        if (phaseOnlyField) {
            // HoloBio Python NP: coherent grayscale -> phase map -> U = exp(i*phi), phi in [0, 2pi).
            float[] p8 = (float[]) src.convertToByteProcessor().convertToFloatProcessor().getPixels();
            for (int i = 0; i < re.length; i++) {
                double phi = (p8[i] / 255.0) * (2.0 * Math.PI);
                re[i] = (float) Math.cos(phi);
                im[i] = (float) Math.sin(phi);
            }
        } else {
            System.arraycopy(px, 0, re, 0, re.length);
        }
        setCurrentField(re, im, w, h);
    }
    private void _propagate_current_field(PropagationUi propUi, String method) {
        if (currentFieldRe == null || currentFieldIm == null || currentFieldW <= 0 || currentFieldH <= 0) {
            HoloBioFijiUi.message("HoloBio", "No reconstructed field is available yet. Run Compensate "
                + "(or Phase Shifting) first; in Numerical Propagation, link a hologram with Use Active Image.");
            return;
        }
        if (propUi == null) {
            HoloBioFijiUi.message("HoloBio", "Propagation options are not initialized.");
            return;
        }
        String mode = propUi.mode();
        if ("Autofocus".equals(mode)) {
            runAutofocusFromUi(propUi, method);
            return;
        }
        if ("Z-scan".equals(mode)) {
            runZScan(propUi, method);
            return;
        }
        double zEff = propUi.effectiveZFixedUm();
        if (Double.isNaN(zEff)) {
            HoloBioFijiUi.message("HoloBio", "Enter a propagation distance (positive, negative or zero).");
            return;
        }
        int w = currentFieldW;
        int h = currentFieldH;
        float[] re = currentFieldRe.clone();
        float[] im = currentFieldIm.clone();
        // z = 0 leaves the field exactly as it is; negative z propagates backwards (fix 9).
        propagateSelectedAlgorithmInPlace(re, im, w, h, zEff, method);
        setShownField(re, im);
        recordReconParams();
        IJ.showStatus("HoloBio: propagated " + method + " to " + propUi.describeWithCamera(zEff) + ".");
    }

    /** Fix 11: a stack of amplitude and phase planes from z start to z end. */
    private void runZScan(PropagationUi ui, String method) {
        double z0 = ui.effectiveZScanStartUm();
        double z1 = ui.effectiveZScanEndUm();
        double dz = ui.effectiveZScanStepUm();
        if (Double.isNaN(z0) || Double.isNaN(z1) || Double.isNaN(dz)) {
            HoloBioFijiUi.message("HoloBio", "Z-scan: enter z start, z end and step.");
            return;
        }
        if (z1 <= z0 || dz <= 0 || dz > z1 - z0) {
            HoloBioFijiUi.message("HoloBio", "Z-scan: z end must be greater than z start, and the step must be "
                + "positive and no larger than the range.");
            return;
        }
        int n = (int) Math.floor((z1 - z0) / dz + 1e-9) + 1;
        if (n > 300) {
            HoloBioFijiUi.message("HoloBio", "Z-scan: " + n + " planes requested; use a larger step (at most 300 planes).");
            return;
        }
        int w = currentFieldW;
        int h = currentFieldH;
        ImageStack amp = new ImageStack(w, h);
        ImageStack phs = new ImageStack(w, h);
        lastZScanUm = new double[n];
        for (int i = 0; i < n; i++) {
            double z = z0 + i * dz;
            lastZScanUm[i] = z;
            IJ.showProgress(i, n);
            float[] re = currentFieldRe.clone();
            float[] im = currentFieldIm.clone();
            propagateSelectedAlgorithmInPlace(re, im, w, h, z, method);
            String label = "z = " + ui.describeUm(z);
            amp.addSlice(label, amplitudeToDisplay(re, im, w, h));
            phs.addSlice(label, phaseToDisplay(re, im, w, h));
        }
        IJ.showProgress(1.0);
        showStack(amp, "Amplitude (Z-scan)");
        showStack(phs, "Phase (Z-scan)");
        SwingUtilities.invokeLater(() -> {
            for (String role : new String[] {"Phase (Z-scan)", "Amplitude (Z-scan)"}) {
                ImagePlus st = WindowManager.getImage(outputTitle(role));
                if (st != null && st.getWindow() != null) {
                    st.getWindow().toFront();
                }
            }
        });
        IJ.showStatus("HoloBio: Z-scan, " + n + " planes from " + ui.describeUm(z0) + " to " + ui.describeUm(z1) + ".");
    }

    /** Z-scan stack; an open window of the same title is reused (item 20). */
    private void showStack(ImageStack stack, String role) {
        String title = outputTitle(role);
        ImagePlus imp = new ImagePlus(title, stack);
        applyOutputScale(imp);
        ImagePlus existing = WindowManager.getImage(title);
        if (existing != null && existing.getWindow() != null) {
            existing.setImage(imp);
            existing.setTitle(title);
            applyOutputScale(existing);
            existing.changes = false;
            return;
        }
        HoloBioFijiUi.showImagePlus(imp);
    }

    /** Last focus-curve plot; for tests. */
    private Plot lastAutofocusPlot;

    /** Planes of the last Z-scan (µm, as propagated); for tests. */
    private double[] lastZScanUm;
    /** Distances, metric values and best distance of the last autofocus (µm, as propagated). */
    private double[] lastAutofocusZ;
    private double[] lastAutofocusScore;
    private double lastAutofocusBest = Double.NaN;

    /**
     * Fix 4: autofocus over [z min, z max] with the user's step, evaluating the metric inside a
     * rectangle drawn on the amplitude image. As in HoloBio Python ({@code autofocus_field}), the
     * distance with the lowest metric value is kept, the field is propagated to it and shown, and
     * the focus curve is plotted.
     */
    private void runAutofocusFromUi(PropagationUi ui, String method) {
        double zMin = ui.autofocusZMinUm();
        double zMax = ui.autofocusZMaxUm();
        double step = ui.autofocusStepUm();
        if (Double.isNaN(zMin) || Double.isNaN(zMax) || Double.isNaN(step)) {
            HoloBioFijiUi.message("HoloBio", "Autofocus: enter z min, z max and step.");
            return;
        }
        if (zMax <= zMin || step <= 0 || step > zMax - zMin) {
            HoloBioFijiUi.message("HoloBio", "Autofocus: z max must be greater than z min, and the step must be "
                + "positive and no larger than the range.");
            return;
        }
        // Planes zMin, zMin + step, … up to zMax (Python: np.arange(zmin, zmax + step/2, step)).
        // The old floor(range/step + 0.5) + 1 added a plane beyond z max when the step did not
        // divide the range (item 4).
        int n = autofocusPlaneCount(zMin, zMax, step);
        if (n > 500) {
            HoloBioFijiUi.message("HoloBio", "Autofocus: " + n + " planes requested; use a larger step (at most 500).");
            return;
        }
        java.awt.Rectangle roi = autofocusRoi();
        if (roi == null) {
            // No region yet: let the user draw one on the amplitude and continue from there,
            // whatever propagation mode or method is selected (item 5).
            if (WindowManager.getImage(outputTitle("Amplitude")) == null) {
                renderCurrentFieldWithoutPropagation();
                update_right_view();
            }
            ImagePlus a = WindowManager.getImage(outputTitle("Amplitude"));
            pickRectangleThen(a, "Draw a rectangle around the object to bring into focus, then click Run autofocus.",
                "Run autofocus", () -> runAutofocusFromUi(ui, method));
            return;
        }
        int w = currentFieldW;
        int h = currentFieldH;
        boolean tenengrad = "Tenengrad".equals(ui.autofocusMetric());
        double[] zs = new double[n];
        double[] scores = new double[n];
        int best = 0;
        for (int i = 0; i < n; i++) {
            zs[i] = zMin + i * step;
            IJ.showProgress(i, n);
            float[] re = currentFieldRe.clone();
            float[] im = currentFieldIm.clone();
            propagateSelectedAlgorithmInPlace(re, im, w, h, zs[i], method);
            double[] amp = roiAmplitude(re, im, w, roi);
            scores[i] = tenengrad ? metricTenengradRoi(amp, roi.width, roi.height) : metricNormalizedVarianceRoi(amp);
            if (scores[i] < scores[best]) {
                best = i;
            }
        }
        IJ.showProgress(1.0);
        lastAutofocusZ = zs;
        lastAutofocusScore = scores;
        lastAutofocusBest = zs[best];

        float[] re = currentFieldRe.clone();
        float[] im = currentFieldIm.clone();
        propagateSelectedAlgorithmInPlace(re, im, w, h, zs[best], method);
        setShownField(re, im);
        recordReconParams();
        // The focus distance goes into the Fixed field, in the units and plane it was entered in,
        // so Fixed mode reproduces this plane (item 4).
        ui.setFixedFromPropagatedUm(zs[best]);

        // Focus curve as HoloBio Python draws it (blue "metric_nv"/"metric_tenv" line, red dashed
        // optimum), with x in the units and plane the range was entered in.
        String metricName = tenengrad ? "metric_tenv" : "metric_nv";
        double[] xs = new double[n];
        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            xs[i] = ui.toDisplayUnits(zs[i]);
            yMin = Math.min(yMin, scores[i]);
            yMax = Math.max(yMax, scores[i]);
        }
        double pad = (yMax - yMin) * 0.05 + 1e-12;
        double xBest = xs[best];
        Plot plot = new Plot("Focus Curve \u2013 " + metricName, ui.distanceAxisLabel(), "Sharpness Metric");
        double xPad = (xs[n - 1] - xs[0]) * 0.05;     // matplotlib-like margin so an edge optimum stays visible
        plot.setLimits(xs[0] - xPad, xs[n - 1] + xPad, yMin - pad, yMax + pad);
        plot.setColor(Color.BLUE);
        plot.setLineWidth(2);
        plot.addPoints(xs, scores, Plot.LINE);
        plot.setColor(Color.RED);
        plot.setLineWidth(2);
        plot.addPoints(new double[] {xBest, xBest}, new double[] {yMin - pad, yMax + pad}, Plot.LINE);
        plot.setColor(Color.BLACK);
        plot.setLineWidth(1);
        plot.addLegend(metricName + "\n" + String.format(java.util.Locale.US, "Optimal Distance = %.4g %s",
            xBest, ui.unitLabel()));
        plot.setFrameSize(640, 384);
        if (!java.awt.GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("holobio.noPlots")) {
            // One focus-curve window: a new run replaces the previous curve instead of adding a window.
            ImagePlus old = WindowManager.getImage(outputTitle("Focus Curve"));
            if (old != null && old.getWindow() instanceof ij.gui.PlotWindow) {
                ((ij.gui.PlotWindow) old.getWindow()).drawPlot(plot);
                old.setTitle(outputTitle("Focus Curve"));
            } else {
                ImagePlus pimp = plot.show().getImagePlus();
                pimp.setTitle(outputTitle("Focus Curve"));
            }
        }
        lastAutofocusPlot = plot;
        IJ.showStatus("HoloBio: autofocus best " + ui.describeWithCamera(zs[best]) + " (" + n
            + " planes); copied to Fixed distance.");
    }

    /** Number of planes zMin + k·step not beyond zMax + step/2 (as Python's np.arange). */
    static int autofocusPlaneCount(double zMin, double zMax, double step) {
        return Math.max(1, (int) Math.ceil((zMax - zMin) / step + 0.5 - 1e-9));
    }

    /**
     * Autofocus region: an area ROI on the image clicked last when it has the field's size (the
     * hologram, amplitude, phase or a Z-scan plane), else one on the amplitude or phase output.
     */
    private java.awt.Rectangle autofocusRoi() {
        List<ImagePlus> candidates = new ArrayList<>();
        candidates.add(WindowManager.getCurrentImage());
        candidates.add(WindowManager.getImage(outputTitle("Amplitude")));
        candidates.add(WindowManager.getImage(outputTitle("Phase")));
        candidates.add(linkedImage);
        for (ImagePlus imp : candidates) {
            if (imp == null || imp.getRoi() == null || !imp.getRoi().isArea()
                    || imp.getWidth() != currentFieldW || imp.getHeight() != currentFieldH) {
                continue;
            }
            java.awt.Rectangle r = imp.getRoi().getBounds().intersection(new java.awt.Rectangle(0, 0, currentFieldW, currentFieldH));
            if (r.width >= 3 && r.height >= 3) {
                return r;
            }
        }
        return null;
    }

    /**
     * Rectangle tool on {@code target}, with a small always-on-top helper; {@code then} runs when
     * the user confirms. No window is created or closed besides the helper.
     */
    private void pickRectangleThen(ImagePlus target, String instructions, String okText, Runnable then) {
        if (target == null || target.getWindow() == null) {
            HoloBioFijiUi.message("HoloBio", "Reconstruct first, then draw a rectangle on the amplitude.");
            return;
        }
        WindowManager.setCurrentWindow(target.getWindow());
        target.getWindow().toFront();
        IJ.setTool("rectangle");
        JDialog helper = new JDialog((Frame) null, "HoloBio — region", false);
        helper.setAlwaysOnTop(true);
        helper.setLayout(new BorderLayout(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_2));
        JLabel lab = new JLabel("<html><body style='width:260px'>" + instructions + "</body></html>");
        lab.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3,
            HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3));
        helper.add(lab, BorderLayout.CENTER);
        JButton ok = HoloBioUiStyle.primaryButton(okText);
        JButton cancel = HoloBioUiStyle.secondaryButton("Cancel");
        helper.add(HoloBioUiStyle.buildFooter("On \"" + target.getTitle() + "\"", cancel, ok), BorderLayout.SOUTH);
        ok.addActionListener(e -> {
            if (target.getRoi() == null || !target.getRoi().isArea()) {
                JOptionPane.showMessageDialog(helper, "Draw a rectangle on the image first.", "HoloBio",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            helper.dispose();
            WindowManager.setCurrentWindow(target.getWindow());
            then.run();
        });
        cancel.addActionListener(e -> helper.dispose());
        helper.pack();
        java.awt.Window iw = target.getWindow();
        helper.setLocation(iw.getX() + 20, iw.getY() + 20);
        helper.setVisible(true);
    }

    private static double[] roiAmplitude(float[] re, float[] im, int w, java.awt.Rectangle r) {
        double[] out = new double[r.width * r.height];
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                int i = (r.y + y) * w + r.x + x;
                out[y * r.width + x] = Math.hypot(re[i], im[i]);
            }
        }
        return out;
    }

    /** {@code pyDHM_methods.metric_nv}: sum((A - mean)^2) / mean. */
    static double metricNormalizedVarianceRoi(double[] a) {
        double mean = 0;
        for (double v : a) {
            mean += v;
        }
        mean /= a.length;
        if (mean == 0) {
            return 0;
        }
        double acc = 0;
        for (double v : a) {
            acc += (v - mean) * (v - mean);
        }
        return acc / mean;
    }

    /**
     * {@code pyDHM_methods.metric_tenv}: variance of Gx^2 + Gy^2 (Sobel), with SciPy's 'reflect'
     * border (d c b a | a b c d) so values match HoloBio Python.
     */
    static double metricTenengradRoi(double[] a, int w, int h) {
        double[] g = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double p00 = at(a, w, h, x - 1, y - 1), p01 = at(a, w, h, x, y - 1), p02 = at(a, w, h, x + 1, y - 1);
                double p10 = at(a, w, h, x - 1, y), p12 = at(a, w, h, x + 1, y);
                double p20 = at(a, w, h, x - 1, y + 1), p21 = at(a, w, h, x, y + 1), p22 = at(a, w, h, x + 1, y + 1);
                double gx = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20);
                double gy = (p20 + 2 * p21 + p22) - (p00 + 2 * p01 + p02);
                g[y * w + x] = gx * gx + gy * gy;
            }
        }
        double mean = 0;
        for (double v : g) {
            mean += v;
        }
        mean /= g.length;
        double acc = 0;
        for (double v : g) {
            acc += (v - mean) * (v - mean);
        }
        return acc / g.length;
    }

    private static double at(double[] a, int w, int h, int x, int y) {
        if (x < 0) x = -x - 1;
        if (x >= w) x = 2 * w - x - 1;
        if (y < 0) y = -y - 1;
        if (y >= h) y = 2 * h - y - 1;
        return a[y * w + x];
    }
    private boolean _update_physical_params() {
        if (isModuleNumericalProp()) {
            return readParamsFromNumericalPanel();
        }
        if (isModulePhaseShift()) {
            return readParamsFromPhaseShiftPanel();
        }
        return readParamsFromPhaseCompPanel();
    }

    private void update_right_view() {
        openOutputImageInFiji(state.getAmplitudeImage(), "Amplitude");
        openOutputImageInFiji(state.getPhaseImage(), "Phase");
    }

    private void _on_tools_select(String selectedOption) {
        debugLog("Tools menu selected: " + selectedOption);
        activateFromFiji(false);
        if ("Set scale".equals(selectedOption)) apply_set_scale();
        if ("QPI".equals(selectedOption) || "Bio-Analysis".equals(selectedOption)) apply_QPI();
        if ("Speckle".equals(selectedOption)) apply_speckle();
        if ("Speckle Filter".equals(selectedOption)) apply_speckle_filter();
    }

    private void apply_QPI() {
        debugLog("Tools: QPI dialog requested.");
        HoloBioFijiUi.showQpiDialog(dialogOwnerOrNull(), createToolInputs(), toolCallbacks);
    }

    /**
     * Set scale (item 14): pixel pitch and lateral magnification, inherited from the
     * reconstruction, give the µm per pixel of its amplitude, phase and Z-scan windows. QPI
     * and Fiji's own measurements read that scale from the windows.
     */
    private void apply_set_scale() {
        double[] s = outputScaleUm();
        double pitchX = cur != null && cur.scalePitchX > 0 ? cur.scalePitchX
            : (cur != null && cur.dx > 0 ? cur.dx : parseDoubleOr(tfPcPitchX, 3.75));
        double pitchY = cur != null && cur.scalePitchY > 0 ? cur.scalePitchY
            : (cur != null && cur.dy > 0 ? cur.dy : parseDoubleOr(tfPcPitchY, pitchX));
        double m = scaleMag > 0 ? scaleMag : (propOptionsPc != null ? propOptionsPc.getLateralMagnification() : 1.0);
        double[] chosen = HoloBioUiStyle.showSetScaleDialog(dialogOwnerOrNull(), holoBaseName, pitchX, pitchY, m);
        if (chosen == null) {
            return;
        }
        if (cur == null) {
            HoloBioFijiUi.message("HoloBio", "Reconstruct a hologram first; the scale applies to its outputs.");
            return;
        }
        cur.scalePitchX = chosen[0];
        cur.scalePitchY = chosen[1];
        scaleMag = chosen[2];
        saveCurrentRecon();
        int n = 0;
        for (String role : new String[] {"Amplitude", "Phase", "Amplitude (Z-scan)", "Phase (Z-scan)"}) {
            ImagePlus imp = WindowManager.getImage(outputTitle(role));
            if (imp != null) {
                applyOutputScale(imp);
                imp.repaintWindow();
                n++;
            }
        }
        double[] now = outputScaleUm();
        IJ.showStatus(String.format(java.util.Locale.US, "HoloBio: scale %.4g µm/px set on %d window(s) of %s.",
            now[0], n, holoBaseName));
    }

    /** Opens Speckle dialog (HoloBio Python speckle panel). */
    private void apply_speckle() {
        debugLog("Tools: Speckle dialog requested.");
        HoloBioFijiUi.showSpeckleDialog(dialogOwnerOrNull(), createToolInputs(), toolCallbacks);
    }

    private void apply_speckle_filter() {
        HoloBioFijiUi.showSpeckleDialog(dialogOwnerOrNull(), createToolInputs(), toolCallbacks);
    }

    private java.awt.Frame dialogOwnerOrNull() {
        ij.ImageJ ij = IJ.getInstance();
        return ij instanceof java.awt.Frame ? (java.awt.Frame) ij : null;
    }

    /** Tools work on the field as shown (after propagation and speckle filtering), items 8 and 13. */
    private HoloBioToolInputs createToolInputs() {
        float[] re = shownFieldRe != null ? shownFieldRe : currentFieldRe;
        float[] im = shownFieldIm != null ? shownFieldIm : currentFieldIm;
        HoloBioDhmParams p = new HoloBioDhmParams();
        p.setWavelengthUm(cur != null && cur.lambda > 0 ? cur.lambda : params.getWavelengthUm());
        p.setPixelPitchXUm(cur != null && cur.dx > 0 ? cur.dx : params.getPixelPitchXUm());
        p.setPixelPitchYUm(cur != null && cur.dy > 0 ? cur.dy : params.getPixelPitchYUm());
        return HoloBioToolInputs.fromState(state, re, im, currentFieldW, currentFieldH,
            p, scaleMag > 0 ? scaleMag : 1.0);
    }

    private void change_menu_to(String name) {
        if ("phase_compensation".equals(name)) {
            showModuleCard("PHASE_COMP");
        }
        if ("phase_shifting".equals(name)) {
            showModuleCard("PHASE_SHIFT");
        }
        if ("numerical_propagation".equals(name)) {
            showModuleCard("NUM_PROP");
        }
    }

    private void activate_phase_compensation() {
        rbModulePhaseComp.setSelected(true);
        change_menu_to("phase_compensation");
    }

    private void activate_phase_shifting() {
        rbModulePhaseShift.setSelected(true);
        change_menu_to("phase_shifting");
    }

    private void activate_numerical_propagation() {
        rbModuleNumericalProp.setSelected(true);
        change_menu_to("numerical_propagation");
    }

    private int nextPowerOfTwo(int value) {
        int n = 1;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }

    /** Phase shifting from the active stack reads its planes, so the stack must still be open. */
    private boolean sourceStackOpen() {
        if (activeSourceImage == null || activeSourceImage.getStack() == null
                || activeSourceImage.getStack().getSize() < 1 || activeSourceImage.getProcessor() == null) {
            HoloBioFijiUi.message("HoloBio", "No stack is linked. Click the phase-shifted stack in Fiji "
                + "(or use Use Active Image), or set Frames source to Folder.");
            return false;
        }
        return true;
    }

    /** Frames from the chosen source. requiredFrames = 0 means all frames (at least 3). */
    private List<FloatProcessor> collectPhaseShiftFrames(int requiredFrames) {
        if (SRC_FOLDER.equals(psSourceBox.getSelectedItem())) {
            return folderFrames(tfPsFolder.getText(), tfPsFilter.getText(), requiredFrames, "Frames", null);
        }
        if (!sourceStackOpen()) {
            return null;
        }
        return stackFrames(activeSourceImage, requiredFrames, "Frames");
    }

    private static List<FloatProcessor> stackFrames(ImagePlus imp, int requiredFrames, String what) {
        int nPlanes = imp.getStackSize();
        int n = requiredFrames > 0 ? requiredFrames : nPlanes;
        if (nPlanes < (requiredFrames > 0 ? requiredFrames : 3)) {
            HoloBioFijiUi.message(
                "HoloBio",
                what + ": selected method needs " + (requiredFrames > 0 ? requiredFrames : "at least 3")
                    + " planes; this image has " + nPlanes
                    + " (C=" + imp.getNChannels() + ", Z=" + imp.getNSlices() + ", T=" + imp.getNFrames() + ").\n"
                    + "Use a Z stack with the required number of slices (not multiple channels in one slice), e.g. 2 slices for BPS2, 3 for BPS3, or at least 3 for AIA."
            );
            return null;
        }
        List<FloatProcessor> frames = new ArrayList<>(n);
        int w = imp.getWidth();
        int h = imp.getHeight();
        ImageStack stack = imp.getStack();
        for (int i = 1; i <= n; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            if (ip.getWidth() != w || ip.getHeight() != h) {
                HoloBioFijiUi.message("HoloBio", what + ": all phase-shift planes must have the same width and height.");
                return null;
            }
            frames.add((FloatProcessor) ip.convertToFloatProcessor().duplicate());
        }
        return frames;
    }

    /**
     * Images in {@code dir} whose name contains {@code filter}, sorted by name.
     * The names of the files used are added to {@code usedNames} when it is not null.
     */
    private static List<FloatProcessor> folderFrames(String dir, String filter, int requiredFrames, String what,
                                                     List<String> usedNames) {
        java.io.File folder = new java.io.File(dir.trim());
        String[] all = folder.list();
        if (dir.trim().isEmpty() || all == null) {
            HoloBioFijiUi.message("HoloBio", what + ": choose a valid folder.");
            return null;
        }
        Arrays.sort(all);
        List<String> names = new ArrayList<>();
        for (String name : all) {
            String low = name.toLowerCase();
            boolean image = low.endsWith(".bmp") || low.endsWith(".tif") || low.endsWith(".tiff")
                || low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg");
            if (image && !name.startsWith("._") && name.contains(filter)) {
                names.add(name);
            }
        }
        int n = requiredFrames > 0 ? requiredFrames : names.size();
        if (names.size() < (requiredFrames > 0 ? requiredFrames : 3)) {
            HoloBioFijiUi.message("HoloBio", what + ": found " + names.size() + " matching images in " + dir
                + "; the selected method needs " + (requiredFrames > 0 ? requiredFrames : "at least 3") + ".");
            return null;
        }
        List<FloatProcessor> frames = new ArrayList<>(n);
        int w = 0;
        int h = 0;
        for (int i = 0; i < n; i++) {
            IJ.showStatus("HoloBio: loading " + what.toLowerCase() + " " + (i + 1) + "/" + n);
            IJ.showProgress(i, n);
            ImagePlus imp = IJ.openImage(new java.io.File(folder, names.get(i)).getPath());
            if (imp == null) {
                HoloBioFijiUi.message("HoloBio", what + ": could not open " + names.get(i) + ".");
                return null;
            }
            if (i == 0) {
                w = imp.getWidth();
                h = imp.getHeight();
            } else if (imp.getWidth() != w || imp.getHeight() != h) {
                HoloBioFijiUi.message("HoloBio", what + ": " + names.get(i) + " has a different size than "
                    + names.get(0) + ".");
                return null;
            }
            frames.add(imp.getProcessor().convertToFloatProcessor());
            if (usedNames != null) {
                usedNames.add(names.get(i));
            }
        }
        IJ.showProgress(1.0);
        return frames;
    }

    private void setCurrentField(float[] re, float[] im, int w, int h) {
        if (w <= 0 || h <= 0 || re == null || im == null || re.length != w * h || im.length != w * h) {
            throw new IllegalArgumentException("Invalid complex field buffer size.");
        }
        currentFieldRe = re.clone();
        currentFieldIm = im.clone();
        currentFieldW = w;
        currentFieldH = h;
        shownFieldRe = currentFieldRe;
        shownFieldIm = currentFieldIm;
        if (cur == null) {
            // e.g. phase shifting from a folder with no hologram linked: give the result a record too.
            Recon r = new Recon();
            r.base = holoBaseName;
            recons.put(r.base, r);
            cur = r;
        }
    }

    /** Amplitude display aligned with Python: (amp − min) / (max − min) → 0…255. */
    private FloatProcessor amplitudeToDisplay(float[] re, float[] im, int w, int h) {
        int len = w * h;
        FloatProcessor fp = new FloatProcessor(w, h);
        float[] out = (float[]) fp.getPixels();
        float min = Float.POSITIVE_INFINITY;
        float max = 0f;
        for (int i = 0; i < len; i++) {
            float amp = (float) Math.hypot(re[i], im[i]);
            out[i] = amp;
            if (amp < min) {
                min = amp;
            }
            if (amp > max) {
                max = amp;
            }
        }
        float span = max - min;
        float inv = span > 1e-12f ? 255f / span : 0f;
        for (int i = 0; i < len; i++) {
            out[i] = (out[i] - min) * inv;
        }
        // Pixels were mutated via getPixels(); refresh the (otherwise stale [0,0]) range.
        fp.resetMinAndMax();
        return fp;
    }

    /**
     * Wrapped phase for display: {@code (angle+π)/(2π)→255}, same as Python after compensation.
     */
    private FloatProcessor phaseToDisplay(float[] re, float[] im, int w, int h) {
        int len = w * h;
        FloatProcessor fp = new FloatProcessor(w, h);
        float[] out = (float[]) fp.getPixels();
        for (int i = 0; i < len; i++) {
            double ph = Math.atan2(im[i], re[i]);
            double t = (ph + Math.PI) / (2.0 * Math.PI);
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
            out[i] = (float) (255.0 * t);
        }
        // Pixels were mutated via getPixels(); refresh the (otherwise stale [0,0]) range.
        fp.resetMinAndMax();
        return fp;
    }

    private double parseDoubleOr(JTextField field, double fallback) {
        if (field == null) return fallback;
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void propagateSelectedAlgorithmInPlace(float[] re, float[] im, int w, int h, double zUm, String method) {
        // Rectangular propagators accept any size, so non-square / non-power-of-two fields
        // (e.g. 1280x960, or the 960x960 Vortex-Legendre crop) no longer throw (fix 9).
        if ("Fresnel".equals(method)) {
            HoloBioPropagationMath.propagateFresnelInPlaceRect(re, im, h, w, zUm,
                params.getWavelengthUm(), params.getPixelPitchXUm(), params.getPixelPitchYUm());
        } else {
            HoloBioPropagationMath.propagateAngularSpectrumInPlaceRect(re, im, h, w, zUm,
                params.getWavelengthUm(), params.getPixelPitchXUm(), params.getPixelPitchYUm());
        }
    }
    /**
     * Per-module propagation controls (matches HoloBio Python: fixed uses M and distance in µm with M² axial scaling).
     */
    private final class PropagationUi {
        /**
         * When true (phase compensation / phase shifting), axial distances from this panel are multiplied by M²
         * to match tube-length style scaling. When false (numerical propagation), distances match HoloBio Python NP:
         * use the propagation options fixed-z field directly and do not scale z-scan/autofocus by M².
         */
        private final boolean scaleAxialWithMagnification;
        private final JComboBox<String> modeBox;
        private final JComboBox<String> distanceUnitBox;
        private final JTextField lateralMagField;
        private final JTextField fixedDistanceField;
        private final JTextField zScanZMinField;
        private final JTextField zScanZMaxField;
        private final JTextField zScanStepField;
        private final JTextField afZMinField;
        private final JTextField afZMaxField;
        private final JTextField afStepField;
        private final JLabel lblFixedDist, lblZStart, lblZEnd, lblZStep, lblAfMin, lblAfMax, lblAfStep;
        private final JComboBox<String> autofocusMetricBox;
        /** "= … at the camera (z·M²)" under the fixed distance of the phase modules. */
        private final JLabel cameraHint;
        private final JPanel cardPanel;
        private final CardLayout cardLayout;
        private String distanceUnit = "µm";
        private boolean updatingDistanceUi;
        /** Root panel added to each module (outer class cannot access a private inner field). */
        final JPanel root;

        PropagationUi() {
            this(true);
        }

        PropagationUi(boolean scaleAxialWithMagnification) {
            this.scaleAxialWithMagnification = scaleAxialWithMagnification;
            root = HoloBioUiStyle.formSection("Propagation");
            GridBagConstraints pc = HoloBioUiStyle.formGbc();

            modeBox = new JComboBox<>(new String[] {"Fixed", "Z-scan", "Autofocus"});
            modeBox.setLightWeightPopupEnabled(false);
            modeBox.addActionListener(e -> syncModeCards());
            HoloBioUiStyle.addLabelField(root, pc, "Mode", modeBox);

            distanceUnitBox = new JComboBox<>(HoloBioUiStyle.DISTANCE_UNITS);
            distanceUnitBox.setSelectedItem(distanceUnit);
            distanceUnitBox.setLightWeightPopupEnabled(false);
            distanceUnitBox.setToolTipText("Unit for propagation distances (model stays in µm)");
            distanceUnitBox.addActionListener(e -> onDistanceUnitChanged());
            HoloBioUiStyle.addLabelField(root, pc, "Distance unit", distanceUnitBox);

            lateralMagField = new JTextField(HoloBioUiStyle.formatMag(40.0), FIELD_COLS);
            if (scaleAxialWithMagnification) {
                // Phase compensation / shifting: distances are entered at the sample and propagated
                // z*M^2 at the camera, as HoloBio Python. Numerical Propagation uses distances as typed
                // and has no M (it never changed its propagation); the image scale is set with
                // Analysis tools > Set scale.
                lateralMagField.setToolTipText("Lateral magnification M. Distances below are at the sample; "
                    + "the field is propagated z·M² at the camera. M also sets the µm scale (pitch / M).");
                HoloBioUiStyle.addLabelField(root, pc, HoloBioUiStyle.MAG_LABEL, lateralMagField);
            }

            cardLayout = new CardLayout();
            cardPanel = HoloBioUiStyle.huggingCardPanel(cardLayout);
            cardPanel.setOpaque(false);

            JPanel fixedCard = new JPanel(new GridBagLayout());
            GridBagConstraints fc = HoloBioUiStyle.formGbc();
            fixedDistanceField = new JTextField("", FIELD_COLS);
            lblFixedDist = HoloBioUiStyle.addLabelField(fixedCard, fc,
                scaleAxialWithMagnification ? "Distance at sample (µm)" : "Distance (µm)", fixedDistanceField);
            cameraHint = HoloBioUiStyle.helperText(" ");
            if (scaleAxialWithMagnification) {
                fc.gridx = 0;
                fc.gridwidth = 2;
                fixedCard.add(cameraHint, fc);
                fc.gridy++;
                fc.gridwidth = 1;
                javax.swing.event.DocumentListener hint = new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshCameraHint(); }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshCameraHint(); }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshCameraHint(); }
                };
                fixedDistanceField.getDocument().addDocumentListener(hint);
                lateralMagField.getDocument().addDocumentListener(hint);
            }
            cardPanel.add(fixedCard, "FIXED");

            JPanel zCard = new JPanel(new GridBagLayout());
            GridBagConstraints zc = HoloBioUiStyle.formGbc();
            zScanZMinField = new JTextField("", FIELD_COLS);
            zScanZMaxField = new JTextField("", FIELD_COLS);
            zScanStepField = new JTextField("", FIELD_COLS);
            lblZStart = HoloBioUiStyle.addLabelField(zCard, zc, "z start (µm)", zScanZMinField);
            lblZEnd   = HoloBioUiStyle.addLabelField(zCard, zc, "z end (µm)", zScanZMaxField);
            lblZStep  = HoloBioUiStyle.addLabelField(zCard, zc, "Step (µm)", zScanStepField);
            cardPanel.add(zCard, "ZSCAN");

            JPanel afCard = new JPanel(new GridBagLayout());
            GridBagConstraints ac = HoloBioUiStyle.formGbc();
            afZMinField = new JTextField("", FIELD_COLS);
            afZMaxField = new JTextField("", FIELD_COLS);
            afStepField = new JTextField("", FIELD_COLS);
            autofocusMetricBox = new JComboBox<>(new String[] {"Normalized Variance", "Tenengrad"});
            autofocusMetricBox.setLightWeightPopupEnabled(false);
            lblAfMin = HoloBioUiStyle.addLabelField(afCard, ac, "z min (µm)", afZMinField);
            lblAfMax = HoloBioUiStyle.addLabelField(afCard, ac, "z max (µm)", afZMaxField);
            lblAfStep = HoloBioUiStyle.addLabelField(afCard, ac, "Step (µm)", afStepField,
                "Distance between the planes tested. Smaller = more precise and slower.");
            afZMinField.setToolTipText("Negative values propagate backwards.");
            JLabel afRoiNote = HoloBioUiStyle.helperText("Draw a rectangle ROI on the amplitude image first.");
            ac.gridx = 0;
            ac.gridwidth = 2;
            afCard.add(afRoiNote, ac);
            ac.gridy++;
            ac.gridwidth = 1;
            HoloBioUiStyle.addLabelField(afCard, ac, "Autofocus metric", autofocusMetricBox,
                HoloBioUiStyle.FORM_LABEL_W, 140);
            cardPanel.add(afCard, "AF");

            pc.gridx = 0;
            pc.gridwidth = 2;
            pc.fill = GridBagConstraints.HORIZONTAL;
            pc.weightx = 1.0;
            pc.weighty = 0;
            pc.insets = new Insets(HoloBioUiStyle.SPACE_1, 0, 0, 0);
            root.add(cardPanel, pc);

            syncModeCards();

            if (!scaleAxialWithMagnification) {
                fixedDistanceField.setToolTipText("Fixed propagation z for Numerical Propagation.");
            }
        }

        private void onDistanceUnitChanged() {
            if (updatingDistanceUi) return;
            String next = (String) distanceUnitBox.getSelectedItem();
            if (next == null || next.equals(distanceUnit)) return;
            JTextField[] fields = {fixedDistanceField, zScanZMinField, zScanZMaxField, zScanStepField,
                afZMinField, afZMaxField, afStepField};
            // Only values the user typed are converted; empty fields stay empty (they used to be
            // filled with hidden defaults such as 5000 µm when the unit changed, item 3).
            double[] um = new double[fields.length];
            for (int i = 0; i < fields.length; i++) {
                um[i] = requiredUm(fields[i]);
            }
            distanceUnit = next;
            updatingDistanceUi = true;
            try {
                refreshDistanceLabels();
                for (int i = 0; i < fields.length; i++) {
                    if (!Double.isNaN(um[i])) {
                        fields[i].setText(fmtDist(um[i]));
                    }
                }
            } finally {
                updatingDistanceUi = false;
            }
            refreshCameraHint();
        }

        /** "= 16 mm at the camera (z·M²)" under the fixed distance of the phase modules. */
        private void refreshCameraHint() {
            if (cameraHint == null || !scaleAxialWithMagnification) {
                return;
            }
            double z = effectiveZFixedUm();
            cameraHint.setText(Double.isNaN(z) ? " "
                : "= " + formatUmAuto(z) + " at the camera (z·M², M = " + HoloBioUiStyle.formatMag(magnification()) + ")");
        }

        /** Put a propagated distance (µm) into the Fixed field, in the entered plane and units. */
        void setFixedFromPropagatedUm(double propagatedUm) {
            fixedDistanceField.setText(fmtDist(scaleAxialWithMagnification ? propagatedUm / scaleImg() : propagatedUm));
        }

        /** Axis label for distances in the entered plane and units. */
        String distanceAxisLabel() {
            return (scaleAxialWithMagnification ? "Distance at sample (" : "Propagation distance (") + distanceUnit + ")";
        }

        /** "z = 10 µm at the sample (16 mm at the camera)", or "z = 10 µm" in Numerical Propagation. */
        String describeWithCamera(double propagatedUm) {
            if (!scaleAxialWithMagnification) {
                return "z = " + describeUm(propagatedUm);
            }
            return "z = " + describeUm(propagatedUm) + " at the sample (" + formatUmAuto(propagatedUm) + " at the camera)";
        }

        private void refreshDistanceLabels() {
            String u = distanceUnit;
            lblFixedDist.setText(scaleAxialWithMagnification ? "Distance at sample (" + u + ")" : "Distance (" + u + ")");
            lblZStart.setText("z start (" + u + ")");
            lblZEnd.setText("z end (" + u + ")");
            lblZStep.setText("Step (" + u + ")");
            lblAfMin.setText("z min (" + u + ")");
            lblAfMax.setText("z max (" + u + ")");
            lblAfStep.setText("Step (" + u + ")");
        }

        private double umPerUnit() {
            return HoloBioUiStyle.umPerDistanceUnit(distanceUnit);
        }

        private double parseDistUm(JTextField tf, double fallbackUm) {
            return parseDoubleOr(tf, fallbackUm / umPerUnit()) * umPerUnit();
        }

        /** Distance in the selected unit: whole numbers from 100 up, else 4 significant digits, no trailing zeros. */
        private String fmtDist(double um) {
            double v = um / umPerUnit();
            if (Math.abs(v) >= 100) {
                return String.format(java.util.Locale.US, "%.0f", v);
            }
            if (v == 0) {
                return "0";
            }
            return new java.math.BigDecimal(v).round(new java.math.MathContext(4)).stripTrailingZeros().toPlainString();
        }

        private void syncModeCards() {
            String mode = mode();
            if ("Z-scan".equals(mode)) {
                cardLayout.show(cardPanel, "ZSCAN");
            } else if ("Autofocus".equals(mode)) {
                cardLayout.show(cardPanel, "AF");
            } else {
                cardLayout.show(cardPanel, "FIXED");
            }
            if (cardPanel.getParent() != null) {
                cardPanel.revalidate();
                cardPanel.getParent().revalidate();
                java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(cardPanel);
                if (w != null) {
                    packWindow(w);
                }
            }
        }

        private String mode() {
            String m = (String) modeBox.getSelectedItem();
            return m != null ? m : "Fixed";
        }

        private double magnification() {
            double M = parseDoubleOr(lateralMagField, 40.0);
            return M > 0 ? M : 1.0;
        }

        double getLateralMagnification() {
            return magnification();
        }

        private double scaleImg() {
            double M = magnification();
            return M * M;
        }

        /** Value in µm, or NaN when the field is empty / not a number (no silent defaults). */
        private double requiredUm(JTextField tf) {
            String t = tf.getText().trim();
            if (t.isEmpty()) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(t) * umPerUnit();
            } catch (NumberFormatException ex) {
                return Double.NaN;
            }
        }

        /** Phase modules scale entered (object-side) distances by M^2, like HoloBio Python. */
        private double toPropagatedUm(double enteredUm) {
            return scaleAxialWithMagnification ? enteredUm * scaleImg() : enteredUm;
        }

        /**
         * Fixed-mode distance in µm as propagated (phase modules: distance x M^2, as Python;
         * Numerical Propagation: as entered). Any sign; NaN when empty (fix 9).
         */
        private double effectiveZFixedUm() {
            return toPropagatedUm(requiredUm(fixedDistanceField));
        }

        private double effectiveZScanStartUm() {
            return toPropagatedUm(requiredUm(zScanZMinField));
        }

        private double effectiveZScanEndUm() {
            return toPropagatedUm(requiredUm(zScanZMaxField));
        }

        private double effectiveZScanStepUm() {
            return toPropagatedUm(requiredUm(zScanStepField));
        }

        private double autofocusZMinUm() {
            return toPropagatedUm(requiredUm(afZMinField));
        }

        private double autofocusZMaxUm() {
            return toPropagatedUm(requiredUm(afZMaxField));
        }

        private double autofocusStepUm() {
            return toPropagatedUm(requiredUm(afStepField));
        }

        /** A propagated distance back in the units the user entered (undoing M^2). */
        private double toDisplayUnits(double propagatedUm) {
            double entered = scaleAxialWithMagnification ? propagatedUm / scaleImg() : propagatedUm;
            return entered / umPerUnit();
        }

        private String unitLabel() {
            return distanceUnit;
        }

        /** Propagated distance in object-plane µm (z / M^2 in the phase modules), as Python plots it. */
        private double toObjectUm(double propagatedUm) {
            return scaleAxialWithMagnification ? propagatedUm / scaleImg() : propagatedUm;
        }

        private String describeUm(double propagatedUm) {
            return String.format(java.util.Locale.US, "%.4g %s", toDisplayUnits(propagatedUm), distanceUnit);
        }

        private String autofocusMetric() {
            String m = (String) autofocusMetricBox.getSelectedItem();
            return m != null ? m : "Normalized Variance";
        }
    }

}
