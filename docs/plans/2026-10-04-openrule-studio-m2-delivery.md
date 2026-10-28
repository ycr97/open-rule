# Studio M2 实施计划

> 日期：2026-10-18。文件名保留任务指定日期。
> 状态：M2-D0 已形成设计与静态契约。B1/B2/F1/V1 均未在本轮实施。
> 输入：[设计](../design/2026-10-04-openrule-studio-m2-facts.md)、[ADR-0006 Proposed](../adr/0006-studio-m2-fact-resolution.md)、[契约](../contracts/studio-m2/README.md)、[验收矩阵](../contracts/studio-m2/ACCEPTANCE.md)。

## 1. 顺序与并行边界

| 批次 | 前置条件 | 可以并行的工作 | 完成条件 |
| --- | --- | --- | --- |
| M2-B1 | D0 契约 | F1 的 DTO、页面和契约 Mock；B2 的独立持久化部分 | 编译依赖、补数编排、HTTP/Mock、共享 Deadline 及回归测试通过 |
| M2-B2 | D0 可开始建表/管理 API；执行接入依赖 B1 公开接口 | F1 页面与适配 | 真 MySQL、真实管理/执行 API、配置快照/CAS 和 M1 回归通过 |
| M2-F1 | D0；真实联调依赖 B2 | B1/B2 | 三种试运行方式、目录/Provider、字段明细与真实操作通过 |
| M2-V1 | B1+B2+F1 | 无依赖未完成项 | MF/MS/MT 验收、M1 回归与视觉证据齐全 |

B1 与 B2 必须先约定公开 Java 输入/结果类型。B2 可使用测试桩开发数据库接口，不得自行另建补数实现。B1 修改 Core/Spring；B2 修改宿主；F1 修改 Vue。涉及共同文件时先交接再修改，不让两个 agent 同时改同一文件。

每批只完成本批任务。交付后停止，不自动进入下一批。禁止扩大到 Scene/Release、历史 Trace、缓存中间件、自动重试/降级或其他 Provider 类型。

## 2. M2-B1：事实依赖、SPI、解析编排和适配器

### 输入事实

- M1 已验收。现有 `StudioCompiler.compile()` 没有依赖元数据；`StudioEngine.execute(..., long)` 自行建立请求 deadline。
- M1 的五种节点、Condition AST、FailPolicy、Terminal、数值和摘要继续使用。
- `fixtures/order-admission.cases.json` 是待执行预期，不是运行通过记录。

### 修改范围

- `openrule-core/src/{main,test}/java/io/openrule/core/studio/`：编译产物、依赖位置、绝对 Deadline 入口和对应测试。
- `openrule-spring/src/{main,test}/java/io/openrule/spring/fact/`：SPI/值模型、编排、HTTP/Mock、SecretResolver port 和测试。
- 必要的现有 Core/Spring 装配代码及模块测试配置。保持依赖方向，Core 仍仅依赖 JDK。
- `docs/contracts/studio-m2/B1-IMPLEMENTATION.md`：实现与验证交接。

### 执行步骤

1. 检查工作区和 M1 基线，读取全部 D0 文件。
2. 让编译器产生已验证 Flow 与 requiredFacts。保留原文位置和稳定 ID。
3. 增加复用同一执行逻辑的绝对 Deadline 入口。保持 M1 原调用方式。
4. 实现不可变批次输入/字段结果及显式类型的配置快照接口。
5. 实现 callerFacts 查找、分组、映射、严格响应解析和只补 missing 的合并。
6. 实现受总预算约束的 HTTP 和无网络/无 secret 读取的 Mock。
7. 加入在途上限、取消、完整响应体超时、迟到结果隔离。
8. 对受影响行为运行验证，并交付公开 Java 类型与调用示例。

### 验收标准

- MF-01～30 在合适层级执行：成功/partial/失败预期由同一 M1 定义进入现有 Core 核对。
- HTTP 测试使用受控本地服务，记录每个用例收到的 POST 数、请求体和可控时序。只用 Mock 不足以证明真实超时/取消。
- MF-03 保留已有年龄；MF-04 保留显式 null；MF-13/14/25 不替换父节点。响应额外字段不能覆盖 callerFacts。
- MT-01～04、MT-08/09/11 的 B1 部分有测试。presence、类型提示冲突、重排位置和迟到响应必须覆盖。
- `mvn -pl openrule-core,openrule-spring -am test` 和 `mvn clean verify` 通过；环境问题记录原始失败和允许范围内的重跑。
- 不创建目录数据库、管理 API 或 Vue 页面；不改 M1 fixture 及其预期。

## 3. M2-B2：配置持久化、管理 API 与执行接入

### 输入事实

使用 B1 交接的公共编译/解析接口。现有 `StudioService` 可读取精确版本；M1 端点必须继续执行原请求语义。

### 修改范围

- `openrule-studio-server/src/{main,test}/java/io/openrule/studio/.../fact/`：Controller、服务、JDBC adapter、配置、SecretResolver 和响应投影。
- 宿主 resources 新增 M2 DDL，并在现有启动配置中追加加载入口。
- 宿主测试配置/POM 中必要的 MySQL 集成测试接线。
- 确需扩展的 OSS **公开读取/装配接口**，先与 B1 对齐；不得把 EE 配置实体移入 Core。
- `docs/contracts/studio-m2/B2-IMPLEMENTATION.md`。

### 执行步骤

