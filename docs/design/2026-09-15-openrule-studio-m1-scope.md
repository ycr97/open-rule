# OpenRule Studio：首期范围与验收清单

> 日期：2026-09-15
> 状态：设计稿；D0 契约已形成，ADR-0005 仍为 Proposed，业务实现与验收尚未完成。
> 配套：[前后端契约与开发顺序](../plans/2026-09-15-openrule-studio-contract-and-delivery.md)
> 本文不覆盖 Accepted ADR。D0 的明确替代条款见 [ADR-0005](../adr/0005-studio-m1-flow-contract.md)。

## 1. 已确认的方向

- 用户主导、AI 辅助开发；采用 Vue 3 + TypeScript。
- 视觉尽量贴近现有原型；沿用 shadcn-vue + Tailwind CSS 方向。
- 选择前后端同步推进，优先交付一条真实业务闭环。
- 首条用例为原型的“订单准入”，flowId 为 order_admission。
- 第一阶段由调用方提供完整 facts；随后接入 Provider 和 Scene Release。
- 框架选型、视觉方向和首条用例已收敛，后续讨论集中在会影响实现的契约。

## 2. 依据与当前基线

| 来源 | 用途 |
| --- | --- |
| [产品蓝图](../product/2026-08-23-openrule-product-blueprint.md) | OSS/EE 边界与产品方向 |
| [ADR-0001](../adr/0001-core-extension-contract.md)、[ADR-0002](../adr/0002-execution-state-and-value-semantics.md)、[ADR-0003](../adr/0003-deadline-cancellation-and-resource-isolation.md)、[ADR-0004](../adr/0004-decision-package-and-evidence-boundary.md) | 已接受的技术约束 |
| [原型定义与样例](/Users/ycr/Downloads/OpenRule-Studio-Prototype/source/lib/studio/seed.ts) | 流程、阈值、事实样例与默认 Mock 值 |
| [原型交接说明](/Users/ycr/Downloads/OpenRule-Studio-Prototype/docs/UX-HANDOFF.md) | 页面交互与原型限制 |
| [原型样式](/Users/ycr/Downloads/OpenRule-Studio-Prototype/source/app/globals.css) | 视觉变量和实际布局 |

本地原型是设计参考。其 React 组件需要迁移，浏览器解释器不进入正式执行链路。原型的 26 项检查属于原型自检，不能替代 Java 和真实接口验收。

当前后端是 Core/Spring/API/JDBC 四模块；管理接口尚无 Flow 总列表、精确版本定义读取和独立发布，保存会生成版本并自动启用。M1.6 的若干契约已经写成 ADR，但当前代码仍使用旧模型。首期工期必须包含后端前置工作。

## 3. 订单准入业务基线

| Stage | 模式 | 处理 |
| --- | --- | --- |
| s_base 基础准入 | SERIAL | blacklist 判断黑名单；base_rules 检查金额 ≥ 50000、地区属于 XX/ZZ |
| s_reject 风险拦截 | SERIAL | 前述任一节点命中，Terminal 输出 REJECT / ADMISSION_REJECTED |
| s_score 并行风险评估 | PARALLEL | buyer_score 评分；order_segment 金额分层；共享阶段入口快照 |
| s_fallback 数据异常复核 | SERIAL | blacklist、buyer_score 或 order_segment 技术失败，输出 REVIEW / FACT_RESOLUTION_FAILED |
| s_review 高风险复核 | SERIAL | riskScore 存在且 ≥ 60，输出 REVIEW / HIGH_RISK_SCORE |
| s_approve 默认决策 | SERIAL | 无条件 Terminal，输出 APPROVE / RISK_ACCEPTABLE |

评分卡：注册天数 < 30 得 30 分，否则 5 分；退款率 ≥ 0.3 得 45 分，否则 10 分。金额分层按 FIRST：≥ 5000 为 HIGH，≥ 1000 为 MEDIUM，< 1000 为 LOW。

base_rules 使用 ABORT；blacklist、buyer_score、order_segment 使用 CONTINUE。普通节点失败可以被后续 Stage 显式转为 REVIEW；请求总超时、取消、引擎不变量故障不得转换为业务通过或复核。

真实节点超时与原型存在一个需显式适配的差异：原型数据异常守卫只检查 FAILED，而新后端保留 TIMED_OUT。建议该守卫覆盖 FAILED/TIMED_OUT，并继续区分 Trace 状态；D0 固化后补充验收。请求总超时和调用方取消仍立即终止，不进入这条业务兜底。

M1 保留原型的结果含义：节点未命中属于成功执行；只有 Terminal 产生最终业务决策。错误来源和跳过原因必须可见。Terminal 已结束流程后，后续节点不得执行。

## 4. 分期边界

本文使用 Studio M1/M2/M3 表示交付阶段，与旧后端路线图的 M1/M2a/M2b 编号无关。

