package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FirstTerminalAggregatorTest {

    private final FirstTerminalAggregator aggregator = new FirstTerminalAggregator();
    private final DecisionContext context = new DecisionContext("r", "f", "b", Map.of());

    @Test
    void supportsFirstTerminalPolicy() {
        assertThat(aggregator.supportPolicy()).isEqualTo(AggregatePolicy.FIRST_TERMINAL);
    }

    @Test
    void firstStoppedNodeWins_evenWhenLaterDecisionIsRiskier() {
        List<NodeResult> results = List.of(
                result("observed", true, false, Decision.REVIEW, 10),
                result("first", true, true, Decision.REVIEW, 20),
                result("later", true, true, Decision.REJECT, 30));

        AggregateOutcome outcome = aggregator.aggregate(results, context);

        assertThat(outcome.decision()).isEqualTo(Decision.REVIEW);
        assertThat(outcome.reason()).isEqualTo("first-reason");
        assertThat(outcome.totalScore()).isEqualTo(60);
        assertThat(outcome.hitNodes()).containsExactly("observed", "first", "later");
    }

    @Test
    void noTerminalNode_defaultsToPass() {
        AggregateOutcome outcome = aggregator.aggregate(List.of(
                result("review", true, false, Decision.REVIEW, 10)), context);

        assertThat(outcome.decision()).isEqualTo(Decision.PASS);
        assertThat(outcome.reason()).isEmpty();
        assertThat(outcome.totalScore()).isEqualTo(10);
        assertThat(outcome.hitNodes()).containsExactly("review");
    }

    private NodeResult result(String id, boolean hit, boolean stop,
                              Decision decision, int score) {
        return NodeResult.builder().nodeId(id).hit(hit).stop(stop).decision(decision)
                .reason(id + "-reason").score(score).success(true).build();
    }
}
