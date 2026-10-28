# Studio M2：最小事实数据接入

> 状态：M2-D0 设计交付，尚未实现。初稿日期：2026-10-07；交付核对日期：2026-10-18。文件名保留任务指定的 2026-10-04。
> 契约入口：[studio-m2](../contracts/studio-m2/README.md)；实施顺序：[delivery](../plans/2026-10-04-openrule-studio-m2-delivery.md)；补充决策：[ADR-0006](../adr/0006-studio-m2-fact-resolution.md)。

## 1. 基线与唯一目标

M1 的订单规则、五类节点、版本生命周期、精确执行和视觉已通过 [V1](../contracts/studio-m1/V1-ACCEPTANCE.md)。本轮只增加“原始请求 → 按缺失字段补数 → 原有 Core → 原有 Terminal → 当次字段明细”。以 `order_admission` 和 `risk_profile` 为唯一交付案例。

源码核对基线：后端 `348a48d209f0571049535f4b513eef556859876b`；前端 `1c520ca753213a8fea95bd1976887fe1a8c84c02`。旧设计中“尚无宿主/发布 API”的文字属于历史状态，不作为新实施事实。

| 当前事实 | M2 增量 |
| --- | --- |
| `StudioCompiler.compile` 返回排序后的 `StudioModel.Flow`；已有结构化错误位置，无 requiredFacts 产物 | 新增包含 Flow 和事实依赖的编译产物，保留原入口 |
| `StudioConditions` 支持 FACT/VARIABLE/NODE、RFC 6901、presence；运行时严格比较 | 只遍历现有 AST，不扩展表达式 |
| `StudioEngine.execute(..., long)` 在内部创建 deadline，Core 用 FactMap 冻结 facts | 增加接受已编译计划和绝对 Deadline 的入口，复用同一执行逻辑 |
| `StudioService.execute` 严格拒绝未知请求字段，在 Core 返回后创建 traceId | 原入口保持不变；新增 M2 编排入口提前创建 traceId/deadline |
| `StudioDocumentCodec` 严格 JSON、BigDecimal/BigInteger、定义摘要 | 数值边界复用；Provider 原始响应不用浮点解析 |
| `JdbcStudioStore` 负责 Flow CAS/发布，两张表与旧 v1 隔离 | M2 配置另建两张表，不向 Definition 塞 Provider 配置 |
| `openrule-studio-server` 已有 Boot/JDBC 宿主 | 在宿主中装配 EE fact 配置包，无新服务/模块 |
| 原型 `catalog.tsx` 包含事实字段、Provider 表单及浏览器映射测试 | Vue 复刻必要交互，测试和补数都请求服务端 |

原型成功响应为 `{data:{blacklisted:false,ageDays:365,refundRate:0.08}}`。原始请求提供 `order.amount=680`、`order.country=JP`、`buyer.id=buyer_1024`，一次 POST 补齐三个字段，原 M1 定义得到 APPROVE/15/LOW。原型代码可能覆盖非对象父路径、合成耗时及用 Number 处理业务值；这些行为不移植。

## 2. 分层与公开接口

| 位置 | 职责 |
| --- | --- |
| `openrule-core/.../studio` | `StudioCompiledPlan`、`RequiredFact`/使用位置收集、绝对 Deadline 执行入口；纯 JDK，无 Provider/HTTP/数据库配置 |
| `openrule-spring/.../fact`（OSS） | `FactProvider` SPI、不可变批次输入/结果、解析编排、HTTP/Mock 参考适配、SecretResolver port；不知道目录表和管理 API |
| `openrule-studio-server/.../fact`（EE 装配包） | 配置模型、管理 Controller/Service/JDBC adapter、secretRef 环境解析器、执行/预览入口、脱敏投影；依赖 OSS 公开接口 |
| 现有 `openrule-api` / `openrule-jdbc` | 继续承载 M1/旧 v1；不放入 EE Catalog/Provider 管理模型 |
| Vue `features/facts`、`features/providers`、现有 editor | 配置与展示，统一 api/lossless 边界 |

