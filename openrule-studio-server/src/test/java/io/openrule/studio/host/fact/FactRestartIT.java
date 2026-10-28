package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.openrule.spring.studio.StudioDocumentCodec;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Actual owned Java process and dedicated MySQL container restart; no existing service is touched. */
@Testcontainers
class FactRestartIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("studio_m2_b2_restart");
    final StudioDocumentCodec codec = new StudioDocumentCodec();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Path fixtures = Path.of("../docs/contracts/studio-m2/fixtures");
    int port;
    String jdbcUrl;
    String jdbcUrl() { return jdbcUrl == null ? MYSQL.getJdbcUrl() : jdbcUrl; }
    JsonNode request(String method, String path, JsonNode body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        builder.header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(codec.json(body)));
        var result = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = codec.parse(result.body()); assertThat(result.statusCode()).as(path).isEqualTo(expected); return json;
    }
    Process start() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Path logs = Path.of("../output/m2-b2/restart-java.log"); Files.createDirectories(logs.getParent());
        ProcessBuilder command = new ProcessBuilder("sh", "../scripts/start-studio-m2.sh");
        command.environment().put("JAVA_HOME", System.getProperty("java.home"));
        command.environment().putAll(Map.of("PORT", Integer.toString(port), "STUDIO_JDBC_URL", jdbcUrl(), "STUDIO_DB_USER", MYSQL.getUsername(), "STUDIO_DB_PASSWORD", MYSQL.getPassword()));
        command.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logs.toFile()));
        Process process = command.start();
        try {
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < limit) {
                if (!process.isAlive()) throw new AssertionError("Owned Studio process exited; see restart-java.log");
                try { request("GET", "/api/v2/admin/facts", null, 200); return process; }
                catch (java.io.IOException ex) { Thread.sleep(100); }
            }
            throw new AssertionError("Owned Studio process did not become ready");
        } catch (Throwable error) { stop(process); throw error; }
    }
    static void stop(Process process) throws Exception {
        process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void realJavaAndMysqlRestartRetainConfigurationsPublishedChecksumAndM1() throws Exception {
        Process process = start(); long firstPid = process.pid();
        try {
            Path seedLog = Path.of("../output/m2-b2/seed-demo.log");
            for (int run = 0; run < 2; run++) {
                Process seed = new ProcessBuilder("python3", "../scripts/studio-m2-seed.py", "--base-url", "http://127.0.0.1:" + port,
                        "--flow-id", "order_admission").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(seedLog.toFile())).start();
                assertThat(seed.waitFor(15, TimeUnit.SECONDS)).isTrue(); assertThat(seed.exitValue()).as("seed script; see seed-demo.log").isZero();
            }
            ObjectNode provider = (ObjectNode) codec.parse(Files.readString(fixtures.resolve("valid/precision.provider.json")));
            provider.put("providerId", "b2_demo_risk"); provider.put("secretRef", "env:OPENRULE_B2_TEST_TOKEN");
            ObjectNode update = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); update.put("expectedRevision", "1"); update.set("document", provider);
            request("PUT", "/api/v2/admin/fact-providers/b2_demo_risk", update, 200);
            Map<String, JsonNode> before = new LinkedHashMap<>();
            for (String path : List.of("/admin/fact-providers/b2_demo_risk", "/admin/facts/b2_demo_refund_rate", "/admin/facts?pageSize=100", "/admin/fact-providers?pageSize=100", "/admin/flows/order_admission/versions/1")) before.put(path, request("GET", "/api/v2" + path, null, 200));
            stop(process); process = null;
            MYSQL.getDockerClient().restartContainerCmd(MYSQL.getContainerId()).exec();
            var inspected = MYSQL.getDockerClient().inspectContainerCmd(MYSQL.getContainerId()).exec();
            var binding = inspected.getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(3306))[0];
            jdbcUrl = "jdbc:mysql://127.0.0.1:" + binding.getHostPortSpec() + "/" + MYSQL.getDatabaseName() + "?connectTimeout=1000&socketTimeout=2000";
            System.out.println("B2 MySQL restart inspected mapped port=" + binding.getHostPortSpec());
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(45); boolean ready = false;
            String lastState = "";
            while (System.nanoTime() < limit) {
                try (var connection = java.sql.DriverManager.getConnection(jdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); var statement = connection.createStatement()) { statement.execute("SELECT 1"); ready = true; break; }
                catch (java.sql.SQLException ex) { lastState = ex.getSQLState() + "/" + ex.getErrorCode(); Thread.sleep(100); }
            }
            assertThat(ready).as("MySQL restart readiness SQLState/code=" + lastState).isTrue(); process = start(); assertThat(process.pid()).isNotEqualTo(firstPid);
            for (var entry : before.entrySet()) assertThat(request("GET", "/api/v2" + entry.getKey(), null, 200)).isEqualTo(entry.getValue());
            ObjectNode mock = (ObjectNode) codec.parse(Files.readString(fixtures.resolve("valid/simulate-mock.request.json"))); mock.put("expectedRevision", "3");
            JsonNode result = request("POST", "/api/v2/admin/flows/order_admission/versions/1/fact-simulations", mock, 200);
            assertThat(result.path("execution").path("decision").path("score").asText()).isEqualTo("15");
            ObjectNode live = (ObjectNode) codec.parse(Files.readString(fixtures.resolve("valid/live-http.request.json"))); live.remove("factMode");
            live.with("facts").with("buyer").put("ageDays", 365); live.with("facts").set("metrics", codec.parse("{\"refundRate\":0.08}")); live.with("facts").set("risk", codec.parse("{\"blacklisted\":false}"));
            assertThat(request("POST", "/api/v2/flows/order_admission/versions/1/executions", live, 200).path("decision").path("score").asText()).isEqualTo("15");
            Files.writeString(Path.of("../output/m2-b2/restart-evidence.json"), "{\"javaPidBefore\":" + firstPid + ",\"javaPidAfter\":" + process.pid() + ",\"mysqlRestarted\":true,\"resourcesEqual\":true,\"m2Score\":\"15\",\"m1Score\":\"15\"}\n");
            System.out.println("B2 MT-07 actual Java/MySQL restart PASS");
        } finally { if (process != null) stop(process); }
    }
}
