# ADR-0003：绝对 Deadline、单任务提交与资源隔离

> 状态：Accepted
> 日期：2026-08-20
> 决策范围：M1.6 Core 调度与 Spring 装配

## 背景

当前 Parallel Stage 先向 parallel pool 提交任务，任务内部的 NodeRunner 再向 timeout pool 提交一次并阻塞等待。两个池一旦改成有界平台线程池，就可能出现外层线程全部等待内层任务、内层任务无工作线程的饥饿。当前 node timeout、stage timeout 和调用方中断也没有统一的绝对时间预算。

`CompletableFuture.cancel(true)` 只表达取消意图。它不能强制终止忽略中断的代码，因此超时后必须保证迟到任务无法修改引擎状态，外部副作用则需要单独治理。

## 决策

### 1. 统一使用单调绝对 Deadline

```java
public final class Deadline {
    private final long deadlineNanos;
    private final Ticker ticker;

    public Duration remaining();
    public boolean isExpired();
    public Deadline min(Deadline other);
}

@FunctionalInterface
public interface Ticker {
    long readNanos();
}
```

生产默认 `System::nanoTime`，测试使用手动 Ticker。不得用 `currentTimeMillis` 计算 timeout。

每次执行只有一个 request deadline：

```text
requestDeadline = start + request.timeout/defaultRequestTimeout
stageDeadline   = min(requestDeadline, stageStart + stage.timeout/defaultStageTimeout)
nodeDeadline    = min(stageDeadline, nodeStart + node.timeout/defaultNodeTimeout)
```

进入任何调度步骤前先检查剩余预算；剩余时间小于等于零时不得再提交任务。

### 2. 每个节点业务代码只提交一次

引入 `NodeTaskCoordinator`：

```java
final class NodeTaskCoordinator {
    NodeTask submit(NodeExecutionInput input,
                    CompiledNode node,
                    Deadline deadline);
    NodeResult await(NodeTask task);
    List<NodeResult> awaitParallel(List<NodeTask> tasks,
                                   Deadline stageDeadline);
}
```

每个节点只向 `nodeExecutorService` 提交一次业务 Callable。`ScheduledExecutorService` 只负责到点完成 timeout 信号和发送 interrupt，不再次执行业务逻辑。

```text
orchestrator ──submit once──> nodeExecutorService ──> NodeExecutor.execute
      │
      └──schedule deadline──> timeoutScheduler ──> complete timeout + interrupt worker
```

Standalone 默认使用 virtual-thread-per-task executor；Spring 默认也使用虚拟线程 executor。调用方可注入平台线程池，但调度模型不发生变化。

### 3. 并行 Stage 的完成规则

1. 进入 Stage 时冻结 variables 快照。
2. 按节点定义顺序提交，任务列表保持同一顺序。
3. 节点自己的 deadline 到达时，生成 `OR-NODE-TIMEOUT`，再应用该节点 FailPolicy。
4. stage deadline 到达时，所有未完成节点生成 `OR-STAGE-TIMEOUT`，分别应用各自 FailPolicy。
5. 任一节点产生 ABORT，立即取消未完成兄弟任务并向 Flow 传播原始异常。
6. 非 ABORT 节点全部终态后，调度线程按定义顺序合并结果和 outputs。
7. 已完成但在 stage timeout 信号之后才交付的结果属于迟到结果，必须丢弃。

不能按节点定义顺序逐个 `Future.get(timeout)`，否则前序慢任务会造成后序已完成任务的错误超时。实现使用 completion queue 或等价的完成事件循环，并根据最近 node/stage deadline 计算下一次 poll 时间。

### 4. timeout、interrupt、cancel 和 FailPolicy 的边界

| 事件 | 错误码 | 应用 FailPolicy | Flow 行为 |
|---|---|---:|---|
| NodeExecutor 抛业务/适配器异常 | `OR-NODE-EXECUTION` | 是 | 由策略决定 |
| node deadline 到达 | `OR-NODE-TIMEOUT` | 是 | 由策略决定 |
| stage deadline 到达 | `OR-STAGE-TIMEOUT` | 是，对每个未完成节点 | 由策略决定 |
| request deadline 到达 | `OR-EXECUTION-TIMEOUT` | 否 | 整次执行失败，无业务决策 |
| 调用方线程被 interrupt | `OR-EXECUTION-INTERRUPTED` | 否 | 恢复中断位并失败 |
| 引擎关闭导致取消 | `OR-ENGINE-CLOSED` | 否 | 执行失败 |
| executor/plan 类型不变量破坏 | `OR-ENGINE-INVARIANT` | 否 | 执行失败 |
| bulkhead 无容量 | `OR-ENGINE-OVERLOADED` | 否 | 提交前失败 |

