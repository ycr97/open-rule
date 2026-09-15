package io.openrule.core.runtime;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.FlowResult;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.DecisionAggregator;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class FlowExecutorTest {

    private ExecutorService pool;
    private FlowExecutor flowExecutor;
    private HitExecutor hitExecutor;

    /** 命中即产出指定 decision + stop 的执行器。 */
    static class HitExecutor implements NodeExecutor {
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            NodeDefinition d = n.getDefinition();
            boolean hit = Boolean.TRUE.equals(c.fact(d.getNodeId() + ".hit"));
            return NodeResult.builder().nodeId(d.getNodeId()).nodeType(NodeType.OPERATOR)
                    .hit(hit).success(true).build();
        }
    }

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        hitExecutor = new HitExecutor();
        NodeRunner runner = new NodeRunner(pool);
        SerialStageExecutor serial = new SerialStageExecutor(runner);
        ParallelStageExecutor parallel = new ParallelStageExecutor(runner, pool);
        DecisionAggregator priority = new PriorityAggregator();
        flowExecutor = new FlowExecutor(serial, parallel,
                Map.of(AggregatePolicy.PRIORITY, priority));
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private NodeDefinition node(String id, Decision onHit, boolean stopOnHit) {
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR)
                .decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(1000).build();
    }

    private CompiledFlow compile(FlowDefinition def) {
        List<CompiledStage> stages = def.getStages().stream()
                .map(s -> new CompiledStage(s,
                        s.getNodes().stream().map(hitExecutor::compile).toList()))
                .toList();
        return new CompiledFlow(def, stages);
    }

    @Test
    void runsStagesAndAggregatesDecision() {
        StageDefinition s1 = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("BLACKLIST", Decision.REJECT, true))).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("order_risk").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(s1)).build();

        DecisionContext ctx = new DecisionContext("R", "order_risk", "BIZ",
                Map.of("BLACKLIST.hit", true));
        FlowResult fr = flowExecutor.execute(ctx, compile(def));

        assertThat(fr.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(fr.getHitNodes()).contains("BLACKLIST");
        assertThat(fr.getFlowId()).isEqualTo("order_risk");
    }

    @Test
    void skipsLaterStageWhenStopped() {
        StageDefinition s1 = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("BLACKLIST", Decision.REJECT, true))).build();
        StageDefinition s2 = StageDefinition.builder()
                .stageId("s2").order(200).executionMode(ExecutionMode.PARALLEL).skipWhenStopped(true)
                .stageTimeoutMillis(2000)
                .nodes(List.of(node("SCORE", Decision.REVIEW, false))).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("f").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(s1, s2)).build();

        DecisionContext ctx = new DecisionContext("R", "f", "BIZ",
                Map.of("BLACKLIST.hit", true, "SCORE.hit", true));
        FlowResult fr = flowExecutor.execute(ctx, compile(def));

        // s1 命中 REJECT 并 stop → s2 跳过 → 最终仍是 REJECT，SCORE 未命中
        assertThat(fr.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(fr.getHitNodes()).doesNotContain("SCORE");
    }

    @Test
    void serialStageWithSkipWhenStoppedFalse_runsAfterPreviousStop() {
        StageDefinition stopping = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("STOP", Decision.REJECT, true))).build();
        StageDefinition alwaysRun = StageDefinition.builder()
                .stageId("s2").order(200).executionMode(ExecutionMode.SERIAL).skipWhenStopped(false)
                .nodes(List.of(node("AUDIT", Decision.REVIEW, false))).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("f").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stopping, alwaysRun)).build();

        FlowResult result = flowExecutor.execute(
                new DecisionContext("R", "f", "BIZ", Map.of(
                        "STOP.hit", true, "AUDIT.hit", true)),
                compile(def));

        assertThat(result.getHitNodes()).containsExactly("STOP", "AUDIT");
    }

    @Test
    void parallelStageWithSkipWhenStoppedFalse_runsAfterPreviousStop() {
        StageDefinition stopping = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("STOP", Decision.REJECT, true))).build();
        StageDefinition alwaysRun = StageDefinition.builder()
                .stageId("s2").order(200).executionMode(ExecutionMode.PARALLEL).skipWhenStopped(false)
                .stageTimeoutMillis(2000)
                .nodes(List.of(node("AUDIT", Decision.REVIEW, false))).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("f").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stopping, alwaysRun)).build();

        FlowResult result = flowExecutor.execute(
                new DecisionContext("R", "f", "BIZ", Map.of(
                        "STOP.hit", true, "AUDIT.hit", true)),
                compile(def));

        assertThat(result.getHitNodes()).containsExactly("STOP", "AUDIT");
    }

    @Test
    void alwaysRunSerialStage_stillHonorsStopProducedInsideThatStage() {
        StageDefinition stopping = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("STOP", Decision.REJECT, true))).build();
        StageDefinition alwaysRun = StageDefinition.builder()
                .stageId("s2").order(200).executionMode(ExecutionMode.SERIAL).skipWhenStopped(false)
                .nodes(List.of(
                        node("AUDIT_STOP", Decision.REVIEW, true),
                        node("NEVER", Decision.REVIEW, false)))
                .build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("f").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stopping, alwaysRun)).build();

        FlowResult result = flowExecutor.execute(
                new DecisionContext("R", "f", "BIZ", Map.of(
                        "STOP.hit", true, "AUDIT_STOP.hit", true, "NEVER.hit", true)),
                compile(def));

        assertThat(result.getHitNodes()).containsExactly("STOP", "AUDIT_STOP");
    }
}
