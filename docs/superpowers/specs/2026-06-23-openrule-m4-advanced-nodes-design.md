# OpenRule M4（高级节点）设计

> 里程碑：M4 · 高级节点执行器
> 模块：`openrule-core`（零 Spring，纯 JUnit 可测）
> 路线 B 次序：M2a →（本文）**M4** → M3 → M2b → M5 → ycr-starter-rule
> 状态：设计已批准，待 writing-plans 出实现计划

---

## 1. 背景与定位

M1 交付了执行内核（Flow/Stage/Node 编排 + NodeExecutor SPI + NodeResult 隔离合并 + `OperatorNodeExecutor` + `PriorityAggregator`），56 个单测全绿。M2a 把内核接入 Spring（三模块 reactor + 端口-适配 + 内存仓储 + REST），正在收尾。

M4 在内核之上补齐**"零编码、配置型"的高级节点**——评分卡、决策表、决策树、规则集——以及配套的**分数阈值聚合器**。这四类节点是规则引擎覆盖 80% 业务规则的主力，且全部是**纯 Java、无外部依赖、无安全面**的逻辑，因此放在 `openrule-core` 内、用纯 JUnit 测试，最契合路线 B"先做安全的纯 Java、把脚本沙箱（M3）押后"的排序。

技术规范 §14 的"M4 高级节点"原本还含 `SubFlowNodeExecutor`。SubFlow 需要加载子流程，而 `FlowLoader` 在 `openrule-spring`，`openrule-core` 不能反向依赖；它还需要递归防护（C10：嵌套 ≤ 5）。这两点使 SubFlow 与其余四类自包含节点性质不同，**故 SubFlow 从 M4 移出**，作为后续独立小里程碑（引入 core `SubFlowResolver` SPI + spring 侧 `FlowLoader` 适配）。

### 1.1 前置依赖（M2a 已完成）

- M2a 已于本仓完成（commit `8656440`：全 reactor 验收通过，`OpenRuleAutoConfiguration` / `OpenRuleService.simulate` / REST 层均就绪）。因此 M4 的 autoconfig 装配 + `simulate` 端到端**不再是"未来项"，可直接落地**。
- 纯 core 的四个执行器 + 聚合器的 TDD 仍**不依赖任何 Spring**，纯 JUnit 即可推进；autoconfig 装配只是在内核 TDD 全绿后追加注册新 bean。

---

## 2. 关键工程决策

| 决策 | 内容 | 理由 |
|------|------|------|
| D1 范围 | M4 = ScoreCard + DecisionTable + DecisionTree + RuleSet + ScoreThresholdAggregator，全落 `openrule-core`。SubFlow 移出 | 四类节点自包含、纯 Java、零新端口；SubFlow 跨 core↔spring 边界，性质不同 |
| D2 模型 additive | `NodeDefinition` 只加 4 个 def 字段，不改既有字段 | 遵守"后续里程碑只加不改"；M1 的 OPERATOR 流程不受影响 |
| D3 共享比较器 | 抽出 `OperatorMatcher`，`OperatorNodeExecutor`/决策表/决策树共用 | 运算符矩阵单一真源；ReDoS 防护（C10 正则 ≤ 512）只实现一次；M1 行为不变 |
| D4 compile 重活前置 | 排序分箱 / 预解析单元格 / 树深校验 / 递归编译内部规则都在 `compile()` 完成 | 贴合 SPI 三阶段语义；`execute()` 只做纯执行，热路径轻 |
| D5 RuleSet 破环 | `RuleSetNodeExecutor` 经 `Supplier<NodeRunner>` 延迟注入 | 打破 `NodeRunner → Registry → RuleSetExecutor` 构造环；不污染 SPI |
| D6 聚合器 SPI 微调 | `DecisionAggregator.aggregate` 增参 `FlowDefinition definition` | 分数阈值在 `flow.metadata`，聚合器原签名看不到 flow；显式增参优于往 `ctx.variables` 塞魔法 key |

---

## 3. 范围与 YAGNI

