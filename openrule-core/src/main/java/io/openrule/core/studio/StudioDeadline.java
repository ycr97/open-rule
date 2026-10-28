package io.openrule.core.studio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One monotonic absolute budget and cooperative cancellation signal per request. */
public final class StudioDeadline {
    private final long expiresAtNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private StudioDeadline(long expiresAtNanos) { this.expiresAtNanos = expiresAtNanos; }
    public static StudioDeadline afterMillis(long millis) {
        if (millis < 1 || millis > TimeUnit.DAYS.toMillis(1))
            throw new IllegalArgumentException("timeoutMillis must be in 1..86400000");
        return new StudioDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
    }
    public long expiresAtNanos() { return expiresAtNanos; }
    public long remainingNanos() { return Math.max(0, expiresAtNanos - System.nanoTime()); }
    public boolean expired() { return remainingNanos() == 0; }
    public void cancel() { cancelled.set(true); }
    public boolean cancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    public String failureCode() {
        return cancelled() ? "OR-EXECUTION-CANCELLED" : expired() ? "OR-EXECUTION-TIMEOUT" : null;
    }
}
