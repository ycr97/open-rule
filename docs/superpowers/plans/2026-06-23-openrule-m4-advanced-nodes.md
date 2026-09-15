# OpenRule M4（高级节点）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `openrule-core` 内补齐四个纯 Java 高级节点执行器（评分卡 / 决策表 / 决策树 / 规则集）+ 分数阈值聚合器，并接入 M2a 的 Spring 自动装配。

**Architecture:** 全部落 `openrule-core`，零 Spring、纯 JUnit 可测；运算符比较逻辑抽成共享 `OperatorMatcher`；各执行器在 `compile()` 把重活前置，`execute()` 只做纯执行；`RuleSetNodeExecutor` 用 `Supplier<NodeRunner>` 延迟注入打破构造环；`DecisionAggregator` SPI 增 `FlowDefinition` 参以便分数阈值聚合读取 `metadata`。

**Tech Stack:** Java 21（虚拟线程）· Maven 多模块 reactor · Lombok · JUnit 5 · AssertJ · Spring Boot 3.3.5（仅 openrule-spring 装配 + ApplicationContextRunner 测试）。

## Global Constraints

- 构建必须用 JDK 21：每条 mvn 命令前置 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`（默认 JDK 是 17，shell 环境不跨 Bash 调用保留）。
- 包名统一 `io.openrule.*`；新代码 `@author ycr`；注释/提交信息用中文；提交**不带** `Co-Authored-By` trailer。
- 模型只加不改（additive）：`NodeDefinition` 只新增 def 字段，不动既有字段，保证 M1 的 OPERATOR 流程不受影响。
- 并发与正确性约束（违反即缺陷）：C1 并行节点禁写 ctx（执行器只读 ctx、只返回 `NodeResult`）；C3 `finalDecision` 仅聚合器写；C7 facts 不可变；C8 节点执行唯一经 `NodeRunner`，执行器不吞异常、直接抛由 `NodeRunner` 应用 FailPolicy；C10 决策树深度 ≤ 20、正则 pattern ≤ 512。
- TDD：先写失败测试 → 跑红 → 最小实现 → 跑绿 → 提交。每个 Task 结束 reactor 可编译、相关测试全绿。
- 前置：M2a 已完成（commit `8656440`），`OpenRuleAutoConfiguration`/`OpenRuleService.simulate`/REST 均就绪。

---

## 文件结构

**openrule-core 新建：**
- `definition/defs/ScoreCardDef.java` · `DecisionTableDef.java` · `DecisionTreeDef.java` · `RuleSetDef.java`
- `executor/OperatorMatcher.java`（从 `OperatorNodeExecutor` 抽出的共享比较器）
- `executor/ScoreCardNodeExecutor.java` · `DecisionTableNodeExecutor.java` · `DecisionTreeNodeExecutor.java` · `RuleSetNodeExecutor.java`
- `aggregate/ScoreThresholdAggregator.java`
- `demo/M4Demo.java`

**openrule-core 修改：**
- `executor/OperatorNodeExecutor.java`（委托 `OperatorMatcher`）
- `definition/NodeDefinition.java`（+4 def 字段，分散在 Task 3–6）
- `spi/DecisionAggregator.java`（aggregate 增 `FlowDefinition` 参）
- `aggregate/PriorityAggregator.java`（加参忽略）
- `runtime/CompiledFlow.java`（+`getDefinition()`）
- `runtime/FlowExecutor.java`（aggregate 调用传 `flow.getDefinition()`）
- `runtime/NodeRunner.java`（+`getRegistry()`，供 RuleSet 编译内部规则）

**openrule-core 测试新建：** `executor/OperatorMatcherTest` · `ScoreCardNodeExecutorTest` · `DecisionTableNodeExecutorTest` · `DecisionTreeNodeExecutorTest` · `RuleSetNodeExecutorTest` · `aggregate/ScoreThresholdAggregatorTest`
**openrule-core 测试修改：** `aggregate/PriorityAggregatorTest`（3 处 aggregate 调用加参）

**openrule-spring 修改：** `autoconfigure/OpenRuleAutoConfiguration.java`（+5 bean）
**openrule-spring 测试新建：** `autoconfigure/AdvancedNodesAutoConfigTest.java`

---

## Task 1: 抽出共享比较器 OperatorMatcher（重构，保持绿）

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/executor/OperatorMatcher.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/executor/OperatorNodeExecutor.java`
- Test: `openrule-core/src/test/java/io/openrule/core/executor/OperatorMatcherTest.java`

**Interfaces:**
- Produces: `OperatorMatcher.match(Object left, String op, Object right) -> boolean`；`OperatorMatcher.resolveValue(String ref, DecisionContext ctx) -> Object`；`OperatorMatcher.SUPPORTED : Set<String>`；`OperatorMatcher.MAX_REGEX_LEN : int`；包内可见 `static BigDecimal OperatorMatcher.toBigDecimal(Object)`（供 ScoreCard 复用）。
- Consumes: `DecisionContext.getFacts().getByPath(String)`、`DecisionContext.variable(String)`、`RuleEngineException`。

