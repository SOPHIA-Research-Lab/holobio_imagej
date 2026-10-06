import com.github.sarxos.webcam.Webcam;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.Locale;

/**
 * Real-time DHM window — off-axis sideband demodulation.
 *
 * Layout mirrors the HoloBio Python real-time GUI: two viewports side by side, each with a
 * radio selector choosing what it shows. The left viewport shows the input (hologram or its
 * Fourier transform), the right one the reconstruction (phase or amplitude). Only the two
 * selected products are computed per frame, so switching views changes the workload rather
 * than adding to it.
 *
 * Video input strategy:
 *   • TIFF image stack  → ImageJ native reader (no dialog)
 *   • Everything else   → FFmpeg pipe (MP4, AVI, MOV, MKV, …)
 *   Webcam input uses HoloBioWebcamBackend (Sarxos).
 */
public class HoloBioRtDhmWindow extends JFrame {

    private static final String FT_UNFILTERED        = "Unfiltered";
    private static final String FT_FILTERED          = "Filtered";
    private static final String FT_CIRCLE            = "Circular";
    private static final String FT_RECT              = "Rectangular";

    private static final String VIEW_HOLOGRAM  = "Hologram";
    private static final String VIEW_FOURIER   = "Fourier Transform";
    private static final String VIEW_PHASE     = "Phase Reconstruction";
    private static final String VIEW_AMPLITUDE = "Amplitude Reconstruction";

    // ── Source controls ───────────────────────────────────────────────────────
    private JRadioButton      rbCamera, rbVideo;
    private JComboBox<String> cbCamera;
    private final JComboBox<String> cbResolution = new JComboBox<>();
    private JButton           btnRefresh, btnBrowse;
    private JLabel            lblVideoName;
    private File              videoFile;

    // ── Physical parameters ───────────────────────────────────────────────────
    private JTextField tfWavelength, tfPixelX, tfPixelY, tfMagnification;
    /** Live intensity histogram of the raw hologram, under Optics. */
    private final HoloBioRtHistogram histogram = new HoloBioRtHistogram();

    // ── Viewports and their selectors ─────────────────────────────────────────
    private HoloBioRtDisplayPanel pnlLeft, pnlRight;
    private JRadioButton          rbHologram, rbFourier, rbPhase, rbAmplitude;
    private JComboBox<String>     cbCompMethod;
    private JComboBox<String>     cbFtView, cbFtShape;
    private JTextField            tfFtFactor, tfFtRadius;
    private JButton               btnBarPlay, btnBarPause;
    private volatile boolean      pausedPlayback;

    // ── Controls & status ─────────────────────────────────────────────────────
    private JButton btnStart, btnStop, btnSnap;
    private JLabel  lblAcqFps, lblReconFps, lblStatus, lblVideoTime;
    private JTextField tfStopAfterSec;
    private HoloBioRtScrubBar scrubBar;
    private volatile int pendingSeek = -1;

    // ── Live phase profile ─────────────────────────────────────────────────────
    private JToggleButton          btnProfile;
    private JRadioButton           rbProfLine, rbProfRect;
    private JButton                btnProfRecStart, btnProfRecStop, btnLoadRois, btnExportCurve;
    private JLabel                 lblProfRec;
    private final HoloBioRtProfileCsvRecorder csvRec = new HoloBioRtProfileCsvRecorder();
    private HoloBioRtProfileWindow profileWin;
    private volatile boolean       profileMode;
    private volatile HoloBioRtDhmMath.Frame lastProfileFrame;
    private volatile double lastDxUm = 3.75, lastDyUm = 3.75;
    /** Last line profiles for curve CSV export (plot curves: 1-D along line / rect midline). */
    private volatile java.util.List<HoloBioRtProfileWindow.Curve> lastPlotCurves =
            java.util.Collections.emptyList();
    private volatile java.util.List<String> lastCurveLabels = java.util.Collections.emptyList();
    /** Segments in image coordinates; read by the reconstruction thread every frame. */
    private final java.util.List<HoloBioRtDisplayPanel.ProfileLine> profileLines =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Color[] PROFILE_COLORS = {
        new Color(220,  50,  50), new Color( 40, 120, 220), new Color( 30, 160,  70),
        new Color(230, 140,  20), new Color(150,  60, 200), new Color(  0, 160, 160),
        new Color(200,  40, 120), new Color(130, 140,  20), new Color(140,  90,  40),
        new Color( 25,  45, 140), new Color(120, 200,  60), new Color( 80, 180, 240),
        new Color(210, 170,   0), new Color(255, 110,  90), new Color(100, 100, 100),
    };

    // ── Recording ─────────────────────────────────────────────────────────────
    private final HoloBioRtRecorder            recorder = new HoloBioRtRecorder();
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

    // ── View state, read by the processing thread ─────────────────────────────
    private volatile boolean showFourier = true;
    private volatile boolean showPhase   = true;

    // ── Spatial filter, in centred-spectrum bins ──────────────────────────────
    // Always auto-detected; these mirror the mask in force so the overlay can draw it.
    private volatile int filterCx, filterCy, filterRadius;
    private volatile int gridRows, gridCols;
    private JLabel  lblFilter;
    private JButton btnAutoFilter;

    // ── Most recent renders, so a view switch repaints without a new frame ────
    private volatile BufferedImage lastHologram, lastFourier, lastAmplitude, lastPhase;

    // ── Back-end state ────────────────────────────────────────────────────────
    private final HoloBioWebcamBackend webcam = new HoloBioWebcamBackend();
    private volatile HoloBioRtFrameSource source;

    private volatile boolean running;
    private Thread           processThread, captureThread;
    private volatile boolean reportedGeometry;
    private volatile boolean warnedNoCarrier;

    // ── Capture → reconstruction handoff (single slot, newest wins) ───────────
    private final Object frameLock = new Object();
    private float[]      readyGray, spareGray;
    private int          readyW, readyH;

    // ── Frame-rate meters ─────────────────────────────────────────────────────
    private final    HoloBioRtFpsMeter acqFps   = new HoloBioRtFpsMeter();
    private final    HoloBioRtFpsMeter reconFps = new HoloBioRtFpsMeter();
    private volatile double            sourceFps = 30.0;
    private volatile boolean           paceCapture;

