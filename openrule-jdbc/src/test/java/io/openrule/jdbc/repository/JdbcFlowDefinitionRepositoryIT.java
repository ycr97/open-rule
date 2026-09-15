package io.openrule.jdbc.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.jdbc.AbstractMySqlIT;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcFlowDefinitionRepositoryIT extends AbstractMySqlIT {

    private JdbcFlowDefinitionRepository repo;

    private FlowDefinition flow() {
        return FlowDefinition.builder().flowId("f").flowName("n")
                .aggregatePolicy(AggregatePolicy.PRIORITY).stages(List.of()).build();
    }

    @BeforeEach
    void setUp() {
        repo = new JdbcFlowDefinitionRepository(jdbc, txm,
                new FlowDefinitionJsonCodec(new ObjectMapper()));
    }

    @Test
    void firstSave_v1Enabled_withChecksum() {
        FlowDefinition saved = repo.save(flow());
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
        String checksum = jdbc.queryForObject(
                "SELECT checksum FROM or_flow WHERE flow_id='f' AND version=1", String.class);
        assertThat(checksum).hasSize(64);
    }

    @Test
    void secondSave_incrementsAndSwitchesActive_atMostOneEnabled() {
        repo.save(flow());
        FlowDefinition v2 = repo.save(flow());
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(2);
        assertThat(repo.findByFlowIdAndVersion("f", 1)).map(FlowDefinition::isEnabled).contains(false);
        Integer enabledCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM or_flow WHERE flow_id='f' AND enabled=1", Integer.class);
        assertThat(enabledCount).isEqualTo(1);
        assertThat(repo.listVersions("f")).containsExactly(1, 2);
        assertThat(repo.findAllVersions("f")).extracting(FlowDefinition::getVersion).containsExactly(1, 2);
    }

    @Test
    void enable_switchesPointer_missingIsNoop() {
        repo.save(flow());
        repo.save(flow());      // active=v2
        repo.enable("f", 1);    // 回滚 v1
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
        repo.enable("f", 99);   // 不存在 → no-op，active 仍 v1
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
    }

    @Test
    void unknownFlow_empty() {
        assertThat(repo.findActiveByFlowId("nope")).isEmpty();
        assertThat(repo.listVersions("nope")).isEmpty();
    }
}
