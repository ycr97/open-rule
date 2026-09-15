package io.openrule.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.api.dto.ExecuteResponse;
import io.openrule.api.dto.FlowSummary;
import io.openrule.api.dto.SimulateRequest;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.model.ExecutionLogEntry;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class FlowAdminController {

    private final OpenRuleService service;
    private final FlowDefinitionJsonCodec codec;
    private final ExecutionLogQuery logQuery;

    public FlowAdminController(OpenRuleService service, FlowDefinitionJsonCodec codec,
                              ExecutionLogQuery logQuery) {
        this.service = service;
        this.codec = codec;
        this.logQuery = logQuery;
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

    /** 版本列表（含 enabled 标记）。 */
    @GetMapping("/flows/{flowId}/versions")
    public List<FlowSummary> versions(@PathVariable String flowId) {
        return service.listVersions(flowId).stream().map(FlowSummary::from).toList();
    }

    /** 启用指定版本（切指针 + 失效缓存）。 */
    @PostMapping("/flows/{flowId}/enable")
    public FlowSummary enable(@PathVariable String flowId, @RequestParam int version) {
        return FlowSummary.from(service.enableVersion(flowId, version));
    }

    /** 回滚 = 启用旧版本。 */
    @PostMapping("/flows/{flowId}/rollback")
    public FlowSummary rollback(@PathVariable String flowId, @RequestParam int version) {
        return FlowSummary.from(service.rollback(flowId, version));
    }

    /** 执行日志查询。 */
    @GetMapping("/logs")
    public List<ExecutionLogEntry> logs(@RequestParam(required = false) String bizId,
                                        @RequestParam(required = false) String flowId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return logQuery.query(bizId, flowId, limit);
    }
}
