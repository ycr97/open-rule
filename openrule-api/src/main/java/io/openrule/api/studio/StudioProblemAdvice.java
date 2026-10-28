package io.openrule.api.studio;

import io.openrule.spring.studio.StudioProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = StudioController.class)
public final class StudioProblemAdvice {
    @ExceptionHandler(StudioProblem.class)
    public ResponseEntity<Map<String, Object>> problem(StudioProblem problem, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", problem.code());
        body.put("message", problem.getMessage());
        Object requestId = request.getAttribute("studioRequestId");
        if (problem.execution() instanceof Map<?, ?> execution) {
            body.put("requestId", execution.get("requestId"));
            body.put("traceId", execution.get("traceId"));
        } else {
            body.put("requestId", requestId == null ? UUID.randomUUID().toString() : requestId);
            body.put("traceId", UUID.randomUUID().toString());
        }
        body.put("issues", problem.issues());
        body.put("execution", problem.execution());
        return ResponseEntity.status(problem.status()).body(body);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception problem, HttpServletRequest request) {
        return problem(new StudioProblem(400, "OR-REQUEST-INVALID", "Malformed request"), request);
    }
}