    // ─────────────────────────────────────────────────────────────────────────

    public HoloBioRtDhmWindow() {
        super("HoloBio — Real-Time DHM");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        buildUI();
        pack();
        // Cap initial size so short screens still see the sidebar (it scrolls).
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(Math.max(getWidth(), 980), screen.width - 48);
        int h = Math.min(Math.max(getHeight(), 560), screen.height - 80);
        setSize(w, h);
        setMinimumSize(new Dimension(860, 480));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                fieldRec.discard();
                stopProcessing();
                webcam.disconnect();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI assembly
    // ─────────────────────────────────────────────────────────────────────────

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
            "Real-Time DHM",
            null,
            null,
            btnSnap
        );
    }

    private JScrollPane buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(HoloBioUiStyle.contentPad());

        JPanel[] sections = {
            buildCapturePanel(),
            buildOpticsPanel(),
            buildProfilePanel(),
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

    /** Camera / video plus Start / Stop — one group instead of Source + Playback. */
    private JPanel buildCapturePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Capture"));
        GridBagConstraints c = gbc(0);

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
        c.gridwidth = 2;
        p.add(srcRow, c); c.gridy++;

        cbCamera = new JComboBox<>();
        cbCamera.setMaximumRowCount(6);
        sizeControl(cbCamera);
        p.add(cbCamera, c); c.gridy++;
        cbCamera.addActionListener(e -> {
            if (cbCamera.getSelectedItem() != null) {
                HoloBioCameraModes.populate(cbResolution, (String) cbCamera.getSelectedItem());
            }
        });
        p.add(HoloBioCameraModes.row(cbResolution), c); c.gridy++;

        btnRefresh = HoloBioUiStyle.secondaryButton("Refresh");
        btnRefresh.setToolTipText("Rescan connected cameras");
        btnRefresh.addActionListener(e -> refreshCameraList());
        btnBrowse = HoloBioUiStyle.secondaryButton("Browse…");
        btnBrowse.setToolTipText("MP4, AVI, MOV, MKV, TIFF, PNG, JPEG");
        btnBrowse.addActionListener(e -> browseVideo());
        sizeControl(btnRefresh);
        sizeControl(btnBrowse);
        p.add(HoloBioUiStyle.pairRow(btnRefresh, btnBrowse), c); c.gridy++;

        lblVideoName = HoloBioUiStyle.footerNote("No file chosen");
        p.add(lblVideoName, c); c.gridy++;

        c.gridwidth = 1;
        tfStopAfterSec = new JTextField("0", 6);
        tfStopAfterSec.setToolTipText("0 = play to the end. Otherwise stop once video time reaches this many seconds.");
        HoloBioRtUtil.liveField(tfStopAfterSec, "Stop after", "s", HoloBioRtUtil.Rule.NON_NEGATIVE,
                null, this::setStatus);
        HoloBioUiStyle.addLabelField(p, c, "Stop after (s)", tfStopAfterSec,
            HoloBioUiStyle.FORM_LABEL_W_SM, HoloBioUiStyle.FORM_FIELD_W);

        btnStart = HoloBioUiStyle.primaryButton("Start");
        btnStop  = HoloBioUiStyle.secondaryButton("Stop");
        btnStop.setEnabled(false);
        btnStart.setToolTipText("Start live capture or video playback.");
        btnStop.setToolTipText("Stop capture or playback.");
        btnStart.addActionListener(e -> startProcessing());
        btnStop .addActionListener(e -> stopProcessing());
        sizeControl(btnStart);
        sizeControl(btnStop);
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        p.add(HoloBioUiStyle.pairRow(btnStart, btnStop), c);

        refreshCameraList();
        updateSourceState();
        return p;
    }

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
        fieldRec.add(HoloBioRtDhmMath.lastField());
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
            setStatus("Nothing was recorded — start processing, then Start recording.");
            return;
        }
        double fps = recorder.measuredFps();

        JFileChooser fc = new JFileChooser(HoloBioRtUtil.defaultSaveDir());
        fc.setDialogTitle("Save recorded " + recorder.target());
        fc.setSelectedFile(new File(HoloBioRtUtil.defaultSaveDir(),
                "holobio_" + recorder.target().name().toLowerCase() + ".mp4"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            recorder.discard();
            setStatus("Recording discarded.");
            return;
        }
        final File out = HoloBioRtUtil.realPath(fc.getSelectedFile());
        setStatus("Writing " + n + " frames…");

        // Encoding a long clip blocks for a while; keep it off the event thread
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
        }, "HoloBio-RTDHM-save");
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

    private JPanel buildOpticsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Optics"));
        GridBagConstraints c = gbc(0);
        c.gridy = 0;

        tfWavelength = new JTextField("0.633", 6);
        tfPixelX = new JTextField("3.75", 6);
        tfPixelY = new JTextField("3.75", 6);
        tfMagnification = new JTextField("", 6);
        tfWavelength.setToolTipText("Illumination wavelength in micrometres, e.g. 0.633.");
        tfPixelX.setToolTipText("Sensor pixel size X in micrometres. Do not divide by M.");
        tfPixelY.setToolTipText("Sensor pixel size Y in micrometres. Do not divide by M.");
        tfMagnification.setToolTipText(
                "Objective magnification M. Object-plane scale: µm/px = pitch / M. "
                + "Only affects the live profile X axis; leave empty for sensor-plane µm.");

        // Short centred boxes leave the caption column room for "Lateral magnification (M)".
        for (JTextField tf : new JTextField[] { tfWavelength, tfPixelX, tfPixelY, tfMagnification }) {
            tf.setHorizontalAlignment(JTextField.CENTER);
        }
        HoloBioUiStyle.addLabelField(p, c, "λ (µm)", tfWavelength,
            HoloBioUiStyle.FORM_LABEL_W, HoloBioUiStyle.FORM_FIELD_W_OPTICS);
        HoloBioUiStyle.addLabelField(p, c, "Pitch X (µm)", tfPixelX,
            HoloBioUiStyle.FORM_LABEL_W, HoloBioUiStyle.FORM_FIELD_W_OPTICS);
        HoloBioUiStyle.addLabelField(p, c, "Pitch Y (µm)", tfPixelY,
            HoloBioUiStyle.FORM_LABEL_W, HoloBioUiStyle.FORM_FIELD_W_OPTICS);
        HoloBioUiStyle.addLabelField(p, c, HoloBioUiStyle.MAG_LABEL, tfMagnification,
            HoloBioUiStyle.FORM_LABEL_W, HoloBioUiStyle.FORM_FIELD_W_OPTICS);

        javax.swing.event.DocumentListener scaleListener = new javax.swing.event.DocumentListener() {
            private void refresh() { refreshProfilesFromCache(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        };
        tfMagnification.getDocument().addDocumentListener(scaleListener);
        tfPixelX.getDocument().addDocumentListener(scaleListener);
        tfPixelY.getDocument().addDocumentListener(scaleListener);

        HoloBioRtUtil.liveField(tfWavelength, "λ", "µm", HoloBioRtUtil.Rule.POSITIVE, null, this::setStatus);
        HoloBioRtUtil.liveField(tfPixelX, "Pitch X", "µm", HoloBioRtUtil.Rule.POSITIVE, null, this::setStatus);
        HoloBioRtUtil.liveField(tfPixelY, "Pitch Y", "µm", HoloBioRtUtil.Rule.POSITIVE, null, this::setStatus);
        HoloBioRtUtil.liveField(tfMagnification, "Magnification", "×", HoloBioRtUtil.Rule.POSITIVE,
                "profile axis in sensor-plane µm (M = 1).", this::setStatus);

        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(HoloBioUiStyle.SPACE_2, 0, 0, 0);
        p.add(histogram.row(), c);
        c.gridy++;
        return p;
    }

    private JPanel buildProfilePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Profile"));
        GridBagConstraints c = gbc(0);
        c.insets = new Insets(0, 0, HoloBioUiStyle.SPACE_1, 0);

        btnProfile = HoloBioUiStyle.toggleButton("Live");
        btnProfile.setToolTipText(
                "The plot updates on every reconstructed frame. Draw a line or region on the phase view, or Load ROIs…");
        btnProfile.addActionListener(e -> toggleProfileMode());
        btnLoadRois = HoloBioUiStyle.secondaryButton("Load ROIs…");
        btnLoadRois.setToolTipText("Same rois_*.txt as Python QPI — sampled every live frame");
        btnLoadRois.addActionListener(e -> loadSharedRois());
        sizeControl(btnProfile);
        sizeControl(btnLoadRois);
        p.add(HoloBioUiStyle.pairRow(btnProfile, btnLoadRois), c); c.gridy++;

        rbProfLine = new JRadioButton("Line", true);
        rbProfRect = new JRadioButton("Region", false);
        rbProfLine.setOpaque(false);
        rbProfRect.setOpaque(false);
        rbProfLine.setToolTipText("Drag on the phase view to add a line profile.");
        rbProfRect.setToolTipText("Drag on the phase view to add a rectangular region (mid-line is plotted).");
        ButtonGroup profShape = new ButtonGroup();
        profShape.add(rbProfLine);
        profShape.add(rbProfRect);
        rbProfLine.setEnabled(false);
        rbProfRect.setEnabled(false);
        rbProfLine.addActionListener(e -> applyProfileCaptureMode());
        rbProfRect.addActionListener(e -> applyProfileCaptureMode());
        p.add(HoloBioUiStyle.pairRow(rbProfLine, rbProfRect), c); c.gridy++;

        btnProfRecStart = HoloBioUiStyle.secondaryButton("Record…");
        btnProfRecStop  = HoloBioUiStyle.primaryButton("Save…");
        btnProfRecStart.setEnabled(false);
        btnProfRecStop .setEnabled(false);
        btnProfRecStart.setToolTipText(
                "Record Δφ per frame while live profile is on; Save writes the CSV");
        btnProfRecStop.setToolTipText("Stop recording and save the sample table");
        btnProfRecStart.addActionListener(e -> startProfileRecording());
        btnProfRecStop .addActionListener(e -> stopProfileRecording(true));
        sizeControl(btnProfRecStart);
        sizeControl(btnProfRecStop);
        p.add(HoloBioUiStyle.pairRow(btnProfRecStart, btnProfRecStop), c); c.gridy++;

        btnExportCurve = HoloBioUiStyle.secondaryButton("Export CSV…");
        btnExportCurve.setEnabled(false);
        btnExportCurve.setToolTipText(
                "Save the current live curve (position µm vs phase rad) for Excel. Same columns as Python QPI.");
        btnExportCurve.addActionListener(e -> exportProfileCurveCsv());
        sizeControl(btnExportCurve);
        p.add(btnExportCurve, c); c.gridy++;

        lblProfRec = HoloBioUiStyle.recordLabel();
        p.add(lblProfRec, c);
        return p;
    }

    private JPanel buildRecordPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(HoloBioUiStyle.sectionPad("Record"));
        GridBagConstraints c = gbc(0);

        cbRecordTarget = new JComboBox<>(HoloBioRtRecorder.Target.values());
        cbRecordTarget.setSelectedItem(HoloBioRtRecorder.Target.PHASE);
        cbRecordTarget.setToolTipText("Which live product to write to the video file.");
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

    private static void sizeControl(JComponent c) {
        HoloBioUiStyle.stretchWidth(c);
        HoloBioUiStyle.styleField(c);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live phase profile
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleProfileMode() {
        profileMode = btnProfile.isSelected();
        if (profileMode) {
            if (profileWin == null) {
                profileWin = new HoloBioRtProfileWindow();
                profileWin.setLocationRelativeTo(this);
                profileWin.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent e) {
                        btnProfile.setSelected(false);
                        toggleProfileMode();
                    }
                });
            }
            profileWin.showFront();
            pnlRight.setLineCapture(true, rbProfRect != null && rbProfRect.isSelected(),
                    new HoloBioRtDisplayPanel.LineCaptureListener() {
                @Override public void onLine(double x1, double y1, double x2, double y2) {
                    Color color = PROFILE_COLORS[profileLines.size() % PROFILE_COLORS.length];
                    profileLines.add(HoloBioRtDisplayPanel.ProfileLine.line(x1, y1, x2, y2, color));
                    pnlRight.setLines(profileLines);
                    refreshProfilesFromCache();
                }
                @Override public void onRect(double x, double y, double w, double h) {
                    Color color = PROFILE_COLORS[profileLines.size() % PROFILE_COLORS.length];
                    profileLines.add(HoloBioRtDisplayPanel.ProfileLine.rect(x, y, w, h, color));
                    pnlRight.setLines(profileLines);
                    refreshProfilesFromCache();
                }
                @Override public void onClear() {
                    profileLines.clear();
                    pnlRight.setLines(profileLines);
                    if (profileWin != null) {
                        profileWin.update(java.util.Collections.<HoloBioRtProfileWindow.Curve>emptyList());
                    }
                }
            });
            btnProfRecStart.setEnabled(true);
            if (btnExportCurve != null) btnExportCurve.setEnabled(!lastPlotCurves.isEmpty());
            btnLoadRois.setEnabled(true);
            if (rbProfLine != null) rbProfLine.setEnabled(true);
            if (rbProfRect != null) rbProfRect.setEnabled(true);
            setStatus(profileCaptureStatus());
        } else {
            if (csvRec.isRecording()) stopProfileRecording(false);
            btnProfRecStart.setEnabled(false);
            btnProfRecStop .setEnabled(false);
            if (btnExportCurve != null) btnExportCurve.setEnabled(false);
            if (rbProfLine != null) rbProfLine.setEnabled(false);
            if (rbProfRect != null) rbProfRect.setEnabled(false);
            lblProfRec.setText(" ");
            pnlRight.setLineCapture(false, null);
            profileLines.clear();
            pnlRight.setLines(profileLines);
            lastPlotCurves = java.util.Collections.emptyList();
            lastCurveLabels = java.util.Collections.emptyList();
            if (profileWin != null) profileWin.setVisible(false);
        }
    }

    private void applyProfileCaptureMode() {
        if (!profileMode) return;
        boolean rect = rbProfRect != null && rbProfRect.isSelected();
        pnlRight.setCaptureRectMode(rect);
        setStatus(profileCaptureStatus());
    }

    private String profileCaptureStatus() {
        boolean rect = rbProfRect != null && rbProfRect.isSelected();
        return rect
                ? "Live profile: drag a region on the phase view — right-click clears."
                : "Live profile: drag a line on the phase view — right-click clears.";
    }

    private void loadSharedRois() {
        if (!profileMode) {
            btnProfile.setSelected(true);
            toggleProfileMode();
        }
        JFileChooser fc = new JFileChooser(
                new File(System.getProperty("user.home"),
                        "Documents/DECIMO SEMESTRE/AVANZADO 2/sample images/benchmark"));
        fc.setDialogTitle("Load shared ROIs (rois_*.txt)");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            HoloBioRtRoiFile.Bundle b = HoloBioRtRoiFile.load(fc.getSelectedFile());
            double sx = 1.0, sy = 1.0;
            BufferedImage img = pnlRight.getImage();
            if (img == null) img = pnlLeft.getImage();
            if (img != null && b.width > 0 && b.height > 0
                    && (img.getWidth() != b.width || img.getHeight() != b.height)) {
                sx = img.getWidth()  / (double) b.width;
                sy = img.getHeight() / (double) b.height;
            }
            profileLines.clear();
            int i = 0;
            for (HoloBioRtRoiFile.Roi r : b.rois) {
                Color color = PROFILE_COLORS[i++ % PROFILE_COLORS.length];
                profileLines.add(HoloBioRtRoiFile.toProfileLine(r, color, sx, sy));
            }
            pnlRight.setLines(profileLines);
            String scaleNote = (sx != 1.0 || sy != 1.0)
                    ? String.format("  ·  scaled ×%.3g/×%.3g to %d×%d",
                            sx, sy, img.getWidth(), img.getHeight())
                    : (b.width > 0 ? "  ·  declared " + b.width + "×" + b.height : "");
            setStatus("Loaded " + b.rois.size() + " ROI(s) from "
                    + fc.getSelectedFile().getName() + scaleNote);
            refreshProfilesFromCache();
        } catch (Exception ex) {
            setStatus("Cannot load ROIs: " + ex.getMessage());
        }
    }

    private void startProfileRecording() {
        if (csvRec.isRecording()) return;
        if (profileLines.isEmpty()) {
            setStatus("Draw at least one profile line before recording.");
            return;
        }
        csvRec.start();
        btnProfRecStart.setEnabled(false);
        btnProfRecStop .setEnabled(true);
        lblProfRec.setText("Recording…");
        setStatus("Recording phase profiles — Stop / save when finished.");
    }

    /**
     * @param askSave true when the user pressed Stop / save; false when profile mode or
     *                the main Stop button ends the session.
     */
    private void stopProfileRecording(boolean askSave) {
        if (!csvRec.isRecording() && !askSave) return;

        btnProfRecStart.setEnabled(profileMode);
        btnProfRecStop .setEnabled(false);
        lblProfRec.setText(" ");

        if (!csvRec.isRecording()) return;

        if (!askSave) {
            csvRec.stopWithoutSave();
            return;
        }

        JFileChooser fc = new JFileChooser(HoloBioRtUtil.defaultSaveDir());
        fc.setDialogTitle("Save recorded phase profiles");
        fc.setSelectedFile(new File(HoloBioRtUtil.defaultSaveDir(), "holobio_profiles.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            csvRec.stopWithoutSave();
            setStatus("Recording discarded.");
            return;
        }

        try {
            boolean saved = csvRec.stopAndSave(fc.getSelectedFile());
            if (!saved) {
                setStatus("Nothing was recorded.");
                return;
            }
            String note = csvRec.skippedCount() > 0
                    ? " (" + csvRec.skippedCount() + " frame(s) skipped after the lines changed)"
                    : "";
            setStatus("Phase profiles saved — " + csvRec.sampleCount() + " sample(s)" + note + ".");
        } catch (IOException ex) {
            setStatus("Cannot save profiles: " + ex.getMessage());
        }
    }

    /** Dump the current live curve (one row per sample) — Excel-friendly CSV. */
    private void exportProfileCurveCsv() {
        java.util.List<HoloBioRtProfileWindow.Curve> curves = lastPlotCurves;
        java.util.List<String> labels = lastCurveLabels;
        if (curves == null || curves.isEmpty()) {
            setStatus("No live profile to export — turn Live on and load or draw a line.");
            return;
        }
        JFileChooser fc = new JFileChooser(HoloBioRtUtil.defaultSaveDir());
        fc.setDialogTitle("Export live phase profile for Excel");
        fc.setSelectedFile(new File(HoloBioRtUtil.defaultSaveDir(), "fiji_rt_profiles.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            HoloBioRtProfileCurveIo.write(fc.getSelectedFile(), curves, labels, "fiji_rt");
            int n = 0;
            for (HoloBioRtProfileWindow.Curve c : curves) n += c.values.length;
            setStatus("Curve CSV saved — " + n + " sample(s) → " + fc.getSelectedFile().getName());
        } catch (IOException ex) {
            setStatus("Cannot export curve CSV: " + ex.getMessage());
        }
    }

    /**
     * Python QPI Y: {@code (angle + π)} in {@code [0, 2π]}.
     * Prefer float radians (no 8-bit round-trip) so peaks are not clipped by the display map.
     */
    private static float[] phaseMapForProfile(HoloBioRtDhmMath.Frame result) {
        if (result == null) return null;
        if (result.phaseRadians != null) {
            float[] out = new float[result.phaseRadians.length];
            float twoPi = (float) (2.0 * Math.PI);
            for (int i = 0; i < out.length; i++) {
                float p = result.phaseRadians[i];
                if (Float.isNaN(p)) { out[i] = Float.NaN; continue; }
                // phaseRadians is (−π, π]; same map as the 8-bit display and Python QPI.
                float v = (float) (p + Math.PI);
                if (v < 0f) v += twoPi;
                while (v >= twoPi) v -= twoPi;
                out[i] = v;
            }
            return out;
        }
        if (result.phase == null) return null;
        float[] out = new float[result.phase.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (result.phase[i] / 255.0 * 2.0 * Math.PI);
        }
        return out;
    }

    /** Re-sample cached phase with the current M / pitch (no new reconstruction). */
    private void refreshProfilesFromCache() {
        if (!profileMode || lastProfileFrame == null) return;
        double dx = HoloBioRtUtil.parseDouble(tfPixelX, lastDxUm);
        double dy = HoloBioRtUtil.parseDouble(tfPixelY, lastDyUm);
        updateLiveProfiles(lastProfileFrame, dx, dy);
    }

    /** Sample every region once for this reconstruction and update the live plot. */
    private void updateLiveProfiles(HoloBioRtDhmMath.Frame result, double dxUm, double dyUm) {
        HoloBioRtProfileWindow win = profileWin;
        float[] phaseQpi = phaseMapForProfile(result);
        if (win == null || phaseQpi == null || profileLines.isEmpty()) return;
        lastProfileFrame = result;
        lastDxUm = dxUm;
        lastDyUm = dyUm;
        double mag = Math.max(1e-6, HoloBioRtUtil.parseDouble(tfMagnification, 1.0));
        // Python QPI: dist = arange(N) * (pixel_size / M)  — not geometric hypot/(N-1)
        final double umPerPx = dxUm / mag;
        java.util.List<HoloBioRtProfileWindow.Curve> csvCurves =
                new java.util.ArrayList<>(profileLines.size());
        java.util.List<HoloBioRtProfileWindow.Curve> plotCurves =
                new java.util.ArrayList<>(profileLines.size());
        double firstLenUm = 0;
        for (HoloBioRtDisplayPanel.ProfileLine ln : profileLines) {
            if (ln.rect) {
                double[] rectProf = HoloBioQpiSpeckleMath.profileAlongRect(
                        phaseQpi, result.width, result.height,
                        ln.x1, ln.y1, ln.x2, ln.y2);
                double yMid = ln.y1 + 0.5 * ln.y2;
                double[] plotProf = HoloBioQpiSpeckleMath.profileAlongLine(
                        phaseQpi, result.width, result.height,
                        ln.x1, yMid, ln.x1 + ln.x2, yMid);
                double lenUm = plotProf.length > 1 ? (plotProf.length - 1) * umPerPx : 0;
                if (firstLenUm <= 0) firstLenUm = lenUm;
                csvCurves.add(new HoloBioRtProfileWindow.Curve(
                        rectProf.length > 0 ? rectProf : plotProf, umPerPx, ln.color));
                plotCurves.add(new HoloBioRtProfileWindow.Curve(plotProf, umPerPx, ln.color));
            } else {
                double[] prof = HoloBioQpiSpeckleMath.profileAlongLine(
                        phaseQpi, result.width, result.height,
                        ln.x1, ln.y1, ln.x2, ln.y2);
                double lenUm = prof.length > 1 ? (prof.length - 1) * umPerPx : 0;
                if (firstLenUm <= 0) firstLenUm = lenUm;
                HoloBioRtProfileWindow.Curve c = new HoloBioRtProfileWindow.Curve(prof, umPerPx, ln.color);
                csvCurves.add(c);
                plotCurves.add(c);
            }
        }
        win.update(plotCurves);
        final double firstLenUmF = firstLenUm;
        SwingUtilities.invokeLater(() -> win.setTitle(String.format(
                Locale.US, "RT DHM — Live Phase Profile · M=%.2f · %.5g µm/px · L≈%.2f µm",
                mag, umPerPx, firstLenUmF)));
        lastPlotCurves = plotCurves;
        java.util.List<String> labels = new java.util.ArrayList<>(profileLines.size());
        for (int i = 0; i < profileLines.size(); i++) {
            labels.add(profileLines.get(i).label(i + 1));
        }
        lastCurveLabels = labels;
        SwingUtilities.invokeLater(() -> {
            if (btnExportCurve != null) btnExportCurve.setEnabled(!plotCurves.isEmpty());
        });
        if (csvRec.isRecording()) {
            csvRec.add(csvCurves);
            final int n = csvRec.sampleCount();
            SwingUtilities.invokeLater(() -> {
                if (csvRec.isRecording()) lblProfRec.setText("Recording… " + n + " sample(s)");
            });
        }
    }

    // ── Viewports ────────────────────────────────────────────────────────────

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
        pnlLeft = new HoloBioRtDisplayPanel(VIEW_FOURIER);

        rbHologram = new JRadioButton("Hologram");
        rbFourier  = new JRadioButton("FT", true);
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbHologram); bg.add(rbFourier);
        rbHologram.addActionListener(e -> onInputViewChanged());
        rbFourier .addActionListener(e -> onInputViewChanged());

        btnAutoFilter = HoloBioUiStyle.secondaryButton("Re-detect");
        btnAutoFilter.setToolTipText("Search the spectrum again for the +1 diffraction order");
        btnAutoFilter.addActionListener(e -> {
            warnedNoCarrier = false;
            HoloBioRtDhmMath.resetSideband();
            setStatus("Re-detecting carrier…");
        });

        // Python's ft_display_var: the spectrum as it arrives, or what survives the mask.
        cbFtView = new JComboBox<>(new String[] { FT_UNFILTERED, FT_FILTERED });
        cbFtView.setToolTipText("Show the spectrum before or after the spatial filter");
        cbFtView.addActionListener(e -> {
            HoloBioRtDhmMath.setFtFiltered(FT_FILTERED.equals(cbFtView.getSelectedItem()));
            lastFourier = null;
        });

        cbFtShape = new JComboBox<>(new String[] { FT_CIRCLE, FT_RECT });
        cbFtShape.setToolTipText("Python QPI default is Circular");
        cbFtShape.addActionListener(e -> {
            HoloBioRtDhmMath.setCircularMask(FT_CIRCLE.equals(cbFtShape.getSelectedItem()));
            lastFourier = null;
        });
        HoloBioRtDhmMath.setCircularMask(true);

        tfFtFactor = new JTextField("3", 3);
        tfFtFactor.setToolTipText(
                "Python QPI Circular: radius = distance(DC, +1 order) / factor. Default 3.");
        tfFtRadius = new JTextField("", 4);
        tfFtRadius.setToolTipText(
                "Mask radius in spectrum pixels. Empty = auto (dist / factor), same as Python.");

        HoloBioUiStyle.sizeField(cbFtView, HoloBioUiStyle.FORM_COMBO_W);
        HoloBioUiStyle.sizeField(cbFtShape, HoloBioUiStyle.FORM_COMBO_W);
        HoloBioUiStyle.sizeField(tfFtFactor, HoloBioUiStyle.FORM_FIELD_W_SM);
        HoloBioRtUtil.liveField(tfFtFactor, "Factor", "", HoloBioRtUtil.Rule.POSITIVE, null, this::setStatus);
        HoloBioRtUtil.liveField(tfFtRadius, "Filter radius", "px", HoloBioRtUtil.Rule.NON_NEGATIVE,
                "radius is automatic (distance / Factor).", this::setStatus);
        HoloBioUiStyle.sizeField(tfFtRadius, HoloBioUiStyle.FORM_FIELD_W_SM);

        lblFilter = HoloBioUiStyle.fieldLabel("Filter: auto");

        JPanel head = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 2);
        head.add(rbHologram);
        head.add(rbFourier);
        head.add(cbFtView);
        head.add(cbFtShape);
        head.add(HoloBioUiStyle.hgap(HoloBioUiStyle.SPACE_2));
        head.add(HoloBioUiStyle.fieldLabel("Factor"));
        head.add(tfFtFactor);
        head.add(HoloBioUiStyle.hgap(HoloBioUiStyle.SPACE_2));
        head.add(HoloBioUiStyle.fieldLabel("r px"));
        head.add(tfFtRadius);
        head.add(HoloBioUiStyle.hgap(HoloBioUiStyle.SPACE_2));
        head.add(btnAutoFilter);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(HoloBioUiStyle.sectionPad("Input"));
        wrap.add(head,    BorderLayout.NORTH);
        wrap.add(pnlLeft, BorderLayout.CENTER);
        return wrap;
    }

    private void updateFilterLabel() {
        int gR = gridRows, gC = gridCols;
        String offset = (gR > 0 && gC > 0)
                ? String.format("  ·  offset from DC (%+d, %+d)", filterCx - gC / 2, filterCy - gR / 2)
                : "";
        String how = HoloBioRtUtil.parseInt(tfFtRadius, 0) > 0
                ? "manual"
                : String.format(Locale.US, "auto dist/%.3g", HoloBioRtUtil.parseDouble(tfFtFactor, 3.0));
        String text = String.format("Filter: %s  ·  r = %d (%s)%s",
                FT_CIRCLE.equals(cbFtShape.getSelectedItem()) ? "Circular" : "Rectangular",
                filterRadius, how, offset);
        SwingUtilities.invokeLater(() -> lblFilter.setText(text));
    }

    private JPanel buildOutputView() {
        pnlRight = new HoloBioRtDisplayPanel(VIEW_PHASE);

        rbPhase     = new JRadioButton("Phase", true);
        rbAmplitude = new JRadioButton("Amplitude");
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbPhase); bg.add(rbAmplitude);
        rbPhase    .addActionListener(e -> onOutputViewChanged());
        rbAmplitude.addActionListener(e -> onOutputViewChanged());

        cbCompMethod = new JComboBox<>(new String[] { "Vortex", "Vortex Legendre" });
        cbCompMethod.setSelectedItem("Vortex Legendre");
        cbCompMethod.setToolTipText(
                "Vortex: sideband extraction without Legendre flattening. "
                + "Vortex Legendre: plus polynomial flattening of residual tilt and curvature. "
                + "Semi-Heuristic and Tu-DHM are in Offline DHM.");
        HoloBioUiStyle.sizeField(cbCompMethod, 148);
        cbCompMethod.addActionListener(e -> {
            HoloBioRtDhmMath.setFlattenPhase("Vortex Legendre".equals(cbCompMethod.getSelectedItem()));
            lastPhase = null;
        });
        HoloBioRtDhmMath.setFlattenPhase(true);

        JPanel head = HoloBioUiStyle.flowLeft(HoloBioUiStyle.SPACE_2, 2);
        head.add(rbPhase);
        head.add(rbAmplitude);
        head.add(cbCompMethod);
        head.add(HoloBioUiStyle.infoButton("Reconstruction method",
            "Vortex extracts the +1-order sideband.\n\n"
            + "Vortex Legendre (default) also flattens residual tilt and curvature "
            + "with Legendre polynomials.\n\n"
            + "Semi-Heuristic and Tu-DHM are available in Offline DHM."));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(HoloBioUiStyle.sectionPad("Reconstruction"));
        wrap.add(head,     BorderLayout.NORTH);
        wrap.add(pnlRight, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildStatusBar() {
        lblStatus = HoloBioUiStyle.footerNote("Ready — select a source and press Start.");
        JPanel bar = HoloBioUiStyle.statusBar(lblStatus);
        if (lblFilter != null) {
            bar.add(lblFilter, BorderLayout.EAST);
        }
        return bar;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View selection
    // ─────────────────────────────────────────────────────────────────────────

    private void onInputViewChanged() {
        showFourier = rbFourier.isSelected();
        pnlLeft.setHint(showFourier ? VIEW_FOURIER : VIEW_HOLOGRAM);
        pnlLeft.setImage(showFourier ? lastFourier : lastHologram);
    }

    private void onOutputViewChanged() {
        showPhase = rbPhase.isSelected();
        pnlRight.setHint(showPhase ? VIEW_PHASE : VIEW_AMPLITUDE);
        pnlRight.setImage(showPhase ? lastPhase : lastAmplitude);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateSourceState() {
        boolean isCamera = rbCamera.isSelected();
        cbCamera    .setEnabled(isCamera);
        cbResolution.setEnabled(isCamera);
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
            for (int i = 0; i < cams.size(); i++) {
                cbCamera.addItem(i + ": " + cams.get(i).getName());
            }
        }
    }

    private void browseVideo() {
        JFileChooser fc = new JFileChooser(HoloBioRtUtil.defaultSaveDir());
        fc.setDialogTitle("Open video or still hologram");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        javax.swing.filechooser.FileNameExtensionFilter media =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "Video / image (MP4, AVI, MOV, MKV, TIFF, PNG, JPEG)",
                        "mp4", "avi", "mov", "mkv", "tif", "tiff", "png", "jpg", "jpeg", "bmp");
        fc.addChoosableFileFilter(media);
        fc.setFileFilter(media);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        videoFile = fc.getSelectedFile().getAbsoluteFile();
        lblVideoName.setText(videoFile.getName());
        lblVideoName.setToolTipText(videoFile.getAbsolutePath());
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lblStatus.setText(msg));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Processing lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private void startProcessing() {
        if (running) return;
        pausedPlayback = false;
        if (csvRec.isRecording()) {
            setStatus("Stop and save the phase-profile recording before starting playback again "
                    + "(otherwise frames from multiple runs get stacked).");
            return;
        }
        btnStart.setEnabled(false);

        if (rbCamera.isSelected()) {
            int idx = Math.max(0, cbCamera.getSelectedIndex());
            setStatus("Connecting to camera " + idx + "…");
            try {
                source = HoloBioRtFrameSource.camera(webcam, idx,
                        HoloBioCameraModes.parse(cbResolution.getSelectedItem()));
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
            // Opening can block while FFmpeg starts up, so keep it off the event thread
            Thread loader = new Thread(() -> {
                HoloBioRtFrameSource opened;
                try {
                    opened = HoloBioRtFrameSource.file(path); // always native resolution
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
        btnStop .setEnabled(true);
        btnSnap .setEnabled(true);
        reportedGeometry = false;
        warnedNoCarrier  = false;
        pendingSeek = -1;
        acqFps.reset();
        reconFps.reset();
        synchronized (frameLock) { readyGray = null; spareGray = null; }
        refreshFpsLabels();
        HoloBioRtDhmMath.resetSideband();
        setStatus(statusMsg);

        processThread = new Thread(this::processLoop, "HoloBio-RTDHM-recon");
        captureThread = new Thread(this::captureLoop, "HoloBio-RTDHM-capture");
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
        if (csvRec.isRecording()) stopProfileRecording(false);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Frame processing loop (background thread)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Grab frames and publish them for reconstruction.
     *
     * <p>Live viewing uses a single slot with newest-wins overwrite so a slow reconstruction
     * does not pile up latency. While a phase-profile CSV is recording, capture instead
     * <b>waits</b> until the previous frame has been consumed — otherwise every other source
     * frame is dropped and the CSV frame column jumps (33, 35, 38…).
     */
    private void captureLoop() {
        long nextDueNs = System.nanoTime();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                boolean recordEvery = csvRec.isRecording();

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

                // Pace file playback to the source rate, except while profile-recording:
                // then we go as fast as reconstruction allows so no frame is skipped.
                if (paceCapture && !recordEvery) {
                    long periodNs = (long) (1e9 / Math.max(sourceFps, 0.1));
                    long waitNs = nextDueNs - System.nanoTime();
                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (int) (waitNs % 1_000_000L));
                    long now = System.nanoTime();
                    nextDueNs = (now - nextDueNs > periodNs) ? now + periodNs : nextDueNs + periodNs;
                }

                BufferedImage frame = source != null ? source.next() : null;

                if (frame == null) {
                    if (paceCapture && (source == null || !source.isStill())) {
                        SwingUtilities.invokeLater(() -> {
                            running = false;
                            btnStart.setEnabled(true);
                            btnStop .setEnabled(false);
                            if (csvRec.isRecording()) stopProfileRecording(true);
                            else lblStatus.setText("Video ended.");
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
                histogram.sample(gray, w, h);

                if (!showFourier) {
                    BufferedImage img = HoloBioRtUtil.grayImage(gray, w, h);
                    lastHologram = img;
                    SwingUtilities.invokeLater(() -> pnlLeft.setImage(img));
                }

                synchronized (frameLock) {
                    if (recordEvery) {
                        // Block until the reconstructor takes the previous frame
                        while (running && readyGray != null) frameLock.wait(200);
                        if (!running) break;
                    } else if (readyGray != null) {
                        spareGray = readyGray;   // drop unread frame (live view only)
                    }
                    readyGray = gray; readyW = w; readyH = h;
                    frameLock.notifyAll();
                }
                markAcquired();

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
                    frameLock.notifyAll(); // unblock capture when profile-recording waits
                }

                // Optional early stop for long videos (source timeline from delivered frames)
                double stopAfter = HoloBioRtUtil.parseDouble(tfStopAfterSec, 0.0);
                if (stopAfter > 0 && source != null && source.needsPacing()) {
                    double pos = source.positionSec();
                    if (pos >= stopAfter) {
                        running = false;
                        final double posF = pos, limF = stopAfter;
                        SwingUtilities.invokeLater(() -> {
                            btnStart.setEnabled(true);
                            btnStop .setEnabled(false);
                            if (csvRec.isRecording()) stopProfileRecording(true);
                            else setStatus(String.format(
                                    "Stopped after %.2f s (limit %.2f s).", posF, limF));
                            synchronized (frameLock) { frameLock.notifyAll(); }
                        });
                        break;
                    }
                }

                boolean wantFt    = showFourier;
                boolean wantPhase = showPhase;

                // Normally only the visible product is evaluated. A recording of one that is
                // not on screen would then capture nothing, so pull it in while it is taping.
                HoloBioRtRecorder.Target recTarget =
                        recorder.isRecording() ? recorder.target() : null;
                boolean needPhase = wantPhase || profileMode
                        || recTarget == HoloBioRtRecorder.Target.PHASE;
                boolean needAmp   = !wantPhase || recTarget == HoloBioRtRecorder.Target.AMPLITUDE;
                boolean needFt    = wantFt; // FT is display-only; recording matches Python (no FT target)

                double lambdaUm = HoloBioRtUtil.parseDouble(tfWavelength, 0.633);
                double dxUm     = HoloBioRtUtil.parseDouble(tfPixelX,     3.75);
                double dyUm     = HoloBioRtUtil.parseDouble(tfPixelY,     3.75);
                HoloBioRtDhmMath.setRadiusFactor(HoloBioRtUtil.parseDouble(tfFtFactor, 3.0));
                HoloBioRtDhmMath.setManualRadiusPx(HoloBioRtUtil.parseInt(tfFtRadius, 0));
                HoloBioRtDhmMath.Frame result = HoloBioRtDhmMath.process(
                        gray, h, w, lambdaUm, dxUm, dyUm,
                        HoloBioRtDhmMath.FILTER_NONE, 0, 0, 0,
                        needFt, true, needAmp, needPhase);
                captureField();

                // Read back the mask actually applied, so the overlay and label track it.
                int side = Math.min(h, w);
                if ((side & 1) != 0) side--;
                gridRows = HoloBioRectFft.nextPowerOfTwo(Math.max(2, side));
                gridCols = gridRows;
                filterCx = HoloBioRtDhmMath.sidebandCol();
                filterCy = HoloBioRtDhmMath.sidebandRow();
                filterRadius = HoloBioRtDhmMath.sidebandRadius();
                updateFilterLabel();
                if (!HoloBioRtDhmMath.sidebandConfident() && !warnedNoCarrier) {
                    warnedNoCarrier = true;
                    setStatus(String.format(
                            "No off-axis carrier found (prominence %.1f× vs %.0f× needed) — "
                            + "the input may not be an off-axis hologram.",
                            HoloBioRtDhmMath.sidebandProminence(), 12.0));
                }

                BufferedImage inputImg = wantFt
                        ? HoloBioRtUtil.grayImage(result.ft, result.ftWidth, result.ftHeight) : null;
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
                } else if (recTarget == HoloBioRtRecorder.Target.HOLOGRAM) {
                    // Match Python: record the input grey frame (min–max → 0…255).
                    float[] holoDisp = gray.clone();
                    HoloBioRtUtil.stretchTo255(holoDisp, w * h);
                    recorder.add(recTarget, holoDisp, w, h);
                    updateRecIndicator();
                }

                if (!reportedGeometry) {
                    reportedGeometry = true;
                    setStatus(String.format("Frame %d×%d  ·  processing %d×%d  ·  FFT grid %d×%d",
                            w, h, result.width, result.height, gridCols, gridRows));
                }

                if (wantFt) lastFourier = inputImg;
                if (wantPhase) lastPhase = outputImg; else lastAmplitude = outputImg;

                if (profileMode) updateLiveProfiles(result, dxUm, dyUm);

                SwingUtilities.invokeLater(() -> {
                    if (inputImg != null) pnlLeft.setImage(inputImg);
                    pnlRight.setImage(outputImg);
                });
                markReconstructed();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running) break;   // Stop interrupted a parallel FFT pass: not an error
                e.printStackTrace();
                setStatus("Frame error: " + e.getMessage());
            }
        }
    }


    private void markAcquired() {
        if (acqFps.tick()) refreshFpsLabels();
    }

    private void markReconstructed() {
        if (reconFps.tick()) refreshFpsLabels();
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

    // ─────────────────────────────────────────────────────────────────────────
    // Snap to Fiji
    // ─────────────────────────────────────────────────────────────────────────

    private void snapToFiji() {
        BufferedImage in  = pnlLeft .getImage();
        BufferedImage out = pnlRight.getImage();
        if (in == null && out == null) { setStatus("No frame ready."); return; }
        if (in != null) {
            String title = showFourier ? "RT DHM — Fourier Transform" : "RT DHM — Hologram";
            HoloBioFijiUi.showImagePlus(new ImagePlus(title, toProcessor(in)));
        }
        if (out != null) {
            String title = showPhase ? "RT DHM — Phase" : "RT DHM — Amplitude";
            HoloBioFijiUi.showImagePlus(new ImagePlus(title, toProcessor(out)));
        }
        setStatus("Snapped current views to Fiji.");
    }

    /**
     * Build an {@link ImageProcessor} straight from the {@link BufferedImage} raster. The old code
     * used {@code new ColorProcessor(img)}, which routes through AWT's {@code PixelGrabber}; that can
     * silently yield a blank image on some Java2D/GPU-driver setups (no exception thrown). Reading
     * the raster directly is both faster and reliable.
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

    private static GridBagConstraints gbc(int gridy) {
        return HoloBioRtUtil.gbc(gridy);
    }
}
