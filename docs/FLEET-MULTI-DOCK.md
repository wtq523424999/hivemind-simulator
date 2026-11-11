# 多机库模拟与机库群管理

> 面向场景：在平台侧开发「智能航线引擎」——用户在地图上选择感兴趣的区域或点位，
> 平台根据各机库到目标点的距离智能生成航线并选择机库。要验证这套逻辑，
> 需要**多台位于不同地理位置的机库**同时在线。

本文说明如何用 hivemind-simulator 搭出这样一组机库，以及控制台新增的「机库管理」能力。

---

## 1. 总体思路

**每个机库 = 一个独立的模拟器进程**（一台机场 + 一台飞行器）。

模拟器没有把多设备塞进一个进程，而是继续保持「一个进程 = 一台设备」的模型：

- 每个进程有自己的 `RuntimeConfig`、`DeviceState`、MQTT 连接、SN、坐标；
- 每个进程有自己的 config 卷，互不覆盖；
- 控制台新增「机库管理」，作为这组进程的**编排入口**：维护清单、聚合状态、批量操作、生成 compose。

这样做的好处是单实例的全部既有行为（注册、OSD、航线、直播、媒体、HMS、DRC 等）一行都没改，
多机库只是在外面加了一层控制面。

```
            浏览器（9513 / 9090 控制台）
                      │
                      │  /fleet/{id}/ 同源代理（打开其他机库的控制台）
                      ▼
        ┌─────────────────────────────┐
        │  机库管理（FleetController） │
        │  清单 / 状态聚合 / 批量代理   │
        │  控制台代理（/fleet/{id}/）  │
        └──────┬───────────┬──────────┘
               │ HTTP      │ HTTP
        ┌──────▼───┐  ┌────▼─────┐  ┌──────────┐
        │ 实例 1   │  │ 实例 2   │  │ 实例 3   │
        │ 机场+飞机 │  │ 机场+飞机 │  │ 机场+飞机 │
        │ 坐标 A   │  │ 坐标 B   │  │ 坐标 C   │
        └──────┬───┘  └────┬─────┘  └────┬─────┘
               └───────────┴─────────────┘
                           │ MQTT
                    ┌──────▼──────┐
                    │  平台 Broker │
                    └─────────────┘
```

---

## 2. 快速开始

### 2.0 部署实例供给服务（推荐，一次即可）

有了它，页面点「新建机库」就能直接创建新实例，不需要手工 `docker run`。

```bash
# 1) 构建边车镜像
cd provisioner
docker build -t hivemind-simulator-provisioner:latest .

# 2) 启动边车（只挂 Docker socket，挂载方式是这一方案的核心）
docker run -d --name dji-dock-provisioner --restart unless-stopped \
  -p 9500:9500 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --network hivemind-simulator_simulator-net \
  -e PROVISIONER_NETWORK=hivemind-simulator_simulator-net \
  -e PROVISIONER_MQTT_HOST=host.docker.internal \
  -e PROVISIONER_MQTT_PORT=1883 \
  -e PROVISIONER_MQTT_USERNAME=<mqtt账号> \
  -e PROVISIONER_MQTT_PASSWORD=<mqtt密码> \
  hivemind-simulator-provisioner:latest

# 3) 让模拟器主容器知道边车在哪
docker run -d --name dji-dock-simulator \
  ... \
  -e SIMULATOR_PROVISIONER_URL=http://dji-dock-provisioner:9500 \
  hivemind-simulator-simulator:latest
```

> **为什么不用一个容器全包**：模拟器主容器不挂 Docker socket。
> 网页若要能创建容器，就必须有进程能访问 Docker Engine；把这能力隔离在独立边车里，
> 主容器即使被攻破也碰不到宿主机。边车只暴露 5 个接口，带镜像白名单、端口范围限制、
> 容器名与卷名由服务端派生，且只允许删除自己创建的容器（`hivemind.simulator.managed` 标签）。

未部署边车时功能自动降级：新建机库只登记清单，界面提示「未部署供给服务」，
其余能力（状态聚合、批量操作、选库排序等）全部照常可用。

