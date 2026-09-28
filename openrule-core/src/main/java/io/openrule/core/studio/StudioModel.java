package io.openrule.core.studio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Studio schemaVersion 2's Core-side, infrastructure-free execution model. */
public final class StudioModel {
    private StudioModel() { }

    public record Flow(int schemaVersion, String flowId, String flowName, String version,
                       String description, List<Stage> stages) { }
    public record Stage(String stageId, int order, Mode mode, Condition when,
                        long timeoutMillis, List<Node> nodes) { }
    public record Node(String nodeId, int order, String type, int configVersion,
                       long timeoutMillis, FailPolicy failPolicy, Config config) { }
    public enum Mode { SERIAL, PARALLEL }
    public enum FailPolicy { ABORT, CONTINUE }
    public enum Status { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, SKIPPED }
    public enum StageStatus { SUCCEEDED, FAILED, SKIPPED }
    public enum SkipReason { WHEN_FALSE, TERMINATED, ABORTED }
    public enum Source { FACT, VARIABLE, NODE }
    public record Ref(Source source, String pointer) { }

    public sealed interface Condition permits Group, Compare, Presence { }
    public record Group(String kind, List<Condition> children) implements Condition { }
    public record Compare(Ref ref, String op, Object value) implements Condition { }
    public record Presence(String kind, Ref ref) implements Condition { }

    public sealed interface Config permits Operator, Terminal, Deferred, RuleSet, Scorecard, DecisionTable { }
    public record Operator(Condition condition, String outputKey, String reasonCode) implements Config { }
    public record Terminal(String decisionCode, List<String> reasonCodes, String scoreRef) implements Config { }
    /** Retained for B1 construction tests; JSON binding uses the typed configurations. */
    public record Deferred(String outputKey, List<Condition> conditions) implements Config { }
    public record Rule(String id, Condition condition, Object value, String reasonCode) { }
    public record RuleSet(String outputKey, String matchPolicy, List<Rule> rules) implements Config { }
    public record Bin(String id, Condition condition, BigDecimal score, String reasonCode) { }
    public record Characteristic(String id, String name, List<Bin> bins) { }
    public record Scorecard(String outputKey, List<Characteristic> characteristics) implements Config { }
    public record DecisionTable(String outputKey, String hitPolicy, List<Rule> rules) implements Config { }

    public sealed interface Detail permits RuleDetail, ScoreDetail { }
    public record RuleDetail(String kind, String ruleId, String reasonCode, Object value) implements Detail { }
    public record ScoreDetail(String kind, String characteristicId, String binId,
                              String score, String reasonCode) implements Detail { }

    public record NodeResult(String stageId, String nodeId, String type, Status status,
                             boolean hit, Map<String, Object> outputs, List<String> reasonCodes,
                             List<Detail> details, String failure, SkipReason skipReason, long elapsedMillis) {
        public NodeResult(String stageId, String nodeId, String type, Status status,
                          boolean hit, Map<String, Object> outputs, List<String> reasonCodes,
                          String failure, SkipReason skipReason, long elapsedMillis) {
            this(stageId, nodeId, type, status, hit, outputs, reasonCodes, List.of(),
                    failure, skipReason, elapsedMillis);
        }
    }
    public record Decision(String decisionCode, List<String> reasonCodes, BigDecimal score,
                           String terminalNodeId, Map<String, Object> outputs) { }
    public record StageResult(String stageId, StageStatus status, SkipReason skipReason) { }
    public record Result(String status, Decision decision, List<StageResult> stageResults,
                         List<NodeResult> nodeResults, Map<String, Object> variables,
                         String errorCode, long elapsedMillis) { }
}
