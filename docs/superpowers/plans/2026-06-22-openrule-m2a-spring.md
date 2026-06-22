# OpenRule M2a · openrule-spring + openrule-api Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 M1 的纯 Java 内核封装成一个用纯 Spring Boot 即可启动、零外部中间件、可端到端 MockMvc 单测的决策服务。

**Architecture:** 端口-适配（六边形）。`openrule-spring` 只依赖自己拥有的 3 个端口（仓储/变更通知/执行日志），M2a 提供进程内 standalone 实现；`openrule-api` 暴露 REST（控制器返回裸 DTO + 条件化异常 advice）。core 零改动。依赖严格单向 `api → spring → core`。

**Tech Stack:** Java 21（虚拟线程）· Maven 多模块 reactor · Spring Boot 3.3.x · Caffeine · Jackson · Lombok · JUnit 5 · AssertJ · Spring Boot Test（MockMvc）。

**配套设计文档：** `docs/superpowers/specs/2026-06-22-openrule-m2a-spring-design.md`

## Global Constraints

- 包根：`openrule-spring` 用 `io.openrule.spring`，`openrule-api` 用 `io.openrule.api`。core 仍是 `io.openrule.core`，**本计划不改 core 任何一行**。
- `openrule-spring` **不依赖 spring-web、不依赖任何 ycr starter**；只依赖 `openrule-core` + `spring-boot-autoconfigure` + Caffeine + Jackson。
- `openrule-api` 控制器一律返回**裸 DTO**（不自包 `R`）；异常 advice 用 `@ConditionalOnProperty(matchIfMissing=true)` 默认开、可被关掉（ycr 退让）。
- Java 21；Spring Boot **3.3.5**。提交信息用中文，**不带** `Co-Authored-By` 尾注（与本仓库已有提交一致）。
- 跑构建需 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`（本机默认 JDK 17）。
- M2a 加载/执行的流程**只含 OPERATOR 节点**（目前唯一注册的执行器）。
- 内存仓储语义：同 `flowId` 每次 `save` 版本号自增（首次=1），新版本置为唯一 `enabled`，旧版本转非启用；`findActiveByFlowId` 恒返回 `enabled` 版本。
- 并发约束沿用 M1（C1–C3/C7–C11）；新增 C12：`ExecutionLogger.log` 失败不影响主流程（调用处 try/catch）。

---

## 文件结构总览

```
open-rule/
├── pom.xml                         (新) parent 聚合 pom：packaging=pom，dependencyManagement 导入 spring-boot BOM，pluginManagement 配 compiler+lombok+surefire，modules
├── openrule-core/                  (移动) M1 工程整体迁入此目录；pom 加 <parent>，零代码改动
│   ├── pom.xml
│   └── src/...
├── openrule-spring/                (新)
│   ├── pom.xml
│   └── src/main/java/io/openrule/spring/
│   │   ├── port/        FlowDefinitionRepository  FlowChangeNotifier  ExecutionLogger
│   │   ├── model/       ExecuteCommand  ExecutionOutcome
│   │   ├── standalone/  InMemoryFlowDefinitionRepository  InMemoryExecutionLogger  LocalFlowChangeNotifier
│   │   ├── loader/      FlowCompiler  FlowLoader  FlowDefinitionJsonCodec
│   │   ├── service/     OpenRuleService
│   │   └── autoconfigure/  OpenRuleProperties  OpenRuleAutoConfiguration
│   ├── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── src/test/java/io/openrule/spring/...
└── openrule-api/                   (新)
    ├── pom.xml
    └── src/main/java/io/openrule/api/
    │   ├── dto/         ExecuteRequest  ExecuteResponse  SimulateRequest  FlowSummary  ErrorBody
    │   ├── ExecuteController  FlowAdminController
    │   ├── advice/      OpenRuleExceptionAdvice
    │   └── autoconfigure/  OpenRuleApiAutoConfiguration
    ├── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── src/test/java/io/openrule/api/...
```

---

## Task 1: Reactor 重构（parent pom + core 降级为子模块）

**Files:**
- Move: `pom.xml` → `openrule-core/pom.xml`；`src/` → `openrule-core/src/`
- Create: `pom.xml`（新的 parent 聚合 pom）
- Modify: `openrule-core/pom.xml`（加 `<parent>`，去掉 groupId/version/properties/build，靠继承）

**Interfaces:**
- Produces: parent GAV `io.openrule:openrule-parent:1.0.0-SNAPSHOT`（packaging=pom）；子模块 `io.openrule:openrule-core:1.0.0-SNAPSHOT`。

- [ ] **Step 1: 把 M1 工程整体迁入 openrule-core/**

Run:
```bash
cd /Users/ycr/IdeaProjects/Sandbox/open-rule
mkdir -p openrule-core
git mv pom.xml openrule-core/pom.xml
git mv src openrule-core/src
```
Expected: `git status` 显示 `pom.xml`、`src/` 重命名到 `openrule-core/` 下。

- [ ] **Step 2: 写新的 parent 聚合 pom**

Create `pom.xml`（仓库根）：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.openrule</groupId>
    <artifactId>openrule-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>openrule-core</module>
    </modules>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <lombok.version>1.18.34</lombok.version>
        <junit.version>5.10.3</junit.version>
        <assertj.version>3.26.3</assertj.version>
        <spring-boot.version>3.3.5</spring-boot.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.openrule</groupId>
                <artifactId>openrule-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.openrule</groupId>
                <artifactId>openrule-spring</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
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
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 3: 改 openrule-core/pom.xml 继承 parent**

把 `openrule-core/pom.xml` 整体替换为（去掉自带 groupId/version/properties/build，全部继承 parent；保留显式依赖版本以让 core 自洽）：
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

    <artifactId>openrule-core</artifactId>
    <packaging>jar</packaging>

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
</project>
```
> 说明：core 不再写 `<build>`——compiler（含 lombok 注解处理器）与 surefire 都从 parent 的 `pluginManagement` 继承（二者绑定在默认生命周期，无需在子模块 `<plugins>` 重复声明即生效）。