### 2.1 单实例先跑通

```bash
# 构建
mvn package -DskipTests
docker compose up -d

# 打开控制台
open http://localhost:9090
```

按原有流程注册上线（填写平台 MQTT 地址、组织 ID、绑定码），确认一台能正常在线。

### 2.2 新建机库（页面一键完成）

打开控制台 → 右上角「机库管理」→「新建机库」：

1. **创建方式**选「新建并启动」（部署了供给服务时默认选中）。
2. 填机库名称、机型、坐标、海拔；机场/飞行器 SN 建议填**平台侧已有的固定值**
   （留空则实例自动生成唯一 SN，平台不认识，注册会失败）。
3. 「宿主机端口」留空由供给服务自动分配。
4. 「自动注册上线」保持开启，并在工具栏「参数」里填好 MQTT 与组织信息。
5. 点「新建并启动」。

后端一次完成六步，并在结果区逐步显示：

```
登记机库 → 创建实例容器 → 对齐设备身份 → 开机 → 连接 MQTT → 上线 → 下发坐标
```

实测完整闭环约 7.6 秒。若某步失败会停在该步并给出原因，机库登记保留在清单里，
修正参数后可再点「启用」重试，不必重填表单。

> **顺序为什么是这样**：SN 一旦上线就不能再改（实例侧拒绝、平台拓扑已按旧 SN 建立），
> 所以「对齐设备身份」必须在开机和上线之前。而 `organization_id`/`device_binding_code`
> 只能通过 `/api/connect` 写入实例，所以即使 MQTT 已连接，带注册参数时也会补发一次。

### 2.3 仅登记（手动起容器）

若未部署供给服务，或想复用已有容器：创建方式选「仅登记」，填控制台地址：

1. 填名称、控制台地址（`host` + `port`）、机型、坐标、海拔。
2. **如果这台就是当前访问的控制台**，勾选「当前实例」。
   容器部署时对外端口是宿主机映射端口（如 9090 映射到 9513），
   勾选后服务端会自动走容器内部回环访问，避免跨端口不可达。
3. 保存后表格里会显示实时状态（可达 / 开机 / MQTT / 在线）。

容器部署下判断地址是否可达的规则：

| 场景 | 控制台地址填法 |
|---|---|
| 当前这台（容器内） | `127.0.0.1:9090` **并勾选「当前实例」** |
| 同 Docker 网络的另一实例 | `容器名:9090`（推荐） |
| 跨机器实例 | `宿主机局域网 IP:映射端口` |

> 容器内 `127.0.0.1` 指向容器自己，**不是**宿主机。填 `127.0.0.1:9091`
> 访问不到另一个容器的宿主机映射端口，会显示「不可达」。

### 2.3.1 打开其他机库的控制台（同源代理）

机库管理表格里每行的「控制台」按钮打开的是**当前入口下的子路径**：

```
http://<你现在访问的入口>/fleet/<实例 id>/
```

例如从 `http://<宿主机>:9513` 打开控制台，点「模拟器机库2」的「控制台」，
地址是 `http://<宿主机>:9513/fleet/dock-711a51ab/`。

**为什么不是清单里的地址**：清单登记的是**服务端视角**地址（`容器名:9090` 或
`127.0.0.1:9090`）。这个地址服务端用没问题，但交给浏览器会失败——浏览器解析不了
Docker 内部主机名，其他实例的宿主机端口对外也通常没有映射。同源代理由当前实例
转发到目标实例，因此：

- 从 9090、9513 或任意反向代理域名访问，点「控制台」都可用；
- 不需要为目标实例额外开放宿主机端口；
- 目标实例是本机（勾选「当前实例」）时，服务端走内部回环，避免容器内访问宿主机映射端口不通；
- 代理会改写目标页面里的根绝对路径（`/api/...`、`/vendor/...`、`/panels/...`），
  让页面请求回到同一个代理前缀，拿到的是**目标实例**的数据而不是当前实例的；
