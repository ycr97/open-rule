package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并行 Stage 正确性模型：
 *  ① 节点在隔离线程池并发执行，只返回 NodeResult，不触碰 context（C1）
 *  ② 任一 ABORT 异常立即传播；否则等待全部完成或整组超时
 *  ③ 合并线程单线程、按节点定义 order 顺序合并 outputs → variables（C2，确定性）
 *  ④ stop 在合并阶段统一判定
 */
public class ParallelStageExecutor {

    private static final long DEFAULT_STAGE_TIMEOUT_MS = 10_000;

    private final NodeRunner nodeRunner;
    private final Executor parallelPool;

    public ParallelStageExecutor(NodeRunner nodeRunner, Executor parallelPool) {
        this.nodeRunner = nodeRunner;
        this.parallelPool = parallelPool;
    }

    public StageResult execute(DecisionContext ctx, CompiledStage stage) {
        boolean runWhenStopped = ctx.isStopped() && !stage.isSkipWhenStopped();
        if (ctx.isStopped() && stage.isSkipWhenStopped()) {
            return StageResult.skipped(stage.getStageId());
        }

        long stageTimeout = stage.getStageTimeoutMillis() > 0
                ? stage.getStageTimeoutMillis() : DEFAULT_STAGE_TIMEOUT_MS;

        List<CompletableFuture<NodeResult>> futures = stage.getNodes().stream()
                .map(node -> CompletableFuture.supplyAsync(
                        () -> nodeRunner.run(ctx, node, runWhenStopped), parallelPool))
                .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        CompletableFuture<Throwable> firstFailure = new CompletableFuture<>();
        futures.forEach(future -> future.whenComplete((result, error) -> {
            if (error != null) {
                firstFailure.complete(unwrap(error));
            }
        }));

        try {
            CompletableFuture.anyOf(all, firstFailure).get(stageTimeout, TimeUnit.MILLISECONDS);
            if (firstFailure.isDone()) {
                cancelAll(futures);
                throw asRuleEngineException(firstFailure.join(), stage.getStageId());
            }
        } catch (TimeoutException te) {
            cancelAll(futures);
        } catch (InterruptedException ie) {
            cancelAll(futures);
            Thread.currentThread().interrupt();
            throw new RuleEngineException("Parallel stage interrupted: " + stage.getStageId(), ie);
        } catch (ExecutionException ee) {
            cancelAll(futures);
            throw asRuleEngineException(unwrap(ee), stage.getStageId());
        }

        // 单线程合并：按节点定义顺序，而非完成顺序
        List<NodeResult> results = new ArrayList<>();
        boolean anyStop = false;
        for (int i = 0; i < futures.size(); i++) {
            NodeResult r = resolveResult(futures.get(i), stage.getNodes().get(i));
            results.add(r);
            ctx.addNodeResult(r);
            r.getOutputs().forEach(ctx::putVariable);
            anyStop |= r.isStop();
        }

        if (anyStop) {
            ctx.stop();
        }
        return StageResult.of(stage.getStageId(), results);
    }

    private NodeResult resolveResult(CompletableFuture<NodeResult> f, CompiledNode node) {
        if (f.isCancelled()) {
            return timeoutResult(node);
        }
        try {
            NodeResult r = f.getNow(null);
            return r != null ? r : timeoutResult(node);
        } catch (Exception e) {
            return errorResult(node, e);
        }
    }

    private NodeResult timeoutResult(CompiledNode node) {
        NodeDefinition def = node.getDefinition();
        return NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .success(false).skipped(true).errorMessage("整组超时被取消")
                .build();
    }

    private NodeResult errorResult(CompiledNode node, Exception e) {
        NodeDefinition def = node.getDefinition();
        return NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .success(false).errorMessage(String.valueOf(e.getMessage()))
                .build();
    }

    private void cancelAll(List<CompletableFuture<NodeResult>> futures) {
        futures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private RuleEngineException asRuleEngineException(Throwable error, String stageId) {
        if (error instanceof RuleEngineException ruleEngineException) {
            return ruleEngineException;
        }
        return new RuleEngineException("Parallel stage failed: " + stageId, error);
    }
}
