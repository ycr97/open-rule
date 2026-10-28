package io.openrule.spring.fact;

public final class MockFactProvider implements FactProvider {
    @Override public String type() { return "MOCK"; }
    @Override public FactBatchResult resolve(FactBatchRequest request) {
        try { FactTransportSupport.encode(FactTransportSupport.requestBody(request)); }
        catch (RuntimeException ex) { return FactBatchResult.failed(request, "OR-FACT-REQUEST-INPUT", false); }
        if (request.deadline().failureCode() != null || request.remainingNanos() == 0)
            return FactBatchResult.failed(request, "OR-FACT-TIMEOUT", false);
        request.attempt().markStarted();
        return switch (request.provider().mock().outcome()) {
            case TIMEOUT -> FactBatchResult.failed(request, "OR-FACT-TIMEOUT", true);
            case ERROR -> FactBatchResult.failed(request, "OR-FACT-MOCK", true);
            case RESPONSE -> {
                try {
                    byte[] bytes = FactTransportSupport.encode(request.provider().mock().response());
                    yield FactTransportSupport.response(request, FactTransportSupport.parse(bytes));
                } catch (RuntimeException ex) { yield FactBatchResult.failed(request, "OR-FACT-RESPONSE", true); }
            }
        };
    }
}