- 页面存储按实例 id 隔离（`localStorage` 的键加 `fleet:<id>:` 前缀），
  多个实例的控制台在同一入口下不会互相覆盖注册配置、已注册标记与高德 Key。

| 代理路径 | 转发目标 |
|---|---|
| `/fleet/{id}/` | 该实例控制台首页 |
| `/fleet/{id}/api/...` | 该实例 REST 接口 |
| `/fleet/{id}/monitor.html` | 该实例调试器页面 |
| `/fleet/{id}/panels/...`、`/vendor/...` | 该实例静态资源 |

> 代理只在**当前入口**这一层生效：从代理页面里再打开第三个机库，链接仍然是
> `/fleet/{第三个id}/`，落在入口根上，不会叠加成 `/fleet/A/fleet/B/`。

### 2.4 生成多实例编排

「机库管理」→「生成编排」：

- 按清单生成完整的 `docker-compose-fleet.yml`；
- 可填镜像名、MQTT 地址/端口/账号、外部网络名；
- 「下载」后放到仓库根目录执行：

```bash
docker compose -f docker-compose-fleet.yml up -d
```

生成规则：

| 项 | 规则 |
|---|---|
| service 名 | 机库名 slug（中文名退化为 `simulator-N`） |
| 容器名 | `dock-sim-<service>` |
| 端口 | 每个实例映射自己的宿主机端口 |
| config 卷 | 每实例独立 `simulator-config-<service>` |
| media/videos 卷 | 全局共享 |
| 坐标 | 通过 `SIMULATOR_LOCATION_*` 注入 |
| SN | 不写进 compose，由控制台「下发身份」设置 |
| 本机实例 | **不写入编排**（已在运行，重复映射端口会冲突） |
| 停用实例 | 不写入编排 |

### 2.5 一键启用（全部机库）

清单里勾选机库（或不勾选＝全部启用项），点「一键启用」，按顺序执行：

```
开机 → 连接 MQTT → 上线 (update_topo) → 下发坐标
```

特性：

- **逐步幂等**：已开机跳过开机、已连接跳过连接、已在线跳过上线、坐标一致跳过下发；
- **失败即停**：某一步失败会中止该台后续步骤，但继续处理其他机库；
- **逐台明细**：结果区按机库展开每一步的「通过 / 跳过 / 失败」与原因。

首次启用需要填写平台连接参数（工具栏「参数」展开）：MQTT 地址/端口/账号、
组织 ID、绑定码。已注册过的设备重连可打开「跳过注册」。

### 2.6 分步批量（需要精细控制时）

如果只想做某一步，用工具栏的批量按钮，它们对每台机库执行同一个动作：

1. **批量开机**
2. **批量连接**（建立 MQTT）
3. **批量上线**（update_topo）
4. **下发身份/坐标**（把清单里的 SN 与坐标写入各实例）

结果逐实例列在表格下方，部分失败不影响其他实例。

### 2.7 选库排序（联调智能航线引擎）

工具栏「选库排序」：输入目标点经纬度（用户圈选的区域中心或点位），
列出所有机库按**直线距离**排序，并标注能否派遣与原因。

判定口径（与后端 `FleetInstanceStatus.dispatchable()` 一致）：

| 条件 | 说明 |
|---|---|
| 不在线 / 不可达 | 排除 |
| 未上报坐标 | 排除（无法算距离） |
| 正在执行任务 | 排除 |
| 飞行器不在舱 | 排除 |
| 电量 < 30% | 排除 |
| 字段为 null（旧实例） | **不排除**，避免混版本时误伤 |

> 只提供模拟器可确证的数据。完整选库权重（电量权重、空域限制、优先级、
> 跨库接力）属于平台业务，不在模拟器里复制。

### 2.8 清单导入导出

- **导出清单**：下载 `fleet.json`（不含任何凭据）；
- **导入清单**：支持整表替换或按 id 合并；非法条目会被单独拒绝并列出原因，
  其余条目正常导入（适合批量迁移，不会"一条坏数据卡死整批"）。

### 2.9 平台预置数据导出

工具栏「平台预置」按当前清单生成平台侧所需记录：

