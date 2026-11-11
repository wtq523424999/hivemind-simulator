# Dock3 异常条件拦截行为规格

> 依据：DJI Cloud API 官方文档（Dock3 `properties.html` / `wayline.html` / `hms.html` / `drc.html` 与全局 `error-code` 表，共 448 条错误码，抓取日期 2026-11）。
> 目的：让模拟器具备"异常条件检查"能力，即平台下发任务/起飞命令时，设备端按真实机场行为拦截并返回对应错误码。

---

## 一、核心结论：拦截分两个阶段，错误码完全不同

| 阶段 | 时机 | 上报通道 | 是否产生 `break_reason` |
|---|---|---|---|
| **准备期拦截** | `flighttask_prepare` / `flighttask_execute` / `takeoff_to_point` 收到时 | `services_reply.result` | 否（任务未开始，无断点） |
| **飞行期中断** | 任务已在执行（`mode_code=5`） | `flighttask_progress(status=failed)` + `ext.break_point.break_reason` | 是 |

环境类条件（风、雨、供电、温度）**同时在两个阶段有码**，但含义不同：

- 准备期：`514134` 雨量过大、`514135` 风速过大、`514136` 供电断开、`514137` 环境温度低于 -20℃ → 拒绝执行，用户可"稍后重试"。
- 飞行期：`784` 大风返航导致航线中断 → 已经返航，任务终结，不可续飞。

即：风速雨量在起飞前是"拒飞"，起飞后是"被迫返航"。二者互斥，取决于异常发生在哪个阶段。

---

## 二、环境条件与官方错误码对照表

### 2.1 准备期（`services_reply.result`）

| 条件 | 判据（OSD 字段） | result 错误码 | 官方原文 |
|---|---|---|---|
| 雨量过大 | `rainfall >= 2`（中雨）或 `>= 3`（大雨），见 2.3 | `514134` | 雨量过大，机场无法执行飞行任务，请稍后重试 |
| 风速过大 | `wind_speed >= 9` m/s | `514135` | 风速过大，机场无法执行飞行任务，请稍后重试 |
| 机场供电断开 | 供电状态异常 | `514136` | 机场供电断开，机场无法执行飞行任务，请恢复机场供电后重试 |
| 环境温度过低 | `environment_temperature < -20` ℃ | `514137` | 环境温度过低于-20℃ (-4°F)，机场无法执行飞行任务，请稍后重试 |
| 低温 + 降雨结冰 | `environment_temperature < 5` **且** `rainfall >= 2` | `514173` | 由于天气原因（环境温度低于5度并且降雨大于等于中雨），可能导致桨叶结冰影响作业安全，暂无法执行任务 |
| 飞行器电量过低 | `battery.capacity_percent < 30` | `514103` / `321772` | 飞行器电量低于30%，无法执行飞行任务，请充电后重试（建议电量≥50%） |
| 机场未标定 | 未标定状态 | `514168` | 机场未标定，机场无法执行飞行任务，请标定机场 |
| 飞机不在舱内 | `drone_in_dock=0` | `514155` | 飞机不在舱内，无法执行飞行任务，请将飞行器放回至机场 |
| 机场急停被按下 | 急停状态=1 | `514121` | 机场急停按钮被按下，请释放急停按钮 |
| 机场已在作业中 | `mode_code=4` | `514148` | 机场已经在作业中，无法进行远程调试或再次执行飞行任务，请等待当前任务执行完成后重试 |
| 机场初始化中 | `mode_code=5`（待标定）等 | `514170` | 机场系统初始化中，无法执行当前操作或指令，请等待机场系统初始化完成后重试 |
| 舱盖未关闭就位 | `cover_state` 异常 | `514144` | 舱盖未关闭或关闭不到位，请稍后重试或重启机场后重试 |
| 推杆未闭合就位 | 推杆状态异常 | `514143` | 推杆未闭合或闭合不到位，请稍后重试或重启机场后重试 |
| 机场与飞行器断连 | 链路状态异常 | `514120` | 机场与飞行器断连，请重启机场后重试或重新对频 |
| 远程调试中 | `debugMode=true` | `514146` | 机场处于远程调试中，无法执行飞行任务，请退出远程调试后重试 |
| 设备升级中 | 升级状态 | `514147` | 设备升级中，无法进行远程调试或执行飞行任务，请等待升级完成后重试 |
| RTK 业务异常 | RTK 异常 | `514158` | 无法起飞，机场 RTK 业务异常，请重启机场后再试 |