建议公开接口形状（名称可在 B1 按邻近代码落地，语义不可偏离）：

```java
StudioCompiledPlan compilePlan(StudioModel.Flow flow);
// plan = 已验证的不可变 Flow + List<RequiredFact>，不参与 Definition 序列化/checksum
StudioModel.Result execute(StudioCompiledPlan plan, Map<String, Object> facts,
                           StudioDeadline deadline);

interface FactProvider {
    String type(); // HTTP / MOCK，注册表拒绝重复
    FactBatchResult resolve(FactBatchRequest request);
}
interface SecretResolver { String resolve(String secretRef); }
```

`FactBatchRequest` 持有 traceId、purpose、该 Provider 的不可变配置快照、所需字段的绑定/类型、原始 facts 快照、同一个 deadline/取消信号。`FactBatchResult` 返回逐路径 resolved(value)/missing(code)/failed(code)，value 仅内部使用；不返回最终 decision。跨 SPI 值限 null/Boolean/String/BigDecimal/BigInteger/不可变 Map/List，复用 M1 的严格转换；禁止 double 中转和共享可变 Map。编排器负责合并与最终脱敏，不让 HTTP worker 修改请求快照。

HTTP/Mock transport 接收经过 EE 解析的只读配置值，不读取 JDBC/配置中心。SecretResolver 实现由宿主注入；Core 不依赖 Spring、Jackson 或任何 Secret 类。M2 不顺带迁移旧 Core 或实现 ADR-0001/0004 尚未落地的全套插件/决策包体系。

## 3. requiredFacts：静态依赖，不是全局必填表

1. 对已通过现有编译校验的文档遍历 Stage.when、Operator.condition、RuleSet.rules、Scorecard.characteristics/bins、DecisionTable.rules 的 Condition。递归 all/any/not。Terminal.scoreRef 是变量引用，不是 FACT；所有 VARIABLE/NODE 均排除。
2. 保留原文 JSON Pointer 与稳定 stageId/nodeId/itemId；Stage.when 的 nodeId/itemId 为 null。分箱使用 binId，规则/表行使用 rowId；路径由原数组索引产生，不能用排序后索引。每次出现都保留，按原文遍历顺序排列。
3. requiredFacts 按 RFC 6901 路径去重，路径按 Unicode code point 顺序输出；解码 token 后匹配，不使用点路径。根 `""` 和数组读路径仍可成为依赖，不能注册为本批 Catalog 写目标。
4. 每处 `expectedTypes` 是提示集合。eq/ne 的非 null 标量右值提示对应类型；gt/gte/lt/lte 按右值 number/string 提示；between=number。in/not-in 的非空右值数组若为同一种非null标量类型，提示该类型；混合类型、空数组或含null时为 []。starts-with/ends-with=string；contains/not-contains 右值string时为[array,string]，其他右值为[array]。presence及无法安全推断的情况为 []，表示未知。所有类型数组按名称升序输出。
5. 聚合 expectedTypes 为各非空提示的并集；若至少两处非空集合交集为空，`typeConflict=true`。它只用于诊断，不拒绝既有 Flow，不用全局类型检查取代短路/节点实际求值。未来实现可提高提示精度，但不能改变运行语义。
6. presence 引用也收集。显式选择补数后，presence-only 路径如有绑定且缺失也补数；因此 is-missing 的观察值可能变化，这是新入口的显式语义。M1 原入口绝不补数。
7. 未配置 Catalog/Provider 的依赖保持 missing；错误在实际使用时按原节点策略处理。即使依赖所在分支最终未运行，也可能预取：首批按静态全集补数，不实现路径预测或逐节点懒加载。
8. `/buyer/id` 是 Provider 请求入参，不会伪装成规则 requiredFacts；从 Provider requestMap 单独检查。输入目录登记不等于规则必须引用。

