package io.openrule.core.studio;

import io.openrule.core.studio.StudioModel.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class StudioB3NodeTest {
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Ref x = new Ref(Source.FACT, "/x");

    @AfterEach void close() { pool.shutdownNow(); }

    @Test void ruleSetFirstAndAllMatchPreserveOrderAndNullMiss() {
        List<Rule> rules = List.of(new Rule("first", new Compare(x, "gte", BigDecimal.ONE), "A", "A_HIT"),
                new Rule("second", new Compare(x, "gte", BigDecimal.ONE), "B", "B_HIT"));
        NodeResult first = run(new RuleSet("out", "FIRST_MATCH", rules), Map.of("x", BigDecimal.ONE));
        assertThat(first.outputs()).containsEntry("out", "A");
        assertThat(first.reasonCodes()).containsExactly("A_HIT");
        assertThat(first.details()).hasSize(1);
        NodeResult all = run(new RuleSet("out", "ALL_MATCH", rules), Map.of("x", BigDecimal.ONE));
        assertThat(all.outputs()).containsEntry("out", List.of("A", "B"));
        assertThat(all.reasonCodes()).containsExactly("A_HIT", "B_HIT");
        assertThat(all.details()).extracting(detail -> ((RuleDetail) detail).ruleId())
                .containsExactly("first", "second");
        NodeResult miss = run(new RuleSet("out", "FIRST_MATCH", rules), Map.of("x", BigDecimal.ZERO));
        assertThat(miss.hit()).isFalse();
        assertThat(miss.outputs()).containsKey("out").containsEntry("out", null);
        assertThat(miss.details()).isEmpty();
        NodeResult allMiss = run(new RuleSet("out", "ALL_MATCH", rules), Map.of("x", BigDecimal.ZERO));
        assertThat(allMiss.outputs()).containsEntry("out", null);
    }

    @Test void scorecardFailsWithoutExactlyOneBinAtRuntime() {
        Scorecard overlap = new Scorecard("out", List.of(new Characteristic("dim", "Dimension", List.of(
                new Bin("a", new Presence("is-present", x), BigDecimal.ONE, "A"),
                new Bin("b", new Compare(x, "gte", BigDecimal.ZERO), BigDecimal.TEN, "B")))));
        NodeResult many = run(overlap, Map.of("x", BigDecimal.ONE));
        assertThat(many.status()).isEqualTo(Status.FAILED);
        assertThat(many.failure()).contains("Multiple score bins matched");
        assertThat(many.outputs()).isEmpty();
        // Dynamic mixed conditions are needed to test runtime no-match, because simple numeric gaps are rejected at compile time.
        Scorecard dynamicGap = new Scorecard("out", List.of(new Characteristic("dim", "Dimension", List.of(
                new Bin("a", new Compare(x, "eq", BigDecimal.ONE), BigDecimal.ONE, "A"),
                new Bin("b", new Presence("is-missing", x), BigDecimal.TEN, "B")))));
        NodeResult none = run(dynamicGap, Map.of("x", BigDecimal.ZERO));
        assertThat(none.status()).isEqualTo(Status.FAILED);
        assertThat(none.failure()).contains("No score bin matched");
        assertThat(none.outputs()).isEmpty();
    }

    private NodeResult run(Config config, Map<String, Object> facts) {
        Node node = new Node("node", 1, config instanceof Scorecard ? "openrule.scorecard" : "openrule.rule-set",
                1, 1000, FailPolicy.CONTINUE, config);
        Stage stage = new Stage("work", 1, Mode.SERIAL, null, 1000, List.of(node));
        Node terminal = new Node("end", 1, "openrule.terminal", 1, 1000, null,
                new Terminal("DONE", List.of("DONE"), null));
        Flow flow = new Flow(2, "test_flow", "Test", "1", "", List.of(stage,
                new Stage("last", 2, Mode.SERIAL, null, 1000, List.of(terminal))));
        return new StudioEngine(pool).execute(flow, facts, 3000).nodeResults().getFirst();
    }
}
