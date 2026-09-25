import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.plugin.filter.GaussianBlur;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * QPI and speckle numerics aligned with HoloBio Python {@code tools_GUI.apply_QPI},
 * {@code apply_speckle}, and {@code apply_speckle_filter} (subset).
 */
public final class HoloBioQpiSpeckleMath {

    private HoloBioQpiSpeckleMath() {}

    /** Bilinear sample; out-of-bounds returns NaN. */
    public static double sampleBilinear(float[] img, int w, int h, double x, double y) {
        if (img == null || w <= 0 || h <= 0) {
            return Double.NaN;
        }
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) {
            return Double.NaN;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        double tx = x - x0;
        double ty = y - y0;
        double v00 = img[y0 * w + x0];
        double v01 = img[y0 * w + x1];
        double v10 = img[y1 * w + x0];
        double v11 = img[y1 * w + x1];
        double a = v00 * (1 - tx) + v01 * tx;
        double b = v10 * (1 - tx) + v11 * tx;
        return a * (1 - ty) + b * ty;
    }

    /** Phase along a straight segment (Python line profile). */
    public static double[] profileAlongLine(float[] img, int w, int h,
                                          double x1, double y1, double x2, double y2) {
        int L = (int) Math.hypot(x2 - x1, y2 - y1);
        L = Math.max(L, 2);
        double[] prof = new double[L];
        for (int k = 0; k < L; k++) {
            double t = L <= 1 ? 0 : k / (double) (L - 1);
            double x = x1 + (x2 - x1) * t;
            double y = y1 + (y2 - y1) * t;
            prof[k] = sampleBilinear(img, w, h, x, y);
        }
        return prof;
    }

    /**
     * All pixels inside an axis-aligned rectangle (x,y,width,height), row-major.
     * Used for live / QPI region stats where a box replaces a line.
     */
    public static double[] profileAlongRect(float[] img, int w, int h,
                                            double x, double y, double rw, double rh) {
        int x0 = (int) Math.max(0, Math.floor(x));
        int y0 = (int) Math.max(0, Math.floor(y));
        int x1 = (int) Math.min(w, Math.ceil(x + rw));
        int y1 = (int) Math.min(h, Math.ceil(y + rh));
        if (x1 <= x0 || y1 <= y0) return new double[0];
        double[] prof = new double[(x1 - x0) * (y1 - y0)];
        int k = 0;
        for (int yy = y0; yy < y1; yy++) {
            int row = yy * w;
            for (int xx = x0; xx < x1; xx++) prof[k++] = img[row + xx];
        }
        return prof;
    }

    /** Phase samples along circle (Python circular ROI). */
    public static double[] profileAlongCircle(float[] img, int w, int h,
                                              double cx, double cy, double r) {
        int n = Math.max(64, (int) Math.ceil(2 * Math.PI * Math.max(1, r)));
        double[] prof = new double[n];
        for (int k = 0; k < n; k++) {
            double ang = 2 * Math.PI * k / n;
            double x = cx + r * Math.cos(ang);
            double y = cy + r * Math.sin(ang);
            prof[k] = sampleBilinear(img, w, h, x, y);
        }
        return prof;
    }

