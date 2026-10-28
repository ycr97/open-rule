# OpenRule M2b · openrule-jdbc（JDBC 持久化适配器）实现设计

> 配套文档：`open-rule-engine-technical-spec.md`（完整技术规范）、`docs/superpowers/specs/2026-06-22-openrule-m2a-spring-design.md`（M2a 设计）、`docs/sessions/2026-06-22-openrule-m2a-session.md`（M2a session）。
> 本文档覆盖 **M2b：在公开 `open-rule` 仓库内提供一组「中立、零 ycr」的 JDBC 持久化适配器**，把 M2a 的内存端口替换为真 MySQL；用 Testcontainers 真库验证。
> 日期：2026-06-23

---

## 0. 背景与定位

M2a 交付了端口-适配骨架（3 端口 + standalone 内存实现 + REST），用纯 Spring Boot 即可启动、零中间件、78 测试全绿。M2b 把其中两个端口换成真 MySQL 实现，并补齐「完整 admin」。

两条约束（沿用 M1/M2a）决定形态：

1. **公开仓库零 ycr 依赖**：`open-rule` 是公开 GitHub 项目，M2b 的持久化必须用中立技术栈（JdbcTemplate），不得引入 ycr `BaseMapperX`/`BaseDO` 或任何 ycr starter。
2. **standalone 纯净性不退化**：`openrule-spring` 不得被 jdbc/DataSource 污染；引入持久化 = 引入一个**新的可选模块**，不引入则保持 M2a 内存形态。

> **ycr 适配是独立的将来一轮**：把端口绑到 ycr data-mp/cache、响应对齐 `R<T>`/`ErrorCode` 的 `ycr-starter-rule` 在 `ycr-framework` 仓库里做，公开 `open-rule` 对此一无所知。本轮（C 决策的 A 部分）只做公开仓库的中立实现。

**M2b 目标**：交付一个引入即切 MySQL、Testcontainers 真库可验证的持久化适配器组 + 完整 admin。验收 = `mvn test` 全绿（M2a 78 + 新增单测 + Testcontainers 集成，Docker 缺席自动跳过）+ 真库「注册→执行→查日志→回滚」跑通。

---

## 1. 工程决策（已确认）

| 项 | 决策 | 理由 |
|---|---|---|
| 模块形态 | **新增第 4 模块 `openrule-jdbc`** | JDBC 适配器组，与 standalone 内存适配器并列；保住 `openrule-spring` 轻量 |
| 持久化技术 | **JdbcTemplate**（`spring-boot-starter-jdbc`） | 三张简单表 + JSON 大字段，零 ORM、依赖最薄、SQL 透明；不把 ORM 取向焊进公开仓库 |
| Redis 多实例热更新 | **本轮后延** | 与持久化正交；单进程 `LocalFlowChangeNotifier` 够用；多实例广播单独成轮或交 ycr |
| 版本/启用语义 | **沿用「save 即启用」** + 增 enable/rollback/versions/logs | 与 M2a 全兼容，e2e 不破；草稿→启用暂存模型留将来 |
| 异步审计 | **模块内自带虚拟线程 `ExecutorService`** | 自包含、不碰使用方全局 `@EnableAsync`、可独立测 |
| 集成测试 | **Testcontainers 真 MySQL**，Docker 缺席自动跳过 | 真实 schema/SQL 保真；补 ycr-framework「零 Testcontainers」短板；不挂断无 Docker 构建 |
| Java / Spring Boot | Java 21 / Spring Boot 3.3.5 | 对齐 M2a |

---

## 2. 范围与 YAGNI 取舍

### 2.1 做（M2b 范围内）
- 新模块 `openrule-jdbc`：`schema.sql` + `JdbcFlowDefinitionRepository` + `JdbcExecutionLogger` + `JdbcExecutionLogQuery` + `OpenRuleJdbcAutoConfiguration` + `OpenRuleJdbcProperties`。
- `openrule-spring`：新增只读端口 `ExecutionLogQuery` + 模型 `ExecutionLogEntry` + 内存实现；`OpenRuleService` 增版本治理方法（enableVersion/rollback/listVersions）+ 日志查询委派。
- `openrule-api`：`FlowAdminController` 增 4 端点（enable/rollback/versions/logs）+ DTO。
- 测试：jdbc 模块 Testcontainers 真库；spring 内存查询单测；api 4 端点 MockMvc（内存适配器）。

### 2.2 明确不做（后延）
- `or_script` 表与脚本节点（M3）。
- Redis Pub/Sub 多实例热更新（后续一轮 / ycr）。
- 「草稿→启用」暂存模型（沿用 save 即启用）。
- ycr 适配 `ycr-starter-rule`（ycr 仓库，独立一轮）。
- 自动建表到使用方库（仅提供 DDL 文档 + 测试用 `schema.sql`）。
- facts 之外的告警/指标体系（Micrometer 属 M5）。

