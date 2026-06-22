package io.openrule.core.demo;

import io.openrule.core.aggregate.PriorityAggregator;
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
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.core.spi.CompiledNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** M1 验收：纯 Java 手动装配，跑通 order_risk 流程。 */
public class M1Demo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeRunner runner = new NodeRunner(
                new NodeExecutorRegistry(List.of(new OperatorNodeExecutor())), pool);
        FlowExecutor flow = new FlowExecutor(
                new SerialStageExecutor(runner),
                new ParallelStageExecutor(runner, pool),
                Map.of(AggregatePolicy.PRIORITY, new PriorityAggregator()));

        CompiledFlow compiled = buildOrderRisk();

        run(flow, compiled, "大额订单", Map.of(
                "order", Map.of("amount", 80000), "buyer", Map.of("level", "NEW")));
        run(flow, compiled, "新买家小额", Map.of(
                "order", Map.of("amount", 1000), "buyer", Map.of("level", "NEW")));
        run(flow, compiled, "VIP小额", Map.of(
                "order", Map.of("amount", 1000), "buyer", Map.of("level", "VIP")));

        pool.shutdownNow();
    }

    private static void run(FlowExecutor flow, CompiledFlow compiled,
                            String label, Map<String, Object> facts) {
        DecisionContext ctx = new DecisionContext("REQ-" + label, "order_risk", "BIZ", facts);
        FlowResult fr = flow.execute(ctx, compiled);
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

    private static CompiledFlow buildOrderRisk() {
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
        FlowDefinition def = FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").version(3).enabled(true)
                .aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(hard, scoring)).build();
        List<CompiledStage> stages = def.getStages().stream()
                .map(s -> new CompiledStage(s,
                        s.getNodes().stream().map(n -> new CompiledNode(n, null)).toList()))
                .toList();
        return new CompiledFlow(def, stages);
    }
}
