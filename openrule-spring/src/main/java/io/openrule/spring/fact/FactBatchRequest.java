package io.openrule.spring.fact;

import io.openrule.core.studio.StudioDeadline;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static io.openrule.spring.fact.FactConfiguration.*;

public record FactBatchRequest(String traceId, Purpose purpose, Provider provider, List<Binding> fields,
                               Map<String, Object> callerFacts, StudioDeadline deadline, long callDeadlineNanos, Attempt attempt) {
    public FactBatchRequest(String traceId, Purpose purpose, Provider provider, List<Binding> fields,
                            Map<String, Object> callerFacts, StudioDeadline deadline, long callDeadlineNanos) {
        this(traceId, purpose, provider, fields, callerFacts, deadline, callDeadlineNanos, new Attempt());
    }
    /** Only transport progress is mutable; the configuration and fact values remain frozen. */
    public static final class Attempt {
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        public void markStarted() { started.set(true); }
        public boolean started() { return started.get(); }
    }
    public FactBatchRequest {
        Objects.requireNonNull(traceId); Objects.requireNonNull(purpose); Objects.requireNonNull(provider);
        fields = List.copyOf(fields); callerFacts = FactValues.map(callerFacts); Objects.requireNonNull(deadline); Objects.requireNonNull(attempt);
    }
    public long remainingNanos() { return Math.max(0, Math.min(deadline.remainingNanos(), callDeadlineNanos - System.nanoTime())); }
    @Override public String toString() { return "FactBatchRequest[traceId=" + traceId + ",providerId=" + provider.providerId() + "]"; }
}
