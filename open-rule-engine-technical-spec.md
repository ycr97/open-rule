# OpenRule · 开源规则/决策引擎技术实现规范

> **文档用途**：供 Claude Code / Codex / Cursor 等 Agentic Coding 工具直接参考实现。
> **技术栈**：Java 21 · Spring Boot 3.x · MySQL 8 · Redis · Groovy 4 · GraalVM JS · CPython
> **架构模式**：Pipeline(Flow/Stage/Node) + NodeExecutor SPI + NodeResult 隔离合并
> **定位**：开源内核版。Scene 路由 / 不可变发布包 / 灰度回测等平台能力属于企业版范围，本文档不涉及，但所有接口为其预留扩展点。

---

## 0. 阅读说明（给 Agentic 工具）

实现时严格遵循以下顺序与约束：

1. 按 [第 14 章实现顺序](#14-实现顺序与里程碑) 分里程碑实现，每个里程碑可独立编译、测试、运行。
2. [第 15 章并发与正确性约束](#15-并发与正确性约束清单) 是硬性要求，违反任何一条视为实现错误。
3. 所有代码骨架中标注 `// IMPL:` 的位置是需要补全的实现点，标注 `// FIXED:` 的代码原样保留。
4. 包名统一为 `io.openrule.*`，模块划分见第 1 章。

---

## 1. 项目结构

```
open-rule-engine/
├── openrule-core/                          # 执行内核（零 Spring 依赖，纯 Java，可独立单测）
│   └── src/main/java/io/openrule/core/
│       ├── context/
│       │   ├── DecisionContext.java        # 执行上下文
│       │   └── FactMap.java                # 只读事实封装
│       ├── definition/
│       │   ├── FlowDefinition.java
│       │   ├── StageDefinition.java
│       │   ├── NodeDefinition.java
│       │   └── defs/                       # 各节点类型的配置定义
│       │       ├── OperatorDef.java
│       │       ├── ScoreCardDef.java
│       │       ├── DecisionTableDef.java
│       │       └── DecisionTreeDef.java
│       ├── enums/
│       │   ├── NodeType.java
│       │   ├── ExecutionMode.java          # SERIAL | PARALLEL
│       │   ├── Decision.java               # PASS | REJECT | REVIEW | LIMIT
│       │   ├── FailPolicy.java             # SKIP | REJECT | REVIEW | ABORT
│       │   └── AggregatePolicy.java
│       ├── spi/
│       │   ├── NodeExecutor.java           # 核心 SPI（validate/compile/execute）
│       │   ├── CompiledNode.java
│       │   ├── DecisionAggregator.java
│       │   └── ScriptExecutor.java
│       ├── runtime/
│       │   ├── FlowExecutor.java           # 流程执行入口
│       │   ├── SerialStageExecutor.java
│       │   ├── ParallelStageExecutor.java
│       │   ├── NodeExecutorRegistry.java
│       │   └── NodeRunner.java             # 单节点执行包装（超时/FailPolicy/Trace）
│       ├── result/
│       │   ├── NodeResult.java             # 节点统一输出（含 outputs 隔离区）
│       │   ├── StageResult.java
│       │   └── FlowResult.java
│       ├── aggregate/
│       │   ├── PriorityAggregator.java     # 决策优先级聚合
│       │   └── ScoreThresholdAggregator.java
│       └── exception/
│           ├── RuleEngineException.java
│           └── FlowValidationException.java
│
├── openrule-executor/                      # 节点执行器实现（依赖 core）
│   └── src/main/java/io/openrule/executor/
│       ├── OperatorNodeExecutor.java       # 内置运算符
│       ├── JavaNativeNodeExecutor.java
│       ├── GroovyScriptNodeExecutor.java
│       ├── JsScriptNodeExecutor.java
│       ├── PythonScriptNodeExecutor.java
│       ├── RuleSetNodeExecutor.java
│       ├── ScoreCardNodeExecutor.java
│       ├── DecisionTableNodeExecutor.java
│       ├── DecisionTreeNodeExecutor.java
│       ├── SubFlowNodeExecutor.java
│       └── script/
│           ├── GroovyRuntime.java          # 编译缓存 + 沙箱
│           ├── JsRuntime.java              # GraalVM ThreadLocal Context
│           └── python/
│               ├── PythonProcessPool.java
│               └── PythonWorker.java
│
├── openrule-spring/                        # Spring Boot 集成
│   └── src/main/java/io/openrule/spring/
│       ├── OpenRuleAutoConfiguration.java
│       ├── loader/
│       │   ├── FlowLoader.java             # JSON → CompiledFlow + Caffeine 缓存
│       │   └── FlowRepository.java         # MySQL 存取
│       ├── hot/
│       │   ├── HotUpdatePublisher.java
│       │   └── HotUpdateListener.java
│       ├── service/
│       │   └── OpenRuleService.java        # 门面
│       └── audit/
│           └── ExecutionLogger.java        # 异步执行日志
│
├── openrule-api/                           # REST API
│   └── src/main/java/io/openrule/api/
│       ├── ExecuteController.java
│       ├── FlowAdminController.java
│       └── dto/
│
├── openrule-python/                        # Python worker 脚本
│   └── worker.py
│
└── openrule-console/                       # 前端（Vue 3 + LogicFlow，独立仓库可选）
```

依赖方向（严格单向）：

```
openrule-api → openrule-spring → openrule-executor → openrule-core
```

`openrule-core` 不依赖 Spring，所有执行器通过构造注入，便于纯 JUnit 测试。

---

## 2. 核心领域模型

### 2.1 DecisionContext（执行上下文）

```java
// FIXED: 字段的并发类型选择是硬性约束，不得修改
public class DecisionContext {

    // ── 只读区：初始化后不变 ──
    private final String requestId;
    private final String flowId;
    private final String bizId;
    private final FactMap facts;                 // 外部输入事实，只读

    // ── 读写区：仅串行节点和 StageExecutor 合并线程可写 ──
    // 注意：并行节点【禁止】直接写 variables，必须通过 NodeResult.outputs 返回
    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    // ── 执行记录：并发追加安全 ──
    private final List<NodeResult> nodeResults = new CopyOnWriteArrayList<>();

    // ── 流程控制：volatile 保证可见性 ──
    private volatile boolean  stopped = false;
    private volatile Decision finalDecision;     // 仅 Aggregator 写入
    private volatile String   finalReason;

    public DecisionContext(String requestId, String flowId,
                           String bizId, Map<String, Object> facts) {
        this.requestId = requestId;
        this.flowId    = flowId;
        this.bizId     = bizId;
        this.facts     = new FactMap(facts);
    }

    // ── 读取方法 ──
    public Object fact(String key)       { return facts.get(key); }
    public Object variable(String key)   { return variables.get(key); }
    public boolean isStopped()           { return stopped; }

    // ── 写入方法（仅限串行路径 / StageExecutor 合并线程调用）──
    public void putVariable(String k, Object v) { variables.put(k, v); }
    public void stop()                          { this.stopped = true; }
    public void addNodeResult(NodeResult r)     { nodeResults.add(r); }

    // getters ...
}

// FactMap：包装为不可变视图
public class FactMap {
    private final Map<String, Object> data;
    public FactMap(Map<String, Object> source) {
        this.data = Map.copyOf(source);          // JDK 不可变 Map
    }
    public Object get(String key) { return data.get(key); }
    // 支持 "order.amount" 点路径解析（嵌套 Map）
    public Object getByPath(String path) { /* IMPL: 按 . 分割逐层取值 */ return null; }
}
```

### 2.2 NodeResult（节点统一输出 · 并行隔离的关键）

```java
// FIXED: 这是并行正确性的核心设计。
// 并行节点的所有写入都封装在 outputs 中返回，由 StageExecutor 单线程合并进 context.variables。
// 并行节点绝不直接调用 context.putVariable / context.stop。
@Builder
@Getter
public class NodeResult {
    private String   nodeId;
    private String   nodeName;
    private NodeType nodeType;

    private boolean  hit;                 // 规则是否命中
    private Decision decision;            // 本节点建议决策（可为 null = 无建议）
    private int      score;               // 评分贡献（非评分节点为 0）
    private boolean  stop;                // 建议终止流程（串行立即生效；并行由合并线程统一处理）
    private String   reason;              // 命中/拒绝原因

    // 并行隔离写入区：StageExecutor 合并后才进入 context.variables
    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();

    // 节点类型相关明细（决策表命中行/评分卡分项/脚本 stdout 摘要等），Trace 用
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    private boolean success;
    private String  errorCode;
    private String  errorMessage;
    private long    costMillis;
    private boolean skipped;              // 因 stopped 或 FailPolicy.SKIP 被跳过
}
```

### 2.3 FlowDefinition / StageDefinition / NodeDefinition

```java
@Data
public class FlowDefinition {
    private String  flowId;
    private String  flowName;
    private int     version;
    private boolean enabled;
    private AggregatePolicy aggregatePolicy;     // 全局聚合策略
    private List<StageDefinition> stages;
    private Map<String, Object>   metadata;
}

@Data
public class StageDefinition {
    private String        stageId;
    private String        stageName;
    private int           order;
    private ExecutionMode executionMode;         // SERIAL | PARALLEL
    private boolean       skipWhenStopped;       // context.stopped 时是否跳过本 Stage（默认 true）
    private long          stageTimeoutMillis;    // 并行 Stage 整组超时（默认 10000）
    private List<NodeDefinition> nodes;
}

@Data
public class NodeDefinition {
    private String     nodeId;
    private String     nodeName;
    private NodeType   nodeType;
    private int        order;

    // 节点配置（按 nodeType 取其一，JSON 反序列化时多态处理）
    private OperatorDef       operatorDef;       // OPERATOR
    private String            script;            // SCRIPT_* 小脚本直存
    private String            scriptId;          // SCRIPT_* 大脚本引用
    private String            beanName;          // JAVA_NATIVE：Spring Bean 名称
    private ScoreCardDef      scoreCardDef;      // SCORECARD
    private DecisionTableDef  decisionTableDef;  // DECISION_TABLE
    private DecisionTreeDef   decisionTreeDef;   // DECISION_TREE
    private String            subFlowId;         // SUB_FLOW
    private Integer           subFlowVersion;    // null = 当前启用版本
    private List<NodeDefinition> rules;          // RULE_SET 内部规则

    // 命中时行为
    private Decision   decisionOnHit;            // 命中时的建议决策
    private boolean    stopOnHit;                // 命中即终止

    // 异常治理
    private FailPolicy failPolicy;               // 默认 SKIP
    private long       timeoutMillis;            // 单节点超时（默认 3000）
}
```

### 2.4 枚举定义

```java
public enum NodeType {
    OPERATOR,            // 内置运算符（零编码，80% 规则首选）
    JAVA_NATIVE,         // Spring Bean（高性能路径）
    SCRIPT_GROOVY,
    SCRIPT_JS,
    SCRIPT_PYTHON,
    RULE_SET,            // 规则集（内部规则分组，可串/并行）
    SCORECARD,           // 评分卡
    DECISION_TABLE,      // 决策表
    DECISION_TREE,       // 决策树
    SUB_FLOW,            // 子流程引用
    // 预留：DAG_FLOW（企业版/后续迭代）
}

public enum ExecutionMode { SERIAL, PARALLEL }

public enum Decision {
    PASS, LIMIT, REVIEW, REJECT;
    // FIXED: 优先级顺序 REJECT > REVIEW > LIMIT > PASS（ordinal 越大风险越高，
    //        实现 PriorityAggregator 时按此顺序取最高风险）
    public boolean riskierThan(Decision other) {
        return this.ordinal() > other.ordinal();
    }
}

public enum FailPolicy {
    SKIP,      // 节点异常：记录后跳过，流程继续（弱辅助规则默认）
    REVIEW,    // 节点异常：本节点产出 REVIEW 建议（核心模型/评分节点推荐）
    REJECT,    // 节点异常：本节点产出 REJECT 建议（强监管阻断规则推荐）
    ABORT      // 节点异常：整个流程异常终止，向调用方抛错
}

public enum AggregatePolicy {
    PRIORITY,         // 按 Decision 优先级取最高风险（默认）
    FIRST_TERMINAL,   // 第一个 stop=true 的节点决策为准
    SCORE_THRESHOLD   // 累积 score 后按阈值映射（配合 thresholds 配置）
}
```

---

## 3. NodeExecutor SPI（核心扩展点）

```java
// FIXED: 三阶段 SPI。validate 在保存时调用，compile 在加载时调用，execute 在运行时调用。
public interface NodeExecutor {

    /** 声明支持的节点类型 */
    NodeType supportType();

    /**
     * 配置校验：FlowAdmin 保存流程定义时调用。
     * 校验失败抛 FlowValidationException，阻止保存。
     */
    default void validate(NodeDefinition node) throws FlowValidationException {}

    /**
     * 预编译：FlowLoader 加载流程时调用一次，结果随 CompiledFlow 缓存。
     * 在此完成脚本编译、决策表索引构建、表达式解析等重活，
     * execute 阶段只做纯执行。
     */
    default CompiledNode compile(NodeDefinition node) {
        return new CompiledNode(node, null);
    }

    /**
     * 运行时执行。
     * 约束：
     * 1. 不得调用 context.putVariable / context.stop（并行安全），
     *    所有写入放进 NodeResult.outputs，终止意图放 NodeResult.stop。
     * 2. 读取数据只通过 context.fact() / context.variable()。
     * 3. 不得吞异常：内部异常直接抛出，由 NodeRunner 统一应用 FailPolicy。
     */
    NodeResult execute(DecisionContext context, CompiledNode compiled);
}

// 编译产物容器
public class CompiledNode {
    private final NodeDefinition definition;
    private final Object         compiledArtifact;   // Script 对象 / 决策表索引 / 解析后的表达式树
    // constructor + getters
}
```

```java
// 注册表：构造注入所有实现（core 模块手动 new，spring 模块自动收集 Bean）
public class NodeExecutorRegistry {
    private final Map<NodeType, NodeExecutor> registry = new EnumMap<>(NodeType.class);

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        for (NodeExecutor e : executors) {
            NodeExecutor prev = registry.put(e.supportType(), e);
            if (prev != null) {
                throw new IllegalStateException("Duplicate executor for " + e.supportType());
            }
        }
    }

    public NodeExecutor getRequired(NodeType type) {
        NodeExecutor e = registry.get(type);
        if (e == null) throw new RuleEngineException("No executor for NodeType: " + type);
        return e;
    }
}
```

---

## 4. 执行内核

### 4.1 NodeRunner（单节点执行包装：超时 + FailPolicy + 计时）

```java
// FIXED: 所有节点执行必须经过 NodeRunner，统一治理。
@Slf4j
public class NodeRunner {

    private final NodeExecutorRegistry registry;
    private final ExecutorService      timeoutPool;   // 超时控制专用池

    public NodeResult run(DecisionContext ctx, CompiledNode compiled) {
        NodeDefinition def = compiled.getDefinition();
        long start = System.currentTimeMillis();

        if (ctx.isStopped()) {
            return skippedResult(def, start);
        }

        try {
            NodeResult result = executeWithTimeout(ctx, compiled, def);
            // 节点声明命中时附加 decisionOnHit / stopOnHit
            if (result.isHit() && def.getDecisionOnHit() != null && result.getDecision() == null) {
                result = result.toBuilder().decision(def.getDecisionOnHit()).build();
            }
            if (result.isHit() && def.isStopOnHit()) {
                result = result.toBuilder().stop(true).build();
            }
            return result;
        } catch (Exception e) {
            log.warn("[NodeRunner] node={} failed: {}", def.getNodeId(), e.getMessage());
            return applyFailPolicy(def, e, start);
        }
    }

    private NodeResult executeWithTimeout(DecisionContext ctx, CompiledNode compiled,
                                           NodeDefinition def) throws Exception {
        long timeout = def.getTimeoutMillis() > 0 ? def.getTimeoutMillis() : 3000;
        Future<NodeResult> future = timeoutPool.submit(
                () -> registry.getRequired(def.getNodeType()).execute(ctx, compiled));
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuleEngineException("Node timeout " + timeout + "ms: " + def.getNodeId());
        }
    }

    // FIXED: FailPolicy 语义
    private NodeResult applyFailPolicy(NodeDefinition def, Exception e, long start) {
        FailPolicy policy = def.getFailPolicy() != null ? def.getFailPolicy() : FailPolicy.SKIP;
        NodeResult.NodeResultBuilder base = NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName())
                .nodeType(def.getNodeType())
                .success(false).errorMessage(e.getMessage())
                .costMillis(System.currentTimeMillis() - start);

        return switch (policy) {
            case SKIP   -> base.skipped(true).build();
            case REVIEW -> base.hit(true).decision(Decision.REVIEW)
                               .reason("节点异常降级人审: " + def.getNodeName()).build();
            case REJECT -> base.hit(true).decision(Decision.REJECT).stop(true)
                               .reason("节点异常保守拒绝: " + def.getNodeName()).build();
            case ABORT  -> throw new RuleEngineException("Node abort: " + def.getNodeId(), e);
        };
    }
}
```

### 4.2 SerialStageExecutor

```java
// FIXED: 串行 Stage。节点依次执行，stop 立即生效，outputs 立即合并。
public class SerialStageExecutor {

    private final NodeRunner nodeRunner;

    public StageResult execute(DecisionContext ctx, CompiledStage stage) {
        List<NodeResult> results = new ArrayList<>();

        for (CompiledNode node : stage.getNodes()) {
            if (ctx.isStopped()) break;

            NodeResult result = nodeRunner.run(ctx, node);
            results.add(result);
            ctx.addNodeResult(result);

            // 串行路径：合并 outputs 进 variables（单线程，安全）
            result.getOutputs().forEach(ctx::putVariable);

            // 串行路径：stop 立即生效
            if (result.isStop()) {
                ctx.stop();
                break;
            }
        }
        return StageResult.of(stage.getStageId(), results);
    }
}
```

### 4.3 ParallelStageExecutor（核心正确性设计）

```java
// FIXED: 并行 Stage 的正确性模型：
//   1. 节点在隔离线程池并发执行，只返回 NodeResult，不触碰 context
//   2. allOf 等待全部完成或整组超时
//   3. 【合并线程单线程】按节点 order 顺序合并 outputs → variables（确定性）
//   4. stop 在合并阶段统一判定，不在并发阶段生效
@Slf4j
public class ParallelStageExecutor {

    private final NodeRunner nodeRunner;
    private final Executor   parallelPool;   // 业务隔离池（虚拟线程或有界池）

    public StageResult execute(DecisionContext ctx, CompiledStage stage) {
        if (ctx.isStopped()) return StageResult.skipped(stage.getStageId());

        long stageTimeout = stage.getStageTimeoutMillis() > 0
                ? stage.getStageTimeoutMillis() : 10_000;

        // 1) 并发执行，收集 Future（顺序与节点定义顺序一致）
        List<CompletableFuture<NodeResult>> futures = stage.getNodes().stream()
                .map(node -> CompletableFuture.supplyAsync(
                        () -> nodeRunner.run(ctx, node), parallelPool))
                .toList();

        // 2) 整组等待
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(stageTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            futures.forEach(f -> f.cancel(true));
            log.warn("[ParallelStage] stage={} 整组超时 {}ms", stage.getStageId(), stageTimeout);
        } catch (Exception e) {
            log.error("[ParallelStage] stage={} 执行异常", stage.getStageId(), e);
        }

        // 3) 单线程合并（确定性：按节点定义顺序，而非完成顺序）
        List<NodeResult> results = new ArrayList<>();
        boolean anyStop = false;
        for (int i = 0; i < futures.size(); i++) {
            NodeResult r = resolveResult(futures.get(i), stage.getNodes().get(i));
            results.add(r);
            ctx.addNodeResult(r);
            r.getOutputs().forEach(ctx::putVariable);   // 合并线程单线程写入
            anyStop |= r.isStop();
        }

        // 4) stop 统一生效
        if (anyStop) ctx.stop();

        return StageResult.of(stage.getStageId(), results);
    }

    private NodeResult resolveResult(CompletableFuture<NodeResult> f, CompiledNode node) {
        try {
            return f.getNow(null) != null ? f.getNow(null)
                    : timeoutResult(node);   // 超时被 cancel 的节点
        } catch (Exception e) {
            return errorResult(node, e);
        }
    }
}
```

### 4.4 FlowExecutor（流程执行入口）

```java
public class FlowExecutor {

    private final SerialStageExecutor   serialExecutor;
    private final ParallelStageExecutor parallelExecutor;
    private final Map<AggregatePolicy, DecisionAggregator> aggregators;

    public FlowResult execute(DecisionContext ctx, CompiledFlow flow) {
        long start = System.currentTimeMillis();

        for (CompiledStage stage : flow.getStages()) {
            if (ctx.isStopped() && stage.isSkipWhenStopped()) continue;

            if (stage.getExecutionMode() == ExecutionMode.SERIAL) {
                serialExecutor.execute(ctx, stage);
            } else {
                parallelExecutor.execute(ctx, stage);
            }
        }

        // 决策聚合：唯一写 finalDecision 的地方
        DecisionAggregator aggregator = aggregators.get(flow.getAggregatePolicy());
        AggregateOutcome outcome = aggregator.aggregate(ctx.getNodeResults(), ctx);
        ctx.setFinalDecision(outcome.decision());
        ctx.setFinalReason(outcome.reason());

        return FlowResult.builder()
                .requestId(ctx.getRequestId())
                .flowId(ctx.getFlowId())
                .bizId(ctx.getBizId())
                .decision(outcome.decision())
                .reason(outcome.reason())
                .totalScore(outcome.totalScore())
                .hitNodes(outcome.hitNodes())
                .nodeResults(ctx.getNodeResults())
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }
}
```

### 4.5 决策聚合器

```java
public interface DecisionAggregator {
    AggregatePolicy supportPolicy();
    AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx);
}

public record AggregateOutcome(Decision decision, String reason,
                               int totalScore, List<String> hitNodes) {}

// 默认聚合器：按风险优先级取最高
public class PriorityAggregator implements DecisionAggregator {
    @Override public AggregatePolicy supportPolicy() { return AggregatePolicy.PRIORITY; }

    @Override
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx) {
        Decision highest = Decision.PASS;
        List<String> hitNodes = new ArrayList<>();
        StringBuilder reason = new StringBuilder();

        for (NodeResult r : results) {
            if (r.isHit()) hitNodes.add(r.getNodeId());
            if (r.getDecision() != null && r.getDecision().riskierThan(highest)) {
                highest = r.getDecision();
                reason.setLength(0);
                reason.append(r.getReason());
            }
        }
        int totalScore = results.stream().mapToInt(NodeResult::getScore).sum();
        return new AggregateOutcome(highest, reason.toString(), totalScore, hitNodes);
    }
}

// 评分阈值聚合器：累积 score → 阈值映射
// IMPL: thresholds 从 FlowDefinition.metadata 读取，格式
//       { "review": 60, "reject": 85 }，score >= reject → REJECT，>= review → REVIEW，否则 PASS
public class ScoreThresholdAggregator implements DecisionAggregator { /* IMPL */ }
```

---

## 5. 节点执行器实现

### 5.1 OperatorNodeExecutor（内置运算符，一期首发）

```java
public class OperatorNodeExecutor implements NodeExecutor {

    @Override public NodeType supportType() { return NodeType.OPERATOR; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        OperatorDef def = node.getOperatorDef();
        if (def == null || def.getLeftFact() == null || def.getOperator() == null) {
            throw new FlowValidationException("OPERATOR 节点配置不完整: " + node.getNodeId());
        }
        // IMPL: 校验 operator 是否在支持列表内
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        OperatorDef def = compiled.getDefinition().getOperatorDef();
        long start = System.currentTimeMillis();

        Object leftValue = resolveValue(def.getLeftFact(), ctx);   // 支持 fact./var. 前缀
        boolean hit = compare(leftValue, def.getOperator(), def.getRightValue());

        return NodeResult.builder()
                .nodeId(compiled.getDefinition().getNodeId())
                .nodeName(compiled.getDefinition().getNodeName())
                .nodeType(NodeType.OPERATOR)
                .hit(hit).success(true)
                .reason(hit ? buildHitReason(def, leftValue) : null)
                .details(Map.of("leftValue", String.valueOf(leftValue),
                                "operator", def.getOperator(),
                                "rightValue", String.valueOf(def.getRightValue())))
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    // 取值路径约定：
    //   "fact.order.amount"  → ctx.fact 按点路径
    //   "var.scorecard.score" → ctx.variable
    private Object resolveValue(String path, DecisionContext ctx) { /* IMPL */ return null; }

    // FIXED: 支持的运算符全集
    // 数值：GT GTE LT LTE EQ NE BETWEEN
    // 字符串：EQ NE CONTAINS NOT_CONTAINS STARTS_WITH ENDS_WITH
    // 集合：IN NOT_IN
    // 空值：IS_NULL NOT_NULL
    // 正则：REGEX（编译结果缓存在 CompiledNode，防 ReDoS：限制 pattern 长度 + 执行超时由 NodeRunner 兜底）
    private boolean compare(Object left, String op, Object right) { /* IMPL */ return false; }
}
```

### 5.2 GroovyScriptNodeExecutor + GroovyRuntime

```java
// GroovyRuntime：编译缓存 + 沙箱
public class GroovyRuntime {

    private final GroovyShell shell;
    private final Cache<String, Class<? extends Script>> classCache = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterAccess(Duration.ofHours(4)).recordStats().build();

    public GroovyRuntime() {
        CompilerConfiguration config = new CompilerConfiguration();
        SecureASTCustomizer sec = new SecureASTCustomizer();
        // FIXED: 沙箱黑名单
        sec.setDisallowedReceiversClasses(List.of(
                System.class, Runtime.class, ProcessBuilder.class,
                Thread.class, ClassLoader.class));
        sec.setIndirectImportCheckEnabled(true);
        config.addCompilationCustomizers(sec);
        this.shell = new GroovyShell(config);
    }

    // FIXED: 缓存 Class 而非 Script 实例（Script 实例非线程安全）。
    //        每次执行 newInstance + 独立 Binding。
    public Object eval(String scriptText, Map<String, Object> bindings) throws Exception {
        String key = DigestUtils.md5Hex(scriptText);
        Class<? extends Script> clazz = classCache.get(key,
                k -> shell.getClassLoader().parseClass(scriptText));

        Script instance = clazz.getDeclaredConstructor().newInstance();
        Binding binding = new Binding();
        bindings.forEach(binding::setVariable);
        instance.setBinding(binding);
        return instance.run();
    }
}

// 执行器：脚本输入/输出协议
public class GroovyScriptNodeExecutor implements NodeExecutor {

    private final GroovyRuntime runtime;

    @Override public NodeType supportType() { return NodeType.SCRIPT_GROOVY; }

    @Override
    public CompiledNode compile(NodeDefinition node) {
        // IMPL: 加载时预编译，编译失败在加载阶段暴露而非运行时
        return new CompiledNode(node, null);
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) throws RuleEngineException {
        // FIXED: 脚本可见的绑定变量（白名单注入，不暴露 context 本体）
        Map<String, Object> bindings = Map.of(
                "facts",  new HashMap<>(/* facts 快照 */),
                "vars",   new HashMap<>(ctx.getVariablesSnapshot()),
                "result", new HashMap<String, Object>()   // 脚本写出区
        );
        // 脚本协议：脚本向 result 写入
        //   result.hit      = true/false
        //   result.decision = "REJECT" / "REVIEW" / ...
        //   result.stop     = true/false
        //   result.reason   = "..."
        //   result.outputs  = [k: v]    （进入 NodeResult.outputs）
        // IMPL: eval 后解析 result Map 构建 NodeResult
        return null;
    }
}
```

### 5.3 JsRuntime（GraalVM）

```java
public class JsRuntime {
    // FIXED: Context 非线程安全 → ThreadLocal 隔离；沙箱全关
    private final ThreadLocal<Context> contexts = ThreadLocal.withInitial(() ->
            Context.newBuilder("js")
                    .allowAllAccess(false)
                    .allowHostAccess(HostAccess.NONE)
                    .allowIO(IOAccess.NONE)
                    .option("js.ecmascript-version", "2022")
                    .build());

    public Value eval(String script, Map<String, Object> bindings) {
        Context c = contexts.get();
        Value jsBindings = c.getBindings("js");
        bindings.forEach((k, v) -> jsBindings.putMember(k, ProxyObject.fromMap(toMap(v))));
        return c.eval("js", script);
    }
    // 脚本协议与 Groovy 一致：返回 {hit, decision, stop, reason, outputs} JS 对象
}
```

### 5.4 PythonProcessPool + worker.py

```java
// FIXED: 进程池要点
//  1. 池大小 = CPU 核数（GIL 限制，更大无意义）
//  2. 启动时全量预热，while True 常驻
//  3. 异常进程销毁重建，绝不放回池
//  4. JSON line 协议（stdin 一行请求 / stdout 一行响应）
public class PythonProcessPool implements AutoCloseable {

    private final BlockingQueue<PythonWorker> pool = new LinkedBlockingQueue<>();
    private final int    poolSize;
    private final String pythonBin;
    private final String workerScript;

    public void start() throws IOException {
        for (int i = 0; i < poolSize; i++) pool.offer(spawn(i));
    }

    public Map<String, Object> call(Map<String, Object> request, long timeoutMillis)
            throws Exception {
        PythonWorker worker = pool.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (worker == null) throw new RuleEngineException("Python pool exhausted");
        try {
            return worker.call(request, timeoutMillis);
        } catch (Exception e) {
            worker.destroy();
            pool.offer(spawn(worker.getId()));   // 重建补位
            throw e;
        } finally {
            if (worker.isAlive()) pool.offer(worker);
        }
    }

    private PythonWorker spawn(int id) throws IOException {
        Process p = new ProcessBuilder(pythonBin, "-u", workerScript)
                .redirectErrorStream(false).start();
        return new PythonWorker(id, p);
    }
}
```

```python
# openrule-python/worker.py
# FIXED: 常驻 worker，JSON line 协议
import sys, json

# 重依赖在进程启动时加载一次（numpy/sklearn 等按需）
def handle(req: dict) -> dict:
    script = req.get('script', '')
    local_vars = {
        'facts': req.get('facts', {}),
        'vars': req.get('vars', {}),
        'result': {'hit': False, 'decision': None, 'stop': False,
                   'reason': None, 'outputs': {}}
    }
    exec(script, {'__builtins__': __builtins__}, local_vars)  # 生产可进一步收窄 builtins
    return local_vars['result']

if __name__ == '__main__':
    sys.stdout.reconfigure(line_buffering=True)
    for line in sys.stdin:
        try:
            req = json.loads(line)
            resp = handle(req)
            resp['success'] = True
        except Exception as e:
            resp = {'success': False, 'error': str(e),
                    'hit': False, 'decision': None, 'stop': False, 'outputs': {}}
        sys.stdout.write(json.dumps(resp) + '\n')
```

### 5.5 高级节点执行器（实现要点）

```java
// ScoreCardNodeExecutor
// IMPL 要点:
//  compile(): 将 bins 区间排序并构建查找结构
//  execute(): 遍历 attributes → 取特征 → 分箱取分 →（加权）累加 baseScore
//             score 写入 NodeResult.score；明细写 details.attributeScores
//             outputs 写入 "scorecard.{nodeId}.score" 供后续 Operator 节点引用
//             thresholds 命中时给出 decision 建议

// DecisionTableNodeExecutor
// IMPL 要点:
//  hitPolicy: FIRST | PRIORITY | COLLECT
//  compile(): 构建行条件的解析结果（每列预解析为 Operator 比较器）
//  execute(): 按 hitPolicy 匹配行；details 记录 hitRows + outputColumns
//             命中行的 outputColumns 进入 NodeResult.outputs

// DecisionTreeNodeExecutor
// IMPL 要点:
//  递归遍历，MAX_DEPTH=20；details 记录完整路径 "root->condA:true->leaf:REJECT"
//  叶子节点的 decision 字段作为 NodeResult.decision

// RuleSetNodeExecutor
// IMPL 要点:
//  内部规则列表复用 NodeRunner 逐个执行（SERIAL）或并发（PARALLEL，同样走 NodeResult 隔离合并）
//  内部 hitPolicy: FIRST_HIT | ANY_HIT | ALL_HIT | COLLECT
//  RuleSet 自身的 NodeResult 由内部结果聚合而来

// SubFlowNodeExecutor
// IMPL 要点:
//  加载子流程 CompiledFlow（FlowLoader），新建【子 DecisionContext】执行（facts 透传，variables 隔离）
//  子流程 FlowResult.decision 作为本节点 decision；防递归：加载链上检测 flowId 重复，最大嵌套 5 层
```

---

## 6. FlowLoader 与缓存

```java
// FIXED: 缓存的是 CompiledFlow（编译产物），不是 JSON。
//        key = flowId:v{version}；热更新 invalidate flowId 全部版本。
@Slf4j
public class FlowLoader {

    private final FlowRepository       repository;
    private final NodeExecutorRegistry registry;

    private final Cache<String, CompiledFlow> cache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterAccess(Duration.ofHours(2))
            .recordStats().build();

    public CompiledFlow loadActive(String flowId) {
        FlowDefinition def = repository.findActiveByFlowId(flowId);
        if (def == null) throw new RuleEngineException("Flow not found or disabled: " + flowId);
        return cache.get(flowId + ":v" + def.getVersion(), k -> compile(def));
    }

    public void invalidate(String flowId) {
        cache.asMap().keySet().removeIf(k -> k.startsWith(flowId + ":"));
        log.info("[FlowLoader] invalidated: {}", flowId);
    }

    private CompiledFlow compile(FlowDefinition def) {
        List<CompiledStage> stages = def.getStages().stream()
                .sorted(Comparator.comparingInt(StageDefinition::getOrder))
                .map(s -> compileStage(s))
                .toList();
        return new CompiledFlow(def, stages);
    }

    private CompiledStage compileStage(StageDefinition stage) {
        List<CompiledNode> nodes = stage.getNodes().stream()
                .sorted(Comparator.comparingInt(NodeDefinition::getOrder))
                .map(n -> registry.getRequired(n.getNodeType()).compile(n))
                .toList();
        return new CompiledStage(stage, nodes);
    }
}
```

热更新（Redis Pub/Sub）：

```java
// 发布端：FlowAdminController 保存/启停流程后
publisher.publish(flowId);   // channel: openrule:hot-update, message: flowId

// 订阅端：所有实例
public class HotUpdateListener implements MessageListener {
    @Override public void onMessage(Message message, byte[] pattern) {
        flowLoader.invalidate(new String(message.getBody()));
    }
}
```

---

## 7. 数据库表结构

```sql
CREATE TABLE or_flow (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id       VARCHAR(100) NOT NULL,
    flow_name     VARCHAR(200) NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    enabled       TINYINT      NOT NULL DEFAULT 0 COMMENT '同一 flow_id 仅一个版本 enabled=1',
    aggregate_policy VARCHAR(32) NOT NULL DEFAULT 'PRIORITY',
    definition_json MEDIUMTEXT NOT NULL,
    checksum      VARCHAR(64)  NOT NULL COMMENT 'definition_json 的 SHA-256，审计可复现',
    remark        VARCHAR(500),
    created_by    VARCHAR(100),
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_flow_version (flow_id, version),
    KEY idx_flow_enabled (flow_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义（版本化）';

CREATE TABLE or_script (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    script_id     VARCHAR(100) NOT NULL,
    script_type   VARCHAR(20)  NOT NULL COMMENT 'GROOVY|JS|PYTHON',
    script_name   VARCHAR(200),
    script_body   MEDIUMTEXT   NOT NULL,
    md5           VARCHAR(32)  NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_script_version (script_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='脚本库（节点 scriptId 引用）';

CREATE TABLE or_execute_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id    VARCHAR(128) NOT NULL,
    flow_id       VARCHAR(100) NOT NULL,
    flow_version  INT          NOT NULL,
    biz_id        VARCHAR(200) NOT NULL,
    decision      VARCHAR(20)  NOT NULL,
    reason        VARCHAR(512),
    total_score   INT          NOT NULL DEFAULT 0,
    cost_millis   INT          NOT NULL,
    hit_nodes     JSON,
    node_results  MEDIUMTEXT COMMENT '节点明细 JSON（含 details）',
    facts_snapshot MEDIUMTEXT COMMENT '入参快照（脱敏后）',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request (request_id),
    KEY idx_biz   (biz_id),
    KEY idx_flow_time (flow_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志（高量场景建议改写 ES）';
```

注意：开源版以 `flow_version + checksum` 记录在执行日志中实现**轻量可复现**（历史决策能定位到当时的 definition_json）。完整的不可变发布包模型属于企业版。

---

## 8. REST API

```
POST /api/v1/execute
请求:
{
  "flowId": "order_risk",
  "requestId": "REQ_xxx",        // 幂等键，可选，缺省生成 UUID
  "bizId": "ORDER_10001",
  "debug": false,                 // true 时响应携带 nodeResults 明细
  "facts": {
    "order": { "amount": 12800, "country": "JP" },
    "buyer": { "id": "B10001", "level": "NORMAL" }
  }
}
响应:
{
  "requestId": "REQ_xxx",
  "flowId": "order_risk",
  "flowVersion": 3,
  "decision": "REVIEW",
  "reason": "评分卡总分超过人审阈值",
  "totalScore": 72,
  "hitNodes": ["BUYER_SCORECARD"],
  "costMillis": 18,
  "nodeResults": [ ... ]          // 仅 debug=true
}

流程管理:
POST   /api/v1/admin/flows                     创建（version=1, enabled=0 草稿）
PUT    /api/v1/admin/flows/{flowId}            保存为新版本（触发全节点 validate）
POST   /api/v1/admin/flows/{flowId}/enable     启用指定版本（原子切换 enabled 指针 + 热更新广播）
POST   /api/v1/admin/flows/{flowId}/rollback   回滚 = enable 旧版本
GET    /api/v1/admin/flows/{flowId}/versions   版本列表
POST   /api/v1/admin/simulate                  草稿模拟执行（不要求 enabled，返回完整明细）
GET    /api/v1/admin/logs                      执行日志查询（by bizId / flowId / 时间）
```

---

## 9. Spring 装配

```java
@AutoConfiguration
public class OpenRuleAutoConfiguration {

    @Bean("openRuleParallelPool")
    public Executor parallelPool() {
        return Executors.newVirtualThreadPerTaskExecutor();   // JDK21 虚拟线程
    }

    @Bean("openRuleTimeoutPool")
    public ExecutorService timeoutPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public NodeExecutorRegistry nodeExecutorRegistry(List<NodeExecutor> executors) {
        return new NodeExecutorRegistry(executors);   // 自动收集所有 NodeExecutor Bean
    }

    @Bean
    public Map<AggregatePolicy, DecisionAggregator> aggregators(List<DecisionAggregator> list) {
        return list.stream().collect(Collectors.toMap(
                DecisionAggregator::supportPolicy, a -> a));
    }

    @Bean
    @ConditionalOnProperty("openrule.python.enabled")
    public PythonProcessPool pythonPool(OpenRuleProperties props) { /* ... */ }

    // FlowLoader / NodeRunner / FlowExecutor / Serial/ParallelStageExecutor 常规装配
}
```

```yaml
# application.yml 配置项全集
openrule:
  parallel:
    stage-timeout-millis: 10000
  node:
    default-timeout-millis: 3000
  groovy:
    cache-max-size: 1000
  js:
    enabled: true
  python:
    enabled: false
    pool-size: 4              # 建议 = CPU 核数
    executable: python3
    worker-script: classpath:worker.py
  hot-update:
    channel: openrule:hot-update
  audit:
    async: true
    facts-desensitize-keys: mobile,idCard,bankCard,password
```

---

## 10. 审计与可观测

```java
// 异步执行日志：不阻塞主链路，失败仅告警
@Async("openRuleAuditPool")
public void log(FlowResult result, DecisionContext ctx) {
    // 1. facts 脱敏（配置的 key 替换为 ****）
    // 2. nodeResults 序列化（含 details）
    // 3. 写 or_execute_log；高量场景实现 EsExecutionLogger 替换
}

// Micrometer 指标（实现为 NodeRunner / FlowExecutor 内埋点）
// openrule_execute_total{flowId, decision}            Counter
// openrule_execute_duration_ms{flowId}                Timer
// openrule_node_duration_ms{flowId, nodeType}         Timer
// openrule_node_error_total{flowId, nodeId, policy}   Counter
// openrule_script_cache_hit_ratio{lang}               Gauge
// openrule_python_pool_available                      Gauge
```

---

## 11. 流程定义 JSON 完整示例

```json
{
  "flowId": "order_risk",
  "flowName": "订单风控流程",
  "version": 3,
  "aggregatePolicy": "PRIORITY",
  "stages": [
    {
      "stageId": "s1", "stageName": "硬规则", "order": 100,
      "executionMode": "SERIAL", "skipWhenStopped": true,
      "nodes": [
        {
          "nodeId": "BLACKLIST", "nodeName": "黑名单", "nodeType": "JAVA_NATIVE",
          "order": 10, "beanName": "blacklistRule",
          "decisionOnHit": "REJECT", "stopOnHit": true,
          "failPolicy": "REJECT", "timeoutMillis": 200
        },
        {
          "nodeId": "AMOUNT_LIMIT", "nodeName": "金额上限", "nodeType": "OPERATOR",
          "order": 20,
          "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": 50000 },
          "decisionOnHit": "REJECT", "stopOnHit": true,
          "failPolicy": "SKIP", "timeoutMillis": 100
        }
      ]
    },
    {
      "stageId": "s2", "stageName": "并行评分", "order": 200,
      "executionMode": "PARALLEL", "stageTimeoutMillis": 8000,
      "nodes": [
        {
          "nodeId": "BUYER_SCORECARD", "nodeName": "买家评分卡", "nodeType": "SCORECARD",
          "order": 10, "scoreCardDef": { "baseScore": 0, "scoreMode": "SUM",
            "attributes": [
              { "featureKey": "fact.buyer.level", "bins": [
                  { "match": "NEW",    "score": 30 },
                  { "match": "NORMAL", "score": 10 },
                  { "match": "VIP",    "score": 0 } ] }
            ],
            "thresholds": { "review": 60, "reject": 85 } },
          "failPolicy": "REVIEW", "timeoutMillis": 1000
        },
        {
          "nodeId": "FREQ_SCRIPT", "nodeName": "频率脚本", "nodeType": "SCRIPT_GROOVY",
          "order": 20,
          "script": "def cnt = vars['tx.count.1h'] ?: 0\nresult.hit = cnt > 20\nresult.outputs['risk.freq.hit'] = result.hit\nif (result.hit) { result.decision='REVIEW'; result.reason='1小时交易超20次' }",
          "failPolicy": "SKIP", "timeoutMillis": 2000
        }
      ]
    }
  ]
}
```

---

## 12. 测试要求

```
单元测试（openrule-core，无 Spring）：
  - SerialStageExecutor：stop 短路 / outputs 合并 / skipWhenStopped
  - ParallelStageExecutor：整组超时 / 单节点异常 FailPolicy / 合并确定性
                          （并发写 variables 的竞争测试：同 key 不同节点写入，验证按 order 合并）
  - NodeRunner：四种 FailPolicy 各一个用例 / 超时 cancel
  - PriorityAggregator：REJECT > REVIEW > LIMIT > PASS 全组合
  - OperatorNodeExecutor：全运算符 × 类型矩阵

集成测试（openrule-spring）：
  - FlowLoader 缓存命中 / invalidate / 版本切换
  - 热更新 Pub/Sub 端到端
  - GroovyRuntime 并发执行同一脚本 100 线程无数据串扰
  - PythonProcessPool 异常进程重建

性能基线（JMH 或简单压测）：
  - 纯 OPERATOR 流程（5 节点串行）P99 < 2ms
  - 含 Groovy 热路径流程 P99 < 20ms
```

---

## 13. 企业版预留扩展点（开源版不实现，但接口不堵死）

```
1. SceneRouter：OpenRuleService.execute(flowId, ...) 之上可加一层
   execute(sceneCode, ...) → 路由解析出 flowId，开源接口不变
2. PublishedPackage：FlowLoader.loadActive() 可替换为 loadPackage(packageId)，
   CompiledFlow 结构不变
3. GrayRouter：enable 接口已是指针切换，灰度 = 指针按比例路由，存储模型兼容
4. Model 节点：NodeType 预留，新增 ModelNodeExecutor 即可（SPI 不变）
5. DAG Flow：FlowDefinition 增加 flowType + edges 字段，新增 DagFlowExecutor，
   线性模型代码不动
```

---

## 14. 实现顺序与里程碑

```
M1 — 内核可运行（openrule-core 纯 Java）
  ✅ 全部枚举 / DecisionContext / NodeResult / 定义模型
  ✅ NodeExecutor SPI + Registry + NodeRunner（含 FailPolicy + 超时）
  ✅ SerialStageExecutor / ParallelStageExecutor（NodeResult 隔离合并）
  ✅ FlowExecutor + PriorityAggregator
  ✅ OperatorNodeExecutor
  ✅ 单元测试全绿
  验收：纯 Java main 方法跑通 JSON 定义的流程

M2 — Spring 集成 + 持久化
  ✅ FlowRepository(MySQL) / FlowLoader(Caffeine) / checksum
  ✅ OpenRuleService / ExecuteController / FlowAdminController
  ✅ 热更新 Pub/Sub
  ✅ ExecutionLogger 异步日志
  验收：REST 创建流程 → 启用 → 执行 → 查日志全链路

M3 — 脚本引擎
  ✅ GroovyRuntime（Class 缓存 + 沙箱）+ GroovyScriptNodeExecutor
  ✅ JsRuntime（GraalVM ThreadLocal）+ JsScriptNodeExecutor
  ✅ JavaNativeNodeExecutor
  ✅ simulate 接口（草稿测试）
  验收：三种语言节点在同一流程混跑

M4 — 高级节点
  ✅ ScoreCardNodeExecutor / DecisionTableNodeExecutor
  ✅ DecisionTreeNodeExecutor / RuleSetNodeExecutor / SubFlowNodeExecutor
  ✅ ScoreThresholdAggregator
  验收：评分卡 + 决策表 + 子流程组合流程跑通

M5 — Python + 可观测
  ✅ PythonProcessPool + worker.py + PythonScriptNodeExecutor
  ✅ Micrometer 指标全埋点
  ✅ 性能基线测试达标
```

---

## 15. 并发与正确性约束清单

> 实现中违反任何一条视为缺陷。Code Review 与测试必查。

```
C1  并行节点（NodeExecutor.execute 在 PARALLEL Stage 中被调用时）
    禁止调用 context.putVariable / context.stop / context.setFinalDecision。
    所有写入通过 NodeResult.outputs，终止意图通过 NodeResult.stop。

C2  ParallelStageExecutor 的 outputs 合并必须单线程、按节点定义 order 顺序，
    保证同 key 冲突时结果确定（后 order 覆盖前 order），禁止按完成顺序合并。

C3  finalDecision 全局唯一写入点 = DecisionAggregator（FlowExecutor 调用处）。
    任何节点、任何 StageExecutor 不得直接写。

C4  Groovy 缓存 Class<? extends Script> 而非 Script 实例；
    每次执行 newInstance + 新 Binding。共享 Script 实例 = 缺陷。

C5  GraalVM Context 必须 ThreadLocal 隔离，禁止跨线程共享。

C6  Python 进程异常后销毁重建，禁止放回池。池大小默认 = CPU 核数。

C7  facts 不可变（Map.copyOf）。任何节点修改 facts = 缺陷。

C8  NodeRunner 是节点执行唯一入口：超时、FailPolicy、计时、skipped 判断全在此层，
    各 NodeExecutor 内部不重复实现，也不吞异常。

C9  stopped / finalDecision / finalReason 字段必须 volatile。
    variables 必须 ConcurrentHashMap（合并线程与串行线程可能不同）。
    nodeResults 必须 CopyOnWriteArrayList。

C10 SubFlow 防递归：加载链检测 flowId 重复，嵌套深度上限 5。
    DecisionTree 深度上限 20。正则 pattern 长度上限 512。

C11 缓存 key 必含版本（flowId:v{n}），enable 切换后旧版本缓存自然失效 + 主动 invalidate。

C12 执行日志写入失败不影响主流程（catch + 告警计数器）。
```

---

*OpenRule v1.0 · 开源内核版技术实现规范 · Pipeline + SPI + NodeResult 隔离架构*
