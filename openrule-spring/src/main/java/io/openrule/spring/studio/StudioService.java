package io.openrule.spring.studio;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.core.studio.StudioEngine;
import io.openrule.core.studio.StudioModel;
import io.openrule.spring.studio.StudioStore.Version;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Flow lifecycle and exact-version execution. The JDBC adapter owns atomic writes. */
public final class StudioService {
    private final StudioStore store;
    private final StudioDocumentCodec codec;
    private final StudioEngine engine;
    private final long maxTimeoutMillis;

    public StudioService(StudioStore store, StudioDocumentCodec codec, StudioEngine engine,
                         long maxTimeoutMillis) {
        this.store = store;
        this.codec = codec;
        this.engine = engine;
        this.maxTimeoutMillis = maxTimeoutMillis;
    }

    public StudioStore.Page<StudioStore.FlowHead> flows(String q, int page, int pageSize) {
        page(page, pageSize);
        return store.flows(q, page, pageSize);
    }

    public StudioStore.Page<Version> versions(String flowId, int page, int pageSize) {
        page(page, pageSize);
        return store.versions(flowId, page, pageSize);
    }

    public Version version(String flowId, String version) {
        Version found = store.version(flowId, positive(version));
        if (found.status().equals("PUBLISHED")
                && !codec.checksum(codec.parse(found.documentJson())).equals(found.checksum()))
            throw new StudioProblem(500, "OR-DEF-CHECKSUM", "Published definition checksum mismatch");
        return found;
    }

    public Version createFlow(JsonNode body) {
        fields(body, Set.of("flowId", "flowName"), Set.of("flowId", "flowName"));
        String flowId = text(body, "flowId");
        String name = text(body, "flowName");
        if (!flowId.matches("[a-z][a-z0-9_-]{2,63}") || name.isBlank())
            throw bad("Invalid flowId or flowName");
        JsonNode document = codec.initial(flowId, name);
        codec.requireDraft(document, flowId, "1");
        return store.createFlow(flowId, name, codec.json(document));
    }

    public Version createDraft(String flowId, JsonNode body) {
        fields(body, Set.of("sourceVersion"), Set.of("sourceVersion"));
        String source = text(body, "sourceVersion");
        version(flowId, source);
        return store.createDraft(flowId, positive(source), codec);
    }

    public Version save(String flowId, String version, JsonNode body) {
        fields(body, Set.of("expectedRevision", "document"), Set.of("expectedRevision", "document"));
        long revision = positive(text(body, "expectedRevision"));
        long v = positive(version);
        JsonNode document = body.path("document");
        codec.requireDraft(document, flowId, version);
        return store.save(flowId, v, revision, document.path("flowName").asText(), codec.json(document));
    }

    public StudioDocumentCodec.Report validate(String flowId, String version, JsonNode body) {
        fields(body, Set.of("document"), Set.of("document"));
        store.version(flowId, positive(version));
        JsonNode document = body.path("document");
        codec.requireDraft(document, flowId, version);
        return codec.validate(document);
    }

    public Version publish(String flowId, String version, JsonNode body) {
        fields(body, Set.of("expectedRevision", "changeNote"), Set.of("expectedRevision", "changeNote"));
        String note = text(body, "changeNote");
        if (note.isBlank()) throw bad("changeNote is required");
        return store.publish(flowId, positive(version), positive(text(body, "expectedRevision")), note, codec);
    }

