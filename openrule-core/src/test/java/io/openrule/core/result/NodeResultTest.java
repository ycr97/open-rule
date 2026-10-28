package io.openrule.core.result;

import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NodeResultTest {

    @Test
    void builder_defaultsOutputsAndDetailsToEmptyMaps() {
        NodeResult r = NodeResult.builder().nodeId("n1").build();
        assertThat(r.getOutputs()).isEmpty();
        assertThat(r.getDetails()).isEmpty();
    }

    @Test
    void toBuilder_allowsImmutableCopyWithOverride() {
        NodeResult base = NodeResult.builder()
                .nodeId("n1").nodeType(NodeType.OPERATOR).hit(true).build();
        NodeResult withDecision = base.toBuilder().decision(Decision.REJECT).build();
        assertThat(withDecision.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(withDecision.isHit()).isTrue();
        assertThat(base.getDecision()).isNull();
    }

    @Test
    void stageResult_ofAndSkipped() {
        StageResult ok = StageResult.of("s1", List.of(NodeResult.builder().nodeId("n").build()));
        assertThat(ok.getStageId()).isEqualTo("s1");
        assertThat(ok.isSkipped()).isFalse();
        assertThat(ok.getNodeResults()).hasSize(1);

        StageResult sk = StageResult.skipped("s2");
        assertThat(sk.isSkipped()).isTrue();
        assertThat(sk.getNodeResults()).isEmpty();
    }

    @Test
    void flowResult_buildsWithAllFields() {
        FlowResult fr = FlowResult.builder()
                .requestId("REQ").flowId("f").bizId("b")
                .decision(Decision.PASS).reason("ok").totalScore(12)
                .hitNodes(List.of("n1")).costMillis(5).build();
        assertThat(fr.getDecision()).isEqualTo(Decision.PASS);
        assertThat(fr.getTotalScore()).isEqualTo(12);
        assertThat(fr.getHitNodes()).containsExactly("n1");
    }
}
