# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增
- **遥控器上云控制台可切换飞行器「激活/休眠」**：机库管理界面打开遥控器实例控制台后，右侧「状态模拟」卡片新增飞行器激活开关（此前只有机库模式有）。Pilot 模式此前在 `/api/state` 被硬性拒绝 `droneActivated=false`，前端也没有对应控件，单兵实例的飞行器只能一直激活；现在两种接入模式语义一致：
  - **休眠**：`droneActivated` 置 false 后停止推送 drone OSD，并向平台发送 `sub_devices` 为空的 `update_topo`（网关仍是遥控器 SN），平台侧飞行器子设备下线
  - **激活**：状态置 true 后重发带飞行器的 `update_topo` 把子设备挂回拓扑，同时补推 drone state 初始属性（此前单兵唤醒不重发拓扑，平台会一直认为飞行器离线）
  - **上线/下线**：遥控器上线时先置激活再发 `update_topo`，首次上线仍带飞行器；`update_topo` 的 `sub_devices` 改为跟随激活状态（`PilotOnlineService.buildUpdateTopoData`），监控器重连重发拓扑时口径一致
  - **live_capacity 联动**：单兵休眠时 `live_capacity` 的 `device_list` 为空、可用路数为 0，与机库模式同口径，避免平台按能力表发起必然失败的 `live_start_push`
  - 新增 `PilotDroneActivationTest`（休眠发空拓扑、唤醒重发拓扑并补推 state、未上线只改本地状态、机库链路未受影响）与 `LiveStreamSimulatorTest` 单兵休眠 live_capacity 断言
- **「机库管理 → 控制台」改为同源代理，从任意入口（9090 / 9513 / 反代域名）都能打开其他机库**：此前该按钮把清单登记的地址直接交给浏览器打开，而清单里存的是**服务端视角**地址——容器部署时是 `容器名:9090`，浏览器解析不了 Docker 内部主机名；其他实例的宿主机端口（9091/9100…）对外通常也没有映射，点击后必然打不开。现改为打开当前入口下的子路径 `http://<当前入口>/fleet/<实例 id>/`，由后端反向代理到目标实例：
  - **代理路径**：`/fleet/{id}/` 覆盖页面、REST 接口、静态资源与上传下载，一次注册全部透传（`FleetConsoleProxyController`）。本机实例（勾选「当前实例」）走内部回环 `127.0.0.1:server.port`，与既有服务端调用一致
  - **路径改写**：目标页面的 `/api/...`、`/vendor/...`、`/panels/...`、`/monitor.html`、`/favicon.svg` 与 `href="/"` 全部前插实例前缀（`FleetConsoleRewriter`），否则页面里的接口请求会打到当前实例而不是目标实例。只改紧贴引号的根路径字面量，`topic.endsWith('/' + f)` 这类普通斜杠不受影响；`//cdn...` 协议相对地址不动
  - **存储隔离**：代理后所有实例共享一个 origin，`localStorage` 也会共享。注入到 `<head>` 最前面的脚本按实例 id 加前缀（`fleet:<id>:`），注册连接配置、已注册标记、高德 Key 不会跨实例串用
  - **上传与流式响应**：新增 `MultipartConfig`，对 `/fleet/**` 跳过 multipart 解析（否则 Spring 会先把 form-data 读成 part，代理转发时 body 为空，报 `Required part 'file' is not present`）。代理以同步流式方式转发，SSE（一键安装 FFmpeg 进度）与 512MB 视频上传均不被额外的异步超时截断
  - **不嵌套**：从代理页面里再打开第三个机库，链接仍是 `/fleet/{第三个id}/`（落在入口根），不会叠加成 `/fleet/A/fleet/B/`；该前缀刻意不参与改写
  - 新增 `FleetService.findInstance(id)`（代理解析目标实例）与 `FleetConsoleProxyController.consolePath(id)`；前端 `openConsole`/`openRegistry` 改用相对路径，表格里的地址列显示代理路径、`title` 悬停仍可看到清单内部地址
  - TC-FLEET-801~808（HTML 属性改写、`href="/"`、内联脚本字面量、普通斜杠不误改、协议相对地址不动、JavaScript 接口路径、存储隔离注入位置、`/fleet/` 链接不重复前插）
- **机库管理支持「遥控器上云」类型，可与机库并存并保持在线**：此前「机库管理」只认机场上云语义（`dockSn`/`dockType`），新增的实例只能是机库，无法在同一份清单里登记遥控器（单兵）实例。现在清单、表单、状态聚合、身份下发、平台预置与容器创建全链路都支持两种接入模式，可在一个控制台里同时管理 N 台机库 + M 台遥控器（M350 RTK + RC Plus 实测端到端上线并保持在线）：
  - **清单与表单**：`FleetInstance` 新增 `deviceMode`（DOCK / PILOT）、`controllerType`、`controllerSn`。表单新增「接入模式」切换：选「遥控器上云」时显示遥控器机型（来自 SDK `RcModel`）与遥控器 SN，飞行器下拉按 `DeviceCompatibility` 搭配关系联动（如 RC Plus → M350 RTK / M300 RTK / M30 / M30T），不再显示无关的机场机型字段
  - **身份下发**：「对齐设备身份」按模式下发——PILOT 下发 `device_mode` + `controller_sn/controller_type` + 飞行器身份；DOCK 下发 `device_mode` + `dock_sn/dock_type`。二者互不污染，切换模式时不会把旧模式的 SN 写进清单
  - **模式切换安全**：清单登记 PILOT 但实例仍在线为 DOCK 时，bringup 会先「下线旧拓扑」再下发身份，避免平台侧同时存在两套拓扑；身份对齐后刷新一次状态，避免 `apply_identity` 顺带完成的连接被后续步骤重复执行
  - **状态聚合**：`FleetInstanceStatus` 新增 `controllerSn` / `controllerType` / `gatewaySn`，表格按模式展示「网关 SN / 机型」（DOCK=机场、PILOT=遥控器），概览区分「机库 / 遥控器」数量。遥控器实例标记为「不可派遣」并说明原因（单兵无机库任务能力，平台无法派发 `flighttask_*`）
  - **平台预置**：PILOT 实例导出的网关记录为 `kind='REMOTE_CONTROLLER'`、`sn=<遥控器 SN>`、`model=<遥控器机型>`，飞行器通过 `gateway_sn=<遥控器 SN>` 挂载；不再写只有机场才有的 `linked_drone_id`
  - **容器创建**：provisioner 新增 `deviceMode` / `controllerType` / `controllerSn` 入参，容器**首启即为遥控器身份**（注入 `SIMULATOR_DEVICE_MODE` / `SIMULATOR_CONTROLLER_TYPE`），不再依赖「创建后再 connect 一次」——否则实例在 connect 之前被重启会退化为机场
  - **接入模式持久化（关键修复）**：`deviceMode` / `controllerType` / `selectedPayload` 此前不落盘，容器重启后 PILOT 实例会静默退回 DOCK，遥控器不再是网关、平台侧该设备永久离线。现随 `live-config.json` 持久化并在启动时恢复，实测重启后 `device-info` 仍为 `PILOT` 且 `controllerSn` 不变，一键启用可直接恢复上线
  - 新增 `simulator.device.mode` / `simulator.device.controller-type` 配置项（支持 `SIMULATOR_DEVICE_MODE` / `SIMULATOR_CONTROLLER_TYPE` 环境变量），仅作为「没有 live-config.json 时的初值」，控制台改过的身份优先
  - `GET /api/fleet/models` 新增 `controllerTypes`（含每个遥控器可搭配的飞行器）与 `deviceModes`；`GET /api/fleet/node-summary` 新增 `controllerSn` / `controllerType` / `gatewaySn`
  - TC-FLEET-701~712（PILOT 实例登记保留身份、非法模式拒绝、缺省归一化 DOCK、遥控器不可派遣、机型选项含遥控器与搭配、平台预置用遥控器 SN 与 `REMOTE_CONTROLLER`、缺遥控器 SN 跳过、compose 双模式并存、清单 JSON 往返不被派生属性破坏、更新切换模式、不可达占位保留遥控器身份、网关 SN 回落）