- [ ] **Step 4: 从根跑全量测试，确认 M1 56 测试仍全绿**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn test`
Expected: `Tests run: 56, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（reactor 构建 openrule-core）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor: 升级为多模块 reactor（parent + openrule-core 子模块），M1 测试不变"
```

---

## Task 2: openrule-spring 骨架 + 三端口 + 命令/结果模型

**Files:**
- Create: `openrule-spring/pom.xml`
- Modify: `pom.xml`（parent `<modules>` 追加 `openrule-spring`）
- Create: `openrule-spring/src/main/java/io/openrule/spring/port/FlowDefinitionRepository.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/port/FlowChangeNotifier.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/port/ExecutionLogger.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/model/ExecuteCommand.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/model/ExecutionOutcome.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/SpringModuleSanityTest.java`

**Interfaces:**
- Consumes: core `FlowDefinition`、`FlowResult`、`DecisionContext`。
- Produces:
  - `FlowDefinitionRepository`: `Optional<FlowDefinition> findActiveByFlowId(String)`、`Optional<FlowDefinition> findByFlowIdAndVersion(String,int)`、`FlowDefinition save(FlowDefinition)`、`void enable(String,int)`、`List<Integer> listVersions(String)`
  - `FlowChangeNotifier`: `void publishInvalidation(String flowId)`
  - `ExecutionLogger`: `void log(FlowResult result, DecisionContext ctx)`
  - `ExecuteCommand`（record）: `String flowId, String requestId, String bizId, boolean debug, Map<String,Object> facts`
  - `ExecutionOutcome`（record）: `FlowResult result, int flowVersion`

- [ ] **Step 1: 写 openrule-spring/pom.xml**

Create `openrule-spring/pom.xml`:
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

    <artifactId>openrule-spring</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>io.openrule</groupId>
            <artifactId>openrule-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
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
    </dependencies>
</project>
```
> 版本：core 内部依赖由 parent `dependencyManagement` 管理；spring/caffeine/jackson/junit/assertj 由 spring-boot BOM 管理，故不写版本。

- [ ] **Step 2: parent 追加模块**

Modify `pom.xml`，把 `<modules>` 改为：
```xml
    <modules>
        <module>openrule-core</module>
        <module>openrule-spring</module>
    </modules>
```

- [ ] **Step 3: 写三个端口接口**

Create `port/FlowDefinitionRepository.java`:
```java
package io.openrule.spring.port;

import io.openrule.core.definition.FlowDefinition;

import java.util.List;
import java.util.Optional;

/** 流程定义仓储端口。standalone=内存；M2b=MySQL（ycr data-mp）。 */
public interface FlowDefinitionRepository {

    Optional<FlowDefinition> findActiveByFlowId(String flowId);

    Optional<FlowDefinition> findByFlowIdAndVersion(String flowId, int version);

    /** 保存为新版本：版本号自增、置为唯一 enabled，旧版本转非启用。返回带最终 version 的定义。 */
    FlowDefinition save(FlowDefinition def);

    void enable(String flowId, int version);

    List<Integer> listVersions(String flowId);
}
```

Create `port/FlowChangeNotifier.java`:
```java
package io.openrule.spring.port;

/** 流程变更通知端口。standalone=本地直接失效缓存；M2b=Redis Pub/Sub。 */
public interface FlowChangeNotifier {
    void publishInvalidation(String flowId);
}
```

Create `port/ExecutionLogger.java`:
```java
package io.openrule.spring.port;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;

/** 执行日志端口。standalone=内存缓冲；M2b=异步落库/ES。实现内部必须吞掉异常（C12）。 */
public interface ExecutionLogger {
    void log(FlowResult result, DecisionContext ctx);
}
```

- [ ] **Step 4: 写命令/结果模型**

Create `model/ExecuteCommand.java`:
```java
package io.openrule.spring.model;

import java.util.Map;

/** 服务层执行入参（与 api DTO 解耦）。 */
public record ExecuteCommand(String flowId, String requestId, String bizId,
                             boolean debug, Map<String, Object> facts) {
}
```

Create `model/ExecutionOutcome.java`:
```java
package io.openrule.spring.model;

import io.openrule.core.result.FlowResult;

/** 编排结果：core FlowResult + 命中的流程版本（core 不持有版本，由此补齐）。 */
public record ExecutionOutcome(FlowResult result, int flowVersion) {
}
```

- [ ] **Step 5: 写 sanity 测试**

Create `src/test/java/io/openrule/spring/SpringModuleSanityTest.java`:
```java
package io.openrule.spring;

import io.openrule.spring.model.ExecuteCommand;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SpringModuleSanityTest {
    @Test
    void commandCarriesFields() {
        ExecuteCommand c = new ExecuteCommand("f", "R1", "B1", true, Map.of("k", 1));
        assertThat(c.flowId()).isEqualTo("f");
        assertThat(c.debug()).isTrue();
        assertThat(c.facts()).containsEntry("k", 1);
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -am test`
Expected: BUILD SUCCESS，SpringModuleSanityTest 通过。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(spring): openrule-spring 骨架 + 三端口接口 + 命令/结果模型"
```

---

## Task 3: standalone 内存适配器（仓储 + 执行日志）

**Files:**
- Create: `openrule-spring/src/main/java/io/openrule/spring/standalone/InMemoryFlowDefinitionRepository.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/standalone/InMemoryExecutionLogger.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/standalone/InMemoryFlowDefinitionRepositoryTest.java`

**Interfaces:**
- Consumes: `FlowDefinitionRepository`、`ExecutionLogger`、core `FlowDefinition`/`FlowResult`/`DecisionContext`。
- Produces:
  - `InMemoryFlowDefinitionRepository implements FlowDefinitionRepository`
  - `InMemoryExecutionLogger implements ExecutionLogger` + `List<FlowResult> recent()`（读最近日志，供测试/demo）

- [ ] **Step 1: 写失败测试（版本/启用语义）**

Create `src/test/java/io/openrule/spring/standalone/InMemoryFlowDefinitionRepositoryTest.java`:
```java
package io.openrule.spring.standalone;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFlowDefinitionRepositoryTest {

    private FlowDefinition flow(String id) {
        return FlowDefinition.builder()
                .flowId(id).flowName("n").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of()).build();
    }

    @Test
    void firstSaveBecomesVersion1AndEnabled() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        FlowDefinition saved = repo.save(flow("f"));
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
    }

    @Test
    void secondSaveIncrementsVersionAndSwitchesActive() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(flow("f"));
        FlowDefinition v2 = repo.save(flow("f"));
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(2);
        assertThat(repo.findByFlowIdAndVersion("f", 1)).map(FlowDefinition::isEnabled).contains(false);
        assertThat(repo.listVersions("f")).containsExactly(1, 2);
    }

    @Test
    void enableSwitchesActivePointer() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(flow("f"));
        repo.save(flow("f"));      // active = v2
        repo.enable("f", 1);       // 回滚到 v1
        assertThat(repo.findActiveByFlowId("f")).map(FlowDefinition::getVersion).contains(1);
    }

    @Test
    void unknownFlowHasNoActive() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        assertThat(repo.findActiveByFlowId("nope")).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=InMemoryFlowDefinitionRepositoryTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 InMemoryFlowDefinitionRepository**

