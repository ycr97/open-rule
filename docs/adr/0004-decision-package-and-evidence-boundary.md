# ADR-0004：不可变决策包与回放证据属于 OSS 基础层

> 状态：Accepted
> 日期：2026-08-20
> 实施批次：M1.6 预留执行契约；M2d 实现包、codec 和证据端口

## 背景

现有规划把不可变发布包放在 EE，把 OSS FlowLoader 定义为“读取当前启用版本并编译”。但以下能力并非企业控制台专属：

- 确认正在运行的定义和插件版本；
- 对缓存内容建立不可变身份；
- 复现历史决策；
- 检查定义在当前引擎上的兼容性；
- 为发布物生成 checksum、SBOM 或签名。

另一方面，当前 JDBC 审计会脱敏 facts。脱敏数据适合查询和运营审计，但不一定能重放。把审计日志直接当回测数据源会得到不可证明的结果。

## 决策

### 1. 能力归属

OSS 提供：

- Definition JSON schema 与 canonical codec；
- 不可变 DecisionPackage 格式；
- checksum、兼容检查和签名 SPI；
- ExecutionPurpose、副作用等级、证据记录和回放端口；
- 单包加载与按 digest 缓存。

EE 增加：

- tenant、Scene、Strategy 和 RouteRevision；
- 审批、职责分离、发布状态机和配额；
- 多包灰度、回滚、回测任务编排和漂移监控；
- Kafka/ClickHouse 等规模化实现。

因此 EE 只能依赖 OSS 包格式，不能定义另一套不兼容的 package checksum 或 replay record。

### 2. 目标模块边界

```text
openrule-core
  ├── definition/plan/runtime/SPI
  └── ExecutionPurpose、EffectKind、Evidence SPI

openrule-codec-json
  ├── Definition Schema migrator
  ├── strict JSON binding
  └── RFC 8785-compatible canonical JSON

openrule-package
  ├── DecisionPackage / PackageManifest
  ├── checksum / compatibility verifier
  └── PackageSigner / PackageSignatureVerifier SPI

openrule-spring
  └── 装配、缓存、Service；依赖上述 OSS 模块
```

M1.6 不立即增加后两个模块，但必须冻结其所依赖的 type ID、configVersion、PluginDescriptor、ExecutionPurpose 和不可变值模型。

### 3. DecisionPackage 内容

包是内容寻址、不可修改的逻辑制品。物理格式为 ZIP，条目按名称升序，时间戳归零，不使用平台相关路径：

```text
manifest.json
definition.json
dependencies/<kind>/<id>/<version>/...   # 仅允许内联的依赖
signature.json                            # 可选，签名不参与 contentSha256
```

```java
public record PackageManifest(
        int formatVersion,
        String packageId,
        String contentSha256,
        String definitionSha256,
        int definitionSchemaVersion,
        String engineApiVersion,
        String compilerVersion,
        FlowIdentity source,
        List<PluginRequirement> plugins,
        List<PackageDependency> dependencies,
        ResolvedExecutionDefaults defaults,
        Instant createdAt) {}
```

```java
public record PluginRequirement(
        String pluginId,
        String pluginVersion,
        String compilerVersion,
        String typeId,
        int configVersion) {}

public record PackageDependency(
        String kind,
        String id,
        String version,
        String sha256,
        boolean embedded) {}
```

checksum 规则：

1. definition 先升级到当前 schema，再 canonicalize；
2. manifest 中除 `packageId/contentSha256/createdAt` 外的语义字段 canonicalize；
3. embedded dependency 逐文件计算 SHA-256；
4. `contentSha256 = SHA-256(manifest semantic bytes + definition bytes + dependency digests)`；
5. `packageId = "pkg_" + contentSha256 前 24 个十六进制字符`；
6. `createdAt` 和签名不影响内容身份。

同一语义输入在不同机器和时间构包，必须得到相同 packageId。

### 4. 加载兼容检查

PackageLoader 在 compile 前依次检查：

1. ZIP 路径安全、条目数量和解压大小限制；
2. content/definition/dependency checksum；
3. formatVersion 与 definitionSchemaVersion；
4. engineApiVersion 兼容范围；
5. 每个 type 的 pluginId、pluginVersion、compilerVersion、configVersion；
6. 可选签名策略；
7. Definition validate 与 compile。

