package io.openrule.spring.model;

import io.openrule.core.result.FlowResult;

/** 编排结果：core FlowResult + 命中的流程版本（core 不持有版本，由此补齐）。 */
public record ExecutionOutcome(FlowResult result, int flowVersion) {
}
