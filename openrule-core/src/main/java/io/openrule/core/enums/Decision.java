package io.openrule.core.enums;

/**
 * 决策结果。ordinal 越大风险越高：PASS &lt; LIMIT &lt; REVIEW &lt; REJECT。
 */
public enum Decision {
    PASS, LIMIT, REVIEW, REJECT;

    /** 本决策是否比 other 风险更高（严格大于）。 */
    public boolean riskierThan(Decision other) {
        return this.ordinal() > other.ordinal();
    }
}
