# OpenRule Studio：前后端契约与开发顺序

> 日期：2026-09-15
> 状态：实施顺序设计稿；D0 目标契约已产出，ADR-0005 为 Proposed；不是当前 API 文档或已批准的发布计划。
> 范围：[Studio 首期范围与验收清单](../design/2026-09-15-openrule-studio-m1-scope.md)中的订单准入。
> 用户已确认 Vue + TypeScript、尽量还原原型、真实闭环优先及订单准入用例。D0 细节以 [契约入口](../contracts/studio-m1/README.md) 和 [ADR-0005](../adr/0005-studio-m1-flow-contract.md) 为准；本文不改写现有 Accepted ADR。

## 1. 工程与职责

建议前端工程名为 openrule-studio，独立构建和发布；Git 仓库归属在建项时落实，本次不创建仓库。结构保持一个应用：

~~~text
src/
  app/                    路由、工作台布局、应用启动
  components/ui/          shadcn-vue 基础组件
  features/flows/         列表、版本与发布操作
  features/editor/        草稿、Stage、条件与五类节点编辑器
  features/simulation/    样例、输入和执行明细
  api/                    HTTP、DTO、错误与数值序列化适配
  styles/                 原型主题和业务样式
~~~

业务组件经 api/ 访问服务端；编辑状态由页面级 composable 管理，Pinia 只承接必要共享状态。远程查询结果、当前编辑草稿和临时 UI 状态分别管理。前端不复制 Java 决策执行器。

Flow 校验、执行、生命周期的通用能力位于 OSS；Studio 的可视化以及 Scene/Provider 管理属于 EE。后端建议使用一个最小 EE Spring Boot 宿主装配公开 API/JDBC 能力，M2/M3 在同一应用中增加业务包，不复制 Core Runtime。当前仓库主代码未提供可直接启动的 Spring Boot 应用，启动宿主和数据库迁移需列入任务。

## 2. D0：实施前必须收敛的契约差异

| 决策 | 现状与差异 | 本次建议、影响范围 |
| --- | --- | --- |
| D0-1 控制流 | ADR-0001/0002 与 M1.6 设计仍含 decisionOnHit、stopOnHit、聚合器，以及 REVIEW/REJECT FailPolicy；蓝图改为 Stage when + Terminal | 新 authoring 路径采用蓝图；扩充 NODE 结果引用，技术策略收敛 ABORT/CONTINUE；以 superseding ADR 明确替代条款和节点/Stage 结果 |
| D0-2 草稿与发布 | 当前 save 新建版本并启用；旧路线中还有“不可变草稿”描述 | 一个 Flow 同时最多一个可编辑草稿；保存只递增 revision；发布冻结该 version；创建下一草稿才分配新 version |
| D0-3 Definition 与数值 | ADR 已描述 schema v2，实际代码仍是旧枚举与 operatorDef；新模型还需 when、Terminal 和 NODE | 沿用开放 type/configVersion/config；schema 编号、五类配置、Condition AST 与迁移明确后，才输出 OpenAPI/JSON Schema。不能直接把原型 schema=1 或 Node 类型当协议 |
| D0-4 制品身份 | ADR-0004 定义 packageId；新版蓝图先以 flow/version/checksum 发布 | M1 只需要服务端 Definition 摘要，不能冒充 package checksum。M3 Scene 绑定方式及包能力延期需明确 ADR；M1 的数值规范化/checksum 必须先有一致规则 |

D0 交付一份最小补充/替代 ADR、正式 Schema/OpenAPI、合法和非法 fixture，以及旧数据兼容方案。只覆盖本条闭环需要的决定；D0-4 的 Scene 部分可以到 M3 前完成。

旧持久化 v1 必须仍可读取，先校验旧行原始 checksum；按旧语义隔离执行，不能改写历史行。旧聚合逻辑无法证明等价时，不得自动“转换成 Terminal”；未来显式迁移须创建新 v2 版本并用黄金样例对照。部署宿主的旧引擎维护承诺仍是 ADR-0005 的待决问题，不能因为新建 v2 HTTP 路径就认为数据迁移问题已经解决。

