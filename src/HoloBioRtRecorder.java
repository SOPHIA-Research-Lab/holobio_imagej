import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Frame recorder for the real-time DHM / DLHM windows, following the Record panel in HoloBio
 * Python: pick one of Phase, Amplitude or Hologram, press Start, press Stop, then choose where
 * to save.
 *
 * <p>Frames are streamed to a temporary raw file on disk while recording and encoded from it
 * after Stop, because the target file is only chosen at the end. They used to be held in memory
 * under a 512 MB cap, which a 1280×960 hologram stream (1.2 MB per frame) filled in about 40 s;
 * on disk the length is limited only by free space, so ten-minute recordings work. Recording
 * stops on its own, and says so, only when the disk is nearly full.
 *
 * <p>The capture thread only copies the frame into a pooled buffer and queues it; a writer
 * thread does the disk I/O, so a slow write never stalls acquisition. If the disk cannot keep up
 * and the queue fills, frames are dropped and counted rather than blocking the camera.
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

    /** Recording stops when the temp folder's disk has less free space than this. */
    private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;
    /** Frames waiting for the writer (~80 MB at 1280×960); beyond that frames are dropped. */
    private static final int QUEUE_FRAMES = 64;
    private static final byte[] END = new byte[0];

    private Target  target = Target.PHASE;
    private boolean recording;
    private boolean hitBudget;
    private int     width, height;
    private long    startNs;
    private long    stopNs;
    private int     frames;
    private int     dropped;

    private File                  rawFile;
    private BlockingQueue<byte[]> queue;
    private BlockingQueue<byte[]> pool;
    /** Frame buffers made so far (at most {@link #QUEUE_FRAMES}); reused through {@link #pool}. */
    private int                   allocated;
    private Thread                writer;
    private volatile IOException  writeError;

    public synchronized void start(Target t) {
        discard();
        target    = t;
        recording = true;
        hitBudget = false;
        width = height = 0;
        frames = dropped = 0;
        writeError = null;
        startNs = System.nanoTime();
        // Opened here, on the thread that pressed Start, so the first frame does not stall the
        // capture thread while the file and writer are set up.
        try {
            openWriter();
        } catch (IOException e) {
            writeError = e;
        }
    }

    /** Why recording could not write to disk, or null. */
    public synchronized String writeErrorMessage() {
        return writeError != null ? writeError.getMessage() : null;
    }

    /** @return true when this call stopped a recording */
    public synchronized boolean stop() {
        if (!recording) return false;
        recording = false;
        stopNs = System.nanoTime();
        finishWriter();
        return true;
    }

    public synchronized boolean isRecording() { return recording; }
    /** True when recording stopped by itself because the disk was nearly full. */
    public synchronized boolean hitBudget()   { return hitBudget; }
    public synchronized int     frameCount()  { return frames; }
    /** Frames skipped because the disk could not keep up. */
    public synchronized int     droppedCount() { return dropped; }
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
        if (width == 0) {
            width = w;
            height = h;
        }
        // A resolution change mid-recording cannot go into one video file
        if (w != width || h != height) return;
        if (writeError != null) {
            recording = false;
            stopNs = System.nanoTime();
            finishWriter();
            return;
        }
        if ((frames & 63) == 0 && rawFile.getParentFile().getUsableSpace() < MIN_FREE_BYTES) {
            hitBudget = true;
            recording = false;
            stopNs = System.nanoTime();
            finishWriter();
            return;
        }
        byte[] copy = pool.poll();
        if (copy == null && allocated < QUEUE_FRAMES) {
            // Buffers are made as needed, so starting a recording does not stall the camera
            // allocating all of them at once.
            copy = new byte[width * height];
            allocated++;
        }
        if (copy == null) {
            dropped++;
            return;
        }
        int n = Math.min(copy.length, data.length);
        for (int i = 0; i < n; i++) {
            float v = data[i];
            copy[i] = (byte) (v <= 0 ? 0 : (v >= 255 ? 255 : (int) (v + 0.5f)));
        }
        queue.add(copy);    // never blocks: the queue holds every buffer in the pool
        frames++;
    }

    /** Temp file, buffer queue and writer thread. Buffers are sized by the first frame. */
    private void openWriter() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "holobio_rec");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        // A plain timestamp name: File.createTempFile seeds SecureRandom, which took ~0.4 s.
        rawFile = new File(dir, "rec_" + System.currentTimeMillis() + "_" + System.nanoTime() + ".raw");
        if (!rawFile.createNewFile()) {
            throw new IOException("Cannot create " + rawFile);
        }
        rawFile.deleteOnExit();
        queue = new ArrayBlockingQueue<>(QUEUE_FRAMES + 1);
        pool = new ArrayBlockingQueue<>(QUEUE_FRAMES);
        allocated = 0;
        final OutputStream os = new BufferedOutputStream(new FileOutputStream(rawFile), 8 << 20);
        final BlockingQueue<byte[]> q = queue;
        final BlockingQueue<byte[]> p = pool;
        writer = new Thread(() -> {
            try (OutputStream out = os) {
                while (true) {
                    byte[] f = q.take();
                    if (f == END) break;
                    out.write(f);
                    p.offer(f);
                }
            } catch (IOException e) {
                writeError = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "HoloBio-rec-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Let the writer flush what is queued and close the temp file. */
    private void finishWriter() {
        if (writer == null) return;
        try {
            while (writer.isAlive() && !queue.offer(END, 100, TimeUnit.MILLISECONDS)) {
                // queue momentarily full; the writer is draining it
            }
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer = null;
        pool = null;
    }

    /** Frames per second as actually captured, or 0 when there is nothing to measure. */
    public synchronized double measuredFps() {
        long end = recording ? System.nanoTime() : stopNs;
        double elapsed = (end - startNs) / 1e9;
        if (elapsed <= 0 || frames == 0) return 0;
        return frames / elapsed;
    }

    /** Drop the recording and delete its temp file. */
    public synchronized void discard() {
        recording = false;
        finishWriter();
        if (rawFile != null) {
            rawFile.delete();
            rawFile = null;
        }
        frames = 0;
        width = height = 0;
    }

    /**
     * Write the recorded frames to {@code out}. A {@code .tif} extension produces an ImageJ
     * stack, which needs no external tool; anything else is muxed by FFmpeg.
     *
     * @return a short description of what was written
     */
    public String save(File out, double fps) throws IOException {
        File raw;
        int w, h, n;
        Target t;
        synchronized (this) {
            if (recording) stop();
            if (writeError != null) throw new IOException("Recording to disk failed: " + writeError.getMessage());
            if (frames == 0 || rawFile == null) throw new IOException("Nothing recorded.");
            raw = rawFile;
            // The save owns the temp file from here, so a new recording started meanwhile
            // cannot delete it while it is being encoded.
            rawFile = null;
            w = width; h = height;
            n = (int) Math.min(frames, raw.length() / ((long) w * h));
            t = target;
        }
        try {
            String what = encode(out, raw, w, h, n, t, fps);
            raw.delete();
            return what;
        } catch (IOException | RuntimeException e) {
            // Keep the frames: the raw file can still be converted by hand.
            throw new IOException(e.getMessage() + "\nThe recorded frames are kept in " + raw
                + " (8-bit raw, " + w + "×" + h + ", " + n + " frames).", e);
        }
    }

    private static String encode(File out, File raw, int w, int h, int n, Target t, double fps) throws IOException {
        double rate = fps > 0.1 ? fps : 10.0;

        // Fail here rather than inside FFmpeg, whose complaint about the destination arrives
        // buried in a page of stream diagnostics.
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot write to " + dir + " — that folder does not exist.");
        }

        String name = out.getName().toLowerCase(Locale.US);
        if (name.endsWith(".tif") || name.endsWith(".tiff")) {
            writeTiffStack(out, raw, w, h, n);
            return n + " frames → TIFF stack";
        }
        boolean lossless = t == Target.HOLOGRAM;
        writeVideo(out, raw, w, h, n, rate, lossless);
        return String.format(Locale.US, "%d frames at %.1f fps%s", n, rate,
                lossless ? ", lossless" : "");
    }

    /** The temp file as an ImageJ stack read slice by slice, so a long recording is never all in memory. */
    private static final class RawFileStack extends VirtualStack {
        private final File raw;
        private final int w, h, n;

        RawFileStack(File raw, int w, int h, int n) {
            super(w, h, null, null);
            this.raw = raw;
            this.w = w;
            this.h = h;
            this.n = n;
        }

        @Override public int getSize() { return n; }
        @Override public String getSliceLabel(int k) { return null; }
        @Override public int getBitDepth() { return 8; }

        @Override
        public ImageProcessor getProcessor(int k) {
            byte[] px = new byte[w * h];
            try (RandomAccessFile f = new RandomAccessFile(raw, "r")) {
                f.seek((long) (k - 1) * w * h);
                f.readFully(px);
            } catch (IOException e) {
                throw new RuntimeException("Cannot read recorded frame " + k + ": " + e.getMessage(), e);
            }
            return new ByteProcessor(w, h, px, null);
        }
    }

    private static void writeTiffStack(File out, File raw, int w, int h, int n) {
        ImageStack stack = new RawFileStack(raw, w, h, n);
        ImagePlus imp = new ImagePlus(out.getName(), stack);
        if (!new FileSaver(imp).saveAsTiffStack(out.getAbsolutePath())) {
            throw new RuntimeException("ImageJ could not write " + out);
        }
    }

    /**
     * Pipe raw 8-bit frames from the temp file into FFmpeg. It is already required for video
     * input, so recording adds no new dependency.
     *
     * <p>{@code lossless} is for holograms. Ordinary H.264 (CRF 23, 4:2:0) is fine to watch but
     * discards exactly the fine fringes a hologram is made of: a USAF hologram pushed through
     * it no longer reconstructs, while the lossless encode does. Lossless files are ~25× larger
     * and are meant to be re-processed (HoloBio Video input, Fiji, FFmpeg, VLC) — some
     * built-in players will not play them.
     */
    private static void writeVideo(File out, File raw, int w, int h, int n, double fps,
                                   boolean lossless) throws IOException {
        boolean avi = out.getName().toLowerCase(Locale.US).endsWith(".avi");
        List<String> cmd = new ArrayList<>(java.util.Arrays.asList(
                "ffmpeg", "-y",
                "-f", "rawvideo", "-pix_fmt", "gray",
                "-s", w + "x" + h,
                "-r", String.format(Locale.US, "%.4f", fps),
                "-i", "pipe:0",
                "-an"));
        if (lossless) {
            // Grey stays grey and every pixel is kept bit-for-bit.
            cmd.addAll(avi
                    ? java.util.Arrays.asList("-c:v", "ffv1", "-pix_fmt", "gray")
                    : java.util.Arrays.asList("-c:v", "libx264", "-qp", "0", "-preset", "veryfast",
                                              "-pix_fmt", "gray"));
        } else {
            // yuv420p halves chroma resolution, so both axes must be even
            cmd.addAll(java.util.Arrays.asList(
                    "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
                    "-c:v", avi ? "mpeg4" : "libx264",
                    "-pix_fmt", "yuv420p"));
        }
        cmd.add(out.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);

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
                int k;
                while ((k = es.read(b)) != -1) {
                    synchronized (err) {
                        if (err.length() < 4000) err.append(new String(b, 0, k));
                    }
                }
            } catch (IOException ignored) {
                // Pipe closed with FFmpeg; nothing useful left to read
            }
        }, "ffmpeg-rec-stderr");
        drain.setDaemon(true);
        drain.start();

        long total = (long) n * w * h;
        try (OutputStream os = proc.getOutputStream();
             InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(raw), 8 << 20)) {
            byte[] buf = new byte[1 << 20];
            long left = total;
            while (left > 0) {
                int k = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (k < 0) break;
                os.write(buf, 0, k);
                left -= k;
            }
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
            String tail;
            synchronized (err) {
                tail = err.toString();
            }
            int cut = Math.max(0, tail.length() - 400);
            throw new IOException("FFmpeg failed (exit " + code + "): " + tail.substring(cut));
        }
    }
}
