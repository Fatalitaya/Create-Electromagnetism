# CEE 交流慢域优化后的剩余性能热点

2026-11-19。在本地世界的 27 支路发电机、变压器、调压器和负载电路上，**优先处理周期求解失败后的长尾，其次减少正常区间的数值分解**。五轮回放中，每轮后 100 个 tick 都有相同的三个区间超过 50 ms。其余 97 个区间每个仍需 42 次 Newton 求解。两类问题分别决定卡顿和持续可承载的电路数量。

## 50 ms 预算花在了哪里

原生回放从同一已接受检查点 sequence 8146 恢复，逐 tick 重交相同轴速 26.8083 rad/s，每轮推进 110 × 50 ms。下表按从零开始的回放 tick 编号分组。墙钟耗时来自此前五轮关闭详细日志的 Windows x86-64 release 测速（Ryzen 9 9950X）。区间路径与求解计数来自本次对同一检查点另跑的 110 个 tick。恢复初期一行计入首次推进，快照反序列化和进程启动在计时窗外。

| 区间 | 五轮样本数 | P50 | P95 | 超过 50 ms | 单区间 Newton 求解数 |
|---|---:|---:|---:|---:|---:|
| 恢复后第 0–9 tick | 50 | 125.25 ms | 172.65 ms | 50/50 | 172–475 |
| 稳态，接受混合时域轨迹 | 485 | 17.34 ms | 20.99 ms | 0/485 | 42 |
| 稳态，回退到自适应瞬态 | 15 | 109.63 ms | 123.36 ms | 15/15 | 278–289 |

合并两类稳态样本后，P95 为 **23.08 ms**，P99 为 **110.66 ms**。回退仅占稳态 tick 的 3%，却占五轮稳态原生总耗时的 **16.2%**。恢复后的前十个 tick 占整轮原生总耗时的 **37.5%**。固定输入使回退可重复，发生比例只适用于这个检查点和这段运行时间。

## 优化优先级

**1. 缩短回退路径，直接改善 P99。** 回放 tick 36、43、50 各记录一次周期路径回退，分别接受了 39、35、33 个外层瞬态步，消耗 278、289、283 次 Newton 求解。三个区间的保护事件定位次数均为零。源码在候选失败后将下次尝试推迟 50 ms；此时可能已经支付了粗、细两条轨迹的成本。现有计数将热保护界限、窗口误差、端点储能差、能量账本和内部求解失败合并记录，无法据此指定某个电压、器件或阈值为主因。[周期回退与重试](../native/crates/em-core/src/solver/transient/harmonic.rs) · [混合轨迹的验收条件](../native/crates/em-core/src/solver/transient/harmonic/mixed.rs)

建议先按失败原因记录首个失败支路、归一化误差以及失败前完成的求解数。若主因是粗细轨迹差异，优先比较一次二分重试与当前整窗回退的成本，两个半窗都从有效候选边界推进。若主因是接近保护阈值，保留原事件定位。验收看同一回放的超预算次数和 P99，同时比较负载 RMS、轴功、累计电能与保护触发 tick。

**2. 减少正常混合区间的重复分解，提高持续吞吐。** 4.267 Hz 下，每 50 ms 的粗轨迹取 7 步，细轨迹取 14 步。每步求解隐式中点和后向欧拉端点，因此一个外层接受步实际调用 `(7 + 14) × 2 = 42` 次 Newton。97 个正常稳态区间合计 4,074 次 Newton 求解、13,788 次 Newton 迭代、27,576 次数值分解，记录 1,592 次因子缓存命中。该组带计时器的原生推进共 1,315 ms，其中分解累计 **633 ms（48.1%）**，装配 146 ms（11.1%），回代与缓存因子应用 37 ms（2.8%）。剩余时间包含模型求值、状态与测量处理、控制和诊断等开销，计数不足以继续分摊。这些占比使用同一带计时器运行的分母。[两档步长与中点推进](../native/crates/em-core/src/solver/transient/harmonic/mixed.rs) · [计时器定义](../native/crates/em-core/src/solver/progress.rs)

