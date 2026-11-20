# Electromagnetism：瞬态性能瓶颈确认与重构指导

> **基准源码：** 用户最新上传的 `electromagnetism-sources(1).zip`\
> **审查范围：** Rust 瞬态求解、保护事件定位、稀疏
> Newton、独立电路岛、Java Fault Domain 调度、世界输入采集\
> **审查方式：** 当前源码静态路径核对；结合用户提供的 429
> 维电站实测耗时\
> **重要限制：** 本次 ZIP 是 source-only 包，不包含 `build/`
> 性能日志、游戏运行日志、根 Cargo workspace 清单及
> `gradle.properties`。因此本文能确认代码执行路径和算法工作量，但不能声称已从
> profiler 日志测出 172
> 秒中各阶段的真实耗时占比。文中将"源码已确认"和"运行时待测"分开。

------------------------------------------------------------------------

## 1. 结论摘要

当前最慢的 429 维电站场景，首要嫌疑不是单次稀疏矩阵分解，而是：

**保护事件定位的每一次时间试探，都会重新执行完整的自适应瞬态求解；每次自适应求解又可能触发多次
Newton 和稀疏数值分解。**

源码链路：

``` text
CircuitSession.advance_with_trial()
  └─ ProtectionBracket::first()
      └─ 检测到 Relay / Thermal 保护事件
          └─ 二分定位，最多 52 轮
              └─ trial_step(..., world_tick = true)
                  └─ TransientCircuit::refined_trial()
                      └─ refine_local()
                          ├─ full step
                          ├─ first half step
                          ├─ second half step
                          └─ error > 1 时再尝试 four-quarter path
                              └─ trial_for_duration()
                                  └─ Newton
                                      └─ sparse numeric factorization
```

这条路径在源码中可以直接确认。

对一个约 50 ms 的初始事件区间，当前定位停止宽度为约 100 ps：

\[ N `\approx `{=tex}`\log`{=tex}\_2(0.05/10\^{-10}) `\approx 29`{=tex}
\]

即每个事件大约需要 29 次二分试探（实际次数取决于初始 bracket
宽度和浮点时间）。如果有两个事件，约为 58 次定位试探。

每次定位试探通常至少进行一次完整步与两半步比较，即 3 次底层
trial；若误差超限，还会进行四分步验证，最多再增加 4 次底层
trial。由此得到的量级估算是：

-   两个事件约 58 次定位 probe；
-   通常约 174 次底层瞬态 trial；
-   如果大量 probe 触发 finer path，可能接近 406 次底层 trial；
-   每个底层 trial 还可能包含多个 Newton iteration 和矩阵分解。

这是**由当前算法结构推导出的工作量估算，不是实测计数**。实际数量应由
`localization_trials`、`newton_iterations`、`factorizations`
等日志确认。

另一个已确认的性能放大器是：电站包含
Relay、MOSFET、BJT、整流器等模型，而 Rust
的电路岛计划采用全局白名单；任一不支持模型都会令整个 `Plans`
为空。与此同时，429 维规模超过谐波快速路径的 256 未知量上限，且 Relay
不在谐波模型白名单内。

最后，游戏线程仍同步等待所有 Fault Domain 的计算完成。因此 172
秒不仅是物理计算延迟，也会直接转化为游戏 tick 阻塞。

**建议优先顺序：** 1. 给保护定位增加专用低成本 probe，不要每次都执行完整
step-doubling。 2. 对事件定位增加插值/Brent
类的安全加速，但最终事件状态必须重新通过真实积分验证。 3. 对 Newton
的重复数值分解做受控 Jacobian/factorization reuse 实验。 4.
将独立岛资格从"整个 CircuitSession
的全局白名单"改为"按实际耦合组件分类"，但必须保留全局最早事件屏障。 5.
重构游戏 tick 调度前，先设计可持久化的 native continuation；不能只删掉
`join()` 或把 budget 改成 5 ms。 6. 为真实 429 维电站增加专用 benchmark
和事件级 telemetry。

------------------------------------------------------------------------

## 2. 已确认的源码瓶颈

### 2.1 保护事件定位重复执行自适应求解

**优先级：P0 / 首要优化对象**

文件：

-   `native/crates/em-core/src/session/circuit.rs`
-   `native/crates/em-core/src/solver/transient.rs`

`CircuitSession::advance_with_trial()`：

-   第 959--1021 行：检测到 `ProtectionBracket` 后进入最多 52
    轮的定位循环。
-   第 977--980 行：每轮调用 `trial_step(..., true, ...)`，并增加
    `localization_trials`。
-   第 963 行：停止条件为 `1e-10 s` 或相对时间精度 `1e-12`。

关键代码形态：

