package io.openrule.core.compiler;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowCompilerValidationTest {

    private final FlowCompiler compiler = new FlowCompiler(
            new NodeExecutorRegistry(List.of(new OperatorNodeExecutor())));

    @Test
    void rejectsNullFlowAndBlankFlowId() {
        assertInvalid(null, "Flow definition");
        assertInvalid(FlowDefinition.builder().flowId(" ").stages(List.of()).build(), "flowId");
    }

    @Test
    void rejectsNullOrEmptyStages() {
        assertInvalid(FlowDefinition.builder().flowId("f").stages(null).build(), "stages");
        assertInvalid(FlowDefinition.builder().flowId("f").stages(List.of()).build(), "stages");
    }

    @Test
    void rejectsDuplicateStageIdAndOrder() {
        assertInvalid(flow(List.of(stage("same", 10, List.of(node("n1", 10))),
                stage("same", 20, List.of(node("n2", 10))))), "stageId");
        assertInvalid(flow(List.of(stage("s1", 10, List.of(node("n1", 10))),
                stage("s2", 10, List.of(node("n2", 10))))), "stage order");
    }

    @Test
    void rejectsMissingExecutionModeAndInvalidStageTimeout() {
        StageDefinition missingMode = stage("s1", 10, List.of(node("n1", 10)));
        missingMode.setExecutionMode(null);
        assertInvalid(flow(List.of(missingMode)), "executionMode");

        StageDefinition negativeTimeout = stage("s1", 10, List.of(node("n1", 10)));
        negativeTimeout.setStageTimeoutMillis(-1);
        assertInvalid(flow(List.of(negativeTimeout)), "stageTimeoutMillis");
    }

    @Test
    void rejectsNullOrEmptyNodes() {
        StageDefinition nullNodes = stage("s1", 10, List.of(node("n1", 10)));
        nullNodes.setNodes(null);
        assertInvalid(flow(List.of(nullNodes)), "nodes");
        assertInvalid(flow(List.of(stage("s1", 10, List.of()))), "nodes");
    }

    @Test
    void rejectsDuplicateNodeIdAcrossFlowAndOrderWithinStage() {
        assertInvalid(flow(List.of(stage("s1", 10, List.of(node("same", 10))),
                stage("s2", 20, List.of(node("same", 10))))), "nodeId");
        assertInvalid(flow(List.of(stage("s1", 10,
                List.of(node("n1", 10), node("n2", 10))))), "node order");
    }

    @Test
    void rejectsMissingNodeTypeAndNegativeTimeout() {
        NodeDefinition missingType = node("n1", 10);
        missingType.setNodeType(null);
        assertInvalid(flow(List.of(stage("s1", 10, List.of(missingType)))), "nodeType");

        NodeDefinition negativeTimeout = node("n1", 10);
        negativeTimeout.setTimeoutMillis(-1);
        assertInvalid(flow(List.of(stage("s1", 10, List.of(negativeTimeout)))), "timeoutMillis");
    }

    private void assertInvalid(FlowDefinition definition, String message) {
        assertThatThrownBy(() -> compiler.validate(definition))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining(message);
    }

    private FlowDefinition flow(List<StageDefinition> stages) {
        return FlowDefinition.builder().flowId("f").stages(stages).build();
    }

    private StageDefinition stage(String id, int order, List<NodeDefinition> nodes) {
        return StageDefinition.builder().stageId(id).order(order)
                .executionMode(ExecutionMode.SERIAL).nodes(nodes).build();
    }

    private NodeDefinition node(String id, int order) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact("fact.amount");
        def.setOperator("GT");
        def.setRightValue(100);
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR)
                .order(order).operatorDef(def).build();
    }
}
