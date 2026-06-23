package io.openrule.spring.loader;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.spi.CompiledNode;

import java.util.Comparator;
import java.util.List;

/** 把 FlowDefinition 编译成 CompiledFlow（按 order 排序 + 逐节点 SPI compile）；并提供保存期 validate。 */
public class FlowCompiler {

    private final NodeExecutorRegistry registry;

    public FlowCompiler(NodeExecutorRegistry registry) {
        this.registry = registry;
    }

    public CompiledFlow compile(FlowDefinition def) {
        List<CompiledStage> stages = def.getStages().stream()
                .sorted(Comparator.comparingInt(StageDefinition::getOrder))
                .map(this::compileStage)
                .toList();
        return new CompiledFlow(def, stages);
    }

    private CompiledStage compileStage(StageDefinition stage) {
        List<CompiledNode> nodes = stage.getNodes().stream()
                .sorted(Comparator.comparingInt(NodeDefinition::getOrder))
                .map(n -> registry.getRequired(n.getNodeType()).compile(n))
                .toList();
        return new CompiledStage(stage, nodes);
    }

    /** 保存期校验：逐节点调 SPI validate（残缺配置抛 FlowValidationException）。 */
    public void validate(FlowDefinition def) {
        for (StageDefinition stage : def.getStages()) {
            for (NodeDefinition node : stage.getNodes()) {
                registry.getRequired(node.getNodeType()).validate(node);
            }
        }
    }
}
