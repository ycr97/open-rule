# Studio M2-D0 契约入口

> 2026-10-28：当前实际工作区的运行结论见 [M2-V1总体验收](V1-ACCEPTANCE.md)。下文D0状态和矩阵保留为原始阶段背景，冻结fixture和预期不变。

状态：M2-V1总体验收已通过，见上方报告。以下保留D0设计与静态契约入口说明。2026-10-18 完成交付核对。[ADR-0006](../../adr/0006-studio-m2-fact-resolution.md) 为 Proposed。[设计](../../design/2026-10-04-openrule-studio-m2-facts.md) 定义算法和边界；[计划](../../plans/2026-10-04-openrule-studio-m2-delivery.md) 划分 B1/B2/F1/V1；[验收矩阵](ACCEPTANCE.md) 定义后续运行证据。

## 1. 资产与使用方式

| 资产 | 内容 |
| --- | --- |
| [openapi.json](openapi.json) | 8条新增路径、12个操作；不重复或覆盖M1路径 |
| [schema/m2.schema.json](schema/m2.schema.json) | 31个 `$defs`；按具体定义验证，根不是请求模型 |
| [manifest.json](manifest.json) | 每个 Schema fixture 的目标类型及是否应通过 |
| [fixtures/order-admission.cases.json](fixtures/order-admission.cases.json) | MF-01～30的输入、受控上游、预期投影 |
| [fixtures/semantic.cases.json](fixtures/semantic.cases.json) | MS-01～08：结构合法但业务校验应拒绝 |
| [fixtures/catalog.json](fixtures/catalog.json) | HTTP订单用例目录快照 |
| [fixtures/catalog-mock.json](fixtures/catalog-mock.json) | 同字段绑定MOCK配置的隔离用例 |
| [fixtures/valid/order-admission.required-facts.json](fixtures/valid/order-admission.required-facts.json) | M1定义的5个事实路径、10处原文引用 |
| [validate.py](validate.py)、[checks/SchemaCheck.java](checks/SchemaCheck.java) | 离线文档校验；复用仓库既有 JSON Schema 验证器，不实现业务运行时 |

Schema 的 M1 ExecutionResult/ValidationIssue 使用相对 `$ref` 指向冻结的 M1 OpenAPI。不能复制并修改其枚举。`SCORE_BIN`、版本字符串、score十进制字符串、Issue位置等沿用M1。

### 运行静态校验

