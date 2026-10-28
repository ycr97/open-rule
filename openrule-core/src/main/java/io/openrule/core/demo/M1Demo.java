package io.openrule.core.demo;

import io.openrule.core.OpenRuleEngine;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.FlowResult;

import java.util.List;
import java.util.Map;

/** M1.5 验收：纯 Java Facade 从 Definition 直接编译并执行。 */
public class M1Demo {

    public static void main(String[] args) {
        try (OpenRuleEngine engine = OpenRuleEngine.create()) {
            FlowDefinition definition = buildOrderRisk();
            run(engine, definition, "大额订单", Map.of(
                    "order", Map.of("amount", 80000), "buyer", Map.of("level", "NEW")));
            run(engine, definition, "新买家小额", Map.of(
                    "order", Map.of("amount", 1000), "buyer", Map.of("level", "NEW")));
            run(engine, definition, "VIP小额", Map.of(
                    "order", Map.of("amount", 1000), "buyer", Map.of("level", "VIP")));
        }
    }

    private static void run(OpenRuleEngine engine, FlowDefinition definition,
                            String label, Map<String, Object> facts) {
        DecisionContext ctx = new DecisionContext("REQ-" + label, "order_risk", "BIZ", facts);
        FlowResult fr = engine.execute(definition, ctx);
        System.out.printf("[%s] decision=%s hitNodes=%s cost=%dms%n",
                label, fr.getDecision(), fr.getHitNodes(), fr.getCostMillis());
    }

    private static NodeDefinition opNode(String id, String left, String op, Object right,
                                         Decision onHit, boolean stopOnHit) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(left); def.setOperator(op); def.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(10).operatorDef(def).decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(500).build();
    }

    private static FlowDefinition buildOrderRisk() {
        StageDefinition hard = StageDefinition.builder()
                .stageId("s1").stageName("硬规则").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(opNode("AMOUNT_LIMIT", "fact.order.amount", "GT", 50000,
                        Decision.REJECT, true)))
                .build();
        StageDefinition scoring = StageDefinition.builder()
                .stageId("s2").stageName("并行评分").order(200)
                .executionMode(ExecutionMode.PARALLEL).skipWhenStopped(true).stageTimeoutMillis(2000)
                .nodes(List.of(opNode("VIP_CHECK", "fact.buyer.level", "EQ", "NEW",
                        Decision.REVIEW, false)))
                .build();
        return FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").version(3).enabled(true)
                .aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(hard, scoring)).build();
    }
}
