package io.openrule.spring.fact;

import java.net.URI;
import java.util.*;

/** Typed execution snapshots supplied by the host after its configuration transaction ends. */
public final class FactConfiguration {
    private FactConfiguration() { }
    public enum Mode { HTTP, MOCK }
    public enum Purpose { SIMULATE, LIVE, MOCK_TEST }
    public enum ValueType { NUMBER, STRING, BOOLEAN;
        public boolean accepts(Object value) {
            return switch (this) {
                case NUMBER -> value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger;
                case STRING -> value instanceof String;
                case BOOLEAN -> value instanceof Boolean;
            };
        }
        public static ValueType from(String type) {
            return valueOf(type.toUpperCase(java.util.Locale.ROOT));
        }
    }
    public sealed interface Mapping permits Constant, Fact { }
    public record Constant(Object value) implements Mapping {
        public Constant { value = FactValues.freeze(value); }
        @Override public String toString() { return "Constant[REDACTED]"; }
    }
    public record Fact(String pointer) implements Mapping {
        public Fact { FactValues.tokens(pointer); }
    }
    public enum MockOutcome { RESPONSE, TIMEOUT, ERROR }
    public record MockSettings(MockOutcome outcome, Map<String, Object> response) {
        public MockSettings {
            Objects.requireNonNull(outcome);
            if (outcome == MockOutcome.RESPONSE) response = FactValues.map(response);
            else if (response != null) throw new IllegalArgumentException("Non-response mock must have null response");
        }
        @Override public String toString() { return "MockSettings[outcome=" + outcome + "]"; }
    }
    public record HttpSettings(URI endpoint, long connectTimeoutMillis, long readTimeoutMillis,
                               String secretRef, boolean readOnly, boolean allowSimulationHttp) {
        public HttpSettings {
            Objects.requireNonNull(endpoint);
            if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !readOnly || connectTimeoutMillis < 1 || connectTimeoutMillis > readTimeoutMillis
                    || readTimeoutMillis > 60000 || secretRef != null && !secretRef.matches("env:OPENRULE_[A-Z0-9_]+"))
                throw new IllegalArgumentException("Invalid HTTP execution settings");
        }
        @Override public String toString() { return "HttpSettings[REDACTED]"; }
    }
    public record Provider(String providerId, String revision, boolean enabled, Map<String, Mapping> requestMap,
                           MockSettings mock, HttpSettings http) {
        public Provider {
            Objects.requireNonNull(providerId); FactConfiguration.revision(revision); Objects.requireNonNull(mock);
            requestMap = Map.copyOf(requestMap);
            if (requestMap.size() > 64 || requestMap.keySet().stream().anyMatch(k -> !k.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")))
                throw new IllegalArgumentException("Invalid request mapping");
        }
        public String type() { return http == null ? "MOCK" : "HTTP"; }
    }
    public record Binding(String factId, String revision, String path, ValueType type, boolean sensitive,
                          String providerId, String responsePointer) {
        public Binding {
            Objects.requireNonNull(factId); FactConfiguration.revision(revision); FactValues.writePath(path); Objects.requireNonNull(type);
            if (providerId != null) FactValues.tokens(responsePointer);
            else if (responsePointer != null) throw new IllegalArgumentException("Unbound response pointer");
        }
    }
    public record Snapshot(List<Binding> facts, List<Provider> providers) {
        public Snapshot {
            facts = List.copyOf(facts); providers = List.copyOf(providers);
            Set<String> ids = new HashSet<>(), paths = new HashSet<>(), providerIds = new HashSet<>();
            for (Provider p : providers) if (!providerIds.add(p.providerId())) throw new IllegalArgumentException("Duplicate provider");
            for (Binding f : facts) {
                if (!ids.add(f.factId()) || !paths.add(f.path())) throw new IllegalArgumentException("Duplicate fact");
                if (f.providerId() != null && !providerIds.contains(f.providerId())) throw new IllegalArgumentException("Unknown provider");
            }
            for (Binding a : facts) for (Binding b : facts)
                if (a != b && FactValues.tokens(b.path()).size() > FactValues.tokens(a.path()).size()
                        && FactValues.tokens(b.path()).subList(0, FactValues.tokens(a.path()).size()).equals(FactValues.tokens(a.path())))
                    throw new IllegalArgumentException("Overlapping fact paths");
        }
    }
    private static void revision(String revision) {
        if (revision == null || !revision.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(revision)
                .compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw new IllegalArgumentException("Invalid revision");
    }
}
