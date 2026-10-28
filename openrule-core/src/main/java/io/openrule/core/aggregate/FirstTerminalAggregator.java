package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import io.openrule.core.spi.DecisionAggregator;

import java.util.List;

/** 按执行结果顺序采用第一个 stop=true 节点的决策。 */
public class FirstTerminalAggregator implements DecisionAggregator {

    @Override
    public AggregatePolicy supportPolicy() {
        return AggregatePolicy.FIRST_TERMINAL;
    }

    @Override
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext context) {
        NodeResult terminal = results.stream().filter(NodeResult::isStop).findFirst().orElse(null);
        Decision decision = terminal != null && terminal.getDecision() != null
                ? terminal.getDecision() : Decision.PASS;
        String reason = terminal != null && terminal.getReason() != null
                ? terminal.getReason() : "";
        int totalScore = results.stream().mapToInt(NodeResult::getScore).sum();
        List<String> hitNodes = results.stream()
                .filter(NodeResult::isHit)
                .map(NodeResult::getNodeId)
                .toList();
        return new AggregateOutcome(decision, reason, totalScore, hitNodes);
    }
}
