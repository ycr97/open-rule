package io.openrule.core.definition;

import io.openrule.core.enums.ExecutionMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageDefinition {
    private String        stageId;
    private String        stageName;
    private int           order;
    private ExecutionMode executionMode;
    private boolean       skipWhenStopped;
    private long          stageTimeoutMillis;
    private List<NodeDefinition> nodes;
}
