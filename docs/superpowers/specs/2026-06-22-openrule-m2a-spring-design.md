# OpenRule M2a · openrule-spring + openrule-api（Spring 编排骨架）实现设计

> 配套文档：`open-rule-engine-technical-spec.md`（完整技术规范）、`open-rule-engine-design.html`（架构设计）、`docs/superpowers/specs/2026-06-11-openrule-core-m1-design.md`（M1 设计）。
> 本文档覆盖 **M2a：把 M1 的纯 Java 内核封装成一个可端到端运行的 Spring 决策服务**，采用端口-适配（六边形）风格，零中间件、可端到端单测。
> 日期：2026-06-22

---

## 0. 背景与定位

M1 交付了 `openrule-core`——零 Spring、零运行时依赖的纯 Java 决策内核（56 测试全绿，`M1Demo` 端到端跑通）。M2 起进入 Spring/持久化/REST 层。

两点关键约束（本次确认）决定了 M2 的形态：

1. **OpenRule 要开源**。整个 `open-rule` reactor 就是公开 GitHub 项目；`openrule-core` 必须保持可被单独依赖/发布的纯净 jar（别人可以只用 `io.openrule:openrule-core`，不被迫拖入 Spring）。
2. **OpenRule 要能原生融进 `ycr-framework` 应用**。`ycr-framework` 已是生产级底层框架（35 个 starter），并有 `ycr-scaffold-mvc` / `ycr-scaffold-ddd` 两个脚手架示例。ycr 应用期望 `R<T>` 统一响应、`BaseException`/`ErrorCode` 异常体系、`BaseDO`/`PageQuery`/`PageResult`/`BaseMapperX`（MyBatis-Plus）数据层、ycr 缓存。

这两个目标看似冲突（开源要纯净 vs 融合要复用 ycr 设施），**端口-适配把它们解开**：`openrule-spring` 只依赖它自己拥有的小接口（端口）；M2a 提供进程内 standalone 默认实现（开源开箱即用）；ycr 耦合抽到将来 ycr 侧的 `ycr-starter-rule` 里（公开仓库对此一无所知）。这延续了 M1“现在写、将来融合零返工”的性质。

**M2a 目标**：交付一个用纯 Spring Boot 即可启动、零外部中间件、可端到端单测的决策服务。验收 = `mvn test` 全绿（含 MockMvc 全链路）+ REST `注册流程 → 执行 → 模拟` 跑通。

---

## 1. 工程决策（已确认）

| 项 | 决策 | 理由 |
|---|---|---|
| 融合策略 | **端口-适配（六边形）** | 开源纯净 + ycr 融合零返工，二者兼得 |
| 本轮范围 | **仅 M2a**（编排骨架 + standalone + REST） | M2 较大，按端口缝拆增量；M2b（MySQL/Redis/异步审计/完整 admin/版本化）独立成 brainstorm→plan 循环 |
| 模块粒度 | **三模块 reactor**：`openrule-core` + `openrule-spring` + `openrule-api` | 贴规范分层；保住 core 零 Spring |
| core 地位 | 原样降为子模块，**零改动**，仍可被单独依赖/发布 | 开源要求 |
| ycr 适配位置 | 将来在 **ycr 侧出 `ycr-starter-rule`**，依赖公开的 `io.openrule:openrule-spring` | 公开仓库零 ycr 依赖 |
| Java / Spring Boot | Java 21 / **Spring Boot 3.3.x** | 对齐 ycr-framework 那条线，融合少摩擦 |
| JSON | Jackson | 与规范、ycr 一致；取代 M1 的 Builder 手搭 |
| 实现纪律 | TDD | 与 M1 一致 |

---

## 2. 范围与 YAGNI 取舍

### 2.1 做（M2a 范围内）
- 三模块 reactor 骨架（parent 聚合 pom + core 降级 + spring + api）。
- **3 个端口**：`FlowDefinitionRepository` / `FlowChangeNotifier` / `ExecutionLogger`，各配 `@ConditionalOnMissingBean` 的进程内默认实现。
- `FlowDefinitionJsonCodec`（Jackson）、`FlowLoader`（Caffeine 缓存 + 编译）、`OpenRuleService`（门面）、`OpenRuleAutoConfiguration` + `OpenRuleProperties`。
- REST：`POST /api/v1/execute`、`POST /api/v1/admin/flows`（注册=解析+校验+save+enable）、`POST /api/v1/admin/simulate`。
- 条件化 `@RestControllerAdvice` 错误处理。
- 端到端 MockMvc 测试。

