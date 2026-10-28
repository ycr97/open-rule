# OpenRule M2b · openrule-jdbc（JDBC 持久化适配器）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在公开 `open-rule` 仓库内提供一组「中立、零 ycr」的 JDBC 持久化适配器（MySQL），把 M2a 的内存端口替换为真库，并补齐完整 admin（enable/rollback/versions/logs），用 Testcontainers 真库验证。

**Architecture:** 端口-适配第二组适配器。新增第 4 模块 `openrule-jdbc`（JdbcTemplate）实现 `FlowDefinitionRepository`/`ExecutionLogger`/新增 `ExecutionLogQuery` 三端口，`@ConditionalOnBean(DataSource)`+`@ConditionalOnMissingBean` 覆盖内存默认。spring/api 各加少量端口演进与端点；core 仅加一个只读 getter。

**Tech Stack:** Java 21 · Spring Boot 3.3.5 · JdbcTemplate（spring-boot-starter-jdbc）· MySQL · Testcontainers · Jackson · JUnit 5 · AssertJ · MockMvc。

## Global Constraints

- 包根：`openrule-jdbc` 用 `io.openrule.jdbc`；spring 改动在 `io.openrule.spring`；api 改动在 `io.openrule.api`；core 仅加 `FactMap.asMap()`（additive getter，**不改 M1 行为**）。
- `openrule-jdbc` **不依赖任何 ycr**；只依赖 `openrule-spring` + `spring-boot-starter-jdbc`（+ 测试 Testcontainers/mysql 驱动）。
- `openrule-spring`/`openrule-api` 引入路径**不得**新增 jdbc/DataSource 依赖（不引 jdbc 模块时零 DataSource）。
- 跑构建需 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`（本机默认 JDK 17）。
- reactor 单类测试统一加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 提交信息用中文，**不带** `Co-Authored-By` 尾注。
- 版本/启用语义沿用 M2a「save 即启用」；同 `flowId` save 版本自增、置唯一 enabled、旧版本转非启用；任意时刻至多一个 enabled（JDBC 靠事务保证）。
- 并发约束沿用：C11（缓存按版本失效）、C12（审计失败不影响主流程）。
- Testcontainers 集成测试用 `@Testcontainers(disabledWithoutDocker = true)`：Docker 缺席自动跳过，不挂断构建。
- checksum = `definition_json` 的 SHA-256（小写 hex）；facts 脱敏 key 默认 `mobile,idCard,bankCard,password`，递归打码为 `****`。

---

## 文件结构总览

```
open-rule/
├── openrule-core/    +FactMap.asMap()（唯一 core 改动）
├── openrule-spring/
│   ├── port/         +ExecutionLogQuery；ExecutionLogger.log 加 flowVersion 参数；FlowDefinitionRepository +findAllVersions
│   ├── model/        +ExecutionLogEntry
│   ├── standalone/   InMemoryExecutionLogger 改造（implements 双端口+版本）；InMemoryFlowDefinitionRepository +findAllVersions
│   ├── service/      OpenRuleService +enableVersion/rollback/listVersions；safeLog 传 flowVersion
│   └── autoconfigure/ OpenRuleAutoConfiguration：InMemory 双端口装配
├── openrule-api/
│   ├── dto/          ExecuteResponse 复用；versions 返回 List<FlowSummary>；logs 返回 List<ExecutionLogEntry>
│   └── FlowAdminController +enable/rollback/versions/logs；OpenRuleApiAutoConfiguration 注入 ExecutionLogQuery
└── openrule-jdbc/    (新)
    ├── pom.xml
    └── src/main/java/io/openrule/jdbc/
    │   ├── support/      ChecksumUtil  Desensitizer
    │   ├── repository/   JdbcFlowDefinitionRepository
    │   ├── audit/        JdbcExecutionLogger  JdbcExecutionLogQuery
    │   └── autoconfigure/ OpenRuleJdbcAutoConfiguration  OpenRuleJdbcProperties
    ├── src/main/resources/
    │   ├── schema.sql
    │   └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── src/test/java/io/openrule/jdbc/...   (Testcontainers IT)
```

---

## Task 1: spring 端口演进（ExecutionLogQuery + 日志带版本 + core getter）

**Files:**
- Modify: `openrule-core/src/main/java/io/openrule/core/context/FactMap.java`
- Modify: `openrule-spring/src/main/java/io/openrule/spring/port/ExecutionLogger.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/port/ExecutionLogQuery.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/model/ExecutionLogEntry.java`
- Modify: `openrule-spring/src/main/java/io/openrule/spring/standalone/InMemoryExecutionLogger.java`
- Modify: `openrule-spring/src/main/java/io/openrule/spring/service/OpenRuleService.java`
- Modify: `openrule-spring/src/main/java/io/openrule/spring/autoconfigure/OpenRuleAutoConfiguration.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/standalone/InMemoryExecutionLogQueryTest.java`

**Interfaces:**
- Produces:
  - `FactMap.asMap() : Map<String,Object>`（不可变视图）
  - `ExecutionLogger.log(FlowResult result, DecisionContext ctx, int flowVersion) : void`
  - `ExecutionLogQuery.query(String bizId, String flowId, int limit) : List<ExecutionLogEntry>`
  - `ExecutionLogEntry`（record）：`String requestId, String flowId, int flowVersion, String bizId, String decision, String reason, int totalScore, long costMillis, java.time.Instant createdAt`
  - `InMemoryExecutionLogger implements ExecutionLogger, ExecutionLogQuery`，保留 `recent() : List<ExecutionLogEntry>`

- [ ] **Step 1: core 加 FactMap.asMap()**

Modify `FactMap.java`，在 `get(...)` 之前加：
```java
    /** 返回不可变事实视图（审计快照用）。 */
    public java.util.Map<String, Object> asMap() {
        return data;
    }
```
（`data` 已是 `Collections.unmodifiableMap`，直接返回安全。）

- [ ] **Step 2: 写 ExecutionLogEntry 模型**

Create `model/ExecutionLogEntry.java`:
```java
package io.openrule.spring.model;

import java.time.Instant;

/** 执行日志查询条目（读模型）。 */
public record ExecutionLogEntry(String requestId, String flowId, int flowVersion,
                                String bizId, String decision, String reason,
                                int totalScore, long costMillis, Instant createdAt) {
}
```

- [ ] **Step 3: 写 ExecutionLogQuery 端口**

Create `port/ExecutionLogQuery.java`:
```java
package io.openrule.spring.port;

import io.openrule.spring.model.ExecutionLogEntry;

import java.util.List;

/** 执行日志只读查询端口。standalone=内存缓冲；M2b=or_execute_log。 */
public interface ExecutionLogQuery {

    /** 按 bizId/flowId（null=不过滤）查询最近 limit 条，按时间倒序。 */
    List<ExecutionLogEntry> query(String bizId, String flowId, int limit);
}
```

- [ ] **Step 4: 演进 ExecutionLogger 端口签名**

Replace `port/ExecutionLogger.java` 内容为：
```java
package io.openrule.spring.port;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;