- **JSON**：`docks` / `devices` 数组，字段名对齐平台 `POST /api/docks`、`POST /api/devices`；
- **SQL**：可直接执行的 `INSERT`，带 `WHERE NOT EXISTS` 可重复执行，自动补 `linked_drone_id` 绑定。

未登记固定 SN 的机库会列入"未生成"并说明原因（唯一 SN 模式下 SN 由实例随机生成，
清单无法预知）。

---

## 3. 平台侧必须做的准备

这一步不做，机库上不了线。平台在 `airport_organization_bind` 阶段会校验网关是否为**已知设备**
（详见平台 `DjiDeviceOnlineApplicationService.isKnownGateway`）：

> 只允许在 `dock` 表或 `device` 表里已存在的 SN 完成组织绑定。

因此每个模拟机库需要在平台侧预置：

| 表 | 必填内容 |
|---|---|
| `dock` | `sn` = 机场 SN（有唯一索引）、`name`、`latitude`/`longitude`、`org_id`、`kind='DOCK'` |
| `device` | `drone_sn` = 飞行器 SN（唯一）、`gateway_sn` = 机场 SN（唯一）、`name`、`model`、`org_id` |
| `dock.linked_drone_id` | 指向上面这台 `device` |

可选：`dock_endpoint`（MQTT / VIDEO_FLV / ROUTE_UPLOAD 等端点），用于平台侧连通性测试与航线上传。

### SN 对齐的两种做法

**做法 A（推荐，可控）**：在控制台「机库管理」里给每台机库填**固定 SN**，
先「下发身份」，再拿这个 SN 去平台建记录。

> `下发身份` 走的是 `/api/connect`，语义上包含"建立 MQTT 连接"。
> 如果此时平台还不可达，该调用会连接超时并报失败，但 SN 覆盖值已经写入并持久化；
> 以「机库管理」表格里显示的机场 SN 为准，确认后再继续。

**做法 B（省事）**：让实例用唯一 SN 模式自动生成 SN，
从表格里读到实际 SN 后再去平台建记录。缺点是每次重建 config 卷 SN 会变。

### 坐标的两处来源

平台的机库坐标有两个来源，务必理解差异：

| 来源 | 写入方式 | 用途 |
|---|---|---|
| `dock.latitude/longitude`（静态） | `POST/PUT /api/docks` 或管理页表单 | 机库列表、航线编辑基准 |
| `dock_runtime_latest.latitude/longitude`（运行时） | 机库 OSD 自动上报 | 实时位置 |
| `displayLat/displayLng`（合并值） | 后端 `coalesce(runtime, static)` | **前端展示与推荐计算建议用它** |

**模拟器改坐标不会自动回写 `dock` 表的静态值**。所以智能选库建议统一读
`/api/runtime/docks/latest` 的 `displayLat/displayLng`：模拟器一改坐标（OSD）立刻生效，
真机接入后行为也一致。

---

## 4. 控制台接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/fleet/instances` | 机库清单（含清单文件路径） |
| POST | `/api/fleet/instances` | 新增登记 |
| PUT | `/api/fleet/instances/{id}` | 修改登记 |
| DELETE | `/api/fleet/instances/{id}` | 移除登记（不停进程） |
| GET | `/api/fleet/instances/export` | 导出清单 JSON |
| POST | `/api/fleet/instances/import?replace=` | 导入清单（`true` 整表替换 / `false` 按 id 合并） |
| GET | `/api/fleet/statuses` | 全部实例实时状态（并发，单台不可达不影响其他） |
| GET | `/api/fleet/models` | 机型选项（由 SDK 枚举推导，不维护静态表） |
| GET | `/api/fleet/candidates?latitude=&longitude=&limit=` | 按到目标点距离排序的候选机库 |
| GET | `/api/fleet/node-summary` | **单实例**摘要（供聚合用：身份/连接/坐标/电量/在舱/任务） |
| POST | `/api/fleet/instances/{id}/actions` | 单实例动作 |
| POST | `/api/fleet/batch` | 批量动作（同一动作并发到多台） |
| POST | `/api/fleet/bringup` | 启用工作流（每台内部按顺序执行多步） |
| GET | `/api/fleet/compose` | 生成 docker compose（`text/yaml`） |
| GET | `/api/fleet/platform-seed` | 平台预置数据（dock/device JSON + SQL） |
| GET | `/api/fleet/platform-seed.sql` | 平台预置 SQL（纯文本，可下载） |
| GET | `/api/fleet/provisioner` | 供给服务状态（enabled / reachable） |
| POST | `/api/fleet/instances/provision` | **新建并启动**：登记 → 创建实例 → 对齐身份 → 连接 → 上线 |
| DELETE | `/api/fleet/instances/{id}?removeContainer=` | 移除登记，可选连容器一起删 |