### 2.2 明确不做（后延）
- **M2b**：MySQL `FlowRepository`（ycr data-mp）、Redis Pub/Sub 热更新、异步 `ExecutionLogger` 落库 + `or_execute_log`、完整 admin（`enable`/`rollback`/`versions`/`logs` 多版本）、`checksum` 版本化、facts 脱敏。
- **ycr 适配**：`ycr-starter-rule`（端口→ycr 设施、`R<T>`/`ErrorCode` 对齐）。两个脚手架是它将来的验收参照，但本轮不建。
- 脚本/高级节点执行器（M3/M4）：M2a 加载的流程**只含 OPERATOR 节点**（目前唯一注册的执行器）。
- Micrometer 指标（M5）。

---

## 3. 架构与模块

`open-rule` reactor = 公开 GitHub 项目本身，零 ycr 依赖：

```
open-rule/                    parent 聚合 pom (packaging=pom, Java 21, 引入 Spring Boot 3.3.x BOM 作 dependencyManagement)
├── openrule-core/            M1 纯 Java 内核（原样降为子模块；零 Spring、零运行时依赖；可单独依赖/发布）
├── openrule-spring/          编排 + 端口 + standalone 适配 + autoconfig
│   └── io.openrule.spring
│       ├── port/             FlowDefinitionRepository  FlowChangeNotifier  ExecutionLogger
│       ├── loader/           FlowLoader  FlowDefinitionJsonCodec
│       ├── service/          OpenRuleService（门面）
│       ├── standalone/       InMemoryFlowDefinitionRepository  LocalFlowChangeNotifier  InMemoryExecutionLogger
│       ├── model/            ExecuteCommand / SimulateCommand（服务层入参，与 api DTO 解耦）
│       └── autoconfigure/    OpenRuleAutoConfiguration  OpenRuleProperties
│   依赖：openrule-core + spring-boot-autoconfigure + caffeine + jackson-databind（不依赖 spring-web、不依赖任何 ycr starter）
└── openrule-api/             REST：控制器返回“裸 DTO” + 条件化异常处理
    └── io.openrule.api
        ├── ExecuteController  FlowAdminController
        ├── dto/               ExecuteRequest/Response  FlowRegisterRequest  SimulateRequest
        └── advice/            OpenRuleExceptionAdvice（@ConditionalOnMissingBean 才生效）
    依赖：openrule-spring + spring-boot-starter-web
```

**依赖方向（严格单向）**：`openrule-api → openrule-spring → openrule-core`。

**两种消费形态，一套代码**：
- **独立开源**：纯 Spring Boot 引 `openrule-api`（或只引 `openrule-spring` 自写控制器）。standalone 端口实现开箱即用，零中间件。
- **ycr 原生（将来）**：`ycr-starter-rule` 依赖 `io.openrule:openrule-spring`，把端口绑到 ycr 的 data-mp/cache；控制器裸 DTO 被 ycr 的 `UnifiedResponseBodyAdvice` 包成 `R<T>`。

**裸 DTO 不打架的机制**：控制器一律返回裸 DTO（不自包 `R`）。standalone 下 `OpenRuleExceptionAdvice` 仅 `@ConditionalOnMissingBean` 时生效做最小错误体；ycr 下它自动退让，裸 DTO 与异常交给 ycr 的统一包装/`GlobalExceptionHandler`。既不双重包装、也不另造平行的 `R`。

---

## 4. 端口与 standalone 适配（M2b 的接缝）

3 个端口都放 `openrule-spring`，是 M2b 要换成 MySQL/Redis/异步落库的那三处：

| 端口 | 接口要点 | M2a standalone 实现 | M2b / ycr 适配将来换成 |
|---|---|---|---|
| `FlowDefinitionRepository` | `findActiveByFlowId`、`save`、`enable(flowId,version)`、（轻量）`findByFlowIdAndVersion` | `InMemoryFlowDefinitionRepository`（`ConcurrentHashMap`） | MySQL `or_flow`（ycr `BaseMapperX`） |
| `FlowChangeNotifier` | `publishInvalidation(flowId)` | `LocalFlowChangeNotifier`（直接调 `FlowLoader.invalidate`） | Redis Pub/Sub |
| `ExecutionLogger` | `log(FlowResult, DecisionContext)` | `InMemoryExecutionLogger`（有界缓冲，供 demo/测试读） | 异步写 `or_execute_log` / ES |

`FlowLoader` 与 `OpenRuleService` 只依赖这些端口，因此 M2a→M2b、standalone→ycr 全是“换实现”，编排代码零改动。端口失败语义遵循规范 C12（执行日志写入失败不影响主流程）。

**M2a 版本与启用语义（消除歧义，内存仓储按此实现）**：同一 `flowId` 每次 `save` 版本号自增（首次=1）；`save` 后该新版本即被置为唯一 `enabled` 版本（旧版本自动转为非启用）；`findActiveByFlowId` 恒返回当前 `enabled` 版本；保留历史版本对象以备 M2b 的 `versions`/`rollback`。`enable(flowId,version)` 端口本轮实现但 admin 不暴露独立端点（留 M2b）。

