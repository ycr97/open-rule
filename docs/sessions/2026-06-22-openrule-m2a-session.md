# OpenRule M2a · Session 记录（openrule-spring + openrule-api）

> 配套：`docs/superpowers/specs/2026-06-22-openrule-m2a-spring-design.md`（设计）、`docs/superpowers/plans/2026-06-22-openrule-m2a-spring.md`（计划）。
> 完成日期：2026-06-23

## 一句话现状

M2a 完成：`open-rule` 升为**三模块 reactor**（`openrule-core` + `openrule-spring` + `openrule-api`），用纯 Spring Boot 即可启动、零外部中间件，端到端 MockMvc 全链路打通。`mvn clean test` 全绿 **78** 测试（core 56 / spring 17 / api 5），`mvn install` 四件制品（parent/core/spring/api）均成功。

## 已敲定决策（沿用设计）

- **端口-适配（六边形）**：`openrule-spring` 只依赖自有 3 端口 `FlowDefinitionRepository` / `FlowChangeNotifier` / `ExecutionLogger`，各配 standalone 进程内默认实现（`@ConditionalOnMissingBean` 可覆盖）。
- **三模块**：依赖严格单向 `api → spring → core`。`executor` 未独立成模块（沿用 M1，`OperatorNodeExecutor` 仍在 `io.openrule.core.executor`）。
- **纯净性达成**（已用 `dependency:tree` 验证）：`openrule-core` 零 Spring / 零 Jackson / 零 Caffeine（纯 jar）；`openrule-spring` 无 spring-web、无任何 ycr 依赖；公开仓库零 ycr 依赖。
- **ycr 适配**留到将来 ycr 侧出 `ycr-starter-rule`，依赖公开的 `io.openrule:openrule-spring`。

## 实施中相对计划/设计的偏差（重要，M2b 需知）

1. **core 不再「零改动」**：`FlowDefinition`/`StageDefinition`/`NodeDefinition` 是 `@Data @Builder`，Lombok 不生成无参构造器，Jackson 无法反序列化。给三类各加 `@NoArgsConstructor @AllArgsConstructor`（**纯 Lombok，未引入 Jackson 依赖，core 仍是纯 jar**，已验证）。这让 core 真正具备设计 §5/§11 一直假定的「JSON 可反序列化」能力。M1 的 56 测试不受影响。
2. **parent 编译开 `-parameters`**：autoconfig 按参数名注入两个虚拟线程池（`openRuleTimeoutPool`/`openRuleParallelPool`，都是 `ExecutorService`/`Executor`，类型歧义），name 消歧需 `-parameters`（spring-boot-starter-parent 默认开，我们自定义 parent 漏了）。
3. **autoconfig 聚合器装配**：`FlowExecutor` 改由注入 `List<DecisionAggregator>` 在 Bean 方法内自建 `policy→aggregator` 映射，规避 Spring 对 `Map<枚举,接口>` 注入得到**空 Map**的歧义（原计划用单独 `aggregators` Map Bean，运行时 `No aggregator for policy: PRIORITY`）。
4. **codec 的 ObjectMapper**：`openrule-spring` 无 spring-web，该场景 `JacksonAutoConfiguration` 不产出 `ObjectMapper`（它依赖 spring-web 的 `Jackson2ObjectMapperBuilder`）。codec Bean 改用 `ObjectProvider<ObjectMapper>` 缺省自备纯 `ObjectMapper`，保住「spring 可无 web 独立使用」。
5. **api 端到端测试 `@DirtiesContext(AFTER_EACH_TEST_METHOD)`**：`@SpringBootTest` 跨方法复用单例内存仓储，重复 `register` 导致版本自增；每方法重建上下文使版本恒为 1。
6. **单测命令**：reactor 下跑单类测试统一加 `-Dsurefire.failIfNoSpecifiedTests=false`（否则非匹配模块报 "No tests matching pattern" 而失败）。

## 构建备忘

- 本机默认 JDK 17，构建 M2a 需：`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`。
- 全量：`mvn clean test`；单模块单类：`mvn -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`。
- 制品安装：`mvn -DskipTests install`（parent/core/spring/api 四件）。

## 下一步（M2b，非本轮）

- MySQL `FlowRepository`（ycr data-mp `BaseMapperX`/`BaseDO`）、Redis Pub/Sub 热更新、异步 `ExecutionLogger` 落库（`or_execute_log` + facts 脱敏 + checksum 版本化）、完整 admin（enable/rollback/versions/logs）。全部「换端口实现 + 加 admin 端点」，不动 M2a 编排。
- `ycr-starter-rule`（ycr 侧）：端口绑 ycr 设施，响应/异常对齐 `R<T>`/`ErrorCode`，以 `ycr-scaffold-mvc`/`ycr-scaffold-ddd` 为验收参照。
- M3/M4/M5：脚本/高级节点执行器、Python、Micrometer（`NodeExecutorRegistry` 自动收集机制已为新执行器预留）。
