import java.util.Arrays;

/**
 * Weighted 2D phase unwrap ({@code holobio.unwrapping.phase_unwrap}).
 */
final class HoloBioWeightedPhaseUnwrap {

    private static final int KMAX = 100;
    private static final double EPS = 1e-9;

    private HoloBioWeightedPhaseUnwrap() {
    }

    static double[] phaseUnwrap(double[] wrapped, int width, int height, double[] weight) {
        int n = width * height;
        double[] ww = weight;
        if (ww == null) {
            ww = new double[n];
            ArraysFill(ww, 1.0);
        }
        Workspace ws = new Workspace(width, height);
        double[] dx = ws.bufA;
        double[] dy = ws.bufB;
        for (int y = 0; y < height; y++) {
            for (int x = 1; x < width; x++) {
                int i = y * width + x;
                int p = y * width + (x - 1);
                dx[i] = wrapToPi(wrapped[i] - wrapped[p]);
            }
        }
        for (int y = 1; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int p = (y - 1) * width + x;
                dy[i] = wrapToPi(wrapped[i] - wrapped[p]);
            }
        }
        double[] wwx = ws.bufC;
        double[] wwy = ws.bufD;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width - 1; x++) {
                int i = y * width + x;
                int i1 = y * width + (x + 1);
                wwx[i] = Math.min(ww[i], ww[i1]);
            }
        }
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int i1 = (y + 1) * width + x;
                wwy[i] = Math.min(ww[i], ww[i1]);
            }
        }
        double[] wwDx = dx;
        double[] wwDy = dy;
        for (int i = 0; i < n; i++) {
            wwDx[i] = wwx[i] * dx[i];
            wwDy[i] = wwy[i] * dy[i];
        }
        double[] rk = ws.bufE;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                double left = x > 0 ? wwDx[y * width + (x - 1)] : 0.0;
                double right = x < width - 1 ? -wwDx[i] : 0.0;
                double up = y > 0 ? wwDy[(y - 1) * width + x] : 0.0;
                double down = y < height - 1 ? -wwDy[i] : 0.0;
                rk[i] = left + right + up + down;
            }
        }
        double normR0 = l2Norm(rk);
        double[] scale = precompPoissonScaling(width, height);
        double[] phi = new double[n];
        double[] pk = ws.bufF;
        double[] zk = ws.bufG;
        double[] qpk = ws.bufH;
        double rkzkPrev = 0.0;
        for (int k = 0; k < KMAX; k++) {
            if (l2Norm(rk) < 1e-15) {
                break;
            }
            solvePoissonPrecomped(zk, rk, scale, width, height, ws.dctWork);
            double rkzksum = dot(rk, zk);
            if (k == 0) {
                System.arraycopy(zk, 0, pk, 0, n);
            } else {
                double beta = rkzksum / Math.max(1e-30, rkzkPrev);
                for (int i = 0; i < n; i++) {
                    pk[i] = zk[i] + beta * pk[i];
                }
            }
            rkzkPrev = rkzksum;
            applyQ(qpk, pk, wwx, wwy, width, height, ws);
            double denom = dot(pk, qpk);
            double alpha = denom > 1e-30 ? rkzksum / denom : 0.0;
            for (int i = 0; i < n; i++) {
                phi[i] += alpha * pk[i];
                rk[i] -= alpha * qpk[i];
            }
            if (k + 1 >= KMAX || l2Norm(rk) < EPS * normR0) {
                break;
            }
        }
        return phi;
    }

    private static final class Workspace {
        final double[] bufA;
        final double[] bufB;
        final double[] bufC;
        final double[] bufD;
        final double[] bufE;
        final double[] bufF;
        final double[] bufG;
        final double[] bufH;
        final double[] dctWork;

        Workspace(int width, int height) {
            int n = width * height;
            bufA = new double[n];
            bufB = new double[n];
            bufC = new double[n];
            bufD = new double[n];
            bufE = new double[n];
            bufF = new double[n];
            bufG = new double[n];
            bufH = new double[n];
            dctWork = new double[n];
        }
    }

    private static void applyQ(double[] out, double[] p, double[] wwx, double[] wwy,
                               int width, int height, Workspace ws) {
        ArraysFill(out, 0.0);
        double[] dx = ws.bufA;
        double[] dy = ws.bufB;
        for (int y = 0; y < height; y++) {
            for (int x = 1; x < width; x++) {
                dx[y * width + x] = p[y * width + x] - p[y * width + (x - 1)];
            }
        }
        for (int y = 1; y < height; y++) {
            for (int x = 0; x < width; x++) {
                dy[y * width + x] = p[y * width + x] - p[(y - 1) * width + x];
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width - 1; x++) {
                int i = y * width + x;
                dx[i + 1] = wwx[i] * dx[i + 1];
            }
        }
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                dy[i + width] = wwy[i] * dy[i + width];
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                double left = x > 0 ? dx[i] : 0.0;
                double right = x < width - 1 ? -dx[i + 1] : 0.0;
                double up = y > 0 ? dy[i] : 0.0;
                double down = y < height - 1 ? -dy[i + width] : 0.0;
                out[i] = left + right + up + down;
            }
        }
    }

    private static double[] precompPoissonScaling(int width, int height) {
        double[] scale = new double[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int i = row * width + col;
                if (row == 0 && col == 0) {
                    scale[i] = 1.0;
                } else {
                    scale[i] = 2.0 * (Math.cos(Math.PI * col / width) + Math.cos(Math.PI * row / height) - 2.0);
                }
            }
        }
        return scale;
    }

    private static void solvePoissonPrecomped(double[] phi, double[] rho, double[] scale,
                                            int width, int height, double[] work) {
        System.arraycopy(rho, 0, work, 0, rho.length);
        HoloBioDct2.dct2InPlace(work, width, height);
        for (int i = 0; i < work.length; i++) {
            work[i] /= scale[i];
        }
        HoloBioDct2.idct2InPlace(work, width, height);
        System.arraycopy(work, 0, phi, 0, phi.length);
    }

    private static double wrapToPi(double a) {
        double t = (a + Math.PI) % (2.0 * Math.PI);
        if (t < 0.0) {
            t += 2.0 * Math.PI;
        }
        return t - Math.PI;
    }

    private static double l2Norm(double[] a) {
        double s = 0.0;
        for (double v : a) {
            s += v * v;
        }
        return Math.sqrt(s);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static void ArraysFill(double[] a, double v) {
        for (int i = 0; i < a.length; i++) {
            a[i] = v;
        }
    }
}
