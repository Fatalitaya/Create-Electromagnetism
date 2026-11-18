# 电路仿真的状态归属与重构方案

2026-11-18 工作树静态调查。Java 掌握世界中仍然存在的设备及其生命周期，Rust 掌握电路已接受的物理状态。最先需要处理的是两者之间的交付边界：求解已经提交，交付却可能只完成一部分。其次可以收敛同步 tick 的提交接口，再按设备族整理支路知识。下列重构和验收均为待实施项。

## 一次 tick 中谁拥有哪项事实

| 时点 | 权威来源 | 必须保留的约束 |
| --- | --- | --- |
| 采集与分域 | Java 的 CEE 世界集成捕获端口、支路和接收者身份，`FaultDomainManager` 为各域选择输入和截止时间 | 采集帧描述当时的世界。交付时按设备实例重新解析，避免同位置的新设备接收旧损坏事件。 |
| 数值推进 | Rust 的 `CircuitSession` 从已接受状态创建 tick candidate。FFI 在候选会话上编辑输入、推进并生成结果 | 预算耗尽或编辑失败时保留上一个已提交边界。带保护事件的已接受前缀可以先提交，等待世界确认。 |
| 世界交付 | Java 的 `SimulationCoordinator` 保留待交付观察值，`NativeWorldDelivery` 将其作用于世界 | 区分读数覆盖、音频发送和方块损坏。重试必须考虑每种操作是否已经生效。 |
| 确认与存档 | Java 确认实际处理的事件和消失的支路；Rust 校验事件、revision 和物理边界。Java 配对保存 native 快照与域交付状态 | 恢复后要能判断哪一批仍可重放。revision 用于标识运行中的事务；兼容性判断沿用模组元数据。 |

源码入口见 [故障域管理](../src/main/java/com/electromagnetism/simulation/FaultDomainManager.java)、[候选会话](../native/crates/em-core/src/session/circuit.rs)、[FFI 提交点](../native/crates/em-ffi/src/bridge.rs)、[保护事件确认](../native/crates/em-core/src/session/circuit/protection.rs)和[存档编解码](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeWorldPersistence.java)。`em-core` 仅依赖工作区的 `em-protocol`，可在独立 Rust 进程中使用同一套元件和求解器。[crate 依赖](../native/crates/em-core/Cargo.toml)

## 首先收窄效果重试并限定去重记录的寿命

**同一批音频存在重复发送路径。** `NativeWorldDelivery.apply` 先发布波形，随后校验并执行保护事件。如果后半段抛异常，`tryApplyAwaitingEffects` 留下整批观察值以便重试。收音机的 `publishWaveform` 先调用 `AudioNetworking.output` 发包，之后才按采样时间去重并写本地波形缓冲。因此本地曲线可以保持正确，服务器仍可能再次发送同一批样本。玩家是否听到重复音频有待实机观测。[效果执行顺序](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeWorldDelivery.java) · [交付重试](../src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java) · [波形发布](../src/main/java/com/electromagnetism/content/instruments/InstrumentBlockEntity.java)

**保护事件去重集合随已处理事件数增长。** 每个已处理的事件以 `(session, event)` 加入 `confirmedEvents`。存档全量写出该集合，加载时全量恢复。Rust 在确认成功后清空对应的 `pending_events`，Java 集合没有随该边界退休的路径。反复吸合、释放的继电器即可累积记录，实际存档开销取决于事件量。[事件集合与序列化](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeWorldDelivery.java) · [世界保存入口](../src/main/java/com/electromagnetism/integration/cee/nativebridge/CeeNativeWorld.java)

建议按以下顺序修改交付链：

1. 将整批可预检的目标身份、事件消费者校验移到首次产生副作用之前。在真正修改方块时仍核对实例，前一项修改触发的邻居回调可能已替换后续目标。
2. 按交付后果处理重试。可覆盖的显示值允许重算；流式音频按通道和已发送采样范围跳过重复发送；损坏和继电器动作沿用逐事件确认。只有同一边界全部事件都得到处置后，才向 Rust 确认。预检无法消除执行途中失败的情形。
3. 在构造同一份世界存档时，从各域仍待交付的事件算出去重记录的保留范围。某个事件已作用于世界、同批另一事件却失败时，前者仍需保留。核对普通 tick、保存时主动确认、域迁移三条路径后，再回收已不可重放的记录。恢复已有存档时先读取原记录，再依据恢复出的待交付边界裁剪。[保存前确认](../src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java) · [待交付边界的保存与恢复](../src/main/java/com/electromagnetism/simulation/WorldInputHistory.java)

## 再收敛同步 tick 的事务接口

