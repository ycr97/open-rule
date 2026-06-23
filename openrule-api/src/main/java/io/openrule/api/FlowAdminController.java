package io.openrule.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.api.dto.ExecuteResponse;
import io.openrule.api.dto.FlowSummary;
import io.openrule.api.dto.SimulateRequest;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class FlowAdminController {

    private final OpenRuleService service;
    private final FlowDefinitionJsonCodec codec;

    public FlowAdminController(OpenRuleService service, FlowDefinitionJsonCodec codec) {
        this.service = service;
        this.codec = codec;
    }

    /** 注册流程：解析 JSON → 全节点 validate → save（M2a 直接 enabled）。 */
    @PostMapping("/flows")
    public FlowSummary register(@RequestBody JsonNode definition) {
        FlowDefinition def = codec.parse(definition);
        return FlowSummary.from(service.registerFlow(def));
    }

    /** 草稿模拟：不要求 enabled，回完整明细。 */
    @PostMapping("/simulate")
    public ExecuteResponse simulate(@RequestBody SimulateRequest req) {
        FlowDefinition draft = codec.parse(req.definition());
        ExecutionOutcome outcome = service.simulate(draft, req.facts());
        return ExecuteResponse.from(outcome, true);
    }
}