`GET .../required-facts` 只对已保存且可执行定义返回结果；不完整草稿按 M1 编译错误返回 400。响应绑定 flow/version/revision/checksum。目录是否存在不影响发布；配置关联诊断在 M2 管理/补数入口进行。

## 4. 一次请求的解析算法

1. 验证请求和模式，建立 traceId 与单调时钟绝对 deadline；加载精确 Flow/校验 revision 或 PUBLISHED/checksum，编译计划。加载、编译、配置读取时间都消耗 M2 总预算。
2. 深度冻结 callerFacts。在一个短的 MySQL REPEATABLE_READ 只读事务中读取所需 Catalog 行及关联 Provider 行，形成一致不可变快照；事务结束后才开始外部 I/O。不锁住数据库等待 HTTP。
3. 对每个 requiredFacts 路径进行 presence lookup：已存在的值（包括 null、错误类型）保留为 resolved/CALLER，不读取 Provider 覆盖它。resolved 只表示值已取得，不表示节点类型校验通过。
4. 对缺失字段先查询绑定。无目录或无绑定为 missing/OR-FACT-UNBOUND。有绑定时检查写路径，父节点阻挡为 failed/OR-FACT-PATH-BLOCKED。这两种情况都不触发该字段的外部调用。根/数组等仅支持读取的依赖可以保持未绑定，不因其无法注册而拒绝整个Flow。
5. 可补字段按 providerId 分组；Provider 按 ID 排序串行执行，每组至多一次。全局上限约束不同请求的在途调用，无跨请求缓存/去重。已满足字段不进入 requestedPaths；全部满足时调用次数为0。
6. requestMap 的每个顶层目标键对应一个 CONSTANT 或 FACT 映射。FACT 只读取 callerFacts，不读取已补值、variables 或其他 Provider 结果；missing 或显式 null 入参令整组 OR-FACT-REQUEST-INPUT，HTTP 调用0次。常量允许 JSON 值（包括 null），严格保真。
7. 响应必须为对象 JSON；逐字段 responsePointer 读取并按 Catalog 的 number/string/boolean 严格验证。缺路径=missing/OR-FACT-RESPONSE-MISSING；null/类型错误=failed/OR-FACT-TYPE。成功字段加入独立补丁，其余保持原样；不整包失败、不用0或空串填充。非2xx、非法 JSON、连接失败为组失败。
8. 合并只创建缺失的中间 object。已有中间 object 可深入；已有 null/标量/数组不可替换。Catalog 写目标限制为非空、非数字、非危险名的 object token；不创建数组或写数组索引。prefix 重叠的 Catalog 字段在管理保存时拒绝，避免合并顺序改变含义。
9. 每次合并前检查 deadline/取消；迟到响应丢弃，终态只写一次。Core 接收最终不可变 facts 快照和相同绝对 deadline。字段失败不直接生成 decision，不预判 REVIEW。

Pointer 读遵循现有 RFC 6901（含 ~0/~1、根与数组读取）；Catalog 写 token 不允许空段、纯数字、`__proto__`、`constructor`、`prototype`。父节点是数组时整条写入失败，不能把它转换为对象。返回中的 extra 字段、未请求路径和已提供字段一律忽略。

## 5. 超时、取消与资源

