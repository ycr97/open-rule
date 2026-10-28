package io.openrule.spring.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.openrule.core.studio.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static io.openrule.spring.fact.FactConfiguration.*;
import static io.openrule.spring.fact.FactTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class FactFixtureTest {
    @TestFactory Stream<DynamicTest> frozenMfCases() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode sample : read("order-admission.cases.json").path("cases"))
            tests.add(DynamicTest.dynamicTest(sample.path("id").asText() + " " + sample.path("title").asText(), () -> execute(sample)));
        return tests.stream();
    }
    private void execute(JsonNode sample) throws Exception {
        try (var env = new FactTestSupport(sample.path("transport"))) {
            String id = sample.path("id").asText();
            JsonNode expected = sample.path("expected"), request = sample.path("request");
            var definition = CODEC.parse(Files.readString(FIXTURES.resolve(sample.path("definitionFile").asText())));
            var plan = new StudioCompiler().compilePlan(CODEC.bind(definition));
            Map<String, Object> caller = map(request.path("facts"));
            var engine = new StudioEngine(env.workers);
            AtomicInteger secrets = new AtomicInteger();
            FactResolutionResult resolved = null;
            StudioModel.Result execution = null;
            String error = null;
            if (sample.path("endpoint").asText().startsWith("M1")) execution = engine.execute(plan.flow(), caller, request.path("timeoutMillis").asLong());
            else {
                ObjectNode document = overlay((ObjectNode) read(sample.path("providerDocumentFile").asText()), sample.path("providerPatch"));
                URI endpoint = env.endpoint;
                if (sample.path("transport").path("outcome").asText().equals("CONNECT_ERROR")) {
                    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                        endpoint = URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/closed");
                    }
                }
                Provider provider = provider(document, sample.path("providerRevision").asText(), endpoint);
                Snapshot snapshot = new Snapshot(bindings(read(sample.path("catalogFile").asText())), List.of(provider));
                var deadline = StudioDeadline.afterMillis(request.path("timeoutMillis").asLong());
                resolved = env.resolver(ref -> { secrets.incrementAndGet(); return null; }).resolve(new FactResolutionRequest(
                        "trace-" + id, sample.path("endpoint").asText().equals("LIVE") ? Purpose.LIVE : Purpose.SIMULATE,
                        Mode.valueOf(request.path("factMode").asText()), plan.requiredFacts(), caller, snapshot, deadline));
                error = resolved.errorCode();
                if (resolved.successful()) execution = engine.execute(plan, resolved.facts(), deadline);
                Map<String, FactResolutionReport.Field> fields = new HashMap<>();
                resolved.report().fields().forEach(f -> fields.put(f.path(), f));
                assertThat(fields).hasSize(plan.requiredFacts().size());
                fields.forEach((path, field) -> {
                    if (FactValues.lookup(caller, path).present()) {
                        assertThat(field.source()).isEqualTo("CALLER");
                        assertThat(field.providerId()).isNull(); assertThat(field.providerRevision()).isNull();
                    } else if (field.source().equals("PROVIDER")) {
                        assertThat(field.providerId()).isNotBlank(); assertThat(field.providerRevision()).isEqualTo("1");
                    }
                });
                expected.path("fieldStates").fields().forEachRemaining(e -> assertThat(fields.get(e.getKey()).status()).isEqualTo(e.getValue().asText()));
                expected.path("fieldErrors").fields().forEachRemaining(e -> assertThat(fields.get(e.getKey()).error().code()).isEqualTo(e.getValue().asText()));
                FactResolutionResult actual = resolved;
                expected.path("preserved").fields().forEachRemaining(e -> assertThat(FactValues.lookup(actual.facts(), e.getKey()).value()).isEqualTo(FactValues.freeze(CODEC.value(e.getValue()))));
                if (expected.has("requestedPaths")) {
                    List<String> paths = new ArrayList<>(); expected.path("requestedPaths").forEach(p -> paths.add(p.asText()));
                    assertThat(resolved.report().calls().getFirst().requestedPaths()).containsExactlyElementsOf(paths);
                }
                assertThat(resolved.report().calls()).allSatisfy(call -> {
                    assertThat(call.traceId()).isEqualTo("trace-" + id);
                    assertThat(call.providerRevision()).isEqualTo(sample.path("providerRevision").asText());
                });
                assertThat(fields.values()).allSatisfy(field -> assertThat(field.catalogRevision()).isEqualTo("1"));
                if (expected.has("mockCalls")) assertThat(resolved.report().calls().stream().filter(c -> c.transport().equals("MOCK") && c.attempted()).count()).isEqualTo(expected.path("mockCalls").asLong());
                if (expected.has("secretResolutions")) assertThat(secrets.get()).isEqualTo(expected.path("secretResolutions").asInt());
                if (id.equals("MF-05")) assertThat(FactValues.lookup(resolved.facts(), "/buyer/ageDays").value()).isEqualTo(new java.math.BigDecimal("365"));
                if (id.equals("MF-01")) {
                    assertThat(fields.get("/risk/blacklisted").valueSummary()).isEqualTo("[REDACTED]");
                    assertThat(fields.get("/buyer/ageDays").valueSummary()).isEqualTo("365");
                }
            }
            assertThat(execution != null).isEqualTo(expected.path("coreInvoked").asBoolean());
            if (execution != null) {
                assertThat(execution.status()).isEqualTo(expected.path("status").asText());
                assertThat(execution.decision() == null ? null : execution.decision().decisionCode()).isEqualTo(expected.path("decisionCode").isNull() ? null : expected.path("decisionCode").asText());
                if (expected.has("terminalNodeId")) assertThat(execution.decision().terminalNodeId()).isEqualTo(expected.path("terminalNodeId").asText());
                if (expected.has("score")) assertThat(execution.decision() == null || execution.decision().score() == null ? null : execution.decision().score().stripTrailingZeros().toPlainString()).isEqualTo(expected.path("score").isNull() ? null : expected.path("score").asText());
                if (expected.has("segment")) assertThat(execution.variables().get("segment")).isEqualTo(expected.path("segment").asText());
                StudioModel.Result core = execution;
                expected.path("nodeFailures").fields().forEachRemaining(e -> {
                    String failure = core.nodeResults().stream().filter(n -> n.nodeId().equals(e.getKey())).findFirst().orElseThrow().failure();
                    if (e.getValue().asText().equals("TYPE")) assertThat(failure).doesNotContain("Missing value").containsIgnoringCase("type");
                });
                error = execution.errorCode();
                var safe = FactRedaction.execution(execution);
                assertThat(safe.variables().values()).allMatch(v -> v.equals("[REDACTED]"));
                assertThat(safe.nodeResults()).allSatisfy(n -> assertThat(n.outputs().values()).allMatch(v -> v.equals("[REDACTED]")));
            }
            if (expected.has("errorCode")) assertThat(error).isEqualTo(expected.path("errorCode").asText());
            int status = error == null ? 200 : error.equals("OR-EXECUTION-TIMEOUT") ? 504 : 500;
            assertThat(status).isEqualTo(expected.path("httpStatus").asInt()); // B1 projection only; no host API.
            if (sample.path("transport").path("outcome").asText().equals("CONNECT_ERROR")) {
                assertThat(env.bodies).isEmpty();
                assertThat(resolved.report().calls().getFirst().attempted()).isTrue();
            } else assertThat(env.bodies).hasSize(expected.path("httpCalls").asInt());
            assertThat(env.methods).allMatch("POST"::equals);
            for (String body : env.bodies) assertThat(new ObjectMapper().readTree(body)).isEqualTo(new ObjectMapper().readTree("{\"buyerId\":\"buyer_1024\",\"region\":\"GLOBAL\"}"));
            System.out.println(id + " PASS post=" + env.bodies.size() + " error=" + error
                    + " decision=" + (execution == null || execution.decision() == null ? null : execution.decision().decisionCode()));
        }
    }
    @Test void requiredFactsMatchFrozenFixtureAndOriginalPointersAfterReorder() throws Exception {
        ObjectNode definition = (ObjectNode) CODEC.parse(Files.readString(FIXTURES.resolve("../../studio-m1/fixtures/valid/order-admission.definition.json")));
        ObjectMapper mapper = new ObjectMapper();
        var plan = new StudioCompiler().compilePlan(CODEC.bind(definition));
        assertThat((JsonNode) mapper.valueToTree(plan.requiredFacts())).isEqualTo(read("valid/order-admission.required-facts.json").path("requiredFacts"));
        ArrayNode stages = (ArrayNode) definition.path("stages"); reverse(stages);
        for (JsonNode stage : stages) {
            reverse((ArrayNode) stage.path("nodes"));
            for (JsonNode node : stage.path("nodes")) {
                JsonNode config = node.path("config");
                if (config.has("rules")) reverse((ArrayNode) config.path("rules"));
                if (config.has("characteristics")) {
                    reverse((ArrayNode) config.path("characteristics"));
                    for (JsonNode c : config.path("characteristics")) reverse((ArrayNode) c.path("bins"));
                }
            }
        }
        var reordered = new StudioCompiler().compilePlan(CODEC.bind(definition));
        assertThat(reordered.requiredFacts()).hasSize(5);
        for (var fact : reordered.requiredFacts()) for (var use : fact.uses()) {
            assertThat(definition.at(use.pointer()).asText()).isEqualTo(fact.path());
            String nodePointer = use.pointer().split("/config")[0];
            assertThat(definition.at(nodePointer).path("nodeId").asText()).isEqualTo(use.nodeId());
            if (use.itemId() != null) {
                String itemPointer = use.pointer().split("/condition")[0];
                JsonNode item = definition.at(itemPointer);
                assertThat(item.path("id").asText()).isEqualTo(use.itemId());
            }
        }
    }
    private static void reverse(ArrayNode array) {
        List<JsonNode> list = new ArrayList<>(); array.forEach(list::add); Collections.reverse(list); array.removeAll(); list.forEach(array::add);
    }
}
