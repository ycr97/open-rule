package io.openrule.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SanityTest {
    @Test
    void java21_and_toolchain_works() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