M1.6 已批准但未落地的类型化配置、不可变值、严格比较、快照和 Deadline 是依赖项。实施时对照更新后的 ADR，不能先全量照搬含旧控制流的 M1.6 计划再返工。

## 3. 草稿、版本与运行定义

### 3.1 三类状态

| 对象 | 职责 |
| --- | --- |
| DraftDocument | 可保存的编辑文档。结构合法、ID/类型可识别；业务字段允许未填，完整性错误由校验报告指出 |
| ExecutableDefinition | DraftDocument 通过完整校验后生成，绑定明确 schema、flowId/version，供编译和执行 |
| EditorState | 选中节点、展开项、弹窗、未保存标记、输入文本等客户端状态，不写入 Definition |

DraftDocument 不是任意 JSON：未知字段、非法类型、重复身份和无法解析的结构仍拒绝。未填写的业务值不能由服务端静默补成可执行规则。M1 的 v2 Schema 只接受五类已声明节点；旧 v1 定义通过隔离读取路径只读展示原文，不能用 M1 编辑器保存时丢掉未知配置。

草稿保存返回 revision 和校验报告；完整校验失败仍可保存结构合法的草稿。校验、发布和模拟使用同一套服务端规则；前端只提前检查输入。

### 3.2 生命周期提案

~~~text
新建 Flow → v1 DRAFT / revision=1
保存草稿 → version 不变，revision+1
发布草稿 → 同一 version PUBLISHED，revision+1，记录 checksum 与发布说明
基于发布版本新建草稿 → 分配下一个 version / revision=1
~~~

- 服务端为 version/revision 唯一分配方。接口以正十进制字符串传输它们，避免 Java long 在浏览器中失真；界面显示 v4 等正常标签。
- 文档身份以目标资源的 flowId/version 为准；提交文档若携带不一致身份则拒绝。DRAFT 资源的发布 checksum 为 null；模拟成功校验后返回本次 ExecutableDefinition 的摘要，PUBLISHED 资源返回冻结摘要。
- 保存和发布必须携带 expectedRevision。以状态及 revision 作为原子更新条件；冲突返回 409，前端保留编辑内容。
- 发布在事务中重新检查 revision、完整校验、冻结内容并写入发布状态；不能只相信前端之前的校验结果。
- 已发布内容不可更新，发布不设置 Flow active，也不激活 Scene。新接口不提供隐式启用动作。
- 建议使用 Flow head 串行分配版本、管理当前草稿；版本表对 flowId/version 建唯一约束。建 Flow 时依赖唯一键消除首次并发竞争，分配版本不使用无锁 MAX(version)+1。
- 不要求首期多分支草稿或自动合并。已有草稿时新建草稿返回 409 和已有草稿身份。
- 写入失败或响应丢失不自动重试：先读取资源状态并保留本地内容。requestId 仅关联，不默认提供幂等。发布重试不得重复创建版本或重复写发布记录。

## 4. M1 API 提案

使用 /api/v2 隔离与旧管理接口的行为差异；正式路径在 D0 固化。v1 enable/rollback 不能修改新生命周期资源。下表为目标能力，当前均未按此协议实现。

