package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorNodeExecutorTest {

    private final OperatorNodeExecutor exec = new OperatorNodeExecutor();

    private boolean run(String leftFact, String op, Object right, Map<String, Object> facts) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(leftFact);
        def.setOperator(op);
        def.setRightValue(right);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("OP").nodeType(NodeType.OPERATOR).operatorDef(def).build();
        DecisionContext ctx = new DecisionContext("R", "f", "b", facts);
        NodeResult r = exec.execute(ctx, new CompiledNode(node, null));
        assertThat(r.isSuccess()).isTrue();
        return r.isHit();
    }

    @Test
    void numericComparators() {
        Map<String, Object> f = Map.of("order", Map.of("amount", 12800));
        assertThat(run("fact.order.amount", "GT", 5000, f)).isTrue();
        assertThat(run("fact.order.amount", "GTE", 12800, f)).isTrue();
        assertThat(run("fact.order.amount", "LT", 5000, f)).isFalse();
        assertThat(run("fact.order.amount", "LTE", 12800, f)).isTrue();
        assertThat(run("fact.order.amount", "EQ", 12800, f)).isTrue();
        assertThat(run("fact.order.amount", "NE", 1, f)).isTrue();
    }

    @Test
    void between() {
        Map<String, Object> f = Map.of("age", 30);
        assertThat(run("fact.age", "BETWEEN", List.of(18, 60), f)).isTrue();
        assertThat(run("fact.age", "BETWEEN", List.of(40, 60), f)).isFalse();
    }

    @Test
    void stringOps() {
        Map<String, Object> f = Map.of("name", "hello-world");
        assertThat(run("fact.name", "CONTAINS", "world", f)).isTrue();
        assertThat(run("fact.name", "NOT_CONTAINS", "xyz", f)).isTrue();
        assertThat(run("fact.name", "STARTS_WITH", "hello", f)).isTrue();
        assertThat(run("fact.name", "ENDS_WITH", "world", f)).isTrue();
    }

    @Test
    void collectionOps() {
        Map<String, Object> f = Map.of("country", "JP");
        assertThat(run("fact.country", "IN", List.of("JP", "US"), f)).isTrue();
        assertThat(run("fact.country", "NOT_IN", List.of("CN", "US"), f)).isTrue();
    }

    @Test
    void nullOps() {
        Map<String, Object> f = Map.of("present", 1);
        assertThat(run("fact.missing", "IS_NULL", null, f)).isTrue();
        assertThat(run("fact.present", "NOT_NULL", null, f)).isTrue();
    }

    @Test
    void regexOp() {
        Map<String, Object> f = Map.of("phone", "13800138000");
        assertThat(run("fact.phone", "REGEX", "1[0-9]{10}", f)).isTrue();
        assertThat(run("fact.phone", "REGEX", "^9.*", f)).isFalse();
    }

    @Test
    void regexTooLong_throws() {
        Map<String, Object> f = Map.of("v", "x");
        String huge = "a".repeat(513);
        assertThatThrownBy(() -> run("fact.v", "REGEX", huge, f))
                .isInstanceOf(RuleEngineException.class)
                .hasMessageContaining("512");
    }

    @Test
    void resolvesVarPath() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        ctx.putVariable("scorecard.score", 72);
        OperatorDef def = new OperatorDef();
        def.setLeftFact("var.scorecard.score");
        def.setOperator("GTE");
        def.setRightValue(60);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("OP").nodeType(NodeType.OPERATOR).operatorDef(def).build();
        NodeResult r = exec.execute(ctx, new CompiledNode(node, null));
        assertThat(r.isHit()).isTrue();
    }

    @Test
    void hitResult_carriesReasonAndDetails() {
        Map<String, Object> f = Map.of("order", Map.of("amount", 12800));
        OperatorDef def = new OperatorDef();
        def.setLeftFact("fact.order.amount");
        def.setOperator("GT");
        def.setRightValue(5000);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("AMOUNT").nodeName("金额").nodeType(NodeType.OPERATOR).operatorDef(def).build();
        NodeResult r = exec.execute(new DecisionContext("R", "f", "b", f), new CompiledNode(node, null));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getReason()).isNotBlank();
        assertThat(r.getDetails()).containsKeys("leftValue", "operator", "rightValue");
    }

    @Test
    void validate_rejectsIncompleteConfig() {
        NodeDefinition bad = NodeDefinition.builder()
                .nodeId("BAD").nodeType(NodeType.OPERATOR).operatorDef(new OperatorDef()).build();
        assertThatThrownBy(() -> exec.validate(bad))
                .isInstanceOf(FlowValidationException.class);
    }

    @Test
    void supportType_isOperator() {
        assertThat(exec.supportType()).isEqualTo(NodeType.OPERATOR);
    }
}
