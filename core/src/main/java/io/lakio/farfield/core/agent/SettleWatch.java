package io.lakio.farfield.core.agent;

/**
 * The {@code wait stable} rule of {@link AgentCommand}: the renderer counts
 * as settled once it stayed quiet (nothing wanted in view, nothing meshing,
 * nothing fetched) for a whole hold time; any busy check starts the hold
 * again. Times in nanoseconds from one clock.
 */
public final class SettleWatch {

    public enum Outcome { WAITING, SETTLED, TIMED_OUT }

    private final long start;
    private final long holdNanos;
    private final long timeoutNanos;
    private long quietSince = -1;

    public SettleWatch(long start, double holdSeconds, double timeoutSeconds) {
        this.start = start;
        this.holdNanos = (long) (holdSeconds * 1e9);
        this.timeoutNanos = (long) (timeoutSeconds * 1e9);
    }

    public Outcome check(boolean quiet, long now) {
        if (!quiet) {
            quietSince = -1;
        } else if (quietSince < 0) {
            quietSince = now;
        }
        if (quietSince >= 0 && now - quietSince >= holdNanos) {
            return Outcome.SETTLED;
        }
        return now - start >= timeoutNanos ? Outcome.TIMED_OUT : Outcome.WAITING;
    }

    /** Seconds since the watch started. */
    public double elapsedSeconds(long now) {
        return (now - start) / 1e9;
    }
}
