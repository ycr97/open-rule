package io.openrule.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部输入事实的不可变封装（防御性拷贝，C7）。
 * 顶层一层防修改；支持 "order.amount" 点路径逐层取值。
 * 用 HashMap+unmodifiable 而非 Map.copyOf：容忍 null 值。
 */
public class FactMap {

    private final Map<String, Object> data;

    public FactMap(Map<String, Object> source) {
        this.data = Collections.unmodifiableMap(
                new HashMap<>(source == null ? Map.of() : source));
    }

    public Object get(String key) {
        return data.get(key);
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
}
