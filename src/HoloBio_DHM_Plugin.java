import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
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
import java.util.List;

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
    private final HoloBioSpeckleState speckleState = new HoloBioSpeckleState();
    /** Speckle filter output: updates amplitude/phase ImagePlus and Fiji windows. */
    private final HoloBioToolCallbacks toolCallbacks = new HoloBioToolCallbacks() {
        @Override
        public void onSpeckleFilterResult(boolean amplitudeChannel, FloatProcessor filtered0255) {
            float[] px = (float[]) filtered0255.getPixels();
            if (amplitudeChannel) {
                speckleState.setFilteredAmplitude(px);
                state.setAmplitudeImage(new ImagePlus("Amplitude", cropToSource(filtered0255)));
            } else {
                speckleState.setFilteredPhase(px);
                state.setPhaseImage(new ImagePlus("Phase", cropToSource(filtered0255)));
            }
            update_right_view();
        }

        @Override
        public void onSpeckleSppResult(float[] fieldRe, float[] fieldIm, int w, int h,
                                       boolean amplitudeChannel, FloatProcessor display0255) {
            currentFieldRe = fieldRe;
            currentFieldIm = fieldIm;
            currentFieldW = w;
            currentFieldH = h;
            int canvasW = srcWidth > 0 ? srcWidth : w;
            int canvasH = srcHeight > 0 ? srcHeight : h;
            renderCurrentFieldWithoutPropagation(canvasW, canvasH);
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

    @Override
    public void run(String arg) {
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

        JComboBox<String> toolsMenu = new JComboBox<>(new String[] {"Analysis tools", "Speckle", "QPI"});
        toolsMenu.setToolTipText("Open Speckle or QPI on the current reconstruction.");
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

        JPanel actions = HoloBioUiStyle.flowLeft();
        btnCompensate = HoloBioUiStyle.primaryButton("Compensate");
        btnCompensate.setToolTipText("Run the selected compensation method on the linked hologram.");
        btnCompensate.addActionListener(e ->
            HoloBioUiStyle.runBusy(btnCompensate, "Working…", this::run_phase_compensation));
        actions.add(btnCompensate);
        content.add(actions, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 0, 0);

        propOptionsPc = new PropagationUi();
        panel.add(content, BorderLayout.NORTH);
        panel.add(propOptionsPc.root, BorderLayout.SOUTH);
        return panel;
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
        JButton openOutputsButton = HoloBioUiStyle.secondaryButton("Open Outputs");
        JButton saveButton = HoloBioUiStyle.secondaryButton("Save…");
        btnApply = HoloBioUiStyle.primaryButton("Apply");

        resetButton.setToolTipText("Clear the linked hologram and reconstructed outputs.");
        openOutputsButton.setToolTipText("Bring amplitude, phase, and Fourier windows to the front.");
        saveButton.setToolTipText("Save Fourier transform, phase, amplitude, or the complex field (.npy).");
        btnApply.setToolTipText("Run the current submodule (compensate, shift, or propagate).");

        openOutputsButton.addActionListener(e -> openAllOutputsInFiji());
        saveButton.addActionListener(e -> onSave());
        btnApply.addActionListener(e -> {
            if (isAiaSelected()) {
                startAiaRun();
            } else {
                HoloBioUiStyle.runBusy(btnApply, "Working…", this::onApplyMvp);
            }
        });
        resetButton.addActionListener(e -> onReset());

        return HoloBioUiStyle.buildActionBar(
            resetButton, openOutputsButton, saveButton, btnApply);
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
     * Load a float grayscale hologram from {@code imp} (duplicated; first slice if stack).
     */
    private void loadHologramFromImagePlus(ImagePlus imp, String hologramTitle) {
        if (imp == null) {
            return;
        }
        activeSourceImage = imp.duplicate();
        if (activeSourceImage.getStackSize() > 1) {
            if (activeSourceImage.isHyperStack()) {
                activeSourceImage.setPosition(1, 1, 1);
            } else {
                activeSourceImage.setSlice(1);
            }
        }
        ImageProcessor gray = activeSourceImage.getProcessor().convertToFloatProcessor();
        state.setHologramImage(new ImagePlus(hologramTitle, gray.duplicate()));
        srcWidth = gray.getWidth();
        srcHeight = gray.getHeight();
        state.setFourierImage(null);
        state.setAmplitudeImage(null);
        state.setPhaseImage(null);
        currentFieldRe = null;
        currentFieldIm = null;
        currentFieldW = 0;
        speckleState.clear();
        currentFieldH = 0;
        debugLog("Loaded image: title=\"" + imp.getTitle() + "\" size=" + srcWidth + "x" + srcHeight
            + " stack=" + activeSourceImage.getStackSize());
        debugLogOutputs("After image load");
        IJ.showStatus("HoloBio: hologram linked.");
    }

    private void onApplyMvp() {
        debugLog("Apply clicked.");
        debugLogSelectionSnapshot("Apply snapshot");
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
            debugLog("Module: Phase Compensation (Apply propagation only).");
            // In Phase Compensation module, global Apply means "apply propagation options"
            // over the current compensated field (Python PP style), not "run compensation" again.
            _apply_propagation(propOptionsPc, "Angular Spectrum");
        }
    }

    private void onReset() {
        if (!HoloBioUiStyle.confirm(null, "Reset DHM",
                "Clear the linked hologram and all reconstructed outputs?")) {
            return;
        }
        debugLog("Reset clicked.");
        state.setHologramImage(null);
        state.setFourierImage(null);
        state.setAmplitudeImage(null);
        state.setPhaseImage(null);
        activeSourceImage = null;
        currentFieldRe = null;
        currentFieldIm = null;
        currentFieldW = 0;
        speckleState.clear();
        currentFieldH = 0;
        debugLogOutputs("After reset");
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

    /**
     * Save the current complex field as a complex64 .npy, cropped to the hologram's own
     * footprint exactly like the Amplitude/Phase images — the working canvas is padded to a
     * power of two, and the padding is not part of the reconstruction.
     */
    private void saveComplexField() {
        float[] re = currentFieldRe, im = currentFieldIm;
        int w = currentFieldW, h = currentFieldH;
        if (re == null || im == null || w <= 0 || h <= 0) {
            HoloBioNpy.saveComplexDialog("complex_field", null);
            return;
        }
        boolean padded = srcWidth > 0 && srcHeight > 0 && (w != srcWidth || h != srcHeight)
                && padOffsetX >= 0 && padOffsetY >= 0
                && padOffsetX + srcWidth <= w && padOffsetY + srcHeight <= h;
        if (padded) {
            float[] cr = new float[srcWidth * srcHeight];
            float[] ci = new float[srcWidth * srcHeight];
            for (int y = 0; y < srcHeight; y++) {
                System.arraycopy(re, (y + padOffsetY) * w + padOffsetX, cr, y * srcWidth, srcWidth);
                System.arraycopy(im, (y + padOffsetY) * w + padOffsetX, ci, y * srcWidth, srcWidth);
            }
            re = cr; im = ci; w = srcWidth; h = srcHeight;
        }
        HoloBioNpy.saveComplexDialog("complex_field", re, im, w, h);
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

    private boolean openOutputImageInFiji(ImagePlus image, String role) {
        if (image == null) {
            return false;
        }
        String title = "HoloBio " + role;
        ImagePlus existing = WindowManager.getImage(title);
        if (existing != null) {
            existing.changes = false;
            existing.close();
        }
        ImagePlus out = image.duplicate();
        out.deleteRoi();
        out.setTitle(title);
        HoloBioFijiUi.showImagePlus(out);
        return true;
    }

    private void saveFtImage() {
        ImagePlus ft = state.getFourierImage();
        if (ft == null && state.getHologramImage() != null) {
            try {
                ft = computeLogPowerSpectrum(state.getHologramImage());
                state.setFourierImage(ft.duplicate());
            } catch (RuntimeException ex) {
                HoloBioFijiUi.message("HoloBio", "Could not build FT image for saving: " + ex.getMessage());
                return;
            }
        }
        saveStateImage(ft, "FT", "No Fourier image to save. Run Select +1 ROI or compensation first.");
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
    private void run_phase_compensation() {
        debugLog("Compensate clicked.");
        if (!state.hasHologram()) {
            HoloBioFijiUi.message("HoloBio", "Use an active image first.");
            return;
        }
        if (!readParamsFromPhaseCompPanel()) {
            return;
        }

        FloatProcessor src = state.getHologramImage().getProcessor().convertToFloatProcessor();
        FloatProcessor padded = padToCompensationCanvas(src);
        int cw = padded.getWidth();
        int ch = padded.getHeight();
        int len = cw * ch;
        float[] holoPx = (float[]) padded.getPixels();
        double[] inp = meanRemovedDouble(holoPx, len);

        HoloBioCompensationAlgorithms.RoiRect roiRect =
            HoloBioCompensationAlgorithms.autoFirstOrderSpectrumRoi(inp, cw, ch);
        Roi previewRoi = roiFromCompensationRect(roiRect);
        debugLog("Compensation method selection: " +
            (rbPcMethodTu != null && rbPcMethodTu.isSelected() ? "CFS" :
            rbPcMethodVl != null && rbPcMethodVl.isSelected() ? "Vortex-Legendre" : "ERS")
            + " | filter=Automatic (Rectangle) | canvas=" + cw + "x" + ch);
        IJ.showStatus("HoloBio: Compensation filter -> Automatic (Rectangle)");

        float[] previewSpectrum = holoPx.clone();
        subtractMeanInPlace(previewSpectrum, len);
        updateMaskedFourierPreview(previewSpectrum, cw, ch, previewRoi);
        double lam = params.getWavelengthUm();
        double dx = params.getPixelPitchXUm();
        double dy = params.getPixelPitchYUm();

        int ersS = compSettings.getErsSearchSize();
        double ersSt = compSettings.getErsStep();
        HoloBioCompensationAlgorithms.CompensationResult result;
        try {
            if (rbPcMethodTu != null && rbPcMethodTu.isSelected()) {
                result = HoloBioCompensationAlgorithms.cfs(inp, cw, ch, lam, dx, dy, roiRect,
                    compSettings.getCfsStepWindow(), compSettings.getCfsGridSize());
            } else if (rbPcMethodVl != null && rbPcMethodVl.isSelected()) {
                int legendreLimit = compSettings.getVlLimit();
                // Python vortexLegendre uses arr_hologram as-is (no mean subtraction; ERS/CFS subtract internally).
                double[] holoContent = floatRegionToDouble(
                        extractPaddedRegionFloat(holoPx, cw, ch, padOffsetX, padOffsetY, srcWidth, srcHeight));
                debugLog(String.format("VL settings: limit=%d piston=%s pca=%s",
                        legendreLimit, compSettings.isVlPiston(), compSettings.isVlPca()));
                result = HoloBioCompensationAlgorithms.vortexLegendre(holoContent, srcWidth, srcHeight, lam, dx, dy,
                    roiRect, legendreLimit, ersS, ersSt, compSettings.isVlPiston(), compSettings.isVlPca());
            } else {
                result = HoloBioCompensationAlgorithms.ers(inp, cw, ch, lam, dx, dy, roiRect, ersS, ersSt);
            }
        } catch (RuntimeException ex) {
            HoloBioFijiUi.message("HoloBio", "Phase compensation failed: " + ex.getMessage());
            IJ.handleException(ex);
            return;
        }

        HoloBioCompensationAlgorithms.ComplexField f = result.field;
        int fw = f.width;
        int fh = f.height;
        int flen = fw * fh;
        float[] re = new float[flen];
        float[] im = new float[flen];
        for (int i = 0; i < flen; i++) {
            re[i] = (float) f.re[i];
            im[i] = (float) f.im[i];
        }
        setCurrentField(re, im, fw, fh);
        // VL already letterboxes the square crop into the hologram frame. Pasting that
        // onto the POT compensation canvas and cropToSource()'ing it again shifted the
        // phase image relative to Python / QPI ROI coordinates.
        if (fw == srcWidth && fh == srcHeight) {
            FloatProcessor amp = amplitudeToDisplay(re, im, fw, fh);
            FloatProcessor phs = phaseToDisplay(re, im, fw, fh);
            state.setAmplitudeImage(new ImagePlus("Amplitude", amp));
            state.setPhaseImage(new ImagePlus("Phase", phs));
        } else {
            renderCurrentFieldWithoutPropagation(cw, ch);
        }
        debugLog(String.format("Compensation done. fx=%.3f fy=%.3f", result.fx, result.fy));
        debugLogOutputs("After compensation");
        update_right_view();
        IJ.showStatus("HoloBio: Phase compensation completed (fx=" + String.format("%.1f", result.fx) + ", fy=" + String.format("%.1f", result.fy) + ").");
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

    /** Masked spectrum preview; {@code holoPixels} should already be mean-removed to match the compensation path. */
    private void updateMaskedFourierPreview(float[] holoPixels, int cw, int ch, Roi roi) {
        FloatProcessor fp = new FloatProcessor(cw, ch, holoPixels);
        ImagePlus filteredFt = computeFijiFftDisplayFromFloat(fp, "Filtered FT (log)");
        ImageProcessor ip = filteredFt.getProcessor();
        applyMaskOutsideRoi(ip, roi);
        filteredFt.setProcessor(ip.convertToByteProcessor());
        filteredFt.setRoi((Roi) roi.clone());
        state.setFourierImage(filteredFt);
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

    /** Hologram pixels inside the pad (exclude zero border), matching Python {@code arr_hologram}. */
    private static float[] extractPaddedRegionFloat(float[] pad, int cw, int ch,
                                                   int offX, int offY, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int srcOff = (offY + y) * cw + offX;
            System.arraycopy(pad, srcOff, out, y * w, w);
        }
        return out;
    }

    /** Build an ImageJ ROI from algorithm rectangle (x2,y2 exclusive upper bounds). */
    private static Roi roiFromCompensationRect(HoloBioCompensationAlgorithms.RoiRect r) {
        int rw = r.x2 - r.x1;
        int rh = r.y2 - r.y1;
        return new Roi(r.x1, r.y1, rw, rh);
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
        setCurrentFieldFromImageSize(ps.re, ps.im, ps.width, ps.height);
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
            if (activeSourceImage == null) {
                HoloBioFijiUi.message("HoloBio", "Use an active image first, or set Frames source to Folder.");
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

    private void renderCurrentFieldWithoutPropagation() {
        renderCurrentFieldWithoutPropagation(0, 0);
    }

    /**
     * Renders amp/phase views. When {@code canvasW/H > 0}, embeds the field at the hologram pad offset
     * before {@link #cropToSource} so display matches Python (compensated M×M, not misaligned pad coords).
     */
    private void renderCurrentFieldWithoutPropagation(int canvasW, int canvasH) {
        if (currentFieldRe == null || currentFieldIm == null || currentFieldW <= 0 || currentFieldH <= 0) {
            return;
        }
        int w = currentFieldW;
        int h = currentFieldH;
        FloatProcessor amp = amplitudeToDisplay(currentFieldRe, currentFieldIm, w, h);
        FloatProcessor phs = phaseToDisplay(currentFieldRe, currentFieldIm, w, h);
        if (canvasW > 0 && canvasH > 0) {
            amp = embedFieldOnCompensationCanvas(amp, w, h, canvasW, canvasH);
            phs = embedFieldOnCompensationCanvas(phs, w, h, canvasW, canvasH);
        }
        state.setAmplitudeImage(new ImagePlus("Amplitude", cropToSource(amp)));
        state.setPhaseImage(new ImagePlus("Phase", cropToSource(phs)));
    }

    private FloatProcessor embedFieldOnCompensationCanvas(FloatProcessor field, int fieldW, int fieldH,
                                                          int canvasW, int canvasH) {
        FloatProcessor canvas = new FloatProcessor(canvasW, canvasH);
        for (int y = 0; y < fieldH; y++) {
            int cy = padOffsetY + y;
            if (cy < 0 || cy >= canvasH) {
                continue;
            }
            for (int x = 0; x < fieldW; x++) {
                int cx = padOffsetX + x;
                if (cx >= 0 && cx < canvasW) {
                    canvas.setf(cx, cy, field.getf(x, y));
                }
            }
        }
        return canvas;
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
        FloatProcessor padded = padToCompensationCanvas(src);
        int cw = padded.getWidth();
        int ch = padded.getHeight();
        float[] px = (float[]) padded.getPixels();
        float[] re = new float[cw * ch];
        float[] im = new float[cw * ch];
        if (phaseOnlyField) {
            // Match HoloBio Python NP path: coherent grayscale -> phase map -> U = exp(i*phi), phi in [0, 2pi).
            FloatProcessor src8 = (FloatProcessor) src.convertToByteProcessor().convertToFloatProcessor();
            FloatProcessor padded8 = padToCompensationCanvas(src8);
            float[] p8 = (float[]) padded8.getPixels();
            for (int i = 0; i < re.length; i++) {
                double phi = (p8[i] / 255.0) * (2.0 * Math.PI);
                re[i] = (float) Math.cos(phi);
                im[i] = (float) Math.sin(phi);
            }
            debugLog("Initialized NP field from coherent image as phase-only complex field.");
        } else {
            for (int i = 0; i < re.length; i++) {
                re[i] = px[i];
                im[i] = 0f;
            }
        }
        setCurrentField(re, im, cw, ch);
    }

    private void _propagate_current_field(PropagationUi propUi, String method) {
        if (currentFieldRe == null || currentFieldIm == null || currentFieldW <= 0 || currentFieldH <= 0) {
            HoloBioFijiUi.message("HoloBio", "No reconstructed field is available.");
            return;
        }
        if (propUi == null) {
            HoloBioFijiUi.message("HoloBio", "Propagation options are not initialized.");
            return;
        }

        float[] re = currentFieldRe.clone();
        float[] im = currentFieldIm.clone();
        int w = currentFieldW;
        int h = currentFieldH;

        String mode = propUi.mode();
        if ("Autofocus".equals(mode)) {
            double zMin = propUi.autofocusZMinUm();
            double zMax = propUi.autofocusZMaxUm();
            if (zMin <= 0.0) {
                HoloBioFijiUi.message("HoloBio", "Autofocus z min must be greater than zero.");
                return;
            }
            if (zMax <= zMin) {
                zMax = zMin + 1.0;
            }
            double bestZ = runAutofocus(re, im, w, h, zMin, zMax, method, propUi.autofocusMetric());
            propagateSelectedAlgorithmInPlace(re, im, w, h, bestZ, method);
            IJ.showStatus(String.format("HoloBio: Autofocus selected z=%.2f um", bestZ));
        } else if ("Z-scan".equals(mode)) {
            double zStart = propUi.effectiveZScanStartUm();
            if (zStart <= 0.0) {
                HoloBioFijiUi.message("HoloBio", "Z-scan start z must be greater than zero.");
                return;
            }
            propagateSelectedAlgorithmInPlace(re, im, w, h, zStart, method);
        } else {
            double zEff = propUi.effectiveZFixedUm();
            if (zEff <= 0.0) {
                HoloBioFijiUi.message("HoloBio", "Fixed propagation z must be greater than zero.");
                return;
            }
            propagateSelectedAlgorithmInPlace(re, im, w, h, zEff, method);
        }

        FloatProcessor amp = amplitudeToDisplay(re, im, w, h);
        FloatProcessor phs = phaseToDisplay(re, im, w, h);
        state.setAmplitudeImage(new ImagePlus("Amplitude", cropToSource(amp)));
        state.setPhaseImage(new ImagePlus("Phase", cropToSource(phs)));
        debugLogOutputs("After propagation (" + method + ", mode=" + mode + ")");
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
        if ("QPI".equals(selectedOption) || "Bio-Analysis".equals(selectedOption)) apply_QPI();
        if ("Speckle".equals(selectedOption)) apply_speckle();
        if ("Speckle Filter".equals(selectedOption)) apply_speckle_filter();
    }

    private void apply_QPI() {
        debugLog("Tools: QPI dialog requested.");
        HoloBioFijiUi.showQpiDialog(dialogOwnerOrNull(), createToolInputs());
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

    private HoloBioToolInputs createToolInputs() {
        return HoloBioToolInputs.fromState(state, currentFieldRe, currentFieldIm, currentFieldW, currentFieldH,
            params, readLateralMagnificationForTools());
    }

    private double readLateralMagnificationForTools() {
        if (isModulePhaseShift()) {
            return propOptionsPs.getLateralMagnification();
        }
        if (isModuleNumericalProp()) {
            return propOptionsNp.getLateralMagnification();
        }
        return propOptionsPc.getLateralMagnification();
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

    /**
     * Fourier visualization: mean-removed padded hologram, then {@code log1p(|fftshift(fft2)|)} via {@link HoloBioFftDisplay}
     * (same layout as compensation / Python PP).
     */
    private ImagePlus computeLogPowerSpectrum(ImagePlus source) {
        ImageProcessor processor = source.getProcessor().convertToFloatProcessor();
        FloatProcessor fp = (FloatProcessor) processor.duplicate();
        FloatProcessor padded = padToCompensationCanvas(fp);
        float[] p = (float[]) padded.getPixels();
        subtractMeanInPlace(p, p.length);
        return computeFijiFftDisplayFromFloat(padded, "Fourier Transform (log)");
    }

    /**
     * Log spectrum image for ROI drawing; uses {@link HoloBioFftDisplay} so pixel indices match
     * {@link HoloBioCompensationAlgorithms} (Fiji {@code FFT.forward} uses a square FHT canvas and would misalign rects).
     */
    private ImagePlus computeFijiFftDisplayFromFloat(FloatProcessor src, String outTitle) {
        int w = src.getWidth();
        int h = src.getHeight();
        ByteProcessor bp = HoloBioFftDisplay.generateFftLogDisplayRect(src, w, h);
        return new ImagePlus(outTitle, bp);
    }

    /**
     * Pads the hologram to the next power-of-two width and height (independently), centered with zeros.
     * Matches HoloBio Python PP using {@code np.fft.fft2} on the loaded array shape up to radix-2 padding per axis.
     */
    private FloatProcessor padToCompensationCanvas(FloatProcessor src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int cw = nextPowerOfTwo(w);
        int ch = nextPowerOfTwo(h);
        padOffsetX = (cw - w) / 2;
        padOffsetY = (ch - h) / 2;
        srcWidth = w;
        srcHeight = h;

        FloatProcessor dst = new FloatProcessor(cw, ch);
        dst.insert(src, padOffsetX, padOffsetY);
        return dst;
    }

    /** Legacy square pad (same max side); kept for callers that still require a square canvas. */
    private FloatProcessor padToFhtSize(FloatProcessor src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int maxDim = Math.max(w, h);
        int n = nextPowerOfTwo(maxDim);
        padSize = n;
        padOffsetX = (n - w) / 2;
        padOffsetY = (n - h) / 2;
        srcWidth = w;
        srcHeight = h;

        FloatProcessor dst = new FloatProcessor(n, n);
        dst.insert(src, padOffsetX, padOffsetY);
        return dst;
    }

    private int nextPowerOfTwo(int value) {
        int n = 1;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }

    private FloatProcessor cropToSource(FloatProcessor padded) {
        if (srcWidth <= 0 || srcHeight <= 0) {
            return padded;
        }
        int cw = padded.getWidth();
        int ch = padded.getHeight();
        FloatProcessor out = new FloatProcessor(srcWidth, srcHeight);
        for (int y = 0; y < srcHeight; y++) {
            for (int x = 0; x < srcWidth; x++) {
                int px = x + padOffsetX;
                int py = y + padOffsetY;
                if (px >= 0 && py >= 0 && px < cw && py < ch) {
                    out.setf(x, y, padded.getf(px, py));
                }
            }
        }
        return out;
    }

    private void applyMaskOutsideRoi(ImageProcessor ip, Roi roi) {
        int w = ip.getWidth();
        int h = ip.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!roi.contains(x, y)) {
                    ip.setf(x, y, 0f);
                }
            }
        }
    }

    /** Frames from the chosen source. requiredFrames = 0 means all frames (at least 3). */
    private List<FloatProcessor> collectPhaseShiftFrames(int requiredFrames) {
        if (SRC_FOLDER.equals(psSourceBox.getSelectedItem())) {
            return folderFrames(tfPsFolder.getText(), tfPsFilter.getText(), requiredFrames, "Frames", null);
        }
        if (activeSourceImage == null) {
            HoloBioFijiUi.message("HoloBio", "Use an active image first, or set Frames source to Folder.");
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
        speckleState.setOriginalField(currentFieldRe, currentFieldIm);
    }

    private void setCurrentFieldFromImageSize(float[] re, float[] im, int w, int h) {
        if (w == h && HoloBioRectFft.isPowerOfTwo(w) && re.length == w * h) {
            setCurrentField(re, im, w, h);
            srcWidth = w;
            srcHeight = h;
            padSize = w;
            padOffsetX = 0;
            padOffsetY = 0;
            return;
        }
        int n = nextPowerOfTwo(Math.max(w, h));
        padSize = n;
        padOffsetX = (n - w) / 2;
        padOffsetY = (n - h) / 2;
        srcWidth = w;
        srcHeight = h;
        float[] outRe = new float[n * n];
        float[] outIm = new float[n * n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int srcIdx = y * w + x;
                int dstIdx = (y + padOffsetY) * n + (x + padOffsetX);
                outRe[dstIdx] = re[srcIdx];
                outIm[dstIdx] = im[srcIdx];
            }
        }
        setCurrentField(outRe, outIm, n, n);
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

    private double runAutofocus(float[] baseRe, float[] baseIm, int w, int h, double zMinUm, double zMaxUm, String method, String metric) {
        int steps = 20;
        boolean tenengrad = metric != null && "Tenengrad".equals(metric);
        double bestZ = zMinUm;
        double bestScore = tenengrad ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i <= steps; i++) {
            double z = zMinUm + (zMaxUm - zMinUm) * i / (double) steps;
            float[] re = baseRe.clone();
            float[] im = baseIm.clone();
            propagateSelectedAlgorithmInPlace(re, im, w, h, z, method);
            double score = tenengrad ? metricTenengrad(re, im, w, h) : metricNormalizedVariance(re, im, w, h);
            if (tenengrad) {
                if (score > bestScore) {
                    bestScore = score;
                    bestZ = z;
                }
            } else {
                if (score < bestScore) {
                    bestScore = score;
                    bestZ = z;
                }
            }
        }
        return bestZ;
    }

    private double metricNormalizedVariance(float[] re, float[] im, int w, int h) {
        double mean = 0.0;
        int len = w * h;
        double[] amp = new double[len];
        for (int i = 0; i < len; i++) {
            amp[i] = Math.hypot(re[i], im[i]);
            mean += amp[i];
        }
        mean /= len;
        if (mean < 1e-12) return 0.0;
        double acc = 0.0;
        for (double v : amp) {
            double d = v - mean;
            acc += d * d;
        }
        return acc / mean;
    }

    /** Matches HoloBio Python {@code pyDHM_methods.metric_tenv}: variance of Sobel gradient energy G = Gx² + Gy². */
    private double metricTenengrad(float[] re, float[] im, int w, int h) {
        int len = w * h;
        double[] amp = new double[len];
        for (int i = 0; i < len; i++) {
            amp[i] = Math.hypot(re[i], im[i]);
        }
        int inner = (w - 2) * (h - 2);
        if (inner <= 0) {
            return 0.0;
        }
        double[] gEnergy = new double[inner];
        int k = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                double gx = -amp[idx - w - 1] + amp[idx - w + 1] - 2 * amp[idx - 1] + 2 * amp[idx + 1] - amp[idx + w - 1] + amp[idx + w + 1];
                double gy = -amp[idx - w - 1] - 2 * amp[idx - w] - amp[idx - w + 1] + amp[idx + w - 1] + 2 * amp[idx + w] + amp[idx + w + 1];
                gEnergy[k++] = gx * gx + gy * gy;
            }
        }
        double mean = 0.0;
        for (double v : gEnergy) {
            mean += v;
        }
        mean /= inner;
        double acc = 0.0;
        for (double v : gEnergy) {
            double d = v - mean;
            acc += d * d;
        }
        return acc / inner;
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
        if (w == h) {
            if ("Fresnel".equals(method)) {
                HoloBioPropagationMath.propagateFresnelInPlace(re, im, w, zUm, params.getWavelengthUm(), params.getPixelPitchXUm(), params.getPixelPitchYUm());
            } else {
                HoloBioPropagationMath.propagateAngularSpectrumInPlace(re, im, w, zUm, params.getWavelengthUm(), params.getPixelPitchXUm(), params.getPixelPitchYUm());
            }
        } else {
            if ("Fresnel".equals(method)) {
                HoloBioPropagationMath.propagateFresnelInPlaceRect(re, im, h, w, zUm, params.getWavelengthUm(), params.getPixelPitchXUm(), params.getPixelPitchYUm());
            } else {
                HoloBioPropagationMath.propagateAngularSpectrumInPlaceRect(re, im, h, w, zUm, params.getWavelengthUm(), params.getPixelPitchXUm(), params.getPixelPitchYUm());
            }
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
        private final JLabel lblFixedDist, lblZStart, lblZEnd, lblZStep, lblAfMin, lblAfMax;
        private final JComboBox<String> autofocusMetricBox;
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
            if (!scaleAxialWithMagnification) {
                lateralMagField.setEnabled(false);
                lateralMagField.setToolTipText(
                    "Not used for axial z in Numerical Propagation (Python NP applies distance in µm directly).");
            }
            HoloBioUiStyle.addLabelField(root, pc, HoloBioUiStyle.MAG_LABEL, lateralMagField);

            cardLayout = new CardLayout();
            cardPanel = HoloBioUiStyle.huggingCardPanel(cardLayout);
            cardPanel.setOpaque(false);

            JPanel fixedCard = new JPanel(new GridBagLayout());
            GridBagConstraints fc = HoloBioUiStyle.formGbc();
            fixedDistanceField = new JTextField("", FIELD_COLS);
            lblFixedDist = HoloBioUiStyle.addLabelField(fixedCard, fc,
                scaleAxialWithMagnification ? "Distance (µm)" : "Fixed z (µm)", fixedDistanceField);
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
            autofocusMetricBox = new JComboBox<>(new String[] {"Normalized Variance", "Tenengrad"});
            autofocusMetricBox.setLightWeightPopupEnabled(false);
            lblAfMin = HoloBioUiStyle.addLabelField(afCard, ac, "z min (µm)", afZMinField);
            lblAfMax = HoloBioUiStyle.addLabelField(afCard, ac, "z max (µm)", afZMaxField);
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
            double fixedUm = parseDistUm(fixedDistanceField, scaleAxialWithMagnification ? 5000.0 : 0.0);
            double z0Um = parseDistUm(zScanZMinField, 1000.0);
            double z1Um = parseDistUm(zScanZMaxField, 9000.0);
            double stepUm = parseDistUm(zScanStepField, 100.0);
            double af0Um = parseDistUm(afZMinField, 1000.0);
            double af1Um = parseDistUm(afZMaxField, 9000.0);
            distanceUnit = next;
            updatingDistanceUi = true;
            try {
                refreshDistanceLabels();
                fixedDistanceField.setText(fmtDist(fixedUm));
                zScanZMinField.setText(fmtDist(z0Um));
                zScanZMaxField.setText(fmtDist(z1Um));
                zScanStepField.setText(fmtDist(stepUm));
                afZMinField.setText(fmtDist(af0Um));
                afZMaxField.setText(fmtDist(af1Um));
            } finally {
                updatingDistanceUi = false;
            }
        }

        private void refreshDistanceLabels() {
            String u = distanceUnit;
            lblFixedDist.setText(scaleAxialWithMagnification ? "Distance (" + u + "):" : "Fixed z (" + u + "):");
            lblZStart.setText("z start (" + u + "):");
            lblZEnd.setText("z end (" + u + "):");
            lblZStep.setText("Step (" + u + "):");
            lblAfMin.setText("z min (" + u + "):");
            lblAfMax.setText("z max (" + u + "):");
        }

        private double umPerUnit() {
            return HoloBioUiStyle.umPerDistanceUnit(distanceUnit);
        }

        private double parseDistUm(JTextField tf, double fallbackUm) {
            return parseDoubleOr(tf, fallbackUm / umPerUnit()) * umPerUnit();
        }

        private String fmtDist(double um) {
            double v = um / umPerUnit();
            return Math.abs(v) >= 100 ? String.format("%.0f", v) : String.format("%.4g", v);
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

        /**
         * Effective propagation distance (µm) for fixed mode. Phase modules: {@code distance × M²} like Python
         * compensated-field propagation. Numerical propagation: fixed-z from this panel (no M²).
         */
        private double effectiveZFixedUm() {
            if (!scaleAxialWithMagnification) {
                return parseDistUm(fixedDistanceField, 0.0);
            }
            return parseDistUm(fixedDistanceField, 5000.0) * scaleImg();
        }

        /** First plane for Z-scan (µm). */
        private double effectiveZScanStartUm() {
            double z0 = parseDistUm(zScanZMinField, 1000.0);
            return scaleAxialWithMagnification ? z0 * scaleImg() : z0;
        }

        private double autofocusZMinUm() {
            double z0 = parseDistUm(afZMinField, 1000.0);
            return scaleAxialWithMagnification ? z0 * scaleImg() : z0;
        }

        private double autofocusZMaxUm() {
            double z0 = parseDistUm(afZMaxField, 9000.0);
            return scaleAxialWithMagnification ? z0 * scaleImg() : z0;
        }

        private String autofocusMetric() {
            String m = (String) autofocusMetricBox.getSelectedItem();
            return m != null ? m : "Normalized Variance";
        }
    }

}
