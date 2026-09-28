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
