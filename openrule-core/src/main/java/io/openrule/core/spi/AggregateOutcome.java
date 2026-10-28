package io.openrule.core.spi;

import io.openrule.core.enums.Decision;
import java.util.List;

public record AggregateOutcome(Decision decision, String reason,
                               int totalScore, List<String> hitNodes) {}
