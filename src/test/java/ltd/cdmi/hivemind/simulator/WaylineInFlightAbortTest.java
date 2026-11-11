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
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞行期异常中断测试。
 * <p>核心验证点：中断后任务必须真正停止，不能再发出 {@code in_progress}/{@code ok}，
 * 否则平台会看到"失败→继续→成功"的矛盾序列。
 */
class WaylineInFlightAbortTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
    }

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDockType()).thenReturn(DockModel.DOCK3);
        Mockito.when(rc.getDockSn()).thenReturn("DOCK3-SN");
        Mockito.when(rc.getGatewaySn()).thenReturn("DOCK3-SN");
        Mockito.when(rc.getLocationLatitude()).thenReturn(30.67);
        Mockito.when(rc.getLocationLongitude()).thenReturn(104.07);
        Mockito.when(rc.getLocationHeight()).thenReturn(500.0);
        return rc;
    }

    private WaylineTaskSimulator simulator(DeviceState state, MqttClientManager mqtt) {
        return new WaylineTaskSimulator(
                testProps(), mqtt, state, new ObjectMapper(),
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(MediaUploadSimulator.class),
                runtimeConfig(), Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(), Mockito.mock(HmsSimulator.class));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = WaylineTaskSimulator.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 构造一条最短的两点航线，供 routeEngine 进入 RUNNING 状态 */
    private static ltd.cdmi.hivemind.simulator.wayline.WaylinePlan minimalPlan() {
        var start = new ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Waypoint(
                30.67, 104.07, 50.0, 550.0, 10.0, 0);
        var end = new ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Waypoint(
                30.671, 104.071, 50.0, 550.0, 10.0, 1);
        var segment = new ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Segment(
                start, end, 10.0, 140.0, 140.0, 14.0, 0.0, 0.0);
        return new ltd.cdmi.hivemind.simulator.wayline.WaylinePlan(
                "test", java.util.List.of(start, end), java.util.List.of(segment),
                140.0, 14.0, 10.0, "goHome", 0.0);
    }

    /** 从 mqtt.publish 的 payload 中按 method 收集事件 */
    private List<JsonNode> eventsOf(MqttClientManager mqtt, ObjectMapper om, String method) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeastOnce()).publish(Mockito.anyString(), captor.capture());
        List<JsonNode> result = new ArrayList<>();
        for (String payload : captor.getAllValues()) {
            try {
                JsonNode n = om.readTree(payload);
                if (method.equals(n.path("method").asText())) {
                    result.add(n);
                }
            } catch (Exception ignored) {
                // 非 JSON payload 忽略
            }
        }
        return result;
    }

    @DisplayName("TC-ABORT-001：飞行中大风触发 failed + break_reason=784，且后续不再推进")
    @Test
    void windAbortsInFlightAndStopsProgress() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim = simulator(state, mqtt);

        // 置为飞行中，并让进度任务处于航线执行分支
        setField(sim, "currentFlightId", "FLIGHT-ABORT-1");
        setField(sim, "currentTrackId", "TRACK-ABORT-1");
        setField(sim, "currentStepIndex", 2);

        // 准备航线计划并启动（走 routeEngine，使 advanceProgress 进入 RUNNING 分支）
        Field engineField = WaylineTaskSimulator.class.getDeclaredField("routeEngine");
        engineField.setAccessible(true);
        var engine = (ltd.cdmi.hivemind.simulator.wayline.WaylineFlightEngine) engineField.get(sim);
        engine.prepare(minimalPlan());
        engine.start("TRACK-ABORT-1");

        // 触发环境异常
        state.setWindSpeed(15.0);

        Method advance = WaylineTaskSimulator.class.getDeclaredMethod("advanceProgress");
        advance.setAccessible(true);
        advance.invoke(sim);

        List<JsonNode> progressEvents = eventsOf(mqtt, om, "flighttask_progress");
        JsonNode failed = null;
        for (JsonNode e : progressEvents) {
            if ("failed".equals(e.path("data").path("output").path("status").asText())) {
                failed = e;
            }
        }
        assertNotNull(failed, "应上报 failed 状态的 flighttask_progress");
        assertEquals(AbnormalConditionChecker.BREAK_REASON_WIND_HIGH,
                failed.path("data").path("output").path("ext").path("break_point")
                        .path("break_reason").asInt());

        // 再推进一次：任务已终结，不应产生新的 flighttask_progress
        int before = eventsOf(mqtt, om, "flighttask_progress").size();
        advance.invoke(sim);
        assertEquals(before, eventsOf(mqtt, om, "flighttask_progress").size(),
                "中断后不应继续上报进度（否则出现 失败→继续→成功 矛盾序列）");
    }

    @DisplayName("TC-ABORT-003：无 KMZ 的兼容路径同样触发飞行期中断（回归）")
    @Test
    void compatPathWithoutKmzAlsoAborts() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim = simulator(state, mqtt);

        // 兼容路径：无 KMZ 计划，但飞行器已离舱且在飞（mode_code=5 航线飞行）
        setField(sim, "currentFlightId", "FLIGHT-COMPAT");
        setField(sim, "currentTrackId", "TRACK-COMPAT");
        setField(sim, "currentStepIndex", 2);
        state.setDroneInDock(false);
        state.setDroneModeCode(5);

        // 中途电量跌到阈值以下
        state.setBatteryPercent(10);

        Method advance = WaylineTaskSimulator.class.getDeclaredMethod("advanceProgress");
        advance.setAccessible(true);
        advance.invoke(sim);

        List<JsonNode> events = eventsOf(mqtt, om, "flighttask_progress");
        assertTrue(events.stream().anyMatch(e ->
                        "failed".equals(e.path("data").path("output").path("status").asText())),
                "无 KMZ 的兼容路径也必须触发 failed 中断");
    }

    @DisplayName("TC-ABORT-004：地面态（未离舱）不触发低电量中断")
    @Test
    void groundStateDoesNotAbort() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim = simulator(state, mqtt);

        setField(sim, "currentFlightId", "FLIGHT-GROUND");
        setField(sim, "currentStepIndex", 0);
        state.setDroneInDock(true);      // 仍在舱内
        state.setDroneModeCode(0);
        state.setBatteryPercent(10);     // 电量低

        Method advance = WaylineTaskSimulator.class.getDeclaredMethod("advanceProgress");
        advance.setAccessible(true);
        advance.invoke(sim);

        List<JsonNode> events = eventsOf(mqtt, om, "flighttask_progress");
        assertTrue(events.stream().noneMatch(e ->
                        "failed".equals(e.path("data").path("output").path("status").asText())),
                "地面态不应因低电量触发飞行期中断（那是准备期拒飞的职责）");
    }

    @DisplayName("TC-ABORT-002：手动 failed 上报在有任务时也会终止任务")
    @Test
    void manualFailedAbortsActiveTask() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        WaylineTaskSimulator sim = simulator(state, mqtt);
        setField(sim, "currentFlightId", "FLIGHT-ABORT-2");
        setField(sim, "currentTrackId", "TRACK-ABORT-2");
        setField(sim, "currentStepIndex", 2);

        assertTrue(sim.publishProgressFailedWithBreakReason(
                AbnormalConditionChecker.BREAK_REASON_BATTERY_LOW));

        List<JsonNode> progressEvents = eventsOf(mqtt, om, "flighttask_progress");
        assertTrue(progressEvents.stream().anyMatch(e ->
                        "failed".equals(e.path("data").path("output").path("status").asText())),
                "应上报 failed");
        assertFalse(eventsOf(mqtt, om, "return_home_info").isEmpty(),
                "应同时上报 return_home_info 供平台绘制返航轨迹");

        // 任务状态已清理
        Field f = WaylineTaskSimulator.class.getDeclaredField("currentFlightId");
        f.setAccessible(true);
        assertNull(f.get(sim), "中断后 currentFlightId 应被清空");
    }
}
