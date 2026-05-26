import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.border.TitledBorder;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Shared Swing styling for HoloBio Fiji UIs (main plugin, Bio-Analysis, Speckle).
 */
public final class HoloBioUiStyle {

    public static final Insets PANEL_PADDING = new Insets(10, 14, 10, 14);
    public static final Insets ROW_GAP = new Insets(0, 0, 8, 0);

    private HoloBioUiStyle() {}

    public static JLabel mainTitle(String text) {
        JLabel title = new JLabel(text, SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        return title;
    }

    public static JLabel sectionTitle(String text) {
        JLabel title = new JLabel(text, SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        return title;
    }

    public static JLabel workflowSteps(String text) {
        JLabel steps = new JLabel(text, SwingConstants.LEFT);
        steps.setFont(steps.getFont().deriveFont(12f));
        return steps;
    }

    public static JLabel hintHtml(String html, int widthPx) {
        return new JLabel("<html><p style='width:" + widthPx + "px'>" + html + "</p></html>");
    }

    public static JLabel statusHtml(String html) {
        JLabel status = new JLabel("<html>" + html + "</html>", SwingConstants.LEFT);
        status.setFont(status.getFont().deriveFont(12f));
        return status;
    }

    public static JLabel footerNote(String text) {
        JLabel note = new JLabel(text, SwingConstants.LEFT);
        note.setFont(note.getFont().deriveFont(11f));
        return note;
    }

    public static JPanel flowLeft() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    }

    public static JPanel flowLeft(int hgap, int vgap) {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, hgap, vgap));
    }

    public static JPanel paddedPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(
            PANEL_PADDING.top, PANEL_PADDING.left, PANEL_PADDING.bottom, PANEL_PADDING.right));
        return panel;
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

    public static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
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

    /**
     * Header block: bold title, workflow line, optional status, then action row.
     */
    public static JPanel buildHeader(String titleText, String workflowText, JLabel statusSlot, Component... actions) {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        GridBagConstraints hc = westGbc(0);
        hc.insets = new Insets(0, 0, 4, 0);
        header.add(mainTitle(titleText), hc);
        hc.gridy++;
        hc.insets = new Insets(0, 0, 6, 0);
        header.add(workflowSteps(workflowText), hc);
        if (statusSlot != null) {
            hc.gridy++;
            hc.insets = new Insets(0, 0, 8, 0);
            header.add(statusSlot, hc);
        }
        if (actions != null && actions.length > 0) {
            hc.gridy++;
            hc.insets = new Insets(0, 0, 0, 0);
            hc.fill = GridBagConstraints.NONE;
            JPanel actionRow = flowLeft();
            for (Component action : actions) {
                if (action != null) {
                    actionRow.add(action);
                }
            }
            header.add(actionRow, hc);
        }
        return header;
    }

    /**
     * Footer: hint on the left, action buttons on the right (last button treated as primary if bold already).
     */
    public static JPanel buildFooter(String hintText, JButton... buttons) {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
        footer.add(footerNote(hintText), BorderLayout.WEST);
        JPanel buttonRow = flowLeft(10, 4);
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

    public static void alignLeft(JComponent component) {
        if (component instanceof JPanel) {
            ((JPanel) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
    }

    /** Section group border title — bold 13pt (matches Bio-Analysis / Speckle section headers). */
    public static TitledBorder sectionBorder(String title) {
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD, 13f));
        return border;
    }

    /** 12pt body text for labels and controls in HoloBio dialogs. */
    public static Font bodyFont(Component ref) {
        return ref.getFont().deriveFont(12f);
    }

    /** Apply 12pt font to labels, buttons, radios, and combos under {@code root} (not text fields). */
    public static void applyBodyFont(Container root) {
        if (root == null) {
            return;
        }
        Font body = bodyFont(root);
        for (Component c : root.getComponents()) {
            if (c instanceof Container) {
                applyBodyFont((Container) c);
            }
            if (c instanceof JLabel) {
                Font f = c.getFont();
                if (!(f.isBold() && f.getSize2D() >= 14f)) {
                    c.setFont(body);
                }
            } else if (c instanceof JButton || c instanceof javax.swing.JRadioButton
                || c instanceof javax.swing.JCheckBox || c instanceof javax.swing.JComboBox) {
                c.setFont(body);
            }
        }
    }

    /** Fonts only: 12pt controls, bold 13pt section borders, bold Apply/Compensate. */
    public static void applyPluginTypography(Container root) {
        if (root == null) {
            return;
        }
        applyBodyFont(root);
        applySectionBorderFonts(root);
        applyPrimaryActionFonts(root);
    }

    private static void applyPrimaryActionFonts(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton) {
                String t = ((JButton) c).getText();
                if (t != null && ("Apply".equals(t) || "Compensate".equals(t))) {
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
            }
            if (c instanceof Container) {
                applyPrimaryActionFonts((Container) c);
            }
        }
    }

    private static void applySectionBorderFonts(Container root) {
        if (root instanceof JComponent) {
            if (((JComponent) root).getBorder() instanceof TitledBorder) {
                TitledBorder tb = (TitledBorder) ((JComponent) root).getBorder();
                tb.setTitleFont(tb.getTitleFont().deriveFont(Font.BOLD, 13f));
            }
        }
        for (Component c : root.getComponents()) {
            if (c instanceof Container) {
                applySectionBorderFonts((Container) c);
            }
        }
    }
}