Create `standalone/InMemoryFlowDefinitionRepository.java`:
```java
package io.openrule.spring.standalone;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.spring.port.FlowDefinitionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内仓储：flowId → (version → 定义)。版本自增、唯一 enabled（见 Global Constraints）。 */
public class InMemoryFlowDefinitionRepository implements FlowDefinitionRepository {

    private final Map<String, TreeMap<Integer, FlowDefinition>> store = new ConcurrentHashMap<>();

    @Override
    public synchronized FlowDefinition save(FlowDefinition def) {
        TreeMap<Integer, FlowDefinition> versions =
                store.computeIfAbsent(def.getFlowId(), k -> new TreeMap<>());
        int next = versions.isEmpty() ? 1 : versions.lastKey() + 1;
        versions.values().forEach(d -> d.setEnabled(false));
        def.setVersion(next);
        def.setEnabled(true);
        versions.put(next, def);
        return def;
    }

    @Override
    public synchronized void enable(String flowId, int version) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        if (versions == null || !versions.containsKey(version)) {
            return;
        }
        versions.values().forEach(d -> d.setEnabled(false));
        versions.get(version).setEnabled(true);
    }

    @Override
    public Optional<FlowDefinition> findActiveByFlowId(String flowId) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        if (versions == null) {
            return Optional.empty();
        }
        return versions.values().stream().filter(FlowDefinition::isEnabled).findFirst();
    }

    @Override
    public Optional<FlowDefinition> findByFlowIdAndVersion(String flowId, int version) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public List<Integer> listVersions(String flowId) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        return versions == null ? List.of() : new ArrayList<>(versions.keySet());
    }
}
```

- [ ] **Step 4: 实现 InMemoryExecutionLogger**

Create `standalone/InMemoryExecutionLogger.java`:
```java
package io.openrule.spring.standalone;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.result.FlowResult;
import io.openrule.spring.port.ExecutionLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 进程内有界执行日志缓冲（最近 N 条），供 demo/测试读取。 */
public class InMemoryExecutionLogger implements ExecutionLogger {

    private static final int MAX = 500;
    private final Deque<FlowResult> buffer = new ArrayDeque<>();

    @Override
    public synchronized void log(FlowResult result, DecisionContext ctx) {
        if (buffer.size() >= MAX) {
            buffer.pollFirst();
        }
        buffer.offerLast(result);
    }

    public synchronized List<FlowResult> recent() {
        return new ArrayList<>(buffer);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=InMemoryFlowDefinitionRepositoryTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(spring): standalone 内存仓储(版本/启用语义) + 内存执行日志"
```

---

## Task 4: FlowDefinitionJsonCodec（Jackson 往返）