---

## 5. 编排组件（openrule-spring）

- **`FlowDefinitionJsonCodec`**（Jackson）：流程定义 JSON ⇄ `FlowDefinition`。M1 的 `NodeDefinition` 字段已能反序列化 OPERATOR 节点（`operatorDef`/`decisionOnHit`/`stopOnHit`/`failPolicy`/`timeoutMillis`）。**M2a 仅支持 OPERATOR 节点**。
- **`FlowLoader`**（对齐规范 §6）：`loadActive(flowId)` → `repo.findActiveByFlowId`（缺失/停用抛 `RuleEngineException`）→ 按 `order` 排序 Stage/Node → 逐节点 `registry.getRequired(type).compile(node)` → 存 Caffeine（key=`flowId:v{version}`，C11；`maximumSize`/`expireAfterAccess` 可配）。`invalidate(flowId)` 清该 flow 全部版本。
- **保存即校验**（规范“PUT 触发全节点 validate”）：注册/保存时对每节点 `registry.getRequired(type).validate(node)`，残缺配置抛 `FlowValidationException`（SPI validate 阶段，M1 `OperatorNodeExecutor.validate` 已实现）。
- **`OpenRuleService`**（门面，唯一对外编排入口）：
  - `execute(ExecuteCommand)`：组装 `DecisionContext`（requestId 缺省 UUID）→ `FlowLoader.loadActive` → `FlowExecutor.execute` → `ExecutionLogger.log`（端口，失败不冒泡）→ 返回 `FlowResult`。
  - `simulate(draft, facts)`：**不要求 enabled、不走缓存、不写日志**，即时编译草稿并执行，返回完整明细。
- **`OpenRuleAutoConfiguration` + `OpenRuleProperties`**：装配虚拟线程池（parallel/timeout，`Executors.newVirtualThreadPerTaskExecutor()`）、`NodeExecutorRegistry`（收集所有 `NodeExecutor` Bean，并把 `OperatorNodeExecutor` 注册为 Bean）、`aggregators` Map（收集 `DecisionAggregator` Bean，含 `PriorityAggregator`）、`NodeRunner`、`SerialStageExecutor`、`ParallelStageExecutor`、`FlowExecutor`、`FlowLoader`、`OpenRuleService`；三个 standalone 端口实现均 `@ConditionalOnMissingBean`。
  - 配置项：`openrule.parallel.stage-timeout-millis`、`openrule.node.default-timeout-millis`、`openrule.flow-cache.maximum-size`、`openrule.flow-cache.expire-after-access`。
  - 注册方式遵循 ycr 约定：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

---

## 6. REST 面（openrule-api）

M2a 端点（规范 §8 的最小可端到端子集；`enable`/`rollback`/`versions`/`logs` 完整 admin 留 M2b）：

```
POST /api/v1/execute                 执行已启用流程（debug=true 才回 nodeResults 明细）
POST /api/v1/admin/flows             注册流程（解析 JSON → 全节点 validate → save，M2a 直接 enabled）
POST /api/v1/admin/simulate          草稿模拟（不要求 enabled，回完整明细）
```

**DTO**：
- `ExecuteRequest{ flowId, requestId?, bizId, debug=false, facts:Map<String,Object> }`
- `ExecuteResponse{ requestId, flowId, flowVersion, decision, reason, totalScore, hitNodes:List, costMillis, nodeResults?（仅 debug=true） }`
- `FlowRegisterRequest{ definition:JsonNode/String }`
- `SimulateRequest{ definition, facts:Map }`

控制器返回**裸 DTO**；`OpenRuleExceptionAdvice` 仅 standalone（无 ycr 包装）时生效。

---

## 7. 数据流

```
execute:   HTTP → ExecuteController → OpenRuleService.execute
                  → FlowLoader.loadActive(Caffeine 命中/未命中→编译)
                  → FlowExecutor.execute(core：Stage 调度 + 聚合，C1–C3/C8/C9)
                  → ExecutionLogger.log(端口，失败不冒泡 C12)  → ExecuteResponse
register:  HTTP → FlowAdminController → JsonCodec 解析 → 逐节点 validate(SPI)
                  → repo.save → notifier.publishInvalidation → FlowLoader.invalidate
simulate:  HTTP → FlowAdminController → OpenRuleService.simulate
                  → 即时编译草稿(不缓存/不校验 enabled) → FlowExecutor.execute → 完整明细
```

---

## 8. 错误处理

