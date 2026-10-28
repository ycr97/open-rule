# Studio M1 V1 总体验收

结论：**Studio M1 验收通过**。OA-01～OA-12 全部19个冻结子用例及 ST-01～ST-13 全部 PASS；FAIL=0、BLOCKED=0、NOT_RUN=0。报告汇总日期：2026-10-04（Asia/Shanghai）。实际构建、MySQL 故障/重启、浏览器异常恢复及精度执行记录来自 2026-10-01；完整 HTTP 与修复后编辑器/视觉回归于 2026-10-04 重跑。以下结论依据当前源码的实际执行，F3 交接只用于定位输入。

## 代码、环境和证据边界

| 项目 | 本次基线 |
| --- | --- |
| 后端 | `/Users/ycr/IdeaProjects/Sandbox/open-rule`；验收基线 HEAD `ab106e4eefd9c6b388cbc47ae02c3eb18772f9da` |
| 前端 | `/Users/ycr/IdeaProjects/Sandbox/openrule-studio`；验收基线 HEAD `cb892d476322a9a63c1a0e66c3abf7dcea7caafd` |
| 工作区 | 开始时两个仓库 clean；验收结束时为本次 V1 未提交的代码、回归测试、脚本、文档和真实证据；本地提交收尾见文末；没有 reset/stash、push、合并或部署 |
| Java | Temurin 21；当前修复后 `openrule-studio-server/target/openrule-studio-server-1.0.0-SNAPSHOT.jar`，SHA256 `b8b221070092b44cb55ab5ee823c0d008daf86415350f282099a6b61a964336d`；8081 运行此构建 |
| MySQL | `openrule-f3-mysql`，MySQL 8，3308；仅本验收容器重启，卷保留；本地测试 DB/user 为 `openrule` |
| 前端 | 5174 留存 F3；5175 专用 V1，代理 8082 → Java 8081；原型 5176 服务本地目录，无业务执行用途 |
| 浏览器 | 真实 Chrome；有界执行、单个命名会话 `v1`；CAS 场景临时第二标签页，结束关闭 |
| 数值 / 契约 | D0 fixtures、预期、OpenAPI 和 checksum 规则未修改；Issue DTO 字段和接口语义保持原契约 |

验证层级：**D**=D0 静态契约；**J**=Java Core/Spring/API 测试（MockMvc 明确计入 J）；**I**=真实 MySQL/JDBC 集成测试；**H**=本地实际 Java/MySQL 的 HTTP；**B**=真实浏览器用户操作；**R**=专用 Java/MySQL 进程实际重启；**V**=真实截图和原型人工核对。代理合成的 504 只验证前端状态，真实 deadline 由 J 验证。

证据目录：后端 [`output/v1`](../../../output/v1)，前端 [`output/v1`](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/v1) 和 [`output/playwright`](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/playwright)。构建日志保留实际时间；中断、模态未处理、没有最终 PASS 的输出不计通过。最终工作区、源文件 SHA256 和 PNG 尺寸清单见 [`source-manifest.json`](../../../output/v1/source-manifest.json)、[`evidence-index.json`](../../../output/v1/evidence-index.json)；当前改动文件及 Git diff 快照见 `output/v1/v1-source-snapshot.zip`。

## OA：冻结的全部 fixture 子用例

19 个冻结用例均在同一修复后 Java 服务上分别执行 SIMULATE 和指定已发布版本 LIVE，共 **38 次**。四种定义仅替换独立 V1 flowId/version；没有更改 fixture。逐次核对 status、decision、reasonCodes、score、segment、Terminal、失败节点及版本/checksum；黑名单还核对后续 SKIPPED/TERMINATED。

原始请求/响应：[`http-evidence.json`](../../../output/v1/http-evidence.json)。逐例执行日志：[`final-http-results.txt`](../../../output/v1/final-http-results.txt)。本次 run=`v1_close_20261004`。

