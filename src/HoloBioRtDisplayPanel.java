import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Live viewport for the real-time modules: draws one image scaled to fit.
 *
 * <p>Shared by the RT DHM and RT DLHM windows. Supports inspection without pausing the
 * stream: mouse wheel zooms around the cursor, dragging pans, and a double click resets to
 * the fitted view. Zoom state survives frame updates, so a magnified corner of the spectrum
 * stays put while new frames arrive.
 *
 * <p>Line capture, used by the live phase profile: when armed, a left drag draws a segment
 * in image coordinates instead of panning, a right click clears all segments. The panel
 * only renders the segments it is given; the owning window keeps the authoritative list.
 */
public final class HoloBioRtDisplayPanel extends JPanel {

    private static final double ZOOM_STEP = 1.25, ZOOM_MAX = 32.0;

    /** A measurement ROI in image coordinates (line or axis-aligned rectangle). */
    public static final class ProfileLine {
        public final double x1, y1, x2, y2;
        public final Color  color;
        /** If true: {@code x1,y1} top-left and {@code x2,y2} = width,height. */
        public final boolean rect;
        /** Optional label from shared rois_*.txt (e.g. {@code L1}). */
        public final String name;

        public ProfileLine(double x1, double y1, double x2, double y2, Color color) {
            this(x1, y1, x2, y2, color, false, null);
        }
        private ProfileLine(double x1, double y1, double x2, double y2, Color color,
                            boolean rect, String name) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.color = color; this.rect = rect; this.name = name;
        }
        public static ProfileLine line(double x1, double y1, double x2, double y2, Color c) {
            return new ProfileLine(x1, y1, x2, y2, c, false, null);
        }
        public static ProfileLine line(double x1, double y1, double x2, double y2, Color c, String name) {
            return new ProfileLine(x1, y1, x2, y2, c, false, name);
        }
        public static ProfileLine rect(double x, double y, double w, double h, Color c) {
            return new ProfileLine(x, y, w, h, c, true, null);
        }
        public static ProfileLine rect(double x, double y, double w, double h, Color c, String name) {
            return new ProfileLine(x, y, w, h, c, true, name);
        }
        public String label(int fallbackIndex1) {
            if (name != null && !name.isEmpty()) return name;
            return (rect ? "R" : "L") + fallbackIndex1;
        }
    }

    /** Callback for ROI capture; methods arrive on the event thread. */
    public interface LineCaptureListener {
        void onLine(double x1, double y1, double x2, double y2);
        void onRect(double x, double y, double w, double h);
        void onClear();
    }

    private volatile BufferedImage image;
    private volatile String        hint;

    // ── Line capture state ──────────────────────────────────────────────────
    private volatile java.util.List<ProfileLine> lines = java.util.Collections.emptyList();
    private LineCaptureListener captureListener;
    private boolean captureArmed;
    private boolean captureRect;
    private boolean rubberBand;
    private int     rbX1, rbY1, rbX2, rbY2;   // panel coordinates while dragging

    // ── View transform state ────────────────────────────────────────────────
    private double zoom = 1.0;
    private double viewCx, viewCy;          // image point shown at the panel centre
    private int    lastIw = -1, lastIh = -1;

    // Last transform used to paint, so mouse handlers map coordinates consistently
    private double curScale = 1.0;
    private int    curOx, curOy;

    private int dragX, dragY;

    public HoloBioRtDisplayPanel(String hint) {
        this.hint = hint;
        setBackground(new Color(20, 20, 20));
        setPreferredSize(new Dimension(400, 320));

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(e.getX(), e.getY(), e.getPreciseWheelRotation() < 0);
            }
            @Override public void mousePressed(MouseEvent e) {
                if (captureArmed && javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    if (captureListener != null) captureListener.onClear();
                    return;
                }
                if (captureArmed && javax.swing.SwingUtilities.isLeftMouseButton(e)
                        && image != null) {
                    rubberBand = true;
                    rbX1 = rbX2 = e.getX();
                    rbY1 = rbY2 = e.getY();
                    return;
                }
                dragX = e.getX(); dragY = e.getY();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (rubberBand) {
                    rbX2 = e.getX(); rbY2 = e.getY();
                    repaint();
                    return;
                }
                if (zoom <= 1.0) return;
                viewCx -= (e.getX() - dragX) / curScale;
                viewCy -= (e.getY() - dragY) / curScale;
                dragX = e.getX(); dragY = e.getY();
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!rubberBand) return;
                rubberBand = false;
                rbX2 = e.getX(); rbY2 = e.getY();
                repaint();
                BufferedImage img = image;
                if (img == null || captureListener == null) return;
                if (Math.hypot(rbX2 - rbX1, rbY2 - rbY1) < 5) return;
                double ix1 = clampD((rbX1 - curOx) / curScale, 0, img.getWidth()  - 1);
                double iy1 = clampD((rbY1 - curOy) / curScale, 0, img.getHeight() - 1);
                double ix2 = clampD((rbX2 - curOx) / curScale, 0, img.getWidth()  - 1);
                double iy2 = clampD((rbY2 - curOy) / curScale, 0, img.getHeight() - 1);
                if (captureRect) {
                    double x = Math.min(ix1, ix2), y = Math.min(iy1, iy2);
                    double w = Math.abs(ix2 - ix1), h = Math.abs(iy2 - iy1);
                    if (w < 2 || h < 2) return;
                    captureListener.onRect(x, y, w, h);
                } else {
                    captureListener.onLine(ix1, iy1, ix2, iy2);
                }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) resetView();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void          setImage(BufferedImage img) { image = img; repaint(); }
    public BufferedImage getImage()                  { return image; }
    public void          setHint(String h)           { hint = h; repaint(); }

    /** Arm ROI capture; {@code rectMode} selects rectangle vs line rubber-band. */
    public void setLineCapture(boolean armed, LineCaptureListener listener) {
        setLineCapture(armed, false, listener);
    }

    public void setLineCapture(boolean armed, boolean rectMode, LineCaptureListener listener) {
        captureArmed    = armed;
        captureRect     = rectMode;
        captureListener = listener;
        if (!armed) rubberBand = false;
        setCursor(armed ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR)
                        : java.awt.Cursor.getDefaultCursor());
        repaint();
    }

    public void setCaptureRectMode(boolean rectMode) {
        captureRect = rectMode;
    }

    /** Segments to render on top of the image, in image coordinates. */
    public void setLines(java.util.List<ProfileLine> newLines) {
        lines = (newLines == null) ? java.util.Collections.<ProfileLine>emptyList() : newLines;
        repaint();
    }

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public void resetView() {
        zoom = 1.0;
        BufferedImage img = image;
        if (img != null) { viewCx = img.getWidth() / 2.0; viewCy = img.getHeight() / 2.0; }
        repaint();
    }

    private void zoomAt(int mx, int my, boolean in) {
        BufferedImage img = image;
        if (img == null) return;
        double newZoom = in ? zoom * ZOOM_STEP : zoom / ZOOM_STEP;
        newZoom = Math.max(1.0, Math.min(ZOOM_MAX, newZoom));
        if (newZoom == zoom) return;

        // Keep the image point under the cursor stationary while the scale changes
        double ix = (mx - curOx) / curScale;
        double iy = (my - curOy) / curScale;
        double fit = fitScale(img);
        double newScale = fit * newZoom;
        viewCx = ix + (getWidth()  / 2.0 - mx) / newScale;
        viewCy = iy + (getHeight() / 2.0 - my) / newScale;
        zoom = newZoom;
        if (zoom == 1.0) { viewCx = img.getWidth() / 2.0; viewCy = img.getHeight() / 2.0; }
        repaint();
    }

    private double fitScale(BufferedImage img) {
        return Math.min((double) getWidth()  / img.getWidth(),
                        (double) getHeight() / img.getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        BufferedImage img = image;
        int pw = getWidth(), ph = getHeight();
        if (img == null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(28, 30, 32));
            g2.fillRect(0, 0, pw, ph);
            int cx = pw / 2, cy = ph / 2 - 18;
            int camW = 44, camH = 28;
            g2.setColor(new Color(90, 98, 106));
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawRoundRect(cx - camW / 2, cy - camH / 2, camW, camH, 6, 6);
            g2.drawOval(cx - 8, cy - 8, 16, 16);
            g2.fillOval(cx - 3, cy - camH / 2 - 6, 6, 5);
            g2.setColor(new Color(210, 214, 218));
            g2.setFont(getFont() != null ? getFont().deriveFont(Font.PLAIN, 13f) : g2.getFont());
            FontMetrics fm = g2.getFontMetrics();
            String msg = "Start capture to see live output";
            g2.drawString(msg, cx - fm.stringWidth(msg) / 2, cy + camH / 2 + 22);
            if (hint != null && !hint.isEmpty()) {
                g2.setColor(new Color(140, 148, 156));
                g2.setFont(g2.getFont().deriveFont(11f));
                fm = g2.getFontMetrics();
                g2.drawString(hint, cx - fm.stringWidth(hint) / 2, cy + camH / 2 + 40);
            }
            g2.dispose();
            return;
        }
        int iw = img.getWidth(), ih = img.getHeight();
        if (iw != lastIw || ih != lastIh) {
            // New source geometry: any pan/zoom framing refers to nothing, start fitted
            lastIw = iw; lastIh = ih;
            zoom = 1.0; viewCx = iw / 2.0; viewCy = ih / 2.0;
        }

        double sc = fitScale(img) * zoom;
        int    dw = (int) Math.round(iw * sc), dh = (int) Math.round(ih * sc);

        int ox, oy;
        if (dw <= pw) {
            ox = (pw - dw) / 2;
        } else {
            ox = (int) Math.round(pw / 2.0 - viewCx * sc);
            ox = Math.max(pw - dw, Math.min(0, ox));
        }
        if (dh <= ph) {
            oy = (ph - dh) / 2;
        } else {
            oy = (int) Math.round(ph / 2.0 - viewCy * sc);
            oy = Math.max(ph - dh, Math.min(0, oy));
        }
        // Store the clamped transform, and fold the clamp back into the view centre so the
        // next zoom or drag starts from what is actually on screen
        curScale = sc; curOx = ox; curOy = oy;
        if (dw > pw) viewCx = (pw / 2.0 - ox) / sc;
        if (dh > ph) viewCy = (ph / 2.0 - oy) / sc;

        Graphics2D g2 = (Graphics2D) g;
        // Bilinear keeps the fitted view smooth; nearest keeps individual bins visible when
        // inspecting the spectrum up close
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            sc >= 4.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                      : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(img, ox, oy, dw, dh, null);

        java.util.List<ProfileLine> ls = lines;
        if (!ls.isEmpty() || rubberBand) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new java.awt.BasicStroke(1.8f));
            int idx = 1;
            for (ProfileLine ln : ls) {
                g2.setColor(ln.color);
                if (ln.rect) {
                    int px = ox + (int) Math.round(ln.x1 * sc);
                    int py = oy + (int) Math.round(ln.y1 * sc);
                    int pwR = Math.max(1, (int) Math.round(ln.x2 * sc));
                    int phR = Math.max(1, (int) Math.round(ln.y2 * sc));
                    g2.drawRect(px, py, pwR, phR);
                    g2.drawString("R" + idx++, px + 4, py - 4);
                } else {
                    int px1 = ox + (int) Math.round(ln.x1 * sc), py1 = oy + (int) Math.round(ln.y1 * sc);
                    int px2 = ox + (int) Math.round(ln.x2 * sc), py2 = oy + (int) Math.round(ln.y2 * sc);
                    g2.drawLine(px1, py1, px2, py2);
                    g2.fillOval(px1 - 3, py1 - 3, 6, 6);
                    g2.drawString("L" + idx++, px2 + 5, py2 - 5);
                }
            }
            if (rubberBand) {
                g2.setColor(new Color(255, 255, 255, 180));
                if (captureRect) {
                    int rx = Math.min(rbX1, rbX2), ry = Math.min(rbY1, rbY2);
                    g2.drawRect(rx, ry, Math.abs(rbX2 - rbX1), Math.abs(rbY2 - rbY1));
                } else {
                    g2.drawLine(rbX1, rbY1, rbX2, rbY2);
                }
            }
        }

        if (zoom > 1.0) {
            String badge = String.format(Locale.US, "×%.3g", zoom);
            FontMetrics fm = g2.getFontMetrics();
            int bw = fm.stringWidth(badge) + 10, bh = fm.getHeight() + 4;
            g2.setColor(new Color(0, 0, 0, 140));
            g2.fillRoundRect(pw - bw - 6, 6, bw, bh, 8, 8);
            g2.setColor(new Color(230, 230, 230));
            g2.drawString(badge, pw - bw - 1, 6 + fm.getAscent() + 2);
        }
    }
}