| 方法与路径 | 请求/响应与行为 |
| --- | --- |
| GET /api/v2/admin/flows | q、page、pageSize；返回每个 Flow 的摘要、当前草稿和最新发布版本 |
| POST /api/v2/admin/flows | flowId、flowName；创建包含默认 Terminal 的结构合法初始草稿；201 返回资源 |
| GET /api/v2/admin/flows/{flowId}/versions | 分页版本摘要，按 version 降序；返回状态和时间 |
| GET /api/v2/admin/flows/{flowId}/versions/{version} | 返回完整编辑文档、status、revision、checksum、validation |
| POST /api/v2/admin/flows/{flowId}/drafts | sourceVersion；从已发布版本建立新草稿；201 返回资源 |
| PUT /api/v2/admin/flows/{flowId}/versions/{version} | expectedRevision、document；只保存 DRAFT，200 返回新 revision 和 validation |
| POST /api/v2/admin/flows/{flowId}/versions/{version}/validations | document；校验当前编辑快照，200 返回报告，不保存或编译缓存 |
| POST /api/v2/admin/flows/{flowId}/versions/{version}/simulations | expectedRevision、requestId、bizId、facts、timeoutMillis；对已保存 DRAFT/PUBLISHED 快照执行 SIMULATE |
| POST /api/v2/admin/flows/{flowId}/versions/{version}/publish | expectedRevision、changeNote；必填发布说明；200 返回冻结资源 |
| POST /api/v2/flows/{flowId}/versions/{version}/executions | requestId、bizId、facts、timeoutMillis；服务端固定 LIVE，只允许精确 PUBLISHED 版本 |

分页建议从 page=1 开始，pageSize 默认 20、上限 100；返回 items/page/pageSize/total。非法分页参数返回 400。M1 不实现全站搜索和历史 Trace API。

M1 试运行要求先保存，提供明确的“保存并试运行”操作。保存成功才提交该 revision 的执行请求，保存失败不执行。服务端校验 expectedRevision 后读取不可变快照；执行期间的新保存不会改变本次输入。用户查看已发布版本时可直接 SIMULATE。

requestId、bizId 非空且 ≤ 128 字符；timeoutMillis 为正整数并受服务端配置上限约束。接口固定返回裸 DTO，宿主的统一响应包装需在该 API 边界明确配置，不能让前端猜测多种响应形态。

### 4.1 校验报告与错误定位

~~~json
{
  "valid": false,
  "issues": [
    {
      "code": "OR-DEF-VALIDATION",
      "severity": "ERROR",
      "message": "评分分箱缺少分值",
      "stageId": "s_score",
      "nodeId": "buyer_score",
      "itemId": "b3",
      "pointer": "/stages/2/nodes/0/config/characteristics/1/bins/0/score"
    }
  ]
}
~~~

pointer 为相对于提交 document 的 JSON Pointer；稳定 ID 用于节点/规则调序后的定位。以上配置字段名为提案，D0 确认后与 Schema 一致。异步校验响应必须绑定提交时的编辑快照，已过时的响应不得应用到新文档。

400 表示请求/文档结构非法；404 表示资源不存在；409 表示 revision 或生命周期冲突。显式校验接口对业务校验未通过返回 200/valid=false；发布和执行遇到无效定义返回 400，并携带同样的 issues。

### 4.2 执行结果

正常响应必须包含 requestId、traceId、bizId、purpose、flow 身份、status、decision、nodeResults、elapsedMillis。flow 身份含 flowId/version/revision/definitionChecksum；未发布的模拟也标明具体 revision，不能宣称它是不可变发布身份。

decision 的最小形态：

~~~json
{
  "decisionCode": "REVIEW",
  "reasonCodes": ["HIGH_RISK_SCORE"],
  "score": "75",
  "outputs": {},
  "terminalNodeId": "risk_review"
}
~~~

响应另带执行变量摘要，展示 order_segment 等中间输出；不能因为示例 Terminal 没配置 outputs 而丢失节点输出。敏感输出由服务端脱敏。

- Flow status 使用 DECIDED/FAILED；技术失败时 decision=null。
- 节点状态建议为 SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/SKIPPED；SKIPPED 另带 skipReason，区分条件不满足和先前 Terminal 停止。API 映射不覆盖 Core 的原始失败码，Core 新状态在 D0 明确。
- nodeResults 带 stageId/nodeId/type、status、hit、outputs、类型化 details、failure 和 elapsedMillis。按定义顺序返回，不能按并行任务完成时间排列。
- 评分卡明细包含 characteristicId、binId、score 和原因；规则集/决策表包含命中 ruleId 与输出。
- 普通节点失败经 CONTINUE 后到达 Terminal，HTTP 200/DECIDED，同时保留失败节点。
- 遵循现有失败边界：请求总超时为 504、引擎过载为 503、ABORT/引擎故障为 500；错误体含 code/message/requestId/traceId。若已经开始执行，可带 execution 明细且 status=FAILED/decision=null，前端在非 2xx 下也能显示已有节点结果。
- 尚未执行的校验失败不伪造 execution。服务端和前端都不自动重试执行请求。

