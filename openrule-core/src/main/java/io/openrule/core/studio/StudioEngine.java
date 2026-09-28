package io.openrule.core.studio;

import io.openrule.core.context.FactMap;
import io.openrule.core.studio.StudioModel.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Studio v2 execution path using the existing Core condition and deadline semantics. */
public final class StudioEngine {
    private final ExecutorService pool;
    private final Map<String, NodeLogic> extensions;

    public StudioEngine(ExecutorService pool) { this(pool, Map.of()); }
    public StudioEngine(ExecutorService pool, Map<String, NodeLogic> extensions) {
        this.pool = pool;
        this.extensions = Map.copyOf(extensions);
    }

    @FunctionalInterface
    public interface NodeLogic {
        NodeResult execute(Node node, String stageId, Map<String, Object> facts,
                           Map<String, Object> variables, Map<String, NodeResult> prior) throws Exception;
    }

    public Result execute(Flow definition, Map<String, Object> facts, long requestTimeoutMillis) {
        long start = System.nanoTime();
        Flow flow = new StudioCompiler().compile(definition);
        if (requestTimeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be positive");
        Map<String, Object> inputFacts = new FactMap(facts).asMap();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, NodeResult> prior = new LinkedHashMap<>();
        List<NodeResult> results = new ArrayList<>();
        Decision decision = null;
        String error = null;
        for (Stage stage : flow.stages()) {
            if (error != null) {
                skip(stage, SkipReason.ABORTED, prior, results);
                continue;
            }
            if (decision != null) {
                skip(stage, SkipReason.TERMINATED, prior, results);
                continue;
            }
            if (expired(deadline)) {
                error = "OR-EXECUTION-TIMEOUT";
                skip(stage, SkipReason.ABORTED, prior, results);
                continue;
            }
            boolean enter;
            try {
                enter = stage.when() == null || StudioConditions.evaluate(stage.when(), inputFacts, variables, prior);
            } catch (RuntimeException ex) {
                error = "OR-STAGE-CONDITION";
                skip(stage, SkipReason.ABORTED, prior, results);
                continue;
            }
            if (!enter) {
                skip(stage, SkipReason.WHEN_FALSE, prior, results);
                continue;
            }
            long stageDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stage.timeoutMillis()));
            if (stage.mode() == Mode.PARALLEL) {
                List<NodeResult> batch = runParallel(stage, inputFacts,
                        Collections.unmodifiableMap(new LinkedHashMap<>(variables)),
                        Collections.unmodifiableMap(new LinkedHashMap<>(prior)), stageDeadline, deadline);
                if (Thread.currentThread().isInterrupted()) error = "OR-EXECUTION-CANCELLED";
                else if (expired(deadline)) error = "OR-EXECUTION-TIMEOUT";
                else {
                    for (NodeResult result : batch) {
                        if (fatal(stage, result)) { error = failureCode(result); break; }
                    }
                }
                for (NodeResult result : batch) { prior.put(result.nodeId(), result); results.add(result); }
                if (error != null) continue;
                Map<String, Object> batchOutputs = new LinkedHashMap<>();
                for (NodeResult result : batch) {
                    for (Map.Entry<String, Object> output : result.outputs().entrySet()) {
                        if (batchOutputs.containsKey(output.getKey())) { error = "OR-OUTPUT-CONFLICT"; break; }
                        batchOutputs.put(output.getKey(), output.getValue());
                    }
                    if (error != null) break;
                }
                if (error != null) continue;
                variables.putAll(batchOutputs);
            } else {
                for (int i = 0; i < stage.nodes().size(); i++) {
                    Node node = stage.nodes().get(i);
                    Pending future = submit(node, stage.stageId(), inputFacts,
                            Collections.unmodifiableMap(new LinkedHashMap<>(variables)),
                            Collections.unmodifiableMap(new LinkedHashMap<>(prior)));
                    NodeResult result = await(future, node, stage.stageId(), stageDeadline, deadline);
                    prior.put(node.nodeId(), result);
                    results.add(result);
                    if (expired(deadline)) { error = "OR-EXECUTION-TIMEOUT"; skipRemaining(stage, i + 1, prior, results); break; }
                    if (fatal(stage, result)) { error = failureCode(result); skipRemaining(stage, i + 1, prior, results); break; }
                    variables.putAll(result.outputs());
                    if (node.config() instanceof Terminal terminal && result.status() == Status.SUCCEEDED) {
                        BigDecimal score = null;
                        if (terminal.scoreRef() != null) {
                            Object value = variables.get(terminal.scoreRef());
                            if (!(value instanceof Number number) || value instanceof Double || value instanceof Float) {
                                error = "OR-NODE-FAILED";
                                NodeResult failed = new NodeResult(stage.stageId(), node.nodeId(), node.type(),
                                        Status.FAILED, false, Map.of(), List.of(),
                                        "Terminal scoreRef is missing or non-numeric: " + terminal.scoreRef(), null,
                                        result.elapsedMillis());
                                prior.put(node.nodeId(), failed);
                                results.set(results.size() - 1, failed);
                                skipRemaining(stage, i + 1, prior, results);
                                break;
                            }
                            score = number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
                        }
                        decision = new Decision(terminal.decisionCode(), List.copyOf(terminal.reasonCodes()),
                                score, node.nodeId(), Map.of());
                        break;
                    }
                }
                if (error != null) continue;
            }
        }
        if (error == null && decision == null) error = "OR-FLOW-NO-TERMINAL";
        List<StageResult> stageResults = new ArrayList<>();
        for (Stage stage : flow.stages()) {
            List<NodeResult> stageNodes = results.stream()
                    .filter(node -> node.stageId().equals(stage.stageId())).toList();
            boolean allSkipped = stageNodes.stream().allMatch(node -> node.status() == Status.SKIPPED);
            boolean failed = stageNodes.stream().anyMatch(node -> node.status() == Status.FAILED
                    || node.status() == Status.TIMED_OUT || node.status() == Status.CANCELLED);
            stageResults.add(new StageResult(stage.stageId(), allSkipped ? StageStatus.SKIPPED
                    : failed ? StageStatus.FAILED : StageStatus.SUCCEEDED,
                    allSkipped ? stageNodes.getFirst().skipReason() : null));
        }
        return new Result(error == null ? "DECIDED" : "FAILED", error == null ? decision : null,
                List.copyOf(stageResults), List.copyOf(results),
                Collections.unmodifiableMap(new LinkedHashMap<>(variables)), error, elapsed(start));
    }

    private NodeResult run(Node node, String stageId, Map<String, Object> facts,
                           Map<String, Object> variables, Map<String, NodeResult> prior) {
        long start = System.nanoTime();
        try {
            NodeLogic extension = extensions.get(node.type());
            if (extension != null) {
                NodeResult result = extension.execute(node, stageId, facts, variables, prior);
                if (result == null || !node.nodeId().equals(result.nodeId())
                        || !stageId.equals(result.stageId()) || result.status() == null
                        || result.outputs() == null || result.outputs().containsKey(null) || result.details() == null)
                    throw new IllegalStateException("Invalid node result from " + node.type());
                return result;
            }
            if (node.config() instanceof Operator operator) {
                boolean hit = StudioConditions.evaluate(operator.condition(), facts, variables, prior);
                return new NodeResult(stageId, node.nodeId(), node.type(), Status.SUCCEEDED, hit,
                        Map.of(operator.outputKey(), hit), hit ? List.of(operator.reasonCode()) : List.of(),
                        null, null, elapsed(start));
            }
            if (node.config() instanceof RuleSet rules)
                return rules(node, stageId, facts, variables, prior, rules.outputKey(),
                        rules.rules(), rules.matchPolicy().equals("ALL_MATCH"), start);
            if (node.config() instanceof DecisionTable table)
                return rules(node, stageId, facts, variables, prior, table.outputKey(),
                        table.rules(), false, start);
            if (node.config() instanceof Scorecard scorecard)
                return scorecard(node, stageId, facts, variables, prior, scorecard, start);
            if (node.config() instanceof Terminal)
                return new NodeResult(stageId, node.nodeId(), node.type(), Status.SUCCEEDED, true,
                        Map.of(), List.of(), null, null, elapsed(start));
            throw new IllegalStateException("B3 node executor unavailable: " + node.type());
        } catch (StudioConditions.ConditionFailure ex) {
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.FAILED, false,
                    Map.of(), List.of(), ex.getMessage(), null, elapsed(start));
        } catch (Exception ex) {
            throw new IllegalStateException("Node executor failed: " + node.type(), ex);
        }
    }

    private NodeResult rules(Node node, String stageId, Map<String, Object> facts,
                             Map<String, Object> variables, Map<String, NodeResult> prior,
                             String outputKey, List<Rule> rules, boolean allMatch, long start) {
        List<Detail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Rule rule : rules) {
            if (!StudioConditions.evaluate(rule.condition(), facts, variables, prior)) continue;
            values.add(rule.value());
            reasons.add(rule.reasonCode());
            details.add(new RuleDetail("RULE", rule.id(), rule.reasonCode(), rule.value()));
            if (!allMatch) break;
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put(outputKey, values.isEmpty() ? null
                : allMatch ? Collections.unmodifiableList(new ArrayList<>(values)) : values.getFirst());
        return new NodeResult(stageId, node.nodeId(), node.type(), Status.SUCCEEDED,
                !values.isEmpty(), outputs, List.copyOf(reasons), List.copyOf(details),
                null, null, elapsed(start));
    }

    private NodeResult scorecard(Node node, String stageId, Map<String, Object> facts,
                                 Map<String, Object> variables, Map<String, NodeResult> prior,
                                 Scorecard card, long start) {
        BigDecimal total = BigDecimal.ZERO;
        List<Detail> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Characteristic characteristic : card.characteristics()) {
            Bin matched = null;
            for (Bin bin : characteristic.bins()) {
                if (!StudioConditions.evaluate(bin.condition(), facts, variables, prior)) continue;
                if (matched != null) throw new StudioConditions.ConditionFailure(
                        "Multiple score bins matched: " + characteristic.id());
                matched = bin;
            }
            if (matched == null) throw new StudioConditions.ConditionFailure(
                    "No score bin matched: " + characteristic.id());
            total = total.add(matched.score());
            reasons.add(matched.reasonCode());
            details.add(new ScoreDetail("SCORE_BIN", characteristic.id(), matched.id(),
                    decimal(matched.score()), matched.reasonCode()));
        }
        return new NodeResult(stageId, node.nodeId(), node.type(), Status.SUCCEEDED, true,
                Map.of(card.outputKey(), total), List.copyOf(reasons), List.copyOf(details),
                null, null, elapsed(start));
    }

    private static String decimal(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }

    private Pending submit(Node node, String stageId, Map<String, Object> facts,
                           Map<String, Object> variables, Map<String, NodeResult> prior) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(node.timeoutMillis());
        return new Pending(pool.submit(() -> run(node, stageId, facts, variables, prior)), deadline);
    }

    private List<NodeResult> runParallel(Stage stage, Map<String, Object> facts,
                                         Map<String, Object> variables, Map<String, NodeResult> prior,
                                         long stageDeadline, long requestDeadline) {
        BlockingQueue<Integer> completed = new LinkedBlockingQueue<>();
        List<Pending> pending = new ArrayList<>();
        int size = stage.nodes().size();
        NodeResult[] ordered = new NodeResult[size];
        for (int i = 0; i < size; i++) {
            int index = i;
            Node node = stage.nodes().get(i);
            long nodeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(node.timeoutMillis());
            Future<NodeResult> future = pool.submit(() -> {
                try { return run(node, stage.stageId(), facts, variables, prior); }
                finally { completed.offer(index); }
            });
            pending.add(new Pending(future, nodeDeadline));
        }
        int remaining = size;
        boolean abort = false;
        while (remaining > 0 && !abort) {
            long next = Math.min(stageDeadline, requestDeadline);
            for (int i = 0; i < size; i++) {
                if (ordered[i] != null) continue;
                Pending task = pending.get(i);
                Node node = stage.nodes().get(i);
                if (task.future().isDone()) {
                    ordered[i] = completedResult(task.future(), node, stage.stageId());
                    remaining--;
                    if (fatal(stage, ordered[i])) abort = true;
                } else if (System.nanoTime() >= Math.min(next, task.deadline())) {
                    task.future().cancel(true);
                    ordered[i] = timeoutResult(node, stage.stageId());
                    remaining--;
                    if (fatal(stage, ordered[i])) abort = true;
                } else next = Math.min(next, task.deadline());
            }
            if (abort || remaining == 0) break;
            try {
                completed.poll(Math.max(1, next - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                abort = true;
            }
        }
        if (abort) {
            for (int i = 0; i < size; i++) {
                if (ordered[i] == null) {
                    pending.get(i).future().cancel(true);
                    Node node = stage.nodes().get(i);
                    ordered[i] = new NodeResult(stage.stageId(), node.nodeId(), node.type(), Status.SKIPPED,
                            false, Map.of(), List.of(), null, SkipReason.ABORTED, 0);
                }
            }
        }
        return List.of(ordered);
    }

    private NodeResult completedResult(Future<NodeResult> future, Node node, String stageId) {
        try { return future.get(); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.CANCELLED, false,
                    Map.of(), List.of(), "Execution interrupted", null, 0);
        } catch (Exception ex) {
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.FAILED, false,
                    Map.of(), List.of(), "OR-ENGINE-FAILURE: " + ex.getMessage(), null, 0);
        }
    }

    private NodeResult timeoutResult(Node node, String stageId) {
        return new NodeResult(stageId, node.nodeId(), node.type(), Status.TIMED_OUT, false,
                Map.of(), List.of(), "Node deadline exceeded", null, 0);
    }

    private NodeResult await(Pending pending, Node node, String stageId,
                             long stageDeadline, long requestDeadline) {
        Future<NodeResult> future = pending.future();
        long deadline = Math.min(stageDeadline, Math.min(requestDeadline, pending.deadline()));
        try {
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return timeoutResult(node, stageId);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.CANCELLED, false,
                    Map.of(), List.of(), "Execution interrupted", null, 0);
        } catch (Exception ex) {
            return new NodeResult(stageId, node.nodeId(), node.type(), Status.FAILED, false,
                    Map.of(), List.of(), "OR-ENGINE-FAILURE: " + ex.getMessage(), null, 0);
        }
    }

    private static boolean fatal(Stage stage, NodeResult result) {
        if (result.status() == Status.SUCCEEDED) return false;
        if (result.status() == Status.CANCELLED || result.failure() != null
                && result.failure().startsWith("OR-ENGINE-FAILURE")) return true;
        Node node = stage.nodes().stream().filter(n -> n.nodeId().equals(result.nodeId())).findFirst().orElseThrow();
        return node.failPolicy() != FailPolicy.CONTINUE;
    }

    private static String failureCode(NodeResult result) {
        if (result.status() == Status.CANCELLED) return "OR-EXECUTION-CANCELLED";
        if (result.failure() != null && result.failure().startsWith("OR-ENGINE-FAILURE"))
            return "OR-ENGINE-FAILURE";
        return "OR-NODE-FAILED";
    }

    private static void skip(Stage stage, SkipReason reason, Map<String, NodeResult> prior,
                             List<NodeResult> results) {
        skipRemaining(stage, 0, reason, prior, results);
    }

    private static void skipRemaining(Stage stage, int from, Map<String, NodeResult> prior,
                                      List<NodeResult> results) {
        skipRemaining(stage, from, SkipReason.ABORTED, prior, results);
    }

    private static void skipRemaining(Stage stage, int from, SkipReason reason,
                                      Map<String, NodeResult> prior, List<NodeResult> results) {
        for (Node node : stage.nodes().subList(from, stage.nodes().size())) {
            NodeResult result = new NodeResult(stage.stageId(), node.nodeId(), node.type(), Status.SKIPPED,
                    false, Map.of(), List.of(), null, reason, 0);
            prior.put(node.nodeId(), result);
            results.add(result);
        }
    }

    private static boolean expired(long deadline) { return System.nanoTime() >= deadline; }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private record Pending(Future<NodeResult> future, long deadline) { }
}
