# ADR-0001：Core 使用开放类型 ID、类型化配置和 executor-owned plan

> 状态：Accepted
> 日期：2026-08-20
> 决策范围：M1.6 及后续全部 OSS/EE 节点和聚合器

## 背景

当前 `NodeType` 和 `AggregatePolicy` 都是 Core 枚举。新增插件或 EE `MODEL` 节点必须修改并重新发布 Core，这与“EE 扩展而不修改 Core”直接冲突。

`NodeDefinition` 还会为每种节点增加一个可空字段，`CompiledNode` 以 `Object` 保存 artifact，并手工复制当前已知的 `OperatorDef`。这三个问题会在 M4、脚本和 EE 模型节点进入后同时放大。

## 决策

### 1. 开放标识符

删除公开枚举 `NodeType` 和 `AggregatePolicy`，改为不可变值对象：

```java
public record NodeTypeId(String value) {
    public NodeTypeId {
        value = Identifiers.requireCanonical(value, "nodeType");
    }
}

public record AggregatorId(String value) {
    public AggregatorId {
        value = Identifiers.requireCanonical(value, "aggregatorId");
    }
}
```

标识符必须匹配：

```text
[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*
```

长度为 3–80，区分大小写但只允许小写。Core 内置 ID：

```java
public final class BuiltinNodeTypes {
    public static final NodeTypeId OPERATOR = new NodeTypeId("openrule.operator");
}

public final class BuiltinAggregators {
    public static final AggregatorId PRIORITY = new AggregatorId("openrule.priority");
    public static final AggregatorId FIRST_TERMINAL = new AggregatorId("openrule.first-terminal");
}
```

`openrule.*` 命名空间仅供官方模块。第三方使用自己的稳定命名空间，例如 `acme.credit-model`。

### 2. 单一节点配置槽位

`NodeDefinition` 不再增长 `operatorDef`、`scoreCardDef`、`script` 等互斥字段，统一使用 `config`：

```java
public interface NodeConfig {}

public record NodeDefinition(
        String nodeId,
        String nodeName,
        NodeTypeId type,
        int order,
        int configVersion,
        NodeConfig config,
        Decision decisionOnHit,
        boolean stopOnHit,
        FailPolicy failPolicy,
        Duration timeout) {}
```

每个配置必须是不可变 record 或具备等价的深度不可变保证。Core 内置 Operator 使用 `OperatorConfig implements NodeConfig`。Definition JSON v2 统一为：

```json
{
  "nodeId": "amount-limit",
  "nodeName": "大额限制",
  "type": "openrule.operator",
  "order": 10,
  "configVersion": 1,
  "config": {
    "left": { "source": "FACT", "pointer": "/order/amount" },
    "operator": "gte",
    "right": 1000
  },
  "decisionOnHit": "REJECT",
  "stopOnHit": true,
  "failPolicy": "ABORT",
  "timeout": "PT1S"
}
```

### 3. 执行器拥有编译计划类型

取消 `CompiledNode(Object compiledArtifact)`。执行器返回自己的不可变 `NodePlan`：

```java
public interface NodePlan {
    EffectKind effectKind();
}

public interface NodeExecutor<C extends NodeConfig, P extends NodePlan> {
    NodeTypeId type();
    int configVersion();
    Class<C> configType();
    Class<P> planType();
    PluginDescriptor provider();

    void validate(NodeDefinition node, C config, ValidationContext context);
    P compile(NodeDefinition node, C config, CompilationContext context);
    NodeResult execute(NodeExecutionInput input, P plan) throws Exception;
}

public record PluginDescriptor(
        String pluginId,
        String pluginVersion,
        String compilerVersion) {}
```

例如 Operator 编译结果是：

```java
record OperatorPlan(
        ValueAccessor left,
        OperatorMatcher matcher,
        DecisionValue right) implements NodePlan {
    @Override public EffectKind effectKind() { return EffectKind.PURE; }
}
```

`CompiledNode` 变成 Core 内部绑定容器，只保存运行元数据、已注册执行器和该执行器生成的 `NodePlan`。它不再复制 Definition，也不再公开 raw `Object`。

### 4. 注册时完成类型校验

