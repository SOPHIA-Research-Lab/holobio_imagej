import ij.IJ;
import ij.io.SaveDialog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes complex fields as NumPy {@code .npy} files, so a reconstruction can be picked up
 * directly with {@code np.load(path)} — as a {@code complex64} array of shape
 * {@code (rows, cols)}, C order, exactly the layout HoloBio Python works in.
 *
 * <p>Format: NPY v1.0 — magic, version, little-endian header length, then an ASCII dict
 * padded with spaces and a newline so the data starts on a 64-byte boundary, then the
 * interleaved little-endian (re, im) float32 pairs.
 */
public final class HoloBioNpy {

    private HoloBioNpy() {
    }

    /** An immutable copy of a complex field, {@code cols x rows}, row-major. */
    public static final class Snapshot {
        public final float[] re;
        public final float[] im;
        public final int cols;
        public final int rows;

        public Snapshot(float[] re, float[] im, int cols, int rows) {
            this.re = re;
            this.im = im;
            this.cols = cols;
            this.rows = rows;
        }
    }

    /** {@link #saveComplexDialog} for a snapshot; null-safe. */
    public static File saveComplexDialog(String defaultName, Snapshot s) {
        return s == null
            ? saveComplexDialog(defaultName, null, null, 0, 0)
            : saveComplexDialog(defaultName, s.re, s.im, s.cols, s.rows);
    }

    /** Write {@code re + i·im}, {@code cols x rows}, row-major, as {@code <c8}. */
    public static void writeComplex64(File file, float[] re, float[] im, int cols, int rows)
            throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            writeComplex64(out, re, im, cols, rows);
        }
    }

    /** Same, into a caller-owned stream (left open — e.g. one entry of an .npz zip). */
    public static void writeComplex64(OutputStream out, float[] re, float[] im, int cols, int rows)
            throws IOException {
        int n = cols * rows;
        if (re == null || im == null || re.length < n || im.length < n) {
            throw new IllegalArgumentException("Field arrays do not match " + cols + "x" + rows);
        }
        writeHeader(out, "<c8", "(" + rows + ", " + cols + ")");
        ByteBuffer buf = ByteBuffer.allocate(8 * 8192).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buf.putFloat(re[i]).putFloat(im[i]);
            if (!buf.hasRemaining()) {
                out.write(buf.array(), 0, buf.position());
                buf.clear();
            }
        }
        out.write(buf.array(), 0, buf.position());
    }

    /** NPY v1.0 preamble, padded so the data starts on a 64-byte boundary. */
    private static void writeHeader(OutputStream out, String descr, String shape) throws IOException {
        String dict = "{'descr': '" + descr + "', 'fortran_order': False, 'shape': " + shape + ", }";
        int unpadded = 10 + dict.length() + 1;     // magic+version+len, dict, newline
        int pad = (64 - unpadded % 64) % 64;
        StringBuilder header = new StringBuilder(dict);
        for (int i = 0; i < pad; i++) {
            header.append(' ');
        }
        header.append('\n');
        byte[] h = header.toString().getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[] { (byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0 });
        out.write(h.length & 0xff);
        out.write((h.length >>> 8) & 0xff);
        out.write(h);
    }

    /**
     * Ask for a destination and write the field. Returns the saved file, or null when the
     * user cancelled or there was nothing to save (the reason is reported to the user).
     */
    public static File saveComplexDialog(String defaultName, float[] re, float[] im,
                                         int cols, int rows) {
        if (re == null || im == null || cols <= 0 || rows <= 0) {
            HoloBioFijiUi.message("HoloBio", "No complex field yet — run a reconstruction first.");
            return null;
        }
        SaveDialog sd = new SaveDialog("Save complex field", defaultName, ".npy");
        if (sd.getDirectory() == null || sd.getFileName() == null) {
            return null;
        }
        File out = new File(sd.getDirectory(), sd.getFileName());
        try {
            writeComplex64(out, re, im, cols, rows);
            IJ.showStatus("HoloBio: saved complex field " + cols + "x" + rows + " -> " + out);
            return out;
        } catch (IOException | RuntimeException ex) {
            HoloBioFijiUi.error("HoloBio", "Could not save complex field:\n" + ex.getMessage());
            return null;
        }
    }
}
