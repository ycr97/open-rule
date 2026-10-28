# Studio M2-B2 实现与交接

日期：2026-10-28（Asia/Shanghai）。基线 HEAD：`348a48d209f0571049535f4b513eef556859876b`。B1 与 D0 仍是已有未提交工作，本批保留其源码和证据。

本批在现有 Studio 宿主实现事实目录、Provider 当前配置、CAS、配置快照和显式事实执行入口。补数调用 B1 `FactResolver`，编译调用 `StudioCompiler.compilePlan`，执行调用接受同一个 `StudioDeadline` 的 `StudioEngine.execute`。没有复制补数或决策实现。ADR-0006 保持 Proposed。

## 1. 文件、分层与公开接口

| 文件（仓库相对路径） | 职责 |
| --- | --- |
| `openrule-studio-server/src/main/java/io/openrule/studio/host/fact/FactDocuments.java` | 冻结 M2 Schema、结构/语义验证、向 B1 不可变类型转换；错误不回显配置值 |
| 同目录 `JdbcFactStore.java` | 两表读写、分页、CAS、路径锁与一致快照；公开 Resource/Page/Selection |
| 同目录 `FactService.java` | required-facts、mock-tests、SIMULATE/LIVE 装配；调用现有 M1 版本读取/checksum 守卫 |
| 同目录 `FactExecutionContext.java` | 包内请求状态；统一 traceId、入口耗时、报告与安全错误投影 |
| 同目录 `FactController.java`、`FactProblemAdvice.java` | 12 个 operation；独立 M2 错误体；不改变 M1 Controller/Advice |
| 同目录 `EnvironmentSecretResolver.java` | 只读取 `env:OPENRULE_[A-Z0-9_]+` 指向的进程环境变量 |
| 同目录 `FactHostConfiguration.java` | 单例 FactResolver、64 默认在途上限、宿主管理的虚拟线程 executor |
| `openrule-studio-server/src/main/resources/db/studio/V2__fact_tables.sql` | 新增两张配置表 |
| `openrule-studio-server/src/main/resources/application.yml` | 追加新 DDL 加载路径和 `studio.facts.max-in-flight` |
| `openrule-studio-server/pom.xml` | 打包冻结 Schema；新增宿主 Testcontainers/Failsafe；IT 使用编译目录，避免 Boot jar 的嵌套类路径问题 |
| `openrule-spring/src/main/java/io/openrule/spring/studio/StudioService.java` | 提取公开静态 `executionResult(Version, requestId, bizId, purpose, traceId, checksum, Result)`，复用 M1 DTO 投影 |
| `openrule-studio-server/src/test/java/io/openrule/studio/host/fact/FactDocumentsTest.java` | Schema、非法配置、错误脱敏和连接失败 503 单元测试 |
| 同目录 `FactHostIT.java`、`ControlledProvider.java` | 真 MySQL + 随机端口宿主 HTTP + 受控 Provider；MF/MS/M1、CAS、快照、精度、脱敏 |
| 同目录 `FactRestartIT.java` | 专用 MySQL 容器和独立 Java 进程真实重启；同时运行交付的启动/初始化脚本 |
| `scripts/start-studio-m2.sh` | JDK 无重试参数及显式 JDBC 环境的启动入口 |
| `scripts/studio-m2-upstream.py` | 仅绑定 loopback 的受控 Provider；NORMAL/PARTIAL/TIMEOUT/ERROR/TYPE/INVALID |
| `scripts/studio-m2-seed.py` | API 初始化/复用专用演示数据；精确十进制，不覆盖内容不同的现有资源 |

OSS 只新增共享 DTO 投影方法。原 M1 `execute` 调用顺序、旧入口计时和响应保持原行为；B2 传入已经脱敏的 Core Result 和提前创建的 traceId。Core、B1 事实 SPI、原 M1 契约/fixture/checksum 算法均未在本批修改。没有发现需要修改 B1 解析算法的接入缺陷。

## 2. DDL、并发和快照