- [ ] **Step 1: 写失败测试** `OperatorMatcherTest.java`

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.exception.RuleEngineException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorMatcherTest {

    @Test
    void match_numericAndString() {
        assertThat(OperatorMatcher.match(12800, "GT", 5000)).isTrue();
        assertThat(OperatorMatcher.match("hello", "STARTS_WITH", "he")).isTrue();
        assertThat(OperatorMatcher.match("JP", "IN", List.of("JP", "US"))).isTrue();
        assertThat(OperatorMatcher.match(null, "IS_NULL", null)).isTrue();
    }

    @Test
    void match_regexTooLong_throws() {
        assertThatThrownBy(() -> OperatorMatcher.match("x", "REGEX", "a".repeat(513)))
                .isInstanceOf(RuleEngineException.class).hasMessageContaining("512");
    }

    @Test
    void resolveValue_factVarLiteral() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of("order", Map.of("amount", 100)));
        ctx.putVariable("k", 42);
        assertThat(OperatorMatcher.resolveValue("fact.order.amount", ctx)).isEqualTo(100);
        assertThat(OperatorMatcher.resolveValue("var.k", ctx)).isEqualTo(42);
        assertThat(OperatorMatcher.resolveValue("literal", ctx)).isEqualTo("literal");
    }

    @Test
    void supported_containsFullSet() {
        assertThat(OperatorMatcher.SUPPORTED).contains("GT", "BETWEEN", "REGEX", "IN", "NOT_NULL");
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=OperatorMatcherTest test`
Expected: 编译失败，`cannot find symbol: OperatorMatcher`。

- [ ] **Step 3: 创建 `OperatorMatcher.java`**

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.exception.RuleEngineException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运算符比较器（共享）：OPERATOR / 决策表 / 决策树复用。
 * 运算符矩阵单一真源 + ReDoS 防护（C10：正则 pattern ≤ 512）。
 *
 * @author ycr
 */
public final class OperatorMatcher {

    public static final int MAX_REGEX_LEN = 512;
    public static final Set<String> SUPPORTED = Set.of(
            "GT", "GTE", "LT", "LTE", "EQ", "NE", "BETWEEN",
            "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH",
            "IN", "NOT_IN", "IS_NULL", "NOT_NULL", "REGEX");

    private OperatorMatcher() {}

    /** "fact.x.y" → facts 点路径；"var.k" → variables；其余视为字面量。 */
    public static Object resolveValue(String ref, DecisionContext ctx) {
        if (ref == null) return null;
        if (ref.startsWith("fact.")) return ctx.getFacts().getByPath(ref.substring(5));
        if (ref.startsWith("var."))  return ctx.variable(ref.substring(4));
        return ref;
    }

    public static boolean match(Object left, String op, Object right) {
        return switch (op) {
            case "GT"  -> toBigDecimal(left).compareTo(toBigDecimal(right)) > 0;
            case "GTE" -> toBigDecimal(left).compareTo(toBigDecimal(right)) >= 0;
            case "LT"  -> toBigDecimal(left).compareTo(toBigDecimal(right)) < 0;
            case "LTE" -> toBigDecimal(left).compareTo(toBigDecimal(right)) <= 0;
            case "EQ"  -> equalsLoose(left, right);
            case "NE"  -> !equalsLoose(left, right);
            case "BETWEEN" -> between(left, right);
            case "CONTAINS"     -> String.valueOf(left).contains(String.valueOf(right));
            case "NOT_CONTAINS" -> !String.valueOf(left).contains(String.valueOf(right));
            case "STARTS_WITH"  -> String.valueOf(left).startsWith(String.valueOf(right));
            case "ENDS_WITH"    -> String.valueOf(left).endsWith(String.valueOf(right));
            case "IN"     -> toCollection(right).stream().anyMatch(o -> equalsLoose(o, left));
            case "NOT_IN" -> toCollection(right).stream().noneMatch(o -> equalsLoose(o, left));
            case "IS_NULL"  -> left == null;
            case "NOT_NULL" -> left != null;
            case "REGEX" -> left != null
                    && compileRegex(String.valueOf(right)).matcher(String.valueOf(left)).matches();
            default -> throw new RuleEngineException("Unsupported operator: " + op);
        };
    }

    private static boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
        }
        return a.toString().equals(b.toString());
    }

    private static boolean between(Object left, Object right) {
        List<?> bounds = toCollection(right).stream().toList();
        if (bounds.size() != 2) {
            throw new RuleEngineException("BETWEEN 需要 [lo, hi] 两个边界");
        }
        BigDecimal l = toBigDecimal(left);
        return l.compareTo(toBigDecimal(bounds.get(0))) >= 0
                && l.compareTo(toBigDecimal(bounds.get(1))) <= 0;
    }

    /** 包内可见：ScoreCard 区间分箱复用数值转换。 */
    static BigDecimal toBigDecimal(Object v) {
        if (v == null) throw new RuleEngineException("数值运算遇到 null");
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

    private static Collection<?> toCollection(Object v) {
        if (v instanceof Collection<?> c) return c;
        if (v instanceof Object[] a) return Arrays.asList(a);
        throw new RuleEngineException("IN/BETWEEN 期望集合类型，实际: " + v);
    }

    private static Pattern compileRegex(String pattern) {
        if (pattern.length() > MAX_REGEX_LEN) {
            throw new RuleEngineException("正则过长(>512)，疑似 ReDoS: len=" + pattern.length());
        }
        return Pattern.compile(pattern);
    }
}
```

- [ ] **Step 4: 改 `OperatorNodeExecutor.java` 委托 OperatorMatcher**

整体替换为（删除原私有 compare/resolveValue/equalsLoose/between/toBigDecimal/toCollection/compileRegex/SUPPORTED/MAX_REGEX_LEN）：

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.util.Map;

/**
 * 内置运算符执行器：零编码字段比较。比较逻辑委托共享 {@link OperatorMatcher}。
 *
 * @author ycr
 */
public class OperatorNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() { return NodeType.OPERATOR; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        OperatorDef def = node.getOperatorDef();
        if (def == null || def.getLeftFact() == null || def.getOperator() == null) {
            throw new FlowValidationException("OPERATOR 节点配置不完整: " + node.getNodeId());
        }
        if (!OperatorMatcher.SUPPORTED.contains(def.getOperator())) {
            throw new FlowValidationException("不支持的运算符 " + def.getOperator()
                    + " @ " + node.getNodeId());
        }
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        NodeDefinition node = compiled.getDefinition();
        OperatorDef def = node.getOperatorDef();
        long start = System.currentTimeMillis();

        Object leftValue = OperatorMatcher.resolveValue(def.getLeftFact(), ctx);
        boolean hit = OperatorMatcher.match(leftValue, def.getOperator(), def.getRightValue());

        return NodeResult.builder()
                .nodeId(node.getNodeId())
                .nodeName(node.getNodeName())
                .nodeType(NodeType.OPERATOR)
                .hit(hit).success(true)
                .reason(hit ? buildHitReason(def, leftValue) : null)
                .details(Map.of(
                        "leftValue", String.valueOf(leftValue),
                        "operator", String.valueOf(def.getOperator()),
                        "rightValue", String.valueOf(def.getRightValue())))
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private String buildHitReason(OperatorDef def, Object leftValue) {
        return def.getLeftFact() + "(" + leftValue + ") " + def.getOperator()
                + " " + def.getRightValue();
    }
}
```

- [ ] **Step 5: 跑测试，确认全绿（含 M1 回归）**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest='OperatorMatcherTest,OperatorNodeExecutorTest' test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 两个测试类全绿，`BUILD SUCCESS`。

- [ ] **Step 6: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/executor/OperatorMatcher.java \
        openrule-core/src/main/java/io/openrule/core/executor/OperatorNodeExecutor.java \
        openrule-core/src/test/java/io/openrule/core/executor/OperatorMatcherTest.java
git commit -m "refactor(core): 抽出共享 OperatorMatcher，OperatorNodeExecutor 委托（M1 回归绿）"
```

---

## Task 2: DecisionAggregator SPI 增参 + CompiledFlow.getDefinition（重构，保持绿）

**Files:**
- Modify: `openrule-core/src/main/java/io/openrule/core/spi/DecisionAggregator.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/aggregate/PriorityAggregator.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/runtime/CompiledFlow.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/runtime/FlowExecutor.java:48`
- Test: `openrule-core/src/test/java/io/openrule/core/aggregate/PriorityAggregatorTest.java`（更新 3 处调用）

**Interfaces:**
- Produces: `DecisionAggregator.aggregate(List<NodeResult> results, DecisionContext ctx, FlowDefinition definition) -> AggregateOutcome`；`CompiledFlow.getDefinition() -> FlowDefinition`。
- Consumes: `CompiledFlow` 内部 `definition` 字段；`FlowDefinition`。

- [ ] **Step 1: 改 SPI 接口 `DecisionAggregator.java`**

```java
package io.openrule.core.spi;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.result.NodeResult;
import java.util.List;

public interface DecisionAggregator {
    AggregatePolicy supportPolicy();

    /** definition 提供 flow 级配置（如 SCORE_THRESHOLD 的 metadata 阈值）；不需要者忽略。 */
    AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx, FlowDefinition definition);
}
```

- [ ] **Step 2: 改 `PriorityAggregator.java` 签名（加参忽略）**

把方法签名改为带 `definition`，方法体不变。新签名（imports 增 `io.openrule.core.definition.FlowDefinition`）：

```java
    @Override
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx,
                                      io.openrule.core.definition.FlowDefinition definition) {
        // PRIORITY 不依赖 flow 级配置，definition 未使用
        Decision highest = Decision.PASS;
        List<String> hitNodes = new ArrayList<>();
        StringBuilder reason = new StringBuilder();

        for (NodeResult r : results) {
            if (r.isHit()) {
                hitNodes.add(r.getNodeId());
            }
            if (r.getDecision() != null && r.getDecision().riskierThan(highest)) {
                highest = r.getDecision();
                reason.setLength(0);
                reason.append(r.getReason() == null ? "" : r.getReason());
            }
        }
        int totalScore = results.stream().mapToInt(NodeResult::getScore).sum();
        return new AggregateOutcome(highest, reason.toString(), totalScore, hitNodes);
    }
```

- [ ] **Step 3: 给 `CompiledFlow.java` 加 `getDefinition()`**

在 `getStages()` 后新增（`FlowDefinition` 已 import）：

```java
    public FlowDefinition getDefinition()      { return definition; }
```

- [ ] **Step 4: 改 `FlowExecutor.java:48` 传 definition**

把该行：

```java
        AggregateOutcome outcome = aggregator.aggregate(ctx.getNodeResults(), ctx);
```

改为：

```java
        AggregateOutcome outcome = aggregator.aggregate(ctx.getNodeResults(), ctx, flow.getDefinition());
```

- [ ] **Step 5: 更新 `PriorityAggregatorTest.java`（3 处调用加参）**

在类内新增字段（import `io.openrule.core.definition.FlowDefinition`）：

```java
    private final FlowDefinition flow = FlowDefinition.builder().build();
```

把第 29/38/45 行的三处 `agg.aggregate(List.of(...), ctx)` 末尾参数补成 `..., ctx, flow)`：

```java
        AggregateOutcome out = agg.aggregate(List.of(
                hit("a", Decision.REVIEW, 0, "review原因"),
                hit("b", Decision.REJECT, 0, "reject原因")), ctx, flow);
```
```java
        AggregateOutcome out = agg.aggregate(List.of(
                NodeResult.builder().nodeId("a").hit(false).build()), ctx, flow);
```
```java
        AggregateOutcome out = agg.aggregate(List.of(
                hit("a", Decision.PASS, 30, "r1"),
                hit("b", Decision.REVIEW, 42, "r2")), ctx, flow);
```

- [ ] **Step 6: 跑核心模块全测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core test 2>&1 | grep -E "Tests run|BUILD"`
Expected: `BUILD SUCCESS`，M1 的 56 测试全绿（含 EndToEndTest、FlowExecutorTest 经 execute 间接走新签名）。

- [ ] **Step 7: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/spi/DecisionAggregator.java \
        openrule-core/src/main/java/io/openrule/core/aggregate/PriorityAggregator.java \
        openrule-core/src/main/java/io/openrule/core/runtime/CompiledFlow.java \
        openrule-core/src/main/java/io/openrule/core/runtime/FlowExecutor.java \
        openrule-core/src/test/java/io/openrule/core/aggregate/PriorityAggregatorTest.java
git commit -m "refactor(core): DecisionAggregator.aggregate 增 FlowDefinition 参 + CompiledFlow.getDefinition（保持绿）"
```

---

## Task 3: ScoreCardNodeExecutor（评分卡）

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/definition/defs/ScoreCardDef.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java`（+`scoreCardDef` 字段）
- Create: `openrule-core/src/main/java/io/openrule/core/executor/ScoreCardNodeExecutor.java`
- Test: `openrule-core/src/test/java/io/openrule/core/executor/ScoreCardNodeExecutorTest.java`

**Interfaces:**
- Consumes: `OperatorMatcher.resolveValue / match / toBigDecimal`（Task 1）；`NodeExecutor` SPI；`CompiledNode`；`NodeResult`。
- Produces: `ScoreCardNodeExecutor implements NodeExecutor`（supportType=SCORECARD）；`ScoreCardDef`（含嵌套 `Attribute`/`Bin`/`Thresholds`）；`NodeDefinition.getScoreCardDef()`。score 写 `NodeResult.score` 与 `outputs["scorecard." + nodeId + ".score"]`。

- [ ] **Step 1: 写失败测试** `ScoreCardNodeExecutorTest.java`

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.ScoreCardDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ScoreCardNodeExecutorTest {

    private final ScoreCardNodeExecutor exec = new ScoreCardNodeExecutor();

    private NodeResult run(ScoreCardDef def, Map<String, Object> facts) {
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("SC").nodeName("评分卡").nodeType(NodeType.SCORECARD).scoreCardDef(def).build();
        CompiledNode compiled = exec.compile(node);
        return exec.execute(new DecisionContext("R", "f", "b", facts), compiled);
    }

    private ScoreCardDef.Bin exact(Object match, int score) {
        ScoreCardDef.Bin b = new ScoreCardDef.Bin();
        b.setMatch(match); b.setScore(score); return b;
    }
    private ScoreCardDef.Bin range(Integer min, Integer max, int score) {
        ScoreCardDef.Bin b = new ScoreCardDef.Bin();
        if (min != null) b.setMin(BigDecimal.valueOf(min));
        if (max != null) b.setMax(BigDecimal.valueOf(max));
        b.setScore(score); return b;
    }
    private ScoreCardDef.Bin defaultBin(int score) {
        ScoreCardDef.Bin b = new ScoreCardDef.Bin();
        b.setDefaultBin(true); b.setScore(score); return b;
    }
    private ScoreCardDef.Attribute attr(String key, ScoreCardDef.Bin... bins) {
        ScoreCardDef.Attribute a = new ScoreCardDef.Attribute();
        a.setFeatureKey(key); a.setBins(List.of(bins)); return a;
    }

    @Test
    void enumBins_pickExactScore() {
        ScoreCardDef def = new ScoreCardDef();
        def.setAttributes(List.of(attr("fact.buyer.level",
                exact("NEW", 30), exact("NORMAL", 10), exact("VIP", 0))));
        NodeResult r = run(def, Map.of("buyer", Map.of("level", "NEW")));
        assertThat(r.getScore()).isEqualTo(30);
        assertThat(r.getOutputs()).containsEntry("scorecard.SC.score", 30);
    }

    @Test
    void rangeBins_leftClosedRightOpen() {
        ScoreCardDef def = new ScoreCardDef();
        def.setAttributes(List.of(attr("fact.age",
                range(0, 18, 50), range(18, 60, 10), range(60, null, 30))));
        assertThat(run(def, Map.of("age", 18)).getScore()).isEqualTo(10);   // 18 属 [18,60)
        assertThat(run(def, Map.of("age", 60)).getScore()).isEqualTo(30);   // 60 属 [60,+inf)
    }

    @Test
    void defaultBin_whenNoMatch() {
        ScoreCardDef def = new ScoreCardDef();
        def.setAttributes(List.of(attr("fact.buyer.level",
                exact("VIP", 0), defaultBin(25))));
        assertThat(run(def, Map.of("buyer", Map.of("level", "UNKNOWN"))).getScore()).isEqualTo(25);
    }

    @Test
    void missingFeature_noDefault_contributesZero() {
        ScoreCardDef def = new ScoreCardDef();
        def.setAttributes(List.of(attr("fact.absent", exact("X", 99))));
        NodeResult r = run(def, Map.of());
        assertThat(r.getScore()).isZero();
        assertThat(r.getDetails()).containsKey("attributeScores");
    }

    @Test
    void weightApplied() {
        ScoreCardDef.Attribute a = attr("fact.buyer.level", exact("NEW", 20));
        a.setWeight(2.0);
        ScoreCardDef def = new ScoreCardDef();
        def.setBaseScore(5);
        def.setAttributes(List.of(a));
        assertThat(run(def, Map.of("buyer", Map.of("level", "NEW"))).getScore()).isEqualTo(45); // 5 + 20*2
    }

    @Test
    void thresholds_emitNodeDecision() {
        ScoreCardDef.Thresholds t = new ScoreCardDef.Thresholds();
        t.setReview(60); t.setReject(85);
        ScoreCardDef def = new ScoreCardDef();
        def.setThresholds(t);
        def.setAttributes(List.of(attr("fact.score", range(0, null, 70))));
        NodeResult r = run(def, Map.of("score", 1));
        assertThat(r.getScore()).isEqualTo(70);
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REVIEW);
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=ScoreCardNodeExecutorTest test`
Expected: 编译失败，`cannot find symbol: ScoreCardDef / scoreCardDef / ScoreCardNodeExecutor`。

- [ ] **Step 3: 创建 `ScoreCardDef.java`**

```java
package io.openrule.core.definition.defs;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * 评分卡配置。bin 三选一：精确 match / 区间 [min,max) 左闭右开 / defaultBin 兜底。
 * 匹配优先级：精确 &gt; 区间 &gt; 默认。
 *
 * @author ycr
 */
@Data
public class ScoreCardDef {

    private int    baseScore;
    private String scoreMode = "SUM";      // M4 仅 SUM
    private List<Attribute> attributes;
    private Thresholds thresholds;         // 可空：null 时不给节点级 decision 建议

    @Data
    public static class Attribute {
        private String   featureKey;       // fact./var. 引用
        private double    weight = 1.0;
        private List<Bin> bins;
    }

    @Data
    public static class Bin {
        private Object     match;          // 精确匹配值
        private BigDecimal min;            // 区间下界（含）；null = 无下界
        private BigDecimal max;            // 区间上界（不含）；null = 无上界
        private boolean    defaultBin;     // 兜底
        private int        score;
    }

    @Data
    public static class Thresholds {
        private Integer review;            // score >= review → REVIEW
        private Integer reject;            // score >= reject → REJECT（优先于 review）
    }
}
```

- [ ] **Step 4: 给 `NodeDefinition.java` 加 `scoreCardDef` 字段**

import 增 `io.openrule.core.definition.defs.ScoreCardDef;`，在 `private OperatorDef operatorDef;` 下方新增：

```java
    private ScoreCardDef scoreCardDef;
```

- [ ] **Step 5: 创建 `ScoreCardNodeExecutor.java`**

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.ScoreCardDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分卡执行器：按属性分箱取分加权累加，可按节点级阈值产出 decision 建议。
 *
 * @author ycr
 */
public class ScoreCardNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() { return NodeType.SCORECARD; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        ScoreCardDef def = node.getScoreCardDef();
        if (def == null || def.getAttributes() == null || def.getAttributes().isEmpty()) {
            throw new FlowValidationException("SCORECARD 配置不完整(attributes 为空): " + node.getNodeId());
        }
        for (ScoreCardDef.Attribute a : def.getAttributes()) {
            if (a.getFeatureKey() == null || a.getBins() == null || a.getBins().isEmpty()) {
                throw new FlowValidationException("SCORECARD 属性配置不完整: " + node.getNodeId());
            }
        }
    }

    @Override
    public CompiledNode compile(NodeDefinition node) {
        ScoreCardDef def = node.getScoreCardDef();
        List<CompiledAttribute> attrs = new ArrayList<>();
        for (ScoreCardDef.Attribute a : def.getAttributes()) {
            List<ScoreCardDef.Bin> exact = new ArrayList<>();
            List<ScoreCardDef.Bin> range = new ArrayList<>();
            ScoreCardDef.Bin dft = null;
            for (ScoreCardDef.Bin b : a.getBins()) {
                if (b.isDefaultBin())        dft = b;
                else if (b.getMatch() != null) exact.add(b);
                else                           range.add(b);
            }
            range.sort(Comparator.comparing(b -> b.getMin() == null
                    ? new BigDecimal(Long.MIN_VALUE) : b.getMin()));
            attrs.add(new CompiledAttribute(a.getFeatureKey(), a.getWeight(), exact, range, dft));
        }
        Integer review = def.getThresholds() == null ? null : def.getThresholds().getReview();
        Integer reject = def.getThresholds() == null ? null : def.getThresholds().getReject();
        return new CompiledNode(node, new CompiledScoreCard(def.getBaseScore(), attrs, review, reject));
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        long start = System.currentTimeMillis();
        NodeDefinition node = compiled.getDefinition();
        CompiledScoreCard sc = (CompiledScoreCard) compiled.getCompiledArtifact();

        double total = sc.baseScore;
        Map<String, Object> attrScores = new LinkedHashMap<>();
        for (CompiledAttribute attr : sc.attributes) {
            Object value = OperatorMatcher.resolveValue(attr.featureKey, ctx);
            Integer binScore = scoreOf(attr, value);
            if (binScore == null) {
                attrScores.put(attr.featureKey, "miss");
                continue;
            }
            double weighted = binScore * attr.weight;
            total += weighted;
            attrScores.put(attr.featureKey, weighted);
        }
        int score = (int) Math.round(total);

        boolean hit = false;
        Decision decision = null;
        String reason = null;
        if (sc.reject != null && score >= sc.reject) {
            hit = true; decision = Decision.REJECT; reason = "评分卡总分 " + score + " ≥ 拒绝阈值 " + sc.reject;
        } else if (sc.review != null && score >= sc.review) {
            hit = true; decision = Decision.REVIEW; reason = "评分卡总分 " + score + " ≥ 人审阈值 " + sc.review;
        }

        Map<String, Object> outputs = new HashMap<>();
        outputs.put("scorecard." + node.getNodeId() + ".score", score);

        return NodeResult.builder()
                .nodeId(node.getNodeId()).nodeName(node.getNodeName()).nodeType(NodeType.SCORECARD)
                .hit(hit).decision(decision).score(score).reason(reason)
                .outputs(outputs).details(Map.of("attributeScores", attrScores))
                .success(true).costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private Integer scoreOf(CompiledAttribute attr, Object value) {
        if (value != null) {
            for (ScoreCardDef.Bin b : attr.exactBins) {
                if (b.getMatch() != null && OperatorMatcher.match(value, "EQ", b.getMatch())) {
                    return b.getScore();
                }
            }
            BigDecimal v = tryBigDecimal(value);
            if (v != null) {
                for (ScoreCardDef.Bin b : attr.rangeBins) {
                    boolean geMin = b.getMin() == null || v.compareTo(b.getMin()) >= 0;
                    boolean ltMax = b.getMax() == null || v.compareTo(b.getMax()) < 0;
                    if (geMin && ltMax) return b.getScore();
                }
            }
        }
        return attr.defaultBin != null ? attr.defaultBin.getScore() : null;
    }

    private static BigDecimal tryBigDecimal(Object v) {
        try { return OperatorMatcher.toBigDecimal(v); } catch (RuntimeException e) { return null; }
    }

    // ── 编译产物 ──
    private static final class CompiledScoreCard {
        final int baseScore;
        final List<CompiledAttribute> attributes;
        final Integer review;
        final Integer reject;
        CompiledScoreCard(int baseScore, List<CompiledAttribute> attributes, Integer review, Integer reject) {
            this.baseScore = baseScore; this.attributes = attributes;
            this.review = review; this.reject = reject;
        }
    }

    private static final class CompiledAttribute {
        final String featureKey;
        final double weight;
        final List<ScoreCardDef.Bin> exactBins;
        final List<ScoreCardDef.Bin> rangeBins;
        final ScoreCardDef.Bin defaultBin;
        CompiledAttribute(String featureKey, double weight, List<ScoreCardDef.Bin> exactBins,
                          List<ScoreCardDef.Bin> rangeBins, ScoreCardDef.Bin defaultBin) {
            this.featureKey = featureKey; this.weight = weight;
            this.exactBins = exactBins; this.rangeBins = rangeBins; this.defaultBin = defaultBin;
        }
    }
}
```

- [ ] **Step 6: 跑测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=ScoreCardNodeExecutorTest test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 6 个用例全绿，`BUILD SUCCESS`。

- [ ] **Step 7: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/definition/defs/ScoreCardDef.java \
        openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java \
        openrule-core/src/main/java/io/openrule/core/executor/ScoreCardNodeExecutor.java \
        openrule-core/src/test/java/io/openrule/core/executor/ScoreCardNodeExecutorTest.java
git commit -m "feat(core): ScoreCardNodeExecutor（区间/枚举/默认分箱 + 加权 + 阈值建议）"
```

---

## Task 4: DecisionTableNodeExecutor（决策表）

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/definition/defs/DecisionTableDef.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java`（+`decisionTableDef` 字段）
- Create: `openrule-core/src/main/java/io/openrule/core/executor/DecisionTableNodeExecutor.java`
- Test: `openrule-core/src/test/java/io/openrule/core/executor/DecisionTableNodeExecutorTest.java`

**Interfaces:**
- Consumes: `OperatorMatcher.resolveValue / match`（Task 1）；`Decision.riskierThan`。
- Produces: `DecisionTableNodeExecutor implements NodeExecutor`（supportType=DECISION_TABLE）；`DecisionTableDef`（含嵌套 `Row`/`Cell`）；`NodeDefinition.getDecisionTableDef()`。**`DecisionTableDef.Cell` 被 Task 5 决策树复用。**

- [ ] **Step 1: 写失败测试** `DecisionTableNodeExecutorTest.java`

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.DecisionTableDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionTableNodeExecutorTest {

    private final DecisionTableNodeExecutor exec = new DecisionTableNodeExecutor();

    private NodeResult run(DecisionTableDef def, Map<String, Object> facts) {
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("DT").nodeName("决策表").nodeType(NodeType.DECISION_TABLE).decisionTableDef(def).build();
        return exec.execute(new DecisionContext("R", "f", "b", facts), exec.compile(node));
    }

    private DecisionTableDef.Cell cell(String left, String op, Object right) {
        DecisionTableDef.Cell c = new DecisionTableDef.Cell();
        c.setLeftFact(left); c.setOperator(op); c.setRightValue(right); return c;
    }
    private DecisionTableDef.Row row(int priority, Decision d, int score,
                                     Map<String, Object> outputs, List<DecisionTableDef.Cell> when) {
        DecisionTableDef.Row r = new DecisionTableDef.Row();
        r.setPriority(priority); r.setDecision(d); r.setScore(score);
        r.setOutputs(outputs); r.setWhen(when); return r;
    }

    @Test
    void first_picksFirstMatchingRow() {
        DecisionTableDef def = new DecisionTableDef();
        def.setHitPolicy("FIRST");
        def.setRows(List.of(
                row(0, Decision.REVIEW, 10, Map.of("tag", "a"), List.of(cell("fact.amt", "GT", 100))),
                row(0, Decision.REJECT, 20, Map.of("tag", "b"), List.of(cell("fact.amt", "GT", 50)))));
        NodeResult r = run(def, Map.of("amt", 200));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REVIEW);   // 第一条命中
        assertThat(r.getScore()).isEqualTo(10);
        assertThat(r.getOutputs()).containsEntry("tag", "a");
    }

    @Test
    void priority_picksHighestPriorityRow() {
        DecisionTableDef def = new DecisionTableDef();
        def.setHitPolicy("PRIORITY");
        def.setRows(List.of(
                row(1, Decision.REVIEW, 10, Map.of(), List.of(cell("fact.amt", "GT", 50))),
                row(5, Decision.REJECT, 20, Map.of(), List.of(cell("fact.amt", "GT", 50)))));
        NodeResult r = run(def, Map.of("amt", 200));
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);   // priority=5 胜
        assertThat(r.getScore()).isEqualTo(20);
    }

    @Test
    void collect_sumsScoresMergesOutputsHighestDecision() {
        DecisionTableDef def = new DecisionTableDef();
        def.setHitPolicy("COLLECT");
        def.setRows(List.of(
                row(0, Decision.REVIEW, 10, Map.of("a", 1), List.of(cell("fact.amt", "GT", 50))),
                row(0, Decision.REJECT, 30, Map.of("b", 2), List.of(cell("fact.amt", "GT", 100)))));
        NodeResult r = run(def, Map.of("amt", 200));
        assertThat(r.getScore()).isEqualTo(40);
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(r.getOutputs()).containsEntry("a", 1).containsEntry("b", 2);
        assertThat(r.getDetails()).containsKey("hitRows");
    }

    @Test
    void noMatch_notHit() {
        DecisionTableDef def = new DecisionTableDef();
        def.setHitPolicy("FIRST");
        def.setRows(List.of(row(0, Decision.REJECT, 9, Map.of(), List.of(cell("fact.amt", "GT", 999)))));
        NodeResult r = run(def, Map.of("amt", 1));
        assertThat(r.isHit()).isFalse();
        assertThat(r.getScore()).isZero();
        assertThat(r.getDecision()).isNull();
    }

    @Test
    void multipleCells_areAnded() {
        DecisionTableDef def = new DecisionTableDef();
        def.setHitPolicy("FIRST");
        def.setRows(List.of(row(0, Decision.REJECT, 5, Map.of(),
                List.of(cell("fact.amt", "GT", 100), cell("fact.country", "EQ", "CN")))));
        assertThat(run(def, Map.of("amt", 200, "country", "CN")).isHit()).isTrue();
        assertThat(run(def, Map.of("amt", 200, "country", "JP")).isHit()).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=DecisionTableNodeExecutorTest test`
Expected: 编译失败，`cannot find symbol: DecisionTableDef / decisionTableDef / DecisionTableNodeExecutor`。

- [ ] **Step 3: 创建 `DecisionTableDef.java`**

```java
package io.openrule.core.definition.defs;

import io.openrule.core.enums.Decision;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 决策表配置。hitPolicy：FIRST（首条命中）| PRIORITY（priority 最大）| COLLECT（全部命中）。
 * Cell 复用 OperatorMatcher 运算符。
 *
 * @author ycr
 */
@Data
public class DecisionTableDef {

    private String    hitPolicy = "FIRST";
    private List<Row> rows;

    @Data
    public static class Row {
        private int                priority;     // PRIORITY 用：值大者优先
        private List<Cell>         when;         // 行内多 Cell 取 AND
        private Map<String,Object> outputs;      // 命中后并入 NodeResult.outputs
        private Decision           decision;     // 命中行 decision（可空）
        private int                score;
        private String             reason;
    }

    @Data
    public static class Cell {
        private String leftFact;                 // fact./var. 引用
        private String operator;                 // OperatorMatcher.SUPPORTED 之一
        private Object rightValue;
    }
}
```

- [ ] **Step 4: 给 `NodeDefinition.java` 加 `decisionTableDef` 字段**

import 增 `io.openrule.core.definition.defs.DecisionTableDef;`，在 `scoreCardDef` 下方新增：

```java
    private DecisionTableDef decisionTableDef;
```

- [ ] **Step 5: 创建 `DecisionTableNodeExecutor.java`**

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.DecisionTableDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策表执行器：按 hitPolicy 匹配行；单元格复用 OperatorMatcher。
 *
 * @author ycr
 */
public class DecisionTableNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() { return NodeType.DECISION_TABLE; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        DecisionTableDef def = node.getDecisionTableDef();
        if (def == null || def.getRows() == null || def.getRows().isEmpty()) {
            throw new FlowValidationException("DECISION_TABLE 配置不完整(rows 为空): " + node.getNodeId());
        }
        for (DecisionTableDef.Row row : def.getRows()) {
            if (row.getWhen() == null) continue;
            for (DecisionTableDef.Cell c : row.getWhen()) {
                if (c.getOperator() == null || !OperatorMatcher.SUPPORTED.contains(c.getOperator())) {
                    throw new FlowValidationException("DECISION_TABLE 单元格运算符非法 @ " + node.getNodeId());
                }
            }
        }
    }

    @Override
    public CompiledNode compile(NodeDefinition node) {
        DecisionTableDef def = node.getDecisionTableDef();
        String hitPolicy = def.getHitPolicy() == null ? "FIRST" : def.getHitPolicy();
        List<DecisionTableDef.Row> rows = new ArrayList<>(def.getRows());
        if ("PRIORITY".equals(hitPolicy)) {
            rows.sort(Comparator.comparingInt(DecisionTableDef.Row::getPriority).reversed());
        }
        return new CompiledNode(node, new CompiledTable(hitPolicy, rows));
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        long start = System.currentTimeMillis();
        NodeDefinition node = compiled.getDefinition();
        CompiledTable table = (CompiledTable) compiled.getCompiledArtifact();
        boolean collect = "COLLECT".equals(table.hitPolicy);

        boolean hit = false;
        Decision decision = null;
        int score = 0;
        String reason = null;
        Map<String, Object> outputs = new HashMap<>();
        List<Integer> hitRows = new ArrayList<>();

        for (int i = 0; i < table.rows.size(); i++) {
            DecisionTableDef.Row row = table.rows.get(i);
            if (!rowMatches(row, ctx)) continue;
            hit = true;
            hitRows.add(i);
            if (row.getDecision() != null && (decision == null || row.getDecision().riskierThan(decision))) {
                decision = row.getDecision();
                reason = row.getReason();
            }
            score += row.getScore();
            if (row.getOutputs() != null) outputs.putAll(row.getOutputs());
            if (!collect) break;   // FIRST / PRIORITY：首条命中即止
        }

        return NodeResult.builder()
                .nodeId(node.getNodeId()).nodeName(node.getNodeName()).nodeType(NodeType.DECISION_TABLE)
                .hit(hit).decision(decision).score(score).reason(reason)
                .outputs(outputs).details(Map.of("hitRows", hitRows, "hitPolicy", table.hitPolicy))
                .success(true).costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private boolean rowMatches(DecisionTableDef.Row row, DecisionContext ctx) {
        if (row.getWhen() == null) return true;
        for (DecisionTableDef.Cell c : row.getWhen()) {
            Object left = OperatorMatcher.resolveValue(c.getLeftFact(), ctx);
            if (!OperatorMatcher.match(left, c.getOperator(), c.getRightValue())) return false;
        }
        return true;
    }

    private static final class CompiledTable {
        final String hitPolicy;
        final List<DecisionTableDef.Row> rows;
        CompiledTable(String hitPolicy, List<DecisionTableDef.Row> rows) {
            this.hitPolicy = hitPolicy; this.rows = rows;
        }
    }
}
```

- [ ] **Step 6: 跑测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=DecisionTableNodeExecutorTest test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 5 个用例全绿，`BUILD SUCCESS`。

- [ ] **Step 7: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/definition/defs/DecisionTableDef.java \
        openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java \
        openrule-core/src/main/java/io/openrule/core/executor/DecisionTableNodeExecutor.java \
        openrule-core/src/test/java/io/openrule/core/executor/DecisionTableNodeExecutorTest.java
git commit -m "feat(core): DecisionTableNodeExecutor（FIRST/PRIORITY/COLLECT，复用 OperatorMatcher）"
```

---

## Task 5: DecisionTreeNodeExecutor（决策树）

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/definition/defs/DecisionTreeDef.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java`（+`decisionTreeDef` 字段）
- Create: `openrule-core/src/main/java/io/openrule/core/executor/DecisionTreeNodeExecutor.java`
- Test: `openrule-core/src/test/java/io/openrule/core/executor/DecisionTreeNodeExecutorTest.java`

**Interfaces:**
- Consumes: `OperatorMatcher.resolveValue / match`（Task 1）；`DecisionTableDef.Cell`（Task 4，作为树节点 condition）；`FlowValidationException`。
- Produces: `DecisionTreeNodeExecutor implements NodeExecutor`（supportType=DECISION_TREE）；`DecisionTreeDef`（含嵌套 `TreeNode`/`Branch`）；`NodeDefinition.getDecisionTreeDef()`。MAX_DEPTH=20（C10）compile 期校验。

- [ ] **Step 1: 写失败测试** `DecisionTreeNodeExecutorTest.java`

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.DecisionTableDef;
import io.openrule.core.definition.defs.DecisionTreeDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionTreeNodeExecutorTest {

    private final DecisionTreeNodeExecutor exec = new DecisionTreeNodeExecutor();

    private DecisionTableDef.Cell cell(String left, String op, Object right) {
        DecisionTableDef.Cell c = new DecisionTableDef.Cell();
        c.setLeftFact(left); c.setOperator(op); c.setRightValue(right); return c;
    }
    private DecisionTreeDef.TreeNode leaf(Decision d, int score) {
        DecisionTreeDef.TreeNode n = new DecisionTreeDef.TreeNode();
        n.setDecision(d); n.setScore(score); return n;
    }
    private DecisionTreeDef.Branch branch(boolean onTrue, DecisionTreeDef.TreeNode next) {
        DecisionTreeDef.Branch b = new DecisionTreeDef.Branch();
        b.setOnTrue(onTrue); b.setNext(next); return b;
    }
    private NodeResult run(DecisionTreeDef def, Map<String, Object> facts) {
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("TREE").nodeName("决策树").nodeType(NodeType.DECISION_TREE).decisionTreeDef(def).build();
        return exec.execute(new DecisionContext("R", "f", "b", facts), exec.compile(node));
    }

    @Test
    void traversesToRejectLeaf() {
        DecisionTreeDef.TreeNode root = new DecisionTreeDef.TreeNode();
        root.setCondition(cell("fact.amt", "GT", 100));
        root.setBranches(List.of(
                branch(true, leaf(Decision.REJECT, 50)),
                branch(false, leaf(Decision.PASS, 0))));
        DecisionTreeDef def = new DecisionTreeDef();
        def.setRoot(root);

        NodeResult hit = run(def, Map.of("amt", 200));
        assertThat(hit.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(hit.getScore()).isEqualTo(50);
        assertThat(hit.isHit()).isTrue();
        assertThat(hit.getDetails()).containsKey("path");

        NodeResult pass = run(def, Map.of("amt", 1));
        assertThat(pass.getDecision()).isEqualTo(Decision.PASS);
    }

    @Test
    void compileRejectsDepthOver20() {
        // 构造 21 层内部节点链
        DecisionTreeDef.TreeNode leaf = leaf(Decision.PASS, 0);
        DecisionTreeDef.TreeNode cur = leaf;
        for (int i = 0; i < 21; i++) {
            DecisionTreeDef.TreeNode parent = new DecisionTreeDef.TreeNode();
            parent.setCondition(cell("fact.x", "GT", 0));
            parent.setBranches(List.of(branch(true, cur), branch(false, leaf(Decision.PASS, 0))));
            cur = parent;
        }
        DecisionTreeDef def = new DecisionTreeDef();
        def.setRoot(cur);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("DEEP").nodeType(NodeType.DECISION_TREE).decisionTreeDef(def).build();
        assertThatThrownBy(() -> exec.compile(node))
                .isInstanceOf(FlowValidationException.class).hasMessageContaining("20");
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=DecisionTreeNodeExecutorTest test`
Expected: 编译失败，`cannot find symbol: DecisionTreeDef / decisionTreeDef / DecisionTreeNodeExecutor`。

- [ ] **Step 3: 创建 `DecisionTreeDef.java`**

```java
package io.openrule.core.definition.defs;

import io.openrule.core.enums.Decision;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 决策树配置。内部节点有 condition + branches；叶子节点 branches 空、带 decision。
 * condition 复用 {@link DecisionTableDef.Cell}。深度上限 20（C10）。
 *
 * @author ycr
 */
@Data
public class DecisionTreeDef {

    private TreeNode root;

    @Data
    public static class TreeNode {
        private DecisionTableDef.Cell condition;   // 内部节点：判定条件
        private List<Branch>          branches;    // 内部节点：分支
        // 叶子：
        private Decision              decision;
        private int                   score;
        private Map<String,Object>    outputs;
        private String                reason;
    }

    @Data
    public static class Branch {
        private boolean  onTrue;                   // condition 结果等于该值时进入
        private TreeNode next;
    }
}
```

- [ ] **Step 4: 给 `NodeDefinition.java` 加 `decisionTreeDef` 字段**

import 增 `io.openrule.core.definition.defs.DecisionTreeDef;`，在 `decisionTableDef` 下方新增：

```java
    private DecisionTreeDef decisionTreeDef;
```

- [ ] **Step 5: 创建 `DecisionTreeNodeExecutor.java`**

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.DecisionTreeDef;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * 决策树执行器：从 root 递归到叶子，叶子产出 decision/score/outputs。深度上限 20（C10）。
 *
 * @author ycr
 */
public class DecisionTreeNodeExecutor implements NodeExecutor {

    private static final int MAX_DEPTH = 20;

    @Override
    public NodeType supportType() { return NodeType.DECISION_TREE; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        DecisionTreeDef def = node.getDecisionTreeDef();
        if (def == null || def.getRoot() == null) {
            throw new FlowValidationException("DECISION_TREE 配置不完整(root 为空): " + node.getNodeId());
        }
        checkDepth(def.getRoot(), 1);
    }

    @Override
    public CompiledNode compile(NodeDefinition node) {
        validate(node);                         // 编译期静态校验结构与深度
        return new CompiledNode(node, node.getDecisionTreeDef().getRoot());
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        long start = System.currentTimeMillis();
        NodeDefinition node = compiled.getDefinition();
        DecisionTreeDef.TreeNode cur = (DecisionTreeDef.TreeNode) compiled.getCompiledArtifact();

        StringBuilder path = new StringBuilder("root");
        int depth = 0;
        while (cur != null) {
            boolean leaf = cur.getBranches() == null || cur.getBranches().isEmpty();
            if (leaf) {
                Map<String, Object> outputs = cur.getOutputs() == null
                        ? new HashMap<>() : new HashMap<>(cur.getOutputs());
                return NodeResult.builder()
                        .nodeId(node.getNodeId()).nodeName(node.getNodeName()).nodeType(NodeType.DECISION_TREE)
                        .hit(cur.getDecision() != null).decision(cur.getDecision()).score(cur.getScore())
                        .reason(cur.getReason()).outputs(outputs)
                        .details(Map.of("path", path.toString()))
                        .success(true).costMillis(System.currentTimeMillis() - start)
                        .build();
            }
            if (++depth > MAX_DEPTH) {
                throw new RuleEngineException("决策树深度超限(>20): " + node.getNodeId());
            }
            Object left = OperatorMatcher.resolveValue(cur.getCondition().getLeftFact(), ctx);
            boolean cond = OperatorMatcher.match(left, cur.getCondition().getOperator(),
                    cur.getCondition().getRightValue());
            path.append("->").append(cur.getCondition().getLeftFact()).append(cond ? ":true" : ":false");
            DecisionTreeDef.TreeNode next = null;
            for (DecisionTreeDef.Branch b : cur.getBranches()) {
                if (b.isOnTrue() == cond) { next = b.getNext(); break; }
            }
            cur = next;
        }
        // 无匹配分支：未命中
        return NodeResult.builder()
                .nodeId(node.getNodeId()).nodeName(node.getNodeName()).nodeType(NodeType.DECISION_TREE)
                .hit(false).success(true).details(Map.of("path", path + "->(no-branch)"))
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private void checkDepth(DecisionTreeDef.TreeNode n, int depth) {
        if (depth > MAX_DEPTH) {
            throw new FlowValidationException("决策树深度超限(>20)");
        }
        if (n.getBranches() == null) return;
        for (DecisionTreeDef.Branch b : n.getBranches()) {
            if (b.getNext() != null) checkDepth(b.getNext(), depth + 1);
        }
    }
}
```

- [ ] **Step 6: 跑测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=DecisionTreeNodeExecutorTest test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 2 个用例全绿，`BUILD SUCCESS`。

- [ ] **Step 7: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/definition/defs/DecisionTreeDef.java \
        openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java \
        openrule-core/src/main/java/io/openrule/core/executor/DecisionTreeNodeExecutor.java \
        openrule-core/src/test/java/io/openrule/core/executor/DecisionTreeNodeExecutorTest.java
git commit -m "feat(core): DecisionTreeNodeExecutor（递归遍历 + 深度≤20 + 路径 details）"
```

---

## Task 6: RuleSetNodeExecutor（规则集，Supplier 破环）

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/definition/defs/RuleSetDef.java`
- Modify: `openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java`（+`ruleSetDef` 字段）
- Modify: `openrule-core/src/main/java/io/openrule/core/runtime/NodeRunner.java`（+`getRegistry()`）
- Create: `openrule-core/src/main/java/io/openrule/core/executor/RuleSetNodeExecutor.java`
- Test: `openrule-core/src/test/java/io/openrule/core/executor/RuleSetNodeExecutorTest.java`

**Interfaces:**
- Consumes: `NodeRunner.run(DecisionContext, CompiledNode)`、新增 `NodeRunner.getRegistry() -> NodeExecutorRegistry`；`NodeExecutorRegistry.getRequired(NodeType)`；`Decision.riskierThan`。
- Produces: `RuleSetNodeExecutor(Supplier<NodeRunner>)` implements NodeExecutor（supportType=RULE_SET）；`RuleSetDef`（hitPolicy + `List<NodeDefinition> rules`）；`NodeDefinition.getRuleSetDef()`。内部规则 SERIAL 执行、按 hitPolicy 聚合为单个 `NodeResult`（score=内部求和，decision=命中规则最高风险，outputs=命中规则合并）。

- [ ] **Step 1: 写失败测试** `RuleSetNodeExecutorTest.java`

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.definition.defs.RuleSetDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.NodeResult;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class RuleSetNodeExecutorTest {

    private ExecutorService pool;
    private RuleSetNodeExecutor ruleSet;
    private NodeExecutorRegistry registry;
    private final AtomicReference<NodeRunner> runnerRef = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        ruleSet = new RuleSetNodeExecutor(runnerRef::get);   // Supplier 破环
        registry = new NodeExecutorRegistry(List.of(new OperatorNodeExecutor(), ruleSet));
        runnerRef.set(new NodeRunner(registry, pool));
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private NodeDefinition op(String id, String left, String op, Object right, Decision onHit, int score) {
        OperatorDef d = new OperatorDef();
        d.setLeftFact(left); d.setOperator(op); d.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(1).operatorDef(d).decisionOnHit(onHit).failPolicy(FailPolicy.SKIP)
                .timeoutMillis(500).build();
    }

    private NodeResult runRuleSet(String hitPolicy, List<NodeDefinition> rules, Map<String, Object> facts) {
        RuleSetDef def = new RuleSetDef();
        def.setHitPolicy(hitPolicy);
        def.setRules(rules);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("RS").nodeName("规则集").nodeType(NodeType.RULE_SET).ruleSetDef(def).build();
        CompiledNode compiled = registry.getRequired(NodeType.RULE_SET).compile(node);
        return ruleSet.execute(new DecisionContext("R", "f", "b", facts), compiled);
    }

    @Test
    void anyHit_hitsWhenOneInnerHits() {
        NodeResult r = runRuleSet("ANY_HIT", List.of(
                op("a", "fact.amt", "GT", 999, Decision.REVIEW, 0),
                op("b", "fact.amt", "GT", 100, Decision.REJECT, 0)), Map.of("amt", 200));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);   // 命中规则最高风险
    }

    @Test
    void allHit_falseWhenNotAllHit() {
        NodeResult r = runRuleSet("ALL_HIT", List.of(
                op("a", "fact.amt", "GT", 100, Decision.REVIEW, 0),
                op("b", "fact.amt", "GT", 999, Decision.REJECT, 0)), Map.of("amt", 200));
        assertThat(r.isHit()).isFalse();
    }

    @Test
    void firstHit_takesFirstHittingRuleDecision() {
        NodeResult r = runRuleSet("FIRST_HIT", List.of(
                op("a", "fact.amt", "GT", 100, Decision.REVIEW, 0),
                op("b", "fact.amt", "GT", 100, Decision.REJECT, 0)), Map.of("amt", 200));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REVIEW);   // 第一条命中规则
    }

    @Test
    void collect_sumsInnerScores() {
        NodeResult r = runRuleSet("COLLECT", List.of(
                op("a", "fact.amt", "GT", 100, Decision.REVIEW, 10),
                op("b", "fact.amt", "GT", 100, Decision.REJECT, 30)), Map.of("amt", 200));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getScore()).isEqualTo(40);
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);
    }

    @Test
    void innerRuleError_isSkippedByFailPolicy_doesNotBreakRuleSet() {
        // GT 比较字符串触发数值转换异常 → 内部规则 FailPolicy.SKIP → 跳过、不命中
        NodeResult r = runRuleSet("ANY_HIT", List.of(
                op("bad", "fact.name", "GT", 100, Decision.REJECT, 0),
                op("ok", "fact.amt", "GT", 100, Decision.REVIEW, 0)),
                Map.of("name", "abc", "amt", 200));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REVIEW);   // bad 被跳过，ok 命中
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=RuleSetNodeExecutorTest test`
Expected: 编译失败，`cannot find symbol: RuleSetDef / ruleSetDef / RuleSetNodeExecutor / getRegistry`。

- [ ] **Step 3: 创建 `RuleSetDef.java`**

```java
package io.openrule.core.definition.defs;

import io.openrule.core.definition.NodeDefinition;
import lombok.Data;
import java.util.List;

/**
 * 规则集配置。内部规则 M4 仅 SERIAL 串行；hitPolicy：FIRST_HIT | ANY_HIT | ALL_HIT | COLLECT。
 *
 * @author ycr
 */
@Data
public class RuleSetDef {
    private String hitPolicy = "ANY_HIT";
    private List<NodeDefinition> rules;
}
```

- [ ] **Step 4: 给 `NodeDefinition.java` 加 `ruleSetDef` 字段**

import 增 `io.openrule.core.definition.defs.RuleSetDef;`，在 `decisionTreeDef` 下方新增：

```java
    private RuleSetDef ruleSetDef;
```

- [ ] **Step 5: 给 `NodeRunner.java` 加 `getRegistry()`**

在 `run(...)` 方法上方（构造器之后）新增：

```java
    /** 供 RuleSet 编译内部规则复用注册表。 */
    public NodeExecutorRegistry getRegistry() { return registry; }
```

- [ ] **Step 6: 创建 `RuleSetNodeExecutor.java`**

```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.RuleSetDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 规则集执行器：内部规则经 NodeRunner 串行执行，按 hitPolicy 聚合为自身单个 NodeResult。
 * 用 Supplier&lt;NodeRunner&gt; 延迟注入打破 NodeRunner→Registry→RuleSetExecutor 构造环。
 * 内部规则读同一 ctx 快照，相互之间不做 output 链式传递（C1 安全 + 确定性）。
 *
 * @author ycr
 */
public class RuleSetNodeExecutor implements NodeExecutor {

    private final Supplier<NodeRunner> nodeRunnerRef;

    public RuleSetNodeExecutor(Supplier<NodeRunner> nodeRunnerRef) {
        this.nodeRunnerRef = nodeRunnerRef;
    }

    @Override
    public NodeType supportType() { return NodeType.RULE_SET; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        RuleSetDef def = node.getRuleSetDef();
        if (def == null || def.getRules() == null || def.getRules().isEmpty()) {
            throw new FlowValidationException("RULE_SET 配置不完整(rules 为空): " + node.getNodeId());
        }
        NodeExecutorRegistry reg = nodeRunnerRef.get().getRegistry();
        for (NodeDefinition rule : def.getRules()) {
            reg.getRequired(rule.getNodeType()).validate(rule);
        }
    }

    @Override
    public CompiledNode compile(NodeDefinition node) {
        RuleSetDef def = node.getRuleSetDef();
        NodeExecutorRegistry reg = nodeRunnerRef.get().getRegistry();
        List<CompiledNode> inner = def.getRules().stream()
                .map(r -> reg.getRequired(r.getNodeType()).compile(r))
                .toList();
        String hitPolicy = def.getHitPolicy() == null ? "ANY_HIT" : def.getHitPolicy();
        return new CompiledNode(node, new CompiledRuleSet(hitPolicy, inner));
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        long start = System.currentTimeMillis();
        NodeDefinition node = compiled.getDefinition();
        CompiledRuleSet rs = (CompiledRuleSet) compiled.getCompiledArtifact();
        NodeRunner runner = nodeRunnerRef.get();

        List<NodeResult> inner = new ArrayList<>();
        for (CompiledNode c : rs.rules) {
            inner.add(runner.run(ctx, c));
        }
        return aggregate(node, rs.hitPolicy, inner, start);
    }

    private NodeResult aggregate(NodeDefinition node, String hitPolicy,
                                 List<NodeResult> inner, long start) {
        List<NodeResult> hits = inner.stream().filter(NodeResult::isHit).toList();
        boolean hit = switch (hitPolicy) {
            case "ALL_HIT" -> !inner.isEmpty() && hits.size() == inner.size();
            default        -> !hits.isEmpty();   // ANY_HIT / FIRST_HIT / COLLECT
        };

        List<NodeResult> contributing = "FIRST_HIT".equals(hitPolicy)
                ? (hits.isEmpty() ? List.of() : List.of(hits.get(0)))
                : hits;

        Decision decision = null;
        String reason = null;
        Map<String, Object> outputs = new HashMap<>();
        for (NodeResult r : contributing) {
            if (r.getDecision() != null && (decision == null || r.getDecision().riskierThan(decision))) {
                decision = r.getDecision();
                reason = r.getReason();
            }
            if (r.getOutputs() != null) outputs.putAll(r.getOutputs());
        }
        int totalScore = inner.stream().mapToInt(NodeResult::getScore).sum();

        List<Map<String, Object>> ruleResults = new ArrayList<>();
        for (NodeResult r : inner) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", r.getNodeId());
            m.put("hit", r.isHit());
            m.put("decision", r.getDecision());
            m.put("score", r.getScore());
            ruleResults.add(m);
        }

        return NodeResult.builder()
                .nodeId(node.getNodeId()).nodeName(node.getNodeName()).nodeType(NodeType.RULE_SET)
                .hit(hit).decision(hit ? decision : null).score(totalScore).reason(hit ? reason : null)
                .outputs(outputs).details(Map.of("ruleResults", ruleResults, "hitPolicy", hitPolicy))
                .success(true).costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private static final class CompiledRuleSet {
        final String hitPolicy;
        final List<CompiledNode> rules;
        CompiledRuleSet(String hitPolicy, List<CompiledNode> rules) {
            this.hitPolicy = hitPolicy; this.rules = rules;
        }
    }
}
```

- [ ] **Step 7: 跑测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=RuleSetNodeExecutorTest test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 5 个用例全绿，`BUILD SUCCESS`。

- [ ] **Step 8: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/definition/defs/RuleSetDef.java \
        openrule-core/src/main/java/io/openrule/core/definition/NodeDefinition.java \
        openrule-core/src/main/java/io/openrule/core/runtime/NodeRunner.java \
        openrule-core/src/main/java/io/openrule/core/executor/RuleSetNodeExecutor.java \
        openrule-core/src/test/java/io/openrule/core/executor/RuleSetNodeExecutorTest.java
git commit -m "feat(core): RuleSetNodeExecutor（Supplier 破环 + 四 hitPolicy + 内部 SERIAL 聚合）"
```

---

## Task 7: ScoreThresholdAggregator（分数阈值聚合）

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/aggregate/ScoreThresholdAggregator.java`
- Test: `openrule-core/src/test/java/io/openrule/core/aggregate/ScoreThresholdAggregatorTest.java`

**Interfaces:**
- Consumes: `DecisionAggregator`（Task 2 三参签名）；`FlowDefinition.getMetadata()`；`AggregateOutcome`。
- Produces: `ScoreThresholdAggregator implements DecisionAggregator`（supportPolicy=SCORE_THRESHOLD）。total = ΣNodeResult.score；阈值取 metadata `reject`/`review`；映射 REJECT/REVIEW/PASS；无阈值退化 PASS。

- [ ] **Step 1: 写失败测试** `ScoreThresholdAggregatorTest.java`

```java
package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ScoreThresholdAggregatorTest {

    private final ScoreThresholdAggregator agg = new ScoreThresholdAggregator();
    private final DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());

    private NodeResult scored(String id, int score) {
        return NodeResult.builder().nodeId(id).hit(true).score(score).build();
    }
    private FlowDefinition withThresholds(Integer review, Integer reject) {
        return FlowDefinition.builder().metadata(Map.of("review", review, "reject", reject)).build();
    }

    @Test
    void supportsScoreThresholdPolicy() {
        assertThat(agg.supportPolicy()).isEqualTo(AggregatePolicy.SCORE_THRESHOLD);
    }

    @Test
    void mapsTotalScoreToDecision() {
        FlowDefinition flow = withThresholds(60, 85);
        assertThat(agg.aggregate(List.of(scored("a", 90)), ctx, flow).decision()).isEqualTo(Decision.REJECT);
        assertThat(agg.aggregate(List.of(scored("a", 40), scored("b", 30)), ctx, flow).decision())
                .isEqualTo(Decision.REVIEW);  // 70 ≥ 60
        assertThat(agg.aggregate(List.of(scored("a", 10)), ctx, flow).decision()).isEqualTo(Decision.PASS);
    }

    @Test
    void boundaryIsInclusive() {
        FlowDefinition flow = withThresholds(60, 85);
        assertThat(agg.aggregate(List.of(scored("a", 85)), ctx, flow).decision()).isEqualTo(Decision.REJECT);
        assertThat(agg.aggregate(List.of(scored("a", 60)), ctx, flow).decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void noThresholds_defaultsToPass() {
        FlowDefinition flow = FlowDefinition.builder().build();   // metadata == null
        AggregateOutcome out = agg.aggregate(List.of(scored("a", 999)), ctx, flow);
        assertThat(out.decision()).isEqualTo(Decision.PASS);
        assertThat(out.reason()).contains("未配置阈值");
        assertThat(out.totalScore()).isEqualTo(999);
    }
}
```

- [ ] **Step 2: 跑测试，确认编译失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=ScoreThresholdAggregatorTest test`
Expected: 编译失败，`cannot find symbol: ScoreThresholdAggregator`。

- [ ] **Step 3: 创建 `ScoreThresholdAggregator.java`**

```java
package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import io.openrule.core.spi.DecisionAggregator;

import java.util.List;
import java.util.Map;

/**
 * 分数阈值聚合器：累积总分按 flow.metadata 的 {review, reject} 映射决策。
 * total ≥ reject → REJECT；≥ review → REVIEW；否则 PASS。无阈值退化 PASS。
 *
 * @author ycr
 */
public class ScoreThresholdAggregator implements DecisionAggregator {

    @Override
    public AggregatePolicy supportPolicy() { return AggregatePolicy.SCORE_THRESHOLD; }

    @Override
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx, FlowDefinition definition) {
        int total = results.stream().mapToInt(NodeResult::getScore).sum();
        List<String> hitNodes = results.stream().filter(NodeResult::isHit)
                .map(NodeResult::getNodeId).toList();

        Map<String, Object> md = definition == null ? null : definition.getMetadata();
        Integer review = intFrom(md, "review");
        Integer reject = intFrom(md, "reject");

        if (review == null && reject == null) {
            return new AggregateOutcome(Decision.PASS,
                    "未配置阈值(metadata.review/reject)，默认 PASS；总分=" + total, total, hitNodes);
        }
        Decision decision;
        String reason;
        if (reject != null && total >= reject) {
            decision = Decision.REJECT; reason = "总分 " + total + " ≥ 拒绝阈值 " + reject;
        } else if (review != null && total >= review) {
            decision = Decision.REVIEW; reason = "总分 " + total + " ≥ 人审阈值 " + review;
        } else {
            decision = Decision.PASS; reason = "总分 " + total + " 低于阈值";
        }
        return new AggregateOutcome(decision, reason, total, hitNodes);
    }

    private static Integer intFrom(Map<String, Object> md, String key) {
        if (md == null) return null;
        Object v = md.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -Dtest=ScoreThresholdAggregatorTest test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 4 个用例全绿，`BUILD SUCCESS`。

- [ ] **Step 5: 跑核心模块全测试回归**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core test 2>&1 | grep -E "Tests run|BUILD"`
Expected: `BUILD SUCCESS`，M1 的 56 + M4 新增用例全绿。

- [ ] **Step 6: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/aggregate/ScoreThresholdAggregator.java \
        openrule-core/src/test/java/io/openrule/core/aggregate/ScoreThresholdAggregatorTest.java
git commit -m "feat(core): ScoreThresholdAggregator（总分→metadata 阈值映射，无阈值退化 PASS）"
```

---

## Task 8: Spring 自动装配 5 个新 Bean + 端到端

**Files:**
- Modify: `openrule-spring/src/main/java/io/openrule/spring/autoconfigure/OpenRuleAutoConfiguration.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/autoconfigure/AdvancedNodesAutoConfigTest.java`

**Interfaces:**
- Consumes: 全部 M4 执行器/聚合器（Task 3–7）；`OpenRuleService.simulate(FlowDefinition, Map)`；`ObjectProvider<NodeRunner>`。
- Produces: 5 个 `@ConditionalOnMissingBean`：`scoreCardNodeExecutor`/`decisionTableNodeExecutor`/`decisionTreeNodeExecutor`/`ruleSetNodeExecutor(ObjectProvider<NodeRunner>)`/`scoreThresholdAggregator`。`nodeExecutorRegistry(List<NodeExecutor>)` 与 `flowExecutor(...List<DecisionAggregator>)` 自动收集，无需改其装配。

> 构造环说明：`ruleSetNodeExecutor` 注入 `ObjectProvider<NodeRunner>`（惰性），构造时不触发 NodeRunner 创建，故 `nodeExecutorRegistry`（收集含 ruleSet 的所有 executor）可先建，`nodeRunner` 后建，无环；运行期 `provider.getObject()` 解析到单例 NodeRunner。

- [ ] **Step 1: 写失败测试** `AdvancedNodesAutoConfigTest.java`

```java
package io.openrule.spring.autoconfigure;

import io.openrule.core.aggregate.ScoreThresholdAggregator;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.DecisionTableDef;
import io.openrule.core.definition.defs.ScoreCardDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.executor.DecisionTableNodeExecutor;
import io.openrule.core.executor.DecisionTreeNodeExecutor;
import io.openrule.core.executor.RuleSetNodeExecutor;
import io.openrule.core.executor.ScoreCardNodeExecutor;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.service.OpenRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AdvancedNodesAutoConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, OpenRuleAutoConfiguration.class));

    @Test
    void registersAdvancedExecutorsAndAggregator() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ScoreCardNodeExecutor.class);
            assertThat(ctx).hasSingleBean(DecisionTableNodeExecutor.class);
            assertThat(ctx).hasSingleBean(DecisionTreeNodeExecutor.class);
            assertThat(ctx).hasSingleBean(RuleSetNodeExecutor.class);
            assertThat(ctx).hasSingleBean(ScoreThresholdAggregator.class);
        });
    }

    @Test
    void simulateScorecardPlusTableWithScoreThreshold() {
        runner.run(ctx -> {
            OpenRuleService svc = ctx.getBean(OpenRuleService.class);
            ExecutionOutcome out = svc.simulate(buildScoreFlow(),
                    Map.of("buyer", Map.of("level", "NEW"), "order", Map.of("amount", 12800)));
            // 评分卡 NEW=30 + 决策表 amount>10000 → +40 = 70；阈值 review=60 → REVIEW
            assertThat(out.result().getTotalScore()).isEqualTo(70);
            assertThat(out.result().getDecision()).isEqualTo(Decision.REVIEW);
        });
    }

    private FlowDefinition buildScoreFlow() {
        ScoreCardDef.Bin newBin = new ScoreCardDef.Bin(); newBin.setMatch("NEW"); newBin.setScore(30);
        ScoreCardDef.Bin vipBin = new ScoreCardDef.Bin(); vipBin.setMatch("VIP"); vipBin.setScore(0);
        ScoreCardDef.Attribute attr = new ScoreCardDef.Attribute();
        attr.setFeatureKey("fact.buyer.level"); attr.setBins(List.of(newBin, vipBin));
        ScoreCardDef sc = new ScoreCardDef(); sc.setAttributes(List.of(attr));
        NodeDefinition scNode = NodeDefinition.builder()
                .nodeId("SC").nodeName("评分卡").nodeType(NodeType.SCORECARD).order(10).scoreCardDef(sc).build();

        DecisionTableDef.Cell cell = new DecisionTableDef.Cell();
        cell.setLeftFact("fact.order.amount"); cell.setOperator("GT"); cell.setRightValue(10000);
        DecisionTableDef.Row row = new DecisionTableDef.Row();
        row.setScore(40); row.setWhen(List.of(cell));
        DecisionTableDef dt = new DecisionTableDef(); dt.setHitPolicy("FIRST"); dt.setRows(List.of(row));
        NodeDefinition dtNode = NodeDefinition.builder()
                .nodeId("DT").nodeName("决策表").nodeType(NodeType.DECISION_TABLE).order(20).decisionTableDef(dt).build();

        StageDefinition stage = StageDefinition.builder()
                .stageId("s1").stageName("评分").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(scNode, dtNode)).build();

        return FlowDefinition.builder()
                .flowId("score_flow").flowName("评分流程").version(1).enabled(true)
                .aggregatePolicy(AggregatePolicy.SCORE_THRESHOLD)
                .metadata(Map.of("review", 60, "reject", 85))
                .stages(List.of(stage)).build();
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -Dtest=AdvancedNodesAutoConfigTest test 2>&1 | grep -E "Tests run|BUILD|NoSuchBean"`
Expected: 失败 —— `registersAdvancedExecutorsAndAggregator` 因 bean 不存在断言失败；`simulate...` 因 SCORECARD/DECISION_TABLE 无执行器 `RuleEngineException: No executor`。

- [ ] **Step 3: 在 `OpenRuleAutoConfiguration.java` 增 5 个 Bean**

imports 增：
```java
import io.openrule.core.aggregate.ScoreThresholdAggregator;
import io.openrule.core.executor.DecisionTableNodeExecutor;
import io.openrule.core.executor.DecisionTreeNodeExecutor;
import io.openrule.core.executor.RuleSetNodeExecutor;
import io.openrule.core.executor.ScoreCardNodeExecutor;
```
（`ObjectProvider`、`NodeRunner`、`ConditionalOnMissingBean`、`Bean` 已 import。）

在 `priorityAggregator()` Bean 之后新增：
```java
    @Bean
    @ConditionalOnMissingBean
    public ScoreCardNodeExecutor scoreCardNodeExecutor() {
        return new ScoreCardNodeExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionTableNodeExecutor decisionTableNodeExecutor() {
        return new DecisionTableNodeExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionTreeNodeExecutor decisionTreeNodeExecutor() {
        return new DecisionTreeNodeExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleSetNodeExecutor ruleSetNodeExecutor(ObjectProvider<NodeRunner> nodeRunnerProvider) {
        // 惰性 Provider 破环：构造期不触发 NodeRunner 创建，运行期再解析单例
        return new RuleSetNodeExecutor(nodeRunnerProvider::getObject);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScoreThresholdAggregator scoreThresholdAggregator() {
        return new ScoreThresholdAggregator();
    }
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -Dtest=AdvancedNodesAutoConfigTest test 2>&1 | grep -E "Tests run|BUILD"`
Expected: 2 个用例全绿，`BUILD SUCCESS`。

- [ ] **Step 5: 跑 spring 模块全测试回归**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test 2>&1 | grep -E "Tests run|BUILD"`
Expected: `BUILD SUCCESS`，M2a 既有测试 + 新增端到端全绿。

- [ ] **Step 6: 提交**

```bash
git add openrule-spring/src/main/java/io/openrule/spring/autoconfigure/OpenRuleAutoConfiguration.java \
        openrule-spring/src/test/java/io/openrule/spring/autoconfigure/AdvancedNodesAutoConfigTest.java
git commit -m "feat(spring): 自动装配 4 高级节点执行器 + ScoreThresholdAggregator（RuleSet 经 ObjectProvider 破环）+ 组合 simulate 端到端"
```

---

## Task 9: M4Demo + 全 reactor 验收 + session 记录

**Files:**
- Create: `openrule-core/src/main/java/io/openrule/core/demo/M4Demo.java`
- Create: `docs/sessions/2026-06-23-openrule-m4-session.md`

**Interfaces:**
- Consumes: 全部 M4 执行器 + `ScoreThresholdAggregator` + `PriorityAggregator` + 核心 runtime；手动装配（无 Spring），编译走 `registry.getRequired(type).compile(node)`。

- [ ] **Step 1: 创建 `M4Demo.java`**

```java
package io.openrule.core.demo;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.aggregate.ScoreThresholdAggregator;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.DecisionTableDef;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.definition.defs.RuleSetDef;
import io.openrule.core.definition.defs.ScoreCardDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.executor.DecisionTableNodeExecutor;
import io.openrule.core.executor.DecisionTreeNodeExecutor;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.executor.RuleSetNodeExecutor;
import io.openrule.core.executor.ScoreCardNodeExecutor;
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.DecisionAggregator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M4 验收：纯 Java 手动装配，跑通"评分卡 + 决策表 + 规则集"组合流程（SCORE_THRESHOLD 聚合）。
 *
 * @author ycr
 */
public class M4Demo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<NodeRunner> runnerRef = new AtomicReference<>();
        RuleSetNodeExecutor ruleSet = new RuleSetNodeExecutor(runnerRef::get);
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                new OperatorNodeExecutor(), new ScoreCardNodeExecutor(),
                new DecisionTableNodeExecutor(), new DecisionTreeNodeExecutor(), ruleSet));
        NodeRunner runner = new NodeRunner(registry, pool);
        runnerRef.set(runner);

        Map<AggregatePolicy, DecisionAggregator> aggregators = Map.of(
                AggregatePolicy.PRIORITY, new PriorityAggregator(),
                AggregatePolicy.SCORE_THRESHOLD, new ScoreThresholdAggregator());
        FlowExecutor flow = new FlowExecutor(
                new SerialStageExecutor(runner), new ParallelStageExecutor(runner, pool), aggregators);

        CompiledFlow compiled = buildScoreFlow(registry);

        run(flow, compiled, "新买家大额", Map.of(
                "buyer", Map.of("level", "NEW"), "order", Map.of("amount", 12800), "txCount", 25));
        run(flow, compiled, "VIP小额", Map.of(
                "buyer", Map.of("level", "VIP"), "order", Map.of("amount", 1000), "txCount", 1));

        pool.shutdownNow();
    }

    private static void run(FlowExecutor flow, CompiledFlow compiled,
                            String label, Map<String, Object> facts) {
        DecisionContext ctx = new DecisionContext("REQ-" + label, "score_flow", "BIZ", facts);
        FlowResult fr = flow.execute(ctx, compiled);
        System.out.printf("[%s] decision=%s totalScore=%d hitNodes=%s cost=%dms%n",
                label, fr.getDecision(), fr.getTotalScore(), fr.getHitNodes(), fr.getCostMillis());
    }

    private static CompiledFlow buildScoreFlow(NodeExecutorRegistry registry) {
        // 评分卡：买家等级
        ScoreCardDef.Bin newBin = new ScoreCardDef.Bin(); newBin.setMatch("NEW"); newBin.setScore(30);
        ScoreCardDef.Bin vipBin = new ScoreCardDef.Bin(); vipBin.setMatch("VIP"); vipBin.setScore(0);
        ScoreCardDef.Attribute lv = new ScoreCardDef.Attribute();
        lv.setFeatureKey("fact.buyer.level"); lv.setBins(List.of(newBin, vipBin));
        ScoreCardDef sc = new ScoreCardDef(); sc.setAttributes(List.of(lv));
        NodeDefinition scNode = NodeDefinition.builder()
                .nodeId("SC").nodeName("买家评分卡").nodeType(NodeType.SCORECARD).order(10).scoreCardDef(sc).build();

        // 决策表：大额加分
        DecisionTableDef.Cell amtCell = new DecisionTableDef.Cell();
        amtCell.setLeftFact("fact.order.amount"); amtCell.setOperator("GT"); amtCell.setRightValue(10000);
        DecisionTableDef.Row amtRow = new DecisionTableDef.Row();
        amtRow.setScore(40); amtRow.setWhen(List.of(amtCell));
        DecisionTableDef dt = new DecisionTableDef(); dt.setHitPolicy("FIRST"); dt.setRows(List.of(amtRow));
        NodeDefinition dtNode = NodeDefinition.builder()
                .nodeId("DT").nodeName("金额加分").nodeType(NodeType.DECISION_TABLE).order(20).decisionTableDef(dt).build();

        // 规则集：高频交易加 REVIEW（演示 RuleSet）
        OperatorDef freqOp = new OperatorDef();
        freqOp.setLeftFact("fact.txCount"); freqOp.setOperator("GT"); freqOp.setRightValue(20);
        NodeDefinition freqRule = NodeDefinition.builder()
                .nodeId("FREQ").nodeName("高频").nodeType(NodeType.OPERATOR).order(1)
                .operatorDef(freqOp).decisionOnHit(Decision.REVIEW).failPolicy(FailPolicy.SKIP)
                .timeoutMillis(500).build();
        RuleSetDef rsDef = new RuleSetDef(); rsDef.setHitPolicy("ANY_HIT"); rsDef.setRules(List.of(freqRule));
        NodeDefinition rsNode = NodeDefinition.builder()
                .nodeId("RS").nodeName("行为规则集").nodeType(NodeType.RULE_SET).order(30).ruleSetDef(rsDef).build();

        StageDefinition stage = StageDefinition.builder()
                .stageId("s1").stageName("评分与规则").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(scNode, dtNode, rsNode)).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("score_flow").flowName("综合评分流程").version(1).enabled(true)
                .aggregatePolicy(AggregatePolicy.SCORE_THRESHOLD)
                .metadata(Map.of("review", 60, "reject", 85))
                .stages(List.of(stage)).build();

        List<CompiledStage> stages = def.getStages().stream()
                .map(s -> new CompiledStage(s,
                        s.getNodes().stream()
                                .map(n -> registry.getRequired(n.getNodeType()).compile(n)).toList()))
                .toList();
        return new CompiledFlow(def, stages);
    }
}
```

- [ ] **Step 2: 运行 M4Demo，确认输出合理**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-core -q exec:java -Dexec.mainClass=io.openrule.core.demo.M4Demo 2>&1 | grep -E "decision="`
（若无 exec 插件，则改用编译后 java 运行：`mvn -pl openrule-core -q test-compile && java -cp openrule-core/target/classes io.openrule.core.demo.M4Demo`）
Expected: 输出两行；`新买家大额` decision=REVIEW totalScore=70；`VIP小额` decision=PASS totalScore=0。

