package io.openrule.studio.host.fact;

import io.openrule.spring.studio.StudioProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.List;
import java.util.Map;

@RestControllerAdvice(assignableTypes = FactController.class)
public final class FactProblemAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> problem(Exception error, HttpServletRequest request) {
        FactExecutionContext context = (FactExecutionContext) request.getAttribute(FactController.CONTEXT);
        if (context == null) context = new FactExecutionContext();
        int status = 500; String code = "OR-ENGINE-FAILURE", message = "Request failed"; Object issues = List.of();
        if (error instanceof StudioProblem p) { status = p.status(); code = p.code(); message = p.getMessage(); issues = p.issues(); }
        else if (error instanceof DataAccessException || error instanceof org.springframework.transaction.TransactionException) { status = 503; code = "OR-FACT-STORE"; message = "Configuration store unavailable"; }
        else if (error instanceof MethodArgumentTypeMismatchException || error instanceof HttpMessageNotReadableException) { status = 400; code = "OR-REQUEST-INVALID"; message = "Malformed request"; }
        if (context.deadline != null && context.deadline.failureCode() != null) {
            code = context.deadline.failureCode(); status = code.equals("OR-EXECUTION-TIMEOUT") ? 504 : 500; message = "Execution failed";
            if (context.execution != null) { context.execution.put("status", "FAILED"); context.execution.put("decision", null); }
        }
        return ResponseEntity.status(status).body(context.error(code, message, issues));
    }
}
