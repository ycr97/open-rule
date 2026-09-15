# OpenRule 后续开发路线图

> 日期：2026-07-28
> 状态：M1.5 已完成；R0、M2c 待实施
> 基线：`openrule-core` M1.5 已完成，Spring/JDBC/API M2b 基本完成
> 目标：同时完善纯 Java Core 与 Spring 生产化能力，并保持依赖边界稳定

## 1. 总体决策

下一步不直接进入脚本引擎。M1.5 Core 加固已完成；继续完成 **R0 工程基线和 M2c Spring 生产闭环**，再实现 M4 高级节点。跨层节点与脚本运行时后置，避免在发布模型未稳定前扩大实现面。

核心原则：

- `openrule-core` 保持纯 Java，不依赖 Spring、Jackson、JDBC、Redis 或 Micrometer。
- 配置型纯 Java 节点属于 Core；Groovy、GraalJS、Python 独立为可选模块。
- `validate → compile → execute` 三阶段职责严格分离，运行热路径不解析配置、不查 Spring 容器。
- 流程版本不可变，保存草稿与启用版本分离。
- 每个阶段必须有独立的自动化验收和文档同步。

## 2. 目标模块边界

| 模块 | 职责 |
|---|---|
| `openrule-core` | 领域模型、编译器、执行内核、SPI、纯 Java 节点、聚合器、Standalone Facade |
| `openrule-spring` | Service、FlowLoader、缓存、Spring Resolver、自动装配 |
| `openrule-api` | REST 协议、DTO、异常映射 |
| `openrule-jdbc` | 流程仓储、审计日志、数据库迁移 |
| `openrule-redis` | 多实例缓存失效与热更新 |
| `openrule-executor-groovy` | 可选 Groovy 执行器和沙箱 |
| `openrule-executor-js` | 可选 GraalJS 执行器和沙箱 |
| `openrule-executor-python` | 可选 CPython 进程池适配 |

OPERATOR、ScoreCard、DecisionTable、DecisionTree、RuleSet 放在 Core。JavaNative 和 SubFlow 通过 Core SPI 连接 Standalone/Spring 两种 Resolver。

## 3. 路线与依赖顺序

```text
R0 基线与架构定版
├── Core：M1.5 正确性加固 → M4 高级节点
└── Spring：M2c 发布/持久化闭环 → Redis/可观测
                     ↓
          JavaNative + SubFlow 跨层能力
                     ↓
           Groovy → GraalJS → Python
                     ↓
                开源版本发布
```

## 4. R0：工程基线与架构定版

### 工作项

1. 更新技术规范和设计文档中的里程碑状态，移除将计划项误标为已完成的 `✅`。
2. 形成模块边界 ADR，明确 Core 禁止依赖的技术栈。
3. 定版流程生命周期：采用“创建草稿 → 保存新版本 → 显式启用”。
4. Maven Enforcer 强制 Java 21 和依赖收敛。
5. Surefire 执行 `*Test`，Failsafe 执行 `*IT`。
6. CI 分为快速单测与 Docker 集成测试；集成环境缺失不得静默通过。
7. 增加模块依赖门禁，阻止 Core 引入框架和基础设施依赖。

### 验收

```bash
mvn -B -ntp clean verify -DskipITs
mvn -B -ntp clean verify -Pintegration
```

CI 应执行现有 112 个非容器测试和 7 个 MySQL Testcontainers IT。

## 5. M1.5：Core 独立化与正确性加固（已完成，2026-08-20）

实现与验收记录：`docs/sessions/2026-08-20-openrule-m1.5-session.md`。

### 工作项

1. 将无 Spring 依赖的 `FlowCompiler` 下沉到 `openrule-core`。
2. 增加 `OpenRuleEngine`/Builder Facade，支持：

   ```text
   FlowDefinition → validate → compile → execute
   ```

3. 修正 Parallel Stage 吞掉 `FailPolicy.ABORT` 的问题。
4. 明确并修正 `skipWhenStopped=false` 语义。
5. `FactMap` 改为嵌套结构的深层不可变快照。
6. 编译产物不得继续引用可由调用方修改的 Definition 集合。
7. 增加 flow/stage/node 级整体校验，包括空值、ID 唯一性、order 冲突和执行模式。
8. 实现已经公开但缺失的 `FirstTerminalAggregator`。
9. 调整编译节点结构，避免 M4 RuleSet 引入 `NodeRunner → Registry → RuleSetExecutor` 构造环。

### 验收

- 纯 Core 从 Definition 直接编译执行，不引用 Spring。
- Parallel ABORT 正确向上传播。
- 外部修改嵌套 facts 或原始 definition 不影响已开始/已编译的执行。
- 并行节点写同一 output key 连续执行 1000 次结果一致。
- M1 原有行为与测试全部保持通过。

## 6. M2c：Spring/JDBC 生产闭环

### 6.1 版本生命周期

推荐 API：

```text
POST /api/v1/admin/flows
创建 v1 草稿，enabled=false

PUT /api/v1/admin/flows/{flowId}
保存新的不可变草稿版本，不改变 active

POST /api/v1/admin/flows/{flowId}/enable?version=N
原子切换 active 版本

POST /api/v1/admin/flows/{flowId}/rollback?version=N
复用 enable 事务路径
```

新增版本头表：

```text
or_flow_head(flow_id, latest_version, active_version)
```

保存版本时锁定 head 行分配版本，替代存在并发竞争的 `MAX(version)+1`。流程定义记录保持不可变，发布只切换 `active_version`。

