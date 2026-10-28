package io.openrule.jdbc.support;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class SupportUtilTest {

    @Test
    void sha256Hex_stableLowercase() {
        assertThat(ChecksumUtil.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_recursivelyMasksConfiguredKeys() {
        Map<String, Object> facts = Map.of(
                "mobile", "13800000000",
                "buyer", Map.of("idCard", "X", "level", "VIP"),
                "items", List.of(Map.of("password", "p", "qty", 2)));
        Object masked = Desensitizer.mask(facts, Set.of("mobile", "idCard", "password"));

        Map<String, Object> m = (Map<String, Object>) masked;
        assertThat(m.get("mobile")).isEqualTo("****");
        assertThat(((Map<String, Object>) m.get("buyer")).get("idCard")).isEqualTo("****");
        assertThat(((Map<String, Object>) m.get("buyer")).get("level")).isEqualTo("VIP");
        Object item0 = ((List<Object>) m.get("items")).get(0);
        assertThat(((Map<String, Object>) item0).get("password")).isEqualTo("****");
        assertThat(((Map<String, Object>) item0).get("qty")).isEqualTo(2);
    }
}
