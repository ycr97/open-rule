package io.openrule.core.enums;

/** 节点类型。M1 仅 OPERATOR 有执行器，其余为后续里程碑预留。 */
public enum NodeType {
    OPERATOR,
    JAVA_NATIVE,
    SCRIPT_GROOVY,
    SCRIPT_JS,
    SCRIPT_PYTHON,
    RULE_SET,
    SCORECARD,
    DECISION_TABLE,
    DECISION_TREE,
    SUB_FLOW
}
