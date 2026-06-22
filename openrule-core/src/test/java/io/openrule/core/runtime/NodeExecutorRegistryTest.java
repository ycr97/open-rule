package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeExecutorRegistryTest {

    static class StubExecutor implements NodeExecutor {
        private final NodeType type;
        StubExecutor(NodeType type) { this.type = type; }
        public NodeType supportType() { return type; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            return NodeResult.builder().nodeId("x").build();
        }
    }

    @Test
    void routesByType() {
        NodeExecutor op = new StubExecutor(NodeType.OPERATOR);
        NodeExecutorRegistry reg = new NodeExecutorRegistry(List.of(op));
        assertThat(reg.getRequired(NodeType.OPERATOR)).isSameAs(op);
    }

    @Test
    void duplicateType_throws() {
        assertThatThrownBy(() -> new NodeExecutorRegistry(
                List.of(new StubExecutor(NodeType.OPERATOR), new StubExecutor(NodeType.OPERATOR))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void missingType_throws() {
        NodeExecutorRegistry reg = new NodeExecutorRegistry(List.of(new StubExecutor(NodeType.OPERATOR)));
        assertThatThrownBy(() -> reg.getRequired(NodeType.SCRIPT_GROOVY))
                .isInstanceOf(RuleEngineException.class)
                .hasMessageContaining("No executor");
    }
}