`FaultDomainManager` 给生产域传入 `Runnable::run`。协调器仍通过 `supplyAsync` 和 `pending.join()` 表达执行，并在 `execute` 内用 `cursor`、`inputBase`、`applied`、`acknowledged` 追踪进度。JNI 后端的 `applyInput` 则依据 `Session.tickInputs` 是否存在，选择把指令放进列表或立即提交。同一方法的提交语义取决于此前调用顺序。现有验证和游戏脚本确实使用另一种协调器构造方式，迁移前需要保留这些入口的行为。[生产域创建](../src/main/java/com/electromagnetism/simulation/FaultDomainMigration.java) · [JNI 暂存与提交](../src/main/java/com/electromagnetism/simulation/JniSimulationBackend.java) · [队列执行器调用者](../src/verification/java/com/electromagnetism/simulation/WorldInputHistoryChecks.java)

1. 由协调器一次构造 tick 请求，携带起始 revision、世界 tick、时间区间、预算和有序输入编辑。显式保留解除旧波形订阅、改变拓扑、绑定新订阅和设置区间输入的顺序。JNI 编码完整请求，沿用 native 候选会话的单次提交点；涉及字段变动时同步修改两端编解码。
2. 将上一边界的世界确认作为独立提交。若确认成功而新 tick 随后超时，Java 应保留确认后的 revision 和原已接受物理时间，仅丢弃本次候选编辑。这个场景决定了回退的起点。
3. 生产同步调用直接返回执行结果，再于原有世界交付时点消费。分别核对保存时待交付结果、现有有界执行调用者，以及同模组元数据下 `InputHistory` 与 `TickBoundary` 两种已有存档入口。取消多余的 future 状态后仍要保留“求解已接受、效果待交付”这一状态。[协调器提交和存档](../src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java) · [既有存档入口](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeWorldPersistence.java)

## 设备复用和可直接合并的检查

**电泵可作为首个设备族。** `NativeMachineAdapter` 创建整流、限流和储能支路，`NativeWorldDelivery` 重新列举这些角色并汇总功率，`NativeFluidPump` 单独按名称找到储能支路取能。当前功率汇总遇到缺失读数就返回零，角色名称漂移会表现成正常零功率。建议先在电泵实现旁集中支路角色、显示功率所需支路和缓冲支路定位，保留现有角色字符串与派生 UUID。明确区分读数缺失和实测零功率，再由显示端选择呈现方式。其他设备有同类需求时再提取共用逻辑。[支路构建](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeMachineAdapter.java) · [功率汇总](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeWorldDelivery.java) · [电泵取能](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeFluidPump.java)

两项低风险整理可以分别实施。一次帧比较中缓存 `sameBindings` 结果，供静态参数比较和拓扑编辑选择共用；保留对动态音频样本与轴速输入的现有分类。将预算耗尽的原因显式传给 `recentBudgetSkips`，保留变压器最近一次有效读数最多 20 tick 的现有策略，消除展示文案改动对该策略的影响。[帧比较](../src/main/java/com/electromagnetism/simulation/SimulationCoordinator.java) · [读数保留策略](../src/main/java/com/electromagnetism/simulation/FaultDomainManager.java)

## 实施后的游戏内验收

| 场景 | 观察项与预期 |
| --- | --- |
| 同一边界同时含音频和保护事件，后一个效果交付失败后重试 | 记录会话、事件 ID、音频通道及采样区间。每个采样区间只发送一次，已执行事件只改变世界一次，剩余事件仍能完成。 |
| 多事件部分完成时保存、重载，随后让继电器反复切换并再次保存 | 重载后只补完待处理事件，同位置替换设备不受旧事件影响。对照两次存档中待交付事件和去重键；全部确认后的记录数应脱离历史切换总次数。 |
| 本次 tick 预算耗尽、输入被拒绝或上一批确认后新推进失败 | 对照故障前后 revision、已接受时间、故障域和下个有效 tick。上一批确认有效，失败候选的数值状态和编辑均未进入下一次推进。 |
| 电泵正常供电、断电、支路读数不可用 | 观察储能支路身份、实际取能和功率显示；重载后仍对应原缓冲支路。记录缺失读数与实测零功率各自的表现。 |

使用现有 [AutoJS 入口](../scripts/cee-basic-autojs.ps1)和[生产运行状态观察脚本](../scripts/cee-synchronous-runtime-inspect.js)记录场景、操作、预期与实测结果。[tick 恢复脚本](../scripts/cee-tick-recovery.js)使用独立协调器，可核对已有调用者的失败恢复行为。该脚本覆盖的路径与生产域不同；同批部分交付、存档重放和音频重复发送仍需在相应世界场景中单独观测。以上场景均待实测。
