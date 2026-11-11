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

package ltd.cdmi.hivemind.simulator.handler;

import ltd.cdmi.hivemind.simulator.device.DeviceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbnormalConditionChecker} 单元测试。
 * <p>覆盖 Dock3 异常条件拦截的全部判定分支与边界值，
 * 错误码依据见 {@code docs/dock3-abnormal-flight-intercept.md}。
 */
class AbnormalConditionCheckerTest {

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
        state.setDebugMode(false);
        return state;
    }

    // ==================== 放行 ====================

    @DisplayName("TC-ABNORMAL-001：全部条件正常时放行")
    @Test
    void passWhenAllNormal() {
        var result = AbnormalConditionChecker.check(normalState(),
                AbnormalConditionChecker.Stage.PREPARE);
        assertFalse(result.blocked());
        assertEquals(0, result.resultCode());
        assertNull(result.alarm());
    }

    @DisplayName("TC-ABNORMAL-002：风速恰好 9 m/s 视为超限（≥9）")
    @Test
    void windAtLimitIsBlocked() {
        DeviceState state = normalState();
        state.setWindSpeed(9.0);
        var result = AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.EXECUTE);
        assertTrue(result.blocked());
        assertEquals(AbnormalConditionChecker.ERR_WIND_HIGH, result.resultCode());
        assertEquals(HmsSimulator.AlarmType.WIND_HIGH, result.alarm());
    }

    @DisplayName("TC-ABNORMAL-003：风速 8.9 m/s 不拦截")
    @Test
    void windBelowLimitPasses() {
        DeviceState state = normalState();
        state.setWindSpeed(8.9);
        assertFalse(AbnormalConditionChecker.check(state,
                AbnormalConditionChecker.Stage.PREPARE).blocked());
    }

    // ==================== 环境条件 ====================

    @DisplayName("TC-ABNORMAL-004：中雨及以上触发 514134")
    @Test
    void rainfallHeavyBlocked() {
        DeviceState state = normalState();
        state.setRainfall(2);
        var result = AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE);
        assertEquals(AbnormalConditionChecker.ERR_RAIN_HEAVY, result.resultCode());
        assertEquals(HmsSimulator.AlarmType.RAIN_HEAVY, result.alarm());
    }

    @DisplayName("TC-ABNORMAL-005：小雨不触发 514134")
    @Test
    void lightRainPasses() {
        DeviceState state = normalState();
        state.setRainfall(1);
        assertFalse(AbnormalConditionChecker.check(state,
                AbnormalConditionChecker.Stage.PREPARE).blocked());
    }

    @DisplayName("TC-ABNORMAL-006：低温 + 中雨优先返回 514173（结冰），而非 514134")
    @Test
    void icingRiskTakesPrecedenceOverRain() {
        DeviceState state = normalState();
        state.setEnvironmentTemperature(0.0);
        state.setRainfall(2);
        var result = AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE);
        assertEquals(AbnormalConditionChecker.ERR_ICING_RISK, result.resultCode());
    }

    @DisplayName("TC-ABNORMAL-007：低温但无雨不触发结冰，返回 514137")
    @Test
    void lowTemperatureWithoutRain() {
        DeviceState state = normalState();
        state.setEnvironmentTemperature(-25.0);
        state.setRainfall(0);
        var result = AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE);
        assertEquals(AbnormalConditionChecker.ERR_TEMPERATURE_LOW, result.resultCode());
        assertEquals(HmsSimulator.AlarmType.TEMPERATURE_LOW, result.alarm());
    }

    @DisplayName("TC-ABNORMAL-008：供电断开返回 514136")
    @Test
    void powerLostBlocked() {
        DeviceState state = normalState();
        state.setPowerSupplyNormal(false);
        var result = AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE);
        assertEquals(AbnormalConditionChecker.ERR_POWER_LOST, result.resultCode());
        assertEquals(HmsSimulator.AlarmType.POWER_LOST, result.alarm());
    }

    // ==================== 状态条件 ====================

    @DisplayName("TC-ABNORMAL-009：电量低于 30% 返回 514103")
    @Test
    void batteryLowBlocked() {
        DeviceState state = normalState();
        state.setBatteryPercent(29);
        var result = AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.EXECUTE);
        assertEquals(AbnormalConditionChecker.ERR_BATTERY_LOW, result.resultCode());
        assertEquals(HmsSimulator.AlarmType.BATTERY_LOW, result.alarm());
    }

    @DisplayName("TC-ABNORMAL-010：电量恰好 30% 放行（低于才拦）")
    @Test
    void batteryExactlyThirtyPasses() {
        DeviceState state = normalState();
        state.setBatteryPercent(30);
        assertFalse(AbnormalConditionChecker.check(state,
                AbnormalConditionChecker.Stage.EXECUTE).blocked());
    }

    @DisplayName("TC-ABNORMAL-011：急停按钮被按下返回 514121")
    @Test
    void emergencyStopBlocked() {
        DeviceState state = normalState();
        state.setEmergencyStopPressed(true);
        assertEquals(AbnormalConditionChecker.ERR_EMERGENCY_STOP,
                AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE).resultCode());
    }

    @DisplayName("TC-ABNORMAL-012：机场未标定返回 514168")
    @Test
    void notCalibratedBlocked() {
        DeviceState state = normalState();
        state.setDockCalibrated(false);
        assertEquals(AbnormalConditionChecker.ERR_DOCK_NOT_CALIBRATED,
                AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE).resultCode());
    }

    @DisplayName("TC-ABNORMAL-013：飞机不在舱内返回 514155")
    @Test
    void droneNotInDockBlocked() {
        DeviceState state = normalState();
        state.setDroneInDock(false);
        assertEquals(AbnormalConditionChecker.ERR_DRONE_NOT_IN_DOCK,
                AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.PREPARE).resultCode());
    }

    @DisplayName("TC-ABNORMAL-014：远程调试中返回 514146")
    @Test
    void remoteDebugBlocked() {
        DeviceState state = normalState();
        state.setDebugMode(true);
        assertEquals(AbnormalConditionChecker.ERR_REMOTE_DEBUG,
                AbnormalConditionChecker.check(state, AbnormalConditionChecker.Stage.EXECUTE).resultCode());
    }

    // ==================== 飞控期 ====================

    @DisplayName("TC-ABNORMAL-015：飞行期大风返回 break_reason=784")
    @Test
    void inFlightWindReturns784() {
        DeviceState state = normalState();
        state.setWindSpeed(12.0);
        assertEquals(AbnormalConditionChecker.BREAK_REASON_WIND_HIGH,
                AbnormalConditionChecker.checkInFlight(state));
    }

    @DisplayName("TC-ABNORMAL-016：飞行期电量 ≤20% 返回 break_reason=773")
    @Test
    void inFlightBatteryReturns773() {
        DeviceState state = normalState();
        state.setBatteryPercent(20);
        assertEquals(AbnormalConditionChecker.BREAK_REASON_BATTERY_LOW,
                AbnormalConditionChecker.checkInFlight(state));
    }

    @DisplayName("TC-ABNORMAL-017：飞行期正常返回 0")
    @Test
    void inFlightNormalReturnsZero() {
        assertEquals(0, AbnormalConditionChecker.checkInFlight(normalState()));
    }

    @DisplayName("TC-ABNORMAL-018：飞行期大风优先于低电量（先判风）")
    @Test
    void inFlightWindTakesPrecedence() {
        DeviceState state = normalState();
        state.setWindSpeed(15.0);
        state.setBatteryPercent(10);
        assertEquals(AbnormalConditionChecker.BREAK_REASON_WIND_HIGH,
                AbnormalConditionChecker.checkInFlight(state));
    }
}
