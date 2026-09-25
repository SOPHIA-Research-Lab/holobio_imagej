import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live exposure and gain for the camera feeding a real-time module.
 *
 * <p>Behaviour, deliberately simple:
 * <ul>
 *   <li><b>Attach</b> reads whatever the camera currently holds and shows it. Nothing is
 *       written until the user moves a control — opening HoloBio never changes a camera.</li>
 *   <li>Values are read back after every change and the label shows the camera's value,
 *       not the requested one: devices clamp and round.</li>
 *   <li>Settings live in the camera driver, not in HoloBio. Closing the window leaves them as
 *       they are, exactly like any other camera app.</li>
 *   <li>If the camera disappears, the next change fails, the controls disable, and the
 *       status line says so; nothing is retried in the background.</li>
 * </ul>
 * Hidden entirely when the platform has no DirectShow or the device exposes neither setting.
 */
public final class HoloBioCameraSettingsPanel extends JPanel {

    private final JSlider exposure = new JSlider();
    private final JToggleButton exposureAuto = HoloBioUiStyle.toggleButton("Auto");
    private final JLabel exposureValue = valueLabel();
    private final JSlider gain = new JSlider();
    private final JLabel gainValue = valueLabel();
    private final java.util.function.Consumer<String> status;

    private volatile HoloBioDShowCamera camera;
    /** Suppresses listener feedback while the panel itself moves the controls. */
    private boolean updating;

    /** Background worker; the latest pending request per property wins while dragging. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HoloBio-camera-settings");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Runnable> pendingExposure = new AtomicReference<>();
    private final AtomicReference<Runnable> pendingGain = new AtomicReference<>();

    /**
     * A sidebar section of its own ("Camera"), meant to sit directly under Capture. The top
     * gap matches the sidebar's inter-section strut, so hiding it leaves no hole.
     */
    public HoloBioCameraSettingsPanel(java.util.function.Consumer<String> status) {
        super(new GridBagLayout());
        this.status = status;
        setOpaque(false);
        setVisible(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createCompoundBorder(
                HoloBioUiStyle.emptyPad(HoloBioUiStyle.SPACE_2, 0, 0, 0),
                HoloBioUiStyle.sectionPad("Camera")));

        exposure.setToolTipText("Exposure time. Each step doubles or halves it.");
        gain.setToolTipText("Sensor gain. Higher is brighter but noisier.");
        exposureAuto.setToolTipText("On: the camera chooses exposure. Off: set it with the slider.");
        exposureAuto.setPreferredSize(new Dimension(64, 22));

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        addControl(c, "Exposure", exposureValue, exposureAuto, exposure);
        addControl(c, "Gain", gainValue, null, gain);

        exposure.addChangeListener(e -> {
            if (!exposureAuto.isSelected()) exposureValue.setText(exposureText(exposure.getValue()));
            if (!updating && !exposureAuto.isSelected()) {
                submit(pendingExposure, () -> apply(HoloBioDShowCamera.Prop.EXPOSURE,
                        exposure.getValue(), false));
            }
        });
        exposureAuto.addActionListener(e -> {
            if (updating) return;
            boolean auto = exposureAuto.isSelected();
            exposure.setEnabled(!auto);
            exposureValue.setText(auto ? "" : exposureText(exposure.getValue()));
            submit(pendingExposure, () -> apply(HoloBioDShowCamera.Prop.EXPOSURE,
                    exposure.getValue(), auto));
        });
        gain.addChangeListener(e -> {
            gainValue.setText(String.valueOf(gain.getValue()));
            if (!updating) {
                submit(pendingGain, () -> apply(HoloBioDShowCamera.Prop.GAIN, gain.getValue(), false));
            }
        });
    }

    /** Grow with content in a BoxLayout sidebar instead of keeping the height it was built with. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private static JLabel valueLabel() {
        JLabel l = new JLabel("—", SwingConstants.RIGHT);
        l.setFont(HoloBioUiStyle.fontBodyBold());
        l.setForeground(HoloBioUiStyle.TEXT);
        return l;
    }

    /**
     * Two lines per control: name, value (and optional toggle) above a full-width slider.
     * Reads as label → reading → adjuster, and leaves the slider the whole sidebar width.
     */
    private void addControl(GridBagConstraints c, String name, JLabel value, JComponent toggle,
                            JSlider slider) {
        JPanel head = new JPanel(new GridBagLayout());
        head.setOpaque(false);
        GridBagConstraints h = new GridBagConstraints();
        h.anchor = GridBagConstraints.WEST;
        h.gridx = 0;
        head.add(HoloBioUiStyle.fieldLabel(name), h);
        h.gridx = 1;
        h.weightx = 1;
        h.anchor = GridBagConstraints.EAST;
        head.add(value, h);
        if (toggle != null) {
            h.gridx = 2;
            h.weightx = 0;
            h.insets = new Insets(0, HoloBioUiStyle.SPACE_2, 0, 0);
            head.add(toggle, h);
        }

        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(c.gridy == 0 ? HoloBioUiStyle.SPACE_1 : HoloBioUiStyle.SPACE_2, 0, 0, 0);
        head.setPreferredSize(new Dimension(10, HoloBioUiStyle.CONTROL_H));
        add(head, c);
        c.gridy++;

        slider.setOpaque(false);
        slider.setFocusable(false);
        slider.setPreferredSize(new Dimension(10, 22));
        c.insets = new Insets(0, -3, 0, -3);   // JSlider pads its track; line it up with the text
        add(slider, c);
        c.gridy++;
    }