### 6.2 持久化与 API

- checksum 在读取时校验，损坏定义拒绝加载。
- 将数据库脚本改为 Flyway 版本化迁移，避免依赖 JAR 中的 `schema.sql` 被宿主应用意外执行。
- 日志查询增加 `from`、`to`、分页和最大 limit。
- 明确 `requestId` 是关联 ID 还是幂等键；若保留幂等语义，增加同步去重端口。
- 增加 ActiveVersion 元数据缓存，避免每次执行都查库并反序列化完整 JSON。
- JDBC 自动配置采用显式属性或明确的模块启用条件，多 DataSource 场景要求唯一候选。
- `ExecutionLogger` 与 `ExecutionLogQuery` 分别按端口退让，避免只覆盖一个端口时装配失败。
- 审计执行器使用有界队列、优雅停机及 accepted/dropped/failure 计数。

### 6.3 全链路集成测试

建立独立集成测试层，覆盖：

```text
创建 v1 草稿
→ 未启用时 execute 失败
→ enable v1
→ execute 命中 v1
→ 异步日志落 MySQL
→ 创建 v2 草稿但仍执行 v1
→ enable v2
→ rollback v1
```

同时验证并发创建版本、并发启用、checksum 损坏、审计脱敏、重复 requestId、时区和 JSON 字段。

## 7. M4：Core 高级节点

现有 M4 设计可复用，但实现前调整以下基础设计：

1. 抽取通用 `ConditionDef`、`CompiledCondition` 和 `OperatorMatcher`。
2. 正则、数值边界、分箱和表格条件全部在 compile 阶段预编译。
3. 聚合器 SPI 改为单入参：

   ```java
   AggregateOutcome aggregate(AggregationInput input);
   ```

4. 使用类型化 `AggregationDef`/`ScoreThresholdDef`，避免从 `metadata` 读取魔法 key。
5. DecisionTree 不直接依赖 `DecisionTableDef.Cell`。

推荐实现顺序：

```text
共享 Condition/Operator 编译模型
→ FirstTerminal / ScoreThreshold
→ ScoreCard
→ DecisionTable
→ DecisionTree
→ RuleSet
→ Core 组合 Demo + JSON/Spring E2E
```

验收重点：

- ScoreCard：分箱互斥、区间不重叠、默认分箱唯一、阈值顺序和舍入规则。
- DecisionTable：FIRST/PRIORITY/COLLECT 及稳定覆盖顺序。
- DecisionTree：内部节点与叶子约束、分支唯一、环检测、深度上限 20。
- RuleSet：嵌套校验、内部 stop/ABORT/score/output 语义和最大深度。

## 8. Redis 热更新与可观测

新增 `openrule-redis`，实现发布端、订阅端和自动配置。事件至少携带：

```json
{
  "eventId": "...",
  "flowId": "order_risk",
  "activeVersion": 3,
  "occurredAt": "..."
}
```

DB 事务完成后，本实例同步失效并广播，其他实例按 flowId 失效。Redis Pub/Sub 会丢消息，因此使用 ActiveVersion 短 TTL/版本复核兜底；重连时清空本地 active 元数据缓存。

Core 增加无依赖 `ExecutionObserver` SPI，Spring 提供 Micrometer 实现。指标不得以 `bizId`、`requestId` 或任意 `nodeId` 作为高基数标签。

## 9. JavaNative 与 SubFlow

### JavaNative

- Core 定义 `JavaAction`、`JavaActionResolver` 和只读 `RuleInput`。
- Standalone 使用 Map 注册 Action。
- Spring 使用 Bean Resolver。
- compile 阶段解析 Action，execute 阶段不访问容器。

### SubFlow

- Core 定义 `SubFlowResolver`、`ExecutionScope` 和 `FlowCallStack`。
- Spring 通过 `FlowLoader` 实现 Resolver。
- 覆盖 A→A、A→B→A、深度大于 5、固定版本/活动版本和并行上下文隔离。

## 10. M3/M5：脚本、Python 与性能

1. Groovy 独立模块：Class 缓存、每次独立 Binding、沙箱负例和并发无串扰。
2. GraalJS 独立模块：Context 隔离、Host/IO 禁用、编译期语法校验。
3. 脚本源码通过 `ScriptSourceResolver` 获取，JDBC 模块再增加 `or_script` 存储。
4. Python 最后实现常驻 worker、进程池、超时和坏进程重建。
5. 增加 JMH/性能 profile：
   - 5 节点纯 OPERATOR 流程 P99 `< 2ms`
   - Groovy 热路径 P99 `< 20ms`

## 11. 开源发布门槛

- Java 21 全量构建和 CI 门禁稳定。
- 单元、MySQL、Redis、多实例热更新测试全部通过。
- Core 行覆盖率建议不低于 85%，分支覆盖率不低于 80%。
- 完成 README、快速开始、Standalone 示例、Spring Boot 示例和配置元数据。
- 提供 BOM/Starter、版本兼容说明、Changelog 和数据库迁移说明。
- 1.0 后引入 Revapi 或 Japicmp 检查公共 API 兼容性。

## 12. 下一实施批次

M1.5 已完成。下一批先补齐 **R0**，建议拆为以下提交：

1. `docs: 对齐实际里程碑状态与模块边界`
2. `build: Java 21 enforcer + surefire/failsafe + integration profile`
3. `ci: 增加单元测试与 Docker 集成测试门禁`
4. `build: 增加 Core 模块依赖边界检查`

完成 R0 后，再启动 M2c；M2c 发布模型稳定后进入 M4。
