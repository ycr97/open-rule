package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import io.openrule.core.spi.DecisionAggregator;

import java.util.ArrayList;
import java.util.List;

/** 默认聚合器：按 Decision 风险优先级取最高（REJECT &gt; REVIEW &gt; LIMIT &gt; PASS）。 */
public class PriorityAggregator implements DecisionAggregator {

    @Override
    public AggregatePolicy supportPolicy() { return AggregatePolicy.PRIORITY; }

    @Override
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx) {
        Decision highest = Decision.PASS;
        List<String> hitNodes = new ArrayList<>();
        StringBuilder reason = new StringBuilder();

        for (NodeResult r : results) {
            if (r.isHit()) {
                hitNodes.add(r.getNodeId());
            }
            if (r.getDecision() != null && r.getDecision().riskierThan(highest)) {
                highest = r.getDecision();
                reason.setLength(0);
                reason.append(r.getReason() == null ? "" : r.getReason());
            }
        }
        int totalScore = results.stream().mapToInt(NodeResult::getScore).sum();
        return new AggregateOutcome(highest, reason.toString(), totalScore, hitNodes);
    }
}