``` rust
for _ in 0..52 {
    let high = crossing.high.time_seconds;

    if high - crossing.low <= 1e-10_f64.max(high.abs() * 1e-12) {
        break;
    }

    let middle = crossing.low + (high - crossing.low) * 0.5;

    let trial = trial_step(
        &advancing,
        crossing.seed.as_ref(),
        middle,
        self.accuracy,
        true,
        start,
        budget,
    );
}
```

`TrialCursor::poll()`：

-   第 93--100 行：为给定 anchor/end 创建 `trial_seed(seed)`。
-   第 97 行：只要存在 adaptive accuracy，就调用
    `refined_trial(end, accuracy, world_tick)`。

`TransientCircuit::refined_trial()`：

-   第 629 行：先尝试 harmonic path。
-   第 638--643 行：没有 sampler 时尝试 island path；不适用则进入
    `refine_local()`。
-   第 646--700 行：`refine_local()` 执行 full step、half step、第二
    half step；误差超过 1 时再试四个 quarter step。

因此，**保护事件的每一次二分并不是一次廉价的事件函数求值，而是一次完整自适应瞬态试算**。

#### 重构方向

增加明确的 trial purpose：

``` rust
enum TrialPurpose {
    AcceptedAdvance,
    ProtectionProbe,
    ProtectionCommit,
}
```

不同用途采用不同计算策略：

-   `AcceptedAdvance`：保留当前完整 step-doubling 和能量误差检查。
-   `ProtectionProbe`：只为判断事件函数在时间区间哪一侧，不提交物理状态；使用单次受控隐式步或经过验证的局部近似，避免每轮都做完整
    step-doubling。
-   `ProtectionCommit`：在候选事件时间上重新执行正常精度的积分与误差验证，只有通过后才接受事件状态。

**不可做的简化：** 不得把 probe
的近似状态直接作为最终接受状态；不得只根据线性插值时间就提交继电器切换。

#### 事件函数应显式化

当前 `protection::triggered()` 只返回触发 branch
index。建议另行提供连续的 signed margin：

-   Relay 未吸合：(g=\|V\_{coil}\|-V\_{pickup})
-   Relay 已吸合：(g=V\_{dropout}-\|V\_{coil}\|)
-   热保护：(g=T-T\_{trip})

明确 crossing direction，并记录事件前后 margin。这样可以使用带 bracket
保证的 secant/Brent 类 root finder，减少纯二分次数。

若使用 dense output 或插值，必须先证明其对当前 Backward Euler/DAE
状态的误差边界。当前代码没有现成的高阶 dense output，不能直接照搬显式 RK
的插值公式。

参考资料： - Wolfram `WhenEvent`：事件检测、插值、Brent
与线性插值的精度/成本权衡。<https://reference.wolfram.com/language/tutorial/NDSolveWhenEvents.html> -
SciPy `solve_ivp`：事件以连续函数 (g(t,y)=0) 表达，并通过 root finding
定位。<https://docs.scipy.org/doc/scipy/reference/generated/scipy.integrate.solve_ivp.html>

### 2.2 电路岛优化采用整个 CircuitSession 的全局白名单

**优先级：P1**

文件：

`native/crates/em-core/src/solver/transient/islands.rs`

`Plans::compile()` 第 68--84 行：

``` rust
if !circuit.branches.iter().all(|b| {
    matches!(
        b.element,
        Element::Resistor { .. }
            | Element::DcVoltageSource { .. }
            | Element::Capacitor { .. }
            | Element::Inductor { .. }
            | Element::Windings(_)
            | Element::SineVoltageSource { .. }
            | Element::Diode { .. }
    )
}) {
    return Ok(Self(Vec::new()));
}
```

这意味着只要一个 CircuitSession
中存在不在白名单的模型，所有电路岛计划都会被禁用，而不是只禁用含该模型的组件。

当前白名单不包含：

-   `Relay`
-   `Mosfet`
-   `Bipolar`
-   `Rectifier`
-   `ThermalResistor`
-   `Arc`
-   `TappedWire`
-   `Regulator`
-   `Converter`
-   `FieldShaft` / `FieldAcShaft`

用户上传的电站场景脚本 `scripts/cee-creative-power-plant.py` 明确包含
Relay、N-MOSFET、P-MOSFET、NPN、PNP、高压整流器、励磁定子、变压器等。因此该场景中的
CircuitSession 若包含这些支路，当前全局白名单会返回空计划。

这能确认**岛优化对该类电站不适用**，但不能仅凭源码断言 429
维电路里存在多少个本可独立求解的电气岛。需要从运行时 `Layout.components`
和 branch 连接图测量。

#### 重构方向

不要简单把 Relay/MOSFET/BJT 加进白名单。正确方向是：

1.  先按实际电气耦合关系生成 connected components。
2.  对每个 component
    单独分类：无保护事件、带保护事件、含非线性设备、含采样器、含跨端口耦合等。
