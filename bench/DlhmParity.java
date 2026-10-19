import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Parity probe for offline DLHM AS / DL against a known analytic field.
 * Prints amp/phase stats so a Python twin can be compared by eye / numbers.
 */
public class DlhmParity {

    public static void main(String[] args) throws Exception {
        int rows = 256, cols = 256;   // power-of-two: should match Python closely
        runCase("pow2", rows, cols);

        rows = 240; cols = 320;       // non-pow2: exercises padding path
        runCase("rect", rows, cols);
    }

    static void runCase(String tag, int rows, int cols) throws Exception {
        float[] holo = synthetic(rows, cols);
        HoloBioDlhmParams p = new HoloBioDlhmParams();
        p.setWavelengthUm(0.532);
        p.setPixelPitchUm(2.40);
        p.setL(10000);
        p.setZ(5000);
        p.setAlgorithm("AS");

        float[] re = new float[rows * cols], im = new float[rows * cols];
        HoloBioDlhmMath.reconstruct(holo, rows, cols, p, re, im);
        dump(tag + "_AS", re, im, rows, cols);

        p.setAlgorithm("DL");
        HoloBioDlhmMath.reconstruct(holo, rows, cols, p, re, im);
        dump(tag + "_DL", re, im, rows, cols);
    }

    /** Soft Gaussian object under spherical-ish fringe modulation. */
    static float[] synthetic(int rows, int cols) {
        float[] h = new float[rows * cols];
        double cx = cols / 2.0, cy = rows / 2.0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double x = (c - cx) / cols, y = (r - cy) / rows;
                double env = Math.exp(-8 * (x * x + y * y));
                double fringe = Math.cos(40 * Math.PI * (x * x + y * y));
                h[r * cols + c] = (float) (80 + 60 * env * (1 + 0.4 * fringe));
            }
        }
        return h;
    }

    static void dump(String tag, float[] re, float[] im, int rows, int cols) throws Exception {
        int n = rows * cols;
        double ampMean = 0, ampMax = 0, phMean = 0;
        float[] amp = new float[n], phase = new float[n];
        for (int i = 0; i < n; i++) {
            double a = Math.hypot(re[i], im[i]);
            double ph = Math.atan2(im[i], re[i]);
            amp[i] = (float) a;
            phase[i] = (float) ph;
            ampMean += a;
            if (a > ampMax) ampMax = a;
            phMean += ph;
        }
        ampMean /= n;
        phMean /= n;
        // centre 32×32 energy vs whole — a focused reconstruction concentrates energy
        int hw = Math.min(16, cols / 4), hh = Math.min(16, rows / 4);
        double centre = 0, total = 0;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                double a2 = (double) amp[r * cols + c] * amp[r * cols + c];
                total += a2;
                if (Math.abs(r - rows / 2) < hh && Math.abs(c - cols / 2) < hw) centre += a2;
            }
        System.out.printf(Locale.US,
                "%-10s %dx%d | amp mean=%.3f max=%.3f | phase mean=%.4f | centreE=%.3f%%%n",
                tag, cols, rows, ampMean, ampMax, phMean, 100 * centre / (total + 1e-30));

        // Write raw floats for optional Python comparison
        File dir = new File("bench/dlhm_parity");
        dir.mkdirs();
        writeFloats(new File(dir, tag + "_amp.bin"), amp);
        writeFloats(new File(dir, tag + "_phase.bin"), phase);
        // Files.writeString is Java 11+; Fiji runs this on Java 8.
        Files.write(new File(dir, tag + "_meta.txt").toPath(),
                String.format(Locale.US, "rows=%d cols=%d ampMean=%.8f ampMax=%.8f phaseMean=%.8f%n",
                        rows, cols, ampMean, ampMax, phMean)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void writeFloats(File f, float[] data) throws Exception {
        byte[] buf = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            int bits = Float.floatToIntBits(data[i]);
            buf[4 * i]     = (byte) (bits);
            buf[4 * i + 1] = (byte) (bits >> 8);
            buf[4 * i + 2] = (byte) (bits >> 16);
            buf[4 * i + 3] = (byte) (bits >> 24);
        }
        Files.write(f.toPath(), buf);
    }
}
