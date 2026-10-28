package io.openrule.studio.host.fact;

import io.openrule.core.studio.StudioDeadline;
import io.openrule.spring.fact.FactResolutionReport;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Request-thread state only. No raw facts or secrets enter error bodies. */
final class FactExecutionContext {
    final long start = System.nanoTime();
    final String traceId = UUID.randomUUID().toString();
    String requestId;
    StudioDeadline deadline;
    FactResolutionReport report;
    Map<String, Object> execution;
    long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    Map<String, Object> error(String code, String message, Object issues) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code); body.put("message", message); body.put("requestId", requestId); body.put("traceId", traceId);
        body.put("issues", issues); body.put("execution", execution);
        if (report != null && (code.equals("OR-EXECUTION-TIMEOUT") || code.equals("OR-EXECUTION-CANCELLED") || code.equals("OR-FACT-OVERLOADED")))
            report = new FactResolutionReport(report.mode(), "FAILED", report.elapsedMillis(), report.fields(), report.calls());
        body.put("factResolution", report); body.put("totalElapsedMillis", elapsed());
        return body;
    }
}
