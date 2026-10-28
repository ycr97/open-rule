package io.openrule.core.studio;

import io.openrule.core.studio.StudioModel.*;
import io.openrule.core.studio.StudioModel.Flow;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class StudioM2Test {
    private static Ref fact(String path) { return new Ref(Source.FACT, path); }
    private static Flow flow(Condition condition) {
        return new Flow(2, "test_flow", "Test", "1", "", List.of(new Stage("stage_one", 1, Mode.SERIAL,
                null, 3000, List.of(new Node("operator_one", 1, "openrule.operator", 1, 3000, FailPolicy.CONTINUE,
                        new Operator(condition, "hit", "HIT")))), new Stage("final_stage", 2, Mode.SERIAL, null, 3000, List.of(new Node("terminal_one", 2, "openrule.terminal", 1, 3000, null,
                        new Terminal("APPROVE", List.of("OK"), null))))));
    }
    @Test void hintsAreDiagnosticAndPreserveNestedPresenceRootEscapesAndArrays() {
        var compiler = new StudioCompiler();
        var condition = new Group("any", List.of(new Presence("is-present", fact("")), new Group("all", List.of(
                new Compare(fact("/mixed"), "eq", BigDecimal.ONE),
                new Compare(fact("/mixed"), "eq", "one"),
                new Group("not", List.of(new Presence("is-null", fact("/a~1b/~0key/0")))),
                new Compare(fact("/contains"), "contains", "abc"),
                new Compare(fact("/membership"), "in", Arrays.asList("one", null)),
                new Compare(fact("/same"), "in", List.of(true, false)),
                new Compare(new Ref(Source.VARIABLE, "/hit"), "eq", true)))));
        // VARIABLE must have a producer; use a separate validated stage.when for exclusion below.
        var withoutVariable = new Group("any", condition.children().subList(0, 1));
        var nested = (Group) condition.children().get(1);
        var actual = new Group("any", List.of(withoutVariable.children().getFirst(),
                new Group("all", nested.children().subList(0, nested.children().size() - 1))));
        var plan = compiler.compilePlan(flow(actual));
        var mixed = plan.requiredFacts().stream().filter(f -> f.path().equals("/mixed")).findFirst().orElseThrow();
        assertThat(mixed.typeConflict()).isTrue();
        assertThat(mixed.expectedTypes()).containsExactly("number", "string");
        assertThat(mixed.uses().getFirst().pointer()).isEqualTo("/stages/0/nodes/0/config/condition/children/1/children/0/ref/pointer");
        assertThat(plan.requiredFacts()).anySatisfy(f -> {
            assertThat(f.path()).isEqualTo("/contains"); assertThat(f.expectedTypes()).containsExactly("array", "string");
        }).anySatisfy(f -> {
            assertThat(f.path()).isEqualTo("/membership"); assertThat(f.expectedTypes()).isEmpty();
        }).anySatisfy(f -> {
            assertThat(f.path()).isEqualTo("/same"); assertThat(f.expectedTypes()).containsExactly("boolean");
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new StudioEngine(pool).execute(plan, Map.of(), StudioDeadline.afterMillis(1000));
            assertThat(result.decision().decisionCode()).isEqualTo("APPROVE");
            assertThat(result.variables().get("hit")).isEqualTo(true); // missing branch never evaluated
        }
        assertThat(StudioConditions.evaluate(new Presence("is-present", fact("/a~1b/~0key/0")),
                Map.of("a/b", Map.of("~key", List.of(1))), Map.of(), Map.of())).isTrue();
    }
    @Test void stageWhenIsCollectedAndVariableNodeAndScoreRefAreExcluded() {
        Flow original = flow(new Presence("is-present", fact("/first")));
        Stage first = original.stages().getFirst();
        var second = new Stage("stage_two", 2, Mode.SERIAL,
                new Group("all", List.of(new Presence("is-present", fact("/stage")),
                        new Presence("is-present", new Ref(Source.VARIABLE, "/hit")),
                        new Compare(new Ref(Source.NODE, "/operator_one/status"), "eq", "SUCCEEDED"))),
                3000, List.of(original.stages().getLast().nodes().getFirst()));
        var noTerminal = new Stage(first.stageId(), first.order(), first.mode(), null, first.timeoutMillis(), List.of(first.nodes().getFirst()));
        var plan = new StudioCompiler().compilePlan(new Flow(2, "test_flow", "Test", "1", "", List.of(second, noTerminal, new Stage("default_stage", 3, Mode.SERIAL, null, 3000, List.of(new Node("default_terminal", 1, "openrule.terminal", 1, 3000, null, new Terminal("APPROVE", List.of("OK"), null)))))));
        assertThat(plan.requiredFacts()).extracting(RequiredFact::path).containsExactly("/first", "/stage");
        var use = plan.requiredFacts().getLast().uses().getFirst();
        assertThat(use.pointer()).isEqualTo("/stages/0/when/children/0/ref/pointer");
        assertThat(use.nodeId()).isNull(); assertThat(use.itemId()).isNull();
    }
    @Test void planCopiesNestedInputsAndSortsUnicodeCodePoints() {
        List<Condition> children = new ArrayList<>(List.of(new Presence("is-present", fact("/\uE000")), new Presence("is-present", fact("/\uD800\uDC00"))));
        var plan = new StudioCompiler().compilePlan(flow(new Group("any", children)));
        children.clear();
        assertThat(plan.requiredFacts()).extracting(RequiredFact::path).containsExactly("/\uE000", "/\uD800\uDC00");
        assertThat(((Group) ((Operator) plan.flow().stages().getFirst().nodes().getFirst().config()).condition()).children()).hasSize(2);
        assertThatThrownBy(() -> plan.flow().stages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void absoluteDeadlineCannotBeResetAndCancellationHasNoDecision() throws Exception {
        var plan = new StudioCompiler().compilePlan(flow(new Presence("is-present", fact(""))));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            StudioDeadline expired = StudioDeadline.afterMillis(10);
            Thread.sleep(30);
            var result = new StudioEngine(pool).execute(plan, Map.of(), expired);
            assertThat(result.errorCode()).isEqualTo("OR-EXECUTION-TIMEOUT"); assertThat(result.decision()).isNull();
            CountDownLatch entered = new CountDownLatch(1);
            var engine = new StudioEngine(pool, Map.of("openrule.operator", (node, stage, facts, vars, prior) -> {
                entered.countDown(); Thread.sleep(3000); throw new AssertionError();
            }));
            var deadline = StudioDeadline.afterMillis(3000);
            Future<Result> pending = pool.submit(() -> engine.execute(plan, Map.of(), deadline));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); deadline.cancel();
            result = pending.get(1, TimeUnit.SECONDS);
            assertThat(result.errorCode()).isEqualTo("OR-EXECUTION-CANCELLED"); assertThat(result.decision()).isNull();
        }
    }
}
