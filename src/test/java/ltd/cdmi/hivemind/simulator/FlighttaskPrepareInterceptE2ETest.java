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
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker;
import ltd.cdmi.hivemind.simulator.handler.AuthFlowHandler;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator;
import ltd.cdmi.hivemind.simulator.handler.PayloadControlHandler;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteDebugSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.UnlockLicenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证：模拟 MQTT 入站的 flighttask_prepare，断言 services_reply 报文中的 result。
 *
 * <p>不复用 {@code handleWaylineCommand} 反射调用，而是走真实路由链：
 * {@code mqtt services topic → ServiceCommandHandler.handleService → routeCommand
 * → WaylineTaskSimulator.handleWaylineCommand → handlePrepare → publishServiceReply}。
 * 这样才能证明错误码真的出现在平台收到的报文里，而不只是 handler 返回值。
 */
class FlighttaskPrepareInterceptE2ETest {

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
        DeviceState s = new DeviceState();
        s.setBatteryPercent(100);
        s.setWindSpeed(3.0);
        s.setRainfall(0);
        s.setEnvironmentTemperature(25.0);
        s.setPowerSupplyNormal(true);
        s.setEmergencyStopPressed(false);
        s.setDockCalibrated(true);
        s.setDroneInDock(true);
        return s;
    }

    /**
     * 走完整链路下发一条 flighttask_prepare，返回 services_reply 的 data 节点。
     */
    private JsonNode dispatchPrepare(DeviceState state, HmsSimulator hms) throws Exception {
        ObjectMapper om = new ObjectMapper();
        RuntimeConfig rc = runtimeConfig();
        DockTopicSchema schema = new DockTopicSchema();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);

        ServiceCommandHandler router = new ServiceCommandHandler(
                testProps(), mqtt, state, om,
                Mockito.mock(FlightCommandSimulator.class),
                Mockito.mock(RemoteDebugSimulator.class),
                Mockito.mock(FlightAreaSimulator.class),
                Mockito.mock(UnlockLicenseSimulator.class),
                Mockito.mock(PsdkSimulator.class),
                Mockito.mock(EsdkSimulator.class),
                Mockito.mock(RemoteLogSimulator.class),
                Mockito.mock(OtaSimulator.class),
                Mockito.mock(DiagnosticLogRecorder.class),
                Mockito.mock(CoverageRecorder.class),
                rc, schema,
                Mockito.mock(PayloadControlHandler.class),
                Mockito.mock(AuthFlowHandler.class));

        // 用真实 router 作为 commandHandler，这样 wayline.init() 注册的处理器才会进入路由链
        WaylineTaskSimulator wayline = new WaylineTaskSimulator(
                testProps(), mqtt, state, om,
                router, Mockito.mock(MediaUploadSimulator.class),
                rc, Mockito.mock(DiagnosticLogRecorder.class), schema, hms);

        // 复现 @PostConstruct：把 handleWaylineCommand 注册进 router
        wayline.init();

        // 捕获监听注册，拿到真实入站回调
        ArgumentCaptor<MqttClientManager.MqttMessageListener> listenerCaptor =
                ArgumentCaptor.forClass(MqttClientManager.MqttMessageListener.class);
        router.init();
        Mockito.verify(mqtt).addListener(Mockito.anyString(), listenerCaptor.capture());
        MqttClientManager.MqttMessageListener listener = listenerCaptor.getValue();

        // 构造平台下发的 services 报文
        String payload = om.writeValueAsString(Map.of(
                "tid", "tid-e2e-1",
                "bid", "bid-e2e-1",
                "timestamp", System.currentTimeMillis(),
                "method", "flighttask_prepare",
                "data", Map.of("flight_id", "F-E2E-1", "rth_altitude", 100)));

        listener.onMessage(schema.topic(schema.services(), "DOCK3-SN"), payload);

        // 捕获 services_reply（publishJson 第二参数为 Object，内部再序列化）
        ArgumentCaptor<Object> replyCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(Mockito.anyString(), replyCaptor.capture());
        return om.valueToTree(replyCaptor.getValue()).path("data");
    }

    @DisplayName("TC-E2E-001：大风下 flighttask_prepare 返回 514135（services_reply 报文级验证）")
    @Test
    void prepareRepliesWindErrorCodeOverMqtt() throws Exception {
        DeviceState state = normalState();
        state.setWindSpeed(15.0);
        HmsSimulator hms = Mockito.mock(HmsSimulator.class);

        JsonNode data = dispatchPrepare(state, hms);

        assertEquals(AbnormalConditionChecker.ERR_WIND_HIGH, data.path("result").asInt(),
                "services_reply.data.result 应为 514135");
        assertFalse(data.has("output"), "拒飞回复不应带 output.status=ok 之类的成功语义");
        Mockito.verify(hms).publishAlarm(HmsSimulator.AlarmType.WIND_HIGH);
    }

    @DisplayName("TC-E2E-002：大风拒绝后机场保持待机，未进入作业模式")
    @Test
    void prepareBlockedKeepsDockIdle() throws Exception {
        DeviceState state = normalState();
        state.setWindSpeed(12.0);
        dispatchPrepare(state, Mockito.mock(HmsSimulator.class));

        assertEquals(0, state.getDockModeCode(), "dock mode_code 应仍为 0=空闲中");
        assertFalse(state.isCoverOpen(), "舱盖不应打开");
    }

    @DisplayName("TC-E2E-003：无异常时 flighttask_prepare 返回 0")
    @Test
    void preparePassesWhenNormal() throws Exception {
        DeviceState state = normalState();
        JsonNode data = dispatchPrepare(state, Mockito.mock(HmsSimulator.class));
        assertEquals(0, data.path("result").asInt());
    }

    @DisplayName("TC-E2E-004：不同异常返回各自错误码")
    @Test
    void differentConditionsReturnDistinctCodes() throws Exception {
        DeviceState rain = normalState();
        rain.setRainfall(3);
        assertEquals(514134, dispatchPrepare(rain, Mockito.mock(HmsSimulator.class))
                .path("result").asInt());

        DeviceState temp = normalState();
        temp.setEnvironmentTemperature(-25.0);
        assertEquals(514137, dispatchPrepare(temp, Mockito.mock(HmsSimulator.class))
                .path("result").asInt());

        DeviceState power = normalState();
        power.setPowerSupplyNormal(false);
        assertEquals(514136, dispatchPrepare(power, Mockito.mock(HmsSimulator.class))
                .path("result").asInt());

        DeviceState battery = normalState();
        battery.setBatteryPercent(20);
        assertEquals(514103, dispatchPrepare(battery, Mockito.mock(HmsSimulator.class))
                .path("result").asInt());

        assertTrue(true, "四个条件均返回各自错误码");
    }
}
