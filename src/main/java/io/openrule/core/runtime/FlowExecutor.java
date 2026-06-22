package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.FlowResult;
import io.openrule.core.spi.AggregateOutcome;
import io.openrule.core.spi.DecisionAggregator;

import java.util.Map;

/** 流程执行入口：调度 Stage → 聚合决策（finalDecision 唯一写入点，C3）。 */
public class FlowExecutor {

    private final SerialStageExecutor serialExecutor;
    private final ParallelStageExecutor parallelExecutor;
    private final Map<AggregatePolicy, DecisionAggregator> aggregators;

    public FlowExecutor(SerialStageExecutor serialExecutor,
                        ParallelStageExecutor parallelExecutor,
                        Map<AggregatePolicy, DecisionAggregator> aggregators) {
        this.serialExecutor = serialExecutor;
        this.parallelExecutor = parallelExecutor;
        this.aggregators = aggregators;
    }

    public FlowResult execute(DecisionContext ctx, CompiledFlow flow) {
        long start = System.currentTimeMillis();

        for (CompiledStage stage : flow.getStages()) {
            if (ctx.isStopped() && stage.isSkipWhenStopped()) {
                continue;
            }
            if (stage.getExecutionMode() == ExecutionMode.SERIAL) {
                serialExecutor.execute(ctx, stage);
            } else {
                parallelExecutor.execute(ctx, stage);
            }
        }

        AggregatePolicy policy = flow.getAggregatePolicy() != null
                ? flow.getAggregatePolicy() : AggregatePolicy.PRIORITY;
        DecisionAggregator aggregator = aggregators.get(policy);
        if (aggregator == null) {
            throw new RuleEngineException("No aggregator for policy: " + policy);
        }
        AggregateOutcome outcome = aggregator.aggregate(ctx.getNodeResults(), ctx);
        ctx.setFinalDecision(outcome.decision());
        ctx.setFinalReason(outcome.reason());

        return FlowResult.builder()
                .requestId(ctx.getRequestId())
                .flowId(ctx.getFlowId())
                .bizId(ctx.getBizId())
                .decision(outcome.decision())
                .reason(outcome.reason())
                .totalScore(outcome.totalScore())
                .hitNodes(outcome.hitNodes())
                .nodeResults(ctx.getNodeResults())
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }
}
