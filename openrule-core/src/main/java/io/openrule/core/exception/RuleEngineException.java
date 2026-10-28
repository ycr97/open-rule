package io.openrule.core.exception;

/** 引擎运行期异常。 */
public class RuleEngineException extends RuntimeException {
    public RuleEngineException(String message) { super(message); }
    public RuleEngineException(String message, Throwable cause) { super(message, cause); }
}
