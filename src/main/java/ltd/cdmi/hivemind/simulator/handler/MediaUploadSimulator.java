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

package ltd.cdmi.hivemind.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.protocol.envelope.EventEnvelope;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.EventMethod;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.media.CaptureSnapshot;
import ltd.cdmi.hivemind.simulator.media.MediaUploader;
import ltd.cdmi.hivemind.simulator.media.StorageConfig;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 媒体管理模拟器。
 * <p>处理 DJI Cloud API「媒体管理」协议（三 Dock 一致，无差异）：
 * <ul>
 *   <li>Event 上行：file_upload_callback（文件上传结果，need_reply=1，等待 events_reply）、
 *       highest_priority_upload_flighttask_media（优先级上报，need_reply=1）</li>
 *   <li>Service 下行：upload_flighttask_media_prioritize（调整最高优先级）→ services_reply result=0</li>
 *   <li>Requests 上行：storage_config_get（获取 STS 临时凭证）→ 解析 bucket/credentials/endpoint/object_key_prefix</li>
 *   <li>S3 文件上传（非 MQTT）：使用 STS 凭证通过 {@link MediaUploader} 上传本地文件到对象存储</li>
 * </ul>
 * <p>降级策略：media-dir 未配置或 STS 凭证获取失败时跳过文件上传，仅发 file_upload_callback（元数据上报）。
 * <p>详见 DJI Cloud API
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html">媒体管理（Dock3）</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/file.html">Dock2</a>、
 * <a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/file.html">Dock1</a>。
 */
