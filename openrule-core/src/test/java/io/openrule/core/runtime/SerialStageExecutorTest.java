package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
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

class SerialStageExecutorTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() { pool = Executors.newVirtualThreadPerTaskExecutor(); }
    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /** 一个把固定 NodeResult 原样返回的执行器（按 nodeId 区分行为）。 */
    static class ScriptedExecutor implements NodeExecutor {
        private final Map<String, NodeResult> byId;
        ScriptedExecutor(Map<String, NodeResult> byId) { this.byId = byId; }
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            return byId.get(n.getDefinition().getNodeId());
        }
    }

    private CompiledStage stage(List<NodeDefinition> defs) {
        StageDefinition sd = StageDefinition.builder()
                .stageId("s1").executionMode(ExecutionMode.SERIAL).skipWhenStopped(true).build();
        return new CompiledStage(sd, defs.stream().map(d -> new CompiledNode(d, null)).toList());
    }

    private NodeDefinition def(String id) {
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR).timeoutMillis(1000).build();
    }

    @Test
    void mergesOutputsIntoVariables() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        NodeResult r = NodeResult.builder().nodeId("A").outputs(Map.of("k", 1)).success(true).build();
        ScriptedExecutor exec = new ScriptedExecutor(Map.of("A", r));
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(exec)), pool);
        SerialStageExecutor serial = new SerialStageExecutor(runner);

        StageResult sr = serial.execute(ctx, stage(List.of(def("A"))));
        assertThat(ctx.variable("k")).isEqualTo(1);
        assertThat(sr.getNodeResults()).hasSize(1);
    }

    @Test
    void stopShortCircuitsRemainingNodes() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        NodeResult a = NodeResult.builder().nodeId("A").stop(true).success(true).build();
        NodeResult b = NodeResult.builder().nodeId("B").success(true).build();
        ScriptedExecutor exec = new ScriptedExecutor(Map.of("A", a, "B", b));
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(exec)), pool);
        SerialStageExecutor serial = new SerialStageExecutor(runner);

        StageResult sr = serial.execute(ctx, stage(List.of(def("A"), def("B"))));
        assertThat(ctx.isStopped()).isTrue();
        assertThat(sr.getNodeResults()).hasSize(1);  // B 未执行
    }
}