/** 执行日志写端口。standalone=内存缓冲；M2b=异步落库/ES。实现内部必须吞掉异常（C12）。 */
public interface ExecutionLogger {

    /** flowVersion 由编排处（持有 CompiledFlow）补齐，core FlowResult 不含版本。 */
    void log(FlowResult result, DecisionContext ctx, int flowVersion);
}
```

- [ ] **Step 5: 改 InMemoryExecutionLogger 实现双端口 + 带版本**

Replace `standalone/InMemoryExecutionLogger.java` 内容为：
```java
package io.openrule.spring.standalone;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;
import io.openrule.spring.model.ExecutionLogEntry;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.port.ExecutionLogger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 进程内有界执行日志缓冲（最近 N 条），同时充当写端口与只读查询端口。 */
public class InMemoryExecutionLogger implements ExecutionLogger, ExecutionLogQuery {

    private static final int MAX = 500;
    private final Deque<ExecutionLogEntry> buffer = new ArrayDeque<>();

    @Override
    public synchronized void log(FlowResult r, DecisionContext ctx, int flowVersion) {
        if (buffer.size() >= MAX) {
            buffer.pollFirst();
        }
        buffer.offerLast(new ExecutionLogEntry(
                r.getRequestId(), r.getFlowId(), flowVersion, r.getBizId(),
                r.getDecision() == null ? null : r.getDecision().name(),
                r.getReason(), r.getTotalScore(), r.getCostMillis(), Instant.now()));
    }

    @Override
    public synchronized List<ExecutionLogEntry> query(String bizId, String flowId, int limit) {
        List<ExecutionLogEntry> out = new ArrayList<>();
        // 倒序遍历（最近优先）
        var it = buffer.descendingIterator();
        while (it.hasNext() && (limit <= 0 || out.size() < limit)) {
            ExecutionLogEntry e = it.next();
            if (bizId != null && !bizId.equals(e.bizId())) continue;
            if (flowId != null && !flowId.equals(e.flowId())) continue;
            out.add(e);
        }
        return out;
    }

    /** 测试/demo：按写入顺序返回全部。 */
    public synchronized List<ExecutionLogEntry> recent() {
        return new ArrayList<>(buffer);
    }
}
```

- [ ] **Step 6: 改 OpenRuleService.safeLog 传 flowVersion**

Modify `service/OpenRuleService.java`：
把 `safeLog(result, ctx);` 改为 `safeLog(result, ctx, flow.getVersion());`，并把方法改为：
```java
    /** C12：日志失败不影响主流程。 */
    private void safeLog(FlowResult result, DecisionContext ctx, int flowVersion) {
        try {
            executionLogger.log(result, ctx, flowVersion);
        } catch (Exception ignored) {
            // 仅吞掉；M2b 接异步落库后在此加告警计数器
        }
    }
```

- [ ] **Step 7: 改 autoconfig 用同一 InMemory 实例供双端口**

Modify `autoconfigure/OpenRuleAutoConfiguration.java`：
加 import：
```java
import io.openrule.spring.port.ExecutionLogQuery;
```
把现有的 `executionLogger()` Bean 方法整体替换为：
```java
    @Bean
    @ConditionalOnMissingBean({ExecutionLogger.class, ExecutionLogQuery.class})
    public InMemoryExecutionLogger inMemoryExecutionLogger() {
        return new InMemoryExecutionLogger();
    }
```
> 返回具体类型 `InMemoryExecutionLogger`（同时满足 `ExecutionLogger`/`ExecutionLogQuery` 两个注入点）；条件为「两端口均无 Bean 时才建」，jdbc 提供任一端口实现即自动退让。`openRuleService` Bean 仍注入 `ExecutionLogger`（签名不变）。

- [ ] **Step 8: 写内存查询测试**

Create `src/test/java/io/openrule/spring/standalone/InMemoryExecutionLogQueryTest.java`:
```java
package io.openrule.spring.standalone;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.FlowResult;
import io.openrule.spring.model.ExecutionLogEntry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryExecutionLogQueryTest {

    private FlowResult result(String reqId, String flowId, String bizId, Decision d) {
        return FlowResult.builder().requestId(reqId).flowId(flowId).bizId(bizId)
                .decision(d).reason("r").totalScore(1).costMillis(2).build();
    }

    private DecisionContext ctx(String flowId, String bizId) {
        return new DecisionContext("req", flowId, bizId, Map.of());
    }

    @Test
    void logThenQueryByBizAndFlow_recentFirst() {
        InMemoryExecutionLogger log = new InMemoryExecutionLogger();
        log.log(result("R1", "f", "B1", Decision.PASS), ctx("f", "B1"), 1);
        log.log(result("R2", "f", "B1", Decision.REJECT), ctx("f", "B1"), 2);
        log.log(result("R3", "g", "B2", Decision.PASS), ctx("g", "B2"), 1);

        List<ExecutionLogEntry> byBiz = log.query("B1", null, 10);
        assertThat(byBiz).extracting(ExecutionLogEntry::requestId).containsExactly("R2", "R1"); // 倒序
        assertThat(byBiz).allMatch(e -> e.bizId().equals("B1"));

        List<ExecutionLogEntry> byFlow = log.query(null, "g", 10);
        assertThat(byFlow).extracting(ExecutionLogEntry::flowId).containsExactly("g");
        assertThat(byFlow.get(0).flowVersion()).isEqualTo(1);

        assertThat(log.query(null, null, 1)).hasSize(1); // limit 生效
    }
}
```

- [ ] **Step 9: 跑 spring 全量测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -am test`
Expected: BUILD SUCCESS；core 56 + spring 全绿（含新 `InMemoryExecutionLogQueryTest`，M2a `OpenRuleServiceTest` 的 `logger.recent()` 仍 size=1）。

- [ ] **Step 10: 提交**

```bash
git add -A
git commit -m "feat(spring): ExecutionLogQuery 读端口 + 执行日志带 flowVersion + FactMap.asMap()"
```

---

## Task 2: spring 版本治理（findAllVersions + OpenRuleService 方法）

**Files:**
- Modify: `openrule-spring/src/main/java/io/openrule/spring/port/FlowDefinitionRepository.java`
- Modify: `openrule-spring/src/main/java/io/openrule/spring/standalone/InMemoryFlowDefinitionRepository.java`
- Modify: `openrule-spring/src/main/java/io/openrule/spring/service/OpenRuleService.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/service/OpenRuleServiceVersioningTest.java`

**Interfaces:**
- Consumes: `FlowDefinitionRepository.enable/findByFlowIdAndVersion`（M2a）；`FlowChangeNotifier`（M2a）。
- Produces:
  - `FlowDefinitionRepository.findAllVersions(String flowId) : List<FlowDefinition>`（升序）
  - `OpenRuleService.listVersions(String flowId) : List<FlowDefinition>`
  - `OpenRuleService.enableVersion(String flowId, int version) : FlowDefinition`（版本不存在抛 `RuleEngineException("... not found ...")`）
  - `OpenRuleService.rollback(String flowId, int version) : FlowDefinition`（= enableVersion）

