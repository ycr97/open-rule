package io.openrule.core.runtime;

import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.spi.CompiledNode;
import java.util.List;

/** Stage 编译产物：仅保存执行所需标量和不可变节点列表。 */
public class CompiledStage {
    private final String stageId;
    private final ExecutionMode executionMode;
    private final boolean skipWhenStopped;
    private final long stageTimeoutMillis;
    private final List<CompiledNode> nodes;

    public CompiledStage(StageDefinition definition, List<CompiledNode> nodes) {
        this.stageId = definition.getStageId();
        this.executionMode = definition.getExecutionMode();
        this.skipWhenStopped = definition.isSkipWhenStopped();
        this.stageTimeoutMillis = definition.getStageTimeoutMillis();
        this.nodes = List.copyOf(nodes);
    }

    public String getStageId()              { return stageId; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public boolean isSkipWhenStopped()      { return skipWhenStopped; }
    public long getStageTimeoutMillis()     { return stageTimeoutMillis; }
    public List<CompiledNode> getNodes()    { return nodes; }
}
