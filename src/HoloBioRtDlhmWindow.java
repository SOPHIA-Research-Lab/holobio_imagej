import com.github.sarxos.webcam.Webcam;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Real-time DLHM window.
 *
 * <p>Reuses the shared RT plumbing (frame source, display panels, FPS meters, recorder, grey
 * conversion) from the RT DHM module. Live reconstruction uses DLHM-rec
 * ({@link HoloBioDlhmMath#reconstructAS}), matching Python {@code main_DLHM_RT.py}'s
 * {@code propagate()} path with scale factor L/Z.
 */
public class HoloBioRtDlhmWindow extends JFrame {

    private static final String VIEW_HOLOGRAM  = "Hologram";
    private static final String VIEW_PHASE     = "Phase";
    private static final String VIEW_AMPLITUDE = "Amplitude";

    // --- Source ---
    private JRadioButton      rbCamera, rbVideo;
    private JComboBox<String> cbCamera;
    private JButton           btnRefresh, btnBrowse;
    private JLabel            lblVideoName;
    private File              videoFile;

    // --- Geometry / optics ---
    private JTextField        tfWavelength, tfPitch, tfL, tfZ, tfR;
    private JCheckBox         cbFixR;
    /** Block size averaged before reconstruction; 1 = full resolution. */
    private volatile int      binning = DEF_BINNING;
    private float[]           binBuf;
    private JComboBox<String> cbDistanceUnit;
    private JLabel            lblL, lblZ, lblMag, lblR;
    // --- Defaults for a typical bench setup. Distances are held in µm like the rest of
    // the model; the UI shows them in `distanceUnit`.
    private static final double DEF_WAVELENGTH_UM = 0.532;    // 532 nm
    private static final double DEF_PITCH_UM      = 1.85;
    /** 2x2 averaging by default: 4x less transform work, and 2x the pitch. */
    private static final int    DEF_BINNING       = 2;
    private static final double DEF_L_UM          = 45000.0;  // 4.5 cm
    private static final double DEF_R_UM          = 20000.0;  // 2 cm
    /** Follows from r = L − Z, so 2.5 cm. */
    private static final double DEF_Z_UM          = DEF_L_UM - DEF_R_UM;

    /** Display unit for L/Z/r; model stays in µm. */
    private String            distanceUnit = "cm";
    private boolean           updatingDistanceUi;
    /** >0 while a programmatic setText is still working through the event queue. */
    private int               programmaticEdits;

    // --- Viewports ---
    private HoloBioRtDisplayPanel pnlLeft, pnlRight;
    private JRadioButton          rbPhase, rbAmplitude;

    // --- Controls / status ---
    private JButton btnStart, btnStop, btnSnap;
    private JLabel  lblAcqFps, lblReconFps, lblVideoTime, lblStatus;

    // --- Video transport (same scrub bar as RT DHM) ---
    private HoloBioRtScrubBar scrubBar;
    private JButton           btnBarPlay, btnBarPause;
    private volatile boolean  pausedPlayback;
    /** Frame index requested by the scrub bar, or -1 when there is nothing to seek to. */
    private volatile int      pendingSeek = -1;

    // --- Recording ---
    private final HoloBioRtRecorder             recorder = new HoloBioRtRecorder();
    private JComboBox<HoloBioRtRecorder.Target> cbRecordTarget;
    private JButton                             btnRecStart, btnRecStop;
    private JLabel                              lblRec;
    // Complex-field recording (.npz of every reconstructed field)
    private final HoloBioFieldRecorder          fieldRec = new HoloBioFieldRecorder();
    /** Live exposure / gain for camera sources (hidden for video files). */
    private final HoloBioCameraSettingsPanel    cameraSettings =
            new HoloBioCameraSettingsPanel(this::setStatus);
    private JButton                             btnFieldStart, btnFieldStop;
    private JLabel                              lblFieldRec;

    private volatile boolean showPhase = true;
    private volatile BufferedImage lastHologram, lastAmplitude, lastPhase;

    private final HoloBioWebcamBackend webcam = new HoloBioWebcamBackend();
    private volatile HoloBioRtFrameSource source;

    private volatile boolean running;
    private Thread           processThread, captureThread;
    private volatile boolean reportedGeometry;

    private final Object frameLock = new Object();
    private float[]      readyGray, spareGray;
    private int          readyW, readyH;

    private final    HoloBioRtFpsMeter acqFps   = new HoloBioRtFpsMeter();
    private final    HoloBioRtFpsMeter reconFps = new HoloBioRtFpsMeter();
    private volatile double            sourceFps = 30.0;
    private volatile boolean           paceCapture;

    private final HoloBioDlhmParams params = new HoloBioDlhmParams();

    public HoloBioRtDlhmWindow() {
        super("HoloBio — Real-Time DLHM");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        buildUI();
        pack();
        setMinimumSize(new Dimension(900, 540));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                fieldRec.discard();
                stopProcessing();
                webcam.disconnect();
            }
        });
    }

    private void buildUI() {
        setLayout(new BorderLayout(0, 0));
        add(buildTopBar(),      BorderLayout.NORTH);
        add(buildSidebar(),     BorderLayout.WEST);
        add(buildDisplayArea(), BorderLayout.CENTER);
        add(buildStatusBar(),   BorderLayout.SOUTH);
        HoloBioUiStyle.polish(this);
    }

    private JPanel buildTopBar() {
        btnSnap = HoloBioUiStyle.secondaryButton("Snap to Fiji");
        btnSnap.setEnabled(false);
        btnSnap.setToolTipText("Send the current reconstruction view to Fiji.");
        btnSnap.addActionListener(e -> snapToFiji());
        return HoloBioUiStyle.buildHeader(
                "Real-Time DLHM",
                null,
                null,
                btnSnap);
    }

    private JScrollPane buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(HoloBioUiStyle.contentPad());

        JPanel[] sections = {
            buildCapturePanel(),
            buildOpticsPanel(),
            buildRecordPanel(),
        };
        for (int i = 0; i < sections.length; i++) {
            JPanel s = sections[i];
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
            side.add(s);
            // Exposure / gain sit right under Capture and push the rest down when shown.
            if (i == 0) side.add(cameraSettings);
            if (i < sections.length - 1) side.add(Box.createVerticalStrut(HoloBioUiStyle.SPACE_2));
        }

        JScrollPane scroll = new JScrollPane(
                side,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        HoloBioUiStyle.styleScroll(scroll);
        scroll.setPreferredSize(new Dimension(320, 400));
        scroll.setMinimumSize(new Dimension(240, 120));
        return scroll;
    }

    /** Camera / video plus Start / Stop — one group, same as RT DHM. */
    private JPanel buildCapturePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Capture"));
        GridBagConstraints c = HoloBioRtUtil.gbc(0);

        rbCamera = new JRadioButton("Camera", true);
        rbVideo  = new JRadioButton("Video");
        rbVideo.setToolTipText("MP4, AVI, MOV, MKV, TIFF image stack");
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbCamera); bg.add(rbVideo);
        rbCamera.addActionListener(e -> updateSourceState());
        rbVideo .addActionListener(e -> updateSourceState());
        JPanel srcRow = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_1, 0);
        srcRow.add(rbCamera);
        srcRow.add(rbVideo);
        p.add(srcRow, c); c.gridy++;

        cbCamera = new JComboBox<>();
        cbCamera.setMaximumRowCount(6);
        HoloBioUiStyle.stretchWidth(cbCamera);
        p.add(cbCamera, c); c.gridy++;

        btnRefresh = HoloBioUiStyle.secondaryButton("Refresh");
        btnRefresh.setToolTipText("Rescan connected cameras");
        btnRefresh.addActionListener(e -> refreshCameraList());
        btnBrowse = HoloBioUiStyle.secondaryButton("Browse…");
        btnBrowse.setToolTipText("MP4, AVI, MOV, MKV, or TIFF stack");
        btnBrowse.addActionListener(e -> browseVideo());
        HoloBioUiStyle.stretchWidth(btnRefresh);
        HoloBioUiStyle.stretchWidth(btnBrowse);
        p.add(HoloBioUiStyle.pairRow(btnRefresh, btnBrowse), c); c.gridy++;

        lblVideoName = HoloBioUiStyle.footerNote("No file chosen");
        p.add(lblVideoName, c); c.gridy++;

        btnStart = HoloBioUiStyle.primaryButton("Start");
        btnStop  = HoloBioUiStyle.secondaryButton("Stop");
        btnStop.setEnabled(false);
        btnStart.setToolTipText("Start live capture or video playback.");
        btnStop.setToolTipText("Stop capture or playback.");
        btnStart.addActionListener(e -> startProcessing());
        btnStop .addActionListener(e -> stopProcessing());
        HoloBioUiStyle.stretchWidth(btnStart);
        HoloBioUiStyle.stretchWidth(btnStop);
        p.add(HoloBioUiStyle.pairRow(btnStart, btnStop), c);

        refreshCameraList();
        updateSourceState();
        return p;
    }

    /**
     * Three columns: caption · control · optional info button. Keeping the info buttons
     * in their own column means they never widen the control column, so every control in
     * the section shares one left edge.
     */
    private JPanel buildOpticsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Optics"));

        final int lw = HoloBioUiStyle.FORM_LABEL_W_SM;  // caption column
        final int fw = HoloBioUiStyle.FORM_FIELD_W;     // same as RT DHM
        final int uw = HoloBioUiStyle.FORM_FIELD_W;

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;

        // Seed the model first, then render the fields from it, so the three distances
        // start out mutually consistent instead of relying on matching literals.
        params.setWavelengthUm(DEF_WAVELENGTH_UM);
        params.setPixelPitchUm(DEF_PITCH_UM);
        params.setFixR(false);
        params.setL(DEF_L_UM);
        params.setR(DEF_R_UM);          // Z becomes L − r

        tfWavelength = numericField(String.valueOf(DEF_WAVELENGTH_UM), fw);
        tfWavelength.setToolTipText("Illumination wavelength in micrometres, e.g. 0.532.");
        addOpticsRow(p, c, "λ (µm)", tfWavelength, lw, null, null);

        tfPitch = numericField(String.valueOf(DEF_PITCH_UM), fw);
        tfPitch.setToolTipText("Sensor pixel size in micrometres.");
        addOpticsRow(p, c, "Pixel pitch (µm)", tfPitch, lw, null, null);

        // The unit governs L, Z and r rather than being a measurement of its own, so it sits
        // in its own band instead of in line with the fields it does not belong to.
        addOpticsGap(p, c, HoloBioUiStyle.SPACE_3);

        cbDistanceUnit = new JComboBox<>(HoloBioUiStyle.DISTANCE_UNITS);
        cbDistanceUnit.setSelectedItem(distanceUnit);
        cbDistanceUnit.setToolTipText("Display unit for L, Z, and r. Values are stored in micrometres.");
        cbDistanceUnit.addActionListener(e -> onDistanceUnitChanged());
        HoloBioUiStyle.sizeField(cbDistanceUnit, uw);
        addOpticsRow(p, c, "Distance unit", cbDistanceUnit, lw, null, null);

        addOpticsGap(p, c, HoloBioUiStyle.SPACE_3);

        tfL = numericField(fmtDistance(params.getL()), fw);
        tfL.setToolTipText("L — distance between camera and source.");
        listenDistanceField(tfL);
        lblL = addOpticsRow(p, c, "L (" + distanceUnit + ")", tfL, lw, "L — source to camera",
                "Camera-to-source distance. Magnification is L/Z; reconstruction distance r = L − Z.");

        tfZ = numericField(fmtDistance(params.getZ()), fw);
        tfZ.setToolTipText("Z — distance between sample and source.");
        listenDistanceField(tfZ);
        lblZ = addOpticsRow(p, c, "Z (" + distanceUnit + ")", tfZ, lw, "Z — source to sample",
                "Sample-to-source distance. Magnification is L/Z; reconstruction distance r = L − Z.");

        // r is the third of a set where any two determine the rest: users often know
        // r and one of L/Z rather than both distances to the source.
        tfR = numericField(fmtDistance(params.getR()), fw);
        tfR.setToolTipText("r — reconstruction distance, camera to sample.");
        listenDistanceField(tfR);
        lblR = addOpticsRow(p, c, "r (" + distanceUnit + ")", tfR, lw, "r — reconstruction distance",
                "Camera-to-sample distance, r = L − Z. Type any two of L, Z and r and "
                + "the third follows. Editing r moves Z, or moves L when \"Fix r\" is on.");

        cbFixR = new JCheckBox("Fix r");
        cbFixR.setOpaque(false);
        cbFixR.setToolTipText("Hold r steady. Editing L then moves Z; editing Z or r moves L.");
        cbFixR.addActionListener(e -> onFixRToggled());
        addOpticsRow(p, c, "", cbFixR, lw, null, null);

        addOpticsRule(p, c);

        lblMag = readoutLabel("2.00×");
        addOpticsRow(p, c, "Magnification", lblMag, lw, null, null);

        // Algorithm is fixed for live DLHM — a stated value, not a combo of AS/DL/KR.
        addOpticsRow(p, c, "Algorithm", readoutLabel("DLHM"), lw,
                "DLHM-rec",
                "Lensless in-line reconstruction with radial undistort, matching Python "
                + "dlhm_rec(). Chosen over Angular Spectrum because AS is only valid while "
                + "|r|*(L/Z) <= N*pitch^2/lambda, which typical DLHM geometries exceed.");

        params.setAlgorithm("DL");
        refreshMagLabel();
        return p;
    }

    /** Caption · control (· info). Returns the caption so callers can retitle it later. */
    private static JLabel addOpticsRow(JPanel p, GridBagConstraints c, String caption,
            JComponent control, int labelW, String infoTitle, String infoTip) {
        c.gridx = 0;
        c.gridwidth = 1;
        c.insets = new Insets(0, 0, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3);
        JLabel lab = HoloBioUiStyle.fieldLabel(caption);
        lab.setForeground(HoloBioUiStyle.TEXT_MUTED);
        lab.setLabelFor(control);
        lab.setPreferredSize(new Dimension(labelW, HoloBioUiStyle.CONTROL_H));
        lab.setMinimumSize(new Dimension(labelW, HoloBioUiStyle.CONTROL_H));
        p.add(lab, c);

        c.gridx = 1;
        c.insets = new Insets(0, 0, HoloBioUiStyle.SPACE_1, 0);
        p.add(control, c);

        if (infoTip != null && !infoTip.isEmpty()) {
            c.gridx = 2;
            c.insets = new Insets(0, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1, 0);
            p.add(HoloBioUiStyle.infoButton(infoTitle, infoTip), c);
        }

        c.gridy++;
        c.gridx = 0;
        return lab;
    }

    private static void addOpticsGap(JPanel p, GridBagConstraints c, int height) {
        c.gridx = 0;
        c.gridwidth = 3;
        c.insets = new Insets(0, 0, 0, 0);
        p.add(Box.createVerticalStrut(height), c);
        c.gridwidth = 1;
        c.gridy++;
        c.gridx = 0;
    }

    /** Hairline between the editable rows and the values derived from them. */
    private static void addOpticsRule(JPanel p, GridBagConstraints c) {
        JPanel rule = new JPanel();
        rule.setOpaque(false);
        rule.setPreferredSize(new Dimension(1, 1));
        rule.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, HoloBioUiStyle.BORDER));
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(HoloBioUiStyle.SPACE_3, 0, HoloBioUiStyle.SPACE_3, 0);
        p.add(rule, c);
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy++;
        c.gridx = 0;
    }

    /** Numeric entry sized and aligned like the RT DHM optics fields. */
    private static JTextField numericField(String text, int width) {
        JTextField tf = HoloBioUiStyle.numericField(text);
        HoloBioUiStyle.sizeField(tf, width);
        return tf;
    }

    private static JLabel readoutLabel(String text) {
        JLabel lab = new JLabel(text);
        lab.setFont(HoloBioUiStyle.fontBodyBold());
        lab.setForeground(HoloBioUiStyle.TEXT);
        return lab;
    }

    private void listenDistanceField(final JTextField tf) {
        // Deferred: the handler writes into a sibling field, which Swing forbids from
        // inside a document notification.
        final Runnable sync = () -> SwingUtilities.invokeLater(() -> onDistanceEdited(tf));
        tf.addActionListener(e -> sync.run());
        tf.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
        });
    }

    /**
     * Keep L, Z and r consistent after one of them is typed into.
     *
     * <p>All three are editable; {@link HoloBioDlhmParams} decides which of the others
     * moves, matching HoloBio Python. Z is allowed to go negative when r exceeds L.
     */
    /**
     * Write a derived value into a field without that write bouncing back.
     *
     * <p>{@code setText} fires the field's own document listener, which defers a sync onto
     * the event queue. Clearing a plain flag in a finally block happens too early to stop
     * it, so the counter is decremented by a task queued *after* that one — and the sync
     * sees it still raised. Skipping equal text stops the ping-pong converging in the
     * first place.
     */
    private void setDistanceText(JTextField tf, String text) {
        if (text.equals(tf.getText())) {
            return;
        }
        programmaticEdits++;
        tf.setText(text);
        SwingUtilities.invokeLater(() -> programmaticEdits--);
    }

    private void onDistanceEdited(JTextField edited) {
        if (updatingDistanceUi || programmaticEdits > 0 || tfR == null) {
            return;
        }
        updatingDistanceUi = true;
        try {
            // Push only the field that was typed into; the params object moves the others
            // exactly as HoloBio Python does, then every field is written back from it.
            if (edited == tfR) {
                params.setR(parseDistanceUm(tfR, DEF_R_UM));
            } else if (edited == tfZ) {
                params.setZ(parseDistanceUm(tfZ, DEF_Z_UM));
            } else {
                params.setL(parseDistanceUm(tfL, DEF_L_UM));
            }
            if (edited != tfL) setDistanceText(tfL, fmtDistance(params.getL()));
            if (edited != tfZ) setDistanceText(tfZ, fmtDistance(params.getZ()));
            if (edited != tfR) setDistanceText(tfR, fmtDistance(params.getR()));
        } finally {
            updatingDistanceUi = false;
        }
        refreshMagLabel();
    }

    private void onFixRToggled() {
        // Re-read the fields first so r latches from what is on screen, not a stale model.
        params.setFixR(false);
        params.setL(parseDistanceUm(tfL, DEF_L_UM));
        params.setZ(parseDistanceUm(tfZ, DEF_Z_UM));
        params.setFixR(cbFixR.isSelected());
        updatingDistanceUi = true;
        try {
            setDistanceText(tfR, fmtDistance(params.getR()));
        } finally {
            updatingDistanceUi = false;
        }
        refreshMagLabel();
    }

    private JPanel buildRecordPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Record"));
        GridBagConstraints c = HoloBioRtUtil.gbc(0);

        // DLHM has no sideband FT product yet; keep Phase / Amplitude only for now.
        cbRecordTarget = new JComboBox<>(new HoloBioRtRecorder.Target[] {
                HoloBioRtRecorder.Target.PHASE,
                HoloBioRtRecorder.Target.AMPLITUDE
        });
        cbRecordTarget.setSelectedItem(HoloBioRtRecorder.Target.PHASE);
        HoloBioUiStyle.stretchWidth(cbRecordTarget);
        p.add(cbRecordTarget, c); c.gridy++;

        btnRecStart = HoloBioUiStyle.secondaryButton("Start recording");
        btnRecStop  = HoloBioUiStyle.primaryButton("Stop and save…");
        btnRecStop.setEnabled(false);
        btnRecStart.addActionListener(e -> startRecording());
        btnRecStop .addActionListener(e -> stopRecording());
        HoloBioUiStyle.stretchWidth(btnRecStart);
        HoloBioUiStyle.stretchWidth(btnRecStop);
        p.add(btnRecStart, c); c.gridy++;
        p.add(btnRecStop,  c); c.gridy++;

        // Every reconstructed complex field, streamed to one .npz until Stop.
        btnFieldStart = HoloBioUiStyle.secondaryButton("Record complex fields");
        btnFieldStop  = HoloBioUiStyle.primaryButton("Stop and save fields…");
        btnFieldStop.setEnabled(false);
        btnFieldStart.setToolTipText("Save every reconstructed complex field (complex64) into one .npz, "
                + "opened with np.load(). Recording continues until Stop.");
        btnFieldStart.addActionListener(e -> startFieldRecording());
        btnFieldStop .addActionListener(e -> stopFieldRecording());
        HoloBioUiStyle.stretchWidth(btnFieldStart);
        HoloBioUiStyle.stretchWidth(btnFieldStop);
        c.insets = new Insets(HoloBioUiStyle.SPACE_2, 0, HoloBioUiStyle.SPACE_1, 0);
        p.add(btnFieldStart, c); c.gridy++;
        c.insets = new Insets(0, 0, HoloBioUiStyle.SPACE_1, 0);
        p.add(btnFieldStop,  c); c.gridy++;
        lblFieldRec = HoloBioUiStyle.recordLabel();
        p.add(lblFieldRec, c); c.gridy++;

        lblRec = HoloBioUiStyle.recordLabel();
        p.add(lblRec, c);
        return p;
    }

    private JPanel buildDisplayArea() {
        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        JPanel inView = buildInputView();
        JPanel outView = buildOutputView();
        // Their headers differ in height, which otherwise makes one image taller.
        HoloBioUiStyle.matchHeaderHeights(inView, outView);
        row.add(inView);
        row.add(outView);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(HoloBioUiStyle.emptyPad(
            HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3));
        wrap.add(row, BorderLayout.CENTER);
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(buildFpsBar());
        south.add(buildScrubBar());
        wrap.add(south, BorderLayout.SOUTH);
        return wrap;
    }

    private JPanel buildScrubBar() {
        btnBarPlay  = HoloBioUiStyle.transportButton(HoloBioUiStyle.GlyphIcon.play(11),
                "Start or resume playback", HoloBioUiStyle.ButtonRole.PRIMARY);
        btnBarPause = HoloBioUiStyle.transportButton(HoloBioUiStyle.GlyphIcon.pause(11),
                "Hold the current frame (reconstruction stays on screen)",
                HoloBioUiStyle.ButtonRole.SECONDARY);
        btnBarPlay.addActionListener(e -> {
            pausedPlayback = false;
            if (!running) startProcessing();
        });
        btnBarPause.addActionListener(e -> pausedPlayback = true);

        JPanel transport = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 0);
        transport.add(btnBarPlay);
        transport.add(btnBarPause);

        scrubBar = new HoloBioRtScrubBar();
        scrubBar.setOnSeek(frame -> pendingSeek = frame);
        JPanel wrap = new JPanel(new BorderLayout(HoloBioUiStyle.SPACE_2, 0));
        wrap.setBorder(HoloBioUiStyle.emptyPad(0, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_2, HoloBioUiStyle.SPACE_3));
        wrap.add(transport, BorderLayout.WEST);
        wrap.add(scrubBar, BorderLayout.CENTER);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, HoloBioUiStyle.CONTROL_H_SM + HoloBioUiStyle.SPACE_2));
        return wrap;
    }

    private JPanel buildInputView() {
        pnlLeft = new HoloBioRtDisplayPanel(VIEW_HOLOGRAM);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(HoloBioUiStyle.sectionPad("Input"));
        wrap.add(pnlLeft, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildOutputView() {
        pnlRight = new HoloBioRtDisplayPanel(VIEW_PHASE);

        rbPhase     = new JRadioButton(VIEW_PHASE, true);
        rbAmplitude = new JRadioButton(VIEW_AMPLITUDE);
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbPhase); bg.add(rbAmplitude);
        rbPhase    .addActionListener(e -> onOutputViewChanged());
        rbAmplitude.addActionListener(e -> onOutputViewChanged());

        JPanel selector = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 2);
        selector.add(rbPhase);
        selector.add(rbAmplitude);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(HoloBioUiStyle.sectionPad("Reconstruction"));
        wrap.add(selector, BorderLayout.NORTH);
        wrap.add(pnlRight, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildFpsBar() {
        lblAcqFps    = HoloBioUiStyle.footerNote("Acq: 0.0 / 0.0");
        lblReconFps  = HoloBioUiStyle.footerNote("Recon: 0.0 / 0.0");
        lblVideoTime = HoloBioUiStyle.footerNote("Video: —");
        // BorderLayout.CENTER is left-aligned by default, so it ran straight into
        // the acquisition readout on its left.
        lblVideoTime.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3, HoloBioUiStyle.SPACE_1, HoloBioUiStyle.SPACE_3));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        bar.add(lblAcqFps,    BorderLayout.WEST);
        bar.add(lblVideoTime, BorderLayout.CENTER);
        bar.add(lblReconFps,  BorderLayout.EAST);
        return bar;
    }

    private JPanel buildStatusBar() {
        lblStatus = HoloBioUiStyle.footerNote("Ready — select a source and press Start.");
        return HoloBioUiStyle.statusBar(lblStatus);
    }

    private void onOutputViewChanged() {
        showPhase = rbPhase.isSelected();
        BufferedImage cached = showPhase ? lastPhase : lastAmplitude;
        if (cached != null) pnlRight.setImage(cached);
    }

    private void updateSourceState() {
        boolean isCamera = rbCamera.isSelected();
        cbCamera    .setEnabled(isCamera);
        btnRefresh  .setEnabled(isCamera);
        btnBrowse   .setEnabled(!isCamera);
        lblVideoName.setEnabled(!isCamera);
    }

    private void refreshCameraList() {
        List<Webcam> cams = webcam.getAvailableWebcams();
        cbCamera.removeAllItems();
        if (cams.isEmpty()) {
            cbCamera.addItem("No cameras detected");
        } else {
            for (Webcam cam : cams) cbCamera.addItem(cam.getName());
        }
    }

    private void browseVideo() {
        JFileChooser fc = new JFileChooser(HoloBioRtUtil.defaultSaveDir());
        fc.setDialogTitle("Open video or image stack");
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        videoFile = fc.getSelectedFile().getAbsoluteFile();
        lblVideoName.setText(videoFile.getName());
        lblVideoName.setToolTipText(videoFile.getAbsolutePath());
    }

    /** Labels only — safe to call from the reconstruction loop while fields are focused. */
    private void refreshMagLabel() {
        if (lblMag == null) return;
        lblMag.setText(String.format("%.2f×", params.getMagnification()));
    }

    private void onDistanceUnitChanged() {
        if (updatingDistanceUi) return;
        String next = (String) cbDistanceUnit.getSelectedItem();
        if (next == null || next.equals(distanceUnit)) return;
        double Lumb = parseDistanceUm(tfL, DEF_L_UM);
        double Zumb = parseDistanceUm(tfZ, DEF_Z_UM);
        double Rumb = parseDistanceUm(tfR, DEF_R_UM);
        distanceUnit = next;
        updatingDistanceUi = true;
        try {
            lblL.setText("L (" + distanceUnit + ")");
            lblZ.setText("Z (" + distanceUnit + ")");
            lblR.setText("r (" + distanceUnit + ")");
            setDistanceText(tfL, fmtDistance(Lumb));
            setDistanceText(tfZ, fmtDistance(Zumb));
            setDistanceText(tfR, fmtDistance(Rumb));
        } finally {
            updatingDistanceUi = false;
        }
        refreshMagLabel();
    }

    private double umPerDistanceUnit() {
        return HoloBioUiStyle.umPerDistanceUnit(distanceUnit);
    }

    private double parseDistanceUm(JTextField tf, double fallbackUm) {
        double disp = HoloBioRtUtil.parseDouble(tf, fallbackUm / umPerDistanceUnit());
        return disp * umPerDistanceUnit();
    }

    private String fmtDistance(double um) {
        double v = um / umPerDistanceUnit();
        return Math.abs(v) >= 100 ? String.format("%.0f", v) : String.format("%.3g", v);
    }

    private void applyParamsFromUi() {
        params.setWavelengthUm(HoloBioRtUtil.parseDouble(tfWavelength, DEF_WAVELENGTH_UM));
        // The reconstructor sees the binned grid, so it must see the binned pitch too.
        params.setPixelPitchUm(HoloBioRtUtil.parseDouble(tfPitch, DEF_PITCH_UM) * binning);
        // The three fields are already mutually consistent, so push L and Z straight in
        // with the r coupling switched off, then re-latch r from them.
        boolean fix = params.isFixR();
        params.setFixR(false);
        params.setL(parseDistanceUm(tfL, DEF_L_UM));
        params.setZ(parseDistanceUm(tfZ, DEF_Z_UM));
        params.setFixR(fix);
        params.setAlgorithm("DL");
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lblStatus.setText(msg));
    }

    // --- Recording -----------------------------------------------------------

    private void startRecording() {
        HoloBioRtRecorder.Target t =
                (HoloBioRtRecorder.Target) cbRecordTarget.getSelectedItem();
        recorder.start(t);
        btnRecStart.setEnabled(false);
        btnRecStop .setEnabled(true);
        cbRecordTarget.setEnabled(false);
        setStatus("Recording " + t + " — press Stop when finished.");
        updateRecIndicator();
    }

    private void startFieldRecording() {
        try {
            fieldRec.start();
        } catch (java.io.IOException ex) {
            setStatus("Could not start field recording: " + ex.getMessage());
            return;
        }
        btnFieldStart.setEnabled(false);
        btnFieldStop .setEnabled(true);
        lblFieldRec.setText("● FIELDS  0");
        setStatus("Recording complex fields — press Stop and save fields when finished.");
    }

    private void stopFieldRecording() {
        btnFieldStop.setEnabled(false);
        HoloBioFieldRecorder.Result r = fieldRec.stop();
        btnFieldStart.setEnabled(true);
        lblFieldRec.setText(" ");
        HoloBioFieldRecorder.saveAs(r, this::setStatus);
    }

    /** Called from the reconstruction thread after each frame. */
    private void captureField() {
        if (!fieldRec.isRecording()) return;
        fieldRec.add(HoloBioRtDlhmMath.lastField());
        final int n = fieldRec.frameCount(), d = fieldRec.droppedCount();
        SwingUtilities.invokeLater(() -> lblFieldRec.setText(
                String.format("● FIELDS  %d%s", n, d > 0 ? "  (" + d + " dropped)" : "")));
    }

    private void stopRecording() {
        recorder.stop();
        btnRecStart.setEnabled(true);
        btnRecStop .setEnabled(false);
        cbRecordTarget.setEnabled(true);
        lblRec.setText(" ");

        int n = recorder.frameCount();
        if (n == 0) {
            setStatus("Nothing was recorded — is the selected stream being displayed?");
            return;
        }
        double fps = recorder.measuredFps();

        JFileChooser fc = new JFileChooser(HoloBioRtUtil.defaultSaveDir());
        fc.setDialogTitle("Save recorded " + recorder.target());
        fc.setSelectedFile(new File(HoloBioRtUtil.defaultSaveDir(),
                "holobio_dlhm_" + recorder.target().name().toLowerCase() + ".mp4"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            recorder.discard();
            setStatus("Recording discarded.");
            return;
        }
        final File out = HoloBioRtUtil.realPath(fc.getSelectedFile());
        setStatus("Writing " + n + " frames…");

        Thread writer = new Thread(() -> {
            try {
                String what = recorder.save(out, fps);
                recorder.discard();
                setStatus("Saved " + what + " → " + out.getName());
            } catch (Exception ex) {
                setStatus("Save failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        ex.getMessage(), "Recording", JOptionPane.ERROR_MESSAGE));
            }
        }, "HoloBio-RTDLHM-save");
        writer.setDaemon(true);
        writer.start();
    }

    private void updateRecIndicator() {
        if (!recorder.isRecording()) {
            if (recorder.hitBudget()) {
                SwingUtilities.invokeLater(() -> {
                    lblRec.setText("Buffer full — stopped");
                    btnRecStart.setEnabled(true);
                    btnRecStop .setEnabled(true);
                });
            }
            return;
        }
        final String txt = String.format("● REC  %d frames", recorder.frameCount());
        SwingUtilities.invokeLater(() -> lblRec.setText(txt));
    }

    // --- Lifecycle -----------------------------------------------------------

    private void startProcessing() {
        if (running) return;
        pausedPlayback = false;
        btnStart.setEnabled(false);

        if (rbCamera.isSelected()) {
            int idx = Math.max(0, cbCamera.getSelectedIndex());
            setStatus("Connecting to camera " + idx + "…");
            try {
                source = HoloBioRtFrameSource.camera(webcam, idx);
            } catch (Exception ex) {
                setStatus("Camera error: " + ex.getMessage());
                btnStart.setEnabled(true);
                return;
            }
            adoptSourceRate();
            launchProcessing(source.description());
            cameraSettings.attach((String) cbCamera.getSelectedItem());
        } else {
            if (videoFile == null) {
                setStatus("No video chosen — press Browse.");
                btnStart.setEnabled(true);
                return;
            }
            String path = videoFile.getAbsolutePath();
            setStatus("Loading " + videoFile.getName() + "…");
            Thread loader = new Thread(() -> {
                HoloBioRtFrameSource opened;
                try {
                    opened = HoloBioRtFrameSource.file(path);
                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus(ex.getMessage());
                        btnStart.setEnabled(true);
                    });
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    source = opened;
                    adoptSourceRate();
                    launchProcessing(opened.description());
                });
            }, "VideoLoader");
            loader.setDaemon(true);
            loader.start();
        }
    }

    private void adoptSourceRate() {
        sourceFps   = source.nominalFps();
        paceCapture = source.needsPacing();
    }

    private void launchProcessing(String statusMsg) {
        running = true;
        btnStop.setEnabled(true);
        btnSnap.setEnabled(true);
        reportedGeometry = false;
        pendingSeek = -1;
        acqFps.reset();
        reconFps.reset();
        synchronized (frameLock) { readyGray = null; spareGray = null; }
        refreshFpsLabels();
        setStatus(statusMsg);

        processThread = new Thread(this::processLoop, "HoloBio-RTDLHM-recon");
        captureThread = new Thread(this::captureLoop, "HoloBio-RTDLHM-capture");
        processThread.setDaemon(true);
        captureThread.setDaemon(true);
        processThread.start();
        captureThread.start();
    }

    private void stopProcessing() {
        cameraSettings.detach();
        running = false;
        btnStart.setEnabled(true);
        btnStop .setEnabled(false);
        synchronized (frameLock) { frameLock.notifyAll(); }
        if (captureThread != null) {
            captureThread.interrupt();
            try { captureThread.join(1200); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (processThread != null) {
            processThread.interrupt();
            try { processThread.join(1200); } catch (InterruptedException ignored) {}
            processThread = null;
        }
        if (source != null) { source.close(); source = null; }
        setStatus("Stopped.");
    }

    private void captureLoop() {
        long nextDueNs = System.nanoTime();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int seekTo = pendingSeek;
                if (seekTo >= 0 && source != null) {
                    pendingSeek = -1;
                    try {
                        source.seekToFrame(seekTo);
                    } catch (IOException ex) {
                        setStatus("Seek failed: " + ex.getMessage());
                    }
                    nextDueNs = 0;
                }

                if (pausedPlayback) {
                    Thread.sleep(30);
                    continue;
                }

                if (paceCapture) {
                    long periodNs = (long) (1e9 / Math.max(sourceFps, 0.1));
                    long waitNs = nextDueNs - System.nanoTime();
                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (int) (waitNs % 1_000_000L));
                    long now = System.nanoTime();
                    nextDueNs = (now - nextDueNs > periodNs) ? now + periodNs : nextDueNs + periodNs;
                }

                BufferedImage frame = source != null ? source.next() : null;
                if (frame == null) {
                    if (paceCapture) {
                        SwingUtilities.invokeLater(() -> {
                            running = false;
                            btnStart.setEnabled(true);
                            btnStop .setEnabled(false);
                            lblStatus.setText("Video ended.");
                        });
                        return;
                    }
                    Thread.sleep(15);
                    continue;
                }

                int w = frame.getWidth(), h = frame.getHeight();
                float[] buf;
                synchronized (frameLock) { buf = spareGray; spareGray = null; }
                float[] gray = HoloBioRtUtil.toGray(frame, buf);

                BufferedImage img = HoloBioRtUtil.grayImage(gray, w, h);
                lastHologram = img;
                SwingUtilities.invokeLater(() -> pnlLeft.setImage(img));

                synchronized (frameLock) {
                    if (readyGray != null) spareGray = readyGray;
                    readyGray = gray; readyW = w; readyH = h;
                    frameLock.notifyAll();
                }
                if (acqFps.tick()) refreshFpsLabels();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                setStatus("Capture error: " + e.getMessage());
            }
        }
        synchronized (frameLock) { frameLock.notifyAll(); }
    }

    private void processLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                float[] gray; int w, h;
                synchronized (frameLock) {
                    while (running && readyGray == null) frameLock.wait(200);
                    if (!running) break;
                    if (readyGray == null) continue;
                    gray = readyGray; w = readyW; h = readyH;
                    readyGray = null;
                }

                applyParamsFromUi();
                SwingUtilities.invokeLater(this::refreshMagLabel);

                HoloBioRtRecorder.Target recTarget =
                        recorder.isRecording() ? recorder.target() : null;
                boolean wantPhase = showPhase;
                boolean needPhase = wantPhase || recTarget == HoloBioRtRecorder.Target.PHASE;
                boolean needAmp   = !wantPhase || recTarget == HoloBioRtRecorder.Target.AMPLITUDE;

                float[] src = gray;
                int srcW = w, srcH = h;
                int bin = binning;
                if (bin > 1 && w >= bin && h >= bin) {
                    srcW = w / bin;
                    srcH = h / bin;
                    int need = srcW * srcH;
                    if (binBuf == null || binBuf.length != need) binBuf = new float[need];
                    HoloBioRtUtil.bin(gray, w, h, bin, binBuf);
                    src = binBuf;
                }

                HoloBioRtDlhmMath.Frame result =
                        HoloBioRtDlhmMath.process(src, srcH, srcW, params, needAmp, needPhase);
                captureField();

                BufferedImage outputImg = wantPhase
                        ? HoloBioRtUtil.grayImage(result.phase,     result.width, result.height)
                        : HoloBioRtUtil.grayImage(result.amplitude, result.width, result.height);

                synchronized (frameLock) { spareGray = gray; }

                if (recTarget == HoloBioRtRecorder.Target.PHASE && result.phase != null) {
                    recorder.add(recTarget, result.phase, result.width, result.height);
                    updateRecIndicator();
                } else if (recTarget == HoloBioRtRecorder.Target.AMPLITUDE
                        && result.amplitude != null) {
                    recorder.add(recTarget, result.amplitude, result.width, result.height);
                    updateRecIndicator();
                }

                if (!reportedGeometry) {
                    reportedGeometry = true;
                    setStatus(String.format("Frame %d×%d  ·  DLHM-rec  ·  Mag=%.2f×",
                            w, h, params.getMagnification()));
                }

                if (wantPhase) lastPhase = outputImg; else lastAmplitude = outputImg;
                SwingUtilities.invokeLater(() -> pnlRight.setImage(outputImg));
                if (reconFps.tick()) refreshFpsLabels();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                setStatus("Frame error: " + e.getMessage());
            }
        }
    }

    private void refreshFpsLabels() {
        final String acq = String.format("Acq: %.1f / %.1f",
                acqFps.value(), sourceFps);
        final String rec = String.format("Recon: %.1f / %.1f",
                reconFps.value(), sourceFps);
        final String timeTxt = formatVideoTime();
        SwingUtilities.invokeLater(() -> {
            lblAcqFps.setText(acq);
            lblReconFps.setText(rec);
            if (lblVideoTime != null) lblVideoTime.setText(timeTxt);
            if (scrubBar != null) {
                HoloBioRtFrameSource src = source;
                if (src == null || src.isStill() || !src.needsPacing()) {
                    scrubBar.setProgress(0, 0, false);
                } else {
                    scrubBar.setProgress(src.deliveredCount(), src.knownFrameCount(), src.isSeekable());
                }
            }
        });
    }

    private String formatVideoTime() {
        HoloBioRtFrameSource src = source;
        if (src == null || !src.needsPacing() || src.isStill()) {
            return src != null && src.isStill() ? "Still" : "Video: —";
        }
        double pos = src.positionSec();
        double dur = src.durationSec();
        if (pos < 0) return "Video: —";
        if (dur > 0) {
            return String.format("Video: %.1f s / %.1f s", pos, dur);
        }
        return String.format("Video: %.1f s", pos);
    }

    private void snapToFiji() {
        BufferedImage in  = pnlLeft .getImage();
        BufferedImage out = pnlRight.getImage();
        if (in == null && out == null) { setStatus("No frame ready."); return; }
        if (in != null) {
            HoloBioFijiUi.showImagePlus(new ImagePlus("RT DLHM — Hologram", toProcessor(in)));
        }
        if (out != null) {
            String title = showPhase ? "RT DLHM — Phase" : "RT DLHM — Amplitude";
            HoloBioFijiUi.showImagePlus(new ImagePlus(title, toProcessor(out)));
        }
        setStatus("Snapped current views to Fiji.");
    }

    /**
     * Build an {@link ImageProcessor} straight from the {@link BufferedImage} raster instead of via
     * {@code new ColorProcessor(img)} (AWT {@code PixelGrabber}), which can silently produce a blank
     * image on some Java2D/GPU-driver setups.
     */
    private static ImageProcessor toProcessor(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            byte[] src = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
            byte[] px = new byte[w * h];
            System.arraycopy(src, 0, px, 0, Math.min(src.length, px.length));
            return new ByteProcessor(w, h, px, null);
        }
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        return new ColorProcessor(w, h, rgb);
    }
}
