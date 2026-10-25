package io.lakio.farfield;

import io.lakio.farfield.lod.RenderSettings;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Logs every 60 s the CPU time of Farfield's own threads ({@code farfield-*}),
 * grouped by kind, and their share of the whole process: the "CPU share of
 * LOD work" that ADR-018 asks to measure in large modpacks. Threads are
 * sampled every 5 s so short-lived ones (an import) are mostly counted.
 * Render-thread work is not included; the renderer logs its own time per
 * frame.
 */
final class CpuUsage {

    private static final long SAMPLE_SECONDS = 5;
    private static final int SAMPLES_PER_LINE = 12;

    private static final Map<Long, Long> lastByThread = new HashMap<>();
    private static final Map<String, Long> window = new TreeMap<>();
    private static long windowStartProcessNanos;
    private static int samples;

    private CpuUsage() {
    }

    static void start() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported()) {
            Farfield.LOG.info("LOD CPU usage: thread CPU time not supported by this JVM");
            return;
        }
        threads.setThreadCpuTimeEnabled(true);
        windowStartProcessNanos = processCpuNanos();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "farfield-stats");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> sample(threads), SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
    }

    private static void sample(ThreadMXBean threads) {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String name = t.getName();
                if (!name.startsWith("farfield-") || name.equals("farfield-stats")) {
                    continue;
                }
                long cpu = threads.getThreadCpuTime(t.threadId());
                if (cpu < 0) {
                    continue;
                }
                Long last = lastByThread.put(t.threadId(), cpu);
                long delta = cpu - (last == null ? 0 : last);
                // "farfield-server-worker-3" -> "server-worker"
                String kind = name.substring("farfield-".length()).replaceAll("-[0-9]+$", "");
                window.merge(kind, delta, Long::sum);
            }
            if (++samples < SAMPLES_PER_LINE) {
                return;
            }
            samples = 0;
            long process = processCpuNanos();
            long processDelta = windowStartProcessNanos < 0 || process < 0 ? -1 : process - windowStartProcessNanos;
            windowStartProcessNanos = process;
            long total = 0;
            StringBuilder kinds = new StringBuilder();
            for (Map.Entry<String, Long> e : window.entrySet()) {
                total += e.getValue();
                if (e.getValue() > 0) {
                    kinds.append(String.format(Locale.ROOT, ", %s %.1f s", e.getKey(), e.getValue() / 1e9));
                }
            }
            window.clear();
            if (total == 0) {
                return;
            }
            long seconds = SAMPLE_SECONDS * SAMPLES_PER_LINE;
            String share = processDelta > 0
                    ? String.format(Locale.ROOT, "%.1f %% of the process CPU", 100.0 * total / processDelta)
                    : "process CPU unknown";
            if (RenderSettings.performanceLogs()) {
                Farfield.LOG.info(String.format(Locale.ROOT, "LOD CPU usage over %d s: %.1f s (%.2f cores, %s)%s",
                        seconds, total / 1e9, total / 1e9 / seconds, share, kinds));
            }
        } catch (Throwable t) {
            Farfield.LOG.debug("CPU usage sample failed", t);
        }
    }

    private static long processCpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return -1;
    }
}