- **页面一键新建模拟器实例（实例供给服务 provisioner）**：新增独立边车 `provisioner/`，「机库管理 → 新建机库」选「新建并启动」后，一次点击完成 **登记 → 创建容器 → 对齐设备身份 → 开机 → 连接 MQTT → 注册上线**，实测完整闭环约 7.6 秒，不再需要手工 `docker run`。设计要点：
  - **权限隔离**：模拟器主容器不挂 Docker socket；容器创建能力放在独立边车，主容器只通过 HTTP 调用。边车只暴露 `/health`、`/create`、`/remove`、`/inspect`、`/ports` 五个接口，不代理任意 Docker API
  - **白名单约束**：镜像白名单（默认仅模拟器镜像，且要求宿主机已存在、不触发拉取）、端口限制在 9100~9199、容器只能加入指定 bridge 网络、不挂宿主机目录、不加 privileged、不用 host 网络；容器名与 config 卷名由服务端从实例名派生
  - **删除保护**：只允许删除带 `hivemind.simulator.managed=true` 标签的容器（即本服务创建的），手工起的容器拒绝删除
  - **自动回填地址**：创建成功后把清单里的地址回填为 `容器名:9090`（同网络内可达），无需用户知道宿主机映射端口
  - **未部署时自动降级**：无供给服务时「新建」只登记清单并提示原因，其余功能照常；主容器通过 `SIMULATOR_PROVISIONER_URL` 配置
  - 新增接口 `POST /api/fleet/instances/provision`、`GET /api/fleet/provisioner`；`DELETE /api/fleet/instances/{id}` 新增 `removeContainer`/`removeVolume` 参数
- **注册流程顺序修复（影响新建实例能否上线）**：
  - **设备身份对齐提前到开机前**：SN 一旦上线就不能再改（实例拒绝、平台拓扑已按旧 SN 建立）。此前新建实例沿用机型 defaultSn，与清单登记的固定 SN 不一致，上线时平台按「未知网关」拒绝（210231）。现将「对齐设备身份」作为 bringup 第一步执行；设备已在线且 SN 不一致时明确要求先下线
  - **注册参数支持补发**：`organization_id`/`device_binding_code` 只能通过 `/api/connect` 写入实例。此前实例「已连接」时整步跳过，这些值没有更新机会，注册必然因绑定码不匹配失败（210229）。现改为只要请求带了注册参数，即使已连接也补发一次 connect
  - 坐标下发步骤与身份对齐步骤拆分（`set_location`），避免共用 `apply_identity` 造成语义混淆
- TC-FLEET-601~606（未部署供给服务时降级、autoProvision=false、参数非法时校验先失败且不落库、provisionerStatus、删除机库、删除不存在的机库）
- TC-MQTT-030~036（下行订阅刷新：Dock 模式订阅机场 SN、Pilot 模式订阅遥控器 SN、切换模式后遥控器 SN 进入订阅集、修改遥控器 SN 后订阅集跟随变化、未连接时 resubscribe 安全返回 0、订阅集去重、两种网关 SN 同时订阅）
- **多机库管理（机库群控制台）**：控制台 header 新增「机库管理」，把多个模拟器实例当作一组机库统一编排，用于在平台侧验证「按距离智能选库生成航线」。每个机库仍是一个独立进程（独立 config 卷、独立 MQTT、独立 SN、独立坐标），本功能只加控制面，不改设备状态模型，也不接管 MQTT 通信。能力包括：
  - **清单管理**：登记/编辑/移除机库（名称、控制台地址、机型、SN、经纬度、海拔），持久化到 `SIMULATOR_CONFIG_DIR/fleet.json`（与 `live-config.json` 同目录，跟随 config 卷，重启不丢）
  - **状态聚合**：并发拉取各实例的 `/api/fleet/node-summary`（一次请求取全量），聚合为「可达 / 开机 / MQTT / 在线 / 实际 SN / 机型 / 坐标 / 电量 / 在舱 / 任务占用 / 可派遣」一张表；单台不可达降级为灰色可诊断状态，不阻塞其他实例。旧版本实例无该端点时自动回退到 `/api/device-info` + `/api/connection` + `/api/location`，缺失字段置 null 而非误判
  - **一键启用（顺序工作流）**：`POST /api/fleet/bringup` 按「开机 → 连接 MQTT → 上线 → 下发坐标」顺序执行，各步幂等（已满足即跳过），前一步失败中止该台后续步骤但继续处理其他机库，返回逐台逐步明细
  - **选库排序**：`GET /api/fleet/candidates` 按到目标点的 haversine 直线距离排序候选机库，并标注可派遣性（在线 / 在舱 / 电量 ≥30% / 无执行中任务 / 已上报坐标）。只提供模拟器可确证的数据，完整选库权重策略仍属平台业务
  - **清单导入导出**：`GET /api/fleet/instances/export`、`POST /api/fleet/instances/import?replace=`，支持整表替换与按 id 合并；非法条目单独拒绝并列出原因，其余照常导入
  - **平台预置导出**：`GET /api/fleet/platform-seed` 与 `/platform-seed.sql` 按清单生成平台侧 `dock` / `device` 记录（JSON 对齐平台 `POST /api/docks`、`POST /api/devices`）与可重复执行的 SQL（`WHERE NOT EXISTS` + 自动补 `linked_drone_id`），解决「平台只允许已知网关绑定」导致的上不了线问题
  - **批量操作**：批量开机 / 连接 / 上线 / 下线，以及「下发身份/坐标」把清单登记的 SN、机型、坐标写回各实例；部分失败逐条列出，不影响其他实例
  - **编排生成**：按清单生成 `docker-compose-fleet.yml`（每实例独立 config 卷、共享 media/videos、注入 `SIMULATOR_LOCATION_*`、开启唯一 SN 模式），支持复制与下载；本机实例与停用实例不写入编排（避免端口冲突）
  - **容器内自访问修正**：新增 `selfInstance` 标记。容器部署时对外端口是宿主机映射端口（如容器内 9090 映射到宿主机 9513），在容器内不可达；标记后服务端动作改走内部回环 `127.0.0.1:server.port`，浏览器侧仍用对外地址
  - 新增接口：`GET/POST /api/fleet/instances`、`PUT/DELETE /api/fleet/instances/{id}`、`GET /api/fleet/statuses`、`GET /api/fleet/models`、`POST /api/fleet/instances/{id}/actions`、`POST /api/fleet/batch`、`GET /api/fleet/compose`
  - 机型选项与型号名由 SDK `DockModel`/`DroneModel` 枚举推导（`shortName()` 与控制台既有标签口径一致），前端不维护静态机型表
