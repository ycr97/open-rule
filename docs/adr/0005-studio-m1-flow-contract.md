# ADR-0005：Studio M1 Flow 控制流与定义契约

> 状态：Proposed
> 日期：2026-09-24
> 范围：Studio M1 新建的 Flow；待产品与旧定义兼容决策见末节
> 规范入口：[契约说明](../contracts/studio-m1/README.md)、[Schema](../contracts/studio-m1/schema/definition.schema.json)、[OpenAPI](../contracts/studio-m1/openapi.json)

## 决策与替代范围

本提案仅对新建 `schemaVersion: 2` Flow 替代 ADR-0001 的 `decisionOnHit`、`stopOnHit`、`AggregationDefinition`/内置聚合器在 Flow 最终决策中的使用，以及 ADR-0002 `NodeResult` 中的 `decision`、`score`、`stop` 和 `AggregationInput` 最终决策路径。替代 ADR-0001 的 v2 示例中这些字段；保留开放 type ID、`configVersion`、类型化 `config`、严格绑定与 executor-owned plan。替代旧 `FailPolicy` 的 `SKIP/REVIEW/REJECT` 为 `ABORT/CONTINUE`。ADR-0001 的 v1 自动迁移承诺对已持久化旧 Flow 改为下文的只读旧语义和显式迁移；ADR-0002 的 v1 Pointer 转换仅用于显式迁移。ADR-0002 的不可变值、严格比较、missing/null、串并行快照保留；`ValueSource` 增加 `NODE`。ADR-0003 的 deadline、取消和资源隔离全部保留。ADR-0004 的 DecisionPackage、packageId 和包 checksum 仍属后续包能力；其 Definition canonical codec 的 RFC 8785 数字编码在 M1 Definition 摘要范围内由下文精确十进制编码替代，未来构包必须沿用该 Definition 字节串，包级 manifest 规范另行保持。M1 的 Definition checksum 不称 packageId。

旧 v1 的读取和执行保留旧语义，不因本提案重解释为 v2。Accepted ADR 原文不修改。此提案未获人工批准，B1/B2 合并前需处理末节实质问题。

## 控制流

Stage 按 `order` 升序，节点按 `order` 升序。`when: null` 无条件；非空 Condition 在进入 Stage 时针对当前 facts、已提交 variables 和先前节点结果求值。false 时整个 Stage 的节点生成 `SKIPPED/WHEN_FALSE`。求值错误是 `OR-STAGE-CONDITION` 技术失败，无业务 decision；不得应用节点 FailPolicy。`when` 的短路顺序是 AST 数组顺序。请求事实缺失只在实际求值时失败，不做全局预检。

普通节点成功但 `hit=false` 仍为 `SUCCEEDED`。普通节点只输出变量、原因和明细，不产生最终决策。`TERMINAL` 是唯一决策来源，必须在串行 Stage 的最后一个节点；可有多个有条件 Terminal Stage，最后一个 Stage 必须是无条件、只含一个 Terminal 的兜底 Stage。Terminal 执行后流程结束，所有后续节点标记 `SKIPPED/TERMINATED`。无 Terminal 到达为 `OR-FLOW-NO-TERMINAL` 技术失败。Terminal 的 `decisionCode` 为非空开放字符串，`reasonCodes` 为有序非空数组，`scoreRef` 可空；缺失 scoreRef 的值产生 null 分数，若指定的变量不存在或非数值则执行失败。Terminal 不覆写 variables。

`NODE` 引用 JSON Pointer 仅为 `/nodeId/status`、`/nodeId/hit`；不支持读取 node outputs/details/failure 文本。status 为 `SUCCEEDED|FAILED|TIMED_OUT|CANCELLED|SKIPPED`，hit 在非 SUCCEEDED 时恒为 false。不存在的节点或尚未执行的节点属于非法定义，在编译阶段拒绝；已执行但跳过的节点可以读取上述字段。串行 Stage 后续节点可引用先前节点；并行 Stage 内节点只能引用 Stage 入口之前的节点和变量。Stage when 只能引用更早 Stage。引用失败节点的输出变量属于非法定义；运行期若该变量未生成，用 `is-missing` 可检测，否则比较产生节点失败。并行输出键跨节点重复编译拒绝，运行时也保护。

