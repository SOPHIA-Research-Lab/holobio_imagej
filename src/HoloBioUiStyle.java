import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Shared Swing design system for HoloBio Fiji UIs.
 *
 * <p>One grid, used by every dialog — do not special-case window metrics.
 * Spacing: 4 / 8 / 12 / 16 / 24. Controls: 26 px tall. Numeric fields: 72 px wide.
 */
public final class HoloBioUiStyle {

    // ── Spacing ────────────────────────────────────────────────────────────
    public static final int SPACE_1 = 4;   // hairline
    public static final int SPACE_2 = 8;   // label → input
    public static final int SPACE_3 = 12;  // related controls
    public static final int SPACE_4 = 16;  // groups / content margin
    public static final int SPACE_5 = 24;  // major sections

    /** Inner padding of every plugin dialog / tool window. */
    public static final int CONTENT_MARGIN = 8;

    /** Shared control height (text fields, combos, buttons). */
    public static final int CONTROL_H = 26;
    /** Transport / compact chrome. */
    public static final int CONTROL_H_SM = 24;
    public static final int RADIUS = 4;

    /** Fixed label column so every input in a section starts on the same x. */
    public static final int FORM_LABEL_W = 168;
    /** Narrow sidebars (RT DHM / DLHM). */
    public static final int FORM_LABEL_W_SM = 120;
    /** Numeric values (2.400, 40, 1.00, …). */
    public static final int FORM_FIELD_W = 72;
    /** Short toolbar numbers (factor, r px). */
    public static final int FORM_FIELD_W_SM = 44;
    /** Dropdowns and longer text. */
    public static final int FORM_COMBO_W = 96;
    /** Objective magnification — same wording and two-decimal display everywhere. */
    public static final String MAG_LABEL = "Lateral magnification (M)";
    public static String formatMag(double m) {
        return String.format("%.2f", m);
    }

    /**
     * Units offered wherever L, Z, r or a propagation distance is edited. Every model
     * stays in micrometres; these only change how the value is typed and displayed.
     */
    public static final String[] DISTANCE_UNITS = { "µm", "mm", "cm" };

    /** Micrometres in one {@code unit}. Anything unrecognised is treated as µm. */
    public static double umPerDistanceUnit(String unit) {
        if ("mm".equals(unit)) {
            return 1000.0;
        }
        if ("cm".equals(unit)) {
            return 10000.0;
        }
        return 1.0;
    }

    public static final Insets PANEL_PADDING = new Insets(
        CONTENT_MARGIN, CONTENT_MARGIN, CONTENT_MARGIN, CONTENT_MARGIN);
    public static final Insets ROW_GAP = new Insets(0, 0, SPACE_3, 0);

    // ── Colour (slate + a whisper of teal) ─────────────────────────────────
    public static final Color TEXT = new Color(0x1F2933);
    public static final Color TEXT_MUTED = new Color(0x3E4C59);
    public static final Color TEXT_HINT = new Color(0x52606D);
    public static final Color TEXT_ON_PRIMARY = Color.WHITE;
    /** Clearly lighter than TEXT so disabled chrome is not mistaken for active. */
    public static final Color TEXT_DISABLED = new Color(0x9AA5B1);

    public static final Color BORDER = new Color(0xD0D7DC);
    public static final Color BORDER_FOCUS = new Color(0x6A8FA3);
    public static final Color SURFACE = Color.WHITE;
    public static final Color SURFACE_HOVER = new Color(0xF2F5F7);
    public static final Color SURFACE_PRESSED = new Color(0xE4E8EB);
    public static final Color SURFACE_DISABLED = new Color(0xD5DCE2);
    public static final Color BORDER_DISABLED = new Color(0xC5CDD4);

    /** Primary = muted steel-teal fill, white label. */
    public static final Color PRIMARY = new Color(0x3D6E84);
    public static final Color PRIMARY_HOVER = new Color(0x355F73);
    public static final Color PRIMARY_PRESSED = new Color(0x2C5163);
    public static final Color PRIMARY_DISABLED = new Color(0xC5D2D8);
    public static final Color PRIMARY_LINE = new Color(0x355F73);
    public static final Color PRIMARY_LINE_HOVER = new Color(0x2C5163);
    /** Light wash for focused numeric fields (not the solid primary fill). */
    public static final Color FIELD_FOCUS_BG = new Color(0xEEF3F6);
    public static final Color FIELD_LINE = new Color(0xC5CED4);

    public static final Color DANGER = new Color(0xB42318);
    public static final Color DANGER_HOVER = new Color(0x912018);
    public static final Color DANGER_PRESSED = new Color(0x7A1813);
    public static final Color DANGER_DISABLED = new Color(0xE4A8A3);

    public static final Color RECORD = new Color(0xB42318);
    public static final Color FIELD_BG = Color.WHITE;
    public static final Color FIELD_ERROR = new Color(0xB42318);

    public enum ButtonRole { PRIMARY, SECONDARY, TERTIARY, DESTRUCTIVE }

    private HoloBioUiStyle() {}

    // ── Typography ─────────────────────────────────────────────────────────

    public static Font font(int style, float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(style, size);
    }

    public static Font fontTitle() { return font(Font.BOLD, 14f); }
    public static Font fontSection() { return font(Font.BOLD, 12f); }
    public static Font fontBody() { return font(Font.PLAIN, 12f); }
    public static Font fontBodyBold() { return font(Font.BOLD, 12f); }
    public static Font fontHint() { return font(Font.PLAIN, 11f); }

