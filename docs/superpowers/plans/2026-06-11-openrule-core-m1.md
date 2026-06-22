# OpenRule M1 · openrule-core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付纯 Java 决策内核 `openrule-core`，用 Builder 组装的流程端到端跑通并产出正确 `FlowResult`，`mvn test` 全绿。

**Architecture:** 自底向上、依赖序、逐单元 TDD。领域模型（不可变 facts / 受控 variables / 隔离 outputs）→ NodeExecutor SPI → OperatorNodeExecutor → NodeRunner（超时 + FailPolicy）→ Serial/Parallel Stage 执行器（并行隔离 + 按 order 单线程合并）→ PriorityAggregator → FlowExecutor。全程零 Spring、零 JSON、零中间件。

**Tech Stack:** Java 21（虚拟线程）· Maven 单模块 · Lombok · JUnit 5 · AssertJ。

**配套设计文档：** `docs/superpowers/specs/2026-06-11-openrule-core-m1-design.md`

---

## 文件结构总览

```
open-rule/
├── pom.xml
└── src/
    ├── main/java/io/openrule/core/
    │   ├── enums/        NodeType ExecutionMode Decision FailPolicy AggregatePolicy
    │   ├── exception/    RuleEngineException FlowValidationException
    │   ├── context/      FactMap DecisionContext
    │   ├── result/       NodeResult StageResult FlowResult
    │   ├── definition/   FlowDefinition StageDefinition NodeDefinition
    │   │   └── defs/     OperatorDef
    │   ├── spi/          NodeExecutor CompiledNode DecisionAggregator AggregateOutcome
    │   ├── runtime/      NodeExecutorRegistry NodeRunner SerialStageExecutor
    │   │                 ParallelStageExecutor FlowExecutor CompiledFlow CompiledStage
    │   ├── aggregate/    PriorityAggregator
    │   └── executor/     OperatorNodeExecutor
    │   └── demo/         M1Demo (main 验收)
    └── test/java/io/openrule/core/  (镜像测试包)
```

---

## Task 1: Maven 工程骨架

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/io/openrule/core/package-info.java`
- Test: `src/test/java/io/openrule/core/SanityTest.java`

- [ ] **Step 1: 建 git 仓库（仅首次）**

Run:
```bash
cd /Users/ycr/IdeaProjects/Sandbox/open-rule
git init
printf "target/\n.idea/\n*.iml\n.DS_Store\n" > .gitignore
```
Expected: `Initialized empty Git repository`

- [ ] **Step 2: 写 pom.xml**

Create `pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.openrule</groupId>
    <artifactId>openrule-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <lombok.version>1.18.34</lombok.version>
        <junit.version>5.10.3</junit.version>
        <assertj.version>3.26.3</assertj.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.3.1</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 写 package-info.java**

Create `src/main/java/io/openrule/core/package-info.java`:
```java
/**
 * OpenRule 执行内核（零 Spring 依赖，纯 Java）。
 */
package io.openrule.core;
```

- [ ] **Step 4: 写 sanity 测试**

Create `src/test/java/io/openrule/core/SanityTest.java`:
```java
package io.openrule.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SanityTest {
    @Test
    void java21_and_toolchain_works() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
```

- [ ] **Step 5: 跑测试，确认工程可编译可测**

Run: `mvn -q test`
Expected: BUILD SUCCESS，SanityTest 通过。

- [ ] **Step 6: 提交**

```bash
git add pom.xml .gitignore src
git commit -m "chore: openrule-core Maven 骨架 (Java 21 + Lombok + JUnit5 + AssertJ)"
```

---

## Task 2: 枚举与异常

**Files:**
- Create: `src/main/java/io/openrule/core/enums/NodeType.java`
- Create: `src/main/java/io/openrule/core/enums/ExecutionMode.java`
- Create: `src/main/java/io/openrule/core/enums/Decision.java`
- Create: `src/main/java/io/openrule/core/enums/FailPolicy.java`
- Create: `src/main/java/io/openrule/core/enums/AggregatePolicy.java`
- Create: `src/main/java/io/openrule/core/exception/RuleEngineException.java`
- Create: `src/main/java/io/openrule/core/exception/FlowValidationException.java`
- Test: `src/test/java/io/openrule/core/enums/DecisionTest.java`

- [ ] **Step 1: 写 Decision 的失败测试**

Create `src/test/java/io/openrule/core/enums/DecisionTest.java`:
```java
package io.openrule.core.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionTest {

    @Test
    void riskOrder_isRejectGtReviewGtLimitGtPass() {
        assertThat(Decision.REJECT.riskierThan(Decision.REVIEW)).isTrue();
        assertThat(Decision.REVIEW.riskierThan(Decision.LIMIT)).isTrue();
        assertThat(Decision.LIMIT.riskierThan(Decision.PASS)).isTrue();
    }

    @Test
    void riskierThan_isStrict_notReflexive() {
        assertThat(Decision.REJECT.riskierThan(Decision.REJECT)).isFalse();
        assertThat(Decision.PASS.riskierThan(Decision.REJECT)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=DecisionTest test`
Expected: 编译失败（Decision 尚不存在）。

- [ ] **Step 3: 写五个枚举**

Create `NodeType.java`:
```java
package io.openrule.core.enums;

/** 节点类型。M1 仅 OPERATOR 有执行器，其余为后续里程碑预留。 */
public enum NodeType {
    OPERATOR,
    JAVA_NATIVE,
    SCRIPT_GROOVY,
    SCRIPT_JS,
    SCRIPT_PYTHON,
    RULE_SET,
    SCORECARD,
    DECISION_TABLE,
    DECISION_TREE,
    SUB_FLOW
}
```

Create `ExecutionMode.java`:
```java
package io.openrule.core.enums;

public enum ExecutionMode { SERIAL, PARALLEL }
```

Create `Decision.java`:
```java
package io.openrule.core.enums;

/**
 * 决策结果。ordinal 越大风险越高：PASS &lt; LIMIT &lt; REVIEW &lt; REJECT。
 */
public enum Decision {
    PASS, LIMIT, REVIEW, REJECT;

    /** 本决策是否比 other 风险更高（严格大于）。 */
    public boolean riskierThan(Decision other) {
        return this.ordinal() > other.ordinal();
    }
}
```

Create `FailPolicy.java`:
```java
package io.openrule.core.enums;

/** 节点异常治理策略。 */
public enum FailPolicy {
    SKIP,   // 记录后跳过，流程继续
    REVIEW, // 产出 REVIEW 建议，流程继续
    REJECT, // 产出 REJECT 建议并终止
    ABORT   // 整个流程异常终止，向调用方抛错
}
```

Create `AggregatePolicy.java`:
```java
package io.openrule.core.enums;

/** 决策聚合策略。M1 仅实现 PRIORITY。 */
public enum AggregatePolicy {
    PRIORITY,
    FIRST_TERMINAL,
    SCORE_THRESHOLD
}
```

- [ ] **Step 4: 写两个异常**

Create `RuleEngineException.java`:
```java
package io.openrule.core.exception;

/** 引擎运行期异常。 */
public class RuleEngineException extends RuntimeException {
    public RuleEngineException(String message) { super(message); }
    public RuleEngineException(String message, Throwable cause) { super(message, cause); }
}
```

Create `FlowValidationException.java`:
```java
package io.openrule.core.exception;

/** 流程/节点配置校验失败（保存时抛出，阻止保存）。 */
public class FlowValidationException extends RuntimeException {
    public FlowValidationException(String message) { super(message); }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -Dtest=DecisionTest test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src
git commit -m "feat: 五枚举(NodeType/ExecutionMode/Decision/FailPolicy/AggregatePolicy) 与异常"
```

---

## Task 3: FactMap（不可变事实 + 点路径）

**Files:**
- Create: `src/main/java/io/openrule/core/context/FactMap.java`
- Test: `src/test/java/io/openrule/core/context/FactMapTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/context/FactMapTest.java`:
```java
package io.openrule.core.context;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FactMapTest {

    @Test
    void get_returnsTopLevelValue() {
        FactMap fm = new FactMap(Map.of("amount", 100));
        assertThat(fm.get("amount")).isEqualTo(100);
    }

    @Test
    void getByPath_traversesNestedMaps() {
        Map<String, Object> order = new HashMap<>();
        order.put("amount", 12800);
        FactMap fm = new FactMap(Map.of("order", order));
        assertThat(fm.getByPath("order.amount")).isEqualTo(12800);
    }

    @Test
    void getByPath_returnsNullForMissingOrNonMap() {
        FactMap fm = new FactMap(Map.of("order", Map.of("amount", 1)));
        assertThat(fm.getByPath("order.missing")).isNull();
        assertThat(fm.getByPath("order.amount.deep")).isNull();
        assertThat(fm.getByPath("nope")).isNull();
    }

    @Test
    void isImmutable_mutatingSourceDoesNotLeak() {
        Map<String, Object> src = new HashMap<>();
        src.put("a", 1);
        FactMap fm = new FactMap(src);
        src.put("a", 999);
        src.put("b", 2);
        assertThat(fm.get("a")).isEqualTo(1);
        assertThat(fm.get("b")).isNull();
    }

    @Test
    void toleratesNullValues() {
        Map<String, Object> src = new HashMap<>();
        src.put("nullable", null);
        FactMap fm = new FactMap(src);
        assertThat(fm.get("nullable")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=FactMapTest test`