支持的动作：

| action | 说明 | 附加参数 |
|---|---|---|
| `power_on` | 开机（仅置电源标志，不连 MQTT） | - |
| `power_off` | 关机并下线 | - |
| `connect` | 连接 MQTT | 可带 `mqtt_host`/`mqtt_port`/`mqtt_username`/`mqtt_password` |
| `online` | 上线（update_topo） | 可带 `skip_register=true` |
| `offline` | 下线 | - |
| `set_location` | 改机场坐标 | `latitude`/`longitude`/`height` |
| `apply_identity` | 按清单下发 SN/机型/坐标 | - |

持久化：清单存在 `SIMULATOR_CONFIG_DIR/fleet.json`，与 `live-config.json` 同目录，
跟随 config 卷，重启不丢。

### 状态聚合的单次请求

控制台聚合 N 台机库时优先调各实例的 `/api/fleet/node-summary`（一次请求取全量）。
旧版本实例没有该端点时自动回退到 `/api/device-info` + `/api/connection` + `/api/location`，
此时电量/在舱/任务字段为 `null`（选库过滤对 `null` 采取"不排除"，不会误伤旧实例）。

批量动作示例：

```bash
# 全部启用实例开机
curl -X POST http://localhost:19090/api/fleet/batch \
  -H 'Content-Type: application/json' \
  -d '{"action":"power_on"}'

# 只对两台下发坐标
curl -X POST http://localhost:19090/api/fleet/batch \
  -H 'Content-Type: application/json' \
  -d '{"action":"set_location","ids":["dock-aaa","dock-bbb"],"latitude":31.5,"longitude":105.5,"height":700}'

# 一键启用（顺序工作流，带连接参数）
curl -X POST http://localhost:19090/api/fleet/bringup \
  -H 'Content-Type: application/json' \
  -d '{"mqtt_host":"host.docker.internal","mqtt_port":1883,
       "mqtt_username":"drone","mqtt_password":"drone123",
       "organization_id":"00000000-0000-0000-0000-000000000001",
       "device_binding_code":"123"}'

# 按距离选库（目标点：成都某处）
curl 'http://localhost:19090/api/fleet/candidates?latitude=30.68&longitude=104.08'

# 导出平台预置 SQL
curl -o platform-seed.sql \
  'http://localhost:19090/api/fleet/platform-seed.sql?organizationId=<org-uuid>&download=true'
```

`/api/fleet/candidates` 返回示例（已裁减）：

```json
{
  "success": true,
  "target": { "latitude": 30.68, "longitude": 104.08 },
  "candidates": [
    { "name": "成都机库", "dockSn": "6QCDL870020139",
      "distanceMeters": 1304, "dispatchable": false, "rejectReason": "未上线" },
    { "name": "绵阳机库", "dockSn": "7CTDLCE00AU3J7",
      "distanceMeters": 104463, "dispatchable": false, "rejectReason": "实例不可达" }
  ],
  "dispatchable": 0
}
```

---

## 5. 智能航线引擎的对接建议

平台现有的数据结构有一条硬约束：

> `route_main.dock_id NOT NULL` —— 航线创建时必须绑定机库；
> 执行时 `MissionRouteExecutionService` 取 `routeDetail.get("dockId")` 作为目标机库。

因此推荐**两条路线**，先做前者：

### 路线 A：先选库，再生成航线（推荐，不动执行链路）

