package io.openrule.spring.port;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;

/** 执行日志写端口。standalone=内存缓冲；M2b=异步落库/ES。实现内部必须吞掉异常（C12）。 */
public interface ExecutionLogger {

    /** flowVersion 由编排处（持有 CompiledFlow）补齐，core FlowResult 不含版本。 */
    void log(FlowResult result, DecisionContext ctx, int flowVersion);
}
