package io.openrule.core.spi;

import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.runtime.CompiledStage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CompiledTest {

    @Test
    void compiledNode_holdsDefinitionAndArtifact() {
        NodeDefinition def = NodeDefinition.builder().nodeId("n").nodeType(NodeType.OPERATOR).build();
        CompiledNode cn = new CompiledNode(def, "ARTIFACT");
        assertThat(cn.getDefinition().getNodeId()).isEqualTo("n");
        assertThat(cn.getCompiledArtifact()).isEqualTo("ARTIFACT");
    }

    @Test
    void compiledStage_delegatesGetters() {
        NodeDefinition def = NodeDefinition.builder().nodeId("n").nodeType(NodeType.OPERATOR).build();
        StageDefinition sd = StageDefinition.builder()
                .stageId("s1").executionMode(ExecutionMode.PARALLEL)
                .skipWhenStopped(true).stageTimeoutMillis(8000).build();
        CompiledStage cs = new CompiledStage(sd, List.of(new CompiledNode(def, null)));
        assertThat(cs.getStageId()).isEqualTo("s1");
        assertThat(cs.getExecutionMode()).isEqualTo(ExecutionMode.PARALLEL);
        assertThat(cs.isSkipWhenStopped()).isTrue();
        assertThat(cs.getStageTimeoutMillis()).isEqualTo(8000);
        assertThat(cs.getNodes()).hasSize(1);
    }
}