**Files:**
- Create: `openrule-spring/src/main/java/io/openrule/spring/loader/FlowDefinitionJsonCodec.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/loader/FlowDefinitionJsonCodecTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`、`JsonNode`；core `FlowDefinition`；core `FlowValidationException`。
- Produces: `FlowDefinitionJsonCodec`（ctor `ObjectMapper`）: `FlowDefinition parse(JsonNode)`、`FlowDefinition parse(String)`、`String toJson(FlowDefinition)`。

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/spring/loader/FlowDefinitionJsonCodecTest.java`:
```java
package io.openrule.spring.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowDefinitionJsonCodecTest {

    private final FlowDefinitionJsonCodec codec = new FlowDefinitionJsonCodec(new ObjectMapper());

    private static final String ORDER_RISK = """
        {
          "flowId": "order_risk", "flowName": "订单风控", "aggregatePolicy": "PRIORITY",
          "stages": [
            {
              "stageId": "s1", "stageName": "硬规则", "order": 100,
              "executionMode": "SERIAL", "skipWhenStopped": true,
              "nodes": [
                {
                  "nodeId": "AMOUNT_LIMIT", "nodeName": "金额上限", "nodeType": "OPERATOR", "order": 20,
                  "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": 50000 },
                  "decisionOnHit": "REJECT", "stopOnHit": true, "failPolicy": "SKIP", "timeoutMillis": 500
                }
              ]
            }
          ]
        }
        """;

    @Test
    void parsesOperatorFlow() {
        FlowDefinition def = codec.parse(ORDER_RISK);
        assertThat(def.getFlowId()).isEqualTo("order_risk");
        var node = def.getStages().get(0).getNodes().get(0);
        assertThat(node.getNodeType()).isEqualTo(NodeType.OPERATOR);
        assertThat(node.getOperatorDef().getOperator()).isEqualTo("GT");
        assertThat(node.getOperatorDef().getRightValue()).isEqualTo(50000);
    }

    @Test
    void roundTrips() {
        FlowDefinition def = codec.parse(ORDER_RISK);
        FlowDefinition again = codec.parse(codec.toJson(def));
        assertThat(again.getFlowId()).isEqualTo("order_risk");
        assertThat(again.getStages().get(0).getNodes().get(0).getOperatorDef().getOperator())
                .isEqualTo("GT");
    }

    @Test
    void malformedJsonThrowsFlowValidation() {
        assertThatThrownBy(() -> codec.parse("{ not json"))
                .isInstanceOf(FlowValidationException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=FlowDefinitionJsonCodecTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 FlowDefinitionJsonCodec**

Create `loader/FlowDefinitionJsonCodec.java`:
```java
package io.openrule.spring.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.exception.FlowValidationException;

/** 流程定义 JSON ⇄ FlowDefinition（单一解析点；M2b 复用其做 definition_json/checksum）。 */
public class FlowDefinitionJsonCodec {

    private final ObjectMapper mapper;

    public FlowDefinitionJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public FlowDefinition parse(JsonNode node) {
        try {
            return mapper.treeToValue(node, FlowDefinition.class);
        } catch (Exception e) {
            throw new FlowValidationException("流程定义 JSON 解析失败: " + e.getMessage());
        }
    }

    public FlowDefinition parse(String json) {
        try {
            return mapper.readValue(json, FlowDefinition.class);
        } catch (Exception e) {
            throw new FlowValidationException("流程定义 JSON 解析失败: " + e.getMessage());
        }
    }

    public String toJson(FlowDefinition def) {
        try {
            return mapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new FlowValidationException("流程定义序列化失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=FlowDefinitionJsonCodecTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(spring): FlowDefinitionJsonCodec(Jackson 往返 + 解析失败转 FlowValidationException)"
```

---

## Task 5: FlowCompiler + FlowLoader（Caffeine 缓存 / 按版本失效）

**Files:**
- Create: `openrule-spring/src/main/java/io/openrule/spring/loader/FlowCompiler.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/loader/FlowLoader.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/loader/FlowLoaderTest.java`

**Interfaces:**
- Consumes: core `NodeExecutorRegistry`、`FlowDefinition`/`StageDefinition`/`NodeDefinition`、`CompiledFlow`/`CompiledStage`、`CompiledNode`、`RuleEngineException`、`FlowValidationException`、`OperatorNodeExecutor`；`FlowDefinitionRepository`。
- Produces:
  - `FlowCompiler`（ctor `NodeExecutorRegistry`）: `CompiledFlow compile(FlowDefinition)`、`void validate(FlowDefinition)`
  - `FlowLoader`（ctor `FlowDefinitionRepository`, `FlowCompiler`）: `CompiledFlow loadActive(String)`、`void invalidate(String)`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/spring/loader/FlowLoaderTest.java`:
```java
package io.openrule.spring.loader;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.enums.ExecutionMode;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowLoaderTest {

    private FlowCompiler compiler() {
        return new FlowCompiler(new NodeExecutorRegistry(List.of(new OperatorNodeExecutor())));
    }

    private FlowDefinition opFlow(String id) {
        OperatorDef op = new OperatorDef();
        op.setLeftFact("fact.order.amount");
        op.setOperator("GT");
        op.setRightValue(50000);
        NodeDefinition node = NodeDefinition.builder()
                .nodeId("AMOUNT").nodeType(NodeType.OPERATOR).order(20).operatorDef(op).build();
        StageDefinition stage = StageDefinition.builder()
                .stageId("s1").order(100).executionMode(ExecutionMode.SERIAL).skipWhenStopped(true)
                .nodes(List.of(node)).build();
        return FlowDefinition.builder()
                .flowId(id).flowName("n").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of(stage)).build();
    }

    @Test
    void loadActiveCompilesAndCaches() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(opFlow("f"));
        FlowLoader loader = new FlowLoader(repo, compiler());

        CompiledFlow first = loader.loadActive("f");
        CompiledFlow second = loader.loadActive("f");
        assertThat(first).isSameAs(second);              // 缓存命中：同一对象
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(first.getStages()).hasSize(1);
    }

    @Test
    void newVersionAfterInvalidateRecompiles() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(opFlow("f"));
        FlowLoader loader = new FlowLoader(repo, compiler());
        CompiledFlow v1 = loader.loadActive("f");

        repo.save(opFlow("f"));        // 现 active = v2
        loader.invalidate("f");
        CompiledFlow v2 = loader.loadActive("f");
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2).isNotSameAs(v1);
    }

    @Test
    void missingFlowThrows() {
        FlowLoader loader = new FlowLoader(new InMemoryFlowDefinitionRepository(), compiler());
        assertThatThrownBy(() -> loader.loadActive("nope"))
                .isInstanceOf(RuleEngineException.class)
                .hasMessageContaining("not found");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=FlowLoaderTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 FlowCompiler**

Create `loader/FlowCompiler.java`:
```java
package io.openrule.spring.loader;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.StageDefinition;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.CompiledStage;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.spi.CompiledNode;

import java.util.Comparator;
import java.util.List;

/** 把 FlowDefinition 编译成 CompiledFlow（按 order 排序 + 逐节点 SPI compile）；并提供保存期 validate。 */
public class FlowCompiler {

    private final NodeExecutorRegistry registry;

    public FlowCompiler(NodeExecutorRegistry registry) {
        this.registry = registry;
    }

    public CompiledFlow compile(FlowDefinition def) {
        List<CompiledStage> stages = def.getStages().stream()
                .sorted(Comparator.comparingInt(StageDefinition::getOrder))
                .map(this::compileStage)
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

    /** 保存期校验：逐节点调 SPI validate（残缺配置抛 FlowValidationException）。 */
    public void validate(FlowDefinition def) {
        for (StageDefinition stage : def.getStages()) {
            for (NodeDefinition node : stage.getNodes()) {
                registry.getRequired(node.getNodeType()).validate(node);
            }
        }
    }
}
```

- [ ] **Step 4: 实现 FlowLoader**

Create `loader/FlowLoader.java`:
```java
package io.openrule.spring.loader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.spring.port.FlowDefinitionRepository;

import java.time.Duration;

/** 加载并缓存 CompiledFlow（key=flowId:v{version}，C11）。失效清该 flow 全部版本。 */
public class FlowLoader {

    private final FlowDefinitionRepository repository;
    private final FlowCompiler compiler;
    private final Cache<String, CompiledFlow> cache;

    public FlowLoader(FlowDefinitionRepository repository, FlowCompiler compiler) {
        this(repository, compiler, 500, Duration.ofHours(2));
    }

    public FlowLoader(FlowDefinitionRepository repository, FlowCompiler compiler,
                      long maxSize, Duration expireAfterAccess) {
        this.repository = repository;
        this.compiler = compiler;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize).expireAfterAccess(expireAfterAccess)
                .recordStats().build();
    }

    public CompiledFlow loadActive(String flowId) {
        FlowDefinition def = repository.findActiveByFlowId(flowId)
                .orElseThrow(() -> new RuleEngineException("Flow not found or disabled: " + flowId));
        return cache.get(flowId + ":v" + def.getVersion(), k -> compiler.compile(def));
    }

    public void invalidate(String flowId) {
        cache.asMap().keySet().removeIf(k -> k.startsWith(flowId + ":"));
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=FlowLoaderTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(spring): FlowCompiler(排序+SPI compile/validate) + FlowLoader(Caffeine 按版本缓存/失效)"
```

---

## Task 6: LocalFlowChangeNotifier

**Files:**
- Create: `openrule-spring/src/main/java/io/openrule/spring/standalone/LocalFlowChangeNotifier.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/standalone/LocalFlowChangeNotifierTest.java`

**Interfaces:**
- Consumes: `FlowChangeNotifier`、`FlowLoader`。
- Produces: `LocalFlowChangeNotifier implements FlowChangeNotifier`（ctor `FlowLoader`）。

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/spring/standalone/LocalFlowChangeNotifierTest.java`:
```java
package io.openrule.spring.standalone;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.AggregatePolicy;
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.spring.loader.FlowCompiler;
import io.openrule.spring.loader.FlowLoader;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LocalFlowChangeNotifierTest {

    private FlowDefinition flow(String id) {
        return FlowDefinition.builder()
                .flowId(id).flowName("n").aggregatePolicy(AggregatePolicy.PRIORITY)
                .stages(List.of()).build();
    }

    @Test
    void publishInvalidationClearsLoaderCache() {
        InMemoryFlowDefinitionRepository repo = new InMemoryFlowDefinitionRepository();
        repo.save(flow("f"));
        FlowLoader loader = new FlowLoader(repo,
                new FlowCompiler(new NodeExecutorRegistry(List.of(new OperatorNodeExecutor()))));
        CompiledFlow v1 = loader.loadActive("f");

        repo.save(flow("f"));   // active = v2
        new LocalFlowChangeNotifier(loader).publishInvalidation("f");

        CompiledFlow after = loader.loadActive("f");
        assertThat(after.getVersion()).isEqualTo(2);
        assertThat(after).isNotSameAs(v1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=LocalFlowChangeNotifierTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 LocalFlowChangeNotifier**

Create `standalone/LocalFlowChangeNotifier.java`:
```java
package io.openrule.spring.standalone;

import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.port.FlowChangeNotifier;

/** 单进程：流程变更直接失效本地 FlowLoader 缓存（M2b 换 Redis Pub/Sub 跨实例广播）。 */
public class LocalFlowChangeNotifier implements FlowChangeNotifier {

    private final FlowLoader flowLoader;

    public LocalFlowChangeNotifier(FlowLoader flowLoader) {
        this.flowLoader = flowLoader;
    }

    @Override
    public void publishInvalidation(String flowId) {
        flowLoader.invalidate(flowId);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=LocalFlowChangeNotifierTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(spring): LocalFlowChangeNotifier(单进程直接失效缓存)"
```

---

## Task 7: OpenRuleService（门面：execute / registerFlow / simulate）

**Files:**
- Create: `openrule-spring/src/main/java/io/openrule/spring/service/OpenRuleService.java`
- Test: `openrule-spring/src/test/java/io/openrule/spring/service/OpenRuleServiceTest.java`

**Interfaces:**
- Consumes: `FlowLoader`、`FlowCompiler`、core `FlowExecutor`、`FlowDefinitionRepository`、`ExecutionLogger`、`FlowChangeNotifier`、core `DecisionContext`/`FlowResult`/`FlowDefinition`/`CompiledFlow`；`ExecuteCommand`、`ExecutionOutcome`。
- Produces: `OpenRuleService`（ctor `FlowLoader, FlowCompiler, FlowExecutor, FlowDefinitionRepository, ExecutionLogger, FlowChangeNotifier`）:
  - `ExecutionOutcome execute(ExecuteCommand cmd)`
  - `FlowDefinition registerFlow(FlowDefinition def)`
  - `ExecutionOutcome simulate(FlowDefinition draft, Map<String,Object> facts)`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/io/openrule/spring/service/OpenRuleServiceTest.java`:
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
import io.openrule.core.executor.OperatorNodeExecutor;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.core.runtime.NodeExecutorRegistry;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.core.runtime.ParallelStageExecutor;
import io.openrule.core.runtime.SerialStageExecutor;
import io.openrule.spring.loader.FlowCompiler;
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
        NodeRunner runner = new NodeRunner(registry, pool);
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=OpenRuleServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 OpenRuleService**

Create `service/OpenRuleService.java`:
```java
package io.openrule.spring.service;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.result.FlowResult;
import io.openrule.core.runtime.CompiledFlow;
import io.openrule.core.runtime.FlowExecutor;
import io.openrule.spring.loader.FlowCompiler;
import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.model.ExecuteCommand;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.port.ExecutionLogger;
import io.openrule.spring.port.FlowChangeNotifier;
import io.openrule.spring.port.FlowDefinitionRepository;

import java.util.Map;
import java.util.UUID;

/** 对外编排门面：execute / registerFlow / simulate。 */
public class OpenRuleService {

    private final FlowLoader flowLoader;
    private final FlowCompiler flowCompiler;
    private final FlowExecutor flowExecutor;
    private final FlowDefinitionRepository repository;
    private final ExecutionLogger executionLogger;
    private final FlowChangeNotifier changeNotifier;

    public OpenRuleService(FlowLoader flowLoader, FlowCompiler flowCompiler, FlowExecutor flowExecutor,
                           FlowDefinitionRepository repository, ExecutionLogger executionLogger,
                           FlowChangeNotifier changeNotifier) {
        this.flowLoader = flowLoader;
        this.flowCompiler = flowCompiler;
        this.flowExecutor = flowExecutor;
        this.repository = repository;
        this.executionLogger = executionLogger;
        this.changeNotifier = changeNotifier;
    }

    public ExecutionOutcome execute(ExecuteCommand cmd) {
        String requestId = (cmd.requestId() == null || cmd.requestId().isBlank())
                ? UUID.randomUUID().toString() : cmd.requestId();
        DecisionContext ctx = new DecisionContext(requestId, cmd.flowId(), cmd.bizId(), cmd.facts());
        CompiledFlow flow = flowLoader.loadActive(cmd.flowId());
        FlowResult result = flowExecutor.execute(ctx, flow);
        safeLog(result, ctx);
        return new ExecutionOutcome(result, flow.getVersion());
    }

    public FlowDefinition registerFlow(FlowDefinition def) {
        flowCompiler.validate(def);                 // 残缺配置抛 FlowValidationException
        FlowDefinition saved = repository.save(def);
        changeNotifier.publishInvalidation(saved.getFlowId());
        return saved;
    }

    public ExecutionOutcome simulate(FlowDefinition draft, Map<String, Object> facts) {
        flowCompiler.validate(draft);
        CompiledFlow flow = flowCompiler.compile(draft);   // 即时编译，不缓存、不校验 enabled
        DecisionContext ctx = new DecisionContext(UUID.randomUUID().toString(),
                draft.getFlowId(), "SIMULATE", facts);
        FlowResult result = flowExecutor.execute(ctx, flow);
        return new ExecutionOutcome(result, draft.getVersion());
    }

    /** C12：日志失败不影响主流程。 */
    private void safeLog(FlowResult result, DecisionContext ctx) {
        try {
            executionLogger.log(result, ctx);
        } catch (Exception ignored) {
            // 仅吞掉；M2b 接异步落库后在此加告警计数器
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=OpenRuleServiceTest`
Expected: PASS（execute REJECT/PASS、simulate REVIEW、UUID 缺省、日志记录）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(spring): OpenRuleService 门面(execute/registerFlow/simulate + C12 日志兜底)"
```

---

## Task 8: OpenRuleProperties + OpenRuleAutoConfiguration（装配 + 退让）

**Files:**
- Create: `openrule-spring/src/main/java/io/openrule/spring/autoconfigure/OpenRuleProperties.java`
- Create: `openrule-spring/src/main/java/io/openrule/spring/autoconfigure/OpenRuleAutoConfiguration.java`
- Create: `openrule-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `openrule-spring/src/test/java/io/openrule/spring/autoconfigure/OpenRuleAutoConfigurationTest.java`

**Interfaces:**
- Consumes: 全部 core runtime 类型 + spring 端口/适配/loader/service。
- Produces: 单例 Bean：`Executor openRuleParallelPool`、`ExecutorService openRuleTimeoutPool`、`NodeExecutorRegistry`、`Map<AggregatePolicy,DecisionAggregator>`、`NodeRunner`、`SerialStageExecutor`、`ParallelStageExecutor`、`FlowExecutor`、`FlowCompiler`、`FlowLoader`、`FlowDefinitionJsonCodec`、`OpenRuleService`；`@ConditionalOnMissingBean` 的 `FlowDefinitionRepository`/`ExecutionLogger`/`FlowChangeNotifier`/`OperatorNodeExecutor`/`PriorityAggregator`。

- [ ] **Step 1: 写失败测试（装配 + 退让）**

Create `src/test/java/io/openrule/spring/autoconfigure/OpenRuleAutoConfigurationTest.java`:
```java
package io.openrule.spring.autoconfigure;

import io.openrule.spring.port.FlowDefinitionRepository;
import io.openrule.spring.service.OpenRuleService;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRuleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, OpenRuleAutoConfiguration.class));

    @Test
    void wiresCoreBeansAndStandaloneAdapters() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OpenRuleService.class);
            assertThat(ctx).hasSingleBean(FlowDefinitionRepository.class);
            assertThat(ctx.getBean(FlowDefinitionRepository.class))
                    .isInstanceOf(InMemoryFlowDefinitionRepository.class);
        });
    }

    @Test
    void backsOffWhenUserProvidesRepository() {
        runner.withUserConfiguration(CustomRepoConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(FlowDefinitionRepository.class);
            assertThat(ctx.getBean(FlowDefinitionRepository.class))
                    .isNotInstanceOf(InMemoryFlowDefinitionRepository.class);
        });
    }

    @Configuration
    static class CustomRepoConfig {
        @Bean
        FlowDefinitionRepository customRepo() {
            return new FlowDefinitionRepository() {
                public Optional<io.openrule.core.definition.FlowDefinition> findActiveByFlowId(String f) { return Optional.empty(); }
                public Optional<io.openrule.core.definition.FlowDefinition> findByFlowIdAndVersion(String f, int v) { return Optional.empty(); }
                public io.openrule.core.definition.FlowDefinition save(io.openrule.core.definition.FlowDefinition d) { return d; }
                public void enable(String f, int v) {}
                public List<Integer> listVersions(String f) { return List.of(); }
            };
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=OpenRuleAutoConfigurationTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 OpenRuleProperties**

Create `autoconfigure/OpenRuleProperties.java`:
```java
package io.openrule.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** OpenRule 配置项。 */
@ConfigurationProperties(prefix = "openrule")
public class OpenRuleProperties {

    private final FlowCache flowCache = new FlowCache();

    public FlowCache getFlowCache() { return flowCache; }

    public static class FlowCache {
        /** CompiledFlow 缓存上限。 */
        private long maximumSize = 500;
        /** 访问后多久过期。 */
        private Duration expireAfterAccess = Duration.ofHours(2);

        public long getMaximumSize() { return maximumSize; }
        public void setMaximumSize(long maximumSize) { this.maximumSize = maximumSize; }
        public Duration getExpireAfterAccess() { return expireAfterAccess; }
        public void setExpireAfterAccess(Duration v) { this.expireAfterAccess = v; }
    }
}
```

- [ ] **Step 4: 实现 OpenRuleAutoConfiguration**

Create `autoconfigure/OpenRuleAutoConfiguration.java`:
```java
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
    public FlowDefinitionJsonCodec flowDefinitionJsonCodec(ObjectMapper objectMapper) {
        return new FlowDefinitionJsonCodec(objectMapper);
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
```

- [ ] **Step 5: 写 AutoConfiguration.imports**

Create `openrule-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration
```

- [ ] **Step 6: 跑测试确认通过**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring test -Dtest=OpenRuleAutoConfigurationTest`
Expected: PASS（装配 + 退让）。

- [ ] **Step 7: 跑 spring 模块全量测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-spring -am test`
Expected: BUILD SUCCESS，spring 模块全部测试绿。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat(spring): OpenRuleAutoConfiguration 装配 + standalone 端口(@ConditionalOnMissingBean 可覆盖) + Properties"
```

---

## Task 9: openrule-api（DTO + 控制器 + 条件化异常 advice + api autoconfig）+ MockMvc 端到端

**Files:**
- Create: `openrule-api/pom.xml`
- Modify: `pom.xml`（parent `<modules>` 追加 `openrule-api`）
- Create: `openrule-api/src/main/java/io/openrule/api/dto/ExecuteRequest.java`
- Create: `openrule-api/src/main/java/io/openrule/api/dto/ExecuteResponse.java`
- Create: `openrule-api/src/main/java/io/openrule/api/dto/SimulateRequest.java`
- Create: `openrule-api/src/main/java/io/openrule/api/dto/FlowSummary.java`
- Create: `openrule-api/src/main/java/io/openrule/api/dto/ErrorBody.java`
- Create: `openrule-api/src/main/java/io/openrule/api/ExecuteController.java`
- Create: `openrule-api/src/main/java/io/openrule/api/FlowAdminController.java`
- Create: `openrule-api/src/main/java/io/openrule/api/advice/OpenRuleExceptionAdvice.java`
- Create: `openrule-api/src/main/java/io/openrule/api/autoconfigure/OpenRuleApiAutoConfiguration.java`
- Create: `openrule-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `openrule-api/src/test/java/io/openrule/api/ApiTestApplication.java`（在 `io.openrule.api.test` 包，避免组件扫描重复注册控制器）
- Test: `openrule-api/src/test/java/io/openrule/api/EndToEndApiTest.java`

**Interfaces:**
- Consumes: `OpenRuleService`、`FlowDefinitionJsonCodec`、`ExecuteCommand`、`ExecutionOutcome`；core `FlowDefinition`/`FlowResult`/`NodeResult`/`Decision`、`RuleEngineException`/`FlowValidationException`。
- Produces: 三个 REST 端点（见 Global / 设计 §6）；`OpenRuleApiAutoConfiguration` 注册控制器 + advice。

- [ ] **Step 1: 写 openrule-api/pom.xml**

Create `openrule-api/pom.xml`:
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

    <artifactId>openrule-api</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>io.openrule</groupId>
            <artifactId>openrule-spring</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
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
    </dependencies>
</project>
```

- [ ] **Step 2: parent 追加模块**

Modify `pom.xml`，`<modules>` 改为：
```xml
    <modules>
        <module>openrule-core</module>
        <module>openrule-spring</module>
        <module>openrule-api</module>
    </modules>
```

- [ ] **Step 3: 写 DTO**

Create `dto/ExecuteRequest.java`:
```java
package io.openrule.api.dto;

import java.util.Map;

public record ExecuteRequest(String flowId, String requestId, String bizId,
                             boolean debug, Map<String, Object> facts) {
}
```

Create `dto/ExecuteResponse.java`:
```java
package io.openrule.api.dto;

import io.openrule.core.enums.Decision;
import io.openrule.core.result.NodeResult;
import io.openrule.spring.model.ExecutionOutcome;

import java.util.List;

/** 执行响应（裸 DTO）。nodeResults 仅 debug=true 携带。 */
public record ExecuteResponse(String requestId, String flowId, int flowVersion,
                              Decision decision, String reason, int totalScore,
                              List<String> hitNodes, long costMillis, List<NodeResult> nodeResults) {

    public static ExecuteResponse from(ExecutionOutcome o, boolean debug) {
        var r = o.result();
        return new ExecuteResponse(r.getRequestId(), r.getFlowId(), o.flowVersion(),
                r.getDecision(), r.getReason(), r.getTotalScore(), r.getHitNodes(),
                r.getCostMillis(), debug ? r.getNodeResults() : null);
    }
}
```

Create `dto/SimulateRequest.java`:
```java
package io.openrule.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record SimulateRequest(JsonNode definition, Map<String, Object> facts) {
}
```

Create `dto/FlowSummary.java`:
```java
package io.openrule.api.dto;

import io.openrule.core.definition.FlowDefinition;

public record FlowSummary(String flowId, String flowName, int version, boolean enabled) {
    public static FlowSummary from(FlowDefinition d) {
        return new FlowSummary(d.getFlowId(), d.getFlowName(), d.getVersion(), d.isEnabled());
    }
}
```

Create `dto/ErrorBody.java`:
```java
package io.openrule.api.dto;

public record ErrorBody(String code, String message, long timestamp) {
    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, System.currentTimeMillis());
    }
}
```

- [ ] **Step 4: 写控制器**

Create `ExecuteController.java`:
```java
package io.openrule.api;

