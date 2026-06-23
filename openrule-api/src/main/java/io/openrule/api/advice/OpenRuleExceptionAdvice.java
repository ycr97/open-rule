package io.openrule.api.advice;

import io.openrule.api.dto.ErrorBody;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * standalone 错误处理（条件化：openrule.api.exception-advice.enabled=false 可关，让 ycr 接管）。
 * 控制器返回裸 DTO；ycr 形态下本 advice 退让。
 */
@RestControllerAdvice
public class OpenRuleExceptionAdvice {

    @ExceptionHandler(FlowValidationException.class)
    public ResponseEntity<ErrorBody> onValidation(FlowValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody.of("FLOW_VALIDATION", e.getMessage()));
    }

    @ExceptionHandler(RuleEngineException.class)
    public ResponseEntity<ErrorBody> onEngine(RuleEngineException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        HttpStatus status = (msg.contains("not found") || msg.contains("disabled"))
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(ErrorBody.of("RULE_ENGINE", msg));
    }
}
