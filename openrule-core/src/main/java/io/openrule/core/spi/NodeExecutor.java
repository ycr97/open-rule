package io.openrule.core.spi;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;

/**
 * 三阶段 SPI：validate(保存时) → compile(加载时) → execute(运行时)。
 * execute 约束：禁写 context（C1）；不吞异常，直接抛出由 NodeRunner 统一治理（C8）。
 */
public interface NodeExecutor {

    NodeType supportType();

    default void validate(NodeDefinition node) throws FlowValidationException {}

    default CompiledNode compile(NodeDefinition node) {
        return new CompiledNode(node, null);
    }

    NodeResult execute(DecisionContext context, CompiledNode compiled);
}
