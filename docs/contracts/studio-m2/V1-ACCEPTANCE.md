# Studio M2-V1：事实数据层总体验收

日期：2026-10-28（Asia/Shanghai）。**Studio M2 验收通过**。MF-01～30、MS-01～08、MT-01～11 全部 PASS。冻结 M1 的19个 OA 子用例分别执行 SIMULATE/LIVE，共38次；ST-01～13在各自适用层级完成回归。必验项 FAIL=0、BLOCKED=0、NOT_RUN=0。完整执行证据见本报告；交接文档不作为运行通过依据。

## 1. 验收代码与工作区

| 仓库 | 验收 HEAD | 实际工作区 |
| --- | --- | --- |
| 后端 `/Users/ycr/IdeaProjects/Sandbox/open-rule` | `348a48d209f0571049535f4b513eef556859876b` | 6个已修改文件；未跟踪的完整 M2 D0/B1/B2 源码、测试、DDL、Schema和脚本参与构建 |
| 前端 `/Users/ycr/IdeaProjects/Sandbox/openrule-studio` | `1c520ca753213a8fea95bd1976887fe1a8c84c02` | 10个已修改文件；未跟踪的完整 M2 页面、DTO、状态、开发Mock和测试参与前端检查与浏览器执行 |

初始状态、全部已修改/未跟踪文件及SHA256：[initial-workspace.json](../../../output/m2-v1/initial-workspace.json)。后端167、前端414个文件记录包含历史证据，不能当作源码文件数量。最终源码与本轮新增文件摘要见 [source-manifest.json](../../../output/m2-v1/source-manifest.json)。源码依据为当前实际工作区，不是只用上述HEAD中的M1代码。

执行了适用后端AGENTS.md；前端未发现仓库AGENTS.md。遵守本次会话的执行边界。已读取设计、实施计划、Proposed ADR-0006、M2契约/OpenAPI/Schema/manifest及fixture、B1/B2/F1交接、M1冻结契约/V1报告与脚本、原型UX-HANDOFF和对应目录/Provider/试运行页面源码。没有改变ADR状态。

本轮未发现需要修改生产源码的明确缺陷。新增内容仅为验收脚本、报告、状态入口和原始证据。原有未提交M2实现全部保留；M1 fixture、预期、业务阈值和checksum算法未修改。没有commit、push、合并、部署、破坏性Git清理或进入M3。

## 2. 环境与验证层级

D=静态Schema/OpenAPI；J=Java单元、受控时序或MockMvc；I=真实MySQL集成；H=实际HTTP宿主和受控上游；B=真实浏览器；V=原型视觉对照；R=独立Java进程与MySQL容器实际重启。J中的真实上游HTTP仍标J，不冒充宿主H；Mock仅用于Mock隔离用例。所有决策来自现有Java Core/Terminal。

| 环境 | 实际配置 |
| --- | --- |
| 本机运行时 | Temurin21.0.11+10；Node24.14.0；MySQL8.0.46 |
| 当前构建 | `openrule-studio-server/target/openrule-studio-server-1.0.0-SNAPSHOT.jar`，SHA256 `d7f68f1cca5c7a2c50fbe59cc5c58c0aeb4864c81924e44f25a443a22f91b1a4` |
| 专用数据库 | 新容器 `openrule-m2-v1-20261028`，127.0.0.1:3312，持久卷同名前缀；数据库 `studio_m2_v1`、`studio_m2_v1_browser`、`studio_m2_v1_connect` 相互隔离 |
| HTTP矩阵 | Java8094，`studio_m2_v1`；MF受控服务19094；补充过载用原jar重启设置 `STUDIO_FACTS_MAX_IN_FLIGHT=2` |
| 浏览器 | Java8093，`studio_m2_v1_browser`；真实模式Vite5177；独立受控上游19093；原型5178。`STUDIO_CONTRACT_MOCK=0` |
| 真连接超时 | 原生macOS到OrbStack的受控丢SYN探针表现为请求/body等待，不能据此证明连接阶段计时。相同jar只读挂载到 `openrule-m2-v1-java-connect-20261028`，Java21.0.12.1+1，8096；同Linux网络的专用Nginx19095及临时SYN DROP。没有修改宿主网络规则 |
| 现有数据 | 既有 `openrule-m2-f1-20261028:3311` 未改写/重启；历史M1日志/截图/备份逐文件SHA256未变。本轮只向新的专用测试库写入 |