```
用户圈选区域/点位
      │
      ▼
候选机库 = 在线 && 电量足够 && 舱内 && 未被占用 && 机型满足
      │
      ▼
对每个候选库计算：转场距离 = haversine(机库坐标, 区域中心)
      │
      ▼
按 score 排序（距离 + 预计转场时间 + 机库可用性 + 15km 部署限制）
      │
      ▼
用户确认 → 以该 dock_id 创建 route_main → 生成 KMZ（takeOffRefPoint = 该机库坐标）
      │
      ▼
走现有 flighttask_prepare / execute 下发
```

这条路线只需新增一个「推荐」接口 + 前端选库交互，执行链路完全复用。

### 路线 B：航线与机库解耦（大改，暂不建议）

让转场段在派发时按选定机库动态拼装。会同时牵动 `route_main` 表结构、
`MissionRouteExecutionService`、KMZ 生成、前端航线列表，不建议作为第一步。

### 打分建议

| 因子 | 建议 |
|---|---|
| 转场距离 | haversine，主排序因子 |
| 转场时间 | 距离 / 巡航速度，用于展示 |
| 在线状态 | `displayLat` 有值且 `online=true` 才进候选 |
| 电量 | 低于阈值（如 40%）剔除或降权 |
| 是否在舱 | 在舱才可起飞 |
| 占用/预约 | 排除已有执行中任务的机库 |
| 部署距离限制 | DJI 对起降机场部署距离有 15km 约束（错误码 338005），跨库任务需校验 |
| 机型能力 | 任务需要热成像/变焦时按机型过滤 |

### 验证方法

1. 三台模拟机库拉到相距数公里的坐标（默认模板已按 6km / 10km 设置）。
2. 在平台圈选一个明显靠近某台机库的区域。
3. **用模拟器「选库排序」交叉验证**：填该区域中心坐标，确认排第一的就是最近那台，
   且 `dispatchable=true`（平台推荐结果应与之一致）。
4. 派发后确认 `flighttask_prepare` 只发给该 SN 的 topic。
5. 观察飞行器 OSD 的起飞点坐标是否等于该机库坐标 —— 这是"选库正确"的端到端证据。
6. 顺带验证排除逻辑：把某台机库的飞行器改成不在舱（或让电量降到 30% 以下），
   确认它立刻从"可派遣"变为"不可派遣"并给出原因。

> 注意：模拟器的 `multi_dock_task`（蛙跳/跨库接力）目前**只解析记录、不执行**，
> 所以"机库到机库接力飞行"飞不起来，验证时请用单库往返任务。

---

## 6. 遥控器上云（单兵）联调

平台侧同一套 MQTT 通道既承载机场（domain=3）也承载遥控器（domain=2）。
「机库管理」的清单可以同时登记机库与遥控器：新增时选「接入模式 → 遥控器上云」，
填遥控器机型（如 RC Plus）与遥控器 SN 即可，飞行器下拉会按搭配关系自动联动。
两种实例在同一张表里聚合状态，云端看到的是「机库 + 遥控器」两类在线设备。

### 6.1 平台侧确认预置数据

遥控器作为网关必须有 `dock` 记录（`kind='REMOTE_CONTROLLER'`），飞行器有 `device` 记录。
「机库管理 → 平台预置」会按清单直接导出这两类记录，遥控器实例的网关 SN 取**遥控器 SN**：

```sql
SELECT sn, name, kind, model, org_id FROM dock WHERE kind = 'REMOTE_CONTROLLER';
SELECT drone_sn, gateway_sn, model FROM device WHERE gateway_sn = '<遥控器 SN>';
```

缺记录时注册会停在「设备绑定」（`210231`）。

### 6.2 创建并上线（页面一步完成）

在「机库管理」点「新增设备」：

1. 接入模式选「遥控器上云（单兵）」
2. 遥控器机型选 RC Plus，遥控器 SN 填 `1581F5RCD001002`
3. 飞行器机型选 M350 RTK，飞行器 SN 填 `1581F6Q8D241P00BFH4Q`
4. 创建方式选「新建并启动」，在「参数」里填 MQTT 地址与账号

