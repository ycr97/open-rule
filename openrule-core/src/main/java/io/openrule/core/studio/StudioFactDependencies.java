package io.openrule.core.studio;

import io.openrule.core.studio.StudioModel.*;
import java.util.*;

final class StudioFactDependencies {
    private StudioFactDependencies() { }
    static List<RequiredFact> collect(Flow flow) {
        Map<String, List<RequiredFact.Use>> paths = new TreeMap<>(StudioFactDependencies::comparePaths);
        for (int s = 0; s < flow.stages().size(); s++) {
            Stage stage = flow.stages().get(s);
            String base = "/stages/" + s;
            visit(stage.when(), base + "/when", stage.stageId(), null, null, paths);
            for (int n = 0; n < stage.nodes().size(); n++) {
                Node node = stage.nodes().get(n);
                String config = base + "/nodes/" + n + "/config";
                if (node.config() instanceof Operator op)
                    visit(op.condition(), config + "/condition", stage.stageId(), node.nodeId(), null, paths);
                else if (node.config() instanceof RuleSet rs)
                    rules(rs.rules(), config, stage.stageId(), node.nodeId(), paths);
                else if (node.config() instanceof DecisionTable dt)
                    rules(dt.rules(), config, stage.stageId(), node.nodeId(), paths);
                else if (node.config() instanceof Scorecard sc) {
                    for (int c = 0; c < sc.characteristics().size(); c++) {
                        var bins = sc.characteristics().get(c).bins();
                        for (int b = 0; b < bins.size(); b++)
                            visit(bins.get(b).condition(), config + "/characteristics/" + c + "/bins/" + b
                                    + "/condition", stage.stageId(), node.nodeId(), bins.get(b).id(), paths);
                    }
                } else if (node.config() instanceof Deferred d) {
                    for (int c = 0; c < d.conditions().size(); c++)
                        visit(d.conditions().get(c), config + "/conditions/" + c,
                                stage.stageId(), node.nodeId(), null, paths);
                }
            }
        }
        List<RequiredFact> result = new ArrayList<>();
        paths.forEach((path, uses) -> {
            Set<String> union = new TreeSet<>();
            boolean conflict = false;
            for (int i = 0; i < uses.size(); i++) {
                var types = uses.get(i).expectedTypes();
                union.addAll(types);
                if (!types.isEmpty()) for (int j = 0; j < i; j++) {
                    var other = uses.get(j).expectedTypes();
                    if (!other.isEmpty() && Collections.disjoint(types, other)) conflict = true;
                }
            }
            result.add(new RequiredFact(path, List.copyOf(union), conflict, uses));
        });
        return List.copyOf(result);
    }
    static int comparePaths(String a, String b) {
        var x = a.codePoints().iterator(); var y = b.codePoints().iterator();
        while (x.hasNext() && y.hasNext()) {
            int order = Integer.compare(x.nextInt(), y.nextInt());
            if (order != 0) return order;
        }
        return Boolean.compare(x.hasNext(), y.hasNext());
    }
    private static void rules(List<Rule> rules, String base, String stage, String node,
                              Map<String, List<RequiredFact.Use>> paths) {
        for (int i = 0; i < rules.size(); i++)
            visit(rules.get(i).condition(), base + "/rules/" + i + "/condition",
                    stage, node, rules.get(i).id(), paths);
    }
    private static void visit(Condition condition, String pointer, String stage, String node, String item,
                              Map<String, List<RequiredFact.Use>> paths) {
        if (condition == null) return;
        if (condition instanceof Group group) {
            for (int i = 0; i < group.children().size(); i++)
                visit(group.children().get(i), pointer + "/children/" + i, stage, node, item, paths);
            return;
        }
        Ref ref = condition instanceof Compare c ? c.ref() : ((Presence) condition).ref();
        if (ref.source() != Source.FACT) return;
        List<String> types = condition instanceof Compare c ? hints(c) : List.of();
        paths.computeIfAbsent(ref.pointer(), ignored -> new ArrayList<>()).add(new RequiredFact.Use(
                pointer + "/ref/pointer", stage, node, item,
                condition instanceof Presence ? "PRESENCE" : "VALUE", types));
    }
    private static List<String> hints(Compare c) {
        String type = scalar(c.value());
        return switch (c.op()) {
            case "eq", "ne" -> type == null ? List.of() : List.of(type);
            case "gt", "gte", "lt", "lte" -> "number".equals(type) || "string".equals(type) ? List.of(type) : List.of();
            case "between" -> List.of("number");
            case "starts-with", "ends-with" -> List.of("string");
            case "contains", "not-contains" -> "string".equals(type) ? List.of("array", "string") : List.of("array");
            case "in", "not-in" -> {
                List<?> values = (List<?>) c.value();
                String first = values.isEmpty() ? null : scalar(values.getFirst());
                yield first != null && values.stream().allMatch(v -> first.equals(scalar(v))) ? List.of(first) : List.of();
            }
            default -> List.of();
        };
    }
    private static String scalar(Object v) {
        return v instanceof Number ? "number" : v instanceof String ? "string" : v instanceof Boolean ? "boolean" : null;
    }
    static Flow freeze(Flow f) {
        return new Flow(f.schemaVersion(), f.flowId(), f.flowName(), f.version(), f.description(),
                f.stages().stream().map(s -> new Stage(s.stageId(), s.order(), s.mode(), condition(s.when()),
                        s.timeoutMillis(), s.nodes().stream().map(n -> new Node(n.nodeId(), n.order(), n.type(),
                                n.configVersion(), n.timeoutMillis(), n.failPolicy(), config(n.config()))).toList())).toList());
    }
    private static Condition condition(Condition c) {
        if (c instanceof Group g) return new Group(g.kind(), g.children().stream().map(StudioFactDependencies::condition).toList());
        if (c instanceof Compare v) return new Compare(v.ref(), v.op(), value(v.value()));
        return c;
    }
    private static Object value(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof java.math.BigDecimal
                || v instanceof java.math.BigInteger || v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) return v;
        if (v instanceof List<?> list) {
            List<Object> result = new ArrayList<>(); list.forEach(item -> result.add(value(item)));
            return Collections.unmodifiableList(result);
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("JSON keys must be strings");
                result.put(text, value(item));
            });
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Only immutable exact JSON values are supported");
    }
    private static List<Rule> freezeRules(List<Rule> rules) {
        return rules.stream().map(r -> new Rule(r.id(), condition(r.condition()), value(r.value()), r.reasonCode())).toList();
    }
    private static Config config(Config c) {
        if (c instanceof Operator o) return new Operator(condition(o.condition()), o.outputKey(), o.reasonCode());
        if (c instanceof RuleSet r) return new RuleSet(r.outputKey(), r.matchPolicy(), freezeRules(r.rules()));
        if (c instanceof DecisionTable d) return new DecisionTable(d.outputKey(), d.hitPolicy(), freezeRules(d.rules()));
        if (c instanceof Scorecard s) return new Scorecard(s.outputKey(), s.characteristics().stream().map(ch ->
                new Characteristic(ch.id(), ch.name(), ch.bins().stream().map(b ->
                        new Bin(b.id(), condition(b.condition()), b.score(), b.reasonCode())).toList())).toList());
        if (c instanceof Terminal t) return new Terminal(t.decisionCode(), List.copyOf(t.reasonCodes()), t.scoreRef());
        Deferred d = (Deferred) c;
        return new Deferred(d.outputKey(), d.conditions().stream().map(StudioFactDependencies::condition).toList());
    }
}