Java均由 `scripts/start-studio-m2.sh` 或其相同JVM参数启动：`-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.enableAllMethodRetry=false`。新jar在本轮clean verify后启动，避免验收旧产物。证据原始目录后端 `output/m2-v1/`，前端 `output/m2-v1/`、`output/playwright/m2-v1-*`；实时Java/Vite/Provider日志在 `/tmp/openrule-m2-v1-*`，不写入Tailwind扫描目录。浏览器由 `gpt-6.1-terra` 通过Playwright CLI单会话严格串行执行；最终会话已关闭。

## 3. 执行数量与命令

| 检查 | 实际结果 | 原始证据 |
| --- | --- | --- |
| 后端 clean verify | PASS，189单元/装配测试：Core96、Spring71、API15、JDBC4、宿主3；0 failure/error/skipped。默认profile的IT明确跳过，未计入此行 | `backend-verify.log` |
| integration全reactor | PASS，Failsafe实际80 IT：JDBC16（7+6+3）；宿主64（FactHostIT63、FactRestartIT1）；0 failure/error/skipped | `integration.log`；`reports/*-failsafe-reports/TEST-*.xml` |
| M2静态契约 | PASS，8路径/12操作、31 Schema定义、271合法实例、11非法实例拒绝、30 MF引用、5路径/10原文位置 | `m2-contract.log` |
| M1静态契约 | PASS，10checksum向量、legacy原始字节、19OA关联和Schema/语义反例 | `m1-contract.log` |
| 前端 | PASS，48/48 test；typecheck/lint/build退出0；显式STUDIO_CONTRACT_MOCK=1生产构建也不包含Mock控制代码；保留原M1测试 | 前端 `frontend-{test,typecheck,lint,build}.log`、`frontend-mock-isolation-{build.log,result.json}` |
| 独立HTTP矩阵 | PASS，30 MF + 8 MS；当前打包jar、真实MySQL、可计数上游，逐例独立配置；记录所有请求响应 | `http-matrix.log`、`http/{results,requests-responses,provider-posts}.json` |
| 补充H边界 | PASS，8组依赖/短路/分组/数值/body/过载探针，另1组纯Linux真连接超时 | `integrity-retest.log`、`integrity/`、`connect-linux/` |
| M1完整H回归 | PASS，19冻结子例×2=38次，以及原V1生命周期/负例/精度/定位重放 | `m1-http-regression.log`、`m1-run/output/v1/` |
| 浏览器/视觉 | PASS，29个明确B/V子项；三个实际viewport、DPR1 | 前端 `browser-acceptance.json`、各运行文件和真实PNG |
| 独立R复核 | PASS，9个主HTTP资源+15个浏览器资源精确一致；M1旧75/新35、M2真实补数15的3次执行一致 | `restart/{before,after,browser-before,browser-after}.json`及各原始HTTP；`restart-after.log` |

`evidence-index.json`汇总实际XML测试数量、MF/MS结果、补充H结果、浏览器逐项结论及重启数量。不能把BUILD SUCCESS当作IT执行；本轮直接核对XML的tests/failures/errors/skipped。

```sh
# 后端根目录，当前工作区；本地Byte Buddy agent和Docker参数沿用已有约定
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean verify -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
DOCKER_HOST=unix:///Users/ycr/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true   mvn -Pintegration -pl openrule-studio-server -am verify -Dapi.version=1.43   -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
python3 -u docs/contracts/studio-m2/validate.py
./scripts/validate-studio-m1.sh
# 下列脚本只用于本次新建容器和数据库；不接受生产数据库名
python3 -u scripts/m2-v1-http.py --run-id m2v1_20261028
python3 -u scripts/m2-v1-integrity.py --run-id m2v1_extra2_20261028
python3 -u scripts/m2-v1-connect-http.py
python3 scripts/m2-v1-restart.py prepare
python3 scripts/m2-v1-restart.py before
# 仅停止本轮Java，docker restart openrule-m2-v1-20261028，等待SQL ready后按原参数启动
python3 scripts/m2-v1-restart.py after
# 前端根目录
npm test
npm run typecheck
npm run lint
npm run build
```

