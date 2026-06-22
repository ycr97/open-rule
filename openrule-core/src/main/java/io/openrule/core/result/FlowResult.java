package io.openrule.core.result;

import io.openrule.core.enums.Decision;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** 流程最终结果。 */
@Getter
@Builder
public class FlowResult {
    private String   requestId;
    private String   flowId;
    private String   bizId;
    private Decision decision;
    private String   reason;
    private int      totalScore;
    private List<String> hitNodes;
    private List<NodeResult> nodeResults;
    private long     costMillis;
}