### 4.3 数值、定义和摘要

score 与评分明细以十进制字符串返回，前端按文本格式化，计算由 Java BigDecimal 完成。Definition 中的数字和 facts 数字仍是数字，不能全部转为字符串破坏严格类型语义。

前端 api/ 需提供统一的保留精度解析/序列化边界：JSON 输入原始数字字面量与编辑器的十进制文本不能经过有损 Number 往返。D0 用 0.1+0.2、长小数和超出安全整数范围的 fixture 验证方案，然后选择最小适配实现；不在页面各自处理数值协议。

canonical Definition 的覆盖字段、数值规范化和 schema 版本必须在 D0 固化。status/revision/updatedAt/发布说明/UI 状态不进入可执行内容摘要；流程身份、执行配置属于明确覆盖范围。摘要由服务端计算和校验，前端不复用原型的 hash 实现，不把 Definition checksum 称为 packageId。

## 5. 五类节点与共同模型

| 对象 | 需要冻结的内容 |
| --- | --- |
| Node | 稳定 nodeId、开放 type、configVersion、单一类型化 config、顺序与 timeout；保留 ADR-0001 的扩展方向 |
| Condition | all/any/not、compare、is-present/is-missing；FACT/VARIABLE/NODE 引用、JSON Pointer 和严格类型；all/any 按定义顺序短路，not 恰好一个子项 |
| OPERATOR | condition、命中输出与原因；不直接结束 Flow |
| RuleSet | 有序规则、FIRST_MATCH/ALL_MATCH；明确命中结果及输出冲突规则，不能依赖集合遍历顺序 |
| Scorecard | characteristic/bin、十进制分值、每维恰好命中一箱、分值明细 |
| DecisionTable | 有序条件行及输出，FIRST 命中语义；订单用例为单个 segment 输出 |
| Terminal | 开放 decisionCode、reasonCodes、score 引用、outputs；只在串行末尾，必须有无条件兜底 |
| Stage | 稳定 stageId、顺序、SERIAL/PARALLEL、when、timeout；并行读取入口快照，输出冲突拒绝 |

先将订单准入完整定义及非法变体固化成服务端 fixture，再用同一 Schema 生成前端 DTO 和接口 Mock。D0 要明确读取尚未执行/已失败节点的 status、hit 和不存在 outputs 的行为，不能用前端默认值掩盖缺失。

Stage when 本身求值错误为技术失败。requiredFacts 是依赖信息，M1 不因任意静态依赖缺失就直接拒绝请求：实际运行按条件、节点 FailPolicy 和数据异常 Stage 处理，才能满足 OA-07；定义错误则必须在执行前拒绝。

## 6. 实施任务与依赖

