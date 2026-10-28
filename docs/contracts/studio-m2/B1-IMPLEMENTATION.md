# Studio M2-B1 实现与交接

日期：2026-10-26。范围：Studio M2 事实数据层的 B1。基线 HEAD：`348a48d209f0571049535f4b513eef556859876b`。本批源码处于未提交工作区。

B1 实现 requiredFacts、共享绝对 Deadline、事实解析 SPI、配置快照输入、补数编排和 HTTP/Mock。复用现有 StudioEngine，不实现第二套决策逻辑。ADR-0006 保持 Proposed。没有进入 B2。

## 1. 文件与公开 Java 类型

以下路径相对于后端根目录。没有修改 M1 Definition、Schema、fixture、checksum 算法、原入口或宿主代码。

| 路径 | 修改及公开类型 |
| --- | --- |
| `openrule-core/src/main/java/io/openrule/core/studio/StudioCompiler.java` | 增加 `compilePlan(Flow)`；原 `compile(Flow)` 保留 |
| 同目录 `StudioCompiledPlan.java` | `StudioCompiledPlan`，只能由编译器构造，持有深度不可变 Flow 与依赖 |
| 同目录 `RequiredFact.java` | `RequiredFact`、`RequiredFact.Use`；原文 pointer、稳定 ID、VALUE/PRESENCE、类型提示与冲突诊断 |
| 同目录 `StudioFactDependencies.java` | 包内 AST 遍历、值冻结与 Unicode code point 排序 |
| 同目录 `StudioDeadline.java` | `StudioDeadline`，单调绝对期限、剩余纳秒和取消信号 |
| 同目录 `StudioEngine.java` | 增加 `execute(StudioCompiledPlan, Map, StudioDeadline)`，与 M1 复用同一执行方法 |
| `openrule-spring/src/main/java/io/openrule/spring/fact/FactConfiguration.java` | `FactConfiguration` 及 `Mode`、`Purpose`、`ValueType`、`Mapping`、`Constant`、`Fact`、`MockOutcome`、`MockSettings`、`HttpSettings`、`Provider`、`Binding`、`Snapshot` |
| 同目录 `FactProvider.java` | `FactProvider`，按 HTTP/MOCK 注册，拒绝重复类型 |
| 同目录 `SecretResolver.java` | `SecretResolver` 宿主 port |
| 同目录 `FactBatchRequest.java` | `FactBatchRequest`、`Attempt`；不可变数据加共享取消/调用进度信号 |
| 同目录 `FactBatchResult.java` | `FactBatchResult`、`Status`、`Field`；内部精确值及逐字段状态 |
| 同目录 `FactResolutionRequest.java` | `FactResolutionRequest`，目的、模式、原始 callerFacts、依赖、配置快照和同一个 Deadline |
| 同目录 `FactResolutionResult.java` | `FactResolutionResult`，内部合并 facts、安全报告及请求级 errorCode |
| 同目录 `FactResolutionReport.java` | `FactResolutionReport`、`Field`、`Call`、`Error`；脱敏报告 |
| 同目录 `FactResolver.java` | `FactResolver`，按 Provider ID 串行分组、只补 missing、默认 64 在途上限 |
| 同目录 `HttpFactProvider.java` | `HttpFactProvider`，JDK 21 POST、固定 Bearer、禁止重定向、限长响应订阅器 |
| 同目录 `MockFactProvider.java` | `MockFactProvider`，无 HTTP、无 SecretResolver |
| 同目录 `FactRedaction.java` | `FactRedaction.execution(Result)`，M2 共用的保守执行结果投影 |
| 同目录 `FactValues.java`、`FactTransportSupport.java` | 包内精确 JSON 值、Pointer、补丁、严格编解码、1 MiB 边界 |
| `openrule-spring/pom.xml` | 仅为该模块 Surefire JVM 设置 JDK 无重试参数 |
| `openrule-core/src/test/java/io/openrule/core/studio/StudioM2Test.java` | AST 提示/短路、原位置、不可变计划、Deadline/取消回归 |
| `openrule-spring/src/test/java/io/openrule/spring/fact/FactFixtureTest.java` | MF-01～30 动态测试；冻结依赖及重排断言 |
| 同目录 `FactRuntimeTest.java`、`FactTestSupport.java` | 受控本地 HTTP 服务、取消/限流/精度/隔离/脱敏及装配测试 |
| `docs/contracts/studio-m2/B1-IMPLEMENTATION.md` | 本交接文档 |

