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

package ltd.cdmi.hivemind.simulator.config;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 遥控器 SN 覆盖测试。
 *
 * <p>需求：机库上云与遥控器上云都能各自修改 SN。机库侧已有 dock/drone 覆盖，
 * 这里覆盖新增的遥控器（单兵网关）覆盖，重点是三条约束：
 * <ul>
 *   <li>覆盖优先于型号 defaultSn；清空后回退 defaultSn</li>
 *   <li>覆盖持久化，重启后恢复</li>
 *   <li>与机场 SN 覆盖相互独立，切换模式不会互相污染</li>
 * </ul>
 */
class ControllerSnOverrideTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(LiveConfigStore.PROP_CONFIG_DIR, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LiveConfigStore.PROP_CONFIG_DIR);
    }

    private RuntimeConfig newRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                new SimulatorProperties(null, null, null, null, null, null, null, null, null),
                new LiveConfigStore());
    }

    @Test
    @DisplayName("默认取遥控器型号 defaultSn")
    void defaultsToModelSn() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setControllerType(RcModel.RC_PLUS_2);
        assertEquals(RcModel.RC_PLUS_2.defaultSn(), rc.getControllerSn());
        assertNull(rc.getControllerSnOverride());
    }

    @Test
    @DisplayName("覆盖值优先于型号 defaultSn；清空后回退")
    void overrideTakesPrecedenceAndClears() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setControllerType(RcModel.RC_PLUS_2);

        assertTrue(rc.setControllerSnOverride("RC-CUSTOM-001"));
        assertEquals("RC-CUSTOM-001", rc.getControllerSn());
        assertEquals("RC-CUSTOM-001", rc.getControllerSnOverride());

        // 空串清除覆盖
        assertTrue(rc.setControllerSnOverride(""));
        assertEquals(RcModel.RC_PLUS_2.defaultSn(), rc.getControllerSn());
        assertNull(rc.getControllerSnOverride());
    }

    @Test
    @DisplayName("非法 SN 格式被拒绝，不改变生效值")
    void rejectsInvalidSn() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setControllerType(RcModel.RC_PLUS_2);
        String before = rc.getControllerSn();

        assertFalse(rc.setControllerSnOverride("bad/sn#with+chars"), "含 topic 保留字符应被拒绝");
        assertEquals(before, rc.getControllerSn());
        assertNull(rc.getControllerSnOverride());
    }

    @Test
    @DisplayName("覆盖持久化，重启后恢复")
    void overrideSurvivesRestart() {
        RuntimeConfig first = newRuntimeConfig();
        first.setControllerType(RcModel.RC_PLUS_2);
        first.setControllerSnOverride("RC-PERSIST-001");

        RuntimeConfig restarted = newRuntimeConfig();
        assertEquals("RC-PERSIST-001", restarted.getControllerSn(), "重启后应恢复遥控器 SN 覆盖");
    }

    @Test
    @DisplayName("遥控器 SN 与机场/飞行器 SN 覆盖相互独立")
    void controllerOverrideIsIndependentFromDockAndDrone() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setDockType(DockModel.DOCK3);
        rc.setDroneType(DroneModel.M4TD);
        rc.setControllerType(RcModel.RC_PLUS_2);

        rc.setDockSnOverride("DOCK-CUSTOM");
        rc.setDroneSnOverride("DRONE-CUSTOM");
        rc.setControllerSnOverride("RC-CUSTOM");

        assertEquals("DOCK-CUSTOM", rc.getDockSn());
        assertEquals("DRONE-CUSTOM", rc.getDroneSn());
        assertEquals("RC-CUSTOM", rc.getControllerSn());

        // 清除遥控器覆盖不应影响机库身份
        rc.setControllerSnOverride(null);
        assertEquals("DOCK-CUSTOM", rc.getDockSn());
        assertEquals("DRONE-CUSTOM", rc.getDroneSn());
        assertEquals(RcModel.RC_PLUS_2.defaultSn(), rc.getControllerSn());
    }

    @Test
    @DisplayName("单兵模式网关 SN 使用遥控器覆盖值")
    void gatewaySnFollowsControllerOverrideInPilotMode() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setControllerType(RcModel.RC_PLUS_2);
        rc.setControllerSnOverride("RC-GATEWAY-001");
        rc.setDeviceMode(DeviceMode.PILOT);

        assertEquals("RC-GATEWAY-001", rc.getGatewaySn(), "单兵网关 SN 应为遥控器覆盖值");
    }

    @Test
    @DisplayName("切换遥控器型号时保留用户显式设置的 SN 覆盖")
    void switchingControllerTypeKeepsOverride() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setControllerType(RcModel.RC_PLUS);
        rc.setControllerSnOverride("RC-KEEP-001");

        rc.setControllerType(RcModel.RC_PLUS_2);

        assertEquals("RC-KEEP-001", rc.getControllerSn(),
                "覆盖是用户显式指定的身份，切换型号不应静默丢弃");
    }

    @Test
    @DisplayName("遥控器上云身份（模式 + 型号 + 负载）持久化，重启后仍以遥控器为网关")
    void pilotIdentitySurvivesRestart() {
        RuntimeConfig first = newRuntimeConfig();
        first.setDeviceMode(DeviceMode.PILOT);
        first.setControllerType(RcModel.RC_PLUS);
        first.setControllerSnOverride("RC-PERSIST-002");
        first.setSelectedPayload(PayloadType.H20T);
        // 与 /api/connect 一致：整批更新后统一落盘
        first.persistLiveConfig();

        RuntimeConfig restarted = newRuntimeConfig();
        assertEquals(DeviceMode.PILOT, restarted.getDeviceMode(), "重启后应仍是遥控器上云");
        assertEquals(RcModel.RC_PLUS, restarted.getControllerType());
        assertEquals("RC-PERSIST-002", restarted.getControllerSn());
        assertEquals("RC-PERSIST-002", restarted.getGatewaySn(), "网关 SN 应为遥控器 SN");
        assertEquals(PayloadType.H20T, restarted.getSelectedPayload());
    }

    @Test
    @DisplayName("切回机场上云后网关身份恢复为机场 SN，遥控器配置不丢失")
    void switchingBackToDockKeepsControllerConfig() {
        RuntimeConfig rc = newRuntimeConfig();
        rc.setDeviceMode(DeviceMode.PILOT);
        rc.setControllerSnOverride("RC-PERSIST-003");
        rc.persistLiveConfig();
        assertEquals("RC-PERSIST-003", rc.getGatewaySn());

        rc.setDeviceMode(DeviceMode.DOCK);
        rc.persistLiveConfig();

        assertEquals(rc.getDockSn(), rc.getGatewaySn(), "切回机场上云后网关应为机场 SN");

        RuntimeConfig restarted = newRuntimeConfig();
        assertEquals(DeviceMode.DOCK, restarted.getDeviceMode());
        assertEquals("RC-PERSIST-003", restarted.getControllerSn(), "遥控器身份应保留，便于再次切换");
    }
}
