import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Minimal acquisition UI: backend choice, pixel pitch, probe log, snap stub.
 * Not opened from {@link HoloBio_DHM_Plugin} until camera snap is implemented; kept for development.
 */
public class HoloBioAcquisitionDialog extends JDialog {

    private final HoloBioAcquisitionService service;
    private final HoloBioDhmParams dhmParams;

    private JComboBox<HoloBioCameraKind> backendBox;
    private JTextField tfPitchX;
    private JTextField tfPitchY;
    private JTextField tfExposure;
    private JTextArea probeArea;
    private JTextArea logArea;
    private JLabel statusLabel;

    public HoloBioAcquisitionDialog(Frame owner, HoloBioAcquisitionService service,
                                    HoloBioDhmParams dhmParams) {
        super(owner, "HoloBio Acquisition", false);
        this.service = service;
        this.dhmParams = dhmParams;
        buildUi();
        refreshFromParams();
        refreshProbe();
        pack();
        setMinimumSize(new Dimension(440, 420));
        setLocationRelativeTo(owner);
        HoloBioUiStyle.applyPluginTypography(getContentPane());
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = HoloBioUiStyle.westGbc(0);
        gbc.insets = new Insets(0, 0, 6, 8);

        form.add(new JLabel("Backend"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        backendBox = new JComboBox<>(service.getRegistry().listKinds().toArray(new HoloBioCameraKind[0]));
        backendBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof HoloBioCameraKind) {
                    setText(((HoloBioCameraKind) value).getDisplayName());
                }
                return this;
            }
        });
        backendBox.addActionListener(e -> onBackendChanged());
        form.add(backendBox, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        form.add(new JLabel("Pixel pitch X (µm)"), gbc);
        gbc.gridx = 1;
        tfPitchX = new JTextField(8);
        form.add(tfPitchX, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        form.add(new JLabel("Pixel pitch Y (µm)"), gbc);
        gbc.gridx = 1;
        tfPitchY = new JTextField(8);
        form.add(tfPitchY, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        form.add(new JLabel("Exposure (ms)"), gbc);
        gbc.gridx = 1;
        tfExposure = new JTextField(8);
        form.add(tfExposure, gbc);

        root.add(form, BorderLayout.NORTH);

        statusLabel = HoloBioUiStyle.statusHtml("Select a backend and run Probe.");
        root.add(statusLabel, BorderLayout.CENTER);

        probeArea = new JTextArea(8, 40);
        probeArea.setEditable(false);
        probeArea.setLineWrap(true);
        probeArea.setWrapStyleWord(true);
        probeArea.setBorder(HoloBioUiStyle.sectionBorder("Installed backends (probe)"));
        JScrollPane probeScroll = new JScrollPane(probeArea);
        HoloBioUiStyle.styleScroll(probeScroll);

        logArea = new JTextArea(4, 40);
        logArea.setEditable(false);
        logArea.setBorder(HoloBioUiStyle.sectionBorder("Log"));
        JScrollPane logScroll = new JScrollPane(logArea);
        HoloBioUiStyle.styleScroll(logScroll);

        JPanel southStack = new JPanel(new BorderLayout(0, 6));
        southStack.add(probeScroll, BorderLayout.CENTER);
        southStack.add(logScroll, BorderLayout.SOUTH);
        root.add(southStack, BorderLayout.SOUTH);

        JButton probeBtn = new JButton("Probe");
        probeBtn.addActionListener(e -> refreshProbe());
        JButton snapBtn = HoloBioUiStyle.primaryButton("Snap to HoloBio");
        snapBtn.addActionListener(e -> onSnap());
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());

        root.add(HoloBioUiStyle.buildFooter(
            "MM / vendor plugins: backbone only — Active Image works today.",
            probeBtn, snapBtn, closeBtn), BorderLayout.PAGE_END);

        setContentPane(root);
    }

    private void refreshFromParams() {
        service.getSettings().copyFrom(dhmParams);
        tfPitchX.setText(String.valueOf(dhmParams.getPixelPitchXUm()));
        tfPitchY.setText(String.valueOf(dhmParams.getPixelPitchYUm()));
        tfExposure.setText(String.valueOf(service.getSettings().getExposureMs()));
        HoloBioCameraKind kind = service.getSettings().getBackend();
        backendBox.setSelectedItem(kind);
        service.selectBackend(kind);
    }

    private void pushSettingsFromUi() {
        HoloBioAcquisitionSettings s = service.getSettings();
        s.setBackend((HoloBioCameraKind) backendBox.getSelectedItem());
        s.setPixelPitchXUm(parseDouble(tfPitchX.getText(), dhmParams.getPixelPitchXUm()));
        s.setPixelPitchYUm(parseDouble(tfPitchY.getText(), dhmParams.getPixelPitchYUm()));
        s.setExposureMs(parseDouble(tfExposure.getText(), s.getExposureMs()));
        service.selectBackend(s.getBackend());
    }

    private void onBackendChanged() {
        pushSettingsFromUi();
        HoloBioCameraAvailability av = service.probeSelected();
        HoloBioCameraKind kind = (HoloBioCameraKind) backendBox.getSelectedItem();
        String hint = kind != null ? kind.getDescription() : "";
        statusLabel.setText("<html><b>" + (kind != null ? kind.getDisplayName() : "") + "</b><br/>"
            + hint + "<br/>Status: " + av.getStatus() + " — " + av.getMessage() + "</html>");
    }

    private void refreshProbe() {
        pushSettingsFromUi();
        onBackendChanged();
        probeArea.setText(service.buildProbeReport());
        appendLog("Probe complete.");
    }

    private void onSnap() {
        pushSettingsFromUi();
        try {
            service.getSettings().applyTo(dhmParams);
            dhmParams.setPixelPitchXUm(service.getSettings().getPixelPitchXUm());
            dhmParams.setPixelPitchYUm(service.getSettings().getPixelPitchYUm());
            service.snapAndDeliver();
            appendLog("Snap delivered to HoloBio pipeline.");
        } catch (UnsupportedOperationException ex) {
            appendLog("Snap: " + ex.getMessage());
            HoloBioCameraAvailability av = service.probeSelected();
            String msg = ex.getMessage();
            if (av.getSetupHint() != null) {
                msg += "\n\n" + av.getSetupHint();
            }
            IJ.showMessage("HoloBio Acquisition", msg);
        } catch (Exception ex) {
            appendLog("Error: " + ex.getMessage());
            IJ.showMessage("HoloBio Acquisition", ex.getMessage());
        }
    }

    public void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static double parseDouble(String text, double fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static HoloBioAcquisitionDialog show(Frame owner, HoloBioAcquisitionService service,
                                                HoloBioDhmParams dhmParams,
                                                HoloBioCameraFrameListener frameListener) {
        HoloBioAcquisitionDialog dialog = new HoloBioAcquisitionDialog(owner, service, dhmParams);
        service.setFrameListener(new HoloBioCameraFrameListener() {
            @Override
            public void onFrame(ij.ImagePlus frame, HoloBioAcquisitionSettings settings) {
                if (frameListener != null) {
                    frameListener.onFrame(frame, settings);
                }
            }

            @Override
            public void onAcquisitionLog(String line) {
                dialog.appendLog(line);
                if (frameListener != null) {
                    frameListener.onAcquisitionLog(line);
                }
            }
        });
        dialog.setVisible(true);
        return dialog;
    }
}