本批不新增模块、外部依赖、数据库表、管理 API、宿主执行入口或前端。Core 新代码仅使用 JDK。原有未跟踪 D0 文档和 `output/` 保留。

## 2. 行为与结果边界

- requiredFacts 是静态引用。它不参与 Definition 序列化或 checksum，不在 Core 执行前统一拒绝缺失字段。类型冲突只诊断。
- `compilePlan` 对原输入收集位置，再保存排序后的不可变执行 Flow。Stage/Node/行/特征/分箱重排不会把 pointer 换成执行顺序索引。
- callerFacts 在解析请求构造时深度冻结。已有值、显式 null、错误类型值均记录为 CALLER/resolved。该状态表示已取得值，不表示已通过节点类型校验。
- 只有 missing 且可写的绑定字段进入组。同 Provider 一次调用；Provider ID 排序串行。映射只读取原始 callerFacts，不能读取前组结果。未请求响应字段不进入补丁。
- 只创建缺失的 object 中间节点。已有 null、标量、数组父节点不得替换。响应 missing 与 null/类型错误分别返回 missing 与 failed。partial 保留成功字段。
- `FactResolutionResult.successful()` 只表示没有请求级技术失败。报告 PARTIAL/UNRESOLVED 时仍应进入原 Core，由实际求值、FailPolicy、Terminal 决定业务结果。
- Provider 局部超时在总预算充足时返回字段 OR-FACT-TIMEOUT。总期限、取消和过载分别返回 OR-EXECUTION-TIMEOUT、OR-EXECUTION-CANCELLED、OR-FACT-OVERLOADED；宿主不得继续产生业务 decision。
- callDeadline 包含工作排队、映射、secret、连接、响应头、完整 body 和解析。调度线程以不超过 10ms 的有界等待观察取消信号，不向上取整剩余预算。
- 超时/取消会取消 Future 并请求中断。排队任务取消时归还许可；已运行任务仅在 worker finally 退出时归还。忽略中断的任务不能靠超时反复释放许可。返回快照没有迟到写入通道。
- HTTP 请求和响应上限均为 1,048,576 UTF-8 bytes；响应在订阅过程中限长。复用 M1 严格 codec，拒绝重复键、非法 JSON、尾随内容和超限数字；额外拒绝非法 UTF-8。
- Mock RESPONSE/TIMEOUT/ERROR 使用配置快照。TIMEOUT 不睡眠，不虚构耗时。Mock 也检查请求映射、响应类型和精度，但不会解析 secret。

## 3. B2 装配与调用

### JVM 与资源

JDK HttpClient 默认会自动重试 ConnectException，公开 builder 没有按客户端关闭重试的选项。这与 D0 的无自动重试约束存在实际适配差异。本实现用以下 JVM 启动参数解决；未设置时 `HttpFactProvider` 构造即失败，避免静默违反契约：

```sh
java -Djdk.httpclient.disableRetryConnect=true \
     -Djdk.httpclient.enableAllMethodRetry=false \
     -jar openrule-studio-server/target/openrule-studio-server-1.0.0-SNAPSHOT.jar
```

必须在 JVM 启动时设置，不能在运行期 `System.setProperty`；JDK 在第一次 HTTP 使用时缓存这些值。参数是 JVM 范围，会同时关闭该进程内其他 JDK HttpClient 的连接重试。Surefire 已在测试进程启动时设置；B2 须将参数加入宿主启动配置。

宿主应创建单例 FactResolver，读取 `studio.facts.max-in-flight`，默认 64。单例的许可覆盖全部请求。executor 由宿主管理；不要每个请求创建 resolver，也不要让 orchestrator 与其 Provider worker 互相占满同一个有界线程池。参考装配：

```java
import io.openrule.core.studio.*;
import io.openrule.spring.fact.*;
import java.util.*;
import java.util.concurrent.*;

ExecutorService factWorkers = Executors.newVirtualThreadPerTaskExecutor();
ExecutorService nodeWorkers = Executors.newVirtualThreadPerTaskExecutor();
SecretResolver secrets = reference -> {
    if (!reference.matches("env:OPENRULE_[A-Z0-9_]+")) return null;
    return System.getenv(reference.substring("env:".length()));
};
FactResolver resolver = new FactResolver(factWorkers,
        List.of(new HttpFactProvider(secrets), new MockFactProvider()), 64);
StudioEngine engine = new StudioEngine(nodeWorkers);
StudioCompiler compiler = new StudioCompiler();
io.openrule.spring.studio.StudioDocumentCodec codec = new io.openrule.spring.studio.StudioDocumentCodec();
// 宿主关闭时 shutdownNow/close 两个 executor；resolver 不接管其生命周期。
```

