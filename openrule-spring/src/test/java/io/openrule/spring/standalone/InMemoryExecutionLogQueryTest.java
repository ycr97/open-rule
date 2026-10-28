package io.openrule.spring.standalone;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.FlowResult;
import io.openrule.spring.model.ExecutionLogEntry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryExecutionLogQueryTest {

    private FlowResult result(String reqId, String flowId, String bizId, Decision d) {
        return FlowResult.builder().requestId(reqId).flowId(flowId).bizId(bizId)
                .decision(d).reason("r").totalScore(1).costMillis(2).build();
    }

    private DecisionContext ctx(String flowId, String bizId) {
        return new DecisionContext("req", flowId, bizId, Map.of());
    }

    @Test
    void logThenQueryByBizAndFlow_recentFirst() {
        InMemoryExecutionLogger log = new InMemoryExecutionLogger();
        log.log(result("R1", "f", "B1", Decision.PASS), ctx("f", "B1"), 1);
        log.log(result("R2", "f", "B1", Decision.REJECT), ctx("f", "B1"), 2);
        log.log(result("R3", "g", "B2", Decision.PASS), ctx("g", "B2"), 1);

        List<ExecutionLogEntry> byBiz = log.query("B1", null, 10);
        assertThat(byBiz).extracting(ExecutionLogEntry::requestId).containsExactly("R2", "R1"); // 倒序
        assertThat(byBiz).allMatch(e -> e.bizId().equals("B1"));

        List<ExecutionLogEntry> byFlow = log.query(null, "g", 10);
        assertThat(byFlow).extracting(ExecutionLogEntry::flowId).containsExactly("g");
        assertThat(byFlow.get(0).flowVersion()).isEqualTo(1);

        assertThat(log.query(null, null, 1)).hasSize(1); // limit 生效
    }
}
