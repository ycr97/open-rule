package io.openrule.spring.standalone;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;
import io.openrule.spring.port.ExecutionLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 进程内有界执行日志缓冲（最近 N 条），供 demo/测试读取。 */
public class InMemoryExecutionLogger implements ExecutionLogger {

    private static final int MAX = 500;
    private final Deque<FlowResult> buffer = new ArrayDeque<>();

    @Override
    public synchronized void log(FlowResult result, DecisionContext ctx) {
        if (buffer.size() >= MAX) {
            buffer.pollFirst();
        }
        buffer.offerLast(result);
    }

    public synchronized List<FlowResult> recent() {
        return new ArrayList<>(buffer);
    }
}
