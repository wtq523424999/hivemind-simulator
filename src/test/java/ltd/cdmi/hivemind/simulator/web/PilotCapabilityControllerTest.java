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

import ltd.cdmi.dji.cloudapi.sdk.model.DeviceCompatibility;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.osd.M4StateBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M30DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M350DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M400DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4DDroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.MatriceStateBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.Mavic3DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.Mavic3StateBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单兵能力清单端点测试。
 * <p>核心约束：清单必须由 SDK 搭配表与 Builder 支持矩阵推导，不能出现前端静态表
 * 与设备实际行为不一致的情况。</p>
 */
class PilotCapabilityControllerTest {

    private PilotCapabilityController controller() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDeviceMode()).thenReturn(DeviceMode.PILOT);
        Mockito.when(rc.getControllerType()).thenReturn(RcModel.RC_PLUS_2);
        Mockito.when(rc.getDroneType()).thenReturn(DroneModel.M4T);
        Mockito.when(rc.getControllerSn()).thenReturn("RC-SN");
        Mockito.when(rc.getDroneSn()).thenReturn("DRONE-SN");
        return new PilotCapabilityController(rc,
                List.of(new Mavic3DroneOsdBuilder(), new M4DDroneOsdBuilder(),
                        new M350DroneOsdBuilder(), new M30DroneOsdBuilder(), new M400DroneOsdBuilder()),
                List.of(new Mavic3StateBuilder(), new M4StateBuilder(), new MatriceStateBuilder()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    @Test
    @DisplayName("遥控器搭配表与 SDK DeviceCompatibility 完全一致")
    void controllerDroneMatrixMatchesSdk() {
        List<Map<String, Object>> controllers = list(controller().capabilities(), "controllers");

        for (Map<String, Object> rc : controllers) {
            RcModel model = RcModel.valueOf((String) rc.get("key"));
            @SuppressWarnings("unchecked")
            List<String> droneKeys = (List<String>) rc.get("droneKeys");
            for (DroneModel drone : DroneModel.values()) {
                boolean sdkCompatible = DeviceCompatibility.isCompatible(model, drone);
                assertEquals(sdkCompatible, droneKeys.contains(drone.name()),
                        model + " 与 " + drone + " 的搭配关系应与 SDK 一致");
            }
        }
    }

    @Test
    @DisplayName("单兵机型清单不含仅机场侧机型，且每条都有 OSD/State Builder")
    void everyDroneHasBuilders() {
        for (Map<String, Object> drone : list(controller().capabilities(), "drones")) {
            String key = (String) drone.get("key");
            assertNotNull(drone.get("osdBuilder"), key + " 缺少 OSD Builder");
            assertNotNull(drone.get("stateBuilder"), key + " 缺少 State Builder");
            assertEquals(0, ((Number) drone.get("domain")).intValue(), key + " 飞机 domain 应为 0");
        }
    }

    @Test
    @DisplayName("默认负载按机型推导，热成像机型标记正确")
    void defaultPayloadDerivedFromModel() {
        Map<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> d : list(controller().capabilities(), "drones")) {
            byKey.put((String) d.get("key"), d);
        }

        assertEquals("89-0-0", payload(byKey.get("M4T")).get("cameraIndex"), "M4T 默认负载应为 M4T_CAMERA");
        assertEquals(true, payload(byKey.get("M4T")).get("thermal"));
        assertEquals("52-0-0", payload(byKey.get("M30")).get("cameraIndex"));
        assertEquals(false, payload(byKey.get("M30")).get("thermal"));
        assertEquals("42-0-0", payload(byKey.get("M350_RTK")).get("cameraIndex"), "M350 默认 H20");
        assertEquals("82-0-0", payload(byKey.get("M400")).get("cameraIndex"), "M400 默认 H30");
    }

    @Test
    @DisplayName("能力边界显式声明不含 flighttask_*")
    void limitationsMentionManualFlight() {
        @SuppressWarnings("unchecked")
        List<String> limitations = (List<String>) controller().capabilities().get("limitations");
        assertTrue(limitations.stream().anyMatch(s -> s.contains("flighttask_prepare")),
                "必须显式声明单兵无 flighttask_*，避免平台误以为可自动派发");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> drone) {
        return (Map<String, Object>) drone.get("defaultPayload");
    }
}