- [ ] **Step 1: 端口加 findAllVersions**

Modify `port/FlowDefinitionRepository.java`，在 `listVersions` 之后加：
```java
    /** 返回该 flow 全部版本定义（按 version 升序）。 */
    List<FlowDefinition> findAllVersions(String flowId);
```

- [ ] **Step 2: 内存仓储实现 findAllVersions**

Modify `standalone/InMemoryFlowDefinitionRepository.java`，在 `listVersions(...)` 之后加：
```java
    @Override
    public synchronized List<FlowDefinition> findAllVersions(String flowId) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        return versions == null ? List.of() : new ArrayList<>(versions.values());
    }
```

- [ ] **Step 3: 写失败测试**

Create `src/test/java/io/openrule/spring/service/OpenRuleServiceVersioningTest.java`:
```java
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
```

- [ ] **Step 4: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -am test -Dtest=OpenRuleServiceVersioningTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`listVersions`/`enableVersion` 未定义）。

- [ ] **Step 5: OpenRuleService 加版本治理方法**

Modify `service/OpenRuleService.java`：加 import `import io.openrule.core.exception.RuleEngineException;` 和 `import java.util.List;`，在 `registerFlow(...)` 之后加：
```java
    /** 该 flow 全部版本（升序）。 */
    public List<FlowDefinition> listVersions(String flowId) {
        return repository.findAllVersions(flowId);
    }

    /** 启用指定版本（切指针 + 失效缓存）；版本不存在抛 RuleEngineException。 */
    public FlowDefinition enableVersion(String flowId, int version) {
        repository.findByFlowIdAndVersion(flowId, version)
                .orElseThrow(() -> new RuleEngineException(
                        "Flow version not found: " + flowId + " v" + version));
        repository.enable(flowId, version);
        changeNotifier.publishInvalidation(flowId);
        return repository.findByFlowIdAndVersion(flowId, version).orElseThrow();
    }

    /** 回滚 = 启用旧版本。 */
    public FlowDefinition rollback(String flowId, int version) {
        return enableVersion(flowId, version);
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -am test -Dtest=OpenRuleServiceVersioningTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 用例）。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(spring): 版本治理(findAllVersions + OpenRuleService enableVersion/rollback/listVersions)"
```

---

## Task 3: api 完整 admin 端点（enable/rollback/versions/logs）

**Files:**
- Modify: `openrule-api/src/main/java/io/openrule/api/FlowAdminController.java`
- Modify: `openrule-api/src/main/java/io/openrule/api/autoconfigure/OpenRuleApiAutoConfiguration.java`
- Test: `openrule-api/src/test/java/io/openrule/api/AdminApiTest.java`

**Interfaces:**
- Consumes: `OpenRuleService.{registerFlow,listVersions,enableVersion,rollback}`、`ExecutionLogQuery.query`、`FlowSummary.from`、`FlowDefinitionJsonCodec.parse`。
- Produces（REST）：`POST /flows/{flowId}/enable?version=`、`POST /flows/{flowId}/rollback?version=`、`GET /flows/{flowId}/versions`、`GET /logs?bizId=&flowId=&limit=`。

- [ ] **Step 1: 控制器加 4 端点 + 注入 ExecutionLogQuery**

Replace `FlowAdminController.java` 内容为：
```java
package io.openrule.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.api.dto.ExecuteResponse;
import io.openrule.api.dto.FlowSummary;
import io.openrule.api.dto.SimulateRequest;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.model.ExecutionLogEntry;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class FlowAdminController {

    private final OpenRuleService service;
    private final FlowDefinitionJsonCodec codec;
    private final ExecutionLogQuery logQuery;

    public FlowAdminController(OpenRuleService service, FlowDefinitionJsonCodec codec,
                              ExecutionLogQuery logQuery) {
        this.service = service;
        this.codec = codec;
        this.logQuery = logQuery;
    }

    /** 注册流程：解析 JSON → 全节点 validate → save（M2a 直接 enabled）。 */
    @PostMapping("/flows")
    public FlowSummary register(@RequestBody JsonNode definition) {
        FlowDefinition def = codec.parse(definition);
        return FlowSummary.from(service.registerFlow(def));
    }

    /** 草稿模拟：不要求 enabled，回完整明细。 */
    @PostMapping("/simulate")
    public ExecuteResponse simulate(@RequestBody SimulateRequest req) {
        FlowDefinition draft = codec.parse(req.definition());
        ExecutionOutcome outcome = service.simulate(draft, req.facts());
        return ExecuteResponse.from(outcome, true);
    }

    /** 版本列表（含 enabled 标记）。 */
    @GetMapping("/flows/{flowId}/versions")
    public List<FlowSummary> versions(@PathVariable String flowId) {
        return service.listVersions(flowId).stream().map(FlowSummary::from).toList();
    }

    /** 启用指定版本（切指针 + 失效缓存）。 */
    @PostMapping("/flows/{flowId}/enable")
    public FlowSummary enable(@PathVariable String flowId, @RequestParam int version) {
        return FlowSummary.from(service.enableVersion(flowId, version));
    }

    /** 回滚 = 启用旧版本。 */
    @PostMapping("/flows/{flowId}/rollback")
    public FlowSummary rollback(@PathVariable String flowId, @RequestParam int version) {
        return FlowSummary.from(service.rollback(flowId, version));
    }

    /** 执行日志查询。 */
    @GetMapping("/logs")
    public List<ExecutionLogEntry> logs(@RequestParam(required = false) String bizId,
                                        @RequestParam(required = false) String flowId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return logQuery.query(bizId, flowId, limit);
    }
}
```

- [ ] **Step 2: api autoconfig 给控制器注入 ExecutionLogQuery**

Modify `autoconfigure/OpenRuleApiAutoConfiguration.java`：加 import `import io.openrule.spring.port.ExecutionLogQuery;`，把 `flowAdminController` Bean 方法替换为：
```java
    @Bean
    @ConditionalOnMissingBean
    public FlowAdminController flowAdminController(OpenRuleService service,
                                                  FlowDefinitionJsonCodec codec,
                                                  ExecutionLogQuery logQuery) {
        return new FlowAdminController(service, codec, logQuery);
    }
```

- [ ] **Step 3: 写 MockMvc admin 测试**

