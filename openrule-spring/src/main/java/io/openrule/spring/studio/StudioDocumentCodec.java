package io.openrule.spring.studio;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.openrule.core.studio.StudioCompiler;
import io.openrule.core.studio.StudioModel.*;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict v2 JSON boundary; Core remains independent of Jackson and Schema libraries. */
public final class StudioDocumentCodec {
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE]([+-]?[0-9]+))?");
    private final ObjectMapper mapper;
    private final Schema draftSchema;
    private final Schema definitionSchema;

    public record Issue(String code, String severity, String message, String pointer,
                        String stageId, String nodeId, String itemId) { }
    public record Report(boolean valid, List<Issue> issues) { }

    public StudioDocumentCodec() {
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        mapper = new ObjectMapper(factory).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (InputStream draft = resource("studio-m1/schema/draft.schema.json");
             InputStream definition = resource("studio-m1/schema/definition.schema.json")) {
            draftSchema = registry.getSchema(draft);
            definitionSchema = registry.getSchema(definition);
        } catch (IOException ex) { throw new IllegalStateException("Studio schema unavailable", ex); }
    }

    private static InputStream resource(String path) {
        InputStream input = StudioDocumentCodec.class.getClassLoader().getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing schema resource: " + path);
        return input;
    }

    public JsonNode parse(String json) {
        if (json == null) throw new StudioProblem(400, "OR-DEF-SCHEMA", "Missing JSON document");
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT
                        || parser.currentToken() == JsonToken.VALUE_NUMBER_INT) checkNumber(parser.getText());
            }
            JsonNode tree;
            try (JsonParser treeParser = mapper.getFactory().createParser(json)) {
                tree = mapper.readTree(treeParser);
                if (treeParser.nextToken() != null) throw new IllegalArgumentException("Trailing JSON content");
            }
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException("Document must be an object");
            return tree;
        } catch (Exception ex) {
            throw new StudioProblem(400, "OR-DEF-SCHEMA", "Invalid JSON: " + ex.getMessage());
        }
    }

    private void checkNumber(String lexical) {
        Matcher matcher = NUMBER.matcher(lexical);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid decimal literal");
        String digits = lexical.replaceFirst("[eE].*$", "").replaceAll("[^0-9]", "")
                .replaceFirst("^0+", "").replaceFirst("0+$", "");
        if (digits.length() > 128) throw new IllegalArgumentException("Decimal has more than 128 significant digits");
        if (matcher.group(1) != null && new java.math.BigInteger(matcher.group(1)).abs()
                .compareTo(java.math.BigInteger.valueOf(1000)) > 0)
            throw new IllegalArgumentException("Decimal exponent exceeds 1000");
    }

    public void requireDraft(JsonNode document, String flowId, String version) {
        List<Issue> issues = schemaIssues(draftSchema, document, "OR-DEF-SCHEMA");
        if (!issues.isEmpty()) throw new StudioProblem(400, "OR-DEF-SCHEMA", "Invalid draft structure", issues);
        if (!flowId.equals(document.path("flowId").asText()) || !version.equals(document.path("version").asText()))
            throw new StudioProblem(400, "OR-DEF-SCHEMA", "Document identity does not match resource path");
    }

    public Report validate(JsonNode document) {
        List<Issue> issues = schemaIssues(definitionSchema, document, "OR-DEF-VALIDATION");
        if (!issues.isEmpty()) return new Report(false, issues);
        try {
            new StudioCompiler().compile(bind(document));
        } catch (RuntimeException ex) {
            return new Report(false, List.of(semanticIssue(document, ex.getMessage())));
        }
        return new Report(true, List.of());
    }

    private Issue semanticIssue(JsonNode document, String message) {
        int stageIndex = 0;
        for (JsonNode stage : document.path("stages")) {
            int nodeIndex = 0;
            for (JsonNode node : stage.path("nodes")) {
                String nodeId = node.path("nodeId").asText();
                int characteristicIndex = 0;
                for (JsonNode characteristic : node.path("config").path("characteristics")) {
                    String itemId = characteristic.path("id").asText();
                    if (message != null && !itemId.isBlank() && message.contains(itemId))
                        return new Issue("OR-DEF-VALIDATION", "ERROR", message,
                                "/stages/" + stageIndex + "/nodes/" + nodeIndex
                                        + "/config/characteristics/" + characteristicIndex,
                                stage.path("stageId").asText(), nodeId, itemId);
                    characteristicIndex++;
                }
                if (message != null && message.contains(nodeId))
                    return new Issue("OR-DEF-VALIDATION", "ERROR", message,
                            "/stages/" + stageIndex + "/nodes/" + nodeIndex,
                            stage.path("stageId").asText(), nodeId, null);
                nodeIndex++;
            }
            stageIndex++;
        }
        return new Issue("OR-DEF-VALIDATION", "ERROR", message == null ? "Invalid definition" : message,
                stageIndex == 0 ? "" : "/stages/" + (stageIndex - 1), null, null, null);
    }

    public Flow executable(JsonNode document) {
        Report report = validate(document);
        if (!report.valid()) throw new StudioProblem(400, "OR-DEF-VALIDATION", "Definition is not executable", report.issues());
        return new StudioCompiler().compile(bind(document));
    }

    private List<Issue> schemaIssues(Schema schema, JsonNode document, String code) {
        return schema.validate(document).stream().map(error -> locatedIssue(document, code,
                error.getMessage(), error.getInstanceLocation().toString())).toList();
    }

    private Issue locatedIssue(JsonNode document, String code, String message, String location) {
        String pointer = location.startsWith("#") ? location.substring(1) : location;
        if (pointer.equals("$")) pointer = "";
        JsonNode stage = null, node = null, item = null;
        String[] parts = pointer.split("/");
        try {
            if (parts.length > 2 && parts[1].equals("stages"))
                stage = document.path("stages").get(Integer.parseInt(parts[2]));
            if (stage != null && parts.length > 4 && parts[3].equals("nodes"))
                node = stage.path("nodes").get(Integer.parseInt(parts[4]));
            if (node != null) {
                for (int i = 5; i + 1 < parts.length; i++) {
                    if (parts[i].equals("rules") || parts[i].equals("bins") || parts[i].equals("characteristics")) {
                        JsonNode candidate = document.at(pointer.substring(0, pointer.indexOf("/" + parts[i])
                                + parts[i].length() + 1) + "/" + parts[i + 1]);
                        if (candidate.has("id")) item = candidate;
                    }
                }
            }
        } catch (RuntimeException ignored) { /* malformed location stays at its JSON Pointer */ }
        return new Issue(code, "ERROR", message, pointer,
                stage == null ? null : stage.path("stageId").asText(null),
                node == null ? null : node.path("nodeId").asText(null),
                item == null ? null : item.path("id").asText(null));
    }

    public Flow bind(JsonNode root) {
        List<Stage> stages = new ArrayList<>();
        for (JsonNode stage : root.path("stages")) {
            List<Node> nodes = new ArrayList<>();
            for (JsonNode node : stage.path("nodes")) {
                String type = node.path("type").asText();
                JsonNode config = node.path("config");
                Config bound;
                if (type.equals("openrule.operator")) bound = new Operator(condition(config.path("condition")),
                        config.path("outputKey").asText(null), config.path("reasonCode").asText(null));
                else if (type.equals("openrule.terminal")) {
                    List<String> reasons = new ArrayList<>();
                    config.path("reasonCodes").forEach(v -> reasons.add(v.asText()));
                    bound = new Terminal(config.path("decisionCode").asText(null), reasons,
                            config.path("scoreRef").isNull() ? null : config.path("scoreRef").asText(null));
                } else if (type.equals("openrule.rule-set"))
                    bound = new RuleSet(config.path("outputKey").asText(null),
                            config.path("matchPolicy").asText(), rules(config.path("rules")));
                else if (type.equals("openrule.decision-table"))
                    bound = new DecisionTable(config.path("outputKey").asText(null),
                            config.path("hitPolicy").asText(), rules(config.path("rules")));
                else if (type.equals("openrule.scorecard")) {
                    List<Characteristic> characteristics = new ArrayList<>();
                    for (JsonNode characteristic : config.path("characteristics")) {
                        List<Bin> bins = new ArrayList<>();
                        for (JsonNode bin : characteristic.path("bins"))
                            bins.add(new Bin(bin.path("id").asText(), condition(bin.path("condition")),
                                    bin.path("score").decimalValue(), bin.path("reasonCode").asText()));
                        characteristics.add(new Characteristic(characteristic.path("id").asText(),
                                characteristic.path("name").asText(), bins));
                    }
                    bound = new Scorecard(config.path("outputKey").asText(null), characteristics);
                } else throw new IllegalArgumentException("Unknown node type: " + type);
                nodes.add(new Node(node.path("nodeId").asText(), node.path("order").asInt(), type,
                        node.path("configVersion").asInt(), node.path("timeoutMillis").asLong(),
                        node.has("failPolicy") ? FailPolicy.valueOf(node.path("failPolicy").asText()) : null, bound));
            }
            stages.add(new Stage(stage.path("stageId").asText(), stage.path("order").asInt(),
                    Mode.valueOf(stage.path("executionMode").asText()),
                    stage.path("when").isNull() ? null : condition(stage.path("when")),
                    stage.path("timeoutMillis").asLong(), nodes));
        }
        return new Flow(root.path("schemaVersion").asInt(), root.path("flowId").asText(),
                root.path("flowName").asText(), root.path("version").asText(),
                root.path("description").asText(), stages);
    }

    private List<Rule> rules(JsonNode input) {
        List<Rule> rules = new ArrayList<>();
        for (JsonNode rule : input)
            rules.add(new Rule(rule.path("id").asText(), condition(rule.path("condition")),
                    value(rule.path("value")), rule.path("reasonCode").asText()));
        return rules;
    }

    private Condition condition(JsonNode tree) {
        String kind = tree.path("kind").asText();
        if (kind.equals("all") || kind.equals("any") || kind.equals("not")) {
            List<Condition> children = new ArrayList<>();
            tree.path("children").forEach(child -> children.add(condition(child)));
            return new Group(kind, children);
        }
        JsonNode source = tree.path("ref");
        Ref ref = new Ref(Source.valueOf(source.path("source").asText()), source.path("pointer").asText());
        return kind.equals("compare") ? new Compare(ref, tree.path("op").asText(),
                value(tree.path("value"))) : new Presence(kind, ref);
    }

    public String json(JsonNode tree) {
        try { return mapper.writeValueAsString(tree); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public Object value(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) return node.textValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(child -> values.add(value(child)));
            return values;
        }
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), value(entry.getValue())));
        return values;
    }

    public String checksum(JsonNode tree) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(tree).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private String canonical(JsonNode node) {
        if (node.isNull()) return "null";
        if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.signum() == 0) return "0";
            return value.toPlainString();
        }
        if (node.isTextual()) return json(node);
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(canonical(value)));
            return "[" + String.join(",", values) + "]";
        }
        List<String> entries = new ArrayList<>();
        node.fieldNames().forEachRemaining(key -> entries.add(key));
        entries.sort(String::compareTo);
        List<String> fields = new ArrayList<>();
        for (String key : entries) fields.add(json(mapper.getNodeFactory().textNode(key)) + ":" + canonical(node.get(key)));
        return "{" + String.join(",", fields) + "}";
    }

    public ObjectNode initial(String flowId, String flowName) {
        String json = "{\"schemaVersion\":2,\"flowId\":\"" + flowId + "\",\"flowName\":"
                + json(mapper.getNodeFactory().textNode(flowName)) + ",\"version\":\"1\","
                + "\"description\":\"\",\"stages\":[{\"stageId\":\"default_stage\",\"stageName\":\"默认决策\","
                + "\"order\":1,\"executionMode\":\"SERIAL\",\"when\":null,\"timeoutMillis\":1000,"
                + "\"nodes\":[{\"nodeId\":\"default_terminal\",\"nodeName\":\"默认终点\",\"order\":1,"
                + "\"type\":\"openrule.terminal\",\"configVersion\":1,\"timeoutMillis\":1000,"
                + "\"config\":{\"decisionCode\":null,\"reasonCodes\":[],\"scoreRef\":null}}]}]}";
        return (ObjectNode) parse(json);
    }
}
