package io.openrule.spring.service;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.spring.loader.FlowCompiler;
import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.model.ExecuteCommand;
import io.openrule.spring.standalone.InMemoryExecutionLogger;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import io.openrule.spring.standalone.LocalFlowChangeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenRuleServiceVersioningTest {

    private ExecutorService pool;
    private OpenRuleService service;
    private FlowLoader loader;

    private FlowDefinition flow(int threshold) {
        OperatorDef op = new OperatorDef();
        op.setLeftFact("fact.order.amount"); op.setOperator("GT"); op.setRightValue(threshold);
        NodeDefinition node = NodeDefinition.builder().nodeId("AMT").nodeName("AMT").nodeType(NodeType.OPERATOR)
                .order(10).operatorDef(op).decisionOnHit(Decision.REJECT).stopOnHit(true)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(500).build();
        StageDefinition s = StageDefinition.builder().stageId("s1").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true).nodes(List.of(node)).build();
        return FlowDefinition.builder().flowId("f").flowName("n")
                .aggregatePolicy(AggregatePolicy.PRIORITY).stages(List.of(s)).build();
    }

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(new OperatorNodeExecutor()));
        NodeRunner runner = new NodeRunner(registry, pool);
        FlowExecutor exec = new FlowExecutor(new SerialStageExecutor(runner),
                new ParallelStageExecutor(runner, pool), Map.of(AggregatePolicy.PRIORITY, new PriorityAggregator()));
        FlowCompiler compiler = new FlowCompiler(registry);
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        loader = new FlowLoader(repo, compiler);
        service = new OpenRuleService(loader, compiler, exec, repo, new InMemoryExecutionLogger(),
                new LocalFlowChangeNotifier(loader));
        service.registerFlow(flow(50000));   // v1: 阈值 50000
        service.registerFlow(flow(100000));  // v2: 阈值 100000（active）
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    @Test
    void listVersions_returnsBothAscending() {
        assertThat(service.listVersions("f")).extracting(FlowDefinition::getVersion).containsExactly(1, 2);
    }

    @Test
    void enableVersion_switchesActivePointer_observableInExecute() {
        // 当前 active=v2(阈值10w)：amount=60000 不命中 → PASS
        assertThat(service.execute(new ExecuteCommand("f", null, "B", false,
                Map.of("order", Map.of("amount", 60000)))).result().getDecision()).isEqualTo(Decision.PASS);

        FlowDefinition active = service.enableVersion("f", 1);  // 切回 v1(阈值5w)
        assertThat(active.getVersion()).isEqualTo(1);

        // active=v1：amount=60000 命中 → REJECT（指针切换被 execute 观察到）
        assertThat(service.execute(new ExecuteCommand("f", null, "B", false,
                Map.of("order", Map.of("amount", 60000)))).result().getDecision()).isEqualTo(Decision.REJECT);
    }

    @Test
    void enableVersion_missing_throws() {
        assertThatThrownBy(() -> service.enableVersion("f", 99))
                .isInstanceOf(RuleEngineException.class).hasMessageContaining("not found");
    }
}