| 用例 | 冻结预期重点 | SIMULATE | LIVE | 层级 |
| --- | --- | --- | --- | --- |
| OA-01 | DECIDED；APPROVE；score=15；segment=LOW；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-02 | DECIDED；REVIEW；score=75；segment=MEDIUM；terminalNodeId=risk_review | PASS | PASS | H（实际 Java/MySQL） |
| OA-03 | DECIDED；APPROVE；score=35；segment=MEDIUM；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-04 | DECIDED；REJECT；score=null；segment=null；terminalNodeId=reject | PASS | PASS | H（实际 Java/MySQL） |
| OA-05a | DECIDED；REJECT；score=null；terminalNodeId=reject | PASS | PASS | H（实际 Java/MySQL） |
| OA-05b | DECIDED；APPROVE；score=15；segment=HIGH；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-06a | DECIDED；REJECT；terminalNodeId=reject | PASS | PASS | H（实际 Java/MySQL） |
| OA-06b | DECIDED；REJECT；terminalNodeId=reject | PASS | PASS | H（实际 Java/MySQL） |
| OA-07 | DECIDED；REVIEW；score=null；segment=LOW；terminalNodeId=data_review | PASS | PASS | H（实际 Java/MySQL） |
| OA-08 | DECIDED；REVIEW；score=null；terminalNodeId=data_review | PASS | PASS | H（实际 Java/MySQL） |
| OA-09 | FAILED；decision=null；terminalNodeId=null | PASS | PASS | H（实际 Java/MySQL） |
| OA-10a | DECIDED；APPROVE；score=50；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-10b | DECIDED；REVIEW；score=75；terminalNodeId=risk_review | PASS | PASS | H（实际 Java/MySQL） |
| OA-11a | DECIDED；APPROVE；segment=LOW；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-11b | DECIDED；APPROVE；segment=MEDIUM；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-11c | DECIDED；APPROVE；segment=MEDIUM；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-11d | DECIDED；APPROVE；segment=HIGH；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-12a | DECIDED；APPROVE；score=59；terminalNodeId=approve | PASS | PASS | H（实际 Java/MySQL） |
| OA-12b | DECIDED；REVIEW；score=60；terminalNodeId=risk_review | PASS | PASS | H（实际 Java/MySQL） |

OA-05a/b 覆盖 50000/49999.99；OA-06a/b 覆盖 XX/ZZ；OA-10a/b 覆盖年龄 30/29 和退款率 0.3；OA-11a/b/c/d 覆盖 999/1000/4999/5000；OA-12a/b 使用冻结 59/60 分定义，阈值仍是 60。OA-07 failure=`Missing value`，OA-08=`Type mismatch` 且不含 Missing；OA-09 HTTP 500、base_rules FAILED/ABORT、decision=null。完整失败原因还经真实 UI 显示验证。

真实用户闭环另在 `v1_final_ui_20261004` 通过：OA-01 15 APPROVE → OA-02 75 REVIEW → 发布 v1 → 从 v1 创建 v2 草稿 → 在评分卡将 b_refund_high 45 改 5 → 保存、刷新、试运行 35 APPROVE → 发布 v2 → LIVE v1 保持 75、v2 为 35 → 创建 v3 草稿。证据：前端 `close-browser-orders.txt` / `final-browser-orders.txt`，实际 viewport 1440×900、DPR=1，截图 `v1-final-result-{15,75,35}.png` 和 `v1-final-score-45-to-5.png`。

## ST：全部状态与工程验收