上述seed脚本使用唯一测试身份；重复运行必须更换run-id或使用新的专用库。M1脚本以 `STUDIO_API_BASE=http://127.0.0.1:8094/api/v2 V1_RUN_ID=m2v1_m1_20261028` 在隔离cwd执行；docs和前端诊断输入用只读符号链接引用，输出写 `output/m2-v1/m1-run/output/v1`，没有覆盖旧M1输出。

专用环境启动参数与Docker资源见 `environment.json`。HTTP宿主8094使用 `STUDIO_JDBC_URL=jdbc:mysql://127.0.0.1:3312/studio_m2_v1?serverTimezone=UTC`；浏览器宿主换为 `studio_m2_v1_browser` 和PORT8093；账号为本地 `openrule_v1`。受控脚本 `scripts/studio-m2-upstream.py --port 19093` 和19094。Vite使用 `STUDIO_CONTRACT_MOCK=0 STUDIO_API_ORIGIN=http://127.0.0.1:8093 npm run dev -- --port 5177 --strictPort`。

连接探针使用缓存的nginx镜像和新建Linux JRE镜像。Nginx配置为 `scripts/m2-v1-connect.nginx.conf`。netctl容器仅共享该Nginx网络、具有NET_ADMIN并安装iptables；规则仅匹配该命名空间19095端口的新TCP SYN。`scripts/m2-v1-connect-http.py`在finally删除规则，`rules-after.txt`为`-P INPUT ACCEPT`。最终三个连接探针容器均已停止，未删除。

## 4. MF-01～30

每例独立读取冻结Definition、Catalog、Provider与null-preserving providerPatch。仅替换独立Flow身份和受控endpoint，expectedRevision取实际保存/发布结果。不改变业务输入或预期。J检查内部preserved/segment/requestedPaths；H核对所有字段状态、错误、来源、目录/Provider revision、统一trace、真正Terminal、决策/score、实际POST数。所有新入口响应保持脱敏，未为内部断言绕过投影。

MF主证据：[HTTP结果](../../../output/m2-v1/http/results.json)、[原始请求响应](../../../output/m2-v1/http/requests-responses.json)、[真实上游POST](../../../output/m2-v1/http/provider-posts.json)。J为FactFixtureTest30个动态用例；I/H为FactHostIT的30个动态用例和本轮独立H。secret解析次数由真实宿主IT中的计数SecretResolver断言，不能由浏览器零请求推断。