    /**
     * Python {@code phase_stats}: trimmed low/high means and delta (high tail mean minus low tail mean).
     */
    public static double[] phaseStats(double[] profile) {
        double[] p = Arrays.stream(profile).filter(v -> !Double.isNaN(v)).toArray();
        if (p.length < 4) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN};
        }
        Arrays.sort(p);
        int n5 = Math.max(1, (int) Math.floor(0.05 * p.length));
        double low = 0;
        double high = 0;
        for (int i = 0; i < n5; i++) {
            low += p[i];
            high += p[p.length - 1 - i];
        }
        low /= n5;
        high /= n5;
        return new double[] {low, high, high - low};
    }

    /**
     * Subdivide one user ROI into {@code rows}×{@code cols} cells and compute speckle contrast on each
     * (Python {@code subdivide_and_label}).
     */
    public static double[] subdivideZoneContrasts(float[] data, int w, int h,
                                                  int x1, int y1, int x2, int y2, int rows, int cols) {
        x1 = clamp(x1, 0, w);
        x2 = clamp(x2, 0, w);
        y1 = clamp(y1, 0, h);
        y2 = clamp(y2, 0, h);
        if (x2 <= x1 || y2 <= y1 || rows < 1 || cols < 1) {
            return new double[0];
        }
        int wSub = (x2 - x1) / cols;
        int hSub = (y2 - y1) / rows;
        if (wSub < 1 || hSub < 1) {
            return new double[0];
        }
        double[] out = new double[rows * cols];
        int k = 0;
        for (int rr = 0; rr < rows; rr++) {
            for (int cc = 0; cc < cols; cc++) {
                int xx1 = x1 + cc * wSub;
                int yy1 = y1 + rr * hSub;
                int xx2 = Math.min(xx1 + wSub, w);
                int yy2 = Math.min(yy1 + hSub, h);
                out[k++] = speckleContrast(data, w, xx1, yy1, xx2, yy2);
            }
        }
        return out;
    }

    /**
     * Speckle contrast std(|I|²)/mean(|I|²) on a rectangular region — same as Python
     * {@code calc_speckle_contrast} ({@code np.std(np.abs(region)**2) / (np.mean(...) + 1e-9)}, ddof=0).
     * For real-valued {@code data}, |I|² = I².
     */
    public static double speckleContrast(float[] data, int w, int x1, int y1, int x2, int y2) {
        x1 = clamp(x1, 0, w - 1);
        x2 = clamp(x2, 0, w);
        y1 = clamp(y1, 0, data.length / w - 1);
        y2 = clamp(y2, 0, data.length / w);
        if (x2 <= x1 || y2 <= y1) {
            return 0;
        }
        double sum = 0;
        double sumSq = 0;
        long cnt = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                double v = data[y * w + x];
                double i2 = v * v;
                sum += i2;
                sumSq += i2 * i2;
                cnt++;
            }
        }
        if (cnt == 0) {
            return 0;
        }
        double mean = sum / cnt;
        double var = sumSq / cnt - mean * mean;
        if (var < 0) {
            var = 0;
        }
        double std = Math.sqrt(var);
        return std / (mean + 1e-9);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Hybrid median–mean (Python {@code HybridMedianMean}): each iteration median-filters the
     * <em>original</em> {@code sample} with kernel {@code 3 + 2*i} and averages with the running mean.
     * Returns one snapshot per iteration plus the initial copy (length {@code maxIterations + 1}).
     */
    public static List<float[]> hybridMedianMeanIterations(float[] sample, int w, int h, int maxIterations) {
        int n = w * h;
        double[] meanImage = new double[n];
        for (int i = 0; i < n; i++) {
            meanImage[i] = sample[i];
        }
        List<float[]> out = new ArrayList<>(maxIterations + 1);
        out.add(copyFloat0255(sample));
        for (int it = 0; it < maxIterations; it++) {
            int k = 3 + 2 * it;
            double[] med = medianFilterSquare(sample, w, h, k, 0f);
            for (int i = 0; i < n; i++) {
                meanImage[i] = 0.5 * (meanImage[i] + med[i]);
            }
            float[] snap = new float[n];
            for (int i = 0; i < n; i++) {
                snap[i] = (float) meanImage[i];
            }
            out.add(clip0255(snap));
        }
        return out;
    }

    /** Final HMF image (last iteration). */
    public static float[] hybridMedianMean(float[] sample, int w, int h, int maxIterations) {
        List<float[]> iters = hybridMedianMeanIterations(sample, w, h, maxIterations);
        return iters.get(iters.size() - 1);
    }

    private static float[] copyFloat0255(float[] v) {
        return clip0255(v.clone());
    }

    /**
     * SPP on complex field (Python {@code spp_filter}). Returns one complex snapshot per iteration.
     */
    public static SppIterations sppFilter(float[] fieldRe, float[] fieldIm, int w, int h, int maxIterations) {
        int n = w * h;
        double[] realPart = new double[n];
        double[] imagPart = new double[n];
        double realMin = Double.POSITIVE_INFINITY;
        double realMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            realPart[i] = fieldRe[i];
            imagPart[i] = fieldIm[i];
            if (realPart[i] < realMin) {
                realMin = realPart[i];
            }
            if (realPart[i] > realMax) {
                realMax = realPart[i];
            }
        }
        double noiseMean = (realMax + realMin) / 2.0;
        double noiseStd = (realMax - realMin) / 6.0;
        Random rng = new Random();

        double[] accRe = new double[n];
        double[] accIm = new double[n];
        List<float[]> reList = new ArrayList<>(maxIterations);
        List<float[]> imList = new ArrayList<>(maxIterations);

        for (int it = 0; it < maxIterations; it++) {
            for (int i = 0; i < n; i++) {
                double noise = rng.nextGaussian() * noiseStd + noiseMean;
                accRe[i] += realPart[i] + noise;
                accIm[i] += imagPart[i];
            }
            int div = it + 1;
            float[] reSnap = new float[n];
            float[] imSnap = new float[n];
            for (int i = 0; i < n; i++) {
                reSnap[i] = (float) (accRe[i] / div);
                imSnap[i] = (float) (accIm[i] / div);
            }
            reList.add(reSnap);
            imList.add(imSnap);
        }
        return new SppIterations(reList, imList);
    }

    /** HMF iteration contrast in ROI: std/mean (Python compare_speckle_plot_var for hmf). */
    public static double hmfRegionContrast(float[] img, int w, int x1, int y1, int x2, int y2) {
        double sum = 0;
        long cnt = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                double v = img[y * w + x];
                sum += v;
                cnt++;
            }
        }
        if (cnt == 0) {
            return 0;
        }
        double mean = sum / cnt;
        double var = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                double d = img[y * w + x] - mean;
                var += d * d;
            }
        }
        double std = Math.sqrt(var / cnt);
        return std / (mean + 1e-9);
    }

    /** SPP iteration contrast: std(I²)/mean(I²) on complex region (Python calc_speckle_contrast). */
    public static double sppRegionContrast(float[] re, float[] im, int w, int x1, int y1, int x2, int y2) {
        double sum = 0;
        double sumSq = 0;
        long cnt = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int i = y * w + x;
                double amp2 = re[i] * re[i] + im[i] * im[i];
                sum += amp2;
                sumSq += amp2 * amp2;
                cnt++;
            }
        }
        if (cnt == 0) {
            return 0;
        }
        double mean = sum / cnt;
        double var = sumSq / cnt - mean * mean;
        if (var < 0) {
            var = 0;
        }
        return Math.sqrt(var) / (mean + 1e-9);
    }

    /** Max-only scale (plugin preview); speckle SPP uses {@link #complexToAmplitudeMinMax0255}. */
    public static float[] complexToAmplitude0255(float[] re, float[] im) {
        int n = re.length;
        float max = 0f;
        float[] amp = new float[n];
        for (int i = 0; i < n; i++) {
            amp[i] = (float) Math.hypot(re[i], im[i]);
            if (amp[i] > max) {
                max = amp[i];
            }
        }
        float inv = max > 1e-12f ? 255f / max : 0f;
        for (int i = 0; i < n; i++) {
            amp[i] = amp[i] * inv;
        }
        return clip0255(amp);
    }

    /**
     * Python SPP post-filter: {@code 255*(amp - amp.min()) / (amp.max() - amp.min())}.
     */
    public static float[] complexToAmplitudeMinMax0255(float[] re, float[] im) {
        int n = re.length;
        float[] amp = new float[n];
        for (int i = 0; i < n; i++) {
            amp[i] = (float) Math.hypot(re[i], im[i]);
        }
        return minMax0255(amp);
    }

    /**
     * Python SPP post-filter: {@code 255*(phase - phase.min()) / (phase.max() - phase.min())}.
     */
    public static float[] complexToPhaseMinMax0255(float[] re, float[] im) {
        int n = re.length;
        float[] ph = new float[n];
        for (int i = 0; i < n; i++) {
            ph[i] = (float) Math.atan2(im[i], re[i]);
        }
        return minMax0255(ph);
    }

    /** Percentile stretch (plugin phase preview); not used for speckle measure. */
    public static float[] complexToPhase0255(float[] re, float[] im) {
        int n = re.length;
        float[] ph = new float[n];
        for (int i = 0; i < n; i++) {
            ph[i] = (float) Math.atan2(im[i], re[i]);
        }
        return phasePercentileStretch0255(ph);
    }

    /** Min–max to [0,255] (Python {@code amp_norm} / SPP display). */
    public static float[] minMax0255(float[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        double mn = data[0];
        double mx = data[0];
        for (float v : data) {
            if (v < mn) {
                mn = v;
            }
            if (v > mx) {
                mx = v;
            }
        }
        float[] o = new float[data.length];
        if (Math.abs(mx - mn) < 1e-12) {
            return o;
        }
        double inv = 255.0 / (mx - mn);
        for (int i = 0; i < data.length; i++) {
            o[i] = (float) ((data[i] - mn) * inv);
        }
        return clip0255(o);
    }

    private static float[] phasePercentileStretch0255(float[] ph) {
        int len = ph.length;
        double[] sorted = new double[len];
        for (int i = 0; i < len; i++) {
            sorted[i] = ph[i];
        }
        Arrays.sort(sorted);
        int iLo = Math.max(0, (int) Math.floor((len - 1) * 0.02));
        int iHi = Math.min(len - 1, (int) Math.ceil((len - 1) * 0.98));
        double lo = sorted[iLo];
        double hi = sorted[iHi];
        float[] o = new float[len];
        if (hi <= lo + 1e-9) {
            for (int i = 0; i < len; i++) {
                o[i] = (float) (255.0 * ((ph[i] + Math.PI) / (2.0 * Math.PI)));
            }
            return clip0255(o);
        }
        double inv = 255.0 / (hi - lo);
        for (int i = 0; i < len; i++) {
            double t = (ph[i] - lo) * inv;
            if (t < 0) {
                t = 0;
            } else if (t > 255) {
                t = 255;
            }
            o[i] = (float) t;
        }
        return clip0255(o);
    }

    public static final class SppIterations {
        public final List<float[]> rePerIteration;
        public final List<float[]> imPerIteration;

        SppIterations(List<float[]> rePerIteration, List<float[]> imPerIteration) {
            this.rePerIteration = rePerIteration;
            this.imPerIteration = imPerIteration;
        }
    }

    /** Median filter with odd kernel; {@code cval} pad outside (Python constant 0). */
    public static double[] medianFilterSquare(float[] sample, int w, int h, int k, float cval) {
        if (k % 2 == 0) {
            k++;
        }
        int r = k / 2;
        int n = w * h;
        double[] out = new double[n];
        double[] buf = new double[k * k];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int m = 0;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        int yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) {
                            buf[m++] = cval;
                        } else {
                            buf[m++] = sample[yy * w + xx];
                        }
                    }
                }
                Arrays.sort(buf, 0, m);
                out[y * w + x] = buf[m / 2];
            }
        }
        return out;
    }

    /** Box mean filter, kernel side {@code k} (odd; even {@code k} is bumped to {@code k+1}). */
    public static float[] meanFilterSquare(float[] sample, int w, int h, int k) {
        if (k < 1) {
            k = 1;
        }
        if (k % 2 == 0) {
            k++;
        }
        int r = k / 2;
        int n = w * h;
        float[] out = new float[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double s = 0;
                int c = 0;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        int yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                            s += sample[yy * w + xx];
                            c++;
                        }
                    }
                }
                out[y * w + x] = (float) (s / Math.max(1, c));
            }
        }
        return out;
    }

    /** Odd median kernel on float array (in-place copy). */
    public static float[] medianFilterFloat(float[] sample, int w, int h, int k) {
        double[] d = medianFilterSquare(sample, w, h, k, 0f);
        float[] out = new float[d.length];
        for (int i = 0; i < d.length; i++) {
            out[i] = (float) d[i];
        }
        return out;
    }

    /** Gaussian blur (sigma in pixels), matches scipy ndimage on separable Gaussian approximately. */
    public static float[] gaussianFilterFloat(float[] sample, int w, int h, double sigma) {
        FloatProcessor fp = new FloatProcessor(w, h, sample.clone(), null);
        ImageProcessor ip = fp;
        new GaussianBlur().blurGaussian(ip, sigma);
        return (float[]) fp.getPixels();
    }

    /** Clip to [0,255] float array. */
    public static float[] clip0255(float[] v) {
        float[] o = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            float t = v[i];
            if (t < 0) {
                t = 0;
            } else if (t > 255) {
                t = 255;
            }
            o[i] = t;
        }
        return o;
    }

    /** Convert float[] 0–255 to FloatProcessor. */
    public static FloatProcessor toFloatProcessor0255(float[] px, int w, int h) {
        return new FloatProcessor(w, h, clip0255(px.clone()), null);
    }
}
