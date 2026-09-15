package io.openrule.spring.standalone;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;
import io.openrule.spring.model.ExecutionLogEntry;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.port.ExecutionLogger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 进程内有界执行日志缓冲（最近 N 条），同时充当写端口与只读查询端口。 */
public class InMemoryExecutionLogger implements ExecutionLogger, ExecutionLogQuery {

    private static final int MAX = 500;
    private final Deque<ExecutionLogEntry> buffer = new ArrayDeque<>();

    @Override
    public synchronized void log(FlowResult r, DecisionContext ctx, int flowVersion) {
        if (buffer.size() >= MAX) {
            buffer.pollFirst();
        }
        buffer.offerLast(new ExecutionLogEntry(
                r.getRequestId(), r.getFlowId(), flowVersion, r.getBizId(),
                r.getDecision() == null ? null : r.getDecision().name(),
                r.getReason(), r.getTotalScore(), r.getCostMillis(), Instant.now()));
    }

    @Override
    public synchronized List<ExecutionLogEntry> query(String bizId, String flowId, int limit) {
        List<ExecutionLogEntry> out = new ArrayList<>();
        // 倒序遍历（最近优先）
        var it = buffer.descendingIterator();
        while (it.hasNext() && (limit <= 0 || out.size() < limit)) {
            ExecutionLogEntry e = it.next();
            if (bizId != null && !bizId.equals(e.bizId())) continue;
            if (flowId != null && !flowId.equals(e.flowId())) continue;
            out.add(e);
        }
        return out;
    }

    /** 测试/demo：按写入顺序返回全部。 */
    public synchronized List<ExecutionLogEntry> recent() {
        return new ArrayList<>(buffer);
    }
}
