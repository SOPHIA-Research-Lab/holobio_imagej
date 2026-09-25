import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.Arrays;

/**
 * Snapshot of images and scale for HoloBio QPI / Speckle tools (Python {@code main_DHM_PP} state).
 */
public final class HoloBioToolInputs {

    /** Reconstructed field width (same as amplitude/phase from field). */
    public final int fieldWidth;
    public final int fieldHeight;
    /** From complex field: |U|, same size as phaseRadians; may be null. */
    public final float[] amplitudeFromField;
    /** atan2(im,re) per pixel; null if no reconstructed field. */
    public final float[] phaseRadians;
    /** Reconstructed field Re; null if no field. */
    public final float[] fieldRe;
    /** Reconstructed field Im; null if no field. */
    public final float[] fieldIm;
    /**
     * Amplitude mapped to 0–255 like plugin display ({@code max → 255}); same length as field; may be null.
     * Used for speckle filters (Python {@code original_amplitude_arrays}).
     */
    public final float[] amplitudeDisplay0255;
    /**
     * Phase mapped to 0–255 with 2–98% percentile stretch (matches plugin phase preview); may be null.
     */
    public final float[] phaseDisplay0255;
    /** Flat hologram intensities; null if none. */
    public final float[] hologram;
    public final int holoWidth;
    public final int holoHeight;
    public final double wavelengthUm;
    public final double defaultPixelUm;
    public final double defaultMag;

    public HoloBioToolInputs(int fieldWidth, int fieldHeight,
                             float[] amplitudeFromField, float[] phaseRadians,
                             float[] fieldRe, float[] fieldIm,
                             float[] amplitudeDisplay0255, float[] phaseDisplay0255,
                             float[] hologram, int holoWidth, int holoHeight,
                             double wavelengthUm, double defaultPixelUm, double defaultMag) {
        this.fieldWidth = fieldWidth;
        this.fieldHeight = fieldHeight;
        this.amplitudeFromField = amplitudeFromField;
        this.phaseRadians = phaseRadians;
        this.fieldRe = fieldRe;
        this.fieldIm = fieldIm;
        this.amplitudeDisplay0255 = amplitudeDisplay0255;
        this.phaseDisplay0255 = phaseDisplay0255;
        this.hologram = hologram;
        this.holoWidth = holoWidth;
        this.holoHeight = holoHeight;
        this.wavelengthUm = wavelengthUm;
        this.defaultPixelUm = defaultPixelUm;
        this.defaultMag = defaultMag;
    }

    public boolean hasPhaseField() {
        return phaseRadians != null && fieldWidth > 0 && fieldHeight > 0
            && phaseRadians.length == fieldWidth * fieldHeight;
    }

    public boolean hasAmplitudeField() {
        return amplitudeFromField != null && fieldWidth > 0 && fieldHeight > 0
            && amplitudeFromField.length == fieldWidth * fieldHeight;
    }

    public boolean hasAmplitudeDisplay0255() {
        return amplitudeDisplay0255 != null && amplitudeDisplay0255.length == fieldWidth * fieldHeight;
    }

    public boolean hasPhaseDisplay0255() {
        return phaseDisplay0255 != null && phaseDisplay0255.length == fieldWidth * fieldHeight;
    }

    public boolean hasComplexField() {
        return fieldRe != null && fieldIm != null && fieldWidth > 0 && fieldHeight > 0
            && fieldRe.length == fieldWidth * fieldHeight && fieldIm.length == fieldRe.length;
    }

    public boolean hasHologram() {
        return hologram != null && holoWidth > 0 && holoHeight > 0
            && hologram.length == holoWidth * holoHeight;
    }