- `or_studio_fact_provider`：provider_id 主键、revision、document_json、updated_at。
- `or_studio_fact_catalog`：fact_id 主键、path 唯一索引、provider_id 外键、revision、document_json、updated_at。
- revision 为正 BIGINT；创建为 `"1"`。PUT 在行锁内核对 revision 和身份，再执行 `WHERE id=? AND revision=?` 更新并加一；达到 Long.MAX_VALUE 后拒绝递增。
- factId/path/type、providerId/type 不可变。绑定引用在事务内检查，并由 FK 保护。停用允许保留绑定；没有删除 API。
- path 使用 MySQL 8 的 `utf8mb4_0900_bin`，不折叠大小写、Unicode 或尾随空格。前缀比较使用 RFC 6901 解码 token，不使用字符串 startsWith。
- Catalog 创建使用 SERIALIZABLE，沿 path 唯一索引全范围 `FOR UPDATE`，覆盖空表 gap；检查后插入。死锁/锁等待冲突返回 409，不自动重试。普通前缀冲突返回 400 OR-FACT-CONFIG；重复 ID/path 返回 409。
- 文档与 provider_id 索引列由同一 SQL/事务写入。列表按 ID 稳定排序；q 按 Unicode code point 限长，按字面量匹配，使用参数和显式 ESCAPE 转义 `%`、`_`、`!`。ASCII ID 的检索表达式先转换为 utf8mb4，避免含 emoji 的合法查询触发 MySQL 字符集错误。
- 执行快照使用独立 REPEATABLE_READ 只读短事务，读取所需字段及关联 Provider；mock-tests 读取 Provider 与其全部当前绑定字段。返回 B1 Snapshot 前完成事务。没有跨 HTTP 的数据库事务，也没有跨请求配置缓存。

## 3. API 与结果

所有路径前缀为 `/api/v2`，裸 JSON，无额外 envelope。

| operationId | 方法与路径 |
| --- | --- |
| listFacts / createFact | GET / POST `/admin/facts` |
| getFact / updateFact | GET / PUT `/admin/facts/{factId}` |
| listProviders / createProvider | GET / POST `/admin/fact-providers` |
| getProvider / updateProvider | GET / PUT `/admin/fact-providers/{providerId}` |
| testProviderMock | POST `/admin/fact-providers/{providerId}/mock-tests` |
| getRequiredFacts | GET `/admin/flows/{flowId}/versions/{version}/required-facts` |
| simulateWithFacts | POST `/admin/flows/{flowId}/versions/{version}/fact-simulations` |
| executeWithFacts | POST `/flows/{flowId}/versions/{version}/fact-executions` |

创建返回 201/revision="1"。更新提交 `{expectedRevision,document}`。列表支持 q/page/pageSize；facts 另支持 source=CALLER/PROVIDER。Schema 禁止额外字段。

required-facts 从原文数组顺序编译，保留 pointer 与稳定 ID；不完整草稿拒绝。响应包含实际 flow/version/revision/checksum。Catalog 不参与发布或 checksum。

SIMULATE 只执行已保存的精确 revision，HTTP 必须显式允许真实模拟；LIVE 只执行指定 PUBLISHED 且 factMode=HTTP。旧 simulations/executions 不访问 Catalog 或 Provider。

新执行成功体为 `{execution,factResolution,totalElapsedMillis}`。字段失败时仍执行原 Core，由 FailPolicy/Terminal 决策。请求总预算、取消或过载不转 REVIEW。进入 Core 前技术失败 execution=null；Core 失败附实际安全 execution，decision=null。请求预算从合法请求接收后建立，加载、编译、快照、Provider、Core、响应投影共用一次绝对 Deadline；totalElapsedMillis 独立从入口计时。

错误体完整包含 code/message/requestId/traceId/issues/execution/factResolution/totalElapsedMillis。配置语义问题的 Issue 指向原请求字段；没有 stage/node/item ID。配置存储连接/事务不可用为 503 OR-FACT-STORE，消息不包含 SQL 或连接凭证。

凭证只存在于环境解析和 HTTP Bearer 头中。管理响应只返回 secretRef 引用；Mock 不解析 secret、不触网。字段与调用报告使用 B1 安全报告；execution 经 `FactRedaction.execution` 后再按 M1 DTO 投影。变量、node outputs、RuleDetail.value 和原错误消息不回显；最终分数及分箱分数保留。

