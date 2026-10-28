# ADR-0002：执行器只读输入、内部状态与确定性值语义

> 状态：Accepted
> 日期：2026-08-20
> 决策范围：M1.6 Core 执行 API、Definition Schema v2 和结果模型

## 背景

当前 `NodeExecutor.execute` 接收公开可写的 `DecisionContext`。并行节点不得调用 `putVariable`、`stop` 和 `addNodeResult` 只是一条注释，第三方执行器无法被结构性约束。`ConcurrentHashMap` 与 `CopyOnWriteArrayList` 因此被用来补偿过宽的访问权限。

当前 facts 允许任意 Java 对象；缺失路径和显式 null 都返回 null；数字与字符串会隐式互转；结果集合只做浅层封装。这些语义不能可靠序列化、签名或回放。

## 决策

### 1. 对外入口是 ExecutionRequest

```java
public record ExecutionRequest(
        String requestId,
        String bizId,
        DecisionObject facts,
        ExecutionPurpose purpose,
        Duration timeout) {}

public enum ExecutionPurpose {
    LIVE,
    SIMULATE,
    BACKTEST
}
```

`flowId` 和 flow version 只来自 `CompiledFlowPlan`，不再由调用方在 Context 中重复传入。Engine 入口调整为：

```java
FlowResult execute(CompiledFlowPlan plan, ExecutionRequest request);
FlowResult execute(FlowDefinition definition, ExecutionRequest request);
```

requestId、bizId 必须非空且长度不超过 128。timeout 为空时使用 Engine 默认值；不得超过 Engine 配置上限。

### 2. SPI 只获得不可写快照

```java
public interface NodeExecutionInput {
    String requestId();
    String bizId();
    ExecutionPurpose purpose();
    DecisionObject facts();
    DecisionObject variables();
    ValueLookup resolve(ValueRef reference);
    Deadline deadline();
    Clock clock();
    CancellationToken cancellation();
}
```

接口不提供写 variables、stop、追加结果或设置最终决策的方法。

- 串行 Stage：每个节点开始前创建当前 variables 的不可变快照；前一节点 outputs 已合并，因此下一节点可见。
- 并行 Stage：进入 Stage 时只创建一次 variables 快照，所有并行节点读取同一快照。
- 节点只能通过 `NodeResult.outputs` 提交写入意图。
- Stage 合并线程按定义 order 合并 outputs，最后写入内部状态。

### 3. ExecutionState 是包内单线程状态

```java
final class ExecutionState {
    private final LinkedHashMap<String, DecisionValue> variables;
    private final ArrayList<NodeResult> nodeResults;
    private boolean stopped;
}
```

`ExecutionState` 位于 `io.openrule.core.runtime.internal`，不属于公共 API。只有 Flow/Stage 调度线程可写，因此不再使用 `ConcurrentHashMap` 和 `CopyOnWriteArrayList`。并行 worker 永远拿不到该对象。

聚合器接收不可变 `AggregationInput`，不再接收 Context：

```java
public record AggregationInput(
        FlowIdentity flow,
        ExecutionRequest request,
        List<NodeResult> nodeResults,
        DecisionObject finalVariables) {}
```

### 4. DecisionValue 是唯一跨边界值类型

```java
public sealed interface DecisionValue
        permits DecisionNull, DecisionBoolean, DecisionNumber,
                DecisionString, DecisionList, DecisionObject {}

public enum DecisionNull implements DecisionValue { INSTANCE }
public record DecisionBoolean(boolean value) implements DecisionValue {}
public record DecisionNumber(BigDecimal value) implements DecisionValue {}
public record DecisionString(String value) implements DecisionValue {}
public record DecisionList(List<DecisionValue> values) implements DecisionValue {}
public record DecisionObject(Map<String, DecisionValue> values) implements DecisionValue {}
```

所有构造器建立深度不可变快照并保持插入顺序。Map key 必须是非空 String。支持的 Java 输入转换为：

| Java 输入 | DecisionValue | 规则 |
|---|---|---|
| `null` | `DecisionNull` | 与 missing 不同 |
| `Boolean` | `DecisionBoolean` | 不接受字符串布尔值 |
| 整数/小数 Number | `DecisionNumber` | 通过十进制字符串构造 BigDecimal |
| `Float`/`Double` | `DecisionNumber` | NaN 和 Infinity 拒绝 |
| `CharSequence`/`Enum` | `DecisionString` | Enum 使用 `name()` |
| Map | `DecisionObject` | key 必须为 String |
| Collection/array | `DecisionList` | 保持原始顺序；Set 拒绝，避免无序 |
| 其他对象 | 不支持 | 返回 `OR-INPUT-UNSUPPORTED-VALUE` |

时间、日期、UUID 和二进制数据不做隐式类型推断。调用方使用规范字符串；未来类型化 schema 可显式引入相应值类型。

