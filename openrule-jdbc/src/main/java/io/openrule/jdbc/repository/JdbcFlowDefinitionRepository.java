package io.openrule.jdbc.repository;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.jdbc.support.ChecksumUtil;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.port.FlowDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** MySQL 实现：版本自增 + enabled 指针事务内原子切换 + checksum。definition_json ⇄ FlowDefinition 复用 codec。 */
public class JdbcFlowDefinitionRepository implements FlowDefinitionRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FlowDefinitionJsonCodec codec;

    public JdbcFlowDefinitionRepository(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                        FlowDefinitionJsonCodec codec) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.codec = codec;
    }

    private FlowDefinition mapRow(ResultSet rs, int n) throws SQLException {
        FlowDefinition def = codec.parse(rs.getString("definition_json"));
        def.setVersion(rs.getInt("version"));
        def.setEnabled(rs.getBoolean("enabled"));
        return def;
    }

    @Override
    public FlowDefinition save(FlowDefinition def) {
        return tx.execute(status -> {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version),0) FROM or_flow WHERE flow_id=?",
                    Integer.class, def.getFlowId());
            int next = (max == null ? 0 : max) + 1;
            jdbc.update("UPDATE or_flow SET enabled=0 WHERE flow_id=?", def.getFlowId());
            def.setVersion(next);
            def.setEnabled(true);
            String json = codec.toJson(def);
            jdbc.update("INSERT INTO or_flow"
                    + "(flow_id,flow_name,version,enabled,aggregate_policy,definition_json,checksum)"
                    + " VALUES(?,?,?,1,?,?,?)",
                    def.getFlowId(), def.getFlowName(), next,
                    def.getAggregatePolicy() == null ? null : def.getAggregatePolicy().name(),
                    json, ChecksumUtil.sha256Hex(json));
            return def;
        });
    }

    @Override
    public void enable(String flowId, int version) {
        tx.executeWithoutResult(status -> {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM or_flow WHERE flow_id=? AND version=?",
                    Integer.class, flowId, version);
            if (cnt == null || cnt == 0) {
                return; // 不存在 → no-op（与内存语义一致）
            }
            jdbc.update("UPDATE or_flow SET enabled=0 WHERE flow_id=?", flowId);
            jdbc.update("UPDATE or_flow SET enabled=1 WHERE flow_id=? AND version=?", flowId, version);
        });
    }

    @Override
    public Optional<FlowDefinition> findActiveByFlowId(String flowId) {
        return jdbc.query("SELECT definition_json,version,enabled FROM or_flow"
                + " WHERE flow_id=? AND enabled=1 LIMIT 1", this::mapRow, flowId).stream().findFirst();
    }

    @Override
    public Optional<FlowDefinition> findByFlowIdAndVersion(String flowId, int version) {
        return jdbc.query("SELECT definition_json,version,enabled FROM or_flow"
                + " WHERE flow_id=? AND version=?", this::mapRow, flowId, version).stream().findFirst();
    }

    @Override
    public List<Integer> listVersions(String flowId) {
        return jdbc.queryForList(
                "SELECT version FROM or_flow WHERE flow_id=? ORDER BY version", Integer.class, flowId);
    }

    @Override
    public List<FlowDefinition> findAllVersions(String flowId) {
        return jdbc.query("SELECT definition_json,version,enabled FROM or_flow"
                + " WHERE flow_id=? ORDER BY version", this::mapRow, flowId);
    }
}