## 4. 可复现启动与初始化

需要 JDK 21、MySQL 8、Python 3。宿主脚本默认端口 **8091**，受控 Provider 默认 **19091**。前端后续可将 API 代理指向 8091；本批没有修改 Vue。

JDK HttpClient 的无自动重试约束沿用 B1。启动脚本固定传入：

```sh
-Djdk.httpclient.disableRetryConnect=true
-Djdk.httpclient.enableAllMethodRetry=false
```

这些值必须在 JVM 启动时设置；直接运行 jar 也必须携带。它们是 JVM 级设置，会影响同进程其他 JDK HttpClient。应用不在运行时改全局属性。

演示使用独立数据库和独立容器；不连接既有 M1 数据库。以下固定口令仅用于该 loopback 演示容器：

```sh
cd /Users/ycr/IdeaProjects/Sandbox/open-rule
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean verify

docker run -d --name openrule-m2-b2-demo \
  -p 127.0.0.1:3310:3306 \
  --mount type=volume,src=openrule-m2-b2-demo-mysql,dst=/var/lib/mysql \
  -e MYSQL_DATABASE=studio_m2_b2_demo \
  -e MYSQL_USER=openrule_m2 -e MYSQL_PASSWORD=b2-demo-local \
  -e MYSQL_ROOT_PASSWORD=b2-demo-root \
  --health-cmd='mysqladmin ping -h127.0.0.1 --silent' \
  --health-interval=2s --health-retries=30 mysql:8.0
# 如该名称已存在，复用专用容器；不要删除重建已有数据。
docker inspect -f '{{.State.Health.Status}}' openrule-m2-b2-demo
```

等待 healthy 后，在第一个终端启动宿主：

```sh
export STUDIO_JDBC_URL='jdbc:mysql://127.0.0.1:3310/studio_m2_b2_demo?serverTimezone=UTC'
export STUDIO_DB_USER=openrule_m2
export STUDIO_DB_PASSWORD=b2-demo-local
export STUDIO_FACTS_MAX_IN_FLIGHT=64
export OPENRULE_RISK_PROFILE_TOKEN=b2-demo-token
PORT=8091 ./scripts/start-studio-m2.sh
```

第二个终端启动受控 Provider，第三个终端初始化：

```sh
python3 scripts/studio-m2-upstream.py --port 19091
```

```sh
python3 scripts/studio-m2-seed.py --base-url http://127.0.0.1:8091 --flow-id m2_b2_demo
curl -s http://127.0.0.1:8091/api/v2/admin/flows/m2_b2_demo/versions/1/required-facts
```

初始化创建 `b2_demo_risk`、6 个 `b2_demo_*` 目录字段和 PUBLISHED `m2_b2_demo/1`。初次 revision="3"，脚本输出实际 revision/checksum。重复运行只复用完全相同文档；已有内容不同则退出并保留。目录 path 全局唯一，因此应在专用空演示库运行。演示只替换 Flow 身份，原冻结文件不改；checksum 使用服务端实际返回值。

## 5. 正常、partial、超时、Mock 复现

正常请求（复用数据时先 GET 精确版本取得当前 revision）：

```sh
curl -s http://127.0.0.1:8091/api/v2/admin/flows/m2_b2_demo/versions/1/fact-simulations \
  -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{"requestId":"b2-normal","bizId":"B2-ORDER","facts":{"order":{"amount":680,"country":"JP"},"buyer":{"id":"buyer_1024"}},"timeoutMillis":3000,"expectedRevision":"3","factMode":"HTTP"}
JSON
```

期望：一次 POST，APPROVE/15；报告 COMPLETE，三个补数字段来自同一 Provider revision。GET `http://127.0.0.1:19091/stats` 可查看累计实际 POST 数，响应不含原始事实或认证头。

设置受控响应后重复上面的请求：

```sh
# partial：成功字段保留，缺退款率由原 Terminal 得到 REVIEW
curl -s http://127.0.0.1:19091/control -H 'Content-Type: application/json' \
  -d '{"scenario":"PARTIAL"}'
# Provider 局部超时：readTimeout=300ms，请求预算3000ms；200/REVIEW，字段 OR-FACT-TIMEOUT
curl -s http://127.0.0.1:19091/control -H 'Content-Type: application/json' \
  -d '{"scenario":"TIMEOUT"}'
# 503/非法JSON/错误类型也可分别选择 ERROR/INVALID/TYPE；没有重试或自动回退。
```

