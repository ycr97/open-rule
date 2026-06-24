package io.openrule.spring.service;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.spring.loader.FlowCompiler;
import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.model.ExecuteCommand;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.port.ExecutionLogger;
import io.openrule.spring.port.FlowChangeNotifier;
import io.openrule.spring.port.FlowDefinitionRepository;

import java.util.Map;
import java.util.UUID;

/** 对外编排门面：execute / registerFlow / simulate。 */
public class OpenRuleService {

    private final FlowLoader flowLoader;
    private final FlowCompiler flowCompiler;
    private final FlowExecutor flowExecutor;
    private final FlowDefinitionRepository repository;
    private final ExecutionLogger executionLogger;
    private final FlowChangeNotifier changeNotifier;

    public OpenRuleService(FlowLoader flowLoader, FlowCompiler flowCompiler, FlowExecutor flowExecutor,
                           FlowDefinitionRepository repository, ExecutionLogger executionLogger,
                           FlowChangeNotifier changeNotifier) {
        this.flowLoader = flowLoader;
        this.flowCompiler = flowCompiler;
        this.flowExecutor = flowExecutor;
        this.repository = repository;
        this.executionLogger = executionLogger;
        this.changeNotifier = changeNotifier;
    }

    public ExecutionOutcome execute(ExecuteCommand cmd) {
        String requestId = (cmd.requestId() == null || cmd.requestId().isBlank())
                ? UUID.randomUUID().toString() : cmd.requestId();
        DecisionContext ctx = new DecisionContext(requestId, cmd.flowId(), cmd.bizId(), cmd.facts());
        CompiledFlow flow = flowLoader.loadActive(cmd.flowId());
        FlowResult result = flowExecutor.execute(ctx, flow);
        safeLog(result, ctx, flow.getVersion());
        return new ExecutionOutcome(result, flow.getVersion());
    }

    public FlowDefinition registerFlow(FlowDefinition def) {
        flowCompiler.validate(def);                 // 残缺配置抛 FlowValidationException
        FlowDefinition saved = repository.save(def);
        changeNotifier.publishInvalidation(saved.getFlowId());
        return saved;
    }

    public ExecutionOutcome simulate(FlowDefinition draft, Map<String, Object> facts) {
        flowCompiler.validate(draft);
        CompiledFlow flow = flowCompiler.compile(draft);   // 即时编译，不缓存、不校验 enabled
        DecisionContext ctx = new DecisionContext(UUID.randomUUID().toString(),
                draft.getFlowId(), "SIMULATE", facts);
        FlowResult result = flowExecutor.execute(ctx, flow);
        return new ExecutionOutcome(result, draft.getVersion());
    }

    /** C12：日志失败不影响主流程。 */
    private void safeLog(FlowResult result, DecisionContext ctx, int flowVersion) {
        try {
            executionLogger.log(result, ctx, flowVersion);
        } catch (Exception ignored) {
            // 仅吞掉；M2b 接异步落库后在此加告警计数器
        }
    }
}
