package io.openrule.jdbc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;
import io.openrule.jdbc.support.Desensitizer;
import io.openrule.spring.port.ExecutionLogger;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 异步落 or_execute_log；facts 按配置 key 脱敏；失败仅吞 + 计数（C12）。 */
public class JdbcExecutionLogger implements ExecutionLogger {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Set<String> desensitizeKeys;
    private final Executor executor;
    private final AtomicLong failures = new AtomicLong();

    public JdbcExecutionLogger(JdbcTemplate jdbc, ObjectMapper mapper,
                               Set<String> desensitizeKeys, Executor executor) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.desensitizeKeys = desensitizeKeys;
        this.executor = executor;
    }

    public long failureCount() {
        return failures.get();
    }

    @Override
    public void log(FlowResult result, DecisionContext ctx, int flowVersion) {
        executor.execute(() -> {
            try {
                String hitNodes = mapper.writeValueAsString(result.getHitNodes());
                String nodeResults = mapper.writeValueAsString(result.getNodeResults());
                String factsSnapshot = mapper.writeValueAsString(
                        Desensitizer.mask(ctx.getFacts().asMap(), desensitizeKeys));
                jdbc.update("INSERT INTO or_execute_log"
                        + "(request_id,flow_id,flow_version,biz_id,decision,reason,total_score,"
                        + "cost_millis,hit_nodes,node_results,facts_snapshot)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        result.getRequestId(), result.getFlowId(), flowVersion, ctx.getBizId(),
                        result.getDecision() == null ? null : result.getDecision().name(),
                        result.getReason(), result.getTotalScore(), (int) result.getCostMillis(),
                        hitNodes, nodeResults, factsSnapshot);
            } catch (Exception e) {
                failures.incrementAndGet(); // C12：仅吞 + 计数，不冒泡
            }
        });
    }
}