---

## 3. 架构与模块

```
open-rule (reactor)
├── openrule-core            不变（M1 内核）
├── openrule-spring          +ExecutionLogQuery 端口 + ExecutionLogEntry + 内存查询实现
│                            +OpenRuleService 版本治理方法（enableVersion/rollback/listVersions/queryLogs）
├── openrule-api             +FlowAdminController 4 端点（enable/rollback/versions/logs）+ DTO
└── openrule-jdbc (新)       io.openrule.jdbc
    ├── repository/  JdbcFlowDefinitionRepository
    ├── audit/       JdbcExecutionLogger  JdbcExecutionLogQuery
    ├── support/     JsonRowMapper / 脱敏工具 / checksum 工具
    ├── autoconfigure/  OpenRuleJdbcAutoConfiguration  OpenRuleJdbcProperties
    └── resources/   schema.sql（Testcontainers 初始化 + 文档 DDL）
```

**依赖方向**：`jdbc → spring → core`（与 `api → spring → core` 平行；jdbc 与 api 互不依赖）。`openrule-jdbc` 依赖 `openrule-spring` + `spring-boot-starter-jdbc`，**不引入 ORM、不引入 ycr**。

**装配与覆盖**：jdbc 适配器 Bean 一律 `@ConditionalOnBean(DataSource.class)` + `@ConditionalOnMissingBean`。运行时若类路径含 `openrule-jdbc` 且容器有 `DataSource` → JDBC 实现覆盖 M2a 内存默认；否则保持内存形态。**编排（OpenRuleService/FlowLoader）零改动**。

**两种消费形态，一套代码**：
- **内存（M2a）**：只引 `openrule-api`/`openrule-spring`，无 DataSource → 内存适配器，零中间件。
- **MySQL（M2b）**：再引 `openrule-jdbc` + 配 `spring.datasource.*` → 自动切真库。

---

## 4. 数据库 schema（仅本轮所需）

`or_script` 属脚本节点（M3），**不建**。仅两表，置于 `openrule-jdbc/src/main/resources/schema.sql`（Testcontainers 初始化用；生产由使用方按此 DDL 自建，**不自动建表**）。

```sql
CREATE TABLE or_flow (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id         VARCHAR(100) NOT NULL,
    flow_name       VARCHAR(200) NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    enabled         TINYINT      NOT NULL DEFAULT 0 COMMENT '同一 flow_id 至多一个版本 enabled=1',
    aggregate_policy VARCHAR(32) NOT NULL DEFAULT 'PRIORITY',
    definition_json MEDIUMTEXT   NOT NULL,
    checksum        VARCHAR(64)  NOT NULL COMMENT 'definition_json 的 SHA-256',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_flow_version (flow_id, version),
    KEY idx_flow_enabled (flow_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义（版本化）';

CREATE TABLE or_execute_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id      VARCHAR(128) NOT NULL,
    flow_id         VARCHAR(100) NOT NULL,
    flow_version    INT          NOT NULL,
    biz_id          VARCHAR(200) NOT NULL,
    decision        VARCHAR(20)  NOT NULL,
    reason          VARCHAR(512),
    total_score     INT          NOT NULL DEFAULT 0,
    cost_millis     INT          NOT NULL,
    hit_nodes       JSON,
    node_results    MEDIUMTEXT   COMMENT '节点明细 JSON（含 details）',
    facts_snapshot  MEDIUMTEXT   COMMENT '入参快照（脱敏后）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request (request_id),
    KEY idx_biz (biz_id),
    KEY idx_flow_time (flow_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志';
```

---

## 5. 持久化：JdbcFlowDefinitionRepository

`implements FlowDefinitionRepository`（M2a 端口，签名不变），JdbcTemplate 1:1 复刻 M2a 内存仓储已验证语义；`definition_json` ⇄ `FlowDefinition` 复用 M2a 的 `FlowDefinitionJsonCodec`（口径一致）。

- **`save(def)`**（单事务）：
  1. `version = COALESCE(MAX(version),0)+1 WHERE flow_id=?`（首次=1）；
  2. `UPDATE or_flow SET enabled=0 WHERE flow_id=?`（旧版本全部下线）；
  3. `checksum = SHA-256(definition_json)`；`INSERT` 新版本 `enabled=1`；
  4. 回写 `def.setVersion/.setEnabled`，返回。
  指针切换 + 插入同一事务 → 任意时刻至多一个 enabled（与内存仓储 `synchronized` 语义等价，靠事务保证）。
