package io.openrule.spring.fact;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.openrule.core.studio.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.openrule.spring.fact.FactConfiguration.*;
import static io.openrule.spring.fact.FactTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class FactRuntimeTest {
    private static Provider unmappedProvider(ObjectNode doc, URI endpoint) {
        doc.set("requestMap", CODEC.parse("{}"));
        return provider(doc, "1", endpoint);
    }
    private static FactResolutionRequest request(List<Binding> bindings, List<Provider> providers, Mode mode,
                                                  Map<String, Object> facts, StudioDeadline deadline) {
        return new FactResolutionRequest("runtime-trace", Purpose.SIMULATE, mode,
                required(bindings.stream().map(Binding::path).toArray(String[]::new)), facts, new Snapshot(bindings, providers), deadline);
    }
    @Test void providersAreSerialSortedAndMappingsNeverReadEarlierProviderResults() {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> order = new CopyOnWriteArrayList<>();
            var mock = new MockFactProvider();
            FactProvider observed = new FactProvider() {
                public String type() { return "MOCK"; }
                public FactBatchResult resolve(FactBatchRequest r) { order.add(r.provider().providerId()); return mock.resolve(r); }
            };
            var a = mock("aaa", Map.of("constant", new Constant(null)), 12);
            var b = mock("zzz", Map.of("dependency", new Fact("/a")), 99);
            List<Binding> bindings = List.of(binding("/b", "zzz"), binding("/aa", "aaa"), binding("/a", "aaa"));
            var resolver = new FactResolver(workers, List.of(observed));
            var result = resolver.resolve(request(bindings, List.of(b, a), Mode.MOCK, Map.of(), StudioDeadline.afterMillis(3000)));
            assertThat(order).containsExactly("aaa", "zzz");
            assertThat(result.report().calls()).extracting(FactResolutionReport.Call::providerId).containsExactly("aaa", "zzz");
            assertThat(result.report().calls().getFirst().requestedPaths()).containsExactly("/a", "/aa");
            assertThat(result.report().calls().getLast().attempted()).isFalse();
            assertThat(result.report().calls().getLast().error().code()).isEqualTo("OR-FACT-REQUEST-INPUT");
            assertThat(result.facts()).containsEntry("a", BigDecimal.valueOf(12)).containsEntry("aa", BigDecimal.valueOf(12)).doesNotContainKey("b");
            var empty = resolver.resolve(new FactResolutionRequest("empty", Purpose.MOCK_TEST, Mode.MOCK, List.of(), Map.of(),
                    new Snapshot(List.of(), List.of(a)), StudioDeadline.afterMillis(1000)));
            assertThat(empty.report().status()).isEqualTo("COMPLETE"); assertThat(empty.report().calls()).isEmpty();
        }
    }
    @Test void inFlightPermitIsNotReleasedUntilUncooperativeWorkerActuallyEnds() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            FactProvider adapter = new FactProvider() {
                public String type() { return "MOCK"; }
                public FactBatchResult resolve(FactBatchRequest r) {
                    r.attempt().markStarted(); calls.incrementAndGet(); started.countDown();
                    try {
                        while (release.getCount() > 0) {
                            try { release.await(); } catch (InterruptedException ignored) { }
                        }
                        return FactTransportSupport.response(r, Map.of("v", BigDecimal.ONE));
                    } finally { ended.countDown(); }
                }
            };
            var resolver = new FactResolver(workers, List.of(adapter), 1);
            var bindings = List.of(binding("/value", "aaa")); var providers = List.of(mock("aaa", Map.of(), 1));
            var firstRequest = request(bindings, providers, Mode.MOCK, Map.of(), StudioDeadline.afterMillis(200));
            var pending = workers.submit(() -> resolver.resolve(firstRequest));
            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                var rejected = resolver.resolve(request(bindings, providers, Mode.MOCK, Map.of(), StudioDeadline.afterMillis(1000)));
                assertThat(rejected.errorCode()).isEqualTo("OR-FACT-OVERLOADED");
                var timed = pending.get(1, TimeUnit.SECONDS);
                assertThat(timed.errorCode()).isEqualTo("OR-EXECUTION-TIMEOUT");
                assertThat(timed.facts()).isEmpty();
                assertThat(resolver.resolve(request(bindings, providers, Mode.MOCK, Map.of(), StudioDeadline.afterMillis(1000))).errorCode()).isEqualTo("OR-FACT-OVERLOADED");
                release.countDown(); assertThat(ended.await(1, TimeUnit.SECONDS)).isTrue();
                FactResolutionResult next = null;
                for (int i = 0; i < 100; i++) {
                    next = resolver.resolve(request(bindings, providers, Mode.MOCK, Map.of(), StudioDeadline.afterMillis(1000)));
                    if (next.successful()) break;
                    Thread.sleep(5);
                }
                assertThat(next.successful()).isTrue(); assertThat(next.facts()).containsKey("value");
                assertThat(timed.facts()).isEmpty(); // late value cannot mutate returned snapshot
                assertThat(calls.get()).isEqualTo(2);
            } finally { release.countDown(); }
        }
    }
    @Test void queuedCancellationAndExecutorRejectionReleasePermits() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> { try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
        var bindings = List.of(binding("/v", "aaa")); var providers = List.of(mock("aaa", Map.of(), 1));
        var resolver = new FactResolver(executor, List.of(new MockFactProvider()), 1);
        try {
            for (int i = 0; i < 2; i++) assertThat(resolver.resolve(request(bindings, providers, Mode.MOCK, Map.of(), StudioDeadline.afterMillis(30))).errorCode()).isEqualTo("OR-EXECUTION-TIMEOUT");
            release.countDown(); executor.shutdown(); assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 2; i++) assertThat(resolver.resolve(request(bindings, providers, Mode.MOCK, Map.of(), StudioDeadline.afterMillis(1000))).errorCode()).isEqualTo("OR-FACT-OVERLOADED");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void slowCompleteBodyUsesProviderBudgetAndLateBodyNeverMerges() throws Exception {
        ObjectNode transport = (ObjectNode) CODEC.parse("{\"slowBody\":true,\"delayMillis\":600,\"body\":{\"v\":365}}");
        try (var env = new FactTestSupport(transport)) {
            var config = unmappedProvider((ObjectNode) read("valid/risk-profile.provider.json"), env.endpoint);
            var bindings = List.of(binding("/v", config.providerId()));
            var result = env.resolver(ref -> null).resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(3000)));
            assertThat(env.received.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.successful()).isTrue();
            assertThat(result.report().calls().getFirst().status()).isEqualTo("TIMED_OUT");
            assertThat(result.report().fields().getFirst().error().code()).isEqualTo("OR-FACT-TIMEOUT");
            assertThat(result.facts()).isEmpty();
            assertThat(env.finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.facts()).isEmpty(); assertThat(env.bodies).hasSize(1);
        }
    }
    @Test void interruptedRealHttpRestoresInterruptAndSignalCancellationStopsRealHttp() throws Exception {
        for (boolean interrupt : List.of(true, false)) {
            try (var env = new FactTestSupport(CODEC.parse("{\"slowBody\":true,\"delayMillis\":700,\"body\":{\"v\":1}}"))) {
                var doc = (ObjectNode) read("valid/risk-profile.provider.json"); doc.put("readTimeoutMillis", 2000);
                var config = unmappedProvider(doc, env.endpoint);
                var deadline = StudioDeadline.afterMillis(3000);
                var input = request(List.of(binding("/v", config.providerId())), List.of(config), Mode.HTTP, Map.of(), deadline);
                AtomicReference<FactResolutionResult> result = new AtomicReference<>(); AtomicBoolean bit = new AtomicBoolean();
                Thread thread = Thread.ofVirtual().start(() -> { result.set(env.resolver(ref -> null).resolve(input)); bit.set(Thread.currentThread().isInterrupted()); });
                assertThat(env.received.await(2, TimeUnit.SECONDS)).isTrue();
                if (interrupt) thread.interrupt(); else deadline.cancel();
                thread.join(1500); assertThat(thread.isAlive()).isFalse();
                assertThat(result.get().errorCode()).isEqualTo("OR-EXECUTION-CANCELLED");
                assertThat(result.get().report().calls().getFirst().status()).isEqualTo("CANCELLED");
                assertThat(result.get().facts()).isEmpty();
                assertThat(bit.get()).isEqualTo(interrupt);
                assertThat(env.finished.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(result.get().facts()).isEmpty(); assertThat(env.bodies).hasSize(1);
            }
        }
    }
    @Test void bodyByteBoundaryExactNumbersAndNoRetryOrRedirect() throws Exception {
        ObjectNode transport = (ObjectNode) CODEC.parse("{\"outcome\":\"RAW_RESPONSE\",\"status\":200,\"rawBody\":\"{}\"}");
        try (var env = new FactTestSupport(transport)) {
            var doc = (ObjectNode) read("valid/risk-profile.provider.json"); doc.put("readTimeoutMillis", 3000);
            var config = unmappedProvider(doc, env.endpoint);
            var bindings = List.of(binding("/v", config.providerId()));
            var resolver = env.resolver(ref -> "secret");
            String prefix = "{\"v\":1,\"padding\":\"";
            for (int size : List.of(FactTransportSupport.MAX_BYTES, FactTransportSupport.MAX_BYTES + 1)) {
                transport.put("rawBody", prefix + "a".repeat(size - prefix.length() - 2) + "\"}");
                var result = resolver.resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
                if (size == FactTransportSupport.MAX_BYTES) assertThat(result.facts()).containsEntry("v", BigDecimal.ONE);
                else assertThat(result.report().fields().getFirst().error().code()).isEqualTo("OR-FACT-RESPONSE");
            }
            for (String value : List.of("9007199254740995", "0.123456789012345678901234567890123456789", "7".repeat(128))) {
                transport.put("rawBody", "{\"v\":" + value + "}");
                var result = resolver.resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
                assertThat(result.facts().get("v")).isEqualTo(new BigDecimal(value));
            }
            for (String invalid : List.of("7".repeat(129), "1e1001")) {
                transport.put("rawBody", "{\"v\":" + invalid + "}");
                var result = resolver.resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
                assertThat(result.report().fields().getFirst().error().code()).isEqualTo("OR-FACT-RESPONSE");
            }
            transport.put("status", 302); transport.put("rawBody", "{}");
            int before = env.bodies.size();
            var redirect = resolver.resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
            assertThat(redirect.report().fields().getFirst().error().code()).isEqualTo("OR-FACT-HTTP");
            assertThat(env.bodies).hasSize(before + 1);
            transport.put("outcome", "DISCONNECT");
            before = env.bodies.size();
            var broken = resolver.resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
            assertThat(broken.report().fields().getFirst().error().code()).isEqualTo("OR-FACT-HTTP");
            assertThat(env.bodies).hasSize(before + 1);
        }
    }
    @Test void immutableInputsMockPrecisionAndRedactionDoNotLeakCallerOrSecret() throws Exception {
        var document = CODEC.parse(java.nio.file.Files.readString(FIXTURES.resolve("valid/precision.provider.json")));
        Provider provider = provider(document, "1", URI.create("http://127.0.0.1:1/no-network"));
        List<Binding> bindings = bindings(read("catalog.json"));
        Map<String, Object> buyer = new HashMap<>(); buyer.put("id", "caller-private");
        Map<String, Object> facts = new HashMap<>(); facts.put("buyer", buyer);
        var input = request(bindings.stream().filter(b -> b.providerId() != null).toList(), List.of(provider), Mode.MOCK, facts, StudioDeadline.afterMillis(5000));
        buyer.put("id", "mutated"); facts.clear();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var resolver = new FactResolver(workers, List.of(new MockFactProvider()));
            var result = resolver.resolve(input);
            assertThat(FactValues.lookup(result.facts(), "/buyer/id").value()).isEqualTo("caller-private");
            assertThat(FactValues.lookup(result.facts(), "/buyer/ageDays").value()).isEqualTo(new BigDecimal("9007199254740995"));
            assertThat(FactValues.lookup(result.facts(), "/metrics/refundRate").value()).isEqualTo(new BigDecimal("0.123456789012345678901234567890123456789"));
            assertThatThrownBy(() -> ((Map<String, Object>) input.callerFacts().get("buyer")).put("id", "bad")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(input.toString() + result.toString() + provider).doesNotContain("caller-private");
            String summary = FactRedaction.summary("😀".repeat(200), new Binding("str", "1", "/str", ValueType.STRING, false, null, null));
            assertThat(summary.codePointCount(0, summary.length())).isEqualTo(128);
            assertThat(FactRedaction.summary(null, null)).isEqualTo("[REDACTED]");
            var failed = new StudioModel.Result("FAILED", null, List.of(), List.of(new StudioModel.NodeResult(
                    "stage", "node", "openrule.operator", StudioModel.Status.FAILED, false, Map.of(), List.of(),
                    "OR-ENGINE-FAILURE: TOP_SECRET_VALUE", null, 0)), Map.of(), "OR-ENGINE-FAILURE", 0);
            assertThat(FactRedaction.execution(failed).nodeResults().getFirst().failure()).isEqualTo("OR-ENGINE-FAILURE: Node execution failed");
            assertThatThrownBy(() -> new Constant(0.1d)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FactResolver(workers, List.of(new MockFactProvider(), new MockFactProvider()))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void providerAndCoreConsumeOneBudgetAndTotalTimeoutWinsAtEqualLimits() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var bindings = List.of(binding("/v", "aaa"));
            var config = mock("aaa", Map.of(), 1);
            var delegate = new MockFactProvider();
            FactProvider slow = new FactProvider() {
                public String type() { return "MOCK"; }
                public FactBatchResult resolve(FactBatchRequest r) {
                    try { Thread.sleep(200); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    return delegate.resolve(r);
                }
            };
            var plan = new StudioCompiler().compilePlan(new StudioModel.Flow(2, "budget", "Budget", "1", "", List.of(
                    new StudioModel.Stage("work", 1, StudioModel.Mode.SERIAL, null, 3000, List.of(
                            new StudioModel.Node("probe", 1, "openrule.operator", 1, 3000, StudioModel.FailPolicy.CONTINUE,
                                    new StudioModel.Operator(new StudioModel.Presence("is-present", new StudioModel.Ref(StudioModel.Source.FACT, "/v")), "hit", "HIT")))),
                    new StudioModel.Stage("final", 2, StudioModel.Mode.SERIAL, null, 3000, List.of(
                            new StudioModel.Node("done", 1, "openrule.terminal", 1, 3000, null, new StudioModel.Terminal("APPROVE", List.of("OK"), null)))))));
            var deadline = StudioDeadline.afterMillis(500);
            var result = new FactResolver(workers, List.of(slow)).resolve(request(bindings, List.of(config), Mode.MOCK, Map.of(), deadline));
            assertThat(result.successful()).isTrue();
            var engine = new StudioEngine(workers, Map.of("openrule.operator", (n, stage, f, v, prior) -> {
                Thread.sleep(2000); throw new AssertionError("late node result");
            }));
            var core = engine.execute(plan, result.facts(), deadline);
            assertThat(core.errorCode()).isEqualTo("OR-EXECUTION-TIMEOUT"); assertThat(core.decision()).isNull();
        }
        try (var env = new FactTestSupport(CODEC.parse("{\"slowBody\":true,\"delayMillis\":1000,\"body\":{\"v\":1}}"))) {
            ObjectNode doc = (ObjectNode) read("valid/risk-profile.provider.json"); doc.put("readTimeoutMillis", 300);
            var config = unmappedProvider(doc, env.endpoint);
            var result = env.resolver(ref -> null).resolve(request(List.of(binding("/v", config.providerId())), List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(300)));
            assertThat(result.errorCode()).isEqualTo("OR-EXECUTION-TIMEOUT");
            assertThat(result.report().status()).isEqualTo("FAILED"); assertThat(result.facts()).isEmpty();
        }
    }
    @Test void httpRefusesStartupWithoutNoRetryFlags() {
        String prior = System.getProperty("jdk.httpclient.disableRetryConnect");
        try {
            System.clearProperty("jdk.httpclient.disableRetryConnect");
            assertThatThrownBy(() -> new HttpFactProvider(ref -> null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("JVM startup flags");
        } finally {
            if (prior != null) System.setProperty("jdk.httpclient.disableRetryConnect", prior);
        }
    }
    @Test void requestBodyLimitSecretAndSnapshotValidation() throws Exception {
        try (var env = new FactTestSupport(CODEC.parse("{\"body\":{\"v\":1}}"))) {
            ObjectNode doc = (ObjectNode) read("valid/risk-profile.provider.json");
            doc.put("secretRef", "env:OPENRULE_TEST"); doc.put("readTimeoutMillis", 3000);
            var config = unmappedProvider(doc, env.endpoint);
            var bindings = List.of(binding("/v", config.providerId()));
            AtomicInteger secretCalls = new AtomicInteger();
            var resolver = env.resolver(ref -> { secretCalls.incrementAndGet(); return "TOP_SECRET_VALUE"; });
            var result = resolver.resolve(request(bindings, List.of(config), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
            assertThat(result.facts()).containsEntry("v", BigDecimal.ONE);
            assertThat(env.auth).containsExactly("Bearer TOP_SECRET_VALUE");
            assertThat(result.toString() + config).doesNotContain("TOP_SECRET_VALUE");
            Provider huge = new Provider(config.providerId(), "1", true, Map.of("payload", new Constant("x".repeat(FactTransportSupport.MAX_BYTES))), config.mock(), config.http());
            result = resolver.resolve(request(bindings, List.of(huge), Mode.HTTP, Map.of(), StudioDeadline.afterMillis(5000)));
            assertThat(result.report().calls().getFirst().attempted()).isFalse();
            assertThat(result.report().fields().getFirst().error().code()).isEqualTo("OR-FACT-REQUEST-INPUT");
            assertThat(env.bodies).hasSize(1); assertThat(secretCalls).hasValue(1);
        }
        for (String path : List.of("/metrics/0", "/__proto__/polluted", "/constructor/x", "/a//b"))
            assertThatThrownBy(() -> binding(path, "aaa")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(List.of(binding("/a", "aaa")), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(List.of(binding("/a", "aaa"), binding("/a/b", "aaa")), List.of(mock("aaa", Map.of(), 1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpSettings(URI.create("http://127.0.0.1/"), 400, 300, null, true, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpSettings(URI.create("http://127.0.0.1/?token=secret"), 100, 300, null, true, true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void presenceOnlyResolutionChangesOnlyExplicitM2Execution() {
        var condition = new StudioModel.Presence("is-missing", new StudioModel.Ref(StudioModel.Source.FACT, "/v"));
        var flow = new StudioModel.Flow(2, "presence", "Presence", "1", "", List.of(new StudioModel.Stage("stage", 1, StudioModel.Mode.SERIAL,
                null, 3000, List.of(new StudioModel.Node("probe", 1, "openrule.operator", 1, 3000, StudioModel.FailPolicy.CONTINUE,
                new StudioModel.Operator(condition, "missing", "PROBE")))), new StudioModel.Stage("final_stage", 2, StudioModel.Mode.SERIAL, null, 3000, List.of(new StudioModel.Node("done", 2, "openrule.terminal", 1, 3000, null,
                new StudioModel.Terminal("APPROVE", List.of("OK"), null))))));
        var plan = new StudioCompiler().compilePlan(flow);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var engine = new StudioEngine(workers);
            assertThat(engine.execute(flow, Map.of(), 3000).variables().get("missing")).isEqualTo(true);
            var resolver = new FactResolver(workers, List.of(new MockFactProvider()));
            var deadline = StudioDeadline.afterMillis(3000);
            var result = resolver.resolve(new FactResolutionRequest("presence", Purpose.SIMULATE, Mode.MOCK, plan.requiredFacts(), Map.of(),
                    new Snapshot(List.of(binding("/v", "aaa")), List.of(mock("aaa", Map.of(), 1))), deadline));
            assertThat(engine.execute(plan, result.facts(), deadline).variables().get("missing")).isEqualTo(false);
        }
    }
}
