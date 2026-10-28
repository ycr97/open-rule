package io.openrule.spring.port;

import io.openrule.spring.model.ExecutionLogEntry;

import java.util.List;

/** 执行日志只读查询端口。standalone=内存缓冲；M2b=or_execute_log。 */
public interface ExecutionLogQuery {

    /** 按 bizId/flowId（null=不过滤）查询最近 limit 条，按时间倒序。 */
    List<ExecutionLogEntry> query(String bizId, String flowId, int limit);
}
