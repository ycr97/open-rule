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

class PriorityAggregatorTest {

    private final PriorityAggregator agg = new PriorityAggregator();
    private final DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());

    private NodeResult hit(String id, Decision d, int score, String reason) {
        return NodeResult.builder().nodeId(id).hit(true).decision(d).score(score).reason(reason).build();
    }

    @Test
    void supportsPriorityPolicy() {
        assertThat(agg.supportPolicy()).isEqualTo(AggregatePolicy.PRIORITY);
    }

    @Test
    void picksHighestRisk_rejectOverReview() {
        AggregateOutcome out = agg.aggregate(List.of(
                hit("a", Decision.REVIEW, 0, "review原因"),
                hit("b", Decision.REJECT, 0, "reject原因")), ctx);
        assertThat(out.decision()).isEqualTo(Decision.REJECT);
        assertThat(out.reason()).isEqualTo("reject原因");
    }

    @Test
    void defaultsToPassWhenNoDecision() {
        AggregateOutcome out = agg.aggregate(List.of(
                NodeResult.builder().nodeId("a").hit(false).build()), ctx);
        assertThat(out.decision()).isEqualTo(Decision.PASS);
    }

    @Test
    void sumsScoresAndCollectsHitNodes() {
        AggregateOutcome out = agg.aggregate(List.of(
                hit("a", Decision.PASS, 30, "r1"),
                hit("b", Decision.REVIEW, 42, "r2")), ctx);
        assertThat(out.totalScore()).isEqualTo(72);
        assertThat(out.hitNodes()).containsExactly("a", "b");
    }
}