- **`findActiveByFlowId`** → `WHERE flow_id=? AND enabled=1 LIMIT 1`；空→`Optional.empty()`。
- **`findByFlowIdAndVersion`** / **`listVersions`**（升序）→ 直查。
- **`enable(flowId, version)`**（单事务）：版本不存在则 no-op（与内存语义一致）；否则 `enabled=0` 全部 → `enabled=1` 指定版本。

> 并发：依赖 DB 事务 + `UNIQUE(flow_id, version)`。高并发下同一 flow 并行 save 的版本竞争由唯一键兜底（冲突重试留 M2b 实现细节，测试覆盖串行正确性）。

---

## 6. 审计：JdbcExecutionLogger + ExecutionLogQuery

**端口演进（前置改动）**：`or_execute_log.flow_version NOT NULL`，但 M2a 的 `ExecutionLogger.log(FlowResult, DecisionContext)` 不携带版本（`FlowResult` 有 `flowId` 无 `version`，`DecisionContext` 同）。故本轮把端口签名演进为 `log(FlowResult result, DecisionContext ctx, int flowVersion)`：
- `OpenRuleService.execute` 的 `safeLog` 传入 `flow.getVersion()`（该处本就持有编译后 `CompiledFlow`）；`simulate` 不写日志，不受影响。
- `InMemoryExecutionLogger`（M2a）同步更新签名，缓冲改存 `(FlowResult, flowVersion)` 以支撑 `InMemoryExecutionLogQuery`。
- 这是 openrule-spring 内部端口的小演进（M2a 实现一并改），不破坏 C12 语义。

### 6.1 JdbcExecutionLogger（写）
`implements ExecutionLogger`：
- `log(result, ctx, flowVersion)`：把写库任务提交到**模块自带虚拟线程 `ExecutorService`**（异步，不阻塞主链路）。
- facts 按 `openrule.audit.facts-desensitize-keys`（默认 `mobile,idCard,bankCard,password`）**递归打码**（嵌套 Map 逐层）后序列化存 `facts_snapshot`；`node_results`/`hit_nodes` 序列化存档（可复现）；`flow_version` 取入参。
- 失败仅吞 + 计数（**C12**）。测试内提供同步 flush（`awaitIdle()`）以断言落库。

### 6.2 ExecutionLogQuery（读，新端口）
放 `openrule-spring`，与写端口分离（读写分明）：
```java
public interface ExecutionLogQuery {
    List<ExecutionLogEntry> query(String bizId, String flowId, int limit);
}
public record ExecutionLogEntry(String requestId, String flowId, int flowVersion,
        String bizId, String decision, String reason, int totalScore, long costMillis,
        Instant createdAt) {}
```
- standalone：`InMemoryExecutionLogQuery`（读 `InMemoryExecutionLogger` 缓冲）。
- jdbc：`JdbcExecutionLogQuery`（`SELECT ... FROM or_execute_log WHERE (?=null OR biz_id=?) AND (?=null OR flow_id=?) ORDER BY created_at DESC LIMIT ?`）。
- admin `logs` 端点走它。

---

## 7. 完整 admin 端点（openrule-api，沿用 save 即启用）

`FlowAdminController` 增 4 端点 + `OpenRuleService` 对应方法（后者委派端口 + 失效缓存）：

```
POST /api/v1/admin/flows/{flowId}/enable     ?version=N 启用指定版本（repo.enable + notifier.publishInvalidation）
POST /api/v1/admin/flows/{flowId}/rollback   ?version=N = enable 旧版本（语义糖，复用 enableVersion）
GET  /api/v1/admin/flows/{flowId}/versions   版本列表（FlowSummary 列表，含 enabled 标记）
GET  /api/v1/admin/logs?bizId=&flowId=&limit= 执行日志查询（ExecutionLogQuery → List<ExecutionLogEntry>）
```

`register`（save+自动启用）、`execute`、`simulate` **不变**。enable/rollback 后必须 `FlowChangeNotifier.publishInvalidation(flowId)` 失效缓存。错误处理沿用 M2a 条件化 advice（流程/版本不存在 404、非法 400/422）。

---

## 8. 装配（OpenRuleJdbcAutoConfiguration）

- `@AutoConfiguration(after = OpenRuleAutoConfiguration.class)`，注册 `AutoConfiguration.imports`。
- 全部 Bean `@ConditionalOnBean(DataSource.class)` + `@ConditionalOnMissingBean`：`JdbcFlowDefinitionRepository`、`JdbcExecutionLogger`、`JdbcExecutionLogQuery`、审计用虚拟线程 `ExecutorService`。
- `OpenRuleJdbcProperties`：`openrule.audit.facts-desensitize-keys`（List，默认 4 项）、`openrule.audit.async`（默认 true）、审计池大小等。
- 引入 jdbc + 配 `DataSource` → 覆盖 M2a 内存实现；否则内存实现继续生效（`ExecutionLogQuery` 的内存实现仍在 spring 模块按 `@ConditionalOnMissingBean` 提供）。