3.  只有确实独立的 component 才可独立求解。
4.  保护组件必须提供事件轨迹。
5.  所有组件共享一个全局最早事件屏障：任何组件发生事件时，其他组件也必须能提供同一物理时间的状态。
6.  只有全部组件到达同一个接受边界后，才能合并候选状态和能量账本。

目前 `island_trial()` 第 202--293 行是顺序遍历各
island，并把最终状态合并为
`Trial::single(candidate, error)`。它没有保留每个 island
的独立中间轨迹。因此，支持"保护岛 + 普通岛"混合求解，需要重构 Trial
结果结构，不能只改白名单。

#### 重要限制

如果 429 维电站在实际拓扑上本来就是一个连通的耦合组件，那么
component-level islanding 不会把它拆小。此时必须转向事件 probe、Newton
和稀疏矩阵优化，而不是期待 islanding 解决全部问题。

### 2.3 429 维场景无法使用当前 harmonic fast path

**优先级：P1 / 能力边界**

文件：

`native/crates/em-core/src/solver/transient/harmonic.rs`

第 80--108 行：

-   harmonic plan 只接受一组受限模型。
-   `Relay`、MOSFET、BJT、Rectifier 等不在允许模型集合中。
-   第 107--108 行：`n > 256` 时直接返回 `None`。

``` rust
let n = circuit.layout.size;
if n > 256 {
    return Ok(None);
}
```

如果"429 维"指 `layout.size` 或 progress 日志中的
`max_unknowns=429`，则该场景无论如何都会超过 256 的限制；若 429
指支路数，应以实际 `max_unknowns` 确认。

因此，不能期望当前 harmonic acceleration 自动帮助该电站。

**建议：** 不要先简单把 256 改成 512。harmonic plan 还需要构造 (2n)
的实数矩阵、符号分解和波形响应；应先建立 429
维实际模型的内存、分解和误差基准，再决定是否扩展上限或引入更适合大系统的周期稳态算法。

### 2.4 Newton 每次迭代仍然需要新的数值分解

**优先级：P1**

文件：

-   `native/crates/em-core/src/solver/newton.rs`
-   `native/crates/em-core/src/solver/sparse.rs`

当前 Newton 已经做了一项正确优化：

-   `newton.rs` 第 83--111 行：把线性支路的固定 Jacobian stamp
    预先构建到 `fixed`。
-   第 113--156 行：每轮从 fixed matrix 开始，只重新评估 nonlinear
    branches 并 stamp 其 Jacobian。

这部分应保留。

但第 175--192 行每轮都调用：

``` rust
jacobian.solve_newton_async(&rhs, &layout.symbolic).await?
```

`solve_newton_async()`：

-   第 289--307 行：将矩阵重新 permute，并执行 `solve_permuted()`。
-   `solve_permuted()` 第 321--333 行进入数值消元。
-   `solve_blocks()` 第 419--533 行执行 pivot、消元、fill 和回代。

当前 Newton 调用没有向 `solve_newton_async()` 提供跨迭代的 numeric
factor cache。符号 ordering 可以复用，但数值 Jacobian
改变后仍要重新分解。

这不一定是错误：非线性模型的 Jacobian
确实随工作点变化。问题是**事件定位会重复启动许多 Newton
solve，放大了每轮数值分解成本**。

#### 可优化方向

按风险从低到高：

1.  **减少 Newton solve 总数：**
    先优化事件定位，这是最安全的乘数级优化。
2.  **Modified Newton / chord iteration 实验：** 在同一拓扑、同一 active
    set 下允许短期复用
    Jacobian/factorization；设置最大复用轮数、残差下降率、backtrack
    数和模型状态变化阈值。任何收敛恶化都必须强制重新组装/分解。
3.  **非线性支路与线性网络的 Schur complement：**
    将非线性器件端口变量与大型线性子网络分块，消去线性内部未知量。必须评估填充、条件数和数值误差，不能假定
    Schur complement 一定更快。
4.  **跨 event probe 的局部 Jacobian 复用：** 只有在 active
    set、时间步、储能历史与参数条件满足时才允许；probe 与最终 commit
    的状态必须隔离。

不建议一开始就修改稀疏消元算法。先从 progress 数据确认 factorization
时间是否确实占主要比例。

------------------------------------------------------------------------

## 3. Java 调度层：性能被同步放大成游戏 tick 阻塞

### 3.1 每个 Fault Domain 都在游戏线程等待完成

**优先级：P0 / 游戏体验**

文件：

-   `src/main/java/com/electromagnetism/integration/cee/mixin/NativeSimulationTickerMixin.java`
-   `src/main/java/com/electromagnetism/integration/cee/nativebridge/CeeNativeWorld.java`
-   `src/main/java/com/electromagnetism/simulation/FaultDomainManager.java`
-   `src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java`

