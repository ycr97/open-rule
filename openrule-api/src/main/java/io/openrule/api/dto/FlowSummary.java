package io.openrule.api.dto;

import io.openrule.core.definition.FlowDefinition;

public record FlowSummary(String flowId, String flowName, int version, boolean enabled) {
    public static FlowSummary from(FlowDefinition d) {
        return new FlowSummary(d.getFlowId(), d.getFlowName(), d.getVersion(), d.isEnabled());
    }
}