    public Map<String, Object> execute(String flowId, String version, JsonNode body, boolean simulate) {
        Set<String> allowed = simulate
                ? Set.of("requestId", "bizId", "facts", "timeoutMillis", "expectedRevision")
                : Set.of("requestId", "bizId", "facts", "timeoutMillis");
        fields(body, allowed, allowed);
        String requestId = text(body, "requestId"), bizId = text(body, "bizId");
        if (requestId.isBlank() || bizId.isBlank() || requestId.length() > 128 || bizId.length() > 128)
            throw bad("requestId and bizId must be 1..128 characters");
        JsonNode timeoutNode = body.path("timeoutMillis");
        if (!timeoutNode.isIntegralNumber() || !timeoutNode.canConvertToLong() || timeoutNode.longValue() < 1
                || timeoutNode.longValue() > maxTimeoutMillis) throw bad("Invalid timeoutMillis");
        JsonNode factsNode = body.path("facts");
        if (!factsNode.isObject()) throw bad("facts must be an object");
        Version stored = version(flowId, version);
        if (simulate) {
            long revision = positive(text(body, "expectedRevision"));
            if (revision != stored.revision())
                throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
        } else if (!stored.status().equals("PUBLISHED"))
            throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "LIVE requires a published version");
        JsonNode document = codec.parse(stored.documentJson());
        if (stored.status().equals("PUBLISHED") && !codec.checksum(document).equals(stored.checksum()))
            throw new StudioProblem(500, "OR-DEF-CHECKSUM", "Published definition checksum mismatch");
        StudioModel.Flow executable = codec.executable(document);
        String checksum = stored.status().equals("PUBLISHED") ? stored.checksum() : codec.checksum(document);
        @SuppressWarnings("unchecked") Map<String, Object> facts = (Map<String, Object>) codec.value(factsNode);
        StudioModel.Result result = engine.execute(executable, facts, timeoutNode.longValue());
        String traceId = java.util.UUID.randomUUID().toString();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("traceId", traceId);
        response.put("bizId", bizId);
        response.put("purpose", simulate ? "SIMULATE" : "LIVE");
        response.put("flowId", flowId);
        response.put("version", version);
        response.put("revision", Long.toString(stored.revision()));
        response.put("definitionChecksum", checksum);
        response.put("status", result.status());
        Map<String, Object> decision = null;
        if (result.decision() != null) {
            decision = new LinkedHashMap<>();
            decision.put("decisionCode", result.decision().decisionCode());
            decision.put("reasonCodes", result.decision().reasonCodes());
            decision.put("score", result.decision().score() == null ? null : decimal(result.decision().score()));
            decision.put("terminalNodeId", result.decision().terminalNodeId());
        }
        response.put("decision", decision);
        response.put("variables", result.variables());
        response.put("stageResults", result.stageResults().stream().map(stage -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stageId", stage.stageId());
            item.put("status", stage.status().name());
            item.put("skipReason", stage.skipReason() == null ? null : stage.skipReason().name());
            return item;
        }).toList());
        response.put("nodeResults", result.nodeResults().stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stageId", node.stageId());
            item.put("nodeId", node.nodeId());
            item.put("type", node.type());
            item.put("status", node.status().name());
            item.put("skipReason", node.skipReason() == null ? null : node.skipReason().name());
            item.put("hit", node.hit());
            item.put("reasonCodes", node.reasonCodes());
            item.put("outputs", node.outputs());
            item.put("details", node.details().stream().map(detail -> {
                Map<String, Object> value = new LinkedHashMap<>();
                if (detail instanceof StudioModel.RuleDetail rule) {
                    value.put("kind", rule.kind());
                    value.put("ruleId", rule.ruleId());
                    value.put("reasonCode", rule.reasonCode());
                    value.put("value", rule.value());
                } else if (detail instanceof StudioModel.ScoreDetail score) {
                    value.put("kind", score.kind());
                    value.put("characteristicId", score.characteristicId());
                    value.put("binId", score.binId());
                    value.put("score", score.score());
                    value.put("reasonCode", score.reasonCode());
                }
                return value;
            }).toList());
            String failureCode = node.status() == StudioModel.Status.TIMED_OUT ? "OR-NODE-TIMEOUT"
                    : node.failure() != null && node.failure().startsWith("OR-ENGINE-FAILURE")
                    ? "OR-ENGINE-FAILURE" : "OR-NODE-FAILED";
            item.put("failure", node.failure() == null ? null : Map.of("code", failureCode, "message", node.failure()));
            item.put("elapsedMillis", node.elapsedMillis());
            return item;
        }).toList());
        response.put("elapsedMillis", result.elapsedMillis());
        if (!result.status().equals("DECIDED")) {
            int status = result.errorCode().equals("OR-EXECUTION-TIMEOUT") ? 504
                    : result.errorCode().equals("OR-ENGINE-OVERLOADED") ? 503 : 500;
            throw new StudioProblem(status, result.errorCode(), "Execution failed", List.of(), response);
        }
        return response;
    }

    public StudioDocumentCodec.Report validation(Version version) {
        return codec.validate(codec.parse(version.documentJson()));
    }

    public JsonNode document(Version version) { return codec.parse(version.documentJson()); }

    private static String decimal(BigDecimal number) {
        BigDecimal value = number.stripTrailingZeros();
        return value.signum() == 0 ? "0" : value.toPlainString();
    }

    private static void page(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw bad("Invalid pagination");
    }

    private static long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw bad("Expected positive decimal string");
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw bad("Version or revision is out of range"); }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual()) throw bad(key + " must be a string");
        return value.textValue();
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> allowed) {
        if (!node.isObject()) throw bad("Request must be an object");
        for (String key : required) if (!node.has(key)) throw bad("Missing field: " + key);
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) throw bad("Unknown request field");
    }

    private static StudioProblem bad(String message) { return new StudioProblem(400, "OR-REQUEST-INVALID", message); }
}