`NativeSimulationTickerMixin.java` 第 45--48 行取消原 CEE
`tick()`，直接调用：

``` java
ci.cancel();
electromagnetism$world().tick();
```

`CeeNativeWorld.tick()` 第 115 行调用 `coordinator.tick(frame)`。

`FaultDomainManager.tick()` 第 69--79 行：

1.  configure domain。
2.  对 domain 提交数值任务。
3.  对所有 domain 调用 `awaitNumericalWork()`。

`SimulationCoordinator.awaitNumericalWork()` 第 241--249 行对 pending
future 执行 `join()`。

所以当前设计仍是：

**并发提交多个 Domain，但服务器 tick 线程必须等待所有 Domain 完成。**

一个 172 秒的 Domain 就足以让游戏服务器主线程停顿 172 秒。其他 Domain
即使已经完成，也不能消除这个最长任务的阻塞。

`FaultDomainManager` 的 worker pool 使用 `availableProcessors()-1`
作为默认并行度，队列容量也等于并行度；超过队列容量时
RejectedExecutionHandler 会阻塞提交线程执行 `queue.put()`。这是有界
backpressure，但它同样可能让主线程在提交阶段等待。

### 3.2 不能简单删除 join 或把预算改成 5 ms

当前 `SimulationCoordinator` 和 native bridge 是围绕同步 world tick
设计的：

-   `FaultDomainManager` 使用 `UNLIMITED_STEPS` / `UNLIMITED_NANOS`。
-   `JniSimulationBackend.create()` 为 adaptive session 传入 50 ms
    最大物理步长和 accuracy。
-   `em-ffi/src/bridge.rs` Tick 命令以 `i32::MAX` steps、`i64::MAX`
    nanoseconds 执行。
-   `SimulationCoordinator.startWorldTick()` 第 74--82 行要求前一个
    pending task 已经 join；并会清理旧的 unfinished history。

更重要的是，`bridge.rs` 第 280--313 行：

1.  `session.tick_candidate(start)` 创建候选 session。
2.  在候选上执行 topology/parameter/input edits。
3.  执行 `candidate.advance_tick(...)`。
4.  如果返回 Partial 且没有 pending protection event，第 300--304
    行直接返回，不把 candidate 写回 `*session`。

因此，若把 native budget 改成有限时间并让每次 tick
只算几毫秒，**未完成的 candidate continuation
可能被丢弃**。`CircuitSession` 内部的 localization bracket/trial cursor
虽然支持 cooperative suspension，但如果它们只存在于未提交的 tick
candidate 中，就不能跨 JNI 请求存活。

正确异步化必须先设计 native continuation 的持久化与 revision 语义。

### 3.3 正确的异步调度目标

建议按 Domain 维护：

``` text
DomainRuntime
 ├── accepted physical time
 ├── bounded captured-input queue
 ├── in-flight native continuation
 ├── last accepted revision
 ├── pending protection boundary
 ├── output delivery watermark
 └── backlog / overload diagnostic
```

每个 Minecraft tick：

1.  主线程只 capture 一次不可变输入帧。
2.  将帧追加到对应 Domain 的有界队列。
3.  若该 Domain 没有在途任务，则调度一个有限预算的 native continuation。
4.  不等待所有 Domain 完成。
5.  只在主线程提交已经完成且 revision、输入边界、物理时间均匹配的结果。
6.  保护事件必须先提交并确认，再允许该 Domain 越过事件边界。
7.  backlog 达到上限时，显式报告
    lag/overload；不能静默丢掉机械、音频或保护输入。

**重要：** 如果某个 Domain 的吞吐量低于 20 个 50 ms
窗口/秒，异步化只能防止游戏线程卡死，不能让它实时追上。还需要模型降阶、降低求解成本、限制该域的物理精度/更新频率，或明确允许物理时间滞后。

------------------------------------------------------------------------

## 4. Fault Domain 与数值 Island 需要分离

文件：

`src/main/java/com/electromagnetism/simulation/FaultDomainGraph.java`

第 22--38 行把以下关系 union：

-   电气 branch 端点。
-   多端口 branch 的其他设备端口。
-   同一 Create kinetic network。
-   damage group。
-   alternator assembly。

这对世界副作用、身份、机械输出和故障隔离有意义，但它不等于数值矩阵的实际耦合关系。

建议明确区分：

-   **Fault Domain：** 世界事务/故障隔离/结果接收边界。
-   **Electrical/DAE Component：** Rust 方程组的真实耦合边界。
-   **Mechanical Boundary Group：** 共享 Create
    network、输入速度和输出扭矩的外部耦合边界。

