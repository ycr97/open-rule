package io.openrule.spring.standalone;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFlowDefinitionRepositoryTest {

    private FlowDefinition flow(String id) {
        return FlowDefinition.builder()
                .flowId(id).flowName("n").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of()).build();
    }

    @Test
    void firstSaveBecomesVersion1AndEnabled() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        FlowDefinition saved = repo.save(flow("f"));
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
    }

    @Test
    void secondSaveIncrementsVersionAndSwitchesActive() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(flow("f"));
        FlowDefinition v2 = repo.save(flow("f"));
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(2);
        assertThat(repo.findByFlowIdAndVersion("f", 1)).map(FlowDefinition::isEnabled).contains(false);
        assertThat(repo.listVersions("f")).containsExactly(1, 2);
    }

    @Test
    void enableSwitchesActivePointer() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(flow("f"));
        repo.save(flow("f"));      // active = v2
        repo.enable("f", 1);       // 回滚到 v1
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
    }

    @Test
    void unknownFlowHasNoActive() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        assertThat(repo.findActiveByFlowId("nope")).isEmpty();
    }
}
