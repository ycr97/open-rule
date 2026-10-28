package io.openrule.core;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRuleEngineTest {

    @Test
    void defaultFacade_validatesCompilesAndExecutesDefinition() {
        try (OpenRuleEngine engine = OpenRuleEngine.create()) {
            FlowDefinition definition = flow();

            engine.validate(definition);
            CompiledFlow compiled = engine.compile(definition);
            FlowResult result = engine.execute(compiled,
                    new DecisionContext("r", "order-risk", "b", Map.of("amount", 101)));

            assertThat(result.getDecision()).isEqualTo(Decision.REJECT);
            assertThat(result.getHitNodes()).containsExactly("amount-limit");
        }
    }

    @Test
    void defaultFacade_canCompileOnExecute() {
        try (OpenRuleEngine engine = OpenRuleEngine.builder().build()) {
            FlowResult result = engine.execute(flow(),
                    new DecisionContext("r", "order-risk", "b", Map.of("amount", 10)));

            assertThat(result.getDecision()).isEqualTo(Decision.PASS);
        }
    }

    @Test
    void close_doesNotShutdownCallerOwnedExecutor() {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            OpenRuleEngine engine = OpenRuleEngine.builder().executorService(pool).build();
            engine.close();
            assertThat(pool.isShutdown()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    private FlowDefinition flow() {
        OperatorDef operator = new OperatorDef();
        operator.setLeftFact("fact.amount");
        operator.setOperator("GT");
        operator.setRightValue(100);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("amount-limit").nodeType(NodeType.OPERATOR).order(10)
                .operatorDef(operator).decisionOnHit(Decision.REJECT).build();
        StageDefinition stage = StageDefinition.builder()
                .stageId("risk").order(10).executionMode(ExecutionMode.SERIAL)
                .skipWhenStopped(true).nodes(List.of(node)).build();
        return FlowDefinition.builder().flowId("order-risk")
                .aggregatePolicy(AggregatePolicy.PRIORITY).stages(List.of(stage)).build();
    }
}
