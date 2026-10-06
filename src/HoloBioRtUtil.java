import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.File;

/** Helpers shared by the real-time DHM and DLHM windows. */
public final class HoloBioRtUtil {

    private HoloBioRtUtil() {}

    /**
     * Luma conversion straight off the raster's backing array, reusing {@code reuse} when it
     * is the right size.
     *
     * <p>{@code BufferedImage.getRGB} routes every single pixel through the colour model, which
     * costs more than the whole reconstruction on a megapixel frame. It is kept only as a
     * fallback for image types the direct paths do not cover.
     */
    public static float[] toGray(BufferedImage img, float[] reuse) {
        int w = img.getWidth(), h = img.getHeight();
        int n = w * h;
        float[] gray = (reuse != null && reuse.length == n) ? reuse : new float[n];

        switch (img.getType()) {
            case BufferedImage.TYPE_BYTE_GRAY: {
                byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < n; i++) gray[i] = px[i] & 0xFF;
                return gray;
            }
            case BufferedImage.TYPE_3BYTE_BGR: {
                byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                for (int i = 0, p = 0; i < n; i++, p += 3) {
                    gray[i] = (px[p + 2] & 0xFF) * 0.299f
                            + (px[p + 1] & 0xFF) * 0.587f
                            + (px[p    ] & 0xFF) * 0.114f;
                }
                return gray;
            }
            case BufferedImage.TYPE_4BYTE_ABGR:
            case BufferedImage.TYPE_4BYTE_ABGR_PRE: {
                byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                for (int i = 0, p = 0; i < n; i++, p += 4) {
                    gray[i] = (px[p + 3] & 0xFF) * 0.299f
                            + (px[p + 2] & 0xFF) * 0.587f
                            + (px[p + 1] & 0xFF) * 0.114f;
                }
                return gray;
            }
            case BufferedImage.TYPE_INT_RGB:
            case BufferedImage.TYPE_INT_ARGB:
            case BufferedImage.TYPE_INT_ARGB_PRE: {
                int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < n; i++) {
                    int v = px[i];
                    gray[i] = ((v >> 16) & 0xFF) * 0.299f
                            + ((v >>  8) & 0xFF) * 0.587f
                            + ( v        & 0xFF) * 0.114f;
                }
                return gray;
            }
            default: {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int rgb = img.getRGB(x, y);
                        gray[y * w + x] = ((rgb >> 16) & 0xFF) * 0.299f
                                        + ((rgb >>  8) & 0xFF) * 0.587f
                                        + ( rgb        & 0xFF) * 0.114f;
                    }
                }
                return gray;
            }
        }
    }

    /** Wrap display-scale 0…255 floats as an 8-bit grey image. */
    public static BufferedImage grayImage(float[] data, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length && i < data.length; i++) {
            px[i] = (byte) Math.max(0, Math.min(255, Math.round(data[i])));
        }
        return img;
    }

    /** Linearly stretch {@code src} to 0…255 in place, as Python's _normalize_to_uint8 does. */
    /**
     * Average {@code factor} x {@code factor} blocks into one sample.
     *
     * <p>Two wins at once for lensless reconstruction: the transform shrinks quadratically,
     * and the effective pixel pitch grows by {@code factor}, which raises the propagation
     * distance an angular-spectrum / DLHM kernel can represent before it aliases
     * ({@code N*pitch^2/lambda} scales with the factor). Callers MUST multiply the pitch
     * they hand the reconstructor by the same factor.
     *
     * <p>Any partial block at the right or bottom edge is dropped, so the output is
     * {@code (cols/factor) x (rows/factor)}.
     */
    public static void bin(float[] src, int cols, int rows, int factor, float[] dst) {
        final int bc = cols / factor;
        final int br = rows / factor;
        final float inv = 1f / (factor * factor);
        HoloBioParallel.forEachLine(br, (from, to) -> {
            for (int r = from; r < to; r++) {
                int srcRow = r * factor;
                int outBase = r * bc;
                for (int c = 0; c < bc; c++) {
                    int srcCol = c * factor;
                    float sum = 0f;
                    for (int dy = 0; dy < factor; dy++) {
                        int base = (srcRow + dy) * cols + srcCol;
                        for (int dx = 0; dx < factor; dx++) {
                            sum += src[base + dx];
                        }
                    }
                    dst[outBase + c] = sum * inv;
                }
            }
        });
    }

    public static void stretchTo255(float[] src, int n) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float v = src[i];
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        float span = hi - lo;
        if (span <= 1e-12f) {
            java.util.Arrays.fill(src, 0, n, 0f);
            return;
        }
        float k = 255f / span;
        for (int i = 0; i < n; i++) src[i] = (src[i] - lo) * k;
    }

    public static double parseDouble(JTextField tf, double fallback) {
        if (tf == null) return fallback;
        Double v = tryParse(tf.getText());
        if (v != null) return v;
        // A field under liveField() keeps working with its last valid value while the user
        // is mid-edit or has typed something invalid, instead of snapping to the default.
        Object last = tf.getClientProperty(LAST_GOOD);
        return last instanceof Double ? (Double) last : fallback;
    }

    /** Same leniency as {@link #parseDouble(String, double)}, but null when not a number. */
    public static Double tryParse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() > 1 && (s.endsWith("x") || s.endsWith("X"))) {
            s = s.substring(0, s.length() - 1).trim();
        }
        s = s.replace(',', '.');
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Which values {@link #liveField} accepts. */
    public enum Rule { POSITIVE, NON_NEGATIVE, ANY }

    private static final String LAST_GOOD = "holobio.lastGood";
    private static final String IN_ERROR  = "holobio.inError";
    private static final java.awt.Color APPLIED_BG = new java.awt.Color(0xDDF0E3);

    /**
     * Make a parameter field say when it has taken effect. The real-time loops re-read their
     * fields on every frame, so an edit applies from the next frame — but silently, and a
     * typo used to fall back to the default with no sign at all. With this:
     * <ul>
     *   <li>an invalid entry turns the border red at once, and the pipeline keeps the last
     *       valid value (see {@link #parseDouble(JTextField, double)});</li>
     *   <li>shortly after typing stops, a valid new value flashes the field green and the
     *       status line names it ("λ set to 0.532 µm — applied from the next frame").</li>
     * </ul>
     *
     * @param emptyNote non-null when an empty field is allowed; said in the status line
     */
    public static void liveField(JTextField tf, String name, String unit, Rule rule,
                                 String emptyNote, java.util.function.Consumer<String> status) {
        Double init = tryParse(tf.getText());
        if (init != null) tf.putClientProperty(LAST_GOOD, init);
        final String[] confirmed = { tf.getText().trim() };
        final String tip = tf.getToolTipText();
        final String u = unit == null || unit.isEmpty() ? "" : " " + unit;

        javax.swing.Timer settle = new javax.swing.Timer(600, null);
        settle.setRepeats(false);
        settle.addActionListener(e -> {
            String text = tf.getText().trim();
            if (text.equals(confirmed[0])) return;
            if (Boolean.TRUE.equals(tf.getClientProperty(IN_ERROR))) {
                Object last = tf.getClientProperty(LAST_GOOD);
                status.accept(String.format(java.util.Locale.US, "%s: “%s” is not valid%s",
                        name, text, last instanceof Double
                                ? " — still using " + fmt((Double) last) + u + "." : "."));
                return;
            }
            confirmed[0] = text;
            status.accept(text.isEmpty()
                    ? name + " cleared — " + emptyNote
                    : name + " set to " + fmt(tryParse(text)) + u + " — applied from the next frame.");
            flash(tf);
        });

        javax.swing.event.DocumentListener check = new javax.swing.event.DocumentListener() {
            private void changed() {
                String text = tf.getText().trim();
                Double v = tryParse(text);
                boolean ok = text.isEmpty() ? emptyNote != null
                        : v != null && (rule == Rule.ANY
                            || (rule == Rule.POSITIVE ? v > 0 : v >= 0));
                if (ok && v != null) tf.putClientProperty(LAST_GOOD, v);
                tf.putClientProperty(IN_ERROR, !ok);
                tf.setBorder(ok && !tf.hasFocus() ? HoloBioUiStyle.fieldBorder(false)
                        : ok ? focusBorder() : HoloBioUiStyle.fieldBorder(true));
                tf.setToolTipText(ok ? tip : (rule == Rule.POSITIVE ? "Enter a number greater than 0."
                        : rule == Rule.NON_NEGATIVE ? "Enter 0 or a positive number." : "Enter a number."));
                settle.restart();
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        };
        tf.getDocument().addDocumentListener(check);
        // The style's focus handler resets the border on focus changes; keep the error visible.
        tf.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (Boolean.TRUE.equals(tf.getClientProperty(IN_ERROR))) {
                    tf.setBorder(HoloBioUiStyle.fieldBorder(true));
                }
            }
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                if (Boolean.TRUE.equals(tf.getClientProperty(IN_ERROR))) {
                    tf.setBorder(HoloBioUiStyle.fieldBorder(true));
                }
            }
        });
        tf.addActionListener(e -> settle.restart());   // Enter confirms without waiting
    }

    private static javax.swing.border.Border focusBorder() {
        return javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(HoloBioUiStyle.BORDER_FOCUS, 1),
                javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
    }

    private static void flash(JTextField tf) {
        tf.setBackground(APPLIED_BG);
        javax.swing.Timer back = new javax.swing.Timer(900, e -> tf.setBackground(
                tf.hasFocus() ? HoloBioUiStyle.FIELD_FOCUS_BG : HoloBioUiStyle.FIELD_BG));
        back.setRepeats(false);
        back.start();
    }

    private static String fmt(Double v) {
        if (v == null) return "?";
        return new java.math.BigDecimal(v).round(new java.math.MathContext(6))
                .stripTrailingZeros().toPlainString();
    }

    /**
     * Parse a physical parameter. Accepts {@code 40}, {@code 40x}, {@code 3,75} (comma decimal).
     */
    public static double parseDouble(String raw, double fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.isEmpty()) return fallback;
        // Magnification fields often include a trailing "x"
        if (s.length() > 1 && (s.endsWith("x") || s.endsWith("X"))) {
            s = s.substring(0, s.length() - 1).trim();
        }
        s = s.replace(',', '.');
        try { return Double.parseDouble(s); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public static int parseInt(JTextField tf, int fallback) {
        if (tf == null) return fallback;
        String s = tf.getText();
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty() || "auto".equalsIgnoreCase(s)) return fallback;
        try { return Integer.parseInt(s.replace(",", "")); }
        catch (NumberFormatException ignored) {
            return (int) Math.round(parseDouble(s, fallback));
        }
    }

    /** One-column GridBagConstraints, stretched horizontally. */
    public static GridBagConstraints gbc(int gridy) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = gridy;
        c.weightx = 1.0;
        c.fill    = GridBagConstraints.HORIZONTAL;
        c.anchor  = GridBagConstraints.WEST;
        c.insets  = new Insets(0, 0, 3, 0);
        return c;
    }

    /**
     * A directory the file chooser can safely start in.
     *
     * <p>Left to itself the chooser opens on whatever Windows considers "Home", which is a shell
     * namespace folder rather than a directory: it has no path, only a class id. Saving from
     * there hands back something like {@code ::{E88855EA-…}\clip.mp4}, which then resolves
     * against the working directory — {@code C:\Windows\system32} when Fiji is launched from
     * its shortcut — and FFmpeg is asked to write somewhere that cannot exist.
     */
    public static File defaultSaveDir() {
        File d = new File(System.getProperty("user.home", "."), "Videos");
        if (d.isDirectory()) return d;
        d = new File(System.getProperty("user.home", "."));
        return d.isDirectory() ? d : new File(".").getAbsoluteFile();
    }

    /** Rebase {@code f} onto a real directory if the chooser handed back a virtual one. */
    public static File realPath(File f) {
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && parent.isDirectory()) return f.getAbsoluteFile();
        return new File(defaultSaveDir(), f.getName());
    }
}
