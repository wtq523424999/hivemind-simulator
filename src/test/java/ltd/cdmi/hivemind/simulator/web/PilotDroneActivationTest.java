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

package ltd.cdmi.hivemind.simulator.web;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.AirSenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.EsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightAreaSimulator;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.HmsSimulator;
import ltd.cdmi.hivemind.simulator.handler.LiveStreamSimulator;
import ltd.cdmi.hivemind.simulator.handler.MapElementSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.OtaSimulator;
import ltd.cdmi.hivemind.simulator.handler.PilotHttpSimulator;
import ltd.cdmi.hivemind.simulator.handler.PsdkSimulator;
import ltd.cdmi.hivemind.simulator.handler.RemoteLogSimulator;
import ltd.cdmi.hivemind.simulator.handler.SituationAwarenessSimulator;
import ltd.cdmi.hivemind.simulator.handler.UnlockLicenseSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.media.FfmpegInstaller;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;
import ltd.cdmi.hivemind.simulator.media.LocalFileUploadService;
import ltd.cdmi.hivemind.simulator.media.MediaSampleInitializer;
import ltd.cdmi.hivemind.simulator.mqtt.DrcConnectionManager;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.ws.MopClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 单兵（遥控器上云）飞行器激活/休眠切换测试。
 *
 * <p>遥控器上云时网关是遥控器、飞行器是子设备。控制台切换「激活/休眠」必须同时改变
 * 平台侧拓扑：休眠发空 sub_devices（飞行器下线），唤醒重发带飞行器的 update_topo
 * 并补推 drone state。此前 Pilot 模式被硬性限制为「始终激活」，这两步都不存在。</p>
 */
class PilotDroneActivationTest {

    private DeviceState state;
    private PilotOnlineService pilotService;
    private DockOnlineService dockService;
    private LiveStreamSimulator liveSimulator;
    private SimulatorController controller;

    @BeforeEach
    void setUp() {
        state = new DeviceState();
        state.setOnline(true);
        pilotService = mock(PilotOnlineService.class);
        dockService = mock(DockOnlineService.class);
        liveSimulator = mock(LiveStreamSimulator.class);
        controller = newController(DeviceMode.PILOT);
    }

    private SimulatorController newController(DeviceMode mode) {
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.getDeviceMode()).thenReturn(mode);
        return new SimulatorController(
                dockService, pilotService,
                state, mock(MqttClientManager.class), mock(WaylineTaskSimulator.class),
                liveSimulator, mock(MediaUploadSimulator.class),
                mock(HmsSimulator.class), mock(AirSenseSimulator.class),
                mock(FlightAreaSimulator.class), mock(UnlockLicenseSimulator.class),
                mock(PsdkSimulator.class), mock(EsdkSimulator.class),
                mock(RemoteLogSimulator.class), mock(OtaSimulator.class),
                mock(FlightCommandSimulator.class),
                mock(FfmpegWhipPusher.class), mock(FfmpegInstaller.class),
                mock(LocalFileUploadService.class), mock(MediaSampleInitializer.class),
                runtimeConfig, mock(SimulatorProperties.class),
                mock(DiagnosticLogRecorder.class), mock(CoverageRecorder.class),
                mock(ObjectMapper.class), mock(MapElementSimulator.class),
                mock(SituationAwarenessSimulator.class), mock(PilotHttpSimulator.class),
                mock(MopClient.class), mock(DrcConnectionManager.class),
                mock(ltd.cdmi.hivemind.simulator.device.StatePushService.class),
                mock(ltd.cdmi.hivemind.simulator.device.DockEnvStateStore.class));
    }

    @Test
    @DisplayName("单兵激活→休眠：发空 sub_devices 拓扑，不推 drone state")
    void pilotSleep_sendsEmptyTopo() {
        state.setDroneActivated(true);

        Map<String, Object> result = controller.updateState(Map.of("droneActivated", false));

        assertEquals(true, result.get("success"));
        assertFalse(state.isDroneActivated(), "休眠状态应写入 DeviceState");
        verify(pilotService).publishDroneSleepTopo();
        verify(pilotService, never()).resendTopo();
        verify(pilotService, never()).publishDroneState();
        verify(dockService, never()).publishDroneSleepTopo();
        verify(liveSimulator).publishLiveStateChange();
    }

    @Test
    @DisplayName("单兵休眠→激活：重发带飞行器的拓扑并补推 drone state")
    void pilotWake_resendsTopoAndDroneState() {
        state.setDroneActivated(false);

        Map<String, Object> result = controller.updateState(Map.of("droneActivated", true));

        assertEquals(true, result.get("success"));
        assertTrue(state.isDroneActivated(), "激活状态应写入 DeviceState");
        verify(pilotService).resendTopo();
        verify(pilotService).publishDroneState();
        verify(pilotService, never()).publishDroneSleepTopo();
        verify(dockService, never()).publishDroneState();
        verify(liveSimulator).publishLiveStateChange();
    }

    @Test
    @DisplayName("单兵未上线时切换只改本地状态，不发拓扑")
    void pilotOffline_togglesStateOnly() {
        state.setOnline(false);
        state.setDroneActivated(true);

        controller.updateState(Map.of("droneActivated", false));

        assertFalse(state.isDroneActivated());
        verifyNoInteractions(pilotService);
        verifyNoInteractions(liveSimulator);
    }

    @Test
    @DisplayName("机库模式仍走机场侧休眠/唤醒方法（未误伤机库链路）")
    void dockModeStillUsesDockServices() {
        controller = newController(DeviceMode.DOCK);
        state.setDroneActivated(true);

        controller.updateState(Map.of("droneActivated", false));

        verify(dockService).publishDroneSleepTopo();
        verify(pilotService, never()).publishDroneSleepTopo();

        state.setDroneActivated(false);
        controller.updateState(Map.of("droneActivated", true));
        verify(dockService).publishDroneState();
        verify(pilotService, never()).resendTopo();
    }
}
