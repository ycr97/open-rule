package io.openrule.spring.standalone;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.spring.loader.FlowCompiler;
import io.openrule.spring.loader.FlowLoader;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LocalFlowChangeNotifierTest {

    private FlowDefinition flow(String id) {
        return FlowDefinition.builder()
                .flowId(id).flowName("n").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of()).build();
    }

    @Test
    void publishInvalidationClearsLoaderCache() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(flow("f"));
        FlowLoader loader = new FlowLoader(repo,
                new FlowCompiler(new NodeExecutorRegistry(List.of(new OperatorNodeExecutor()))));
        CompiledFlow v1 = loader.loadActive("f");

        repo.save(flow("f"));   // active = v2
        new LocalFlowChangeNotifier(loader).publishInvalidation("f");

        CompiledFlow after = loader.loadActive("f");
        assertThat(after.getVersion()).isEqualTo(2);
        assertThat(after).isNotSameAs(v1);
    }
}