Expected: 编译失败（FactMap 不存在）。

- [ ] **Step 3: 实现 FactMap**

Create `FactMap.java`:
```java
package io.openrule.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部输入事实的不可变封装（防御性拷贝，C7）。
 * 顶层一层防修改；支持 "order.amount" 点路径逐层取值。
 * 用 HashMap+unmodifiable 而非 Map.copyOf：容忍 null 值。
 */
public class FactMap {

    private final Map<String, Object> data;

    public FactMap(Map<String, Object> source) {
        this.data = Collections.unmodifiableMap(
                new HashMap<>(source == null ? Map.of() : source));
    }

    public Object get(String key) {
        return data.get(key);
    }

    public Object getByPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = data.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=FactMapTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: FactMap 不可变事实封装 + 点路径取值"
```

---

## Task 4: 结果载体（NodeResult / StageResult / FlowResult）

**Files:**
- Create: `src/main/java/io/openrule/core/result/NodeResult.java`
- Create: `src/main/java/io/openrule/core/result/StageResult.java`
- Create: `src/main/java/io/openrule/core/result/FlowResult.java`
- Test: `src/test/java/io/openrule/core/result/NodeResultTest.java`

> 先建结果载体，再建 DecisionContext（Task 5 依赖 NodeResult）。三者均为纯数据 POJO。

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/result/NodeResultTest.java`:
```java
package io.openrule.core.result;

