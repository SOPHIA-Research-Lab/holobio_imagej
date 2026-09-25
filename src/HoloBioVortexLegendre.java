/**
 * Vortex–Legendre compensation matching {@code pyDHM_methods.vortexLegendre}.
 */
final class HoloBioVortexLegendre {

    private static final int MEDIAN_SIZE = 3;
    private static final int CROP_VORTEX = 5;
    private static final int VORTEX_UPSAMPLE = 55;

    private HoloBioVortexLegendre() {
    }

    static HoloBioCompensationAlgorithms.CompensationResult run(
            double[] inp, int width, int height,
            double wavelengthUm, double dxUm, double dyUm,
            int limit, boolean piston, boolean usePca) {
        limit = Math.max(1, limit);

        // Python: M×M square crop; FFT at native size (pad to next POT when needed, never center-crop down).
        SquareCrop sc = cropToSquare(inp, width, height);
        int squareSide = sc.width;
        // Python vortexLegendre FFTs the square crop at native size (960×960 here), not a
        // padded power-of-two. Padding changes every frequency bin and the phase profile.
        int processSide = squareSide;
        double[] holo = sc.data.clone();
        int rows = processSide;
        int cols = processSide;
        int coordOff = 0;

        double wavelength = wavelengthUm * 1e-6;
        double dx = dxUm * 1e-6;
        double dy = dyUm * 1e-6;
        double k = 2.0 * Math.PI / wavelength;

        HoloBioCompensationAlgorithms.FilteredOrder fo =
                HoloBioCompensationAlgorithms.spatialFilteringCf(holo, cols, rows);
        double fxSeed = fo.fx;
        double fySeed = fo.fy;

        double[] fieldFiltered = medianFilteredLogSpectrum(fo.field, rows, cols);

        double[] vortexPos = vortexCompensation(fieldFiltered, rows, cols, fxSeed, fySeed);
        double fxMax = vortexPos[0];
        double fyMax = vortexPos[1];

        HoloBioCompensationAlgorithms.ComplexField holoFilter = fo.field;
        HoloBioCompensationAlgorithms.ComplexField objVl =
                multiplyComplex(applyReferenceWaveVl(holoFilter, rows, cols, wavelength, dx, dy, k, fxMax, fyMax),
                        holoFilter);

        HoloBioCompensationAlgorithms.ComplexField phaseCorrected =
                legendreCompensation(objVl, limit, piston, usePca);

        HoloBioCompensationAlgorithms.ComplexField out = resizeLegendreToFull(
                phaseCorrected, objVl, rows, cols);

        sanitizeComplex(out);
        HoloBioCompensationAlgorithms.ComplexField squareOut =
                upsampleComplexField(out, processSide, squareSide);

        // Place the square back into the full hologram frame (centred), matching RT letterbox.
        // Empty margins use unit amplitude / zero phase → mid-grey on the cyclic phase map
        // (not black zeros from a top-left paste into the pad canvas).
        HoloBioCompensationAlgorithms.ComplexField full =
                embedSquareInFull(squareOut, width, height, sc.x0, sc.y0);

        double fxOut = fxMax + coordOff + sc.x0;
        double fyOut = fyMax + coordOff + sc.y0;
        return new HoloBioCompensationAlgorithms.CompensationResult(full, fxOut, fyOut);
    }

    /**
     * Paste a square field into a full W×H canvas. Prefer the crop origin from
     * {@link #cropToSquare}; fall back to centering when offsets are unknown.
     */
    private static HoloBioCompensationAlgorithms.ComplexField embedSquareInFull(
            HoloBioCompensationAlgorithms.ComplexField square,
            int fullW, int fullH, int x0, int y0) {
        int sw = square.width, sh = square.height;
        if (sw == fullW && sh == fullH) {
            return square;
        }
        HoloBioCompensationAlgorithms.ComplexField full =
                new HoloBioCompensationAlgorithms.ComplexField(fullW, fullH);
        // Empty margins: 0+0i → amp letterbox black; atan2(0,0)=0 → phase mid-grey
        java.util.Arrays.fill(full.re, 0.0);
        java.util.Arrays.fill(full.im, 0.0);
        if (x0 < 0 || y0 < 0) {
            x0 = Math.max(0, (fullW - sw) / 2);
            y0 = Math.max(0, (fullH - sh) / 2);
        }
        for (int y = 0; y < sh; y++) {
            int dy = y0 + y;
            if (dy < 0 || dy >= fullH) continue;
            for (int x = 0; x < sw; x++) {
                int dx = x0 + x;
                if (dx < 0 || dx >= fullW) continue;
                int si = y * sw + x;
                int di = dy * fullW + dx;
                full.re[di] = square.re[si];
                full.im[di] = square.im[si];
            }
        }
        return full;
    }

