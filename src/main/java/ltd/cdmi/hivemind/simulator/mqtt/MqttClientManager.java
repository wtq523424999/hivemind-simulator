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

package ltd.cdmi.hivemind.simulator.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.MessageLogStore;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQTT 客户端管理器：连接 EMQX、订阅下行 topic、分发消息、提供发布方法。
 * <p>采用 Paho v3 客户端，开启自动重连。所有业务消息由 {@link MqttMessageListener} 监听器处理。</p>
 *
 * <h3>关键约束（详见 AGENTS.md §2.1 MQTT 连接）</h3>
 * <ul>
 *   <li><b>连接时机</b>：仅在用户点击「注册到第三方平台」后建立，Spring 启动时不自动连接；
 *       开机时若上次注册成功（前端 {@code localStorage.registered=true}）才尝试自动重连。</li>
 *   <li><b>连接超时</b>：3 秒。超时或失败不得阻塞调用方。</li>
 *   <li><b>关机断开</b>：设备关机时 MQTT 客户端必须断开，避免会话残留。</li>
 *   <li><b>失败分类</b>：地址错误返回 {@code -4}（提示「无法链接到第三方平台」）；
 *       凭证错误返回 {@code -5}（提示「第三方平台凭证有误」）。见 {@link DiagnosticCode}。</li>
 *   <li><b>地址规范化</b>：调用方可能传入带前缀的 URI，经 {@link #normalizeHost} 剥离
 *       {@code tcp://}/{@code ssl://}/{@code mqtt://} 前缀；容器内将 localhost/127.0.0.1
 *       自动映射为 {@code host.docker.internal}，使本地与 Docker 部署填写方式一致。</li>
 * </ul>
 */
@Component
public class MqttClientManager implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttClientManager.class);

    private final MqttProperties mqttProps;
    private final SimulatorProperties props;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;
    private final MessageLogStore messageLogStore;

    private volatile MqttClient client;

    /** 按 topic 注册的消息监听器列表（支持同一 topic 多监听器） */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MqttMessageListener>> listeners = new ConcurrentHashMap<>();

    /** 消息日志缓冲默认大小（当配置未指定时使用） */
    private static final int DEFAULT_MAX_LOG_SIZE = 2000;
    /** getLogs 返回的最大条数（仅返回最新 N 条，减少前端渲染压力） */
    private static final int MAX_RETURN_LOG_SIZE = 500;
    // 使用 ArrayDeque 而非 ArrayList：pollFirst() 是 O(1)，避免 ArrayList.remove(0) 的 O(n) 元素移动
    private final Deque<Map<String, Object>> messageLogs = new ArrayDeque<>();

    public MqttClientManager(MqttProperties mqttProps, SimulatorProperties props, ObjectMapper objectMapper, RuntimeConfig runtimeConfig, DockTopicSchema dockTopicSchema, MessageLogStore messageLogStore) {
        this.mqttProps = mqttProps;
        this.props = props;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        this.messageLogStore = messageLogStore;
    }

    /**
     * 剥离 MQTT host 携带的协议前缀（tcp:// 等，TC-MQTT-010）。
     * <p>REST API 为公共边界，调用方（前端或直接 HTTP 调用）可能传入带前缀的完整 URI；
     * 协议统一由 broker URI 拼接处指定，host 残留前缀会拼出 {@code tcp://tcp://...} 无效 URI。</p>
     */
    static String stripScheme(String host) {
        String h = String.valueOf(host).trim();
        for (String scheme : new String[]{"tcp://", "ssl://", "mqtt://"}) {
            if (h.startsWith(scheme)) {
                return h.substring(scheme.length());
            }
        }
        return h;
    }

    /** 检测是否运行在 Docker 容器内（/.dockerenv 是 Docker 容器标志性文件） */
    static boolean isRunningInContainer() {
        return java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"));
    }

    /**
     * 规范化 MQTT host（TC-MQTT-011）：容器内将 localhost/127.0.0.1 自动映射为 host.docker.internal。
     * <p>Docker 网络隔离使容器内 localhost 指向容器自身，宿主机 Broker 不可达；
     * 自动映射保证本地部署与 Docker 部署填写方式完全一致，用户无需感知容器网络。
     * 远程地址（第三方平台部署的 MQTT）不经任何改写，直连。</p>
     */
    static String normalizeHost(String host, boolean inContainer) {
        String h = stripScheme(host);
        if (inContainer && ("localhost".equals(h) || "127.0.0.1".equals(h))) {
            return "host.docker.internal";
        }
        return h;
    }

    static String normalizeHost(String host) {
        boolean inContainer = isRunningInContainer();
        String normalized = normalizeHost(host, inContainer);
        if (!normalized.equals(String.valueOf(host).trim())) {
            log.warn("MQTT host '{}' 在容器内指向容器自身，已自动映射为 '{}'（宿主机地址）", host, normalized);
        }
        return normalized;
    }

    /**
     * 使用 {@link RuntimeConfig} 当前配置建立 MQTT 连接（首次连接与重连共用）。
     * @return 诊断码：null=成功；{@link DiagnosticCode#PLATFORM_AUTH_FAILED}=凭证错误；{@link DiagnosticCode#PLATFORM_HOST_UNREACHABLE}=地址不可达
     */
    private synchronized DiagnosticCode doConnect() {
        try {
            String clientId = mqttProps.simulatorClientIdPrefix() + UUID.randomUUID().toString().substring(0, 8);
            String brokerUri = "tcp://" + normalizeHost(runtimeConfig.getMqttHost()) + ":" + runtimeConfig.getMqttPort();

            client = new MqttClient(brokerUri, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(runtimeConfig.getMqttUsername());
            options.setPassword(runtimeConfig.getMqttPassword().toCharArray());
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(3);
            options.setKeepAliveInterval(30);
            // 告知 broker 本客户端的遗嘱：设备下线（QoS 1）
            // DJI 下线通过 update_topo 的空 sub_devices 列表体现，method 字段必须为 update_topo。
            // 网关设备类型按当前接入模式取：机场上云为机场（domain=3），遥控器上云为遥控器（domain=2）。
            String willPayload = buildWillPayload();
            TopicSchema schema = currentTopicSchema();
            options.setWill(
                    schema.topic(schema.status(), runtimeConfig.getGatewaySn()),
                    willPayload.getBytes(StandardCharsets.UTF_8),
                    1, false);

            client.setCallback(this);
            client.connect(options);
            log.info("MQTT 客户端已启动，clientId={}, broker={}", clientId, brokerUri);
            return null;
        } catch (MqttException e) {
            DiagnosticCode code = classifyConnectFailure(e.getReasonCode());
            if (code == DiagnosticCode.PLATFORM_AUTH_FAILED) {
                log.error("MQTT 认证失败（账号或密码错误）: reasonCode={}, {}", e.getReasonCode(), e.getMessage());
            } else if (e.getReasonCode() == MqttException.REASON_CODE_CONNECTION_LOST) {
                // TCP 已建立但握手期断开：多为"发夹回环"网络路径问题（TC-MQTT-012），
                // broker 侧无连接记录，凭证正确也会失败——引导排查网络而非凭证
                log.error("MQTT 连接建立后被断开（TCP 可达但数据转发中断，同机 Docker 部署经宿主机 IP 回环访问会触发）: reasonCode={}, {}", e.getReasonCode(), e.getMessage());
            } else {
                log.error("MQTT 连接失败（无法连接到 broker）: reasonCode={}, {}", e.getReasonCode(), e.getMessage(), e);
            }
            return code;
        } catch (Exception e) {
            log.error("MQTT 连接初始化失败: {}", e.getMessage(), e);
            return DiagnosticCode.PLATFORM_HOST_UNREACHABLE;
        }
    }

    /**
     * 构造遗嘱报文（update_topo，空 sub_devices 表示设备下线）。
     * <p>网关设备类型取自当前网关型号：机场上云 domain=3，遥控器上云 domain=2。
     * 若固定写死机场类型，遥控器异常掉线时平台会收到一条"机场下线"，与已上线的遥控器拓扑不一致。</p>
     */
    String buildWillPayload() {
        var gatewayType = runtimeConfig.getGatewayType();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", String.valueOf(gatewayType.domain()));
        data.put("type", gatewayType.type());
        data.put("sub_type", gatewayType.subType());
        data.put("sub_devices", List.of());
        data.put("thing_version", runtimeConfig.getThingVersion());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", "update_topo");
        payload.put("data", data);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("遗嘱报文序列化失败，回退为机场类型: {}", e.getMessage());
            return "{\"method\":\"update_topo\",\"data\":{\"type\":3,\"sub_type\":0,\"sub_devices\":[],\"thing_version\":\""
                    + runtimeConfig.getThingVersion() + "\"}}";
        }
    }

    /**
     * 初始连接阶段的失败分类（TC-MQTT-012）。
     * <ul>
     *   <li>32104/32105：CONNACK 4(Bad User Name or Password)/5(Not Authorized) → 凭证错误</li>
     *   <li>32109 Connection lost：TCP 已建立但 MQTT 握手期连接被断开。实测（2026-08-20，
     *       Docker Desktop + 平台全家桶同机部署）：容器经宿主机 IP 访问端口映射到另一容器的
     *       "发夹回环"路径，TCP 可握手但数据转发被断开，broker 侧无任何连接记录——属网络
     *       路径问题而非凭证错误，归类 P-2 引导用户排查网络（同机 Docker 部署应直连容器网络）</li>
     *   <li>其余（如 32103 连接不上）→ 地址不可达</li>
     * </ul>
     * <p>仅用于 connect() 调用期间的异常；已建立连接后的掉线走 connectionLost 回调，不经过此方法。</p>
     */
    static DiagnosticCode classifyConnectFailure(int reasonCode) {
        if (reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                || reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED) {
            return DiagnosticCode.PLATFORM_AUTH_FAILED;
        }
        return DiagnosticCode.PLATFORM_HOST_UNREACHABLE;
    }

    /**
     * 使用新的连接配置重连：先关闭旧连接，再用 {@link RuntimeConfig} 当前值建立新连接。
     * <p>供 Web 控制台修改 MQTT 配置后调用。</p>
     * @return 诊断码：null=成功；{@link DiagnosticCode#PLATFORM_AUTH_FAILED}=凭证错误；{@link DiagnosticCode#PLATFORM_HOST_UNREACHABLE}=地址不可达
     */
    public synchronized DiagnosticCode reconnect() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (Exception e) {
            log.warn("关闭旧 MQTT 连接异常: {}", e.getMessage());
        }
        client = null;
        return doConnect();
    }

    /**
     * 主动断开 MQTT 连接（下线时调用）。
     */
    public synchronized void disconnect() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (Exception e) {
            log.warn("断开 MQTT 连接异常: {}", e.getMessage());
        }
        client = null;
    }

    @PreDestroy
    public void destroy() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
            if (client != null) {
                client.close();
            }
            log.info("MQTT 客户端已关闭");
        } catch (Exception e) {
            log.warn("关闭 MQTT 客户端异常: {}", e.getMessage());
        }
    }

    // ==================== 连接回调 ====================

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            log.info("MQTT 已重连，重新订阅下行 topic");
        } else {
            log.info("MQTT 首次连接成功");
        }
        subscribeDownTopics();
    }

    /**
     * 按当前网关身份订阅云→设备下行 topic（QoS 1）。
     *
     * <p>Pilot 模式网关为遥控器（controllerSn），Dock 模式网关为机场（dockSn）。
     * 同时订阅两种网关 SN：接入模式可在运行时切换，未激活的那一路不会有设备发布，
     * 但可保证切换后无需等待重连即可收到平台下发到新网关 SN 的指令。</p>
     *
     * @return 本次尝试订阅的 topic 列表（供调用方日志与测试断言）
     */
    private List<String> subscribeDownTopics() {
        if (client == null || !client.isConnected()) {
            log.debug("MQTT 未连接，跳过订阅");
            return List.of();
        }
        List<String> downTopics = computeDownTopics();
        for (String topic : downTopics) {
            try {
                subscribeTopic(topic);
                log.debug("已订阅: {}", topic);
            } catch (Exception e) {
                log.error("订阅失败: {} - {}", topic, e.getMessage());
            }
        }
        return downTopics;
    }

    /**
     * 计算当前网关身份下需要订阅的下行 topic 集合。
     *
     * <p>抽成独立方法（不依赖连接状态）便于单测断言"切换模式后订阅集确实变了"，
     * 而不需要真实 broker。</p>
     */
    List<String> computeDownTopics() {
        TopicSchema schema = currentTopicSchema();
        List<String> downTopics = new ArrayList<>();
        for (String gatewaySn : gatewaySnCandidates()) {
            downTopics.add(dockTopicSchema.topic(dockTopicSchema.services(), gatewaySn));
            downTopics.add(dockTopicSchema.topic(dockTopicSchema.propertySet(), gatewaySn));
            downTopics.add(dockTopicSchema.topic(dockTopicSchema.eventsReply(), gatewaySn));
            downTopics.add(dockTopicSchema.topic(dockTopicSchema.requestsReply(), gatewaySn));
            downTopics.add(schema.topic(schema.statusReply(), gatewaySn));
            downTopics.add(dockTopicSchema.topic(dockTopicSchema.drcDown(), gatewaySn));
        }
        return downTopics;
    }

    /**
     * 订阅单个 topic（QoS 1）。抽成独立方法便于测试观察订阅集，
     * 也把所有 Paho 订阅调用收敛到一处。
     */
    void subscribeTopic(String topic) throws MqttException {
        client.subscribe(topic, 1);
    }

    /**
     * 按当前网关身份重新订阅下行 topic（幂等，可在连接保持期间反复调用）。
     *
     * <p>使用场景：接入模式（Dock ↔ Pilot）或网关 SN 在<b>已连接状态</b>下发生变化。
     * 此时 MQTT 连接不会断开，{@link #connectComplete} 不会被触发，而订阅集是按旧 SN
     * 算出来的——平台下发到新 SN 的 {@code services} / {@code property/set} / {@code drc/down}
     * 等消息将无人接收。切换模式或改 SN 后必须调用本方法刷新订阅。</p>
     *
     * <p>为什么是"重订阅"而不是"先退订旧 topic 再订阅新 topic"：
     * MQTT subscribe 对同一 topic 是幂等的（Paho 按 topic 覆盖，不产生重复投递），
     * 而按历史集合退订需要保存状态，SN 多次切换时容易漏删、误删仍需要的 topic。
     * 保留机场 SN 的订阅也与 {@link #gatewaySnCandidates()} 的既有设计一致
     * （切回原模式时无需再次重订阅）。</p>
     *
     * @return 本次重新订阅的 topic 数量；MQTT 未连接时返回 0（连接建立时会自动订阅）
     */
    public int resubscribe() {
        if (!isConnected()) {
            log.debug("MQTT 未连接，无需重订阅（连接建立时会自动订阅）");
            return 0;
        }
        List<String> topics = subscribeDownTopics();
        log.info("网关身份已变化，按当前网关重新订阅下行 topic: {} 个（网关候选 {}）",
                topics.size(), gatewaySnCandidates());
        return topics.size();
    }

    /** 当前可能的网关 SN 集合（机场 + 遥控器），用于下行 topic 订阅。 */
    private List<String> gatewaySnCandidates() {
        List<String> sns = new ArrayList<>(2);
        sns.add(runtimeConfig.getGatewaySn());
        String dockSn = runtimeConfig.getDockSn();
        String controllerSn = runtimeConfig.getControllerSn();
        if (!sns.contains(dockSn)) {
            sns.add(dockSn);
        }
        if (!sns.contains(controllerSn)) {
            sns.add(controllerSn);
        }
        return sns;
    }

    /**
     * 获取当前模式对应的 TopicSchema。
     * <p>Pilot 模式使用 {@link PilotTopicSchema}（拓扑上行 thing/product/{sn}/status，
     * 回复下行 sys/product/{sn}/status_reply），其他模式使用 {@link DockTopicSchema}
     * （status/statusReply 均走 sys/product 通道）。
     * <p>osd/state/services/drc 等通道两种模式模板一致，统一用 {@link DockTopicSchema}。
     */
    private TopicSchema currentTopicSchema() {
        if (runtimeConfig.getDeviceMode() == DeviceMode.PILOT) {
            return new PilotTopicSchema();
        }
        return dockTopicSchema;
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT 连接断开，等待自动重连: {}", cause == null ? "unknown" : cause.getMessage());
    }

    // ==================== 消息分发回调 ====================

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.debug("收到消息 topic={}, payload={}", topic, payload);
        addLog("recv", topic, payload);

        // 先按 topic 精确匹配，未命中再回退到网关无关模板。
        // 两者用同一集合去重：同一条消息即使同时符合精确 topic 与某个别名，也只投递一次。
        Set<MqttMessageListener> delivered = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MqttMessageListener> topicListeners = listeners.get(topic);
        if (topicListeners != null) {
            for (MqttMessageListener listener : topicListeners) {
                deliver(listener, topic, payload, null, delivered);
            }
        }
        if (!belongsToThisDevice(topic)) {
            log.debug("忽略非本模拟器设备的 topic: {}", topic);
            return;
        }
        for (Map.Entry<String, CopyOnWriteArrayList<MqttMessageListener>> entry : listeners.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.indexOf('+') < 0 && pattern.indexOf('#') < 0) {
                continue;
            }
            if (!topicMatches(pattern, topic)) {
                continue;
            }
            for (MqttMessageListener listener : entry.getValue()) {
                deliver(listener, topic, payload, pattern, delivered);
            }
        }
    }

    private void deliver(MqttMessageListener listener, String topic, String payload,
                         String pattern, Set<MqttMessageListener> delivered) {
        if (!delivered.add(listener)) {
            return;
        }
        try {
            listener.onMessage(topic, payload);
        } catch (Throwable t) {
            // 捕获 Throwable：监听器在 Paho 回调线程执行，逃逸的 Error 会中断该线程并导致连接被判失败断开
            if (pattern == null) {
                log.error("监听器处理消息异常 topic={}: {}", topic, t.getMessage(), t);
            } else {
                log.error("监听器处理消息异常 topic={}, pattern={}: {}", topic, pattern, t.getMessage(), t);
            }
        }
    }

    /**
     * 通配回退分发前的归属校验：{@code thing/product/{sn}/...} 或 {@code sys/product/{sn}/...}
     * 的 sn 必须是本模拟器已知设备（网关机场、网关遥控器或飞行器）。
     * <p>否则一个实例会响应同一 broker 上其他设备（如另一台模拟器）的指令。</p>
     */
    private boolean belongsToThisDevice(String topic) {
        String[] parts = topic.split("/", -1);
        if (parts.length < 3
                || !("thing".equals(parts[0]) || "sys".equals(parts[0]))
                || !"product".equals(parts[1])) {
            return true;
        }
        String sn = parts[2];
        return sn.equals(runtimeConfig.getDockSn())
                || sn.equals(runtimeConfig.getControllerSn())
                || sn.equals(runtimeConfig.getDroneSn());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1/2 投递完成回调，无需处理
    }

    // ==================== 订阅注册 ====================

    /**
     * 注册消息监听器。topic 必须是完整 topic（非模板），订阅由本管理器统一管理。
     * <p>同时按"网关无关"形式额外登记一份（把产品 topic 中的设备 SN 段替换为 {@code +}）：
     * 接入模式（机场上云 / 遥控器上云）可在运行时切换，网关 SN 随之在 dockSn 与 controllerSn
     * 之间变化；只按固定 SN 登记会导致切换模式后平台下发到新网关 SN 的指令无人处理。</p>
     * @param topic 完整 topic
     * @param listener 监听器
     */
    public void addListener(String topic, MqttMessageListener listener) {
        addListenerOnce(topic, listener);
        String alias = gatewayAgnosticTopic(topic);
        if (alias != null) {
            addListenerOnce(alias, listener);
        }
        log.debug("已注册监听器 topic={}", topic);
    }

    /** 同一 topic 下同一监听器只登记一份，避免 SN 变化后重复注册导致重复处理 */
    private void addListenerOnce(String topic, MqttMessageListener listener) {
        CopyOnWriteArrayList<MqttMessageListener> list =
                listeners.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(listener)) {
            list.add(listener);
        }
    }

    /**
     * 把 {@code thing/product/{sn}/...} 或 {@code sys/product/{sn}/...} 的 SN 段替换为 {@code +}，
     * 作为同一监听器的网关无关别名键；非产品 topic 返回 null。
     */
    static String gatewayAgnosticTopic(String topic) {
        String[] parts = topic.split("/", -1);
        if (parts.length >= 3
                && ("thing".equals(parts[0]) || "sys".equals(parts[0]))
                && "product".equals(parts[1])
                && !SN_WILDCARD.equals(parts[2])) {
            parts[2] = SN_WILDCARD;
            return String.join("/", parts);
        }
        return null;
    }

    /** MQTT 单层通配符：匹配 topic 中的任意一段（网关 SN） */
    public static final String SN_WILDCARD = "+";

    /**
     * MQTT topic 通配匹配：支持 {@code +}（单层）与 {@code #}（多层，仅末尾）。
     * <p>仅用于监听器分发；订阅仍由 {@link #connectComplete} 按当前网关精确订阅。</p>
     */
    static boolean topicMatches(String pattern, String topic) {
        if (pattern.equals(topic)) {
            return true;
        }
        String[] p = pattern.split("/", -1);
        String[] t = topic.split("/", -1);
        for (int i = 0; i < p.length; i++) {
            if ("#".equals(p[i])) {
                return i == p.length - 1;
            }
            if (i >= t.length) {
                return false;
            }
            if (!"+".equals(p[i]) && !p[i].equals(t[i])) {
                return false;
            }
        }
        return p.length == t.length;
    }

    /**
     * 记录外部连接的消息流量日志（供 DRC 专用连接等复用，前端指令通讯窗口可见）。
     * @param direction 方向（recv/send）
     * @param topic 完整 topic
     * @param payload 消息内容
     */
    public void logTraffic(String direction, String topic, String payload) {
        addLog(direction, topic, payload);
    }

    // ==================== 发布方法 ====================

    /**
     * 发布消息到指定 topic（QoS 1）。
     * @param topic 完整 topic
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        publish(topic, payload, 1, false);
    }

    /**
     * 发布消息到指定 topic。
     * @param topic 完整 topic
     * @param payload 消息内容
     * @param qos 服务质量等级（0/1/2）
     * @param retained 是否保留消息
     */
    public void publish(String topic, String payload, int qos, boolean retained) {
        if (client == null || !client.isConnected()) {
            log.warn("MQTT 未连接，丢弃消息 topic={}", topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            client.publish(topic, message);
            addLog("send", topic, payload);
            log.debug("已发布 topic={}, payload={}", topic, payload);
        } catch (Exception e) {
            log.error("发布消息失败 topic={}: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * 发布 JSON 对象（自动序列化）。
     */
    public void publishJson(String topic, Object obj) {
        try {
            publish(topic, objectMapper.writeValueAsString(obj));
        } catch (Exception e) {
            log.error("JSON 序列化失败 topic={}: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * MQTT 客户端是否已连接。
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * 获取日志缓冲最大条数（从配置读取，未配置则使用默认值）。
     */
    private int getMaxLogSize() {
        if (props.log() != null && props.log().maxSize() > 0) {
            return props.log().maxSize();
        }
        return DEFAULT_MAX_LOG_SIZE;
    }

    /**
     * 记录消息日志（内存缓冲 + 本地文件持久化）。
     * <p>内存缓冲超过最大条数时自动清除历史；每条消息同时写入本地 JSON Lines 文件，
     * 供前端上拉加载历史消息和下载日志文件。
     */
    private void addLog(String direction, String topic, String payload) {
        long timestamp = System.currentTimeMillis();
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        String method = "";
        try {
            JsonNode node = objectMapper.readTree(payload);
            method = node.path("method").asText("");
        } catch (Exception e) {
            // payload 非 JSON 或解析失败，method 留空
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp);
        entry.put("time", timeStr);
        entry.put("direction", direction);
        entry.put("topic", topic);
        entry.put("method", method);
        entry.put("payload", payload);

        // 写入内存缓冲（供前端快速查询最近 N 条）
        synchronized (messageLogs) {
            if (messageLogs.size() >= getMaxLogSize()) {
                messageLogs.pollFirst();  // O(1)，替代 ArrayList.remove(0) 的 O(n)
            }
            messageLogs.addLast(entry);
        }

        // 持久化到本地文件（供前端上拉加载历史 + 下载）
        messageLogStore.append(direction, topic, method, payload, timestamp);
    }

    /**
     * 从本地文件查询历史消息日志（分页）。
     * <p>返回 timestamp &lt; beforeTime 的最近 limit 条消息（正序：旧→新）。
     *
     * @param beforeTime 时间戳分界点（毫秒），null 表示从最新开始
     * @param limit      返回条数
     * @return 消息日志列表（正序）
     */
    public List<Map<String, Object>> queryHistory(Long beforeTime, int limit) {
        return messageLogStore.queryHistory(beforeTime, limit);
    }

    /**
     * 获取 {@link MessageLogStore} 实例，供 Controller 调用下载/列表功能。
     */
    public MessageLogStore getMessageLogStore() {
        return messageLogStore;
    }

    /**
     * 获取消息日志列表（供 Web 控制台查询）。
     * 仅返回最新 MAX_RETURN_LOG_SIZE 条，减少前端渲染压力。
     */
    public List<Map<String, Object>> getLogs() {
        synchronized (messageLogs) {
            if (messageLogs.size() <= MAX_RETURN_LOG_SIZE) {
                return new ArrayList<>(messageLogs);
            }
            // 从尾部倒序取最新 N 条，再反转为正序（旧→新）
            Iterator<Map<String, Object>> it = ((ArrayDeque<Map<String, Object>>) messageLogs).descendingIterator();
            List<Map<String, Object>> result = new ArrayList<>(MAX_RETURN_LOG_SIZE);
            while (it.hasNext() && result.size() < MAX_RETURN_LOG_SIZE) {
                result.add(it.next());
            }
            Collections.reverse(result);
            return result;
        }
    }

    /**
     * 清空消息日志。
     */
    public void clearLogs() {
        synchronized (messageLogs) {
            messageLogs.clear();
        }
    }

    /**
     * 消息监听器函数式接口。
     */
    @FunctionalInterface
    public interface MqttMessageListener {
        /** 收到消息时回调 */
        void onMessage(String topic, String payload);
    }
}