`NodeExecutorRegistry` 使用 `Map<NodeTypeId, RegisteredNodeExecutor<?, ?>>`。构建注册表时必须拒绝：

- 重复 NodeTypeId；
- 非法或保留命名空间；
- `configVersion < 1`；
- 空 config/plan class；
- 同一个实现声明互相不一致的 provider 信息。

Compiler 根据 type 取得 binding，在 compile 边界用 `configType().isInstance(config)` 校验一次。运行期再用 `planType().isInstance(plan)` 守卫内部不变量。类型错误使用引擎错误 `OR-ENGINE-PLAN-TYPE`，不得应用业务 FailPolicy。

### 5. 聚合器使用同一模型

```java
public interface AggregationConfig {}
public interface AggregationPlan {}

public record AggregationDefinition(
        AggregatorId id,
        int configVersion,
        AggregationConfig config) {}

public interface DecisionAggregator<
        C extends AggregationConfig,
        P extends AggregationPlan> {
    AggregatorId id();
    int configVersion();
    Class<C> configType();
    Class<P> planType();
    PluginDescriptor provider();
    void validate(C config, ValidationContext context);
    P compile(C config, CompilationContext context);
    AggregateOutcome aggregate(AggregationInput input, P plan);
}
```

PRIORITY 和 FIRST_TERMINAL 使用空配置 `NoAggregationConfig`。未来 ScoreThreshold 使用明确的 `ScoreThresholdConfig`，禁止从 flow metadata 读取魔法 key。

### 6. JSON 绑定位于基础设施模块

Core 不引入 Jackson 注解。`FlowDefinitionJsonCodec` 从已注册 executor/aggregator 读取 type → config class 映射：

1. 先读取 `schemaVersion`、节点 `type/configVersion` 与聚合器 ID；
2. 未注册 type 立即返回 `OR-DEF-UNKNOWN-NODE-TYPE`；
3. configVersion 与当前 executor 不一致时返回 `OR-DEF-CONFIG-VERSION`；
4. 使用注册的 config class 严格反序列化 `config`；
5. 未知字段必须失败，不允许静默忽略拼写错误。

M1.6 暂时保留 codec 在 `openrule-spring`。M2d 抽取为不依赖 Spring 的 `openrule-codec-json`，供决策包和命令行工具复用。

## 被拒绝的方案

### 继续扩展枚举

实现简单，但插件和 EE 必须修改 Core，直接破坏模块边界。

### `Map<String, Object> config`

避免新增配置类，但把校验、IDE 重构和编译期类型检查全部推迟到运行时，不适合作为 1.0 契约。

### 为每种节点保留一个可空字段

Jackson 使用方便，但 `NodeDefinition` 会无限增长，Core 必须认识企业版节点，且非法的多配置组合很难阻止。

### 公开 raw `Object` artifact

异构计划确实需要动态分派，但 raw `Object` 不应成为 SPI。executor-owned `NodePlan` 配合 plan class 守卫，在异构和类型安全之间建立明确边界。

## 兼容与迁移

Java API 在 1.0 前直接替换，不保留 `NodeType`、`AggregatePolicy`、旧 `NodeExecutor` 和旧 `CompiledNode` 适配层。

Definition JSON 必须兼容已有数据：缺少 `schemaVersion` 视为 v1，经 v1→v2 migrator 转成新结构后再严格绑定。读取 JDBC 数据时先校验原始 JSON checksum，再迁移；迁移不得回写旧版本行。

## 结果

- M4、脚本和 EE 可新增类型而不修改 Core；
- 编译热路径不再读取 Definition 或 raw config；
- 决策包可记录精确的 plugin/config/compiler 版本；
- Java API 迁移成本集中在 M1.6，避免在每个后续里程碑重复支付。

## 验收

- 一个测试插件 `test.always-hit` 能仅通过注册执行，不修改 Core 枚举或 switch；
- 重复 ID、错误 config type、错误 plan type、未知 ID 和 configVersion 不匹配均有稳定错误码；
- JSON v1 fixture 可读取，重新序列化只产生 v2；
- Core 主代码依赖树仍只有 JDK；Lombok 若继续使用只能是 `provided` 编译辅助，不得成为运行依赖。
