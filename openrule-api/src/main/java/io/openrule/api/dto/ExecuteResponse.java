package io.openrule.api.dto;

import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.spring.model.ExecutionOutcome;

import java.util.List;

/** 执行响应（裸 DTO）。nodeResults 仅 debug=true 携带。 */
public record ExecuteResponse(String requestId, String flowId, int flowVersion,
                              Decision decision, String reason, int totalScore,
                              List<String> hitNodes, long costMillis, List<NodeResult> nodeResults) {

    public static ExecuteResponse from(ExecutionOutcome o, boolean debug) {
        var r = o.result();
        return new ExecuteResponse(r.getRequestId(), r.getFlowId(), o.flowVersion(),
                r.getDecision(), r.getReason(), r.getTotalScore(), r.getHitNodes(),
                r.getCostMillis(), debug ? r.getNodeResults() : null);
    }
}
