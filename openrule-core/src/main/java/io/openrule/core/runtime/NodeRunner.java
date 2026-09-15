package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 节点执行唯一入口：超时、FailPolicy、计时、skipped 全在此层（C8）。 */
public class NodeRunner {

    private static final long DEFAULT_TIMEOUT_MS = 3000;

    private final ExecutorService timeoutPool;

    public NodeRunner(ExecutorService timeoutPool) {
        this.timeoutPool = timeoutPool;
    }

    public NodeResult run(DecisionContext ctx, CompiledNode compiled) {
        return run(ctx, compiled, false);
    }

    NodeResult run(DecisionContext ctx, CompiledNode compiled, boolean runWhenStopped) {
        NodeDefinition def = compiled.getDefinition();
        long start = System.currentTimeMillis();

        if (ctx.isStopped() && !runWhenStopped) {
            return skippedResult(def, start);
        }

        NodeExecutor executor = compiled.getExecutor();
        if (executor == null) {
            throw new RuleEngineException(
                    "Compiled node has no executor, compile it with FlowCompiler: " + def.getNodeId());
        }

        try {
            NodeResult result = executeWithTimeout(ctx, compiled, def, executor);
            if (result.isHit() && def.getDecisionOnHit() != null && result.getDecision() == null) {
                result = result.toBuilder().decision(def.getDecisionOnHit()).build();
            }
            if (result.isHit() && def.isStopOnHit()) {
                result = result.toBuilder().stop(true).build();
            }
            return result;
        } catch (Exception e) {
            return applyFailPolicy(def, e, start);
        }
    }

    private NodeResult executeWithTimeout(DecisionContext ctx, CompiledNode compiled,
                                          NodeDefinition def, NodeExecutor executor) throws Exception {
        long timeout = def.getTimeoutMillis() > 0 ? def.getTimeoutMillis() : DEFAULT_TIMEOUT_MS;
        Future<NodeResult> future = timeoutPool.submit(
                () -> executor.execute(ctx, compiled));
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuleEngineException("Node timeout " + timeout + "ms: " + def.getNodeId());
        }
    }

    private NodeResult skippedResult(NodeDefinition def, long start) {
        return NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .skipped(true).success(true)
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private NodeResult applyFailPolicy(NodeDefinition def, Exception e, long start) {
        FailPolicy policy = def.getFailPolicy() != null ? def.getFailPolicy() : FailPolicy.SKIP;
        NodeResult.NodeResultBuilder base = NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .success(false).errorMessage(e.getMessage())
                .costMillis(System.currentTimeMillis() - start);

        return switch (policy) {
            case SKIP   -> base.skipped(true).build();
            case REVIEW -> base.hit(true).decision(Decision.REVIEW)
                               .reason("节点异常降级人审: " + def.getNodeName()).build();
            case REJECT -> base.hit(true).decision(Decision.REJECT).stop(true)
                               .reason("节点异常保守拒绝: " + def.getNodeName()).build();
            case ABORT  -> throw new RuleEngineException("Node abort: " + def.getNodeId(), e);
        };
    }
}
