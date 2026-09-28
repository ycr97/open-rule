package io.openrule.core.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.openrule.core.studio.StudioModel.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B1 checks the shared D0 definitions; B3 adds execution of the deferred node types. */
class StudioFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @Test void compilesSharedOrderAdmissionFixture() throws IOException {
        Flow compiled = new StudioCompiler().compile(read("valid/order-admission.definition.json"));
        assertThat(compiled.stages()).hasSize(6);
        assertThat(compiled.stages().get(2).mode()).isEqualTo(Mode.PARALLEL);
        assertThat(compiled.stages().getLast().nodes().getFirst().type()).isEqualTo("openrule.terminal");
    }

    @Test void rejectsSharedSemanticFixtures() {
        for (String name : List.of("future-node", "parallel-output", "parallel-terminal", "terminal-missing", "bad-pointer")) {
            try {
                new StudioCompiler().compile(read("invalid/" + name + ".definition.json"));
                throw new AssertionError("Accepted invalid fixture: " + name);
            } catch (IllegalArgumentException expected) {
                assertThat(expected.getMessage()).contains("OR-DEF-VALIDATION");
            } catch (IOException ex) {
                throw new AssertionError(name, ex);
            }
        }
    }

    private Flow read(String name) throws IOException {
        JsonNode root = mapper.readTree(Path.of("../docs/contracts/studio-m1/fixtures", name).toFile());
        List<Stage> stages = new ArrayList<>();
        for (JsonNode stage : root.required("stages")) {
            List<Node> nodes = new ArrayList<>();
            for (JsonNode node : stage.required("nodes")) {
                String type = node.required("type").asText();
                JsonNode config = node.required("config");
                Config mapped;
                if (type.equals("openrule.operator"))
                    mapped = new Operator(condition(config.required("condition")),
                            config.required("outputKey").asText(), config.required("reasonCode").asText());
                else if (type.equals("openrule.terminal")) {
                    List<String> reasons = new ArrayList<>();
                    config.required("reasonCodes").forEach(item -> reasons.add(item.asText()));
                    mapped = new Terminal(config.required("decisionCode").isNull() ? null
                            : config.required("decisionCode").asText(), reasons,
                            config.required("scoreRef").isNull() ? null : config.required("scoreRef").asText());
                } else {
                    List<Condition> conditions = new ArrayList<>();
                    if (type.equals("openrule.scorecard")) {
                        for (JsonNode characteristic : config.required("characteristics"))
                            for (JsonNode bin : characteristic.required("bins"))
                                conditions.add(condition(bin.required("condition")));
                    } else if (config.has("rules")) {
                        for (JsonNode rule : config.required("rules"))
                            conditions.add(condition(rule.required("condition")));
                    }
                    mapped = new Deferred(config.path("outputKey").asText(null), conditions);
                }
                nodes.add(new Node(node.required("nodeId").asText(), node.required("order").asInt(),
                        type, node.required("configVersion").asInt(), node.required("timeoutMillis").asLong(),
                        node.has("failPolicy") ? FailPolicy.valueOf(node.required("failPolicy").asText()) : null, mapped));
            }
            stages.add(new Stage(stage.required("stageId").asText(), stage.required("order").asInt(),
                    Mode.valueOf(stage.required("executionMode").asText()),
                    stage.required("when").isNull() ? null : condition(stage.required("when")),
                    stage.required("timeoutMillis").asLong(), nodes));
        }
        return new Flow(root.required("schemaVersion").asInt(), root.required("flowId").asText(),
                root.required("flowName").asText(), root.required("version").asText(),
                root.required("description").asText(), stages);
    }

    private Condition condition(JsonNode json) {
        String kind = json.required("kind").asText();
        if (List.of("all", "any", "not").contains(kind)) {
            List<Condition> children = new ArrayList<>();
            json.required("children").forEach(child -> children.add(condition(child)));
            return new Group(kind, children);
        }
        JsonNode ref = json.required("ref");
        Ref mappedRef = new Ref(Source.valueOf(ref.required("source").asText()), ref.required("pointer").asText());
        if (kind.equals("compare"))
            return new Compare(mappedRef, json.required("op").asText(), mapper.convertValue(json.required("value"), Object.class));
        return new Presence(kind, mappedRef);
    }
}