- requestDeadline = M2 入口接收已解析且合法请求时的 monotonic start + timeoutMillis。上限复用宿主 M1 配置，不从样例推导生产 SLA。
- callDeadline = min(requestDeadline, callStart + readTimeoutMillis)。本契约 readTimeoutMillis 明确定义为**整次 Provider 响应预算，包含连接、响应头、完整响应体及解析**，不是每次 socket read 都重置的闲置超时。
- connectTimeoutMillis <= readTimeoutMillis；有效连接超时还须受 callDeadline 剩余时间约束。HTTP body 限制1 MiB，不能读完后才检查；请求映射体同上限。M1 数字/深度限制沿用严格 codec 边界。
- requestDeadline 优先：即便同时到达 Provider deadline，也返回 HTTP504 OR-EXECUTION-TIMEOUT、decision不存在/为null。仅 Provider 超时且请求尚有时间，则字段 failed/OR-FACT-TIMEOUT，进入原 Core；节点表现为数据缺失失败，不伪造节点 TIMED_OUT。
- 新增绝对 deadline 入口；不得调用旧 `execute(..., originalTimeout)` 重置预算，也不得向上取整剩余毫秒而延长 deadline。原 M1 入口保留既有计时起点与结果行为。
- Provider 全局在途上限默认64，宿主属性 `studio.facts.max-in-flight` 可调；tryAcquire 无容量即503 OR-FACT-OVERLOADED，不排入无界等待队列、不转 REVIEW。首批单请求 Provider 串行，无单请求扇出池。
- HTTP 参考实现采用 JDK21 HttpClient POST JSON、禁止重试/重定向；以调度器/取消 Future 限制完整 body 读取。客户端或线程的取消不等于对端停止，迟到值不得合并。取消恢复中断位并产生 OR-EXECUTION-CANCELLED，无业务决策。
- 在途许可在底层任务真正结束/资源释放后归还；对不响应取消的任务不能提前释放许可造成无限在途。宿主管理 executor/调度器生命周期。记录有限错误摘要，不持续轮询。

## 6. HTTP、Mock 与 secretRef

Provider document 的 type=HTTP 或 MOCK；两者都有 requestMap、enabled、Mock 配置。HTTP 额外有 endpoint、connect/read timeout、secretRef、allowSimulationHttp、readOnly=true。首版仅查询型 POST，不支持自定义脚本、重试、重定向、任意方法或动态 URL。

| 执行入口/选择 | HTTP Provider | MOCK Provider |
| --- | --- | --- |
| 原 M1 | 不查询目录、不调用 | 不查询目录、不调用 |
| M2 LIVE / HTTP | 真实只读 HTTP | 字段失败 OR-FACT-MOCK-FORBIDDEN，不调用 |
| M2 SIMULATE / HTTP | 仅 enabled 且 allowSimulationHttp=true；否则 OR-FACT-SIMULATION-DENIED | OR-FACT-MOCK-FORBIDDEN |
| M2 SIMULATE / MOCK | 使用此配置快照中的 mock；不解析 secret、不访问网络 | 使用 mock |

停用 Provider 在所有模式下失败 OR-FACT-DISABLED，不用 Mock 绕过停用。模式限制是字段组失败；总预算、取消、过载才是请求级立即失败。LIVE 请求 `factMode=MOCK` 是400请求错误，未开始补数。

Mock 为 `RESPONSE + response对象`、`TIMEOUT + response=null` 或 `ERROR + response=null`。partial 用实际删除响应字段表达，不用“第一个字段缺失”的隐式规则。TIMEOUT 直接产生结构化模拟失败，真实 elapsed 不能冒充配置等待时长；真实耗时/取消由受控 HTTP fixture 单独验证。请求映射检查与响应类型校验仍完整执行。

secretRef 首版为 null 或 `env:OPENRULE_<NAME>`；宿主 SecretResolver 按白名单前缀读取环境变量，组装固定 `Authorization: Bearer <value>`。不引入 Vault，不允许配置明文 token/任意 header。缺失或空凭证=OR-FACT-SECRET；Mock 不解析凭证。管理 API 仅返回引用，不返回解析值；错误/日志不带 URL query、认证头、原始请求/响应体。endpoint 不允许 userinfo、fragment 或 query；HTTP(S) 可用，受控本地 HTTP 用于验收。

## 7. Catalog / Provider 生命周期和存储

两个逻辑表在 Studio 宿主 fact JDBC 包拥有，DDL 在后续 B2 的宿主 resources 下增加；当前 M1 schema.sql/表和加载方式不改。

