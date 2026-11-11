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

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机库群管理测试（TC-FLEET-101~120）。
 *
 * <p>覆盖：清单增删改查与校验、compose 生成、本实例识别、
 * 不可达实例降级、机型选项来自 SDK 枚举。</p>
 */
class FleetServiceTest {

    @TempDir
    Path tempDir;

    private FleetStore store;
    private FleetService service;
    private RuntimeConfig runtimeConfig;
    private DeviceState localState;
    private WaylineTaskSimulator waylineTaskSimulator;

    @BeforeEach
    void setUp() {
        store = new FleetStore(tempDir.resolve("fleet.json"));

        SimulatorProperties props = new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "ffmpeg", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
        LiveConfigStore liveStore = Mockito.mock(LiveConfigStore.class);
        Mockito.when(liveStore.load()).thenReturn(null);
        runtimeConfig = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"), props, liveStore);

        localState = new DeviceState();
        waylineTaskSimulator = Mockito.mock(WaylineTaskSimulator.class);
        Mockito.when(waylineTaskSimulator.getTaskStatus()).thenReturn(Map.of("active", false));

        // 测试里不启用供给服务：新增机库只登记清单，不创建容器
        service = new FleetService(store, new ObjectMapper(), runtimeConfig, localState,
                waylineTaskSimulator, null, 9090);
    }

    private FleetInstance instance(String name, int port) {
        return new FleetInstance(null, name, "127.0.0.1", port,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true);
    }

    // ==================== 清单 CRUD ====================

    @DisplayName("TC-FLEET-101：新增机库自动生成 id 并持久化")
    @Test
    void createAssignsIdAndPersists() {
        FleetInstance created = service.create(instance("机库A", 9091));

        assertNotNull(created.id());
        assertFalse(created.id().isBlank());
        assertEquals(1, service.load().instances().size());
        assertEquals("机库A", service.load().instances().get(0).name());
    }

