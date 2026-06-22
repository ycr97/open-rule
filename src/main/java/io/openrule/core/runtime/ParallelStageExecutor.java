package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并行 Stage 正确性模型：
 *  ① 节点在隔离线程池并发执行，只返回 NodeResult，不触碰 context（C1）
 *  ② allOf 等待全部完成或整组超时
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
        if (ctx.isStopped()) {
            return StageResult.skipped(stage.getStageId());
        }

        long stageTimeout = stage.getStageTimeoutMillis() > 0
                ? stage.getStageTimeoutMillis() : DEFAULT_STAGE_TIMEOUT_MS;

        List<CompletableFuture<NodeResult>> futures = stage.getNodes().stream()
                .map(node -> CompletableFuture.supplyAsync(
                        () -> nodeRunner.run(ctx, node), parallelPool))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(stageTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            futures.forEach(f -> f.cancel(true));
        } catch (Exception ignored) {
            // 个别节点异常已被 NodeRunner 兜底；整体异常不阻断合并
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
}
