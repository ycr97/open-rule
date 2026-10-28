package io.openrule.spring.fact;

import io.openrule.core.studio.StudioModel;
import java.util.*;

/** Shared conservative projection for all M2 host entry points, including mock-tests. */
public final class FactRedaction {
    private FactRedaction() { }
    private static final String MASK = "[REDACTED]";
    static String summary(Object value, FactConfiguration.Binding binding) {
        if (binding == null || binding.sensitive() || value instanceof Map<?, ?> || value instanceof List<?>) return MASK;
        String text = FactTransportSupport.scalarJson(value);
        return text.codePointCount(0, text.length()) <= 128 ? text : text.substring(0, text.offsetByCodePoints(0, 125)) + "...";
    }
    public static StudioModel.Result execution(StudioModel.Result result) {
        var nodes = result.nodeResults().stream().map(n -> new StudioModel.NodeResult(n.stageId(), n.nodeId(), n.type(),
                n.status(), n.hit(), masked(n.outputs()), n.reasonCodes(), n.details().stream().map(d ->
                d instanceof StudioModel.RuleDetail r ? (StudioModel.Detail) new StudioModel.RuleDetail(r.kind(), r.ruleId(), r.reasonCode(), MASK) : d).toList(),
                n.failure() == null ? null : n.failure().startsWith("OR-ENGINE-FAILURE")
                        ? "OR-ENGINE-FAILURE: Node execution failed" : "Node execution failed", n.skipReason(), n.elapsedMillis())).toList();
        var decision = result.decision();
        if (decision != null) decision = new StudioModel.Decision(decision.decisionCode(), decision.reasonCodes(),
                decision.score(), decision.terminalNodeId(), masked(decision.outputs()));
        return new StudioModel.Result(result.status(), decision, result.stageResults(), nodes, masked(result.variables()), result.errorCode(), result.elapsedMillis());
    }
    private static Map<String, Object> masked(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>(); values.keySet().forEach(key -> result.put(key, MASK));
        return Collections.unmodifiableMap(result);
    }
}
