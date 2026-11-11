// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.model.DeviceCompatibility;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 机库清单管理与多实例联动。
 *
 * <p>设计边界（重要）：本服务<b>不</b>在单进程内承载多台设备。每个机库仍然是一个独立的模拟器进程
 * （独立 {@link RuntimeConfig}/{@link DeviceState}/MQTT 连接），本服务只是这组进程的"控制台"——
 * 维护清单、聚合状态、把操作代理到目标实例。这样可以完整保留单实例的全部既有行为，
 * 也不会把设备状态模型改成多租户。</p>
 *
 * <p>为什么必须服务端代理：浏览器直接访问 {@code http://host:9091} 会触发跨域，
 * 而模拟器没有配置 CORS（多实例各自的 origin 不同）。由本服务发请求，
 * 浏览器始终只访问当前站点，不存在跨域问题。</p>
 *
 * <p>清单里的实例可以指向"当前进程自己"（{@code host=127.0.0.1, port=自身端口}）。
 * 此时<b>状态</b>直接读本地 Bean（省一次 HTTP 往返，也不依赖网络配置）；
 * <b>动作</b>仍走 HTTP 回环到自身端口——动作涉及的 Bean 装配在 {@code SimulatorController}，
 * 复用它的接口比分叉一套本地调用更不容易出现两条路径行为不一致。</p>
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    /** 访问远端实例的超时时间（秒）：短超时保证一台掉线不拖慢整表刷新 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    /** 批量动作并发度：避免几十个实例同时刷新时打满线程 */
    private static final int MAX_CONCURRENCY = 8;

    private final FleetStore store;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final DeviceState localState;
    private final WaylineTaskSimulator waylineTaskSimulator;
    /** 实例供给服务客户端（未配置时为 null，表示只能登记清单、不能创建容器） */
    private final FleetProvisionerClient provisioner;

    /** 本实例自身监听端口（用于识别清单中"指向自己"的条目） */
    private final int selfPort;

    private final HttpClient httpClient;
    private final ExecutorService executor;

    public FleetService(FleetStore store, ObjectMapper objectMapper,
                        RuntimeConfig runtimeConfig, DeviceState localState,
                        WaylineTaskSimulator waylineTaskSimulator,
                        FleetProvisionerClient provisioner,
                        @Value("${server.port:9090}") int selfPort) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.localState = localState;
        this.waylineTaskSimulator = waylineTaskSimulator;
        this.provisioner = provisioner;
        this.selfPort = selfPort;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENCY, runnable -> {
            Thread thread = new Thread(runnable, "fleet-io-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    // ==================== 清单 CRUD ====================

    /** 读取清单。 */
    public FleetConfig load() {
        return store.load();
    }

    /** 清单文件路径（诊断展示用）。 */
    public String configPath() {
        return store.getConfigPath().toAbsolutePath().toString();
    }

    /**
     * 按 id 查清单项。
     *
     * <p>供控制台同源代理（{@link FleetConsoleProxyController}）从请求路径
     * {@code /fleet/{id}/...} 解析目标实例。返回空表示未登记。</p>
     */
    public Optional<FleetInstance> findInstance(String id) {
        return find(store.load(), id);
    }

    /**
     * 新增实例。
     *
     * @param request 实例定义（id 可空，由服务端生成）
     * @return 创建后的实例
     * @throws IllegalArgumentException 参数非法（缺名称、缺地址、端口越界、名称重复）
     */
    public FleetInstance create(FleetInstance request) {
        FleetInstance normalized = normalize(request, null);
        FleetConfig current = store.load();
        if (current.instances().stream()
                .anyMatch(item -> item.name() != null && item.name().equalsIgnoreCase(normalized.name()))) {
            throw new IllegalArgumentException("机库名称已存在: " + normalized.name());
        }
        if (current.instances().stream().anyMatch(item -> sameEndpoint(item, normalized))) {
            throw new IllegalArgumentException("该控制台地址已被登记: " + normalized.baseUrl());
        }
        List<FleetInstance> next = new ArrayList<>(current.instances());
        next.add(normalized);
        persist(next);
        log.info("机库已登记: id={}, name={}, url={}", normalized.id(), normalized.name(), normalized.baseUrl());
        return normalized;
    }

    /**
     * 更新实例（整体替换，id 与 name 不可与既有其他实例冲突）。
     */
    public FleetInstance update(String id, FleetInstance request) {
        FleetConfig current = store.load();
        find(current, id).orElseThrow(() -> new IllegalArgumentException("机库不存在: " + id));
        FleetInstance normalized = normalize(request, id);
        List<FleetInstance> next = new ArrayList<>();
        for (FleetInstance item : current.instances()) {
            if (!item.id().equals(id)
                    && item.name() != null && item.name().equalsIgnoreCase(normalized.name())) {
                throw new IllegalArgumentException("机库名称已存在: " + normalized.name());
            }
            if (!item.id().equals(id) && sameEndpoint(item, normalized)) {
                throw new IllegalArgumentException("该控制台地址已被登记: " + normalized.baseUrl());
            }
            next.add(item.id().equals(id) ? normalized : item);
        }
        persist(next);
        log.info("机库已更新: id={}, name={}, url={}", id, normalized.name(), normalized.baseUrl());
        return normalized;
    }

    /**
     * 删除实例登记（只删清单，不停止对方进程）。
     */
    public void delete(String id) {
        FleetConfig current = store.load();
        if (find(current, id).isEmpty()) {
            throw new IllegalArgumentException("机库不存在: " + id);
        }
        List<FleetInstance> next = current.instances().stream()
                .filter(item -> !item.id().equals(id))
                .toList();
        persist(next);
        log.info("机库已移除登记: id={}", id);
    }

    // ==================== 状态聚合 ====================

    /**
     * 是否为"当前控制台所在的这个实例"。
     *
     * <p>两条判定路径：</p>
     * <ol>
     *   <li><b>显式标记</b>（推荐）：登记时勾选"这是当前实例"，即 {@code selfInstance=true}。
     *       端口可以是宿主机映射端口（如容器内 9090 映射到 9091），不会因端口不同而误判。</li>
     *   <li><b>端口推断</b>（兼容）：未标记时，端口等于 {@code server.port} 且 host 是回环地址，
     *       即认定是本实例。这条只适用于本机直接运行（非容器映射）。</li>
     * </ol>
     */
    public boolean isSelf(FleetInstance instance) {
        if (instance == null) {
            return false;
        }
        if (instance.selfFlag()) {
            return true;
        }
        if (instance.port() != selfPort) {
            return false;
        }
        String host = instance.host() == null ? "" : instance.host().trim().toLowerCase();
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("host.docker.internal")
                || host.equals("0.0.0.0") || host.isEmpty();
    }

    /**
     * 聚合全部实例实时状态（并发拉取，单实例失败不影响其他）。
     */
    public List<FleetInstanceStatus> statuses() {
        List<FleetInstance> instances = store.load().instances();
        List<CompletableFuture<FleetInstanceStatus>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(() -> statusOf(instance), executor))
                .toList();
        List<FleetInstanceStatus> result = new ArrayList<>(futures.size());
        for (CompletableFuture<FleetInstanceStatus> future : futures) {
            try {
                result.add(future.join());
            } catch (RuntimeException e) {
                log.warn("聚合机库状态失败: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 按到目标点的距离给出候选机库排序（用于平台侧「智能选库」联调）。
     *
     * <p>只做模拟器侧能确定的事：拿到每台机库的实时坐标与可用性，算大圆距离并排序，
     * 把不可派遣的原因一并返回。**不实现完整的选库权重策略**（电量权重、任务优先级、
     * 空域限制、机库间接力等属于平台业务），避免把平台逻辑复制到模拟器里造成两处漂移。</p>
     *
     * <p>坐标来源：实例 OSD 上报的实时坐标（{@code runtimeConfig.location*}），
     * 与平台 {@code displayLat/displayLng} 的口径一致——改坐标立即生效。</p>
     *
     * @param latitude  目标点纬度
     * @param longitude 目标点经度
     * @param limit     最多返回多少条（&lt;=0 表示不限制）
     * @return 候选列表，可派遣的按距离升序排在前面，不可派遣的附原因排在后面
     */
    public List<FleetCandidate> candidates(double latitude, double longitude, int limit) {
        List<FleetCandidate> result = new ArrayList<>();
        for (FleetInstanceStatus status : statuses()) {
            if (status.latitude() == null || status.longitude() == null) {
                result.add(FleetCandidate.unlocatable(status));
                continue;
            }
            double distance = haversineMeters(latitude, longitude, status.latitude(), status.longitude());
            result.add(new FleetCandidate(status.id(), status.name(), status.dockSn(), status.droneSn(),
                    status.latitude(), status.longitude(), Math.round(distance),
                    status.reachable(), status.online(), status.dispatchable(),
                    rejectReason(status), status.batteryPercent(), status.taskActive()));
        }
        result.sort(Comparator
                .comparing(FleetCandidate::dispatchable).reversed()
                .thenComparing(item -> item.distanceMeters() == null ? Double.MAX_VALUE : (double) item.distanceMeters()));
        if (limit > 0 && result.size() > limit) {
            return List.copyOf(result.subList(0, limit));
        }
        return List.copyOf(result);
    }

    /** 不可派遣的具体原因（可派遣时返回 null）。 */
    private static String rejectReason(FleetInstanceStatus status) {
        if (!status.reachable()) {
            return "实例不可达";
        }
        if (!Boolean.TRUE.equals(status.online())) {
            return "未上线";
        }
        // 遥控器上云没有机库任务能力：航线由飞手在遥控器上执行，平台无法派发
        if (status.pilotMode()) {
            return "遥控器上云无派发能力";
        }
        if (status.latitude() == null || status.longitude() == null) {
            return "未上报坐标";
        }
        if (Boolean.TRUE.equals(status.taskActive())) {
            return "正在执行任务";
        }
        if (Boolean.FALSE.equals(status.droneInDock())) {
            return "飞行器不在舱";
        }
        if (status.batteryPercent() != null && status.batteryPercent() < 30) {
            return "电量不足（" + status.batteryPercent() + "%）";
        }
        return null;
    }

    /** 大圆距离（米），haversine 公式；与航线里程口径一致（地球半径 6371km）。 */
    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadius = 6371000.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 候选机库（按距离排序的结果项）。
     *
     * @param id             实例标识
     * @param name           机库名称
     * @param dockSn         机场 SN
     * @param droneSn        飞行器 SN
     * @param latitude       机库纬度
     * @param longitude      机库经度
     * @param distanceMeters 到目标点的直线距离（米，四舍五入；无坐标为 null）
     * @param reachable      实例是否可达
     * @param online         是否已上线
     * @param dispatchable   是否可派遣（综合在线/在舱/电量/任务占用）
     * @param rejectReason   不可派遣原因（可派遣为 null）
     * @param batteryPercent 飞行器电量
     * @param taskActive     是否有执行中任务
     */
    public record FleetCandidate(
            String id, String name, String dockSn, String droneSn,
            Double latitude, Double longitude, Long distanceMeters,
            boolean reachable, Boolean online, boolean dispatchable,
            String rejectReason, Integer batteryPercent, Boolean taskActive
    ) {
        static FleetCandidate unlocatable(FleetInstanceStatus status) {
            return new FleetCandidate(status.id(), status.name(), status.dockSn(), status.droneSn(),
                    status.latitude(), status.longitude(), null,
                    status.reachable(), status.online(), false,
                    status.reachable() ? "未上报坐标" : "实例不可达",
                    status.batteryPercent(), status.taskActive());
        }
    }

    /**
     * 单实例实时状态。
     *
     * <p>优先调实例自己的 {@code /api/fleet/node-summary}（一次请求取全量，含电量/在舱/任务占用）；
     * 该端点不存在（旧版本实例返回 404）时回退到分接口请求，保证混版本部署时仍能显示基础状态。</p>
     */
    public FleetInstanceStatus statusOf(FleetInstance instance) {
        if (isSelf(instance)) {
            return localStatus(instance);
        }
        try {
            JsonNode summary;
            try {
                summary = getJson(instance, "/api/fleet/node-summary");
            } catch (IOException legacy) {
                // 旧版本实例没有 node-summary：回退到分接口。仅对 404/405 回退，
                // 其余错误（连接失败等）按不可达处理，避免多打两轮无谓请求。
                log.debug("实例无 node-summary 端点，回退分接口: id={}, reason={}", instance.id(), legacy.getMessage());
                return legacyStatus(instance);
            }
            return new FleetInstanceStatus(
                    instance.id(), instance.name(), instance.consoleUrl(), false, true, null,
                    text(summary, "dockSn"), text(summary, "droneSn"),
                    text(summary, "dockType"), text(summary, "droneType"),
                    text(summary, "deviceMode"),
                    bool(summary, "mqttConnected"), bool(summary, "online"), bool(summary, "powered"),
                    number(summary, "latitude"), number(summary, "longitude"), number(summary, "height"),
                    instance.dockSn(),
                    integer(summary, "batteryPercent"),
                    bool(summary, "droneInDock"), bool(summary, "droneActivated"),
                    bool(summary, "taskActive"), text(summary, "flightId"),
                    integer(summary, "dockModeCode"), integer(summary, "droneModeCode"),
                    integer(summary, "chargeState"),
                    text(summary, "controllerSn"), text(summary, "controllerType"),
                    text(summary, "gatewaySn"));
        } catch (Exception e) {
            log.debug("机库实例不可达: id={}, url={}, reason={}", instance.id(), instance.baseUrl(), e.getMessage());
            return FleetInstanceStatus.unreachable(instance, false, describe(e));
        }
    }

    /**
     * 旧版本实例的状态回退路径：分接口请求，拿得到多少算多少。
     *
     * <p>旧版本没有电量/在舱/任务占用的聚合视图，这些字段置 null；
     * 智能选库的过滤条件对 null 采取"不排除"策略，因此混版本时不会误伤旧实例。</p>
     */
    private FleetInstanceStatus legacyStatus(FleetInstance instance) throws IOException, InterruptedException {
        JsonNode info = getJson(instance, "/api/device-info");
        JsonNode connection = getJson(instance, "/api/connection");
        JsonNode location = getJson(instance, "/api/location");
        return new FleetInstanceStatus(
                instance.id(), instance.name(), instance.consoleUrl(), false, true, null,
                text(info, "dockSn"), text(info, "droneSn"),
                modelName(text(info, "dockModelKey")), modelName(text(info, "droneModelKey")),
                text(info, "deviceMode"),
                bool(connection, "mqtt_connected"), bool(connection, "online"), bool(connection, "powered"),
                number(location, "latitude"), number(location, "longitude"), number(location, "height"),
                instance.dockSn(),
                null, null, null, null, null, null, null, null,
                text(info, "controllerSn"), modelName(text(info, "controllerModelKey")), null);
    }

    /** 本进程自身状态（直接读 Bean，不绕 HTTP）。 */
    private FleetInstanceStatus localStatus(FleetInstance instance) {
        Map<String, Object> task = waylineTaskSimulator == null ? null : waylineTaskSimulator.getTaskStatus();
        Object taskActive = task == null ? null : task.get("active");
        Object flightId = task == null ? null : task.get("flight_id");
        return new FleetInstanceStatus(
                instance.id(), instance.name(), instance.consoleUrl(), true, true, null,
                runtimeConfig.getDockSn(), runtimeConfig.getDroneSn(),
                runtimeConfig.getDockType() == null ? null : runtimeConfig.getDockType().shortName(),
                runtimeConfig.getDroneType() == null ? null : runtimeConfig.getDroneType().shortName(),
                runtimeConfig.getDeviceMode() == null ? null : runtimeConfig.getDeviceMode().name(),
                // MQTT 连接状态不在此处读取：本服务不持有 MqttClientManager，
                // 由 FleetController 在返回前补写（避免 fleet 包反向依赖 mqtt 包）。
                null, localState.isOnline(), localState.isPowered(),
                runtimeConfig.getLocationLatitude(), runtimeConfig.getLocationLongitude(), runtimeConfig.getLocationHeight(),
                instance.dockSn(),
                localState.getBatteryPercent(), localState.isDroneInDock(), localState.isDroneActivated(),
                taskActive instanceof Boolean value ? value : null,
                flightId == null ? null : String.valueOf(flightId),
                localState.getDockModeCode(), localState.getDroneModeCode(), localState.getDroneChargeState(),
                runtimeConfig.getControllerSn(),
                runtimeConfig.getControllerType() == null ? null : runtimeConfig.getControllerType().shortName(),
                runtimeConfig.getGatewaySn());
    }

    /** 供 FleetController 补写自身 MQTT 连接状态。 */
    public FleetInstanceStatus withLocalMqtt(FleetInstanceStatus status, boolean mqttConnected) {
        if (status == null || !status.self()) {
            return status;
        }
        return new FleetInstanceStatus(status.id(), status.name(), status.consoleUrl(), status.self(),
                status.reachable(), status.error(), status.dockSn(), status.droneSn(), status.dockType(),
                status.droneType(), status.deviceMode(), mqttConnected, status.online(), status.powered(),
                status.latitude(), status.longitude(), status.height(), status.configuredDockSn(),
                status.batteryPercent(), status.droneInDock(), status.droneActivated(),
                status.taskActive(), status.flightId(), status.dockModeCode(), status.droneModeCode(),
                status.chargeState(), status.controllerSn(), status.controllerType(), status.gatewaySn());
    }

    // ==================== 单实例动作代理 ====================

    /**
     * 执行单个实例动作。
     *
     * @param instance 目标实例
     * @param action   动作名
     * @param body     附加参数（set_location/apply_identity 使用）
     * @return 动作结果
     */
    public FleetBatchResult.FleetActionResult action(FleetInstance instance, String action, Map<String, Object> body) {
        String normalized = action == null ? "" : action.trim().toLowerCase();
        try {
            switch (normalized) {
                case "power_on" -> {
                    postJson(instance, "/api/power/on", Map.of());
                    return ok(instance, "已开机");
                }
                case "power_off" -> {
                    postJson(instance, "/api/offline", Map.of());
                    return ok(instance, "已关机并下线");
                }
                case "connect" -> {
                    Map<String, Object> payload = body == null ? Map.of() : body;
                    JsonNode reply = postJson(instance, "/api/connect", payload);
                    boolean success = bool(reply, "success");
                    return new FleetBatchResult.FleetActionResult(instance.id(), instance.name(), success,
                            success ? "MQTT 已连接" : "连接失败: " + text(reply, "code") + " " + text(reply, "message"));
                }
                case "online" -> {
                    Map<String, Object> payload = body == null ? Map.of() : body;
                    JsonNode reply = postJson(instance, "/api/online", payload);
                    boolean success = bool(reply, "success");
                    return new FleetBatchResult.FleetActionResult(instance.id(), instance.name(), success,
                            success ? "已上线" : "上线失败: " + text(reply, "code") + " " + text(reply, "step"));
                }
                case "offline" -> {
                    postJson(instance, "/api/offline", Map.of());
                    return ok(instance, "已下线");
                }
                case "set_location" -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("latitude", body == null ? null : body.get("latitude"));
                    payload.put("longitude", body == null ? null : body.get("longitude"));
                    payload.put("height", body == null ? null : body.get("height"));
                    if (payload.get("latitude") == null || payload.get("longitude") == null || payload.get("height") == null) {
                        return fail(instance, "缺少坐标参数（latitude/longitude/height）");
                    }
                    JsonNode reply = putJson(instance, "/api/location", payload);
                    boolean success = bool(reply, "success");
                    return new FleetBatchResult.FleetActionResult(instance.id(), instance.name(), success,
                            success ? "坐标已更新" : "坐标更新失败: " + text(reply, "message"));
                }
                case "apply_identity" -> {
                    return applyIdentity(instance);
                }
                default -> {
                    return fail(instance, "未知动作: " + action);
                }
            }
        } catch (Exception e) {
            return fail(instance, describe(e));
        }
    }

    /**
     * 把清单里登记的期望身份（接入模式 + SN + 机型 + 坐标）下发到实例。
     *
     * <p>身份字段统一走 {@code /api/connect}：</p>
     * <ul>
     *   <li>接入模式：{@code device_mode}（DOCK / PILOT）。PILOT 必须显式下发，
     *       否则实例仍按机场上云走注册流程，遥控器身份不会生效；</li>
     *   <li>DOCK 模式：{@code dock_sn/dock_type} + {@code drone_sn/drone_type}；</li>
     *   <li>PILOT 模式：网关是遥控器，下发 {@code controller_sn/controller_type} +
     *       {@code drone_sn/drone_type}（这时的 drone_* 是遥控器的子设备）。</li>
     * </ul>
     * <p>坐标走 {@code PUT /api/location}。这里<b>只下发清单里显式登记的字段</b>：
     * 未填的字段不出现在请求体里，避免把实例已有的 MQTT 主机/账号等连接参数带空覆盖掉。</p>
     *
     * <p>注意事项：{@code /api/connect} 的语义包含"建立 MQTT 连接"。若只想改 SN 而不想重连，
     * 应保证实例已经连上平台；否则该调用会尝试连接并在超时后返回失败，
     * 此时 SN 是否已写入以状态接口显示的 SN 为准。</p>
     */
    private FleetBatchResult.FleetActionResult applyIdentity(FleetInstance instance) {
        List<String> messages = new ArrayList<>();
        boolean success = true;

        Map<String, Object> connectPayload = new LinkedHashMap<>();
        if (instance.deviceMode() != null && !instance.deviceMode().isBlank()) {
            connectPayload.put("device_mode", instance.deviceModeOrDefault());
        }
        // drone_* 两种模式都下发：DOCK 下是机场的子设备，PILOT 下是遥控器的子设备
        if (instance.droneSn() != null && !instance.droneSn().isBlank()) {
            connectPayload.put("drone_sn", instance.droneSn());
        }
        if (instance.droneType() != null && !instance.droneType().isBlank()) {
            connectPayload.put("drone_type", instance.droneType());
        }
        if (instance.pilot()) {
            // PILOT：网关身份是遥控器，机场字段对实例无意义（机场 stub 不参与上线）
            if (instance.controllerSn() != null && !instance.controllerSn().isBlank()) {
                connectPayload.put("controller_sn", instance.controllerSn());
            }
            if (instance.controllerType() != null && !instance.controllerType().isBlank()) {
                connectPayload.put("controller_type", instance.controllerType());
            }
        } else {
            if (instance.dockSn() != null && !instance.dockSn().isBlank()) {
                connectPayload.put("dock_sn", instance.dockSn());
            }
            if (instance.dockType() != null && !instance.dockType().isBlank()) {
                connectPayload.put("dock_type", instance.dockType());
            }
        }
        if (!connectPayload.isEmpty()) {
            try {
                JsonNode reply = postJson(instance, "/api/connect", connectPayload);
                boolean applied = bool(reply, "success");
                success &= applied;
                messages.add(applied ? "身份已下发" : "身份下发失败: " + connectFailureReason(reply));
            } catch (Exception e) {
                success = false;
                messages.add("身份下发异常: " + describe(e));
            }
        }

        if (instance.latitude() != null && instance.longitude() != null) {
            if (instance.height() == null) {
                messages.add("未配置海拔，坐标未下发");
                success = false;
            } else {
                try {
                    JsonNode reply = putJson(instance, "/api/location", Map.of(
                            "latitude", instance.latitude(),
                            "longitude", instance.longitude(),
                            "height", instance.height()));
                    boolean applied = bool(reply, "success");
                    success &= applied;
                    messages.add(applied ? "坐标已下发" : "坐标下发失败: " + text(reply, "message"));
                } catch (Exception e) {
                    success = false;
                    messages.add("坐标下发异常: " + describe(e));
                }
            }
        }

        if (messages.isEmpty()) {
            messages.add("清单未登记 SN/坐标，无需下发");
        }
        return new FleetBatchResult.FleetActionResult(instance.id(), instance.name(), success,
                String.join("；", messages));
    }

    /** 把 /api/connect 的失败回复整理成可读原因（code + message）。 */
    private static String connectFailureReason(JsonNode reply) {
        String code = text(reply, "code");
        String message = text(reply, "message");
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (code != null && !code.isBlank()) {
            return "code=" + code;
        }
        return "未知原因（平台 MQTT 可能不可达或凭证错误）";
    }

    /**
     * 对全部启用实例执行批量动作（并发）。
     *
     * @param action    动作名
     * @param body      附加参数
     * @param onlyIds   仅对这些 id 执行（空表示全部启用实例）
     */
    public FleetBatchResult batch(String action, Map<String, Object> body, List<String> onlyIds) {
        List<FleetInstance> targets = store.load().instances().stream()
                .filter(FleetInstance::enabledOrDefault)
                .filter(item -> onlyIds == null || onlyIds.isEmpty() || onlyIds.contains(item.id()))
                .toList();
        List<CompletableFuture<FleetBatchResult.FleetActionResult>> futures = targets.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                        () -> action(instance, action, body), executor))
                .toList();
        List<FleetBatchResult.FleetActionResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<FleetBatchResult.FleetActionResult> future : futures) {
            try {
                results.add(future.join());
            } catch (RuntimeException e) {
                log.warn("批量动作失败: {}", e.getMessage());
            }
        }
        log.info("机库批量动作完成: action={}, total={}, success={}", action, results.size(),
                results.stream().filter(FleetBatchResult.FleetActionResult::success).count());
        return FleetBatchResult.of(action, results);
    }

    // ==================== 新建机库全流程（登记 + 创建实例 + 连接 + 注册） ====================

    /**
     * 从零创建一台机库：校验 → 登记清单 → 创建实例容器 → 写入清单地址 → 连接 MQTT → 上线。
     *
     * <p>这是"页面点一下就有一台新机库在线"的入口，替代原先手工执行 docker run 的流程。
     * 与 {@link #bringup} 的分工：bringup 面向"容器已经在跑"的实例；本方法负责把容器拉起来，
     * 拉起后再复用同一套连接/上线逻辑，避免两条路径行为漂移。</p>
     *
     * <p>关键设计：</p>
     * <ul>
     *   <li><b>未部署供给服务时不报错而是降级</b>：只登记清单（地址按用户填的），
     *       并在步骤明细里说明"未创建容器"，用户仍可手工起容器后点「启用」。</li>
     *   <li><b>创建成功后才改写清单地址</b>：容器名与自动分配的端口由供给服务决定，
     *       登记项在创建完成后回填，保证清单里永远是"可达的地址"。</li>
     *   <li><b>创建失败保留登记项</b>：便于用户改端口/改名后重试，不用重新填一遍表单。</li>
     * </ul>
     *
     * @param request  机库登记信息（name/host/port/SN/坐标等）
     * @param options  {@code autoProvision}（默认 true）、{@code autoRegister}（默认 true）、
     *                 {@code hostPort}（可选，指定宿主机端口）、MQTT 连接参数与组织信息
     * @return 全流程结果（含容器名、端口、逐步明细）
     */
    public FleetProvisionResult provisionAndStart(FleetInstance request, Map<String, Object> options) {
        Map<String, Object> opts = options == null ? Map.of() : options;
        List<FleetProvisionResult.Step> steps = new ArrayList<>();
        boolean autoProvision = !Boolean.FALSE.equals(opts.get("autoProvision"));
        boolean autoRegister = !Boolean.FALSE.equals(opts.get("autoRegister"));

        // ===== 步骤 1：校验并登记清单 =====
        FleetInstance created;
        try {
            // 自动创建时地址由供给服务决定（容器名 + 自动分配端口），
            // 登记前先用占位值通过校验，创建成功后再回填真实地址。
            FleetInstance toCreate = request;
            if (autoProvision && (request == null || request.host() == null || request.host().isBlank())) {
                toCreate = new FleetInstance(request.id(), request.name(), "pending", 9090,
                        request.dockSn(), request.droneSn(), request.dockType(), request.droneType(),
                        request.latitude(), request.longitude(), request.height(),
                        request.note(), request.enabled(), request.selfInstance(),
                        request.deviceMode(), request.controllerType(), request.controllerSn());
            }
            created = create(toCreate);
            steps.add(new FleetProvisionResult.Step("validate", "登记机库", true,
                    "已写入清单: " + created.name(), false));
        } catch (IllegalArgumentException e) {
            steps.add(new FleetProvisionResult.Step("validate", "登记机库", false, e.getMessage(), false));
            return FleetProvisionResult.fail(null, request == null ? null : request.name(),
                    "validate", e.getMessage(), null, null, null, steps);
        }

        // ===== 步骤 2：创建实例容器 =====
        String containerName = null;
        Integer hostPort = created.port();
        if (!autoProvision) {
            steps.add(new FleetProvisionResult.Step("provision", "创建实例", true,
                    "已跳过（按请求不自动创建）", true));
        } else if (provisioner == null || !provisioner.enabled()) {
            steps.add(new FleetProvisionResult.Step("provision", "创建实例", true,
                    "未配置实例供给服务，仅登记清单；请手工启动容器后点「启用」", true));
        } else {
            // 实例名必须是 DNS 安全的小写 slug；中文名按 ASCII 化后回退到随机后缀
            String slug = slugify(created.name());
            Integer requestedPort = integerOption(opts.get("hostPort"));
            try {
                FleetProvisionerClient.ProvisionResult result = provisioner.create(
                        slug, requestedPort,
                        created.latitude(), created.longitude(), created.height(),
                        created.dockSn(), created.droneSn(), mqttOptions(opts),
                        created.deviceMode(), created.controllerType(), created.controllerSn());
                containerName = result.containerName();
                hostPort = result.hostPort() != null ? result.hostPort() : hostPort;
                steps.add(new FleetProvisionResult.Step("provision", "创建实例", true,
                        "容器 " + containerName + " 已启动，宿主机端口 " + hostPort
                                + (result.ready() ? "（健康检查通过）" : "（健康检查未通过，仍在启动）"),
                        false));

                // 创建成功后回填清单地址：容器名 + 容器内端口 9090，
                // 这是同一 Docker 网络内其他实例可达的地址（不依赖宿主机端口映射）。
                // 用 provisioner 返回的容器名而非本地 slug，避免两侧命名规则不一致。
                String reachableHost = containerName != null && !containerName.isBlank()
                        ? containerName : slug;
                created = update(created.id(), new FleetInstance(
                        created.id(), created.name(), reachableHost, 9090,
                        created.dockSn(), created.droneSn(), created.dockType(), created.droneType(),
                        created.latitude(), created.longitude(), created.height(),
                        created.note(), created.enabled(), created.selfInstance(),
                        created.deviceMode(), created.controllerType(), created.controllerSn()));
            } catch (FleetProvisionerClient.ProvisionException e) {
                steps.add(new FleetProvisionResult.Step("provision", "创建实例", false, e.getMessage(), false));
                return FleetProvisionResult.fail(created.id(), created.name(), "provision",
                        e.getMessage(), created, null, null, steps);
            }
        }

        // ===== 步骤 3~5：连接 MQTT → 上线 → 下发坐标（复用 bringup 逻辑）=====
        FleetInstance target = find(store.load(), created.id()).orElse(created);
        FleetBringupResult bringup = bringupOne(target, withAutoRegister(opts, autoRegister));
        for (FleetBringupResult.Step step : bringup.steps()) {
            steps.add(new FleetProvisionResult.Step(step.step(), step.label(),
                    step.success(), step.detail(), step.skipped()));
        }

        if (!bringup.success()) {
            return FleetProvisionResult.fail(created.id(), created.name(), bringup.failedStep(),
                    bringup.message(), target, containerName, hostPort, steps);
        }
        return FleetProvisionResult.ok(created.id(), created.name(), target, containerName, hostPort,
                steps, containerName == null
                        ? "机库已登记并启用（未创建容器）"
                        : "机库已创建并上线");
    }

    /** 删除机库：可选同时删除实例容器与其 config 卷。 */
    public Map<String, Object> deleteWithContainer(String id, boolean removeContainer, boolean removeVolume) {
        FleetInstance instance = find(store.load(), id)
                .orElseThrow(() -> new IllegalArgumentException("机库不存在: " + id));
        Map<String, Object> result = new LinkedHashMap<>();
        boolean removed = false;
        if (removeContainer && provisioner != null && provisioner.enabled()) {
            removed = provisioner.remove(provisionerName(instance), removeVolume);
        }
        delete(id);
        result.put("success", true);
        result.put("containerRemoved", removed);
        result.put("volumeRemoved", removed && removeVolume);
        return result;
    }

    /** 供给服务状态（前端据此决定是否展示"自动创建实例"选项）。 */
    public Map<String, Object> provisionerStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean enabled = provisioner != null && provisioner.enabled();
        result.put("enabled", enabled);
        result.put("reachable", enabled && provisioner.reachable());
        return result;
    }

    /**
     * 实例在供给服务里的名字（用于删除）。
     *
     * <p>优先从清单回填的地址反解：创建成功后 {@code host} 被写成容器名
     * （如 {@code dji-dock-simulator-m350-553ded}），这是唯一可靠来源。
     * 不能只用 {@link #slugify(String)} 重算——短名（含纯中文名）在创建时会被
     * 补一个随机后缀，重算必然得到不同的名字，「连容器一起删」会静默失效。</p>
     *
     * <p>回退顺序：容器名反解 → slugify（兼容未回填地址的旧登记）。</p>
     */
    private static String provisionerName(FleetInstance instance) {
        String host = instance.host() == null ? "" : instance.host().trim();
        String prefix = "dji-dock-simulator-";
        if (host.startsWith(prefix) && host.length() > prefix.length()) {
            return host.substring(prefix.length());
        }
        return slugify(instance.name());
    }

    /**
     * 容器名 slug：小写、ASCII、只保留字母数字与中划线；过短时补随机后缀。
     *
     * <p>纯中文名（如「自动化机库A」）ASCII 化后可能只剩 1 个字符甚至为空，
     * 而供给服务要求 2~41 位且以字母数字开头。因此过短时补随机后缀保证唯一且合规，
     * 不用固定补位（否则多台中文名会撞名）。</p>
     */
    private static String slugify(String name) {
        String raw = name == null ? "" : java.text.Normalizer
                .normalize(name, java.text.Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (raw.isEmpty()) {
            raw = "dock";
        }
        if (Character.isDigit(raw.charAt(0))) {
            raw = "dock-" + raw;
        }
        // 过短时补随机后缀：既满足最小长度，也避免中文名批量创建时撞名
        if (raw.length() < 6) {
            raw = raw + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return raw.length() > 40 ? raw.substring(0, 40).replaceAll("-+$", "") : raw;
    }

    private static Integer integerOption(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> mqttOptions(Map<String, Object> opts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mqttHost", opts.get("mqttHost"));
        result.put("mqttPort", opts.get("mqttPort"));
        result.put("mqttUsername", opts.get("mqttUsername"));
        result.put("mqttPassword", opts.get("mqttPassword"));
        return result;
    }

    /** autoRegister=false 时把 bringup 的上线步骤改成"跳过注册"语义。 */
    private static Map<String, Object> withAutoRegister(Map<String, Object> opts, boolean autoRegister) {
        Map<String, Object> params = new LinkedHashMap<>(opts);
        if (autoRegister) {
            params.put("skip_register", false);
        }
        return params;
    }

    // ==================== 启用工作流（bring-up） ====================

    /**
     * 按顺序启用一台机库：开机 → 连接 MQTT → 上线 → 下发身份与坐标。
     *
     * <p>为什么需要顺序工作流：这四步有严格依赖——没开机不能连（实例侧会拒绝）、
     * 没连上不能上线（update_topo 发不出去）、上线前改 SN 会导致平台侧拓扑与身份不一致。
     * 批量动作是"同一动作并发到多台"，无法表达这种单机内的顺序，
     * 因此单独提供本方法。任一步失败即中止该机库的后续步骤，但仍继续处理其他机库。</p>
     *
     * <p>各步骤的"已满足"判断是幂等的：已开机跳过开机、已连接跳过连接、已在线跳过上线、
     * 身份与清单一致跳过下发。重复执行安全，适合"点一次把所有机库拉起来"的用法。</p>
     *
     * @param body 可带 {@code mqtt_host}/{@code mqtt_port}/{@code mqtt_username}/{@code mqtt_password}/
     *             {@code organization_id}/{@code device_binding_code} 覆盖连接参数；
     *             可带 {@code skip_register=true} 跳过注册（已注册设备开机重连）
     * @param onlyIds 只处理这些 id（空表示全部启用实例）
     * @return 逐机库的工作流结果
     */
    public List<FleetBringupResult> bringup(Map<String, Object> body, List<String> onlyIds) {
        Map<String, Object> params = body == null ? Map.of() : body;
        List<FleetInstance> targets = store.load().instances().stream()
                .filter(FleetInstance::enabledOrDefault)
                .filter(item -> onlyIds == null || onlyIds.isEmpty() || onlyIds.contains(item.id()))
                .toList();

        List<CompletableFuture<FleetBringupResult>> futures = targets.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                        () -> bringupOne(instance, params), executor))
                .toList();

        List<FleetBringupResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<FleetBringupResult> future : futures) {
            try {
                results.add(future.join());
            } catch (RuntimeException e) {
                log.warn("机库启用工作流异常: {}", e.getMessage());
            }
        }
        log.info("机库启用工作流完成: total={}, success={}", results.size(),
                results.stream().filter(FleetBringupResult::success).count());
        return List.copyOf(results);
    }

    /** 单机库的顺序启用流程。 */
    private FleetBringupResult bringupOne(FleetInstance instance, Map<String, Object> params) {
        List<FleetBringupResult.Step> steps = new ArrayList<>();

        // ===== 步骤 0：探测当前状态（决定哪些步骤可跳过）=====
        FleetInstanceStatus before;
        try {
            before = statusOf(instance);
        } catch (Exception e) {
            return FleetBringupResult.failed(instance.id(), instance.name(), "probe",
                    "实例不可达: " + describe(e), steps);
        }
        if (!before.reachable()) {
            return FleetBringupResult.failed(instance.id(), instance.name(), "probe",
                    "实例不可达: " + (before.error() == null ? "连接失败" : before.error()), steps);
        }

        // ===== 步骤 0：对齐设备身份（必须在开机/上线之前）=====
        // 设备的 SN 一旦上线就不能再改（实例侧拒绝，且平台拓扑已按旧 SN 建立）。
        // 新建实例默认用机型 defaultSn，与清单登记的固定 SN 不一致；
        // 若不在这里先改掉，上线时平台会因"未知网关"拒绝绑定（210231）。
        //
        // 接入模式切换是同一类问题但更彻底：DOCK ↔ PILOT 换的是"谁是网关"，
        // 平台侧拓扑完全不同。设备在线时既要先把旧拓扑撤下来（离线），
        // 也要避开实例对在线改 SN 的拒绝，因此先下线再下发身份。
        boolean modeMismatch = deviceModeMismatch(instance, before);
        if (modeMismatch && Boolean.TRUE.equals(before.online())) {
            FleetBatchResult.FleetActionResult result = action(instance, "offline", Map.of());
            steps.add(new FleetBringupResult.Step("apply_identity", "切换接入模式前下线",
                    result.success(), result.success() ? "已下线旧拓扑，准备切到 "
                            + instance.deviceModeOrDefault() : result.message(), false));
            if (!result.success()) {
                return FleetBringupResult.failed(instance.id(), instance.name(), "apply_identity",
                        result.message(), steps);
            }
            before = statusOf(instance);
        }
        boolean identityMismatch = modeMismatch || !identityMatches(instance, before);
        if (identityMismatch) {
            if (Boolean.TRUE.equals(before.online())) {
                steps.add(new FleetBringupResult.Step("apply_identity", "对齐设备身份", false,
                        "清单 SN 与实例不一致，但设备已在线无法修改；请先下线再执行", false));
                return FleetBringupResult.failed(instance.id(), instance.name(), "apply_identity",
                        "清单 SN 与实例不一致且设备已在线，请先下线", steps);
            }
            FleetBatchResult.FleetActionResult result = action(instance, "apply_identity", Map.of());
            steps.add(new FleetBringupResult.Step("apply_identity", "对齐设备身份", result.success(),
                    result.message(), false));
            if (!result.success()) {
                return FleetBringupResult.failed(instance.id(), instance.name(), "apply_identity",
                        result.message(), steps);
            }
            // 身份下发走的是 /api/connect，可能已顺带完成 MQTT 连接；
            // 刷新一次状态，后续步骤据此正确判断"已连接/已开机"是否可跳过
            before = statusOf(instance);
        } else {
            steps.add(new FleetBringupResult.Step("apply_identity", "对齐设备身份", true,
                    "SN 与机型已一致，跳过", true));
        }

        // ===== 步骤 1：开机 =====
        if (Boolean.TRUE.equals(before.powered())) {
            steps.add(new FleetBringupResult.Step("power_on", "开机", true, "已开机，跳过", false));
        } else {
            FleetBatchResult.FleetActionResult result = action(instance, "power_on", Map.of());
            steps.add(new FleetBringupResult.Step("power_on", "开机", result.success(), result.message(), false));
            if (!result.success()) {
                return FleetBringupResult.failed(instance.id(), instance.name(), "power_on",
                        result.message(), steps);
            }
        }

        // ===== 步骤 2：连接 MQTT（并写入注册所需的组织信息）=====
        // 注意：organization_id / device_binding_code 只能通过 /api/connect 写入实例。
        // 若实例"已连接"就整步跳过，这些值不会有更新机会，后续注册必然因绑定码不匹配失败
        //（平台 210229）。因此只要请求带了这些参数，即使已连接也要补发一次 connect。
        Map<String, Object> registrationParams = selectKeys(params,
                "organization_id", "device_binding_code", "app_license");
        boolean needRegistrationPush = !registrationParams.isEmpty();
        if (Boolean.TRUE.equals(before.mqttConnected()) && !needRegistrationPush) {
            steps.add(new FleetBringupResult.Step("connect", "连接 MQTT", true, "已连接，跳过", false));
        } else {
            Map<String, Object> connectParams = new LinkedHashMap<>(selectKeys(params,
                    "mqtt_host", "mqtt_port", "mqtt_username", "mqtt_password"));
            connectParams.putAll(registrationParams);
            FleetBatchResult.FleetActionResult result = action(instance, "connect", connectParams);
            String detail = result.message();
            if (result.success() && Boolean.TRUE.equals(before.mqttConnected()) && needRegistrationPush) {
                detail = "已连接，并更新注册参数";
            }
            steps.add(new FleetBringupResult.Step("connect", "连接 MQTT", result.success(), detail, false));
            if (!result.success()) {
                return FleetBringupResult.failed(instance.id(), instance.name(), "connect",
                        result.message(), steps);
            }
        }

        // ===== 步骤 3：上线 =====
        if (Boolean.TRUE.equals(before.online())) {
            steps.add(new FleetBringupResult.Step("online", "上线", true, "已在线，跳过", false));
        } else {
            Map<String, Object> onlineParams = new LinkedHashMap<>();
            if (Boolean.TRUE.equals(params.get("skip_register"))
                    || "true".equalsIgnoreCase(String.valueOf(params.get("skip_register")))) {
                onlineParams.put("skip_register", true);
            }
            FleetBatchResult.FleetActionResult result = action(instance, "online", onlineParams);
            steps.add(new FleetBringupResult.Step("online", "上线", result.success(), result.message(), false));
            if (!result.success()) {
                return FleetBringupResult.failed(instance.id(), instance.name(), "online",
                        result.message(), steps);
            }
        }

        // ===== 步骤 4：下发坐标 =====
        // SN 已在开机前的「对齐设备身份」处理（在线后实例会拒绝改 SN）。
        // 这里只处理坐标：清单登记值与实例上报值不一致时下发。
        if (instance.latitude() != null && instance.longitude() != null && instance.height() != null
                && !coordinateMatches(instance, before)) {
            FleetBatchResult.FleetActionResult result = action(instance, "set_location", Map.of(
                    "latitude", instance.latitude(),
                    "longitude", instance.longitude(),
                    "height", instance.height()));
            steps.add(new FleetBringupResult.Step("set_location", "下发坐标", result.success(),
                    result.message(), false));
            if (!result.success()) {
                return FleetBringupResult.failed(instance.id(), instance.name(), "set_location",
                        result.message(), steps);
            }
        } else {
            steps.add(new FleetBringupResult.Step("set_location", "下发坐标", true,
                    "坐标已一致，跳过", true));
        }

        return FleetBringupResult.success(instance.id(), instance.name(), steps);
    }

    /**
     * 清单登记的接入模式是否与实例当前模式不同。
     * <p>实例上报的模式缺失（旧版本实例）时按"无法判断"处理，返回 false，
     * 避免把老实例当成不一致而反复下线。</p>
     */
    private static boolean deviceModeMismatch(FleetInstance instance, FleetInstanceStatus status) {
        if (instance.deviceMode() == null || instance.deviceMode().isBlank()) {
            return false;
        }
        return status.deviceMode() != null
                && !instance.deviceModeOrDefault().equalsIgnoreCase(status.deviceMode());
    }

    /**
     * 清单登记的 SN/机型/接入模式是否与实例当前生效值一致（未登记视为一致）。
     *
     * <p>接入模式也要比对：清单登记 PILOT 但实例还在 DOCK 时，若不下发模式，
     * 实例会按机场上云走注册流程，遥控器永远不会成为网关。</p>
     */
    private static boolean identityMatches(FleetInstance instance, FleetInstanceStatus status) {
        if (instance.deviceMode() != null && !instance.deviceMode().isBlank()
                && status.deviceMode() != null
                && !instance.deviceModeOrDefault().equalsIgnoreCase(status.deviceMode())) {
            return false;
        }
        if (instance.pilot()) {
            // PILOT：网关身份看遥控器 SN，机场 SN 不参与上线
            if (instance.controllerSn() != null && !instance.controllerSn().isBlank()
                    && !instance.controllerSn().equals(status.controllerSn())) {
                return false;
            }
            if (instance.controllerType() != null && !instance.controllerType().isBlank()
                    && status.controllerType() != null
                    && !matchesControllerType(instance.controllerType(), status.controllerType())) {
                return false;
            }
        } else if (instance.dockSn() != null && !instance.dockSn().isBlank()
                && !instance.dockSn().equals(status.dockSn())) {
            return false;
        }
        if (instance.droneSn() != null && !instance.droneSn().isBlank()
                && !instance.droneSn().equals(status.droneSn())) {
            return false;
        }
        return true;
    }

    /**
     * 清单里的遥控器机型（枚举名，如 {@code RC_PLUS}）与实例上报值（简称，如 {@code RC Plus}）比对。
     * <p>两侧口径不同：清单存 SDK 枚举名（可直接回填表单下拉），实例
     * {@code /api/fleet/node-summary} 返回 {@code shortName()}。把清单值翻译成简称再比，
     * 无法识别的枚举名按字符串比较兜底，避免因 SDK 升级导致误判"身份不一致"而反复下发。</p>
     */
    private static boolean matchesControllerType(String configured, String reported) {
        if (configured.equalsIgnoreCase(reported)) {
            return true;
        }
        try {
            return RcModel.valueOf(configured.trim().toUpperCase()).shortName().equalsIgnoreCase(reported);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 清单登记的坐标是否与实例当前坐标一致（容差 1e-6 度，约 0.1 米）。 */
    private static boolean coordinateMatches(FleetInstance instance, FleetInstanceStatus status) {
        if (status.latitude() == null || status.longitude() == null) {
            return false;
        }
        return Math.abs(instance.latitude() - status.latitude()) < 1e-6
                && Math.abs(instance.longitude() - status.longitude()) < 1e-6;
    }

    private static Map<String, Object> selectKeys(Map<String, Object> source, String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    // ==================== 清单导入导出 ====================

    /**
     * 导出清单为可分享的 JSON（含版本号，可直接作为导入输入）。
     *
     * <p>导出内容与 {@code fleet.json} 结构一致，但<b>不包含</b>任何凭据——
     * MQTT 账号密码属于实例自身的 live-config，不在清单里。</p>
     */
    public FleetConfig exportConfig() {
        FleetConfig current = store.load();
        return new FleetConfig(FleetConfig.CURRENT_VERSION, current.instances());
    }

    /**
     * 导入清单（整表替换）。
     *
     * <p>两种合并模式：</p>
     * <ul>
     *   <li>{@code replace=true}（默认）：用导入内容整体替换现有清单；</li>
     *   <li>{@code replace=false}：按 id 合并，id 冲突时更新，新 id 追加，不清空既有条目。</li>
     * </ul>
     *
     * <p>导入过程中逐条校验：名称重复、地址重复、坐标越界的条目会被拒绝并列入 {@code rejected}，
     * 其余合法条目正常写入。这比"整个导入失败"更适合批量迁移场景。</p>
     *
     * @param config  导入的清单
     * @param replace true=整表替换，false=按 id 合并
     * @return 导入结果（成功数、拒绝明细、最终清单）
     */
    public Map<String, Object> importConfig(FleetConfig config, boolean replace) {
        if (config == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        List<FleetInstance> incoming = config.instances() == null ? List.of() : config.instances();

        List<FleetInstance> base = replace ? new ArrayList<>() : new ArrayList<>(store.load().instances());
        List<String> rejected = new ArrayList<>();
        int imported = 0;

        for (FleetInstance raw : incoming) {
            try {
                FleetInstance normalized = normalize(raw, null);
                // 同名/同地址冲突检查（替换模式下 base 为空，首条总能进）
                boolean nameConflict = base.stream().anyMatch(item -> item.id().equals(normalized.id())
                        ? false
                        : item.name() != null && item.name().equalsIgnoreCase(normalized.name()));
                boolean endpointConflict = base.stream().anyMatch(item -> !item.id().equals(normalized.id())
                        && sameEndpoint(item, normalized));
                if (nameConflict) {
                    rejected.add(normalized.name() + "：名称与既有条目冲突");
                    continue;
                }
                if (endpointConflict) {
                    rejected.add(normalized.name() + "：控制台地址与既有条目冲突（" + normalized.baseUrl() + "）");
                    continue;
                }
                // 同 id 覆盖，否则追加
                base.removeIf(item -> item.id().equals(normalized.id()));
                base.add(normalized);
                imported++;
            } catch (IllegalArgumentException e) {
                rejected.add((raw == null ? "<null>" : String.valueOf(raw.name())) + "：" + e.getMessage());
            }
        }

        persist(base);
        log.info("机库清单导入完成: mode={}, imported={}, rejected={}, total={}",
                replace ? "replace" : "merge", imported, rejected.size(), base.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mode", replace ? "replace" : "merge");
        result.put("imported", imported);
        result.put("rejected", rejected);
        result.put("total", base.size());
        result.put("instances", base);
        return result;
    }

    // ==================== 平台预置清单 ====================

    /**
     * 导出平台侧预置数据（{@code dock} + {@code device}）。
     *
     * <p>平台在 {@code airport_organization_bind} 阶段只允许"已知网关"完成绑定
     * （查 {@code dock}/{@code device} 表）。因此每个模拟机库上线前，
     * 必须在平台侧先有对应记录。本方法把清单翻译成两类可直接使用的产物：</p>
     * <ul>
     *   <li>{@code docks} / {@code devices}：JSON 数组，字段名对齐平台
     *       {@code POST /api/docks} 与 {@code POST /api/devices} 的请求体；</li>
     *   <li>{@code sql}：可直接在平台库执行的 INSERT 语句（带存在性判断，可重复执行）。</li>
     * </ul>
     *
     * <p><b>只导出有明确 SN 的机库</b>：唯一 SN 模式下 SN 由实例启动时随机生成并持久化，
     * 清单里若没填固定 SN，这里无法预知，这类机库会被列在 {@code skipped} 中并提示先「下发身份」
     * 固定 SN，或从机库列表读取实际 SN 后回填清单。</p>
     *
     * <p><b>遥控器上云实例按 {@code kind='REMOTE_CONTROLLER'} 导出</b>：这类实例没有机场 SN，
     * 网关身份是遥控器 SN。平台侧要求网关记录同样落在 {@code dock} 表里（用 kind 区分），
     * 飞行器仍通过 {@code gateway_sn=<遥控器 SN>} 挂到该网关下。</p>
     *
     * @param organizationId 平台组织 ID（为空时用占位符，需人工替换）
     * @param brand          品牌（默认 DJI）
     * @return {@code {docks, devices, sql, skipped, organizationId}}
     */
    public Map<String, Object> exportPlatformSeed(String organizationId, String brand) {
        String orgId = blankTo(organizationId, "00000000-0000-0000-0000-000000000001");
        String effectiveBrand = blankTo(brand, "DJI");

        List<Map<String, Object>> docks = new ArrayList<>();
        List<Map<String, Object>> devices = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        sql.append("-- ========================================\n");
        sql.append("-- 平台侧机库预置数据（由模拟器「机库管理」导出）\n");
        sql.append("-- ========================================\n");
        sql.append("-- 用途：平台在 airport_organization_bind 阶段只允许已知网关绑定，\n");
        sql.append("--       因此每个模拟机库上线前必须先在 dock/device 表里存在对应记录。\n");
        sql.append("-- 执行：psql -U <user> -d <db> -f platform-seed.sql\n");
        sql.append("-- 说明：带 WHERE NOT EXISTS，可重复执行；坐标用清单登记值。\n");
        sql.append("--       遥控器上云实例的网关记录 kind='REMOTE_CONTROLLER'，机场上云为 'DOCK'。\n");
        sql.append("-- 组织：").append(orgId).append("\n");
        sql.append("-- ========================================\n\n");

        for (FleetInstance instance : store.load().instances()) {
            if (!instance.enabledOrDefault()) {
                continue;
            }
            boolean pilot = instance.pilot();
            // 网关 SN：DOCK=机场 SN，PILOT=遥控器 SN
            String gatewaySn = pilot ? instance.controllerSn() : instance.dockSn();
            String gatewayLabel = pilot ? "遥控器" : "机场";
            if (gatewaySn == null || gatewaySn.isBlank()) {
                skipped.add(instance.name() + "：未登记固定" + gatewayLabel + " SN"
                        + (pilot ? "（遥控器是网关，请填写遥控器 SN）" : "（请先在「下发身份」中固定 SN）"));
                continue;
            }
            if (instance.droneSn() == null || instance.droneSn().isBlank()) {
                skipped.add(instance.name() + "：未登记飞行器 SN");
                continue;
            }
            String dockModel = pilot
                    ? blankTo(instance.controllerType(), "RC_PLUS")
                    : blankTo(instance.dockType(), "DOCK3");
            String droneModel = blankTo(instance.droneType(), "M4TD");
            String dockName = instance.name();
            String kind = pilot ? "REMOTE_CONTROLLER" : "DOCK";

            // ===== dock 表记录（网关：机场或遥控器）=====
            Map<String, Object> dock = new LinkedHashMap<>();
            dock.put("dockSn", gatewaySn);
            dock.put("name", dockName);
            dock.put("orgId", orgId);
            dock.put("brand", effectiveBrand);
            dock.put("model", dockModel);
            dock.put("brandModel", effectiveBrand + " " + dockModel);
            dock.put("latitude", instance.latitude());
            dock.put("longitude", instance.longitude());
            dock.put("status", "OFFLINE");
            dock.put("kind", kind);
            docks.add(dock);

            // ===== device 表记录 =====
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("name", dockName + "-飞行器");
            device.put("droneSn", instance.droneSn());
            device.put("gatewaySn", gatewaySn);
            device.put("orgId", orgId);
            device.put("model", droneModel);
            device.put("manufacturer", effectiveBrand);
            device.put("status", "enabled");
            devices.add(device);

            // ===== SQL =====
            sql.append("-- ---------- ").append(dockName)
                    .append(pilot ? "（遥控器上云）" : "（机场上云）").append(" ----------\n");
            sql.append("-- 1) 网关记录（").append(gatewayLabel).append("，kind=").append(kind).append("）\n");
            sql.append("INSERT INTO dock (id, name, status, sn, org_id, brand, model, latitude, longitude, location_updated_at, kind)\n");
            sql.append("SELECT uuidv7_generate(), ").append(sqlText(dockName)).append(", 'OFFLINE', ")
                    .append(sqlText(gatewaySn)).append(", cast(").append(sqlText(orgId)).append(" as uuid), ")
                    .append(sqlText(effectiveBrand)).append(", ").append(sqlText(dockModel)).append(", ")
                    .append(sqlNumber(instance.latitude())).append(", ").append(sqlNumber(instance.longitude()))
                    .append(", CURRENT_TIMESTAMP, ").append(sqlText(kind)).append("\n");
            sql.append("WHERE NOT EXISTS (SELECT 1 FROM dock WHERE sn = ")
                    .append(sqlText(gatewaySn)).append(");\n\n");

            sql.append("-- 2) 飞行器记录\n");
            sql.append("INSERT INTO device (id, name, model, manufacturer, status, org_id, gateway_sn, drone_sn)\n");
            sql.append("SELECT uuidv7_generate(), ").append(sqlText(dockName + "-飞行器")).append(", ")
                    .append(sqlText(droneModel)).append(", ").append(sqlText(effectiveBrand))
                    .append(", 'enabled', cast(").append(sqlText(orgId)).append(" as uuid), ")
                    .append(sqlText(gatewaySn)).append(", ").append(sqlText(instance.droneSn())).append("\n");
            sql.append("WHERE NOT EXISTS (SELECT 1 FROM device WHERE drone_sn = ")
                    .append(sqlText(instance.droneSn())).append(");\n\n");

            if (pilot) {
                // 遥控器没有 linked_drone_id 语义（该字段属于机场↔飞行器的停靠关系），
                // 只保证 device.gateway_sn 指向遥控器 SN 即可完成拓扑绑定
                sql.append("-- 3) 遥控器无停靠关系，飞行器通过 gateway_sn 挂在该遥控器下\n");
                sql.append("UPDATE device SET gateway_sn = ").append(sqlText(gatewaySn))
                        .append(" WHERE drone_sn = ").append(sqlText(instance.droneSn())).append(";\n\n");
            } else {
                sql.append("-- 3) 机库 ↔ 飞行器绑定\n");
                sql.append("UPDATE dock SET linked_drone_id = (SELECT id FROM device WHERE drone_sn = ")
                        .append(sqlText(instance.droneSn())).append(")\n");
                sql.append("WHERE sn = ").append(sqlText(gatewaySn)).append(";\n\n");
                sql.append("UPDATE device SET gateway_sn = ").append(sqlText(gatewaySn))
                        .append(" WHERE drone_sn = ").append(sqlText(instance.droneSn())).append(";\n\n");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("organizationId", orgId);
        result.put("docks", docks);
        result.put("devices", devices);
        result.put("sql", sql.toString());
        result.put("skipped", skipped);
        return result;
    }

    /** SQL 字符串字面量（单引号转义）。 */
    private static String sqlText(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    /** SQL 数值字面量（null 安全）。 */
    private static String sqlNumber(Double value) {
        return value == null ? "NULL" : String.valueOf(value);
    }

    // ==================== Compose 生成 ====================

    /**
     * 按清单生成 {@code docker-compose-fleet.yml} 文本。
     *
     * <p>生成的是"每个实例一个 service"的编排片段，复用同一个镜像、各自独立 config 卷、
     * 共享 media/videos 卷、映射不同宿主机端口。坐标通过 {@code SIMULATOR_LOCATION_*} 环境变量注入，
     * SN 通过 {@code /api/connect} 由控制台下发（不写进 compose，避免明文散落）。</p>
     *
     * @param image      镜像名（为空时用默认值）
     * @param mqttHost   实例连接的 MQTT 地址（为空时用默认值）
     * @param network    外部网络名（为空则不加入外部网络，仅用 host.docker.internal）
     */
    public String renderCompose(String image, String mqttHost, Integer mqttPort,
                                String mqttUsername, String mqttPassword, String network) {
        String effectiveImage = blankTo(image, "hivemind-simulator-simulator:latest");
        String effectiveHost = blankTo(mqttHost, "host.docker.internal");
        int effectivePort = mqttPort == null || mqttPort <= 0 ? 1883 : mqttPort;
        String effectiveUser = blankTo(mqttUsername, "");
        String effectivePassword = blankTo(mqttPassword, "");
        String effectiveNetwork = blankTo(network, "");

        StringBuilder sb = new StringBuilder();
        sb.append("# ========================================\n");
        sb.append("# 机库群编排（由模拟器控制台「机库管理」生成）\n");
        sb.append("# ========================================\n");
        sb.append("# 启动: docker compose -f docker-compose-fleet.yml up -d\n");
        sb.append("# 停止: docker compose -f docker-compose-fleet.yml down\n");
        sb.append("#\n");
        sb.append("# 说明:\n");
        sb.append("#   1. 每个 service = 一台机场 + 一台飞行器，共用同一镜像\n");
        sb.append("#   2. 每个实例独立 config 卷（持久化各自 SN 与配置），共享 media/videos 卷\n");
        sb.append("#   3. 坐标由 SIMULATOR_LOCATION_* 注入；SN 建议在控制台「机库管理」里下发\n");
        sb.append("#   4. 唯一 SN 模式已开启，实例会自动生成并持久化唯一 SN，避免共用 broker 时 topic 冲突\n");
        sb.append("#   5. 标记为「本机」的实例不会写入编排（它已经在运行，重复映射端口会冲突）\n");
        sb.append("# ========================================\n");
        sb.append("\nservices:\n");

        List<FleetInstance> instances = store.load().instances().stream()
                .filter(FleetInstance::enabledOrDefault)
                .filter(item -> !isSelf(item))
                .toList();
        if (instances.isEmpty()) {
            sb.append("  # 清单为空（或仅登记了本机实例）：先在控制台「机库管理」中新增机库，再重新生成编排\n");
            sb.append("  simulator: {}\n");
        }
        for (int i = 0; i < instances.size(); i++) {
            FleetInstance instance = instances.get(i);
            String serviceName = serviceName(instance, i);
            sb.append("\n  # ").append(displayName(instance)).append("\n");
            sb.append("  ").append(serviceName).append(":\n");
            sb.append("    image: ").append(effectiveImage).append("\n");
            sb.append("    container_name: dock-sim-").append(serviceName).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(instance.port()).append(":9090\"\n");
            sb.append("    environment:\n");
            sb.append("      MQTT_HOST: ").append(effectiveHost).append("\n");
            sb.append("      MQTT_PORT: ").append(effectivePort).append("\n");
            if (!effectiveUser.isEmpty()) {
                sb.append("      MQTT_USERNAME: ").append(quoteIfNeeded(effectiveUser)).append("\n");
            }
            if (!effectivePassword.isEmpty()) {
                sb.append("      MQTT_PASSWORD: ").append(quoteIfNeeded(effectivePassword)).append("\n");
            }
            sb.append("      SERVER_PORT: 9090\n");
            sb.append("      SIMULATOR_CONFIG_DIR: /app/config\n");
            sb.append("      SIMULATOR_MEDIA_MEDIADIR: /app/media\n");
            sb.append("      SIMULATOR_SN_UNIQUEENABLED: \"true\"\n");
            // 接入模式不写环境变量：实例容器启动后由控制台「启用」按清单下发
            // （/api/connect 写 device_mode + 遥控器身份，并持久化到各自的 config 卷）
            if (instance.latitude() != null) {
                sb.append("      SIMULATOR_LOCATION_LATITUDE: ").append(instance.latitude()).append("\n");
            }
            if (instance.longitude() != null) {
                sb.append("      SIMULATOR_LOCATION_LONGITUDE: ").append(instance.longitude()).append("\n");
            }
            if (instance.height() != null) {
                sb.append("      SIMULATOR_LOCATION_HEIGHT: ").append(instance.height()).append("\n");
            }
            sb.append("    extra_hosts:\n");
            sb.append("      - \"host.docker.internal:host-gateway\"\n");
            if (!effectiveNetwork.isEmpty()) {
                sb.append("    networks:\n");
                sb.append("      - simulator-net\n");
                sb.append("      - ").append(effectiveNetwork).append("\n");
            } else {
                sb.append("    networks:\n");
                sb.append("      - simulator-net\n");
            }
            sb.append("    volumes:\n");
            sb.append("      - simulator-config-").append(serviceName).append(":/app/config\n");
            sb.append("      - simulator-media:/app/media\n");
            sb.append("      - simulator-videos:/app/videos\n");
            sb.append("    restart: unless-stopped\n");
            sb.append("    healthcheck:\n");
            sb.append("      test: [\"CMD\", \"curl\", \"-f\", \"-s\", \"http://localhost:9090/api/health\"]\n");
            sb.append("      interval: 30s\n");
            sb.append("      timeout: 3s\n");
            sb.append("      retries: 3\n");
            sb.append("      start_period: 30s\n");
        }

        sb.append("\nvolumes:\n");
        for (int i = 0; i < instances.size(); i++) {
            FleetInstance instance = instances.get(i);
            String serviceName = serviceName(instance, i);
            sb.append("  simulator-config-").append(serviceName).append(":\n");
            sb.append("    name: dji-dock-simulator-config-").append(serviceName).append("\n");
        }
        sb.append("  simulator-media:\n");
        sb.append("    name: dji-dock-simulator-media\n");
        sb.append("  simulator-videos:\n");
        sb.append("    name: dji-dock-simulator-videos\n");

        sb.append("\nnetworks:\n");
        sb.append("  simulator-net:\n");
        sb.append("    driver: bridge\n");
        if (!effectiveNetwork.isEmpty()) {
            sb.append("  ").append(effectiveNetwork).append(":\n");
            sb.append("    external: true\n");
        }
        return sb.toString();
    }

    // ==================== HTTP 工具 ====================

    private JsonNode getJson(FleetInstance instance, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(instance.internalBaseUrl(selfPort) + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " " + path);
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode postJson(FleetInstance instance, String path, Object body) throws IOException, InterruptedException {
        return sendJson(instance, path, body, "POST");
    }

    /** PUT 请求（{@code /api/location} 等既有接口是 PUT 语义）。 */
    private JsonNode putJson(FleetInstance instance, String path, Object body) throws IOException, InterruptedException {
        return sendJson(instance, path, body, "PUT");
    }

    private JsonNode sendJson(FleetInstance instance, String path, Object body, String method)
            throws IOException, InterruptedException {
        String payload = objectMapper.writeValueAsString(body == null ? Map.of() : body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(instance.internalBaseUrl(selfPort) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " " + method + " " + path);
        }
        String text = response.body();
        return text == null || text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
    }

    // ==================== 内部工具 ====================

    private void persist(List<FleetInstance> instances) {
        store.save(new FleetConfig(FleetConfig.CURRENT_VERSION, List.copyOf(instances)));
    }

    private Optional<FleetInstance> find(FleetConfig config, String id) {
        if (id == null) {
            return Optional.empty();
        }
        return config.instances().stream().filter(item -> id.equals(item.id())).findFirst();
    }

    private static boolean sameEndpoint(FleetInstance a, FleetInstance b) {
        return a.port() == b.port()
                && a.host() != null && b.host() != null
                && a.host().trim().equalsIgnoreCase(b.host().trim());
    }

    /**
     * 归一化实例定义：补 id、裁剪空白、端口默认 9090、校验必填、校验接入模式。
     */
    private FleetInstance normalize(FleetInstance request, String forcedId) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String name = trimToNull(request.name());
        String host = trimToNull(request.host());
        if (name == null) {
            throw new IllegalArgumentException("机库名称不能为空");
        }
        if (host == null) {
            throw new IllegalArgumentException("控制台地址（host）不能为空");
        }
        int port = request.port() > 0 ? request.port() : 9090;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口非法: " + port);
        }
        if (request.latitude() != null && (request.latitude() < -90 || request.latitude() > 90)) {
            throw new IllegalArgumentException("纬度范围应为 -90 ~ 90");
        }
        if (request.longitude() != null && (request.longitude() < -180 || request.longitude() > 180)) {
            throw new IllegalArgumentException("经度范围应为 -180 ~ 180");
        }
        String id = forcedId != null ? forcedId : trimToNull(request.id());
        if (id == null) {
            id = "dock-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return new FleetInstance(id, name, host, port,
                trimToNull(request.dockSn()), trimToNull(request.droneSn()),
                trimToNull(request.dockType()), trimToNull(request.droneType()),
                request.latitude(), request.longitude(), request.height(),
                trimToNull(request.note()),
                request.enabled() == null ? Boolean.TRUE : request.enabled(),
                request.selfFlag() ? Boolean.TRUE : null,
                normalizeDeviceMode(request.deviceMode()),
                trimToNull(request.controllerType()), trimToNull(request.controllerSn()));
    }

    /**
     * 归一化接入模式：空值返回 null（表示沿用实例当前模式），非 DOCK/PILOT 明确拒绝。
     * <p>不做"非法值静默降级为 DOCK"：那会让用户以为登记成功，实际以机库模式上线。</p>
     */
    private static String normalizeDeviceMode(String deviceMode) {
        String value = trimToNull(deviceMode);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase();
        if (!"DOCK".equals(upper) && !"PILOT".equals(upper)) {
            throw new IllegalArgumentException("接入模式非法: " + deviceMode + "（仅支持 DOCK / PILOT）");
        }
        return upper;
    }

    private static FleetBatchResult.FleetActionResult ok(FleetInstance instance, String message) {
        return new FleetBatchResult.FleetActionResult(instance.id(), instance.name(), true, message);
    }

    private static FleetBatchResult.FleetActionResult fail(FleetInstance instance, String message) {
        return new FleetBatchResult.FleetActionResult(instance.id(), instance.name(), false, message);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message = message + " - " + e.getCause().getMessage();
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * 把 SDK 的数值型 modelKey（如 {@code 3-3-0}）映射为可读机型名（如 {@code Dock3}）。
     * <p>用 {@code shortName()} 而不是 {@code displayName()}：前者与控制台既有标签
     * （Dock1/Dock2/Dock3、M4TD 等）口径一致，后者是中文全称（如「大疆机场3」），
     * 放在表格里会与其他位置显示的值不一致。映射来源是 SDK 枚举本身，不维护静态机型表。</p>
     * <p>无法识别时回退原字符串，保证展示不丢信息。</p>
     */
    private static String modelName(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return null;
        }
        try {
            DockModel dock = DockModel.fromModelKey(modelKey);
            if (dock != null) {
                return dock.shortName();
            }
        } catch (RuntimeException ignored) {
            // 不是机场 modelKey，继续尝试飞行器
        }
        try {
            DroneModel drone = DroneModel.fromModelKey(modelKey);
            if (drone != null) {
                return drone.shortName();
            }
        } catch (RuntimeException ignored) {
            // 未识别的 modelKey 原样返回
        }
        return modelKey;
    }

    /**
     * 机型下拉选项（供机库登记表单使用）。
     *
     * @return {@code {dockTypes, droneTypes, controllerTypes, deviceModes}}，每项含
     *         value（枚举名）、label（简称，与页面其他位置口径一致）、displayName（中文全称）。
     *         {@code controllerTypes} 供「遥控器上云」模式选择网关机型；
     *         {@code deviceModes} 供表单切换接入模式（DOCK / PILOT）。
     */
    public Map<String, Object> modelOptions() {
        List<Map<String, String>> dockTypes = new ArrayList<>();
        for (DockModel model : DockModel.values()) {
            dockTypes.add(Map.of(
                    "value", model.name(),
                    "label", model.shortName(),
                    "displayName", model.displayName()));
        }
        List<Map<String, String>> droneTypes = new ArrayList<>();
        for (DroneModel model : DroneModel.values()) {
            droneTypes.add(Map.of(
                    "value", model.name(),
                    "label", model.shortName(),
                    "displayName", model.displayName(),
                    "modelKey", model.modelKey()));
        }
        List<Map<String, Object>> controllerTypes = new ArrayList<>();
        for (RcModel model : RcModel.values()) {
            List<String> droneKeys = new ArrayList<>();
            for (DroneModel drone : DroneModel.values()) {
                if (DeviceCompatibility.isCompatible(model, drone)) {
                    droneKeys.add(drone.name());
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", model.name());
            item.put("label", model.shortName());
            item.put("displayName", model.displayName());
            item.put("modelKey", model.modelKey());
            item.put("defaultSn", model.defaultSn());
            // 可搭配的飞行器：由 SDK 搭配表推导，前端据此联动下拉，不另存静态表
            item.put("droneKeys", droneKeys);
            controllerTypes.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dockTypes", dockTypes);
        result.put("droneTypes", droneTypes);
        result.put("controllerTypes", controllerTypes);
        result.put("deviceModes", List.of(
                Map.of("value", "DOCK", "label", "机库上云", "displayName", "机场作为网关（domain=3）"),
                Map.of("value", "PILOT", "label", "遥控器上云", "displayName", "遥控器作为网关（domain=2）")));
        return result;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.path(field);
        return !value.isMissingNode() && !value.isNull() && value.asBoolean(false);
    }

    private static Double number(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.asInt();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String quoteIfNeeded(String value) {
        return value.matches("[A-Za-z0-9_.:/-]+") ? value : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String serviceName(FleetInstance instance, int index) {
        String base = instance.name() == null ? "" : instance.name()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isEmpty() ? "simulator-" + (index + 1) : base;
    }

    private static String displayName(FleetInstance instance) {
        return instance.name() == null ? instance.id() : instance.name();
    }
}
