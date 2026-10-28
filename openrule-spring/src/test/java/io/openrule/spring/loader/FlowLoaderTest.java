package io.openrule.spring.loader;

import io.openrule.core.compiler.FlowCompiler;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowLoaderTest {

    private FlowCompiler compiler() {
        return new FlowCompiler(new NodeExecutorRegistry(List.of(new OperatorNodeExecutor())));
    }

    private FlowDefinition opFlow(String id) {
        OperatorDef op = new OperatorDef();
        op.setLeftFact("fact.order.amount");
        op.setOperator("GT");
        op.setRightValue(50000);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("AMOUNT").nodeType(NodeType.OPERATOR).order(20).operatorDef(op).build();
        StageDefinition stage = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node)).build();
        return FlowDefinition.builder()
                .flowId(id).flowName("n").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stage)).build();
    }

    @Test
    void loadActiveCompilesAndCaches() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(opFlow("f"));
        FlowLoader loader = new FlowLoader(repo, compiler());

        CompiledFlow first = loader.loadActive("f");
        CompiledFlow second = loader.loadActive("f");
        assertThat(first).isSameAs(second);              // 缓存命中：同一对象
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(first.getStages()).hasSize(1);
    }

    @Test
    void newVersionAfterInvalidateRecompiles() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(opFlow("f"));
        FlowLoader loader = new FlowLoader(repo, compiler());
        CompiledFlow v1 = loader.loadActive("f");

        repo.save(opFlow("f"));        // 现 active = v2
        loader.invalidate("f");
        CompiledFlow v2 = loader.loadActive("f");
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2).isNotSameAs(v1);
    }

    @Test
    void missingFlowThrows() {
        FlowLoader loader = new FlowLoader(new InMemoryFlowDefinitionRepository(), compiler());
        assertThatThrownBy(() -> loader.loadActive("nope"))
                .isInstanceOf(RuleEngineException.class)
                .hasMessageContaining("not found");
    }
}