Create `src/test/java/io/openrule/api/AdminApiTest.java`:
```java
package io.openrule.api;

import io.openrule.api.test.ApiTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ApiTestApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminApiTest {

    @Autowired
    MockMvc mvc;

    private String flow(int threshold) {
        return """
            { "flowId": "f", "flowName": "n", "aggregatePolicy": "PRIORITY",
              "stages": [ { "stageId": "s1", "order": 100, "executionMode": "SERIAL", "skipWhenStopped": true,
                "nodes": [ { "nodeId": "AMT", "nodeName": "AMT", "nodeType": "OPERATOR", "order": 10,
                  "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": %d },
                  "decisionOnHit": "REJECT", "stopOnHit": true, "failPolicy": "SKIP", "timeoutMillis": 500 } ] } ] }
            """.formatted(threshold);
    }

    private void register(int threshold) throws Exception {
        mvc.perform(post("/api/v1/admin/flows").contentType(MediaType.APPLICATION_JSON).content(flow(threshold)))
                .andExpect(status().isOk());
    }

    private String exec(int amount) {
        return """
            { "flowId": "f", "bizId": "B1", "facts": { "order": { "amount": %d } } }
            """.formatted(amount);
    }

    @Test
    void versions_listsAllWithEnabledFlag() throws Exception {
        register(50000);    // v1
        register(100000);   // v2 active
        mvc.perform(get("/api/v1/admin/flows/f/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].enabled").value(true));
    }

    @Test
    void enable_switchesPointer_observableInExecute() throws Exception {
        register(50000);    // v1
        register(100000);   // v2 active(阈值10w)
        // active=v2：amount 60000 不命中 → PASS
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(exec(60000)))
                .andExpect(jsonPath("$.decision").value("PASS"));
        // 启用 v1
        mvc.perform(post("/api/v1/admin/flows/f/enable").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.enabled").value(true));
        // active=v1(阈值5w)：amount 60000 命中 → REJECT
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(exec(60000)))
                .andExpect(jsonPath("$.decision").value("REJECT"));
    }

    @Test
    void rollback_thenLogsQueryReturnsEntries() throws Exception {
        register(50000);    // v1
        register(100000);   // v2
        mvc.perform(post("/api/v1/admin/flows/f/rollback").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(exec(60000)))
                .andExpect(jsonPath("$.decision").value("REJECT"));
        mvc.perform(get("/api/v1/admin/logs").param("bizId", "B1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].flowId").value("f"))
                .andExpect(jsonPath("$[0].flowVersion").value(1));
    }

    @Test
    void enable_missingVersion_404() throws Exception {
        register(50000);
        mvc.perform(post("/api/v1/admin/flows/f/enable").param("version", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RULE_ENGINE"));
    }
}
```

- [ ] **Step 4: 跑 api 全量测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-api -am test`
Expected: BUILD SUCCESS；M2a `EndToEndApiTest`(5) + 新 `AdminApiTest`(4) 全绿。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(api): 完整 admin 端点(enable/rollback/versions/logs)"
```

---

## Task 4: openrule-jdbc 骨架（pom + schema + 支持工具）

**Files:**
- Create: `openrule-jdbc/pom.xml`
- Modify: `pom.xml`（parent `<modules>` 追加 `openrule-jdbc`；`<dependencyManagement>` 追加 `openrule-jdbc`）
- Create: `openrule-jdbc/src/main/resources/schema.sql`
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/support/ChecksumUtil.java`
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/support/Desensitizer.java`
- Test: `openrule-jdbc/src/test/java/io/openrule/jdbc/support/SupportUtilTest.java`

**Interfaces:**
- Produces:
  - `ChecksumUtil.sha256Hex(String s) : String`（小写 hex）
  - `Desensitizer.mask(Object value, java.util.Set<String> keys) : Object`（递归打码 Map/List）

- [ ] **Step 1: 写 openrule-jdbc/pom.xml**

Create `openrule-jdbc/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.openrule</groupId>
        <artifactId>openrule-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>openrule-jdbc</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>io.openrule</groupId>
            <artifactId>openrule-spring</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```
> 版本全部由 spring-boot BOM（parent 已导入）管理：jdbc/jackson/testcontainers/mysql 驱动均不写版本。

- [ ] **Step 2: parent 追加模块 + dependencyManagement**