### 2.2 飞行期（`break_reason`，`flighttask_progress.status=failed`）

| 条件 | break_reason | 官方原文 | 平台侧用户文案 |
|---|---|---|---|
| 大风返航 | `784` | 大风返航导致航线中断 | `321784` 任务过程中遇到大风紧急返航 |
| 低电量返航 | `773` | 低电量返航导致航线中断 | `321773` 飞行器执行飞行任务过程中低电量返航，无法完成航线飞行 |
| 起飞前电量不足 | `772` | 当前电量过低无法开始任务 | `321772` 飞行器电量低于30%，无法执行飞行任务 |
| 遥控器与飞行器失联 | `775` | 遥控器与飞行器失联 | `321775` 飞行器航线飞行过程中失联 |
| 限高 | `513` | 飞行器超过限高高度 | `321513` |
| 限远 | `514` | 飞行器超过限远距离 | `321514` |
| 穿过限飞区 | `515` | 航线穿过限飞区，机场无法执行飞行任务 | `321515` |
| 触发避障 | `517` | 飞行器触发避障 | `321517` |
| 接近禁飞区边界 | `519` | 接近禁飞区边界 | `321519` |
| 障碍物/禁飞区导致航线不可达 | `529`（**仅 Dock2**） | The route cannot be reached due to obstacles or no-fly zones | `321529` 目标点位于禁飞区域或者障碍物内…航线任务已暂停 |
| 触碰自定义飞行区边界 | `528`（**仅 Dock1**） | 接近用户自定义飞行区边界 | `321528` 触碰自定义飞行区边界，航线任务已暂停 |

**重要语义差异**：`528/529/530` 对应的是"**航线任务已暂停**"（可断点续飞），而 `773/784` 是"**无法完成航线飞行**"（已返航，任务终结）。模拟器不应把二者混为一类。

**编号规律（已逐条验证）**：`321000 + break_reason` 即平台侧用户文案码。样例：`513→321513`、`517→321517`、`528→321528`、`529→321529`、`530→321530`、`772→321772`、`773→321773`、`775→321775`、`784→321784`，共 21 个样本全部成立。所以那 448 条错误码表其实是 `break_reason` 的用户可读版加上其他模块的码。

### 2.3 雨量阈值的不确定性（需标注推断）

`rainfall` 是枚举：`0=无雨, 1=小雨, 2=中雨, 3=大雨`。官方对 `514134` 只写"雨量过大"，未给出对应枚举值。

- `514173` 明确写"降雨大于等于中雨"，即 `rainfall >= 2`。
- 因此 `514134` 取 `rainfall >= 2` 是与官方最自洽的推断，但**官方未逐字确认**，实现时应记录 `M-2` 诊断码（模拟器推断，待真机验证）。

风速 `>= 9 m/s` 有直接依据：`hms.json` 的 `dock_tip_0x160900BF` / `dock_tip_0x19114000` 文案明确为"风速过大（≥9 m/s），无法执行飞行任务"。

---

## 三、HMS 联动（两条通道必须同时发）

真实设备在拒绝执行的同时，会通过 HMS 上报环境异常。`imminent=1` 表示及时性告警——"会随着风力减小而自动消失"，因此它是**条件型告警**，不是一次性事件。

