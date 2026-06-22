package io.openrule.core.enums;

/** 决策聚合策略。M1 仅实现 PRIORITY。 */
public enum AggregatePolicy {
    PRIORITY,
    FIRST_TERMINAL,
    SCORE_THRESHOLD
}
