package io.openrule.spring.model;

import java.time.Instant;

/** 执行日志查询条目（读模型）。 */
public record ExecutionLogEntry(String requestId, String flowId, int flowVersion,
                                String bizId, String decision, String reason,
                                int totalScore, long costMillis, Instant createdAt) {
}
