package io.openrule.core.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionTest {

    @Test
    void riskOrder_isRejectGtReviewGtLimitGtPass() {
        assertThat(Decision.REJECT.riskierThan(Decision.REVIEW)).isTrue();
        assertThat(Decision.REVIEW.riskierThan(Decision.LIMIT)).isTrue();
        assertThat(Decision.LIMIT.riskierThan(Decision.PASS)).isTrue();
    }

    @Test
    void riskierThan_isStrict_notReflexive() {
        assertThat(Decision.REJECT.riskierThan(Decision.REJECT)).isFalse();
        assertThat(Decision.PASS.riskierThan(Decision.REJECT)).isFalse();
    }
}
