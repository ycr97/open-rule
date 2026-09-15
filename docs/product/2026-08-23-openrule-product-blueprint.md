# OpenRule OSS 与企业版产品蓝图

> 状态：已确认的产品基线，具体 Java 契约在实现前由 ADR 固化
> 日期：2026-08-23
> 适用仓库：open-rule、openrule-ee
> 已确认选择：1A 线性条件路由、2A 最小事实数据层、3A EE 模块化单体

## 1. 背景与结论

OpenRule 不以复制 re/dm 的全部功能为目标，而是先完成一条可用于真实同步风控/准入场景的生产闭环：

~~~text
业务请求
  -> 场景路由
  -> 精确决策版本
  -> requiredFacts 分析
  -> FactProvider 批量补全
  -> 条件化决策执行
  -> Terminal Decision
  -> 结构化 Trace
~~~

新路线以纵向闭环优先，不再同时建设 DAG、模型服务、回溯平台、复杂治理和多套基础设施。企业级不由模块数量决定，而由确定性、版本不可变、失败语义、资源隔离、可观测、发布回滚和数据边界决定。

本蓝图是产品范围与实施顺序的权威基线。它不直接覆盖已接受 ADR；发生冲突时，必须新增 superseding ADR，不能在代码中静默改变契约。

## 2. 产品定位与边界

### 2.1 open-rule OSS

定位：可嵌入、确定性、可扩展、可追踪的 Java 决策执行引擎。

OSS 负责：

- 决策定义、校验、编译和执行；
- 统一 Condition AST 与严格值语义；
- 线性条件路由、串行/并行 Stage；
- OPERATOR、RuleSet、ScoreCard、DecisionTable、Terminal；
- 编译期 requiredFacts 分析；
- FactProvider SPI、批量事实解析编排和 Mock 扩展点；
- 精确版本执行、LIVE/SIMULATE、基础 Trace；
- REST、Spring 和 JDBC 参考实现。

OSS 不负责：

- 场景和策略路由管理；
- Provider 配置中心和字段资产目录；
- 可视化编排、审批、RBAC、多租户；
- 企业分析、模型托管和复杂回溯。

### 2.2 openrule-ee

定位：基于同一 OSS Core 构建的企业决策管理平台。

EE 负责：

- 场景路由和默认发布；
- 不可变发布、原子激活和回滚；
- 事实字段目录、Provider 配置、请求/响应映射；
- HTTP/Java/Mock Provider 管理；
- 决策 Trace、Fact 调用记录和诊断查询；
- 后续的表单化可视化配置与治理能力。

EE 只能依赖 OSS 的公开契约，禁止复制、修改或分叉 Core Runtime。

## 3. 目标架构

~~~text
┌──────────────── openrule-ee 模块化单体 ────────────────┐
│ DecisionApplicationService                              │
│   ├── SceneRouter（只读取原始请求事实）                 │
│   ├── ReleaseService（精确 flow/version/checksum）      │
│   ├── FactResolutionService（Provider 批量补全）        │
│   └── TraceService                                      │
└────────────────────────┬────────────────────────────────┘
                         │ 仅调用 OSS 公开 API
┌────────────────────────▼────────────────────────────────┐
│ openrule-api / openrule-jdbc / openrule-spring          │
│   ├── HTTP Adapter                                      │
│   ├── Repository Adapter                                │
│   └── 执行编排、FactProvider SPI                        │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│ openrule-core                                            │
│ Definition -> Validate -> Compile -> Execute -> Result   │
│ 无 Spring、HTTP、JDBC、Redis、Provider 配置              │
└─────────────────────────────────────────────────────────┘
~~~

首版不新增 openrule-feature、openrule-executor 等模块。只有出现第二个非 Spring 事实解析使用方时，才评估抽取独立模块。

## 4. Core 最小心智模型

~~~text
Flow
 └── ordered Stages
      ├── optional when
      ├── executionMode: SERIAL | PARALLEL
      └── Nodes
           ├── 普通节点：计算并返回 status/hit/outputs
           └── Terminal：唯一产生最终业务决策
~~~

核心不变量：

