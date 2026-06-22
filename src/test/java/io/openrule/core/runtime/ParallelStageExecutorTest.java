package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class ParallelStageExecutorTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() { pool = Executors.newVirtualThreadPerTaskExecutor(); }
    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /**
     * 行为按 nodeId 编排：可指定睡眠毫秒（制造乱序完成）、要写入的 (key,value)、是否 stop。
     */
    static class TimedExecutor implements NodeExecutor {
        record Spec(long sleepMs, String key, Object value, boolean stop) {}
        private final Map<String, Spec> specs;
        TimedExecutor(Map<String, Spec> specs) { this.specs = specs; }
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            Spec s = specs.get(n.getDefinition().getNodeId());
            if (s.sleepMs() > 0) {
                try { Thread.sleep(s.sleepMs()); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return NodeResult.builder().nodeId(n.getDefinition().getNodeId())
                    .success(true).stop(s.stop())
                    .outputs(s.key() == null ? Map.of() : Map.of(s.key(), s.value()))
                    .build();
        }
    }

    private CompiledStage parallelStage(long timeout, List<NodeDefinition> defs) {
        StageDefinition sd = StageDefinition.builder()
                .stageId("s2").executionMode(ExecutionMode.PARALLEL)
                .stageTimeoutMillis(timeout).build();
        return new CompiledStage(sd, defs.stream().map(d -> new CompiledNode(d, null)).toList());
    }

    private NodeDefinition def(String id) {
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(2000).build();
    }

    private ParallelStageExecutor executor(NodeExecutor e) {
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(e)), pool);
        return new ParallelStageExecutor(runner, pool);
    }

    /** 核心：先完成的节点（B，睡得短）写同一个 key，后 order 的 A（睡得久）必须覆盖 B —— 与完成顺序无关。 */
    @Test
    void mergeIsDeterministicByDefinitionOrder_notCompletionOrder() {
        var specs = Map.of(
                "A", new TimedExecutor.Spec(120, "shared", "fromA", false),  // order 靠前，完成靠后
                "B", new TimedExecutor.Spec(10, "shared", "fromB", false));  // order 靠后，完成靠前
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        StageResult sr = executor(new TimedExecutor(specs))
                .execute(ctx, parallelStage(2000, List.of(def("A"), def("B"))));
        // 节点定义顺序 A,B → 合并时 B(后 order) 覆盖 A
        assertThat(ctx.variable("shared")).isEqualTo("fromB");
        // 结果顺序恒按定义顺序
        assertThat(sr.getNodeResults().stream().map(NodeResult::getNodeId).toList())
                .containsExactly("A", "B");
    }

    @Test
    void anyStopTriggersContextStopAfterMerge() {
        var specs = Map.of(
                "A", new TimedExecutor.Spec(0, "ka", 1, false),
                "B", new TimedExecutor.Spec(0, null, null, true));
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        executor(new TimedExecutor(specs))
                .execute(ctx, parallelStage(2000, List.of(def("A"), def("B"))));
        assertThat(ctx.isStopped()).isTrue();
        assertThat(ctx.variable("ka")).isEqualTo(1);  // 合并仍发生
    }

    @Test
    void groupTimeout_stragglerBecomesSkippedResult() {
        var specs = Map.of(
                "FAST", new TimedExecutor.Spec(10, "f", 1, false),
                "SLOW", new TimedExecutor.Spec(5000, "s", 2, false));
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        StageResult sr = executor(new TimedExecutor(specs))
                .execute(ctx, parallelStage(100, List.of(def("FAST"), def("SLOW"))));
        assertThat(sr.getNodeResults()).hasSize(2);
        NodeResult slow = sr.getNodeResults().stream()
                .filter(r -> r.getNodeId().equals("SLOW")).findFirst().orElseThrow();
        assertThat(slow.isSuccess()).isFalse();  // 被取消 → 兜底结果
        assertThat(ctx.variable("f")).isEqualTo(1);
        assertThat(ctx.variable("s")).isNull();   // SLOW 未贡献
    }

    @Test
    void skipsEntireStageWhenAlreadyStopped() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        ctx.stop();
        StageResult sr = executor(new TimedExecutor(Map.of()))
                .execute(ctx, parallelStage(2000, List.of()));
        assertThat(sr.isSkipped()).isTrue();
    }
}