**做：**
- `SCORECARD`：区间分箱 + 枚举（精确）分箱 + 默认 bin，加权求和（`scoreMode=SUM`），节点级 thresholds 给 decision 建议。
- `DECISION_TABLE`：`hitPolicy ∈ {FIRST, PRIORITY, COLLECT}`，单元格复用运算符。
- `DECISION_TREE`：递归遍历，叶子带 decision，深度上限 20（C10）。
- `RULE_SET`：内部规则 SERIAL 串行，`hitPolicy ∈ {FIRST_HIT, ANY_HIT, ALL_HIT, COLLECT}`，聚合为自身单个 `NodeResult`。
- `ScoreThresholdAggregator`：累积总分 → flow 级阈值映射。

**不做（明确推迟）：**
- `SubFlowNodeExecutor`（独立后续里程碑：core `SubFlowResolver` SPI + spring `FlowLoader` 适配 + 递归 ≤ 5）。
- RuleSet 内部 PARALLEL 执行（M4 仅 SERIAL）。
- RuleSet 内部规则之间的 output 链式传递（语义见 §7.4）。
- ScoreCard 的 WOE/IV、分段评分、`scoreMode=WEIGHTED_AVG` 等高级模式（仅 `SUM`）。
- 脚本类节点（`SCRIPT_*`）、`JAVA_NATIVE`、`PYTHON` —— 属 M3/M5。

---

## 4. 模型新增（`openrule-core/.../definition`）

`NodeDefinition` 新增 4 个字段（与现有 `operatorDef` 同构，运行时按 `nodeType` 取其一；新增 `@NoArgsConstructor/@AllArgsConstructor` 已由 M2a Task4 引入，Jackson 可反序列化）：

```java
private ScoreCardDef     scoreCardDef;      // SCORECARD
private DecisionTableDef decisionTableDef;  // DECISION_TABLE
private DecisionTreeDef  decisionTreeDef;   // DECISION_TREE
private RuleSetDef        ruleSetDef;       // RULE_SET
```

### 4.1 `defs/ScoreCardDef`

```java
@Data
public class ScoreCardDef {
    private int    baseScore;                 // 基础分
    private String scoreMode = "SUM";         // M4 仅 SUM
    private List<Attribute> attributes;
    private Thresholds thresholds;            // 可空：null 时不给节点级 decision 建议

    @Data public static class Attribute {
        private String    featureKey;         // "fact.buyer.level" / "var.x"，复用 OperatorMatcher.resolveValue
        private double     weight = 1.0;
        private List<Bin>  bins;
    }
    // Bin 三选一：区间 [min, max)（左闭右开） | 精确 match | defaultBin 兜底
    @Data public static class Bin {
        private Object     match;             // 精确匹配值（字符串/枚举/数字）
        private BigDecimal min;               // 区间下界（含）
        private BigDecimal max;               // 区间上界（不含）
        private boolean    defaultBin;        // 兜底：以上都不命中时取它
        private int        score;
    }
    @Data public static class Thresholds {    // 节点级：本评分卡分数 → decision 建议
        private Integer review;               // score >= review → REVIEW
        private Integer reject;               // score >= reject → REJECT（优先于 review）
    }
}
```

匹配优先级（compile 时按此排好查找顺序）：**精确 `match` > 区间 `[min,max)` > `defaultBin`**。同一属性内命中第一个即停。

### 4.2 `defs/DecisionTableDef`

```java
@Data
public class DecisionTableDef {
    private String    hitPolicy = "FIRST";    // FIRST | PRIORITY | COLLECT
    private List<Row> rows;

    @Data public static class Row {
        private int        priority;          // PRIORITY 用：值大者优先
        private List<Cell> when;              // 行内多个条件 AND
        private Map<String,Object> outputs;   // 命中后并入 NodeResult.outputs
        private Decision   decision;          // 命中行的 decision 建议（可空）
        private int        score;             // 命中行的评分贡献
        private String     reason;
    }
    @Data public static class Cell {          // 复用运算符
        private String leftFact;              // fact./var. 引用
        private String operator;              // OperatorMatcher.SUPPORTED 之一
        private Object rightValue;
    }
}
```

hitPolicy 语义：
- `FIRST`：按 row 顺序取**第一条**全 Cell 命中的行。
- `PRIORITY`：所有命中行中取 `priority` 最大者；并列取先出现者。
- `COLLECT`：所有命中行都生效——`decision` 取最高风险，`score` 求和，`outputs` 按行顺序合并（后者覆盖），`details.hitRows` 记录全部命中行。

### 4.3 `defs/DecisionTreeDef`

