package io.openrule.core.context;

import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionContextTest {

    private DecisionContext ctx() {
        return new DecisionContext("REQ1", "flowA", "BIZ1",
                Map.of("order", Map.of("amount", 100)));
    }

    @Test
    void exposesReadonlyIdsAndFacts() {
        DecisionContext c = ctx();
        assertThat(c.getRequestId()).isEqualTo("REQ1");
        assertThat(c.getFlowId()).isEqualTo("flowA");
        assertThat(c.getBizId()).isEqualTo("BIZ1");
        assertThat(c.getFacts().getByPath("order.amount")).isEqualTo(100);
    }

    @Test
    void variables_areReadWrite() {
        DecisionContext c = ctx();
        assertThat(c.variable("k")).isNull();
        c.putVariable("k", 7);
        assertThat(c.variable("k")).isEqualTo(7);
    }

    @Test
    void stop_isVisible() {
        DecisionContext c = ctx();
        assertThat(c.isStopped()).isFalse();
        c.stop();
        assertThat(c.isStopped()).isTrue();
    }

    @Test
    void nodeResults_accumulate() {
        DecisionContext c = ctx();
        c.addNodeResult(NodeResult.builder().nodeId("a").build());
        c.addNodeResult(NodeResult.builder().nodeId("b").build());
        assertThat(c.getNodeResults()).hasSize(2);
    }

    @Test
    void finalDecision_isSettable() {
        DecisionContext c = ctx();
        c.setFinalDecision(Decision.REVIEW);
        c.setFinalReason("超阈值");
        assertThat(c.getFinalDecision()).isEqualTo(Decision.REVIEW);
        assertThat(c.getFinalReason()).isEqualTo("超阈值");
    }
}