| ID | 工作 | 依赖 | 可验收交付 |
| --- | --- | --- | --- |
| D0 | 收敛第 2 节 ADR 差异，冻结 Schema/OpenAPI 与 fixture | 无 | 五类配置、状态/错误、数值/checksum、旧数据路径有唯一依据 |
| F1 | 建 Vue 工程、布局、主题和基础组件 | 已确认方向 | 编排外壳与弹窗视觉对照；typecheck/build 可执行 |
| B1 | 落实必要 Core 基础、Condition、when、OPERATOR、Terminal | D0 | 最小输入→条件→Terminal；缺失/null/失败与并行快照测试 |
| B2 | 草稿/版本持久化、发布事务、校验/模拟/精确执行 API；最小宿主 | D0，执行部分依赖 B1 | MySQL 重启恢复、CAS、完整 API 读写执行；无 save 自动启用 |
| F2 | 草稿状态、条件构建、Stage 与基础节点编辑、API 适配 | F1、D0 | 先对契约 Mock 开发，再接 B2；保存失败和离开保护可验证 |
| B3 | RuleSet、Scorecard、DecisionTable 及订单完整流程 | B1 | OA 样例与并行/分箱边界通过 |
| F3 | 三类专用编辑器、执行明细与发布交互 | F2，真实联调依赖 B2/B3 | 原型订单可修改并得到 75→35 分的真实结果 |
| V1 | 版本不可变、恢复/冲突、视觉与全链路验收 | B2、B3、F3 | OA/ST 清单通过，交付 Studio M1 |
| M2 | Provider/事实数据层 | V1、事实解析契约 | 受控 HTTP 服务验证成功/超时/partial |
| M3 | Scene/Release/查询与操作记录 | M2、D0-4 Scene 决策 | 激活、回滚、冲突及端到端诊断 |

F1 可与 D0 同时推进；DTO 和业务状态机依赖 D0。F2/F3 可借助 Mock 开发，但不得绕过真实联调完成 V1。按任务顺序分小批提交，不按“前端全部完成以后再接后端”组织工作。

## 7. 验证与交付约定

- 后端执行受影响模块测试及仓库要求的完整验证；涉及 JDBC 时跑 Docker/MySQL IT。以当前仓库可用命令为准：mvn clean verify、mvn -Pintegration -pl openrule-jdbc -am verify；引入 Wrapper 后再同步命令。
- 前端建项时提供 typecheck、lint、test、build、test:e2e 脚本，分别承接 vue-tsc、静态检查、组件/适配测试、Vite 和 Playwright。
- 测试覆盖实际风险：错误定位、草稿并发、迟到响应、数值保真、发布不可变和 Java 决策结果。无需为纯样式改动增加镜像实现的单测。
- Mock 与真实 API 共享 Schema 和 fixture，但 Mock 不实现第二套规则引擎；Java 集成测试是决策正确性的依据。
- 每批记录完成范围、接口变化、执行命令、失败项及下一依赖。最终 V1 报告引用 OA/ST ID 和截图，未经执行的项目保持未验收。
- D0 已交付 Proposed ADR、Schema、OpenAPI、fixture 和校验脚本；未创建前端工程或修改后端业务实现。

## 8. D0 定版交接（2026-09-24）

- 目标 HTTP 路径、请求/响应与错误 DTO 以 [OpenAPI 3.1](../contracts/studio-m1/openapi.json) 为准；本文第 4 节是阅读摘要，字段冲突时以前者为准。`/api/v1` 保持现状，不能把 `enable/rollback` 与新发布混用。
- `DraftDocument`、`ExecutableDefinition`、五类配置、Condition AST、NODE 可读字段、十进制和 checksum 以 [契约说明](../contracts/studio-m1/README.md)、两份 JSON Schema 和 ADR-0005 为准。前端 DTO 与 Mock 从同一资产生成或校验，不能复制原型 model 作为网络类型。
- [订单验收映射](../contracts/studio-m1/ACCEPTANCE.md) 将 OA-01～OA-12、ST-01～ST-13 对应到 Definition、facts、预期和后续 Java/API/MySQL 验证。D0 只完成静态契约校验，B1/B2/B3/V1 负责真实执行证据。
- B1 可以按 ADR-0005 实现 Stage when、Terminal、NODE 与失败边界；B2 可以按 OpenAPI/Schema 实现草稿、CAS、发布、精确版本及 v1 checksum 守卫；F2 可以按 Mock/fixture 实现编辑和数值适配。F1 已由选型可独立开始。B3 依赖 B1，F3 依赖 F2/B2/B3。
- 仍需产品/兼容性决策仅为 ADR-0005 末节两项：既有 v1 引擎的部署维护承诺，以及订单演示阈值是否进入生产政策。新 ADR 保留 Proposed，不当作人工 Accepted。
