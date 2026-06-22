# OpenRule M1 · openrule-core 实现设计

> 配套文档：`open-rule-engine-technical-spec.md`（完整技术规范）、`open-rule-engine-design.html`（架构设计）。
> 本文档只覆盖 **M1 里程碑：纯 Java 执行内核 `openrule-core`** 的落地设计与实现顺序。
> 日期：2026-06-11

---

## 0. 背景与定位

OpenRule 将来要并入用户自研的 `ycr-framework`（Maven，独立仓库）。该框架与配套快速开发脚手架仍在并行开发，暂不可用。

利用 OpenRule 的分层设计（依赖严格单向 `api → spring → executor → core`），**最底层的 `openrule-core` 是零框架依赖的纯 Java**，因此现在即可独立开发，将来 1:1 并入框架而几乎零返工。融合点在最上面的 `openrule-spring` / `openrule-api` 两层（M2+），本阶段不触碰。

**M1 目标**：交付可独立编译、测试、运行的纯 Java 决策内核。验收为 `mvn test` 全绿 + `main()` 端到端跑通。

---

## 1. 工程决策（已确认）

| 项 | 决策 | 理由 |
|---|---|---|
| 工程范围 | 仅 `openrule-core` | M1 内核，纯 Java，与框架解耦 |
| 构建工具 | Maven **单模块** | 将来直接降格为 ycr-framework 的子模块，零摩擦 |
| Java 版本 | **Java 21**（含虚拟线程） | 照规范；融合时 ycr-framework 需同步升 21（已接受） |
| 依赖 | Lombok + JUnit 5 + AssertJ | 与规范、ycr-framework 一致 |
| 实现纪律 | **TDD**（`superpowers:test-driven-development`） | 内核并发正确性敏感，先测后码钉死 C1–C12 |
| 推进顺序 | **自底向上、依赖序、逐单元 TDD** | 接口已被规范固定，自底向上无接口探索成本，每次提交保持绿色 |

---

## 2. M1 范围与 YAGNI 取舍

### 2.1 做（M1 范围内）
领域模型 + 五枚举 + 异常 + NodeExecutor SPI + NodeExecutorRegistry + NodeRunner + SerialStageExecutor + ParallelStageExecutor + FlowExecutor + PriorityAggregator + **OperatorNodeExecutor（唯一执行器）**。

### 2.2 明确不做（按里程碑后延，现在连类都不建）
- JSON 加载 / FlowLoader / FlowRepository / Caffeine 缓存 → M2
- 脚本执行器与运行时（Groovy / JS / Python）→ M3 / M5
- JAVA_NATIVE 执行器（需查 Spring Bean）→ M3
- 评分卡 / 决策表 / 决策树 / 规则集 / 子流程，及其 `ScoreCardDef / DecisionTableDef / DecisionTreeDef` 配置类 → M4
- ScoreThresholdAggregator → M4
- Micrometer / 审计日志 / 热更新 / REST / 自动装配 → M2+

### 2.3 两项已确认的细节决策
1. **枚举建全套，`*Def` 只建 `OperatorDef`**。
   `NodeType / Decision / FailPolicy / AggregatePolicy / ExecutionMode` 五个枚举一次按规范建全（便宜且稳定）。`NodeDefinition` 现仅挂 `operatorDef` 字段 + M1 用得到的字段（`nodeId / nodeName / nodeType / order / decisionOnHit / stopOnHit / failPolicy / timeoutMillis`），字段名严格对齐规范，后续里程碑**只加不改**。M1 中 `NodeType` 枚举含全部取值，但只有 `OPERATOR` 注册了执行器；其余取值在 M1 流程中不出现。
2. **M1 验收用 Builder 组装 Flow，而非 JSON**。
   规范 M1 描述「main 跑通 JSON 定义的流程」，但 JSON 反序列化属于 M2 的 FlowLoader。M1 在 `main()` / 测试中用 Builder 手搭 `order_risk` 式流程（一个串行硬规则 Stage + 一个并行 Stage）跑通；**JSON 加载留到 M2**，确保 M1 真正零外部依赖。

---

## 3. 包与类清单

单 Maven 模块 `openrule-core`，包根 `io.openrule.core`：

```
io.openrule.core
├── enums/        NodeType  ExecutionMode  Decision  FailPolicy  AggregatePolicy
├── exception/    RuleEngineException  FlowValidationException
├── context/      FactMap  DecisionContext
├── result/       NodeResult  StageResult  FlowResult
├── definition/   FlowDefinition  StageDefinition  NodeDefinition
│   └── defs/     OperatorDef                          (仅此一个)
├── spi/          NodeExecutor  CompiledNode  DecisionAggregator  AggregateOutcome
├── runtime/      NodeExecutorRegistry  NodeRunner
│                 SerialStageExecutor  ParallelStageExecutor  FlowExecutor
│                 CompiledFlow  CompiledStage           (M1 在 core 内驱动执行器，不依赖 FlowLoader)
├── aggregate/    PriorityAggregator
└── executor/     OperatorNodeExecutor                  (M1 唯一执行器，参考实现)
```

> `OperatorNodeExecutor` 在规范中归属 `openrule-executor` 模块。M1 单模块下先置于 `io.openrule.core.executor`；将来拆出执行器模块时再迁移——一次纯位移，零逻辑改动。

---

## 4. 并发与正确性硬约束（M1 必须满足）

