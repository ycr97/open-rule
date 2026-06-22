package io.openrule.core.runtime;

import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.spi.CompiledNode;
import java.util.List;

/** Stage 编译产物：定义 + 已编译节点（按 order 排序后）。 */
public class CompiledStage {
    private final StageDefinition definition;
    private final List<CompiledNode> nodes;

    public CompiledStage(StageDefinition definition, List<CompiledNode> nodes) {
        this.definition = definition;
        this.nodes = nodes;
    }

    public String getStageId()              { return definition.getStageId(); }
    public ExecutionMode getExecutionMode() { return definition.getExecutionMode(); }
    public boolean isSkipWhenStopped()      { return definition.isSkipWhenStopped(); }
    public long getStageTimeoutMillis()     { return definition.getStageTimeoutMillis(); }
    public List<CompiledNode> getNodes()    { return nodes; }
}
