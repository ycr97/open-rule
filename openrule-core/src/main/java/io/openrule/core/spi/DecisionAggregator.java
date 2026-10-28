package io.openrule.core.spi;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.result.NodeResult;
import java.util.List;

public interface DecisionAggregator {
    AggregatePolicy supportPolicy();
    AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx);
}
