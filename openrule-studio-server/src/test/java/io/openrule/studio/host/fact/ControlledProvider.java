package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class ControlledProvider implements AutoCloseable {
    final HttpServer server;
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final CountDownLatch received = new CountDownLatch(1), finished = new CountDownLatch(1);
    volatile CountDownLatch release;
    final JsonNode transport;
    ControlledProvider(JsonNode transport) throws Exception {
        this.transport = transport;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/v1/buyer/batch", exchange -> {
            try (exchange) {
                methods.add(exchange.getRequestMethod());
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth.add(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
                received.countDown();
                if (release != null) release.await();
                byte[] bytes = (transport.path("outcome").asText().equals("RAW_RESPONSE") ? transport.path("rawBody").asText()
                        : transport.has("body") ? transport.path("body").toString() : "{}").getBytes(StandardCharsets.UTF_8);
                if (transport.path("slowBody").asBoolean()) {
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes, 0, 1); exchange.getResponseBody().flush();
                    Thread.sleep(transport.path("delayMillis").asLong()); exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                } else {
                    Thread.sleep(transport.path("delayMillis").asLong());
                    exchange.sendResponseHeaders(transport.path("status").asInt(200), bytes.length); exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* timed out clients are expected */ }
            finally { finished.countDown(); }
        }); server.start();
    }
    URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/buyer/batch"); }
    @Override public void close() { if (release != null) release.countDown(); server.stop(0); workers.shutdownNow(); }
}
