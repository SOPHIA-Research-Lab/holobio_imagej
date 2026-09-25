import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Shared profile-curve CSV for plot overlay with the Python benchmark script.
 *
 * <pre>
 * # source=fiji_rt
 * region,index,position_um,phase_rad
 * L1,0,0.000000,3.141593
 * </pre>
 *
 * {@code phase_rad} is wrapped phase shifted by +π into roughly [0, 2π], matching the
 * live plot and offline QPI.
 */
final class HoloBioRtProfileCurveIo {

    private HoloBioRtProfileCurveIo() {}

    static void write(File dest,
                      List<HoloBioRtProfileWindow.Curve> curves,
                      List<String> labels,
                      String sourceTag) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(dest), StandardCharsets.UTF_8));
        try {
            w.write('\uFEFF'); // Excel UTF-8
            w.write("region,index,position_um,phase_rad\n");
            for (int li = 0; li < curves.size(); li++) {
                HoloBioRtProfileWindow.Curve c = curves.get(li);
                String label = (labels != null && li < labels.size() && labels.get(li) != null)
                        ? labels.get(li) : ("L" + (li + 1));
                double[] v = c.values;
                for (int k = 0; k < v.length; k++) {
                    double ph = v[k];
                    if (Double.isNaN(ph)) continue;
                    double xUm = c.stepUm * k;
                    w.write(label); w.write(',');
                    w.write(Integer.toString(k)); w.write(',');
                    w.write(String.format(Locale.US, "%.6f", xUm)); w.write(',');
                    w.write(String.format(Locale.US, "%.6f", ph)); w.write('\n');
                }
            }
        } finally {
            w.close();
        }
    }
}
