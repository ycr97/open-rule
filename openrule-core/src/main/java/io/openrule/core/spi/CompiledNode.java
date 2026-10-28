package io.openrule.core.spi;

import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 节点编译产物：定义快照 + 编译制品 + 已解析执行器。 */
public class CompiledNode {
    private final NodeDefinition definition;
    private final Object compiledArtifact;
    private final NodeExecutor executor;

    public CompiledNode(NodeDefinition definition, Object compiledArtifact) {
        this(definition, compiledArtifact, null);
    }

    public CompiledNode(NodeDefinition definition, Object compiledArtifact, NodeExecutor executor) {
        this.definition = snapshot(definition);
        this.compiledArtifact = compiledArtifact;
        this.executor = executor;
    }

    public NodeDefinition getDefinition() { return definition; }
    public Object getCompiledArtifact()   { return compiledArtifact; }
    public NodeExecutor getExecutor()     { return executor; }

    /** 兼容自定义执行器仍返回二参 CompiledNode 的场景，由 FlowCompiler 补绑定。 */
    public CompiledNode withExecutor(NodeExecutor resolvedExecutor) {
        if (executor == resolvedExecutor) {
            return this;
        }
        return new CompiledNode(definition, compiledArtifact, resolvedExecutor);
    }

    private static NodeDefinition snapshot(NodeDefinition source) {
        if (source == null) {
            throw new IllegalArgumentException("Node definition must not be null");
        }
        OperatorDef operator = null;
        if (source.getOperatorDef() != null) {
            operator = new OperatorDef();
            operator.setLeftFact(source.getOperatorDef().getLeftFact());
            operator.setOperator(source.getOperatorDef().getOperator());
            operator.setRightValue(immutableValue(source.getOperatorDef().getRightValue()));
        }
        return NodeDefinition.builder()
                .nodeId(source.getNodeId())
                .nodeName(source.getNodeName())
                .nodeType(source.getNodeType())
                .order(source.getOrder())
                .operatorDef(operator)
                .decisionOnHit(source.getDecisionOnHit())
                .stopOnHit(source.isStopOnHit())
                .failPolicy(source.getFailPolicy())
                .timeoutMillis(source.getTimeoutMillis())
                .build();
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            source.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            source.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> source) {
            Set<Object> copy = new LinkedHashSet<>();
            source.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            source.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(immutableValue(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
