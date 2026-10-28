package io.openrule.core.studio;

import java.util.List;

/** Created only by StudioCompiler; contains a validated, deeply immutable flow. */
public final class StudioCompiledPlan {
    private final StudioModel.Flow flow;
    private final List<RequiredFact> requiredFacts;

    StudioCompiledPlan(StudioModel.Flow flow, List<RequiredFact> requiredFacts) {
        this.flow = flow;
        this.requiredFacts = List.copyOf(requiredFacts);
    }
    public StudioModel.Flow flow() { return flow; }
    public List<RequiredFact> requiredFacts() { return requiredFacts; }
}
