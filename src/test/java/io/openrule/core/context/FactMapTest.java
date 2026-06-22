package io.openrule.core.context;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FactMapTest {

    @Test
    void get_returnsTopLevelValue() {
        FactMap fm = new FactMap(Map.of("amount", 100));
        assertThat(fm.get("amount")).isEqualTo(100);
    }

    @Test
    void getByPath_traversesNestedMaps() {
        Map<String, Object> order = new HashMap<>();
        order.put("amount", 12800);
        FactMap fm = new FactMap(Map.of("order", order));
        assertThat(fm.getByPath("order.amount")).isEqualTo(12800);
    }

    @Test
    void getByPath_returnsNullForMissingOrNonMap() {
        FactMap fm = new FactMap(Map.of("order", Map.of("amount", 1)));
        assertThat(fm.getByPath("order.missing")).isNull();
        assertThat(fm.getByPath("order.amount.deep")).isNull();
        assertThat(fm.getByPath("nope")).isNull();
    }

    @Test
    void isImmutable_mutatingSourceDoesNotLeak() {
        Map<String, Object> src = new HashMap<>();
        src.put("a", 1);
        FactMap fm = new FactMap(src);
        src.put("a", 999);
        src.put("b", 2);
        assertThat(fm.get("a")).isEqualTo(1);
        assertThat(fm.get("b")).isNull();
    }

    @Test
    void toleratesNullValues() {
        Map<String, Object> src = new HashMap<>();
        src.put("nullable", null);
        FactMap fm = new FactMap(src);
        assertThat(fm.get("nullable")).isNull();
    }
}
