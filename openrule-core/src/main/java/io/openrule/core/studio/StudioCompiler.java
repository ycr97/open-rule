package io.openrule.core.studio;

import io.openrule.core.studio.StudioModel.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;

/** Cross-field validation and deterministic order for schemaVersion 2 definitions. */
public final class StudioCompiler {
    public Flow compile(Flow flow) {
        if (flow == null || flow.schemaVersion() != 2 || flow.flowId() == null
                || flow.flowName() == null || flow.version() == null || flow.description() == null
                || flow.stages() == null || flow.stages().isEmpty())
            throw invalid("Flow needs stages");
        List<Stage> stages = flow.stages().stream().sorted(Comparator.comparingInt(Stage::order)).toList();
        Set<String> stageIds = new HashSet<>(), nodeIds = new HashSet<>();
        Set<Integer> stageOrders = new HashSet<>();
        Map<String, String> priorOutputs = new HashMap<>();
        Set<String> priorNodes = new HashSet<>();
        List<Stage> compiled = new ArrayList<>();
        for (Stage stage : stages) {
            if (stage.stageId() == null || !stageIds.add(stage.stageId()) || stage.order() < 1
                    || !stageOrders.add(stage.order()) || stage.mode() == null
                    || stage.timeoutMillis() < 1 || stage.nodes() == null || stage.nodes().isEmpty())
                throw invalid("Invalid stage identity/order: " + stage.stageId());
            checkCondition(stage.when(), priorNodes, priorOutputs, stage.stageId());
            List<Node> nodes = stage.nodes().stream().sorted(Comparator.comparingInt(Node::order)).toList();
            Set<Integer> orders = new HashSet<>();
            Set<String> stageOutputs = new HashSet<>();
            Map<String, String> pendingOutputs = new HashMap<>();
            Set<String> availableNodes = new HashSet<>(priorNodes);
            Map<String, String> availableOutputs = new HashMap<>(priorOutputs);
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (node.nodeId() == null || !nodeIds.add(node.nodeId()) || node.order() < 1
                        || !orders.add(node.order()) || node.configVersion() != 1
                        || node.timeoutMillis() < 1 || node.timeoutMillis() > stage.timeoutMillis())
                    throw invalid("Invalid node identity/order/timeout: " + node.nodeId());
                if ("openrule.terminal".equals(node.type())) {
                    if (stage.mode() != Mode.SERIAL || i != nodes.size() - 1 || node.failPolicy() != null
                            || !(node.config() instanceof Terminal terminal)
                            || blank(terminal.decisionCode()) || terminal.reasonCodes() == null
                            || terminal.reasonCodes().isEmpty() || terminal.reasonCodes().stream().anyMatch(StudioCompiler::blank))
                        throw invalid("Terminal must be last in a serial stage: " + node.nodeId());
                    if (terminal.scoreRef() != null && !availableOutputs.containsKey(terminal.scoreRef()))
                        throw invalid("Terminal scoreRef has no previous producer: " + terminal.scoreRef());
                } else {
                    if (node.failPolicy() == null) throw invalid("Missing failPolicy: " + node.nodeId());
                    List<Condition> conditions;
                    String output;
                    if ("openrule.operator".equals(node.type()) && node.config() instanceof Operator op) {
                        if (op.condition() == null) throw invalid("Operator condition is required");
                        conditions = List.of(op.condition());
                        output = op.outputKey();
                        if (blank(op.reasonCode())) throw invalid("Operator reasonCode is required");
                    } else if ("openrule.rule-set".equals(node.type()) && node.config() instanceof RuleSet rules) {
                        checkRules(rules.rules(), node.nodeId());
                        if (rules.matchPolicy() == null
                                || !Set.of("FIRST_MATCH", "ALL_MATCH").contains(rules.matchPolicy()))
                            throw invalid("Invalid matchPolicy: " + node.nodeId());
                        conditions = rules.rules().stream().map(Rule::condition).toList();
                        output = rules.outputKey();
                    } else if ("openrule.decision-table".equals(node.type()) && node.config() instanceof DecisionTable table) {
                        checkRules(table.rules(), node.nodeId());
                        if (!"FIRST".equals(table.hitPolicy())) throw invalid("Invalid hitPolicy: " + node.nodeId());
                        conditions = table.rules().stream().map(Rule::condition).toList();
                        output = table.outputKey();
                    } else if ("openrule.scorecard".equals(node.type()) && node.config() instanceof Scorecard scorecard) {
                        checkScorecard(scorecard, node.nodeId());
                        conditions = scorecard.characteristics().stream().flatMap(c -> c.bins().stream())
                                .map(Bin::condition).toList();
                        output = scorecard.outputKey();
                    } else if (Set.of("openrule.rule-set", "openrule.scorecard", "openrule.decision-table")
                            .contains(node.type()) && node.config() instanceof Deferred deferred) {
                        conditions = deferred.conditions();
                        if (conditions == null || conditions.isEmpty()) throw invalid("Node conditions are required");
                        output = deferred.outputKey();
                    } else throw invalid("Unknown or mismatched node type: " + node.type());
                    if (blank(output) || !output.matches("[a-zA-Z][a-zA-Z0-9_]*"))
                        throw invalid("Invalid output key: " + node.nodeId());
                    if (!stageOutputs.add(output) && stage.mode() == Mode.PARALLEL)
                        throw invalid("Parallel output conflict: " + output);
                    for (Condition condition : conditions)
                        checkCondition(condition, availableNodes, availableOutputs, node.nodeId());
                    if (stage.mode() == Mode.SERIAL) availableOutputs.put(output, node.nodeId());
                    else pendingOutputs.put(output, node.nodeId());
                }
                if (stage.mode() == Mode.SERIAL) availableNodes.add(node.nodeId());
            }
            priorNodes.addAll(nodes.stream().map(Node::nodeId).toList());
            availableOutputs.putAll(pendingOutputs);
            priorOutputs.putAll(availableOutputs);
            compiled.add(new Stage(stage.stageId(), stage.order(), stage.mode(), stage.when(),
                    stage.timeoutMillis(), List.copyOf(nodes)));
        }
        Stage last = compiled.getLast();
        if (last.mode() != Mode.SERIAL || last.when() != null || last.nodes().size() != 1
                || !"openrule.terminal".equals(last.nodes().getFirst().type()))
            throw invalid("Last stage must be an unconditional single Terminal");
        return new Flow(flow.schemaVersion(), flow.flowId(), flow.flowName(), flow.version(),
                flow.description(), List.copyOf(compiled));
    }

    private static void checkRules(List<Rule> rules, String nodeId) {
        if (rules == null || rules.isEmpty()) throw invalid("Rules are required: " + nodeId);
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null || blank(rule.id()) || !ids.add(rule.id())
                    || rule.condition() == null || blank(rule.reasonCode()))
                throw invalid("Invalid or duplicate rule: " + nodeId);
        }
    }

    private static void checkScorecard(Scorecard card, String nodeId) {
        if (card.characteristics() == null || card.characteristics().isEmpty())
            throw invalid("Characteristics are required: " + nodeId);
        Set<String> ids = new HashSet<>();
        for (Characteristic characteristic : card.characteristics()) {
            if (characteristic == null || blank(characteristic.id()) || !ids.add(characteristic.id())
                    || blank(characteristic.name()) || characteristic.bins() == null
                    || characteristic.bins().isEmpty())
                throw invalid("Invalid or duplicate characteristic: " + nodeId);
            Set<String> binIds = new HashSet<>();
            for (Bin bin : characteristic.bins()) {
                if (bin == null || blank(bin.id()) || !binIds.add(bin.id()) || bin.condition() == null
                        || bin.score() == null || blank(bin.reasonCode()))
                    throw invalid("Invalid or duplicate bin: " + characteristic.id());
            }
            checkNumericBins(characteristic);
        }
    }

    /** Proves coverage only for simple numeric comparisons of the same input. */
    private static void checkNumericBins(Characteristic characteristic) {
        List<Interval> intervals = new ArrayList<>();
        Ref ref = null;
        for (Bin bin : characteristic.bins()) {
            if (!(bin.condition() instanceof Compare compare)) return;
            if (ref == null) ref = compare.ref();
            else if (!ref.equals(compare.ref())) return;
            Interval interval;
            if (compare.op().equals("between") && compare.value() instanceof List<?> bounds
                    && bounds.size() == 2 && decimal(bounds.get(0)) != null && decimal(bounds.get(1)) != null)
                interval = new Interval(decimal(bounds.get(0)), true, decimal(bounds.get(1)), true);
            else {
                BigDecimal value = decimal(compare.value());
                if (value == null) return;
                interval = switch (compare.op()) {
                    case "lt" -> new Interval(null, false, value, false);
                    case "lte" -> new Interval(null, false, value, true);
                    case "gt" -> new Interval(value, false, null, false);
                    case "gte" -> new Interval(value, true, null, false);
                    case "eq" -> new Interval(value, true, value, true);
                    default -> null;
                };
            }
            if (interval == null) return;
            intervals.add(interval);
        }
        intervals.sort((a, b) -> a.low == null ? (b.low == null ? 0 : -1)
                : b.low == null ? 1 : a.low.compareTo(b.low));
        if (intervals.getFirst().low != null) throw invalid("Score bins have a provable gap: " + characteristic.id());
        for (int i = 1; i < intervals.size(); i++) {
            Interval previous = intervals.get(i - 1), next = intervals.get(i);
            if (previous.high == null) throw invalid("Score bins overlap: " + characteristic.id());
            if (next.low == null) throw invalid("Score bins overlap: " + characteristic.id());
            int relation = previous.high.compareTo(next.low);
            if (relation > 0 || relation == 0 && previous.highInclusive && next.lowInclusive)
                throw invalid("Score bins overlap: " + characteristic.id());
            if (relation < 0 || relation == 0 && !previous.highInclusive && !next.lowInclusive)
                throw invalid("Score bins have a provable gap: " + characteristic.id());
        }
        if (intervals.getLast().high != null)
            throw invalid("Score bins have a provable gap: " + characteristic.id());
    }

    private record Interval(BigDecimal low, boolean lowInclusive, BigDecimal high, boolean highInclusive) { }

    private static BigDecimal decimal(Object value) {
        return value instanceof Number number && !(value instanceof Double || value instanceof Float)
                ? new BigDecimal(number.toString()) : null;
    }

    private void checkCondition(Condition condition, Set<String> nodes,
                                Map<String, String> outputs, String owner) {
        if (condition == null) return;
        try { StudioConditions.validate(condition); }
        catch (IllegalArgumentException ex) { throw invalid(owner + ": " + ex.getMessage()); }
        StudioConditions.forEachRef(condition, ref -> {
            if (ref.source() == Source.NODE) {
                String nodeId = ref.pointer().split("/")[1];
                if (!nodes.contains(nodeId)) throw invalid(owner + " references unavailable node " + nodeId);
            }
            if (ref.source() == Source.VARIABLE && !ref.pointer().isEmpty()) {
                String key = ref.pointer().substring(1).split("/")[0].replace("~1", "/").replace("~0", "~");
                if (!outputs.containsKey(key)) throw invalid(owner + " references unavailable variable " + key);
            }
        });
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("OR-DEF-VALIDATION: " + message);
    }
}
