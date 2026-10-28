package io.openrule.api;

import io.openrule.api.dto.ExecuteRequest;
import io.openrule.api.dto.ExecuteResponse;
import io.openrule.spring.model.ExecuteCommand;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExecuteController {

    private final OpenRuleService service;

    public ExecuteController(OpenRuleService service) {
        this.service = service;
    }

    @PostMapping("/execute")
    public ExecuteResponse execute(@RequestBody ExecuteRequest req) {
        ExecutionOutcome outcome = service.execute(new ExecuteCommand(
                req.flowId(), req.requestId(), req.bizId(), req.debug(), req.facts()));
        return ExecuteResponse.from(outcome, req.debug());
    }
}