```java
@Data
public class DecisionTreeDef {
    private TreeNode root;

    @Data public static class TreeNode {
        private DecisionTableDef.Cell condition;  // 内部节点：判定条件（复用 Cell/运算符）
        private List<Branch> branches;            // 内部节点：condition 为 true/false 走对应分支
        // 叶子节点（branches 为空）：
        private Decision decision;
        private int      score;
        private Map<String,Object> outputs;
        private String   reason;
    }
    @Data public static class Branch {
        private boolean onTrue;                   // condition 结果匹配该值时进入
        private TreeNode next;
    }
}
```

遍历：从 root 起，内部节点用 `OperatorMatcher.match` 求 condition 真值，选 `onTrue` 匹配的分支前进，直到叶子；叶子的 `decision/score/outputs` 即本节点产出。深度上限 20（C10），compile 时静态校验，超限抛 `FlowValidationException`。`details.path` 记录完整路径（如 `root->amount>5w:true->leaf:REJECT`）。

### 4.4 `defs/RuleSetDef`

```java
@Data
public class RuleSetDef {
    private String hitPolicy = "ANY_HIT";   // FIRST_HIT | ANY_HIT | ALL_HIT | COLLECT
    private List<NodeDefinition> rules;     // 内部规则（M4 仅 SERIAL 串行）
}
```

---

## 5. 共享运算符比较器 `OperatorMatcher`（D3 重构）

新建 `io.openrule.core.executor.OperatorMatcher`，把 M1 `OperatorNodeExecutor` 的比较逻辑搬过来：

```java
public final class OperatorMatcher {
    public static final int MAX_REGEX_LEN = 512;                 // C10
    public static final Set<String> SUPPORTED = Set.of(/* GT...REGEX 全集 */);

    public static boolean match(Object left, String op, Object right) { /* 原 compare 开关 */ }
    public static Object   resolveValue(String ref, DecisionContext ctx) { /* fact./var./字面量 */ }
    // equalsLoose / between / toBigDecimal / toCollection / compileRegex 私有 helper
    private OperatorMatcher() {}
}
```

`OperatorNodeExecutor.execute/validate` 改为委托 `OperatorMatcher.match / resolveValue / SUPPORTED`。决策表 Cell、决策树 condition 全部经 `OperatorMatcher`。**纯重构，行为不变，M1 的 56 测试保持绿**（回归即验证）。

---

## 6. 编译产物（D4）

每执行器在 `compile()` 产出私有 compiled 类型，塞入 `CompiledNode.compiledArtifact`，`execute()` 读取强转：

| 执行器 | compile 产物 | compile 干的活 |
|--------|--------------|----------------|
| ScoreCard | `CompiledScoreCard` | 每属性 bins 按"精确/区间/默认"分类并对区间排序 → O(logN) 或顺序查找 |
| DecisionTable | `CompiledDecisionTable` | 各行各 Cell 校验运算符；PRIORITY 时按 priority 预排序 |
| DecisionTree | `CompiledDecisionTree` | 递归校验结构 + 深度 ≤ 20；预解析各 condition |
| RuleSet | `CompiledRuleSet` | 递归 `registry.getRequired(type).compile(rule)` 得内部 `List<CompiledNode>` |

> RuleSet 的 compile 需要 `NodeExecutorRegistry`（用于编译内部规则）。registry 在构造时已存在，无环；有环的只是运行期的 `NodeRunner`（见 §7）。

---

## 7. 执行器设计要点

### 7.1 ScoreCardNodeExecutor
- 遍历 attributes：`resolveValue(featureKey)` 取特征值 → 在该属性 bins 内按"精确 > 区间 > 默认"找第一个命中 bin → `bin.score * weight` 累加到 `baseScore`。
- 总分写 `NodeResult.score`；`outputs` 写 `"scorecard." + nodeId + ".score"`（供后续 OPERATOR/决策表引用）；`details.attributeScores` 记录每属性命中分。
- 若配 `thresholds`：`score≥reject→decision=REJECT`，否则 `≥review→REVIEW`，命中即 `hit=true` 并给 reason。
- 特征缺失（resolveValue 返回 null）且无 `defaultBin` → 该属性记 0 分（不抛），`details` 标注 `missing`。

