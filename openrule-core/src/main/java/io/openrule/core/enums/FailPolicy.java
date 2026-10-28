package io.openrule.core.enums;

/** 节点异常治理策略。 */
public enum FailPolicy {
    SKIP,   // 记录后跳过，流程继续
    REVIEW, // 产出 REVIEW 建议，流程继续
    REJECT, // 产出 REJECT 建议并终止
    ABORT   // 整个流程异常终止，向调用方抛错
}