当前同一个 Create kinetic network 会将多个设备合并到同一个 Fault
Domain。若这些设备的电路只是共享每 tick 捕获的 shaft
speed，而它们之间没有瞬时电气或 DAE
状态耦合，则未来可以研究把它们拆成独立数值 session、在统一 tick
边界聚合机械输出。

但不能直接删除 `FaultDomainGraph` 的 mechanical network
union。必须证明：

1.  机械速度作为同一边界输入被一致冻结。
2.  各电气 session 的输出扭矩可在同一物理边界聚合。
3.  Create 网络只消费一次聚合后的输出。
4.  任何保护事件或设备销毁都不会让其他 session 使用过期机械边界。
5.  快照、恢复、重分区和能量账本均可原子迁移。

这是第二阶段架构工作，不应与事件定位优化同时大改。

------------------------------------------------------------------------

## 5. Java 世界状态采集：存在可测的重复工作

文件：

`src/main/java/com/electromagnetism/integration/cee/nativebridge/CeeNativeWorld.java`

第 94--127 行：

-   tick 开始时 capture 一次以处理旧 effects。
-   外部能量交换后再 capture。
-   weather 改变时再次 capture。
-   `coordinator.tick(frame)` 后又 capture。
-   Mixin 的 `endTick()` 再次 capture。

`NativeWorldCapture.capture()` 第 45--83 行会重新遍历 registered
devices、重建 descriptors/branches，并调用
panels、alternators、radioLinks、conductors、trains、environment
等采集逻辑。

`captureTick` 只缓存时间，不缓存完整 Frame。

这不是 172 秒的首要原因，但会带来可测的 Java CPU 和分配成本。

建议：

-   为 world mutation 建立 capture epoch/version。
-   在同一 tick 内，若设备拓扑、参数、线网、机械状态和 waveform
    subscription 未变，复用不可变 capture snapshot。
-   对 weather、external energy、Create speed 等有明确副作用的阶段，按
    epoch 使缓存失效。
-   不要只用 `gameTime` 作为缓存键，因为同一个 tick 内世界也可能被修改。
-   分别记录
    `capture_devices_us`、`capture_wires_us`、`capture_alternators_us`、`capture_radio_us`、`capture_encode_us`。

------------------------------------------------------------------------

## 6. 当前 benchmark 与 429 维电站负载不等价

文件：

`native/crates/em-core/examples/scale_benchmark.rs`

第 73--127 行：

-   `rc_islands` / `nonlinear_islands` 是 256 组重复的四支路小电路。
-   `coupled_islands` 是 256 组重复的五支路绕组耦合小电路。
-   第 161 行确认总支路数 1024。

这些 benchmark 很适合验证：

-   大量重复 island 的轨迹复用。
-   小型非线性岛的求解。
-   RC exact response。
-   小型耦合绕组岛。

但它们没有模拟一个 429 未知量、包含 Relay
保护事件、MOSFET/BJT/整流器、励磁轴边界和跨事件拓扑确认的单体大型网络。

因此，199.18 ms 的 `nonlinear_islands` 结果不能用于推断 429
维电站的事件窗口耗时。

### 必须新增的真实 benchmark

从 AutoJS 电站场景捕获一份去除世界坐标和用户数据的 canonical topology
fixture，至少包含：

-   与游戏场景相同的 branch model 分布。
-   相同端口数和耦合关系。
-   相同初始储能。
-   相同 source phases / shaft speeds。
-   相同 Relay active state、pickup/dropout 参数。
-   相同 nonlinear 参数和热保护参数。
-   同一 50 ms 窗口中真实触发的两个保护事件。

建立以下对照：

  -----------------------------------------------------------------------
  Benchmark                           目的
  ----------------------------------- -----------------------------------
  Plant / protection disabled         量出无事件时的基础瞬态成本

  Plant / relay present but no        分离 Relay 模型本身与事件定位成本
  crossing                            

  Plant / one protection crossing     单事件定位成本

  Plant / two crossings               多事件和事件确认成本

  Plant / sampler disabled vs enabled 采样器是否改变求解路径

  Plant / event probe old vs new      验证事件定位重构收益
  -----------------------------------------------------------------------

每项记录：

-   wall time。
-   accepted/rejected/localization trials。
-   Newton iterations。
-   backtracks。
-   linear solves。
-   numeric factorizations。
-   factorization / assembly / substitution time。
-   max unknowns / nonzeros / fill insertions。
-   event times and order。
-   energy residual。
-   final state error。

------------------------------------------------------------------------

## 7. 必须补齐的诊断指标

当前 `native/crates/em-core/src/solver/progress.rs` 已有很好的基础：

