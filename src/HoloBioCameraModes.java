import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.List;
import java.util.Locale;

/**
 * Fills a Resolution combo with the sizes a camera actually offers, largest first, and turns
 * the selection back into a capture size. Shared by both real-time windows.
 *
 * <p>The largest mode is the default because off-axis holograms need every pixel: a reduced
 * mode skips or bins the sensor and the carrier fringes alias away (see
 * {@link HoloBioWebcamBackend#connectIndex(int, Dimension)}).
 */
final class HoloBioCameraModes {

    static final String DEFAULT_ITEM = "Camera default";

    private HoloBioCameraModes() {
    }

    /** Query modes for {@code cameraItem} (a Capture combo entry) off the EDT, then fill. */
    static void populate(JComboBox<String> combo, String cameraItem) {
        boolean enabled = combo.isEnabled();   // the source radio decides this, not us
        combo.removeAllItems();
        combo.addItem("Reading modes…");
        combo.setEnabled(false);
        String name = cameraItem == null ? null : cameraItem.replaceFirst("^\\d+:\\s*", "");
        Thread t = new Thread(() -> {
            List<Dimension> modes = name == null ? java.util.Collections.<Dimension>emptyList()
                    : HoloBioDShowCamera.listModes(name);
            SwingUtilities.invokeLater(() -> {
                combo.removeAllItems();
                for (Dimension d : modes) combo.addItem(label(d));
                combo.addItem(DEFAULT_ITEM);
                combo.setSelectedIndex(0);
                combo.setEnabled(enabled);
            });
        }, "HoloBio-camera-modes");
        t.setDaemon(true);
        t.start();
    }

    /** "Resolution" label beside a combo that takes the rest of the sidebar width. */
    static javax.swing.JPanel row(JComboBox<String> combo) {
        javax.swing.JPanel row = new javax.swing.JPanel(new java.awt.BorderLayout(HoloBioUiStyle.SPACE_2, 0));
        row.setOpaque(false);
        javax.swing.JLabel l = HoloBioUiStyle.fieldLabel("Resolution");
        l.setLabelFor(combo);
        row.add(l, java.awt.BorderLayout.WEST);
        combo.setToolTipText("Capture size. Use the largest: smaller modes skip sensor pixels "
                + "and wash out the hologram fringes.");
        combo.setPreferredSize(new Dimension(80, HoloBioUiStyle.CONTROL_H));
        row.add(combo, java.awt.BorderLayout.CENTER);
        return row;
    }

    static String label(Dimension d) {
        return String.format(Locale.US, "%d × %d", d.width, d.height);
    }

    /** Capture size for a combo entry; null means "let the driver choose". */
    static Dimension parse(Object item) {
        if (item == null) return null;
        String[] p = item.toString().split("\\s*×\\s*");
        if (p.length != 2) return null;
        try {
            return new Dimension(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
