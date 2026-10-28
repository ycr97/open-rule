package io.openrule.core.runtime;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import java.util.List;

/** Flow 编译产物：仅保存执行所需标量和不可变 Stage 列表。 */
public class CompiledFlow {
    private final String flowId;
    private final int version;
    private final AggregatePolicy aggregatePolicy;
    private final List<CompiledStage> stages;

    public CompiledFlow(FlowDefinition definition, List<CompiledStage> stages) {
        this.flowId = definition.getFlowId();
        this.version = definition.getVersion();
        this.aggregatePolicy = definition.getAggregatePolicy();
        this.stages = List.copyOf(stages);
    }

    public String getFlowId()                  { return flowId; }
    public int getVersion()                    { return version; }
    public AggregatePolicy getAggregatePolicy(){ return aggregatePolicy; }
    public List<CompiledStage> getStages()     { return stages; }
}
