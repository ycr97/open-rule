package io.openrule.spring.service;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.compiler.FlowCompiler;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.model.ExecuteCommand;
import io.openrule.spring.model.ExecutionOutcome;
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

class OpenRuleServiceTest {

    private ExecutorService pool;
    private OpenRuleService service;
    private InMemoryExecutionLogger logger;

    private NodeDefinition op(String id, String left, String operator, Object right,
                              Decision onHit, boolean stopOnHit) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(left); def.setOperator(operator); def.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(10).operatorDef(def).decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(500).build();
    }

    private FlowDefinition orderRisk() {
        StageDefinition hard = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(op("AMOUNT_LIMIT", "fact.order.amount", "GT", 50000, Decision.REJECT, true)))
                .build();
        StageDefinition scoring = StageDefinition.builder()
                .stageId("s2").order(200).executionMode(ExecutionMode.PARALLEL).skipWhenStopped(true)
                .stageTimeoutMillis(2000)
                .nodes(List.of(op("VIP_CHECK", "fact.buyer.level", "EQ", "NEW", Decision.REVIEW, false)))
                .build();
        return FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(hard, scoring)).build();
    }

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(new OperatorNodeExecutor()));
        NodeRunner runner = new NodeRunner(pool);
        FlowExecutor flowExecutor = new FlowExecutor(new SerialStageExecutor(runner),
                new ParallelStageExecutor(runner, pool),
                Map.of(AggregatePolicy.PRIORITY, new PriorityAggregator()));
        FlowCompiler compiler = new FlowCompiler(registry);
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        FlowLoader loader = new FlowLoader(repo, compiler);
        logger = new InMemoryExecutionLogger();
        service = new OpenRuleService(loader, compiler, flowExecutor, repo, logger,
                new LocalFlowChangeNotifier(loader));
        service.registerFlow(orderRisk());
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    @Test
    void execute_bigAmount_rejected() {
        ExecutionOutcome out = service.execute(new ExecuteCommand("order_risk", null, "B1", false,
                Map.of("order", Map.of("amount", 80000), "buyer", Map.of("level", "NEW"))));
        assertThat(out.result().getDecision()).isEqualTo(Decision.REJECT);
        assertThat(out.flowVersion()).isEqualTo(1);
        assertThat(out.result().getRequestId()).isNotBlank();   // 缺省生成 UUID
        assertThat(logger.recent()).hasSize(1);                 // 已记日志
    }

    @Test
    void execute_vip_passes() {
        ExecutionOutcome out = service.execute(new ExecuteCommand("order_risk", "REQ", "B2", false,
                Map.of("order", Map.of("amount", 1000), "buyer", Map.of("level", "VIP"))));
        assertThat(out.result().getDecision()).isEqualTo(Decision.PASS);
    }

    @Test
    void simulate_runsDraftWithoutRegistration() {
        ExecutionOutcome out = service.simulate(orderRisk(),
                Map.of("order", Map.of("amount", 1000), "buyer", Map.of("level", "NEW")));
        assertThat(out.result().getDecision()).isEqualTo(Decision.REVIEW);
    }
}
