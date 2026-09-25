import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Captures live phase-profile regions to CSV.
 *
 * <p>One sample = one reconstructed hologram while recording is on. Along each region (line),
 * phase is collapsed to φ_low / φ_high / Δφ (same trim as offline QPI). The CSV does not use
 * video-file frame indices — just consecutive sample numbers and elapsed time.
 *
 * <p>Raw file is grouped by region. {@link #exportAverage} is the mean of those raw rows.
 */
final class HoloBioRtProfileCsvRecorder {

    private static final class Sample {
        final int    index;
        final double timeS;
        final double low, high, delta;
        Sample(int index, double timeS, double low, double high, double delta) {
            this.index = index; this.timeS = timeS;
            this.low = low; this.high = high; this.delta = delta;
        }
    }

    private long            startNanos;
    private int             samples;
    private int             skipped;
    private int             regionCount;
    private boolean         recording;
    private final List<List<Sample>> byRegion = new ArrayList<>();

    synchronized boolean isRecording()  { return recording; }
    synchronized boolean hasData()      { return samples > 0 && !recording; }
    synchronized int     sampleCount()  { return samples; }
    /** @deprecated use {@link #sampleCount()} */
    synchronized int     frameCount()   { return samples; }
    synchronized int     skippedCount() { return skipped; }

    synchronized void start() {
        startNanos  = System.nanoTime();
        samples     = 0;
        skipped     = 0;
        regionCount = 0;
        byRegion.clear();
        recording   = true;
    }

    /** Append one reconstruction; each curve is one drawn region. */
    synchronized void add(List<HoloBioRtProfileWindow.Curve> curves) {
        if (!recording || curves.isEmpty()) return;

        if (regionCount == 0) {
            regionCount = curves.size();
            for (int i = 0; i < regionCount; i++) byRegion.add(new ArrayList<>());
        } else if (curves.size() != regionCount) {
            skipped++;
            return;
        }

        samples++;
        double timeS = (System.nanoTime() - startNanos) / 1e9;
        for (int li = 0; li < curves.size(); li++) {
            double[] raw = curves.get(li).values;
            // Curves are already QPI-mapped [0, 2π]; do not add π again.
            double[] st = HoloBioQpiSpeckleMath.phaseStats(raw);
            byRegion.get(li).add(new Sample(samples, timeS, st[0], st[1], st[2]));
        }
    }

    synchronized boolean stopAndSave(File dest) throws IOException {
        recording = false;
        if (samples == 0) return false;
        writeGrouped(dest);
        return true;
    }

    synchronized void stopWithoutSave() { recording = false; }

    private void writeGrouped(File dest) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(dest), StandardCharsets.UTF_8));
        try {
            w.write("region,sample,time_s,phi_low_rad,phi_high_rad,delta_phi_rad\n");
            for (int li = 0; li < byRegion.size(); li++) {
                String label = "L" + (li + 1);
                for (Sample s : byRegion.get(li)) {
                    w.write(label); w.write(',');
                    w.write(Integer.toString(s.index)); w.write(',');
                    w.write(String.format(Locale.US, "%.4f", s.timeS)); w.write(',');
                    w.write(fmt(s.low)); w.write(',');
                    w.write(fmt(s.high)); w.write(',');
                    w.write(fmt(s.delta)); w.write('\n');
                }
            }
        } finally {
            w.close();
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%.6f", v);
    }

    /** Mean of the raw rows for each region. */
    synchronized void exportAverage(File file) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            w.write("region,phi_low_rad,phi_high_rad,delta_phi_rad,samples\n");
            for (int li = 0; li < byRegion.size(); li++) {
                List<Sample> list = byRegion.get(li);
                double sumL = 0, sumH = 0, sumD = 0;
                int n = 0;
                for (Sample s : list) {
                    if (Double.isNaN(s.delta)) continue;
                    sumL += s.low; sumH += s.high; sumD += s.delta;
                    n++;
                }
                if (n == 0) {
                    w.write(String.format(Locale.US, "L%d,,,,%d%n", li + 1, list.size()));
                    continue;
                }
                w.write(String.format(Locale.US, "L%d,%.6f,%.6f,%.6f,%d%n",
                        li + 1, sumL / n, sumH / n, sumD / n, n));
            }
        } finally {
            w.close();
        }
    }
}