提交后按钮会显示逐步明细：登记 → 创建实例 → 对齐设备身份 → 开机 → 连接 MQTT → 上线。
容器创建时已注入 `SIMULATOR_DEVICE_MODE=PILOT`，所以实例**首启就是遥控器身份**，
不存在「创建后还没 connect 就被重启、退化成机场」的窗口期。

对应的 REST 调用（脚本化时使用）：

```bash
curl -X POST http://localhost:9090/api/fleet/instances/provision -H 'Content-Type: application/json' -d '{
  "name":"M350 单兵机组",
  "deviceMode":"PILOT",
  "controllerType":"RC_PLUS",
  "controllerSn":"1581F5RCD001002",
  "droneType":"M350_RTK",
  "droneSn":"1581F6Q8D241P00BFH4Q",
  "latitude":31.6888, "longitude":104.453064, "height":600,
  "autoProvision":true, "autoRegister":true,
  "mqttHost":"host.docker.internal","mqttPort":1883,
  "mqttUsername":"<账号>","mqttPassword":"<密码>"
}'
```

单独改一台已登记实例的身份时，走它自己的 `/api/connect`（`gateway_changed` + `resubscribed`
表示下行订阅已按新网关刷新）：

```bash
curl -X POST http://localhost:<实例端口>/api/connect -H 'Content-Type: application/json' -d '{
  "device_mode":"PILOT",
  "controller_type":"RC_PLUS",
  "controller_sn":"1581F5RCD001002",
  "drone_type":"M350_RTK",
  "drone_sn":"1581F6Q8D241P00BFH4Q"
}'
```

接入模式与遥控器机型会写进该实例 config 卷里的 `live-config.json`，
**容器重启后仍是遥控器身份**，点一次「启用」即可恢复上线。

### 6.3 验证项

| 检查 | 方法 | 期望 |
|---|---|---|
| 拓扑已上报 | `SELECT state, last_topo_at FROM device_registration WHERE gateway_sn='<RC SN>'` | `ONLINE`，时间刷新 |
| 下行订阅正确 | 实例日志过滤 `已订阅` | 出现 `thing/product/<RC SN>/services` 等 6 个 topic |
| 平台指令可达 | 向 `thing/product/<RC SN>/services` 发一条 `drc_mode_enter` | 实例回 `services_reply`，`result=0` |
| DRC 链路 | `GET /api/drone/position` | `drc_state=2`（已连接） |

> **切换模式后必须重新订阅**：MQTT 连接在模式切换时不会断开，
> `connectComplete` 不触发，订阅集仍是旧 SN 的。模拟器已在 `/api/connect`
> 检测到网关变化时自动重订阅并返回 `gateway_changed`；早期版本需要手动调一次
> `/api/connect` 触发重连才能收到指令。

### 6.4 常见误判

| 现象 | 说明 |
|---|---|
| 平台 `dock_runtime_latest` 为空 | 该表依赖平台侧实时入库链路；与"是否上线成功"无关，以 `device_registration.state` 为准 |
| `live_capacity` 返回 `result=1` | 该字段在 DJI 协议里是设备**单向上报**的 state 能力，不是下行指令 |
| `flighttask_*` 返回 `result=1` | 单兵模式按设计拒绝机库专属任务类指令（避免平台误判任务已被接管） |
| 表格里遥控器一直「不可派遣」 | 单兵无机库任务能力，平台无法派发 `flighttask_*` | 正常现象；遥控器实例的在线状态以「已上线」列为准 |
| 重启容器后遥控器不再上线 | 旧版本未持久化接入模式，重启退回 DOCK | 已修复：`deviceMode` / `controllerType` 写入 `live-config.json`；若仍是旧实例，点一次「启用」重新下发即可 |

---

