package io.openrule.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.core.spi.DecisionAggregator;
import io.openrule.core.spi.NodeExecutor;
import io.openrule.spring.loader.FlowCompiler;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.port.ExecutionLogger;
import io.openrule.spring.port.FlowChangeNotifier;
import io.openrule.spring.port.FlowDefinitionRepository;
import io.openrule.spring.service.OpenRuleService;
import io.openrule.spring.standalone.InMemoryExecutionLogger;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import io.openrule.spring.standalone.LocalFlowChangeNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** OpenRule Spring 装配：core 引擎 + 端口默认实现（standalone，可被覆盖）。 */
@AutoConfiguration
@EnableConfigurationProperties(OpenRuleProperties.class)
public class OpenRuleAutoConfiguration {

    @Bean("openRuleParallelPool")
    @ConditionalOnMissingBean(name = "openRuleParallelPool")
    public Executor openRuleParallelPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("openRuleTimeoutPool")
    @ConditionalOnMissingBean(name = "openRuleTimeoutPool")
    public ExecutorService openRuleTimeoutPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public OperatorNodeExecutor operatorNodeExecutor() {
        return new OperatorNodeExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public PriorityAggregator priorityAggregator() {
        return new PriorityAggregator();
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeExecutorRegistry nodeExecutorRegistry(List<NodeExecutor> executors) {
        return new NodeExecutorRegistry(executors);
    }

    @Bean
    @ConditionalOnMissingBean
    public Map<AggregatePolicy, DecisionAggregator> aggregators(List<DecisionAggregator> list) {
        return list.stream().collect(Collectors.toMap(DecisionAggregator::supportPolicy, a -> a));
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeRunner nodeRunner(NodeExecutorRegistry registry, ExecutorService openRuleTimeoutPool) {
        return new NodeRunner(registry, openRuleTimeoutPool);
    }

    @Bean
    @ConditionalOnMissingBean
    public SerialStageExecutor serialStageExecutor(NodeRunner nodeRunner) {
        return new SerialStageExecutor(nodeRunner);
    }

    @Bean
    @ConditionalOnMissingBean
    public ParallelStageExecutor parallelStageExecutor(NodeRunner nodeRunner, Executor openRuleParallelPool) {
        return new ParallelStageExecutor(nodeRunner, openRuleParallelPool);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowExecutor flowExecutor(SerialStageExecutor serial, ParallelStageExecutor parallel,
                                     Map<AggregatePolicy, DecisionAggregator> aggregators) {
        return new FlowExecutor(serial, parallel, aggregators);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowCompiler flowCompiler(NodeExecutorRegistry registry) {
        return new FlowCompiler(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowDefinitionRepository flowDefinitionRepository() {
        return new InMemoryFlowDefinitionRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionLogger executionLogger() {
        return new InMemoryExecutionLogger();
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowLoader flowLoader(FlowDefinitionRepository repository, FlowCompiler compiler,
                                 OpenRuleProperties props) {
        return new FlowLoader(repository, compiler,
                props.getFlowCache().getMaximumSize(),
                props.getFlowCache().getExpireAfterAccess());
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowChangeNotifier flowChangeNotifier(FlowLoader flowLoader) {
        return new LocalFlowChangeNotifier(flowLoader);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowDefinitionJsonCodec flowDefinitionJsonCodec(ObjectProvider<ObjectMapper> objectMapper) {
        // openrule-spring 不依赖 spring-web，无 web 场景下 JacksonAutoConfiguration 不产出 ObjectMapper，
        // 故此处缺省自备一个纯 ObjectMapper；web 应用中则复用容器内已有的。
        return new FlowDefinitionJsonCodec(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenRuleService openRuleService(FlowLoader flowLoader, FlowCompiler flowCompiler,
                                           FlowExecutor flowExecutor, FlowDefinitionRepository repository,
                                           ExecutionLogger executionLogger, FlowChangeNotifier notifier) {
        return new OpenRuleService(flowLoader, flowCompiler, flowExecutor, repository,
                executionLogger, notifier);
    }
}
