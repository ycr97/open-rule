package io.openrule.spring.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.openrule.core.studio.StudioEngine;
import io.openrule.core.studio.StudioModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class StudioB3FixtureTest {
    private static final Path FIXTURES = Path.of("../docs/contracts/studio-m1/fixtures/valid");
    private final StudioDocumentCodec codec = new StudioDocumentCodec();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach void close() { pool.shutdownNow(); }

    @Test void executesEveryOrderAdmissionFixtureWithTypedDetails() throws Exception {
        JsonNode cases = codec.parse("{\"cases\":" + Files.readString(FIXTURES.resolve("order-admission.cases.json")) + "}").path("cases");
        StudioEngine engine = new StudioEngine(pool);
        for (JsonNode sample : cases) {
            JsonNode document = codec.parse(Files.readString(FIXTURES.resolve(sample.path("definitionFixture").asText())));
            assertThat(codec.validate(document).valid()).as(sample.path("id").asText()).isTrue();
            @SuppressWarnings("unchecked") Map<String, Object> facts = (Map<String, Object>) codec.value(sample.path("facts"));
            StudioModel.Result result = engine.execute(codec.executable(document), facts, 3000);
            JsonNode expected = sample.path("expected");
            String id = sample.path("id").asText();
            assertThat(result.status()).as(id).isEqualTo(expected.path("status").asText());
            assertThat(result.decision() == null ? null : result.decision().decisionCode()).as(id)
                    .isEqualTo(expected.path("decisionCode").isNull() ? null : expected.path("decisionCode").asText());
            assertThat(result.decision() == null ? null : result.decision().terminalNodeId()).as(id)
                    .isEqualTo(expected.path("terminalNodeId").isNull() ? null : expected.path("terminalNodeId").asText());
            if (expected.has("score")) assertThat(result.decision() == null || result.decision().score() == null
                    ? null : result.decision().score().stripTrailingZeros().toPlainString()).as(id)
                    .isEqualTo(expected.path("score").isNull() ? null : expected.path("score").asText());
            if (expected.has("segment")) assertThat(result.variables().get("segment")).as(id)
                    .isEqualTo(expected.path("segment").isNull() ? null : expected.path("segment").asText());
            if (result.decision() != null) assertThat(result.decision().reasonCodes()).as(id)
                    .containsExactlyElementsOf(codecList(expected.path("reasonCodes")));
            expected.path("nodeStatus").fields().forEachRemaining(entry -> assertThat(result.nodeResults().stream()
                    .filter(node -> node.nodeId().equals(entry.getKey())).findFirst().orElseThrow().status().name())
                    .as(id + " " + entry.getKey()).isEqualTo(entry.getValue().asText()));
            if (id.equals("OA-07")) assertThat(node(result, "buyer_score").failure()).contains("Missing value");
            if (id.equals("OA-08")) assertThat(node(result, "buyer_score").failure()).doesNotContain("Missing value");
            if (id.equals("OA-01")) {
                assertThat(node(result, "buyer_score").details()).hasSize(2)
                        .allMatch(detail -> detail instanceof StudioModel.ScoreDetail);
                assertThat(node(result, "order_segment").details()).hasSize(1)
                        .allMatch(detail -> detail instanceof StudioModel.RuleDetail);
            }
        }
    }

    @Test void validatesNumericBinOverlapAndGapAndKeepsDecimalSumExact() throws Exception {
        ObjectNode definition = (ObjectNode) codec.parse(Files.readString(FIXTURES.resolve("order-admission.definition.json")));
        ObjectNode high = (ObjectNode) definition.path("stages").get(2).path("nodes").get(0)
                .path("config").path("characteristics").get(1).path("bins").get(0);
        ObjectNode low = (ObjectNode) definition.path("stages").get(2).path("nodes").get(0)
                .path("config").path("characteristics").get(1).path("bins").get(1);
        high.put("score", new java.math.BigDecimal("0.1"));
        low.put("score", new java.math.BigDecimal("0.2"));
        ObjectNode ageOld = (ObjectNode) definition.path("stages").get(2).path("nodes").get(0)
                .path("config").path("characteristics").get(0).path("bins").get(1);
        ageOld.put("score", new java.math.BigDecimal("0.1"));
        @SuppressWarnings("unchecked") Map<String, Object> facts = (Map<String, Object>) codec.value(codec.parse(
                "{\"facts\":{\"order\":{\"amount\":680,\"country\":\"JP\"},\"buyer\":{\"ageDays\":365},"
                        + "\"metrics\":{\"refundRate\":0.08},\"risk\":{\"blacklisted\":false}}}").path("facts"));
        assertThat(new StudioEngine(pool).execute(codec.executable(definition), facts, 3000)
                .variables().get("riskScore")).isEqualTo(new java.math.BigDecimal("0.3"));
        ObjectNode condition = (ObjectNode) low.path("condition");
        condition.put("op", "lte");
        assertThat(codec.validate(definition).issues()).anySatisfy(issue ->
                assertThat(issue.message()).contains("overlap"));
        condition.put("op", "lt");
        ((ObjectNode) high.path("condition")).put("op", "gt");
        assertThat(codec.validate(definition).issues()).anySatisfy(issue ->
                assertThat(issue.message()).contains("gap"));
    }

    private static StudioModel.NodeResult node(StudioModel.Result result, String id) {
        return result.nodeResults().stream().filter(node -> node.nodeId().equals(id)).findFirst().orElseThrow();
    }

    private static java.util.List<String> codecList(JsonNode input) {
        java.util.List<String> values = new java.util.ArrayList<>();
        input.forEach(value -> values.add(value.asText()));
        return values;
    }
}
