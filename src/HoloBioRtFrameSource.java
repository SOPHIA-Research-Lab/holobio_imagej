import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A stream of frames for the real-time modules, from a camera, a video file, a still
 * image (PNG/TIFF/JPEG), or a TIFF stack.
 *
 * <p>Video / multi-frame stacks deliver each source frame once and then end, unless
 * {@link #seekToFrame(int)} rewinds. Single-frame stills are held and re-emitted so a live
 * profile can be drawn after the reconstruction is on screen.
 */
public final class HoloBioRtFrameSource implements Closeable {

    private static final double FPS_CEILING = 20.0;

    public static final int NATIVE_RESOLUTION = 0;

    private final HoloBioWebcamBackend camera;
    private final ImagePlus            stack;
    private final Ffmpeg               ffmpeg;
    private final boolean              still;
    private final double               nominalFps;
    private final String               description;
    private final int                  knownFrames;   // -1 if unknown (live camera)
    private int                        slice = 1;
    private int                        delivered;
    private BufferedImage              heldStill;

    private HoloBioRtFrameSource(HoloBioWebcamBackend camera, ImagePlus stack, Ffmpeg ffmpeg,
                                 boolean still, double nominalFps, int knownFrames, String description) {
        this.camera      = camera;
        this.stack       = stack;
        this.ffmpeg      = ffmpeg;
        this.still       = still;
        this.nominalFps  = nominalFps;
        this.knownFrames = knownFrames;
        this.description = description;
    }

    public static HoloBioRtFrameSource camera(HoloBioWebcamBackend backend, int index)
            throws Exception {
        backend.connectIndex(index);
        return new HoloBioRtFrameSource(backend, null, null, false, backend.getNominalFps(30.0), -1,
                "Camera connected — streaming.");
    }

    public static HoloBioRtFrameSource file(String path) throws IOException {
        return file(path, NATIVE_RESOLUTION);
    }

    public static HoloBioRtFrameSource file(String path, int maxSide) throws IOException {
        String name  = new File(path).getName();
        String lower = path.toLowerCase(Locale.US);

        if (isImageFile(lower)) {
            ImagePlus imp = IJ.openImage(path);
            if (imp == null) throw new IOException("Cannot open image: " + name);
            int n = Math.max(1, imp.getStackSize());
            boolean still = n == 1;
            String desc = still
                    ? String.format(Locale.US, "Still — %s  ·  %d×%d",
                            name, imp.getWidth(), imp.getHeight())
                    : "Stack: " + n + " frame(s) — " + name;
            return new HoloBioRtFrameSource(null, imp, null, still, still ? 5.0 : 10.0, n, desc);
        }

        try {
            Ffmpeg f = new Ffmpeg(path, maxSide, 0);
            String desc = f.knownFrames > 0
                    ? String.format(Locale.US,
                            "Video (FFmpeg) — %s  ·  %d×%d  ·  %d frame(s)  ·  %.3g fps",
                            name, f.frameWidth, f.frameHeight, f.knownFrames, f.outputFps)
                    : String.format(Locale.US,
                            "Video (FFmpeg) — %s  ·  %d×%d  ·  %.3g fps",
                            name, f.frameWidth, f.frameHeight, f.outputFps);
            return new HoloBioRtFrameSource(null, null, f, false, f.outputFps, f.knownFrames, desc);
        } catch (IOException ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.contains("No such file")) {
                throw new IOException(
                        "FFmpeg not found on PATH. Install FFmpeg or convert the video to a "
                        + "TIFF stack.");
            }
            throw new IOException("Cannot open video: " + msg);
        }
    }

    static boolean isImageFile(String lowerPath) {
        return lowerPath.endsWith(".tif") || lowerPath.endsWith(".tiff")
                || lowerPath.endsWith(".png") || lowerPath.endsWith(".jpg")
                || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".bmp")
                || lowerPath.endsWith(".gif");
    }

    /**
     * Next frame, or null once a video / stack has run out.
     * Frame index for a successful return is {@link #deliveredCount()} after the call.
     */
    public BufferedImage next() throws IOException {
        BufferedImage img;
        if (camera != null) {
            img = camera.grabImage();
        } else if (ffmpeg != null) {
            img = ffmpeg.next();
        } else if (still) {
            if (heldStill == null) {
                if (stack == null) return null;
                stack.setSlice(1);
                heldStill = processorToGrayImage(stack.getProcessor());
                delivered = 1;
            }
            return heldStill;
        } else if (stack == null || stack.getStackSize() == 0) {
            return null;
        } else if (slice > stack.getStackSize()) {
            return null;
        } else {
            stack.setSlice(slice++);
            img = processorToGrayImage(stack.getProcessor());
        }
        if (img != null) delivered++;
        return img;
    }

    /** Jump to a 0-based frame. No-op for cameras and stills. */
    public synchronized void seekToFrame(int frame) throws IOException {
        if (camera != null || still) return;
        int target = Math.max(0, frame);
        if (knownFrames > 0) target = Math.min(target, knownFrames - 1);
        if (ffmpeg != null) {
            ffmpeg.seekToFrame(target);
            delivered = target;
        } else if (stack != null) {
            slice = target + 1;
            delivered = target;
        }
    }

    public boolean isStill() { return still; }

    public boolean isSeekable() {
        return !still && camera == null && knownFrames > 1;
    }

    public int deliveredCount() { return delivered; }

    public int knownFrameCount() { return knownFrames; }

    public double durationSec() {
        if (still) return -1;
        if (knownFrames > 0 && nominalFps > 1e-6) return knownFrames / nominalFps;
        return -1;
    }

    public double positionSec() {
        if (still) return -1;
        if (nominalFps > 1e-6) return delivered / nominalFps;
        return -1;
    }

    public double nominalFps() { return nominalFps; }

    public boolean needsPacing() { return camera == null; }

    public String description() { return description; }

    @Override
    public void close() {
        if (ffmpeg != null) ffmpeg.close();
    }

    /** 8-bit grey without ImageJ auto-contrast (hologram values stay as stored). */
    private static BufferedImage processorToGrayImage(ImageProcessor ip) {
        int w = ip.getWidth(), h = ip.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        if (ip instanceof ByteProcessor) {
            byte[] src = (byte[]) ip.getPixels();
            System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
            return img;
        }
        for (int i = 0; i < dst.length; i++) {
            int v = (int) Math.round(ip.getf(i));
            if (v < 0) v = 0;
            else if (v > 255) v = 255;
            dst[i] = (byte) v;
        }
        return img;
    }

    private static final class Ffmpeg {

        final double outputFps;
        final int    frameWidth, frameHeight;
        final int    knownFrames;
        private final String path;
        private final int    maxSide;
        private Process     proc;
        private InputStream in;
        private final byte[] frameBuffer;
        private int          emitted;

        Ffmpeg(String path, int maxSide, int startFrame) throws IOException {
            this.path = path;
            this.maxSide = maxSide;
            int[] orig = probeDimensions(path);
            int origW = orig[0], origH = orig[1];

            double scale = maxSide > 0
                    ? Math.min(1.0, (double) maxSide / Math.max(origW, origH))
                    : 1.0;
            if (scale < 1.0) {
                frameWidth  = ((int) (origW * scale)) & ~1;
                frameHeight = ((int) (origH * scale)) & ~1;
            } else {
                frameWidth  = origW;
                frameHeight = origH;
            }
            frameBuffer = new byte[frameWidth * frameHeight];
            knownFrames = probeFrameCount(path);
            outputFps = Math.min(probeFrameRate(path, FPS_CEILING), FPS_CEILING);
            openAt(startFrame);
        }

        void seekToFrame(int frame) throws IOException {
            close();
            openAt(Math.max(0, frame));
        }

        private void openAt(int startFrame) throws IOException {
            emitted = startFrame;
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            if (startFrame > 0 && outputFps > 1e-6) {
                cmd.add("-ss");
                cmd.add(String.format(Locale.US, "%.6f", startFrame / outputFps));
            }
            cmd.add("-i");
            cmd.add(path);
            if (maxSide > 0 && (frameWidth != 0)) {
                // scale only when the constructor actually reduced size
                int[] orig = probeDimensions(path);
                if (frameWidth != orig[0] || frameHeight != orig[1]) {
                    cmd.add("-vf");
                    cmd.add("scale=" + frameWidth + ":" + frameHeight);
                }
            }
            int remaining = knownFrames > 0 ? Math.max(1, knownFrames - startFrame) : 0;
            if (remaining > 0) {
                cmd.add("-frames:v");
                cmd.add(Integer.toString(remaining));
            }
            cmd.add("-vsync");
            cmd.add("0");
            cmd.add("-f");
            cmd.add("rawvideo");
            cmd.add("-pix_fmt");
            cmd.add("gray");
            cmd.add("pipe:1");
            proc = new ProcessBuilder(cmd).start();
            drain(proc.getErrorStream(), "ffmpeg-stderr");
            in = new BufferedInputStream(proc.getInputStream(), 1 << 20);
        }

        BufferedImage next() throws IOException {
            if (knownFrames > 0 && emitted >= knownFrames) return null;
            if (!readFully(frameBuffer)) return null;
            emitted++;
            BufferedImage img = new BufferedImage(frameWidth, frameHeight,
                    BufferedImage.TYPE_BYTE_GRAY);
            byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(frameBuffer, 0, px, 0, frameBuffer.length);
            return img;
        }

        void close() {
            if (proc != null) proc.destroy();
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { /* going away */ }
            }
        }

        private boolean readFully(byte[] buf) throws IOException {
            int done = 0;
            while (done < buf.length) {
                int n = in.read(buf, done, buf.length - done);
                if (n < 0) return false;
                done += n;
            }
            return true;
        }

        private static int[] probeDimensions(String path) throws IOException {
            String out = ffprobe(path, "stream=width,height");
            String[] parts = out.replaceAll("[^0-9,]", "").split(",");
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new IOException("Could not probe video dimensions (got: '" + out + "')");
            }
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        }

        private static int probeFrameCount(String path) {
            try {
                String out = ffprobe(path, "stream=nb_frames").trim();
                if (!out.isEmpty() && !out.equals("N/A")) {
                    int n = Integer.parseInt(out.replaceAll("[^0-9]", ""));
                    if (n > 0) return n;
                }
            } catch (Exception ignored) { /* fall through */ }
            try {
                Process p = new ProcessBuilder(
                        "ffprobe", "-v", "error", "-count_frames",
                        "-select_streams", "v:0",
                        "-show_entries", "stream=nb_read_frames",
                        "-of", "csv=p=0", path).start();
                drain(p.getErrorStream(), "ffprobe-count-stderr");
                byte[] buf = new byte[64];
                int n = 0, r;
                InputStream is = p.getInputStream();
                while (n < buf.length && (r = is.read(buf, n, buf.length - n)) > 0) n += r;
                p.destroy();
                String out = new String(buf, 0, n).trim().replaceAll("[^0-9]", "");
                if (!out.isEmpty()) {
                    int c = Integer.parseInt(out);
                    if (c > 0) return c;
                }
            } catch (Exception ignored) { /* unknown length */ }
            return 0;
        }

        private static double probeFrameRate(String path, double fallback) {
            try {
                String out = ffprobe(path, "stream=r_frame_rate").trim();
                int slash = out.indexOf('/');
                double fps = (slash > 0)
                        ? Double.parseDouble(out.substring(0, slash))
                                / Double.parseDouble(out.substring(slash + 1))
                        : Double.parseDouble(out);
                if (fps > 0 && fps <= 240) return fps;
            } catch (Exception ignored) { /* use fallback */ }
            return fallback;
        }

        private static String ffprobe(String path, String entries) throws IOException {
            Process p = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-select_streams", "v:0",
                    "-show_entries", entries,
                    "-of", "csv=p=0",
                    path).start();
            drain(p.getErrorStream(), "ffprobe-stderr");
            byte[] buf = new byte[64];
            int n = 0, r;
            InputStream is = p.getInputStream();
            while (n < buf.length && (r = is.read(buf, n, buf.length - n)) > 0) n += r;
            p.destroy();
            return new String(buf, 0, n).trim();
        }

        private static void drain(InputStream s, String name) {
            Thread t = new Thread(() -> {
                try {
                    byte[] b = new byte[4096];
                    while (s.read(b) != -1) { /* discard */ }
                } catch (IOException ignored) { /* process exited */ }
            }, name);
            t.setDaemon(true);
            t.start();
        }
    }
}