1. Stage 按 order 顺序运行，when 缺省为 true。
2. Stage guard 可读取 facts、前序 variables 和前序 node result。
3. guard 缺失值或类型错误不能静默解释为 false。
4. 并行节点读取同一不可变快照，outputs 按定义顺序合并。
5. 并行输出键冲突必须失败，不能依赖线程完成顺序覆盖。
6. Terminal 只能位于 SERIAL Stage，并且是该 Stage 最后一个节点。
7. Flow 必须存在无条件兜底 Terminal；编译器拒绝无最终出口的定义。
8. 相同 definition、facts、Clock 和插件版本必须产生相同业务结果。

### 4.1 决策职责收敛

目标契约中，普通节点不再通过 decisionOnHit、stopOnHit 直接决定全局结果。业务决策由条件 Stage 和 Terminal 显式表达。

技术失败策略首版收敛为：

- ABORT：立即结束并返回技术失败；
- CONTINUE：保留失败 NodeResult，由后续 Stage 显式路由。

REVIEW、REJECT 属于业务决策码，不应继续作为通用 FailPolicy。Priority/FirstTerminal 聚合器不再作为首版核心心智模型；是否保留兼容能力由 superseding ADR 决定。

## 5. Condition 与值语义

Condition AST 首版只包含：

- all；
- any；
- not；
- compare；
- is-present；
- is-missing。

compare 支持 ADR-0002 已定义的严格运算符：eq/ne、gt/gte/lt/lte、between、contains、starts-with、ends-with、in 及对应反向运算。

约束：

- 不做字符串、数字、布尔值隐式转换；
- missing 与显式 null 保持不同；
- 普通比较遇到 missing 返回条件执行错误；
- is-present/is-missing 是处理缺失的唯一显式方式；
- 不引入 SpEL、MVEL、Groovy、JavaScript 或通用表达式脚本；
- Stage、RuleSet、ScoreCard 和 DecisionTable 复用同一 AST。

## 6. requiredFacts 与事实解析

Core Compiler 遍历 Stage guard、节点条件和 ValueRef，生成 CompiledFlowPlan.requiredFacts()。它只包含事实路径、使用位置和可推导的预期类型，不包含数据源、URL 或鉴权信息。

执行顺序：

1. 接收调用方 facts；
2. 加载精确 CompiledFlowPlan；
3. 找出 requiredFacts 中尚未提供的字段；
4. 按 Provider 分组，一次 Provider 调用解析多个字段；
5. 合并成功值，保留逐字段错误；
6. 使用不可变事实快照进入 Core；
7. 将数据调用和决策执行写入同一 traceId。

FactProvider SPI 位于 OSS 集成层，Core Runtime 不调用它：

~~~java
public interface FactProvider {
    String type();
    FactBatchResult resolve(FactBatchRequest request);
}
~~~

FactBatchResult 禁止使用 -9999、空字符串等魔法值表达错误。每个字段必须明确为 resolved、missing 或 failed。

EE 首版 Provider 配置只支持：

- 一个事实字段绑定一个 Provider；
- POST 批量请求；
- 常量及 JSON Pointer 请求映射；
- JSON Pointer 响应映射；
- connect/read timeout；
- secretRef 鉴权引用；
- MockProvider。

默认不支持 Provider 权重、自动降级、多源竞价、重试编排和派生特征。

## 7. 标准节点范围

### 7.1 OPERATOR

执行一个 Condition，返回 matched，并按配置产生 outputs。它是最小执行原语，不直接结束 Flow。

### 7.2 RuleSet

- 有序规则；
- 每条规则包含 condition、outputs、reasonCode；
- 首版 hit policy：FIRST_MATCH、ALL_MATCH；
- 冲突按定义顺序处理，不能依赖集合遍历顺序。

### 7.3 ScoreCard

- characteristic 和 bin 两级结构；
- 每个 characteristic 只命中一个 bin；
- 使用 BigDecimal 累加；
- 输出 score、命中明细和 reasonCodes；
- 不承担最终 APPROVE/REJECT 阈值判断。

### 7.4 DecisionTable

- 有序条件行和输出列；
- 首版只支持 FIRST hit policy；
- UNIQUE、COLLECT 等策略在真实用例出现后增加。

### 7.5 Terminal