总请求超时的确定复现：保持 TIMEOUT（5 秒），用 CAS 将 Provider readTimeout 改为 10 秒，再执行上面 3 秒请求，得到 504 OR-EXECUTION-TIMEOUT，execution=null：

```sh
curl -s http://127.0.0.1:8091/api/v2/admin/fact-providers/b2_demo_risk > /tmp/studio-m2-provider.json
python3 - <<'PY' > /tmp/studio-m2-provider-update.json
from pathlib import Path
import runpy
helpers = runpy.run_path('scripts/studio-m2-seed.py')
read_resource = helpers.get('read')
encode_resource = helpers.get('encode')
resource = read_resource(Path('/tmp/studio-m2-provider.json'))
resource['document']['readTimeoutMillis'] = 10000
print(encode_resource({'expectedRevision': resource['revision'], 'document': resource['document']}))
PY
curl -s -X PUT http://127.0.0.1:8091/api/v2/admin/fact-providers/b2_demo_risk \
  -H 'Content-Type: application/json' --data-binary @/tmp/studio-m2-provider-update.json
```

HTTP 配置选 Mock：将正常请求的 factMode 改为 MOCK，仍通过新 fact-simulations，得到 APPROVE/15；上游 POST 数不增加，即使受控服务处于 TIMEOUT。Provider 映射预览：

```sh
curl -s http://127.0.0.1:8091/api/v2/admin/fact-providers/b2_demo_risk/mock-tests \
  -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{"requestId":"b2-preview","facts":{"buyer":{"id":"buyer_1024"}},"expectedRevision":"2","timeoutMillis":3000}
JSON
# expectedRevision 使用 GET Provider 的实际值；前面的 CAS 成功后为2，否则初始为1。
```

LIVE 使用 `/api/v2/flows/m2_b2_demo/versions/1/fact-executions`，请求去掉 expectedRevision 并保持 factMode=HTTP。验证 LIVE 前把上游恢复 NORMAL。旧 M1 请求使用原 simulations/executions，去掉 factMode；目录存在也不会补数。

## 6. 验证与证据

全部测试只操作 Testcontainers 创建的独立数据库。宿主随机绑定端口，受控 Provider 也使用随机端口；没有访问、重启或清理原 M1 Java/MySQL/前端服务。