| ID | 场景 | 状态 | 实际宿主HTTP / 上游POST / 决策 | 层级 |
| --- | --- | --- | --- | --- |
| MF-01 | 一次HTTP补齐三个字段 | PASS | 200 / 1 / APPROVE / score=15 | J/I/H/B |
| MF-02 | 完整facts不调用 | PASS | 200 / 0 / APPROVE / score=15 | J/I/H/B |
| MF-03 | 已有年龄不被上游365覆盖 | PASS | 200 / 1 / APPROVE / score=40 | J/I/H |
| MF-04 | 显式null不触发补数 | PASS | 200 / 0 / REVIEW / score=null | J/I/H/B |
| MF-05 | partial保留其他成功字段 | PASS | 200 / 1 / REVIEW / score=null | J/I/H/B |
| MF-06 | Provider超时后仍有预算 | PASS | 200 / 1 / REVIEW / score=null | J/I/H/B |
| MF-07 | 缺buyerId阻止HTTP | PASS | 200 / 0 / REVIEW / score=null | J/I/H |
| MF-08 | 响应退款率字符串类型错误 | PASS | 200 / 1 / REVIEW / score=null | J/I/H/B |
| MF-09 | 总预算耗尽不进入Core | PASS | 504 / 1 / null / score=null | J/I/H/B |
| MF-10 | Mock成功零HTTP且不解析缺失secret | PASS | 200 / 0 / APPROVE / score=15 | J/I/H/B |
| MF-11 | HTTP失败不回退Mock | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-12 | 禁用Provider不请求 | PASS | 200 / 0 / REVIEW / score=null | J/I/H/B |
| MF-13 | 父路径null不覆盖 | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-14 | 父路径标量不覆盖 | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-15 | 真实模拟未授权不发HTTP | PASS | 200 / 0 / REVIEW / score=null | J/I/H/B |
| MF-16 | LIVE仅真实HTTP | PASS | 200 / 1 / APPROVE / score=15 | J/I/H |
| MF-17 | 响应null是类型错误 | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-18 | 已有错误类型由原Core ABORT | PASS | 500 / 0 / null / score=null | J/I/H/B |
| MF-19 | Mock timeout不触网 | PASS | 200 / 0 / REVIEW / score=null | J/I/H/B |
| MF-20 | 请求映射null不发送 | PASS | 200 / 0 / REVIEW / score=null | J/I/H |
| MF-21 | 原M1缺失请求不受Catalog影响 | PASS | 200 / 0 / REVIEW / score=null | J/I/H/B |
| MF-22 | 原M1完整输入保持15分 | PASS | 200 / 0 / APPROVE / score=15 | J/I/H/B |
| MF-23 | HTTP无凭证时失败，不能降级Mock | PASS | 200 / 0 / REVIEW / score=null | J/I/H |
| MF-24 | 已有黑名单不被false覆盖，静态预取仍发生 | PASS | 200 / 1 / REJECT / score=null | J/I/H |
| MF-25 | 数组父路径不得替换 | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-26 | 非法JSON响应不自动重试 | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-27 | 重复响应键被严格解析拒绝 | PASS | 200 / 1 / REVIEW / score=null | J/I/H |
| MF-28 | Mock错误不触网 | PASS | 200 / 0 / REVIEW / score=null | J/I/H |
| MF-29 | 连接失败保留字段错误 | PASS | 200 / 0 / REVIEW / score=null | J/I/H |
| MF-30 | LIVE不执行MOCK配置 | PASS | 200 / 0 / REVIEW / score=null | J/I/H |

MF-29的客户端连接尝试为1、上游接收POST为0；连接拒绝不能冒充成功POST或连接超时。MF-10的真实HTTP/secret均为0；MF-11不重试、不回退Mock；MF-19的Mock TIMEOUT不实际等待；MF-09受控延迟5秒、总预算先到时504且execution=null。MF-07/20、23分别在请求映射、secret预检阶段阻止触网。MF-13/14/25分别保留null/标量/数组父节点。

## 5. MS-01～08

每例通过真实管理API装入合法目录/Provider后提交冻结语义反例。失败不改变旧revision/document；原始输入、HTTP与错误Issue都在 `http/requests-responses.json`。真实MySQL并发/约束另由宿主IT验证。

| ID | 拒绝项 | 状态 | HTTP / code | 层级 |
| --- | --- | --- | --- | --- |
| MS-01 | 数字写路径token | PASS | 400 / OR-FACT-CONFIG | J/I/H |
| MS-02 | 危险属性名 | PASS | 400 / OR-FACT-CONFIG | J/I/H |
| MS-03 | 不存在的Provider引用 | PASS | 400 / OR-FACT-CONFIG | J/I/H |
| MS-04 | 连接预算大于完整响应预算 | PASS | 400 / OR-FACT-CONFIG | J/I/H |
| MS-05 | 目录路径前缀冲突 | PASS | 400 / OR-FACT-CONFIG | J/I/H |
| MS-06 | 更改既有事实type | PASS | 409 / OR-FACT-IDENTITY | J/I/H/B |
| MS-07 | CAS旧revision | PASS | 409 / OR-REVISION-CONFLICT | J/I/H/B |
| MS-08 | BIGINT范围溢出 | PASS | 400 / OR-REQUEST-INVALID | J/I/H |

## 6. MT-01～11

