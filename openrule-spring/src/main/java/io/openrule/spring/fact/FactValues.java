package io.openrule.spring.fact;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** JSON-only deep copies, exact numbers and RFC 6901 lookup/write boundaries. */
final class FactValues {
    private FactValues() { }
    static Object freeze(Object value) {
        return freeze(value, 0);
    }
    private static Object freeze(Object value, int depth) {
        if (depth > 1000) throw new IllegalArgumentException("JSON nesting exceeds 1000");
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            value = BigInteger.valueOf(((Number) value).longValue());
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            BigDecimal decimal = value instanceof BigDecimal d ? d : new BigDecimal((BigInteger) value);
            BigDecimal stripped = decimal.stripTrailingZeros();
            if (stripped.precision() > 128 || Math.abs((long) decimal.precision() - decimal.scale() - 1) > 1128)
                throw new IllegalArgumentException("Number exceeds Studio precision limits");
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys must be strings");
                copy.put(key, freeze(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(freeze(item, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Only exact JSON values are supported");
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> value) {
        return (Map<String, Object>) freeze(Objects.requireNonNull(value));
    }
    static int comparePaths(String a, String b) {
        return Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());
    }
    static List<String> tokens(String pointer) {
        if (pointer == null || !pointer.matches("(?:|/(?:[^~/]|~[01])*(?:/(?:[^~/]|~[01])*)*)"))
            throw new IllegalArgumentException("Invalid JSON Pointer");
        if (pointer.isEmpty()) return List.of();
        return Arrays.stream(pointer.substring(1).split("/", -1))
                .map(s -> s.replace("~1", "/").replace("~0", "~")).toList();
    }
    static void writePath(String pointer) {
        List<String> tokens = tokens(pointer);
        if (tokens.isEmpty() || tokens.stream().anyMatch(s -> s.isEmpty() || s.matches("[0-9]+")
                || Set.of("__proto__", "constructor", "prototype").contains(s)))
            throw new IllegalArgumentException("Invalid object leaf path");
    }
    record Lookup(boolean present, Object value) { }
    static Lookup lookup(Object root, String pointer) {
        Object current = root;
        for (String key : tokens(pointer)) {
            if (current instanceof Map<?, ?> map && map.containsKey(key)) current = map.get(key);
            else if (current instanceof List<?> list && key.matches("0|[1-9][0-9]*")) {
                try {
                    int i = Integer.parseInt(key);
                    if (i >= list.size()) return new Lookup(false, null);
                    current = list.get(i);
                } catch (NumberFormatException ex) { return new Lookup(false, null); }
            } else return new Lookup(false, null);
        }
        return new Lookup(true, current);
    }
    static boolean blocked(Map<String, Object> facts, String path) {
        Object current = facts;
        List<String> keys = tokens(path);
        for (int i = 0; i < keys.size() - 1; i++) {
            if (!(current instanceof Map<?, ?> map)) return true;
            if (!map.containsKey(keys.get(i))) return false;
            current = map.get(keys.get(i));
        }
        return !(current instanceof Map<?, ?>);
    }
    static Map<String, Object> insert(Map<String, Object> facts, String path, Object value) {
        return insert(facts, tokens(path), 0, value);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> insert(Map<String, Object> facts, List<String> keys, int index, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(facts);
        String key = keys.get(index);
        if (index == keys.size() - 1) {
            if (!copy.containsKey(key)) copy.put(key, freeze(value));
        } else {
            Object parent = copy.get(key);
            if (copy.containsKey(key) && !(parent instanceof Map<?, ?>)) return facts;
            copy.put(key, insert(parent == null ? Map.of() : (Map<String, Object>) parent, keys, index + 1, value));
        }
        return Collections.unmodifiableMap(copy);
    }
}
