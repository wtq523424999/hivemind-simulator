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

package ltd.cdmi.hivemind.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.media.CaptureSnapshot;
import ltd.cdmi.hivemind.simulator.media.MediaUploader;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager.MqttMessageListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 媒体管理模拟器单元测试。
 * <p>覆盖 TDD-SPEC TC-MEDIA-001~008 测试用例：
 * <ul>
 *   <li>TC-MEDIA-001：file_upload_callback 事件结构完整性</li>
 *   <li>TC-MEDIA-003：object_key 正确拼接 object_key_prefix</li>
 *   <li>TC-MEDIA-004：highest_priority_upload_flighttask_media 事件结构</li>
 *   <li>TC-MEDIA-005：upload_flighttask_media_prioritize 记录优先级</li>
 *   <li>TC-MEDIA-006：file_upload_callback 等待 events_reply 后继续</li>
 *   <li>TC-MEDIA-007：events_reply 超时不阻塞后续上传</li>
 *   <li>TC-MEDIA-008：媒体上传完整流程时序</li>
 * </ul>
 */
class MediaUploadSimulatorTest {

    /** RuntimeConfig 默认 dockType=DOCK3，dockSn=DockModel.DOCK3.defaultSn() */
    private static final String DOCK_SN = DockModel.DOCK3.defaultSn();

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null,
                null
        );
    }

    private RuntimeConfig newRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(), new LiveConfigStore());
    }

    /**
     * 可配置超时的测试子类（覆盖 eventReplyTimeoutSeconds 加速测试）。
     * <p>TC-MEDIA-007 依赖此子类验证超时不阻塞流程。</p>
     */
    private static class TestableSimulator extends MediaUploadSimulator {
        private final long timeoutSec;

        TestableSimulator(SimulatorProperties props, MqttClientManager mqtt,
                          ObjectMapper objectMapper, DockOnlineService onlineService,
                          ServiceCommandHandler commandHandler, MediaUploader mediaUploader,
                          RuntimeConfig runtimeConfig, long timeoutSec) {
            super(props, mqtt, objectMapper, onlineService, commandHandler, mediaUploader, runtimeConfig, new DockTopicSchema());
            this.timeoutSec = timeoutSec;
        }

        @Override
        protected long eventReplyTimeoutSeconds() {
            return timeoutSec;
        }
    }

    /** 创建 timeout=0 的模拟器（结构测试用，不阻塞等待 events_reply） */
    private TestableSimulator createNoWaitSimulator(ObjectMapper objectMapper,
                                                     MqttClientManager mqtt,
                                                     DockOnlineService onlineService) {
        return new TestableSimulator(testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), new MediaUploader(), newRuntimeConfig(), 0);
    }

    /** Mock storage_config_get 返回含 object_key_prefix 的 requests_reply（完整 STS 字段，凭证有效） */
    private void mockStorageConfig(DockOnlineService onlineService, ObjectMapper objectMapper,
                                   String prefix) throws Exception {
        JsonNode configReply = objectMapper.readTree(String.format(
                "{\"data\":{\"result\":0,\"output\":{\"bucket\":\"test-bucket\",\"object_key_prefix\":\"%s\","
                        + "\"endpoint\":\"http://127.0.0.1:9000\",\"provider\":\"minio\","
                        + "\"credentials\":{\"access_key_id\":\"ak\",\"access_key_secret\":\"sk\","
                        + "\"security_token\":\"token\",\"expire\":3300}}}}", prefix));
        Mockito.when(onlineService.sendRequest(Mockito.eq("storage_config_get"), Mockito.any()))
                .thenReturn(configReply);
    }

    /** 捕获 events_reply 监听器并模拟云端回复（同步触发，测试不阻塞） */
    private void simulateEventReplies(MqttClientManager mqtt, ObjectMapper objectMapper) {
        DockTopicSchema schema = new DockTopicSchema();
        final MqttMessageListener[] capturedListener = new MqttMessageListener[1];
        String eventsReplyTopic = schema.topic(schema.eventsReply(), DOCK_SN);

        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            if (eventsReplyTopic.equals(topic)) {
                capturedListener[0] = invocation.getArgument(1);
            }
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any(MqttMessageListener.class));

        String eventsTopic = schema.topic(schema.events(), DOCK_SN);
        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            String json = invocation.getArgument(1);
            if (eventsTopic.equals(topic) && capturedListener[0] != null) {
                JsonNode envelope = objectMapper.readTree(json);
                String tid = envelope.path("tid").asText();
                String method = envelope.path("method").asText();
                String replyJson = String.format(
                        "{\"tid\":\"%s\",\"method\":\"%s\",\"data\":{\"result\":0}}", tid, method);
                capturedListener[0].onMessage(eventsReplyTopic, replyJson);
            }
            return null;
        }).when(mqtt).publish(Mockito.anyString(), Mockito.anyString());
    }

    /** 构造拍摄快照的测试辅助方法 */
    private static CaptureSnapshot snapshot(double lat, double lng, double absAlt,
                                            double relAlt, double yaw) {
        return new CaptureSnapshot(lat, lng, absAlt, relAlt, yaw,
                "2026-11-05 10:00:00", "", "0");
    }

    // ==================== 航点拍照：快照驱动上传 ====================

    @DisplayName("TC-MEDIA-009：按拍摄快照逐张上报，metadata 使用拍摄瞬间遥测")
    @Test
    @SuppressWarnings("unchecked")
    void uploadsEachSnapshotWithItsOwnMetadata() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        mockStorageConfig(onlineService, objectMapper, "camera");
        simulateEventReplies(mqtt, objectMapper);

        // 上传成功（不依赖真实 MinIO）：凭证有效即视为可上传，这里用无损 mock
        MediaUploadSimulator simulator = Mockito.spy(new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), new MediaUploader(), newRuntimeConfig(), 10));
        // 用已有素材目录避免依赖内置资源解出：直接构造两个快照
        List<CaptureSnapshot> snapshots = List.of(
                new CaptureSnapshot(30.6701, 104.0701, 550.5, 50.5, 12.5,
                        "2026-11-05 10:00:01", "point1", "0"),
                new CaptureSnapshot(30.6702, 104.0702, 551.5, 51.5, 45.0,
                        "2026-11-05 10:00:02", "point2", "0"));

        // 只验证回调内容；S3 上传在网络不可用时返回 false，会导致跳过回调，
        // 因此这里把 upload 打桩为成功以聚焦回调字段
        MediaUploader uploader = Mockito.mock(MediaUploader.class);
        Mockito.when(uploader.listUserPhotoFiles(Mockito.any())).thenReturn(List.of());
        Mockito.when(uploader.listPhotoFiles(Mockito.any())).thenReturn(List.of());
        Mockito.when(uploader.samplePhoto(Mockito.anyInt())).thenReturn(java.nio.file.Path.of("/tmp/fake.jpg"));
        Mockito.when(uploader.upload(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(true);
        TestableSimulator sim = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), uploader, newRuntimeConfig(), 10);

        sim.simulateMediaUpload("FLIGHT-PHOTO", snapshots);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeast(2)).publish(Mockito.anyString(), captor.capture());
        List<JsonNode> callbacks = new ArrayList<>();
        for (String payload : captor.getAllValues()) {
            JsonNode node = objectMapper.readTree(payload);
            if ("file_upload_callback".equals(node.path("method").asText())) {
                callbacks.add(node.path("data").path("file"));
            }
        }
        assertEquals(2, callbacks.size(), "每个 takePhoto 动作应产生一条回调");

        JsonNode first = callbacks.get(0);
        assertEquals("DJI_0001_point1.JPG", first.path("name").asText());
        assertEquals("camera/FLIGHT-PHOTO/DJI_0001_point1.JPG", first.path("object_key").asText());
        assertEquals(550.5, first.path("metadata").path("absolute_altitude").asDouble(), 1e-9);
        assertEquals(50.5, first.path("metadata").path("relative_altitude").asDouble(), 1e-9);
        assertEquals(30.6701, first.path("metadata").path("shoot_position").path("lat").asDouble(), 1e-9);
        assertEquals("2026-11-05 10:00:01", first.path("metadata").path("create_time").asText());
        assertEquals("12.5", first.path("metadata").path("gimbal_yaw_degree").asText());

        JsonNode second = callbacks.get(1);
        assertEquals("DJI_0002_point2.JPG", second.path("name").asText());
        assertEquals(30.6702, second.path("metadata").path("shoot_position").path("lat").asDouble(), 1e-9);
        assertEquals("45.0", second.path("metadata").path("gimbal_yaw_degree").asText());
    }

    @DisplayName("TC-MEDIA-022：目录有用户图片时上传用户图片，视频与预置样例都不参与")
    @Test
    void uploadsUserPhotoFilesInsteadOfPresets() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        mockStorageConfig(onlineService, objectMapper, "camera");
        simulateEventReplies(mqtt, objectMapper);

        java.nio.file.Path userPhoto = java.nio.file.Path.of("/tmp/user-shot.jpg");
        MediaUploader uploader = Mockito.mock(MediaUploader.class);
        Mockito.when(uploader.listUserPhotoFiles(Mockito.any())).thenReturn(List.of(userPhoto));
        Mockito.when(uploader.listPhotoFiles(Mockito.any())).thenReturn(List.of(userPhoto));
        Mockito.when(uploader.samplePhoto(Mockito.anyInt())).thenReturn(java.nio.file.Path.of("/tmp/fake.jpg"));
        Mockito.when(uploader.upload(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(true);

        TestableSimulator sim = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), uploader, newRuntimeConfig(), 10);

        sim.simulateMediaUpload("FLIGHT-USER", List.of(snapshot(30.0, 104.0, 500, 30, 0)));

        // 上传源必须是用户图片，而不是内置示例图
        Mockito.verify(uploader).upload(Mockito.eq(userPhoto), Mockito.any(), Mockito.any());
        Mockito.verify(uploader, Mockito.never()).samplePhoto(Mockito.anyInt());
    }

    @DisplayName("TC-MEDIA-022：目录只有预置样例时回退样例照片，仍不传视频")
    @Test
    void fallsBackToPresetPhotosNotVideos() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        mockStorageConfig(onlineService, objectMapper, "camera");
        simulateEventReplies(mqtt, objectMapper);

        java.nio.file.Path presetPhoto = java.nio.file.Path.of("/tmp/preset/sample-photo-2.jpg");
        MediaUploader uploader = Mockito.mock(MediaUploader.class);
        Mockito.when(uploader.listUserPhotoFiles(Mockito.any())).thenReturn(List.of());
        Mockito.when(uploader.listPhotoFiles(Mockito.any())).thenReturn(List.of(presetPhoto));
        Mockito.when(uploader.upload(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(true);

        TestableSimulator sim = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), uploader, newRuntimeConfig(), 10);

        sim.simulateMediaUpload("FLIGHT-PRESET", List.of(snapshot(30.0, 104.0, 500, 30, 0)));

        Mockito.verify(uploader).upload(Mockito.eq(presetPhoto), Mockito.any(), Mockito.any());
        // 不得退回到视频素材或额外解出临时示例图
        Mockito.verify(uploader, Mockito.never()).listMediaFiles(Mockito.any());
        Mockito.verify(uploader, Mockito.never()).samplePhoto(Mockito.anyInt());
    }

    @DisplayName("TC-MEDIA-010：文件上传失败时不发 file_upload_callback（避免平台按不存在的 object_key 取图）")
    @Test
    void skipsCallbackWhenUploadFails() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        mockStorageConfig(onlineService, objectMapper, "camera");
        simulateEventReplies(mqtt, objectMapper);

        MediaUploader uploader = Mockito.mock(MediaUploader.class);
        Mockito.when(uploader.listUserPhotoFiles(Mockito.any())).thenReturn(List.of());
        Mockito.when(uploader.listPhotoFiles(Mockito.any())).thenReturn(List.of());
        Mockito.when(uploader.samplePhoto(Mockito.anyInt())).thenReturn(java.nio.file.Path.of("/tmp/fake.jpg"));
        Mockito.when(uploader.upload(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);

        TestableSimulator sim = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), uploader, newRuntimeConfig(), 10);

        sim.simulateMediaUpload("FLIGHT-FAIL", List.of(snapshot(30.0, 104.0, 500, 30, 0)));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeastOnce()).publish(Mockito.anyString(), captor.capture());
        for (String payload : captor.getAllValues()) {
            assertNotEquals("file_upload_callback", objectMapper.readTree(payload).path("method").asText(),
                    "上传失败不应上报 file_upload_callback");
        }
        assertTrue(sim.getUploadedFiles().isEmpty());
    }

    @DisplayName("TC-MEDIA-011：无拍照动作时不发起媒体上传")
    @Test
    void skipsUploadWhenNoSnapshots() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator sim = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), new MediaUploader(), newRuntimeConfig(), 0);

        sim.simulateMediaUpload("FLIGHT-EMPTY", List.of(), true);

        // 构造时会注册 events_reply 监听器，因此只断言没有发布会话/请求
        Mockito.verify(mqtt, Mockito.never()).publish(Mockito.anyString(), Mockito.anyString());
    }

    // ==================== TC-MEDIA-001：file_upload_callback 事件结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-MEDIA-001：file_upload_callback 事件结构")
    @Test
    void fileUploadCallbackContainsCorrectStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator simulator = createNoWaitSimulator(objectMapper, mqtt, onlineService);

        // 反射调用 publishFileUploadCallback（4参数：flightId, fileName, objectKey, snapshot）
        Method method = MediaUploadSimulator.class.getDeclaredMethod(
                "publishFileUploadCallback", String.class, String.class,
                String.class, CaptureSnapshot.class);
        method.setAccessible(true);
        method.invoke(simulator, "TEST-FLIGHT", "test.jpg",
                "prefix-abc/TEST-FLIGHT/test.jpg", snapshot(30.67, 104.07, 550.0, 50.0, 30.0));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        // 顶层结构
        assertEquals("file_upload_callback", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt());
        assertEquals(DOCK_SN, node.path("gateway").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
        assertTrue(node.path("timestamp").asLong() > 0);

        // file 结构
        JsonNode file = node.path("data").path("file");
        assertEquals("test.jpg", file.path("name").asText());
        assertEquals("DEFAULT", file.path("cloud_to_cloud_id").asText());
        assertEquals("TEST-FLIGHT", file.path("path").asText());

        // ext 结构
        JsonNode ext = file.path("ext");
        assertEquals("TEST-FLIGHT", ext.path("flight_id").asText());
        assertEquals(DroneModel.M4TD.modelKey(), ext.path("drone_model_key").asText());
        // payload_model_key 是负载（相机）枚举值，不是飞行器型号（官方字段定义）
        assertNotEquals(DroneModel.M4TD.modelKey(), ext.path("payload_model_key").asText());
        assertTrue(ext.path("payload_model_key").asText().matches("\\d+-\\d+-\\d+"),
                "payload_model_key 应为负载 index（如 0-67-0），实际=" + ext.path("payload_model_key").asText());
        assertTrue(ext.path("is_original").asBoolean());

        // metadata 结构：全部来自拍摄快照
        JsonNode metadata = file.path("metadata");
        assertEquals(550.0, metadata.path("absolute_altitude").asDouble(), 1e-9);
        assertEquals(50.0, metadata.path("relative_altitude").asDouble(), 1e-9);
        assertEquals("30.0", metadata.path("gimbal_yaw_degree").asText());
        assertEquals("2026-11-05 10:00:00", metadata.path("create_time").asText());
        assertEquals(30.67, metadata.path("shoot_position").path("lat").asDouble(), 1e-9);
        assertEquals(104.07, metadata.path("shoot_position").path("lng").asDouble(), 1e-9);
    }

    // ==================== TC-MEDIA-003：object_key 拼接 object_key_prefix ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-MEDIA-003：object_key 用 object_key_prefix 构造")
    @Test
    void objectKeyContainsPrefix() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator simulator = createNoWaitSimulator(objectMapper, mqtt, onlineService);

        String prefix = "abc-123";
        String flightId = "FLIGHT-001";
        String fileName = "photo.jpg";

        Method method = MediaUploadSimulator.class.getDeclaredMethod(
                "publishFileUploadCallback", String.class, String.class,
                String.class, CaptureSnapshot.class);
        method.setAccessible(true);
        method.invoke(simulator, flightId, fileName, prefix + "/" + flightId + "/" + fileName,
                snapshot(30.67, 104.07, 550.0, 50.0, 0.0));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        String objectKey = node.path("data").path("file").path("object_key").asText();
        // object_key 格式：{object_key_prefix}/{flight_id}/{file_name}
        assertEquals(prefix + "/" + flightId + "/" + fileName, objectKey);
        assertTrue(objectKey.startsWith(prefix));    }

    // ==================== TC-MEDIA-004：highest_priority_upload_flighttask_media 事件结构 ====================

    @SuppressWarnings("unchecked")
    @DisplayName("TC-MEDIA-004：highest_priority_upload_flighttask_media 事件上报")
    @Test
    void highestPriorityEventStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        MediaUploadSimulator simulator = createNoWaitSimulator(objectMapper, mqtt, onlineService);

        Method method = MediaUploadSimulator.class.getDeclaredMethod(
                "publishHighestPriority", String.class);
        method.setAccessible(true);
        method.invoke(simulator, "FLIGHT-PRIORITY");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(Mockito.anyString(), captor.capture());

        JsonNode node = objectMapper.readTree(captor.getValue());

        assertEquals("highest_priority_upload_flighttask_media", node.path("method").asText());
        assertEquals(1, node.path("need_reply").asInt());
        assertEquals(DOCK_SN, node.path("gateway").asText());
        assertEquals("FLIGHT-PRIORITY", node.path("data").path("flight_id").asText());
        assertFalse(node.path("bid").asText().isEmpty());
        assertFalse(node.path("tid").asText().isEmpty());
    }

    // ==================== TC-MEDIA-005：upload_flighttask_media_prioritize 记录优先级 ====================

    @DisplayName("TC-MEDIA-005：upload_flighttask_media_prioritize 回 result=0")
    @Test
    void prioritizeCommandRecordsFlightId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);
        ServiceCommandHandler commandHandler = Mockito.mock(ServiceCommandHandler.class);

        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService, commandHandler,
                new MediaUploader(), newRuntimeConfig(), 0);

        JsonNode data = objectMapper.readTree("{\"flight_id\":\"FLIGHT-HIGH\"}");

        Map<String, Object> result = simulator.handleMediaCommand(
                "upload_flighttask_media_prioritize", data);

        assertEquals(0, result.get("result"));
        assertEquals("FLIGHT-HIGH", simulator.getPriorityFlightId());
    }

    // ==================== TC-MEDIA-006：file_upload_callback 等待 events_reply 后继续 ====================

    @DisplayName("TC-MEDIA-006：file_upload_callback 上报后等待 events_reply")
    @Test
    void fileUploadCallbackWaitsForEventReply() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        mockStorageConfig(onlineService, objectMapper, "test-prefix");
        simulateEventReplies(mqtt, objectMapper);

        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), new MediaUploader(), newRuntimeConfig(), 10);

        // 执行媒体上传（应该快速完成，因为 events_reply 是同步模拟的）
        simulator.simulateMediaUpload("FLIGHT-REPLY", 3);

        // 验证所有文件都已上报（events_reply 收到后继续上传）
        List<Map<String, Object>> files = simulator.getUploadedFiles();
        assertEquals(3, files.size());
        for (Map<String, Object> f : files) {
            assertEquals("FLIGHT-REPLY", f.get("flight_id"));
        }
    }

    // ==================== TC-MEDIA-007：events_reply 超时不阻塞后续上传 ====================

    @DisplayName("TC-MEDIA-007：events_reply 超时不阻塞后续上传")
    @Test
    void eventReplyTimeoutDoesNotBlockUpload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        mockStorageConfig(onlineService, objectMapper, "test-prefix");
        // 不模拟 events_reply（publish 默认 no-op，future 超时）

        // 1 秒超时（4 个事件 × 1 秒 = 4 秒，验证超时不阻塞）
        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), new MediaUploader(), newRuntimeConfig(), 1);

        simulator.simulateMediaUpload("FLIGHT-TIMEOUT", 3);

        // 验证所有文件都已上报（尽管 events_reply 超时）
        List<Map<String, Object>> files = simulator.getUploadedFiles();
        assertEquals(3, files.size());
    }

    // ==================== TC-MEDIA-008：媒体上传完整流程时序 ====================

    @DisplayName("TC-MEDIA-008：媒体上传完整流程")
    @Test
    void completeUploadFlowSequence() throws Exception {
        DockTopicSchema schema = new DockTopicSchema();
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DockOnlineService onlineService = Mockito.mock(DockOnlineService.class);

        mockStorageConfig(onlineService, objectMapper, "seq-prefix");

        // 捕获所有 publish 调用的 method（用于验证时序）
        List<String> publishedMethods = new ArrayList<>();
        final MqttMessageListener[] capturedListener = new MqttMessageListener[1];
        String eventsReplyTopic = schema.topic(schema.eventsReply(), DOCK_SN);

        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            if (eventsReplyTopic.equals(topic)) {
                capturedListener[0] = invocation.getArgument(1);
            }
            return null;
        }).when(mqtt).addListener(Mockito.anyString(), Mockito.any(MqttMessageListener.class));

        String eventsTopic = schema.topic(schema.events(), DOCK_SN);
        Mockito.doAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            String json = invocation.getArgument(1);
            if (eventsTopic.equals(topic)) {
                JsonNode envelope = objectMapper.readTree(json);
                String method = envelope.path("method").asText();
                String tid = envelope.path("tid").asText();
                publishedMethods.add(method);
                // 模拟 events_reply
                if (capturedListener[0] != null) {
                    String replyJson = String.format(
                            "{\"tid\":\"%s\",\"method\":\"%s\",\"data\":{\"result\":0}}", tid, method);
                    capturedListener[0].onMessage(eventsReplyTopic, replyJson);
                }
            }
            return null;
        }).when(mqtt).publish(Mockito.anyString(), Mockito.anyString());

        MediaUploadSimulator simulator = new TestableSimulator(
                testProps(), mqtt, objectMapper, onlineService,
                Mockito.mock(ServiceCommandHandler.class), new MediaUploader(), newRuntimeConfig(), 10);

        simulator.simulateMediaUpload("FLIGHT-SEQ", 3);

        // 验证时序：storage_config_get → highest_priority → file_upload_callback × 3
        Mockito.verify(onlineService).sendRequest(
                Mockito.eq("storage_config_get"), Mockito.any());

        assertEquals(4, publishedMethods.size());
        assertEquals("highest_priority_upload_flighttask_media", publishedMethods.get(0));
        assertEquals("file_upload_callback", publishedMethods.get(1));
        assertEquals("file_upload_callback", publishedMethods.get(2));
        assertEquals("file_upload_callback", publishedMethods.get(3));

        // 验证所有文件都已上报
        assertEquals(3, simulator.getUploadedFiles().size());
    }
}
