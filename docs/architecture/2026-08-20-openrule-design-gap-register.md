# OpenRule 设计缺口总账

> 日期：2026-08-20
> 状态：Active
> 适用范围：OSS `open-rule` 与规划中的 `openrule-ee`
> 基线：M1.5 已完成但尚未提交；当前四模块为 Core、Spring、API、JDBC

## 1. 目的

本文件是后续设计工作的唯一入口，用来回答三个问题：

1. 哪些能力只有方向，没有可执行规格；
2. 哪些设计必须先完成，否则后续模块会反向破坏 Core；
3. 每项设计在什么条件下才可以进入编码。

“已细化”不等于有一段路线图描述。只有同时具备明确决策、接口或数据模型、并发或事务语义、失败语义、迁移方案、测试矩阵和验收命令，才能标记为 `READY`。

## 2. 状态定义

| 状态 | 含义 | 是否允许编码 |
|---|---|---|
| `DISCOVERED` | 已确认缺口，仅有问题描述 | 否 |
| `ADR` | 核心取舍已决定，实施细节尚未完成 | 否 |
| `READY` | 技术设计和实施计划完整 | 是 |
| `IMPLEMENTING` | 正在按已批准方案编码 | 是 |
| `VERIFIED` | 实现和全部验收已完成 | 已完成 |

## 3. 总体依赖顺序

```text
R0 工程与发布基线
  ↓
M1.6 Core Contract Freeze
  ├── 开放类型 SPI
  ├── 值模型与 Definition Schema v2
  ├── 只读执行输入与不可变结果
  └── Deadline、取消和资源隔离
  ↓
M2c 版本/持久化生产闭环
  ↓
M2d OSS 决策包、证据与回放基础
  ├── M4 高级节点
  ├── Redis 热更新与可观测
  └── JavaNative/SubFlow/脚本适配器
  ↓
OSS 1.0
  ↓
EE E0 控制面/数据面边界
  ├── E1 路由与发布
  ├── E2 租户、RBAC、审批、配额
  ├── E3 回测与事件管道
  └── E4+ 模型、DAG、漂移监控
```

M4 不能早于 M1.6。EE 发布与回测不能早于 M2d。脚本执行器不能早于执行目的、副作用等级和证据接口定版。

## 4. OSS 缺口

