package io.openrule.jdbc.studio;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.openrule.jdbc.AbstractMySqlIT;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStudioStoreIT extends AbstractMySqlIT {
    private JdbcStudioStore store;
    private StudioDocumentCodec codec;

    @BeforeEach void studioSetUp() throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/studio/V1__studio_tables.sql"));
        }
        jdbc.update("DELETE FROM or_studio_version");
        jdbc.update("DELETE FROM or_studio_flow");
        store = new JdbcStudioStore(jdbc, txm);
        codec = new StudioDocumentCodec();
    }

    private String complete(String flowId) {
        ObjectNode document = codec.initial(flowId, "Test");
        ObjectNode config = (ObjectNode) document.path("stages").get(0).path("nodes").get(0).path("config");
        config.put("decisionCode", "APPROVE");
        ((ArrayNode) config.path("reasonCodes")).add("OK");
        return codec.json(document);
    }

    @Test void restartRecoveryCasPublishAndImmutableVersion() {
        var created = store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.status()).isEqualTo("DRAFT");
        var saved = store.save("test_flow", 1, 1, "Test", complete("test_flow"));
        assertThat(saved.revision()).isEqualTo(2);
        assertThat(saved.checksum()).isNull();
        assertThatThrownBy(() -> store.save("test_flow", 1, 1, "Test", complete("test_flow")))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-REVISION-CONFLICT");
        var published = store.publish("test_flow", 1, 2, "approved", codec);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.revision()).isEqualTo(3);
        assertThat(published.checksum()).startsWith("sha256:");
        assertThatThrownBy(() -> store.save("test_flow", 1, 3, "Test", complete("test_flow")))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-LIFECYCLE-CONFLICT");
        var restarted = new JdbcStudioStore(jdbc, txm);
        assertThat(restarted.version("test_flow", 1).documentJson()).isEqualTo(saved.documentJson());
        assertThat(restarted.flows("", 1, 20).items().getFirst().latestPublishedVersion()).isEqualTo(1);
        var next = restarted.createDraft("test_flow", 1, codec);
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.revision()).isEqualTo(1);
        assertThat(codec.parse(next.documentJson()).path("version").asText()).isEqualTo("2");
        assertThat(restarted.version("test_flow", 1).checksum()).isEqualTo(published.checksum());
        assertThatThrownBy(() -> restarted.createDraft("test_flow", 1, codec))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-LIFECYCLE-CONFLICT");
    }

    @Test void simultaneousCasHasOneWinner() throws Exception {
        store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger(), conflicts = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                start.await();
                try { store.save("test_flow", 1, 1, "Test", complete("test_flow")); won.incrementAndGet(); }
                catch (StudioProblem ex) {
                    if (ex.code().equals("OR-REVISION-CONFLICT")) conflicts.incrementAndGet();
                    else throw ex;
                }
                return null;
            })).toList();
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(won.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
    }

    @Test void concurrentPublishAndDraftAllocationAreSerialized() throws Exception {
        store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        store.save("test_flow", 1, 1, "Test", complete("test_flow"));
        AtomicInteger published = new AtomicInteger(), publishConflicts = new AtomicInteger();
        race(() -> {
            try { store.publish("test_flow", 1, 2, "reviewed", codec); published.incrementAndGet(); }
            catch (StudioProblem ex) { publishConflicts.incrementAndGet(); }
        });
        assertThat(published.get()).isEqualTo(1);
        assertThat(publishConflicts.get()).isEqualTo(1);

        AtomicInteger drafts = new AtomicInteger(), draftConflicts = new AtomicInteger();
        race(() -> {
            try { store.createDraft("test_flow", 1, codec); drafts.incrementAndGet(); }
            catch (StudioProblem ex) { draftConflicts.incrementAndGet(); }
        });
        assertThat(drafts.get()).isEqualTo(1);
        assertThat(draftConflicts.get()).isEqualTo(1);
        assertThat(store.versions("test_flow", 1, 20).total()).isEqualTo(2);
    }

    @Test void failedPublishRollsBackStateAndRevision() {
        store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        assertThatThrownBy(() -> store.publish("test_flow", 1, 1, "attempt", codec))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-DEF-VALIDATION");
        var draft = store.version("test_flow", 1);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.revision()).isEqualTo(1);
        assertThat(draft.checksum()).isNull();
        assertThat(store.flows("", 1, 20).items().getFirst().currentDraftVersion()).isEqualTo(1);
    }

    @Test void failureAfterVersionUpdateRollsBackPublishedRowAndHead() {
        store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        var before = store.save("test_flow", 1, 1, "Test", complete("test_flow"));
        // Inject failure before the head write, after a real version UPDATE in the same MySQL transaction.
        var faultingJdbc = new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("UPDATE or_studio_flow SET current_draft_version=NULL"))
                    throw new org.springframework.dao.DataAccessResourceFailureException("V1 controlled publish failure");
                return super.update(sql, args);
            }
        };
        var faultingStore = new JdbcStudioStore(faultingJdbc, txm);
        assertThatThrownBy(() -> faultingStore.publish("test_flow", 1, 2, "attempt", codec))
                .hasMessageContaining("V1 controlled publish failure");
        var after = store.version("test_flow", 1);
        assertThat(after.status()).isEqualTo("DRAFT");
        assertThat(after.revision()).isEqualTo(2);
        assertThat(after.documentJson()).isEqualTo(before.documentJson());
        assertThat(after.checksum()).isNull();
        assertThat(after.changeNote()).isNull();
        assertThat(store.flows("", 1, 20).items().getFirst().currentDraftVersion()).isEqualTo(1);
        assertThat(store.flows("", 1, 20).items().getFirst().latestPublishedVersion()).isNull();
        assertThat(store.publish("test_flow", 1, 2, "retry after recovery", codec).status()).isEqualTo("PUBLISHED");
    }

    @Test void publishRevalidatesSavedRevisionRatherThanEarlierValidContent() {
        store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        var valid = store.save("test_flow", 1, 1, "Test", complete("test_flow"));
        assertThat(codec.validate(codec.parse(valid.documentJson())).valid()).isTrue();
        store.save("test_flow", 1, 2, "Test", codec.json(codec.initial("test_flow", "Test")));
        assertThatThrownBy(() -> store.publish("test_flow", 1, 2, "stale", codec))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-REVISION-CONFLICT");
        assertThatThrownBy(() -> store.publish("test_flow", 1, 3, "actual invalid revision", codec))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-DEF-VALIDATION");
        assertThat(store.version("test_flow", 1).revision()).isEqualTo(3);
        assertThat(store.version("test_flow", 1).status()).isEqualTo("DRAFT");
    }

    @Test void jdbcRoundTripPreservesLongDecimalsBigIntegersNullAndCanonicalChecksum() throws Exception {
        var document = codec.parse(java.nio.file.Files.readString(java.nio.file.Path.of(
                "../docs/contracts/studio-m1/fixtures/valid/order-admission.definition.json")));
        ((ObjectNode) document).put("flowId", "test_flow");
        ((ObjectNode) document.at("/stages/2/nodes/0/config/characteristics/0/bins/1"))
                .put("score", new java.math.BigDecimal("0.123456789012345678901234567890123456789"));
        ((ObjectNode) document.at("/stages/2/nodes/1/config/rules/0"))
                .put("value", new java.math.BigInteger("9007199254740993"));
        store.createFlow("test_flow", "Test", codec.json(codec.initial("test_flow", "Test")));
        store.save("test_flow", 1, 1, "Test", codec.json(document));
        var published = store.publish("test_flow", 1, 2, "precision", codec);
        var restarted = new JdbcStudioStore(jdbc, txm);
        assertThat(codec.parse(restarted.version("test_flow", 1).documentJson())).isEqualTo(document);
        assertThat(published.checksum()).isEqualTo(codec.checksum(document));
        assertThat(document.at("/stages/0/when").isNull()).isTrue();
        assertThat(document.at("/absent").isMissingNode()).isTrue();
    }

    private void race(Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                start.await();
                action.run();
                return null;
            })).toList();
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
    }
}
