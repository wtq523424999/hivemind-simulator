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

import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机库群管理 REST API（多机库配置/管理/创建）。
 *
 * <p>本控制器只做"机库清单 + 跨实例操作"，不改变单实例既有行为：
 * 单实例的注册、开关机、OSD、航线等接口仍在 {@link SimulatorController}，
 * 页面原功能一律保留。这里新增的能力是：登记多个实例、聚合它们的状态、
 * 把操作批量代理到目标实例、按清单生成 docker compose 编排。</p>
 *
 * <p>约束（与 {@link SimulatorController} 一致）：业务错误返回 HTTP 200 +
 * {@code success=false} + {@code message}，不抛异常给前端。</p>
 */
@Tag(name = "机库群管理", description = "多机库实例登记、状态聚合、批量开关机/注册/上线、坐标下发、compose 编排生成")
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private static final Logger log = LoggerFactory.getLogger(FleetController.class);

    private final FleetService fleetService;
    private final MqttClientManager mqtt;
    private final ObjectMapper objectMapper;

    public FleetController(FleetService fleetService, MqttClientManager mqtt,
                           ObjectMapper objectMapper) {
        this.fleetService = fleetService;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
    }

    // ==================== 清单 ====================

    /**
     * 机库清单（不含实时状态）。
     * <p>实时状态见 {@link #statuses()}，两者分开是因为清单变更频率低、状态需要高频刷新。</p>
     */
    @GetMapping("/instances")
    public Map<String, Object> instances() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("configPath", fleetService.configPath());
        result.put("instances", fleetService.load().instances());
        return result;
    }

    /** 新增机库登记（仅写入清单，不创建容器）。 */
    @PostMapping("/instances")
    public Map<String, Object> create(@RequestBody FleetInstance request) {
        try {
            FleetInstance created = fleetService.create(request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("instance", created);
            return result;
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    /**
     * 新建机库并一键启动（页面「新建并启动」按钮）。
     *
     * <p>一次调用完成：登记清单 → 创建实例容器 → 回填容器地址 → 连接 MQTT → 注册上线。
     * 请求体在机库字段之外可带：</p>
     * <ul>
     *   <li>{@code autoProvision}（默认 true）：是否创建实例容器。
     *       未部署供给服务时该步自动跳过并说明原因，不影响清单登记；</li>
     *   <li>{@code autoRegister}（默认 true）：是否注册上线；</li>
     *   <li>{@code hostPort}：指定宿主机端口（不填由供给服务在允许范围内自动分配）；</li>
     *   <li>{@code mqttHost}/{@code mqttPort}/{@code mqttUsername}/{@code mqttPassword}、
     *       {@code organization_id}/{@code device_binding_code}：连接与注册参数。</li>
     * </ul>
     *
     * @return 全流程结果（含容器名、端口、逐步明细）
     */
    @PostMapping("/instances/provision")
    public Map<String, Object> provisionAndStart(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return failure("请求体不能为空");
        }
        FleetInstance request;
        try {
            request = objectMapper.convertValue(body, FleetInstance.class);
        } catch (IllegalArgumentException e) {
            return failure("请求体格式不正确: " + e.getMessage());
        }
        FleetProvisionResult result = fleetService.provisionAndStart(request, body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("message", result.message());
        response.put("failedStep", result.failedStep());
        response.put("id", result.id());
        response.put("containerName", result.containerName());
        response.put("hostPort", result.hostPort());
        response.put("instance", result.instance());
        response.put("steps", result.steps());
        return response;
    }

    /** 实例供给服务状态（前端据此决定是否展示「自动创建实例」选项）。 */
    @GetMapping("/provisioner")
    public Map<String, Object> provisioner() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.putAll(fleetService.provisionerStatus());
        return result;
    }

    /** 更新机库登记。 */
    @PutMapping("/instances/{id}")
    public Map<String, Object> update(@PathVariable("id") String id, @RequestBody FleetInstance request) {
        try {
            FleetInstance updated = fleetService.update(id, request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("instance", updated);
            return result;
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    /**
     * 移除机库登记。
     *
     * @param removeContainer 是否同时删除该实例容器（默认 false，只删清单）
     * @param removeVolume    删容器时是否一并删除其 config 卷（默认 true）
     */
    @DeleteMapping("/instances/{id}")
    public Map<String, Object> delete(
            @PathVariable("id") String id,
            @RequestParam(value = "removeContainer", required = false, defaultValue = "false") boolean removeContainer,
            @RequestParam(value = "removeVolume", required = false, defaultValue = "true") boolean removeVolume) {
        try {
            Map<String, Object> result = fleetService.deleteWithContainer(id, removeContainer, removeVolume);
            return result;
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    // ==================== 状态 ====================

    /**
     * 全部机库实时状态（并发拉取，单实例不可达不影响其他）。
     * <p>本实例（指向自身的条目）直接读 Bean，并补写 MQTT 连接状态。</p>
     */
    @GetMapping("/statuses")
    public Map<String, Object> statuses() {
        List<FleetInstanceStatus> statuses = fleetService.statuses().stream()
                .map(status -> fleetService.withLocalMqtt(status, mqtt.isConnected()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("statuses", statuses);
        result.put("total", statuses.size());
        result.put("online", statuses.stream()
                .filter(item -> Boolean.TRUE.equals(item.online())).count());
        result.put("reachable", statuses.stream().filter(FleetInstanceStatus::reachable).count());
        return result;
    }

    /** 机型下拉选项（机型展示名由 SDK 枚举推导，不维护静态表）。 */
    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.putAll(fleetService.modelOptions());
        return result;
    }

    /**
     * 按到目标点的距离给出候选机库排序（联调平台「智能选库」用）。
     *
     * <p>只返回模拟器侧可确证的信息：实时坐标、直线距离、是否可派遣及原因。
     * 完整的选库权重策略属于平台业务，模拟器不复制。</p>
     *
     * @param latitude  目标点纬度
     * @param longitude 目标点经度
     * @param limit     最多返回条数（默认全部）
     */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(value = "limit", required = false, defaultValue = "0") int limit) {
        if (latitude < -90 || latitude > 90) {
            return failure("纬度范围应为 -90 ~ 90");
        }
        if (longitude < -180 || longitude > 180) {
            return failure("经度范围应为 -180 ~ 180");
        }
        List<FleetService.FleetCandidate> candidates = fleetService.candidates(latitude, longitude, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("target", Map.of("latitude", latitude, "longitude", longitude));
        result.put("candidates", candidates);
        result.put("dispatchable", candidates.stream()
                .filter(FleetService.FleetCandidate::dispatchable).count());
        return result;
    }

    // ==================== 动作 ====================

    /**
     * 单实例动作。
     *
     * <p>支持的动作：{@code power_on}（开机）、{@code power_off}（关机下线）、
     * {@code connect}（连接 MQTT，可带 mqtt_host/username/password 等覆盖）、
     * {@code online}（上线，可带 skip_register）、{@code offline}（下线）、
     * {@code set_location}（改坐标，body 带 latitude/longitude/height）、
     * {@code apply_identity}（按清单下发 SN 与坐标）。</p>
     */
    @PostMapping("/instances/{id}/actions")
    public Map<String, Object> action(@PathVariable("id") String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        FleetInstance instance = fleetService.load().instances().stream()
                .filter(item -> id.equals(item.id()))
                .findFirst()
                .orElse(null);
        if (instance == null) {
            return failure("机库不存在: " + id);
        }
        String action = body == null ? null : String.valueOf(body.getOrDefault("action", ""));
        Map<String, Object> params = new LinkedHashMap<>(body == null ? Map.of() : body);
        params.remove("action");
        FleetBatchResult.FleetActionResult actionResult = fleetService.action(instance, action, params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", actionResult.success());
        result.put("message", actionResult.message());
        result.put("instance", instance.id());
        return result;
    }

    /**
     * 批量动作（默认作用于全部启用实例，可用 ids 限定范围）。
     *
     * <p>典型用法：新增几台机库后一次 {@code power_on} → {@code connect} → {@code online}，
     * 再 {@code apply_identity} 统一下发坐标。</p>
     */
    @PostMapping("/batch")
    public Map<String, Object> batch(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String action = String.valueOf(payload.getOrDefault("action", ""));
        if (action.isBlank()) {
            return failure("缺少 action 参数");
        }
        Map<String, Object> params = new LinkedHashMap<>(payload);
        params.remove("action");
        List<String> ids = readIds(payload.get("ids"));
        FleetBatchResult batchResult = fleetService.batch(action, params, ids);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", batchResult.failed() == 0);
        result.put("action", batchResult.action());
        result.put("total", batchResult.total());
        result.put("ok", batchResult.success());
        result.put("failed", batchResult.failed());
        result.put("results", batchResult.results());
        return result;
    }

    /**
     * 启用工作流：按「开机 → 连接 MQTT → 上线 → 下发坐标」顺序拉起机库。
     *
     * <p>与 {@link #batch} 的区别：批量动作是"同一动作并发到多台"，各步独立；
     * 本接口是"每台内部按顺序执行多步"，前一步失败即中止该台后续步骤，
     * 但继续处理其他机库。各步均幂等（已满足则跳过），可重复调用。</p>
     *
     * <p>请求体可带连接参数覆盖：{@code mqtt_host}/{@code mqtt_port}/
     * {@code mqtt_username}/{@code mqtt_password}/{@code organization_id}/
     * {@code device_binding_code}/{@code app_license}；可带 {@code skip_register}、
     * {@code ids} 限定范围。</p>
     */
    @PostMapping("/bringup")
    public Map<String, Object> bringup(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        List<String> ids = readIds(payload.get("ids"));
        List<FleetBringupResult> results = fleetService.bringup(payload, ids);
        long success = results.stream().filter(FleetBringupResult::success).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success == results.size());
        result.put("total", results.size());
        result.put("ok", success);
        result.put("failed", results.size() - success);
        result.put("results", results);
        return result;
    }

    // ==================== 清单导入导出 ====================

    /**
     * 导出机库清单（JSON，可直接作为 {@link #importConfig} 的输入）。
     *
     * <p>不含任何凭据：MQTT 账号密码属于各实例自身的 live-config，不在清单里。</p>
     */
    @GetMapping("/instances/export")
    public ResponseEntity<FleetConfig> exportConfig() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fleet.json\"")
                .body(fleetService.exportConfig());
    }

    /**
     * 导入机库清单。
     *
     * <p>{@code replace=true}（默认）整表替换；{@code replace=false} 按 id 合并。
     * 结构不合法或字段非法的条目会被拒绝并列入 {@code rejected}，其余正常导入。</p>
     */
    @PostMapping("/instances/import")
    public Map<String, Object> importConfig(
            @RequestBody FleetConfig config,
            @RequestParam(value = "replace", required = false, defaultValue = "true") boolean replace) {
        try {
            return fleetService.importConfig(config, replace);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    /**
     * 导出平台侧预置数据（{@code dock} + {@code device} 的 JSON 与 SQL）。
     *
     * <p>平台在 {@code airport_organization_bind} 阶段只允许已知网关绑定，
     * 因此每个模拟机库上线前必须先在平台侧存在对应记录。本接口把清单翻译成
     * 可直接调用的 JSON 与可重复执行的 SQL。</p>
     */
    @GetMapping("/platform-seed")
    public Map<String, Object> platformSeed(
            @RequestParam(value = "organizationId", required = false) String organizationId,
            @RequestParam(value = "brand", required = false) String brand) {
        return fleetService.exportPlatformSeed(organizationId, brand);
    }

    /**
     * 导出平台预置 SQL 文件（纯文本，便于直接保存执行）。
     */
    @GetMapping(value = "/platform-seed.sql", produces = "text/plain; charset=UTF-8")
    public ResponseEntity<String> platformSeedSql(
            @RequestParam(value = "organizationId", required = false) String organizationId,
            @RequestParam(value = "brand", required = false) String brand,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download) {
        Object sql = fleetService.exportPlatformSeed(organizationId, brand).get("sql");
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"));
        if (download) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"platform-seed.sql\"");
        }
        return builder.body(sql == null ? "" : String.valueOf(sql));
    }

    // ==================== Compose 编排 ====================

    /**
     * 按当前清单生成 docker compose 编排文本。
     *
     * <p>返回纯文本（{@code text/yaml}），前端可直接展示、复制或下载。
     * 镜像/ Broker /网络可通过查询参数覆盖，未提供时使用合理默认值。</p>
     */
    @GetMapping(value = "/compose", produces = "text/yaml; charset=UTF-8")
    public ResponseEntity<String> compose(
            @RequestParam(value = "image", required = false) String image,
            @RequestParam(value = "mqttHost", required = false) String mqttHost,
            @RequestParam(value = "mqttPort", required = false) Integer mqttPort,
            @RequestParam(value = "mqttUsername", required = false) String mqttUsername,
            @RequestParam(value = "mqttPassword", required = false) String mqttPassword,
            @RequestParam(value = "network", required = false) String network,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download) {
        String yaml = fleetService.renderCompose(image, mqttHost, mqttPort,
                mqttUsername, mqttPassword, network == null ? "" : network.trim());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml; charset=UTF-8"));
        if (download) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"docker-compose-fleet.yml\"");
        }
        return builder.body(yaml);
    }

    // ==================== 内部工具 ====================

    private static Map<String, Object> failure(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        log.warn("机库管理请求失败: {}", message);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readIds(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
