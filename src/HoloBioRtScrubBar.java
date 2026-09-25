import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/**
 * YouTube-style playback scrubber: thin track, teal fill, round knob on hover/drag.
 */
final class HoloBioRtScrubBar extends JComponent {

    static final Color TEAL = HoloBioUiStyle.PRIMARY;

    private int     knownFrames;
    private int     delivered;
    private boolean enabledScrub;
    private boolean hover;
    private boolean dragging;
    private IntConsumer onSeek;

    HoloBioRtScrubBar() {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 18));
        setMinimumSize(new Dimension(80, 16));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) {
                if (!dragging) { hover = false; repaint(); }
            }
            @Override public void mousePressed(MouseEvent e) {
                if (!enabledScrub) return;
                dragging = true;
                fireSeek(e.getX());
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (!dragging) return;
                fireSeek(e.getX());
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragging = false;
                hover = contains(e.getPoint());
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void setOnSeek(IntConsumer onSeek) {
        this.onSeek = onSeek;
    }

    void setProgress(int delivered, int knownFrames, boolean seekable) {
        this.delivered    = Math.max(0, delivered);
        this.knownFrames  = knownFrames;
        this.enabledScrub = seekable && knownFrames > 1;
        setEnabled(enabledScrub);
        setCursor(enabledScrub
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        repaint();
    }

    private void fireSeek(int x) {
        if (!enabledScrub || onSeek == null || knownFrames <= 1) return;
        int w = Math.max(1, getWidth());
        double t = Math.max(0.0, Math.min(1.0, x / (double) w));
        int frame = (int) Math.round(t * (knownFrames - 1));
        onSeek.accept(frame);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        int trackH = (hover || dragging) ? 6 : 3;
        int y = (h - trackH) / 2;
        int arc = trackH;
        g2.setColor(new Color(60, 60, 60, 90));
        g2.fillRoundRect(0, y, w, trackH, arc, arc);
        double frac = (knownFrames > 0) ? Math.min(1.0, delivered / (double) knownFrames) : 0.0;
        int fw = (int) Math.round(w * frac);
        if (fw > 0) {
            g2.setColor(TEAL);
            g2.fillRoundRect(0, y, Math.max(trackH, fw), trackH, arc, arc);
        }
        if (enabledScrub && (hover || dragging) && knownFrames > 0) {
            int cx = (int) Math.round(frac * w);
            cx = Math.max(6, Math.min(w - 6, cx));
            g2.setColor(Color.WHITE);
            g2.fillOval(cx - 6, h / 2 - 6, 12, 12);
            g2.setColor(TEAL);
            g2.fillOval(cx - 4, h / 2 - 4, 8, 8);
        }
        g2.dispose();
    }
}