import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NodeResultTest {

    @Test
    void builder_defaultsOutputsAndDetailsToEmptyMaps() {
        NodeResult r = NodeResult.builder().nodeId("n1").build();
        assertThat(r.getOutputs()).isEmpty();
        assertThat(r.getDetails()).isEmpty();
    }

    @Test
    void toBuilder_allowsImmutableCopyWithOverride() {
        NodeResult base = NodeResult.builder()
                .nodeId("n1").nodeType(NodeType.OPERATOR).hit(true).build();
        NodeResult withDecision = base.toBuilder().decision(Decision.REJECT).build();
        assertThat(withDecision.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(withDecision.isHit()).isTrue();
        assertThat(base.getDecision()).isNull();
    }

    @Test
    void stageResult_ofAndSkipped() {
        StageResult ok = StageResult.of("s1", List.of(NodeResult.builder().nodeId("n").build()));
        assertThat(ok.getStageId()).isEqualTo("s1");
        assertThat(ok.isSkipped()).isFalse();
        assertThat(ok.getNodeResults()).hasSize(1);

        StageResult sk = StageResult.skipped("s2");
        assertThat(sk.isSkipped()).isTrue();
        assertThat(sk.getNodeResults()).isEmpty();
    }

    @Test
    void flowResult_buildsWithAllFields() {
        FlowResult fr = FlowResult.builder()
                .requestId("REQ").flowId("f").bizId("b")
                .decision(Decision.PASS).reason("ok").totalScore(12)
                .hitNodes(List.of("n1")).costMillis(5).build();
        assertThat(fr.getDecision()).isEqualTo(Decision.PASS);
        assertThat(fr.getTotalScore()).isEqualTo(12);
        assertThat(fr.getHitNodes()).containsExactly("n1");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=NodeResultTest test`
Expected: 编译失败。

- [ ] **Step 3: 写 NodeResult**

Create `src/main/java/io/openrule/core/result/NodeResult.java`:
```java
package io.openrule.core.result;

import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点统一输出。并行隔离的核心：并行节点所有写入封装在 outputs 返回，
 * 由 StageExecutor 单线程合并进 context.variables（C1/C2）。
 */
@Getter
@Builder(toBuilder = true)
public class NodeResult {

    private String   nodeId;
    private String   nodeName;
    private NodeType nodeType;

    private boolean  hit;
    private Decision decision;
    private int      score;
    private boolean  stop;
    private String   reason;

    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    private boolean success;
    private String  errorCode;
    private String  errorMessage;
    private long    costMillis;
    private boolean skipped;
}
```

- [ ] **Step 4: 写 StageResult**

Create `StageResult.java`:
```java
package io.openrule.core.result;

import lombok.Getter;

import java.util.List;

/** Stage 执行结果。 */
@Getter
public class StageResult {

    private final String stageId;
    private final List<NodeResult> nodeResults;
    private final boolean skipped;

    private StageResult(String stageId, List<NodeResult> nodeResults, boolean skipped) {
        this.stageId = stageId;
        this.nodeResults = nodeResults;
        this.skipped = skipped;
    }

    public static StageResult of(String stageId, List<NodeResult> results) {
        return new StageResult(stageId, results, false);
    }

    public static StageResult skipped(String stageId) {
        return new StageResult(stageId, List.of(), true);
    }
}
```

- [ ] **Step 5: 写 FlowResult**

Create `FlowResult.java`:
```java
package io.openrule.core.result;

import io.openrule.core.enums.Decision;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** 流程最终结果。 */
@Getter
@Builder
public class FlowResult {
    private String   requestId;
    private String   flowId;
    private String   bizId;
    private Decision decision;
    private String   reason;
    private int      totalScore;
    private List<String> hitNodes;
    private List<NodeResult> nodeResults;
    private long     costMillis;
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -q -Dtest=NodeResultTest test`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src
git commit -m "feat: NodeResult(toBuilder/隔离 outputs) + StageResult + FlowResult"
```

---

## Task 5: DecisionContext（执行上下文）

**Files:**
- Create: `src/main/java/io/openrule/core/context/DecisionContext.java`
- Test: `src/test/java/io/openrule/core/context/DecisionContextTest.java`

> 依赖 `FactMap`（Task 3）与 `NodeResult`（Task 4）——均已建好，无需占位。

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/context/DecisionContextTest.java`:
```java
package io.openrule.core.context;

import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionContextTest {

    private DecisionContext ctx() {
        return new DecisionContext("REQ1", "flowA", "BIZ1",
                Map.of("order", Map.of("amount", 100)));
    }

    @Test
    void exposesReadonlyIdsAndFacts() {
        DecisionContext c = ctx();
        assertThat(c.getRequestId()).isEqualTo("REQ1");
        assertThat(c.getFlowId()).isEqualTo("flowA");
        assertThat(c.getBizId()).isEqualTo("BIZ1");
        assertThat(c.getFacts().getByPath("order.amount")).isEqualTo(100);
    }

    @Test
    void variables_areReadWrite() {
        DecisionContext c = ctx();
        assertThat(c.variable("k")).isNull();
        c.putVariable("k", 7);
        assertThat(c.variable("k")).isEqualTo(7);
    }

    @Test
    void stop_isVisible() {
        DecisionContext c = ctx();
        assertThat(c.isStopped()).isFalse();
        c.stop();
        assertThat(c.isStopped()).isTrue();
    }

    @Test
    void nodeResults_accumulate() {
        DecisionContext c = ctx();
        c.addNodeResult(NodeResult.builder().nodeId("a").build());
        c.addNodeResult(NodeResult.builder().nodeId("b").build());
        assertThat(c.getNodeResults()).hasSize(2);
    }

    @Test
    void finalDecision_isSettable() {
        DecisionContext c = ctx();
        c.setFinalDecision(Decision.REVIEW);
        c.setFinalReason("超阈值");
        assertThat(c.getFinalDecision()).isEqualTo(Decision.REVIEW);
        assertThat(c.getFinalReason()).isEqualTo("超阈值");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=DecisionContextTest test`
Expected: 编译失败（DecisionContext 不存在）。

- [ ] **Step 3: 实现 DecisionContext**

Create `DecisionContext.java`:
```java
package io.openrule.core.context;

import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行上下文。并发类型选择是硬性约束（C9）：
 * variables=ConcurrentHashMap，nodeResults=CopyOnWriteArrayList，控制位 volatile。
 * 并行节点禁止直接写本对象（C1）——写入走 NodeResult.outputs。
 */
public class DecisionContext {

    private final String requestId;
    private final String flowId;
    private final String bizId;
    private final FactMap facts;

    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final List<NodeResult> nodeResults = new CopyOnWriteArrayList<>();

    private volatile boolean stopped = false;
    private volatile Decision finalDecision;
    private volatile String finalReason;

    public DecisionContext(String requestId, String flowId,
                           String bizId, Map<String, Object> facts) {
        this.requestId = requestId;
        this.flowId = flowId;
        this.bizId = bizId;
        this.facts = new FactMap(facts);
    }

    public String getRequestId() { return requestId; }
    public String getFlowId()    { return flowId; }
    public String getBizId()     { return bizId; }
    public FactMap getFacts()    { return facts; }

    public Object fact(String key)     { return facts.get(key); }
    public Object variable(String key) { return variables.get(key); }
    public boolean isStopped()         { return stopped; }
    public List<NodeResult> getNodeResults() { return nodeResults; }

    public void putVariable(String k, Object v) { variables.put(k, v); }
    public void stop()                          { this.stopped = true; }
    public void addNodeResult(NodeResult r)     { nodeResults.add(r); }

    public Decision getFinalDecision()            { return finalDecision; }
    public void setFinalDecision(Decision d)      { this.finalDecision = d; }
    public String getFinalReason()                { return finalReason; }
    public void setFinalReason(String r)          { this.finalReason = r; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=DecisionContextTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: DecisionContext 上下文(并发类型按 C9)"
```

---

## Task 6: 定义模型

**Files:**
- Create: `src/main/java/io/openrule/core/definition/defs/OperatorDef.java`
- Create: `src/main/java/io/openrule/core/definition/NodeDefinition.java`
- Create: `src/main/java/io/openrule/core/definition/StageDefinition.java`
- Create: `src/main/java/io/openrule/core/definition/FlowDefinition.java`
- Test: `src/test/java/io/openrule/core/definition/DefinitionTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/definition/DefinitionTest.java`:
```java
package io.openrule.core.definition;

import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefinitionTest {

    @Test
    void buildsNestedFlowDefinition() {
        OperatorDef op = new OperatorDef();
        op.setLeftFact("fact.order.amount");
        op.setOperator("GT");
        op.setRightValue(50000);

        NodeDefinition node = NodeDefinition.builder()
                .nodeId("AMOUNT_LIMIT").nodeName("金额上限")
                .nodeType(NodeType.OPERATOR).order(20)
                .operatorDef(op)
                .decisionOnHit(Decision.REJECT).stopOnHit(true)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(100)
                .build();

        StageDefinition stage = StageDefinition.builder()
                .stageId("s1").stageName("硬规则").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node)).build();

        FlowDefinition flow = FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").version(1).enabled(true)
                .aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stage)).build();

        assertThat(flow.getStages().get(0).getNodes().get(0).getOperatorDef().getOperator())
                .isEqualTo("GT");
        assertThat(flow.getAggregatePolicy()).isEqualTo(AggregatePolicy.PRIORITY);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=DefinitionTest test`
Expected: 编译失败。

- [ ] **Step 3: 写 OperatorDef**

Create `OperatorDef.java`:
```java
package io.openrule.core.definition.defs;

import lombok.Data;

/** OPERATOR 节点配置。leftFact 是 fact./var. 引用；rightValue 是字面量（数值/集合等）。 */
@Data
public class OperatorDef {
    private String leftFact;
    private String operator;
    private Object rightValue;
}
```

- [ ] **Step 4: 写 NodeDefinition（M1 字段集）**

Create `NodeDefinition.java`:
```java
package io.openrule.core.definition;

import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import lombok.Builder;
import lombok.Data;

/**
 * 节点定义。M1 仅承载 OPERATOR 所需字段；后续里程碑只加不改（脚本/评分卡等字段后补）。
 */
@Data
@Builder
public class NodeDefinition {
    private String   nodeId;
    private String   nodeName;
    private NodeType nodeType;
    private int      order;

    private OperatorDef operatorDef;

    private Decision decisionOnHit;
    private boolean  stopOnHit;

    private FailPolicy failPolicy;
    private long       timeoutMillis;
}
```

- [ ] **Step 5: 写 StageDefinition**

Create `StageDefinition.java`:
```java
package io.openrule.core.definition;

import io.openrule.core.enums.ExecutionMode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StageDefinition {
    private String        stageId;
    private String        stageName;
    private int           order;
    private ExecutionMode executionMode;
    private boolean       skipWhenStopped;
    private long          stageTimeoutMillis;
    private List<NodeDefinition> nodes;
}
```

- [ ] **Step 6: 写 FlowDefinition**

Create `FlowDefinition.java`:
```java
package io.openrule.core.definition;

import io.openrule.core.enums.AggregatePolicy;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class FlowDefinition {
    private String  flowId;
    private String  flowName;
    private int     version;
    private boolean enabled;
    private AggregatePolicy aggregatePolicy;
    private List<StageDefinition> stages;
    private Map<String, Object>   metadata;
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -q -Dtest=DefinitionTest test`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add src
git commit -m "feat: 定义模型(FlowDefinition/StageDefinition/NodeDefinition/OperatorDef)"
```

---

## Task 7: SPI 与编译产物容器

**Files:**
- Create: `src/main/java/io/openrule/core/spi/CompiledNode.java`
- Create: `src/main/java/io/openrule/core/spi/NodeExecutor.java`
- Create: `src/main/java/io/openrule/core/spi/AggregateOutcome.java`
- Create: `src/main/java/io/openrule/core/spi/DecisionAggregator.java`
- Create: `src/main/java/io/openrule/core/runtime/CompiledStage.java`
- Create: `src/main/java/io/openrule/core/runtime/CompiledFlow.java`
- Test: `src/test/java/io/openrule/core/spi/CompiledTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/spi/CompiledTest.java`:
```java
package io.openrule.core.spi;

import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.runtime.CompiledStage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CompiledTest {

    @Test
    void compiledNode_holdsDefinitionAndArtifact() {
        NodeDefinition def = NodeDefinition.builder().nodeId("n").nodeType(NodeType.OPERATOR).build();
        CompiledNode cn = new CompiledNode(def, "ARTIFACT");
        assertThat(cn.getDefinition().getNodeId()).isEqualTo("n");
        assertThat(cn.getCompiledArtifact()).isEqualTo("ARTIFACT");
    }

    @Test
    void compiledStage_delegatesGetters() {
        NodeDefinition def = NodeDefinition.builder().nodeId("n").nodeType(NodeType.OPERATOR).build();
        StageDefinition sd = StageDefinition.builder()
                .stageId("s1").executionMode(ExecutionMode.PARALLEL)
                .skipWhenStopped(true).stageTimeoutMillis(8000).build();
        CompiledStage cs = new CompiledStage(sd, List.of(new CompiledNode(def, null)));
        assertThat(cs.getStageId()).isEqualTo("s1");
        assertThat(cs.getExecutionMode()).isEqualTo(ExecutionMode.PARALLEL);
        assertThat(cs.isSkipWhenStopped()).isTrue();
        assertThat(cs.getStageTimeoutMillis()).isEqualTo(8000);
        assertThat(cs.getNodes()).hasSize(1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=CompiledTest test`
Expected: 编译失败。

- [ ] **Step 3: 写 CompiledNode**

Create `CompiledNode.java`:
```java
package io.openrule.core.spi;

import io.openrule.core.definition.NodeDefinition;

/** 节点编译产物容器（compiledArtifact 在 M1 通常为 null）。 */
public class CompiledNode {
    private final NodeDefinition definition;
    private final Object compiledArtifact;

    public CompiledNode(NodeDefinition definition, Object compiledArtifact) {
        this.definition = definition;
        this.compiledArtifact = compiledArtifact;
    }

    public NodeDefinition getDefinition() { return definition; }
    public Object getCompiledArtifact()   { return compiledArtifact; }
}
```

- [ ] **Step 4: 写 NodeExecutor SPI**

Create `NodeExecutor.java`:
```java
package io.openrule.core.spi;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.result.NodeResult;

/**
 * 三阶段 SPI：validate(保存时) → compile(加载时) → execute(运行时)。
 * execute 约束：禁写 context（C1）；不吞异常，直接抛出由 NodeRunner 统一治理（C8）。
 */
public interface NodeExecutor {

    NodeType supportType();

    default void validate(NodeDefinition node) throws FlowValidationException {}

    default CompiledNode compile(NodeDefinition node) {
        return new CompiledNode(node, null);
    }

    NodeResult execute(DecisionContext context, CompiledNode compiled);
}
```

- [ ] **Step 5: 写 AggregateOutcome 与 DecisionAggregator**

Create `AggregateOutcome.java`:
```java
package io.openrule.core.spi;

import io.openrule.core.enums.Decision;
import java.util.List;

public record AggregateOutcome(Decision decision, String reason,
                               int totalScore, List<String> hitNodes) {}
```

Create `DecisionAggregator.java`:
```java
package io.openrule.core.spi;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.result.NodeResult;
import java.util.List;

public interface DecisionAggregator {
    AggregatePolicy supportPolicy();
    AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx);
}
```

- [ ] **Step 6: 写 CompiledStage 与 CompiledFlow**

Create `CompiledStage.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.spi.CompiledNode;
import java.util.List;

/** Stage 编译产物：定义 + 已编译节点（按 order 排序后）。 */
public class CompiledStage {
    private final StageDefinition definition;
    private final List<CompiledNode> nodes;

    public CompiledStage(StageDefinition definition, List<CompiledNode> nodes) {
        this.definition = definition;
        this.nodes = nodes;
    }

    public String getStageId()              { return definition.getStageId(); }
    public ExecutionMode getExecutionMode() { return definition.getExecutionMode(); }
    public boolean isSkipWhenStopped()      { return definition.isSkipWhenStopped(); }
    public long getStageTimeoutMillis()     { return definition.getStageTimeoutMillis(); }
    public List<CompiledNode> getNodes()    { return nodes; }
}
```

Create `CompiledFlow.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import java.util.List;

/** Flow 编译产物：定义 + 已编译 Stage（按 order 排序后）。 */
public class CompiledFlow {
    private final FlowDefinition definition;
    private final List<CompiledStage> stages;

    public CompiledFlow(FlowDefinition definition, List<CompiledStage> stages) {
        this.definition = definition;
        this.stages = stages;
    }

    public String getFlowId()                  { return definition.getFlowId(); }
    public int getVersion()                    { return definition.getVersion(); }
    public AggregatePolicy getAggregatePolicy(){ return definition.getAggregatePolicy(); }
    public List<CompiledStage> getStages()     { return stages; }
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -q -Dtest=CompiledTest test`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add src
git commit -m "feat: NodeExecutor SPI + DecisionAggregator + Compiled 产物容器"
```

---

## Task 8: NodeExecutorRegistry

**Files:**
- Create: `src/main/java/io/openrule/core/runtime/NodeExecutorRegistry.java`
- Test: `src/test/java/io/openrule/core/runtime/NodeExecutorRegistryTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/runtime/NodeExecutorRegistryTest.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeExecutorRegistryTest {

    static class StubExecutor implements NodeExecutor {
        private final NodeType type;
        StubExecutor(NodeType type) { this.type = type; }
        public NodeType supportType() { return type; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            return NodeResult.builder().nodeId("x").build();
        }
    }

    @Test
    void routesByType() {
        NodeExecutor op = new StubExecutor(NodeType.OPERATOR);
        NodeExecutorRegistry reg = new NodeExecutorRegistry(List.of(op));
        assertThat(reg.getRequired(NodeType.OPERATOR)).isSameAs(op);
    }

    @Test
    void duplicateType_throws() {
        assertThatThrownBy(() -> new NodeExecutorRegistry(
                List.of(new StubExecutor(NodeType.OPERATOR), new StubExecutor(NodeType.OPERATOR))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void missingType_throws() {
        NodeExecutorRegistry reg = new NodeExecutorRegistry(List.of(new StubExecutor(NodeType.OPERATOR)));
        assertThatThrownBy(() -> reg.getRequired(NodeType.SCRIPT_GROOVY))
                .isInstanceOf(RuleEngineException.class)
                .hasMessageContaining("No executor");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=NodeExecutorRegistryTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 NodeExecutorRegistry**

Create `NodeExecutorRegistry.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.spi.NodeExecutor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** NodeType → NodeExecutor 路由表。构造时收集所有执行器，重复类型即缺陷。 */
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
        if (e == null) {
            throw new RuleEngineException("No executor for NodeType: " + type);
        }
        return e;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=NodeExecutorRegistryTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: NodeExecutorRegistry 类型路由(重复/缺失校验)"
```

---

## Task 9: OperatorNodeExecutor（M1 唯一执行器）

**Files:**
- Create: `src/main/java/io/openrule/core/executor/OperatorNodeExecutor.java`
- Test: `src/test/java/io/openrule/core/executor/OperatorNodeExecutorTest.java`

- [ ] **Step 1: 写失败测试（运算符矩阵 + 取值路径 + 校验）**

Create `src/test/java/io/openrule/core/executor/OperatorNodeExecutorTest.java`:
```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorNodeExecutorTest {

    private final OperatorNodeExecutor exec = new OperatorNodeExecutor();

    private boolean run(String leftFact, String op, Object right, Map<String, Object> facts) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(leftFact);
        def.setOperator(op);
        def.setRightValue(right);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("OP").nodeType(NodeType.OPERATOR).operatorDef(def).build();
        DecisionContext ctx = new DecisionContext("R", "f", "b", facts);
        NodeResult r = exec.execute(ctx, new CompiledNode(node, null));
        assertThat(r.isSuccess()).isTrue();
        return r.isHit();
    }

    @Test
    void numericComparators() {
        Map<String, Object> f = Map.of("order", Map.of("amount", 12800));
        assertThat(run("fact.order.amount", "GT", 5000, f)).isTrue();
        assertThat(run("fact.order.amount", "GTE", 12800, f)).isTrue();
        assertThat(run("fact.order.amount", "LT", 5000, f)).isFalse();
        assertThat(run("fact.order.amount", "LTE", 12800, f)).isTrue();
        assertThat(run("fact.order.amount", "EQ", 12800, f)).isTrue();
        assertThat(run("fact.order.amount", "NE", 1, f)).isTrue();
    }

    @Test
    void between() {
        Map<String, Object> f = Map.of("age", 30);
        assertThat(run("fact.age", "BETWEEN", List.of(18, 60), f)).isTrue();
        assertThat(run("fact.age", "BETWEEN", List.of(40, 60), f)).isFalse();
    }

    @Test
    void stringOps() {
        Map<String, Object> f = Map.of("name", "hello-world");
        assertThat(run("fact.name", "CONTAINS", "world", f)).isTrue();
        assertThat(run("fact.name", "NOT_CONTAINS", "xyz", f)).isTrue();
        assertThat(run("fact.name", "STARTS_WITH", "hello", f)).isTrue();
        assertThat(run("fact.name", "ENDS_WITH", "world", f)).isTrue();
    }

    @Test
    void collectionOps() {
        Map<String, Object> f = Map.of("country", "JP");
        assertThat(run("fact.country", "IN", List.of("JP", "US"), f)).isTrue();
        assertThat(run("fact.country", "NOT_IN", List.of("CN", "US"), f)).isTrue();
    }

    @Test
    void nullOps() {
        Map<String, Object> f = Map.of("present", 1);
        assertThat(run("fact.missing", "IS_NULL", null, f)).isTrue();
        assertThat(run("fact.present", "NOT_NULL", null, f)).isTrue();
    }

    @Test
    void regexOp() {
        Map<String, Object> f = Map.of("phone", "13800138000");
        assertThat(run("fact.phone", "REGEX", "1[0-9]{10}", f)).isTrue();
        assertThat(run("fact.phone", "REGEX", "^9.*", f)).isFalse();
    }

    @Test
    void regexTooLong_throws() {
        Map<String, Object> f = Map.of("v", "x");
        String huge = "a".repeat(513);
        assertThatThrownBy(() -> run("fact.v", "REGEX", huge, f))
                .isInstanceOf(RuleEngineException.class)
                .hasMessageContaining("512");
    }

    @Test
    void resolvesVarPath() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        ctx.putVariable("scorecard.score", 72);
        OperatorDef def = new OperatorDef();
        def.setLeftFact("var.scorecard.score");
        def.setOperator("GTE");
        def.setRightValue(60);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("OP").nodeType(NodeType.OPERATOR).operatorDef(def).build();
        NodeResult r = exec.execute(ctx, new CompiledNode(node, null));
        assertThat(r.isHit()).isTrue();
    }

    @Test
    void hitResult_carriesReasonAndDetails() {
        Map<String, Object> f = Map.of("order", Map.of("amount", 12800));
        OperatorDef def = new OperatorDef();
        def.setLeftFact("fact.order.amount");
        def.setOperator("GT");
        def.setRightValue(5000);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("AMOUNT").nodeName("金额").nodeType(NodeType.OPERATOR).operatorDef(def).build();
        NodeResult r = exec.execute(new DecisionContext("R", "f", "b", f), new CompiledNode(node, null));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getReason()).isNotBlank();
        assertThat(r.getDetails()).containsKeys("leftValue", "operator", "rightValue");
    }

    @Test
    void validate_rejectsIncompleteConfig() {
        NodeDefinition bad = NodeDefinition.builder()
                .nodeId("BAD").nodeType(NodeType.OPERATOR).operatorDef(new OperatorDef()).build();
        assertThatThrownBy(() -> exec.validate(bad))
                .isInstanceOf(FlowValidationException.class);
    }

    @Test
    void supportType_isOperator() {
        assertThat(exec.supportType()).isEqualTo(NodeType.OPERATOR);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=OperatorNodeExecutorTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 OperatorNodeExecutor**

Create `OperatorNodeExecutor.java`:
```java
package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 内置运算符执行器：零编码字段比较。 */
public class OperatorNodeExecutor implements NodeExecutor {

    private static final int MAX_REGEX_LEN = 512;
    private static final Set<String> SUPPORTED = Set.of(
            "GT", "GTE", "LT", "LTE", "EQ", "NE", "BETWEEN",
            "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH",
            "IN", "NOT_IN", "IS_NULL", "NOT_NULL", "REGEX");

    @Override
    public NodeType supportType() { return NodeType.OPERATOR; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        OperatorDef def = node.getOperatorDef();
        if (def == null || def.getLeftFact() == null || def.getOperator() == null) {
            throw new FlowValidationException("OPERATOR 节点配置不完整: " + node.getNodeId());
        }
        if (!SUPPORTED.contains(def.getOperator())) {
            throw new FlowValidationException("不支持的运算符 " + def.getOperator()
                    + " @ " + node.getNodeId());
        }
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        NodeDefinition node = compiled.getDefinition();
        OperatorDef def = node.getOperatorDef();
        long start = System.currentTimeMillis();

        Object leftValue = resolveValue(def.getLeftFact(), ctx);
        boolean hit = compare(leftValue, def.getOperator(), def.getRightValue());

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

    /** "fact.x.y" → facts 点路径；"var.k" → variables；其余视为字面量。 */
    private Object resolveValue(String ref, DecisionContext ctx) {
        if (ref == null) return null;
        if (ref.startsWith("fact.")) return ctx.getFacts().getByPath(ref.substring(5));
        if (ref.startsWith("var."))  return ctx.variable(ref.substring(4));
        return ref;
    }

    private String buildHitReason(OperatorDef def, Object leftValue) {
        return def.getLeftFact() + "(" + leftValue + ") " + def.getOperator()
                + " " + def.getRightValue();
    }

    private boolean compare(Object left, String op, Object right) {
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

    private boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
        }
        return a.toString().equals(b.toString());
    }

    private boolean between(Object left, Object right) {
        List<?> bounds = toCollection(right).stream().toList();
        if (bounds.size() != 2) {
            throw new RuleEngineException("BETWEEN 需要 [lo, hi] 两个边界");
        }
        BigDecimal l = toBigDecimal(left);
        return l.compareTo(toBigDecimal(bounds.get(0))) >= 0
                && l.compareTo(toBigDecimal(bounds.get(1))) <= 0;
    }

    private static BigDecimal toBigDecimal(Object v) {
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

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=OperatorNodeExecutorTest test`
Expected: PASS（全部用例）。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: OperatorNodeExecutor 运算符全集 + 取值路径 + REGEX 长度防护"
```

---

## Task 10: NodeRunner（超时 + FailPolicy + 计时）

**Files:**
- Create: `src/main/java/io/openrule/core/runtime/NodeRunner.java`
- Test: `src/test/java/io/openrule/core/runtime/NodeRunnerTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/runtime/NodeRunnerTest.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeRunnerTest {

    private ExecutorService pool;
    private DecisionContext ctx;

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        ctx = new DecisionContext("R", "f", "b", Map.of());
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /** 用一个可配置的执行器：要么返回固定结果，要么抛异常，要么睡眠。 */
    static class ProgrammableExecutor implements NodeExecutor {
        Runnable behavior;
        NodeResult result;
        ProgrammableExecutor(Runnable behavior, NodeResult result) {
            this.behavior = behavior; this.result = result;
        }
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            if (behavior != null) behavior.run();
            return result;
        }
    }

    private NodeRunner runnerWith(NodeExecutor e) {
        return new NodeRunner(new NodeExecutorRegistry(List.of(e)), pool);
    }

    private CompiledNode node(FailPolicy policy, long timeout, boolean stopOnHit, Decision onHit) {
        return new CompiledNode(NodeDefinition.builder()
                .nodeId("N").nodeName("节点").nodeType(NodeType.OPERATOR)
                .failPolicy(policy).timeoutMillis(timeout)
                .stopOnHit(stopOnHit).decisionOnHit(onHit).build(), null);
    }

    @Test
    void skipsWhenContextStopped() {
        ctx.stop();
        NodeRunner runner = runnerWith(new ProgrammableExecutor(null,
                NodeResult.builder().nodeId("N").hit(true).build()));
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 1000, false, null));
        assertThat(r.isSkipped()).isTrue();
        assertThat(r.isHit()).isFalse();
    }

    @Test
    void attachesDecisionOnHitAndStopOnHit() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(null,
                NodeResult.builder().nodeId("N").nodeType(NodeType.OPERATOR).hit(true).build()));
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 1000, true, Decision.REJECT));
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(r.isStop()).isTrue();
    }

    @Test
    void failPolicySkip_marksSkippedAndContinues() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 1000, false, null));
        assertThat(r.isSkipped()).isTrue();
        assertThat(r.isSuccess()).isFalse();
    }

    @Test
    void failPolicyReview_producesReview() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        NodeResult r = runner.run(ctx, node(FailPolicy.REVIEW, 1000, false, null));
        assertThat(r.isHit()).isTrue();
        assertThat(r.getDecision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void failPolicyReject_producesRejectAndStop() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        NodeResult r = runner.run(ctx, node(FailPolicy.REJECT, 1000, false, null));
        assertThat(r.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(r.isStop()).isTrue();
    }

    @Test
    void failPolicyAbort_throws() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { throw new RuntimeException("boom"); }, null));
        assertThatThrownBy(() -> runner.run(ctx, node(FailPolicy.ABORT, 1000, false, null)))
                .isInstanceOf(RuleEngineException.class);
    }

    @Test
    void timeout_isTreatedAsFailureUnderPolicy() {
        NodeRunner runner = runnerWith(new ProgrammableExecutor(
                () -> { try { Thread.sleep(500); } catch (InterruptedException ignored) {} }, null));
        // SKIP 策略：超时被当作异常 → skipped
        NodeResult r = runner.run(ctx, node(FailPolicy.SKIP, 50, false, null));
        assertThat(r.isSkipped()).isTrue();
        assertThat(r.isSuccess()).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=NodeRunnerTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 NodeRunner**

Create `NodeRunner.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 节点执行唯一入口：超时、FailPolicy、计时、skipped 全在此层（C8）。 */
public class NodeRunner {

    private static final long DEFAULT_TIMEOUT_MS = 3000;

    private final NodeExecutorRegistry registry;
    private final ExecutorService timeoutPool;

    public NodeRunner(NodeExecutorRegistry registry, ExecutorService timeoutPool) {
        this.registry = registry;
        this.timeoutPool = timeoutPool;
    }

    public NodeResult run(DecisionContext ctx, CompiledNode compiled) {
        NodeDefinition def = compiled.getDefinition();
        long start = System.currentTimeMillis();

        if (ctx.isStopped()) {
            return skippedResult(def, start);
        }

        try {
            NodeResult result = executeWithTimeout(ctx, compiled, def);
            if (result.isHit() && def.getDecisionOnHit() != null && result.getDecision() == null) {
                result = result.toBuilder().decision(def.getDecisionOnHit()).build();
            }
            if (result.isHit() && def.isStopOnHit()) {
                result = result.toBuilder().stop(true).build();
            }
            return result;
        } catch (Exception e) {
            return applyFailPolicy(def, e, start);
        }
    }

    private NodeResult executeWithTimeout(DecisionContext ctx, CompiledNode compiled,
                                          NodeDefinition def) throws Exception {
        long timeout = def.getTimeoutMillis() > 0 ? def.getTimeoutMillis() : DEFAULT_TIMEOUT_MS;
        Future<NodeResult> future = timeoutPool.submit(
                () -> registry.getRequired(def.getNodeType()).execute(ctx, compiled));
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuleEngineException("Node timeout " + timeout + "ms: " + def.getNodeId());
        }
    }

    private NodeResult skippedResult(NodeDefinition def, long start) {
        return NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .skipped(true).success(true)
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private NodeResult applyFailPolicy(NodeDefinition def, Exception e, long start) {
        FailPolicy policy = def.getFailPolicy() != null ? def.getFailPolicy() : FailPolicy.SKIP;
        NodeResult.NodeResultBuilder base = NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
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

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=NodeRunnerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: NodeRunner 超时控制 + 四档 FailPolicy + 命中行为附加"
```

---

## Task 11: SerialStageExecutor

**Files:**
- Create: `src/main/java/io/openrule/core/runtime/SerialStageExecutor.java`
- Test: `src/test/java/io/openrule/core/runtime/SerialStageExecutorTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/runtime/SerialStageExecutorTest.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class SerialStageExecutorTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() { pool = Executors.newVirtualThreadPerTaskExecutor(); }
    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /** 一个把固定 NodeResult 原样返回的执行器（按 nodeId 区分行为）。 */
    static class ScriptedExecutor implements NodeExecutor {
        private final Map<String, NodeResult> byId;
        ScriptedExecutor(Map<String, NodeResult> byId) { this.byId = byId; }
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            return byId.get(n.getDefinition().getNodeId());
        }
    }

    private CompiledStage stage(List<NodeDefinition> defs) {
        StageDefinition sd = StageDefinition.builder()
                .stageId("s1").executionMode(ExecutionMode.SERIAL).skipWhenStopped(true).build();
        return new CompiledStage(sd, defs.stream().map(d -> new CompiledNode(d, null)).toList());
    }

    private NodeDefinition def(String id) {
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR).timeoutMillis(1000).build();
    }

    @Test
    void mergesOutputsIntoVariables() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        NodeResult r = NodeResult.builder().nodeId("A").outputs(Map.of("k", 1)).success(true).build();
        ScriptedExecutor exec = new ScriptedExecutor(Map.of("A", r));
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(exec)), pool);
        SerialStageExecutor serial = new SerialStageExecutor(runner);

        StageResult sr = serial.execute(ctx, stage(List.of(def("A"))));
        assertThat(ctx.variable("k")).isEqualTo(1);
        assertThat(sr.getNodeResults()).hasSize(1);
    }

    @Test
    void stopShortCircuitsRemainingNodes() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        NodeResult a = NodeResult.builder().nodeId("A").stop(true).success(true).build();
        NodeResult b = NodeResult.builder().nodeId("B").success(true).build();
        ScriptedExecutor exec = new ScriptedExecutor(Map.of("A", a, "B", b));
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(exec)), pool);
        SerialStageExecutor serial = new SerialStageExecutor(runner);

        StageResult sr = serial.execute(ctx, stage(List.of(def("A"), def("B"))));
        assertThat(ctx.isStopped()).isTrue();
        assertThat(sr.getNodeResults()).hasSize(1);  // B 未执行
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=SerialStageExecutorTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 SerialStageExecutor**

Create `SerialStageExecutor.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;

import java.util.ArrayList;
import java.util.List;

/** 串行 Stage：节点依次执行，stop 立即生效，outputs 立即合并（单线程，天然安全）。 */
public class SerialStageExecutor {

    private final NodeRunner nodeRunner;

    public SerialStageExecutor(NodeRunner nodeRunner) {
        this.nodeRunner = nodeRunner;
    }

    public StageResult execute(DecisionContext ctx, CompiledStage stage) {
        List<NodeResult> results = new ArrayList<>();

        for (CompiledNode node : stage.getNodes()) {
            if (ctx.isStopped()) break;

            NodeResult result = nodeRunner.run(ctx, node);
            results.add(result);
            ctx.addNodeResult(result);

            result.getOutputs().forEach(ctx::putVariable);

            if (result.isStop()) {
                ctx.stop();
                break;
            }
        }
        return StageResult.of(stage.getStageId(), results);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=SerialStageExecutorTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: SerialStageExecutor 顺序执行 + stop 短路 + outputs 合并"
```

---

## Task 12: ParallelStageExecutor（皇冠：隔离 + 按 order 单线程合并）

**Files:**
- Create: `src/main/java/io/openrule/core/runtime/ParallelStageExecutor.java`
- Test: `src/test/java/io/openrule/core/runtime/ParallelStageExecutorTest.java`

- [ ] **Step 1: 写失败测试（合并确定性是核心）**

Create `src/test/java/io/openrule/core/runtime/ParallelStageExecutorTest.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class ParallelStageExecutorTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() { pool = Executors.newVirtualThreadPerTaskExecutor(); }
    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /**
     * 行为按 nodeId 编排：可指定睡眠毫秒（制造乱序完成）、要写入的 (key,value)、是否 stop。
     */
    static class TimedExecutor implements NodeExecutor {
        record Spec(long sleepMs, String key, Object value, boolean stop) {}
        private final Map<String, Spec> specs;
        TimedExecutor(Map<String, Spec> specs) { this.specs = specs; }
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            Spec s = specs.get(n.getDefinition().getNodeId());
            if (s.sleepMs() > 0) {
                try { Thread.sleep(s.sleepMs()); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return NodeResult.builder().nodeId(n.getDefinition().getNodeId())
                    .success(true).stop(s.stop())
                    .outputs(s.key() == null ? Map.of() : Map.of(s.key(), s.value()))
                    .build();
        }
    }

    private CompiledStage parallelStage(long timeout, List<NodeDefinition> defs) {
        StageDefinition sd = StageDefinition.builder()
                .stageId("s2").executionMode(ExecutionMode.PARALLEL)
                .stageTimeoutMillis(timeout).build();
        return new CompiledStage(sd, defs.stream().map(d -> new CompiledNode(d, null)).toList());
    }

    private NodeDefinition def(String id) {
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(2000).build();
    }

    private ParallelStageExecutor executor(NodeExecutor e) {
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(e)), pool);
        return new ParallelStageExecutor(runner, pool);
    }

    /** 核心：先完成的节点（B，睡得短）写同一个 key，后 order 的 A（睡得久）必须覆盖 B —— 与完成顺序无关。 */
    @Test
    void mergeIsDeterministicByDefinitionOrder_notCompletionOrder() {
        var specs = Map.of(
                "A", new TimedExecutor.Spec(120, "shared", "fromA", false),  // order 靠前，完成靠后
                "B", new TimedExecutor.Spec(10, "shared", "fromB", false));  // order 靠后，完成靠前
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        StageResult sr = executor(new TimedExecutor(specs))
                .execute(ctx, parallelStage(2000, List.of(def("A"), def("B"))));
        // 节点定义顺序 A,B → 合并时 B(后 order) 覆盖 A
        assertThat(ctx.variable("shared")).isEqualTo("fromB");
        // 结果顺序恒按定义顺序
        assertThat(sr.getNodeResults().stream().map(NodeResult::getNodeId).toList())
                .containsExactly("A", "B");
    }

    @Test
    void anyStopTriggersContextStopAfterMerge() {
        var specs = Map.of(
                "A", new TimedExecutor.Spec(0, "ka", 1, false),
                "B", new TimedExecutor.Spec(0, null, null, true));
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        executor(new TimedExecutor(specs))
                .execute(ctx, parallelStage(2000, List.of(def("A"), def("B"))));
        assertThat(ctx.isStopped()).isTrue();
        assertThat(ctx.variable("ka")).isEqualTo(1);  // 合并仍发生
    }

    @Test
    void groupTimeout_stragglerBecomesSkippedResult() {
        var specs = Map.of(
                "FAST", new TimedExecutor.Spec(10, "f", 1, false),
                "SLOW", new TimedExecutor.Spec(5000, "s", 2, false));
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        StageResult sr = executor(new TimedExecutor(specs))
                .execute(ctx, parallelStage(100, List.of(def("FAST"), def("SLOW"))));
        assertThat(sr.getNodeResults()).hasSize(2);
        NodeResult slow = sr.getNodeResults().stream()
                .filter(r -> r.getNodeId().equals("SLOW")).findFirst().orElseThrow();
        assertThat(slow.isSuccess()).isFalse();  // 被取消 → 兜底结果
        assertThat(ctx.variable("f")).isEqualTo(1);
        assertThat(ctx.variable("s")).isNull();   // SLOW 未贡献
    }

    @Test
    void skipsEntireStageWhenAlreadyStopped() {
        DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());
        ctx.stop();
        StageResult sr = executor(new TimedExecutor(Map.of()))
                .execute(ctx, parallelStage(2000, List.of()));
        assertThat(sr.isSkipped()).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ParallelStageExecutorTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 ParallelStageExecutor**

Create `ParallelStageExecutor.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.result.NodeResult;
import io.openrule.core.result.StageResult;
import io.openrule.core.spi.CompiledNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并行 Stage 正确性模型：
 *  ① 节点在隔离线程池并发执行，只返回 NodeResult，不触碰 context（C1）
 *  ② allOf 等待全部完成或整组超时
 *  ③ 合并线程单线程、按节点定义 order 顺序合并 outputs → variables（C2，确定性）
 *  ④ stop 在合并阶段统一判定
 */
public class ParallelStageExecutor {

    private static final long DEFAULT_STAGE_TIMEOUT_MS = 10_000;

    private final NodeRunner nodeRunner;
    private final Executor parallelPool;

    public ParallelStageExecutor(NodeRunner nodeRunner, Executor parallelPool) {
        this.nodeRunner = nodeRunner;
        this.parallelPool = parallelPool;
    }

    public StageResult execute(DecisionContext ctx, CompiledStage stage) {
        if (ctx.isStopped()) {
            return StageResult.skipped(stage.getStageId());
        }

        long stageTimeout = stage.getStageTimeoutMillis() > 0
                ? stage.getStageTimeoutMillis() : DEFAULT_STAGE_TIMEOUT_MS;

        List<CompletableFuture<NodeResult>> futures = stage.getNodes().stream()
                .map(node -> CompletableFuture.supplyAsync(
                        () -> nodeRunner.run(ctx, node), parallelPool))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(stageTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            futures.forEach(f -> f.cancel(true));
        } catch (Exception ignored) {
            // 个别节点异常已被 NodeRunner 兜底；整体异常不阻断合并
        }

        // 单线程合并：按节点定义顺序，而非完成顺序
        List<NodeResult> results = new ArrayList<>();
        boolean anyStop = false;
        for (int i = 0; i < futures.size(); i++) {
            NodeResult r = resolveResult(futures.get(i), stage.getNodes().get(i));
            results.add(r);
            ctx.addNodeResult(r);
            r.getOutputs().forEach(ctx::putVariable);
            anyStop |= r.isStop();
        }

        if (anyStop) {
            ctx.stop();
        }
        return StageResult.of(stage.getStageId(), results);
    }

    private NodeResult resolveResult(CompletableFuture<NodeResult> f, CompiledNode node) {
        try {
            NodeResult r = f.getNow(null);
            return r != null ? r : timeoutResult(node);
        } catch (Exception e) {
            return errorResult(node, e);
        }
    }

    private NodeResult timeoutResult(CompiledNode node) {
        NodeDefinition def = node.getDefinition();
        return NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .success(false).skipped(true).errorMessage("整组超时被取消")
                .build();
    }

    private NodeResult errorResult(CompiledNode node, Exception e) {
        NodeDefinition def = node.getDefinition();
        return NodeResult.builder()
                .nodeId(def.getNodeId()).nodeName(def.getNodeName()).nodeType(def.getNodeType())
                .success(false).errorMessage(String.valueOf(e.getMessage()))
                .build();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ParallelStageExecutorTest test`
Expected: PASS（尤其合并确定性用例）。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: ParallelStageExecutor 隔离执行 + 按 order 单线程合并(C1/C2) + 整组超时"
```

---

## Task 13: PriorityAggregator

**Files:**
- Create: `src/main/java/io/openrule/core/aggregate/PriorityAggregator.java`
- Test: `src/test/java/io/openrule/core/aggregate/PriorityAggregatorTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/aggregate/PriorityAggregatorTest.java`:
```java
package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PriorityAggregatorTest {

    private final PriorityAggregator agg = new PriorityAggregator();
    private final DecisionContext ctx = new DecisionContext("R", "f", "b", Map.of());

    private NodeResult hit(String id, Decision d, int score, String reason) {
        return NodeResult.builder().nodeId(id).hit(true).decision(d).score(score).reason(reason).build();
    }

    @Test
    void supportsPriorityPolicy() {
        assertThat(agg.supportPolicy()).isEqualTo(AggregatePolicy.PRIORITY);
    }

    @Test
    void picksHighestRisk_rejectOverReview() {
        AggregateOutcome out = agg.aggregate(List.of(
                hit("a", Decision.REVIEW, 0, "review原因"),
                hit("b", Decision.REJECT, 0, "reject原因")), ctx);
        assertThat(out.decision()).isEqualTo(Decision.REJECT);
        assertThat(out.reason()).isEqualTo("reject原因");
    }

    @Test
    void defaultsToPassWhenNoDecision() {
        AggregateOutcome out = agg.aggregate(List.of(
                NodeResult.builder().nodeId("a").hit(false).build()), ctx);
        assertThat(out.decision()).isEqualTo(Decision.PASS);
    }

    @Test
    void sumsScoresAndCollectsHitNodes() {
        AggregateOutcome out = agg.aggregate(List.of(
                hit("a", Decision.PASS, 30, "r1"),
                hit("b", Decision.REVIEW, 42, "r2")), ctx);
        assertThat(out.totalScore()).isEqualTo(72);
        assertThat(out.hitNodes()).containsExactly("a", "b");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=PriorityAggregatorTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 PriorityAggregator**

Create `PriorityAggregator.java`:
```java
package io.openrule.core.aggregate;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.AggregateOutcome;
import io.openrule.core.spi.DecisionAggregator;

import java.util.ArrayList;
import java.util.List;

/** 默认聚合器：按 Decision 风险优先级取最高（REJECT &gt; REVIEW &gt; LIMIT &gt; PASS）。 */
public class PriorityAggregator implements DecisionAggregator {

    @Override
    public AggregatePolicy supportPolicy() { return AggregatePolicy.PRIORITY; }

    @Override
    public AggregateOutcome aggregate(List<NodeResult> results, DecisionContext ctx) {
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
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=PriorityAggregatorTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: PriorityAggregator 风险优先级聚合 + 评分求和 + 命中收集"
```

---

## Task 14: FlowExecutor（流程入口）

**Files:**
- Create: `src/main/java/io/openrule/core/runtime/FlowExecutor.java`
- Test: `src/test/java/io/openrule/core/runtime/FlowExecutorTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/core/runtime/FlowExecutorTest.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.Decision;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.FailPolicy;
import io.openrule.core.enums.NodeType;
import io.openrule.core.result.FlowResult;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.DecisionAggregator;
import io.openrule.core.spi.NodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class FlowExecutorTest {

    private ExecutorService pool;
    private FlowExecutor flowExecutor;

    /** 命中即产出指定 decision + stop 的执行器。 */
    static class HitExecutor implements NodeExecutor {
        public NodeType supportType() { return NodeType.OPERATOR; }
        public NodeResult execute(DecisionContext c, CompiledNode n) {
            NodeDefinition d = n.getDefinition();
            boolean hit = Boolean.TRUE.equals(c.fact(d.getNodeId() + ".hit"));
            return NodeResult.builder().nodeId(d.getNodeId()).nodeType(NodeType.OPERATOR)
                    .hit(hit).success(true).build();
        }
    }

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeRunner runner = new NodeRunner(new NodeExecutorRegistry(List.of(new HitExecutor())), pool);
        SerialStageExecutor serial = new SerialStageExecutor(runner);
        ParallelStageExecutor parallel = new ParallelStageExecutor(runner, pool);
        DecisionAggregator priority = new PriorityAggregator();
        flowExecutor = new FlowExecutor(serial, parallel,
                Map.of(AggregatePolicy.PRIORITY, priority));
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private NodeDefinition node(String id, Decision onHit, boolean stopOnHit) {
        return NodeDefinition.builder().nodeId(id).nodeType(NodeType.OPERATOR)
                .decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(1000).build();
    }

    private CompiledFlow compile(FlowDefinition def) {
        List<CompiledStage> stages = def.getStages().stream()
                .map(s -> new CompiledStage(s,
                        s.getNodes().stream().map(n -> new CompiledNode(n, null)).toList()))
                .toList();
        return new CompiledFlow(def, stages);
    }

    @Test
    void runsStagesAndAggregatesDecision() {
        StageDefinition s1 = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("BLACKLIST", Decision.REJECT, true))).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("order_risk").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(s1)).build();

        DecisionContext ctx = new DecisionContext("R", "order_risk", "BIZ",
                Map.of("BLACKLIST.hit", true));
        FlowResult fr = flowExecutor.execute(ctx, compile(def));

        assertThat(fr.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(fr.getHitNodes()).contains("BLACKLIST");
        assertThat(fr.getFlowId()).isEqualTo("order_risk");
    }

    @Test
    void skipsLaterStageWhenStopped() {
        StageDefinition s1 = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node("BLACKLIST", Decision.REJECT, true))).build();
        StageDefinition s2 = StageDefinition.builder()
                .stageId("s2").order(200).executionMode(ExecutionMode.PARALLEL).skipWhenStopped(true)
                .stageTimeoutMillis(2000)
                .nodes(List.of(node("SCORE", Decision.REVIEW, false))).build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("f").version(1).aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(s1, s2)).build();

        DecisionContext ctx = new DecisionContext("R", "f", "BIZ",
                Map.of("BLACKLIST.hit", true, "SCORE.hit", true));
        FlowResult fr = flowExecutor.execute(ctx, compile(def));

        // s1 命中 REJECT 并 stop → s2 跳过 → 最终仍是 REJECT，SCORE 未命中
        assertThat(fr.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(fr.getHitNodes()).doesNotContain("SCORE");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=FlowExecutorTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现 FlowExecutor**

Create `FlowExecutor.java`:
```java
package io.openrule.core.runtime;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.FlowResult;
import io.openrule.core.spi.AggregateOutcome;
import io.openrule.core.spi.DecisionAggregator;

import java.util.Map;

/** 流程执行入口：调度 Stage → 聚合决策（finalDecision 唯一写入点，C3）。 */
public class FlowExecutor {

    private final SerialStageExecutor serialExecutor;
    private final ParallelStageExecutor parallelExecutor;
    private final Map<AggregatePolicy, DecisionAggregator> aggregators;

    public FlowExecutor(SerialStageExecutor serialExecutor,
                        ParallelStageExecutor parallelExecutor,
                        Map<AggregatePolicy, DecisionAggregator> aggregators) {
        this.serialExecutor = serialExecutor;
        this.parallelExecutor = parallelExecutor;
        this.aggregators = aggregators;
    }

    public FlowResult execute(DecisionContext ctx, CompiledFlow flow) {
        long start = System.currentTimeMillis();

        for (CompiledStage stage : flow.getStages()) {
            if (ctx.isStopped() && stage.isSkipWhenStopped()) {
                continue;
            }
            if (stage.getExecutionMode() == ExecutionMode.SERIAL) {
                serialExecutor.execute(ctx, stage);
            } else {
                parallelExecutor.execute(ctx, stage);
            }
        }

        AggregatePolicy policy = flow.getAggregatePolicy() != null
                ? flow.getAggregatePolicy() : AggregatePolicy.PRIORITY;
        DecisionAggregator aggregator = aggregators.get(policy);
        if (aggregator == null) {
            throw new RuleEngineException("No aggregator for policy: " + policy);
        }
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

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=FlowExecutorTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: FlowExecutor Stage 调度 + skipWhenStopped + 聚合唯一写(C3)"
```

---

## Task 15: 端到端验收（main + 集成测试）

**Files:**
- Create: `src/main/java/io/openrule/core/demo/M1Demo.java`
- Test: `src/test/java/io/openrule/core/EndToEndTest.java`

- [ ] **Step 1: 写端到端集成测试**

Create `src/test/java/io/openrule/core/EndToEndTest.java`:
```java
package io.openrule.core;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.context.DecisionContext;
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
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.core.spi.CompiledNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTest {

    private ExecutorService pool;
    private FlowExecutor flow;

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeRunner runner = new NodeRunner(
                new NodeExecutorRegistry(List.of(new OperatorNodeExecutor())), pool);
        flow = new FlowExecutor(new SerialStageExecutor(runner),
                new ParallelStageExecutor(runner, pool),
                Map.of(AggregatePolicy.PRIORITY, new PriorityAggregator()));
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private NodeDefinition opNode(String id, String left, String op, Object right,
                                  Decision onHit, boolean stopOnHit) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(left); def.setOperator(op); def.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(10).operatorDef(def).decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(500).build();
    }

    private CompiledFlow orderRisk() {
        StageDefinition hard = StageDefinition.builder()
                .stageId("s1").stageName("硬规则").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(opNode("AMOUNT_LIMIT", "fact.order.amount", "GT", 50000,
                        Decision.REJECT, true)))
                .build();
        StageDefinition scoring = StageDefinition.builder()
                .stageId("s2").stageName("并行评分").order(200)
                .executionMode(ExecutionMode.PARALLEL).skipWhenStopped(true).stageTimeoutMillis(2000)
                .nodes(List.of(opNode("VIP_CHECK", "fact.buyer.level", "EQ", "NEW",
                        Decision.REVIEW, false)))
                .build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").version(3).enabled(true)
                .aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(hard, scoring)).build();
        List<CompiledStage> stages = def.getStages().stream()
                .map(s -> new CompiledStage(s,
                        s.getNodes().stream().map(n -> new CompiledNode(n, null)).toList()))
                .toList();
        return new CompiledFlow(def, stages);
    }

    @Test
    void bigAmount_isRejectedAtHardStage_andSkipsScoring() {
        DecisionContext ctx = new DecisionContext("REQ1", "order_risk", "ORDER_1",
                Map.of("order", Map.of("amount", 80000),
                       "buyer", Map.of("level", "NEW")));
        FlowResult fr = flow.execute(ctx, orderRisk());
        assertThat(fr.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(fr.getHitNodes()).containsExactly("AMOUNT_LIMIT");  // VIP_CHECK 被跳过
    }

    @Test
    void newBuyer_smallAmount_goesToReview() {
        DecisionContext ctx = new DecisionContext("REQ2", "order_risk", "ORDER_2",
                Map.of("order", Map.of("amount", 1000),
                       "buyer", Map.of("level", "NEW")));
        FlowResult fr = flow.execute(ctx, orderRisk());
        assertThat(fr.getDecision()).isEqualTo(Decision.REVIEW);
        assertThat(fr.getHitNodes()).containsExactly("VIP_CHECK");
    }

    @Test
    void vipBuyer_smallAmount_passes() {
        DecisionContext ctx = new DecisionContext("REQ3", "order_risk", "ORDER_3",
                Map.of("order", Map.of("amount", 1000),
                       "buyer", Map.of("level", "VIP")));
        FlowResult fr = flow.execute(ctx, orderRisk());
        assertThat(fr.getDecision()).isEqualTo(Decision.PASS);
        assertThat(fr.getHitNodes()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=EndToEndTest test`
Expected: 编译失败（M1Demo 未引用，但测试本身依赖已存在的类，应能编译——若失败定位缺失类后修正）。实际预期：测试编译通过但需先确认全链路逻辑，故运行后应 PASS；若 PASS 则跳到 Step 4 仅补 M1Demo。

> 说明：本测试只依赖已实现的类，理论上应直接 PASS。它的作用是端到端验收，而非驱动新代码。若 PASS，直接进入 Step 3 补 `main()`。

- [ ] **Step 3: 写 M1Demo（main 验收）**

Create `M1Demo.java`:
```java
package io.openrule.core.demo;

import io.openrule.core.aggregate.PriorityAggregator;
import io.openrule.core.context.DecisionContext;
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
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.core.spi.CompiledNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** M1 验收：纯 Java 手动装配，跑通 order_risk 流程。 */
public class M1Demo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        NodeRunner runner = new NodeRunner(
                new NodeExecutorRegistry(List.of(new OperatorNodeExecutor())), pool);
        FlowExecutor flow = new FlowExecutor(
                new SerialStageExecutor(runner),
                new ParallelStageExecutor(runner, pool),
                Map.of(AggregatePolicy.PRIORITY, new PriorityAggregator()));

        CompiledFlow compiled = buildOrderRisk();

        run(flow, compiled, "大额订单", Map.of(
                "order", Map.of("amount", 80000), "buyer", Map.of("level", "NEW")));
        run(flow, compiled, "新买家小额", Map.of(
                "order", Map.of("amount", 1000), "buyer", Map.of("level", "NEW")));
        run(flow, compiled, "VIP小额", Map.of(
                "order", Map.of("amount", 1000), "buyer", Map.of("level", "VIP")));

        pool.shutdownNow();
    }

    private static void run(FlowExecutor flow, CompiledFlow compiled,
                            String label, Map<String, Object> facts) {
        DecisionContext ctx = new DecisionContext("REQ-" + label, "order_risk", "BIZ", facts);
        FlowResult fr = flow.execute(ctx, compiled);
        System.out.printf("[%s] decision=%s hitNodes=%s cost=%dms%n",
                label, fr.getDecision(), fr.getHitNodes(), fr.getCostMillis());
    }

    private static NodeDefinition opNode(String id, String left, String op, Object right,
                                         Decision onHit, boolean stopOnHit) {
        OperatorDef def = new OperatorDef();
        def.setLeftFact(left); def.setOperator(op); def.setRightValue(right);
        return NodeDefinition.builder().nodeId(id).nodeName(id).nodeType(NodeType.OPERATOR)
                .order(10).operatorDef(def).decisionOnHit(onHit).stopOnHit(stopOnHit)
                .failPolicy(FailPolicy.SKIP).timeoutMillis(500).build();
    }

    private static CompiledFlow buildOrderRisk() {
        StageDefinition hard = StageDefinition.builder()
                .stageId("s1").stageName("硬规则").order(100)
                .executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(opNode("AMOUNT_LIMIT", "fact.order.amount", "GT", 50000,
                        Decision.REJECT, true)))
                .build();
        StageDefinition scoring = StageDefinition.builder()
                .stageId("s2").stageName("并行评分").order(200)
                .executionMode(ExecutionMode.PARALLEL).skipWhenStopped(true).stageTimeoutMillis(2000)
                .nodes(List.of(opNode("VIP_CHECK", "fact.buyer.level", "EQ", "NEW",
                        Decision.REVIEW, false)))
                .build();
        FlowDefinition def = FlowDefinition.builder()
                .flowId("order_risk").flowName("订单风控").version(3).enabled(true)
                .aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(hard, scoring)).build();
        List<CompiledStage> stages = def.getStages().stream()
                .map(s -> new CompiledStage(s,
                        s.getNodes().stream().map(n -> new CompiledNode(n, null)).toList()))
                .toList();
        return new CompiledFlow(def, stages);
    }
}
```

- [ ] **Step 4: 跑全量测试 + 运行 main**

Run: `mvn -q test`
Expected: BUILD SUCCESS，所有测试类全绿。

Run: `mvn -q -DskipTests compile exec:java -Dexec.mainClass=io.openrule.core.demo.M1Demo` 或在 IDE 直接运行 `M1Demo`。
Expected 输出（三行）：
```
[大额订单] decision=REJECT hitNodes=[AMOUNT_LIMIT] cost=...ms
[新买家小额] decision=REVIEW hitNodes=[VIP_CHECK] cost=...ms
[VIP小额] decision=PASS hitNodes=[] cost=...ms
```
> 注：`exec:java` 需要 exec-maven-plugin；若未配置，直接在 IDE 运行 `M1Demo.main`。本计划不强制加该插件，IDE 运行即可验收。

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "test: M1 端到端验收(EndToEndTest) + M1Demo main"
```

---

## 验收清单（M1 完成标志） — ✅ 全部达成（2026-06-22）

> 全部 15 Task 已实现并逐 Task 提交（15 commit on `main`）。`mvn clean test` → **Tests run: 56, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**。

- [x] `mvn test` 全绿（覆盖 enums / FactMap / DecisionContext / NodeResult / definition / SPI / Registry / Operator / NodeRunner / Serial / Parallel / Aggregator / FlowExecutor / EndToEnd）。
- [x] `M1Demo.main` 输出三种决策（REJECT / REVIEW / PASS）符合预期。
- [x] 并发约束自检：C1（并行节点不写 context）、C2（按 order 单线程合并，确定性测试覆盖）、C3（finalDecision 仅 Aggregator 写）、C7（FactMap 不可变）、C8（NodeRunner 唯一治理入口）、C9（并发类型正确）、C10（REGEX 长度上限）。
- [x] 全程零 Spring、零 JSON、零中间件依赖。

---

## 后续衔接

M2 起进入 `openrule-spring`，是与 ycr-framework 的融合主战场（`R<T>`、异常体系、数据层 starter、Caffeine、Redis）。本 M1 所有类保持纯净，可 1:1 并入。