| 编号 | 状态 | 实际验证、层级与证据 |
| --- | --- | --- |
| ST-01 | PASS | D：冻结 incomplete.draft.json 通过 Draft 而不通过 Executable Schema。H：原样独立身份保存/读取一致，validation=false，执行/发布拒绝。B：不完整草稿保存刷新仍在，UI 校验阻止试运行，发布收到 OR-DEF-VALIDATION；`extra-browser-incomplete.txt`。 |
| ST-02 | PASS | I：重建 Store 读取、CAS/发布。R+H：实际停止验收 Java、重启 openrule-f3-mysql、重新启动当前 jar；13 份完整资源 raw JSON 与 4 次新旧版本执行一致；`restart-before.json`、`mysql-restart.txt`、`restart-after.txt`、`restart-current-recheck.txt`。保存不发布，latestPublished 不变。 |
| ST-03 | PASS | H：PUBLISHED PUT 返回 409 OR-LIFECYCLE-CONFLICT；新 DRAFT 分配新 version。B：前述订单闭环与只读输入/无保存按钮；V：已发布评分卡不可编辑。新草稿及后续编辑不改变 v1/v2 checksum/执行结果。 |
| ST-04 | PASS | I/H：同时 CAS 写入只有一个成功、另一个 409。B：两个真实标签页相同 revision 保存，A 冲突但保留编辑；发布 stale revision 保留本地文档和变更说明；`resumed-browser-conflicts.txt` 和最终 `close-browser-conflicts-retry.txt`。 |
| ST-05 | PASS | B+受控本地代理：保存上游已提交但响应丢失（502），只 PUT 一次再 GET 精确版本确认；503 保存失败保留输入；迟到保存不覆盖新编辑。发布上游 200 后客户端 502，查询状态恢复 PUBLISHED，只发一次 POST；执行 504 不重复；迟到结果标过时、旧版本请求不覆盖新结果。`resumed-browser-saves.txt`、`resumed-browser-simulations-fixed.txt`、`resumed-browser-publication.txt`、`close-browser-saves-retry.txt` / `close-browser-simulations-retry.txt` / `close-browser-publication.txt` 及 `resumed-proxy-events.jsonl`。 |
| ST-06 | PASS | I/H：先验证合法定义，保存实际 revision 为非法，再发布返回校验拒绝且 DRAFT/revision 保持一致；stale revision 409。B：真实 UI 校验通过后清空 Terminal 决策码并保存，发布对新 revision 拒绝；`extra-browser-incomplete.txt`。 |
| ST-07 | PASS | D/J/H：冻结 parallel-terminal、terminal-missing；另覆盖不存在默认兜底、非最后默认 Terminal。编译、验证及执行入口拒绝，返回实际 Stage/Node。`final-http-results.txt`、StudioB1Test。 |
| ST-08 | PASS | D/J/H：冻结 future-node、parallel-output；本并行阶段新变量引用拒绝；阶段入口快照和定义顺序、ABORT 取消兄弟且不提交输出；运行时未声明的输出冲突仍拒绝、无部分变量提交。J 回归 runtimeProtectsParallelOutputConflictEvenWithDistinctDeclaredOutputs。 |
| ST-09 | PASS | J/H：退款 bins 的 lte/gte 重叠与 gt/lt 缺口静态拒绝到 bins；StudioB3NodeTest.scorecardFailsWithoutExactlyOneBinAtRuntime 对编译期不可证明的多命中/零命中执行失败且无输出。OA-10/OA-12 核对恰好一箱边界及明细。 |
| ST-10 | PASS | H：DRAFT LIVE 409；不存在的精确版本 999 返回 404，不回退已发布版本；所有 38 次执行核对实际 version/revision/checksum。B：v1=75、v2=35。 |
| ST-11 | PASS | D：10 checksum 向量。J/I/H：BigDecimal 长小数、大整数、null/missing、JDBC 原值往返及 canonical checksum。B：读取并编辑 39 位小数、9007199254740995，原始 PUT 为数字 literal，不变成字符串或被 JS Number 舍入；保存刷新、发布、SIMULATE/LIVE score/checksum 一致。`extra-browser-precision.txt`、`extra-http-results.txt`、`final-http-results.txt`。 |
| ST-12 | PASS | I：真实事务版本行 UPDATE 后、head UPDATE 前注入失败，版本/头指针/revision/checksum/changeNote 全回滚；并发发布/草稿分配串行，一胜一冲突；冻结 v1 原始 JSON/checksum 原字节兼容、旧引擎执行。H+真实 SQL：仅独立 v1_extra_20261001_checksum 损坏摘要，GET 与 LIVE 返回 OR-DEF-CHECKSUM，finally 恢复并核对；`checksum-corruption.txt`。 |
| ST-13 | PASS | J：真实 StudioEngine buyer_score timeout=10ms、受控 scorecard logic sleep100ms，请求预算3000ms：buyer_score TIMED_OUT / OR-NODE-TIMEOUT，CONTINUE 后 data_review REVIEW；同请求预算1ms：HTTP MockMvc 504 OR-EXECUTION-TIMEOUT、execution.decision=null。Core 另覆盖节点和请求期限。前端代理 504 只用于 UI/不重试验证，不冒充真实引擎 timeout 或浏览器的 node CONTINUE 实测。 |

真实编排操作还验证 Stage 添加/删除并保存刷新后恢复6阶段/8节点（前端 `stage-browser-check.txt`；原生确认由 CLI dialog-accept 显式处理，不把未处理模态计PASS）。