    /** DirectShow exposure is log2(seconds): -6 → 1/64 s, 0 → 1 s, 1 → 2 s. */
    private static String exposureText(int log2s) {
        return log2s < 0 ? "1/" + (1 << -log2s) + " s" : (1 << log2s) + " s";
    }

    /** Coalesce: while a slider is dragged, only the newest value is ever sent. */
    private void submit(AtomicReference<Runnable> slot, Runnable job) {
        if (camera == null) return;
        if (slot.getAndSet(job) == null) {
            worker.execute(() -> {
                Runnable latest = slot.getAndSet(null);
                if (latest != null) latest.run();
            });
        }
    }

    private void apply(HoloBioDShowCamera.Prop p, int value, boolean auto) {
        HoloBioDShowCamera cam = camera;
        if (cam == null) return;
        try {
            HoloBioDShowCamera.Range r = cam.set(p, value, auto);
            SwingUtilities.invokeLater(() -> show(r));
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> lost(ex));
        }
    }

    /**
     * Take control of the camera whose capture name is {@code sarxosName} (as listed in the
     * Capture combo). Reads its current state; changes nothing on the device.
     */
    public void attach(String sarxosName) {
        detach();
        if (!HoloBioDShowCamera.isSupported() || sarxosName == null) return;
        // Combo entries may carry an index prefix ("0: USB Camera 0"); DirectShow knows the bare name.
        String name = sarxosName.replaceFirst("^\\d+:\\s*", "");
        worker.execute(() -> {
            try {
                HoloBioDShowCamera cam = HoloBioDShowCamera.open(name);
                if (cam == null) {
                    ij.IJ.log("HoloBio: no DirectShow device matches \"" + name
                            + "\"; exposure/gain controls unavailable.");
                    return;
                }
                HoloBioDShowCamera.Range e = cam.range(HoloBioDShowCamera.Prop.EXPOSURE);
                HoloBioDShowCamera.Range g = cam.range(HoloBioDShowCamera.Prop.GAIN);
                if (e == null && g == null) {
                    cam.close();
                    return;
                }
                camera = cam;
                SwingUtilities.invokeLater(() -> {
                    configure(exposure, e);
                    exposureAuto.setVisible(e != null && e.canAuto);
                    configure(gain, g);
                    setVisible(true);
                    if (getParent() != null) getParent().revalidate();
                    repaint();
                });
            } catch (Throwable ex) {
                // No DirectShow control for this device — leave the panel hidden, but say why.
                ij.IJ.log("HoloBio: camera controls unavailable for \"" + name + "\": " + ex);
            }
        });
    }

    private void configure(JSlider s, HoloBioDShowCamera.Range r) {
        boolean ok = r != null && r.canManual;
        s.setVisible(ok);
        if (!ok) return;
        updating = true;
        try {
            s.setMinimum(r.min);
            s.setMaximum(r.max);
            s.setMinorTickSpacing(r.step);
            s.setSnapToTicks(true);
            show(r);
        } finally {
            updating = false;
        }
    }

    private void show(HoloBioDShowCamera.Range r) {
        updating = true;
        try {
            if (r.prop == HoloBioDShowCamera.Prop.EXPOSURE) {
                exposure.setValue(r.value);
                exposureAuto.setSelected(r.auto);
                exposure.setEnabled(!r.auto);
                exposureValue.setText(r.auto ? "" : exposureText(r.value));
            } else {
                gain.setValue(r.value);
                gainValue.setText(String.valueOf(r.value));
            }
        } finally {
            updating = false;
        }
    }

    private void lost(Exception ex) {
        exposure.setEnabled(false);
        exposureAuto.setEnabled(false);
        gain.setEnabled(false);
        status.accept("Camera settings unavailable — was the camera disconnected? ("
                + ex.getMessage() + ")");
        detach();
    }

    /** Release the device handle. Settings stay in the camera. */
    public void detach() {
        HoloBioDShowCamera cam = camera;
        camera = null;
        pendingExposure.set(null);
        pendingGain.set(null);
        if (cam != null) {
            worker.execute(cam::close);
        }
        SwingUtilities.invokeLater(() -> {
            setVisible(false);
            exposure.setEnabled(true);
            exposureAuto.setEnabled(true);
            gain.setEnabled(true);
        });
    }
}