### 7.2 DecisionTableNodeExecutor
- 按 §4.2 hitPolicy 匹配；行内多 Cell 取 AND（全 true 才算命中行），每 Cell 经 `OperatorMatcher.match`。
- 命中后：`decision`/`score`/`outputs`/`reason` 按 hitPolicy 组合（见 §4.2）；`details.hitRows` 记录命中行索引。
- 无命中行：`hit=false`，`decision=null`，`score=0`。

### 7.3 DecisionTreeNodeExecutor
- 从 root 递归到叶子（§4.3）；叶子产出 `decision/score/outputs`，`hit=(decision!=null)`。
- `details.path` 记录路径字符串。深度防护已在 compile 静态保证；execute 不再越界。

### 7.4 RuleSetNodeExecutor（D5 破环 + 内部语义）
- 构造：`RuleSetNodeExecutor(Supplier<NodeRunner> nodeRunnerRef)`。运行期 `nodeRunnerRef.get()` 取 `NodeRunner`。
  - Spring autoconfig：`new RuleSetNodeExecutor(() -> ctx.getBean(NodeRunner.class))`（或 `ObjectProvider::getObject`）。
  - 纯 core 测试：`AtomicReference<NodeRunner>` 持有，registry/runner 装好后 `set`。
- execute：对 `CompiledRuleSet` 内部每个 `CompiledNode` 调 `nodeRunner.run(ctx, inner)`（复用统一超时/FailPolicy/计时），收集内部 `NodeResult` 列表。
- 内部语义（M4 定版）：
  - 内部规则**读同一 ctx 快照**，各自产 `NodeResult`；**内部规则之间不做 output 链式传递**（保证 C1 安全 + 确定性）。需要链式请用 Stage 或后续 SubFlow。
  - 按 hitPolicy 聚合为 RuleSet 自身单个 `NodeResult`：
    - `FIRST_HIT`：取第一条命中规则，`hit=true`，decision/reason 取它；其后规则仍执行但不改变 RuleSet 结论（也可短路，M4 取"全跑、取首命中"以保 details 完整）。
    - `ANY_HIT`：任一内部命中则 `hit=true`。
    - `ALL_HIT`：全部内部命中才 `hit=true`。
    - `COLLECT`：`hit=任一命中`，收集全部命中规则。
  - 聚合字段：`decision` = 命中规则中**最高风险**（`riskierThan`）；`score` = 内部规则 score 求和；`outputs` = 命中规则 outputs 按内部顺序合并；`details.ruleResults` = 内部结果摘要。
- C1：RuleSet 自身若处于 PARALLEL Stage，只读 ctx、只返回自己的 `NodeResult`，不写 ctx —— 内部 `nodeRunner.run` 的执行器同样不写 ctx，安全。

---

## 8. ScoreThresholdAggregator + SPI 微调（D6）

### 8.1 SPI 增参（受控演进）
```java
public interface DecisionAggregator {
    AggregatePolicy supportPolicy();
    AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx, FlowDefinition definition);
}
```
- `PriorityAggregator`：方法签名加 `definition` 参数，方法体不变（忽略）。
- `FlowExecutor`：聚合调用处改为 `aggregator.aggregate(ctx.getNodeResults(), ctx, flow.getDefinition())`。
- 影响面：`DecisionAggregator` 接口 + `PriorityAggregator` + `FlowExecutor` 1 行 + 这两者的现有测试签名更新。M1 行为不变。

### 8.2 ScoreThresholdAggregator
```java
public class ScoreThresholdAggregator implements DecisionAggregator {
    public AggregatePolicy supportPolicy() { return AggregatePolicy.SCORE_THRESHOLD; }
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx, FlowDefinition def) {
        int total = results.stream().mapToInt(NodeResult::getScore).sum();
        // thresholds 从 def.getMetadata() 读：{ "review": 60, "reject": 85 }
        Decision d = total >= reject ? REJECT : total >= review ? REVIEW : PASS;
        // hitNodes = 命中节点；reason 形如 "总分 72 ≥ 人审阈值 60"
    }
}
```
- 阈值来源：`def.getMetadata().get("review"/"reject")`；缺省时（无 metadata）退化为 `PASS` 并在 reason 标注"未配置阈值"。
- 注册：M2a 的 autoconfig `Map<AggregatePolicy,DecisionAggregator>` 自动收集，无需改装配代码（仅新增 bean）。

---