Modify 根 `pom.xml`：`<modules>` 改为含 4 模块：
```xml
    <modules>
        <module>openrule-core</module>
        <module>openrule-spring</module>
        <module>openrule-api</module>
        <module>openrule-jdbc</module>
    </modules>
```
并在 `<dependencyManagement><dependencies>` 内（`openrule-spring` 之后）加：
```xml
            <dependency>
                <groupId>io.openrule</groupId>
                <artifactId>openrule-jdbc</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] **Step 3: 写 schema.sql**

Create `openrule-jdbc/src/main/resources/schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS or_flow (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id         VARCHAR(100) NOT NULL,
    flow_name       VARCHAR(200) NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    enabled         TINYINT      NOT NULL DEFAULT 0,
    aggregate_policy VARCHAR(32) NOT NULL DEFAULT 'PRIORITY',
    definition_json MEDIUMTEXT   NOT NULL,
    checksum        VARCHAR(64)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_flow_version (flow_id, version),
    KEY idx_flow_enabled (flow_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS or_execute_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id      VARCHAR(128) NOT NULL,
    flow_id         VARCHAR(100) NOT NULL,
    flow_version    INT          NOT NULL,
    biz_id          VARCHAR(200) NOT NULL,
    decision        VARCHAR(20)  NOT NULL,
    reason          VARCHAR(512),
    total_score     INT          NOT NULL DEFAULT 0,
    cost_millis     INT          NOT NULL,
    hit_nodes       JSON,
    node_results    MEDIUMTEXT,
    facts_snapshot  MEDIUMTEXT,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request (request_id),
    KEY idx_biz (biz_id),
    KEY idx_flow_time (flow_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 4: 写支持工具失败测试**

Create `src/test/java/io/openrule/jdbc/support/SupportUtilTest.java`:
```java
package io.openrule.jdbc.support;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class SupportUtilTest {

    @Test
    void sha256Hex_stableLowercase() {
        assertThat(ChecksumUtil.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_recursivelyMasksConfiguredKeys() {
        Map<String, Object> facts = Map.of(
                "mobile", "13800000000",
                "buyer", Map.of("idCard", "X", "level", "VIP"),
                "items", List.of(Map.of("password", "p", "qty", 2)));
        Object masked = Desensitizer.mask(facts, Set.of("mobile", "idCard", "password"));

        Map<String, Object> m = (Map<String, Object>) masked;
        assertThat(m.get("mobile")).isEqualTo("****");
        assertThat(((Map<String, Object>) m.get("buyer")).get("idCard")).isEqualTo("****");
        assertThat(((Map<String, Object>) m.get("buyer")).get("level")).isEqualTo("VIP");
        Object item0 = ((List<Object>) m.get("items")).get(0);
        assertThat(((Map<String, Object>) item0).get("password")).isEqualTo("****");
        assertThat(((Map<String, Object>) item0).get("qty")).isEqualTo(2);
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=SupportUtilTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`ChecksumUtil`/`Desensitizer` 不存在）。

- [ ] **Step 6: 实现 ChecksumUtil**

Create `support/ChecksumUtil.java`:
```java
package io.openrule.jdbc.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** definition_json 的 SHA-256（小写 hex）。 */
public final class ChecksumUtil {

    private ChecksumUtil() {}

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 7: 实现 Desensitizer**

Create `support/Desensitizer.java`:
```java
package io.openrule.jdbc.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** facts 递归脱敏：命中 key 的值替换为 "****"，嵌套 Map/List 逐层处理。 */
public final class Desensitizer {

    private static final String MASK = "****";

    private Desensitizer() {}

    public static Object mask(Object value, Set<String> keys) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, keys.contains(key) ? MASK : mask(v, keys));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(e -> mask(e, keys)).toList();
        }
        return value;
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=SupportUtilTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 用例）。

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "feat(jdbc): openrule-jdbc 骨架(pom + schema.sql + checksum/脱敏工具)"
```

---

## Task 5: JdbcFlowDefinitionRepository（Testcontainers 真库）

**Files:**
- Create: `openrule-jdbc/src/test/java/io/openrule/jdbc/AbstractMySqlIT.java`
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/repository/JdbcFlowDefinitionRepository.java`
- Test: `openrule-jdbc/src/test/java/io/openrule/jdbc/repository/JdbcFlowDefinitionRepositoryIT.java`

**Interfaces:**
- Consumes: `FlowDefinitionRepository`（端口，含 Task 2 的 `findAllVersions`）、`FlowDefinitionJsonCodec`、`ChecksumUtil`、Spring `JdbcTemplate`/`TransactionTemplate`。
- Produces: `JdbcFlowDefinitionRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager txm, FlowDefinitionJsonCodec codec) implements FlowDefinitionRepository`。

- [ ] **Step 1: 写 Testcontainers 基类**

Create `src/test/java/io/openrule/jdbc/AbstractMySqlIT.java`:
```java
package io.openrule.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 共享 MySQL 容器基类；Docker 缺席自动跳过（disabledWithoutDocker）。 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withInitScript("schema.sql");

    protected JdbcTemplate jdbc;
    protected DataSourceTransactionManager txm;

    @BeforeEach
    void baseSetUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ds.setDriverClassName(MYSQL.getDriverClassName());
        jdbc = new JdbcTemplate(ds);
        txm = new DataSourceTransactionManager(ds);
        jdbc.execute("DELETE FROM or_execute_log");
        jdbc.execute("DELETE FROM or_flow");
    }
}
```

- [ ] **Step 2: 写仓储 IT（失败）**

Create `src/test/java/io/openrule/jdbc/repository/JdbcFlowDefinitionRepositoryIT.java`:
```java
package io.openrule.jdbc.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.jdbc.AbstractMySqlIT;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcFlowDefinitionRepositoryIT extends AbstractMySqlIT {

    private JdbcFlowDefinitionRepository repo;

    private FlowDefinition flow() {
        return FlowDefinition.builder().flowId("f").flowName("n")
                .aggregatePolicy(AggregatePolicy.PRIORITY).stages(List.of()).build();
    }

    @BeforeEach
    void setUp() {
        repo = new JdbcFlowDefinitionRepository(jdbc, txm,
                new FlowDefinitionJsonCodec(new ObjectMapper()));
    }

    @Test
    void firstSave_v1Enabled_withChecksum() {
        FlowDefinition saved = repo.save(flow());
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
        String checksum = jdbc.queryForObject(
                "SELECT checksum FROM or_flow WHERE flow_id='f' AND version=1", String.class);
        assertThat(checksum).hasSize(64);
    }

    @Test
    void secondSave_incrementsAndSwitchesActive_atMostOneEnabled() {
        repo.save(flow());
        FlowDefinition v2 = repo.save(flow());
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(2);
        assertThat(repo.findByFlowIdAndVersion("f", 1)).map(FlowDefinition::isEnabled).contains(false);
        Integer enabledCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM or_flow WHERE flow_id='f' AND enabled=1", Integer.class);
        assertThat(enabledCount).isEqualTo(1);
        assertThat(repo.listVersions("f")).containsExactly(1, 2);
        assertThat(repo.findAllVersions("f")).extracting(FlowDefinition::getVersion).containsExactly(1, 2);
    }

    @Test
    void enable_switchesPointer_missingIsNoop() {
        repo.save(flow());
        repo.save(flow());      // active=v2
        repo.enable("f", 1);    // 回滚 v1
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
        repo.enable("f", 99);   // 不存在 → no-op，active 仍 v1
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
    }

    @Test
    void unknownFlow_empty() {
        assertThat(repo.findActiveByFlowId("nope")).isEmpty();
        assertThat(repo.listVersions("nope")).isEmpty();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=JdbcFlowDefinitionRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`JdbcFlowDefinitionRepository` 不存在）。
> 若本机 Docker 未启动：先 `open -a Docker` 等待就绪；否则该 IT 会被 `disabledWithoutDocker` 跳过（不算通过，需 Docker 才能验证本任务）。

- [ ] **Step 4: 实现 JdbcFlowDefinitionRepository**

Create `repository/JdbcFlowDefinitionRepository.java`:
```java
package io.openrule.jdbc.repository;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.jdbc.support.ChecksumUtil;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.port.FlowDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/** MySQL 实现：版本自增 + enabled 指针事务内原子切换 + checksum。definition_json ⇄ FlowDefinition 复用 codec。 */
public class JdbcFlowDefinitionRepository implements FlowDefinitionRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FlowDefinitionJsonCodec codec;

    public JdbcFlowDefinitionRepository(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                        FlowDefinitionJsonCodec codec) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.codec = codec;
    }

    private final RowMapper<FlowDefinition> mapper = (rs, n) -> {
        FlowDefinition def = codec.parse(rs.getString("definition_json"));
        def.setVersion(rs.getInt("version"));
        def.setEnabled(rs.getBoolean("enabled"));
        return def;
    };

    @Override
    public FlowDefinition save(FlowDefinition def) {
        return tx.execute(status -> {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version),0) FROM or_flow WHERE flow_id=?",
                    Integer.class, def.getFlowId());
            int next = (max == null ? 0 : max) + 1;
            jdbc.update("UPDATE or_flow SET enabled=0 WHERE flow_id=?", def.getFlowId());
            def.setVersion(next);
            def.setEnabled(true);
            String json = codec.toJson(def);
            jdbc.update("INSERT INTO or_flow"
                    + "(flow_id,flow_name,version,enabled,aggregate_policy,definition_json,checksum)"
                    + " VALUES(?,?,?,1,?,?,?)",
                    def.getFlowId(), def.getFlowName(), next,
                    def.getAggregatePolicy() == null ? null : def.getAggregatePolicy().name(),
                    json, ChecksumUtil.sha256Hex(json));
            return def;
        });
    }

    @Override
    public void enable(String flowId, int version) {
        tx.executeWithoutResult(status -> {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM or_flow WHERE flow_id=? AND version=?",
                    Integer.class, flowId, version);
            if (cnt == null || cnt == 0) {
                return; // 不存在 → no-op（与内存语义一致）
            }
            jdbc.update("UPDATE or_flow SET enabled=0 WHERE flow_id=?", flowId);
            jdbc.update("UPDATE or_flow SET enabled=1 WHERE flow_id=? AND version=?", flowId, version);
        });
    }

    @Override
    public Optional<FlowDefinition> findActiveByFlowId(String flowId) {
        return jdbc.query("SELECT definition_json,version,enabled FROM or_flow"
                + " WHERE flow_id=? AND enabled=1 LIMIT 1", mapper, flowId).stream().findFirst();
    }

    @Override
    public Optional<FlowDefinition> findByFlowIdAndVersion(String flowId, int version) {
        return jdbc.query("SELECT definition_json,version,enabled FROM or_flow"
                + " WHERE flow_id=? AND version=?", mapper, flowId, version).stream().findFirst();
    }

    @Override
    public List<Integer> listVersions(String flowId) {
        return jdbc.queryForList(
                "SELECT version FROM or_flow WHERE flow_id=? ORDER BY version", Integer.class, flowId);
    }

    @Override
    public List<FlowDefinition> findAllVersions(String flowId) {
        return jdbc.query("SELECT definition_json,version,enabled FROM or_flow"
                + " WHERE flow_id=? ORDER BY version", mapper, flowId);
    }
}
```

- [ ] **Step 5: 跑测试确认通过（需 Docker）**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=JdbcFlowDefinitionRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 用例，Docker 在场）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(jdbc): JdbcFlowDefinitionRepository(版本/指针事务切换/checksum) + Testcontainers IT"
```

---

## Task 6: JdbcExecutionLogger + JdbcExecutionLogQuery（Testcontainers 真库）

**Files:**
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/audit/JdbcExecutionLogger.java`
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/audit/JdbcExecutionLogQuery.java`
- Test: `openrule-jdbc/src/test/java/io/openrule/jdbc/audit/JdbcAuditIT.java`

**Interfaces:**
- Consumes: `ExecutionLogger`/`ExecutionLogQuery` 端口、`ExecutionLogEntry`、core `FlowResult`/`DecisionContext`（含 `FactMap.asMap()`）、`Desensitizer`、`JdbcTemplate`、Jackson `ObjectMapper`、`java.util.concurrent.Executor`。
- Produces:
  - `JdbcExecutionLogger(JdbcTemplate jdbc, ObjectMapper mapper, java.util.Set<String> desensitizeKeys, Executor executor) implements ExecutionLogger`
  - `JdbcExecutionLogQuery(JdbcTemplate jdbc) implements ExecutionLogQuery`

- [ ] **Step 1: 写审计 IT（失败）**

Create `src/test/java/io/openrule/jdbc/audit/JdbcAuditIT.java`:
```java
package io.openrule.jdbc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.enums.Decision;
import io.openrule.core.result.FlowResult;
import io.openrule.jdbc.AbstractMySqlIT;
import io.openrule.spring.model.ExecutionLogEntry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditIT extends AbstractMySqlIT {

    private static final Executor INLINE = Runnable::run; // 同步执行，便于断言

    private FlowResult result(String reqId, String bizId, Decision d) {
        return FlowResult.builder().requestId(reqId).flowId("f").bizId(bizId).decision(d)
                .reason("r").totalScore(7).costMillis(3).hitNodes(List.of("AMT")).build();
    }

    @Test
    void log_persistsRow_withDesensitizedFacts() {
        JdbcExecutionLogger logger = new JdbcExecutionLogger(
                jdbc, new ObjectMapper(), Set.of("mobile"), INLINE);
        DecisionContext ctx = new DecisionContext("R1", "f", "B1",
                Map.of("mobile", "13800000000", "order", Map.of("amount", 80000)));

        logger.log(result("R1", "B1", Decision.REJECT), ctx, 2);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM or_execute_log WHERE request_id='R1'");
        assertThat(row.get("flow_version")).isEqualTo(2);
        assertThat(row.get("decision")).isEqualTo("REJECT");
        assertThat(row.get("biz_id")).isEqualTo("B1");
        assertThat(row.get("facts_snapshot").toString()).contains("****").doesNotContain("13800000000");
    }

    @Test
    void query_byBizId_recentFirst() {
        JdbcExecutionLogger logger = new JdbcExecutionLogger(
                jdbc, new ObjectMapper(), Set.of(), INLINE);
        logger.log(result("R1", "B1", Decision.PASS), new DecisionContext("R1", "f", "B1", Map.of()), 1);
        logger.log(result("R2", "B1", Decision.REJECT), new DecisionContext("R2", "f", "B1", Map.of()), 1);
        logger.log(result("R3", "B2", Decision.PASS), new DecisionContext("R3", "f", "B2", Map.of()), 1);

        JdbcExecutionLogQuery q = new JdbcExecutionLogQuery(jdbc);
        List<ExecutionLogEntry> byBiz = q.query("B1", null, 10);
        assertThat(byBiz).extracting(ExecutionLogEntry::requestId).containsExactly("R2", "R1");
        assertThat(q.query(null, "f", 1)).hasSize(1);
    }

    @Test
    void log_swallowsFailure_C12() {
        // 故意用错表名触发 SQL 失败：log 不抛
        JdbcExecutionLogger bad = new JdbcExecutionLogger(
                new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
                    @Override public int update(String sql, Object... args) {
                        throw new RuntimeException("boom");
                    }
                }, new ObjectMapper(), Set.of(), INLINE);
        bad.log(result("RX", "B", Decision.PASS), new DecisionContext("RX", "f", "B", Map.of()), 1);
        // 未抛异常即通过（C12）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM or_execute_log", Integer.class)).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=JdbcAuditIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`JdbcExecutionLogger`/`JdbcExecutionLogQuery` 不存在）。

- [ ] **Step 3: 实现 JdbcExecutionLogger**

Create `audit/JdbcExecutionLogger.java`:
```java
package io.openrule.jdbc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;
import io.openrule.jdbc.support.Desensitizer;
import io.openrule.spring.port.ExecutionLogger;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 异步落 or_execute_log；facts 按配置 key 脱敏；失败仅吞 + 计数（C12）。 */
public class JdbcExecutionLogger implements ExecutionLogger {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Set<String> desensitizeKeys;
    private final Executor executor;
    private final AtomicLong failures = new AtomicLong();

    public JdbcExecutionLogger(JdbcTemplate jdbc, ObjectMapper mapper,
                               Set<String> desensitizeKeys, Executor executor) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.desensitizeKeys = desensitizeKeys;
        this.executor = executor;
    }

    public long failureCount() {
        return failures.get();
    }

    @Override
    public void log(FlowResult result, DecisionContext ctx, int flowVersion) {
        executor.execute(() -> {
            try {
                String hitNodes = mapper.writeValueAsString(result.getHitNodes());
                String nodeResults = mapper.writeValueAsString(result.getNodeResults());
                String factsSnapshot = mapper.writeValueAsString(
                        Desensitizer.mask(ctx.getFacts().asMap(), desensitizeKeys));
                jdbc.update("INSERT INTO or_execute_log"
                        + "(request_id,flow_id,flow_version,biz_id,decision,reason,total_score,"
                        + "cost_millis,hit_nodes,node_results,facts_snapshot)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        result.getRequestId(), result.getFlowId(), flowVersion, ctx.getBizId(),
                        result.getDecision() == null ? null : result.getDecision().name(),
                        result.getReason(), result.getTotalScore(), (int) result.getCostMillis(),
                        hitNodes, nodeResults, factsSnapshot);
            } catch (Exception e) {
                failures.incrementAndGet(); // C12：仅吞 + 计数，不冒泡
            }
        });
    }
}
```

- [ ] **Step 4: 实现 JdbcExecutionLogQuery**

Create `audit/JdbcExecutionLogQuery.java`:
```java
package io.openrule.jdbc.audit;

import io.openrule.spring.model.ExecutionLogEntry;
import io.openrule.spring.port.ExecutionLogQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.ZoneId;
import java.util.List;

/** 查询 or_execute_log（按时间倒序）。 */
public class JdbcExecutionLogQuery implements ExecutionLogQuery {

    private final JdbcTemplate jdbc;

    public JdbcExecutionLogQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ExecutionLogEntry> MAPPER = (rs, n) -> new ExecutionLogEntry(
            rs.getString("request_id"), rs.getString("flow_id"), rs.getInt("flow_version"),
            rs.getString("biz_id"), rs.getString("decision"), rs.getString("reason"),
            rs.getInt("total_score"), rs.getInt("cost_millis"),
            rs.getTimestamp("created_at").toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant());

    @Override
    public List<ExecutionLogEntry> query(String bizId, String flowId, int limit) {
        return jdbc.query("SELECT request_id,flow_id,flow_version,biz_id,decision,reason,"
                + "total_score,cost_millis,created_at FROM or_execute_log"
                + " WHERE (? IS NULL OR biz_id=?) AND (? IS NULL OR flow_id=?)"
                + " ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, bizId, bizId, flowId, flowId, limit <= 0 ? Integer.MAX_VALUE : limit);
    }
}
```

- [ ] **Step 5: 跑测试确认通过（需 Docker）**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=JdbcAuditIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 用例，Docker 在场）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(jdbc): JdbcExecutionLogger(异步/脱敏/C12) + JdbcExecutionLogQuery + Testcontainers IT"
```

---

## Task 7: OpenRuleJdbcAutoConfiguration（装配 + 覆盖/退让）

**Files:**
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/autoconfigure/OpenRuleJdbcProperties.java`
- Create: `openrule-jdbc/src/main/java/io/openrule/jdbc/autoconfigure/OpenRuleJdbcAutoConfiguration.java`
- Create: `openrule-jdbc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `openrule-jdbc/src/test/java/io/openrule/jdbc/autoconfigure/OpenRuleJdbcAutoConfigurationTest.java`

**Interfaces:**
- Produces：`@ConditionalOnBean(DataSource)`+`@ConditionalOnMissingBean` 的 `JdbcFlowDefinitionRepository`/`JdbcExecutionLogger`/`JdbcExecutionLogQuery` + 审计虚拟线程 `Executor`；`OpenRuleJdbcProperties`（prefix `openrule.audit`）。

- [ ] **Step 1: 写装配测试（失败）**

Create `src/test/java/io/openrule/jdbc/autoconfigure/OpenRuleJdbcAutoConfigurationTest.java`:
```java
package io.openrule.jdbc.autoconfigure;

import io.openrule.jdbc.audit.JdbcExecutionLogQuery;
import io.openrule.jdbc.audit.JdbcExecutionLogger;
import io.openrule.jdbc.repository.JdbcFlowDefinitionRepository;
import io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.port.ExecutionLogger;
import io.openrule.spring.port.FlowDefinitionRepository;
import io.openrule.spring.standalone.InMemoryExecutionLogger;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRuleJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    OpenRuleAutoConfiguration.class,
                    OpenRuleJdbcAutoConfiguration.class));

    @Test
    void withDataSource_jdbcAdaptersOverrideInMemory() {
        runner.withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:t;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver").run(ctx -> {
            assertThat(ctx.getBean(FlowDefinitionRepository.class))
                    .isInstanceOf(JdbcFlowDefinitionRepository.class);
            assertThat(ctx.getBean(ExecutionLogger.class)).isInstanceOf(JdbcExecutionLogger.class);
            assertThat(ctx.getBean(ExecutionLogQuery.class)).isInstanceOf(JdbcExecutionLogQuery.class);
            assertThat(ctx).doesNotHaveBean(InMemoryFlowDefinitionRepository.class);
        });
    }

    @Test
    void withoutDataSource_staysInMemory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        OpenRuleAutoConfiguration.class,
                        OpenRuleJdbcAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx.getBean(FlowDefinitionRepository.class))
                            .isInstanceOf(InMemoryFlowDefinitionRepository.class);
                    assertThat(ctx.getBean(ExecutionLogger.class))
                            .isInstanceOf(InMemoryExecutionLogger.class);
                });
    }
}
```
> 该测试用 H2（`spring-boot-starter-test` 传递带入）模拟「有 DataSource」，**不需 Docker**，always-on。真库行为由 Task 5/6 的 Testcontainers IT 覆盖。

- [ ] **Step 2: 加 H2 测试依赖**

Modify `openrule-jdbc/pom.xml`，在 test 依赖区加：
```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: 实现 OpenRuleJdbcProperties**

