import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.geom.Path2D;
import java.util.Locale;

/**
 * Live intensity histogram of the hologram as acquired, for setting exposure and gain.
 *
 * <p>The capture thread calls {@link #sample} with every raw grey frame (0…255, before any
 * display stretch); at most a few times a second that frame is binned into 256 levels. The
 * sidebar shows the hologram mean continuously, and the button opens a histogram window that
 * follows the latest sample while it is visible.
 *
 * <p><b>Why not Fiji's Plot window.</b> The first version used {@code ij.gui.Plot} /
 * {@code PlotWindow}. On the development machine (Intel Iris Xe) redrawing it at 4 Hz and
 * opening it again after closing crashed Fiji inside the Intel graphics driver
 * ({@code igxelpicd64.dll}, access violation). PlotWindow renders through ImageJ's
 * {@code createImage(MemoryImageSource)} path into a heavyweight AWT canvas — the path already
 * found fragile on that GPU — and each reopen creates and destroys a native window. This window
 * is created once, hidden rather than disposed when closed, and paints vectors straight into a
 * Swing panel: no images, no AWT canvas, and nothing in {@code paintComponent} that changes
 * component state.
 */
final class HoloBioRtHistogram {

    private static final long SAMPLE_EVERY_NS = 250_000_000L;

    /** One measured frame. Immutable once published, so the EDT can read it safely. */
    private static final class Stats {
        final double[] counts = new double[256];
        double mean, sd;
        int min = 255, max = 0;
        int width, height;
    }

    private volatile Stats latest;
    private long lastSampleNs;

    private final JLabel lblMean = HoloBioUiStyle.fieldLabel("Hologram mean intensity: —");
    private final JButton btn = HoloBioUiStyle.secondaryButton("Histogram");
    private JDialog window;
    private HistogramPanel panel;

    HoloBioRtHistogram() {
        btn.setToolTipText("Live intensity histogram of the acquired hologram");
        btn.addActionListener(e -> open());
        lblMean.setToolTipText("Mean grey level of the raw frame, 0–255");
        // Refresh the label and the open window from the EDT, never from the capture thread.
        Timer t = new Timer(250, e -> refresh());
        t.start();
    }

    /** Button and mean readout, one row, for the Optics section. */
    JPanel row() {
        JPanel row = new JPanel(new BorderLayout(HoloBioUiStyle.SPACE_3, 0));
        row.setOpaque(false);
        row.add(btn, BorderLayout.WEST);
        row.add(lblMean, BorderLayout.CENTER);
        return row;
    }

    /** Called by the capture thread with each raw frame; cheap when not due. */
    void sample(float[] gray, int w, int h) {
        long now = System.nanoTime();
        if (now - lastSampleNs < SAMPLE_EVERY_NS) return;
        lastSampleNs = now;
        int n = w * h;
        if (gray == null || n <= 0 || gray.length < n) return;
        Stats s = new Stats();
        s.width = w;
        s.height = h;
        double sum = 0, sum2 = 0;
        for (int i = 0; i < n; i++) {
            int v = Math.round(gray[i]);
            if (v < 0) v = 0; else if (v > 255) v = 255;
            s.counts[v]++;
            sum += gray[i];
            sum2 += (double) gray[i] * gray[i];
            if (v < s.min) s.min = v;
            if (v > s.max) s.max = v;
        }
        s.mean = sum / n;
        s.sd = Math.sqrt(Math.max(0, sum2 / n - s.mean * s.mean));
        latest = s;
    }

    /** Forget the last frame, e.g. when the source stops. */
    void clear() {
        latest = null;
    }

    private void open() {
        if (window == null) {
            Window owner = SwingUtilities.getWindowAncestor(btn);
            window = new JDialog(owner, "Hologram histogram");
            window.setModalityType(java.awt.Dialog.ModalityType.MODELESS);
            // Hide, never dispose: the native window is made once and reused.
            window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            panel = new HistogramPanel();
            window.getContentPane().add(panel);
            window.pack();
            window.setLocationRelativeTo(owner);
        }
        panel.setStats(latest);
        window.setVisible(true);
        window.toFront();
    }

    private void refresh() {
        Stats s = latest;
        lblMean.setText(s == null ? "Hologram mean intensity: —"
                : String.format(Locale.US, "Hologram mean intensity: %.1f", s.mean));
        if (window != null && window.isVisible()) panel.setStats(s);
    }

    /** Bars, axes and the summary, drawn as vectors. */
    private static final class HistogramPanel extends JPanel {
        private static final Color BAR = new Color(0x3D6E84);
        private static final Color GRID = new Color(0xE3E7EA);
        private Stats stats;

        HistogramPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(620, 360));
        }

        /** Repaints only when the data actually changed. */
        void setStats(Stats s) {
            if (s == stats) return;
            stats = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setFont(HoloBioUiStyle.fontBody());
                FontMetrics fm = g2.getFontMetrics();
                int left = 64, right = 18, top = 56, bottom = 44;
                int w = getWidth() - left - right, h = getHeight() - top - bottom;
                if (w < 40 || h < 40) return;
                Stats s = stats;

                // Title line: summary
                g2.setColor(HoloBioUiStyle.TEXT);
                if (s == null) {
                    g2.drawString("No frame yet. Press Start.", left, 24);
                } else {
                    g2.drawString(String.format(Locale.US,
                            "Hologram mean %.1f   SD %.1f   Min %d   Max %d   (%d×%d)",
                            s.mean, s.sd, s.min, s.max, s.width, s.height), left, 24);
                }

                double peak = 1;
                if (s != null) for (double c : s.counts) peak = Math.max(peak, c);
                peak *= 1.08;

                // Grid and axes
                g2.setColor(GRID);
                for (int k = 1; k <= 4; k++) {
                    int y = top + h - (int) Math.round(h * k / 4.0);
                    g2.drawLine(left, y, left + w, y);
                }
                g2.setColor(HoloBioUiStyle.TEXT_MUTED);
                g2.drawRect(left, top, w, h);
                for (int v = 0; v <= 250; v += 50) {
                    int x = left + (int) Math.round(w * v / 255.0);
                    g2.drawLine(x, top + h, x, top + h + 4);
                    String t = String.valueOf(v);
                    g2.drawString(t, x - fm.stringWidth(t) / 2, top + h + 4 + fm.getAscent());
                }
                for (int k = 0; k <= 4; k++) {
                    int y = top + h - (int) Math.round(h * k / 4.0);
                    String t = String.format(Locale.US, "%.0f", peak * k / 4.0);
                    g2.drawString(t, left - 6 - fm.stringWidth(t), y + fm.getAscent() / 2 - 1);
                }
                String xl = "Grey level";
                g2.drawString(xl, left + (w - fm.stringWidth(xl)) / 2, getHeight() - 8);

                // Histogram as one step outline, filled
                if (s != null) {
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(left, top + h);
                    for (int i = 0; i < 256; i++) {
                        double x0 = left + w * i / 256.0, x1 = left + w * (i + 1) / 256.0;
                        double y = top + h - h * s.counts[i] / peak;
                        path.lineTo(x0, y);
                        path.lineTo(x1, y);
                    }
                    path.lineTo(left + w, top + h);
                    path.closePath();
                    g2.setColor(new Color(0x3D6E84 | 0x40000000, true));
                    g2.fill(path);
                    g2.setColor(BAR);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(path);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