任何不兼容均在缓存前失败。缓存 key 是完整 contentSha256，不使用可变 flowId 或 active pointer。

### 5. 审计与回放证据分离

```java
public record AuditRecord(
        ExecutionIdentity execution,
        FlowIdentity flow,
        String packageId,
        Decision decision,
        List<String> hitNodes,
        Duration elapsed,
        DecisionObject redactedFacts,
        Instant occurredAt) {}

public record ReplayRecord(
        ExecutionIdentity execution,
        String packageId,
        DecisionObject canonicalFacts,
        List<NodeEvidence> nodeEvidence,
        String factsSha256,
        Instant occurredAt) {}
```

AuditRecord 用于检索、运营和合规展示，可以脱敏并采用 best-effort 或 durable 策略。ReplayRecord 用于精确重放，必须加密、严格授权、具备保留期限和删除策略，不能默认暴露给日志查询 API。

是否启用 ReplayStore 是部署选择，但“审计日志存在”不得被解释为“决策可重放”。

### 6. 外部数据与副作用

```java
public enum EffectKind {
    PURE,
    READ_EXTERNAL,
    WRITE_EXTERNAL
}

public interface EvidenceAwareDataAccess {
    DecisionValue read(ExternalReadRequest request,
                       NodeExecutionInput input);
}

public interface EvidenceRecorder {
    void record(NodeEvidence evidence);
}
```

NodePlan 必须声明 EffectKind：

| purpose | PURE | READ_EXTERNAL | WRITE_EXTERNAL |
|---|---:|---:|---:|
| LIVE | 允许 | 允许，记录版本/响应摘要 | 允许，但要求幂等/事务声明 |
| SIMULATE | 允许 | 仅 mock 或显式允许的只读源 | 禁止 |
| BACKTEST | 允许 | 仅 ReplayRecord 命中 | 禁止 |

BACKTEST 缺少外部读取证据时返回 `OR-REPLAY-EVIDENCE-MISSING`，样本状态为 `UNCERTAIN`，不得访问真实外部系统，也不得把该样本计入确定性决策差异分母。

节点自行 new HTTP/JDBC 客户端不会被 Core 技术性阻止，但此类插件不能获得 `REPLAYABLE` 认证，发布器可以按治理策略拒绝进入需要回测的包。

### 7. 证据敏感性

每个 evidence 字段带数据分类：`PUBLIC`、`INTERNAL`、`SENSITIVE`、`RESTRICTED`。Recorder 根据租户策略决定加密、脱敏和保留时间。NodeResult.details 只承载可返回/可审计摘要，不允许默认塞入完整特征或外部响应。

## 被拒绝的方案

### 决策包只属于 EE

会导致 OSS 使用可变 active 定义，EE 再实现一套编译和 checksum 语义，最终形成两个运行内核边界。

### 把 CompiledFlow Java 序列化进包

Java 对象序列化与类版本、JDK 和插件实现强绑定，也扩大反序列化攻击面。包保存 canonical Definition 和依赖，加载时在目标引擎重新 compile。

### 使用脱敏执行日志直接回测

脱敏字段可能正是决策输入，结果既不可复现也无法判断差异来自规则还是数据损失。

### 回测时允许真实只读调用

历史时点的数据通常已经变化，会把“当前外部状态”混入历史回放，破坏可解释性。

## 结果

- OSS/EE 共用一套制品身份和兼容模型；
- EE RouteRevision 只切换 packageId，不修改包内容；
- 审计查询与高敏重放数据各自满足不同安全和可靠性目标；
- M1.6 必须把 ExecutionPurpose、EffectKind 和插件描述信息纳入 Core 契约。

## M2d 验收

- 同一 Definition 在 macOS/Linux 构建产生同一 packageId；
- 修改一个语义值、插件版本或依赖摘要都会改变 packageId；
- 修改 createdAt 或重新签名不改变 packageId；
- zip-slip、超大解压、checksum 损坏、未知插件和不兼容 schema 全部在 compile 前拒绝；
- BACKTEST 对 WRITE_EXTERNAL 的调用次数严格为 0；
- 缺证据样本稳定标记 UNCERTAIN，报告同时展示确定样本数和证据覆盖率。