FailPolicy 只治理节点可预期失败，不得掩盖引擎故障、请求取消或调用方中断。

### 5. 取消是协作式协议

```java
public interface CancellationToken {
    boolean isCancellationRequested();
    void throwIfCancellationRequested();
}
```

Core 在超时/ABORT/关闭时同时：

1. 原子设置 token；
2. 取消对应 Future 并请求 interrupt；
3. 封存 NodeTask 的终态；
4. 忽略之后的正常或异常完成。

NodeExecutor 必须在循环、批处理或远程调用重试间检查 token，并为 I/O 设置不超过 `deadline.remaining()` 的客户端 timeout。忽略取消的纯计算任务可能继续占用一个虚拟线程，但无法写入内部 ExecutionState。

有外部写副作用的节点不能仅依赖 interrupt 保证安全，必须使用幂等键、事务或后续 SideEffectPort；SIMULATE/BACKTEST 不允许执行外部写。

### 6. 默认资源上限

```java
public record EngineLimits(
        Duration defaultRequestTimeout,
        Duration defaultStageTimeout,
        Duration defaultNodeTimeout,
        Duration maxRequestTimeout,
        int maxStagesPerFlow,
        int maxNodesPerFlow,
        int maxNodesPerStage,
        int maxParallelNodesPerStage,
        int maxInFlightNodeTasks) {}
```

默认值：

| 配置 | 默认值 |
|---|---:|
| defaultRequestTimeout | 10s |
| defaultStageTimeout | 10s |
| defaultNodeTimeout | 3s |
| maxRequestTimeout | 60s |
| maxStagesPerFlow | 50 |
| maxNodesPerFlow | 500 |
| maxNodesPerStage | 100 |
| maxParallelNodesPerStage | 64 |
| maxInFlightNodeTasks | 1024 |

结构上限在 compile 阶段校验。运行时用公平 Semaphore 控制 in-flight node tasks。无 permit 时立即返回 `OR-ENGINE-OVERLOADED`，不在引擎内部建立第二个无界等待队列。

默认值是安全起点，不是性能承诺；Spring 属性可以覆盖，但不得超过硬上限而不显式启用 `allowUnsafeLimits`。

### 7. 线程资源所有权

`OpenRuleEngine.Builder` 分别接受 node executor 和 timeout scheduler：

```java
Builder nodeExecutor(ExecutorService executor, ResourceOwnership ownership);
Builder timeoutScheduler(ScheduledExecutorService scheduler,
                         ResourceOwnership ownership);
```

只有 `OWNED` 资源在 `close()` 时关闭。关闭流程：停止接收新请求 → 请求取消在途任务 → 等待 `shutdownGracePeriod` → `shutdownNow` 自有资源。调用方资源永不关闭。

Spring 创建的两个 executor Bean 由 Spring 生命周期管理，因此对 Engine 标记为 `CALLER_MANAGED`。

## 被拒绝的方案

### 外层 parallel pool + 内层 timeout pool

虚拟线程下暂时可工作，但一旦用户注入有界池就存在结构性饥饿，且每个节点浪费两次任务调度。

### 只使用 `CompletableFuture.orTimeout`

它能完成 future，但不会自动停止底层业务代码，也不能直接表达 node/stage/request 三层 deadline 与稳定失败分类。

### 超时后等待任务真正退出

对忽略中断的第三方代码没有上界，会让 request deadline 失效。正确做法是封存结果、隔离状态并把副作用放到受治理端口。

## 验收

- 有界单线程 node executor 下，串行和并行 Stage 均不死锁；
- 每个节点 executor 的调用计数严格为 1；
- 手动 Ticker 测试 node、stage、request deadline，无依赖真实 sleep 的脆弱断言；
- ABORT 在 100ms 内向调用方传播并请求取消兄弟任务；
- 迟到节点返回 outputs 后，最终 variables 和结果不发生变化；
- 调用方 interrupt 后线程中断位保持为 true；
- 1025 个并发 node task 在默认 bulkhead 下，第 1025 个稳定返回 overload；
- Engine 只关闭自己拥有的资源。
