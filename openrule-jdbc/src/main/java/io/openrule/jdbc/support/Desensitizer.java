package io.openrule.jdbc.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** facts 递归脱敏：命中 key 的值替换为 "****"，嵌套 Map/List 逐层处理。 */
public final class Desensitizer {

    private static final String MASK = "****";

    private Desensitizer() {}

    public static Object mask(Object value, Set<String> keys) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, keys.contains(key) ? MASK : mask(v, keys));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(e -> mask(e, keys)).toList();
        }
        return value;
    }
}