仓库根目录执行，需 Python3、JDK21、Maven和已缓存的M1校验依赖：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
python3 -u docs/contracts/studio-m2/validate.py
```

其他环境设置自己的 JAVA_HOME。校验离线运行；编译产物和展平 Schema 写临时目录并自动清理。检查 OpenAPI 结构/本地引用、Schema编译及样例、fixture文件引用、原文使用位置和M1 checksum。它不证明 Provider、Core、JDBC或浏览器已运行。实际结果见 [VALIDATION.md](VALIDATION.md)。

## 2. 新执行入口

- 原 M1 请求继续调用原 simulations/executions，不读目录、不补数。
- 新 fact-simulations 必填 `factMode=HTTP|MOCK` 与 `expectedRevision`。只模拟已保存的精确revision。
- 新 fact-executions 必填 `factMode=HTTP`，只执行指定PUBLISHED版本。LIVE+MOCK为400。
- 成功体为 `{execution, factResolution, totalElapsedMillis}`。execution仍符合M1 DTO。
- 错误体在M1错误字段外增加 `factResolution` 与 `totalElapsedMillis`。未进入Core时execution=null；进入后附实际FAILED结果。技术失败无业务decision。
- `traceId` 由服务端在外部调用前创建。所有Call及execution使用同一ID。管理错误无执行时factResolution=null。

示例请求见 `fixtures/valid/simulate-http.request.json`。字段齐全但存在null/错误类型时，解析状态仍是resolved/CALLER；节点按原行为校验。调用方未提供的字段才参与Provider分组。

`mock-tests` 读取已保存的Provider revision和当前绑定字段一致快照。若其中某字段已由测试facts提供，则保留caller值。没有绑定字段时返回COMPLETE空报告。此接口不执行Core，允许HTTP配置的Mock部分，但不读secret、不发HTTP；停用配置仍失败。

## 3. 配置和并发

所有资源包含 `document`、字符串 `revision`、`updatedAt`。创建提交document，成功revision为"1"。PUT提交 `{expectedRevision,document}`。ID/字段path/type或Provider type不能修改。路径ID与文档ID必须相等。

创建重复ID或字段path返回409 OR-FACT-CONFLICT；CAS失败返回409 OR-REVISION-CONFLICT；不可变字段变更返回409 OR-FACT-IDENTITY。引用不存在、路径前缀冲突和非法配置返回400 OR-FACT-CONFIG。所有语义错误使用 M1 ValidationIssue 结构，pointer 指向请求实际字段。配置问题的 stageId/nodeId/itemId 为null；规则依赖问题保留对应稳定ID。

版本/revision必须不超过Java有符号BIGINT。path唯一性按解码后的Pointer token比较，禁止prefix重叠。原始文本采用合法RFC6901编码，不进行Unicode大小写折叠或归一化。名字和说明可修改。默认新增字段的前端敏感标记为true；Schema要求显式传值。

列表支持q、page、pageSize；q匹配ID/名称，事实目录另匹配path。事实source=CALLER表示binding=null，PROVIDER表示有binding。q按字面量匹配，SQL通配符必须转义。按factId/providerId升序稳定分页。

没有DELETE API。停用走Provider PUT CAS；状态变化不会修改已发布Definition。配置更新立即影响后续显式补数请求。本批没有配置发布、激活、历史或回滚。

## 4. 字段、调用和错误

每条规则requiredFact在fields中出现一次，按路径排序。Provider请求入参不是额外规则依赖，不单独加入fields。mock-tests则按所绑定字段生成fields。

| 情况 | Field status/source | code | 是否发起对应调用 |
| --- | --- | --- | --- |
| 原输入存在，含null/错误类型 | resolved/CALLER | null | 否 |
| 无目录/无绑定 | missing/NONE | OR-FACT-UNBOUND | 否 |
| 父路径不是对象 | failed/NONE | OR-FACT-PATH-BLOCKED | 否 |
| Provider停用 | failed/PROVIDER | OR-FACT-DISABLED | 否 |
| LIVE/HTTP选择绑定MOCK类型 | failed/PROVIDER | OR-FACT-MOCK-FORBIDDEN | 否 |
| 真实模拟未允许 | failed/PROVIDER | OR-FACT-SIMULATION-DENIED | 否 |
| 映射FACT入参missing或null | failed/PROVIDER | OR-FACT-REQUEST-INPUT | 否 |
| secret未配置或不可解析 | failed/PROVIDER | OR-FACT-SECRET | 否 |
| Provider级超时 | failed/PROVIDER | OR-FACT-TIMEOUT | HTTP或Mock已执行 |
| 非2xx、连接失败 | failed/PROVIDER | OR-FACT-HTTP | HTTP |
| 非法/重复键JSON、body超限 | failed/PROVIDER | OR-FACT-RESPONSE | HTTP |
| Mock ERROR | failed/PROVIDER | OR-FACT-MOCK | Mock，无HTTP |
| 响应路径不存在 | missing/PROVIDER | OR-FACT-RESPONSE-MISSING | 已执行 |
| 响应null/类型错误 | failed/PROVIDER | OR-FACT-TYPE | 已执行 |
| 成功取值并合并 | resolved/PROVIDER | null | 已执行 |

source表示值或失败责任来源，不表示一定发起了网络请求。CALLER/NONE的providerId/providerRevision为null；PROVIDER携带配置ID/revision。未登记路径的factId/catalogRevision为null。所有已登记字段都记录读取到的catalogRevision。

Call.attempted 表示适配器是否开始执行：HTTP真实发送进入客户端后为true；Mock函数被调用也为true。映射、启停、模式、secret预检失败为false。Calls按providerId排序，requestedPaths按路径排序。全部caller值满足时calls为空。

Call.status：预检失败SKIPPED；整组resolved为SUCCEEDED；有成功也有失败/缺失为PARTIAL；零成功为FAILED；Provider deadline为TIMED_OUT；请求取消为CANCELLED。字段层失败已列在fields时，SUCCEEDED/PARTIAL的call.error为null；整组失败使用组错误。Mock TIMEOUT为TIMED_OUT，但elapsedMillis必须记录实际耗时，不伪造等待。

请求级错误：504 OR-EXECUTION-TIMEOUT；503 OR-FACT-OVERLOADED；500 OR-EXECUTION-CANCELLED；配置存储不可用为503 OR-FACT-STORE。不能转成业务REVIEW。已经完成的字段不被请求错误改写；未完成字段以同一请求错误终止。报告status=FAILED。请求预算耗尽的Call为TIMED_OUT，取消的Call为CANCELLED。尚未开始的组不发起调用。

其余报告status：全部resolved为COMPLETE（包含空集合）；部分resolved为PARTIAL；无resolved为UNRESOLVED。普通字段失败可与HTTP200/DECIDED并存；最终业务决策仍只由Terminal生成。

## 5. 数值、脱敏与顺序

沿用M1 JSON number/BigDecimal语义。requestMap常量、Mock响应和HTTP响应不经过JS Number或double。请求/响应JSON拒绝重复键；数字沿用M1的128有效数字、指数绝对值1000限制。保留对象/数组与null/missing区别。

字段摘要：敏感或未登记值为 `[REDACTED]`；非敏感标量为精确JSON文本。128字符以上的摘要只保留前125个Unicode code point并追加`...`，摘要不是可回传原始值。对象/数组统一屏蔽。错误状态valueSummary=null，不回显错误原值。

M2 execution的variables、node outputs、RuleDetail.value保守替换为`[REDACTED]`，保留键和明细ID；最终score与ScoreDetail.score仍按M1十进制字符串返回。内部Core仍使用真实值。本期不实现字段血缘或可配置脱敏引擎。前端不要把屏蔽值当作事实重新执行。

## 6. Fixture 规则

1. 行为文件中的所有File路径相对 `fixtures/order-admission.cases.json` 解析。
2. 每个用例从指定目录和Provider快照独立开始；不继承上一用例状态。
3. `providerPatch` 是对象递归覆盖，null必须保留；数组和标量整体替换。它不是JSON Merge Patch。不得将TIMEOUT配置的response=null删掉。
4. `transport.outcome` 是受控测试服务行为：RESPONSE、RAW_RESPONSE、TIMEOUT、CONNECT_ERROR。MOCK用例忽略transport，读取配置mock。
5. expected为断言投影，不是完整响应。status/decisionCode/score/terminalNodeId来自execution；segment是**内部Core变量**断言；preserved/requestedPaths在解析器单测核对。真实HTTP验收不能为了检查segment绕过脱敏。
6. MF-09 使用延迟响应覆盖请求总预算；不能凭机器偶然慢推断超时。MF-18 复用M1的类型错误ABORT，不改变规则。
7. `fixtures/valid/ms-*.semantic.json` 的valid仅表示Schema合法；MS业务校验应拒绝。D0不把语义预期记成服务端执行通过。
8. M1定义通过文件引用复用；不复制后改阈值、不改Description/version/checksum。验收隔离Flow身份时记录新身份及服务端摘要，不能把原fixture摘要直接套到改名资源。

## 7. 边界

没有生产SLA、真实上游地址或生产规则批准。本批受控本地服务足够验证合同。真实部署参数留在实施交接中确认，不阻碍D0交付。后续只有B1/B2/F1/V1可产生运行验收结论。