    public static JLabel mainTitle(String text) {
        JLabel title = new JLabel(text, SwingConstants.LEFT);
        title.setFont(fontTitle());
        title.setForeground(TEXT);
        return title;
    }

    public static JLabel sectionTitle(String text) {
        JLabel title = new JLabel(text, SwingConstants.LEFT);
        title.setFont(fontSection());
        title.setForeground(TEXT);
        return title;
    }

    public static JLabel workflowSteps(String text) {
        JLabel steps = new JLabel(text, SwingConstants.LEFT);
        steps.setFont(fontBody());
        steps.setForeground(TEXT_MUTED);
        return steps;
    }

    public static JLabel hintHtml(String html, int widthPx) {
        JLabel lab = new JLabel("<html><p style='width:" + widthPx + "px;color:#616E7C'>" + html + "</p></html>");
        lab.setFont(fontHint());
        lab.setForeground(TEXT_HINT);
        return lab;
    }

    public static JLabel statusHtml(String html) {
        JLabel status = new JLabel("<html>" + html + "</html>", SwingConstants.LEFT);
        status.setFont(fontBody());
        status.setForeground(TEXT);
        return status;
    }

    public static JLabel footerNote(String text) {
        JLabel note = new JLabel(text, SwingConstants.LEFT);
        note.setFont(fontHint());
        note.setForeground(TEXT_MUTED);
        return note;
    }

    public static JLabel fieldLabel(String text) {
        JLabel lab = new JLabel(text);
        lab.setFont(fontBody());
        lab.setForeground(TEXT);
        return lab;
    }

    // ── Layout helpers ─────────────────────────────────────────────────────

    public static JPanel flowLeft() {
        return flowLeft(SPACE_2, SPACE_1);
    }

