package io.openrule.jdbc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.FlowResult;
import io.openrule.jdbc.AbstractMySqlIT;
import io.openrule.spring.model.ExecutionLogEntry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditIT extends AbstractMySqlIT {

    private static final Executor INLINE = Runnable::run; // 同步执行，便于断言

    private FlowResult result(String reqId, String bizId, Decision d) {
        return FlowResult.builder().requestId(reqId).flowId("f").bizId(bizId).decision(d)
                .reason("r").totalScore(7).costMillis(3).hitNodes(List.of("AMT")).build();
    }

    @Test
    void log_persistsRow_withDesensitizedFacts() {
        JdbcExecutionLogger logger = new JdbcExecutionLogger(
                jdbc, new ObjectMapper(), Set.of("mobile"), INLINE);
        DecisionContext ctx = new DecisionContext("R1", "f", "B1",
                Map.of("mobile", "13800000000", "order", Map.of("amount", 80000)));

        logger.log(result("R1", "B1", Decision.REJECT), ctx, 2);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM or_execute_log WHERE request_id='R1'");
        assertThat(row.get("flow_version")).isEqualTo(2);
        assertThat(row.get("decision")).isEqualTo("REJECT");
        assertThat(row.get("biz_id")).isEqualTo("B1");
        assertThat(row.get("facts_snapshot").toString()).contains("****").doesNotContain("13800000000");
    }

    @Test
    void query_byBizId_recentFirst() {
        JdbcExecutionLogger logger = new JdbcExecutionLogger(
                jdbc, new ObjectMapper(), Set.of(), INLINE);
        logger.log(result("R1", "B1", Decision.PASS), new DecisionContext("R1", "f", "B1", Map.of()), 1);
        logger.log(result("R2", "B1", Decision.REJECT), new DecisionContext("R2", "f", "B1", Map.of()), 1);
        logger.log(result("R3", "B2", Decision.PASS), new DecisionContext("R3", "f", "B2", Map.of()), 1);

        JdbcExecutionLogQuery q = new JdbcExecutionLogQuery(jdbc);
        List<ExecutionLogEntry> byBiz = q.query("B1", null, 10);
        assertThat(byBiz).extracting(ExecutionLogEntry::requestId).containsExactly("R2", "R1");
        assertThat(q.query(null, "f", 1)).hasSize(1);
    }

    @Test
    void log_swallowsFailure_C12() {
        // 故意用错表名触发 SQL 失败：log 不抛
        JdbcExecutionLogger bad = new JdbcExecutionLogger(
                new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
                    @Override public int update(String sql, Object... args) {
                        throw new RuntimeException("boom");
                    }
                }, new ObjectMapper(), Set.of(), INLINE);
        bad.log(result("RX", "B", Decision.PASS), new DecisionContext("RX", "f", "B", Map.of()), 1);
        // 未抛异常即通过（C12）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM or_execute_log", Integer.class)).isZero();
        assertThat(bad.failureCount()).isEqualTo(1);
    }
}
