package io.openrule.core;

import io.openrule.core.aggregate.FirstTerminalAggregator;
import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.compiler.FlowCompiler;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.core.spi.DecisionAggregator;
import io.openrule.core.spi.NodeExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 纯 Java Facade：Definition → validate → compile → execute。 */
public final class OpenRuleEngine implements AutoCloseable {

    private final FlowCompiler compiler;
    private final FlowExecutor executor;
    private final ExecutorService executorService;
    private final boolean ownsExecutorService;

    private OpenRuleEngine(FlowCompiler compiler, FlowExecutor executor,
                           ExecutorService executorService, boolean ownsExecutorService) {
        this.compiler = compiler;
        this.executor = executor;
        this.executorService = executorService;
        this.ownsExecutorService = ownsExecutorService;
    }

    public static OpenRuleEngine create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public void validate(FlowDefinition definition) {
        compiler.validate(definition);
    }

    public CompiledFlow compile(FlowDefinition definition) {
        return compiler.compile(definition);
    }

    public FlowResult execute(FlowDefinition definition, DecisionContext context) {
        return execute(compile(definition), context);
    }

    public FlowResult execute(CompiledFlow flow, DecisionContext context) {
        return executor.execute(context, flow);
    }

    @Override
    public void close() {
        if (ownsExecutorService) {
            executorService.shutdownNow();
        }
    }

    public static final class Builder {
        private final List<NodeExecutor> nodeExecutors = new ArrayList<>();
        private final List<DecisionAggregator> aggregators = new ArrayList<>();
        private ExecutorService executorService;

        private Builder() {
            nodeExecutors.add(new OperatorNodeExecutor());
            aggregators.add(new PriorityAggregator());
            aggregators.add(new FirstTerminalAggregator());
        }

        public Builder addNodeExecutor(NodeExecutor nodeExecutor) {
            nodeExecutors.add(Objects.requireNonNull(nodeExecutor, "nodeExecutor"));
            return this;
        }

        public Builder addDecisionAggregator(DecisionAggregator aggregator) {
            aggregators.add(Objects.requireNonNull(aggregator, "aggregator"));
            return this;
        }

        /** 注入的线程池由调用方管理；未注入时 Engine 自建并在 close 时关闭。 */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = Objects.requireNonNull(executorService, "executorService");
            return this;
        }

        public OpenRuleEngine build() {
            NodeExecutorRegistry registry = new NodeExecutorRegistry(List.copyOf(nodeExecutors));
            Map<AggregatePolicy, DecisionAggregator> aggregatorMap = new LinkedHashMap<>();
            for (DecisionAggregator aggregator : aggregators) {
                DecisionAggregator previous = aggregatorMap.put(
                        aggregator.supportPolicy(), aggregator);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate aggregator for " + aggregator.supportPolicy());
                }
            }
            boolean ownsPool = executorService == null;
            ExecutorService pool = ownsPool
                    ? Executors.newVirtualThreadPerTaskExecutor() : executorService;
            FlowCompiler compiler = new FlowCompiler(registry);
            NodeRunner runner = new NodeRunner(pool);
            FlowExecutor flowExecutor = new FlowExecutor(
                    new SerialStageExecutor(runner),
                    new ParallelStageExecutor(runner, pool),
                    Map.copyOf(aggregatorMap));
            return new OpenRuleEngine(compiler, flowExecutor, pool, ownsPool);
        }
    }
}