1. 实现两张配置表及文档/索引列同事务更新。
2. 实现创建、读取、分页与 CAS 更新；按 Schema 和语义规则检查配置。
3. 用双连接测试目录路径唯一/前缀并发、Provider 引用和配置 CAS。
4. 实现短事务一致配置快照，并在外部调用前结束事务。
5. 装配环境 secretRef 解析和 M2 显式执行入口。
6. 在外部调用前建立 traceId，把同一 Deadline 传入 B1 与 Core。
7. 实现字段/调用报告、错误体和脱敏投影；复用 M1 执行 DTO。
8. 实现 required-facts 和已保存配置的 mock-tests。
9. 验证 MySQL 重启、API 契约和 M1 请求兼容。

### 验收标准

- OpenAPI 的8条路径、12个 operationId 都有真实响应；无删除接口。
- MS-01～08 由管理 API 正确拒绝；409 保持服务端旧 revision/document。
- 同一请求只使用读取到的 fact/provider revision。并发更新只影响下一请求。
- MF-01～30 通过真实 Java/MySQL HTTP 入口验证；内部 expected.segment/preserved 在 B1 测试验证，HTTP 不绕过脱敏。
- `secretRef` 只返回引用；MOCK 不读环境凭证、不触网；原始凭证、事实和响应体不进入日志/结果。
- 全部冻结 M1 OA 子用例与 ST 的相关回归通过。旧入口不得因 Catalog 出现而调用 Provider。
- `mvn clean verify`、现有 `mvn -Pintegration -pl openrule-jdbc -am verify`，以及新增宿主 MySQL IT 的 integration 目标通过。新 IT 必须实际被 Failsafe 执行，Docker 不可用不能记 PASS。

## 4. M2-F1：目录、Provider 和试运行

### 输入与范围

原型仅用于视觉和交互：[catalog.tsx](/Users/ycr/Downloads/OpenRule-Studio-Prototype/source/components/studio/catalog.tsx)、[UX-HANDOFF](/Users/ycr/Downloads/OpenRule-Studio-Prototype/docs/UX-HANDOFF.md)。正式 DTO 来自 M2 Schema/OpenAPI。

允许修改 Vue 的 `src/api/`、`src/app/`、`src/App.vue`、`src/features/facts/`、`src/features/providers/`、现有 editor 的相关状态/组件及必要测试。复用 lossless JSON、基础组件和现有布局，不创建浏览器 Provider/规则执行器。

### 执行步骤

1. 建立契约 DTO 和可替换 API adapter；以冻结 fixture 开发契约 Mock。
2. 完成事实目录分页/来源过滤、新增与编辑。
3. 完成 HTTP/MOCK Provider 配置、启停和 CAS 保存。
4. 完成保存后服务端 Mock 映射测试，明确未保存/错误/冲突状态。
5. 在现有试运行中增加输入、HTTP、Mock三种方式；默认走 M1。
6. 展示字段状态、错误、摘要、调用耗时、配置 revision 和 HTTP/Mock 来源。
7. 保持未保存保护、响应未知时查询、结果过时标记和迟到响应隔离。
8. 接 B2 真实 API 完成浏览器闭环和视觉对照。

### 验收标准

- 一次真实 HTTP 补齐三字段，显示 APPROVE/15；partial/超时经原 Core 到达数据复核，报告保留失败字段。
- 只传完整 facts 时真实 HTTP 次数0；MOCK 的真实 HTTP/SecretResolver 次数0。
- 目录/Provider 保存、刷新、冲突、启停及精确数值不丢失。
- 原型有而 M2 暂未开放的 JAVA Provider 和删除入口不显示可用操作。
- 1440×900、1920×1080及窄屏可操作；记录实际 viewport，保存对照截图。
- `npm run test`、`npm run typecheck`、`npm run lint`、`npm run build` 通过。真实联调未完成时只报告前端实现状态。
- 交付 `openrule-studio/docs/M2-F1-HANDOFF.md`，列出 API、截图、实际操作及未覆盖项。

## 5. M2-V1：总体验收

输入 B1/B2/F1 交接与 [验收矩阵](../contracts/studio-m2/ACCEPTANCE.md)。允许修复本范围明确缺陷并回归。使用独立测试身份、受控 HTTP 服务和专用数据库数据，保留 M1 的已发布版本和证据。

按 MF、MS、MT 逐项记录 PASS/FAIL/BLOCKED/NOT_RUN 和验证层级。覆盖真实单次批量 POST、partial、连接/完整body超时、deadline/取消、Mock隔离、CAS、配置快照、重启、脱敏、精度、M1 OA/ST和视觉。超时测试使用受控时序，不能假定极短请求必然超时。

浏览器脚本串行运行，必须等待明确终态。实时代理日志写工程外，保留 V1 已修复的扫描隔离和请求上限，防止证据写入触发 Vite 重载循环。

交付 `docs/contracts/studio-m2/V1-ACCEPTANCE.md`。记录源码 commit/工作区、环境、命令、原始证据和未完成项。只有全部必验项通过才声明 Studio M2 验收通过。完成后停止，不自动进入 M3。

## 6. 各批通用边界

- 保留已有改动和证据，不做破坏性 Git 清理。
- 实施前读取适用 AGENTS.md。常规实现选择从契约和现有模式解决。
- 确有契约冲突时写明具体字段、现状和影响；不得修改测试预期以适配错误实现。
- 本计划不授权自动 push、合并或部署。
- 后续新增文件的路径由各批任务授权；本次 D0 只增加设计、ADR和契约目录资产。
