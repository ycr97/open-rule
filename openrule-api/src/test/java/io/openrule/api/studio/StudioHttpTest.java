package io.openrule.api.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.openrule.core.studio.StudioEngine;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioProblem;
import io.openrule.spring.studio.StudioService;
import io.openrule.spring.studio.StudioStore;
import io.openrule.spring.studio.StudioStore.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StudioHttpTest {
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final StudioDocumentCodec codec = new StudioDocumentCodec();
    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeStore store = new FakeStore();
    private final MockMvc mvc = standaloneSetup(new StudioController(
            new StudioService(store, codec, new StudioEngine(pool), 30000), codec))
            .setControllerAdvice(new StudioProblemAdvice()).build();

    @AfterEach void close() { pool.shutdownNow(); }

    @Test void fullHttpLifecycleUsesBareContractDtos() throws Exception {
        JsonNode created = call(post("/api/v2/admin/flows"), "{\"flowId\":\"test_flow\",\"flowName\":\"Test\"}", 201);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("revision").asText()).isEqualTo("1");
        assertThat(created.path("validation").path("valid").asBoolean()).isFalse();

        ObjectNode document = (ObjectNode) created.path("document").deepCopy();
        ObjectNode terminal = (ObjectNode) document.path("stages").get(0).path("nodes").get(0).path("config");
        terminal.put("decisionCode", "APPROVE");
        ((ArrayNode) terminal.path("reasonCodes")).add("OK");
        ObjectNode request = mapper.createObjectNode();
        request.put("expectedRevision", "1");
        request.set("document", document);
        JsonNode validated = call(post("/api/v2/admin/flows/test_flow/versions/1/validations"),
                "{\"document\":" + codec.json(document) + "}", 200);
        assertThat(validated.path("valid").asBoolean()).isTrue();
        JsonNode saved = call(put("/api/v2/admin/flows/test_flow/versions/1"), codec.json(request), 200);
        assertThat(saved.path("revision").asText()).isEqualTo("2");
        assertThat(saved.path("definitionChecksum").isNull()).isTrue();
        JsonNode conflict = call(put("/api/v2/admin/flows/test_flow/versions/1"), codec.json(request), 409);
        assertThat(conflict.path("code").asText()).isEqualTo("OR-REVISION-CONFLICT");

        String execution = "{\"requestId\":\"r1\",\"bizId\":\"b1\",\"facts\":{},\"timeoutMillis\":1000}";
        JsonNode simulation = call(post("/api/v2/admin/flows/test_flow/versions/1/simulations"),
                execution.substring(0, execution.length() - 1) + ",\"expectedRevision\":\"2\"}", 200);
        assertThat(simulation.path("purpose").asText()).isEqualTo("SIMULATE");
        assertThat(simulation.path("decision").path("decisionCode").asText()).isEqualTo("APPROVE");
        assertThat(simulation.path("definitionChecksum").asText()).startsWith("sha256:");
        JsonNode draftLive = call(post("/api/v2/flows/test_flow/versions/1/executions"), execution, 409);
        assertThat(draftLive.path("code").asText()).isEqualTo("OR-LIFECYCLE-CONFLICT");

        JsonNode published = call(post("/api/v2/admin/flows/test_flow/versions/1/publish"),
                "{\"expectedRevision\":\"2\",\"changeNote\":\"reviewed\"}", 200);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("revision").asText()).isEqualTo("3");
        assertThat(published.path("definitionChecksum").asText()).startsWith("sha256:");
        JsonNode live = call(post("/api/v2/flows/test_flow/versions/1/executions"), execution, 200);
        assertThat(live.path("purpose").asText()).isEqualTo("LIVE");
        assertThat(live.path("decision").path("decisionCode").asText()).isEqualTo("APPROVE");
        assertThat(live.path("version").asText()).isEqualTo("1");
        JsonNode immutable = call(put("/api/v2/admin/flows/test_flow/versions/1"), codec.json(request), 409);
        assertThat(immutable.path("code").asText()).isEqualTo("OR-LIFECYCLE-CONFLICT");
        JsonNode draft = call(post("/api/v2/admin/flows/test_flow/drafts"), "{\"sourceVersion\":\"1\"}", 201);
        assertThat(draft.path("version").asText()).isEqualTo("2");
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        JsonNode list = call(get("/api/v2/admin/flows"), null, 200);
        assertThat(list.path("items").get(0).path("currentDraft").path("version").asText()).isEqualTo("2");
        JsonNode versions = call(get("/api/v2/admin/flows/test_flow/versions"), null, 200);
        assertThat(versions.path("items").get(0).path("version").asText()).isEqualTo("2");
    }

    @Test void rejectsDuplicateKeysAndMalformedPages() throws Exception {
        assertThat(call(post("/api/v2/admin/flows"),
                "{\"flowId\":\"abc\",\"flowId\":\"xyz\",\"flowName\":\"x\"}", 400)
                .path("code").asText()).isEqualTo("OR-DEF-SCHEMA");
        assertThat(call(get("/api/v2/admin/flows?page=0"), null, 400)
                .path("code").asText()).isEqualTo("OR-REQUEST-INVALID");
        assertThat(call(get("/api/v2/admin/flows?page=abc"), null, 400)
                .path("code").asText()).isEqualTo("OR-REQUEST-INVALID");
    }

    @Test void failedPublishLeavesDraftAtSameRevision() throws Exception {
        JsonNode created = call(post("/api/v2/admin/flows"),
                "{\"flowId\":\"test_flow\",\"flowName\":\"Test\"}", 201);
        ObjectNode body = mapper.createObjectNode();
        body.put("expectedRevision", "1");
        body.set("document", created.path("document"));
        call(put("/api/v2/admin/flows/test_flow/versions/1"), codec.json(body), 200);
        JsonNode rejected = call(post("/api/v2/admin/flows/test_flow/versions/1/publish"),
                "{\"expectedRevision\":\"2\",\"changeNote\":\"attempt\"}", 400);
        assertThat(rejected.path("code").asText()).isEqualTo("OR-DEF-VALIDATION");
        JsonNode current = call(get("/api/v2/admin/flows/test_flow/versions/1"), null, 200);
        assertThat(current.path("status").asText()).isEqualTo("DRAFT");
        assertThat(current.path("revision").asText()).isEqualTo("2");
    }

    @Test void corruptedPublishedChecksumIsRejectedOnRead() throws Exception {
        JsonNode created = call(post("/api/v2/admin/flows"),
                "{\"flowId\":\"test_flow\",\"flowName\":\"Test\"}", 201);
        ObjectNode document = (ObjectNode) created.path("document").deepCopy();
        ObjectNode config = (ObjectNode) document.path("stages").get(0).path("nodes").get(0).path("config");
        config.put("decisionCode", "APPROVE");
        ((ArrayNode) config.path("reasonCodes")).add("OK");
        ObjectNode save = mapper.createObjectNode();
        save.put("expectedRevision", "1");
        save.set("document", document);
        call(put("/api/v2/admin/flows/test_flow/versions/1"), codec.json(save), 200);
        call(post("/api/v2/admin/flows/test_flow/versions/1/publish"),
                "{\"expectedRevision\":\"2\",\"changeNote\":\"approved\"}", 200);
        Version published = store.versions.get(1L);
        store.versions.put(1L, new Version(published.flowId(), published.flowName(), published.version(),
                published.status(), published.revision(), published.documentJson(),
                "sha256:" + "0".repeat(64), published.changeNote(), published.updatedAt()));
        assertThat(call(get("/api/v2/admin/flows/test_flow/versions/1"), null, 500)
                .path("code").asText()).isEqualTo("OR-DEF-CHECKSUM");
    }

    @Test void publishesAndExecutesCompleteOrderAdmissionAtExactVersions() throws Exception {
        Path fixtures = Path.of("../docs/contracts/studio-m1/fixtures/valid");
        JsonNode cases = mapper.readTree(fixtures.resolve("order-admission.cases.json").toFile());
        call(post("/api/v2/admin/flows"), "{\"flowId\":\"order_admission\",\"flowName\":\"订单准入\"}", 201);
        JsonNode base = codec.parse(Files.readString(fixtures.resolve("order-admission.definition.json")));
        saveOrder(base, "1", "1");
        JsonNode validation = call(post("/api/v2/admin/flows/order_admission/versions/1/validations"),
                "{\"document\":" + codec.json(base) + "}", 200);
        assertThat(validation.path("valid").asBoolean()).isTrue();
        JsonNode simulated = executeOrder(cases.get(0), "1", true);
        assertThat(simulated.path("decision").path("decisionCode").asText()).isEqualTo("APPROVE");
        assertThat(simulated.path("decision").path("score").asText()).isEqualTo("15");
        JsonNode scoreNode = java.util.stream.StreamSupport.stream(simulated.path("nodeResults").spliterator(), false)
                .filter(node -> node.path("nodeId").asText().equals("buyer_score")).findFirst().orElseThrow();
        assertThat(scoreNode.path("details").get(0).path("kind").asText()).isEqualTo("SCORE_BIN");
        call(post("/api/v2/admin/flows/order_admission/versions/1/publish"),
                "{\"expectedRevision\":\"2\",\"changeNote\":\"D0 fixture\"}", 200);
        for (JsonNode sample : cases) {
            if (!sample.path("definitionFixture").asText().equals("order-admission.definition.json")) continue;
            int expectedStatus = sample.path("expected").path("status").asText().equals("DECIDED") ? 200 : 500;
            JsonNode response = executeOrder(sample, "1", false, expectedStatus);
            JsonNode execution = expectedStatus == 200 ? response : response.path("execution");
            assertThat(execution.path("status").asText()).as(sample.path("id").asText())
                    .isEqualTo(sample.path("expected").path("status").asText());
            assertThat(execution.path("decision").path("decisionCode").asText(null))
                    .as(sample.path("id").asText())
                    .isEqualTo(sample.path("expected").path("decisionCode").asText(null));
            if (sample.path("expected").has("score"))
                assertThat(execution.path("decision").path("score").asText(null))
                        .as(sample.path("id").asText())
                        .isEqualTo(sample.path("expected").path("score").asText(null));
            if (sample.path("expected").has("segment"))
                assertThat(execution.path("variables").path("segment").asText(null))
                        .as(sample.path("id").asText())
                        .isEqualTo(sample.path("expected").path("segment").asText(null));
            assertThat(execution.path("decision").path("terminalNodeId").asText(null))
                    .as(sample.path("id").asText())
                    .isEqualTo(sample.path("expected").path("terminalNodeId").asText(null));
        }
        call(post("/api/v2/admin/flows/order_admission/drafts"), "{\"sourceVersion\":\"1\"}", 201);
        ObjectNode score35 = (ObjectNode) codec.parse(Files.readString(
                fixtures.resolve("order-admission-score-35.definition.json")));
        score35.put("version", "2");
        saveOrder(score35, "2", "1");
        call(post("/api/v2/admin/flows/order_admission/versions/2/publish"),
                "{\"expectedRevision\":\"2\",\"changeNote\":\"score change\"}", 200);
        JsonNode changed = executeOrder(findCase(cases, "OA-03"), "2", false);
        assertThat(changed.path("decision").path("decisionCode").asText()).isEqualTo("APPROVE");
        assertThat(changed.path("decision").path("score").asText()).isEqualTo("35");
        assertThat(executeOrder(cases.get(1), "1", false).path("decision").path("score").asText())
                .isEqualTo("75");
        publishOrderVariant(fixtures, "order-admission-score-59.definition.json", "3");
        JsonNode score59 = executeOrder(findCase(cases, "OA-12a"), "3", false);
        assertThat(score59.path("decision").path("decisionCode").asText()).isEqualTo("APPROVE");
        assertThat(score59.path("decision").path("score").asText()).isEqualTo("59");
        publishOrderVariant(fixtures, "order-admission-score-60.definition.json", "4");
        JsonNode score60 = executeOrder(findCase(cases, "OA-12b"), "4", false);
        assertThat(score60.path("decision").path("decisionCode").asText()).isEqualTo("REVIEW");
        assertThat(score60.path("decision").path("score").asText()).isEqualTo("60");
    }

    private void publishOrderVariant(Path fixtures, String name, String version) throws Exception {
        JsonNode draft = call(post("/api/v2/admin/flows/order_admission/drafts"),
                "{\"sourceVersion\":\"1\"}", 201);
        assertThat(draft.path("version").asText()).isEqualTo(version);
        ObjectNode document = (ObjectNode) codec.parse(Files.readString(fixtures.resolve(name)));
        document.put("version", version);
        saveOrder(document, version, "1");
        call(post("/api/v2/admin/flows/order_admission/versions/" + version + "/publish"),
                "{\"expectedRevision\":\"2\",\"changeNote\":\"OA threshold fixture\"}", 200);
    }

    private static JsonNode findCase(JsonNode cases, String id) {
        return java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .filter(sample -> sample.path("id").asText().equals(id)).findFirst().orElseThrow();
    }

    private void saveOrder(JsonNode document, String version, String revision) throws Exception {
        call(put("/api/v2/admin/flows/order_admission/versions/" + version),
                "{\"expectedRevision\":\"" + revision + "\",\"document\":" + codec.json(document) + "}", 200);
    }

    private JsonNode executeOrder(JsonNode sample, String version, boolean simulate) throws Exception {
        return executeOrder(sample, version, simulate, 200);
    }

    private JsonNode executeOrder(JsonNode sample, String version, boolean simulate, int status) throws Exception {
        String path = simulate ? "/api/v2/admin/flows/order_admission/versions/" + version + "/simulations"
                : "/api/v2/flows/order_admission/versions/" + version + "/executions";
        String body = "{\"requestId\":\"" + sample.path("id").asText() + "\",\"bizId\":\"order\","
                + "\"facts\":" + codec.json(sample.path("facts")) + ",\"timeoutMillis\":3000"
                + (simulate ? ",\"expectedRevision\":\"2\"}" : "}");
        return call(post(path), body, status);
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, String body, int status) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        var result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private final class FakeStore implements StudioStore {
        private final Map<Long, Version> versions = new LinkedHashMap<>();
        private String id, name;
        private Long draft, published;

        @Override public Version createFlow(String flowId, String flowName, String initialJson) {
            if (id != null) throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "exists");
            id = flowId; name = flowName; draft = 1L;
            Version v = new Version(id, name, 1, "DRAFT", 1, initialJson, null, null, Instant.now());
            versions.put(1L, v);
            return v;
        }
        @Override public Page<FlowHead> flows(String query, int page, int pageSize) {
            return new Page<>(id == null ? List.of() : List.of(new FlowHead(id, name, draft, published)), page, pageSize, id == null ? 0 : 1);
        }
        @Override public Page<Version> versions(String flowId, int page, int pageSize) {
            if (id == null) throw new StudioProblem(404, "OR-FLOW-NOT-FOUND", "missing");
            return new Page<>(versions.values().stream().sorted((a,b) -> Long.compare(b.version(), a.version())).toList(), page, pageSize, versions.size());
        }
        @Override public Version version(String flowId, long version) {
            Version value = versions.get(version);
            if (value == null) throw new StudioProblem(404, "OR-FLOW-NOT-FOUND", "missing");
            return value;
        }
        @Override public Version createDraft(String flowId, long sourceVersion, StudioDocumentCodec codec) {
            if (draft != null) throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "draft exists");
            Version source = version(flowId, sourceVersion);
            ObjectNode copy = (ObjectNode) codec.parse(source.documentJson());
            long nextVersion = versions.keySet().stream().mapToLong(Long::longValue).max().orElseThrow() + 1;
            copy.put("version", Long.toString(nextVersion));
            Version v = new Version(id, name, nextVersion, "DRAFT", 1, codec.json(copy), null, null, Instant.now());
            versions.put(nextVersion, v); draft = nextVersion;
            return v;
        }
        @Override public Version save(String flowId, long version, long expectedRevision, String flowName, String json) {
            Version old = version(flowId, version);
            if (!old.status().equals("DRAFT")) throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "published");
            if (old.revision() != expectedRevision) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "changed");
            name = flowName;
            Version next = new Version(id, flowName, version, "DRAFT", old.revision() + 1, json, null, null, Instant.now());
            versions.put(version, next);
            return next;
        }
        @Override public Version publish(String flowId, long version, long expectedRevision, String note, StudioDocumentCodec codec) {
            Version old = version(flowId, version);
            if (!old.status().equals("DRAFT")) throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "published");
            if (old.revision() != expectedRevision) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "changed");
            var document = codec.parse(old.documentJson());
            codec.executable(document);
            Version next = new Version(id, name, version, "PUBLISHED", old.revision() + 1,
                    old.documentJson(), codec.checksum(document), note, Instant.now());
            versions.put(version, next); draft = null; published = version;
            return next;
        }
    }
}
