package io.openrule.spring.port;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;

/** 执行日志端口。standalone=内存缓冲；M2b=异步落库/ES。实现内部必须吞掉异常（C12）。 */
public interface ExecutionLogger {
    void log(FlowResult result, DecisionContext ctx);
}
