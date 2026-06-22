package io.openrule.core.runtime;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import java.util.List;

/** Flow 编译产物：定义 + 已编译 Stage（按 order 排序后）。 */
public class CompiledFlow {
    private final FlowDefinition definition;
    private final List<CompiledStage> stages;

    public CompiledFlow(FlowDefinition definition, List<CompiledStage> stages) {
        this.definition = definition;
        this.stages = stages;
    }

    public String getFlowId()                  { return definition.getFlowId(); }
    public int getVersion()                    { return definition.getVersion(); }
    public AggregatePolicy getAggregatePolicy(){ return definition.getAggregatePolicy(); }
    public List<CompiledStage> getStages()     { return stages; }
}