Create `autoconfigure/OpenRuleJdbcProperties.java`:
```java
package io.openrule.jdbc.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 审计配置（脱敏 key 等）。 */
@ConfigurationProperties(prefix = "openrule.audit")
public class OpenRuleJdbcProperties {

    /** facts 快照脱敏的字段名。 */
    private List<String> factsDesensitizeKeys =
            List.of("mobile", "idCard", "bankCard", "password");

    public List<String> getFactsDesensitizeKeys() { return factsDesensitizeKeys; }
    public void setFactsDesensitizeKeys(List<String> v) { this.factsDesensitizeKeys = v; }
}
```

- [ ] **Step 4: 实现 OpenRuleJdbcAutoConfiguration**

Create `autoconfigure/OpenRuleJdbcAutoConfiguration.java`:
```java
package io.openrule.jdbc.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.jdbc.audit.JdbcExecutionLogQuery;
import io.openrule.jdbc.audit.JdbcExecutionLogger;
import io.openrule.jdbc.repository.JdbcFlowDefinitionRepository;
import io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.port.ExecutionLogger;
import io.openrule.spring.port.FlowDefinitionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** JDBC 持久化装配：有 DataSource 时覆盖内存端口实现。 */
@AutoConfiguration(after = OpenRuleAutoConfiguration.class)
@EnableConfigurationProperties(OpenRuleJdbcProperties.class)
@ConditionalOnBean(DataSource.class)
public class OpenRuleJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcFlowDefinitionRepository jdbcFlowDefinitionRepository(
            DataSource dataSource, PlatformTransactionManager txm, FlowDefinitionJsonCodec codec) {
        return new JdbcFlowDefinitionRepository(new JdbcTemplate(dataSource), txm, codec);
    }

    @Bean("openRuleAuditPool")
    @ConditionalOnMissingBean(name = "openRuleAuditPool")
    public Executor openRuleAuditPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionLogger.class)
    public JdbcExecutionLogger jdbcExecutionLogger(DataSource dataSource,
            ObjectProvider<ObjectMapper> objectMapper, OpenRuleJdbcProperties props, Executor openRuleAuditPool) {
        Set<String> keys = new HashSet<>(props.getFactsDesensitizeKeys());
        return new JdbcExecutionLogger(new JdbcTemplate(dataSource),
                objectMapper.getIfAvailable(ObjectMapper::new), keys, openRuleAuditPool);
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionLogQuery.class)
    public JdbcExecutionLogQuery jdbcExecutionLogQuery(DataSource dataSource) {
        return new JdbcExecutionLogQuery(new JdbcTemplate(dataSource));
    }
}
```
> `FlowDefinitionRepository` 注入点：spring 的内存 Bean 是 `@ConditionalOnMissingBean`，本模块提供具体类型即令其退让。`JdbcFlowDefinitionRepository` 实现 `FlowDefinitionRepository`，故覆盖。

