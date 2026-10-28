package io.openrule.spring.fact;

import io.openrule.core.studio.RequiredFact;
import io.openrule.core.studio.StudioDeadline;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static io.openrule.spring.fact.FactConfiguration.*;

public record FactResolutionRequest(String traceId, Purpose purpose, Mode mode, List<RequiredFact> requiredFacts,
                                    Map<String, Object> callerFacts, Snapshot snapshot, StudioDeadline deadline) {
    public FactResolutionRequest {
        Objects.requireNonNull(traceId); Objects.requireNonNull(purpose); Objects.requireNonNull(mode);
        requiredFacts = List.copyOf(requiredFacts); callerFacts = FactValues.map(callerFacts);
        Objects.requireNonNull(snapshot); Objects.requireNonNull(deadline);
        if (purpose == Purpose.LIVE && mode != Mode.HTTP || purpose == Purpose.MOCK_TEST && mode != Mode.MOCK)
            throw new IllegalArgumentException("Unsupported purpose/factMode");
    }
    @Override public String toString() { return "FactResolutionRequest[traceId=" + traceId + ",mode=" + mode + "]"; }
}
