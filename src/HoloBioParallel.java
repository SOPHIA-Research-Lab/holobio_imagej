/**
 * Splits an independent-line loop across cores.
 *
 * <p>Reconstruction spends nearly all of its time in passes where each row, column or
 * scanline is computed without reference to any other: the FFT line transforms, the
 * fftshift permutations, and the per-pixel propagation kernel. Those parallelise with no
 * locking, and because every line runs exactly the same code over exactly the same values
 * as it did serially, the results stay bit-identical — worth preserving, since the parity
 * harnesses compare Java output against the Python reference to the last bit.
 *
 * <p>Threads are daemons so a running pass can never keep Fiji open.
 */
public final class HoloBioParallel {

    /** Below this many lines the split costs more than it saves. */
    private static final int MIN_LINES = 64;

    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());

    private static final java.util.concurrent.ExecutorService POOL =
        java.util.concurrent.Executors.newFixedThreadPool(THREADS,
            new java.util.concurrent.ThreadFactory() {
                private int n;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "HoloBio-worker-" + (n++));
                    t.setDaemon(true);
                    return t;
                }
            });

    private HoloBioParallel() {
    }

    /** A half-open slice {@code [from, to)} of the lines in one pass. */
    public interface LineRange {
        void run(int from, int to);
    }

    /**
     * Run {@code body} over {@code count} lines, split across the pool. Falls back to
     * running inline for small counts, and when already on a pool thread — nesting would
     * deadlock a fixed pool.
     */
    public static void forEachLine(int count, LineRange body) {
        if (count <= 0) {
            return;
        }
        int threads = Math.min(THREADS, count);
        if (threads <= 1 || count < MIN_LINES
                || Thread.currentThread().getName().startsWith("HoloBio-worker-")) {
            body.run(0, count);
            return;
        }
        int chunk = (count + threads - 1) / threads;
        java.util.List<java.util.concurrent.Future<?>> pending =
            new java.util.ArrayList<java.util.concurrent.Future<?>>(threads);
        for (int t = 0; t < threads; t++) {
            final int from = t * chunk;
            final int to = Math.min(count, from + chunk);
            if (from >= to) {
                break;
            }
            pending.add(POOL.submit(() -> body.run(from, to)));
        }
        for (java.util.concurrent.Future<?> fut : pending) {
            try {
                fut.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Parallel pass interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new IllegalStateException("Parallel pass failed", cause);
            }
        }
    }
}