- [ ] **Step 3: 全 reactor 验收测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn clean test 2>&1 | grep -E "Tests run|BUILD"`
Expected: `BUILD SUCCESS`，core + spring + api 全模块测试全绿。

- [ ] **Step 4: 全 reactor install（确认四件制品可安装）**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -q clean install 2>&1 | tail -20`
Expected: `BUILD SUCCESS`，安装 openrule-parent / openrule-core / openrule-spring / openrule-api。

- [ ] **Step 5: 写 session 记录** `docs/sessions/2026-06-23-openrule-m4-session.md`

```markdown
# OpenRule M4（高级节点）Session 记录 · 2026-06-23

## 完成
- 抽出共享 `OperatorMatcher`，`OperatorNodeExecutor` 委托（M1 回归绿）。
- `DecisionAggregator` SPI 增 `FlowDefinition` 参 + `CompiledFlow.getDefinition()`。
- 四个纯 core 执行器：ScoreCard（区间/枚举/默认分箱 + 加权 + 阈值）、DecisionTable（FIRST/PRIORITY/COLLECT）、DecisionTree（深度≤20）、RuleSet（Supplier 破环 + 四 hitPolicy + 内部 SERIAL）。
- `ScoreThresholdAggregator`（总分→metadata 阈值）。
- Spring 自动装配 5 个新 bean（RuleSet 经 `ObjectProvider<NodeRunner>` 破环）+ 组合 simulate 端到端。
- `M4Demo` 组合流程跑通；全 reactor `mvn clean test` / `install` 绿。

## 验收对照（设计 §12）
- [x] NodeDefinition 加 4 def 字段，OPERATOR 流程不受影响。
- [x] OperatorMatcher 抽出，M1 的 56 测试全绿。
- [x] 四执行器 + ScoreThresholdAggregator 单测通过。
- [x] SPI 增参后 PriorityAggregator/FlowExecutor 适配，旧测试全绿。
- [x] M4Demo 跑通；全模块 mvn test 绿（JDK 21）。
- [x] 新 5 bean 自动装配 + 组合 simulate 端到端通过。

## 下一步（路线 B）
M3（脚本沙箱：Groovy/JS + JavaNative + simulate 增强）。SubFlow 作为独立小里程碑（core SubFlowResolver SPI + spring FlowLoader 适配 + 递归≤5）。
```

- [ ] **Step 6: 提交**

```bash
git add openrule-core/src/main/java/io/openrule/core/demo/M4Demo.java \
        docs/sessions/2026-06-23-openrule-m4-session.md
git commit -m "feat(core): M4Demo 组合流程 + M4 session 记录（全 reactor 验收绿）"
```

---

## 验收清单（对照设计 §12）

- [ ] `NodeDefinition` 新增 `scoreCardDef`/`decisionTableDef`/`decisionTreeDef`/`ruleSetDef`，OPERATOR 既有流程不受影响。
- [ ] `OperatorMatcher` 抽出、`OperatorNodeExecutor` 委托，M1 的 56 测试全绿。
- [ ] 四个执行器 + `ScoreThresholdAggregator` 实现且各自单测通过。
- [ ] `DecisionAggregator` SPI 增 `FlowDefinition` 参，`PriorityAggregator`/`FlowExecutor`/`CompiledFlow` 适配，旧测试更新后全绿。
- [ ] `M4Demo` 组合流程跑通；全模块 `mvn clean test` 绿（JDK 21）。
- [ ] 新 5 bean 自动装配（autoconfig）+ 组合 simulate 端到端通过。
```