| ID | 优先级 | 主题 | 当前缺口 | 必须形成的设计产物 | 目标批次 | 状态 |
|---|---:|---|---|---|---|---|
| OSS-01 | P0 | 工程基线 | 默认 JDK 17 才在编译阶段失败；无 Wrapper、Enforcer、CI，IT 可因 Docker 缺失静默跳过 | 固定 JDK/Maven/插件版本、单测与 IT 生命周期、CI 门禁、模块边界门禁 | R0 | `READY` |
| OSS-02 | P0 | 框架支持线 | Spring Boot 3.3.5 已脱离当前 OSS 支持线 | 4.1.0 升级范围、兼容验证和回退点 | R0 | `READY` |
| OSS-03 | P0 | 节点扩展契约 | `NodeType` 枚举使第三方和 EE 节点无法扩展；EE 的 `MODEL` 会迫使修改 Core | 开放 ID、类型化配置、执行器注册和 JSON 绑定规则 | M1.6 | `READY` |
| OSS-04 | P0 | 聚合器扩展契约 | `AggregatePolicy` 枚举同样封闭，配置藏入 metadata 的方向不可维护 | 开放聚合器 ID、类型化聚合配置与编译计划 | M1.6 | `READY` |
| OSS-05 | P0 | 编译产物 | `CompiledNode` 保存原始 Definition 快照和 `Object` artifact，且手工复制只认识 Operator | executor-owned `NodePlan`、运行元数据、一次绑定和不变量 | M1.6 | `READY` |
| OSS-06 | P0 | 执行状态边界 | SPI 获得公开可写的 `DecisionContext`，C1/C3 只能靠注释约束；flowId 可与 CompiledFlow 不一致 | `ExecutionRequest`、只读 `NodeExecutionInput`、包内 `ExecutionState` | M1.6 | `READY` |
| OSS-07 | P0 | 结果不可变性 | `NodeResult.outputs/details`、`FlowResult` 和 `StageResult` 暴露可变或浅不可变集合 | 深度不可变结果、明确节点状态和类型化失败 | M1.6 | `READY` |
| OSS-08 | P0 | 值语义 | 缺失值与 null 混同；字符串/数字隐式转换；任意 Java 对象进入 facts，无法稳定序列化和回放 | JSON-like `DecisionValue`、JSON Pointer、严格比较规则 | M1.6 | `READY` |
| OSS-09 | P0 | Definition 演进 | 无 `schemaVersion`，新增节点字段会持续扩张单一 `NodeDefinition` | Schema v2、v1→v2 迁移、未知字段和未知类型处理 | M1.6 | `READY` |
| OSS-10 | P0 | 超时与取消 | Parallel 外层提交后 NodeRunner 再向 timeout pool 提交；有界池会饥饿，`cancel(true)` 也不保证任务停止 | 单业务任务提交、绝对 Deadline、计时器、取消和迟到结果语义 | M1.6 | `READY` |
| OSS-11 | P0 | 失败分类 | timeout、stage timeout、interrupt、ABORT 和业务降级之间边界不清；中断可能落入 FailPolicy | 稳定错误码、引擎错误与节点错误分层、FailPolicy 适用矩阵 | M1.6 | `READY` |
| OSS-12 | P0 | 不可变决策包 | 发布包目前只存在 EE 规格，但复现、缓存和供应链完整性也是 OSS 基础能力 | canonical JSON、manifest、checksum、兼容矩阵、签名 SPI | M2d | `ADR` |
| OSS-13 | P0 | 证据与回放 | 脱敏审计无法重放；外部读取和副作用节点没有统一治理 | LIVE/SIMULATE/BACKTEST、审计/证据分离、外部数据证据协议 | M2d | `ADR` |
| OSS-14 | P1 | M2c 生命周期 | 当前 save 即启用、`MAX(version)+1`、读取不验 checksum | head 表、不可变草稿、原子启用/回滚、并发编辑、Flyway | M2c | `DISCOVERED` |
| OSS-15 | P1 | 请求幂等 | requestId 同时像关联 ID 和幂等键，缺少冲突与重放语义 | 幂等键契约、结果缓存窗口、冲突响应和数据库约束 | M2c | `DISCOVERED` |
| OSS-16 | P1 | 审计可靠性 | 异步审计队列、拒绝策略、停机排空和 durable/best-effort 未定义 | 有界队列、计数、停机协议、Outbox 或明确弱保证 | M2c | `DISCOVERED` |
| OSS-17 | P1 | Redis 一致性 | Pub/Sub 会丢消息，版本复核和重连补偿仅有方向 | Outbox、事件幂等、TTL 复核、重连清缓存、陈旧策略 | Redis | `DISCOVERED` |
| OSS-18 | P1 | 可观测性与 SLO | 缺少 Observer SPI、指标基数规则、容量模型和基准负载 | 指标/Trace 契约、SLO、JMH 场景、容量及背压 | Observe | `DISCOVERED` |
| OSS-19 | P1 | API 与安全 | 无完整输入大小/深度限制、错误码、管理面鉴权和 debug 数据策略 | API 协议、校验矩阵、鉴权边界、限流与脱敏 | M2c/Release | `DISCOVERED` |
| OSS-20 | P1 | 条件与高级节点 | M4 各节点有轮廓但共享条件 AST、命中策略、区间和静态检查未定版 | Condition AST、ScoreCard/Table/Tree/RuleSet 独立规格 | M4 | `DISCOVERED` |
| OSS-21 | P1 | SubFlow/JavaNative | Resolver、调用栈、变量作用域、固定/活动版本与副作用未定版 | 调用图、作用域、递归限制、错误和证据语义 | Cross-layer | `DISCOVERED` |
| OSS-22 | P2 | 脚本运行时 | 沙箱、资源限额、缓存、隔离和可信边界仍是实现要点 | 进程/Context 隔离、安全负例和生命周期规格 | M3/M5 | `DISCOVERED` |
| OSS-23 | P1 | 发布工程 | BOM、SBOM、签名、兼容性检查、升级政策和 release automation 未定 | Release ADR、CycloneDX、签名与兼容门禁 | OSS 1.0 | `DISCOVERED` |

## 5. EE 缺口