- **`docker-compose-multi.yml` 修正**：镜像名改为跟随 compose 项目名的 `hivemind-simulator-simulator:latest`（原 `dock-simulator-simulator` 与实际构建产物不符）；Broker 默认改为 `host.docker.internal` 并支持 `${SIMULATOR_MQTT_USERNAME/PASSWORD}` 注入（原硬编码 `uas-emqx` + 平台内部凭证）；移除不存在的 `uas-network` external 网络引用（原样 `compose up` 会直接失败，现改为注释说明按需启用）；为三实例分别注入相距数公里的 `SIMULATOR_LOCATION_*`，使距离选库验证有区分度
- 新增 [docs/FLEET-MULTI-DOCK.md](docs/FLEET-MULTI-DOCK.md)：多机库搭建、平台侧必须预置的 `dock`/`device` 记录、SN 对齐的两种做法、坐标两处来源（静态 vs `displayLat/displayLng`）、智能航线引擎的两种对接路线与打分建议、排查表
- TC-FLEET-001~008（清单持久化：缺失/损坏/空清单降级、版本归一化、父目录自建、旧字段兼容、baseUrl 归一化）、TC-FLEET-101~122（清单增删改查与校验、本机识别与容器映射端口、不可达降级、SDK 机型选项、compose 生成含本机/停用排除与服务名派生）、TC-FLEET-201~210（haversine 距离与已知值对齐、候选排序、limit、电量/任务/在舱/未上线对可派遣的影响、未知字段不排除）、TC-FLEET-301~306（清单导出导入往返、合并模式、同 id 覆盖、非法条目单条拒绝、导出不含凭据）、TC-FLEET-401~404（启用工作流：不可达即失败、已满足步骤跳过、停用实例不参与、ids 范围限定）、TC-FLEET-501~505（平台预置：dock/device 记录与 SQL 生成、缺 SN 跳过并说明、单引号转义、默认组织、停用排除）
- **两种模式均可自定义 SN，并自动填充当前生效值**：机场上云可改机场 SN + 飞行器 SN，遥控器上云可改遥控器 SN + 飞行器 SN，两套身份相互独立、切换模式不互相污染。打开「注册到第三方平台」弹窗时按当前生效 SN 自动填充输入框，型号切换后自动刷新为新型号的默认值。遥控器 SN 覆盖优先级为「手动覆盖 > 型号 defaultSn」，持久化到 `live-config.json`，重启后恢复；在线时禁止修改，非法格式（含 `/`、`+`、`#` 等 topic 保留字符）明确拒绝。接口为 `POST /api/connect` 的 `controller_sn` 字段，`GET /api/device-info` 新增 `controllerSnOverride`
- **单兵无人机能力清单（前端面板 + 只读端点）**：遥控器上云面板新增「单兵无人机」卡片，列出当前遥控器可搭配的飞机及其行为属性（型号标识 `domain-type-sub_type`、默认负载与相机索引、是否热成像、OSD/State 字段集、可用能力），并高亮当前选择的机型、附上单兵模式边界说明。数据来自新增的 `GET /api/pilot/capabilities`，由 SDK `DeviceCompatibility` 搭配表与仓库内 `DroneOsdBuilder`/`DroneStateBuilder` 支持矩阵推导，前端不维护静态机型表，避免 SDK 升级或新增机型后与实际设备行为漂移
- **遥控器上云（单兵）语义补齐**：单兵的网关是遥控器（domain=2），飞机是子设备（domain=0），与机场上云（domain=3）共用同一套 MQTT 通道。此前 `PILOT` 模式虽可用，但多处仍按"机库"语义工作，本次对齐：
  - 单兵拒绝机库专属的 `flighttask_prepare` / `flighttask_execute` / `flighttask_pause` / `flighttask_recovery` / `flighttask_undo` / `flighttask_stop` / `flight_setup_abort`，返回 `314000`（当前状态不支持）并记录 P-8 诊断，不再静默回 `result=0`。Pilot 侧只有航线的 HTTP 列表/上传/下载，航线由飞手在遥控器手动执行
  - 上行事件（HMS / AirSense / 航线进度 / 媒体回调 / 远程日志 / 飞行区等）统一发往**当前网关 SN**（单兵为遥控器 SN），不再固定发往机场 SN
  - 上行 `requests` 与 `state` 的 `gateway` 字段、`requests_reply` 监听同步改为网关 SN
  - `POST /api/live/state/publish` 在单兵模式下推送遥控器 state，不再推机场 state
  - 飞行器激活/休眠在单兵模式下走 `PilotOnlineService` 的遥控器拓扑，不再发送机场 `update_topo`

