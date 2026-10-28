package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioProblem;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;
import static io.openrule.spring.fact.FactConfiguration.*;

/** Host configuration schema boundary and translation to the OSS immutable snapshots. */
public final class FactDocuments {
    private final StudioDocumentCodec codec;
    private final Map<String, Schema> schemas;
    public FactDocuments(StudioDocumentCodec codec) {
        this.codec = codec;
        Map<String, Schema> compiled = new HashMap<>();
        try (var input = getClass().getClassLoader().getResourceAsStream("studio-m2/schema/m2.schema.json")) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(Objects.requireNonNull(input));
            var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
            for (String name : List.of("FactDocument", "ProviderDocument", "FactUpdate", "ProviderUpdate",
                    "FactSimulationRequest", "FactExecutionRequest", "MockTestRequest")) {
                ObjectNode schema = mapper.createObjectNode();
                schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
                schema.set("$defs", root.path("$defs")); schema.put("$ref", "#/$defs/" + name);
                compiled.put(name, registry.getSchema(new ByteArrayInputStream(mapper.writeValueAsBytes(schema))));
            }
        } catch (Exception ex) { throw new IllegalStateException("M2 schema unavailable", ex); }
        schemas = Map.copyOf(compiled);
    }
    public JsonNode parse(String body) {
        try { return codec.parse(body); }
        catch (StudioProblem ex) { throw new StudioProblem(400, "OR-REQUEST-INVALID", "Invalid JSON"); }
    }
    public void validate(String type, JsonNode document) {
        var issues = schemas.get(type).validate(document);
        if (!issues.isEmpty()) {
            String code = type.endsWith("Document") || type.endsWith("Update") ? "OR-FACT-CONFIG" : "OR-REQUEST-INVALID";
            throw new StudioProblem(400, code, "Invalid request structure", issues.stream().map(issue ->
                    new StudioDocumentCodec.Issue(code, "ERROR", "Invalid field structure", issue.getInstanceLocation().toString(), null, null, null)).toList());
        }
    }
    public void configuration(JsonNode d, boolean fact, String base) {
        if (fact) {
            for (String token : tokens(d.path("path").asText()))
                if (token.isEmpty() || token.matches("[0-9]+") || Set.of("__proto__", "constructor", "prototype").contains(token))
                    throw config("Invalid object leaf path", base + "/path");
        } else if (d.path("type").asText().equals("HTTP")) {
            if (d.path("connectTimeoutMillis").asLong() > d.path("readTimeoutMillis").asLong())
                throw config("connectTimeoutMillis exceeds readTimeoutMillis", base + "/connectTimeoutMillis");
            try { provider(d, "1"); }
            catch (IllegalArgumentException ex) { throw config("Invalid HTTP configuration", base + "/endpoint"); }
        }
    }
    public Provider provider(JsonNode d, String revision) {
        Map<String, Mapping> mappings = new LinkedHashMap<>();
        d.path("requestMap").fields().forEachRemaining(e -> mappings.put(e.getKey(),
                e.getValue().path("source").asText().equals("FACT") ? new Fact(e.getValue().path("pointer").asText())
                        : new Constant(codec.value(e.getValue().path("value")))));
        JsonNode mock = d.path("mock");
        MockOutcome outcome = MockOutcome.valueOf(mock.path("outcome").asText());
        return new Provider(d.path("providerId").asText(), revision, d.path("enabled").asBoolean(), mappings,
                new MockSettings(outcome, outcome == MockOutcome.RESPONSE ? map(mock.path("response")) : null),
                d.path("type").asText().equals("MOCK") ? null : new HttpSettings(URI.create(d.path("endpoint").asText()),
                        d.path("connectTimeoutMillis").asLong(), d.path("readTimeoutMillis").asLong(),
                        d.path("secretRef").isNull() ? null : d.path("secretRef").asText(),
                        d.path("readOnly").asBoolean(), d.path("allowSimulationHttp").asBoolean()));
    }
    public Binding binding(JsonNode d, String revision) {
        JsonNode b = d.path("binding");
        return new Binding(d.path("factId").asText(), revision, d.path("path").asText(), ValueType.from(d.path("type").asText()),
                d.path("sensitive").asBoolean(), b.isNull() ? null : b.path("providerId").asText(), b.isNull() ? null : b.path("responsePointer").asText());
    }
    @SuppressWarnings("unchecked") public Map<String, Object> map(JsonNode node) { return (Map<String, Object>) codec.value(node); }
    public static long positive(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException ex) { throw new StudioProblem(400, "OR-REQUEST-INVALID", "Invalid positive BIGINT string"); }
    }
    public static void id(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9_-]{2,63}")) throw new StudioProblem(400, "OR-REQUEST-INVALID", "Invalid identifier");
    }
    public static List<String> tokens(String pointer) {
        return Arrays.stream(pointer.substring(1).split("/", -1)).map(s -> s.replace("~1", "/").replace("~0", "~")).toList();
    }
    public static StudioProblem config(String message, String pointer) {
        return issue(400, "OR-FACT-CONFIG", message, pointer);
    }
    public static StudioProblem issue(int status, String code, String message, String pointer) {
        return new StudioProblem(status, code, message, List.of(new StudioDocumentCodec.Issue(code, "ERROR", message, pointer, null, null, null)));
    }
}