| 条件 | HMS code | 文案 | level | imminent | device_type |
|---|---|---|---|---|---|
| 风速过大 | `0x19114000` 或 `0x160900BF` | 风速过大（≥9 m/s），无法执行飞行任务 | 2（警告） | 1 | dock |
| 雨量过大 | `0x19114800` | 雨量过大，无法执行飞行任务 | 2 | 1 | dock |
| 供电断开 | `0x19113803` | 设备供电电源断开 | — | 0 | dock |
| 低温 | `0x19113D27` | 低温预警（≤-20℃） | — | 0 | dock |
| 严重低电量 | `0x1610000E` | 无法起飞：严重低电量警报，请及时充电 | 2 | 0 | drone |

HMS 报文结构（`method=hms`，Topic=`thing/product/{gateway_sn}/events`）：

```json
{"data":{"list":[{"args":{"component_index":0,"sensor_index":0},
  "code":"0x19114000","device_type":"3-1-0","imminent":1,
  "in_the_sky":0,"level":2,"module":3}]}}
```

---

## 四、各拦截点的推荐实现位置

### 4.1 航线任务：`WaylineTaskSimulator`

| 拦截点 | 现状 | 应做 |
|---|---|---|
| `handleWaylineCommand`（`:252`） | Dock 不支持时返回 `result=1` | `result=1` 不在 448 个官方码里，平台拿不到可读文案。应改为 `325005`（当前机场无法响应任务，请稍后重试）或 `514100`（机场运行异常，请重启机场后重试） |
| `handlePrepare`（`:428`） | 无条件 `result=0` | 加环境/状态前置检查，命中返回 2.1 表对应码，**不改变任何 `mode_code`**（可恢复的拒绝） |
| `handleExecute`（`:542`） | 无条件 `result=0` | 同上。另外应校验 `ready_conditions.battery_capacity` 与 `executable_conditions.storage_capacity` |
| `advanceProgress`（`:867`） | 只推进进度 | 加运行期检查：风速/电量越界时调用 `publishProgressFailedWithBreakReason(784/773)` 并**同时停止任务** |
| `publishProgressFailedWithBreakReason`（`:1186`） | 只发事件，不停任务 | 这是当前 bug：发完 failed 后 `advanceProgress` 仍会继续发 `in_progress` 和 `ok`，语义矛盾 |

### 4.2 指令飞行：`FlightCommandSimulator`

| 拦截点 | 现状 | 应做 |
|---|---|---|
| `handleTakeoffToPoint`（`:249`） | 仅校验 `rth_mode`，其余无条件成功 | 加与环境/状态前置检查，返回 `514xxx` 或 `337xxx` |
| `handleFlyToPoint`（`:154`） | 仅校验 `points` 非空 | 同上 |
| `scheduleTakeoffProgress`（`:563`） | 固定调度 `task_ready→progress→ok→finish` | 真机在 `task_ready` 之后仍可能因异常中止，需支持中途注入 `wayline_failed` |

`takeoff_to_point_progress` 的官方 status 枚举：`task_finish` / `task_ready` / `wayline_cancel` / `wayline_failed` / `wayline_ok` / `wayline_progress`。**`wayline_failed` 是模拟器目前完全没实现的分支**。

### 4.3 数据源问题：`environment_temperature` 是硬编码

`AbstractDockOsdBuilder.java:75` 把 `environment_temperature` 写死为 `25.0`，不受 `DeviceState` 控制。这意味着即使实现了 `514137`（低于 -20℃）的判据，也没有可修改的输入字段。**必须先把它接到 `DeviceState`**。

同理 `emergency_stop_state`（`:78`）也硬编码为 `0`，`514121`（急停被按下）无法测试。

### 4.4 供电状态缺失

`DeviceState` 有 `electricSupplyVoltage = 230.0`，但没有任何"供电断开"的布尔标志。`514136` 需要新增字段（或约定 `electricSupplyVoltage <= 0` 表示断开）。

