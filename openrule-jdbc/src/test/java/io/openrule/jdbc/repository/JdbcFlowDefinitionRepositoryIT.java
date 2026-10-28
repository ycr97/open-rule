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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void corruptedLegacyChecksumIsRejectedBeforeDecode() {
        repo.save(flow());
        jdbc.update("UPDATE or_flow SET checksum=? WHERE flow_id=? AND version=1", "0".repeat(64), "f");
        assertThatThrownBy(() -> repo.findByFlowIdAndVersion("f", 1))
                .hasMessageContaining("OR-DEF-CHECKSUM");
    }
    @Test void readsFrozenV1RawFixtureWithoutRewritingBytesOrChecksum() throws Exception {
        java.nio.file.Path fixtures = java.nio.file.Path.of("../docs/contracts/studio-m1/fixtures/legacy");
        String raw = java.nio.file.Files.readString(fixtures.resolve("v1-priority.raw.json"));
        String checksum = new ObjectMapper().readTree(fixtures.resolve("v1-priority.meta.json").toFile())
                .path("legacyChecksumHex").asText();
        jdbc.update("INSERT INTO or_flow(flow_id,flow_name,version,enabled,aggregate_policy,definition_json,checksum) "
                + "VALUES('order_risk','legacy',1,1,'PRIORITY',?,?)", raw, checksum);
        FlowDefinition loaded = repo.findByFlowIdAndVersion("order_risk", 1).orElseThrow();
        assertThat(loaded.getFlowId()).isEqualTo("order_risk");
        try (var engine = io.openrule.core.OpenRuleEngine.create()) {
            var result = engine.execute(loaded, new io.openrule.core.context.DecisionContext(
                    "v1-legacy", "order_risk", "v1-order", java.util.Map.of("order", java.util.Map.of("amount", 50001))));
            assertThat(result.getDecision()).isEqualTo(io.openrule.core.enums.Decision.REJECT);
        }

        assertThat(loaded.getStages().getFirst().getNodes().getFirst().getNodeId()).isEqualTo("AMOUNT_LIMIT");
        assertThat(jdbc.queryForObject("SELECT definition_json FROM or_flow WHERE flow_id='order_risk'", String.class)).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT checksum FROM or_flow WHERE flow_id='order_risk'", String.class)).isEqualTo(checksum);
    }

}