## 9. 数据流（评分卡 + 决策表组合示例）

```
facts → Stage(SERIAL) 硬规则(OPERATOR) → Stage(PARALLEL) [ScoreCard, DecisionTable]
      → 合并线程按 order 合并各 NodeResult.outputs（含 scorecard.*.score）
      → FlowExecutor 调 ScoreThresholdAggregator(results, ctx, flowDef)
      → totalScore 映射 finalDecision（唯一写入点，C3）
      → FlowResult
```
关键不变量沿用 M1：并行节点不写 ctx（C1）；合并按 order 单线程（C2）；finalDecision 仅聚合器写（C3）；facts 不可变（C7）；节点执行唯一经 NodeRunner（C8）。

---

## 10. 错误处理
- 配置错误（保存期）：`validate()` 抛 `FlowValidationException`——评分卡属性/分箱为空、决策表运算符不支持、决策树深度超 20、RuleSet 内部规则为空等。
- 运行期异常：执行器**不吞异常**直接抛，由 `NodeRunner` 统一按节点 `FailPolicy` 处理（C8）。RuleSet 内部规则的异常由内层 `NodeRunner.run` 按各内部规则自己的 FailPolicy 处理，RuleSet 不额外吞。
- 特征/变量缺失：评分卡按"0 分 + details 标注"容错；决策表/决策树的 `OperatorMatcher` 对 null 的处理沿用 M1 语义（如数值运算遇 null 抛错 → 经 FailPolicy）。

---

## 11. 测试（TDD，纯 JUnit in core；M2a 合并后补端到端）

| 测试类 | 覆盖 |
|--------|------|
| `OperatorMatcherTest` | 迁移 M1 运算符矩阵；保证重构后全绿（回归基线） |
| `ScoreCardNodeExecutorTest` | 区间分箱 / 枚举分箱 / 默认 bin / 加权 / 特征缺失容错 / thresholds→节点 decision / score 写 outputs |
| `DecisionTableNodeExecutorTest` | FIRST / PRIORITY / COLLECT × 命中/未命中；多 Cell AND；运算符抽样 |
| `DecisionTreeNodeExecutorTest` | 命中路径 details / 叶子 decision / compile 期深度超限抛错 |
| `RuleSetNodeExecutorTest` | 四 hitPolicy / 内部规则异常走各自 FailPolicy / 聚合 score+decision / Supplier 破环装配 |
| `ScoreThresholdAggregatorTest` | reject/review/PASS 三段边界 + 无阈值退化 |
| `PriorityAggregatorTest`（更新） | SPI 增参后签名更新，断言不变 |
| `M4Demo` | 评分卡+决策表+决策树+RuleSet 组合流程纯 Java main 跑通 |
| `AdvancedNodesAutoConfigTest` | （M2a 已就绪）上下文装配断言 5 个新 bean 存在 + 一条组合 simulate 端到端 |

---

## 12. 验收标准
- [ ] `NodeDefinition` 新增 4 def 字段，OPERATOR 既有流程不受影响。
- [ ] `OperatorMatcher` 抽出，`OperatorNodeExecutor` 委托，M1 的 56 测试全绿。
- [ ] 四个执行器 + `ScoreThresholdAggregator` 实现并各自单测通过。
- [ ] `DecisionAggregator` SPI 增参，`PriorityAggregator`/`FlowExecutor` 适配，旧测试更新后全绿。
- [ ] `M4Demo` 组合流程跑通；全模块 `mvn test` 绿（JDK 21）。
- [ ] 新 5 bean 自动装配（autoconfig）+ 组合 simulate 端到端通过。

---

## 13. 未来（非 M4）
1. **SubFlow**：core 新增 `SubFlowResolver` SPI（`CompiledFlow resolve(String flowId, Integer version)`），`SubFlowNodeExecutor` 依赖该端口；spring 侧用 `FlowLoader` 适配；递归防护 C10（加载链查重 + 嵌套 ≤ 5）。
2. **RuleSet PARALLEL 内部**：复用 NodeResult 隔离合并模型。
3. **ScoreCard 高级评分**：`scoreMode=WEIGHTED_AVG`、分段/WOE。
4. M3（脚本沙箱）、M5（Python + 可观测）按路线 B 后续推进。

---

*OpenRule M4 · 高级节点（纯 core）· 设计稿 · 2026-06-23*
