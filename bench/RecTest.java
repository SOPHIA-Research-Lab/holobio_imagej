import java.io.File;

/** Round-trips a short synthetic recording through the recorder's MP4 and TIFF writers. */
public class RecTest {

    public static void main(String[] args) throws Exception {
        int w = 512, h = 480, n = 25;
        HoloBioRtRecorder rec = new HoloBioRtRecorder();
        rec.start(HoloBioRtRecorder.Target.PHASE);

        float[] frame = new float[w * h];
        for (int f = 0; f < n; f++) {
            for (int r = 0; r < h; r++)
                for (int c = 0; c < w; c++)
                    frame[r * w + c] = (float) (128 + 120 * Math.sin(
                            2 * Math.PI * (c + 4.0 * f) / 64.0));
            rec.add(HoloBioRtRecorder.Target.PHASE, frame, w, h);
            Thread.sleep(10);
        }
        rec.stop();

        System.out.printf("buffered %d frames, measured %.1f fps%n",
                rec.frameCount(), rec.measuredFps());

        for (String ext : new String[] { "mp4", "avi", "tif" }) {
            File out = new File("bench/rectest." + ext);
            if (out.exists() && !out.delete()) System.out.println("  (could not clear " + out + ")");
            try {
                String what = rec.save(out, 20.0);
                System.out.printf("  %-4s %-28s %s%n", ext, what,
                        out.exists() && out.length() > 0
                                ? "OK " + out.length() + " bytes" : "EMPTY <-- bad");
            } catch (Exception e) {
                System.out.printf("  %-4s FAILED: %s%n", ext, e.getMessage());
            }
        }
    }
}