    /** Mean-pad a side×side hologram into the center of a target×target grid (target ≥ side). */
    private static double[] padCenterToSquare(double[] data, int side, int target) {
        int off = (target - side) / 2;
        double mean = 0.0;
        for (double v : data) {
            mean += v;
        }
        mean /= Math.max(1, data.length);
        double[] out = new double[target * target];
        java.util.Arrays.fill(out, mean);
        for (int y = 0; y < side; y++) {
            System.arraycopy(data, y * side, out, (y + off) * target + off, side);
        }
        return out;
    }

    private static HoloBioCompensationAlgorithms.ComplexField upsampleComplexField(
            HoloBioCompensationAlgorithms.ComplexField field, int fromSide, int toSide) {
        if (fromSide == toSide) {
            return field;
        }
        HoloBioCompensationAlgorithms.ComplexField up =
                new HoloBioCompensationAlgorithms.ComplexField(toSide, toSide);
        resizeBilinear(field.re, field.im, fromSide, fromSide, up.re, up.im, toSide, toSide);
        return up;
    }

    private static final class SquareCrop {
        final double[] data;
        final int width;
        final int height;
        /** Top-left of the square inside the original hologram. */
        final int x0, y0;

        SquareCrop(double[] data, int width, int height, int x0, int y0) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.x0 = x0;
            this.y0 = y0;
        }
    }

    private static SquareCrop cropToSquare(double[] inp, int width, int height) {
        if (width == height) {
            return new SquareCrop(inp.clone(), width, height, 0, 0);
        }
        if (width > height) {
            int diff = width - height;
            int lim = diff / 2;
            int side = height;
            double[] out = new double[side * side];
            for (int y = 0; y < side; y++) {
                System.arraycopy(inp, y * width + lim, out, y * side, side);
            }
            return new SquareCrop(out, side, side, lim, 0);
        }
        if (height > width) {
            int diff = height - width;
            int lim = diff / 2;
            int side = width;
            double[] out = new double[side * side];
            for (int y = 0; y < side; y++) {
                System.arraycopy(inp, (lim + y) * width, out, y * side, side);
            }
            return new SquareCrop(out, side, side, 0, lim);
        }
        return new SquareCrop(inp.clone(), width, height, 0, 0);
    }

    private static double[] medianFilteredLogSpectrum(HoloBioCompensationAlgorithms.ComplexField holoFilter,
                                                      int rows, int cols) {
        HoloBioCompensationAlgorithms.ComplexField f = holoFilter.copy();
        HoloBioRectFft.fftShift2d(f.re, f.im, rows, cols);
        HoloBioCompensationAlgorithms.ComplexField ft =
                HoloBioCompensationAlgorithms.fft2Complex(f);
        HoloBioRectFft.fftShift2d(ft.re, ft.im, rows, cols);

        double[] logPow = new double[rows * cols];
        for (int i = 0; i < logPow.length; i++) {
            double mag2 = ft.re[i] * ft.re[i] + ft.im[i] * ft.im[i];
            logPow[i] = 10.0 * (Math.log(mag2 + 1e-6) / Math.log(10.0));
        }
        return medianFilter3x3Reflect(logPow, cols, rows);
    }

    private static HoloBioCompensationAlgorithms.ComplexField fft2Complex(
            HoloBioCompensationAlgorithms.ComplexField f, int cols, int rows) {
        HoloBioCompensationAlgorithms.ComplexField spec =
                new HoloBioCompensationAlgorithms.ComplexField(cols, rows);
        System.arraycopy(f.re, 0, spec.re, 0, f.re.length);
        System.arraycopy(f.im, 0, spec.im, 0, f.im.length);
        fftRowsColsComplex(spec, cols, rows, false);
        return spec;
    }

    private static void fftRowsColsComplex(HoloBioCompensationAlgorithms.ComplexField f,
                                           int width, int height, boolean inverse) {
        double[] rowRe = new double[width];
        double[] rowIm = new double[width];
        for (int y = 0; y < height; y++) {
            int o = y * width;
            System.arraycopy(f.re, o, rowRe, 0, width);
            System.arraycopy(f.im, o, rowIm, 0, width);
            fft1d(rowRe, rowIm, inverse);
            System.arraycopy(rowRe, 0, f.re, o, width);
            System.arraycopy(rowIm, 0, f.im, o, width);
        }
        double[] colRe = new double[height];
        double[] colIm = new double[height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                colRe[y] = f.re[i];
                colIm[y] = f.im[i];
            }
            fft1d(colRe, colIm, inverse);
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                f.re[i] = colRe[y];
                f.im[i] = colIm[y];
            }
        }
    }

    private static void fft1d(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if (n <= 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT length must be a positive power of two, got " + n);
        }
        bitReverse(re, im);
        for (int len = 2; len <= n; len <<= 1) {
            double ang = (inverse ? 2.0 : -2.0) * Math.PI / len;
            double wlenRe = Math.cos(ang);
            double wlenIm = Math.sin(ang);
            int half = len >>> 1;
            for (int i = 0; i < n; i += len) {
                double wRe = 1.0;
                double wIm = 0.0;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    double tRe = wRe * re[v] - wIm * im[v];
                    double tIm = wRe * im[v] + wIm * re[v];
                    re[v] = re[u] - tRe;
                    im[v] = im[u] - tIm;
                    re[u] += tRe;
                    im[u] += tIm;
                    double nwRe = wRe * wlenRe - wIm * wlenIm;
                    double nwIm = wRe * wlenIm + wIm * wlenRe;
                    wRe = nwRe;
                    wIm = nwIm;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    private static void bitReverse(double[] re, double[] im) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >>> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
    }

    private static double[] vortexCompensation(double[] field, int rows, int cols,
                                                 double fxOverMax, double fyOverMax) {
        // Python: field[int(fy-crop):int(fy+crop), int(fx-crop):int(fx+crop)] → 2*crop × 2*crop (exclusive end)
        int y0 = (int) (fyOverMax - CROP_VORTEX);
        int yEnd = (int) (fyOverMax + CROP_VORTEX);
        int x0 = (int) (fxOverMax - CROP_VORTEX);
        int xEnd = (int) (fxOverMax + CROP_VORTEX);
        int cropRows = yEnd - y0;
        int cropCols = xEnd - x0;
        double[] sdRe = new double[cropRows * cropCols];
        double[] sdIm = new double[cropRows * cropCols];
        for (int y = 0; y < cropRows; y++) {
            int sr = y0 + y;
            for (int x = 0; x < cropCols; x++) {
                int sc = x0 + x;
                int di = y * cropCols + x;
                if (sr >= 0 && sr < rows && sc >= 0 && sc < cols) {
                    int si = sr * cols + sc;
                    sdRe[di] = field[si];
                    sdIm[di] = 0.0;
                }
            }
        }

        hilbertTransform2d(sdRe, sdIm, cropRows, cropCols, true);

        int upRows = vortexUpsampleGridCount(cropRows, VORTEX_UPSAMPLE);
        int upCols = vortexUpsampleGridCount(cropCols, VORTEX_UPSAMPLE);
        double step = 1.0 / VORTEX_UPSAMPLE;
        double[] psi = new double[upRows * upCols];
        for (int yr = 0; yr < upRows; yr++) {
            double yq = yr * step;
            for (int xr = 0; xr < upCols; xr++) {
                double xq = xr * step;
                double re = bilinearInterp(sdRe, sdIm, cropRows, cropCols, yq, xq);
                double im = bilinearInterpImag(sdRe, sdIm, cropRows, cropCols, yq, xq);
                psi[yr * upCols + xr] = Math.atan2(im, re);
            }
        }

        int n1 = upRows;
        int m1 = upCols;
        double[] ml = new double[n1 * m1];
        for (int y2 = 1; y2 < n1 - 1; y2++) {
            for (int x2 = 1; x2 < m1 - 1; x2++) {
                double m1v = psi[(y2 - 1) * m1 + (x2 - 1)];
                double m2v = psi[(y2 - 1) * m1 + x2];
                double m3v = psi[(y2 - 1) * m1 + (x2 + 1)];
                double m4v = psi[y2 * m1 + (x2 + 1)];
                double m5v = psi[(y2 + 1) * m1 + (x2 + 1)];
                double m6v = psi[(y2 + 1) * m1 + x2];
                double m7v = psi[(y2 + 1) * m1 + (x2 - 1)];
                double m8v = psi[y2 * m1 + (x2 - 1)];
                double d1 = wrapToPi(m2v - m1v);
                double d2 = wrapToPi(m3v - m2v);
                double d3 = wrapToPi(m4v - m3v);
                double d4 = wrapToPi(m5v - m4v);
                double d5 = wrapToPi(m6v - m5v);
                double d6 = wrapToPi(m7v - m6v);
                double d7 = wrapToPi(m8v - m7v);
                double d8 = wrapToPi(m1v - m8v);
                ml[y2 * m1 + x2] = (d1 + d2 + d3 + d4 + d5 + d6 + d7 + d8) / (2.0 * Math.PI);
            }
        }
        HoloBioRectFft.fftShift2dReal(ml, n1, m1);
        for (int y = 70; y < n1; y++) {
            for (int x = 70; x < m1; x++) {
                ml[y * m1 + x] = 0.0;
            }
        }
        HoloBioRectFft.ifftShift2dReal(ml, n1, m1);

        int minIdx = 0;
        double minVal = ml[0];
        for (int i = 1; i < ml.length; i++) {
            if (ml[i] < minVal) {
                minVal = ml[i];
                minIdx = i;
            }
        }
        int yV = minIdx / m1;
        int xV = minIdx % m1;
        double posX = (xV / (double) VORTEX_UPSAMPLE) + (fxOverMax - CROP_VORTEX);
        double posY = (yV / (double) VORTEX_UPSAMPLE) + (fyOverMax - CROP_VORTEX);
        return new double[] {posX, posY};
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    /** 2D DFT for non–power-of-two sizes (e.g. 11×11 vortex crop); matches {@code numpy.fft.fft2/ifft2}. */
    private static HoloBioCompensationAlgorithms.ComplexField fft2Naive(
            HoloBioCompensationAlgorithms.ComplexField in, int cols, int rows, boolean inverse) {
        int n = rows * cols;
        HoloBioCompensationAlgorithms.ComplexField out =
                new HoloBioCompensationAlgorithms.ComplexField(cols, rows);
        double sign = inverse ? 1.0 : -1.0;
        double scale = inverse ? 1.0 / n : 1.0;
        for (int ku = 0; ku < rows; ku++) {
            for (int kv = 0; kv < cols; kv++) {
                double sumRe = 0.0;
                double sumIm = 0.0;
                for (int u = 0; u < rows; u++) {
                    for (int v = 0; v < cols; v++) {
                        double phase = sign * 2.0 * Math.PI * (ku * u / (double) rows + kv * v / (double) cols);
                        double cph = Math.cos(phase);
                        double sph = Math.sin(phase);
                        int idx = u * cols + v;
                        double fr = in.re[idx];
                        double fi = in.im[idx];
                        sumRe += fr * cph - fi * sph;
                        sumIm += fr * sph + fi * cph;
                    }
                }
                int oidx = ku * cols + kv;
                out.re[oidx] = sumRe * scale;
                out.im[oidx] = sumIm * scale;
            }
        }
        return out;
    }

    private static void hilbertTransform2d(double[] re, double[] im, int rows, int cols, boolean hilbertOp) {
        HoloBioCompensationAlgorithms.ComplexField c =
                new HoloBioCompensationAlgorithms.ComplexField(cols, rows);
        System.arraycopy(re, 0, c.re, 0, re.length);
        System.arraycopy(im, 0, c.im, 0, im.length);
        HoloBioCompensationAlgorithms.ComplexField spec =
                isPowerOfTwo(rows) && isPowerOfTwo(cols)
                        ? fft2Complex(c, cols, rows)
                        : fft2Naive(c, cols, rows, false);

        double[] hRe = new double[rows * cols];
        double[] hIm = new double[rows * cols];
        int u0 = cols / 2;
        int v0 = rows / 2;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                double u = col - u0;
                double v = row - v0;
                if (row == v0 && col == u0) {
                    hRe[i] = 0.0;
                    hIm[i] = 0.0;
                } else {
                    double den = Math.hypot(u, v) + 1e-6;
                    hRe[i] = u / den;
                    hIm[i] = v / den;
                }
                if (!hilbertOp) {
                    double t = hRe[i];
                    hRe[i] = -hIm[i];
                    hIm[i] = t;
                }
            }
        }
        ifftShiftComplex(hRe, hIm, rows, cols);
        for (int i = 0; i < spec.re.length; i++) {
            double cr = spec.re[i];
            double ci = spec.im[i];
            spec.re[i] = cr * hRe[i] - ci * hIm[i];
            spec.im[i] = cr * hIm[i] + ci * hRe[i];
        }
        HoloBioCompensationAlgorithms.ComplexField out =
                isPowerOfTwo(rows) && isPowerOfTwo(cols)
                        ? HoloBioCompensationAlgorithms.ifft2(spec)
                        : fft2Naive(spec, cols, rows, true);
        for (int i = 0; i < re.length; i++) {
            re[i] = out.re[i];
            im[i] = -out.im[i];
        }
    }

    private static void ifftShiftComplex(double[] re, double[] im, int rows, int cols) {
        HoloBioRectFft.ifftShift2d(re, im, rows, cols);
    }

    private static HoloBioCompensationAlgorithms.ComplexField applyReferenceWaveVl(
            HoloBioCompensationAlgorithms.ComplexField holoFilter,
            int rows, int cols, double wavelength, double dx, double dy, double k,
            double fxMax, double fyMax) {
        double fx0 = cols / 2.0;
        double fy0 = rows / 2.0;
        double argX = clamp((fx0 - fxMax) * wavelength / (cols * dx), -1.0, 1.0);
        double argY = clamp((fy0 - fyMax) * wavelength / (rows * dy), -1.0, 1.0);
        double thetaX = Math.asin(argX);
        double thetaY = Math.asin(argY);
        HoloBioCompensationAlgorithms.ComplexField ref =
                new HoloBioCompensationAlgorithms.ComplexField(cols, rows);
        for (int row = 0; row < rows; row++) {
            double nCoord = row - rows / 2.0;
            for (int col = 0; col < cols; col++) {
                double mCoord = col - cols / 2.0;
                double phase = k * (dx * Math.sin(thetaX) * mCoord + dy * Math.sin(thetaY) * nCoord);
                int i = row * cols + col;
                ref.re[i] = Math.cos(phase);
                ref.im[i] = Math.sin(phase);
            }
        }
        return ref;
    }

    private static HoloBioCompensationAlgorithms.ComplexField legendreCompensation(
            HoloBioCompensationAlgorithms.ComplexField field, int limit, boolean piston, boolean usePca) {
        int w = field.width;
        int h = field.height;
        HoloBioCompensationAlgorithms.ComplexField spec =
                HoloBioCompensationAlgorithms.fft2Complex(field);
        HoloBioRectFft.fftShift2d(spec.re, spec.im, h, w);

        int centerA = Math.round(h / 2f);
        int centerB = Math.round(w / 2f);
        int startA = centerA - limit;
        int endA = centerA + limit;
        int startB = centerB - limit;
        int endB = centerB + limit;
        int cropH = endA - startA;
        int cropW = endB - startB;

        HoloBioCompensationAlgorithms.ComplexField cropped =
                new HoloBioCompensationAlgorithms.ComplexField(cropW, cropH);
        for (int y = 0; y < cropH; y++) {
            int sy = startA + y;
            for (int x = 0; x < cropW; x++) {
                int sx = startB + x;
                int di = y * cropW + x;
                int si = sy * w + sx;
                cropped.re[di] = spec.re[si];
                cropped.im[di] = spec.im[si];
            }
        }
        HoloBioCompensationAlgorithms.ComplexField square =
                HoloBioCompensationAlgorithms.ifft2FromShiftedSpectrum(cropped);

        double[] weight = new double[cropW * cropH];
        for (int i = 0; i < weight.length; i++) {
            weight[i] = Math.hypot(square.re[i], square.im[i]);
        }
        double[] wrapped = new double[cropW * cropH];
        for (int i = 0; i < wrapped.length; i++) {
            wrapped[i] = Math.atan2(square.im[i], square.re[i]);
        }

        HoloBioCompensationAlgorithms.ComplexField dominant = square.copy();
        if (usePca) {
            dominant = pcaRank1(square, cropW, cropH);
        }
        double[] unwrapped = HoloBioWeightedPhaseUnwrap.phaseUnwrap(
                usePca ? angle(dominant) : wrapped, cropW, cropH, weight);

        int gridSize = cropH;
        int nTerms = 10;
        double dA = Math.pow(2.0 / gridSize, 2.0);
        double[][] basis = buildLegendreBasis(gridSize, cropW, cropH, nTerms, dA);
        double[] normConst = new double[nTerms];
        double[] coeff = new double[nTerms];
        for (int t = 0; t < nTerms; t++) {
            double c = 0.0;
            for (int i = 0; i < unwrapped.length; i++) {
                c += basis[t][i] * unwrapped[i];
            }
            coeff[t] = c * dA;
            normConst[t] = dot(basis[t], basis[t]) * dA;
        }

        double[] coeffsUsed = coeff.clone();
        if (piston) {
            coeffsUsed[0] = 0.0;
            applyLegendreCompensate(square, coeffsUsed, normConst, basis, gridSize, cropW, cropH, nTerms);
        } else {
            double bestVar = Double.POSITIVE_INFINITY;
            double bestPiston = 0.0;
            for (int vi = 0; vi <= 12; vi++) {
                double val = -Math.PI + vi * (Math.PI / 6.0);
                coeffsUsed[0] = val;
                HoloBioCompensationAlgorithms.ComplexField trial = square.copy();
                applyLegendreCompensate(trial, coeffsUsed, normConst, basis, gridSize, cropW, cropH, nTerms);
                double v = variance(angle(trial));
                if (v < bestVar) {
                    bestVar = v;
                    bestPiston = val;
                }
            }
            coeffsUsed[0] = bestPiston;
            applyLegendreCompensate(square, coeffsUsed, normConst, basis, gridSize, cropW, cropH, nTerms);
        }
        return square;
    }

    private static void applyLegendreCompensate(HoloBioCompensationAlgorithms.ComplexField square,
                                                double[] coeffsUsed, double[] normConst,
                                                double[][] basis, int gridSize, int w, int h, int nTerms) {
        double[] wavefront = new double[w * h];
        for (int t = 0; t < nTerms; t++) {
            double cNorm = coeffsUsed[t] / Math.sqrt(Math.max(1e-30, normConst[t]));
            for (int i = 0; i < wavefront.length; i++) {
                wavefront[i] += cNorm * basis[t][i];
            }
        }
        for (int i = 0; i < square.re.length; i++) {
            double mag = Math.hypot(square.re[i], square.im[i]);
            double phNum = Math.atan2(square.im[i], square.re[i]);
            double c = Math.cos(phNum - wavefront[i]);
            double s = Math.sin(phNum - wavefront[i]);
            square.re[i] = mag * c;
            square.im[i] = mag * s;
        }
    }

    private static double[][] buildLegendreBasis(int gridSize, int w, int h, int nTerms, double dA) {
        double[] xNorm = new double[w];
        double[] yNorm = new double[h];
        for (int x = 0; x < w; x++) {
            xNorm[x] = -1.0 + (2.0 * x) / gridSize;
        }
        for (int y = 0; y < h; y++) {
            yNorm[y] = -1.0 + (2.0 * y) / gridSize;
        }
        double[][] basis = new double[nTerms][w * h];
        for (int t = 0; t < nTerms; t++) {
            int order = t + 1;
            double energy = 0.0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    double v = legendreTerm(order, xNorm[x], yNorm[y]);
                    basis[t][i] = v;
                    energy += v * v;
                }
            }
            double inv = 1.0 / Math.sqrt(Math.max(1e-30, energy * dA));
            for (int i = 0; i < basis[t].length; i++) {
                basis[t][i] *= inv;
            }
        }
        return basis;
    }

    /**
     * Rank-1 SVD ({@code svds(square, k=1)}). O(rows*cols) per iteration, not O(n²).
     */
    private static HoloBioCompensationAlgorithms.ComplexField pcaRank1(
            HoloBioCompensationAlgorithms.ComplexField square, int w, int h) {
        double[] aRe = square.re;
        double[] aIm = square.im;
        double[] uRe = new double[h];
        double[] uIm = new double[h];
        double[] vRe = new double[w];
        double[] vIm = new double[w];
        double[] tmpRe = new double[h];
        double[] tmpIm = new double[h];
        // Deterministic init (Python svds); avoids run-to-run drift.
        java.util.Arrays.fill(vRe, 1.0);
        java.util.Arrays.fill(vIm, 0.0);
        normalizeComplexVector(vRe, vIm, w);
        for (int iter = 0; iter < 24; iter++) {
            complexMatVec(aRe, aIm, h, w, vRe, vIm, tmpRe, tmpIm);
            normalizeComplexVector(tmpRe, tmpIm, h);
            System.arraycopy(tmpRe, 0, uRe, 0, h);
            System.arraycopy(tmpIm, 0, uIm, 0, h);
            complexMatVecHermitian(aRe, aIm, h, w, uRe, uIm, vRe, vIm);
            normalizeComplexVector(vRe, vIm, w);
        }
        complexMatVec(aRe, aIm, h, w, vRe, vIm, tmpRe, tmpIm);
        double sigma = 0.0;
        for (int row = 0; row < h; row++) {
            sigma += uRe[row] * tmpRe[row] + uIm[row] * tmpIm[row];
        }
        if (sigma < 0.0) {
            sigma = -sigma;
        }
        HoloBioCompensationAlgorithms.ComplexField out =
                new HoloBioCompensationAlgorithms.ComplexField(w, h);
        for (int row = 0; row < h; row++) {
            int base = row * w;
            double ur = uRe[row];
            double ui = uIm[row];
            for (int col = 0; col < w; col++) {
                int idx = base + col;
                double vr = vRe[col];
                double vi = vIm[col];
                out.re[idx] = sigma * (ur * vr + ui * vi);
                out.im[idx] = sigma * (ui * vr - ur * vi);
            }
        }
        return out;
    }

    private static void complexMatVec(double[] aRe, double[] aIm, int h, int w,
                                      double[] vRe, double[] vIm,
                                      double[] uRe, double[] uIm) {
        for (int row = 0; row < h; row++) {
            double sr = 0.0;
            double si = 0.0;
            int base = row * w;
            for (int col = 0; col < w; col++) {
                int idx = base + col;
                double ar = aRe[idx];
                double ai = aIm[idx];
                double vr = vRe[col];
                double vi = vIm[col];
                sr += ar * vr - ai * vi;
                si += ar * vi + ai * vr;
            }
            uRe[row] = sr;
            uIm[row] = si;
        }
    }

    private static void complexMatVecHermitian(double[] aRe, double[] aIm, int h, int w,
                                             double[] uRe, double[] uIm,
                                             double[] vRe, double[] vIm) {
        java.util.Arrays.fill(vRe, 0.0);
        java.util.Arrays.fill(vIm, 0.0);
        for (int row = 0; row < h; row++) {
            double ur = uRe[row];
            double ui = uIm[row];
            int base = row * w;
            for (int col = 0; col < w; col++) {
                int idx = base + col;
                double ar = aRe[idx];
                double ai = aIm[idx];
                vRe[col] += ar * ur + ai * ui;
                vIm[col] += ar * ui - ai * ur;
            }
        }
    }

    private static void normalizeComplexVector(double[] re, double[] im, int len) {
        double norm = 0.0;
        for (int i = 0; i < len; i++) {
            norm += re[i] * re[i] + im[i] * im[i];
        }
        norm = Math.sqrt(Math.max(1e-30, norm));
        for (int i = 0; i < len; i++) {
            re[i] /= norm;
            im[i] /= norm;
        }
    }

    private static HoloBioCompensationAlgorithms.ComplexField resizeLegendreToFull(
            HoloBioCompensationAlgorithms.ComplexField phaseCorrected,
            HoloBioCompensationAlgorithms.ComplexField objVl,
            int rows, int cols) {
        // Always restore object magnitude onto the Legendre phase (even when sizes match).
        HoloBioCompensationAlgorithms.ComplexField phaseFull = phaseCorrected;
        if (phaseCorrected.height != rows || phaseCorrected.width != cols) {
            phaseFull = new HoloBioCompensationAlgorithms.ComplexField(cols, rows);
            resizeBilinear(phaseCorrected.re, phaseCorrected.im,
                    phaseCorrected.width, phaseCorrected.height,
                    phaseFull.re, phaseFull.im, cols, rows);
        }
        HoloBioCompensationAlgorithms.ComplexField out =
                new HoloBioCompensationAlgorithms.ComplexField(cols, rows);
        for (int i = 0; i < out.re.length; i++) {
            double mag = Math.hypot(objVl.re[i], objVl.im[i]);
            double ph = Math.atan2(phaseFull.im[i], phaseFull.re[i]);
            out.re[i] = mag * Math.cos(ph);
            out.im[i] = mag * Math.sin(ph);
        }
        return out;
    }

    private static HoloBioCompensationAlgorithms.ComplexField multiplyComplex(
            HoloBioCompensationAlgorithms.ComplexField a,
            HoloBioCompensationAlgorithms.ComplexField b) {
        HoloBioCompensationAlgorithms.ComplexField out =
                new HoloBioCompensationAlgorithms.ComplexField(a.width, a.height);
        for (int i = 0; i < out.re.length; i++) {
            out.re[i] = a.re[i] * b.re[i] - a.im[i] * b.im[i];
            out.im[i] = a.re[i] * b.im[i] + a.im[i] * b.re[i];
        }
        return out;
    }

    private static double legendreTerm(int order, double x, double y) {
        switch (order) {
            case 1: return 1.0;
            case 2: return x;
            case 3: return y;
            case 4: return (3.0 * x * x - 1.0) / 2.0;
            case 5: return x * y;
            case 6: return (3.0 * y * y - 1.0) / 2.0;
            case 7: return 0.5 * x * (5.0 * x * x - 3.0);
            case 8: return 0.5 * y * (3.0 * x * x - 1.0);
            case 9: return 0.5 * x * (3.0 * y * y - 1.0);
            case 10: return 0.5 * y * (5.0 * y * y - 3.0);
            default: return 0.0;
        }
    }

    private static double[] angle(HoloBioCompensationAlgorithms.ComplexField f) {
        double[] out = new double[f.re.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.atan2(f.im[i], f.re[i]);
        }
        return out;
    }

    private static double variance(double[] a) {
        double mean = 0.0;
        for (double v : a) {
            mean += v;
        }
        mean /= a.length;
        double s = 0.0;
        for (double v : a) {
            double d = v - mean;
            s += d * d;
        }
        return s / a.length;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static void sanitizeComplex(HoloBioCompensationAlgorithms.ComplexField f) {
        for (int i = 0; i < f.re.length; i++) {
            if (!Double.isFinite(f.re[i])) {
                f.re[i] = 0.0;
            }
            if (!Double.isFinite(f.im[i])) {
                f.im[i] = 0.0;
            }
        }
    }

    /** {@code (angle + pi) % (2*pi) - pi} with positive modulo (Java {@code %} differs from NumPy for negatives). */
    private static double wrapToPi(double a) {
        double t = (a + Math.PI) % (2.0 * Math.PI);
        if (t < 0.0) {
            t += 2.0 * Math.PI;
        }
        return t - Math.PI;
    }

    /** {@code len(np.arange(0, sz - 1/factor + 1e-6, 1/factor))}. */
    private static int vortexUpsampleGridCount(int sz, int factor) {
        double step = 1.0 / factor;
        double stop = sz - 1.0 / factor + 1e-6;
        return (int) Math.floor(stop / step + 1e-6) + 1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int nextPowerOfTwo(int value) {
        int n = 1;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }

    private static double[] medianFilter3x3Reflect(double[] src, int w, int h) {
        double[] out = new double[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = median9(
                        src[reflectIdx(y - 1, h) * w + reflectIdx(x - 1, w)],
                        src[reflectIdx(y - 1, h) * w + reflectIdx(x, w)],
                        src[reflectIdx(y - 1, h) * w + reflectIdx(x + 1, w)],
                        src[reflectIdx(y, h) * w + reflectIdx(x - 1, w)],
                        src[reflectIdx(y, h) * w + reflectIdx(x, w)],
                        src[reflectIdx(y, h) * w + reflectIdx(x + 1, w)],
                        src[reflectIdx(y + 1, h) * w + reflectIdx(x - 1, w)],
                        src[reflectIdx(y + 1, h) * w + reflectIdx(x, w)],
                        src[reflectIdx(y + 1, h) * w + reflectIdx(x + 1, w)]);
            }
        }
        return out;
    }

    /** Median of nine values without full sort. */
    private static double median9(double a0, double a1, double a2, double a3, double a4,
                                  double a5, double a6, double a7, double a8) {
        double[] v = {a0, a1, a2, a3, a4, a5, a6, a7, a8};
        for (int i = 1; i < 9; i++) {
            double key = v[i];
            int j = i - 1;
            while (j >= 0 && v[j] > key) {
                v[j + 1] = v[j];
                j--;
            }
            v[j + 1] = key;
        }
        return v[4];
    }

    private static int reflectIdx(int idx, int n) {
        if (idx < 0) {
            return -idx;
        }
        if (idx >= n) {
            return 2 * n - idx - 2;
        }
        return idx;
    }

    private static double bilinearInterp(double[] re, double[] im, int rows, int cols, double y, double x) {
        return bilinear(re, rows, cols, y, x);
    }

    private static double bilinearInterpImag(double[] re, double[] im, int rows, int cols, double y, double x) {
        return bilinear(im, rows, cols, y, x);
    }

    private static double bilinear(double[] grid, int rows, int cols, double y, double x) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(cols - 1, x0 + 1);
        int y1 = Math.min(rows - 1, y0 + 1);
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        double tx = x - x0;
        double ty = y - y0;
        double v00 = grid[y0 * cols + x0];
        double v01 = grid[y0 * cols + x1];
        double v10 = grid[y1 * cols + x0];
        double v11 = grid[y1 * cols + x1];
        return (1 - ty) * ((1 - tx) * v00 + tx * v01) + ty * ((1 - tx) * v10 + tx * v11);
    }

    private static void resizeBilinear(double[] srcRe, double[] srcIm, int sw, int sh,
                                       double[] dstRe, double[] dstIm, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            double sy = (sh - 1) * y / (double) Math.max(1, dh - 1);
            for (int x = 0; x < dw; x++) {
                double sx = (sw - 1) * x / (double) Math.max(1, dw - 1);
                int i = y * dw + x;
                dstRe[i] = bilinear(srcRe, sh, sw, sy, sx);
                dstIm[i] = bilinear(srcIm, sh, sw, sy, sx);
            }
        }
    }
}