这里平均每次 Newton 迭代发生两次数值分解。源码允许缩减系统解在原方程残差校验失败后重新求解完整系统，也允许同一 Jacobian 用第二个右端项复核收敛。总计数不能区分两者。下一次原生剖析应分别计数缩减成功、缩减后回退、直接全系统和第二右端项的分解时间。若同一拓扑频繁支付两次分解，再对比优先求完整系统的策略。若重复分解收益有限，第二个实验是减少粗轨迹的 14 次 Newton 调用，同时用细轨迹核对端点状态与能量。14/42 是调用数占比，实际节省时间需要实测。[Newton 收敛复核](../native/crates/em-core/src/solver/newton.rs) · [矩阵求解分派](../native/crates/em-core/src/solver/sparse.rs) · [缩减系统残差回退](../native/crates/em-core/src/solver/sparse/reduction.rs)

**3. 单独优化恢复和工况变化后的前几个 tick。** 当前准入至少等待一个电周期，本场景约 234 ms 物理时间。带计数回放在第 0–3 tick 没有混合区间，第 4–8 tick 每 tick 各有一次回退，第 9 tick 首次接受混合区间；该 tick 仍共接受 98 个外层步。初期耗时既有等待积累证据的部分，也有开始尝试后继续走瞬态的部分。建议分别计时计划编译、首次准入和首次整窗成功。在固定轴速恢复、变速、带载换挡三种场景分别比较前十 tick 总耗时。涉及热状态、磁通和保护决定的准入仍需核对。[准入与阻尼判据](../native/crates/em-core/src/solver/transient/harmonic.rs) · [推进步长选择](../native/crates/em-core/src/session/circuit.rs)

## 游戏线程与评估边界

重新解析最终 DLL 的 spark 原始采样。它仅采集服务端主线程，间隔 2 ms，共 15,174 ms 样本时间。其中 **8,024 ms（52.9%）** 落在 `FaultDomainManager.tick → SimulationCoordinator.awaitNumericalWork → CompletableFuture.join → park`，世界捕获占 1.81%，交付占 0.29%。采样结束时 spark 为 19.98 TPS，10 秒 MSPT 的 P50/P95 为 1.09/1.38 ms；同一次域报告中的慢域工作线程耗时 24.89 ms，两个域均完成 469/469 tick。栈上等待、spark 的 tick 统计和工作线程耗时各有自己的计时范围。就此场景，应先缩短慢域占用的时间，再评估 Java 捕获或交付优化。[域等待](../src/main/java/com/electromagnetism/simulation/FaultDomainManager.java) · [工作线程同步](../src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java)

示波器订阅会使周期快路径退出。已有 AutoJS 记录证明带载时交付了 204 个有效样本；订阅期间的稳态 P95 仍待单独采样。11 月 18 日的 29 域满载场景也需要单独复测，重点记录慢域尾延迟、并发域数和主线程等待。

## 本地复核入口

- 无详细日志的单 tick 数据为 `build/ac-release-after-{1..5}.jsonl`，原版对照为 `build/ac-release-before-{1..5}.jsonl`，每轮摘要为 `build/ac-native-comparison.csv`。稳态分位数取每轮最后 100 个样本合并后排序，取最近秩。
- 计数复核使用已有 release 可执行文件 `build/ac-final-target/release/examples/scale_benchmark.exe --replay build/ac-world-8146.bin 110`。将 `EM_NATIVE_PROGRESS_LOG` 指向一个新文件，按顺序将 `phase=complete` 行与标准输出中的 tick 对齐。检查点 SHA-256 为 `1ff1a6450eda1a4cb0c3b8403c33f801d4bc01c55542382fddc344fb2f87cde4`。[Rust 回放入口](../native/crates/em-core/examples/scale_benchmark.rs)
- 游戏侧原始文件为 `build/ac-game-run/config/spark/profile-2026-11-19_13.13.21.sparkprofile`，同目录树下的 `build/ac-game-run/verified-spark-stop.json` 记录采样结束时的域状态，`build/ac-game-run/scope-output.json` 记录独立订阅场景中的示波器读数。