规则执行补充：RuleSet FIRST_MATCH/ALL_MATCH 的相同金额50000+地区XX分别保留1/2条命中明细（J/H），ALL_MATCH 也由真实 UI 修改并执行（B）。DecisionTable FIRST 在金额5000同时满足≥5000/≥1000时只取 t_high（OA-11d / H）。Scorecard 缺失/错误/多命中/零命中保留失败明细而不输出分数。

全部 7 个 frozen invalid fixture 已执行真实校验入口：parallel-terminal、terminal-missing、future-node、parallel-output 返回 valid=false；unknown-node、string-score、bad-pointer 为结构错误 HTTP400 OR-DEF-SCHEMA。结构错误返回400是冻结协议要求，未为使脚本通过调整服务语义。

## 缺陷、修复及回归

| 缺陷 | 复现与修复 | 回归 |
| --- | --- | --- |
| V1-01：between 仅定位节点 | 用完整 F3 diagnostics 请求，buyer_score 退款率 between 值0.3。新增 Core StudioValidationException 的结构化位置；Compiler 基于原始数组位置传递 stage/node/item，Conditions 对相对字段/嵌套 children 加前缀；Codec 沿用 Issue DTO 转换。标准输入 pointer=`/stages/2/nodes/0/config/characteristics/1/bins/0/condition/value`，itemId=`b_refund_high`。 | J：Stage/Node/characteristic/bin 全重排及任意 ID；H：完整 F3 请求重放；B：反序草稿点击 issue 自动展开对应条件，真实焦点为该 bin 的 value。`close-browser-locations.txt`。 |
| V1-02：并行冲突错误定位 /stages/5 | buyer_score.outputKey=segment 与 order_segment 冲突。Compiler 保留原始输入索引而非排序后的索引，定位实际第二输出生产者 config/outputKey；不解析错误文案，不硬编码订单节点 ID。标准 pointer=`/stages/2/nodes/1/config/outputKey`；反序 pointer=`/stages/3/nodes/0/config/outputKey`、nodeId=order_segment。 | J：Stage/Node 重排、producer ID 和 execution order 改变；H 重放；B 点 issue 打开 order_segment，焦点 outputKey。 |
| V1-03：运行时并行冲突抛 NPE | 不同声明输出的自定义逻辑实际写同一变量；Map.of 的 containsKey(null) 在检查 null key 时先抛异常。StudioEngine 使用 keySet 的显式 null 判断，恢复稳定冲突保护。 | StudioB1Test 新回归：FAILED、冲突原因、并行输出不提交；全 Core 回归通过。 |
| V1-04：保存响应未知时缺少恢复 | 真实 PUT 成功后代理丢响应。state.ts 增加 saveUnknown/checkSaveOutcome，查询精确版本并比较 revision/原文；查询期间继续编辑保留输入；未知或其他写入状态阻止盲目重复写。 | 前端4个状态测试及 B 的 save-lost/save-fail/save-late；原始上游200、客户端502、GET证据。 |
| V1-05：异常重复加载 | 代理持续写工程内日志，Tailwind 默认根扫描将其纳入 CSS 依赖，Vite full-reload → GET → 日志 → full-reload；beforeunload 是反馈环后果。限定 Tailwind src/index 扫描，Vite 忽略 output/.playwright-cli，实时代理日志移出工程且增加锁定熔断（>60请求/10秒）。 | 无浏览器、无业务API的同一日志写入探针：修复前 full-reload；修复后0刷新，src mtime正对照1 HMR。独立假上游66请求：60转发、6拒绝且日志不增长。恢复后单会话有界负载，最终摘要见前端 close-load-summary.json。详细事件文档链接见下。 |
| V1-06：画布高度与专用编辑器视觉差异 | desktop flex 子项 auto/min-height 令画布超出900窗口；完整 ConditionBuilder 卡片使评分卡分箱过度拉长。FlowEditorPage canvas-active/style 固定视窗布局；NodeConfigDialog 改成原型紧凑980px表格，条件按需展开，错误定位自动展开。窄屏表格内部滚动。 | V：3实际 viewport×10状态；B：五类节点持久化，rule/bin/dimension/table-row 增删排序及条件编辑，无效 JSON 修正，ALL_MATCH（`close-browser-conditions-and-editors.txt`）；前端25测试/typecheck/lint/build。 |

位置相关新增测试在 StudioDocumentCodecTest；生产前端 issueLocation 删除 between/message 猜测兜底。定位由服务端 pointer 和稳定 ID 驱动。没有新增 DTO 字段、接口、Provider、Scene/Release 或历史 Trace 查询。

