package io.openrule.api.dto;

import java.util.Map;

public record ExecuteRequest(String flowId, String requestId, String bizId,
                             boolean debug, Map<String, Object> facts) {
}
