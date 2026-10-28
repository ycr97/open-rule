package io.openrule.spring.fact;

import java.util.Map;

/** Internal result. The host must project report() and redact the subsequent Core result. */
public record FactResolutionResult(Map<String, Object> facts, FactResolutionReport report, String errorCode) {
    public FactResolutionResult { facts = FactValues.map(facts); }
    public boolean successful() { return errorCode == null; }
    @Override public String toString() { return "FactResolutionResult[report=" + report + ",errorCode=" + errorCode + "]"; }
}
