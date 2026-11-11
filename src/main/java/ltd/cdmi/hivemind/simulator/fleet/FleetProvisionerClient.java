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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机库实例供给服务的客户端（调用 provisioner 边车）。
 *
 * <p>为什么需要边车：网页要能真正拉起新实例，就必须有进程能访问 Docker Engine。
 * 模拟器主容器刻意不挂载 Docker socket（否则一个 Web 应用就能操作宿主机）。
 * 容器创建能力被隔离在独立边车里，主容器只通过 HTTP 调用它，
 * 且只能传结构化字段，无法执行任意 Docker 命令。</p>
 *
 * <p>未配置 {@code simulator.fleet.provisioner-url} 时本客户端为"未启用"状态：
 * 新增机库仍只登记清单，不创建容器，行为与之前一致。这样不部署边车的环境完全不受影响。</p>
 */
@Component
public class FleetProvisionerClient {

    private static final Logger log = LoggerFactory.getLogger(FleetProvisionerClient.class);

    /** 容器创建包含镜像启动与健康检查等待，需要较长超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public FleetProvisionerClient(
            @Value("${simulator.fleet.provisioner-url:}") String provisionerUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = provisionerUrl == null ? "" : provisionerUrl.trim();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        if (enabled()) {
            log.info("机库实例供给服务已配置: {}", baseUrl);
        } else {
            log.info("机库实例供给服务未配置（simulator.fleet.provisioner-url 为空），"
                    + "新增机库将只登记清单、不创建容器");
        }
    }

    /** 是否启用了自动创建实例的能力。 */
    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    /** 供给服务是否可达（用于前端提示"能否一键新建实例"）。 */
    public boolean reachable() {
        if (!enabled()) {
            return false;
        }
        try {
            JsonNode reply = send("GET", "/health", null, QUERY_TIMEOUT);
            return "UP".equalsIgnoreCase(text(reply, "status"));
        } catch (Exception e) {
            log.debug("机库实例供给服务不可达: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建一个模拟器实例。
     *
     * @param name      实例名（小写字母/数字/中划线，作为容器名与卷名后缀）
     * @param hostPort  宿主机端口（null 表示由供给服务自动分配）
     * @param latitude  机场纬度（可空）
     * @param longitude 机场经度（可空）
     * @param height    机场海拔（可空）
     * @param dockSn    固定机场 SN（可空，空则启用唯一 SN 模式）
     * @param droneSn   固定飞行器 SN（可空）
     * @param mqtt      额外 MQTT 参数（host/port/username/password，可空）
     * @return 创建结果
     * @throws ProvisionException 创建失败（含服务端返回的具体原因）
     */
    public ProvisionResult create(String name, Integer hostPort,
                                  Double latitude, Double longitude, Double height,
                                  String dockSn, String droneSn,
                                  Map<String, Object> mqtt) {
        return create(name, hostPort, latitude, longitude, height, dockSn, droneSn, mqtt, null, null, null);
    }

    /**
     * 创建一个模拟器实例（带接入模式与网关机型）。
     *
     * <p>接入模式通过容器环境变量注入：容器首次启动即是正确的设备身份，
     * 不依赖「创建后再 connect 一次」。这对无人值守场景很重要——
     * 否则任一实例在 connect 之前被重启，都会退回 DOCK 并以机场身份上线。</p>
     *
     * @param deviceMode     DOCK / PILOT（可空，空表示 DOCK）
     * @param controllerType 遥控器机型枚举名（PILOT 生效，可空）
     * @param controllerSn   固定遥控器 SN（PILOT 生效，可空）
     */
    public ProvisionResult create(String name, Integer hostPort,
                                  Double latitude, Double longitude, Double height,
                                  String dockSn, String droneSn,
                                  Map<String, Object> mqtt,
                                  String deviceMode, String controllerType, String controllerSn) {
        requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (hostPort != null) {
            body.put("hostPort", hostPort);
        }
        if (latitude != null) {
            body.put("latitude", latitude);
        }
        if (longitude != null) {
            body.put("longitude", longitude);
        }
        if (height != null) {
            body.put("height", height);
        }
        if (dockSn != null && !dockSn.isBlank()) {
            body.put("dockSn", dockSn);
        }
        if (droneSn != null && !droneSn.isBlank()) {
            body.put("droneSn", droneSn);
        }
        if (deviceMode != null && !deviceMode.isBlank()) {
            body.put("deviceMode", deviceMode.trim().toUpperCase());
        }
        if (controllerType != null && !controllerType.isBlank()) {
            body.put("controllerType", controllerType.trim());
        }
        if (controllerSn != null && !controllerSn.isBlank()) {
            body.put("controllerSn", controllerSn);
        }
        if (mqtt != null) {
            body.putAll(selectMqttKeys(mqtt));
        }
        try {
            JsonNode reply = send("POST", "/create", body, CREATE_TIMEOUT);
            return new ProvisionResult(
                    true,
                    text(reply, "containerName"),
                    intValue(reply, "hostPort"),
                    boolValue(reply, "created"),
                    boolValue(reply, "ready"),
                    text(reply, "message"));
        } catch (ProvisionException e) {
            throw e;
        } catch (Exception e) {
            throw new ProvisionException("创建实例失败: " + describe(e));
        }
    }

    /**
     * 删除一个由控制台创建的实例容器。
     *
     * @param name         实例名
     * @param removeVolume 是否同时删除该实例的 config 卷
     */
    public boolean remove(String name, boolean removeVolume) {
        if (!enabled()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("removeVolume", removeVolume);
        try {
            JsonNode reply = send("POST", "/remove", body, QUERY_TIMEOUT);
            return boolValue(reply, "removed");
        } catch (Exception e) {
            log.warn("删除实例容器失败: name={}, reason={}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 查询实例容器是否存在。
     *
     * @return {@code {exists, running, hostPort, status}}；服务未启用时 exists=false
     */
    public Map<String, Object> inspect(String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!enabled()) {
            result.put("exists", false);
            return result;
        }
        try {
            JsonNode reply = send("GET", "/inspect?name=" + encode(name), null, QUERY_TIMEOUT);
            result.put("exists", boolValue(reply, "exists"));
            result.put("running", boolValue(reply, "running"));
            result.put("hostPort", intValue(reply, "hostPort"));
            result.put("status", text(reply, "status"));
        } catch (Exception e) {
            result.put("exists", false);
            result.put("error", describe(e));
        }
        return result;
    }

    // ==================== 内部实现 ====================

    private JsonNode send(String method, String path, Object body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout);
        if (body == null) {
            builder = builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            String payload = objectMapper.writeValueAsString(body);
            builder = builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode parsed = response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
        if (response.statusCode() >= 400) {
            String message = text(parsed, "message");
            throw new ProvisionException(message == null || message.isBlank()
                    ? "供给服务返回 HTTP " + response.statusCode()
                    : message);
        }
        if (!parsed.path("success").asBoolean(true)) {
            String message = text(parsed, "message");
            throw new ProvisionException(message == null ? "供给服务返回失败" : message);
        }
        return parsed;
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ProvisionException(
                    "未配置机库实例供给服务（simulator.fleet.provisioner-url），无法自动创建实例");
        }
    }

    private static Map<String, Object> selectMqttKeys(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : new String[]{"mqttHost", "mqttPort", "mqttUsername", "mqttPassword"}) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer intValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.asInt();
    }

    private static boolean boolValue(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.path(field);
        return !value.isMissingNode() && !value.isNull() && value.asBoolean(false);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message = message + " - " + e.getCause().getMessage();
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * 实例创建结果。
     *
     * @param containerName 容器名
     * @param hostPort      宿主机映射端口
     * @param created       true=本次新建；false=复用已存在的同名容器
     * @param ready         健康检查是否通过
     * @param message       结论摘要
     */
    public record ProvisionResult(
            boolean success,
            String containerName,
            Integer hostPort,
            boolean created,
            boolean ready,
            String message
    ) {
    }

    /** 实例供给失败（携带服务端给出的具体原因，便于直接展示给用户）。 */
    public static class ProvisionException extends RuntimeException {
        public ProvisionException(String message) {
            super(message);
        }
    }
}