import io.openrule.api.dto.ExecuteRequest;
import io.openrule.api.dto.ExecuteResponse;
import io.openrule.spring.model.ExecuteCommand;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExecuteController {

    private final OpenRuleService service;

    public ExecuteController(OpenRuleService service) {
        this.service = service;
    }

    @PostMapping("/execute")
    public ExecuteResponse execute(@RequestBody ExecuteRequest req) {
        ExecutionOutcome outcome = service.execute(new ExecuteCommand(
                req.flowId(), req.requestId(), req.bizId(), req.debug(), req.facts()));
        return ExecuteResponse.from(outcome, req.debug());
    }
}
```

Create `FlowAdminController.java`:
```java
package io.openrule.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.api.dto.ExecuteResponse;
import io.openrule.api.dto.FlowSummary;
import io.openrule.api.dto.SimulateRequest;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.model.ExecutionOutcome;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class FlowAdminController {

    private final OpenRuleService service;
    private final FlowDefinitionJsonCodec codec;

    public FlowAdminController(OpenRuleService service, FlowDefinitionJsonCodec codec) {
        this.service = service;
        this.codec = codec;
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
}
```

- [ ] **Step 5: 写条件化异常 advice**

Create `advice/OpenRuleExceptionAdvice.java`:
```java
package io.openrule.api.advice;

import io.openrule.api.dto.ErrorBody;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * standalone 错误处理（条件化：openrule.api.exception-advice.enabled=false 可关，让 ycr 接管）。
 * 控制器返回裸 DTO；ycr 形态下本 advice 退让。
 */
@RestControllerAdvice
public class OpenRuleExceptionAdvice {

    @ExceptionHandler(FlowValidationException.class)
    public ResponseEntity<ErrorBody> onValidation(FlowValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody.of("FLOW_VALIDATION", e.getMessage()));
    }

    @ExceptionHandler(RuleEngineException.class)
    public ResponseEntity<ErrorBody> onEngine(RuleEngineException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        HttpStatus status = (msg.contains("not found") || msg.contains("disabled"))
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(ErrorBody.of("RULE_ENGINE", msg));
    }
}
```

- [ ] **Step 6: 写 api autoconfig + imports**

Create `autoconfigure/OpenRuleApiAutoConfiguration.java`:
```java
package io.openrule.api.autoconfigure;

