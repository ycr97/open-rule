package io.openrule.core.definition;

import io.openrule.core.enums.AggregatePolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowDefinition {
    private String  flowId;
    private String  flowName;
    private int     version;
    private boolean enabled;
    private AggregatePolicy aggregatePolicy;
    private List<StageDefinition> stages;
    private Map<String, Object>   metadata;
}