源自技术规范第 15 章 C1–C12，M1 相关条目：

- **C1**：并行 Stage 中的 `NodeExecutor.execute` 禁止调用 `context.putVariable / stop / setFinalDecision`；写入走 `NodeResult.outputs`，终止意图走 `NodeResult.stop`。
- **C2**：`ParallelStageExecutor` 的 outputs 合并必须单线程、按节点定义 `order` 顺序；同 key 冲突后 order 覆盖前 order；禁止按完成顺序合并。
- **C3**：`finalDecision` 全局唯一写入点 = `DecisionAggregator`（`FlowExecutor` 调用处）。
- **C7**：`facts` 不可变（`Map.copyOf`），任何节点修改 facts 即缺陷。
- **C8**：`NodeRunner` 是节点执行唯一入口；超时、FailPolicy、计时、skipped 判断全在此层，各 Executor 不重复实现、不吞异常。
- **C9**：`stopped / finalDecision / finalReason` 用 `volatile`；`variables` 用 `ConcurrentHashMap`；`nodeResults` 用 `CopyOnWriteArrayList`。
- **C10（部分）**：REGEX pattern 长度上限 512。

---

## 5. 九阶段构建序列

自底向上、逐单元 TDD；每阶段编译通过 + 测试全绿才进入下一阶段。

| 阶段 | 单元 | 先写的关键测试 |
|---|---|---|
| **P0** | Maven 骨架（Java 21 / Lombok / JUnit5 / AssertJ） | sanity 测试确认工程能跑 |
| **P1** | 五枚举 + 两异常 | `Decision.riskierThan`：REJECT>REVIEW>LIMIT>PASS 全组合 |
| **P2** | FactMap、DecisionContext、三个 Result 载体 | `FactMap.getByPath` 嵌套点路径 / 缺失键；改源 Map 不影响 facts（不可变, C7）；Context 控制位并发类型正确（C9） |
| **P3** | FlowDefinition / StageDefinition / NodeDefinition + OperatorDef + CompiledFlow / CompiledStage / CompiledNode | 纯 POJO，Builder sanity |
| **P4** | NodeExecutor SPI、DecisionAggregator、AggregateOutcome、NodeExecutorRegistry | Registry：重复类型抛错 / 缺失类型抛错 / 正常路由 |
| **P5** | **OperatorNodeExecutor** | 运算符全集 × 类型矩阵（GT/GTE/LT/LTE/EQ/NE/BETWEEN/CONTAINS/NOT_CONTAINS/STARTS_WITH/ENDS_WITH/IN/NOT_IN/IS_NULL/NOT_NULL/REGEX）；`fact.` / `var.` 取值路径；validate 拦截残缺配置；REGEX 长度上限 512（C10） |
| **P6** | **NodeRunner** | 四档 FailPolicy 各一例（C8）；超时→cancel→抛 `RuleEngineException`；stopped 时返回 skipped；命中附加 decisionOnHit / stopOnHit；计时 |
| **P7** | SerialStageExecutor → **ParallelStageExecutor** | 串行：stop 立即短路 / outputs 合并 / skipWhenStopped。**并行（皇冠）：整组超时 cancel 掉队者 / 单节点 FailPolicy / 按定义 order 单线程合并确定性（同 key 后 order 覆盖，与完成顺序无关, C1+C2）/ anyStop 统一生效** |
| **P8** | PriorityAggregator → FlowExecutor | Aggregator：Decision 全组合、totalScore 求和、hitNodes、reason 取最高风险节点（C3）。Flow：Stage 调度、跨 Stage skipWhenStopped、按 policy 选聚合器、FlowResult 装配 |
| **P9** | 端到端验收 | 集成测试 + `main()`：Builder 手搭 order_risk 流程跑通，断言 finalDecision 正确 |

---

## 6. 测试重心

火力集中在三处高风险，POJO / 枚举不强求覆盖率：

1. **ParallelStageExecutor 合并确定性**：同 key 不同节点写入恒按 order 合并（C2）；并行节点不碰 context（C1）。
2. **NodeRunner 四档 FailPolicy + 超时 cancel**（C8）。
3. **PriorityAggregator 是 finalDecision 唯一写入点**（C3）+ 优先级正确。

并行池 / 超时池在 M1 使用 `Executors.newVirtualThreadPerTaskExecutor()`（Java 21）。

---

## 7. 验收标准

- `mvn test` 全绿。
- `main()` 用 Builder 组装的 `order_risk` 式流程（串行硬规则 Stage + 并行 Stage）端到端跑出正确 `FlowResult`（含 decision / totalScore / hitNodes / nodeResults）。
- 全程不引入 Spring、不读 JSON、不连任何中间件——core 保持纯净，便于将来并入 ycr-framework。

---

## 8. 后续里程碑衔接（非本阶段）

- **M2**：`openrule-spring` —— FlowLoader(Caffeine) + FlowRepository(MySQL) + REST + 热更新 + 异步审计。**融合 ycr-framework 的主战场**（`R<T>`、异常体系、数据层 starter）。
- **M3**：脚本引擎（Groovy / JS）+ JAVA_NATIVE + simulate。
- **M4**：评分卡 / 决策表 / 决策树 / 规则集 / 子流程 + ScoreThresholdAggregator。
- **M5**：Python 进程池 + Micrometer + 性能基线。

本阶段所有接口为上述里程碑预留扩展点，不堵死。