---

## 9. 数据流（M2b 形态）

```
register:  HTTP → FlowAdminController → JsonCodec 解析 → 逐节点 validate
                  → JdbcFlowDefinitionRepository.save（事务：版本自增 + 指针切换 + checksum）
                  → notifier.publishInvalidation → FlowLoader.invalidate
execute:   HTTP → ExecuteController → OpenRuleService.execute
                  → FlowLoader.loadActive（Caffeine 未命中→repo.findActiveByFlowId→编译）
                  → FlowExecutor.execute（core）
                  → JdbcExecutionLogger.log（异步落库，脱敏，C12）→ ExecuteResponse
enable:    HTTP → /admin/flows/{id}/enable → repo.enable（事务切指针）→ invalidate
versions:  HTTP → /admin/flows/{id}/versions → repo.listVersions
logs:      HTTP → /admin/logs → ExecutionLogQuery.query → List<ExecutionLogEntry>
```

---

## 10. 测试

- **openrule-jdbc（Testcontainers 真 MySQL）**：`@Testcontainers` + `MySQLContainer`，`schema.sql` 初始化。Docker 缺席用 `@EnabledIf`（探测 `docker info`）**自动跳过**，不挂断构建。
  - `JdbcFlowDefinitionRepository`：版本自增、指针原子切换（任意时刻至多一个 enabled）、checksum 计算、`enable`/回滚、缺失流程空。
  - `JdbcExecutionLogger`：异步落库（同步 flush 后断言行存在）、facts 脱敏（敏感 key 打码、嵌套生效）、node_results 存档。
  - `JdbcExecutionLogQuery`：按 bizId/flowId/limit 查询、倒序。
- **openrule-spring**：`InMemoryExecutionLogQuery` + 新服务方法（enableVersion/rollback/listVersions/queryLogs）单测。
- **openrule-api**：4 端点 MockMvc（内存适配器，无需 MySQL）——versions 列表、enable 切指针后 execute 走新版本、logs 返回、rollback 生效。
- **组合冒烟（可选）**：api + jdbc + MySQL 跑「注册→execute→/admin/logs 查到一条→/rollback→execute 走旧版本」。

---

## 11. 验收标准

- `mvn clean test` 全绿：M2a 78 + spring 新单测 + api 新 MockMvc + jdbc Testcontainers（Docker 在→跑真库，缺→跳过且 BUILD SUCCESS）。
- 真库链路（Docker 在场）：注册 `order_risk` → execute 落 `or_execute_log`（facts 脱敏）→ `/admin/logs` 查到 → 再 save 一版 → `/admin/flows/order_risk/versions` 列出两版 → `/rollback?version=1` → execute 走 v1。
- 纯净性自检：`openrule-jdbc` 不依赖任何 ycr；`openrule-spring`/`openrule-api` 仍不被 jdbc/DataSource 污染（不引 jdbc 模块时零 DataSource 依赖）。
- 约束自检：C11（缓存按版本失效）、C12（审计失败不影响主流程）、save/enable 指针在事务内原子切换。

---

## 12. 相对原始规范的对齐与偏差

- **对齐**：技术规范 §7 schema（`or_flow`/`or_execute_log`）、§8 admin 端点、§10 异步审计 + 脱敏 + checksum 轻量可复现。
- **有意偏差**：
  1. `or_script` 不建（脚本节点 M3 再补）。
  2. Redis 多实例热更新后延（本轮单进程 `LocalFlowChangeNotifier`）。
  3. 持久化用 **JdbcTemplate** 而非 ycr data-mp（公开仓库纯净；ycr 绑定留 `ycr-starter-rule`）。
  4. 沿用「save 即启用」，未实现「草稿→启用」暂存模型。
  5. 新增 `ExecutionLogQuery` 读端口（规范未单列，为 admin `logs` 端点而设，读写分明）。

---

## 13. 后续衔接（非本轮）

- **Redis 热更新一轮**：`openrule-redis` 模块 `RedisFlowChangeNotifier` + 订阅端，多实例广播失效。
- **ycr-starter-rule（ycr 仓库）**：端口绑 ycr data-mp（`BaseMapperX`/`BaseDO`）/cache，响应/异常对齐 `R<T>`/`ErrorCode`，以 `ycr-scaffold-mvc`/`-ddd` 为验收参照。
- **M3/M4/M5**：脚本/高级节点执行器（届时补 `or_script`）、Python、Micrometer。
