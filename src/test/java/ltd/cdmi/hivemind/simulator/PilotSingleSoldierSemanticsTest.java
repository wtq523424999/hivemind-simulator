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
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.error.DjiErrorCode;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单兵（遥控器上云）语义测试。
 *
 * <p>协议背景：上云 API 中遥控器是 domain=2 的网关设备，飞机是 domain=0 的子设备。
 * Pilot 侧只提供航线的 HTTP 列表/上传/下载，<b>没有</b> flighttask_prepare / flighttask_execute；
 * 这套方法只存在于机库 feature-set。因此平台把航线任务派给单兵时必须被显式拒绝，
 * 不能回 result=0 让平台误以为任务已被设备接管。</p>
 */
class PilotSingleSoldierSemanticsTest {

    private RuntimeConfig pilotConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDeviceMode()).thenReturn(DeviceMode.PILOT);
        Mockito.when(rc.getControllerType()).thenReturn(RcModel.RC_PLUS_2);
        Mockito.when(rc.getControllerSn()).thenReturn("RC-SN");
        Mockito.when(rc.getDroneSn()).thenReturn("DRONE-SN");
        Mockito.when(rc.getGatewaySn()).thenReturn("RC-SN");
        Mockito.when(rc.getDockType()).thenReturn(DockModel.DOCK3);
        Mockito.when(rc.getDroneType()).thenReturn(ltd.cdmi.dji.cloudapi.sdk.model.DroneModel.M4T);
        Mockito.when(rc.getLocationLatitude()).thenReturn(30.67);
        Mockito.when(rc.getLocationLongitude()).thenReturn(104.07);
        Mockito.when(rc.getLocationHeight()).thenReturn(500.0);
        return rc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeWayline(WaylineTaskSimulator sim, String method, String json)
            throws Exception {
        Method m = WaylineTaskSimulator.class.getDeclaredMethod(
                "handleWaylineCommand", String.class, JsonNode.class, String.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(sim, method,
                new ObjectMapper().readTree(json), "bid-test");
    }

    private WaylineTaskSimulator wayline(RuntimeConfig rc) {
        return new WaylineTaskSimulator(
                new ltd.cdmi.hivemind.simulator.config.SimulatorProperties(
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Location(30.67, 104.07, 500.0),
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Log(100),
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Live(false, "", "", null),
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Media("", false, 0, false, 0),
                        null, null, null, null, null),
                Mockito.mock(MqttClientManager.class), new DeviceState(), new ObjectMapper(),
                Mockito.mock(ServiceCommandHandler.class), Mockito.mock(MediaUploadSimulator.class),
                rc, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema(),
                Mockito.mock(HmsSimulator.class));
    }

    @DisplayName("单兵模式拒绝 flighttask_prepare，返回非零且不产生任务")
    @Test
    void rejectsFlighttaskPrepareInPilotMode() throws Exception {
        WaylineTaskSimulator sim = wayline(pilotConfig());

        Map<String, Object> reply = invokeWayline(sim, "flighttask_prepare",
                "{\"flight_id\":\"F-1\",\"rth_altitude\":100}");

        assertEquals(DjiErrorCode.OPERATION_NOT_SUPPORTED_IN_STATE, reply.get("result"),
                "单兵不具备 flighttask_prepare 能力，必须显式拒绝");
        assertFalse(reply.containsKey("output"), "拒绝回复不应携带成功语义的 output");
    }

    @DisplayName("单兵模式拒绝 flighttask_execute / pause / recovery / undo / stop")
    @Test
    void rejectsAllFlighttaskCommandsInPilotMode() throws Exception {
        for (String method : new String[]{
                "flighttask_execute", "flighttask_pause", "flighttask_recovery",
                "flighttask_undo", "flighttask_stop"}) {
            WaylineTaskSimulator sim = wayline(pilotConfig());
            Map<String, Object> reply = invokeWayline(sim, method, "{\"flight_id\":\"F-1\"}");
            assertEquals(DjiErrorCode.OPERATION_NOT_SUPPORTED_IN_STATE, reply.get("result"),
                    method + " 在单兵模式下应被拒绝");
        }
    }

    @DisplayName("机库模式仍正常处理 flighttask_prepare（未误伤机库链路）")
    @Test
    void dockModeStillHandlesFlighttaskPrepare() throws Exception {
        RuntimeConfig rc = pilotConfig();
        Mockito.when(rc.getDeviceMode()).thenReturn(DeviceMode.DOCK);
        Mockito.when(rc.getGatewaySn()).thenReturn("DOCK-SN");
        WaylineTaskSimulator sim = wayline(rc);

        // 无异常条件时 prepare 应进入正常流程（可能因 KMZ 文件缺失而失败，但不应是"单兵不支持"码）
        Map<String, Object> reply = invokeWayline(sim, "flighttask_prepare", "{\"flight_id\":\"F-2\"}");
        assertNotEquals(DjiErrorCode.OPERATION_NOT_SUPPORTED_IN_STATE, reply.get("result"),
                "机库模式不应返回单兵专用拒绝码");
    }

    @DisplayName("HMS 告警在单兵模式下发到遥控器 SN 的 events 通道")
    @Test
    void hmsUsesControllerSnInPilotMode() {
        RuntimeConfig rc = pilotConfig();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        HmsSimulator hms = new HmsSimulator(mqtt, new DeviceState(), rc, new DockTopicSchema());

        hms.publishAlarm(HmsSimulator.AlarmType.WIND_HIGH);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt).publish(topicCaptor.capture(), Mockito.anyString());
        String topic = topicCaptor.getValue();
        assertTrue(topic.contains("RC-SN"), "单兵告警应发到遥控器 SN: " + topic);
        assertFalse(topic.contains("DOCK-SN") || topic.contains("DRONE-SN"), "不应发到机库或飞机 SN: " + topic);
    }
}