    public static JPanel flowLeft(int hgap, int vgap) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, hgap, vgap));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    public static JPanel flowRight() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_2, SPACE_1));
        p.setOpaque(false);
        return p;
    }

    public static JPanel paddedPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(contentPad());
        panel.setOpaque(false);
        return panel;
    }

    public static Border contentPad() {
        return emptyPad(CONTENT_MARGIN, CONTENT_MARGIN, CONTENT_MARGIN, CONTENT_MARGIN);
    }

    public static Border emptyPad(Insets in) {
        return BorderFactory.createEmptyBorder(in.top, in.left, in.bottom, in.right);
    }

    public static Border emptyPad(int t, int l, int b, int r) {
        return BorderFactory.createEmptyBorder(t, l, b, r);
    }

    public static void styleScroll(JScrollPane scroll) {
        if (scroll == null) {
            return;
        }
        scroll.setBorder(null);
        if (scroll.getVerticalScrollBar() != null) {
            scroll.getVerticalScrollBar().setUnitIncrement(16);
        }
    }

    public static GridBagConstraints westGbc(int gridy) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = gridy;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = ROW_GAP;
        return gbc;
    }

    /** Label + control on one row, aligned to the form grid. */
    public static JPanel formRow(String labelText, JComponent field) {
        return formRow(labelText, field, FORM_LABEL_W);
    }

    public static JPanel formRow(String labelText, JComponent field, int labelW) {
        JPanel row = flowLeft(SPACE_3, 0);
        JLabel label = fieldLabel(labelText);
        label.setPreferredSize(new Dimension(labelW, CONTROL_H));
        label.setMinimumSize(new Dimension(labelW, CONTROL_H));
        label.setLabelFor(field);
        row.add(label);
        sizeField(field, FORM_FIELD_W);
        row.add(field);
        row.setBorder(emptyPad(0, 0, SPACE_1, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_H + SPACE_1));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    /** Label stacked above the control — for narrow sidebars. */
    public static JPanel stackedField(String labelText, JComponent field) {
        JPanel col = new JPanel(new GridBagLayout());
        col.setOpaque(false);
        col.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = westGbc(0);
        c.insets = new Insets(0, 0, SPACE_2, 0);
        JLabel lab = fieldLabel(labelText);
        lab.setLabelFor(field);
        col.add(lab, c);
        c.gridy = 1;
        c.insets = new Insets(0, 0, 0, 0);
        styleField(field);
        col.add(field, c);
        return col;
    }

    public static void styleField(JComponent field) {
        if (field == null) {
            return;
        }
        field.setFont(fontBody());
        if (field instanceof JTextField) {
            JTextField tf = (JTextField) field;
            tf.setBackground(FIELD_BG);
            tf.setForeground(TEXT);
            tf.setCaretColor(TEXT);
            tf.setBorder(fieldBorder(false));
            installFieldFocus(field);
        }
        Dimension cur = field.getPreferredSize();
        int w = Math.max(cur.width, 48);
        field.setPreferredSize(new Dimension(w, CONTROL_H));
        field.setMinimumSize(new Dimension(Math.min(w, FORM_FIELD_W), CONTROL_H));
    }

    /** Lock a control to the shared height and a fixed width. */
    public static void sizeField(JComponent field, int width) {
        if (field == null) {
            return;
        }
        styleField(field);
        Dimension d = new Dimension(width, CONTROL_H);
        field.setPreferredSize(d);
        field.setMinimumSize(d);
        field.setMaximumSize(d);
    }

    public static JTextField numericField(String text) {
        JTextField tf = new JTextField(text);
        sizeField(tf, FORM_FIELD_W);
        return tf;
    }

    public static void markFieldError(JComponent field, boolean error) {
        if (field instanceof JTextField) {
            field.setBorder(fieldBorder(error));
            field.setToolTipText(error ? "Check this value and try again." : field.getToolTipText());
        }
    }

    public static Border fieldBorder(boolean error) {
        Color line = error ? FIELD_ERROR : FIELD_LINE;
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(line, 1),
            BorderFactory.createEmptyBorder(2, 6, 2, 6));
    }

    private static void installFieldFocus(JComponent field) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_FOCUS, 1),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)));
                field.setBackground(FIELD_FOCUS_BG);
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(fieldBorder(false));
                field.setBackground(FIELD_BG);
            }
        });
    }

    public static void setControlSize(Component control, int width) {
        Dimension d = new Dimension(width, CONTROL_H);
        control.setPreferredSize(d);
        control.setMinimumSize(d);
        control.setMaximumSize(d);
    }

    public static void stretchWidth(JComponent c) {
        if (c == null) {
            return;
        }
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension pref = c.getPreferredSize();
        int h = CONTROL_H;
        c.setPreferredSize(new Dimension(Math.max(pref.width, 80), h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setMinimumSize(new Dimension(80, h));
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    public static JButton primaryButton(String text) {
        return chromeButton(text, ButtonRole.PRIMARY);
    }

    public static JButton secondaryButton(String text) {
        return chromeButton(text, ButtonRole.SECONDARY);
    }

    public static JButton tertiaryButton(String text) {
        return chromeButton(text, ButtonRole.TERTIARY);
    }

    public static JButton destructiveButton(String text) {
        return chromeButton(text, ButtonRole.DESTRUCTIVE);
    }

    public static JButton compactButton(String text) {
        return compactButton(text, ButtonRole.SECONDARY);
    }

    /**
     * Give two side-by-side viewers the same header height.
     *
     * <p>They sit in a GridLayout so the outer panels already match, but each reserves a
     * different amount for its own NORTH row — one may carry radio buttons and combos, the
     * other nothing — which leaves their image areas different heights. Padding the shorter
     * header to the taller one makes the two images line up exactly.
     */
    public static void matchHeaderHeights(JPanel a, JPanel b) {
        Component ha = headerOf(a);
        Component hb = headerOf(b);
        int h = Math.max(ha == null ? 0 : ha.getPreferredSize().height,
                         hb == null ? 0 : hb.getPreferredSize().height);
        if (h <= 0) {
            return;
        }
        padHeader(a, ha, h);
        padHeader(b, hb, h);
    }

    private static Component headerOf(JPanel p) {
        if (p == null || !(p.getLayout() instanceof BorderLayout)) {
            return null;
        }
        return ((BorderLayout) p.getLayout()).getLayoutComponent(BorderLayout.NORTH);
    }

    private static void padHeader(JPanel parent, Component header, int height) {
        if (parent == null) {
            return;
        }
        if (header == null) {
            // No header at all: reserve the same strip so the image starts at the same y.
            JPanel spacer = new JPanel();
            spacer.setOpaque(false);
            spacer.setPreferredSize(new Dimension(1, height));
            parent.add(spacer, BorderLayout.NORTH);
            return;
        }
        Dimension d = header.getPreferredSize();
        if (d.height < height && header instanceof JComponent) {
            ((JComponent) header).setPreferredSize(new Dimension(d.width, height));
        }
    }

    /** Square, icon-only transport button (play / pause) sized for the scrub bar. */
    public static JButton transportButton(Icon icon, String tooltip, ButtonRole role) {
        ChromeButton b = new ChromeButton("", role, CONTROL_H_SM);
        b.setSquare(true);
        b.setIcon(icon);
        b.setToolTipText(tooltip);
        b.getAccessibleContext().setAccessibleName(tooltip);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        return b;
    }

    public static JButton compactButton(String text, ButtonRole role) {
        ChromeButton b = new ChromeButton(text, role, CONTROL_H_SM);
        b.setMargin(new Insets(2, SPACE_2, 2, SPACE_2));
        b.setFont(role == ButtonRole.PRIMARY || role == ButtonRole.DESTRUCTIVE
            ? fontBodyBold() : fontHint());
        return b;
    }

    public static JToggleButton toggleButton(String text) {
        return new ChromeToggle(text);
    }

    public static JButton chromeButton(String text, ButtonRole role) {
        return new ChromeButton(text, role, CONTROL_H);
    }

    public static void applyRole(AbstractButton button, ButtonRole role) {
        if (button instanceof ChromeButton) {
            ((ChromeButton) button).setRole(role);
            return;
        }
        button.setFont(role == ButtonRole.PRIMARY || role == ButtonRole.DESTRUCTIVE
            ? fontBodyBold() : fontBody());
        button.setPreferredSize(new Dimension(
            Math.max(button.getPreferredSize().width, 80), CONTROL_H));
    }

    /** Confirm a destructive or data-clearing action. Returns true when the user agrees. */
    public static boolean confirm(Component parent, String title, String message) {
        return JOptionPane.showConfirmDialog(
            parent, message, title,
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    // ── Icons, busy, window chrome ───────────────────────────────────────────

    private static Image APP_ICON;

    public static Image appIconImage() {
        if (APP_ICON == null) {
            int s = 32;
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(PRIMARY);
            g2.fillRoundRect(1, 1, s - 2, s - 2, 8, 8);
            g2.setColor(TEXT_ON_PRIMARY);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            FontMetrics fm = g2.getFontMetrics();
            String h = "H";
            g2.drawString(h, (s - fm.stringWidth(h)) / 2, (s - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
            APP_ICON = img;
        }
        return APP_ICON;
    }

    /** Native window decorations plus the HoloBio app icon. */
    public static void decorateWindow(Window window) {
        if (window == null) {
            return;
        }
        Image icon = appIconImage();
        if (window instanceof JFrame) {
            ((JFrame) window).setIconImage(icon);
        } else if (window instanceof JDialog) {
            ((JDialog) window).setIconImage(icon);
        }
    }

    public static JButton infoButton(String title, String body) {
        String tip = body == null ? title : body;
        ChromeButton b = new ChromeButton("", ButtonRole.TERTIARY, 18);
        b.setSquare(true);
        b.setIcon(GlyphIcon.info(14));
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName("About " + title);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        b.addActionListener(e -> {
            Component src = (Component) e.getSource();
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(src),
                body, title, JOptionPane.INFORMATION_MESSAGE);
        });
        return b;
    }

    public static JButton gearButton(String tooltip, ActionListener listener) {
        return iconButton("Settings", tooltip, GlyphIcon.gear(18), listener);
    }

    public static JButton iconButton(String accessibleName, String tooltip, Icon icon,
            ActionListener listener) {
        ChromeButton b = new ChromeButton("", ButtonRole.TERTIARY, CONTROL_H_SM);
        b.setSquare(true);
        b.setIcon(icon);
        b.setToolTipText(tooltip);
        b.getAccessibleContext().setAccessibleName(accessibleName);
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        if (listener != null) {
            b.addActionListener(listener);
        }
        return b;
    }

    public static JPanel radioWithInfo(JRadioButton radio, String infoTitle, String infoBody) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        radio.setOpaque(false);
        radio.setBorder(emptyPad(0, 0, 0, 0));
        radio.setIconTextGap(SPACE_2);
        radio.setAlignmentY(Component.CENTER_ALIGNMENT);
        radio.setToolTipText(infoBody);
        row.add(radio);
        row.add(Box.createHorizontalStrut(SPACE_2));
        JButton info = infoButton(infoTitle, infoBody);
        info.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(info);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    /**
     * Left-aligned choice grid. Columns hug their contents; leftover width stays on the right
     * so radios are not stretched apart.
     */
    public static JPanel choiceGrid(int columns, JComponent... cells) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.weighty = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null) {
                continue;
            }
            c.gridx = i % columns;
            c.gridy = i / columns;
            int right = (c.gridx == columns - 1) ? 0 : SPACE_4;
            c.insets = new Insets(SPACE_1, 0, SPACE_1, right);
            cells[i].setOpaque(false);
            p.add(cells[i], c);
        }
        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = columns;
        glue.gridy = 0;
        glue.weightx = 1.0;
        glue.fill = GridBagConstraints.HORIZONTAL;
        p.add(Box.createHorizontalStrut(0), glue);
        return p;
    }

    public static JLabel helperText(String text) {
        JLabel lab = new JLabel(text);
        lab.setFont(fontHint());
        lab.setForeground(TEXT_MUTED);
        return lab;
    }

    /**
     * Disable {@code btn}, show {@code busyText}, then run {@code work} on the EDT
     * after a short delay so the busy chrome can paint first.
     */
    public static void runBusy(final JButton btn, final String busyText, final Runnable work) {
        if (work == null) {
            return;
        }
        if (btn == null) {
            work.run();
            return;
        }
        final String orig = btn.getText();
        btn.setEnabled(false);
        if (busyText != null && !busyText.isEmpty()) {
            btn.setText(busyText);
        }
        Timer t = new Timer(40, e -> {
            try {
                work.run();
            } finally {
                btn.setText(orig);
                btn.setEnabled(true);
            }
        });
        t.setRepeats(false);
        t.start();
    }

    /** Label + shrinking slider + numeric field + Set — fields never clip. */
    public static JPanel sliderFieldRow(String label, JSlider slider, JTextField field, JButton setBtn) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, SPACE_1, SPACE_3);
        JLabel lab = fieldLabel(label);
        lab.setPreferredSize(new Dimension(FORM_LABEL_W, CONTROL_H));
        lab.setMinimumSize(new Dimension(FORM_LABEL_W, CONTROL_H));
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        row.add(lab, c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        slider.setMinimumSize(new Dimension(64, CONTROL_H));
        slider.setPreferredSize(new Dimension(120, CONTROL_H));
        row.add(slider, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        sizeField(field, FORM_FIELD_W);
        row.add(field, c);
        if (setBtn != null) {
            c.gridx = 3;
            c.insets = new Insets(0, 0, SPACE_1, 0);
            row.add(setBtn, c);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_H + SPACE_1));
        return row;
    }

    static final class GlyphIcon implements Icon {
        enum Kind { INFO, GEAR, PLAY, PAUSE }

        private final Kind kind;
        private final int size;

        private GlyphIcon(Kind kind, int size) {
            this.kind = kind;
            this.size = size;
        }

        static GlyphIcon info(int size) { return new GlyphIcon(Kind.INFO, size); }
        static GlyphIcon gear(int size) { return new GlyphIcon(Kind.GEAR, size); }
        static GlyphIcon play(int size)  { return new GlyphIcon(Kind.PLAY, size); }
        static GlyphIcon pause(int size) { return new GlyphIcon(Kind.PAUSE, size); }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            Color ink = c.isEnabled() ? TEXT_MUTED : TEXT_DISABLED;
            if (kind == Kind.PLAY || kind == Kind.PAUSE) {
                // Sit on a filled chrome button, so follow its label colour.
                ink = c.isEnabled() ? c.getForeground() : TEXT_DISABLED;
            }
            g2.setColor(ink);
            if (kind == Kind.PLAY) {
                int m = Math.max(1, size / 5);
                int[] xs = { m, m, size - m };
                int[] ys = { m, size - m, size / 2 };
                g2.fillPolygon(xs, ys, 3);
            } else if (kind == Kind.PAUSE) {
                int m = Math.max(1, size / 5);
                int barW = Math.max(2, (size - 2 * m) / 3);
                g2.fillRect(m, m, barW, size - 2 * m);
                g2.fillRect(size - m - barW, m, barW, size - 2 * m);
            } else if (kind == Kind.INFO) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawOval(1, 1, size - 3, size - 3);
                g2.setFont(font(Font.BOLD, Math.max(9f, size - 7f)));
                FontMetrics fm = g2.getFontMetrics();
                String i = "i";
                g2.drawString(i, (size - fm.stringWidth(i)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
            } else {
                int cx = size / 2, cy = size / 2, r = size / 2 - 2, inner = Math.max(2, size / 6);
                int teeth = 8;
                for (int t = 0; t < teeth; t++) {
                    double a = t * Math.PI * 2.0 / teeth;
                    int tx = cx + (int) Math.round(Math.cos(a) * (r - 1));
                    int ty = cy + (int) Math.round(Math.sin(a) * (r - 1));
                    g2.fillOval(tx - 2, ty - 2, 4, 4);
                }
                g2.fillOval(cx - r + 3, cy - r + 3, (r - 3) * 2, (r - 3) * 2);
                g2.setColor(c.getBackground() != null ? c.getBackground() : SURFACE);
                g2.fillOval(cx - inner, cy - inner, inner * 2, inner * 2);
                g2.setColor(ink);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawOval(cx - inner, cy - inner, inner * 2, inner * 2);
            }
            g2.dispose();
        }
    }

    // ── Chrome controls (LAF-independent paint) ────────────────────────────

    static final class ChromeButton extends JButton {
        private ButtonRole role;
        private final int height;
        private boolean hover;
        private boolean square;

        ChromeButton(String text, ButtonRole role, int height) {
            super(text);
            this.role = role;
            this.height = height;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setFont(role == ButtonRole.PRIMARY || role == ButtonRole.DESTRUCTIVE
                ? fontBodyBold() : fontBody());
            setMargin(new Insets(2, SPACE_2, 2, SPACE_2));
            setBorder(BorderFactory.createEmptyBorder(2, SPACE_2, 2, SPACE_2));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        void setSquare(boolean square) {
            this.square = square;
        }

        void setRole(ButtonRole role) {
            this.role = role;
            setFont(role == ButtonRole.PRIMARY || role == ButtonRole.DESTRUCTIVE
                ? fontBodyBold() : fontBody());
            repaint();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(enabled
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        }

        @Override
        public Dimension getPreferredSize() {
            if (square) {
                return new Dimension(height, height);
            }
            Dimension d = super.getPreferredSize();
            d.height = height;
            d.width = Math.max(d.width, role == ButtonRole.TERTIARY ? 64 : 80);
            return d;
        }

        @Override
        public Dimension getMinimumSize() {
            if (square) {
                return new Dimension(height, height);
            }
            Dimension d = super.getMinimumSize();
            d.height = height;
            return d;
        }

        @Override
        public Color getForeground() {
            // Computed, not stored. Must NOT call setForeground() from paintComponent()
            // — setForeground() calls repaint() when the value changes, so doing it in
            // paint creates a permanent max-rate repaint loop. On weak GPUs the endless
            // antialiased fillRoundRect blitting trips driver timeout-recovery and blanks
            // every window sharing the Java2D surface until one is closed.
            if (role == null) {
                return super.getForeground();
            }
            return chromeForeground(role, isEnabled());
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintChrome(g, this, role, hover, getModel().isArmed(), isEnabled(), hasFocus(), height);
            super.paintComponent(g);
        }
    }

    static final class ChromeToggle extends JToggleButton {
        private boolean hover;

        ChromeToggle(String text) {
            super(text);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFont(fontBodyBold());
            setMargin(new Insets(2, SPACE_2, 2, SPACE_2));
            setBorder(BorderFactory.createEmptyBorder(2, SPACE_2, 2, SPACE_2));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.height = CONTROL_H;
            d.width = Math.max(d.width, 80);
            return d;
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension d = super.getMinimumSize();
            d.height = CONTROL_H;
            return d;
        }

        @Override
        public Color getForeground() {
            // See ChromeButton.getForeground(): never setForeground() from paint().
            return chromeForeground(isSelected() ? ButtonRole.PRIMARY : ButtonRole.SECONDARY,
                isEnabled());
        }

        @Override
        protected void paintComponent(Graphics g) {
            ButtonRole role = isSelected() ? ButtonRole.PRIMARY : ButtonRole.SECONDARY;
            paintChrome(g, this, role, hover, getModel().isArmed(), isEnabled(), hasFocus(), CONTROL_H);
            super.paintComponent(g);
        }
    }

    static void paintChrome(Graphics g, JComponent c, ButtonRole role, boolean hover,
                            boolean armed, boolean enabled, boolean focused, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = c.getWidth();
        int h = c.getHeight();
        int r = RADIUS;
        g2.setColor(chromeFill(role, hover, armed, enabled));
        g2.fillRoundRect(1, 1, w - 3, h - 3, r, r);
        Color bd = chromeBorder(role, enabled);
        if (bd != null) {
            g2.setColor(bd);
            g2.drawRoundRect(1, 1, w - 3, h - 3, r, r);
        }
        if (focused && enabled) {
            g2.setColor(BORDER_FOCUS);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(2, 2, w - 5, h - 5, Math.max(2, r - 1), Math.max(2, r - 1));
        }
        g2.dispose();
    }

    static Color chromeFill(ButtonRole role, boolean hover, boolean armed, boolean enabled) {
        if (!enabled) {
            switch (role) {
                case PRIMARY: return PRIMARY_DISABLED;
                case DESTRUCTIVE: return DANGER_DISABLED;
                case TERTIARY: return new Color(0, 0, 0, 0);
                default: return SURFACE_DISABLED;
            }
        }
        if (armed) {
            switch (role) {
                case PRIMARY: return PRIMARY_PRESSED;
                case DESTRUCTIVE: return DANGER_PRESSED;
                case TERTIARY: return SURFACE_PRESSED;
                default: return SURFACE_PRESSED;
            }
        }
        if (hover) {
            switch (role) {
                case PRIMARY: return PRIMARY_HOVER;
                case DESTRUCTIVE: return DANGER_HOVER;
                case TERTIARY: return SURFACE_HOVER;
                default: return SURFACE_HOVER;
            }
        }
        switch (role) {
            case PRIMARY: return PRIMARY;
            case DESTRUCTIVE: return DANGER;
            case TERTIARY: return new Color(0, 0, 0, 0);
            default: return SURFACE;
        }
    }

    static Color chromeBorder(ButtonRole role, boolean enabled) {
        if (role == ButtonRole.TERTIARY) {
            return null;
        }
        if (!enabled) {
            return role == ButtonRole.PRIMARY ? PRIMARY_DISABLED.darker() : BORDER_DISABLED;
        }
        switch (role) {
            case PRIMARY: return PRIMARY_LINE;
            case DESTRUCTIVE: return DANGER_HOVER;
            default: return BORDER;
        }
    }

    static Color chromeForeground(ButtonRole role, boolean enabled) {
        if (!enabled) {
            return TEXT_DISABLED;
        }
        if (role == ButtonRole.PRIMARY) {
            return TEXT_ON_PRIMARY;
        }
        if (role == ButtonRole.DESTRUCTIVE) {
            return Color.WHITE;
        }
        return TEXT;
    }

    // ── Sections / chrome chrome ───────────────────────────────────────────

    public static TitledBorder sectionBorder(String title) {
        TitledBorder border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(BORDER, 1),
            title,
            TitledBorder.LEFT,
            TitledBorder.TOP,
            fontSection(),
            TEXT);
        return border;
    }

    /** Titled section with inner padding on the 8/12 grid. */
    public static Border sectionPad(String title) {
        return BorderFactory.createCompoundBorder(
            sectionBorder(title),
            emptyPad(SPACE_1, SPACE_2, SPACE_1, SPACE_2));
    }

    public static JPanel sectionPanel(String title) {
        JPanel p = new JPanel();
        p.setBorder(sectionPad(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setOpaque(false);
        return p;
    }

    /** GridBag section whose label column is FORM_LABEL_W so inputs share one x. */
    public static JPanel formSection(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(sectionPad(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setOpaque(false);
        return p;
    }

    public static GridBagConstraints formGbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    /** 24 px between major titled groups. */
    public static void stackSections(JPanel parent, JComponent... sections) {
        if (parent == null || sections == null) {
            return;
        }
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) {
                continue;
            }
            if (parent.getComponentCount() > 0) {
                parent.add(vgap(SPACE_2));
            }
            sections[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            parent.add(sections[i]);
        }
    }

    /**
     * Pack to content, then cap at {@code maxWidth} × {@code maxHeight} (and the screen).
     * A small height slack covers titled-border / native-chrome rounding that otherwise
     * clips the last form row.
     */
    public static void packTight(java.awt.Window window, int maxWidth, int maxHeight) {
        packTight(window, 0, maxWidth, maxHeight);
    }

    public static void packTight(java.awt.Window window, int minWidth, int maxWidth, int maxHeight) {
        packTight(window, minWidth, maxWidth, maxHeight, SPACE_5);
    }

    public static void packTight(java.awt.Window window, int minWidth, int maxWidth, int maxHeight,
            int extraHeight) {
        if (window == null) {
            return;
        }
        window.pack();
        Dimension packed = window.getSize();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = packed.width;
        if (minWidth > 0) {
            w = Math.max(w, minWidth);
        }
        w = Math.min(w, Math.min(maxWidth, screen.width - 48));
        int slack = Math.max(0, extraHeight);
        int h = packed.height + slack;
        h = Math.min(h, Math.min(maxHeight, screen.height - 80));
        int minW = minWidth > 0 ? minWidth : Math.min(w, packed.width);
        window.setMinimumSize(new Dimension(Math.max(320, minW), Math.min(packed.height, 240)));
        window.setSize(w, h);
    }

    /** Min/max pair on one row: {@code L  [min] – [max]}. */
    public static void addMinMaxRow(JPanel parent, GridBagConstraints c, String label,
            JTextField minF, JTextField maxF) {
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, SPACE_2, SPACE_2);
        JLabel lab = fieldLabel(label);
        lab.setPreferredSize(new Dimension(24, CONTROL_H));
        lab.setMinimumSize(new Dimension(24, CONTROL_H));
        parent.add(lab, c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, SPACE_2, SPACE_2);
        sizeField(minF, FORM_FIELD_W);
        parent.add(minF, c);
        c.gridx = 2;
        parent.add(fieldLabel("–"), c);
        c.gridx = 3;
        c.insets = new Insets(0, SPACE_2, SPACE_2, 0);
        sizeField(maxF, FORM_FIELD_W);
        parent.add(maxF, c);
        c.gridx = 4;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        parent.add(Box.createHorizontalStrut(0), c);
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy++;
        c.gridx = 0;
    }

    /** Pin content to the top so leftover window height does not stretch form rows. */
    public static JPanel hugNorth(JComponent content) {
        JPanel hug = new JPanel(new BorderLayout());
        hug.setOpaque(false);
        hug.add(content, BorderLayout.NORTH);
        return hug;
    }

    public static JPanel pairRow(JComponent a, JComponent b) {
        JPanel row = new JPanel(new GridLayout(1, 2, SPACE_2, 0));
        row.setOpaque(false);
        row.add(a);
        row.add(b);
        return row;
    }

    /** CardLayout panel sized to the visible card, not the tallest hidden one. */
    public static JPanel huggingCardPanel(CardLayout layout) {
        return new JPanel(layout) {
            @Override
            public Dimension getPreferredSize() {
                for (Component comp : getComponents()) {
                    if (comp.isVisible()) {
                        return comp.getPreferredSize();
                    }
                }
                return super.getPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }

            @Override
            public Dimension getMaximumSize() {
                Dimension p = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, p.height);
            }
        };
    }

    /** Label on the left, fixed-width field on the right — inputs share one x. */
    public static JLabel addLabelField(JPanel parent, GridBagConstraints c, String label, JComponent field) {
        return addLabelField(parent, c, label, field, FORM_LABEL_W, FORM_FIELD_W, null);
    }

    public static JLabel addLabelField(JPanel parent, GridBagConstraints c, String label,
            JComponent field, String infoTip) {
        return addLabelField(parent, c, label, field, FORM_LABEL_W, FORM_FIELD_W, infoTip);
    }

    public static JLabel addLabelField(JPanel parent, GridBagConstraints c, String label,
            JComponent field, int labelW, int fieldW) {
        return addLabelField(parent, c, label, field, labelW, fieldW, null);
    }

    public static JLabel addLabelField(JPanel parent, GridBagConstraints c, String label,
            JComponent field, int labelW, int fieldW, String infoTip) {
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, SPACE_1, SPACE_3);
        JLabel lab = fieldLabel(label);
        lab.setLabelFor(field);
        JComponent labelCell = lab;
        if (infoTip != null && !infoTip.isEmpty()) {
            lab.setToolTipText(infoTip);
            JPanel cell = flowLeft(SPACE_1, 0);
            cell.setPreferredSize(new Dimension(labelW, CONTROL_H));
            cell.setMinimumSize(new Dimension(labelW, CONTROL_H));
            lab.setPreferredSize(null);
            cell.add(lab);
            cell.add(infoButton(label, infoTip));
            labelCell = cell;
        } else {
            lab.setPreferredSize(new Dimension(labelW, CONTROL_H));
            lab.setMinimumSize(new Dimension(labelW, CONTROL_H));
        }
        parent.add(labelCell, c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, SPACE_1, 0);
        sizeField(field, fieldW);
        parent.add(field, c);
        if (c.gridy == 0) {
            GridBagConstraints glue = new GridBagConstraints();
            glue.gridx = 2;
            glue.gridy = 0;
            glue.weightx = 1.0;
            glue.gridheight = GridBagConstraints.REMAINDER;
            glue.fill = GridBagConstraints.HORIZONTAL;
            parent.add(Box.createHorizontalStrut(0), glue);
        }
        c.gridy++;
        c.gridx = 0;
        return lab;
    }

    /**
     * Compact header: title on the left (single line, never wraps into actions),
     * actions on the right.
     */
    public static JPanel buildHeader(String titleText, String workflowText, JLabel statusSlot, Component... actions) {
        JPanel header = new JPanel(new BorderLayout(SPACE_2, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
            emptyPad(SPACE_2, CONTENT_MARGIN, SPACE_2, CONTENT_MARGIN)));

        JLabel title = mainTitle(titleText);
        title.setMinimumSize(title.getPreferredSize());
        if (workflowText != null && !workflowText.isEmpty()) {
            JPanel left = new JPanel(new BorderLayout(SPACE_2, 0));
            left.setOpaque(false);
            left.add(title, BorderLayout.WEST);
            left.add(workflowSteps("·  " + workflowText), BorderLayout.CENTER);
            header.add(left, BorderLayout.WEST);
        } else {
            header.add(title, BorderLayout.WEST);
        }

        if (actions != null && actions.length > 0) {
            JPanel actionRow = flowRight();
            actionRow.setOpaque(false);
            for (Component action : actions) {
                if (action != null) {
                    actionRow.add(action);
                }
            }
            header.add(actionRow, BorderLayout.EAST);
        }
        if (statusSlot != null) {
            JPanel south = new JPanel(new BorderLayout());
            south.setOpaque(false);
            south.add(statusSlot, BorderLayout.WEST);
            header.add(south, BorderLayout.SOUTH);
        }
        return header;
    }

    /**
     * Shared module footer: {@code [leading Reset] … [trailing Save/Export] [primary]}.
     */
    public static JPanel buildActionBar(JButton leading, JButton... trailing) {
        JPanel footer = new JPanel(new BorderLayout(SPACE_2, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            emptyPad(SPACE_2, CONTENT_MARGIN, SPACE_2, CONTENT_MARGIN)));
        if (leading != null) {
            JPanel left = flowLeft(SPACE_2, 0);
            left.add(leading);
            footer.add(left, BorderLayout.WEST);
        }
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_3, 0));
        buttonRow.setOpaque(false);
        if (trailing != null) {
            for (JButton button : trailing) {
                if (button != null) {
                    buttonRow.add(button);
                }
            }
        }
        footer.add(buttonRow, BorderLayout.EAST);
        return footer;
    }

    /**
     * Footer: optional hint on the left, action buttons on the right.
     */
    public static JPanel buildFooter(String hintText, JButton... buttons) {
        JPanel footer = new JPanel(new BorderLayout(SPACE_2, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            emptyPad(SPACE_2, CONTENT_MARGIN, SPACE_2, CONTENT_MARGIN)));
        if (hintText != null && !hintText.isEmpty()) {
            footer.add(footerNote(hintText), BorderLayout.WEST);
        }
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_3, 0));
        buttonRow.setOpaque(false);
        if (buttons != null) {
            for (JButton button : buttons) {
                if (button != null) {
                    buttonRow.add(button);
                }
            }
        }
        footer.add(buttonRow, BorderLayout.EAST);
        return footer;
    }

    public static JPanel statusBar(JLabel status) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
            bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            emptyPad(SPACE_2, CONTENT_MARGIN, SPACE_3, CONTENT_MARGIN)));
        if (status != null) {
            status.setFont(fontBody());
            status.setForeground(TEXT);
            bar.add(status, BorderLayout.WEST);
        }
        return bar;
    }

    public static JLabel recordLabel() {
        JLabel lab = new JLabel(" ");
        lab.setFont(fontHint());
        lab.setForeground(RECORD);
        return lab;
    }

    public static void alignLeft(JComponent component) {
        if (component instanceof JPanel) {
            ((JPanel) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
    }

    public static Component vgap(int px) {
        return Box.createVerticalStrut(px);
    }

    public static Component hgap(int px) {
        return Box.createHorizontalStrut(px);
    }

    // ── Tree polish ────────────────────────────────────────────────────────

    /** 12pt body text for labels and controls in HoloBio dialogs. */
    public static Font bodyFont(Component ref) {
        return fontBody();
    }

    /** Apply 12pt font to labels, buttons, radios, and combos under {@code root} (not text fields). */
    public static void applyBodyFont(Container root) {
        if (root == null) {
            return;
        }
        Font body = fontBody();
        for (Component c : root.getComponents()) {
            if (c instanceof Container) {
                applyBodyFont((Container) c);
            }
            if (c instanceof ChromeButton || c instanceof ChromeToggle) {
                continue;
            }
            if (c instanceof JLabel) {
                Font f = c.getFont();
                if (f == null || !f.isBold()) {
                    c.setFont(body);
                    if (c.getForeground() == null || Color.BLACK.equals(c.getForeground())) {
                        c.setForeground(TEXT);
                    }
                }
            } else if (c instanceof JButton || c instanceof javax.swing.JRadioButton
                || c instanceof javax.swing.JCheckBox || c instanceof JComboBox) {
                c.setFont(body);
            }
        }
    }

    /** Fonts, section titles, control heights. */
    public static void applyPluginTypography(Container root) {
        polish(root);
    }

    public static void polish(Container root) {
        if (root == null) {
            return;
        }
        applyBodyFont(root);
        applySectionBorderFonts(root);
        applyControlMetrics(root);
        pinTitledSections(root);
        Window w = root instanceof Window ? (Window) root : SwingUtilities.getWindowAncestor(root);
        if (w != null) {
            decorateWindow(w);
        }
    }

    private static void applyControlMetrics(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof ChromeButton || c instanceof ChromeToggle) {
                if (c instanceof Container) {
                    applyControlMetrics((Container) c);
                }
                continue;
            }
            if (c instanceof JTextField || c instanceof JComboBox) {
                JComponent jc = (JComponent) c;
                Dimension d = jc.getPreferredSize();
                jc.setPreferredSize(new Dimension(d.width, CONTROL_H));
                Dimension max = jc.getMaximumSize();
                int maxW = max.width < Short.MAX_VALUE ? max.width : d.width;
                jc.setMaximumSize(new Dimension(maxW, CONTROL_H));
            }
            if (c instanceof JButton && !(c instanceof ChromeButton)) {
                Dimension d = c.getPreferredSize();
                if (d.height < CONTROL_H) {
                    c.setPreferredSize(new Dimension(Math.max(d.width, 72), CONTROL_H));
                }
            }
            if (c instanceof Container) {
                applyControlMetrics((Container) c);
            }
        }
    }

    private static void applySectionBorderFonts(Container root) {
        if (root instanceof JComponent) {
            TitledBorder tb = findTitledBorder(((JComponent) root).getBorder());
            if (tb != null) {
                tb.setTitleFont(fontSection());
                tb.setTitleColor(TEXT);
            }
        }
        for (Component c : root.getComponents()) {
            if (c instanceof Container) {
                applySectionBorderFonts((Container) c);
            }
        }
    }

    /** Stop GridBag titled groups from stretching. BoxLayout sections keep natural height. */
    private static void pinTitledSections(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof Container) {
                pinTitledSections((Container) c);
            }
        }
        if (!(root instanceof JComponent)) {
            return;
        }
        JComponent jc = (JComponent) root;
        if (findTitledBorder(jc.getBorder()) == null) {
            return;
        }
        if (!(jc.getLayout() instanceof GridBagLayout)) {
            return;
        }
        Dimension p = jc.getPreferredSize();
        jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
    }

    private static TitledBorder findTitledBorder(Border b) {
        if (b instanceof TitledBorder) {
            return (TitledBorder) b;
        }
        if (b instanceof CompoundBorder) {
            CompoundBorder cb = (CompoundBorder) b;
            TitledBorder outer = findTitledBorder(cb.getOutsideBorder());
            if (outer != null) {
                return outer;
            }
            return findTitledBorder(cb.getInsideBorder());
        }
        return null;
    }
}