实际命令（JDK21；Docker 环境为 OrbStack）：

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export DOCKER_HOST=unix:///Users/ycr/.orbstack/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true
mvn clean verify
mvn -Pintegration -pl openrule-jdbc -am verify -Dapi.version=1.43
mvn -Pintegration -pl openrule-studio-server -am verify -Dapi.version=1.43
python3 -u docs/contracts/studio-m2/validate.py
./scripts/validate-studio-m1.sh
```

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| clean verify | PASS；189 个单元/装配测试：Core96、Spring71、API15、JDBC4、宿主3；0 failure/error/skipped。默认 profile 未执行 IT | `output/m2-b2/clean-verify-final.log` |
| 指定 JDBC integration | PASS；实际 Failsafe 16 IT：StudioStore7、legacyRepository6、audit3；0 failure/error/skipped | `output/m2-b2/jdbc-integration.log` |
| 宿主 integration 全 reactor | PASS；实际宿主64 IT（FactHostIT63 + FactRestartIT1）；同时实际执行上游 JDBC16 IT；没有筛选或跳过 | `output/m2-b2/host-integration-complete.log`、各模块 `target/failsafe-reports/TEST-*.xml` |
| M2 静态契约 | PASS；8 路径/12 操作、31 Schema 定义、271 valid instances、11 invalid 拒绝、5 依赖/10 原位置及冻结 checksum | `output/m2-b2/m2-contract.log` |
| M1 静态契约 | PASS；5 valid/4 schema invalid、3 semantic invalid、10 checksum 向量、legacy 原字节摘要、19 OA 关联 | `output/m2-b2/m1-contract.log` |
| 交付脚本 | 启动脚本与初始化脚本在实际 Java/MySQL 重启 IT 使用；初始化连续两次成功，revision/checksum 不变；受控 Provider 脚本真实 NORMAL/PARTIAL/ERROR 共3 POST 冒烟通过 | `restart-java.log`、`seed-demo.log`、`upstream-script-evidence.json`，均在 `output/m2-b2/` |
| 工作区保护 | 旧 B1/D0/M1 文件逐字节比对；仅原 StudioService DTO 投影是本批允许的 OSS 改动。`git diff --check` 通过 | `output/m2-b2/before-sha256.json`、`source-manifest.json` |

MF 测试中的 catalog/provider 每例通过真实管理 API 建立，Provider revision 保持 fixture 的1；执行 expectedRevision 使用实际保存/发布后的3。Definition 文件、业务阈值和预期不改。HTTP endpoint 仅替换为受控随机端口。M1 字段 segment/preserved 的内部断言留在 B1；M2 HTTP 始终使用脱敏投影。

| Fixture | B2 层级/结果 | 实际 Provider POST | 核对内容 |
| --- | --- | --- | --- |
| MF-01 | I/H PASS | 1 | 一次HTTP补齐三个字段 |
| MF-02 | I/H PASS | 0 | 完整facts不调用 |
| MF-03 | I/H PASS | 1 | 已有年龄不被上游365覆盖 |
| MF-04 | I/H PASS | 0 | 显式null不触发补数 |
| MF-05 | I/H PASS | 1 | partial保留其他成功字段 |
| MF-06 | I/H PASS | 1 | Provider超时后仍有预算 |
| MF-07 | I/H PASS | 0 | 缺buyerId阻止HTTP |
| MF-08 | I/H PASS | 1 | 响应退款率字符串类型错误 |
| MF-09 | I/H PASS | 1 | 总预算耗尽不进入Core |
| MF-10 | I/H PASS | 0 | Mock成功零HTTP且不解析缺失secret |
| MF-11 | I/H PASS | 1 | HTTP失败不回退Mock |
| MF-12 | I/H PASS | 0 | 禁用Provider不请求 |
| MF-13 | I/H PASS | 1 | 父路径null不覆盖 |
| MF-14 | I/H PASS | 1 | 父路径标量不覆盖 |
| MF-15 | I/H PASS | 0 | 真实模拟未授权不发HTTP |
| MF-16 | I/H PASS | 1 | LIVE仅真实HTTP |
| MF-17 | I/H PASS | 1 | 响应null是类型错误 |
| MF-18 | I/H PASS | 0 | 已有错误类型由原Core ABORT |
| MF-19 | I/H PASS | 0 | Mock timeout不触网 |
| MF-20 | I/H PASS | 0 | 请求映射null不发送 |
| MF-21 | I/H PASS | 0 | 原M1缺失请求不受Catalog影响 |
| MF-22 | I/H PASS | 0 | 原M1完整输入保持15分 |
| MF-23 | I/H PASS | 0 | HTTP无凭证时失败，不能降级Mock |
| MF-24 | I/H PASS | 1 | 已有黑名单不被false覆盖，静态预取仍发生 |
| MF-25 | I/H PASS | 1 | 数组父路径不得替换 |
| MF-26 | I/H PASS | 1 | 非法JSON响应不自动重试 |
| MF-27 | I/H PASS | 1 | 重复响应键被严格解析拒绝 |
| MF-28 | I/H PASS | 0 | Mock错误不触网 |
| MF-29 | I/H PASS | 0 | 连接失败保留字段错误 |
| MF-30 | I/H PASS | 0 | LIVE不执行MOCK配置 |

MF-29 是连接拒绝：实际接收 POST=0，但客户端一次 attempted=true；不把连接尝试写成服务端收到的请求。MF-09 使用真实5秒延迟、3秒总预算、10秒 Provider 预算，宿主返回504/无decision。MF-06 使用局部300ms预算，总预算充足后由原 Terminal 得到 REVIEW。Mock、已有值、显式null、父路径阻挡和 partial 的字段状态/来源、配置revision、统一traceId均有实际响应断言。成功与错误响应按冻结 M2 Schema（引用冻结 M1 DTO）验证。

| MS/工程项 | 结果与测试 |
| --- | --- |
| MS-01～08 | I/H PASS；管理 API 分别返回 OR-FACT-CONFIG（01～05）、OR-FACT-IDENTITY（06）、OR-REVISION-CONFLICT（07）、OR-REQUEST-INVALID（08）；拒绝后原资源/revision/document 不变 |
| MT-05 | `concurrentCasAndPrefixCreationIncludingEmptyTable`：Provider/Catalog CAS 两次真实HTTP请求一胜一冲突；空表和已有表前缀不双成功。`deterministicTwoConnectionCasAndEmptyTableRangeLock`：锁内屏障记录两个实际 MySQL CONNECTION_ID，确定触发CAS等待和空表并发gap锁；一成功一409。另验证FK和document/provider_id同事务状态 |
| MT-06 | `executionUsesOneSnapshotAndDatabaseTransactionEndsBeforeHttp`：HTTP阻塞时由专用库的root观察 innodb_trx=0；同时更新 Provider 和绑定不阻塞。当前请求仍APPROVE/15、全部旧revision；下一请求用新Provider revision并进入原数据复核 |
| MT-07 | `FactRestartIT`：记录两个不同Java PID，停止/重启仅本测试Java和MySQL；比较全部目录、全部Provider及PUBLISHED资源完全一致，M2 Mock与原M1 LIVE仍15分。恢复时重新inspect随机映射端口，不使用Docker重启前的缓存端口 |
| MT-08（后端部分） | 管理API→MySQL→GET→Mock保留39位小数和9007199254740995；实际HTTP/Mock输入及body边界、128位数字和迟到响应由B1回归覆盖；新宿主另验证慢完整body超时。Vue数值保存未执行 |
| MT-09（后端部分） | 实际secretRef环境解析和Bearer头；Mock解析次数0；敏感caller值/认证值不在响应，字段摘要/变量/输出/明细按B1统一投影；管理错误、执行错误与Schema均验证 |
| MT-11 / M1 OA | 19个冻结子用例分别通过原 SIMULATE 和 LIVE，共38次实际宿主HTTP；核对状态、decision、score、segment、Terminal、reasonCodes、nodeStatus；Catalog存在仍0 Provider调用。新入口使用相同冻结Definition/checksum |
| 相关 M1 ST | 宿主实测不完整草稿拒绝、已发布不可写、CAS、stale revision、精确版本/草稿LIVE拒绝、checksum破坏拒绝并finally恢复、M2请求误投M1拒绝、重启恢复；原Core/Spring/API回归和16个MySQL IT覆盖并行/分箱/事务/legacy等既有守卫 |

## 7. 异常运行、未覆盖项和停止边界

环境问题已解决：首次 Docker socket 不存在，启动 OrbStack 后运行全部真 MySQL IT；沙箱端口/容器权限通过受批准的执行环境运行。没有将 Docker 不可用或跳过计为 PASS。

初次 IT 装配失败（Boot jar 嵌套类路径）、观察 innodb_trx 的普通用户缺 PROCESS 权限、Docker重启后随机端口改变，均保留在 `host-focused-it*.log`。修复测试装配/观察方法后重跑；最终结论只采用上述明确成功的全目标日志。没有修改冻结 fixture 预期。末轮 Unicode 查询边界补测发现 ASCII ID LIKE 参数的字符集错误，已修复为 utf8mb4 检索表达式；128/129 code point 回归保留，原失败记录为 `unicode-query-failure.log`。

本批不实现/不执行：前端、浏览器操作、3个viewport视觉对照、MT-10、M1 ST的浏览器异常恢复部分、生产真实上游或生产部署。MySQL容器测试运行完自动退出；独立Java和受控脚本进程均只关闭本轮创建者。演示启动命令供后续F1复现，没有擅自留下新的常驻应用或操作已有M1数据。

没有新增删除接口、配置发布/历史、历史Trace、Scene/Release、缓存或新生产基础设施。未改ADR状态，未提交、未push，未执行破坏性Git清理。B2到此停止，不进入前端或V1，也不声明Studio M2总体验收通过。
