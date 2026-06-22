package io.openrule.core.spi;

import io.openrule.core.definition.NodeDefinition;

/** 节点编译产物容器（compiledArtifact 在 M1 通常为 null）。 */
public class CompiledNode {
    private final NodeDefinition definition;
    private final Object compiledArtifact;

    public CompiledNode(NodeDefinition definition, Object compiledArtifact) {
        this.definition = definition;
        this.compiledArtifact = compiledArtifact;
    }

    public NodeDefinition getDefinition() { return definition; }
    public Object getCompiledArtifact()   { return compiledArtifact; }
}
