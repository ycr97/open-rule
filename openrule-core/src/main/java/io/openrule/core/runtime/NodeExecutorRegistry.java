package io.openrule.core.runtime;

import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.spi.NodeExecutor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** NodeType → NodeExecutor 路由表。构造时收集所有执行器，重复类型即缺陷。 */
public class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> registry = new EnumMap<>(NodeType.class);

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        for (NodeExecutor e : executors) {
            NodeExecutor prev = registry.put(e.supportType(), e);
            if (prev != null) {
                throw new IllegalStateException("Duplicate executor for " + e.supportType());
            }
        }
    }

    public NodeExecutor getRequired(NodeType type) {
        NodeExecutor e = registry.get(type);
        if (e == null) {
            throw new RuleEngineException("No executor for NodeType: " + type);
        }
        return e;
    }
}
