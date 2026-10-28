package io.openrule.spring.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioDocumentCodecTest {
    private final StudioDocumentCodec codec = new StudioDocumentCodec();

    @Test void initialDraftIsStructuralButIncomplete() {
        JsonNode initial = codec.initial("test_flow", "Test \"Flow\"\nName");
        codec.requireDraft(initial, "test_flow", "1");
        assertThat(codec.validate(initial).valid()).isFalse();
        assertThat(codec.validate(initial).issues()).anySatisfy(issue ->
                assertThat(issue.stageId()).isEqualTo("default_stage"));
    }

    @Test void checksumMatchesD0Vectors() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Path.of("../docs/contracts/studio-m1/fixtures/valid/checksum-vectors.json").toFile());
        for (JsonNode vector : vectors) {
            String json = vector.has("file")
                    ? Files.readString(Path.of("../docs/contracts/studio-m1/fixtures/valid", vector.path("file").asText()))
                    : vector.path("rawJson").asText();
            assertThat(codec.checksum(codec.parse(json))).as(vector.toString())
                    .isEqualTo(vector.path("checksum").asText());
        }
    }

    @Test void rejectsDuplicateKeysAndUnsafeDecimals() {
        assertThatThrownBy(() -> codec.parse("{\"x\":1,\"x\":2}"))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-DEF-SCHEMA");
        assertThatThrownBy(() -> codec.parse("{\"x\":1e1001}"))
                .isInstanceOf(StudioProblem.class);
        assertThatThrownBy(() -> codec.parse("{\"x\":9007199254740993,\"n\":1e-1001}"))
                .isInstanceOf(StudioProblem.class);
    }
    @Test void betweenBinLocationSurvivesStageNodeCharacteristicAndBinReordering() throws Exception {
        for (boolean reordered : new boolean[] {false, true}) {
            ObjectNode document = orderDocument();
            ObjectNode stage = byId((ArrayNode) document.path("stages"), "stageId", "s_score");
            ObjectNode node = byId((ArrayNode) stage.path("nodes"), "nodeId", "buyer_score");
            ArrayNode characteristics = (ArrayNode) node.path("config").path("characteristics");
            ObjectNode characteristic = byId(characteristics, "id", "c_refund");
            ArrayNode bins = (ArrayNode) characteristic.path("bins");
            ObjectNode bin = byId(bins, "id", "b_refund_high");
            ((ObjectNode) bin.path("condition")).put("op", "between");
            if (reordered) {
                reverse((ArrayNode) document.path("stages"));
                reverse((ArrayNode) stage.path("nodes"));
                reverse(characteristics);
                reverse(bins);
            }
            var issue = codec.validate(document).issues().getFirst();
            assertThat(issue.stageId()).isEqualTo("s_score");
            assertThat(issue.nodeId()).isEqualTo("buyer_score");
            assertThat(issue.itemId()).isEqualTo("b_refund_high");
            assertThat(issue.pointer()).isEqualTo(reordered
                    ? "/stages/3/nodes/1/config/characteristics/0/bins/1/condition/value"
                    : "/stages/2/nodes/0/config/characteristics/1/bins/0/condition/value");
            assertThat(document.at(issue.pointer())).isEqualTo(bin.path("condition").path("value"));
        }
    }

    @Test void parallelOutputLocationUsesActualProducerAndInputOrder() throws Exception {
        for (boolean reordered : new boolean[] {false, true}) {
            ObjectNode document = orderDocument();
            ObjectNode stage = byId((ArrayNode) document.path("stages"), "stageId", "s_score");
            ObjectNode score = byId((ArrayNode) stage.path("nodes"), "nodeId", "buyer_score");
            ObjectNode table = byId((ArrayNode) stage.path("nodes"), "nodeId", "order_segment");
            score.put("nodeId", "producer_a");
            table.put("nodeId", "producer_b");
            ((ObjectNode) score.path("config")).put("outputKey", "segment");
            if (reordered) {
                reverse((ArrayNode) document.path("stages"));
                reverse((ArrayNode) stage.path("nodes"));
                score.put("order", 2);
                table.put("order", 1);
            }
            var issue = codec.validate(document).issues().getFirst();
            assertThat(issue.stageId()).isEqualTo("s_score");
            assertThat(issue.nodeId()).isEqualTo(reordered ? "producer_a" : "producer_b");
            assertThat(issue.itemId()).isNull();
            assertThat(issue.pointer()).isEqualTo(reordered
                    ? "/stages/3/nodes/1/config/outputKey" : "/stages/2/nodes/1/config/outputKey");
            assertThat(document.at(issue.pointer()).asText()).isEqualTo("segment");
        }
    }

    @Test void nestedRuleConditionReportsChildValueAndRuleId() throws Exception {
        ObjectNode document = orderDocument();
        ObjectNode rule = (ObjectNode) document.at("/stages/0/nodes/1/config/rules/0");
        JsonNode condition = rule.path("condition").deepCopy();
        ((ObjectNode) condition).put("op", "between");
        ObjectNode group = rule.putObject("condition");
        group.put("kind", "all");
        group.putArray("children").add(condition);
        var issue = codec.validate(document).issues().getFirst();
        assertThat(issue.pointer()).isEqualTo("/stages/0/nodes/1/config/rules/0/condition/children/0/value");
        assertThat(issue.itemId()).isEqualTo("r_amount");
    }

    private ObjectNode orderDocument() throws Exception {
        return (ObjectNode) codec.parse(Files.readString(Path.of(
                "../docs/contracts/studio-m1/fixtures/valid/order-admission.definition.json")));
    }

    private static ObjectNode byId(ArrayNode items, String field, String id) {
        for (JsonNode item : items) if (id.equals(item.path(field).asText())) return (ObjectNode) item;
        throw new AssertionError("Missing " + id);
    }

    private static void reverse(ArrayNode items) {
        java.util.List<JsonNode> copy = new java.util.ArrayList<>();
        items.forEach(copy::add);
        java.util.Collections.reverse(copy);
        items.removeAll();
        copy.forEach(items::add);
    }

}