| ID | 优先级 | 主题 | 当前缺口 | 必须形成的设计产物 | 前置 | 状态 |
|---|---:|---|---|---|---|---|
| EE-01 | P0 | OSS/EE 边界 | “扩展而不修改 Core”与 EE 新增 `NodeType.MODEL` 冲突 | 依赖规则、开放 SPI、许可证和兼容政策 | OSS-03/04 | `ADR` |
| EE-02 | P0 | 部署拓扑 | 模块清单存在，但控制面、数据面、遥测面的进程和故障域未定义 | 模块化单体起步拓扑、拆分条件、网络和数据所有权 | M2d | `DISCOVERED` |
| EE-03 | P0 | 路由修订 | 路由直接读可变 Strategy；hash 未纳入 tenant/scene/revision；调权后“仅影响新 key”无法成立 | 不可变 RouteRevision、10k bucket、原子指针、分配策略 | M2d | `DISCOVERED` |
| EE-04 | P0 | 发布状态机 | 生命周期记录表不是当前状态权威；审批、构包、落库、事件缺少事务和幂等边界 | artifact head/history、状态转换表、Outbox、发布幂等 | M2d | `DISCOVERED` |
| EE-05 | P0 | 包兼容与加载 | EE 包模型缺少引擎、编译器、插件和 schema 兼容信息 | 复用 OSS PackageManifest，PackageLoader 兼容检查 | OSS-12 | `ADR` |
| EE-06 | P0 | 回测数据 | 脱敏 facts 不足以复现，外部读取快照和 UNCERTAIN 统计口径未定义 | ReplayRecord、采样、证据命中率、差异统计和门禁 | OSS-13 | `ADR` |
| EE-07 | P1 | 多租户 | 仅依靠 MyBatis 拦截器不能保证所有 SQL 隔离，MySQL 也无原生 RLS | tenant-aware 端口、组合主键/唯一键、负面隔离测试 | EE-02 | `DISCOVERED` |
| EE-08 | P1 | RBAC/审批 | 角色矩阵有方向，授权点、职责分离数据和并发审批未定义 | 权限资源模型、授权矩阵、不可变审批记录、并发状态机 | EE-04/07 | `DISCOVERED` |
| EE-09 | P1 | 配额 | Redis 滑窗只是实现选择，超卖、降级和计费账本未定义 | 配额维度、原子算法、失败模式、账本与对账 | EE-07 | `DISCOVERED` |
| EE-10 | P1 | 模型平台 | `MODEL` 契约、特征 schema、artifact 完整性、ONNX/HTTP 一致性未定版 | ModelManifest、特征类型、推理错误、fallback 与证据 | OSS-03/13 | `DISCOVERED` |
| EE-11 | P1 | DAG | 拓扑轮廓存在，JOIN 的缺失分支、取消、变量冲突和 Deadline 未定义 | 编译算法、token/join 语义、作用域、资源上限 | OSS-10/20 | `DISCOVERED` |
| EE-12 | P2 | 漂移与动作治理 | 指标和阈值有示例，基线、窗口、迟到数据、告警去重和回滚权限未定义 | 统计口径、窗口、状态机、动作审批和审计 | EE-06 | `DISCOVERED` |

## 6. 已接受的跨版本决策

1. OSS 1.0 之前允许破坏 Java API，以正确架构优先；不维护旧 Java API 的过渡层。
2. 已持久化的 Definition JSON 不能直接失效：Schema v1 必须可读，写入统一升级为 v2。
3. `enabled`、审批状态和路由权重属于控制面状态，不进入可执行 Definition。
4. 决策包、canonical JSON、checksum 和回放证据属于 OSS 基础；EE 在其上增加租户、审批、路由和治理。
5. Core 保持纯 Java 21，不依赖 Spring、Jackson、日志门面、数据库、缓存、指标 SDK 或脚本运行时。
6. 第三方扩展只能通过开放 ID 和注册表进入，不允许要求向 Core 枚举追加常量。

## 7. 设计完成门禁

任何 `DISCOVERED` 项进入 `READY` 前，设计文档必须包含：

- 范围、非目标和依赖前置；
- 至少一个明确选择及被拒绝方案；
- Java API、JSON、SQL 或事件协议中的适用部分；
- 状态机、事务边界或并发时序中的适用部分；
- 稳定错误码与重试/降级规则；
- 向前/向后兼容和数据迁移；
- 单元、集成、并发、故障注入和性能测试矩阵；
- 可逐提交执行的文件清单与命令；
- 无未决设计或实现占位标记。

## 8. 当前可直接执行的文档

- `docs/adr/0001-core-extension-contract.md`
- `docs/adr/0002-execution-state-and-value-semantics.md`
- `docs/adr/0003-deadline-cancellation-and-resource-isolation.md`
- `docs/adr/0004-decision-package-and-evidence-boundary.md`
- `docs/design/2026-08-20-r0-m1.6-core-contract-freeze.md`
- `docs/plans/2026-08-20-r0-m1.6-implementation-plan.md`

其余条目必须先达到本文件第 7 节的门禁，不得只凭现有总技术规范直接编码。