### 修复
- **风速上报被放大 10 倍（`wind_speed: 30` 实为 3 m/s）**：机场与飞行器 OSD 的 `wind_speed` 在构建时都乘了 10（`getWindSpeed() * 10`、`getDroneWindSpeed() * 10`），依据是「DJI 文档单位 0.1 m/s」——该依据不成立。DJI Dock1/Dock2 properties 文档字段表原文为 `wind_speed | 风速 | float | {unit_name: "米每秒 / m/s"}`，飞行器 properties 文档同样标注 float 且无换算说明；平台侧（uav-platform）按 m/s 直接消费并与 `maximumWindMps` 阈值比较，于是 3 m/s 被读成 30 m/s，正常天气也会命中「风雨条件尚未满足」的拒飞判定。现统一为 **m/s 原值上报、不做任何倍数换算**：`AbstractDockOsdBuilder` 与 `AbstractDroneOsdBuilder` 去掉 ×10，`PropertySetHandler` 收到 `wind_speed` 时去掉 /10。新增 `WindSpeedUnitTest` 锁定该约定（含拒飞阈值 9 m/s 边界）。注意此修复会改变线上报文数值：原 `wind_speed: 30` 现为 `3.0`，平台若已按错误值做过阈值放宽需一并回调
- **机场环境参数改了不生效（前端整包回写与轮询互相覆盖）**：控制台的「机场状态模拟」把整个 `editState` 一次性 `PUT /api/state`，同时页面每 2 秒轮询 `/api/state` 并用返回值覆盖同一个 `editState`。两个方向共用一个缓冲区，一旦轮询响应落在「编辑完成」与「提交完成」之间，或连续修改多个字段，后到的整包快照就会把刚改的值连同其他字段一起写回旧值（联调实测：把风速从 3.0 调到 3.5，OSD 只上报了一帧 `wind_speed: 35`，2 秒后即回到 30）。现改为**只提交本次变化的字段**：控件 `@change` 明确传入字段名（`saveState('windSpeed')`），后端本就支持部分字段更新；同时 `refreshState` 不再覆盖「已本地修改但尚未被服务端确认」的字段，并用写入版本号丢弃「写之前发出、写之后才返回」的过期轮询响应。服务端返回的权威值会立即回填，界面与后端保持一致。新增前端行为测试覆盖单字段提交、在途旧轮询不打回、未提交编辑被保留、连续改多字段、外部改动能带出等 5 类场景
- **平台通过 `property/set` 改机场环境参数是「假成功」**：`PropertySetHandler` 只处理 `silent_mode` / `air_transfer_enable` / `user_experience_improvement` 三个属性，其余字段一律回 `{"result": 0}` 却不更新 `DeviceState`——平台显示设置成功，机场 OSD 毫无变化，联调时无法判断是平台没发还是模拟器吞了。现补上环境参数的写入：`temperature` / `humidity` / `rainfall` / `environment_temperature` / `cover_state` / `emergency_stop_state` / `wind_speed` 直接生效（均为 DJI 文档原生单位，不做换算）。这些属性是 pushMode=0（由 OSD 带出），不走 state 增量上报
- **机场环境参数重启后丢失**：`DeviceState` 是纯内存对象，`windSpeed` / `dockTemperature` / `environmentTemperature` / `rainfall` 等不落盘，容器重启即回到默认值（3.0 m/s / 25℃ / 50%），而拒飞判定（风速≥9 m/s → 514135、环境温度<-20℃ → 514137）依赖它们，重启后无法复现同一场景。现新增 `DockEnvStateStore`，把可模拟的环境参数持久化到 `live-config.json` 同目录的 `dock-env-state.json`，与控制台改值（`/api/state`）和平台下发（`property/set`）两条路径联动写入，启动时自动恢复。旧文件缺少新增字段时保留内存当前值，损坏文件降级为默认值不阻断启动
- **接入模式/网关 SN 在已连接状态下变更后，下行订阅不刷新**：MQTT 下行 topic 的订阅只在 `connectComplete` 回调里执行一次。运行期切换「机场上云 ↔ 遥控器上云」或修改 SN 时，MQTT 连接不会断开，回调不触发，订阅集仍是按旧 SN 算的——平台下发到新网关 SN 的 `services` / `property/set` / `events_reply` / `requests_reply` / `status_reply` / `drc/down` 全部无人接收（联调实测：先以 DOCK 模式连接、再切到 `PILOT` + 遥控器 SN `1581F5RCD001002` 后，订阅仍是旧机场 SN，`update_topo` 能成功但平台指令收不到）。现新增 `MqttClientManager.resubscribe()`：按当前网关身份幂等重订阅（保留机场 SN 订阅，切回时无需再次刷新），`/api/connect` 在检测到网关 SN 或接入模式变化时自动调用，并在响应中返回 `gateway_changed` / `resubscribed`。修复后同一场景无需手动重连，平台指令即时送达并回 `services_reply`（`drc_mode_enter` 返回 0）
  - 订阅计算抽取为 `computeDownTopics()`、单条订阅收敛到 `subscribeTopic()`，便于单测断言"切换后订阅集确实包含新网关 SN"而无需真实 broker
- **航点拍照上传的是纯蓝色占位图**：内置示例照片原为一张 1280×720 全蓝图片，`media-dir` 未配置或只有预置样例时，每次拍照上传的都是它。现改为 3 张真实风景示例照片（1280×720，单张 90~170KB）并按拍照序号轮换；`media-dir` 里你放入/上传的图片优先生效，不再按文件名和样例混排（样例名 `sample-*` 排序靠前会导致取不到用户图）；视频不再被当成照片上传。旧部署启动时会识别并替换那张全蓝图（按 SHA-256 精确匹配，你自己放的同名文件不动），同时补齐新增的两张样例图。「上传媒体」按钮支持一次多选
- **遥控器上云拓扑上报的 Topic 与回复监听不匹配**：`PilotTopicSchema` 此前按型号分叉——RC Plus / RC Pro 的 `update_topo` 发到 `sys/product/{gateway_sn}/status`，RC Plus 2 则在 `thing/product/{gateway_sn}/status_reply` 上等回复。DJI Pilot 上云设备文档对 RC 与 RC Pro 型号的规定一致：上行 `thing/product/{gateway_sn}/status`，回复 `sys/product/{gateway_sn}/status_reply`。现所有遥控器型号统一按此实现，机型差异只体现在报文字段（domain/index）。此前 RC Plus 把拓扑发到 `sys` 通道，平台侧若按 `thing` 订阅则收不到；RC Plus 2 在错误通道等回复，10 秒超时后按"不下线"继续，平台返回的非零 `result` 被静默吞掉
- **单兵模式网关 SN 未使用遥控器覆盖值**：`getGatewaySn()` 直接读取 `controllerSn` 字段而非生效的遥控器 SN，导致覆盖值不生效，MQTT topic 仍带型号默认 SN
- **切换接入模式后单兵收不到平台指令**：下行监听器在应用启动时按当时的网关 SN（默认 DOCK 的机场 SN）注册，运行期切到遥控器上云后，平台下发到遥控器 SN 的 `services`/`property/set`/`drc/down` 等消息无人处理。现监听器额外按网关无关形式（`thing/product/+/...`、`sys/product/+/...`）登记别名，并加设备归属校验，避免误响应同一 broker 上其他设备的指令
- **遗嘱报文网关类型写死为机场**：MQTT 遗嘱此前固定 `type=3`（机场），遥控器异常掉线时平台会收到"机场下线"。现按当前网关型号构造（遥控器 domain=2）
- **`file_upload_callback` 字段语义偏差**：`ext.payload_model_key` 此前误填飞行器型号，改为负载（相机）枚举值；`metadata` 的位置/高度/云台偏航/拍摄时间改为拍摄瞬间值（此前恒为机场配置值 + 虚构偏移）；`create_time` 按官方格式输出 `yyyy-MM-dd HH:mm:ss`；`flighttask_progress.ext.media_count` 改为真实已拍媒体数（此前填任务步骤索引）
- **飞行器 OSD 高度字段对调**：`thing/product/{drone_sn}/osd` 与 DRC `osd_info_push` 的 `height`/`elevation` 此前按内部变量名直出，与 DJI 文档相反。现 `height` 上报绝对高度（椭球高，double），`elevation` 上报相对起飞点高度（float）