| 阶段 | 交付 | 完成标准 |
| --- | --- | --- |
| Studio M1 | 五类节点编辑、条件构建、串并行 Stage；草稿读写、校验、Java 试运行、发布、精确版本执行和当次执行明细 | 第 6、7 节通过；MySQL 持久化，重启后可读取；真实 API 联调 |
| Studio M2 | 事实目录、买家风险画像 Provider、映射、Mock、真实 HTTP 补数及字段调用明细 | 补全成功、部分失败、超时、不覆盖已有 facts；服务端脱敏 |
| Studio M3 | 订单 Scene、可信渠道路由、不可变 Release、激活回滚、Trace 查询及配置变更记录 | 精确版本路由、revision 冲突、回滚不改历史、traceId 串联全链路 |

M1 当次执行明细直接来自服务端响应，不依赖历史 Trace 检索平台。历史执行记录的列表、分页和详情放在 M3；定义校验失败也需要请求标识与可定位错误，但不伪造节点执行记录。

M1 页面只开放可用入口。优先完成决策流列表、版本查看、编排、试运行；已发布版本只读，并能基于它创建下一草稿。发布动作位于 Flow 内，Scene 发布中心随 M3 开放。

五类节点均需可编辑，规则和分箱支持增删、排序及条件构建。RuleSet 支持 FIRST_MATCH/ALL_MATCH，DecisionTable 首期为 FIRST。首条用例之外的脚本、DAG、审批、多租户、资产独立发布、回测和模型能力不进入本批次。

## 5. 视觉与交互验收

- 从原型提取主题变量：主色 #3265ed、前景 #1b2b48、工作台背景 #f7f9fd、边框 #e4eaf3；保留原型字体回退、间距、圆角和节点色系。
- 桌面布局保留 216px 工作台侧栏，以及编排页的节点区、Stage 区和属性区。优先匹配原型最终生效样式，不能只复制 CSS 中较早的声明。
- 使用 shadcn-vue 基础组件，业务编辑器独立组成；DOM 和插槽差异逐项适配，不承诺同名组件自动产生相同外观。
- 建议首批对照尺寸为 1440×900、1920×1080；同一浏览器、字体和设备缩放下比较。窄屏保证主要操作可达，完整移动端优化后置。
- 对照状态：编排页、条件弹窗、评分卡弹窗、75 分复核、35 分通过、发布只读、错误定位。首次人工核对通过后，将 Vue 页面截图作为后续回归基线。
- 加载、空数据、保存失败、执行失败和版本冲突均有明确状态；颜色之外显示状态文字。
- 草稿改动后显示未保存，离开需处理；失败保留输入。保存期间继续编辑的内容不能被响应覆盖。
- 试运行结果绑定已保存的草稿 revision 和本次输入；规则或输入变化后标为旧结果。较早请求的迟到响应不能覆盖较新结果。
- 校验项能打开相应 Stage/节点/规则行并定位字段；发布进行中防止重复提交，成功后以服务端返回的状态切换只读。

## 6. M1 业务验收样例

正常订单完整输入如下。三个画像字段取自原型成功 Mock；M1 不发生 Provider 调用。

~~~json
{
  "order": { "amount": 680, "country": "JP" },
  "buyer": { "id": "buyer_1024", "ageDays": 365 },
  "metrics": { "refundRate": 0.08 },
  "risk": { "blacklisted": false },
  "channel": "web"
}
~~~

下表各行独立从正常输入与原版规则出发；只有明确写明时才修改规则。预期来自源码分析，尚未通过 Java 实现验证。得分列为展示值，传输格式见配套文档。

| ID | 输入或规则变化 | 预期 |
| --- | --- | --- |
| OA-01 | 正常输入 | DECIDED；APPROVE；score=15；segment=LOW；终点 approve |
| OA-02 | buyer.id=buyer_2056，ageDays=12，refundRate=0.42，amount=1800 | DECIDED；REVIEW；score=75；segment=MEDIUM；终点 risk_review |
| OA-03 | OA-02；仅将高退款率分箱分值 45 改为 5 | DECIDED；APPROVE；score=35；终点 approve |
| OA-04 | risk.blacklisted=true | REJECT；score=null；终点 reject；评分和后续节点均不执行 |
| OA-05 | amount=50000；另测 49999.99 | 前者 REJECT；后者 APPROVE/15；验证准入边界 |
| OA-06 | country=XX；再测 ZZ | 均 REJECT，命中地区限制 |
| OA-07 | 删除 metrics.refundRate，其他字段不变 | buyer_score 失败；REVIEW；score=null；终点 data_review；Trace 保留缺失原因 |
| OA-08 | metrics.refundRate=null，其他字段不变 | 路径存在，类型错误；REVIEW；不得归类为 missing |
| OA-09 | order.amount 改成字符串 "680" | base_rules 技术失败并 ABORT；无业务 decision；不得隐式转成数字 |
| OA-10 | ageDays=30，refundRate=0.3；再把 ageDays 改为 29 | 前者 APPROVE/50；后者 REVIEW/75；验证两处分箱边界 |
| OA-11 | 正常输入，仅依次改 amount=999、1000、4999、5000 | segment 依次 LOW、MEDIUM、MEDIUM、HIGH |
| OA-12 | 专用边界测试：调整分箱使总分依次为 59、60 | 分别 APPROVE、REVIEW；不修改发布阈值 60 |

