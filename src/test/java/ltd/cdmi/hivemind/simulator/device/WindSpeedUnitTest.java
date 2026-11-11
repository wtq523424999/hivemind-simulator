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

package ltd.cdmi.hivemind.simulator.device;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.osd.Dock3OsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4DDroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.OsdContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * wind_speed 单位回归测试。
 *
 * <p><b>背景</b>：模拟器曾按「wind_speed 单位 0.1 m/s」在 OSD 上报前乘 10，
 * 导致 3.0 m/s 上报成 30.0。DJI Dock1/Dock2 properties 文档的字段表明确写明
 * {@code wind_speed | 风速 | float | {unit_name: "米每秒 / m/s"}}，飞行器 properties
 * 文档同样标注 float 无换算；平台侧（uav-platform）也按 m/s 直接消费并与
 * {@code maximumWindMps} 阈值比较，3 m/s 会被读成 30 m/s 而误判为大风拒飞。</p>
 *
 * <p>本测试锁定修复后的约定：{@code DeviceState.windSpeed} / {@code droneWindSpeed}
 * 内部单位 m/s，OSD 原样上报，不做任何倍数换算。</p>
 */
class WindSpeedUnitTest {

    private OsdContext ctx(double dockWindSpeed, double droneWindSpeed) {
        DeviceState state = new DeviceState();
        state.setWindSpeed(dockWindSpeed);
        state.setDroneWindSpeed(droneWindSpeed);
        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);
        Mockito.lenient().when(props.location()).thenReturn(
                new SimulatorProperties.Location(22.5, 113.9, 100.0));
        RuntimeConfig config = Mockito.mock(RuntimeConfig.class);
        Mockito.lenient().when(config.getDockType()).thenReturn(DockModel.DOCK3);
        Mockito.lenient().when(config.getDroneType()).thenReturn(DroneModel.M4D);
        Mockito.lenient().when(config.getLocationLatitude()).thenReturn(22.5);
        Mockito.lenient().when(config.getLocationLongitude()).thenReturn(113.9);
        Mockito.lenient().when(config.getLocationHeight()).thenReturn(100.0);
        Mockito.lenient().when(config.getDroneSn()).thenReturn("DRONE-SN");
        return new OsdContext(state, props, config);
    }

    private Object dockWindSpeed(Map<String, Object> ctx) {
        return ctx.get("wind_speed");
    }

    @Test
    @DisplayName("机场 OSD：windSpeed=3.0 m/s 原样上报 3.0，不再是 30")
    void dockOsdWindSpeedIsMetersPerSecond() {
        List<Map<String, Object>> groups = new Dock3OsdBuilder().buildDockOsd(ctx(3.0, 3.0));
        Object value = groups.stream()
                .filter(g -> g.containsKey("wind_speed"))
                .findFirst()
                .map(g -> dockWindSpeed(g))
                .orElse(null);
        assertEquals(3.0, value, "机场 wind_speed 应为 3.0 m/s（DJI 文档单位 m/s，不做 ×10）");
    }

    @Test
    @DisplayName("飞行器 OSD：droneWindSpeed=3.0 m/s 原样上报 3.0，不再是 30")
    void droneOsdWindSpeedIsMetersPerSecond() {
        Map<String, Object> osd = new M4DDroneOsdBuilder().buildDroneOsd(ctx(3.0, 3.0));
        assertEquals(3.0, osd.get("wind_speed"), "飞行器 wind_speed 应为 3.0 m/s（不做 ×10）");
    }

    @Test
    @DisplayName("拒飞阈值 9 m/s：windSpeed=8.9 放行、9.0 拦截（内部单位已是 m/s）")
    void windLimitUsesMetersPerSecond() {
        DeviceState calm = new DeviceState();
        calm.setWindSpeed(8.9);
        assertEquals(0, ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker
                .check(calm, ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker.Stage.PREPARE)
                .resultCode(), "8.9 m/s 未达阈值应放行");

        DeviceState windy = new DeviceState();
        windy.setWindSpeed(9.0);
        assertEquals(ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker.ERR_WIND_HIGH,
                ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker
                        .check(windy, ltd.cdmi.hivemind.simulator.handler.AbnormalConditionChecker.Stage.PREPARE)
                        .resultCode(), "9.0 m/s 达阈值应返回 514135");
    }
}