@Component
public class MediaUploadSimulator {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadSimulator.class);

    /** 等待云端 events_reply 超时时间（秒），超时不阻塞后续上传 */
    private static final long EVENT_REPLY_TIMEOUT_SECONDS = 10;

    /** 拍摄时间格式：ISO 8601（官方 file_upload_callback metadata.create_time 定义）；样例为空格分隔 */
    private static final DateTimeFormatter CAPTURE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SimulatorProperties props;
    private final MqttClientManager mqtt;
    private final ObjectMapper objectMapper;
    private final DockOnlineService onlineService;
    private final ServiceCommandHandler commandHandler;
    private final MediaUploader mediaUploader;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    /** 已上报的媒体文件列表（供 Web 控制台展示） */
    private final List<Map<String, Object>> uploadedFiles = new CopyOnWriteArrayList<>();
    /** tid → CompletableFuture，用于等待 events_reply（need_reply=1 的事件） */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingEventReplies = new ConcurrentHashMap<>();
    /** 当前最高优先级 flight_id（upload_flighttask_media_prioritize 设置） */
    private volatile String priorityFlightId;

    /** 已注册 events_reply 监听器的 dockSn（dockSn 变化时需重新注册） */
    private volatile String registeredReplyDockSn;

    public MediaUploadSimulator(SimulatorProperties props, MqttClientManager mqtt,
                                ObjectMapper objectMapper, DockOnlineService onlineService,
                                ServiceCommandHandler commandHandler,
                                MediaUploader mediaUploader, RuntimeConfig runtimeConfig,
                                DockTopicSchema dockTopicSchema) {
        this.props = props;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
        this.onlineService = onlineService;
        this.commandHandler = commandHandler;
        this.mediaUploader = mediaUploader;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
        ensureReplyListeners();
    }

    /**
     * 确保 events_reply 监听器已注册（dockSn 变化时重新注册）。
     * <p>切换 Dock 类型时 {@link RuntimeConfig#setDockType} 会自动更新 dockSn，
     * 此方法确保监听器始终绑定当前 dockSn 对应的 topic。</p>
     * <p>模式与 {@link DockOnlineService#ensureReplyListeners} 一致。</p>
     */
    private void ensureReplyListeners() {
        String dockSn = runtimeConfig.getGatewaySn();
        if (dockSn.equals(registeredReplyDockSn)) {
            return;
        }
        mqtt.addListener(dockTopicSchema.topic(dockTopicSchema.eventsReply(), runtimeConfig.getGatewaySn()), this::handleEventReply);
        registeredReplyDockSn = dockSn;
        log.info("MediaUploadSimulator 已注册 events_reply 监听器: dockSn={}", dockSn);
    }

    @PostConstruct
    public void init() {
        commandHandler.setMediaHandler(this::handleMediaCommand);
        log.info("MediaUploadSimulator 已注册媒体命令处理器");
    }

    /**
     * 处理 events_reply：按 tid 匹配并完成 pending future。
     */
    private void handleEventReply(String topic, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tid = node.path("tid").asText();
            CompletableFuture<JsonNode> future = pendingEventReplies.remove(tid);
            if (future != null) {
                future.complete(node);
                log.debug("events_reply 匹配成功 tid={}, method={}", tid, node.path("method").asText());
            }
        } catch (Exception e) {
            log.error("解析 events_reply 失败: {}", e.getMessage(), e);
        }
    }

    // ==================== Service 指令处理 ====================

    /**
     * 统一路由媒体相关 services 命令（由 ServiceCommandHandler 调用）。
     * @param method 指令方法名
     * @param data   指令 data
     * @return services_reply 的 output（含 result 字段）
     */
    public Map<String, Object> handleMediaCommand(String method, JsonNode data) {
        log.info("处理媒体命令: method={}", method);

        if (ServiceMethod.UPLOAD_FLIGHTTASK_MEDIA_PRIORITIZE.methodName().equals(method)) {
            return handlePrioritize(data);
        }
        return Map.of("result", 0);
    }

    /**
     * 调整上传文件为最高优先级：记录 flight_id，回 result=0。
     * <p>核实依据：[Dock3 media.html] Service upload_flighttask_media_prioritize，Data 含 flight_id。</p>
     */
    private Map<String, Object> handlePrioritize(JsonNode data) {
        if (data != null) {
            String flightId = data.path("flight_id").asText();
            this.priorityFlightId = flightId;
            log.info("媒体上传优先级已调整: flightId={}", flightId);
        }
        return Map.of("result", 0);
    }

    // ==================== 媒体上传流程 ====================

    /**
     * 模拟媒体上传流程（手动触发/无真实拍摄场景）。
     * <p>时序：storage_config_get → highest_priority_upload_flighttask_media → 逐个上传文件 + file_upload_callback</p>
     * <p>降级策略：media-dir 未配置或 STS 凭证获取失败时跳过文件上传，仅发 file_upload_callback（元数据上报）。</p>
     *
     * @param flightId  任务 ID
     * @param fileCount 模拟生成的文件数量
     */
    public void simulateMediaUpload(String flightId, int fileCount) {
        List<CaptureSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            snapshots.add(defaultSnapshot(i));
        }
        // 手动调试触发：保留既有语义（上传失败也上报元数据，便于协议联调）
        simulateMediaUpload(flightId, snapshots, false);
    }

    /**
     * 按航点拍照动作上传媒体（严格对齐真机行为）。
     * <p>对齐 DJI 真机时序：{@code storage_config_get} 取临时凭证 → {@code highest_priority_upload_flighttask_media}
     * → 每个文件用 S3 兼容协议上传到对象存储 → 上传成功后逐条上报 {@code file_upload_callback}
     * （need_reply=1，等待 {@code events_reply}）。
     * <p>每条回调的 metadata 取自该张照片拍摄瞬间的遥测（{@link CaptureSnapshot}），
     * 而非任务结束时的位置。
     * <p>真机不会为未落盘的文件发回调，因此单个文件上传失败时跳过该文件的回调，
     * 避免平台按不存在的 object_key 取图 404。
     *
     * @param flightId  任务 ID
     * @param snapshots 每张照片的拍摄快照（数量 = takePhoto 动作数）
     */
    public void simulateMediaUpload(String flightId, List<CaptureSnapshot> snapshots) {
        simulateMediaUpload(flightId, snapshots, true);
    }

    /**
     * 媒体上传核心流程。
     *
     * @param strictUpload true=上传失败不上报该文件回调（真机行为）；
     *                     false=上传失败仍上报元数据（手动调试降级语义）
     */
    public void simulateMediaUpload(String flightId, List<CaptureSnapshot> snapshots, boolean strictUpload) {
        if (snapshots == null || snapshots.isEmpty()) {
            log.info("无拍照动作，跳过媒体上传: flightId={}", flightId);
            return;
        }
        log.info("开始媒体上传: flightId={}, photos={}", flightId, snapshots.size());

        // 1. 发 storage_config_get 请求获取 STS 凭证
        StorageConfig storageConfig = fetchStorageConfig();
        boolean canUpload = storageConfig != null && storageConfig.isValid();
        if (!canUpload) {
            log.warn("STS 凭证获取失败或无效，降级为仅元数据上报（虚构 object_key）");
        }

        // 2. 从 media-dir 读取本地照片素材（作为拍摄生成的图片内容）
        //    用户自己放入/上传的照片优先；仅有预置样例时退回样例；都没有则用 JAR 内置示例图。
        //    只取图片扩展名——目录里的视频不是照片，不能作为 takePhoto 的产物上传。
        List<Path> localFiles = selectPhotoSources();
        boolean hasRealFiles = !localFiles.isEmpty();
        if (hasRealFiles) {
            log.info("从 media-dir 读取到 {} 张照片素材: {}", localFiles.size(), runtimeConfig.getMediaDir());
        } else {
            log.info("media-dir 未配置或无照片，使用内置示例照片");
        }

        // 3. 上报 highest_priority_upload_flighttask_media（等待 events_reply）
        publishHighestPriority(flightId);

        // 4. 逐张上传并上报 file_upload_callback
        String objectKeyPrefix = (canUpload && storageConfig.objectKeyPrefix() != null
                && !storageConfig.objectKeyPrefix().isBlank())
                ? storageConfig.objectKeyPrefix() : "sim";
        int uploadedCount = 0;
        for (int i = 0; i < snapshots.size(); i++) {
            CaptureSnapshot snapshot = snapshots.get(i);
            String fileName = photoFileName(flightId, i, snapshot.fileSuffix());
            String objectKey = objectKeyPrefix + "/" + flightId + "/" + fileName;

            boolean uploaded;
            if (canUpload) {
                Path source = hasRealFiles ? localFiles.get(i % localFiles.size()) : null;
                boolean temporary = source == null;
                try {
                    if (temporary) {
                        source = mediaUploader.samplePhoto(i);
                    }
                    uploaded = mediaUploader.upload(source, storageConfig, objectKey);
                } catch (Exception e) {
                    uploaded = false;
                    log.warn("构造上传源失败: flightId={}, file={} - {}", flightId, fileName, e.getMessage());
                } finally {
                    if (temporary && source != null) {
                        try {
                            Files.deleteIfExists(source);
                        } catch (Exception ignored) {
                            // temp file cleanup failure must not abort the upload batch
                        }
                    }
                }
                if (uploaded) {
                    uploadedCount++;
                }
            } else {
                uploaded = false;
            }

            if (strictUpload && canUpload && !uploaded) {
                // 真机只有文件成功写入对象存储后才回调；失败时后续按优先级重试，不上报
                log.warn("文件上传失败，跳过该文件的 file_upload_callback: flightId={}, file={}",
                        flightId, fileName);
                continue;
            }

            publishFileUploadCallback(flightId, fileName, objectKey, snapshot);
            uploadedFiles.add(Map.of(
                    "flight_id", flightId,
                    "name", fileName,
                    "object_key", objectKey,
                    "uploaded", uploaded,
                    "upload_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ));
        }
        log.info("媒体上传完成: flightId={}, 已上报 {} 个文件, 实际上传 {} 个文件",
                flightId, snapshots.size(), uploadedCount);
    }

    /**
     * 选出可用的照片素材：用户照片 > 预置样例（仅当目录里没有用户照片时）。
     * <p>只返回图片扩展名文件；视频文件被排除，因为 takePhoto 产出的是照片。
     * <p>优先用户照片是为了让用户上传的真实图片真正生效：预置样例始终在目录里，
     * 若不分优先级，按文件名排序后 {@code sample-photo.jpg} 很可能排在用户文件之后，
     * 循环取用时仍会取到样例图。
     *
     * @return 照片文件列表；无可用照片时返回空列表（调用方回退到内置示例图）
     */
    private List<Path> selectPhotoSources() {
        String mediaDir = runtimeConfig.getMediaDir();
        List<Path> userPhotos = mediaUploader.listUserPhotoFiles(mediaDir);
        if (!userPhotos.isEmpty()) {
            return userPhotos;
        }
        return mediaUploader.listPhotoFiles(mediaDir);
    }

    /**
     * 文件名对齐真机命名习惯：{@code DJI_<seq>_<fileSuffix>.JPG}。
     * <p>WPML {@code fileSuffix} 会附加到媒体文件名后（官方 takePhoto 文档：
     * "为生成媒体文件命名时将额外附带该后缀"）。
     */
    private String photoFileName(String flightId, int index, String fileSuffix) {
        String suffix = fileSuffix == null ? "" : fileSuffix.trim();
        String base = String.format("DJI_%04d", index + 1);
        return suffix.isEmpty() ? base + ".JPG" : base + "_" + suffix + ".JPG";
    }

    /** 无真实拍摄场景（手动触发）使用的默认快照，取当前运行时配置。 */
    private CaptureSnapshot defaultSnapshot(int index) {
        return new CaptureSnapshot(
                runtimeConfig.getLocationLatitude(),
                runtimeConfig.getLocationLongitude(),
                runtimeConfig.getLocationHeight() + 50,
                50.0,
                index * 30.0,
                LocalDateTime.now().format(CAPTURE_TIME_FORMAT),
                "",
                "0");
    }

    /**
     * 发送 storage_config_get 请求并解析 STS 凭证。
     * @return StorageConfig，超时或解析失败返回 null
     */
    private StorageConfig fetchStorageConfig() {
        JsonNode configReply = onlineService.sendRequest("storage_config_get", Map.of("module", 0));
        StorageConfig config = StorageConfig.fromReply(configReply);
        if (config != null && config.isValid()) {
            log.info("获取存储凭证成功: bucket={}, endpoint={}, provider={}, object_key_prefix={}",
                    config.bucket(), config.endpoint(), config.provider(), config.objectKeyPrefix());
        } else {
            log.warn("获取存储凭证失败或无效，降级为仅元数据上报");
        }
        return config;
    }

    /**
     * 发布 highest_priority_upload_flighttask_media 事件（need_reply=1，等待 events_reply）。
     * <p>核实依据：[Dock3 media.html] Event，Data 含 flight_id。</p>
     */
    private void publishHighestPriority(String flightId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flight_id", flightId);
        publishEventAndWaitReply(EventMethod.HIGHEST_PRIORITY_UPLOAD_FLIGHTTASK_MEDIA, data);
    }

    /**
     * 发布 file_upload_callback 事件（need_reply=1，等待 events_reply）。
     * <p>metadata 全部取自该张照片的拍摄快照（{@link CaptureSnapshot}），与真机一致；
     * {@code payload_model_key} 使用负载枚举（相机），不是飞行器型号。
     *
     * @param flightId   任务 ID
     * @param fileName   文件名
     * @param objectKey  对象存储 Key（与实际上传使用的 Key 完全一致）
     * @param snapshot   拍摄瞬间的遥测快照
     */
    private void publishFileUploadCallback(String flightId, String fileName, String objectKey,
                                           CaptureSnapshot snapshot) {
        // ext 扩展信息（官方字段：flight_id / drone_model_key / payload_model_key / is_original）
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("drone_model_key", runtimeConfig.getDroneType().modelKey());
        ext.put("flight_id", flightId);
        ext.put("is_original", true);
        ext.put("payload_model_key", payloadModelKey());

        // metadata 媒体元数据（官方字段：absolute_altitude / create_time / gimbal_yaw_degree
        // / relative_altitude / shoot_position）
        Map<String, Object> shootPosition = new LinkedHashMap<>();
        shootPosition.put("lat", snapshot.latitude());
        shootPosition.put("lng", snapshot.longitude());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("absolute_altitude", snapshot.absoluteAltitude());
        metadata.put("create_time", snapshot.captureTimeIso());
        metadata.put("gimbal_yaw_degree", String.valueOf(snapshot.gimbalYawDegree()));
        metadata.put("relative_altitude", snapshot.relativeAltitude());
        metadata.put("shoot_position", shootPosition);

        // file 文件信息
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("cloud_to_cloud_id", "DEFAULT");
        file.put("ext", ext);
        file.put("metadata", metadata);
        file.put("name", fileName);
        file.put("object_key", objectKey);
        file.put("path", flightId);

        // 完整事件 data
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", file);

        publishEventAndWaitReply(EventMethod.FILE_UPLOAD_CALLBACK, data);
        log.info("已上报媒体文件: flightId={}, file={}, object_key={}", flightId, fileName, objectKey);
    }

    /**
     * 当前负载（相机）的枚举值，用于 {@code ext.payload_model_key}。
     * <p>官方文档该字段是"负载产品枚举值"（如 {@code 0-67-0}），不是飞行器型号。
     * 平台若配置了所选负载则用它，否则按机型默认主相机解析；两者都缺失时回退空串，
     * 避免把飞行器型号错填为负载型号。
     */
    private String payloadModelKey() {
        PayloadType payload = runtimeConfig.getSelectedPayload();
        if (payload == null) {
            payload = DefaultCameraResolver.defaultCameraFor(runtimeConfig.getDroneType());
        }
        return payload == null ? "" : payload.cameraIndex();
    }

    /**
     * 发布事件并等待 events_reply（need_reply=1）。
     * <p>用 tid 关联 CompletableFuture，超时不阻塞流程（warn 日志后继续）。</p>
     * @param method 事件方法枚举
     * @param data 事件 data
     */
    private void publishEventAndWaitReply(EventMethod method, Map<String, Object> data) {
        ensureReplyListeners();
        String tid = UUID.randomUUID().toString();
        String bid = UUID.randomUUID().toString();

        EventEnvelope envelope = EventEnvelope.of(tid, bid, System.currentTimeMillis(),
                method, data, runtimeConfig.getGatewaySn());

        // 注册等待 future
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingEventReplies.put(tid, future);

        String topic = dockTopicSchema.topic(dockTopicSchema.events(), runtimeConfig.getGatewaySn());
        mqtt.publish(topic, MessageCodec.toJson(envelope));

        // 等待 events_reply
        try {
            JsonNode reply = future.get(eventReplyTimeoutSeconds(), TimeUnit.SECONDS);
            int result = reply.path("data").path("result").asInt(-1);
            if (result == 0) {
                log.debug("收到 events_reply: method={}, result=0", method.methodName());
            } else {
                log.warn("events_reply 返回非零: method={}, result={}", method.methodName(), result);
            }
        } catch (Exception e) {
            pendingEventReplies.remove(tid);
            log.warn("等待 events_reply 超时（不阻塞后续上传）: method={}, {}", method.methodName(), e.getMessage());
        }
    }

    /**
     * 等待 events_reply 超时时间（秒），子类可覆盖以加速测试。
     * <p>默认 10 秒；超时不阻塞后续上传流程。</p>
     */
    protected long eventReplyTimeoutSeconds() {
        return EVENT_REPLY_TIMEOUT_SECONDS;
    }

    /**
     * 获取已上传的媒体文件列表（供 Web 控制台使用）。
     */
    public List<Map<String, Object>> getUploadedFiles() {
        return new ArrayList<>(uploadedFiles);
    }

    /** 获取当前最高优先级 flight_id（upload_flighttask_media_prioritize 设置）。 */
    public String getPriorityFlightId() {
        return priorityFlightId;
    }
}
