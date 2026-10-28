# ADR-0006：Studio M2 显式事实解析边界

> 状态：Proposed
> 日期：2026-10-07
> 范围：Studio M2-D0；没有将任何历史 ADR 改为 Accepted。
> 规范：[设计](../design/2026-10-04-openrule-studio-m2-facts.md)、[契约](../contracts/studio-m2/README.md)。

## 背景

M1 已验收，原请求由调用方提供 facts。产品蓝图要求 requiredFacts 与批量 Provider；现有 M1 请求/响应都禁止额外字段，Core 入口会重新建立 timeout。直接在原入口自动补数会改变 missing/null 的行为并可能重置总预算。

## 决策

1. 新增独立 fact-simulations/fact-executions；旧 M1 路径不查目录、不补数。补数目的显式，SIMULATE 的 HTTP 还须配置 allowSimulationHttp=true、readOnly=true；LIVE 不能选择 Mock。
2. 编译产物收集 FACT 路径、使用位置和类型提示，不将 requiredFacts 当全局必填/拒绝条件，不写回 Definition/checksum。presence-only 可在新入口触发补数；旧入口保持原行为。
3. Provider SPI/批量解析归 OSS 集成层；Catalog/配置/映射管理归现有 Studio 宿主 EE 包。Core 无 HTTP/Provider配置，不复制 Runtime，不增加模块或服务。
4. 只补 missing。已有值和null不覆盖；非对象父路径阻挡合并。请求映射只读原始 facts，单Provider多字段单次POST，无依赖链、跨请求缓存和自动重试。
5. resolved/missing/failed 为逐字段状态，partial保留成功值。可预期 Provider 失败交给原 Core 根据实际求值/FailPolicy/Terminal处理；请求预算、取消或过载为技术失败，不直接产出REVIEW。
6. M2 入口建立单调绝对 Deadline，同一实例传入 Provider 与 Core；连接、完整body、解析和排队均受预算约束。迟到结果无写入资格。
7. 管理配置当前revision CAS；执行读取短事务一致快照并记录revision，不引入配置发布/历史。只提供新增/更新/启停，不提供删除，引用不会因删除悬空。
8. traceId 在读取外部数据前建立。secretRef只解析宿主环境引用，固定Bearer鉴权；默认脱敏，Mock不解析secret/不触网。当次字段记录不是历史重放证据。

## 与既有 ADR 的关系

- ADR-0001 的开放类型、类型化配置、Core无JSON基础设施不变；本批不实现旧聚合器/插件体系迁移。
- ADR-0002 的不可变快照、严格值和missing/null语义保留。字段依赖类型为诊断提示，不收紧原比较器对已存在 Flow 的接受范围。
- ADR-0003 的绝对Deadline/取消/过载原则扩展到事实解析阶段。新增绝对时间入口是实现对齐，不重新解释M1旧入口的计时行为。
- ADR-0004 的 SIMULATE 外部读取约束落实为显式Mock或已允许的只读HTTP。包、Evidence、Replay与历史Trace仍延期，不把配置revision或脱敏报告叫作可重放制品。
- ADR-0005 已实现的Studio控制流、版本、精确十进制/checksum和旧v1隔离保留；其Proposed状态及原待决生产事项不被本ADR改变。

## 取舍

独立执行路径增加两个API，但允许严格兼容M1和清楚区分响应；现有UI默认仍用M1。静态预取可能调用最终未运行分支的数据源，但避免路径预测/节点I/O。配置只保存当前revision不能还原历史Provider配置，本期仅承诺当次可解释，不承诺历史重放。Provider按请求串行以降低调度复杂度；全局在途限制仍必须存在。

## 验证

采用M2契约fixture和验收矩阵：缺失/显式null、一次批量、partial、Mock隔离、绝对Deadline、取消/迟到结果、并发配置快照/CAS，以及M1全量OA/ST回归。本ADR本身不声称这些运行能力已完成。