import io.openrule.api.ExecuteController;
import io.openrule.api.FlowAdminController;
import io.openrule.api.advice.OpenRuleExceptionAdvice;
import io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** REST 装配：控制器经 @Bean 注册（不靠组件扫描，避免污染用户包）；异常 advice 条件化可退让。 */
@AutoConfiguration(after = OpenRuleAutoConfiguration.class)
public class OpenRuleApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExecuteController executeController(OpenRuleService service) {
        return new ExecuteController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowAdminController flowAdminController(OpenRuleService service, FlowDefinitionJsonCodec codec) {
        return new FlowAdminController(service, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "openrule.api.exception-advice.enabled", havingValue = "true",
            matchIfMissing = true)
    public OpenRuleExceptionAdvice openRuleExceptionAdvice() {
        return new OpenRuleExceptionAdvice();
    }
}
```

Create `openrule-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
io.openrule.api.autoconfigure.OpenRuleApiAutoConfiguration
```

- [ ] **Step 7: 写测试启动类（独立子包，避免扫描重复注册控制器）**

Create `openrule-api/src/test/java/io/openrule/api/test/ApiTestApplication.java`:
```java
package io.openrule.api.test;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 测试用最小启动类。置于 io.openrule.api.test，不扫描 io.openrule.api（控制器只经 autoconfig @Bean 注册）。 */
@SpringBootApplication
public class ApiTestApplication {
}
```

- [ ] **Step 8: 写 MockMvc 端到端测试**

Create `openrule-api/src/test/java/io/openrule/api/EndToEndApiTest.java`:
```java
package io.openrule.api;

import io.openrule.api.test.ApiTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ApiTestApplication.class)
@AutoConfigureMockMvc
class EndToEndApiTest {

