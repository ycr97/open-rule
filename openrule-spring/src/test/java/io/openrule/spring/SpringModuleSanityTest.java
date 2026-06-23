package io.openrule.spring;

import io.openrule.spring.model.ExecuteCommand;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SpringModuleSanityTest {
    @Test
    void commandCarriesFields() {
        ExecuteCommand c = new ExecuteCommand("f", "R1", "B1", true, Map.of("k", 1));
        assertThat(c.flowId()).isEqualTo("f");
        assertThat(c.debug()).isTrue();
        assertThat(c.facts()).containsEntry("k", 1);
    }
}
