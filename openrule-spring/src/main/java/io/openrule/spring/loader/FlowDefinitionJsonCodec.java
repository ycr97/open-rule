package io.openrule.spring.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.exception.FlowValidationException;

/** 流程定义 JSON ⇄ FlowDefinition（单一解析点；M2b 复用其做 definition_json/checksum）。 */
public class FlowDefinitionJsonCodec {

    private final ObjectMapper mapper;

    public FlowDefinitionJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public FlowDefinition parse(JsonNode node) {
        try {
            return mapper.treeToValue(node, FlowDefinition.class);
        } catch (Exception e) {
            throw new FlowValidationException("流程定义 JSON 解析失败: " + e.getMessage());
        }
    }

    public FlowDefinition parse(String json) {
        try {
            return mapper.readValue(json, FlowDefinition.class);
        } catch (Exception e) {
            throw new FlowValidationException("流程定义 JSON 解析失败: " + e.getMessage());
        }
    }

    public String toJson(FlowDefinition def) {
        try {
            return mapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new FlowValidationException("流程定义序列化失败: " + e.getMessage());
        }
    }
}
