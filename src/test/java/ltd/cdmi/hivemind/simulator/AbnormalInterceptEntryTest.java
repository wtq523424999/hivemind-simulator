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
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常条件拦截的入口级测试：验证错误码真正进入 services_reply。
 * <p>覆盖航线任务（prepare/execute）与指令飞行（takeoff_to_point/fly_to_point）四个入口。
 */
class AbnormalInterceptEntryTest {

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

    private DeviceState normalState() {
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        state.setWindSpeed(3.0);
        state.setRainfall(0);
        state.setEnvironmentTemperature(25.0);
        state.setPowerSupplyNormal(true);
        state.setEmergencyStopPressed(false);
        state.setDockCalibrated(true);
        state.setDroneInDock(true);
        return state;
    }

    private WaylineTaskSimulator wayline(DeviceState state, HmsSimulator hms) {
        return new WaylineTaskSimulator(
                testProps(), Mockito.mock(MqttClientManager.class), state, new ObjectMapper(),
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(MediaUploadSimulator.class),
                runtimeConfig(), Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema(), hms);
    }

    private FlightCommandSimulator flight(DeviceState state, HmsSimulator hms) {
        return new FlightCommandSimulator(
                testProps(), Mockito.mock(MqttClientManager.class), state,
                runtimeConfig(), Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(), hms);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeWayline(WaylineTaskSimulator simulator, String method, JsonNode data)
            throws Exception {
        Method m = WaylineTaskSimulator.class.getDeclaredMethod(
                "handleWaylineCommand", String.class, JsonNode.class, String.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(simulator, method, data, "bid-test");
    }

    // ==================== 航线任务 ====================

    @DisplayName("TC-ABNORMAL-ENTRY-001：prepare 阶段风速过大返回 514135 并上报 HMS")
    @Test
    void prepareBlockedByWind() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        state.setWindSpeed(15.0);
        HmsSimulator hms = Mockito.mock(HmsSimulator.class);
        WaylineTaskSimulator sim = wayline(state, hms);

        Map<String, Object> reply = invokeWayline(sim, "flighttask_prepare",
                om.readTree("{\"flight_id\":\"F-1\"}"));

        assertEquals(AbnormalConditionChecker.ERR_WIND_HIGH, reply.get("result"));
        Mockito.verify(hms).publishAlarm(HmsSimulator.AlarmType.WIND_HIGH);
    }

    @DisplayName("TC-ABNORMAL-ENTRY-002：prepare 被拦截时不改变 dock 状态（可恢复拒绝）")
    @Test
    void prepareBlockedDoesNotMutateState() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        state.setRainfall(3);
        WaylineTaskSimulator sim = wayline(state, Mockito.mock(HmsSimulator.class));

        invokeWayline(sim, "flighttask_prepare", om.readTree("{\"flight_id\":\"F-2\"}"));

        assertEquals(0, state.getDockModeCode(), "拦截后机场应保持待机，不进入作业模式");
        assertFalse(state.isCoverOpen(), "拦截后舱盖不应打开");
    }

    @DisplayName("TC-ABNORMAL-ENTRY-003：execute 阶段电量不足返回 514103")
    @Test
    void executeBlockedByBattery() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        state.setBatteryPercent(15);
        HmsSimulator hms = Mockito.mock(HmsSimulator.class);
        WaylineTaskSimulator sim = wayline(state, hms);

        Map<String, Object> reply = invokeWayline(sim, "flighttask_execute",
                om.readTree("{\"flight_id\":\"F-3\"}"));

        assertEquals(AbnormalConditionChecker.ERR_BATTERY_LOW, reply.get("result"));
        Mockito.verify(hms).publishAlarm(HmsSimulator.AlarmType.BATTERY_LOW);
        assertEquals(0, state.getDroneModeCode(), "拦截后飞行器不应起飞");
    }

    @DisplayName("TC-ABNORMAL-ENTRY-004：条件正常时 prepare/execute 仍返回 0")
    @Test
    void normalConditionsPassPrepareAndExecute() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        WaylineTaskSimulator sim = wayline(state, Mockito.mock(HmsSimulator.class));

        assertEquals(0, invokeWayline(sim, "flighttask_prepare",
                om.readTree("{\"flight_id\":\"F-4\"}")).get("result"));
        assertEquals(0, invokeWayline(sim, "flighttask_execute",
                om.readTree("{\"flight_id\":\"F-4\"}")).get("result"));
    }

    // ==================== 指令飞行 ====================

    @DisplayName("TC-ABNORMAL-ENTRY-005：takeoff_to_point 风速过大返回 514135")
    @Test
    void takeoffBlockedByWind() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        state.setWindSpeed(10.0);
        HmsSimulator hms = Mockito.mock(HmsSimulator.class);
        FlightCommandSimulator sim = flight(state, hms);

        Map<String, Object> reply = sim.handle("takeoff_to_point", om.readTree("""
                {"flight_id":"T-1","target_latitude":12.2,"target_longitude":12.3,
                 "target_height":100,"security_takeoff_height":80,"rth_altitude":100,"rth_mode":1}
                """), "bid-t");

        assertEquals(AbnormalConditionChecker.ERR_WIND_HIGH, reply.get("result"));
        Mockito.verify(hms).publishAlarm(HmsSimulator.AlarmType.WIND_HIGH);
    }

    @DisplayName("TC-ABNORMAL-ENTRY-006：fly_to_point 温度过低返回 514137")
    @Test
    void flyToPointBlockedByTemperature() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        state.setEnvironmentTemperature(-30.0);
        FlightCommandSimulator sim = flight(state, Mockito.mock(HmsSimulator.class));

        Map<String, Object> reply = sim.handle("fly_to_point", om.readTree("""
                {"fly_to_id":"FLY-1","max_speed":10,
                 "points":[{"latitude":12.2,"longitude":12.3,"height":100}]}
                """), "bid-f");

        assertEquals(AbnormalConditionChecker.ERR_TEMPERATURE_LOW, reply.get("result"));
    }

    @DisplayName("TC-ABNORMAL-ENTRY-007：条件正常时 takeoff_to_point 返回 0")
    @Test
    void takeoffPassesWhenNormal() throws Exception {
        ObjectMapper om = new ObjectMapper();
        FlightCommandSimulator sim = flight(normalState(), Mockito.mock(HmsSimulator.class));

        Map<String, Object> reply = sim.handle("takeoff_to_point", om.readTree("""
                {"flight_id":"T-2","target_latitude":12.2,"target_longitude":12.3,
                 "target_height":100,"security_takeoff_height":80,"rth_altitude":100,"rth_mode":1}
                """), "bid-t2");

        assertEquals(0, reply.get("result"));
    }

    @DisplayName("TC-ABNORMAL-ENTRY-008：fly_to_point_stop 不受异常条件拦截")
    @Test
    void stopCommandNotBlocked() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeviceState state = normalState();
        state.setWindSpeed(20.0);
        FlightCommandSimulator sim = flight(state, Mockito.mock(HmsSimulator.class));

        Map<String, Object> reply = sim.handle("fly_to_point_stop",
                om.readTree("{}"), "bid-s");

        assertEquals(0, reply.get("result"), "停止命令不应被环境条件拦截");
    }
}