> **以上 4.3 / 4.4 已在实现中修复**：`DeviceState` 新增 `environmentTemperature`、`powerSupplyNormal`、
> `emergencyStopPressed`、`dockCalibrated` 四个字段，OSD builder 改为读取状态值，
> `PUT /api/state` 与前端"机场状态模拟"面板均已接入。

---

## 五、实现形态（已完成）

检查逻辑集中在 `AbnormalConditionChecker`（`handler` 包），四个入口共用同一套判据：

```java
AbnormalConditionChecker.check(DeviceState, Stage.PREPARE|EXECUTE)  // → BlockResult(resultCode, alarm, reason)
AbnormalConditionChecker.checkInFlight(DeviceState)                 // → break_reason（0 / 773 / 784）
```

`BlockResult` 同时携带 `resultCode` 与 HMS `alarm`，保证两条通道成对产出、不会漏发。

接入点：

| 入口 | 位置 | 行为 |
|---|---|---|
| `flighttask_prepare` | `WaylineTaskSimulator.handlePrepare` | 拦截后直接返回错误码，不修改任何状态 |
| `flighttask_execute` | `WaylineTaskSimulator.handleExecute` | 同上（prepare 与 execute 之间环境可能已变化，需复查） |
| `advanceProgress`（飞行中） | `WaylineTaskSimulator` | 命中后调 `abortInFlight`：发 failed + break_reason、发 `return_home_info`、停全部调度器、`mode_code=9` |
| `takeoff_to_point` / `fly_to_point` | `FlightCommandSimulator.handle` | 拦截后返回错误码，不推进任务 |

判定顺序按官方错误码编号升序，唯一例外：`514173`（低温+中雨结冰）排在 `514134`（雨量过大）之前，
因为它是后者的更精确前提（`temp<5 且 rain≥中雨`），先判才能给出准确文案。

理由：

1. 同一套条件要服务四个入口（prepare / execute / takeoff_to_point / fly_to_point），散落会重复。
2. 检查结果需要同时产出 `resultCode` 和 `hmsCode`，两者必须成对，集中管理才不会漏发 HMS。
3. 需要在 Web UI 上让用户自由开关"当前模拟哪些异常"，集中判定器便于与 UI 状态绑定。

---

## 六、必须标注为"待验证"的部分

以下内容官方文档未逐字确认，实现时应记录 `M-2` 诊断码：

1. **`514134` 对应的 `rainfall` 枚举值**。官方只说"雨量过大"，未指明是 `2` 还是 `3`。建议取 `>= 2`（与 `514173` 措辞一致）。
2. **多个条件同时命中时报哪个码**。官方未定义优先级。
3. **`514xxx` 是否会在 `flighttask_execute` 阶段出现，还是只在 `flighttask_prepare`**。官方错误码表是全局的，没有标注适用方法。从语义推断：`514103`（电量低）、`514134`（雨量）、`514135`（风速）应主要在 `execute` 或 `prepare` 时返回；`514148`（已在作业中）可能出现在任一入口。
4. **真机是否会"先发 HMS 再回 result"还是并行**。两者在 MQTT 上是独立主题，顺序无保证。

---

## 七、模拟器接入异常检查后的平台能力

完成上述改造后，平台侧可以验证以下场景：

1. 风速调到 15 m/s → 下发航线任务 → 收到 `result=514135` + HMS `0x19114000` → 平台展示"风速过大，稍后重试"。
2. 雨量调到 3 → 下发一键起飞 → 收到 `result=514134` + HMS `0x19114800`。
3. 环境温度调到 -25℃ → 下发任务 → 收到 `result=514137` + HMS `0x19113D27`。
4. 任务飞行中把风速调到 20 → 收到 `flighttask_progress(status=failed, break_reason=784)` + `return_home_info` + `mode_code=9`。
5. 任务飞行中把电量调到 5% → 收到 `break_reason=773`。
6. 电量 25% → 下发任务 → 收到 `result=514103`（准备期拒飞），而非飞到一半返航。

这六条正好覆盖"拒飞"与"中断"两条路径，是平台异常条件检查能力的最小验证集。
