package io.openrule.core.context;

import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行上下文。并发类型选择是硬性约束（C9）：
 * variables=ConcurrentHashMap，nodeResults=CopyOnWriteArrayList，控制位 volatile。
 * 并行节点禁止直接写本对象（C1）——写入走 NodeResult.outputs。
 */
public class DecisionContext {

    private final String requestId;
    private final String flowId;
    private final String bizId;
    private final FactMap facts;

    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final List<NodeResult> nodeResults = new CopyOnWriteArrayList<>();

    private volatile boolean stopped = false;
    private volatile Decision finalDecision;
    private volatile String finalReason;

    public DecisionContext(String requestId, String flowId,
                           String bizId, Map<String, Object> facts) {
        this.requestId = requestId;
        this.flowId = flowId;
        this.bizId = bizId;
        this.facts = new FactMap(facts);
    }

    public String getRequestId() { return requestId; }
    public String getFlowId()    { return flowId; }
    public String getBizId()     { return bizId; }
    public FactMap getFacts()    { return facts; }

    public Object fact(String key)     { return facts.get(key); }
    public Object variable(String key) { return variables.get(key); }
    public boolean isStopped()         { return stopped; }
    public List<NodeResult> getNodeResults() { return nodeResults; }

    public void putVariable(String k, Object v) { variables.put(k, v); }
    public void stop()                          { this.stopped = true; }
    public void addNodeResult(NodeResult r)     { nodeResults.add(r); }

    public Decision getFinalDecision()            { return finalDecision; }
    public void setFinalDecision(Decision d)      { this.finalDecision = d; }
    public String getFinalReason()                { return finalReason; }
    public void setFinalReason(String r)          { this.finalReason = r; }
}
