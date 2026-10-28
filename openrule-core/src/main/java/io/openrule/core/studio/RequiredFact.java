package io.openrule.core.studio;

import java.util.List;

/** Static FACT references. These are diagnostics, never global execution preconditions. */
public record RequiredFact(String path, List<String> expectedTypes, boolean typeConflict, List<Use> uses) {
    public RequiredFact { expectedTypes = List.copyOf(expectedTypes); uses = List.copyOf(uses); }
    public record Use(String pointer, String stageId, String nodeId, String itemId,
                      String access, List<String> expectedTypes) {
        public Use { expectedTypes = List.copyOf(expectedTypes); }
    }
}
