import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frame recorder for the real-time DHM window, following the Record panel in HoloBio Python:
 * pick one of Phase, Amplitude or Hologram, press Start, press Stop, then choose where to save.
 *
 * <p>Frames are held in memory while recording and written out afterwards, because the target
 * file is only chosen at the end. That is bounded by {@link #BUDGET_BYTES} — an unbounded
 * buffer would eventually take the JVM down mid-experiment, so recording stops on its own and
 * says so rather than dying.
 *
 * <p>Playback rate is measured from the wall clock rather than taken from the nominal source
 * rate, matching Python: reconstruction cannot always keep up with capture, so the frames that
 * were actually recorded arrived slower than the camera ran, and using the nominal rate would
 * play the result back too fast.
 */
public final class HoloBioRtRecorder {

    public enum Target {
        PHASE("Phase"), AMPLITUDE("Amplitude"), HOLOGRAM("Hologram");

        private final String label;
        Target(String label) { this.label = label; }
        @Override public String toString() { return label; }

        static Target fromLabel(String s) {
            for (Target t : values()) if (t.label.equals(s)) return t;
            return PHASE;
        }
    }

    /** Ceiling on buffered frame data; recording stops once it is reached. */
    private static final long BUDGET_BYTES = 512L * 1024 * 1024;

    private final List<byte[]> frames = new ArrayList<>();
    private Target  target = Target.PHASE;
    private boolean recording;
    private boolean hitBudget;
    private int     width, height;
    private long    startNs;
    private long    stopNs;

    public synchronized void start(Target t) {
        frames.clear();
        target    = t;
        recording = true;
        hitBudget = false;
        width = height = 0;
        startNs = System.nanoTime();
    }

    /** @return true when this call filled the buffer and recording was stopped */
    public synchronized boolean stop() {
        if (!recording) return false;
        recording = false;
        stopNs = System.nanoTime();
        return true;
    }

    public synchronized boolean isRecording() { return recording; }
    public synchronized boolean hitBudget()   { return hitBudget; }
    public synchronized int     frameCount()  { return frames.size(); }
    public synchronized Target  target()      { return target; }
    public synchronized int     width()       { return width; }
    public synchronized int     height()      { return height; }

    /**
     * Append one frame if it belongs to the stream being recorded. Values are display-scale
     * 0…255 floats; they are clamped to bytes and copied here, so the caller may reuse its
     * buffer immediately and needs no scratch array of its own.
     */
    public synchronized void add(Target t, float[] data, int w, int h) {
        if (!recording || t != target) return;
        if (width == 0) { width = w; height = h; }
        // A resolution change mid-recording cannot go into one video file
        if (w != width || h != height) return;

        long n = (long) w * h;
        if ((frames.size() + 1) * n > BUDGET_BYTES) {
            hitBudget = true;
            recording = false;
            stopNs = System.nanoTime();
            return;
        }
        byte[] copy = new byte[w * h];
        for (int i = 0; i < copy.length && i < data.length; i++) {
            float v = data[i];
            copy[i] = (byte) (v <= 0 ? 0 : (v >= 255 ? 255 : (int) (v + 0.5f)));
        }
        frames.add(copy);
    }

    /** Frames per second as actually captured, or 0 when there is nothing to measure. */
    public synchronized double measuredFps() {
        long end = recording ? System.nanoTime() : stopNs;
        double elapsed = (end - startNs) / 1e9;
        if (elapsed <= 0 || frames.isEmpty()) return 0;
        return frames.size() / elapsed;
    }

    public synchronized void discard() {
        frames.clear();
        width = height = 0;
    }

    /**
     * Write the buffered frames to {@code out}. A {@code .tif} extension produces an ImageJ
     * stack, which needs no external tool; anything else is muxed by FFmpeg.
     *
     * @return a short description of what was written
     */
    public String save(File out, double fps) throws IOException {
        List<byte[]> snapshot;
        int w, h;
        synchronized (this) {
            if (frames.isEmpty()) throw new IOException("Nothing recorded.");
            snapshot = new ArrayList<>(frames);
            w = width; h = height;
        }
        double rate = fps > 0.1 ? fps : 10.0;

        // Fail here rather than inside FFmpeg, whose complaint about the destination arrives
        // buried in a page of stream diagnostics.
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot write to " + dir + " — that folder does not exist.");
        }

        String name = out.getName().toLowerCase(Locale.US);
        if (name.endsWith(".tif") || name.endsWith(".tiff")) {
            writeTiffStack(out, snapshot, w, h);
            return snapshot.size() + " frames → TIFF stack";
        }
        writeVideo(out, snapshot, w, h, rate);
        return String.format(Locale.US, "%d frames at %.1f fps", snapshot.size(), rate);
    }

    private static void writeTiffStack(File out, List<byte[]> frames, int w, int h) {
        ImageStack stack = new ImageStack(w, h);
        for (byte[] f : frames) stack.addSlice(new ByteProcessor(w, h, f, null));
        ImagePlus imp = new ImagePlus(out.getName(), stack);
        if (!new FileSaver(imp).saveAsTiffStack(out.getAbsolutePath())) {
            throw new RuntimeException("ImageJ could not write " + out);
        }
    }

    /**
     * Pipe raw 8-bit frames into FFmpeg. It is already required for video input, so recording
     * adds no new dependency.
     */
    private static void writeVideo(File out, List<byte[]> frames, int w, int h, double fps)
            throws IOException {
        boolean avi = out.getName().toLowerCase(Locale.US).endsWith(".avi");
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "rawvideo", "-pix_fmt", "gray",
                "-s", w + "x" + h,
                "-r", String.format(Locale.US, "%.4f", fps),
                "-i", "pipe:0",
                "-an",
                // yuv420p halves chroma resolution, so both axes must be even
                "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
                "-c:v", avi ? "mpeg4" : "libx264",
                "-pix_fmt", "yuv420p",
                out.getAbsolutePath());

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("FFmpeg not found on PATH. Save as .tif instead, "
                    + "or install FFmpeg.", e);
        }

        StringBuilder err = new StringBuilder();
        InputStream es = proc.getErrorStream();
        Thread drain = new Thread(() -> {
            try {
                byte[] b = new byte[4096];
                int n;
                while ((n = es.read(b)) != -1) {
                    if (err.length() < 4000) err.append(new String(b, 0, n));
                }
            } catch (IOException ignored) {
                // Pipe closed with FFmpeg; nothing useful left to read
            }
        }, "ffmpeg-rec-stderr");
        drain.setDaemon(true);
        drain.start();

        try (OutputStream os = proc.getOutputStream()) {
            for (byte[] f : frames) os.write(f);
        } catch (IOException e) {
            // FFmpeg rejecting its input closes the pipe; its stderr explains why
        }

        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroy();
            throw new IOException("Recording save interrupted.");
        }
        if (code != 0) {
            String tail = err.toString();
            int cut = Math.max(0, tail.length() - 400);
            throw new IOException("FFmpeg failed (exit " + code + "): " + tail.substring(cut));
        }
    }
}
