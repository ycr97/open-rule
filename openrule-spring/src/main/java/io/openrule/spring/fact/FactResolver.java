package io.openrule.spring.fact;

import io.openrule.core.studio.RequiredFact;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.openrule.spring.fact.FactConfiguration.*;

/** Request-serial orchestration with a shared, fail-fast in-flight bound across requests. */
public final class FactResolver {
    private final Map<String, FactProvider> providers;
    private final Executor executor;
    private final Semaphore permits;

    public FactResolver(Executor executor, List<FactProvider> adapters) { this(executor, adapters, 64); }
    public FactResolver(Executor executor, List<FactProvider> adapters, int maxInFlight) {
        this.executor = Objects.requireNonNull(executor);
        if (maxInFlight < 1) throw new IllegalArgumentException("maxInFlight must be positive");
        permits = new Semaphore(maxInFlight);
        Map<String, FactProvider> registry = new HashMap<>();
        for (FactProvider adapter : adapters) {
            if (!Set.of("HTTP", "MOCK").contains(adapter.type()) || registry.putIfAbsent(adapter.type(), adapter) != null)
                throw new IllegalArgumentException("Invalid or duplicate provider type");
        }
        providers = Map.copyOf(registry);
    }

    public FactResolutionResult resolve(FactResolutionRequest request) {
        long start = System.nanoTime();
        Map<String, Binding> catalog = new HashMap<>();
        request.snapshot().facts().forEach(f -> catalog.put(f.path(), f));
        Map<String, Provider> configs = new HashMap<>();
        request.snapshot().providers().forEach(p -> configs.put(p.providerId(), p));
        Map<String, FactResolutionReport.Field> fields = new TreeMap<>(FactValues::comparePaths);
        Map<String, List<Binding>> groups = new TreeMap<>();
        List<FactResolutionReport.Call> calls = new ArrayList<>();
        Map<String, Object> facts = request.callerFacts();
        for (RequiredFact required : request.requiredFacts()) {
            String path = required.path();
            Binding binding = catalog.get(path);
            var value = FactValues.lookup(facts, path);
            if (value.present()) fields.put(path, field(path, binding, null, "resolved", "CALLER", value.value(), null));
            else if (binding == null || binding.providerId() == null)
                fields.put(path, field(path, binding, null, "missing", "NONE", null, "OR-FACT-UNBOUND"));
            else if (FactValues.blocked(facts, path))
                fields.put(path, field(path, binding, null, "failed", "NONE", null, "OR-FACT-PATH-BLOCKED"));
            else groups.computeIfAbsent(binding.providerId(), ignored -> new ArrayList<>()).add(binding);
        }
        String error = request.deadline().failureCode();
        for (var group : groups.entrySet()) {
            Provider config = configs.get(group.getKey());
            List<Binding> bindings = group.getValue().stream().sorted(Comparator.comparing(Binding::path, FactValues::comparePaths)).toList();
            if (error != null) {
                for (Binding b : bindings) fields.put(b.path(), field(b.path(), b, config, "failed", "PROVIDER", null, error));
                continue;
            }
            long callStart = System.nanoTime();
            long callDeadline = Math.min(request.deadline().expiresAtNanos(), callStart + TimeUnit.MILLISECONDS.toNanos(
                    config.http() == null ? 60000 : config.http().readTimeoutMillis()));
            FactBatchRequest batch = new FactBatchRequest(request.traceId(), request.purpose(), config, bindings,
                    request.callerFacts(), request.deadline(), callDeadline);
            String policy = !config.enabled() ? "OR-FACT-DISABLED"
                    : request.mode() == Mode.HTTP && config.http() == null ? "OR-FACT-MOCK-FORBIDDEN"
                    : request.mode() == Mode.HTTP && request.purpose() == Purpose.SIMULATE && !config.http().allowSimulationHttp()
                    ? "OR-FACT-SIMULATION-DENIED" : null;
            FactBatchResult result;
            if (policy != null) result = FactBatchResult.failed(batch, policy, false);
            else {
                FactProvider adapter = providers.get(request.mode().name());
                if (adapter == null) throw new IllegalStateException("Missing adapter: " + request.mode());
                if (!permits.tryAcquire()) {
                    error = "OR-FACT-OVERLOADED";
                    result = FactBatchResult.failed(batch, error, false);
                } else result = invoke(adapter, batch);
            }
            // The request budget has priority over both successful and locally timed-out completion.
            if (request.deadline().failureCode() != null) {
                error = request.deadline().failureCode();
                result = FactBatchResult.failed(batch, error, batch.attempt().started());
            } else if ("OR-EXECUTION-CANCELLED".equals(result.errorCode()) || "OR-FACT-OVERLOADED".equals(result.errorCode())) error = result.errorCode();
            Map<String, FactBatchResult.Field> returned = new HashMap<>();
            for (var f : result.fields()) returned.put(f.path(), f);
            int resolved = 0;
            for (Binding binding : bindings) {
                String late = request.deadline().failureCode();
                if (late != null) error = late;
                var value = returned.get(binding.path());
                if (error != null) value = new FactBatchResult.Field(binding.path(), FactBatchResult.Status.failed, null, error);
                else if (value == null || value.status() == FactBatchResult.Status.resolved && !binding.type().accepts(value.value()))
                    value = new FactBatchResult.Field(binding.path(), FactBatchResult.Status.failed, null, "OR-FACT-TYPE");
                if (value.status() == FactBatchResult.Status.resolved) {
                    facts = FactValues.insert(facts, binding.path(), value.value());
                    resolved++;
                }
                fields.put(binding.path(), field(binding.path(), binding, config, value.status().name(), "PROVIDER", value.value(), value.errorCode()));
            }
            String code = error != null ? error : result.errorCode();
            String status = "OR-EXECUTION-CANCELLED".equals(code) ? "CANCELLED"
                    : "OR-EXECUTION-TIMEOUT".equals(code) || "OR-FACT-TIMEOUT".equals(code) ? "TIMED_OUT"
                    : !result.attempted() ? "SKIPPED" : resolved == bindings.size() ? "SUCCEEDED"
                    : resolved > 0 ? "PARTIAL" : "FAILED";
            if (code == null && resolved == 0) code = fields.get(bindings.getFirst().path()).error().code();
            calls.add(new FactResolutionReport.Call(config.providerId(), config.revision(), request.traceId(), request.mode().name(),
                    status, result.attempted(), bindings.stream().map(Binding::path).toList(), elapsed(callStart),
                    FactResolutionReport.Error.of(resolved > 0 && error == null ? null : code)));
        }
        if (request.deadline().failureCode() != null) error = request.deadline().failureCode();
        long resolved = fields.values().stream().filter(f -> f.status().equals("resolved")).count();
        String status = error != null ? "FAILED" : resolved == fields.size() ? "COMPLETE" : resolved > 0 ? "PARTIAL" : "UNRESOLVED";
        return new FactResolutionResult(facts, new FactResolutionReport(request.mode(), status, elapsed(start), List.copyOf(fields.values()), calls), error);
    }

