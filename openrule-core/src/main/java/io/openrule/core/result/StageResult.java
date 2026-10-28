package io.openrule.core.result;

import lombok.Getter;

import java.util.List;

/** Stage 执行结果。 */
@Getter
public class StageResult {

    private final String stageId;
    private final List<NodeResult> nodeResults;
    private final boolean skipped;

    private StageResult(String stageId, List<NodeResult> nodeResults, boolean skipped) {
        this.stageId = stageId;
        this.nodeResults = nodeResults;
        this.skipped = skipped;
    }

    public static StageResult of(String stageId, List<NodeResult> results) {
        return new StageResult(stageId, results, false);
    }

    public static StageResult skipped(String stageId) {
        return new StageResult(stageId, List.of(), true);
    }
}