### 新入口调用顺序

在合法 M2 请求被接收后，先创建 traceId 与 Deadline。加载精确 Flow、编译和短事务快照都使用这份预算。在数据库事务结束后才能调用 resolver。

```java
import static io.openrule.spring.fact.FactConfiguration.*;

String traceId = UUID.randomUUID().toString();
StudioDeadline deadline = StudioDeadline.afterMillis(timeoutMillis);

// 在此加载精确 definition，并校验身份/revision/PUBLISHED/checksum；读取一致 snapshot。
// definition 保留原文数组顺序，snapshot 事务在 resolve 前结束。
StudioCompiledPlan plan = compiler.compilePlan(codec.bind(definition));
// snapshot 是短只读事务形成的 FactConfiguration.Snapshot，调用前事务必须结束。
FactResolutionResult resolution = resolver.resolve(new FactResolutionRequest(
        traceId, Purpose.SIMULATE, Mode.HTTP, plan.requiredFacts(),
        callerFacts, snapshot, deadline));
if (!resolution.successful()) {
    // 投影 resolution.report()；execution=null。
    // TIMEOUT -> 504，OVERLOADED -> 503，CANCELLED -> 500；decision 不存在。
    return;
}
StudioModel.Result core = engine.execute(plan, resolution.facts(), deadline);
StudioModel.Result safeExecution = FactRedaction.execution(core);
// 沿用 M1 DTO 映射 safeExecution；报告取 resolution.report()。
// Core FAILED 时附实际 execution、decision=null；不得自行构造 REVIEW。
// 宿主继续核对响应投影阶段的总期限，并用入口 start 计算 totalElapsedMillis。
```

`codec.bind(definition)` 之前应执行原有 Schema/可执行校验；不要把 `codec.executable(definition)` 的排序结果作为 compilePlan 输入，否则 requiredFacts 原文数组位置会丢失。required-facts API 可使用 `plan.requiredFacts()`，宿主补 flow/version/revision/checksum。

### 配置快照转换

OSS 类型只包含执行所需值，不包含管理文档、表名、CAS 或事务。宿主从一次事务读取的行构造：

```java
Provider provider = new Provider("risk_profile", "1", true,
        Map.of("buyerId", new Fact("/buyer/id"), "region", new Constant("GLOBAL")),
        new MockSettings(MockOutcome.RESPONSE,
                Map.of("data", Map.of("blacklisted", false,
                        "ageDays", new java.math.BigDecimal("365"),
                        "refundRate", new java.math.BigDecimal("0.08")))),
        new HttpSettings(java.net.URI.create("http://127.0.0.1:19091/v1/buyer/batch"),
                100, 300, null, true, true));
Binding age = new Binding("buyer_age_days", "1", "/buyer/ageDays", ValueType.NUMBER,
        false, "risk_profile", "/data/ageDays");
Snapshot snapshot = new Snapshot(List.of(age), List.of(provider));
```

完整订单快照还需从 catalog.json 转换其他字段。`ValueType.from(documentType)` 接受 number/string/boolean。MOCK 类型 Provider 的 http=null；HTTP 配置选 Mode.MOCK 时仍使用其 mock，不解析 secret。LIVE 只能 Mode.HTTP。MOCK_TEST 只能 Mode.MOCK；宿主从该 Provider 当前绑定字段构造 requiredFacts，无绑定时传空列表，返回 COMPLETE 空报告，不执行 Core。

内部 `FactBatchResult.Field.value`、`FactResolutionResult.facts` 只能交给内部编排/Core，不能直接序列化到 API。API 使用安全 report 与 `FactRedaction.execution`；该投影保留键、明细 ID、最终 score 和 ScoreDetail.score，屏蔽变量、node outputs、RuleDetail.value 和原失败消息，同时保留引擎故障分类。traceId 在所有 Call 与宿主 execution 使用同一值。

## 4. Fixture 与测试映射

`FactFixtureTest.frozenMfCases` 按 fixture 中 id 建立 30 个独立 DynamicTest。每例读取指定 M1 定义、catalog 和 provider 文档，执行 null-preserving recursive overlay，不改预期。真实服务随机绑定 127.0.0.1 端口，断言 POST 次数、方法及请求体 `{"buyerId":"buyer_1024","region":"GLOBAL"}`；Mock/预检失败断言 0 POST。