## 视觉对照和真实操作

本地原型准确路由 `http://127.0.0.1:5176/index.html#/editor/order_admission/4`。同 Chrome、DPR=1、字体回退（Inter / SF Pro / PingFang SC）对照。原型截图记录当前实际 viewport；原型解释器执行不作为 M1 功能证据。

| 实际 viewport | 原型对照 | Vue 状态 | 结果 |
| --- | --- | --- | --- |
| 1440×900 / DPR1 | canvas、RuleSet、Scorecard、DecisionTable | canvas、三个编辑器、条件、发布确认、35分执行、明细、已发布只读、已发布评分卡 | PASS，10状态；桌面画布终点y=900，弹窗980px以内且在视窗内 |
| 1920×1080 / DPR1 | 同上 | 同上 | PASS，10状态；桌面画布终点y=1080 |
| 640×900 / DPR1 | 同上 | 同上 | PASS，10状态；主要操作可达，整页无横向溢出，弹窗限制视窗内；宽表格局部横滚，内容区纵滚 |

日志：前端 `close-visual-{1440x900,1920x1080,640x900}.txt`，每张截图包含 viewport、DPR、documentWidth 和目标矩形。截图 `output/playwright/v1-visual-{width}x{height}-{state}.png`；原型 `v1-prototype-{width}x{height}-{canvas,ruleset,scorecard,decisiontable}.png`，原型采集日志 `prototype-capture-final.txt`。窄屏保存 fullPage，PNG 高度可以大于900，不能把 PNG 全页高度写成浏览器 viewport。

人工核对了并排原型与修复后的画布/评分卡：216px侧栏、三栏、主题色、字体、分箱表格、弹窗/Close及只读态一致；980px紧凑表格在1440窗口显示2维度4分箱。执行明细与发布确认基于真实后端数据，不展示原型Mock输出。

代表截图：[1440评分卡](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/playwright/v1-visual-1440x900-scorecard.png)、[原型评分卡](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/playwright/v1-prototype-1440x900-scorecard.png)、[640评分卡](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/playwright/v1-visual-640x900-scorecard.png)、[发布只读](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/playwright/v1-visual-1440x900-published-scorecard.png)、[重排错误定位](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/output/playwright/v1-reordered-bin-location.png)。

## 执行命令与结果

以下从相应仓库根目录执行，使用 Node24.14+。浏览器脚本必须串行，每个命令出现明确最终 PASS 后再执行下一个；如 CLI 返回 modal 或 Error，立即处理/停止当前用例，不继续导航。

```sh
# open-rule；Java main源码最终修复后构建，当前8081运行此jar
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn clean verify -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
DOCKER_HOST=unix:///Users/ycr/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn -Pintegration -pl openrule-jdbc -am verify -Dapi.version=1.43 \
  -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
./scripts/validate-studio-m1.sh
V1_RUN_ID=v1_replay_$(date +%s) node scripts/v1-acceptance.mjs
node scripts/v1-persistence-check.mjs after # 使用留存restart-before.json读取其精确身份
# 重启重放：先before取得新基线；仅停止本验收Java和重启该MySQL容器，再以当前jar启动，最后after。
# extra-acceptance / corruption 只用脚本创建的独立v1_extra_*身份；不得对F3数据注入。

# openrule-studio
npm test
npm run typecheck
npm run lint
npm run build
/Users/ycr/.codex/skills/playwright/scripts/playwright_cli.sh -s=v1 open http://127.0.0.1:5175/ --headed --browser=chrome
export V1_UI_FLOW="v1_replay_ui_$(date +%s)"
node scripts/v1-order-browser.mjs
node scripts/v1-editor-browser.mjs
node scripts/v1-extra-browser.mjs locations
export V1_FAULT_FLOW="v1_replay_fault_$(date +%s)"
node scripts/v1-seed-fault.mjs
# 严格串行，任一命令exit非0即停止，先处理modal/错误
for phase in saves conflicts simulations publication; do
  node scripts/v1-browser-acceptance.mjs "$phase" || exit 1
done
node scripts/v1-visual-acceptance.mjs 1440x900
node scripts/v1-visual-acceptance.mjs 1920x1080
node scripts/v1-visual-acceptance.mjs 640x900
node scripts/v1-prototype-capture.mjs
```