-   `newton_iterations`
-   `backtracks`
-   `linear_solves`
-   `localization_trials`（通过 `AdvanceWork`）
-   `assembly_us`
-   `factor_us`
-   `substitution_us`
-   `factorizations`
-   `cache_hits`
-   `max_unknowns`
-   `max_factor_dimension`
-   `max_nonzeros`
-   `harmonic_trials`
-   `mixed_intervals`
-   `periodic_fallbacks`
-   `largest_error` / `error_branch`

`EM_NATIVE_PROGRESS_LOG` 会在 unlimited request
下启用详细日志；`EM_NATIVE_PROFILE_INIT` 可用于初始化/工作点诊断。

但当前指标仍缺少 event-specific 分解。建议新增：

``` text
localization_us
localization_probe_solves
localization_newton_iterations
localization_factorizations
localization_backtracks
localization_rejected_probes
localization_final_validation_us

refined_trials
full_step_solves
half_step_solves
quarter_step_solves
refinement_error_retries

island_plan_enabled
island_plan_disabled_reason
electrical_component_count
protected_component_count
largest_component_unknowns
```

并且 `phase` 应能区分：

``` text
advance
adaptive-refinement
newton
linear-factorization
protection-detection
protection-localization
protection-final-validation
island-solve
```

不要只输出总 `factor_us`。需要能回答"这 172
秒里，保护定位用了多少秒、做了多少次分解"。

------------------------------------------------------------------------

## 8. 重构路线图

### Phase 0：建立基准与诊断，不改算法

-   [ ] 保存当前 429 维电站 topology fixture。
-   [ ] 固定同一初始 snapshot、参数、输入和随机/时钟状态。
-   [ ] 开启 `EM_NATIVE_PROGRESS_LOG`。
-   [ ] 记录
    `localization_trials`、`newton_iterations`、`factorizations`、`factor_us`。
-   [ ] 增加 `localization_us` 与 refined trial 计数。
-   [ ] 记录 Rust `Layout.components` 数量、`Plans` 是否为空及禁用原因。
-   [ ] 记录 `layout.size`，确认 429 是支路数还是未知量数。
-   [ ] 记录 harmonic plan 是否启用及失败原因。
-   [ ] 运行 5 次，使用 P50/P95，不只比较单次最好值。

**阶段 0 验收：** 可以明确解释 172
秒中事件定位、Newton、factorization、Java capture 各自占比。

### Phase 1：低风险事件定位优化

-   [ ] 提取 `ProtectionEventFunction`，为 Relay/thermal protection 提供
    signed margin。
-   [ ] 增加 `TrialPurpose::ProtectionProbe`。
-   [ ] Probe 使用单次非提交求解，避免每轮 full+half+quarter。
-   [ ] 使用 safeguarded secant/Brent + bisection fallback。
-   [ ] 保留 event bracket 与方向约束。
-   [ ] 在最终事件时间执行完整精度 `ProtectionCommit`。
-   [ ] 最终 commit 必须通过正常误差检查、能量检查和 event recheck。
-   [ ] 对多个接近事件按最早时间和稳定 ID 排序。
-   [ ] 对 probe 不得写入 accepted state、energy ledger、measurements 或
    sampler history。

**阶段 1 验收：**

-   保护事件时间误差在明确容差内。
-   两次事件顺序不变。
-   pending event ID/branch/action 一致。
-   Java acknowledgement 后能够继续推进。
-   能量残差与原基线一致。
-   localization 的 Newton/factorization 次数显著下降。

### Phase 2：Newton 与稀疏数值优化

-   [ ] 从 profile 确认 factorization 是否为主要成本。
-   [ ] 对 Relay active state 固定的 event probe 测试 Jacobian reuse。
-   [ ] 实验 Modified Newton/chord iteration。
-   [ ] 设置 Jacobian 最大复用次数。
-   [ ] 残差下降率不达标时强制刷新 Jacobian。
-   [ ] backtracking 增多、active set 改变或模型状态变化时强制刷新。
-   [ ] 对照完整 Newton，验证相同事件顺序、误差与能量账本。
-   [ ] 评估线性子网络 Schur complement 的 fill 和条件数。

**禁止：** 不得直接跨拓扑、参数、Relay active state 或储能历史变化复用
numeric factorization。

### Phase 3：按组件分类的混合电路岛

-   [ ] 将全局白名单改为组件级能力分类。
-   [ ] 识别保护组件、普通非线性组件、可 exact RC 组件和可重复方程组件。
-   [ ] 让保护组件保留中间轨迹。
-   [ ] 为普通独立组件生成同一时间点的状态。
-   [ ] 建立全局最早事件屏障。
-   [ ] 所有组件必须在同一接受时间合并。
-   [ ] 只有验证组件之间没有共享状态/电气/机械瞬时依赖时才允许拆分。

如果电站实际上只有一个电气连通组件，则跳过"拆岛能加速该电站"的假设，转而优化单体稀疏求解。