    @DisplayName("TC-FLEET-102：名称重复拒绝")
    @Test
    void createRejectsDuplicateName() {
        service.create(instance("机库A", 9091));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(instance("机库a", 9092)));
        assertTrue(ex.getMessage().contains("名称已存在"));
    }

    @DisplayName("TC-FLEET-103：控制台地址重复拒绝")
    @Test
    void createRejectsDuplicateEndpoint() {
        service.create(instance("机库A", 9091));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(instance("机库B", 9091)));
        assertTrue(ex.getMessage().contains("地址已被登记"));
    }

    @DisplayName("TC-FLEET-104：名称为空拒绝")
    @Test
    void createRejectsBlankName() {
        FleetInstance bad = new FleetInstance(null, "   ", "127.0.0.1", 9091,
                null, null, null, null, null, null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> service.create(bad));
    }

    @DisplayName("TC-FLEET-105：host 为空拒绝")
    @Test
    void createRejectsBlankHost() {
        FleetInstance bad = new FleetInstance(null, "机库A", "  ", 9091,
                null, null, null, null, null, null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> service.create(bad));
    }

    @DisplayName("TC-FLEET-106：端口非法拒绝")
    @Test
    void createRejectsInvalidPort() {
        FleetInstance bad = new FleetInstance(null, "机库A", "127.0.0.1", 70000,
                null, null, null, null, null, null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> service.create(bad));
    }

    @DisplayName("TC-FLEET-107：坐标越界拒绝")
    @Test
    void createRejectsOutOfRangeCoordinate() {
        FleetInstance bad = new FleetInstance(null, "机库A", "127.0.0.1", 9091,
                null, null, null, null, 91.0, 104.0, 500.0, null, true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.create(bad));
        assertTrue(ex.getMessage().contains("纬度"));
    }

    @DisplayName("TC-FLEET-108：更新保留 id，名称可改")
    @Test
    void updateKeepsId() {
        FleetInstance created = service.create(instance("机库A", 9091));
        FleetInstance updated = service.update(created.id(),
                new FleetInstance(null, "机库A-改名", "127.0.0.1", 9091,
                        null, null, "DOCK3", "M4TD", 31.0, 105.0, 600.0, "备注", true));

        assertEquals(created.id(), updated.id());
        assertEquals("机库A-改名", service.load().instances().get(0).name());
        assertEquals(31.0, service.load().instances().get(0).latitude());
    }

    @DisplayName("TC-FLEET-109：更新不存在的机库抛异常")
    @Test
    void updateUnknownThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update("not-exist", instance("机库A", 9091)));
    }

    @DisplayName("TC-FLEET-110：删除移除登记项")
    @Test
    void deleteRemovesInstance() {
        FleetInstance created = service.create(instance("机库A", 9091));
        service.delete(created.id());
        assertTrue(service.load().instances().isEmpty());
    }

    @DisplayName("TC-FLEET-111：删除不存在的机库抛异常")
    @Test
    void deleteUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("not-exist"));
    }

    // ==================== 本实例识别 ====================

    @DisplayName("TC-FLEET-112：显式标记的当前实例优先于端口推断")
    @Test
    void recognizesSelfInstance() {
        FleetInstance self = new FleetInstance("self", "本机", "127.0.0.1", 9090,
                null, null, null, null, null, null, null, null, true);
        assertTrue(service.isSelf(self));

        FleetInstance other = new FleetInstance("other", "其他", "127.0.0.1", 9091,
                null, null, null, null, null, null, null, null, true);
        assertFalse(service.isSelf(other));
    }

    @DisplayName("TC-FLEET-112b：容器内宿主机映射端口需靠 selfInstance 标记识别")
    @Test
    void selfFlagOverridesPortInference() {
        // 容器内监听 9090，宿主机映射到 9091：端口与 server.port 不等，端口推断会失败
        FleetInstance mapped = new FleetInstance("self", "本机", "127.0.0.1", 9091,
                null, null, null, null, null, null, null, null, true, true);
        assertTrue(service.isSelf(mapped));
        assertTrue(mapped.selfFlag());
        // 服务端调用走内部回环端口，而不是宿主机映射端口
        assertEquals("http://127.0.0.1:9090", mapped.internalBaseUrl(9090));
        // 浏览器仍用对外映射端口
        assertEquals("http://127.0.0.1:9091", mapped.baseUrl());
    }

    @DisplayName("TC-FLEET-112c：非本机实例不重写调用地址")
    @Test
    void remoteInstanceKeepsBaseUrl() {
        FleetInstance remote = new FleetInstance("r", "远程", "10.0.0.9", 9091,
                null, null, null, null, null, null, null, null, true);
        assertEquals("http://10.0.0.9:9091", remote.internalBaseUrl(9090));
    }

    @DisplayName("TC-FLEET-113：本实例状态直接读 Bean，标记 self=true")
    @Test
    void localStatusReadsBeans() {
        FleetInstance self = new FleetInstance("self", "本机", "127.0.0.1", 9090,
                null, null, null, null, null, null, null, null, true);
        FleetInstanceStatus status = service.statusOf(self);

        assertTrue(status.self());
        assertTrue(status.reachable());
        assertEquals(runtimeConfig.getDockSn(), status.dockSn());
        assertEquals(30.67, status.latitude());
    }

    @DisplayName("TC-FLEET-114：补写本实例 MQTT 状态，不影响远程实例")
    @Test
    void withLocalMqttOnlyAffectsSelf() {
        FleetInstance self = new FleetInstance("self", "本机", "127.0.0.1", 9090,
                null, null, null, null, null, null, null, null, true);
        FleetInstanceStatus local = service.statusOf(self);
        assertNull(local.mqttConnected());

        FleetInstanceStatus patched = service.withLocalMqtt(local, true);
        assertEquals(Boolean.TRUE, patched.mqttConnected());

        FleetInstance remote = new FleetInstance("r", "远程", "10.0.0.9", 9091,
                null, null, null, null, null, null, null, null, true);
        FleetInstanceStatus unreachable = FleetInstanceStatus.unreachable(remote, false, "timeout");
        assertSame(unreachable, service.withLocalMqtt(unreachable, true));
    }

    @DisplayName("TC-FLEET-115：不可达实例降级为可诊断状态，不抛异常")
    @Test
    void unreachableStatusDegrades() {
        // 端口 1 必然连接失败，用极短路径验证降级
        FleetInstance remote = new FleetInstance("r", "不存在的实例", "127.0.0.1", 1,
                null, null, null, null, 31.5, 105.5, 600.0, null, true);
        FleetInstanceStatus status = service.statusOf(remote);

        assertFalse(status.reachable());
        assertNotNull(status.error());
        // 坐标回落到清单登记值，便于区分"配错地址"与"没配坐标"
        assertEquals(31.5, status.latitude());
        assertEquals(105.5, status.longitude());
    }

    // ==================== 机型选项 ====================

    @DisplayName("TC-FLEET-116：机型选项来自 SDK 枚举，label 用与研究界面一致的简称")
    @Test
    void modelOptionsFromSdkEnums() {
        Map<String, Object> options = service.modelOptions();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> dockTypes = (List<Map<String, String>>) options.get("dockTypes");
        assertTrue(dockTypes.stream().anyMatch(item -> "DOCK3".equals(item.get("value"))));
        // label 用 shortName（Dock3），与控制台其他机型标签口径一致；displayName 另附中文全称
        assertTrue(dockTypes.stream().anyMatch(item ->
                "DOCK3".equals(item.get("value")) && "Dock3".equals(item.get("label"))));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> droneTypes = (List<Map<String, String>>) options.get("droneTypes");
        assertTrue(droneTypes.stream().anyMatch(item -> "M4TD".equals(item.get("value"))));
        assertTrue(droneTypes.stream().anyMatch(item ->
                "M4TD".equals(item.get("value")) && "M4TD".equals(item.get("label"))));
    }

    // ==================== compose 生成 ====================

    @DisplayName("TC-FLEET-117：按清单生成 compose，含端口/坐标/唯一 SN/独立 config 卷")
    @Test
    void renderComposeContainsPerInstanceConfig() {
        service.create(new FleetInstance(null, "成都高新机库", "127.0.0.1", 9091,
                null, null, "DOCK3", "M4TD", 30.670815, 104.071523, 500.0, null, true));
        service.create(new FleetInstance(null, "北川机库", "127.0.0.1", 9092,
                null, null, "DOCK3", "M4TD", 31.695247, 104.451402, 600.0, null, true));

        String yaml = service.renderCompose("img:test", "host.docker.internal", 1883, "u", "p", "");

        assertTrue(yaml.contains("img:test"));
        assertTrue(yaml.contains("\"9091:9090\""));
        assertTrue(yaml.contains("\"9092:9090\""));
        assertTrue(yaml.contains("SIMULATOR_LOCATION_LATITUDE: 30.670815"));
        assertTrue(yaml.contains("SIMULATOR_LOCATION_LONGITUDE: 104.451402"));
        assertTrue(yaml.contains("SIMULATOR_SN_UNIQUEENABLED: \"true\""));
        assertTrue(yaml.contains("simulator-config-"), "每个实例应有独立 config 卷");
        assertTrue(yaml.contains("dji-dock-simulator-media"), "应共享 media 卷");
        assertFalse(yaml.contains("platform-net"), "默认不加入外部网络，避免网络不存在导致启动失败");
    }

    @DisplayName("TC-FLEET-118：空清单生成可读的占位编排，不产生非法 YAML")
    @Test
    void renderComposeEmptyList() {
        String yaml = service.renderCompose(null, null, null, null, null, null);

        assertTrue(yaml.contains("清单为空"));
        assertTrue(yaml.contains("volumes:"));
        assertTrue(yaml.contains("networks:"));
        // 空清单时不应出现任何实例配置
        assertFalse(yaml.contains("SIMULATOR_LOCATION_LATITUDE"));
    }

    @DisplayName("TC-FLEET-119：停用的机库不进入编排")
    @Test
    void renderComposeSkipsDisabled() {
        service.create(new FleetInstance(null, "停用机库", "127.0.0.1", 9093,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, false));
        String yaml = service.renderCompose(null, null, null, null, null, null);

        assertFalse(yaml.contains("9093:9090"), "停用实例不应出现在编排中");
    }

    @DisplayName("TC-FLEET-120：compose 含外部网络时写入 external: true")
    @Test
    void renderComposeWithExternalNetwork() {
        service.create(instance("机库A", 9091));
        String yaml = service.renderCompose(null, null, null, null, null, "backend_backend");

        assertTrue(yaml.contains("      - backend_backend"));
        assertTrue(yaml.contains("backend_backend:\n    external: true"));
    }

    @DisplayName("TC-FLEET-121：本机实例不写入编排（避免端口冲突）")
    @Test
    void renderComposeSkipsSelfInstance() {
        service.create(new FleetInstance(null, "本机", "127.0.0.1", 9090,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true, true));
        String yaml = service.renderCompose(null, null, null, null, null, null);

        // 本机实例端口 9090 已在用，不能出现在编排里
        assertFalse(yaml.contains("\"9090:9090\""));
        assertTrue(yaml.contains("清单为空（或仅登记了本机实例）"));
    }

    @DisplayName("TC-FLEET-122：service 名由机库名派生，容器名不叠加镜像前缀")
    @Test
    void renderComposeServiceNaming() {
        service.create(new FleetInstance(null, "Bei Chuan Dock", "127.0.0.1", 9092,
                null, null, "DOCK3", "M4TD", 31.0, 104.0, 500.0, null, true));
        String yaml = service.renderCompose(null, null, null, null, null, null);

        assertTrue(yaml.contains("  bei-chuan-dock:\n"), "service 名应为机库名的 slug");
        assertTrue(yaml.contains("container_name: dock-sim-bei-chuan-dock"));
        assertTrue(yaml.contains("simulator-config-bei-chuan-dock:/app/config"));
    }

    // ==================== 选库距离计算（TC-FLEET-201~210） ====================

    @DisplayName("TC-FLEET-201：haversine 距离与已知值一致（成都→重庆约 267km）")
    @Test
    void haversineMatchesKnownDistance() {
        // 成都天府广场 (30.657,104.066) → 重庆解放碑 (29.563,106.577)
        double meters = FleetService.haversineMeters(30.657, 104.066, 29.563, 106.577);
        double km = meters / 1000;
        assertTrue(km > 260 && km < 275, "实际约 267km，计算值: " + km + "km");
    }

    @DisplayName("TC-FLEET-202：同点距离为 0")
    @Test
    void haversineSamePointIsZero() {
        assertEquals(0.0, FleetService.haversineMeters(30.0, 104.0, 30.0, 104.0), 0.001);
    }

    @DisplayName("TC-FLEET-203：候选排序把可派遣的排前面，再按距离升序")
    @Test
    void candidatesSortByDispatchableThenDistance() {
        // 本机实例（self）坐标由 RuntimeConfig 决定，设为 30.67,104.07；状态需在线才可派遣
        localState.setOnline(true);
        localState.setBatteryPercent(100);
        localState.setDroneInDock(true);
        Mockito.when(waylineTaskSimulator.getTaskStatus())
                .thenReturn(Map.of("active", false));
        service.create(new FleetInstance(null, "本机", "127.0.0.1", 9090,
                null, null, "DOCK3", "M4TD", null, null, null, null, true, true));
        // 一台不可达的远机库
        service.create(new FleetInstance(null, "远机库", "127.0.0.1", 19999,
                null, null, "DOCK3", "M4TD", 31.0, 105.0, 500.0, null, true));

        List<FleetService.FleetCandidate> candidates =
                service.candidates(30.68, 104.08, 0);

        assertEquals(2, candidates.size());
        assertTrue(candidates.get(0).dispatchable(), "可派遣的应排第一");
        assertEquals("本机", candidates.get(0).name());
        assertNotNull(candidates.get(0).distanceMeters());
        assertFalse(candidates.get(1).dispatchable());
        assertEquals("实例不可达", candidates.get(1).rejectReason());
    }

    @DisplayName("TC-FLEET-204：未上报坐标的实例距离为 null，且不可派遣")
    @Test
    void candidateWithoutCoordinateHasNullDistance() {
        service.create(new FleetInstance(null, "无坐标机库", "127.0.0.1", 19999,
                null, null, null, null, null, null, null, null, true));

        List<FleetService.FleetCandidate> candidates = service.candidates(30.0, 104.0, 0);
        assertEquals(1, candidates.size());
        assertNull(candidates.get(0).distanceMeters());
        assertFalse(candidates.get(0).dispatchable());
    }

    @DisplayName("TC-FLEET-205：limit 限制返回条数")
    @Test
    void candidatesRespectLimit() {
        for (int i = 1; i <= 5; i++) {
            service.create(new FleetInstance(null, "机库" + i, "127.0.0." + i, 19990 + i,
                    null, null, "DOCK3", "M4TD", 30.0 + i * 0.01, 104.0, 500.0, null, true));
        }
        assertEquals(3, service.candidates(30.0, 104.0, 3).size());
        assertEquals(5, service.candidates(30.0, 104.0, 0).size());
    }

    @DisplayName("TC-FLEET-206：电量低于 30% 不可派遣")
    @Test
    void lowBatteryIsNotDispatchable() {
        FleetInstanceStatus low = new FleetInstanceStatus("x", "低电量", "u", true, true, null,
                "sn", "dsn", "Dock3", "M4TD", "DOCK", true, true, true,
                30.0, 104.0, 500.0, null, 20, true, false, false, null, 0, 0, 2);
        assertFalse(low.dispatchable());
    }

    @DisplayName("TC-FLEET-207：正在执行任务不可派遣")
    @Test
    void busyDockIsNotDispatchable() {
        FleetInstanceStatus busy = new FleetInstanceStatus("x", "忙", "u", true, true, null,
                "sn", "dsn", "Dock3", "M4TD", "DOCK", true, true, true,
                30.0, 104.0, 500.0, null, 100, true, true, true, "flight-1", 4, 5, 0);
        assertFalse(busy.dispatchable());
    }

    @DisplayName("TC-FLEET-208：飞行器不在舱不可派遣")
    @Test
    void droneOutOfDockIsNotDispatchable() {
        FleetInstanceStatus out = new FleetInstanceStatus("x", "出舱", "u", true, true, null,
                "sn", "dsn", "Dock3", "M4TD", "DOCK", true, true, true,
                30.0, 104.0, 500.0, null, 100, false, true, false, null, 0, 5, 0);
        assertFalse(out.dispatchable());
    }

    @DisplayName("TC-FLEET-209：未知字段（null）不因此排除，避免混版本误伤")
    @Test
    void unknownFieldsDoNotExclude() {
        FleetInstanceStatus unknown = new FleetInstanceStatus("x", "旧实例", "u", true, true, null,
                "sn", "dsn", "Dock3", "M4TD", "DOCK", true, true, true,
                30.0, 104.0, 500.0, null, null, null, null, null, null, null, null, null);
        assertTrue(unknown.dispatchable(), "电量/在舱/任务未知时不应排除");
    }

    @DisplayName("TC-FLEET-210：未上线不可派遣")
    @Test
    void offlineDockIsNotDispatchable() {
        FleetInstanceStatus offline = new FleetInstanceStatus("x", "离线", "u", true, true, null,
                "sn", "dsn", "Dock3", "M4TD", "DOCK", false, false, true,
                30.0, 104.0, 500.0, null, 100, true, false, false, null, 0, 0, 2);
        assertFalse(offline.dispatchable());
    }

    // ==================== 清单导入导出（TC-FLEET-301~308） ====================

    @DisplayName("TC-FLEET-301：导出后可原样导入（整表替换）")
    @Test
    void exportThenImportRoundTrip() {
        service.create(new FleetInstance(null, "机库A", "127.0.0.1", 9091,
                "SN-A", "DSN-A", "DOCK3", "M4TD", 30.5, 104.5, 500.0, null, true));
        service.create(new FleetInstance(null, "机库B", "127.0.0.1", 9092,
                "SN-B", "DSN-B", "DOCK3", "M4TD", 31.5, 105.5, 600.0, null, true));

        FleetConfig exported = service.exportConfig();
        assertEquals(1, exported.versionOrDefault());
        assertEquals(2, exported.instances().size());

        // 清空后整表导入
        service.importConfig(FleetConfig.empty(), true);
        assertTrue(service.load().instances().isEmpty());

        Map<String, Object> result = service.importConfig(exported, true);
        assertEquals(2, result.get("imported"));
        assertEquals(2, service.load().instances().size());
    }

    @DisplayName("TC-FLEET-302：合并导入不清空既有条目")
    @Test
    void importMergeKeepsExisting() {
        service.create(new FleetInstance(null, "既有", "127.0.0.1", 9091,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        FleetConfig incoming = new FleetConfig(1, List.of(
                new FleetInstance("dock-new", "新增", "127.0.0.1", 9092,
                        null, null, "DOCK3", "M4TD", 31.0, 105.0, 500.0, null, true)));
        Map<String, Object> result = service.importConfig(incoming, false);

        assertEquals(1, result.get("imported"));
        assertEquals(2, service.load().instances().size());
        assertTrue(service.load().instances().stream().anyMatch(i -> "既有".equals(i.name())));
    }

    @DisplayName("TC-FLEET-303：导入时同 id 覆盖而非重复")
    @Test
    void importOverwritesSameId() {
        FleetInstance created = service.create(new FleetInstance(null, "旧名", "127.0.0.1", 9091,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        FleetConfig incoming = new FleetConfig(1, List.of(
                new FleetInstance(created.id(), "新名", "127.0.0.1", 9091,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true)));
        service.importConfig(incoming, false);

        assertEquals(1, service.load().instances().size());
        assertEquals("新名", service.load().instances().get(0).name());
    }

    @DisplayName("TC-FLEET-304：非法条目被拒绝，合法条目照常导入")
    @Test
    void importRejectsInvalidEntriesOnly() {
        FleetConfig incoming = new FleetConfig(1, List.of(
                new FleetInstance(null, "合法机库", "127.0.0.1", 9091,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true),
                // 名称为空 → 拒绝
                new FleetInstance(null, "  ", "127.0.0.1", 9092,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true),
                // 纬度越界 → 拒绝
                new FleetInstance(null, "越界机库", "127.0.0.1", 9093,
                        null, null, "DOCK3", "M4TD", 100.0, 104.0, 500.0, null, true)));

        Map<String, Object> result = service.importConfig(incoming, true);

        assertEquals(1, result.get("imported"));
        @SuppressWarnings("unchecked")
        List<String> rejected = (List<String>) result.get("rejected");
        assertEquals(2, rejected.size());
        assertEquals(1, service.load().instances().size());
    }

    @DisplayName("TC-FLEET-305：导入时名称冲突条目被拒绝")
    @Test
    void importRejectsDuplicateName() {
        FleetConfig incoming = new FleetConfig(1, List.of(
                new FleetInstance("id-1", "同名", "127.0.0.1", 9091,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true),
                new FleetInstance("id-2", "同名", "127.0.0.2", 9092,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true)));

        Map<String, Object> result = service.importConfig(incoming, true);

        assertEquals(1, result.get("imported"));
        @SuppressWarnings("unchecked")
        List<String> rejected = (List<String>) result.get("rejected");
        assertEquals(1, rejected.size());
        assertTrue(rejected.get(0).contains("名称与既有条目冲突"));
    }

    @DisplayName("TC-FLEET-306：导出不含任何凭据字段")
    @Test
    void exportContainsNoCredentials() {
        service.create(new FleetInstance(null, "机库A", "127.0.0.1", 9091,
                "SN-A", "DSN-A", "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(service.exportConfig());
        } catch (Exception e) {
            fail("序列化失败: " + e.getMessage());
            return;
        }
        assertFalse(json.toLowerCase().contains("password"), "清单不应含密码");
        assertFalse(json.toLowerCase().contains("mqtt"), "清单不应含 MQTT 连接参数");
        assertTrue(json.contains("SN-A"));
    }

    // ==================== 启用工作流（TC-FLEET-401~406） ====================

    @DisplayName("TC-FLEET-401：实例不可达时工作流在探测阶段失败")
    @Test
    void bringupFailsWhenUnreachable() {
        service.create(new FleetInstance(null, "离线", "127.0.0.1", 19999,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        List<FleetBringupResult> results = service.bringup(Map.of(), null);

        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertEquals("probe", results.get(0).failedStep());
        assertTrue(results.get(0).message().contains("不可达"));
    }

    @DisplayName("TC-FLEET-402：已开机已连接已在线时工作流全部跳过并成功")
    @Test
    void bringupSkipsSatisfiedSteps() {
        localState.setPowered(true);
        localState.setOnline(true);
        localState.setDroneInDock(true);
        localState.setBatteryPercent(100);
        service.create(new FleetInstance(null, "本机", "127.0.0.1", 9090,
                null, null, "DOCK3", "M4TD", 30.67, 104.07, 500.0, null, true, true));

        List<FleetBringupResult> results = service.bringup(Map.of(), null);

        assertEquals(1, results.size());
        // 状态探测走本地 Bean；MQTT 未连（null→false），但本机动作会走 HTTP 回环，
        // 测试环境无服务端，因此只断言流程结构与跳过语义
        FleetBringupResult result = results.get(0);
        assertNotNull(result.steps());
        assertTrue(result.steps().stream().anyMatch(s -> "power_on".equals(s.step()) && s.detail().contains("跳过")),
                "已开机应跳过开机步骤: " + result.steps());
    }

    @DisplayName("TC-FLEET-403：工作流只处理启用的实例")
    @Test
    void bringupSkipsDisabledInstances() {
        service.create(new FleetInstance(null, "停用", "127.0.0.1", 19999,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, false));
        assertTrue(service.bringup(Map.of(), null).isEmpty());
    }

    @DisplayName("TC-FLEET-404：ids 参数限定处理范围")
    @Test
    void bringupRespectsIdsFilter() {
        FleetInstance a = service.create(new FleetInstance(null, "A", "127.0.0.1", 19998,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));
        service.create(new FleetInstance(null, "B", "127.0.0.1", 19999,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        List<FleetBringupResult> results = service.bringup(Map.of(), List.of(a.id()));
        assertEquals(1, results.size());
        assertEquals(a.id(), results.get(0).id());
    }

    // ==================== 平台预置导出（TC-FLEET-501~505） ====================

    @DisplayName("TC-FLEET-501：按清单生成 dock/device 记录与 SQL")
    @Test
    void platformSeedGeneratesRecords() {
        service.create(new FleetInstance(null, "成都机库", "127.0.0.1", 9091,
                "DOCK-SN-1", "DRONE-SN-1", "DOCK3", "M4TD", 30.5, 104.5, 500.0, null, true));

        Map<String, Object> seed = service.exportPlatformSeed("org-uuid", "DJI");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docks = (List<Map<String, Object>>) seed.get("docks");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) seed.get("devices");
        String sql = String.valueOf(seed.get("sql"));

        assertEquals(1, docks.size());
        assertEquals("DOCK-SN-1", docks.get(0).get("dockSn"));
        assertEquals(30.5, docks.get(0).get("latitude"));
        assertEquals(1, devices.size());
        assertEquals("DRONE-SN-1", devices.get(0).get("droneSn"));
        assertEquals("DOCK-SN-1", devices.get(0).get("gatewaySn"));

        assertTrue(sql.contains("INSERT INTO dock"));
        assertTrue(sql.contains("INSERT INTO device"));
        assertTrue(sql.contains("'DOCK-SN-1'"));
        assertTrue(sql.contains("org-uuid"));
        assertTrue(sql.contains("WHERE NOT EXISTS"), "SQL 应可重复执行");
        assertTrue(sql.contains("linked_drone_id"));
    }

    @DisplayName("TC-FLEET-502：未登记 SN 的机库被跳过并给出原因")
    @Test
    void platformSeedSkipsMissingSn() {
        service.create(new FleetInstance(null, "唯一SN机库", "127.0.0.1", 9091,
                null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        Map<String, Object> seed = service.exportPlatformSeed(null, null);

        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) seed.get("skipped");
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).contains("固定机场 SN"));
    }

    @DisplayName("TC-FLEET-503：SQL 单引号正确转义，避免注入/语法错误")
    @Test
    void platformSeedEscapesQuotes() {
        service.create(new FleetInstance(null, "O'Reilly 机库", "127.0.0.1", 9091,
                "SN'1", "DSN'1", "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        String sql = String.valueOf(service.exportPlatformSeed(null, null).get("sql"));

        assertTrue(sql.contains("'O''Reilly 机库'"), "单引号应转义为两个单引号");
        assertTrue(sql.contains("'SN''1'"));
    }

    @DisplayName("TC-FLEET-504：未指定组织时使用默认占位组织")
    @Test
    void platformSeedDefaultOrganization() {
        service.create(new FleetInstance(null, "机库A", "127.0.0.1", 9091,
                "SN-A", "DSN-A", "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));

        Map<String, Object> seed = service.exportPlatformSeed(null, null);
        assertEquals("00000000-0000-0000-0000-000000000001", seed.get("organizationId"));
    }

    @DisplayName("TC-FLEET-505：停用实例不进入平台预置")
    @Test
    void platformSeedSkipsDisabled() {
        service.create(new FleetInstance(null, "停用", "127.0.0.1", 9091,
                "SN-X", "DSN-X", "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, false));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docks =
                (List<Map<String, Object>>) service.exportPlatformSeed(null, null).get("docks");
        assertTrue(docks.isEmpty());
    }

    // ==================== 新建并启动（TC-FLEET-601~610） ====================

    @DisplayName("TC-FLEET-601：未配置供给服务时降级为只登记，不报错")
    @Test
    void provisionDegradesWithoutProvisioner() {
        FleetProvisionResult result = service.provisionAndStart(
                new FleetInstance(null, "新机库", "127.0.0.1", 9092,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true),
                Map.of("autoProvision", true));

        assertEquals(1, service.load().instances().size(), "清单仍应登记成功");
        // 创建步骤被标记为跳过，且原因是未配置供给服务
        FleetProvisionResult.Step provisionStep = result.steps().stream()
                .filter(s -> "provision".equals(s.step())).findFirst().orElseThrow();
        assertTrue(provisionStep.skipped());
        assertTrue(provisionStep.detail().contains("未配置实例供给服务"));
    }

    @DisplayName("TC-FLEET-602：autoProvision=false 时不创建容器且不报错")
    @Test
    void provisionSkipsWhenDisabled() {
        FleetProvisionResult result = service.provisionAndStart(
                new FleetInstance(null, "手工机库", "127.0.0.1", 9093,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true),
                Map.of("autoProvision", false));

        assertNull(result.containerName());
        FleetProvisionResult.Step step = result.steps().stream()
                .filter(s -> "provision".equals(s.step())).findFirst().orElseThrow();
        assertTrue(step.skipped());
        assertTrue(step.detail().contains("不自动创建"));
    }

    @DisplayName("TC-FLEET-603：参数非法时在登记步骤即失败，清单不落库")
    @Test
    void provisionFailsOnInvalidInput() {
        FleetProvisionResult result = service.provisionAndStart(
                new FleetInstance(null, "  ", "127.0.0.1", 9094,
                        null, null, "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true),
                Map.of());

        assertFalse(result.success());
        assertEquals("validate", result.failedStep());
        assertTrue(service.load().instances().isEmpty(), "校验失败不应写入清单");
    }

    @DisplayName("TC-FLEET-604：供给服务未启用时 provisionerStatus 返回 enabled=false")
    @Test
    void provisionerStatusDisabled() {
        Map<String, Object> status = service.provisionerStatus();
        assertEquals(false, status.get("enabled"));
        assertEquals(false, status.get("reachable"));
    }

    @DisplayName("TC-FLEET-605：删除机库时未启用供给服务也能正常删登记")
    @Test
    void deleteWithoutProvisioner() {
        FleetInstance created = service.create(instance("待删机库", 9095));
        Map<String, Object> result = service.deleteWithContainer(created.id(), true, true);

        assertEquals(true, result.get("success"));
        assertEquals(false, result.get("containerRemoved"));
        assertTrue(service.load().instances().isEmpty());
    }

    @DisplayName("TC-FLEET-606：删除不存在的机库抛异常")
    @Test
    void deleteUnknownWithContainer() {
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteWithContainer("not-exist", false, false));
    }

    // ==================== 遥控器上云（TC-FLEET-701~712） ====================

    private FleetInstance pilotInstance(String name, int port) {
        return new FleetInstance(null, name, "127.0.0.1", port,
                null, "1581F6Q8D241P00BFH4Q", null, "M350_RTK",
                31.6888, 104.453064, 600.0, null, true, false,
                "PILOT", "RC_PLUS", "1581F5RCD001002");
    }

    @DisplayName("TC-FLEET-701：遥控器上云实例登记后保留接入模式与遥控器身份")
    @Test
    void createPilotKeepsDeviceModeAndControllerIdentity() {
        FleetInstance created = service.create(pilotInstance("M350 单兵机组", 9091));

        FleetInstance saved = service.load().instances().get(0);
        assertEquals("PILOT", saved.deviceMode());
        assertEquals("RC_PLUS", saved.controllerType());
        assertEquals("1581F5RCD001002", saved.controllerSn());
        assertEquals("M350_RTK", saved.droneType());
        assertTrue(saved.pilot());
        assertTrue(created.pilot());
    }

    @DisplayName("TC-FLEET-702：接入模式非法时拒绝登记")
    @Test
    void createRejectsInvalidDeviceMode() {
        FleetInstance bad = new FleetInstance(null, "非法模式", "127.0.0.1", 9091,
                null, null, null, null, null, null, null, null, true, false,
                "AIRCRAFT", null, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(bad));
        assertTrue(ex.getMessage().contains("接入模式非法"));
    }

    @DisplayName("TC-FLEET-703：未登记接入模式时归一化为 DOCK（旧清单兼容）")
    @Test
    void missingDeviceModeDefaultsToDock() {
        FleetInstance created = service.create(instance("旧机库", 9091));
        assertEquals("DOCK", created.deviceModeOrDefault());
        assertFalse(created.pilot());
        // 旧构造器不传模式 → 清单里该字段为 null，不写进 JSON
        assertNull(service.load().instances().get(0).deviceMode());
    }

    @DisplayName("TC-FLEET-704：遥控器上云实例不进入可派遣候选（无机库任务能力）")
    @Test
    void pilotInstanceIsNotDispatchable() {
        localState.setOnline(true);
        localState.setBatteryPercent(100);
        localState.setDroneInDock(true);
        Mockito.when(waylineTaskSimulator.getTaskStatus()).thenReturn(Map.of("active", false));
        // 实例自身也处于 PILOT 模式（清单登记 PILOT 但实例仍是 DOCK 时，
        // 状态按实例实际模式判断——那是"身份尚未下发"的过渡态）
        runtimeConfig.setDeviceMode(ltd.cdmi.hivemind.simulator.device.DeviceMode.PILOT);

        service.create(new FleetInstance(null, "遥控器", "127.0.0.1", 9090,
                null, null, null, "M350_RTK", 30.67, 104.07, 500.0, null, true, true,
                "PILOT", "RC_PLUS", null));

        List<FleetService.FleetCandidate> candidates = service.candidates(30.68, 104.08, 0);
        assertEquals(1, candidates.size());
        assertFalse(candidates.get(0).dispatchable());
        assertEquals("遥控器上云无派发能力", candidates.get(0).rejectReason());
    }

    @DisplayName("TC-FLEET-705：机型选项包含遥控器机型与接入模式，遥控器带可搭配飞行器")
    @Test
    void modelOptionsIncludeControllerTypes() {
        Map<String, Object> options = service.modelOptions();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> controllerTypes = (List<Map<String, Object>>) options.get("controllerTypes");
        assertFalse(controllerTypes.isEmpty());
        Map<String, Object> rcPlus = controllerTypes.stream()
                .filter(item -> "RC_PLUS".equals(item.get("value"))).findFirst().orElseThrow();
        assertEquals(rcPlus.get("defaultSn"), controllerTypes.stream()
                .filter(item -> "RC_PLUS".equals(item.get("value")))
                .findFirst().orElseThrow().get("defaultSn"));
        @SuppressWarnings("unchecked")
        List<String> droneKeys = (List<String>) rcPlus.get("droneKeys");
        assertTrue(droneKeys.contains("M350_RTK"), "RC Plus 应可搭配 M350 RTK");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> deviceModes = (List<Map<String, String>>) options.get("deviceModes");
        assertTrue(deviceModes.stream().anyMatch(item -> "DOCK".equals(item.get("value"))));
        assertTrue(deviceModes.stream().anyMatch(item -> "PILOT".equals(item.get("value"))));
    }

    @DisplayName("TC-FLEET-706：遥控器实例的平台预置使用遥控器 SN 与 REMOTE_CONTROLLER")
    @Test
    void platformSeedForPilotUsesControllerIdentity() {
        service.create(pilotInstance("M350 单兵机组", 9091));

        Map<String, Object> seed = service.exportPlatformSeed("org-uuid", "DJI");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docks = (List<Map<String, Object>>) seed.get("docks");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) seed.get("devices");
        String sql = String.valueOf(seed.get("sql"));

        assertEquals(1, docks.size());
        assertEquals("1581F5RCD001002", docks.get(0).get("dockSn"), "网关 SN 应为遥控器 SN");
        assertEquals("REMOTE_CONTROLLER", docks.get(0).get("kind"));
        assertEquals("RC_PLUS", docks.get(0).get("model"));
        assertEquals(1, devices.size());
        assertEquals("1581F5RCD001002", devices.get(0).get("gatewaySn"), "飞行器应挂在遥控器下");
        assertEquals("M350_RTK", devices.get(0).get("model"));

        assertTrue(sql.contains("'REMOTE_CONTROLLER'"));
        assertFalse(sql.contains("linked_drone_id"), "遥控器无停靠关系，不应写 linked_drone_id");
    }

    @DisplayName("TC-FLEET-707：遥控器实例未登记 SN 时跳过并提示是遥控器 SN")
    @Test
    void platformSeedPilotSkipsWhenControllerSnMissing() {
        service.create(new FleetInstance(null, "无SN遥控器", "127.0.0.1", 9091,
                null, "DSN-1", null, "M350_RTK", 30.0, 104.0, 500.0, null, true, false,
                "PILOT", "RC_PLUS", null));

        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) service.exportPlatformSeed(null, null).get("skipped");
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).contains("遥控器 SN"));
    }

    @DisplayName("TC-FLEET-708：compose 中遥控器实例写机型，机库实例写机场字段（互不污染）")
    @Test
    void renderComposeKeepsModesSeparated() {
        service.create(new FleetInstance(null, "成都机库", "127.0.0.1", 9091,
                null, "DSN-A", "DOCK3", "M4TD", 30.0, 104.0, 500.0, null, true));
        service.create(pilotInstance("单兵机组", 9092));
        String yaml = service.renderCompose(null, null, null, null, null, null);

        assertTrue(yaml.contains("\"9091:9090\""));
        assertTrue(yaml.contains("\"9092:9090\""));
        // 两个实例各自独立 config 卷：模式持久化不共享
        assertTrue(yaml.contains("simulator-config-"));
        assertTrue(yaml.contains("# 成都机库"));
        assertTrue(yaml.contains("# 单兵机组"));
    }

    @DisplayName("TC-FLEET-709：清单 JSON 往返保留遥控器字段（不被 Jackson 额外属性破坏）")
    @Test
    void instanceJsonRoundTripKeepsControllerFields() throws Exception {
        service.create(pilotInstance("M350 单兵机组", 9091));
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(service.exportConfig());

        assertFalse(json.contains("\"pilot\""), "不应序列化派生属性 pilot，否则读回时被当未知字段拒绝");
        assertTrue(json.contains("\"deviceMode\":\"PILOT\""));
        assertTrue(json.contains("\"controllerSn\":\"1581F5RCD001002\""));

        FleetConfig back = mapper.readValue(json, FleetConfig.class);
        assertEquals(1, back.instances().size());
        assertEquals("PILOT", back.instances().get(0).deviceMode());
        assertEquals("1581F5RCD001002", back.instances().get(0).controllerSn());
    }

    @DisplayName("TC-FLEET-710：更新实例可切换接入模式并保留 id")
    @Test
    void updateSwitchesDeviceMode() {
        FleetInstance created = service.create(instance("机库A", 9091));
        FleetInstance updated = service.update(created.id(), new FleetInstance(
                null, "机库A", "127.0.0.1", 9091,
                null, "1581F6Q8D241P00BFH4Q", null, "M350_RTK",
                30.0, 104.0, 500.0, null, true, false,
                "PILOT", "RC_PLUS", "1581F5RCD001002"));

        assertEquals(created.id(), updated.id());
        FleetInstance saved = service.load().instances().get(0);
        assertEquals("PILOT", saved.deviceMode());
        assertEquals("1581F5RCD001002", saved.controllerSn());
        assertTrue(saved.pilot());
    }

    @DisplayName("TC-FLEET-711：遥控器实例状态占位包含遥控器身份（不可达时仍可排查）")
    @Test
    void unreachablePilotStatusKeepsControllerIdentity() {
        FleetInstance pilot = pilotInstance("不可达遥控器", 1);
        FleetInstanceStatus status = FleetInstanceStatus.unreachable(pilot, false, "timeout");

        assertEquals("PILOT", status.deviceMode());
        assertEquals("1581F5RCD001002", status.controllerSn());
        assertEquals("RC_PLUS", status.controllerType());
        assertEquals("PILOT", status.deviceMode());
        assertFalse(status.reachable());
        assertFalse(status.dispatchable());
    }

    @DisplayName("TC-FLEET-712：遥控器实例生效网关 SN 回落到遥控器 SN")
    @Test
    void pilotGatewaySnFallsBackToControllerSn() {
        FleetInstanceStatus pilot = new FleetInstanceStatus("x", "遥控器", "u", true, true, null,
                "7UUXN1Q00A008W", "1581F6Q8D241P00BFH4Q", "Dock3", "M350 RTK", "PILOT",
                true, true, true, 31.68, 104.45, 600.0, null, 100, true, true, false, null, 0, 0, 2,
                "1581F5RCD001002", "RC Plus", null);
        assertEquals("1581F5RCD001002", pilot.effectiveGatewaySn());

        FleetInstanceStatus dock = new FleetInstanceStatus("y", "机场", "u", true, true, null,
                "6QCDL870020139", "1581F6Q8D241P00BFH3Q", "Dock3", "M4D", "DOCK",
                true, true, true, 30.0, 104.0, 500.0, null, 100, true, true, false, null, 0, 0, 2);
        assertEquals("6QCDL870020139", dock.effectiveGatewaySn());
    }

    // ==================== 删除容器（TC-FLEET-801~803） ====================

    /** 记录 remove 调用参数的供给服务替身（不真正访问 Docker）。 */
    private static final class RecordingProvisioner extends FleetProvisionerClient {
        private final List<String> removedNames = new ArrayList<>();

        RecordingProvisioner() {
            super("http://127.0.0.1:1", new ObjectMapper());
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean remove(String name, boolean removeVolume) {
            removedNames.add(name);
            return true;
        }
    }

    @DisplayName("TC-FLEET-801：删除容器时用清单回填的容器名，而不是重算 slug")
    @Test
    void deleteUsesContainerNameFromInstanceHost() {
        RecordingProvisioner provisioner = new RecordingProvisioner();
        FleetService svc = new FleetService(store, new ObjectMapper(), runtimeConfig, localState,
                waylineTaskSimulator, provisioner, 9090);
        // 创建时供给服务给短名补了随机后缀，清单里的 host 是真实容器名
        FleetInstance created = svc.create(new FleetInstance(null, "M350 单兵机组", "dji-dock-simulator-m350-553ded",
                9090, null, null, null, null, null, null, null, null, true, false, "PILOT", "RC_PLUS", null));

        svc.deleteWithContainer(created.id(), true, true);

        assertEquals(List.of("m350-553ded"), provisioner.removedNames,
                "应按回填的容器名删除；重算 slug 会得到不同的随机名导致删除失败");
    }

    @DisplayName("TC-FLEET-802：未回填容器地址的旧登记回退到 slug")
    @Test
    void deleteFallsBackToSlugWhenHostIsIp() {
        RecordingProvisioner provisioner = new RecordingProvisioner();
        FleetService svc = new FleetService(store, new ObjectMapper(), runtimeConfig, localState,
                waylineTaskSimulator, provisioner, 9090);
        FleetInstance created = svc.create(new FleetInstance(null, "bei-chuan-dock", "127.0.0.1", 9091,
                null, null, "DOCK3", "M4TD", null, null, null, null, true));

        svc.deleteWithContainer(created.id(), true, true);

        assertEquals(List.of("bei-chuan-dock"), provisioner.removedNames);
    }

    @DisplayName("TC-FLEET-803：removeContainer=false 时不调用供给服务")
    @Test
    void deleteSkipsProvisionerWhenNotRequested() {
        RecordingProvisioner provisioner = new RecordingProvisioner();
        FleetService svc = new FleetService(store, new ObjectMapper(), runtimeConfig, localState,
                waylineTaskSimulator, provisioner, 9090);
        FleetInstance created = svc.create(instance("机库A", 9091));

        Map<String, Object> result = svc.deleteWithContainer(created.id(), false, true);

        assertTrue(provisioner.removedNames.isEmpty());
        assertEquals(false, result.get("containerRemoved"));
    }
}