| ID | 状态 | 实际证据与层级 |
| --- | --- | --- |
| MT-01 | PASS | J：StudioM2Test、FactFixtureTest覆盖五类型/Stage.when/all/any/not、presence、重复、root/转义/数组读取及VARIABLE/NODE/scoreRef排除。H：5路径/10原文引用与冻结fixture完全相同；Stage/Node/规则/维度/分箱反序后每个pointer都回到实际FACT路径，稳定ID匹配。`integrity-retest.log` / `MT-01-HTTP`。 |
| MT-02 | PASS | J/H：同一路径number/string冲突只诊断。root presence使any短路，未访问的missing分支不拒绝执行。H：相同presence定义，M1不补数、hit=false；M2补数一次、hit=true。`MT-02-presence-HTTP`、`MT-02-hints-short-circuit-HTTP`。 |
| MT-03 | PASS | J/H：aaa/zzz排序串行；aaa两字段一次POST；zzz的FACT /a不读取aaa新补值，REQUEST-INPUT、attempted=false；无绑定/unbound字段不发请求。`MT-03-HTTP`与FactRuntimeTest.providersAreSerialSortedAndMappingsNeverReadEarlierProviderResults。 |
| MT-04 | PASS | J：局部和总预算同时到达时请求失败优先；Provider耗时后Core不能重置预算；真实上游慢body取消、恢复中断位、迟到值不入返回快照；忽略中断的worker真正结束前不释放许可，排队取消/拒绝释放。H：真Linux SYN DROP连接138ms，对应100ms connect而非1500ms body；慢body800ms受300ms预算终止，Terminal真实REVIEW；max2并行两请求，第三503 OR-FACT-OVERLOADED、execution=null且不触网，完成后下一请求恢复。具体见connect-linux、integrity及Spring XML。取消/中断标记是J层内部观测，不称浏览器或Servlet断连实测。 |
| MT-05 | PASS | I/H：FactHostIT.concurrentCasAndPrefixCreationIncludingEmptyTable、deterministicTwoConnectionCasAndEmptyTableRangeLock及pagingCasIdentityBindingAndMockPreview。真实双连接验证POST重复ID/path、PUT一胜一409、空表/已有表前缀不双成功；索引provider_id与document同事务；FK拒绝无效引用；identity/revision不变。XML/日志含实际连接ID。 |
| MT-06 | PASS | I/H：请求阻塞在真实上游时，通过独立root连接查询innodb_trx=0，证明配置事务不跨HTTP；并发修改绑定和Provider，当前响应仍是旧完整revision快照，下一请求使用新revision。FactHostIT.executionUsesOneSnapshotAndDatabaseTransactionEndsBeforeHttp。 |
| MT-07 | PASS | I/R/H：FactRestartIT真正重启Java/MySQL，配置/精度/发布checksum恢复。另独立重启当前专用服务，9个主资源、15个浏览器资源原始JSON相同；新旧M1 LIVE75/35与M2 LIVE15一致。目录/Provider错误输入未污染已保存资源；SQL文档仅含secretRef，不保存解析后的凭证。`restart/`、`it-restart-evidence.json`。 |
| MT-08 | PASS | J/I/H/B：39位小数、>2^53整数在requestMap常量、Mock、HTTP响应、DB和Vue保存刷新保持原值；Linux/本机均无业务Number/double中转。H接受128有效数字与指数1000，拒绝129/1001；1,048,576 bytes接受，+1拒绝；慢body也受预算约束。J拒绝double常量。I数据库原文与B精度报告均有证据；`precision-config-original.tsv`、`MT-08-*`、前端provider-precision/precision-mock浏览器文件。 |
| MT-09 | PASS | J/I/H/B：敏感和未登记字段屏蔽，失败valueSummary=null，统一trace和实际配置revision；M2 variables/node outputs/RuleDetail.value屏蔽，分数保留。SIMULATE/LIVE/mock-tests各入口核对原始响应不含caller私值、凭证或上游body。J核对对象/数组、未知/null与Unicode摘要128 code point边界、失败消息去原值。B检查实际报告、trace和过时状态，不能把显示摘要重新当facts。 |
| MT-10 | PASS | B/V：真实配置保存刷新、丢响应后GET确认且不重发、409保留输入、未保存禁映射预览和路由确认；迟到PUT推进revision而保留新输入；输入/方式/预算变更使旧结果过时；迟到试运行不洗掉新输入过时标记，失败后旧成功结果仍明确过时；默认M1、LIVE无Mock界面/API拒绝。3实际viewport和原型对照通过。29个B/V子项见前端browser-acceptance.json；不以契约Mock替代真实运行。 |
| MT-11 | PASS | J/I/H/B：M1全部19子例SIMULATE/LIVE及ST完整分层回归见下表。M2 request投原M1返回400；旧v1原始字节/checksum与损坏守卫仍通过。B覆盖15/75/35、CAS、保存/发布丢响应和重排定位；全量OA在H执行，不声称每一个OA均通过浏览器。 |

