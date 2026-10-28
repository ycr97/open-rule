package io.openrule.spring.model;

import java.util.Map;

/** 服务层执行入参（与 api DTO 解耦）。 */
public record ExecuteCommand(String flowId, String requestId, String bizId,
                             boolean debug, Map<String, Object> facts) {
}