    @Autowired
    MockMvc mvc;

    private static final String ORDER_RISK = """
        {
          "flowId": "order_risk", "flowName": "订单风控", "aggregatePolicy": "PRIORITY",
          "stages": [
            { "stageId": "s1", "stageName": "硬规则", "order": 100,
              "executionMode": "SERIAL", "skipWhenStopped": true,
              "nodes": [ { "nodeId": "AMOUNT_LIMIT", "nodeName": "金额上限", "nodeType": "OPERATOR", "order": 20,
                "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": 50000 },
                "decisionOnHit": "REJECT", "stopOnHit": true, "failPolicy": "SKIP", "timeoutMillis": 500 } ] },
            { "stageId": "s2", "stageName": "并行评分", "order": 200,
              "executionMode": "PARALLEL", "skipWhenStopped": true, "stageTimeoutMillis": 2000,
              "nodes": [ { "nodeId": "VIP_CHECK", "nodeName": "VIP", "nodeType": "OPERATOR", "order": 10,
                "operatorDef": { "leftFact": "fact.buyer.level", "operator": "EQ", "rightValue": "NEW" },
                "decisionOnHit": "REVIEW", "stopOnHit": false, "failPolicy": "SKIP", "timeoutMillis": 500 } ] }
          ]
        }
        """;