Terminal 输出统一 DecisionOutcome：

- decisionCode：开放字符串，推荐使用 APPROVE/REJECT/REVIEW；
- reasonCodes；
- score；
- outputs；
- terminal node identity。

首版节点配置内联在不可变 FlowDefinition 中。只有出现跨 Flow 复用并要求独立发布的真实需求后，才增加 AssetRepository 和 AssetRef。

## 8. 版本、发布与模拟

### 8.1 OSS Flow 生命周期

- DRAFT 可修改；
- PUBLISHED 不可修改；
- LIVE 只能执行 PUBLISHED 精确版本；
- SIMULATE 可以执行 DRAFT 或 PUBLISHED；
- save 不得自动启用；
- canonical JSON 计算 SHA-256 checksum；
- CompiledPlan 缓存键为 flowId + version + checksum。

requestId 首版只是关联 ID，不提供隐式幂等语义。

### 8.2 EE Scene Release

Scene Release 是不可变路由快照，包含：

- 有序路由条件；
- 每条路由对应的精确 flowId/version/checksum；
- 默认 Flow 版本；
- release checksum。

scene 只保存 activeReleaseId。激活和回滚通过带 revision 的原子指针更新完成，不修改历史 Release。

首版路由：

- 只读取调用方原始事实；
- 按 priority 首条命中；
- 必须有默认目标；
- 不支持权重、随机、灰度和粘性分流。

## 9. EE 模块化单体

建议使用一个 Spring Boot 应用和以下逻辑包：

~~~text
io.openrule.ee
 ├── decision      在线请求编排
 ├── scene         Scene 与路由规则
 ├── release       发布、激活、回滚
 ├── fact          字段、Provider、映射、Mock
 ├── trace         决策和数据调用记录
 └── authoring     后续配置界面
~~~

首版 MySQL 数据模型至少包含：

- ee_scene；
- ee_scene_release；
- ee_fact_definition；
- ee_fact_provider；
- ee_provider_fact_mapping；
- ee_decision_trace；
- ee_fact_call_record。

MySQL 是唯一强制基础设施。CompiledPlan 使用进程内缓存；Scene Release 首版可直接查询数据库，确认 SLO 后再增加安全缓存。Redis、Kafka、ClickHouse 均不是首版依赖。

## 10. 在线接口基线

EE 在线入口：

~~~http
POST /v1/scenes/{sceneCode}/decisions
~~~

~~~json
{
  "requestId": "req-001",
  "bizId": "order-001",
  "timeoutMillis": 300,
  "facts": {}
}
~~~

响应至少包含：

~~~json
{
  "requestId": "req-001",
  "traceId": "trace-001",
  "status": "DECIDED",
  "sceneCode": "risk-admission",
  "flow": {
    "flowId": "admission",
    "version": 3,
    "checksum": "sha256:..."
  },
  "decision": {
    "decisionCode": "REVIEW",
    "reasonCodes": [],
    "score": null,
    "outputs": {}
  }
}
~~~

不得默认在响应或持久化 Trace 中暴露原始敏感事实。Trace 保存字段名称、结果状态、耗时、错误码和按策略脱敏后的摘要。

## 11. 能力地图

### 11.1 P0：当前蓝图必须交付

OSS：

- Core 契约冻结；
- Condition AST；
- Stage when 和 Terminal；
- requiredFacts；
- 四类标准决策节点；
- FactProvider、批量解析和 Mock；
- LIVE/SIMULATE；
- 不可变版本、checksum、基础 Trace；
- API/JDBC 参考实现。

EE：

- 模块化单体；
- Scene Release；
- 有序路由和原子回滚；
- Fact Catalog；
- Provider 配置和 JSON Pointer 映射；
- 决策与 Fact 调用查询。

### 11.2 Next：有首个生产用例后

- 表单化条件编辑器；
- RuleSet、ScoreCard、DecisionTable 专用编辑器；
- 发布差异比较；
- 指标面板和 Trace 诊断；
- 基础权限与操作审计；
- Trace 保留和归档策略。

### 11.3 Later：由真实需求触发