| 表 | 最小列与约束 |
| --- | --- |
| `or_studio_fact_provider` | provider_id PK VARCHAR(64), revision BIGINT>0, document_json MEDIUMTEXT, updated_at DATETIME(3)；文档保留精确 JSON，含启停、Mock和secretRef，无明文凭证 |
| `or_studio_fact_catalog` | fact_id PK VARCHAR(64), path VARCHAR(512) UNIQUE（binary collation）, provider_id nullable FK→provider, revision BIGINT>0, document_json MEDIUMTEXT, updated_at；冗余path/provider与文档同事务写入 |

配置 ID 由客户端一次生成，创建时指定，重复返回409；创建 revision="1"。PUT 携带 expectedRevision 和完整 document，WHERE id=? AND revision=? 原子更新并 revision+1；失败409不覆盖本地编辑。revision 超出 BIGINT 拒绝，不回绕。

Fact 的 factId/path/type 创建后不可变；Provider 的 providerId/type 不可变。允许改名称/说明/敏感标记/绑定/响应Pointer及 Provider 配置。绑定 Provider 必须存在，停用可绑定但执行会失败。字段只能有一个 binding，null表示由调用方提供。Catalog 路径唯一且不能互为严格 token 前缀；这是本批 primitive/object-leaf 范围，不是所有 Flow FACT 引用的限制。

首批管理 API **不提供删除**：原型删除按钮隐藏，停用 Provider 使用已有 CAS 更新。这样无需为删除扫描所有不可变 Flow 或引入引用索引；已有文档和路径不会悬空。请求映射 FACT 不要求先登记 Catalog，运行期只读取原始请求；配置存在循环命名也不会产生解析依赖链。

创建Catalog时，服务在SERIALIZABLE短事务中通过path唯一索引执行全范围锁定读，检查相同路径和token前缀，再插入。并发死锁或锁等待超时返回409 OR-FACT-CONFLICT，不自动重试。path/type不可变，因此更新不引入新路径。B2必须用双连接测试覆盖空表和已有表，证明互斥路径不能同时创建成功。

每次执行记录所用 factId/catalogRevision 和 providerId/providerRevision。配置只保存当前 revision，无配置历史/激活/回滚；同一 Flow 版本可能因调用方 facts、上游响应或配置变化得到不同结果，这是外部输入变化，不改变 Definition checksum，也不承诺历史重放。读快照后发生的更新仅影响下一次请求。

## 8. HTTP 契约与兼容

具体字段由 [OpenAPI](../contracts/studio-m2/openapi.json) 和 [Schema](../contracts/studio-m2/schema/m2.schema.json) 定义。

| 新路径 | 行为 |
| --- | --- |
| `/api/v2/admin/facts`、`/{factId}` | GET分页/单项、POST创建、PUT CAS |
| `/api/v2/admin/fact-providers`、`/{providerId}` | GET分页/单项、POST创建、PUT CAS |
| `/api/v2/admin/fact-providers/{providerId}/mock-tests` | 对已保存的精确 Provider revision 与当前绑定字段做服务端 Mock 映射测试；无 Core/真实 HTTP |
| `/api/v2/admin/flows/{flowId}/versions/{version}/required-facts` | 已保存可执行定义的静态依赖 |
| `/api/v2/admin/flows/{flowId}/versions/{version}/fact-simulations` | 显式补数模拟；必填 expectedRevision、factMode=HTTP/MOCK |
| `/api/v2/flows/{flowId}/versions/{version}/fact-executions` | 精确 PUBLISHED 执行；factMode只能HTTP |

M1原simulations/executions路径、允许字段、响应、OA/ST和Definition均不变。选择新路径是唯一补数开关。目录新增字段不会改变旧请求。