## 7. 排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 表格里实例「不可达」 | 容器内无法访问宿主机映射端口 | 勾选「当前实例」，或把 host 改成容器可达地址（容器名:9090） |
| 点「控制台」打不开 / 浏览器报「无法访问此网站」 | 旧版本直接把清单里的内部地址（`容器名:9090`）交给浏览器 | 升级到含同源代理的版本：按钮改为打开 `/fleet/{id}/`，由当前入口转发（见 §2.3.1） |
| 代理页面打开后显示的是当前实例的数据 | 目标实例是旧版本，页面里的绝对路径没被改写 | 目标实例也升级到同版本；代理只改写它拿到的 HTML/JS |
| 代理页面上传媒体报 `Required part 'file' is not present` | 主入口实例是旧版本，multipart 被本地解析后再转发，body 为空 | 升级主入口实例（新增 `MultipartConfig` 对 `/fleet/**` 跳过 multipart 解析） |
| 代理打开报 502 `实例不可达` | 目标实例容器没起、地址填错，或与当前实例不在同一 Docker 网络 | `docker ps` 确认容器；把 host 改成容器名并确认两者同网络 |
| 新建机库后显示「不可达」 | 只登记了地址，没有启动对应容器 | 部署供给服务后用「新建并启动」；或手动起容器 |
| 「创建实例」步骤报「镜像不存在」 | 宿主机没有该镜像 | 先 `docker build -t hivemind-simulator-simulator:latest .` |
| 「创建实例」报 name 非法 | 容器名需 2~41 位小写字母/数字/中划线 | 中文名会自动转 slug 并补随机后缀，通常无需手改 |
| 注册报 `210229` 机构信息错误 | 绑定码与平台不一致，或注册参数没写进实例 | 在「参数」里填平台实际的 `device_binding_code`；重试会补发 connect |
| 注册报 `210231` 设备绑定失败 | 平台没有该 SN 的预置记录，或实例 SN 与平台不一致 | 按 §3 预置记录；「对齐设备身份」会在开机前把清单 SN 下发给实例 |
| 遥控器实例上线报 `210231` | 平台 `dock` 表没有该**遥控器 SN** 的记录（`kind='REMOTE_CONTROLLER'`） | 用「平台预置」导出并执行 SQL；遥控器 SN 是网关，不是飞行器 SN |
| 改了遥控器机型但实例没变 | 清单只是期望值，需下发到实例 | 点「启用」（身份不一致会自动下发）或单实例「下发身份/坐标」 |
| 注册报 `210231` 且日志里 SN 是 `7UUXN...` 默认值 | 实例用了机型 defaultSn，清单里的固定 SN 没生效 | 已修复：身份对齐提前到开机前；若设备已在线需先下线再启用 |
| 创建成功但「上线」失败、显示未上线 | 缺组织 ID/绑定码 | 在「参数」里补全后点「一键启用」重试，无需重新创建 |
| 供给服务标签显示「未部署供给服务」 | 主容器没配 `SIMULATOR_PROVISIONER_URL` | 加该环境变量后重建主容器 |
| 供给服务标签显示「供给服务不可达」 | 边车未运行或与主容器不在同一网络 | `docker ps` 检查边车；确认两者都在 `simulator-net` |
| 多实例 topic 互相踩 | 共用同一 SN | 确认每台实例 SN 不同（唯一 SN 或手动覆盖） |
| `docker compose up` 报网络不存在 | 编排里引用了不存在的 external 网络 | 生成的编排默认只用 `simulator-net`；需要外部网络时先创建 |
| 改坐标后平台列表没变 | 平台静态坐标不随 OSD 回写 | 读 `displayLat/displayLng`，或用管理页「使用 OSD 位置」回填 |
| 距离推荐没区分度 | 三台机库坐标太近 | 把坐标拉开到数公里 |

---

## 8. 与单实例行为的关系

**单实例的全部既有功能保持不变**：

- 页面布局、注册弹窗、指令通讯窗口、Dock/Pilot 面板、诊断日志、覆盖率报告都没改；
- 所有原有 REST 接口未改动；
- 新增的只有 header 上的「机库管理」按钮与其弹窗。

「机库管理」只做三件事：维护清单、聚合状态、把操作代理到目标实例的既有接口。
它不改变设备状态模型，也不接管 MQTT 通信。
