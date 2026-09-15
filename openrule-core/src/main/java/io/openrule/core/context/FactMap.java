package io.openrule.core.context;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部输入事实的深层不可变快照（C7）；支持 "order.amount" 点路径逐层取值。
 * Map/List/Set/Collection 递归复制，数组规范化为不可变 List，并容忍 null 值。
 */
public class FactMap {

    private final Map<String, Object> data;

    public FactMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        }
        this.data = Collections.unmodifiableMap(copy);
    }

    public Object get(String key) {
        return data.get(key);
    }

    /** 返回不可变事实视图（审计快照用）。 */
    public Map<String, Object> asMap() {
        return data;
    }

    public Object getByPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = data.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            source.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            source.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> source) {
            Set<Object> copy = new LinkedHashSet<>();
            source.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            source.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(immutableValue(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
