package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.core.studio.*;
import io.openrule.spring.fact.*;
import io.openrule.spring.studio.*;
import java.util.*;
import static io.openrule.spring.fact.FactConfiguration.*;

/** EE entry-point orchestration; all fact resolution is delegated to B1. */
public final class FactService {
    private final JdbcFactStore facts;
    private final FactDocuments documents;
    private final StudioService flows;
    private final StudioDocumentCodec codec;
    private final StudioEngine engine;
    private final FactResolver resolver;
    private final long maxTimeout;
    public FactService(JdbcFactStore facts, FactDocuments documents, StudioService flows, StudioDocumentCodec codec,
                       StudioEngine engine, FactResolver resolver, long maxTimeout) {
        this.facts = facts; this.documents = documents; this.flows = flows; this.codec = codec;
        this.engine = engine; this.resolver = resolver; this.maxTimeout = maxTimeout;
    }
    public Map<String, Object> required(String flowId, String version) {
        var stored = version(flowId, version);
        JsonNode document = codec.parse(stored.documentJson());
        var plan = plan(document);
        return Map.of("flowId", flowId, "version", version, "revision", Long.toString(stored.revision()),
                "definitionChecksum", checksum(stored, document), "requiredFacts", plan.requiredFacts());
    }
    Map<String, Object> execute(String flowId, String version, JsonNode body, boolean simulate, FactExecutionContext context) {
        documents.validate(simulate ? "FactSimulationRequest" : "FactExecutionRequest", body);
        long revision = simulate ? FactDocuments.positive(body.path("expectedRevision").asText()) : 0;
        begin(body, context);
        var stored = version(flowId, version);
        if (simulate && revision != stored.revision()) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
        if (!simulate && !stored.status().equals("PUBLISHED")) throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "LIVE requires a published version");
        JsonNode document = codec.parse(stored.documentJson());
        var plan = plan(document);
        check(context);
        var snapshot = facts.snapshot(plan.requiredFacts().stream().map(RequiredFact::path).toList(), null).snapshot();
        var resolved = resolver.resolve(new FactResolutionRequest(context.traceId, simulate ? Purpose.SIMULATE : Purpose.LIVE,
                Mode.valueOf(body.path("factMode").asText()), plan.requiredFacts(), documents.map(body.path("facts")), snapshot, context.deadline));
        context.report = resolved.report();
        if (!resolved.successful()) throw technical(resolved.errorCode());
        check(context);
        StudioModel.Result result = engine.execute(plan, resolved.facts(), context.deadline);
        context.execution = StudioService.executionResult(stored, body.path("requestId").asText(), body.path("bizId").asText(),
                simulate ? "SIMULATE" : "LIVE", context.traceId, checksum(stored, document), FactRedaction.execution(result));
        if (!result.status().equals("DECIDED")) throw technical(result.errorCode());
        // Projection consumes the same total budget. If it expires, attach a true FAILED Core-shaped result.
        String failure = context.deadline.failureCode();
        if (failure != null) {
            context.execution.put("status", "FAILED"); context.execution.put("decision", null);
            throw technical(failure);
        }
        return Map.of("execution", context.execution, "factResolution", context.report, "totalElapsedMillis", context.elapsed());
    }
    Map<String, Object> mock(String id, JsonNode body, FactExecutionContext context) {
        FactDocuments.id(id); documents.validate("MockTestRequest", body);
        long revision = FactDocuments.positive(body.path("expectedRevision").asText());
        begin(body, context);
        var selection = facts.snapshot(List.of(), id);
        if (revision != Long.parseLong(selection.providerRevision())) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
        var required = selection.snapshot().facts().stream().map(b -> new RequiredFact(b.path(), List.of(), false, List.of())).toList();
        var result = resolver.resolve(new FactResolutionRequest(context.traceId, Purpose.MOCK_TEST, Mode.MOCK, required,
                documents.map(body.path("facts")), selection.snapshot(), context.deadline));
        context.report = result.report();
        if (!result.successful()) throw technical(result.errorCode());
        check(context);
        return Map.of("requestId", context.requestId, "traceId", context.traceId, "factResolution", context.report, "totalElapsedMillis", context.elapsed());
    }
    private void begin(JsonNode body, FactExecutionContext context) {
        long timeout = body.path("timeoutMillis").longValue();
        if (timeout > maxTimeout) throw new StudioProblem(400, "OR-REQUEST-INVALID", "Timeout exceeds host maximum");
        context.requestId = body.path("requestId").asText(); context.deadline = StudioDeadline.afterMillis(timeout);
    }
    private StudioStore.Version version(String id, String version) { FactDocuments.id(id); FactDocuments.positive(version); return flows.version(id, version); }
    private StudioCompiledPlan plan(JsonNode document) {
        var validation = codec.validate(document);
        if (!validation.valid()) throw new StudioProblem(400, "OR-DEF-VALIDATION", "Definition is not executable", validation.issues());
        return new StudioCompiler().compilePlan(codec.bind(document));
    }
    private String checksum(StudioStore.Version stored, JsonNode document) { return stored.status().equals("PUBLISHED") ? stored.checksum() : codec.checksum(document); }
    private static void check(FactExecutionContext context) { if (context.deadline.failureCode() != null) throw technical(context.deadline.failureCode()); }
    private static StudioProblem technical(String code) {
        return new StudioProblem(code.equals("OR-EXECUTION-TIMEOUT") ? 504 : code.contains("OVERLOADED") ? 503 : 500, code, "Execution failed");
    }
}
