package io.lakio.farfield.core.stream;

/**
 * How many section requests a client keeps in flight (ADR-068), grown and
 * shrunk by the time its answers take (AIMD): one more per window of answers
 * that come back within {@code slackNanos} of the quickest one, halved when
 * one comes back later than that, at most once per round trip. The server
 * queues its answers behind its bandwidth budgets; a link slower than those
 * budgets fills the connection's buffers instead, and the game's own
 * packets (the ping) wait behind them. A smaller window keeps that queue
 * short, and the newest wishes (the player turned) are served sooner. Not
 * thread-safe.
 */
public final class FetchWindow {

    private final int min;
    private final int max;
    private final long slackNanos;
    private double window;
    private long quickest = Long.MAX_VALUE;
    private boolean cut;
    private long lastCut;

    public FetchWindow(int min, int max, int initial, long slackNanos) {
        this.min = min;
        this.max = max;
        this.slackNanos = slackNanos;
        this.window = Math.max(min, Math.min(max, initial));
    }

    /** Requests allowed in flight now. */
    public int size() {
        return (int) window;
    }

    /** An answer came back {@code rttNanos} after its request was sent. */
    public void answered(long rttNanos, long nowNanos) {
        if (rttNanos < quickest) {
            quickest = rttNanos;
        }
        if (rttNanos > quickest + slackNanos) {
            // One cut per round trip: the answers already queued behind it are late too.
            if (!cut || nowNanos - lastCut > rttNanos) {
                window = Math.max(min, window / 2);
                cut = true;
                lastCut = nowNanos;
            }
        } else {
            window = Math.min(max, window + 1 / window);
        }
    }

    /** A request was never answered: back to the smallest window. */
    public void timedOut(long nowNanos) {
        window = min;
        cut = true;
        lastCut = nowNanos;
    }

    /** A new session: the link may be another one. */
    public void reset(int initial) {
        window = Math.max(min, Math.min(max, initial));
        quickest = Long.MAX_VALUE;
        cut = false;
    }
}
