package io.lakio.farfield.core.importer;

import java.util.concurrent.TimeUnit;

/**
 * Shared permit schedule: hands out one permit every {@code 1 / rate}
 * seconds. Waits shorter than {@link #MIN_SLEEP_NANOS} are not slept (the OS
 * timer cannot honour them, notably on Windows) but still advance the
 * schedule, so the average rate holds. A short burst allowance lets workers
 * catch up after an oversleep.
 */
final class RateLimiter {

    static final long MIN_SLEEP_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long BURST_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final long intervalNanos;
    private long next = System.nanoTime();

    RateLimiter(double permitsPerSecond) {
        if (!(permitsPerSecond > 0)) {
            throw new IllegalArgumentException("rate must be positive");
        }
        this.intervalNanos = Math.max(1, (long) (1e9 / permitsPerSecond));
    }

    /** Takes one permit, sleeping if the schedule is ahead; returns the nanoseconds slept. */
    long acquire() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(next, now - BURST_NANOS);
            next = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait < MIN_SLEEP_NANOS) {
            return 0;
        }
        TimeUnit.NANOSECONDS.sleep(wait);
        return wait;
    }
}
