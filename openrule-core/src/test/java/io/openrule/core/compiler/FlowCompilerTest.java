package io.openrule.core.compiler;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowCompilerTest {

    private final OperatorNodeExecutor operator = new OperatorNodeExecutor();
    private final FlowCompiler compiler = new FlowCompiler(
            new NodeExecutorRegistry(List.of(operator)));

    @Test
    void compile_sortsAndSnapshotsDefinitionsAndCollections() {
        List<Object> acceptedLevels = new ArrayList<>(List.of("VIP"));
        NodeDefinition later = node("later", 20, "fact.level", "IN", acceptedLevels);
        NodeDefinition earlier = node("earlier", 10, "fact.amount", "GT", 100);
        List<NodeDefinition> nodes = new ArrayList<>(List.of(later, earlier));
        StageDefinition stage = StageDefinition.builder()
                .stageId("risk").order(100).executionMode(ExecutionMode.SERIAL)
                .skipWhenStopped(true).nodes(nodes).build();
        List<StageDefinition> stages = new ArrayList<>(List.of(stage));
        FlowDefinition definition = FlowDefinition.builder()
                .flowId("order-risk").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(stages).build();

        CompiledFlow compiled = compiler.compile(definition);

        definition.setFlowId("mutated-flow");
        stage.setStageId("mutated-stage");
        earlier.setNodeId("mutated-node");
        acceptedLevels.add("BLACKLIST");
        nodes.clear();
        stages.clear();

        assertThat(compiled.getFlowId()).isEqualTo("order-risk");
        assertThat(compiled.getStages()).hasSize(1);
        assertThat(compiled.getStages().getFirst().getStageId()).isEqualTo("risk");
        assertThat(compiled.getStages().getFirst().getNodes())
                .extracting(n -> n.getDefinition().getNodeId())
                .containsExactly("earlier", "later");
        assertThat(compiled.getStages().getFirst().getNodes().get(1)
                .getDefinition().getOperatorDef().getRightValue())
                .isEqualTo(List.of("VIP"));
        assertThatThrownBy(() -> compiled.getStages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> compiled.getStages().getFirst().getNodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compiledNodeOwnsExecutor_nodeRunnerNeedsNoRegistry() {
        CompiledFlow compiled = compiler.compile(flow(List.of(
                stage("s1", 100, ExecutionMode.SERIAL,
                        List.of(node("n1", 10, "fact.amount", "GT", 100))))));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeRunner runner = new NodeRunner(pool);
            assertThat(runner.run(
                    new DecisionContext("r", "f", "b", Map.of("amount", 101)),
                    compiled.getStages().getFirst().getNodes().getFirst()).isHit()).isTrue();
        }
    }

    private FlowDefinition flow(List<StageDefinition> stages) {
        return FlowDefinition.builder()
                .flowId("f").aggregatePolicy(AggregatePolicy.PRIORITY).stages(stages).build();
    }

    private StageDefinition stage(String id, int order, ExecutionMode mode,
                                  List<NodeDefinition> nodes) {
        return StageDefinition.builder().stageId(id).order(order).executionMode(mode)
                .skipWhenStopped(true).nodes(nodes).build();
    }

    private NodeDefinition node(String id, int order, String left, String op, Object right) {
        OperatorDef operatorDef = new OperatorDef();
        operatorDef.setLeftFact(left);
        operatorDef.setOperator(op);
        operatorDef.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(order).operatorDef(operatorDef).failPolicy(FailPolicy.SKIP).build();
    }
}
