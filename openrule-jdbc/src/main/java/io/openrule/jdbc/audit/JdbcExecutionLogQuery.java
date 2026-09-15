package io.openrule.jdbc.audit;

import io.openrule.spring.model.ExecutionLogEntry;
import io.openrule.spring.port.ExecutionLogQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.ZoneId;
import java.util.List;

/** 查询 or_execute_log（按时间倒序）。 */
public class JdbcExecutionLogQuery implements ExecutionLogQuery {

    private final JdbcTemplate jdbc;

    public JdbcExecutionLogQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ExecutionLogEntry> MAPPER = (rs, n) -> new ExecutionLogEntry(
            rs.getString("request_id"), rs.getString("flow_id"), rs.getInt("flow_version"),
            rs.getString("biz_id"), rs.getString("decision"), rs.getString("reason"),
            rs.getInt("total_score"), rs.getInt("cost_millis"),
            rs.getTimestamp("created_at").toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant());

    @Override
    public List<ExecutionLogEntry> query(String bizId, String flowId, int limit) {
        return jdbc.query("SELECT request_id,flow_id,flow_version,biz_id,decision,reason,"
                + "total_score,cost_millis,created_at FROM or_execute_log"
                + " WHERE (? IS NULL OR biz_id=?) AND (? IS NULL OR flow_id=?)"
                + " ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, bizId, bizId, flowId, flowId, limit <= 0 ? Integer.MAX_VALUE : limit);
    }
}