## 7. M1回归清单

38次独立H执行逐例核对status、decision、score、segment、原因、Terminal、失败节点、实际version/revision/checksum；黑名单核对后续SKIPPED/TERMINATED。另FactHostIT带目录跑同样19个子例且上游/secret次数为0。

| OA子例 | SIMULATE | LIVE | 层级 |
| --- | --- | --- | --- |
| OA-01 | PASS | PASS | J/I/H |
| OA-02 | PASS | PASS | J/I/H |
| OA-03 | PASS | PASS | J/I/H |
| OA-04 | PASS | PASS | J/I/H |
| OA-05a | PASS | PASS | J/I/H |
| OA-05b | PASS | PASS | J/I/H |
| OA-06a | PASS | PASS | J/I/H |
| OA-06b | PASS | PASS | J/I/H |
| OA-07 | PASS | PASS | J/I/H |
| OA-08 | PASS | PASS | J/I/H |
| OA-09 | PASS | PASS | J/I/H |
| OA-10a | PASS | PASS | J/I/H |
| OA-10b | PASS | PASS | J/I/H |
| OA-11a | PASS | PASS | J/I/H |
| OA-11b | PASS | PASS | J/I/H |
| OA-11c | PASS | PASS | J/I/H |
| OA-11d | PASS | PASS | J/I/H |
| OA-12a | PASS | PASS | J/I/H |
| OA-12b | PASS | PASS | J/I/H |

| ST | 状态 | 当前工作区回归依据 |
| --- | --- | --- |
| ST-01 | PASS | H冻结不完整草稿保存/读取、执行/发布拒绝；J/API相同守卫 |
| ST-02 | PASS | I和实际R资源原文一致，M1旧75/新35结果不变，保存不发布 |
| ST-03 | PASS | H已发布不可写、下一草稿独立；B45→5发布及精确LIVE旧75/新35，发布只读 |
| ST-04 | PASS | I/J并发CAS一胜一冲突；B真实双标签409保留编辑 |
| ST-05 | PASS | J前端状态保留；B原M1编辑器与M2配置真PUT/POST后丢响应，各一次写入再GET确认；迟到保存保留新编辑；M2试运行失败/迟到仍标旧结果 |
| ST-06 | PASS | H校验合法后保存非法revision，再发布拒绝；J/I实际revision重新校验，stale revision 409 |
| ST-07 | PASS | J/H默认Terminal缺失、并行Terminal等冻结反例拒绝；原文位置正确 |
| ST-08 | PASS | J/H并行入口快照、输出冲突及非法引用；冲突阶段不提交部分变量 |
| ST-09 | PASS | J/H可证明重叠/缺口编译拒绝；运行时多命中/零命中失败，冻结分箱与阈值边界通过 |
| ST-10 | PASS | H DRAFT LIVE409、精确不存在版本404不回退、38次实际checksum；B新旧版本隔离 |
| ST-11 | PASS | D10 checksum向量、J/I/H精度/null/missing；B当前精度配置和M1评分编辑后35 |
| ST-12 | PASS | I真实发布中途失败全回滚、并发发布/草稿分配串行、旧v1读取；J/H损坏checksum拒绝且恢复 |
| ST-13 | PASS | J/API实际buyer_score受控10ms节点超时CONTINUE到REVIEW；总预算1ms技术失败504、decision=null；M2 Provider超时与共享期限另证 |

原M1的between-bin与parallel-output完整诊断请求通过H重放；B对反序数组点错定位，真实焦点为b_refund_high/value或order_segment/outputKey。证据前端 `extra-browser-locations.txt` 和 `m2-v1-reordered-*`。

## 8. 浏览器和视觉

