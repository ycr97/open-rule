package io.openrule.core;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.compiler.FlowCompiler;
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
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTest {

    private ExecutorService pool;
    private FlowExecutor flow;
    private FlowCompiler compiler;

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(new OperatorNodeExecutor()));
        NodeRunner runner = new NodeRunner(pool);
        compiler = new FlowCompiler(registry);
        flow = new FlowExecutor(new SerialStageExecutor(runner),
                new ParallelStageExecutor(runner, pool),
                Map.of(AggregatePolicy.PRIORITY, new PriorityAggregator()));
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private NodeDefinition opNode(String id, String left, String op, Object right,
                                  Decision onHit, boolean stopOnHit) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(left); def.setOperator(op); def.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(10).operatorDef(def).decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(500).build();
    }

    private CompiledFlow orderRisk() {
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
        return compiler.compile(def);
    }

    @Test
    void bigAmount_isRejectedAtHardStage_andSkipsScoring() {
        DecisionContext ctx = new DecisionContext("REQ1", "order_risk", "ORDER_1",
                Map.of("order", Map.of("amount", 80000),
                       "buyer", Map.of("level", "NEW")));
        FlowResult fr = flow.execute(ctx, orderRisk());
        assertThat(fr.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(fr.getHitNodes()).containsExactly("AMOUNT_LIMIT");  // VIP_CHECK 被跳过
    }

    @Test
    void newBuyer_smallAmount_goesToReview() {
        DecisionContext ctx = new DecisionContext("REQ2", "order_risk", "ORDER_2",
                Map.of("order", Map.of("amount", 1000),
                       "buyer", Map.of("level", "NEW")));
        FlowResult fr = flow.execute(ctx, orderRisk());
        assertThat(fr.getDecision()).isEqualTo(Decision.REVIEW);
        assertThat(fr.getHitNodes()).containsExactly("VIP_CHECK");
    }

    @Test
    void vipBuyer_smallAmount_passes() {
        DecisionContext ctx = new DecisionContext("REQ3", "order_risk", "ORDER_3",
                Map.of("order", Map.of("amount", 1000),
                       "buyer", Map.of("level", "VIP")));
        FlowResult fr = flow.execute(ctx, orderRisk());
        assertThat(fr.getDecision()).isEqualTo(Decision.PASS);
        assertThat(fr.getHitNodes()).isEmpty();
    }
}