M2 再恢复原型的 Provider 超时和 partial 样例，使用受控 HTTP 测试服务验证。Provider 超时但请求预算仍足够时，可以进入数据异常复核；整个请求预算耗尽必须技术失败。

## 7. M1 状态与工程验收

| ID | 操作 | 验收 |
| --- | --- | --- |
| ST-01 | 保存结构合法但缺少业务必填项的草稿 | 可重新读取；校验报告未通过；不能发布或执行 |
| ST-02 | 保存、重启后读取 | 内容一致；保存不发布、不改变任何活动指针 |
| ST-03 | 发布后修改、再次创建草稿 | 发布版本不可修改；新草稿分配新 version；旧版本结果保持不变 |
| ST-04 | 两个编辑会话以相同 revision 保存 | 仅一个成功；另一方冲突，保留本地编辑，不自动覆盖 |
| ST-05 | 保存失败、发布响应丢失或执行请求超时 | 不显示虚假成功；写入先查状态再决定重试，执行不自动重复 |
| ST-06 | 草稿校验后又修改，再发布 | 服务端对实际待发布 revision 重新校验 |
| ST-07 | 移除默认 Terminal、把 Terminal 移到并行 Stage | 校验阻止发布和执行，定位到相应位置 |
| ST-08 | 引用未来节点/本并行 Stage 新变量、重复并行输出键 | 服务端校验拒绝；运行时仍保护冲突与快照隔离 |
| ST-09 | 可证明重叠的分箱；缺失分箱 | 校验尽可能提前定位；运行时要求恰好命中一箱，不能静默任选 |
| ST-10 | 直接执行 DRAFT；精确执行已发布版本 | 前者拒绝；后者返回实际 version/checksum，不能退回 active 版本 |
| ST-11 | 评分卡十进制、定义数值读写、大整数输入 | 服务端精确运算；前端不得发生静默精度损失 |
| ST-12 | 发布失败、并发发布、checksum 不符 | 状态与内容不能部分提交；冲突和损坏定义有稳定错误码 |
| ST-13 | buyer_score 节点超时且请求预算充足；另测请求总超时 | 前者保留 TIMED_OUT 并按 CONTINUE 进入 REVIEW；后者技术失败，无业务 decision |

前端建议以 Vitest + Vue Test Utils 验证编辑和适配逻辑，Playwright 覆盖关键操作及截图；后端用既有 JUnit/API/JDBC 测试设施。验收以关键行为通过为准，不设置没有业务意义的全量覆盖率目标。

## 8. 完成定义

Studio M1 完成需同时具备：实际 Vue 页面、Java 执行、MySQL 草稿与版本持久化、OA/ST 验收证据及视觉对照。原型运行成功、Mock 页面完成或仅通过编译，均不能单独判为 M1 完成。

M1 是开发/联调交付点。对外部署前，管理 API 需接入部署环境的真实身份认证和操作者来源；本批次不建设完整 RBAC/审批平台。

技术参考：[Vue TypeScript](https://vuejs.org/guide/typescript/overview.html)、[shadcn-vue Vite](https://www.shadcn-vue.com/docs/installation/vite)、[Vue Test Utils](https://test-utils.vuejs.org/guide/)、[Vitest](https://vitest.dev/guide/)、[Playwright 截图比较](https://playwright.dev/docs/test-snapshots)。具体依赖版本在建项时验证并锁定。

## 9. D0 契约交接（2026-09-24）

- 实施入口：[Studio M1 契约](../contracts/studio-m1/README.md)、[OpenAPI](../contracts/studio-m1/openapi.json)、[验收映射](../contracts/studio-m1/ACCEPTANCE.md)。五类节点配置、Condition AST、草稿/可执行 Schema、数值、checksum 和 v1 路径均以这些文件及 Proposed ADR-0005 为准。
- 订单准入为 [`order-admission.definition.json`](../contracts/studio-m1/fixtures/valid/order-admission.definition.json)；OA-01～OA-12 已分解为 19 个输入/预期记录，且明确规则变体。ST-01～ST-13 与契约资产及后续 Java 验证层在验收映射中对应。
- 本节是实施目标，不表示当前 Java 已支持 Stage when、Terminal、草稿版本或精确执行。当前 `/api/v1`、JDBC save 自动启用和原型浏览器解释器均不能替代 `/api/v2` 目标协议。
- 设计中旧版 `Definition JSON v2` 的控制流字段由 ADR-0005 的替代条款收敛；ADR-0001/0002 的其他约束继续生效。新 ADR 待人工处理的兼容和生产政策问题不影响 D0 静态契约校验。
