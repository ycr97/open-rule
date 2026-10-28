# Studio M1 契约入口

状态：D0 目标契约；ADR-0005 为 **Proposed**，当前 Java `/api/v1` 尚未实现此协议。仅用于 Studio M1 订单准入。实施依赖：[ADR-0005](../../adr/0005-studio-m1-flow-contract.md)。本目录的 `openapi.json` 是 `/api/v2` 目标协议，`schema/definition.schema.json` 是发布/执行定义，`schema/draft.schema.json` 是可保存草稿。原型 `State.schema=1`、`Node.type=OPERATOR` 和浏览器 hash 不等于本契约。

## 文档和节点

`DraftDocument` 和 `ExecutableDefinition` 都有 `schemaVersion=2`、`flowId`、`flowName`、字符串 `version`、`description` 和有序 `stages`。Stage/Node 的 `order` 从 1 开始，在同级唯一；ID 在 Flow 内稳定且唯一。发布身份由资源路径与文档身份共同确认。UI 选中态、表单临时文本、checksum、revision、status、更新时间和发布说明不写进 Definition。草稿可保存业务不完整项；结构非法不保存。生成 ExecutableDefinition 时必须无空业务值且通过编译器校验。已发布读取的 `document` 与其发布时 ExecutableDefinition 同内容。

五类内置 type ID 与 configVersion 均为 1：

| type | config | 运行结果 |
| --- | --- | --- |
| `openrule.operator` | `condition`, `outputKey`, `reasonCode` | 成功时始终输出 boolean；命中为 true |
| `openrule.rule-set` | `outputKey`, `matchPolicy`, `rules[]` | FIRST_MATCH 输出首条命中值；ALL_MATCH 输出按行顺序的值数组；无命中输出 null，hit=false |
| `openrule.scorecard` | `outputKey`, `characteristics[].bins[]` | 每维必须恰好命中一箱；精确十进制求和，hit=true；失败时不输出 |
| `openrule.decision-table` | `outputKey`, `hitPolicy=FIRST`, `rules[]` | 首条命中值；无命中输出 null，hit=false |
| `openrule.terminal` | `decisionCode`, `reasonCodes[]`, `scoreRef` | 产生最终决策；无 failPolicy 和变量输出 |

规则行与分箱按数组顺序判断；`reasonCode` 只在命中时进入节点明细，Terminal 的 reasonCodes 是最终业务原因。RuleSet ALL_MATCH 的 output 是数组，FIRST_MATCH/DecisionTable 的 output 是单值。`outputKey` 扁平写入 variables，不能以对象深路径合并。普通节点的 `failPolicy` 必填；Terminal 不带。不存在默认 failPolicy。所有 timeout 使用正整数毫秒；实际 deadline 取 request/stage/node 的最小值。node timeout 大于 Stage timeout 是无意义配置，编译拒绝。

NodeResult 明细使用 `RuleDetail`（命中行、原因和值）或 `ScoreDetail`（维度、分箱、原因、十进制字符串分值）；未命中时 details 为空。`stageResults`、`nodeResults` 均按定义顺序返回，不能按并行完成顺序排序。

`Condition` 支持 `all`、`any`、`not`、`compare`、`is-present`、`is-missing`。`not` 恰一个子项；all/any 非空且按数组顺序短路。Ref 使用 RFC 6901 JSON Pointer；`FACT` 指向 facts，`VARIABLE` 指向已合并变量，`NODE` 只指向已完成节点的 `/nodeId/status` 或 `/nodeId/hit`。空 Pointer 仅对 FACT/VARIABLE 允许。`NODE` 的 SKIPPED 状态可读且 hit=false；未开始节点编译拒绝。缺失与显式 null 不同，`is-present` 对 null 为 true。比较/排序不隐式转换类型，数字按 BigDecimal 值比较；`in` 右值须数组，`between` 为两个升序数字，`is-null/not-null` 对 present 值。非法操作数组合由编译器拒绝，实际输入类型错是节点失败。

## 数字和摘要