- [ ] **Step 5: 写 imports**

Create `openrule-jdbc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
io.openrule.jdbc.autoconfigure.OpenRuleJdbcAutoConfiguration
```

- [ ] **Step 6: 跑装配测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test -Dtest=OpenRuleJdbcAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 用例，H2，不需 Docker）。

- [ ] **Step 7: 跑 jdbc 模块全量测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-jdbc -am test`
Expected: BUILD SUCCESS。Docker 在场：含 2 个 IT；缺席：IT 跳过、装配/工具单测仍绿。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat(jdbc): OpenRuleJdbcAutoConfiguration(有 DataSource 即覆盖内存端口) + Properties"
```

---

## Task 8: 全 reactor 验收 + session 文档

**Files:**
- Create: `docs/sessions/2026-06-24-openrule-m2b-session.md`

- [ ] **Step 1: 从根跑全量测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn clean test`
Expected: `BUILD SUCCESS`；四模块全绿（core 56 + spring 含新测 + api 含 AdminApiTest + jdbc 工具/装配；Docker 在场再加 2 个真库 IT）。记录总测试数。

- [ ] **Step 2: 安装四件制品（含 jdbc）**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -q -DskipTests install`
Expected: `openrule-parent/core/spring/api/jdbc` 均 install 成功。

