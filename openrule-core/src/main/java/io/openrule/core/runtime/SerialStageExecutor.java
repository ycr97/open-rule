package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;

import java.util.ArrayList;
import java.util.List;

/** 串行 Stage：节点依次执行，stop 立即生效，outputs 立即合并（单线程，天然安全）。 */
public class SerialStageExecutor {

    private final NodeRunner nodeRunner;

    public SerialStageExecutor(NodeRunner nodeRunner) {
        this.nodeRunner = nodeRunner;
    }

    public StageResult execute(DecisionContext ctx, CompiledStage stage) {
        boolean runWhenStopped = ctx.isStopped() && !stage.isSkipWhenStopped();
        if (ctx.isStopped() && stage.isSkipWhenStopped()) {
            return StageResult.skipped(stage.getStageId());
        }
        List<NodeResult> results = new ArrayList<>();

        for (CompiledNode node : stage.getNodes()) {
            if (ctx.isStopped() && !runWhenStopped) break;

            NodeResult result = nodeRunner.run(ctx, node, runWhenStopped);
            results.add(result);
            ctx.addNodeResult(result);

            result.getOutputs().forEach(ctx::putVariable);

            if (result.isStop()) {
                ctx.stop();
                break;
            }
        }
        return StageResult.of(stage.getStageId(), results);
    }
}