- 可复用决策资产及独立版本；
- DecisionTree、DecisionMatrix、RuleStrategy、SubFlow；
- 审批、多租户、配额；
- 回测和样本集管理；
- Provider 降级和复杂派生事实；
- 模型节点与模型服务。

### 11.4 Not Now

- 完整 DAG、JOIN 和网关体系；
- Groovy、JavaScript、Python Runtime；
- Redis 强依赖；
- Kafka、ClickHouse、Flink；
- ZIP 决策包、签名、SBOM；
- 漂移检测和自动回滚；
- 七模块或多服务 EE 拆分。

## 12. re/dm 吸收矩阵

| re/dm 实践 | OpenRule 决策 |
|---|---|
| getNeedVariables | 吸收为编译期 requiredFacts |
| DataMarket 批量取数 | 吸收为 FactProvider 批量解析 |
| Provider/数据源工厂 | 改造成注册式 SPI，禁止 Core 查 Spring 容器 |
| Mock 和试跑 | 吸收为 SIMULATE + MockProvider |
| 数据异常分支 | 改为 missing、Fact 错误和显式 Terminal |
| Scene/Strategy 路由 | 简化为有序条件路由和默认 Release |
| 不可变版本与回滚 | 吸收为 checksum + 原子 Release 指针 |
| 节点、数据调用记录 | 吸收为统一 traceId 下的结构化 Trace |
| 图和 JOIN | 延期，等待线性模型无法表达的真实流程 |
| DAO/HTTP 进入 Runtime | 舍弃 |
| mutable Context | 舍弃 |
| 魔法错误值 | 舍弃 |
| 业务专用枚举侵入 Core | 舍弃 |
| 原始请求和敏感数据直接落日志 | 舍弃 |

## 13. 实施里程碑

### R0：蓝图与决策重置

交付：

- 本产品蓝图；
- OSS/EE 边界 ADR；
- Terminal 与条件路由 ADR；
- Fact Resolution Boundary ADR；
- Flow/Scene Release 生命周期 ADR；
- 新路线图和旧文档 superseded 标记。

验收：

- 产品范围只有一个权威来源；
- 旧设计与新设计冲突全部登记；
- 每个 Later 能力均有明确触发条件。

### R1：M1.6 Core 契约

交付：

- 开放 NodeTypeId；
- 类型化 Config/Plan；
- DecisionValue 和不可变 ExecutionRequest；
- Deadline、Cancellation、资源隔离；
- 节点结果和技术失败语义。

验收：

- Core 运行依赖仍只有 JDK；
- 第三方节点无需修改 Core；
- 并行执行读取相同快照；
- canonical 序列化稳定；
- mvn clean verify 通过。

### R2：控制流与事实依赖

交付：

- Condition AST；
- Stage when；
- Terminal；
- requiredFacts collector；
- node result 引用。

验收：

- 可表达正常、拒绝、人工复核、数据异常和默认兜底路径；
- 编译器拒绝无 Terminal、并行 Terminal 和非法引用；
- missing/null/type mismatch 均有固定测试。

### R3：标准节点

交付 OPERATOR、RuleSet、ScoreCard、DecisionTable。

验收：

- 每种节点均有 validate/compile/execute 测试；
- 输入顺序和并行调度不影响业务结果；
- ScoreCard 使用 BigDecimal；
- DecisionTable FIRST 行为确定。

### R4：OSS 集成闭环

交付：

- FactProvider SPI；
- 批量解析和 Mock；
- Fact Trace；
- DRAFT/PUBLISHED；
- checksum；
- LIVE/SIMULATE；
- API/JDBC 更新。

验收：

- Core 无任何网络和数据库调用；
- save 不自动启用；
- LIVE 不能执行 DRAFT；
- 精确版本可重复执行；
- Provider 超时受总 Deadline 约束。

### E1：EE 场景与发布

交付模块化单体、Scene、Scene Release、有序路由、原子激活和回滚。

验收：

- EE 不包含 Core Runtime 副本；
- 并发发布只有一个 revision 更新成功；
- 所有在线结果记录精确 version/checksum；
- 回滚不修改历史定义。

### E2：EE 事实数据层

交付 Fact Catalog、Provider 配置、请求/响应映射、Mock 和调用记录。

验收：