### 新增
- **航点拍照与媒体回传（对齐真机链路）**：`WaylineRouteLoader` 解析 WPML `actionGroup`（仅 `reachPoint` 触发）与 `actionActuatorFunc=takePhoto`，`WaylineFlightEngine` 在到达对应航点时按拍摄瞬间遥测（经纬度/椭球高/相对高/云台偏航/`fileSuffix`）产出媒体快照，任务完成或空中回传时按大疆时序上传 MinIO 并逐条上报 `file_upload_callback`。`air_transfer_enable` 开启（默认）时拍照即上传（Dock3 最新固件支持航线任务空中回传），关闭时落地后批量上传；上传失败的文件不上报回调，避免平台按不存在的 `object_key` 取图
- **pushMode=1 状态变化上报**：`thing/product/{gateway_sn}/state` 不再只在上线时推一次全量。`property/set` 改到 `silent_mode` / `air_transfer_enable` / `user_experience_improvement`、控制台改静音模式、直播开停推流、镜头切换、舱内/舱外切换、飞行器激活/休眠时，只推变化字段（不定频）。值未变化不发。设备未上线时跳过，下次上线全量仍带当前值。`live_capacity` 改为按当前推流构造：Dock 含机场相机 `165-0-7`（舱位）+ 飞行器主相机（镜头类型），飞行器休眠时从 `device_list` 移除

## [v1.4.9] - 2026-09-04

### 新增
- **DRC 专用 MQTT 连接（对齐真机行为）**：进入 DRC 模式后按平台下发 `drc_mode_enter` 的 `mqtt_broker` 凭证建立独立低延迟连接（EMQX 可见 `drc-{sn}` client_id），承载 `drc/down` 订阅与 `drc/up` 发布；`drc_mode_exit` 断开专用连接；重复 `drc_mode_enter` 复用已有连接（防重复订阅）；`mqtt_broker` 缺失或连接失败回退主连接（DRC 消息走主连接），不影响 DRC 指令可用性；关机联动断开。主连接继续承载常规消息（OSD/state/services），DRC 消息归属随专用连接切换。新增 `DrcConnectionManager` 管理连接生命周期，TC-DRC 系列测试覆盖（DrcConnectionManagerTest / DrcDockVersionIsolationTest / DrcStickControlTest 等）

### 修复
- **负载属性 key 占位符误用**（差异文档 I22）：OSD/state 负载属性 struct 的 key 此前误用文档 Column 名字面量 `type_subtype_gimbalindex`——该名称实为 DJI 文档 `{type-subtype-gimbalindex}` 花括号占位符，实际 key 为**负载索引枚举值**（M30→`52-0-0`、M3D→`80-0-0`、M4TD→`99-0-0`、M400→`82-0-0`），与 struct 内 `payload_index` 字段数值一致。平台若按字面量解析 Dock3/M4D 负载属性将取不到值。修复：`DeviceSimulator`/`DockOnlineService`/`AbstractDroneOsdBuilder`/`M400DroneOsdBuilder`/`M4DDroneOsdBuilder` 统一改为动态负载索引枚举值，并加反向断言禁止字面量 key（TC-PAYLOAD-027）
- M3D/M4TD 负载属性 key 依据 [M3D/M3TD properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m3d-properties.html)、[M4D/M4TD properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html)（2026-01-28 版）与 [M30 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html) 语义核实一致；Dock2 Example 实证 `52-0-0`，M3D/M4TD/M400 真机 key 待抓包确认

### 依赖
- **dji-cloud-api-sdk 1.16.1.0-SNAPSHOT → 1.16.1.3**（正式版）：含 SDK 侧同步修复——`OsdField`/`StateField` 删除 `type_subtype_gimbalindex` 占位符枚举（javadoc 说明动态 key）、`DrcMqttBroker` 补 password 字段 + 放松过度校验、`DrcModeEnterRequest` 必填校验放宽（缺省字段合法报文不再反序列化失败）

### 文档
- **新增 [SIMULATOR-VS-REAL-DEVICE.md](docs/SIMULATOR-VS-REAL-DEVICE.md) 模拟器与真机差异清单**：收录 22 项「模拟器推断/简化」行为（含 I22 负载属性 key），每项含模拟器实现、文档依据、验证状态，供平台开发者排查「模拟器与真机不一致」时自查

## [v1.4.8] - 2026-09-04