### 5. 缺失值与 null 明确区分

```java
public record ValueLookup(boolean present, DecisionValue value) {
    public static ValueLookup missing() { return new ValueLookup(false, null); }
    public static ValueLookup present(DecisionValue value) {
        return new ValueLookup(true, Objects.requireNonNull(value));
    }
}
```

路径采用 RFC 6901 JSON Pointer，不再使用含义不完整的点路径：

```java
public record ValueRef(ValueSource source, String pointer) {}
public enum ValueSource { FACT, VARIABLE }
```

`/order/amount` 可以无歧义访问嵌套值，`~0` 和 `~1` 按 RFC 6901 解码。空 pointer 表示整个根对象。数组索引必须为非负十进制整数。

### 6. 内置比较不做隐式类型转换

| 运算 | 支持类型 | 语义 |
|---|---|---|
| `eq/ne` | 任意 DecisionValue | 同类型比较；数字使用 BigDecimal `compareTo` |
| `gt/gte/lt/lte/between` | Number | 其他类型返回节点执行错误 |
| `contains/not-contains` | String/String 或 List/任意 | 字符串包含或列表类型化相等 |
| `starts-with/ends-with` | String/String | 大小写敏感，Locale 不参与 |
| `in/not-in` | 任意/List | 使用类型化相等 |
| `is-null/not-null` | present 值 | missing 不等于 null |
| `is-missing/is-present` | ValueLookup | 只判断路径存在性 |
| `regex` | 不作为 M1.6 默认运算 | 见下文 |

Java `Pattern` 的长度限制不能从根本上阻止 ReDoS，且线程 interrupt 不能可靠停止灾难性回溯。M1.6 从默认 Operator 中移除 `regex`；后续由可选的线性时间正则模块提供，并通过 Matcher SPI 注册。v1 定义若含 `REGEX`，迁移时返回 `OR-DEF-UNSUPPORTED-REGEX`，不得悄悄改变行为。

### 7. 结果模型不可变且状态明确

```java
public enum NodeStatus {
    SUCCEEDED,
    SKIPPED_STOPPED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}

public record NodeFailure(
        String code,
        String message,
        FailureKind kind) {}

public record NodeResult(
        NodeIdentity node,
        NodeStatus status,
        boolean hit,
        Decision decision,
        int score,
        boolean stop,
        String reason,
        DecisionObject outputs,
        DecisionObject details,
        NodeFailure failure,
        Duration elapsed) {}
```

`success`、`skipped` 和 `errorCode/errorMessage` 旧字段由 status/failure 取代。FlowResult、StageResult、AggregateOutcome 都使用 record 和不可变集合。`hitNodes` 从最终 nodeResults 推导或构造时复制，不能与 nodeResults 产生不一致。

### 8. 确定性边界

- 运行时耗时使用单调时钟；业务时间只通过注入的 `Clock` 读取。
- Core 默认 `Clock.systemUTC()`，测试必须可注入固定 Clock。
- Core 不向节点提供隐式随机数生成器。
- 任意外部读取必须通过后续 Evidence-aware port；直接访问网络/数据库的插件不能声明为可回放。
- Definition 顺序、Map 插入顺序和并行输出冲突都采用确定规则，不依赖线程完成顺序。

## 被拒绝的方案

### 保留 DecisionContext，仅把写方法改成 package-private

第三方 SPI 与运行时位于不同 package，虽然能阻止直接写，但仍把请求、状态、结果和聚合职责混在同一对象中，也无法定义并行快照语义。

### 继续传递 `Map<String, Object>`

短期代码少，但无法定义数字、null、集合顺序、序列化和签名语义，决策包与回放都会建立在不稳定输入上。

### 所有内容深拷贝但仍返回 Object

只能解决修改泄漏，不能解决缺失/null、隐式转换和可序列化范围。

## 兼容与迁移

- Java API 直接迁移，不保留 DecisionContext 执行入口。
- API/Spring 层继续接收 `Map<String, Object>`，但在进入 Core 前调用 `DecisionValues.fromJava`，非法值返回 400 与稳定错误码。
- v1 的 `fact.order.amount` 转换为 `{source: FACT, pointer: /order/amount}`；`var.x` 转为 VARIABLE。
- v1 Operator 名称转为 v2 小写 kebab-case；不支持的隐式字符串/数字比较必须在迁移或 compile 阶段失败。

## 验收

- 自定义 NodeExecutor 无法取得任何状态写方法；
- 并行节点读取到完全相同的变量快照；
- 源 Map、嵌套集合和结果集合在执行前后均不能修改引擎数据；
- missing、null、数字 1/1.0、字符串 "1"、非有限浮点、Set、非 String key 均有固定测试；
- CompiledFlowPlan 与请求之间不再存在 flowId 不一致的构造可能；
- 结果序列化两次得到字节等价的 canonical 内容。
