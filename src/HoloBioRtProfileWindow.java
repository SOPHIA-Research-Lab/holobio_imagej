import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Live plot of phase profiles along user-drawn segments, updated once per reconstructed
 * frame.
 *
 * <p>Y axis auto-zooms to the data (with padding) so small Δφ features are readable.
 * Dashed guides mark the 5% φ_low / φ_high used for Δφ. Incoming samples are already
 * in {@code [0, 2π]} (Python QPI 8-bit map).
 */
public final class HoloBioRtProfileWindow extends JFrame {

    /** One profile: wrapped phase samples (radians, NaN = outside data) at a fixed µm step. */
    public static final class Curve {
        final double[] values;
        final double   stepUm;
        final Color    color;
        public Curve(double[] values, double stepUm, Color color) {
            this.values = values;
            this.stepUm = stepUm;
            this.color  = color;
        }
    }

    private final PlotPanel plot = new PlotPanel();

    public HoloBioRtProfileWindow() {
        this("RT DHM — Live Phase Profile");
    }

    public HoloBioRtProfileWindow(String title) {
        super(title != null ? title : "Phase Profile");
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        add(plot);
        setSize(720, 440);
        HoloBioUiStyle.polish(this);
    }

    /** Replace the plotted curves; safe to call from the reconstruction thread. */
    public void update(List<Curve> curves) {
        plot.curves = (curves == null) ? Collections.<Curve>emptyList() : curves;
        plot.repaint();
    }

    /** Show / un-minimize / raise (fixes stuck-behind or iconified profile windows). */
    public void showFront() {
        setVisible(true);
        setExtendedState(getExtendedState() & ~ICONIFIED);
        toFront();
        requestFocus();
    }

    private static final class PlotPanel extends JPanel {

        volatile List<Curve> curves = Collections.emptyList();

        PlotPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(720, 440));
        }

        private static double niceStep(double target) {
            if (!(target > 0) || Double.isNaN(target)) return 1;
            double pow10 = Math.pow(10, Math.floor(Math.log10(target)));
            double n = target / pow10;
            double f = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
            return f * pow10;
        }

        /** Samples are already [0, 2π] from the 8-bit cyclic phase map. */
        private static double toDisplay(double v) {
            return v;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            int pw = getWidth(), ph = getHeight();
            int left = 88, right = 18, top = 48, bottom = 52;
            int plotW = pw - left - right, plotH = ph - top - bottom;
            if (plotW < 40 || plotH < 40) return;

            List<Curve> cs = curves;
            double maxUm = 0;
            double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
            for (Curve c : cs) {
                if (c.values.length > 1) {
                    maxUm = Math.max(maxUm, c.stepUm * (c.values.length - 1));
                }
                for (double v : c.values) {
                    if (Double.isNaN(v)) continue;
                    double d = toDisplay(v);
                    if (d < yMin) yMin = d;
                    if (d > yMax) yMax = d;
                }
            }
            if (!(yMax > yMin)) {
                yMin = 0;
                yMax = 2 * Math.PI;
            } else {
                // Matplotlib-style pad; do not clamp to [0, 2π] so a 4.4 rad peak is not
                // squeezed against a 4.25 axis like the 8-bit display map.
                double pad = Math.max(0.08, 0.10 * (yMax - yMin));
                yMin = yMin - pad;
                yMax = yMax + pad;
                if (yMax - yMin < 0.5) {
                    double mid = 0.5 * (yMin + yMax);
                    yMin = mid - 0.25;
                    yMax = mid + 0.25;
                }
                // Snap to 0.25 rad like Matplotlib so the top tick can read 4.50
                double snap = 0.25;
                yMin = Math.floor(yMin / snap) * snap;
                yMax = Math.ceil(yMax / snap) * snap;
                if (yMax <= yMin) yMax = yMin + snap;
            }
            double ySpan = Math.max(1e-6, yMax - yMin);

            // ── Frame ─────────────────────────────────────────────────────
            g2.setColor(new Color(235, 235, 235));
            g2.fillRect(left, top, plotW, plotH);
            g2.setColor(new Color(140, 140, 140));
            g2.drawRect(left, top, plotW, plotH);

            Font tickFont = HoloBioUiStyle.fontHint();
            Font titleFont = HoloBioUiStyle.fontBody();
            g2.setFont(tickFont);
            FontMetrics fm = g2.getFontMetrics();

            // Y grid + labels (Python QPI uses 0.25 rad)
            double yStep = ySpan <= 4 ? 0.25 : niceStep(ySpan / 8);
            double y0 = Math.ceil(yMin / yStep) * yStep;
            for (double t = y0; t <= yMax + 1e-9; t += yStep) {
                int y = top + (int) Math.round(plotH * (1.0 - (t - yMin) / ySpan));
                g2.setColor(new Color(220, 226, 230));
                g2.drawLine(left + 1, y, left + plotW - 1, y);
                g2.setColor(HoloBioUiStyle.TEXT_MUTED);
                String lab = String.format(Locale.US, "%.2f", t);
                g2.drawString(lab, left - 6 - fm.stringWidth(lab), y + fm.getAscent() / 2 - 1);
            }

            // X grid + labels (Python QPI uses 2 µm)
            double xStep = maxUm > 0 ? (maxUm <= 20 ? 2 : niceStep(maxUm / 8)) : 1;
            for (double t = 0; maxUm > 0 && t <= maxUm * 1.0001; t += xStep) {
                int x = left + (int) Math.round(plotW * t / maxUm);
                if (t > 0 && t < maxUm * 0.999) {
                    g2.setColor(new Color(210, 210, 210));
                    g2.drawLine(x, top + 1, x, top + plotH - 1);
                }
                g2.setColor(new Color(70, 70, 70));
                String lab = String.format(Locale.US, "%.0f", t);
                g2.drawString(lab, x - fm.stringWidth(lab) / 2, top + plotH + fm.getAscent() + 4);
            }

            g2.setFont(HoloBioUiStyle.fontTitle());
            fm = g2.getFontMetrics();
            g2.setColor(HoloBioUiStyle.TEXT);
            String plotTitle = "Phase Profiles";
            g2.drawString(plotTitle,
                    left + (plotW - fm.stringWidth(plotTitle)) / 2,
                    Math.max(fm.getAscent() + 4, top - 12));

            g2.setFont(titleFont);
            fm = g2.getFontMetrics();
            String xTitle = "Distance [µm]";
            g2.drawString(xTitle, left + (plotW - fm.stringWidth(xTitle)) / 2,
                          ph - fm.getDescent() - 4);
            String yTitle = "Phase [rad]";
            AffineTransform saved = g2.getTransform();
            int ycx = 16;
            int ycy = top + plotH / 2;
            g2.translate(ycx, ycy);
            g2.rotate(-Math.PI / 2.0);
            g2.drawString(yTitle, -fm.stringWidth(yTitle) / 2, fm.getAscent() / 2);
            g2.setTransform(saved);

            if (cs.isEmpty() || maxUm <= 0) {
                g2.setColor(Color.GRAY);
                String hint = "Load ROIs or drag a line on the reconstruction view";
                g2.drawString(hint, left + (plotW - fm.stringWidth(hint)) / 2,
                              top + plotH / 2);
                return;
            }

            // ── Curves + φ_low / φ_high guides ────────────────────────────
            g2.setClip(left, top, plotW + 1, plotH + 1);
            int idx = 1;
            int legendY = top + 10;
            for (Curve c : cs) {
                // Stats on display-shifted samples
                double[] forStats = new double[c.values.length];
                for (int k = 0; k < c.values.length; k++) {
                    double v = c.values[k];
                    forStats[k] = Double.isNaN(v) ? Double.NaN : toDisplay(v);
                }
                double[] st = HoloBioQpiSpeckleMath.phaseStats(forStats);
                double low = st[0], high = st[1], dphi = st[2];

                if (!Double.isNaN(low) && !Double.isNaN(high)) {
                    g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
                    g2.setColor(new Color(c.color.getRed(), c.color.getGreen(),
                            c.color.getBlue(), 140));
                    int yLo = top + (int) Math.round(plotH * (1.0 - (low - yMin) / ySpan));
                    int yHi = top + (int) Math.round(plotH * (1.0 - (high - yMin) / ySpan));
                    g2.drawLine(left, yLo, left + plotW, yLo);
                    g2.drawLine(left, yHi, left + plotW, yHi);
                }

                // Matplotlib default is ~1 px, square caps; AA on a thick round stroke
                // visually low-passes speckle that Python still shows.
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_OFF);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                                    RenderingHints.VALUE_STROKE_PURE);
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g2.setColor(c.color);
                int n = c.values.length;
                int prevX = 0, prevY = 0;
                double prevD = 0;
                boolean prevOk = false;
                for (int k = 0; k < n; k++) {
                    double v = c.values[k];
                    if (Double.isNaN(v)) { prevOk = false; continue; }
                    double d = toDisplay(v);
                    int x = left + (int) Math.round(plotW * (c.stepUm * k) / maxUm);
                    int y = top + (int) Math.round(plotH * (1.0 - (d - yMin) / ySpan));
                    if (prevOk && Math.abs(d - prevD) > Math.PI) prevOk = false;
                    if (prevOk) g2.drawLine(prevX, prevY, x, y);
                    prevX = x; prevY = y; prevD = d; prevOk = true;
                }
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);

                // Legend chip with Δφ
                g2.setClip(null);
                g2.setFont(tickFont);
                fm = g2.getFontMetrics();
                String lab = Double.isNaN(dphi)
                        ? ("L" + idx)
                        : String.format(Locale.US, "L%d  Δφ=%.3f rad", idx, dphi);
                int chipW = 18 + fm.stringWidth(lab) + 8;
                int lx = left + 8;
                g2.setColor(new Color(255, 255, 255, 210));
                g2.fillRoundRect(lx - 2, legendY - 2, chipW, fm.getHeight() + 2, 6, 6);
                g2.setColor(c.color);
                g2.fillRect(lx, legendY + fm.getAscent() / 2 - 1, 12, 3);
                g2.setColor(new Color(40, 40, 40));
                g2.drawString(lab, lx + 16, legendY + fm.getAscent());
                legendY += fm.getHeight() + 4;
                idx++;
                g2.setClip(left, top, plotW + 1, plotH + 1);
            }
            g2.setClip(null);
        }
    }
}
