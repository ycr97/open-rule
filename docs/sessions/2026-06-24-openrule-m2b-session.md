# OpenRule M2b · Session 记录（openrule-jdbc 持久化适配器）

> 配套：`docs/superpowers/specs/2026-06-23-openrule-m2b-jdbc-design.md`（设计）、`docs/superpowers/plans/2026-06-24-openrule-m2b-jdbc.md`（计划）。
> 完成日期：2026-06-24

## 一句话现状

M2b 完成：新增第 4 模块 **`openrule-jdbc`**（JdbcTemplate，零 ORM、零 ycr），把 M2a 的内存端口在「有 DataSource 时」覆盖为真 MySQL 实现，并补齐完整 admin（enable/rollback/versions/logs）。`mvn clean test` 四模块全绿 **90** 个可跑测试（core 56 / spring 21 / api 9 / jdbc 4），`mvn install` 五件制品（parent/core/spring/api/jdbc）成功。

## 已敲定决策（本轮 = C 决策的 A 部分：公开仓库中立实现）

- **新模块 `openrule-jdbc`**：依赖 `openrule-spring` + `spring-boot-starter-jdbc`，`@ConditionalOnBean(DataSource)`+`@ConditionalOnMissingBean` 覆盖内存端口。引入即切 MySQL，不引入保持 M2a 内存形态。
- **JdbcTemplate**（非 ORM、非 ycr data-mp）：`save` 事务内「版本自增 + enabled 指针原子切换 + checksum(SHA-256)」。
- **沿用「save 即启用」** + 增 enable/rollback/versions/logs；与 M2a 全兼容。
- **异步审计**：`JdbcExecutionLogger` 用模块自带虚拟线程池（不碰全局 `@EnableAsync`）；facts 递归脱敏；失败仅吞 + 计数（C12）。
- **新增只读端口 `ExecutionLogQuery`**（读写分明），standalone 内存 + jdbc 两实现。
- **Testcontainers 真 MySQL**，`@Testcontainers(disabledWithoutDocker = true)` Docker 缺席自动跳过。
- **纯净性**（dependency:tree 实证）：`openrule-jdbc` 零 ycr；`openrule-spring`/`openrule-api` 不引 jdbc 模块时零 DataSource。

## 验证状态（重要）

- **已真验证（无需 Docker）**：core 56、spring 21、api 9（含 4 个 admin 端点 MockMvc）、jdbc 装配测试 2（H2 验证「有 DataSource 覆盖、无则退让」）、jdbc 工具 2。
- **暂未验证（Docker 未运行，IT 跳过）**：`JdbcFlowDefinitionRepositoryIT`（4）+ `JdbcAuditIT`（3）。代码已写、编译通过，但**未对真 MySQL 跑过**。补跑方式：`open -a Docker` 就绪后 `mvn -pl openrule-jdbc -am test`，IT 自动启用。

## 实施中相对计划的偏差/修复

1. **端口签名演进**：`ExecutionLogger.log` 加 `flowVersion` 参数（`or_execute_log.flow_version NOT NULL` 必需）；连带改 M2a 的 `InMemoryExecutionLogger`/`OpenRuleService.safeLog`。
2. **第二处 core 加法**：`FactMap.asMap()`（审计快照取 facts；纯 additive getter，M1 56 测试不受影响）。
3. **计划漏项修复**：`OpenRuleService` 缺 `java.util.List` import；M2a `OpenRuleAutoConfigurationTest` 的匿名 `FlowDefinitionRepository` 需补 `findAllVersions`。
4. **编译 bug**：`JdbcFlowDefinitionRepository` 的 RowMapper 字段初始化器引用空白 final `codec` → 改私有 `mapRow` 方法 + `this::mapRow`。
5. **装配排序 bug**（关键）：jdbc 自动配置须 `@AutoConfiguration(after = DataSourceAutoConfiguration, before = OpenRuleAutoConfiguration)`——只有排在 OpenRuleAutoConfiguration **之前**，其内存默认的 `@ConditionalOnMissingBean` 才会退让；否则两个 `FlowDefinitionRepository` Bean 冲突。

## 构建备忘

- JDK 21：`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`。
- 单类：`mvn -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`（**从 reactor 根目录跑**；Bash 工作目录会随 `cd` 改变）。
- 真库 IT：需 Docker Desktop；缺席自动跳过、不挂构建。

## 下一步（非本轮）

- **Redis 多实例热更新一轮**：`openrule-redis` 模块 `RedisFlowChangeNotifier` + 订阅端。
- **C 决策的 B 部分 · `ycr-starter-rule`（ycr 仓库）**：端口绑 ycr data-mp（`BaseMapperX`/`BaseDO`）/cache，响应/异常对齐 `R<T>`/`ErrorCode`。
- **M3/M4/M5**：脚本/高级节点执行器（届时补 `or_script` 表）、Python、Micrometer。
