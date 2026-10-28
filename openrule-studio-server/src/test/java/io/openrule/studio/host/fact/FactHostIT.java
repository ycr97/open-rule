package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import io.openrule.spring.fact.SecretResolver;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.studio.host.StudioServerApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = {StudioServerApplication.class, FactHostIT.CountSecrets.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FactHostIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("studio_m2_b2_test");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername); registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    static final AtomicInteger SECRET_CALLS = new AtomicInteger();
    @TestConfiguration(proxyBeanMethods = false) static class CountSecrets {
        @Bean @Primary SecretResolver countedSecrets() { return ref -> { SECRET_CALLS.incrementAndGet(); return new EnvironmentSecretResolver().resolve(ref); }; }
    }
    static final Path CONTRACTS = Path.of("../docs/contracts");
    static final Path FIX = CONTRACTS.resolve("studio-m2/fixtures");
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcFactStore store;
    @Autowired StudioDocumentCodec codec;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final Map<String, Schema> schemas = new HashMap<>();
    record Reply(int status, JsonNode body) { }
    JsonNode read(Path path) throws Exception { return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)).readTree(Files.readString(path)); }
    JsonNode read(String file) throws Exception { return read(FIX.resolve(file)); }
    Reply call(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(codec.json(body))).header("Content-Type", "application/json");
        HttpResponse<String> reply = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(reply.statusCode(), codec.parse(reply.body()));
    }
    JsonNode ok(String method, String path, JsonNode body, int status) throws Exception {
        Reply reply = call(method, path, body); assertThat(reply.status).as(method + " " + path + " code=" + reply.body.path("code").asText()).isEqualTo(status); return reply.body;
    }
    void reset() {
        assertThat(MYSQL.getDatabaseName()).isEqualTo("studio_m2_b2_test");
        jdbc.update("DELETE FROM or_studio_fact_catalog"); jdbc.update("DELETE FROM or_studio_fact_provider");
        jdbc.update("DELETE FROM or_studio_version"); jdbc.update("DELETE FROM or_studio_flow"); SECRET_CALLS.set(0);
    }
    String seedFlow(JsonNode definition, boolean publish) throws Exception {
        String id = definition.path("flowId").asText();
        ok("POST", "/api/v2/admin/flows", codec.parse("{\"flowId\":\"" + id + "\",\"flowName\":\"M2 B2 test\"}"), 201);
        ObjectNode save = new ObjectMapper().createObjectNode(); save.put("expectedRevision", "1"); save.set("document", definition);
        JsonNode resource = ok("PUT", "/api/v2/admin/flows/" + id + "/versions/1", save, 200);
        if (publish) resource = ok("POST", "/api/v2/admin/flows/" + id + "/versions/1/publish", codec.parse("{\"expectedRevision\":\"" + resource.path("revision").asText() + "\",\"changeNote\":\"B2 isolated fixture\"}"), 200);
        return resource.path("revision").asText();
    }
    void seedCatalog(JsonNode catalog, JsonNode provider) throws Exception {
        ok("POST", "/api/v2/admin/fact-providers", provider, 201);
        for (JsonNode resource : catalog) ok("POST", "/api/v2/admin/facts", resource.path("document"), 201);
    }
    static ObjectNode overlay(ObjectNode target, JsonNode patch) {
        patch.fields().forEachRemaining(e -> { if (target.path(e.getKey()).isObject() && e.getValue().isObject()) overlay((ObjectNode) target.path(e.getKey()), e.getValue()); else target.set(e.getKey(), e.getValue().deepCopy()); }); return target;
    }
    @TestFactory Stream<DynamicTest> realHostFrozenMfCases() throws Exception {
        List<DynamicTest> cases = new ArrayList<>();
        for (JsonNode sample : read("order-admission.cases.json").path("cases")) cases.add(DynamicTest.dynamicTest(sample.path("id").asText(), () -> mf(sample)));
        return cases.stream();
    }
    void mf(JsonNode sample) throws Exception {
        reset();
        try (var upstream = new ControlledProvider(sample.path("transport"))) {
            String id = sample.path("id").asText(); JsonNode expected = sample.path("expected");
            JsonNode definition = read(FIX.resolve(sample.path("definitionFile").asText()));
            String revision = seedFlow(definition, true);
            ObjectNode provider = overlay((ObjectNode) read(sample.path("providerDocumentFile").asText()), sample.path("providerPatch"));
            if (provider.path("type").asText().equals("HTTP")) {
                URI endpoint = upstream.endpoint();
                if (sample.path("transport").path("outcome").asText().equals("CONNECT_ERROR")) {
                    try (var closed = new java.net.ServerSocket(0)) { endpoint = URI.create("http://127.0.0.1:" + closed.getLocalPort() + "/closed"); }
                }
                provider.put("endpoint", endpoint.toString());
            }
            seedCatalog(read(sample.path("catalogFile").asText()), provider);
            ObjectNode request = sample.path("request").deepCopy(); if (request.has("expectedRevision")) request.put("expectedRevision", revision);
            String mode = sample.path("endpoint").asText();
            String path = mode.equals("LIVE") ? "/api/v2/flows/order_admission/versions/1/fact-executions" : "/api/v2/admin/flows/order_admission/versions/1/" + (mode.startsWith("M1") ? "simulations" : "fact-simulations");
            Reply response = call("POST", path, request);
            assertThat(response.status).as(id + " code=" + response.body.path("code").asText()).isEqualTo(expected.path("httpStatus").asInt());
            JsonNode execution = mode.startsWith("M1") ? response.body : response.body.path("execution");
            if (expected.path("coreInvoked").asBoolean()) {
                assertThat(execution.path("status").asText()).isEqualTo(expected.path("status").asText());
                assertThat(execution.path("decision").path("decisionCode").asText(null)).isEqualTo(expected.path("decisionCode").asText(null));
                if (expected.has("score")) assertThat(execution.path("decision").path("score").asText(null)).isEqualTo(expected.path("score").asText(null));
                if (expected.has("terminalNodeId")) assertThat(execution.path("decision").path("terminalNodeId").asText()).isEqualTo(expected.path("terminalNodeId").asText());
                assertThat(execution.path("definitionChecksum").asText()).isEqualTo(read("valid/order-admission.required-facts.json").path("definitionChecksum").asText());
            } else assertThat(execution.isNull()).isTrue();
            if (expected.has("errorCode")) assertThat(response.body.path("code").asText()).isEqualTo(expected.path("errorCode").asText());
            if (!mode.startsWith("M1")) {
                Map<String, JsonNode> fields = new HashMap<>(); response.body.path("factResolution").path("fields").forEach(f -> fields.put(f.path("path").asText(), f));
                assertThat(fields).hasSize(5);
                expected.path("fieldStates").fields().forEachRemaining(e -> assertThat(fields.get(e.getKey()).path("status").asText()).isEqualTo(e.getValue().asText()));
                expected.path("fieldErrors").fields().forEachRemaining(e -> assertThat(fields.get(e.getKey()).path("error").path("code").asText()).isEqualTo(e.getValue().asText()));
                String trace = execution.isNull() ? response.body.path("traceId").asText() : execution.path("traceId").asText();
                response.body.path("factResolution").path("calls").forEach(c -> { assertThat(c.path("traceId").asText()).isEqualTo(trace); assertThat(c.path("providerRevision").asText()).isEqualTo("1"); });
                if (response.status >= 400) assertWire("ErrorBody", response.body); else assertWire("FactExecutionResult", response.body);
                if (!execution.isNull()) execution.path("variables").elements().forEachRemaining(v -> assertThat(v.asText()).isEqualTo("[REDACTED]"));
                if (expected.has("secretResolutions")) assertThat(SECRET_CALLS).hasValue(expected.path("secretResolutions").asInt());
                assertThat(response.body.toString()).doesNotContain("buyer_1024", "b2-controlled-token");
            }
            int actualPosts = sample.path("transport").path("outcome").asText().equals("CONNECT_ERROR") ? 0 : expected.path("httpCalls").asInt();
            assertThat(upstream.bodies).hasSize(actualPosts); assertThat(upstream.methods).allMatch("POST"::equals);
            for (String body : upstream.bodies) assertThat(codec.parse(body)).isEqualTo(codec.parse("{\"buyerId\":\"buyer_1024\",\"region\":\"GLOBAL\"}"));
            System.out.println("B2 " + id + " PASS status=" + response.status + " POST=" + actualPosts);
        }
    }
    @TestFactory Stream<DynamicTest> managementSemanticCases() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode sample : read("semantic.cases.json")) tests.add(DynamicTest.dynamicTest(sample.path("id").asText(), () -> {
            reset(); seedCatalog(read(sample.path("catalogFile").asText()), read(sample.path("providerResourceFile").asText()).path("document"));
            JsonNode before = ok("GET", "/api/v2/admin/facts/refund_rate", null, 200);
            JsonNode providerBefore = ok("GET", "/api/v2/admin/fact-providers/risk_profile", null, 200);
            Reply result = call(sample.path("operation").path("method").asText(), sample.path("operation").path("path").asText(), sample.path("document"));
            assertThat(result.status).isEqualTo(sample.path("expectedHttpStatus").asInt());
            assertThat(result.body.path("code").asText()).isEqualTo(sample.path("expectedCode").asText()); assertWire("ErrorBody", result.body);
            assertThat(ok("GET", "/api/v2/admin/facts/refund_rate", null, 200)).isEqualTo(before);
            assertThat(ok("GET", "/api/v2/admin/fact-providers/risk_profile", null, 200)).isEqualTo(providerBefore);
            if (!sample.path("id").asText().equals("MS-07") && !sample.path("id").asText().equals("MS-08"))
                assertThat(result.body.path("issues")).isNotEmpty();
            System.out.println("B2 " + sample.path("id").asText() + " PASS status=" + result.status);
        })); return tests.stream();
    }
    @Test void pagingCasIdentityBindingAndMockPreview() throws Exception {
        reset(); seedCatalog(read("catalog.json"), read("valid/risk-profile.provider.json"));
        JsonNode page = ok("GET", "/api/v2/admin/facts?source=PROVIDER&page=1&pageSize=2", null, 200);
        assertWire("FactPage", page); assertThat(page.path("total").asInt()).isEqualTo(3); assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(ok("GET", "/api/v2/admin/facts?source=CALLER", null, 200).path("total").asInt()).isEqualTo(3);
        ObjectNode named = (ObjectNode) read("valid/risk-profile.provider.json"); named.put("providerId", "literal_source"); named.put("name", "literal%_!name");
        assertWire("ProviderResource", ok("POST", "/api/v2/admin/fact-providers", named, 201));
        assertThat(ok("GET", "/api/v2/admin/fact-providers?q=%25_!", null, 200).path("total").asInt()).isEqualTo(1);
        assertWire("ProviderPage", ok("GET", "/api/v2/admin/fact-providers?pageSize=1&page=2", null, 200));
        ok("POST", "/api/v2/admin/fact-providers", named, 409);
        ok("POST", "/api/v2/admin/facts", read("valid/refund_rate.fact.json"), 409);
        ObjectNode identity = (ObjectNode) read("valid/provider-update.request.json"); identity.with("document").put("providerId", "another_source");
        assertThat(ok("PUT", "/api/v2/admin/fact-providers/risk_profile", identity, 409).path("code").asText()).isEqualTo("OR-FACT-IDENTITY");
        JsonNode preview = ok("POST", "/api/v2/admin/fact-providers/risk_profile/mock-tests", read("valid/provider-mock-test.request.json"), 200);
        assertWire("MockTestResult", preview); assertThat(preview.path("factResolution").path("status").asText()).isEqualTo("COMPLETE"); assertThat(SECRET_CALLS).hasValue(0);
        JsonNode empty = ok("POST", "/api/v2/admin/fact-providers/literal_source/mock-tests", read("valid/provider-mock-test.request.json"), 200);
        assertThat(empty.path("factResolution").path("fields")).isEmpty();
        ObjectNode unbind = (ObjectNode) read("valid/fact-update.request.json"); unbind.with("document").putNull("binding");
        assertWire("FactResource", ok("PUT", "/api/v2/admin/facts/refund_rate", unbind, 200));
        assertThat(jdbc.queryForObject("SELECT provider_id FROM or_studio_fact_catalog WHERE fact_id='refund_rate'", String.class)).isNull();
        assertThat(codec.parse(jdbc.queryForObject("SELECT document_json FROM or_studio_fact_catalog WHERE fact_id='refund_rate'", String.class)).path("binding").isNull()).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE or_studio_fact_catalog SET provider_id='not_exists' WHERE fact_id='refund_rate'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        ok("GET", "/api/v2/admin/facts/unknown_fact", null, 404);
        ok("GET", "/api/v2/admin/facts?page=0", null, 400);
        ok("GET", "/api/v2/admin/facts?pageSize=abc", null, 400);
        ok("GET", "/api/v2/admin/facts?q=" + java.net.URLEncoder.encode("😀".repeat(128), java.nio.charset.StandardCharsets.UTF_8), null, 200);
        ok("GET", "/api/v2/admin/facts?q=" + java.net.URLEncoder.encode("😀".repeat(129), java.nio.charset.StandardCharsets.UTF_8), null, 400);
        ObjectNode invalidRequest = (ObjectNode) read("valid/simulate-mock.request.json"); invalidRequest.put("requestId", "");
        assertWire("ErrorBody", ok("POST", "/api/v2/admin/flows/unknown_flow/versions/1/fact-simulations", invalidRequest, 400));
    }
    @Test void concurrentCasAndPrefixCreationIncludingEmptyTable() throws Exception {
        reset(); seedCatalog(read("catalog.json"), read("valid/risk-profile.provider.json"));
        for (boolean fact : List.of(true, false)) {
            String path = fact ? "/api/v2/admin/facts/refund_rate" : "/api/v2/admin/fact-providers/risk_profile";
            JsonNode update = read(fact ? "valid/fact-update.request.json" : "valid/provider-update.request.json");
            List<Reply> replies = race("PUT", path, update, update);
            assertThat(replies).extracting(Reply::status).containsExactlyInAnyOrder(200, 409);
            assertThat(ok("GET", path, null, 200).path("revision").asText()).isEqualTo("2");
        }
        for (boolean empty : List.of(true, false)) {
            if (empty) jdbc.update("DELETE FROM or_studio_fact_catalog");
            ObjectNode a = (ObjectNode) read("valid/refund_rate.fact.json"), b = a.deepCopy();
            a.put("factId", empty ? "empty_parent" : "existing_parent"); a.put("path", empty ? "/emptyRoot/value" : "/otherRoot/value"); a.putNull("binding");
            b.put("factId", empty ? "empty_child" : "existing_child"); b.put("path", a.path("path").asText() + "/child"); b.putNull("binding");
            List<Reply> replies = race("POST", "/api/v2/admin/facts", a, b);
            assertThat(replies.stream().filter(r -> r.status == 201).count()).isEqualTo(1);
            assertThat(replies.stream().filter(r -> r.status != 201).findFirst().orElseThrow().status).isIn(400, 409);
        }
        try (var a = jdbc.getDataSource().getConnection(); var b = jdbc.getDataSource().getConnection()) {
            long first, second;
            try (var statement = a.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) { result.next(); first = result.getLong(1); }
            try (var statement = b.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) { result.next(); second = result.getLong(1); }
            assertThat(first).isNotEqualTo(second);
            System.out.println("B2 MT-05 two MySQL connections=" + first + "," + second);
        }
    }
    List<Reply> race(String method, String path, JsonNode a, JsonNode b) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            List<Future<Reply>> work = new ArrayList<>();
            for (JsonNode body : List.of(a, b)) work.add(pool.submit(() -> { ready.countDown(); start.await(); return call(method, path, body); }));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(work.get(0).get(15, TimeUnit.SECONDS), work.get(1).get(15, TimeUnit.SECONDS));
        }
    }
    @Test void executionUsesOneSnapshotAndDatabaseTransactionEndsBeforeHttp() throws Exception {
        reset(); String revision = seedFlow(read(CONTRACTS.resolve("studio-m1/fixtures/valid/order-admission.definition.json")), true);
        try (var upstream = new ControlledProvider(codec.parse("{\"body\":{\"data\":{\"blacklisted\":false,\"ageDays\":365,\"refundRate\":0.08}}}")); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            upstream.release = new CountDownLatch(1);
            ObjectNode provider = (ObjectNode) read("valid/risk-profile.provider.json"); provider.put("endpoint", upstream.endpoint().toString()); provider.put("readTimeoutMillis", 10000);
            seedCatalog(read("catalog.json"), provider);
            ObjectNode request = (ObjectNode) read("valid/simulate-http.request.json"); request.put("expectedRevision", revision); request.put("timeoutMillis", 15000);
            Future<Reply> pending = pool.submit(() -> call("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", request));
            assertThat(upstream.received.await(3, TimeUnit.SECONDS)).isTrue();
            // The external call is blocked. No InnoDB read transaction may remain in this dedicated database.
            try (var observer = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                 var statement = observer.createStatement(); var active = statement.executeQuery("SELECT COUNT(*) FROM information_schema.innodb_trx")) {
                active.next(); assertThat(active.getInt(1)).isZero();
            }
            ObjectNode changed = new ObjectMapper().createObjectNode(); changed.put("expectedRevision", "1"); changed.set("document", provider.deepCopy()); changed.with("document").put("enabled", false);
            ok("PUT", "/api/v2/admin/fact-providers/risk_profile", changed, 200);
            ObjectNode binding = (ObjectNode) read("valid/fact-update.request.json"); binding.with("document").with("binding").put("responsePointer", "/data/does_not_exist");
            ok("PUT", "/api/v2/admin/facts/refund_rate", binding, 200);
            upstream.release.countDown();
            Reply first = pending.get(4, TimeUnit.SECONDS); assertThat(first.status).isEqualTo(200); assertThat(first.body.path("execution").path("decision").path("score").asText()).isEqualTo("15");
            first.body.path("factResolution").path("fields").forEach(f -> assertThat(f.path("catalogRevision").asText()).isEqualTo("1"));
            first.body.path("factResolution").path("calls").forEach(c -> assertThat(c.path("providerRevision").asText()).isEqualTo("1"));
            Reply second = call("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", request);
            assertThat(second.body.path("execution").path("decision").path("decisionCode").asText()).isEqualTo("REVIEW");
            second.body.path("factResolution").path("calls").forEach(c -> assertThat(c.path("providerRevision").asText()).isEqualTo("2"));
            assertThat(upstream.bodies).hasSize(1);
        }
    }
    @TestFactory Stream<DynamicTest> frozenM1OrdersRemainCompatibleWithCatalogPresent() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode sample : read(CONTRACTS.resolve("studio-m1/fixtures/valid/order-admission.cases.json"))) tests.add(DynamicTest.dynamicTest("M1-" + sample.path("id").asText(), () -> {
            reset();
            try (var upstream = new ControlledProvider(codec.parse("{\"body\":{}}"))) {
                ObjectNode provider = (ObjectNode) read("valid/risk-profile.provider.json"); provider.put("endpoint", upstream.endpoint().toString()); seedCatalog(read("catalog.json"), provider);
                JsonNode definition = read(CONTRACTS.resolve("studio-m1/fixtures/valid/" + sample.path("definitionFixture").asText())); String revision = seedFlow(definition, true);
                JsonNode expected = sample.path("expected");
                for (boolean simulate : List.of(true, false)) {
                    ObjectNode request = new ObjectMapper().createObjectNode(); request.put("requestId", "b2-m1"); request.put("bizId", "b2-order"); request.put("timeoutMillis", 3000); request.set("facts", sample.path("facts")); if (simulate) request.put("expectedRevision", revision);
                    String path = (simulate ? "/api/v2/admin/flows/" : "/api/v2/flows/") + "order_admission/versions/1/" + (simulate ? "simulations" : "executions");
                    Reply reply = call("POST", path, request); JsonNode core = reply.status == 200 ? reply.body : reply.body.path("execution");
                    assertThat(core.path("status").asText()).isEqualTo(expected.path("status").asText());
                    assertThat(core.path("decision").path("decisionCode").asText(null)).isEqualTo(expected.path("decisionCode").asText(null));
                    assertThat(core.path("decision").path("terminalNodeId").asText(null)).isEqualTo(expected.path("terminalNodeId").asText(null));
                    if (!core.path("decision").isNull()) assertThat(core.path("decision").path("reasonCodes")).isEqualTo(expected.path("reasonCodes"));
                    expected.path("nodeStatus").fields().forEachRemaining(e -> {
                        JsonNode node = java.util.stream.StreamSupport.stream(core.path("nodeResults").spliterator(), false)
                                .filter(n -> n.path("nodeId").asText().equals(e.getKey())).findFirst().orElseThrow();
                        assertThat(node.path("status").asText()).isEqualTo(e.getValue().asText());
                    });
                    if (expected.has("score")) assertThat(core.path("decision").path("score").asText(null)).isEqualTo(expected.path("score").asText(null));
                    if (expected.has("segment")) assertThat(core.path("variables").path("segment").asText(null)).isEqualTo(expected.path("segment").asText(null));
                }
                assertThat(upstream.bodies).isEmpty(); assertThat(SECRET_CALLS).hasValue(0);
                System.out.println("B2 M1 " + sample.path("id").asText() + " SIMULATE/LIVE PASS");
            }
        })); return tests.stream();
    }
    @Test void preciseSavedConfigurationSecretIsolationAndSlowBodyDeadline() throws Exception {
        reset(); String revision = seedFlow(read(CONTRACTS.resolve("studio-m1/fixtures/valid/order-admission.definition.json")), true);
        try (var upstream = new ControlledProvider(codec.parse("{\"body\":{\"data\":{\"blacklisted\":false,\"ageDays\":365,\"refundRate\":0.08}}}"))) {
            ObjectNode provider = (ObjectNode) read("valid/precision.provider.json"); provider.put("endpoint", upstream.endpoint().toString()); provider.put("secretRef", "env:OPENRULE_B2_TEST_TOKEN");
            seedCatalog(read("catalog.json"), provider);
            JsonNode saved = ok("GET", "/api/v2/admin/fact-providers/risk_profile", null, 200);
            assertThat(saved.path("document").path("mock").path("response").path("data").path("refundRate").decimalValue())
                    .isEqualByComparingTo("0.123456789012345678901234567890123456789");
            String raw = jdbc.queryForObject("SELECT document_json FROM or_studio_fact_provider WHERE provider_id='risk_profile'", String.class);
            assertThat(raw).contains("9007199254740995", "0.123456789012345678901234567890123456789").doesNotContain("b2-controlled-token");
            ObjectNode request = (ObjectNode) read("valid/simulate-mock.request.json"); request.put("expectedRevision", revision);
            JsonNode mock = ok("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", request, 200);
            assertThat(mock.path("execution").path("decision").path("score").asText()).isEqualTo("15"); assertThat(SECRET_CALLS).hasValue(0); assertThat(upstream.bodies).isEmpty();
            request.put("factMode", "HTTP");
            JsonNode real = ok("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", request, 200);
            assertThat(upstream.auth).containsExactly("Bearer b2-controlled-token"); assertThat(SECRET_CALLS).hasValue(1);
            assertThat(real.toString()).doesNotContain("b2-controlled-token", "buyer_1024");
            request.put("factMode", "MOCK"); request.remove("expectedRevision");
            ok("POST", "/api/v2/flows/order_admission/versions/1/fact-executions", request, 400);
            ObjectNode disable = new ObjectMapper().createObjectNode(); disable.put("expectedRevision", "1"); disable.set("document", provider); provider.put("enabled", false);
            ok("PUT", "/api/v2/admin/fact-providers/risk_profile", disable, 200);
            ObjectNode preview = (ObjectNode) read("valid/provider-mock-test.request.json"); preview.put("expectedRevision", "2");
            JsonNode blocked = ok("POST", "/api/v2/admin/fact-providers/risk_profile/mock-tests", preview, 200);
            blocked.path("factResolution").path("fields").forEach(f -> assertThat(f.path("error").path("code").asText()).isEqualTo("OR-FACT-DISABLED"));
        }
        reset(); revision = seedFlow(read(CONTRACTS.resolve("studio-m1/fixtures/valid/order-admission.definition.json")), true);
        try (var upstream = new ControlledProvider(codec.parse("{\"slowBody\":true,\"delayMillis\":800,\"body\":{\"data\":{\"blacklisted\":false,\"ageDays\":365,\"refundRate\":0.08}}}"))) {
            ObjectNode provider = (ObjectNode) read("valid/risk-profile.provider.json"); provider.put("endpoint", upstream.endpoint().toString()); seedCatalog(read("catalog.json"), provider);
            ObjectNode request = (ObjectNode) read("valid/simulate-http.request.json"); request.put("expectedRevision", revision);
            JsonNode result = ok("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", request, 200);
            assertThat(result.path("execution").path("decision").path("decisionCode").asText()).isEqualTo("REVIEW");
            result.path("factResolution").path("calls").forEach(c -> assertThat(c.path("status").asText()).isEqualTo("TIMED_OUT"));
            assertThat(upstream.finished.await(2, TimeUnit.SECONDS)).isTrue(); assertThat(upstream.bodies).hasSize(1);
        }
    }
    @Test void requiredFactsLifecycleAndChecksumGuards() throws Exception {
        reset(); JsonNode definition = read(CONTRACTS.resolve("studio-m1/fixtures/valid/order-admission.definition.json"));
        String revision = seedFlow(definition, true);
        JsonNode required = ok("GET", "/api/v2/admin/flows/order_admission/versions/1/required-facts", null, 200);
        assertWire("RequiredFactsResource", required);
        assertThat(required.path("requiredFacts")).isEqualTo(read("valid/order-admission.required-facts.json").path("requiredFacts"));
        ObjectNode stale = (ObjectNode) read("valid/simulate-mock.request.json");
        assertThat(ok("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", stale, 409).path("code").asText()).isEqualTo("OR-REVISION-CONFLICT");
        ObjectNode m2Body = (ObjectNode) read("valid/simulate-mock.request.json"); m2Body.put("expectedRevision", revision);
        ok("POST", "/api/v2/admin/flows/order_admission/versions/1/simulations", m2Body, 400);
        ObjectNode immutable = new ObjectMapper().createObjectNode(); immutable.put("expectedRevision", revision); immutable.set("document", definition);
        ok("PUT", "/api/v2/admin/flows/order_admission/versions/1", immutable, 409);
        String checksum = jdbc.queryForObject("SELECT definition_checksum FROM or_studio_version WHERE flow_id='order_admission'", String.class);
        try {
            jdbc.update("UPDATE or_studio_version SET definition_checksum=? WHERE flow_id='order_admission'", "sha256:" + "0".repeat(64));
            assertThat(ok("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", m2Body, 500).path("code").asText()).isEqualTo("OR-DEF-CHECKSUM");
        } finally { jdbc.update("UPDATE or_studio_version SET definition_checksum=? WHERE flow_id='order_admission'", checksum); }
        ok("POST", "/api/v2/admin/flows/order_admission/drafts", codec.parse("{\"sourceVersion\":\"1\"}"), 201);
        JsonNode live = read("valid/live-http.request.json");
        ok("POST", "/api/v2/flows/order_admission/versions/2/fact-executions", live, 409);
        ok("POST", "/api/v2/flows/order_admission/versions/999/fact-executions", live, 404);
        ObjectNode incomplete = (ObjectNode) read(CONTRACTS.resolve("studio-m1/fixtures/valid/incomplete.draft.json")); incomplete.put("flowId", "b2_incomplete");
        String incompleteId = incomplete.path("flowId").asText();
        seedFlow(incomplete, false);
        ok("GET", "/api/v2/admin/flows/" + incompleteId + "/versions/1/required-facts", null, 400);
    }
    @Test void deterministicTwoConnectionCasAndEmptyTableRangeLock() throws Exception {
        reset(); seedCatalog(read("catalog.json"), read("valid/risk-profile.provider.json"));
        var ids = new java.util.concurrent.ConcurrentSkipListSet<Long>();
        CountDownLatch firstLocked = new CountDownLatch(1), secondEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger readers = new AtomicInteger();
        JdbcTemplate traced = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                int index = 0;
                if (sql.endsWith("FOR UPDATE")) {
                    ids.add(super.queryForObject("SELECT CONNECTION_ID()", Long.class)); index = readers.incrementAndGet();
                    if (index == 2) secondEntered.countDown();
                }
                List<T> result = super.query(sql, mapper, args);
                if (index == 1) { firstLocked.countDown(); await(release); }
                return result;
            }
        };
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var documents = new FactDocuments(codec); var observed = new JdbcFactStore(traced, manager, codec, documents);
        JsonNode update = read("valid/provider-update.request.json");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> writeCode(() -> observed.update(false, "risk_profile", update)));
            assertThat(firstLocked.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> writeCode(() -> observed.update(false, "risk_profile", update)));
            assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue(); assertThat(ids).hasSize(2);
            release.countDown(); assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { release.countDown(); }
        jdbc.update("DELETE FROM or_studio_fact_catalog"); ids.clear();
        CountDownLatch bothReadEmpty = new CountDownLatch(2);
        JdbcTemplate emptyRange = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> queryForList(String sql, Class<T> type) {
                List<T> result = super.queryForList(sql, type);
                if (sql.contains("FOR UPDATE")) {
                    ids.add(super.queryForObject("SELECT CONNECTION_ID()", Long.class)); assertThat(result).isEmpty();
                    bothReadEmpty.countDown(); await(bothReadEmpty);
                }
                return result;
            }
        };
        var empty = new JdbcFactStore(emptyRange, manager, codec, documents);
        ObjectNode a = (ObjectNode) read("valid/refund_rate.fact.json"), b = a.deepCopy();
        a.put("factId", "lock_parent"); a.put("path", "/locked/value"); a.putNull("binding");
        b.put("factId", "lock_child"); b.put("path", "/locked/value/child"); b.putNull("binding");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> writeCode(() -> empty.create(true, a)));
            var second = pool.submit(() -> writeCode(() -> empty.create(true, b)));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(ids).hasSize(2); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM or_studio_fact_catalog", Integer.class)).isEqualTo(1);
        }
        System.out.println("B2 deterministic CAS and empty-table concurrent gap locks PASS connections=" + ids);
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Controlled transaction barrier timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
    static int writeCode(Runnable write) {
        try { write.run(); return 200; } catch (io.openrule.spring.studio.StudioProblem ex) { return ex.status(); }
    }
    void assertWire(String name, JsonNode instance) throws Exception {
        if (!schemas.containsKey(name)) {
            Path schemaPath = CONTRACTS.resolve("studio-m2/schema/m2.schema.json");
            JsonNode definition = read(schemaPath).path("$defs").path(name);
            JsonNode bundled = bundle(definition, schemaPath);
            schemas.put(name, SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(new ByteArrayInputStream(codec.json(bundled).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        assertThat(schemas.get(name).validate(instance)).as(name).isEmpty();
    }
    JsonNode bundle(JsonNode node, Path source) throws Exception {
        if (node.isArray()) { ArrayNode result = new ObjectMapper().createArrayNode(); for (JsonNode child : node) result.add(bundle(child, source)); return result; }
        if (!node.isObject()) return node;
        if (node.has("$ref")) {
            String[] ref = node.path("$ref").asText().split("#", 2); Path path = ref[0].isEmpty() ? source : source.getParent().resolve(ref[0]);
            return bundle(read(path).at(ref.length > 1 ? ref[1] : ""), path);
        }
        ObjectNode result = new ObjectMapper().createObjectNode(); var entries = node.fields();
        while (entries.hasNext()) { var entry = entries.next(); result.set(entry.getKey(), bundle(entry.getValue(), source)); } return result;
    }
}