- 同一 Provider 的字段单次批量获取；
- 部分成功和部分失败可区分；
- 凭证只保存 secretRef；
- 敏感字段默认不落原始日志。

### E3：首个生产垂直切片

使用一条脱敏的真实 re/dm 风控流程完成：

- 正常命中；
- 默认放行或默认决策；
- Provider 超时；
- 字段缺失；
- 类型错误；
- Mock 模拟；
- 发布切换；
- 历史回滚；
- Trace 定位。

验收以真实业务样例、目标 QPS、延迟、超时和审计策略为准，不在缺少工作负载数据时虚构性能数字。

## 14. 生产门禁

### 正确性

- 相同版本与输入产生相同业务结果；
- 并发调度不改变 outputs；
- 所有非法定义在 compile 阶段失败；
- missing、null 和数据源错误保持不同。

### 可靠性

- 总 Deadline 贯穿 Provider 和 Core；
- 线程池有界；
- 超时和取消不会泄漏线程；
- Provider 默认不自动重试；
- 多实例共享 MySQL 状态时发布结果一致。

### 可运维性

- traceId 串联路由、事实解析、节点执行和最终决策；
- 提供 scene、flow、node type、provider 的耗时和错误指标；
- 发布、回滚和配置变更有操作记录；
- Trace 有明确保留、清理和脱敏策略。

### 安全

- API 入口限制 payload 和字段深度；
- Provider 凭证不写入数据库明文；
- Trace 默认不记录原始敏感数据；
- 未实施 EE RBAC 前部署在企业网关和认证层之后。

## 15. 延期能力的触发条件

| 能力 | 引入条件 |
|---|---|
| DAG/JOIN | 已确认生产 Flow 需要分支汇聚或分支局部状态 |
| Redis | MySQL + 本地不可变缓存无法满足实测 SLO |
| Kafka/ClickHouse | Trace 写入量、保留量或查询量超过 MySQL 能力 |
| 派生事实 | 出现跨 Flow 复用且存在多级依赖的真实特征 |
| 多 Provider 降级 | 单 Provider 不可用已经造成可量化业务损失 |
| 模型服务 | 已有需要部署、版本化和监控的真实模型 |
| 审批/RBAC/多租户 | 平台开始服务多个独立团队或租户 |
| Backtest | 已有稳定样本集、标签和明确对比指标 |

## 16. 旧设计迁移建议

后续文档迁移按以下顺序执行：

1. 根技术规格保留历史，但将产品范围和路线图指向本蓝图；
2. 重写 development roadmap，使里程碑依赖与本文件一致；
3. 新增 ADR 覆盖 ADR-0001 中 decisionOnHit、stopOnHit、业务 FailPolicy 和 Aggregator 的相关决策；
4. 保留 ADR-0002 的不可变状态与值语义，BACKTEST 从当前交付范围移出；
5. 保留 ADR-0003 的 Deadline、Cancellation 和资源隔离；
6. ADR-0004 首版只落 canonical checksum，ZIP、签名、Evidence 和 Replay 延期；
7. openrule-ee-technical-spec.md 替换七模块、多服务和多存储设计；
8. HTML 蓝图仅作为展示产物，不再作为权威需求源。

旧文档不直接删除。使用 Superseded 标记和迁移说明保留设计演进历史。

## 17. 实施前仍需输入

以下参数不改变总体架构，但必须在对应里程碑开始前确定：

1. 一条脱敏的真实决策流程，包括输入字段、外部事实、规则、评分和最终决策；
2. 目标峰值 QPS、p95/p99 延迟、总超时、Provider 超时和 Trace 保留周期；
3. 在线决策成功但审计持久化失败时，业务选择阻断响应还是优先返回决策。

在这些参数确认前，可以完成 R0 至 R2；不得据此提前引入缓存中间件、消息队列或分析存储。

## 18. 学习与实施原则

核心代码由项目作者亲自实现，AI 主要用于：

- 审查契约是否自洽；
- 构造反例和失败场景；
- 检查模块边界和过度设计；
- 评审测试覆盖与生产门禁。

每个里程碑必须形成可运行的纵向增量，禁止以占位模块、空 SPI 或未被真实场景使用的抽象冒充进度。
