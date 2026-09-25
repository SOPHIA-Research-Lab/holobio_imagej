import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams every reconstructed complex field to an {@code .npz} while recording.
 *
 * <p>An {@code .npz} is just a zip of {@code .npy} files, so the result opens with
 * {@code d = np.load("fields.npz")}: {@code d["frame_00000"]}, {@code d["frame_00001"]}, … are
 * {@code complex64 (rows, cols)} arrays, in capture order.
 *
 * <p>Fields go to a temp file on disk as they arrive, never accumulated in memory — a minute
 * of 960x540 frames is several GB. Writing happens on its own thread behind a small bounded
 * queue so the reconstruction loop never waits on the disk; if the disk falls behind, frames
 * are dropped and counted rather than stalling live reconstruction. Entries are STORED, not
 * deflated: float noise barely compresses and deflate would cost far more CPU than it saves.
 */
public final class HoloBioFieldRecorder {

    /** Outcome of {@link #stop()}: the temp .npz and what went into it. */
    public static final class Result {
        public final File file;
        public final int frames;
        public final int dropped;
        public final String error;

        Result(File file, int frames, int dropped, String error) {
            this.file = file;
            this.frames = frames;
            this.dropped = dropped;
            this.error = error;
        }
    }

    private static final class Item {
        final HoloBioNpy.Snapshot field;

        Item(HoloBioNpy.Snapshot field) {
            this.field = field;
        }
    }

    private static final Item END = new Item(null);

    /** Enough to ride out a slow disk for a second or two without holding GBs of fields. */
    private static final int QUEUE_FRAMES = 16;

    private volatile boolean recording;
    private volatile int written;
    private volatile int dropped;
    private volatile String error;

    private BlockingQueue<Item> queue;
    private Thread writer;
    private File tmp;

    public boolean isRecording() {
        return recording;
    }

    public int frameCount() {
        return written;
    }

    public int droppedCount() {
        return dropped;
    }

    public synchronized void start() throws IOException {
        if (recording) {
            return;
        }
        tmp = File.createTempFile("holobio_fields_", ".npz");
        tmp.deleteOnExit();
        final ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp));
        zip.setMethod(ZipOutputStream.STORED);
        queue = new ArrayBlockingQueue<Item>(QUEUE_FRAMES);
        written = 0;
        dropped = 0;
        error = null;
        final BlockingQueue<Item> q = queue;
        writer = new Thread(() -> drain(q, zip), "HoloBio-field-recorder");
        writer.setDaemon(true);
        writer.start();
        recording = true;
    }

    /** Hand over one frame. Never blocks: a full queue drops the frame and counts it. */
    public void add(HoloBioNpy.Snapshot field) {
        BlockingQueue<Item> q = queue;
        if (!recording || field == null || q == null) {
            return;
        }
        if (!q.offer(new Item(field))) {
            dropped++;
        }
    }

    /** Finish writing and close the archive. The caller moves {@link Result#file} somewhere. */
    public synchronized Result stop() {
        // Keyed on the writer, not on `recording`: a disk error clears `recording` from the
        // writer thread, and the partial file still has to be handed back or deleted.
        if (writer == null) {
            return new Result(null, 0, 0, null);
        }
        recording = false;
        try {
            // Let the queue drain rather than dropping the tail of the recording — but a
            // writer that already died on an error will never take END, so don't wait on it.
            while (writer.isAlive() && !queue.offer(END, 100, TimeUnit.MILLISECONDS)) {
                // retry until the writer makes room or exits
            }
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted while finishing the recording.";
        }
        Result r = new Result(tmp, written, dropped, error);
        queue = null;
        writer = null;
        tmp = null;
        return r;
    }

    /** Stop and delete whatever was recorded. */
    public void discard() {
        Result r = stop();
        if (r.file != null) {
            //noinspection ResultOfMethodCallIgnored
            r.file.delete();
        }
    }

    /**
     * Ask where to keep a finished recording and move it there off the EDT (a move across
     * drives is a full copy). Cancelling deletes it. {@code status} receives progress text.
     */
    public static void saveAs(Result r, java.util.function.Consumer<String> status) {
        if (r == null || r.file == null) {
            return;
        }
        if (r.frames == 0) {
            //noinspection ResultOfMethodCallIgnored
            r.file.delete();
            HoloBioFijiUi.message("HoloBio", r.error != null
                ? "Field recording failed: " + r.error
                : "No fields were recorded — is reconstruction running?");
            return;
        }
        ij.io.SaveDialog sd = new ij.io.SaveDialog("Save complex fields", "complex_fields", ".npz");
        if (sd.getDirectory() == null || sd.getFileName() == null) {
            //noinspection ResultOfMethodCallIgnored
            r.file.delete();
            status.accept("Field recording discarded.");
            return;
        }
        final File dest = new File(sd.getDirectory(), sd.getFileName());
        final String summary = r.frames + " field(s)"
            + (r.dropped > 0 ? ", " + r.dropped + " dropped (disk too slow)" : "")
            + (r.error != null ? " — stopped early: " + r.error : "");
        status.accept("Saving " + summary + "…");
        Thread t = new Thread(() -> {
            String msg;
            try {
                java.nio.file.Files.move(r.file.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                msg = "Saved " + summary + " → " + dest.getName();
            } catch (IOException ex) {
                msg = "Could not save fields: " + ex.getMessage();
            }
            final String m = msg;
            javax.swing.SwingUtilities.invokeLater(() -> status.accept(m));
        }, "HoloBio-field-save");
        t.setDaemon(true);
        t.start();
    }

    private void drain(BlockingQueue<Item> q, ZipOutputStream zip) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 22);
        try {
            while (true) {
                Item it = q.poll(250, TimeUnit.MILLISECONDS);
                if (it == null) {
                    continue;
                }
                if (it == END) {
                    break;
                }
                bytes.reset();
                HoloBioNpy.Snapshot f = it.field;
                HoloBioNpy.writeComplex64(bytes, f.re, f.im, f.cols, f.rows);
                putStored(zip, String.format("frame_%05d.npy", written), bytes);
                written++;
            }
        } catch (Exception ex) {
            error = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            recording = false;
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
                // the error above, if any, is the one worth reporting
            }
        }
    }

    /** STORED entries must declare size and CRC up front, so the entry is buffered first. */
    private static void putStored(ZipOutputStream zip, String name, ByteArrayOutputStream bytes)
            throws IOException {
        byte[] data = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