| Fixture | B1 结果 | 实际本地 POST | 核对内容 |
| --- | --- | --- | --- |
| MF-01 | PASS | 1 | 一次HTTP补齐三个字段 |
| MF-02 | PASS | 0 | 完整facts不调用 |
| MF-03 | PASS | 1 | 已有年龄不被上游365覆盖 |
| MF-04 | PASS | 0 | 显式null不触发补数 |
| MF-05 | PASS | 1 | partial保留其他成功字段 |
| MF-06 | PASS | 1 | Provider超时后仍有预算 |
| MF-07 | PASS | 0 | 缺buyerId阻止HTTP |
| MF-08 | PASS | 1 | 响应退款率字符串类型错误 |
| MF-09 | PASS | 1 | 总预算耗尽不进入Core |
| MF-10 | PASS | 0 | Mock成功零HTTP且不解析缺失secret |
| MF-11 | PASS | 1 | HTTP失败不回退Mock |
| MF-12 | PASS | 0 | 禁用Provider不请求 |
| MF-13 | PASS | 1 | 父路径null不覆盖 |
| MF-14 | PASS | 1 | 父路径标量不覆盖 |
| MF-15 | PASS | 0 | 真实模拟未授权不发HTTP |
| MF-16 | PASS | 1 | LIVE仅真实HTTP |
| MF-17 | PASS | 1 | 响应null是类型错误 |
| MF-18 | PASS | 0 | 已有错误类型由原Core ABORT |
| MF-19 | PASS | 0 | Mock timeout不触网 |
| MF-20 | PASS | 0 | 请求映射null不发送 |
| MF-21 | PASS | 0 | 原M1缺失请求不受Catalog影响 |
| MF-22 | PASS | 0 | 原M1完整输入保持15分 |
| MF-23 | PASS | 0 | HTTP无凭证时失败，不能降级Mock |
| MF-24 | PASS | 1 | 已有黑名单不被false覆盖，静态预取仍发生 |
| MF-25 | PASS | 1 | 数组父路径不得替换 |
| MF-26 | PASS | 1 | 非法JSON响应不自动重试 |
| MF-27 | PASS | 1 | 重复响应键被严格解析拒绝 |
| MF-28 | PASS | 0 | Mock错误不触网 |
| MF-29 | PASS | 0 | 连接失败保留字段错误 |
| MF-30 | PASS | 0 | LIVE不执行MOCK配置 |

MF-09 验证总期限错误及 execution=null；测试中的 504 是 B1 错误投影断言，不是已实现的宿主 HTTP 状态。MF-16 只验证 LIVE 目的及 HTTP 补数；真实 PUBLISHED 读取留 B2。MF-21/22 直接使用原 M1 入口，不构造新的字段报告。MF-29 的 fixture.httpCalls=1 表示一次客户端连接尝试；连接被拒绝时，本地服务实际接收 POST=0，测试另断言 Call.attempted=true。

| 资产/验收项 | B1 证据与边界 |
| --- | --- |
| 冻结 required-facts.json | `requiredFactsMatchFrozenFixtureAndOriginalPointersAfterReorder`，精确比对 5 路径/10 原文位置，重排全部数组后核对 pointer 和 ID |
| MT-01/02 | `StudioM2Test` + 上述冻结比对；五种节点、Stage.when、all/any/not、root/转义/数组读、presence、类型提示冲突、Unicode 排序、短路；VARIABLE/NODE/scoreRef 不进入依赖 |
| MT-03 | `providersAreSerialSortedAndMappingsNeverReadEarlierProviderResults`；两 Provider 串行、同组多字段一次、映射不读前组补值、无字段零调用 |
| MT-04 | `absoluteDeadlineCannotBeResetAndCancellationHasNoDecision`、`providerAndCoreConsumeOneBudgetAndTotalTimeoutWinsAtEqualLimits`、`slowCompleteBodyUsesProviderBudgetAndLateBodyNeverMerges`、`interruptedRealHttpRestoresInterruptAndSignalCancellationStopsRealHttp`、`inFlightPermitIsNotReleasedUntilUncooperativeWorkerActuallyEnds`、`queuedCancellationAndExecutorRejectionReleasePermits` |
| MT-08（B1） | `bodyByteBoundaryExactNumbersAndNoRetryOrRedirect`、`immutableInputsMockPrecisionAndRedactionDoNotLeakCallerOrSecret`、`requestBodyLimitSecretAndSnapshotValidation`；HTTP/Mock 39 位小数、大于 2^53 整数、128/129 位边界、指数、1MiB/1MiB+1、慢 body、请求限长 |
| MT-09（B1） | MF 的 source/revision/traceId 断言；安全 Field 摘要、未知/敏感值屏蔽、Unicode 摘要截断、执行结果投影、失败消息屏蔽、secret 预检与 Bearer；所有真实宿主入口的接线留 B2 |
| MT-11（B1） | 原有 `StudioB3FixtureTest` 跑冻结 19 OA 子用例；Core/Spring 全量回归及 API MockMvc、legacy/checksum 静态检查；ST 的 MySQL/真实宿主/浏览器部分未重跑 |
| MS-01～05 | B1 Snapshot/HttpSettings 构造守卫覆盖数字/危险路径、未知 Provider、超时顺序和前缀重叠；不代替 B2 管理 API、并发事务验收 |
| manifest/schema/openapi 及全部引用 fixture | 原静态校验器通过；这些样例没有全部变成 B1 Java 管理 API 用例。MS-06～08 的管理更新/CAS 属于 B2 |