普通节点的可预期错误（包括事实缺失、类型错误、分箱无命中/重叠、节点或 Stage deadline）按节点 `ABORT` 或 `CONTINUE` 处理：`CONTINUE` 保留 `FAILED` 或 `TIMED_OUT` 供后续 Stage 路由；`ABORT` 结束为技术失败。请求 deadline、调用方取消、资源过载、引擎不变量故障永不转为业务决策。并行 Stage 的兄弟节点全部达到终态后再按定义顺序提交成功输出；若出现 ABORT，取消兄弟任务且不提交该 Stage 输出。技术失败时 `decision=null`；已取得的节点结果可以附在错误响应。执行结果中的 `stageResults` 与 `nodeResults` 均按定义顺序排列，跳过节点也保留位置。

## 文档与生命周期

`DraftDocument` 是结构严格的编辑文档，允许节点配置中的业务必填项缺失（用 null 或空数组表示），保存返回完整性校验报告。未知字段、未知类型、非法 JSON Pointer、非法数字字面量和重复 JSON key 为结构错误，拒绝保存。`ExecutableDefinition` 从完整校验通过的草稿生成，不包含 UI 状态、revision、status、发布说明和时间戳；所有必填项齐全。两者使用同一 `schemaVersion: 2` 与五类配置。后端在绑定、编译前执行 Schema 校验及跨字段语义校验，不能把通过 Schema 等同于可执行。

每个 Flow 同时最多一个 DRAFT。创建 Flow 分配 version `"1"`/revision `"1"`，保存仅递增 revision。发布携带 expectedRevision，在同一事务中重读、验证、生成 canonical Definition/checksum、冻结同一 version、递增 revision；不设置 active 指针。创建下一草稿从指定 PUBLISHED 版本复制并分配下一 version/revision `"1"`。PUBLISHED 永不可写。版本分配在 Flow head 行锁或等效原子机制下完成，不使用无锁 MAX+1。HTTP 中 version/revision 为正十进制字符串，服务端拥有身份。发布说明必填，但不进入 Definition checksum。

Definition checksum 为 `sha256:` 加 64 位小写十六进制，输入是完整 ExecutableDefinition 的 Studio M1 精确十进制 canonical JSON UTF-8 字节：对象键按 Unicode UTF-16 码元升序，字符串转义遵循 RFC 8785，数组顺序保留；数值用十进制任意精度解析，去除无意义的前后零与指数，输出普通十进制形式，负零归零。相等的 `1`、`1.0`、`1e0` 字面量摘要相同。输入最多 128 位有效数字、指数绝对值不超过 1000，超限拒绝；严禁先经 binary float。此格式因数值规则不是 RFC 8785 的 IEEE 754 数值格式，独立标识为 `openrule-decimal-c14n-v1`。发布内容变化必变摘要；revision/status/时间和说明变化不变摘要。客户端不得自行声明可信 checksum。v1 原始行仍使用旧 `SHA-256(raw definition_json UTF-8)` 的裸 hex，先校验再解码；不能拿 v2 canonical 规则重验 v1。

## 契约验证边界

Schema 验证字段、联合类型、枚举、JSON Pointer 的基本形状及单个数值的字面量类型。编译器验证 ID/order 唯一、引用拓扑、输出冲突、Terminal 位置和兜底、条件操作数类型与可证明的分箱重叠。运行时验证事实的实际类型、动态分箱恰好命中、deadline 和变量是否实际存在。条件 `is-present` 对显式 null 返回 true；`is-missing` 仅对不存在返回 true。

## 旧 v1 路径

旧 JDBC 行读取时先对数据库中原始 `definition_json` UTF-8 计算旧裸 hex 并与 `checksum` 作常量时间比较；不符拒绝读取 `OR-DEF-CHECKSUM`。缺少 `schemaVersion` 视为 v1。v1 在隔离的 legacy codec/runtime 中按原 version、enabled 和聚合器语义只读执行；新 `/api/v2` 不创建、覆盖、发布或激活 v1。若未来迁移：导出原行、选择业务认可的 v2 Terminal 映射、创建**新** v2 version，运行原始与新定义黄金输入对比决策/原因/分数/失败路径后人工批准切换；不重写 v1 行。含 REGEX 或无法证明等价的聚合策略必须显式迁移，不能自动转换。v1 的 `fact.*`、`var.*` Pointer 及 Operator 名称转换规则沿用 ADR-0002，仅用于可证明等价的显式迁移。

## 仍需决定

1. 旧 v1 运行引擎是否在 Studio M1 的部署宿主继续可用，以及其维护期限。这是现有已启用流程的兼容承诺，不能由 D0 推断；若不可用，必须给出停用/切换计划。
2. `order_admission` 原型阈值和决策码是否用于生产业务。当前仅作为 OA 验收样例，不自动升级为生产准入政策。