- [ ] **Step 3: 纯净性自检**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl openrule-jdbc dependency:tree | grep -iE "ycr" || echo "✓ jdbc 无 ycr"
mvn -pl openrule-spring dependency:tree | grep -iE "spring-jdbc|datasource|spring-web" || echo "✓ spring 仍无 jdbc/web"
```
Expected: jdbc 无 ycr；spring 不含 spring-jdbc/spring-web。

- [ ] **Step 4: 写 M2b session 记录**

Create `docs/sessions/2026-06-24-openrule-m2b-session.md`，内容含：一句话现状（M2b 完成，4 模块 reactor，JdbcTemplate 真库持久化 + 完整 admin + Testcontainers）、已敲定决策（中立 JDBC/非 ycr、JdbcTemplate、save 即启用、异步审计模块自带池、Docker 缺席跳过）、相对计划的偏差（FactMap.asMap() 第二处 core 加法、`ExecutionLogger` 端口加 flowVersion、`@ConditionalOnBean(DataSource)` 覆盖机制）、下一步（Redis 多实例热更新一轮 / ycr-starter-rule）、构建备忘（JDK21 + Docker 起停 + Testcontainers 跳过语义）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "docs: M2b 完成,session 记录 + 全 reactor 验收"
```

---

## 验收清单（M2b 完成标志）

- [ ] `mvn clean test` 从根全绿（Docker 在场含 Testcontainers IT；缺席自动跳过且 BUILD SUCCESS）。
- [ ] 真库链路（Docker 在场）：注册 → execute 落 `or_execute_log`（facts 脱敏）→ `/admin/logs` 查到 → 多版本 `/versions` → `/rollback` 切指针 → execute 走旧版本。
- [ ] `mvn install` 五件制品（parent/core/spring/api/jdbc）成功。
- [ ] 纯净性：`openrule-jdbc` 零 ycr；`openrule-spring`/`openrule-api` 不引 jdbc 模块时零 DataSource。
- [ ] 约束：save/enable 指针事务内原子切换（至多一个 enabled）、C11、C12（审计失败计数不冒泡）、端口 `@ConditionalOnBean(DataSource)`+`@ConditionalOnMissingBean` 覆盖/退让经测试覆盖。

---

## Self-Review（写计划后自查）

**Spec coverage**：spec §3 模块→Task4/7；§4 schema→Task4；§5 仓储→Task5；§6 审计写+读端口→Task1（端口/模型/内存）+Task6（jdbc）；§7 admin 端点→Task2（服务）+Task3（REST）；§8 装配→Task7；§10 测试→各 Task IT/MockMvc；§11 验收→Task8 + 验收清单；§12 偏差→Task8 session。`ExecutionLogger.log` 加 flowVersion + `FactMap.asMap()` 落 Task1。覆盖完整。

**Placeholder scan**：无 TBD/TODO；每步含完整可粘贴代码与确切命令/期望。

**Type consistency**：`ExecutionLogger.log(FlowResult,DecisionContext,int)` 在 Task1 定义，Task6 `JdbcExecutionLogger`/Task1 `InMemoryExecutionLogger` 一致实现，调用处 Task1 `OpenRuleService.safeLog` 一致；`ExecutionLogQuery.query(String,String,int):List<ExecutionLogEntry>` 在 Task1 定义，Task3 控制器/Task6 `JdbcExecutionLogQuery`/Task1 内存实现一致；`ExecutionLogEntry` 字段（requestId/flowId/flowVersion/bizId/decision/reason/totalScore/costMillis/createdAt）跨 Task1/3/6 一致；`FlowDefinitionRepository.findAllVersions(String):List<FlowDefinition>` 在 Task2 定义，Task5 jdbc 实现一致；`OpenRuleService.{listVersions,enableVersion,rollback}` 在 Task2 定义，Task3 控制器一致；`ChecksumUtil.sha256Hex`/`Desensitizer.mask` 在 Task4 定义，Task5/6 引用一致；`FactMap.asMap()` 在 Task1 定义，Task6 引用一致；core API（`FlowResult` getters、`DecisionContext.getFacts/getBizId`、`FlowDefinition` setter）与现有实现一致。
