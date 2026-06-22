package io.openrule.core.definition;

import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefinitionTest {

    @Test
    void buildsNestedFlowDefinition() {
        OperatorDef op = new OperatorDef();
        op.setLeftFact("fact.order.amount");
        op.setOperator("GT");
        op.setRightValue(50000);

        NodeDefinition node = NodeDefinition.builder()
                .nodeId("AMOUNT_LIMIT").nodeName("金额上限")
                .nodeType(NodeType.OPERATOR).order(20)
                .operatorDef(op)
                .decisionOnHit(Decision.REJECT).stopOnHit(true)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(100)
                .build();

        StageDefinition stage = StageDefinition.builder()
                .stageId("s1").stageName("硬规则").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node)).build();

        FlowDefinition flow = FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").version(1).enabled(true)
                .aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stage)).build();

        assertThat(flow.getStages().get(0).getNodes().get(0).getOperatorDef().getOperator())
                .isEqualTo("GT");
        assertThat(flow.getAggregatePolicy()).isEqualTo(AggregatePolicy.PRIORITY);
    }
}
