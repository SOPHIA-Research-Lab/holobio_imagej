import ij.gui.Plot;
import ij.gui.PlotWindow;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.Locale;

/**
 * Live intensity histogram of the hologram as acquired, for setting exposure and gain.
 *
 * <p>The capture thread calls {@link #sample} with every raw grey frame (0…255, before any
 * display stretch); at most a few times a second that frame is binned into 256 levels. The
 * sidebar shows the mean continuously, and the button opens Fiji's own Plot window, which is
 * redrawn from the latest sample while it stays open.
 */
final class HoloBioRtHistogram {

    private static final long SAMPLE_EVERY_NS = 250_000_000L;

    /** One measured frame. Immutable, so the EDT can read it while the next is built. */
    private static final class Stats {
        final double[] counts = new double[256];
        double mean, sd;
        int min = 255, max = 0;
        double saturated, dark;   // fractions at 255 and at 0
        int width, height;
    }

    private volatile Stats latest;
    private long lastSampleNs;

    private final JLabel lblMean = HoloBioUiStyle.fieldLabel("Mean intensity: —");
    private final JButton btn = HoloBioUiStyle.secondaryButton("Histogram");
    private PlotWindow window;
    private Stats drawn;

    HoloBioRtHistogram() {
        btn.setToolTipText("Live intensity histogram of the acquired hologram (Fiji plot)");
        btn.addActionListener(e -> open());
        lblMean.setToolTipText("Mean grey level of the raw frame, 0–255");
        // Refresh the label and any open plot from the EDT, never from the capture thread.
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
        s.saturated = s.counts[255] / n;
        s.dark = s.counts[0] / n;
        latest = s;
    }

    /** Forget the last frame, e.g. when the source stops. */
    void clear() {
        latest = null;
    }

    private void open() {
        if (window != null && !window.isVisible()) window = null;   // closed by the user
        if (window == null) {
            drawn = null;
            window = build(latest).show();
        } else {
            window.toFront();
        }
    }

    private void refresh() {
        Stats s = latest;
        lblMean.setText(s == null ? "Mean intensity: —"
                : String.format(Locale.US, "Mean intensity: %.1f", s.mean));
        if (window == null) return;
        if (!window.isVisible()) {        // closed from its own title bar
            window = null;
            return;
        }
        if (s != null && s != drawn) {
            window.drawPlot(build(s));
            drawn = s;
        }
    }

    private static Plot build(Stats s) {
        Plot p = new Plot("Hologram histogram", "Grey level", "Pixels");
        double[] x = new double[256];
        for (int i = 0; i < 256; i++) x[i] = i;
        if (s == null) {
            p.setLimits(0, 255, 0, 1);
            p.addLabel(0.02, 0.08, "No frame yet. Press Start.");
            return p;
        }
        double peak = 1;
        for (double c : s.counts) peak = Math.max(peak, c);
        p.setLimits(0, 255, 0, peak * 1.08);
        p.setColor(new Color(0x3D6E84));
        p.add("bar", x, s.counts);
        p.setColor(Color.BLACK);
        p.addLabel(0.02, 0.08, String.format(Locale.US,
                "Mean %.1f   SD %.1f   Min %d   Max %d   (%d×%d)",
                s.mean, s.sd, s.min, s.max, s.width, s.height));
        if (s.saturated > 0.001 || s.dark > 0.001) {
            // Clipped pixels carry no fringe information: say so where it is decided.
            p.setColor(new Color(0xB42318));
            p.addLabel(0.02, 0.15, String.format(Locale.US,
                    "Saturated (255): %.2f%%   Black (0): %.2f%%",
                    100 * s.saturated, 100 * s.dark));
        }
        return p;
    }
}
