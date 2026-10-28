package io.openrule.core.exception;

/** 流程/节点配置校验失败（保存时抛出，阻止保存）。 */
public class FlowValidationException extends RuntimeException {
    public FlowValidationException(String message) { super(message); }
}
