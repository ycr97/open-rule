package io.openrule.core.compiler;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Core 编译入口：整体校验、稳定排序、节点 SPI 编译和执行器绑定。 */
public class FlowCompiler {

    private final NodeExecutorRegistry registry;

    public FlowCompiler(NodeExecutorRegistry registry) {
        this.registry = registry;
    }

    public CompiledFlow compile(FlowDefinition definition) {
        validate(definition);
        List<CompiledStage> stages = definition.getStages().stream()
                .sorted(Comparator.comparingInt(StageDefinition::getOrder))
                .map(this::compileStage)
                .toList();
        return new CompiledFlow(definition, stages);
    }

    private CompiledStage compileStage(StageDefinition stage) {
        List<CompiledNode> nodes = stage.getNodes().stream()
                .sorted(Comparator.comparingInt(NodeDefinition::getOrder))
                .map(this::compileNode)
                .toList();
        return new CompiledStage(stage, nodes);
    }

    private CompiledNode compileNode(NodeDefinition node) {
        NodeExecutor executor = registry.getRequired(node.getNodeType());
        return executor.compile(node).withExecutor(executor);
    }

    public void validate(FlowDefinition definition) {
        if (definition == null) {
            throw invalid("Flow definition must not be null");
        }
        if (isBlank(definition.getFlowId())) {
            throw invalid("flowId must not be blank");
        }
        if (definition.getStages() == null || definition.getStages().isEmpty()) {
            throw invalid("stages must not be null or empty: " + definition.getFlowId());
        }

        Set<String> stageIds = new HashSet<>();
        Set<Integer> stageOrders = new HashSet<>();
        Set<String> nodeIds = new HashSet<>();
        for (StageDefinition stage : definition.getStages()) {
            if (stage == null) {
                throw invalid("stage must not be null: " + definition.getFlowId());
            }
            if (isBlank(stage.getStageId())) {
                throw invalid("stageId must not be blank: " + definition.getFlowId());
            }
            if (!stageIds.add(stage.getStageId())) {
                throw invalid("Duplicate stageId: " + stage.getStageId());
            }
            if (!stageOrders.add(stage.getOrder())) {
                throw invalid("Duplicate stage order: " + stage.getOrder());
            }
            if (stage.getExecutionMode() == null) {
                throw invalid("executionMode must not be null: " + stage.getStageId());
            }
            if (stage.getStageTimeoutMillis() < 0) {
                throw invalid("stageTimeoutMillis must not be negative: " + stage.getStageId());
            }
            if (stage.getNodes() == null || stage.getNodes().isEmpty()) {
                throw invalid("nodes must not be null or empty: " + stage.getStageId());
            }

            Set<Integer> nodeOrders = new HashSet<>();
            for (NodeDefinition node : stage.getNodes()) {
                if (node == null) {
                    throw invalid("node must not be null: " + stage.getStageId());
                }
                if (isBlank(node.getNodeId())) {
                    throw invalid("nodeId must not be blank: " + stage.getStageId());
                }
                if (!nodeIds.add(node.getNodeId())) {
                    throw invalid("Duplicate nodeId: " + node.getNodeId());
                }
                if (!nodeOrders.add(node.getOrder())) {
                    throw invalid("Duplicate node order " + node.getOrder()
                            + " in stage " + stage.getStageId());
                }
                if (node.getNodeType() == null) {
                    throw invalid("nodeType must not be null: " + node.getNodeId());
                }
                if (node.getTimeoutMillis() < 0) {
                    throw invalid("timeoutMillis must not be negative: " + node.getNodeId());
                }
                registry.getRequired(node.getNodeType()).validate(node);
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private FlowValidationException invalid(String message) {
        return new FlowValidationException(message);
    }
}