- `FlowValidationException`（保存期，残缺/非法配置）→ HTTP 400。
- `RuleEngineException`（运行期）→ 流程未找到/已停用 404；其余（无执行器、超时等）422/500。
- **节点级失败不冒泡成 HTTP 错误**：已由 core `NodeRunner` 按 FailPolicy 在引擎内治理（SKIP/REVIEW/REJECT/ABORT，C8）；HTTP 层只反映整个 execute 调用的成败。
- 错误体由条件化 `OpenRuleExceptionAdvice` 产出；ycr 形态交给 ycr `GlobalExceptionHandler`（OpenRule 异常在 ycr app 的友好映射是将来 `ycr-starter-rule` 的事，M2a 不管）。

---

## 9. 测试（直接补 M1 暴露的“零集成测试”缺口）

- **openrule-spring**：`FlowLoader` 缓存命中 / 按版本失效；`OpenRuleService` execute/simulate；`FlowDefinitionJsonCodec` 往返；内存适配器；autoconfig 用 `ApplicationContextRunner` 验证 Bean 装配 + `@ConditionalOnMissingBean` 退让。
- **openrule-api**：`@SpringBootTest` + `MockMvc` **端到端**：注册 `order_risk` JSON → `/execute` 跑出 REJECT/REVIEW/PASS（复刻 M1 三案例，但走 HTTP+JSON 全链路）→ `/simulate` 跑草稿。**首次证明 starters 能组装成真正能跑的应用**（bean/装配顺序）。
- 全程**零中间件**：内存仓储，连 H2 都不需要，纯 Spring 上下文 + MockMvc。

---

## 10. 验收标准

- `mvn test` 全绿（core 56 + spring 单测 + api MockMvc 端到端）。
- REST 全链路：`POST /admin/flows` 注册 `order_risk` → `POST /execute` 三种 facts 跑出 REJECT / REVIEW / PASS → `POST /admin/simulate` 草稿模拟回完整明细。
- `openrule-core` 仍零 Spring、可单独构建；`openrule-spring` 不依赖 spring-web、不依赖任何 ycr starter；公开仓库零 ycr 依赖。
- 并发约束沿用 M1（C1–C3/C7–C11）；新增 C12（日志失败不影响主流程）由 `ExecutionLogger` 调用处保证。

---

## 11. 后续衔接（非本阶段）

> **重要前提（2026-06-22 厘清）**：open-rule 与 ycr-framework **都将开源**，因此不存在“私有最后融合”——`ycr-starter-rule` 只是一个开源项目（ycr-framework）公开依赖另一个开源项目（`io.openrule:openrule-spring`）的正常组合。由此坐实：**open-rule 公开仓库保持零 ycr 依赖**；ycr 侧的 starter 才引 ycr 设施。M2b 与 ycr-starter-rule 是**同一组端口的两套适配实现**，互不替代。

- **M2b（public，open-rule 仓库内）**：standalone 落地适配——MySQL `FlowDefinitionRepository`（**纯 JDBC/MyBatis，零 ycr 依赖**）、Redis Pub/Sub `FlowChangeNotifier`、异步落库 `ExecutionLogger`（`or_execute_log` + facts 脱敏 + checksum 版本化）、完整 admin（enable/rollback/versions/logs）。全部是“换端口实现 + 加 admin 端点”，不动 M2a 编排。
- **ycr-starter-rule（ycr-framework 仓库内，public）**：依赖公开 `io.openrule:openrule-spring`，把端口绑到 ycr 设施——仓储用 ycr data-mp（`BaseMapperX`/`BaseDO`）、缓存用 ycr cache，响应/异常对齐 `R<T>`/`ErrorCode`；以 `ycr-scaffold-mvc` / `ycr-scaffold-ddd` 为验收参照。
- **M3/M4/M5**：脚本/高级节点执行器、Python、Micrometer。M2a 的 `NodeExecutorRegistry` 自动收集机制已为新执行器预留——新增执行器 Bean 即自动接入，编排零改动。

### 里程碑路线图（路线 B · 开源 MVP 优先，2026-06-22 定）

`M2a → M4 → M3 → M2b → M5 → ycr-starter-rule`

- 先把**能力**做厚再落地服务：M4（评分卡/决策表/决策树/子流程，纯 Java、零沙箱、风控刚需）**先于** M3（脚本，带 Groovy/GraalVM 沙箱、安全敏感）——安全高价值的先做，沙箱风险后置。
- M2b 让公开项目可独立部署（标准中间件，零 ycr）。M5（Python + Micrometer）收尾。
- `ycr-starter-rule` 作为开源组合最后做，随时可插（只依赖 M2a 起就稳定的 `openrule-spring` 端口）。
- 依赖事实：M3/M4/M5 只依赖 M2a（registry/aggregators 自动收集 + FlowLoader），用内存 standalone 仓储即可跑，不阻塞于 M2b。