[逐项JSON](../../../../openrule-studio/output/m2-v1/browser-acceptance.json)、[浏览器说明](../../../../openrule-studio/output/m2-v1/browser-acceptance.md)。29个当前子项全部PASS；中途NOT_RUN和脚本失败留在history/原文件，完成补测后才形成当前结论。没有使用M2-F1旧截图冒充本轮结果。

| 实际viewport | DPR | 真实页面及原型 | 状态 |
| --- | --- | --- | --- |
| 1440×900 | 1 | 事实目录、Provider、Flow输入/当次报告；原型对应目录/Provider/实验室 | PASS |
| 1920×1080 | 1 | 同上 | PASS |
| 640×900 | 1 | 同上；窄表格/弹窗在容器内滚动，主要操作可达 | PASS |

截图前端 `output/playwright/m2-v1-{facts,providers,simulation,report}-*.png` 及 `m2-v1-prototype-*`；实际viewport文件和 `report-visual-browser.txt` 同时记录尺寸/DPR。图片为真实浏览器，未用绘图重建。目录/Provider沿用216px侧栏、蓝色选中态、表格、卡片和弹窗；已隐藏JAVA和删除。正式试运行按M2设计复用Flow输入页，原型为独立三栏实验室，属于已约定结构差异，不能声明像素相同。错误/失败状态包含文字，不只依赖颜色。

B可观测结论不替代内部SecretResolver计数、并发数据库快照或取消许可观测。浏览器不执行规则，不直接请求Provider，不读取secret。

## 9. 缺陷、重跑与证据保护

本轮没有发现需改生产源码的M2缺陷，也没有修改冻结业务预期以使检查通过。新增以下验证资产：`scripts/m2-v1-http.py`、`m2-v1-integrity.py`、`m2-v1-connect-http.py`、`m2-v1-restart.py`、`m2-v1-connect.nginx.conf`；前端浏览器脚本保存在 `output/m2-v1/`。验收报告和契约状态入口仅为文档更新。

保留以下非成功运行，不计PASS：

- 初次本机backlog返回RST；随后饱和Linux队列及SYN DROP经macOS→OrbStack路径仍表现为body等待1500ms。独立原生JDK21.0.11探针同样等待request timeout；该环境不满足本次已验证的连接阶段前置条件，不能作为connect计时证据。改用同jar纯Linux宿主后，100ms connect实际138ms、body预算1500ms，才计PASS。未为错误探针改Provider语义或放宽预期。
- 单独连接探针一度与19094重启上游端口冲突；该次未执行，保留OSError日志。最终专用Linux8096与第三库无冲突。
- Python py_compile因系统PYCACHEPREFIX指向受限缓存失败；改用只读AST解析检查语法，不扩大系统写权限。
- 浏览器脚本曾错误等待手动查询按钮，实际自动GET；另一次原生beforeunload中止外层脚本。两项均保留首次输出，随后从独立草稿完整重跑，有最终PASS，不用部分DOM补作成功。

原M1历史证据（后端32个本地文件、前端146个本地文件）逐文件SHA256保持不变；已跟踪M1文件无差异。M1输出重定向到独立cwd；已有F1容器/卷未操作。当前IT自己的FactRestartIT固定写 `output/m2-b2/`，因此该处restart-java.log、seed-demo.log有追加，restart-evidence.json刷新为此次运行；初始文件摘要和本次副本在V1目录中记录，不把新的B2输出称为旧运行原件。

配置与发布结果保留在专用新库。重启前备份为 `output/m2-v1/mysql-before-restart.sql`（3个本轮数据库）。连接探针iptables规则已撤销，三个探针容器停止；专用Java/MySQL/Vite/上游保留供复核，浏览器会话关闭。实时日志保持工程外，不产生日志→HMR→API请求循环。

## 10. 最终判定

必验 MF30、MS8、MT11 全部PASS，完整M1分层回归及浏览器/视觉完成。无剩余范围内缺陷、环境阻塞或必验未执行项。只声明Studio M2在本报告的当前源码和本地受控环境验收通过；未执行生产部署。到此停止，不进入M3，不提交、不push、不合并，不执行破坏性Git清理。
