package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeRunnerTest {

    private ExecutorService pool;
    private DecisionContext ctx;

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        ctx = new DecisionContext("R", "f", "b", Map.of());
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /** 用一个可配置的执行器：要么返回固定结果，要么抛异常，要么睡眠。 */
    static class ProgrammableExecutor implements NodeExecutor {
        Runnable behavior;
        NodeResult result;
        ProgrammableExecutor(Runnable behavior, NodeResult result) {
            this.behavior = behavior; this.result = result;
        }
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            if (behavior != null) behavior.run();
            return result;
        }
    }

    private NodeRunner runnerWith(NodeExecutor e) {
        return new NodeRunner(new NodeExecutorRegistry(List.of(e)), pool);
    }

    private CompiledNode node(FailPolicy policy, long timeout, boolean stopOnHit, Decision onHit) {
        return new CompiledNode(NodeDefinition.builder()
                .nodeId("N").nodeName("节点").nodeType(NodeType.OPERATOR)
                .failPolicy(policy).timeoutMillis(timeout)
                .stopOnHit(stopOnHit).decisionOnHit(onHit).build(), null);
    }

    @Test
    void skipsWhenContextStopped() {
        ctx.stop();
        NodeRunner runner = runnerWith(new ProgrammableExecutor(null,
                NodeResult.builder().nodeId("N").hit(true).build()));
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 1000, false, null));
        assertThat(r.isSkipped()).isTrue();
        assertThat(r.isHit()).isFalse();
    }

    @Test
    void attachesDecisionOnHitAndStopOnHit() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(null,
                NodeResult.builder().nodeId("N").nodeType(NodeType.OPERATOR).hit(true).build()));
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 1000, true, Decision.REJECT));
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(r.isStop()).isTrue();
    }

    @Test
    void failPolicySkip_marksSkippedAndContinues() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 1000, false, null));
        assertThat(r.isSkipped()).isTrue();
        assertThat(r.isSuccess()).isFalse();
    }

    @Test
    void failPolicyReview_producesReview() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        NodeResult r = runner.run(ctx, node(FailPolicy.REVIEW, 1000, false, null));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void failPolicyReject_producesRejectAndStop() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        NodeResult r = runner.run(ctx, node(FailPolicy.REJECT, 1000, false, null));
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(r.isStop()).isTrue();
    }

    @Test
    void failPolicyAbort_throws() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        assertThatThrownBy(() -> runner.run(ctx, node(FailPolicy.ABORT, 1000, false, null)))
                .isInstanceOf(RuleEngineException.class);
    }

    @Test
    void timeout_isTreatedAsFailureUnderPolicy() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { try { Thread.sleep(500); } catch (InterruptedException ignored) {} }, null));
        // SKIP 策略：超时被当作异常 → skipped
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 50, false, null));
        assertThat(r.isSkipped()).isTrue();
        assertThat(r.isSuccess()).isFalse();
    }
}