### Phase 4：Java 异步调度与 continuation

-   [ ] 设计每个 Fault Domain 的 bounded input queue。
-   [ ] 设计持久化 native continuation token 或可序列化 continuation
    state。
-   [ ] 修复 `Command::Tick` 对 Partial candidate 直接丢弃的问题。
-   [ ] 明确 accepted prefix 与 unaccepted probe state 的提交语义。
-   [ ] 不允许下一 tick 清空尚未处理的历史输入。
-   [ ] 结果只在主线程按 revision、frame identity、physical time 提交。
-   [ ] Domain backlog 达上限时给出明确诊断。
-   [ ] 保护事件未确认时禁止该 Domain 越过事件边界。
-   [ ] 不让一个 Domain 的慢任务阻塞其他无依赖 Domain。

**禁止：** 只删除 `awaitNumericalWork()`、只把 `UNLIMITED_NANOS`
改小、或只取消 CompletableFuture。没有 continuation
持久化和输入队列时，这些改动会丢失历史输入或未接受候选状态。

### Phase 5：外部机械边界分区（可选，高风险）

-   [ ] 分析 `FaultDomainGraph` 中 mechanical network union 的实际贡献。
-   [ ] 区分"共享冻结速度输入"与"真正瞬时耦合状态"。
-   [ ] 若多个电气系统只共享已采样的 Create shaft speed，评估独立 native
    session。
-   [ ] 将各 session 的 torque 在同一 world boundary 汇总。
-   [ ] 保证 Create 只消费一次合并后的机械输出。
-   [ ] 对拓扑变化、网络合并/拆分、存档恢复和保护事件建立事务测试。

不要在未证明独立性前删除 mechanical network union。

------------------------------------------------------------------------

## 9. 验收标准

### 数值正确性

-   [ ] 保护事件时间在预先定义的误差范围内。
-   [ ] 保护事件顺序不变。
-   [ ] 同一事件不会重复触发或漏触发。
-   [ ] 事件确认后所有相关 Domain 从同一物理边界继续。
-   [ ] 能量账本、储能和耗散残差与基线一致。
-   [ ] Snapshot/Restore 后继续推进与不中断推进结果一致。
-   [ ] Probe 失败/取消不会污染 accepted state。

### 性能

至少报告：

  指标                           目标/说明
  ------------------------------ -----------------------------------------
  单事件 localization probe 数   与当前约 29 次二分基线比较
  localization wall time         单独统计
  Newton iterations              按 accepted advance / localization 分开
  numeric factorizations         按事件和普通步分开
  factorization wall time        P50/P95
  50 ms 窗口 wall time           P50/P95/P99
  Java tick blocking time        必须独立统计
  Domain backlog                 必须有上限和告警
  429 维电站                     使用真实拓扑 fixture，不用重复小岛替代

### 游戏可用性

若目标是实时游戏，最终必须证明持续吞吐至少达到：

\[ 20 `\text{个 50 ms 物理窗口/秒}`{=tex} \]

且服务器 MSPT 的 P95 不超过 50 ms。

若单个大型电站仍无法达到这个吞吐量，则需要明确提供受控降阶/降频模式，而不是仅靠后台异步把
172 秒延迟隐藏起来。

------------------------------------------------------------------------

## 10. Codex 实施提示词

以下内容可直接作为下一阶段 Codex
任务。要求先测量，再实施，不允许凭猜测改精度或删除保护。