| 验证 | 实际结果 | 日志 |
| --- | --- | --- |
| Java reactor clean verify | PASS，140 tests（Core92 / Spring29 / API15 / JDBC4），0失败/错误/跳过；server jar重建 | 后端 final-backend-verify.txt |
| MySQL IT | PASS，16 tests（StudioStore7 / legacyRepository6 / audit3），0失败/错误/跳过 | 后端 final-mysql-it.txt |
| D0 | PASS，5 valid/4 schema invalid，3 semantic invalid，8 paths/10 operations，10 checksum，19 OA关联、v1原字节摘要 | 后端 final-d0-validation.txt |
| 前端 | PASS，25/25 tests；typecheck/lint/build exit0 | 前端 final-frontend-{test,typecheck,lint,build}.txt |
| 实际 HTTP | PASS，38 OA执行，生命周期、7invalid、缺口/重叠/并行变量、两个F3完整定位复现、精度 | 后端 final-http-results.txt / http-evidence.json |
| 实际重启 | PASS，13资源/4执行完全一致；Oct4再次核对一致 | 后端 restart-after.txt / restart-current-recheck.txt |
| 浏览器 / 视觉 | 全部必须操作和3尺寸对照；详见OA/ST/视觉矩阵 | 前端各明确最终PASS日志和真实PNG |

## 数据保护、异常运行和剩余项

F3 flow=`order_admission_f3_20261001`：发布v1=75、v2=35，草稿v3和历史证据保留。原 V1 已发布版本同样保留。测试数据备份 `output/v1/mysql-before.sql`。checksum损坏只作用独立flow且已恢复；事务注入使用 Testcontainers；原型只读；服务重启仅本验收 Java/MySQL。

异常过程不计 PASS：初始自动化加载反馈环（已修复并重跑）；结构fixture脚本错误假定所有验证HTTP200（修正为契约400）；重复运行超时脚本误计历史事件（改为本用例时间区间统计全部模拟POST，包含普通请求，避免漏掉重试）；不受控1ms普通请求可能快速完成（改用真实引擎受控时序，不修改服务语义）；MySQL重启早期ping在服务ready前失败后重新确认并完成比较；CLI模态/ReferenceError和未完成运行保留日志，仅采用后续明确PASS；自动审批额度临时拒绝后继续获批，不绕过审批。Oct4补充fault重跑期间一项仍待beforeunload处理时误启动下一项，两个运行都不计PASS，关闭专用会话并按序重跑。详细加载事件见[前端事件报告](/Users/ycr/IdeaProjects/Sandbox/openrule-studio/docs/V1-AUTOMATION-INCIDENT.md)。

最终 FAIL=0、BLOCKED=0、NOT_RUN=0；没有未完成的 M1 必验项或范围内已知缺陷。V1 专用浏览器已关闭，Java/MySQL/前端/代理保留供复核。最终代理空闲60秒请求为0（前端 `close-idle-load.json`）；Java/两个Vite/代理采样CPU均0.0%，额外RSS进程排名保存于 `close-memory-ranking.txt`，不把系统压缩内存归因于某个进程。验收只覆盖冻结 Studio M1 和上述范围；本次结束后不自动进入 M2，不push、合并或部署。


## 交付收尾（2026-10-04）

逐文件核对本次待提交文件与验收时 `output/v1/source-manifest.json`：后端15个、前端17个文件SHA256全部一致，两个base HEAD也一致；没有验收之后的源码/测试/脚本变更。本轮复用上述成功执行证据，只补充前端README/F3历史交接的V1入口、V1交接文档和本节，校正文档中null的显示；不改变fixture、预期或验收结论。

源码、测试、3个后端/8个前端可复用V1脚本、验收报告/映射与交接文档按明确清单本地提交，使用中文说明。前端按既有约定提交精选截图到 `output/playwright/`；原始日志、数据库备份、源码快照及其余截图保留本地原位置，不批量加入Git。见[前端V1交接](../../../../openrule-studio/docs/V1-HANDOFF.md)。原始source-manifest和zip仍表示验收时未提交状态，保留不覆写；后续本地commit不能被误读为验收当时的base HEAD。

本轮提交结果可用两个仓库各自 `git log -1 --format='%H %s'` 查询；剩余未跟踪证据及本次提交清单保存于后端本地 `output/v1/delivery-manifest.json`。未执行push、合并、部署，不进入M2。
