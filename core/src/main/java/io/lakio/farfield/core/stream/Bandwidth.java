package io.lakio.farfield.core.stream;

/**
 * A byte budget refilled at a fixed rate (token bucket), holding at most one
 * second of it: the streaming sends a message only when its player's budget
 * and the budget shared by all players both cover it (ADR-068), so a burst
 * never floods the game connection (Neo-Voxy's 16 MB/s, ADR-013). A rate of
 * 0 means no limit. Thread-safe.
 */
public final class Bandwidth {

    private final long bytesPerSecond;
    private double available;
    private long last;

    /** @param bytesPerSecond the refill rate; 0 for unlimited */
    public Bandwidth(long bytesPerSecond, long nowNanos) {
        this.bytesPerSecond = Math.max(0, bytesPerSecond);
        this.available = this.bytesPerSecond;
        this.last = nowNanos;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    private void refill(long nowNanos) {
        if (nowNanos > last) {
            available = Math.min(bytesPerSecond, available + (nowNanos - last) * 1e-9 * bytesPerSecond);
            last = nowNanos;
        }
    }

    /**
     * Takes {@code bytes} from this budget and {@code shared} (may be null)
     * when both hold them; else takes nothing. A message larger than a whole
     * second of budget passes once the budget is full, so it is never stuck.
     */
    public boolean tryTake(int bytes, Bandwidth shared, long nowNanos) {
        if (shared == null) {
            synchronized (this) {
                return take(bytes, nowNanos);
            }
        }
        synchronized (shared) {
            synchronized (this) {
                refill(nowNanos);
                shared.refill(nowNanos);
                if (!covers(bytes) || !shared.covers(bytes)) {
                    return false;
                }
                spend(bytes);
                shared.spend(bytes);
                return true;
            }
        }
    }

    private boolean take(int bytes, long nowNanos) {
        refill(nowNanos);
        if (!covers(bytes)) {
            return false;
        }
        spend(bytes);
        return true;
    }

    private boolean covers(int bytes) {
        return bytesPerSecond == 0 || available >= Math.min(bytes, bytesPerSecond);
    }

    private void spend(int bytes) {
        if (bytesPerSecond != 0) {
            available -= bytes;
        }
    }
}