### 修复
- **DRC 杆量控制归一化值域错误**：`DrcCommandHandler.clampStick` 假设 stick_control 杆量值域 `[-65536, 65536]`（`value/65536` 线性归一化，v1.4.4 引入），与 DJI 文档实际值域 `[1024±660]`（中值 1024=悬停，满杆 1684/364）不符——满杆 1684 归一化仅 0.0257，杆量积分几乎失效（飞行器近乎不动）。修复：改为 `(value - 1024) / 660`，满杆 1684→1.0、中值 1024→0.0、364→-1.0，超界值截断到 [-1,1]。依据 [Pilot RC Plus 2 远程控制](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/pilot-to-cloud/mqtt/dji-rc-plus-2/remote-control.html) 四通道 constraint `{"max":"1684","min":"364"}` + [DJI SDK 虚拟摇杆速度公式](https://sdk-forum.dji.net/hc/zh-cn/articles/10546782854553) 最大偏移量 660。杆量符号方向（pitch 正=前飞/下俯、roll 正=右倾、throttle 正=上升、yaw 正=顺时针）已与文档一致，未改动；Dock 端 stick_control 字段表未在 Dock2/Dock3 文档独立列出，值域依据 Pilot 端约定 + SDK 推定，M-2 诊断日志已记录待真机验证
- TC-DRC-066（杆量归一化逻辑：1024→0、1684→1、364→-1、超界截断）；TC-DRC-061~065 满杆值 65536→1684、无动作通道 0→1024（中值）

## [v1.4.7] - 2026-09-04

### 修复
- **flight_safety_advance_check 类型错误导致一键起飞执行失败**：DJI Cloud API 文档定义 `flight_safety_advance_check` 为 **bool 类型**（平台下发 `true`/`false`），但 SDK POJO `TakeoffToPointRequest` 声明为 `Integer`。Jackson 无法将 JSON boolean `true` 反序列化为 `Integer`，抛出 `MismatchedInputException: Cannot deserialize value of type java.lang.Integer from Boolean value`，异常被 `handleService` catch 捕获返回 `result=1`（执行失败）。真机下发 `true` 可正常执行，模拟器却返回失败。修复：POJO 字段改为 `Boolean`，`DeviceState` 字段改为 `boolean`，`FlightCommandSimulator` 适配 `Boolean.TRUE.equals()` 逻辑
- TC-FLY-036（flight_safety_advance_check 为 bool 类型，非 int）

## [v1.4.6] - 2026-08-31

### 修复
- **flighttask_progress 的 bid 为随机 UUID，平台无法关联任务**：云端下发 `flighttask_prepare`/`flighttask_execute` 后，模拟器上报的任务进度事件 bid 恒为随机 UUID，平台无法通过 bid 关联到下发任务的 jobId。现 `ServiceCommandHandler` 路由航线指令时传递 envelope 的 bid，`WaylineTaskSimulator` 保存 `currentTaskBid`，进度事件回带与下发指令一致的 bid（jobId）；tid 保持消息级随机 UUID（tid=消息 id，bid=业务 id）；bid 缺失时 fallback 随机 UUID（与修复前行为一致）；任务结束/取消后清空。与指令飞行 `fly_to_point_progress` 的 bid 回带机制同源
- **Pilot live_capacity 的 camera_list 元素错误**：`camera_list` 数组元素错误放入 `PayloadType` 枚举对象而非相机信息 map（camera_index/available_video_number/coexist_video_number_max/video_list），序列化结构与 DJI 文档不符。改为 `List.of(cameraInfo)`
- TC-WAYLINE-027（进度 bid 关联 jobId）

## [v1.4.5] - 2026-08-31

### 修复
- **live_capacity 相机索引错误**：Dock/Pilot 两种模式上报的 `live_capacity` 中 `camera_index` 硬编码为机场相机枚举 `165-0-7`，与飞行器实际推流相机不符，平台按此拼 video_id（`{sn}/165-0-7/normal-0`）与模拟器其他位置（drone OSD payloads、drone_authority_info）的相机枚举不一致。改为由 `DefaultCameraResolver` 按飞行器机型动态解析（M30→52-0-0、M3D→80-0-0、M4D→98-0-0、M400→82-0-0/H30），与 drone OSD payloads 的 `payload_index` 完全同源，机型切换后下次 state 上报自动携带新值
- **进入调试模式后机场状态无变化**：`debug_mode_open`/`debug_mode_close` 仅设置内部 `debugMode` 标志，未联动 `dockModeCode`，机场 OSD 的 `mode_code` 恒为 0（空闲中），平台无法感知调试模式状态。现 open 联动 `mode_code=2`（远程调试）、close 恢复 `0`（空闲中），对齐 DJI 机场 mode_code 枚举（0=空闲中、1=现场调试、2=远程调试、3=固件升级中、4=作业中、5=待标定）；监控器判断调试模式改查 `mode_code === 2`（原检查协议中不存在的 `debug_mode` 字段，恒为 false 导致重复下发 `debug_mode_open`）
- **负载编号硬编码 81-0-0**：DRC 相机遥测（`drc_camera_state_push`/`drc_camera_osd_info_push`）与 `delay_info_push` 的 `payload_index`/video_id 硬编码为 M3TD 相机 `81-0-0`，与实际机型不符。改为动态解析（指令优先 + 机型默认），与 live_capacity、drone OSD payloads 同源；机型切换时重置指令指定的 payloadIndex
- **DRC 相机遥测与 OSD 负载属性数据源不统一**：M30/M350/M400 DroneOsdBuilder 直接调 `DefaultCameraResolver.defaultCameraFor()` 而非 `ctx.getSelectedPayload()`，导致 OSD cameras 数组、负载属性 key、type_subtype_gimbalindex.payload_index 三处负载编号可能不一致（用户选择负载覆盖时）。统一改为 `ctx.getSelectedPayload()` 同源
- **OSD 云台姿态与变焦硬编码**：`type_subtype_gimbalindex`（M3D/M4D/M400）与 `{type-subtype-gimbalindex}` 负载属性 key 结构（M30/M350）中 `gimbal_pitch/roll/yaw` 硬编码 0.0、`zoom_factor` 硬编码 2.0，DRC 云台控制/变焦后 OSD 不反映实际状态。改为从 `state` 读取，与 cameras 数组同源
- **负载编号 fallback 不统一**：7 处相机索引 fallback 分别硬编码 52-0-0/99-0-0/82-0-0（差异值），且正常路径永远不触发，静默掩盖 DefaultCameraResolver 映射缺口。统一改为 `DefaultCameraResolver.requireDefaultCameraIndex/requireCameraIndex` 抛 `IllegalStateException`（含机型+位置上下文），不回退硬编码，快速暴露问题
- TC-LIVE-026 / TC-RD-013 / TC-DRC-039 / TC-DRC-040 / TC-PAYLOAD-026

## [v1.4.4] - 2026-08-29

### 新增
- **飞行位置连续插值模拟**（历史飞行模拟功能的完善）：航线任务、DRC flyto、一键起飞三类飞行场景的位置（经纬度/高度）从"阶段瞬移"升级为连续匀速推进（0.5s 步进，与 OSD 0.5Hz 上报对齐），平台轨迹地图可绘制连续飞行轨迹
  - **DRC 杆量积分**：`stick_control` 杆量（[-65536, 65536] 线性归一化）驱动经纬度/高度/偏航连续变化（满杆水平 10m/s、垂直 5m/s、偏航 60°/s），attitudePitch/Roll 随杆量映射 ±15°；断流 >0.5s 时间步封顶防瞬移
  - **航线模式插值**：起飞→航线飞行→返航→降落各步骤设定目标点，插值器匀速推进（水平 10m/s、垂直 3m/s），消除 100 米瞬移跳变
  - **flyto/一键起飞插值**：按指令 `max_speed` 匀速飞向目标点，到达自动停止；`fly_to_point_stop` 悬停当前位置；事件时序（wayline_ok/task_finish）保持固定调度与位置解耦，联调时序稳定
- **Swagger 接口文档**：集成 springdoc-openapi，启动后访问 `http://localhost:9090/swagger-ui.html` 人工查阅，`/v3/api-docs`（OpenAPI JSON）供 AI 助手与代码生成器消费
- **README 英文简介**：头部新增英文摘要段，提升搜索引擎与 AI 收录定位
- TC-DRC-061~065（杆量积分）/ TC-WAYLINE-024~026（航线插值）/ TC-FLY-033~036（flyto 插值）/ TC-API-DOC-001~003（Swagger）/ TC-MQTT-015（连接状态）

### 修复
- **调试器"监控: 未连接"与实际状态不符**：网络闪断/Broker 重启后 Paho 自动重连成功，但自定义 `connected` 标志未恢复，导致前端显示未连接而设备型号仍显示、消息正常接收的矛盾状态。删除冗余标志，`isConnected()` 直接委托 Paho 实际状态（唯一真相源），重连后自动恢复"已连接"

### 设计说明
- **drone_control（Dock1 废弃接口）不做位置模拟**：DJI 已废弃（Dock2/3 不支持），P-9 诊断日志引导平台迁移 `stick_control`
- **航线模式不做偏航/云台模拟**：真机由 KMZ 航线动作定义，模拟器不解析 KMZ
- `simulate_mission.is_enable=1` 室内调试模式不启动位置插值（既有语义保留）

## [v1.4.3] - 2026-08-26

### 新增
- **SN 手动修改**：模拟器注册面板新增机场/飞行器 SN 输入框，无需改配置文件即可切换设备身份。SN 生成采用三级优先级：手动覆盖 > 唯一模式生成 SN > 机型 defaultSn，覆盖值持久化到 live-config.json。约束：在线状态下拒绝修改 SN（须先关机）；SN 仅允许 MQTT 安全字符 `[0-9A-Za-z_-]`（长度 1~32）；SN 变更视为换设备，前端自动重置 `localStorage.registered`，新 SN 生效后必须重新注册到第三方平台
- TC-REG-032~037（SN 手动修改）/ TC-LOC-020（机场位置动态上报）

### 修复
- **机场 OSD 位置不随位置模拟更新**：机场 OSD 的 `latitude/longitude/height` 与 Dock3 `self_converge_coordinate` 原从 yml 静态配置读取，前端位置模拟设置新坐标后仍上报成都默认值。改为从 `RuntimeConfig` 动态读取，下一次 `osd_info_push` 即上报新坐标

## [v1.4.2] - 2026-08-25

### 新增
- **DRC 拍照信息推送**：实现 `drc_camera_photo_info_push` 事件（此前整个事件未实现）。平台下发 `drc_camera_photo_take` 后立即推送 `in_progress`（progress 0%），拍照完成（2 秒模拟）推送 `ok`（100%）并归零 photoState；`drc_camera_photo_stop` 可中断流程。对齐 DJI Dock2/Dock3 remote-control「拍照信息推送」文档
- **DRC 状态可视化**：模拟器页面飞行器状态栏与调试器页面新增 DRC 状态标签（已连接=绿色 / 未连接），数据源统一为 `DeviceState.drcState`（`/api/drone/position` 新增 `drc_state` 字段）
- **云台控制闭环**：`drc_gimbal_reset`（按 reset_mode 回中/俯仰向下）与 `drc_camera_screen_drag`（按速度增量）更新 state 的 gimbalPitch/Roll/Yaw，经 `osd_info_push` 与飞行器 OSD 的 `gimbal_pitch/roll/yaw` 上报形成闭环（原硬编码 0.0）
- **调试器指令回复显示**：监控器订阅 `thing/product/+/services_reply`，`sendCommand` 按 tid 缓存回复并等待 3 秒；前端显示设备执行结果（成功=绿色 / failed=红色 / 超时提示），不再以「已发送」掩盖失败
- **调试器远程调试模式自动处理**：下发 cover_open 等远程调试类指令前自动检查 debug_mode，未进入时先发 `debug_mode_open`（对齐真机行为）
- **OSD 协议字段补齐**：M30/M30T 飞行器 OSD 追加 `country`（国家区域码，pushMode=0）；`drc_camera_state_push` 追加 `photo_format`（7=RJPEG）；`drc_camera_osd_info_push` 的 ir_lense 追加 `thermal_supported_palette_styles`（[1,6,11]）

### 修复
- **一键起飞相对高度异常（-519.5m / 仅 1.5m）**：`takeoff_to_point`/`fly_to_point` 的 `target_height` 恢复 DJI 文档椭球高（WGS84）语义——`elevation=target_height`、`height=target_height−机场海拔`；调试器一键起飞参数改为协议合法值（`target_height=机场椭球高+30`、`security_takeoff_height=20`，补 Dock3 必填的 commander_mode_lost_action 等字段），修正后爬升至 20m 安全高度再飞抵目标
- **DRC 模式无法进入**：`drc_mode_enter` 缺失 `mqtt_broker` 时 SDK 反序列化抛异常导致 result=1，改为宽容处理（记录告警仍进入 DRC 模式）；调试器同时补全协议完整 data（mqtt_broker/hsi_frequency/osd_frequency）
- **调试器 OSD 数据被覆盖**：MonitorService OSD 缓存改用 `compute` 合并多 topic 消息的 data 字段，drc_state（Group 2）不再被后续 Group 3 消息覆盖
- **调试器 topic 过滤失效**：过滤选项修正（`drc/down`、`drc/up`、`property/set` 等），匹配逻辑由 `endsWith('/'+f.replace(' ','/'))` 改为 `endsWith('/'+f)`
- **模拟器远程调试模式校验**：cover_open 等 Job 指令执行前检查 debugMode，未开启返回 `status=failed` 并记录 M-2 诊断日志（对齐真机 check_work_mode 行为）

## [v1.4.1] - 2026-08-25

### 修复
- **多实例 SN 冲突**：v1.4.0 Docker 多实例编排中三实例共用同一 defaultSn 导致 MQTT topic 互踩（原 `DOCK3_001` 等环境变量格式无效且代码未读取，已删除）。新增 `simulator.sn.unique-enabled` 配置：启用后每个实例启动时生成 defaultSn 前缀 + 随机后缀的唯一 SN，与设备型号成对持久化，重启后设备身份不变；型号切换时重新生成（幂等）。`docker-compose-multi.yml` 三实例默认启用

### 新增
- **直播推流记录**：直播推流配置面板新增推流记录区域，每次 `live_start_push` 生成一条记录（含平台下发地址、实际推流地址、状态与失败原因），用于核对平台下发的推流地址是否配错。失败记录（513013）保留供排错；WHIP 降级 RTMP 等容错场景标注实际推流地址；`live_stop_push`/设备下线自动更新状态；有界保留最近 20 条

### 变更
- **版本基线对齐**：v1.4.0 发布产物内部版本显示 1.3.2（tag 绕过版本号递增流程所致），自本版本起版本号与 tag 恢复一致

## [v1.3.2] - 2026-08-20

### 修复
- **Docker 部署完善**：非 root 用户运行、三 Named Volumes 持久化（config/media/videos）、EMQX 可选 profile、HEALTHCHECK 健康检查、JVM 强制 IPv4
- **MQTT 连接健壮性**：地址自动剥离 tcp:// 前缀；容器内 localhost 自动映射 host.docker.internal（本地与 Docker 填写方式一致）；初始连接失败分类修正，同机 Docker 部署提示直连容器网络方案
- **媒体文件预置**：新增媒体/视频目录 Web 上传（路径净化+扩展名校验）与样例自动释放，解决 Docker 环境文件预置难题；「上报媒体」与「上传媒体」职责分离
- **开机状态持久化**：电源状态改由后端管理，刷新页面不再回到可开机状态；未注册时点击「开机」直接弹出注册弹窗
- **设备状态展示**：Title 栏拆分网关（机场/遥控器）与飞行器独立状态标签（含型号），飞行器三态（离线/休眠/开机）

## [v1.1.2] - 2026-08-14

### 新增
- **MQTT 消息日志本地持久化**：每条消息以 JSON Lines 格式写入本地文件（按日期滚动），文件位置 `${user.home}/.hivemind-simulator/logs/messages-yyyy-MM-dd.jsonl`，重启后历史消息不丢失
- **日志区上拉懒加载**：滚动到顶部自动从本地文件加载更早的历史消息，历史消息独立于轮询缓冲不会被覆盖
- **日志文件下载**：新增"下载日志"按钮，下载当天完整的原始日志文件（.jsonl 格式）
- `/api/logs` 接口新增 `beforeTime`/`limit` 分页参数，支持历史消息查询
- 新增 `/api/logs/download`（下载日志文件）、`/api/logs/files`（文件列表）接口

### 变更
- "导出OSD"按钮改为"下载日志"按钮，精简功能重叠（原 `/api/logs/export` 接口保留供外部工具调用）
- 监控器同步改造：`exportOsdLogs` → `exportLogs`，移除 OSD topic 过滤，支持所有消息类型
- `MqttClientManager.addLog()` 同时写入内存缓冲和本地文件，新增 `ts` 字段供分页查询

## [v1.1.1] - 2026-08-14

### 新增
- **Pilot 上云完整实现**：TopicSchema 分层差异化（Pilot/Dock Topic 分离）、DRC 协议策略（RC Pro 走 services / RC Plus 2 走 DRC 通道）、云控授权流程、负载控制统一路由、遥控器 OSD 构建（RC Plus/RC Plus 2/RC Pro）、多机型飞行器 OSD（M400/Mavic 3/M4 系列）、Pilot HTTP/WebSocket 接口模拟（地图元素/态势感知/媒体/航线/MOP）
- **机场功能扩展**：PSDK 喊话器、ESDK 互联互通、OTA 固件升级、自定义飞行区、AirSense、远程日志、解锁 License 模拟器
- **WHIP 降级 RTMP 自动推流**：平台下发 `url_type=4`（WebRTC）但 ffmpeg 不支持 WHIP 时，自动将 WebRTC URL 转换为 RTMP URL 推流（ZLM 做 RTMP→WebRTC 转换），无需修改平台配置
- **航线任务模拟器大幅扩展**：全流程任务模拟（上传/下发/执行/进度/完成）

### 优化
- **前端架构重构**：index.html 拆分为外壳 + dock-panel.js + pilot-panel.js，DOCK/PILOT 主内容页全面切换，provide/inject 共享上下文
- Tauri 启动时序优化：`window.show()` 后使用 JS fetch 重试机制加载后端页面，消除启动瞬间 127.0.0.1 无法访问的闪烁
- NSIS 安装/卸载钩子：使用 `cmd /c taskkill` 替代 PowerShell（避免引号转义问题），确保残留 Java sidecar 进程被终止，避免升级时 `java.dll` 文件占用错误

### 修复
- **OSD 导出无数据**：前端传所有相关设备 SN（机场/遥控器+飞行器），后端改为按 topic 后缀过滤（不依赖 payload 字段，避免遗漏 Dock 分组上报）
- DRC 事件推送格式（timestamp 替代 seq）、心跳响应多余 seq 字段

## [v1.1.0] - 2026-08-13

### 新增
- **Pilot 遥控器接入模式**：支持遥控器直接接入云平台，MQTT 连接后直接上线（无需注册流程），包含 update_topo、live_capacity 上报
- **Pilot 遥控器 OSD 构建**：支持不同遥控器型号的差异化字段上报
- 开源协作基础设施：CONTRIBUTING.md、CHANGELOG.md、CODE_OF_CONDUCT.md
- GitHub Issue/PR 模板、Dependabot 配置
- GitHub Actions Release 工作流（Tauri 安装包自动构建发布）
- README 界面截图（主界面、注册配置、设备控制、消息日志、位置模拟、监控器）
- JaCoCo 测试覆盖率报告

### 变更
- README 新增「适用场景」「交流与支持」「支持作者」「Roadmap」章节
- README「不支持的功能」改为「Roadmap」正向表述
- 精简 README「开发工作流」，移除内部流程细节

### 修复
- **MQTT 日志缓冲区性能缺陷（运行 5 分钟后崩溃）**：`ArrayList.remove(0)` 为 O(n) 操作，满载时每条消息需移动 1999 个元素；替换为 `ArrayDeque.pollFirst()` O(1) 操作
- **M30 OSD/State 字段协议对齐**：`payloads` 从 OSD 移到 state topic（pushMode=1）；`rid_state` 仅 M30/M30T 上报；`exit_wayline_when_rc_lost` 废弃字段不上报；`cameras` 子字段全部补齐；`{type-subtype-gimbalindex}.payload_index` 按 pushMode 归属到正确 topic
- **FFmpeg 安装 EventSource 资源泄漏**：组件卸载时未关闭 EventSource 和 installTimer，导致定时器和 SSE 连接泄漏
- M-2 诊断日志过时描述修复（`measure_target_error_state` 值更正、已移除字段描述清理）
- M4D OSD builder 类注释过时修复（`is_near_area_limit/is_near_height_limit` 已提升到基类）

### 优化
- `getLogs()` 从返回全部 2000 条改为最新 500 条，减少网络传输和前端渲染压力
- 前端 `logs` 数组增加防御性截断（`slice(-500)`），避免后端异常导致浏览器渲染过多 DOM

## [v1.0.0] - 2026-08-09

### 首个开源版本
- 多机型支持：Dock1/Dock2/Dock3 + M30/M3D/M4D 系列飞行器
- 完整注册流程：config → airport_bind_status → airport_organization_get → airport_organization_bind → update_topo 上线
- OSD/State 属性上报、航线任务模拟、直播、媒体上传、HMS 告警
- 位置模拟：高德地图选点（自动获取海拔）或手动输入坐标
- 监控器页面：独立 MQTT 客户端，实时查看设备遥测与下行指令
- 诊断系统：DJI Cloud API 方法覆盖率统计
- 桌面端打包：Tauri + 内置精简 JRE