## 5. 实际验证

环境：macOS；Temurin JDK 21.0.11+10。以下命令从后端根目录执行，`PATH` 的 JDK bin 用于静态脚本中的 javac/java：

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl openrule-core,openrule-spring -am test
mvn clean verify
python3 -u docs/contracts/studio-m2/validate.py
./scripts/validate-studio-m1.sh
```

| 命令/检查 | 实际结果 | 证据（仓库相对路径） |
| --- | --- | --- |
| Core/Spring `mvn -pl openrule-core,openrule-spring -am test` | PASS；Core 96 + Spring 71 = 167，0 failure/error/skipped | `output/m2-b1/module-tests.log` |
| `mvn clean verify` | PASS；Core 96 / Spring 71 / API 15 / JDBC 4，共 186，0 failure/error/skipped；宿主 jar 重建 | `output/m2-b1/clean-verify.log` |
| M2 静态契约 | PASS；8 新路径/12 操作，31 Schema 定义，271 valid instances、11 invalid instances 拒绝，5 路径/10 位置及冻结 checksum | `output/m2-b1/m2-contract.log` |
| M1 契约检查 | PASS；5 valid/4 schema invalid、3 semantic invalid、8 路径/10 操作、10 checksum 向量、19 OA 关联、legacy 原字节摘要 | `output/m2-b1/m1-contract.log` |
| Core jar 依赖、工作区差异 | Core jar 仅依赖 java.base；`git diff --check` 通过；M1/ADR/宿主/API/JDBC 无已跟踪差异 | `output/m2-b1/core-dependencies.log`、`output/m2-b1/source-manifest.json` |

受控 HTTP 测试实际运行于允许绑定本地端口的执行环境。第一次沙箱运行报 `Socket Operation not permitted`，未计成功；放行后重跑。早期测试自身的 Terminal 测试构造、映射准备及 ID 断言错误已修正，没有改 fixture 预期。保留原失败日志 `initial-sandbox-test.log`、`initial-http-test.log`。补 JDK 无重试保护前的成功记录保留为 `*-before-no-retry-guard.log`；最终结论只使用上表最新日志。最终无 B1 环境阻塞。

## 6. 未执行项与停止边界

| 项目 | 状态与后续责任 |
| --- | --- |
| 宿主新增管理/required-facts/fact-simulations/fact-executions/mock-tests API | NOT_RUN / 未实现，属于 B2；B1 测试调用 Java 编排器，不冒充宿主 HTTP API |
| M2 配置数据库、CAS、事务、双连接并发、快照读取事务边界、重启恢复 | NOT_RUN，属于 B2；MT-05～07 及 MS 管理语义验收留后续 |
| 真实 PUBLISHED 读取、traceId 贯穿新 API、所有入口脱敏/状态码和总耗时投影 | NOT_RUN，属于 B2；已提供共享 Java 能力与示例 |
| MySQL integration profile、宿主 MySQL IT | NOT_RUN；本批不涉及 JDBC/DDL。默认 clean verify 的 JDBC 4 项是单元/装配测试，不是 MySQL IT |
| 浏览器、Vue、MT-10、3 个 viewport 视觉验收 | NOT_RUN，属于 F1/V1 |
| M1 ST 的真实 MySQL/宿主 HTTP/浏览器/视觉层 | 本轮 NOT_RUN；仅声明本轮冻结 OA Java、Core/Spring/API MockMvc 和静态契约回归 |

除 JDK HttpClient 的全 JVM 重试参数这一已处理适配差异，没有发现需改变 D0 业务契约的冲突。没有改 ADR 状态，没有提交、push、部署或破坏性 Git 清理。B1 到此停止，不自动进入 B2，也不声明 Studio M2 总体验收通过。