    private void register() throws Exception {
        mvc.perform(post("/api/v1/admin/flows").contentType(MediaType.APPLICATION_JSON).content(ORDER_RISK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flowId").value("order_risk"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private String executeBody(int amount, String level, boolean debug) {
        return """
            { "flowId": "order_risk", "bizId": "B1", "debug": %s,
              "facts": { "order": { "amount": %d }, "buyer": { "level": "%s" } } }
            """.formatted(debug, amount, level);
    }

    @Test
    void register_thenExecute_bigAmount_rejected() throws Exception {
        register();
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(80000, "NEW", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT"))
                .andExpect(jsonPath("$.flowVersion").value(1))
                .andExpect(jsonPath("$.hitNodes[0]").value("AMOUNT_LIMIT"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void execute_newBuyer_review() throws Exception {
        register();
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(1000, "NEW", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void execute_vip_pass() throws Exception {
        register();
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(1000, "VIP", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"));
    }

    @Test
    void execute_unknownFlow_404() throws Exception {
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"flowId\": \"nope\", \"bizId\": \"B\", \"facts\": {} }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RULE_ENGINE"));
    }

    @Test
    void simulate_draftReturnsDetail() throws Exception {
        String body = """
            { "definition": %s, "facts": { "order": { "amount": 80000 }, "buyer": { "level": "NEW" } } }
            """.formatted(ORDER_RISK);
        mvc.perform(post("/api/v1/admin/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT"))
                .andExpect(jsonPath("$.nodeResults").isArray());
    }
}
```

- [ ] **Step 9: 跑 api 端到端测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl openrule-api -am test`
Expected: BUILD SUCCESS，EndToEndApiTest 全绿（REJECT/REVIEW/PASS + 404 + simulate 明细）。

- [ ] **Step 10: 提交**

```bash
git add -A
git commit -m "feat(api): REST 端点(execute/admin flows/simulate) + 条件化异常 advice + MockMvc 端到端"
```

---

## Task 10: 全 reactor 验收 + session 文档更新

**Files:**
- Modify: `docs/sessions/2026-06-11-openrule-core-m1-session.md`（追加 M2a 完成状态）或新建 `docs/sessions/2026-06-22-openrule-m2a-session.md`

- [ ] **Step 1: 从根跑全量 reactor 测试**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn clean test`
Expected: `BUILD SUCCESS`；三模块全绿（core 56 + spring 各测试 + api 端到端）。记录总测试数。

- [ ] **Step 2: 安装到本地仓库（验证可作为依赖发布）**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -q -DskipTests install`
Expected: `io.openrule:openrule-core`、`openrule-spring`、`openrule-api`、`openrule-parent` 均 install 成功（证明 ycr 侧 starter 将来可依赖）。

- [ ] **Step 3: 写 M2a session 记录**

Create `docs/sessions/2026-06-22-openrule-m2a-session.md`，内容包含：一句话现状（M2a 完成，三模块 reactor，端到端 MockMvc 全绿）、已敲定决策（端口-适配 / 三模块 / standalone / ycr 适配在 ycr 侧出 starter）、下一步（M2b：MySQL/Redis/异步审计/完整 admin/版本化，以及 ycr-starter-rule）、构建备忘（`JAVA_HOME` 指 21）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "docs: M2a 完成，session 记录 + 全 reactor 验收"
```

---

## 验收清单（M2a 完成标志）

- [ ] `mvn clean test` 从根全绿（core 56 + spring 单测 + api MockMvc 端到端）。
- [ ] REST 全链路：`POST /api/v1/admin/flows` 注册 order_risk → `POST /api/v1/execute` 三种 facts 跑出 REJECT/REVIEW/PASS → `POST /api/v1/admin/simulate` 回完整明细 → 未知流程 404。
- [ ] `mvn install` 四件制品（parent/core/spring/api）成功。
- [ ] 纯净性自检：`openrule-core` 零改动、零 Spring；`openrule-spring` 不依赖 spring-web、不依赖任何 ycr starter；公开仓库零 ycr 依赖。
- [ ] 约束自检：C11（缓存 key 含版本 + invalidate）、C12（日志失败不影响主流程）；端口 `@ConditionalOnMissingBean` 退让经测试覆盖。

---

## Self-Review（写计划后自查）

**Spec coverage**：设计 §1 架构→Task1-2/9；§2 范围→全计划；§3 模块→Task1/2/9；§4 三端口→Task2/3/6/8；§5 编排（Codec/Loader/Compiler/Service/autoconfig）→Task4/5/7/8；§6 REST→Task9；§7 数据流→Task7/9；§8 错误处理→Task9（advice）；§9 测试→各 Task + Task9 MockMvc；§10 验收→Task10 + 验收清单；§11 后续→Task10 session。覆盖完整。

**Placeholder scan**：无 TBD/TODO；每步含完整可粘贴代码与确切命令/期望。

**Type consistency**：端口签名（`findActiveByFlowId`/`save`/`enable`/`publishInvalidation`/`log`）在 Task2 定义，Task3/5/6/7/8 一致引用；`ExecuteCommand`/`ExecutionOutcome` 字段（`flowId/requestId/bizId/debug/facts`、`result/flowVersion`）跨 Task2/7/9 一致；`OpenRuleService` ctor 6 参在 Task7 定义、Task8 装配一致；`ExecuteResponse.from(ExecutionOutcome, boolean)` 在 Task9 定义并自用；core API（`FlowExecutor.execute(DecisionContext,CompiledFlow)`、`CompiledFlow.getVersion()`、`NodeExecutorRegistry.getRequired`、`OperatorNodeExecutor`）与 M1 已实现一致。