    /**
     * Builds a snapshot from plugin state: hologram from {@code state}, field from re/im,
     * wavelength and default scale from {@code params}.
     */
    public static HoloBioToolInputs fromState(HoloBioDhmState state, float[] fieldRe, float[] fieldIm,
                                             int fw, int fh, HoloBioDhmParams params, double lateralMag) {
        double lam = params != null ? params.getWavelengthUm() : 0.532;
        double px = params != null ? params.getPixelPitchXUm() : 2.40;
        double mag = lateralMag > 1e-6 ? lateralMag : 40.0;
        float[] holo = null;
        int hw = 0;
        int hh = 0;
        if (state != null && state.getHologramImage() != null) {
            ImagePlus him = state.getHologramImage();
            ImageProcessor ip = him.getProcessor().convertToFloat();
            hw = ip.getWidth();
            hh = ip.getHeight();
            holo = (float[]) ip.duplicate().getPixels();
        }
        float[] amp = null;
        float[] ph = null;
        float[] amp255 = null;
        float[] ph255 = null;
        if (fieldRe != null && fieldIm != null && fw > 0 && fh > 0 && fieldRe.length == fw * fh) {
            int n = fw * fh;
            amp = new float[n];
            ph = new float[n];
            for (int i = 0; i < n; i++) {
                amp[i] = (float) Math.hypot(fieldRe[i], fieldIm[i]);
                ph[i] = (float) Math.atan2(fieldIm[i], fieldRe[i]);
            }
            amp255 = amplitudeTo0255(amp);
            ph255 = phasePercentileStretch0255(ph);
        }
        float[] fre = fieldRe != null ? fieldRe.clone() : null;
        float[] fim = fieldIm != null ? fieldIm.clone() : null;
        return new HoloBioToolInputs(fw, fh, amp, ph, fre, fim, amp255, ph255, holo, hw, hh, lam, px, mag);
    }

    private static float[] amplitudeTo0255(float[] amp) {
        float max = 0f;
        for (float v : amp) {
            if (v > max) {
                max = v;
            }
        }
        float inv = max > 1e-12f ? 255f / max : 0f;
        float[] o = new float[amp.length];
        for (int i = 0; i < amp.length; i++) {
            o[i] = amp[i] * inv;
        }
        return o;
    }

    /** Same 2–98% stretch as {@link HoloBio_DHM_Plugin} phase preview. */
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
            return o;
        }
        double inv = 255.0 / (hi - lo);
        for (int i = 0; i < len; i++) {
            double t = (ph[i] - lo) * inv;
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 255.0) {
                t = 255.0;
            }
            o[i] = (float) t;
        }
        return o;
    }

    /**
     * Phase 0–255 for speckle measure — matches Python {@code apply_speckle}
     * ({@code (raw_phase + π) / (2π) · 255}), not the percentile-stretched preview.
     */
    public static float[] phasePythonSpeckle0255(float[] phaseRad) {
        if (phaseRad == null || phaseRad.length == 0) {
            return null;
        }
        float[] o = new float[phaseRad.length];
        for (int i = 0; i < phaseRad.length; i++) {
            double t = (phaseRad[i] + Math.PI) / (2.0 * Math.PI);
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
            o[i] = (float) (t * 255.0);
        }
        return o;
    }

    /**
     * 0–255 base for speckle HMF / measure — matches Python {@code original_amplitude_arrays} /
     * {@code original_phase_arrays} at reconstruction.
     */
    public static float[] speckleChannelBase0255(HoloBioToolInputs in, boolean amplitudeChannel) {
        if (in == null) {
            return null;
        }
        if (amplitudeChannel) {
            return in.hasAmplitudeField() ? minMax0255(in.amplitudeFromField) : null;
        }
        return in.hasPhaseField() ? phasePythonSpeckle0255(in.phaseRadians) : null;
    }

    /** Min–max scale to 0–255 (Python speckle display / {@code amplitude_arrays}). */
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
        return o;
    }

    /** Byte image 0–255 for ROI picking; same size as phase grid. */
    public static ij.process.ByteProcessor phaseToBytePickImage(float[] phaseRad, int w, int h) {
        if (phaseRad == null || phaseRad.length != w * h) {
            return null;
        }
        ij.process.ByteProcessor bp = new ij.process.ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double t = (phaseRad[i] + Math.PI) / (2.0 * Math.PI);
                if (t < 0) {
                    t = 0;
                } else if (t > 1) {
                    t = 1;
                }
                int v = (int) Math.round(t * 255.0);
                bp.putPixel(x, y, v);
            }
        }
        return bp;
    }
}
