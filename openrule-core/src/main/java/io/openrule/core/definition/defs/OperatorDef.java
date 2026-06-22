package io.openrule.core.definition.defs;

import lombok.Data;

/** OPERATOR 节点配置。leftFact 是 fact./var. 引用；rightValue 是字面量（数值/集合等）。 */
@Data
public class OperatorDef {
    private String leftFact;
    private String operator;
    private Object rightValue;
}