API 中 version/revision 为正整数字符串；timeout/elapsed/order 为有界整数。facts 和 Definition 中的业务数字仍是 JSON number，不能改为字符串。前端必须用保留字面量的 JSON 解析/序列化适配器维护十进制文本，不经过 JavaScript `Number` 后再传；超出 JS 安全整数的输入同理。服务端以 BigDecimal 解析并计算。响应 `decision.score` 与分箱分值明细为规范十进制字符串，变量摘要中的数值应由服务端按同样规则安全序列化或脱敏。`0.1+0.2` 必须为 `0.3`；`9007199254740993` 不得变为 `9007199254740992`。

Definition checksum 只覆盖 ExecutableDefinition 六个顶层字段及其全部 Stage/Node/config/Condition，使用 ADR-0005 的 `openrule-decimal-c14n-v1`、UTF-8 和 SHA-256，外观为 `sha256:<64 lowercase hex>`。字段顺序和数值词法差异不改摘要，数组调序、阈值、名称、版本或 timeout 改变摘要。客户端只展示服务端摘要；不能将其当作 ADR-0004 的 package checksum。v1 行继续按原始 `definition_json` 字节校验裸 hex，再隔离读取/执行；显式迁移产生新版本并做黄金样例对照，不回写旧行。当前 JDBC `mapRow` 未验证 checksum，B2 必须补齐。

## HTTP 与错误

OpenAPI 中所有响应为裸 JSON DTO，无通用 envelope。写操作的 `expectedRevision` 做原子 CAS；409 返回 `OR-REVISION-CONFLICT` 或 `OR-LIFECYCLE-CONFLICT`。定义结构错误为 400 `OR-DEF-SCHEMA`，完整性/编译失败为 400 `OR-DEF-VALIDATION`（校验专用接口返回 200/`valid=false`），资源不存在 404。v1 checksum 损坏为 `OR-DEF-CHECKSUM`，绝不继续迁移/执行。所有错误体含 `code/message/requestId/traceId/issues/execution`，未进入 Core 时 execution=null。服务端产生 requestId/traceId，模拟与执行结果回显。请求超时为 504 `OR-EXECUTION-TIMEOUT`；过载为 503 `OR-ENGINE-OVERLOADED`；ABORT 和引擎故障为 500，技术失败 `decision=null`。节点 CONTINUE 后 Terminal 成功是 200/DECIDED，仍保留失败 NodeResult。调用方取消不转 REVIEW。执行无自动重试。发布响应丢失先 GET 状态，再决定后续动作。

试运行只接受已保存的精确 revision；草稿可模拟，LIVE 仅精确 PUBLISHED。服务端返回所用 `flowId/version/revision/definitionChecksum`。草稿的模拟 checksum 仅代表该次有效快照，资源的 `definitionChecksum` 在发布前为 null。执行时按定义顺序返回含跳过节点的结果。`variables` 和 `outputs` 仅是服务端脱敏摘要，不承诺保存原始敏感 facts。

## Schema 与编译器分工

| 约束 | Schema | 编译器/运行时 |
| --- | --- | --- |
| 字段、类型、五类 config、空业务值、Pointer 基本语法 | 是 | 严格绑定再确认 |
| ID/order 唯一、`not` 一子项、操作数与 op 匹配 | 否 | 编译器 |
| NODE/VARIABLE 拓扑、并行输出冲突、Terminal 位置与默认兜底 | 否 | 编译器，运行时再保护 |
| 可证明的分箱重叠与缺口 | 否 | 编译器尽力报告 |
| 动态分箱恰好命中、facts 类型、deadline、实际缺失变量 | 否 | 运行时 |
| JSON 重复键、有效数字精度上限 | 否 | 严格解析器 |

`fixtures/valid/order-admission.definition.json` 为可执行定义，`fixtures/valid/incomplete.draft.json` 仅能保存草稿。`fixtures/invalid` 分为 Schema 失败与跨字段语义失败。`fixtures/valid/order-admission.cases.json` 的 expected 是 Java 实施验收预期，当前校验脚本只校验输入夹具和静态语义，不声称已运行 Java 决策。

本地校验：

```sh
./scripts/validate-studio-m1.sh
```

脚本通过 Maven 离线使用 JSON Schema Draft 2020-12 验证器，另检查 OpenAPI 3.1 路径、operationId、本地引用和跨字段 fixture。B1/B2 应将同一 fixture 加入编译器/API 集成测试。