新成功响应为 `{execution, factResolution, totalElapsedMillis}`，其中execution复用M1 ExecutionResult。新错误体沿用M1 code/message/requestId/traceId/issues/execution，并增加可为空的factResolution和totalElapsedMillis。不向封闭的M1 DTO添加字段。

进入补数后Core尚未开始就失败：execution=null，factResolution包含已完成字段/调用及请求级失败，不伪造节点结果。已进入Core后失败：附真正的FAILED execution，decision=null。前后结构中的requestId/traceId必须相同。

factResolution.elapsedMillis记录配置快照就绪至解析终止的耗时。execution.elapsedMillis记录Core耗时。totalElapsedMillis记录M2入口至响应投影完成的总耗时，包含加载、配置读取和编译。不得用两个阶段耗时之和替代总计时。

字段 status 为 resolved/missing/failed；source 为 CALLER/PROVIDER/NONE。CALLER/resolved 可为显式null或错误类型，后续节点仍可能失败。每个 requiredFact 恰一条记录；Call只列 requestedPaths/配置revision/transport/attempted/status/elapsed/error，不复制原值。全部字段resolved=COMPLETE；部分resolved=PARTIAL；无resolved=UNRESOLVED；请求级失败=FAILED。字段错误可与 HTTP200、DECIDED/REVIEW 同时存在。

默认仅返回状态与脱敏 valueSummary：敏感或未登记字段为 `[REDACTED]`，登记为非敏感的标量按精确 JSON 文本展示（最多128字符）；错误状态不含原值。M2 execution 的 variables、node outputs、RuleDetail.value 也采用保守全值摘要屏蔽，保留字段键但值用 `[REDACTED]`；ScoreDetail.score 和最终 score 为规则结果保留。所有新入口一致执行投影，防止 facts经自定义输出泄露。M1旧响应语义不变。

## 9. 前端交互

- 增加“事实字段”“数据源”页面，沿用原型216px侧栏、色彩/表格/卡片/弹窗；不移植 React。
- 事实编辑含路径、名称、number/string/boolean、敏感标记、来源和响应Pointer；唯一性/不可变字段由服务端确认，冲突保持输入。
- Provider 表单只显示 HTTP/MOCK；HTTP 有 endpoint、timeouts、secretRef、显式允许真实模拟开关；Mock 编辑 response JSON/模拟结果。保存/重新加载必须保持业务数字原文。
- “测试映射”先保存 Provider，调用 mock-tests 携带实际 revision；只模拟已绑定字段，未保存状态禁用。预览不偷偷保存/发真实请求。空绑定返回空COMPLETE报告。
- 试运行选择“仅使用输入（默认）/真实补数/Mock补数”。默认调用原 M1；另外两项调用 fact-simulations。真实补数显示将访问已允许的查询数据源；LIVE无Mock选项。
- 原结果页新增“事实解析”，按字段显示来源、状态、错误、脱敏摘要；按 Provider 显示耗时/HTTP或Mock/实际是否发起调用/配置revision。无历史Trace页。
- 结果绑定规则revision、输入、模式及实际配置revision。当前页面修改配置/输入/模式后标旧结果；外部配置不轮询，下一次执行以服务端快照为准。晚到响应不得覆盖新结果。
- 保存响应未知时以稳定ID GET核对 revision+document；不能自动重发POST/PUT。分页参数延续M1 page/pageSize，无前端全量假分页。

## 10. 验收与待确认项

验收用例和层级见 [ACCEPTANCE](../contracts/studio-m2/ACCEPTANCE.md)。本轮只验证 Schema/OpenAPI/fixture 静态一致性；HTTP、数据库、Java补数、浏览器行为留给 B1/B2/F1/V1。

本批没有阻碍设计交付的业务待确认项。只读画像服务的真实 URL、鉴权变量、目标 QPS/p95/p99、生产超时和生产订单政策在部署前确认；本批使用受控本地服务和示例配置，数值不是 SLA。历史Trace保留周期及审计写失败策略属于M3，不提前创建审计存储。