``` text
任务：重构 Create-Electromagnetism 的大型电站瞬态保护事件求解性能。

基准源码为当前工作树。首先检查：
- native/crates/em-core/src/session/circuit.rs
- native/crates/em-core/src/session/circuit/protection.rs
- native/crates/em-core/src/solver/transient.rs
- native/crates/em-core/src/solver/transient/islands.rs
- native/crates/em-core/src/solver/transient/harmonic.rs
- native/crates/em-core/src/solver/newton.rs
- native/crates/em-core/src/solver/sparse.rs
- native/crates/em-core/src/solver/progress.rs
- native/crates/em-ffi/src/bridge.rs
- src/main/java/com/electromagnetism/simulation/FaultDomainManager.java
- src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java
- src/main/java/com/electromagnetism/simulation/FaultDomainGraph.java
- src/main/java/com/electromagnetism/integration/cee/nativebridge/CeeNativeWorld.java
- scripts/cee-creative-power-plant.py

第一步：建立真实 429 维电站基准，记录：
localization_trials、newton_iterations、backtracks、linear_solves、
factorizations、factor_us、assembly_us、substitution_us、
max_unknowns、max_nonzeros、island plan 状态和 harmonic plan 状态。

不要把 1024 支路重复独立小岛 benchmark 当作 429 维真实电站的替代。

第二步：实现专用 ProtectionProbe。
当前每次保护二分都会调用 refined_trial，导致每次 probe 重复 full/half/quarter step。
将事件探测与物理接受分离：
- Probe 不得修改 accepted state、energy ledger、measurements、sampler。
- Probe 使用低成本非提交求解。
- 使用有 bracket 保证的 safeguarded secant/Brent 或 bisection fallback。
- 最终事件时间必须执行正常精度的 ProtectionCommit。
- Commit 必须重新验证事件函数、积分误差、能量残差和事件顺序。
- 保留 cooperative cancellation 和可恢复的 bracket。

第三步：评估 Newton numeric factorization reuse。
当前 fixed linear stamp 已在一次 Newton solve 内复用，保留该实现。
仅在同一拓扑、参数、active set 和储能历史下实验 Modified Newton。
必须设置刷新条件；收敛变差、回溯增加或 active set 变化时强制重分解。
不得跨不兼容状态复用 numeric factorization。

第四步：重构电路岛 eligibility。
当前 Plans::compile 使用整个 CircuitSession 的全局模型白名单。
改为按实际耦合组件分类，但必须保留全局最早保护事件屏障。
如果组件之间存在多端口、磁耦合、机械或其他 DAE 依赖，不得错误拆分。
所有组件必须在同一接受物理时间合并。

第五步：评估游戏 tick 非阻塞调度。
当前 FaultDomainManager.tick 最终等待所有 pending.join()。
但不能只删除 join 或缩短 budget。
先设计 bounded input queue、native continuation 持久化、accepted-prefix 提交和保护事件确认语义。
修复 bridge.rs 中 Partial candidate 未提交会丢失 continuation 的路径。
任何时刻都不能丢失历史输入或重复提交能量/世界副作用。

第六步：增加回归测试：
- 单 Relay pickup/dropout。
- 两个接近的保护事件。
- 事件定位中途 cooperative pause/resume。
- probe 被取消后 accepted state 不变。
- 事件最终 commit 与原基线一致。
- 两个独立电气组件共享机械速度输入。
- 保护组件与普通组件混合。
- Snapshot/Restore 后继续定位。
- 多 Domain 中一个慢域不得阻塞其他无依赖域。

禁止：
- 通过放宽保护阈值隐藏性能问题。
- 通过删除保护事件定位来加速。
- 通过静默跳过 Relay/thermal protection。
- 通过清空能量账本、历史输入或 pending events 恢复运行。
- 通过让不同组件接受不同物理时间破坏全局时间一致性。
- 通过直接提高 worker 数量声称解决单域瓶颈。

交付：
1. 修改后的源码。
2. 真实电站 benchmark fixture。
3. before/after 原始 JSONL。
4. 数值误差与能量对照。
5. localization/Newton/factorization 分项耗时。
6. Java tick MSPT 与 Domain backlog 报告。
7. 明确列出未解决的实时吞吐限制。
```

------------------------------------------------------------------------

## 11. 本次审查的最终判断

### 已由源码确认

1.  每次保护定位 probe 都走 `refined_trial()`，而不是专用轻量事件求值。
2.  `refine_local()` 至少执行 full + two-half，误差较大时还会执行
    quarter path。
3.  保护定位最多 52 轮，当前时间 bracket 目标约 100 ps。
4.  电路岛计划采用全局模型白名单，出现 Relay/MOSFET/BJT/Rectifier
    等不支持模型时整个计划为空。
5.  harmonic plan 对未知量规模设置 256 上限，并有严格模型白名单。
6.  Newton 的线性部分已做固定 stamp 复用，但每轮 numeric Jacobian
    仍要进行新的数值分解。
7.  Java FaultDomainManager 在游戏 tick 中等待全部 Domain 计算完成。
8.  直接把 native budget 改为有限时间存在丢失候选 continuation 的风险。
9.  当前 1024 支路基准是重复独立小岛，不等价于真实 429 维保护事件电站。

### 需要真实日志确认

1.  172 秒中 `localization_trials` 实际数量。
2.  localization 的总 wall time。
3.  Newton iterations 与 factorization 的实际数量。
4.  `factor_us` 是否占据大部分耗时。
5.  `Layout.components` 实际数量，以及岛计划禁用后本可拆分的组件数量。
6.  429 指未知量规模还是支路数量。
7.  Java capture/encode 与 native solve 的耗时占比。
8.  多 Domain 场景的 worker 并发度和队列等待时间。

**最终建议：下一轮先做 Phase 0 和 Phase
1。不要同时重写岛架构、Newton、异步调度。先证明保护定位的重复完整求解占据多少成本，再用专用
probe 减少这部分工作量。随后才判断是否值得继续做 Modified
Newton、混合岛和非阻塞调度。**
