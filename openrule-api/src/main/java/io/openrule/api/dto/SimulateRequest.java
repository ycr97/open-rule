package io.openrule.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record SimulateRequest(JsonNode definition, Map<String, Object> facts) {
}
