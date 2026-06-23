package io.openrule.core.definition;

import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点定义。M1 仅承载 OPERATOR 所需字段；后续里程碑只加不改（脚本/评分卡等字段后补）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeDefinition {
    private String   nodeId;
    private String   nodeName;
    private NodeType nodeType;
    private int      order;

    private OperatorDef operatorDef;

    private Decision decisionOnHit;
    private boolean  stopOnHit;

    private FailPolicy failPolicy;
    private long       timeoutMillis;
}
