package io.openrule.core.definition;

import io.openrule.core.enums.AggregatePolicy;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class FlowDefinition {
    private String  flowId;
    private String  flowName;
    private int     version;
    private boolean enabled;
    private AggregatePolicy aggregatePolicy;
    private List<StageDefinition> stages;
    private Map<String, Object>   metadata;
}