    private FactBatchResult invoke(FactProvider adapter, FactBatchRequest batch) {
        // 0=queued, 1=running, 2=cancelled before start. Running permits are released only in worker finally.
        AtomicInteger state = new AtomicInteger();
        FutureTask<FactBatchResult> task = new FutureTask<>(() -> {
            if (!state.compareAndSet(0, 1)) return FactBatchResult.failed(batch, "OR-EXECUTION-CANCELLED", false);
            try {
                String failure = batch.deadline().failureCode();
                if (failure != null) return FactBatchResult.failed(batch, failure, false);
                if (batch.remainingNanos() == 0) return FactBatchResult.failed(batch, "OR-FACT-TIMEOUT", false);
                return Objects.requireNonNull(adapter.resolve(batch));
            } finally { permits.release(); }
        });
        try {
            executor.execute(task);
            while (true) {
                String failure = batch.deadline().failureCode();
                if (failure != null) return FactBatchResult.failed(batch, failure, batch.attempt().started());
                long left = batch.remainingNanos();
                if (left == 0) return FactBatchResult.failed(batch, "OR-FACT-TIMEOUT", batch.attempt().started());
                try { return task.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException ignored) { }
            }
        } catch (RejectedExecutionException ex) {
            return FactBatchResult.failed(batch, "OR-FACT-OVERLOADED", false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FactBatchResult.failed(batch, "OR-EXECUTION-CANCELLED", batch.attempt().started());
        } catch (ExecutionException ex) {
            return FactBatchResult.failed(batch, "OR-FACT-RESPONSE", batch.attempt().started());
        } finally {
            if (state.compareAndSet(0, 2)) permits.release();
            task.cancel(true);
        }
    }
    private static FactResolutionReport.Field field(String path, Binding binding, Provider provider,
                                                     String status, String source, Object value, String code) {
        return new FactResolutionReport.Field(path, status, source, binding == null ? null : binding.factId(),
                binding == null ? null : binding.revision(), provider == null ? null : provider.providerId(),
                provider == null ? null : provider.revision(), status.equals("resolved") ? FactRedaction.summary(value, binding) : null,
                FactResolutionReport.Error.of(code));
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}
