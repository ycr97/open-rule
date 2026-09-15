package io.openrule.spring.loader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.openrule.core.compiler.FlowCompiler;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.spring.port.FlowDefinitionRepository;

import java.time.Duration;

/** 加载并缓存 CompiledFlow（key=flowId:v{version}，C11）。失效清该 flow 全部版本。 */
public class FlowLoader {

    private final FlowDefinitionRepository repository;
    private final FlowCompiler compiler;
    private final Cache<String, CompiledFlow> cache;

    public FlowLoader(FlowDefinitionRepository repository, FlowCompiler compiler) {
        this(repository, compiler, 500, Duration.ofHours(2));
    }

    public FlowLoader(FlowDefinitionRepository repository, FlowCompiler compiler,
                      long maxSize, Duration expireAfterAccess) {
        this.repository = repository;
        this.compiler = compiler;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize).expireAfterAccess(expireAfterAccess)
                .recordStats().build();
    }

    public CompiledFlow loadActive(String flowId) {
        FlowDefinition def = repository.findActiveByFlowId(flowId)
                .orElseThrow(() -> new RuleEngineException("Flow not found or disabled: " + flowId));
        return cache.get(flowId + ":v" + def.getVersion(), k -> compiler.compile(def));
    }

    public void invalidate(String flowId) {
        cache.asMap().keySet().removeIf(k -> k.startsWith(flowId + ":"));
    }
}
