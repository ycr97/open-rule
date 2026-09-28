package io.openrule.core.studio;

import io.openrule.core.studio.StudioModel.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict, short-circuit condition evaluation with RFC 6901 references. */
public final class StudioConditions {
    private StudioConditions() { }

    public static boolean evaluate(Condition condition, Map<String, Object> facts,
                                   Map<String, Object> variables, Map<String, NodeResult> nodes) {
        if (condition instanceof Group group) {
            return switch (group.kind()) {
                case "all" -> group.children().stream().allMatch(c -> evaluate(c, facts, variables, nodes));
                case "any" -> group.children().stream().anyMatch(c -> evaluate(c, facts, variables, nodes));
                case "not" -> !evaluate(group.children().getFirst(), facts, variables, nodes);
                default -> throw new ConditionFailure("Unknown condition group: " + group.kind());
            };
        }
        Ref ref = condition instanceof Compare c ? c.ref() : ((Presence) condition).ref();
        Lookup found = resolve(ref, facts, variables, nodes);
        if (condition instanceof Presence presence) {
            return switch (presence.kind()) {
                case "is-present" -> found.present();
                case "is-missing" -> !found.present();
                case "is-null" -> found.present() && found.value() == null;
                case "not-null" -> found.present() && found.value() != null;
                default -> throw new ConditionFailure("Unknown presence test: " + presence.kind());
            };
        }
        Compare compare = (Compare) condition;
        if (!found.present()) throw new ConditionFailure("Missing value: " + ref.pointer());
        Object left = found.value(), right = compare.value();
        return switch (compare.op()) {
            case "eq" -> equal(left, right);
            case "ne" -> !equal(left, right);
            case "gt" -> ordered(left, right) > 0;
            case "gte" -> ordered(left, right) >= 0;
            case "lt" -> ordered(left, right) < 0;
            case "lte" -> ordered(left, right) <= 0;
            case "between" -> {
                if (!(right instanceof List<?> values) || values.size() != 2)
                    throw new ConditionFailure("between requires two bounds");
                yield ordered(left, values.get(0)) >= 0 && ordered(left, values.get(1)) <= 0;
            }
            case "in", "not-in" -> {
                if (!(right instanceof List<?> values)) throw new ConditionFailure("in requires an array");
                boolean contains = values.stream().anyMatch(v -> equal(left, v));
                yield compare.op().equals("in") == contains;
            }
            case "contains", "not-contains" -> {
                boolean contains;
                if (left instanceof String s && right instanceof String t) contains = s.contains(t);
                else if (left instanceof List<?> values) contains = values.stream().anyMatch(v -> equal(v, right));
                else throw new ConditionFailure("contains requires a string or array");
                yield compare.op().equals("contains") == contains;
            }
            case "starts-with" -> text(left).startsWith(text(right));
            case "ends-with" -> text(left).endsWith(text(right));
            default -> throw new ConditionFailure("Unknown comparison: " + compare.op());
        };
    }

    private static Lookup resolve(Ref ref, Map<String, Object> facts,
                                  Map<String, Object> variables, Map<String, NodeResult> nodes) {
        if (ref.source() == Source.NODE) {
            String[] parts = ref.pointer().split("/", -1);
            NodeResult result = nodes.get(parts[1]);
            if (result == null) throw new ConditionFailure("Node result unavailable: " + parts[1]);
            return new Lookup(true, parts[2].equals("status") ? result.status().name() : result.hit());
        }
        Object current = ref.source() == Source.FACT ? facts : variables;
        if (ref.pointer().isEmpty()) return new Lookup(true, current);
        for (String token : ref.pointer().substring(1).split("/", -1)) {
            String key = token.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(key)) return new Lookup(false, null);
                current = map.get(key);
            } else if (current instanceof List<?> list && key.matches("0|[1-9][0-9]*")) {
                int index;
                try { index = Integer.parseInt(key); } catch (NumberFormatException ex) { return new Lookup(false, null); }
                if (index >= list.size()) return new Lookup(false, null);
                current = list.get(index);
            } else return new Lookup(false, null);
        }
        return new Lookup(true, current);
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return decimal(x).compareTo(decimal(y)) == 0;
        if (a instanceof Number || b instanceof Number) throw new ConditionFailure("Type mismatch in comparison");
        if (a != null && b != null && !a.getClass().equals(b.getClass()))
            throw new ConditionFailure("Type mismatch in comparison");
        return Objects.equals(a, b);
    }

    private static int ordered(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return decimal(x).compareTo(decimal(y));
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        throw new ConditionFailure("Type mismatch in ordered comparison");
    }

    private static String text(Object value) {
        if (value instanceof String text) return text;
        throw new ConditionFailure("String comparison requires strings");
    }

    private static BigDecimal decimal(Number number) {
        if (number instanceof Double || number instanceof Float)
            throw new ConditionFailure("Binary floating point is not a supported Studio value");
        return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    }

    static void validate(Condition condition) {
        if (condition == null) throw new IllegalArgumentException("condition is required");
        if (condition instanceof Group group) {
            if (!Set.of("all", "any", "not").contains(group.kind()) || group.children() == null
                    || group.children().isEmpty() || group.kind().equals("not") && group.children().size() != 1)
                throw new IllegalArgumentException("Invalid condition group");
            group.children().forEach(StudioConditions::validate);
            return;
        }
        Ref ref = condition instanceof Compare c ? c.ref() : ((Presence) condition).ref();
        if (ref == null || ref.source() == null || ref.pointer() == null
                || !ref.pointer().matches("(?:|/(?:[^~/]|~[01])*(?:/(?:[^~/]|~[01])*)*)"))
            throw new IllegalArgumentException("Invalid condition reference");
        if (ref.source() == Source.NODE && !ref.pointer().matches("/[^/]+/(?:status|hit)"))
            throw new IllegalArgumentException("Invalid NODE reference");
        if (condition instanceof Presence p) {
            if (!Set.of("is-present", "is-missing", "is-null", "not-null").contains(p.kind()))
                throw new IllegalArgumentException("Invalid presence operation");
        } else {
            Compare c = (Compare) condition;
            if (!Set.of("eq", "ne", "gt", "gte", "lt", "lte", "between", "in", "not-in",
                    "contains", "not-contains", "starts-with", "ends-with").contains(c.op()))
                throw new IllegalArgumentException("Invalid comparison operation");
            if ((c.op().equals("in") || c.op().equals("not-in")) && !(c.value() instanceof List<?>))
                throw new IllegalArgumentException("Membership requires array");
            if (c.op().equals("between") && (!(c.value() instanceof List<?> list) || list.size() != 2
                    || !(list.get(0) instanceof Number) || !(list.get(1) instanceof Number)
                    || ordered(list.get(0), list.get(1)) > 0))
                throw new IllegalArgumentException("Invalid between bounds");
        }
    }

    static void forEachRef(Condition condition, java.util.function.Consumer<Ref> consumer) {
        if (condition instanceof Group group) group.children().forEach(c -> forEachRef(c, consumer));
        else consumer.accept(condition instanceof Compare c ? c.ref() : ((Presence) condition).ref());
    }

    private record Lookup(boolean present, Object value) { }
    public static final class ConditionFailure extends RuntimeException {
        public ConditionFailure(String message) { super(message); }
    }
}
