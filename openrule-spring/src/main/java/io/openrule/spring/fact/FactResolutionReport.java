package io.openrule.spring.fact;

import java.util.List;
import static io.openrule.spring.fact.FactConfiguration.Mode;

/** Safe field/call projection: never contains raw facts, secrets, endpoints or upstream bodies. */
public record FactResolutionReport(Mode mode, String status, long elapsedMillis, List<Field> fields, List<Call> calls) {
    public FactResolutionReport { fields = List.copyOf(fields); calls = List.copyOf(calls); }
    public record Error(String code, String message) {
        public static Error of(String code) { return code == null ? null : new Error(code, "Fact resolution failed"); }
    }
    public record Field(String path, String status, String source, String factId, String catalogRevision,
                        String providerId, String providerRevision, String valueSummary, Error error) { }
    public record Call(String providerId, String providerRevision, String traceId, String transport, String status,
                       boolean attempted, List<String> requestedPaths, long elapsedMillis, Error error) {
        public Call { requestedPaths = List.copyOf(requestedPaths); }
    }
}
