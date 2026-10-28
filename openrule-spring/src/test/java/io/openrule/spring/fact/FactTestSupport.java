package io.openrule.spring.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.openrule.core.studio.*;
import io.openrule.spring.studio.StudioDocumentCodec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static io.openrule.spring.fact.FactConfiguration.*;

final class FactTestSupport implements AutoCloseable {
    static final Path FIXTURES = Path.of("../docs/contracts/studio-m2/fixtures");
    static final StudioDocumentCodec CODEC = new StudioDocumentCodec();
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final CountDownLatch received = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);
    final HttpServer server;
    final URI endpoint;
    FactTestSupport(JsonNode transport) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/buyer/batch");
        server.createContext("/v1/buyer/batch", exchange -> {
            try (exchange) {
                methods.add(exchange.getRequestMethod());
                auth.add(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                received.countDown();
                String outcome = transport.path("outcome").asText();
                String body = outcome.equals("RAW_RESPONSE") ? transport.path("rawBody").asText()
                        : transport.has("body") ? CODEC.json(transport.path("body")) : "{\"data\":{\"ageDays\":365}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (transport.path("slowBody").asBoolean()) {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(transport.path("delayMillis").asLong());
                    exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                } else {
                    Thread.sleep(transport.path("delayMillis").asLong());
                    if (outcome.equals("DISCONNECT")) return;
                    exchange.sendResponseHeaders(transport.path("status").asInt(200), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* client cancellation is an expected fixture event */ }
            finally { finished.countDown(); }
        });
        server.start();
        FactTransportSupport.encode(Map.of()); // Warm strict schemas outside controlled call timing.
    }
    static JsonNode read(String file) throws Exception { return new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)).readTree(Files.readString(FIXTURES.resolve(file))); }
    static ObjectNode overlay(ObjectNode target, JsonNode patch) {
        patch.fields().forEachRemaining(e -> {
            if (e.getValue().isObject() && target.path(e.getKey()).isObject()) overlay((ObjectNode) target.path(e.getKey()), e.getValue());
            else target.set(e.getKey(), e.getValue().deepCopy());
        });
        return target;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(JsonNode value) { return (Map<String, Object>) CODEC.value(value); }
    static Provider provider(JsonNode doc, String revision, URI endpoint) {
        Map<String, Mapping> mappings = new LinkedHashMap<>();
        doc.path("requestMap").fields().forEachRemaining(e -> mappings.put(e.getKey(),
                e.getValue().path("source").asText().equals("FACT") ? new Fact(e.getValue().path("pointer").asText())
                        : new Constant(CODEC.value(e.getValue().path("value")))));
        MockOutcome outcome = MockOutcome.valueOf(doc.path("mock").path("outcome").asText());
        return new Provider(doc.path("providerId").asText(), revision, doc.path("enabled").asBoolean(), mappings,
                new MockSettings(outcome, outcome == MockOutcome.RESPONSE ? map(doc.path("mock").path("response")) : null),
                doc.path("type").asText().equals("HTTP") ? new HttpSettings(endpoint,
                        doc.path("connectTimeoutMillis").asLong(), doc.path("readTimeoutMillis").asLong(),
                        doc.path("secretRef").isNull() ? null : doc.path("secretRef").asText(), doc.path("readOnly").asBoolean(),
                        doc.path("allowSimulationHttp").asBoolean()) : null);
    }
    static List<Binding> bindings(JsonNode catalog) {
        List<Binding> result = new ArrayList<>();
        for (JsonNode resource : catalog) {
            JsonNode d = resource.path("document"), b = d.path("binding");
            result.add(new Binding(d.path("factId").asText(), resource.path("revision").asText(), d.path("path").asText(),
                    ValueType.from(d.path("type").asText()), d.path("sensitive").asBoolean(),
                    b.isNull() ? null : b.path("providerId").asText(), b.isNull() ? null : b.path("responsePointer").asText()));
        }
        return result;
    }
    static List<RequiredFact> required(String... paths) {
        return Arrays.stream(paths).map(p -> new RequiredFact(p, List.of(), false, List.of())).toList();
    }
    static Binding binding(String path, String provider) { return new Binding(path.replace("/", "_"), "1", path, ValueType.NUMBER, false, provider, "/v"); }
    static Provider mock(String id, Map<String, Mapping> mappings, Object value) {
        return new Provider(id, "1", true, mappings, new MockSettings(MockOutcome.RESPONSE, Map.of("v", value)), null);
    }
    FactResolver resolver(SecretResolver secrets) { return new FactResolver(workers, List.of(new HttpFactProvider(secrets), new MockFactProvider())); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
