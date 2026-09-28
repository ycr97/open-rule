package io.openrule.core.studio;

import io.openrule.core.studio.StudioModel.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioB1Test {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach void close() { pool.shutdownNow(); }

    private static Compare compare(Source source, String pointer, String op, Object value) {
        return new Compare(new Ref(source, pointer), op, value);
    }

    private static Node operator(String id, int order, String output, Condition condition, FailPolicy policy) {
        return new Node(id, order, "openrule.operator", 1, 100, policy,
                new Operator(condition, output, "MATCHED"));
    }

    private static Node terminal(String id, String code) {
        return new Node(id, 1, "openrule.terminal", 1, 100, null,
                new Terminal(code, List.of("RULE"), null));
    }

    private static Stage stage(String id, int order, Mode mode, Condition when, Node... nodes) {
        return new Stage(id, order, mode, when, 200, List.of(nodes));
    }

    private static Flow flow(Stage... stages) {
        return new Flow(2, "test_flow", "Test", "1", "", List.of(stages));
    }

    @Test void conditionPreservesMissingNullAndDecimalSemantics() {
        Map<String, Object> facts = Map.of("amount", new BigDecimal("9007199254740993"),
                "nested", new LinkedHashMap<>(Map.of("value", "x")));
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("value", null);
        assertThat(StudioConditions.evaluate(new Presence("is-present", new Ref(Source.FACT, "/value")),
                nullable, Map.of(), Map.of())).isTrue();
        assertThat(StudioConditions.evaluate(new Presence("is-missing", new Ref(Source.FACT, "/value")),
                nullable, Map.of(), Map.of())).isFalse();
        assertThat(StudioConditions.evaluate(new Presence("is-missing", new Ref(Source.FACT, "/missing")),
                nullable, Map.of(), Map.of())).isTrue();
        assertThat(StudioConditions.evaluate(compare(Source.FACT, "/amount", "gt",
                new BigDecimal("9007199254740992")), facts, Map.of(), Map.of())).isTrue();
        assertThat(StudioConditions.evaluate(compare(Source.FACT, "/amount", "eq",
                new BigDecimal("9007199254740993.0")), facts, Map.of(), Map.of())).isTrue();
        assertThatThrownBy(() -> StudioConditions.evaluate(compare(Source.FACT, "/amount", "eq", "9007199254740993"),
                facts, Map.of(), Map.of())).isInstanceOf(StudioConditions.ConditionFailure.class);
    }

    @Test void shortCircuitAvoidsMissingFact() {
        Condition condition = new Group("any", List.of(
                compare(Source.FACT, "/ok", "eq", true),
                compare(Source.FACT, "/absent", "eq", true)));
        assertThat(StudioConditions.evaluate(condition, Map.of("ok", true), Map.of(), Map.of())).isTrue();
    }

    @Test void operatorWhenAndTerminalProduceDecisionAndOrderedSkips() {
        Flow definition = flow(
                stage("check", 1, Mode.SERIAL, null,
                        operator("risk", 1, "risk", compare(Source.FACT, "/risk", "eq", true), FailPolicy.CONTINUE)),
                stage("reject", 2, Mode.SERIAL, compare(Source.NODE, "/risk/hit", "eq", true), terminal("deny", "REJECT")),
                stage("approve", 3, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        Result rejected = new StudioEngine(pool).execute(definition, Map.of("risk", true), 1000);
        assertThat(rejected.decision().decisionCode()).isEqualTo("REJECT");
        assertThat(rejected.nodeResults()).extracting(NodeResult::status)
                .containsExactly(Status.SUCCEEDED, Status.SUCCEEDED, Status.SKIPPED);
        assertThat(rejected.nodeResults().get(2).skipReason()).isEqualTo(SkipReason.TERMINATED);
        Result approved = new StudioEngine(pool).execute(definition, Map.of("risk", false), 1000);
        assertThat(approved.decision().decisionCode()).isEqualTo("APPROVE");
        assertThat(approved.nodeResults().get(1).skipReason()).isEqualTo(SkipReason.WHEN_FALSE);
    }

    @Test void continueFailureCanRouteUsingNodeStatusButAbortCannot() {
        Flow continueFlow = flow(
                stage("check", 1, Mode.SERIAL, null,
                        operator("risk", 1, "risk", compare(Source.FACT, "/risk", "eq", true), FailPolicy.CONTINUE)),
                stage("review", 2, Mode.SERIAL, compare(Source.NODE, "/risk/status", "eq", "FAILED"), terminal("manual", "REVIEW")),
                stage("approve", 3, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        Result result = new StudioEngine(pool).execute(continueFlow, Map.of(), 1000);
        assertThat(result.decision().decisionCode()).isEqualTo("REVIEW");
        assertThat(result.nodeResults().getFirst().failure()).contains("Missing");
        Flow abortFlow = flow(stage("check", 1, Mode.SERIAL, null,
                        operator("risk", 1, "risk", compare(Source.FACT, "/risk", "eq", true), FailPolicy.ABORT)),
                stage("approve", 2, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        Result aborted = new StudioEngine(pool).execute(abortFlow, Map.of(), 1000);
        assertThat(aborted.status()).isEqualTo("FAILED");
        assertThat(aborted.decision()).isNull();
    }

    @Test void rejectsTerminalAndReferenceTopologyViolations() {
        StudioCompiler compiler = new StudioCompiler();
        assertThatThrownBy(() -> compiler.compile(flow(stage("parallel", 1, Mode.PARALLEL, null, terminal("bad", "APPROVE")),
                stage("end", 2, Mode.SERIAL, null, terminal("allow", "APPROVE")))))
                .hasMessageContaining("Terminal must be last");
        assertThatThrownBy(() -> compiler.compile(flow(stage("check", 1, Mode.SERIAL, null,
                        operator("risk", 1, "risk", compare(Source.NODE, "/future/hit", "eq", true), FailPolicy.CONTINUE)),
                stage("end", 2, Mode.SERIAL, null, terminal("allow", "APPROVE")))))
                .hasMessageContaining("unavailable node");
        assertThatThrownBy(() -> compiler.compile(flow(stage("parallel", 1, Mode.PARALLEL, null,
                        operator("one", 1, "same", compare(Source.FACT, "/x", "eq", true), FailPolicy.CONTINUE),
                        operator("two", 2, "same", compare(Source.FACT, "/x", "eq", true), FailPolicy.CONTINUE)),
                stage("end", 2, Mode.SERIAL, null, terminal("allow", "APPROVE")))))
                .hasMessageContaining("Parallel output conflict");
        assertThatThrownBy(() -> compiler.compile(flow(stage("check", 1, Mode.SERIAL, null,
                operator("risk", 1, "risk", compare(Source.FACT, "/x", "eq", true), FailPolicy.CONTINUE)))))
                .hasMessageContaining("unconditional single Terminal");
    }

    @Test void parallelNodesCannotReadEachOthersNewVariables() {
        Flow definition = flow(stage("parallel", 1, Mode.PARALLEL, null,
                        operator("one", 1, "first", compare(Source.FACT, "/x", "eq", true), FailPolicy.CONTINUE),
                        operator("two", 2, "second", compare(Source.VARIABLE, "/first", "eq", true), FailPolicy.CONTINUE)),
                stage("end", 2, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        assertThatThrownBy(() -> new StudioCompiler().compile(definition)).hasMessageContaining("unavailable variable");
    }

    @Test void nodeTimeoutContinuesButRequestTimeoutIsTechnicalFailure() {
        Node slow = new Node("slow", 1, "openrule.operator", 1, 10, FailPolicy.CONTINUE,
                new Operator(compare(Source.FACT, "/x", "eq", true), "slowHit", "MATCHED"));
        Flow definition = flow(stage("check", 1, Mode.SERIAL, null, slow),
                stage("review", 2, Mode.SERIAL,
                        compare(Source.NODE, "/slow/status", "eq", "TIMED_OUT"), terminal("manual", "REVIEW")),
                stage("approve", 3, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        StudioEngine.NodeLogic delayed = (node, stageId, facts, variables, prior) -> {
            Thread.sleep(80);
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.SUCCEEDED, true,
                    Map.of("slowHit", true), List.of(), null, null, 80);
        };
        StudioEngine engine = new StudioEngine(pool, Map.of("openrule.operator", delayed));
        Result continued = engine.execute(definition, Map.of("x", true), 1000);
        assertThat(continued.status()).isEqualTo("DECIDED");
        assertThat(continued.decision().decisionCode()).isEqualTo("REVIEW");
        assertThat(continued.nodeResults().getFirst().status()).isEqualTo(Status.TIMED_OUT);
        Result timedOut = engine.execute(definition, Map.of("x", true), 1);
        assertThat(timedOut.status()).isEqualTo("FAILED");
        assertThat(timedOut.errorCode()).isEqualTo("OR-EXECUTION-TIMEOUT");
        assertThat(timedOut.decision()).isNull();
    }

    @Test void parallelStageUsesEntrySnapshotAndDefinitionOrder() {
        Flow definition = flow(
                stage("prior", 1, Mode.SERIAL, null,
                        operator("seed", 1, "ready", compare(Source.FACT, "/x", "eq", true), FailPolicy.ABORT)),
                stage("parallel", 2, Mode.PARALLEL, null,
                        operator("first", 1, "one", compare(Source.VARIABLE, "/ready", "eq", true), FailPolicy.ABORT),
                        operator("second", 2, "two", compare(Source.VARIABLE, "/ready", "eq", true), FailPolicy.ABORT)),
                stage("approve", 3, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        Result result = new StudioEngine(pool).execute(definition, Map.of("x", true), 1000);
        assertThat(result.status()).isEqualTo("DECIDED");
        assertThat(result.nodeResults()).extracting(NodeResult::nodeId)
                .containsExactly("seed", "first", "second", "allow");
        assertThat(result.variables()).containsEntry("one", true).containsEntry("two", true);
    }

    @Test void parallelAbortCancelsSiblingAndCommitsNoStageOutput() throws Exception {
        CountDownLatch siblingStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        StudioEngine.NodeLogic logic = (node, stageId, facts, variables, prior) -> {
            if (node.nodeId().equals("first")) {
                siblingStarted.await(1, TimeUnit.SECONDS);
                throw new StudioConditions.ConditionFailure("bad fact");
            }
            siblingStarted.countDown();
            try { Thread.sleep(1000); }
            catch (InterruptedException ex) { interrupted.set(true); throw ex; }
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.SUCCEEDED, true,
                    Map.of("second", true), List.of(), null, null, 1000);
        };
        Flow definition = flow(stage("parallel", 1, Mode.PARALLEL, null,
                        operator("first", 1, "first", compare(Source.FACT, "/x", "eq", true), FailPolicy.ABORT),
                        operator("second", 2, "second", compare(Source.FACT, "/x", "eq", true), FailPolicy.CONTINUE)),
                stage("approve", 2, Mode.SERIAL, null, terminal("allow", "APPROVE")));
        Result result = new StudioEngine(pool, Map.of("openrule.operator", logic))
                .execute(definition, Map.of("x", true), 2000);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.decision()).isNull();
        assertThat(result.variables()).isEmpty();
        assertThat(result.nodeResults()).extracting(NodeResult::status)
                .containsExactly(Status.FAILED, Status.SKIPPED, Status.SKIPPED);
        assertThat(siblingStarted.getCount()).isZero();
        for (int i = 0; i < 20 && !interrupted.get(); i++) Thread.sleep(5);
        assertThat(interrupted.get()).isTrue();
    }
}
