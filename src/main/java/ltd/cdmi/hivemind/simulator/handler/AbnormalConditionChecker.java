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

/**
 * 异常条件判定器：模拟真实机场在收到任务/起飞命令时的设备端前置检查。
 *
 * <p>真实机场的异常拦截分两个阶段，错误码体系完全不同：
 * <ul>
 *   <li><b>准备期</b>：拒飞，返回 {@code services_reply.result} 错误码（514xxx）。任务未开始，不产生断点。</li>
 *   <li><b>飞行期</b>：飞控自主返航，返回 {@code flighttask_progress.ext.break_point.break_reason}（773/784）。任务终结。</li>
 * </ul>
 *
 * <p>错误码全部来自 DJI Cloud API 官方错误码表（448 条），依据见
 * {@code docs/dock3-abnormal-flight-intercept.md}。
 *
 * <p>判定顺序按官方错误码编号升序（先报结构性状态错误，再报环境错误），
 * 唯一例外是 {@code 514173}（低温+中雨结冰）排在 {@code 514134}（雨量过大）之前——
 * 前者是后者的更精确前提（temp&lt;5 且 rain≥中雨），先判才能给出准确文案。
 */
public final class AbnormalConditionChecker {

    // ==================== 准备期错误码（services_reply.result） ====================

    /** 飞行器电量低于 30%，无法执行飞行任务 */
    public static final int ERR_BATTERY_LOW = 514103;
    /** 机场急停按钮被按下 */
    public static final int ERR_EMERGENCY_STOP = 514121;
    /** 雨量过大，机场无法执行飞行任务 */
    public static final int ERR_RAIN_HEAVY = 514134;
    /** 风速过大，机场无法执行飞行任务 */
    public static final int ERR_WIND_HIGH = 514135;
    /** 机场供电断开 */
    public static final int ERR_POWER_LOST = 514136;
    /** 环境温度低于 -20℃ */
    public static final int ERR_TEMPERATURE_LOW = 514137;
    /** 机场处于远程调试中 */
    public static final int ERR_REMOTE_DEBUG = 514146;
    /** 飞机不在舱内 */
    public static final int ERR_DRONE_NOT_IN_DOCK = 514155;
    /** 机场未标定 */
    public static final int ERR_DOCK_NOT_CALIBRATED = 514168;
    /** 低温且降雨≥中雨，桨叶结冰风险 */
    public static final int ERR_ICING_RISK = 514173;

    // ==================== 飞行期 break_reason ====================

    /** 低电量返航导致航线中断 */
    public static final int BREAK_REASON_BATTERY_LOW = 773;
    /** 大风返航导致航线中断 */
    public static final int BREAK_REASON_WIND_HIGH = 784;

    // ==================== 阈值常量 ====================

    /** 风速拒飞阈值（m/s）。依据 hms.json「风速过大（≥9 m/s），无法执行飞行任务」 */
    public static final double WIND_LIMIT_MPS = 9.0;
    /** 环境温度下限（℃）。依据 514137「环境温度过低于-20℃」 */
    public static final double TEMPERATURE_MIN_C = -20.0;
    /** 结冰风险的温度上限（℃）。依据 514173「环境温度低于5度并且降雨大于等于中雨」 */
    public static final double ICING_TEMP_MAX_C = 5.0;
    /** 「雨量过大」对应的降雨等级下限。依据 514173「降雨大于等于中雨」推定，官方未逐字确认（M-2） */
    public static final int RAINFALL_HEAVY_LEVEL = 2;
    /** 起飞前最低电量（%）。依据 514103「飞行器电量低于30%」 */
    public static final int BATTERY_MIN_PERCENT = 30;
    /** 飞行期低电量返航阈值（%）。对齐 OSD serious_low_battery_warning_threshold=20 */
    public static final int BATTERY_RTH_PERCENT = 20;

    /** 降雨等级：中雨 */
    private static final int RAINFALL_MODERATE = 2;

    private AbnormalConditionChecker() {
    }

    /**
     * 前置检查阶段。不同阶段同一状态字段的处理不同，例如「机场在作业中」在 execute 阶段是正常状态。
     */
    public enum Stage {
        /** flighttask_prepare：任务准备 */
        PREPARE,
        /** flighttask_execute / takeoff_to_point：执行/起飞 */
        EXECUTE
    }

    /**
     * 判定结果。{@code resultCode=0} 表示放行；非 0 时 {@code alarm} 为需联动上报的 HMS 告警（可为 null）。
     *
     * @param resultCode 非 0 时为官方错误码，直接作为 services_reply.result 返回
     * @param alarm      需联动上报的 HMS 告警类型
     * @param reason     拒绝原因（中文，用于日志）
     */
    public record BlockResult(int resultCode, HmsSimulator.AlarmType alarm, String reason) {

        /** 是否拦截 */
        public boolean blocked() {
            return resultCode != 0;
        }

        /** 放行 */
        public static BlockResult pass() {
            return new BlockResult(0, null, "");
        }
    }

    /**
     * 准备期/起飞前异常条件检查。
     *
     * @param state 设备状态
     * @param stage 阶段（PREPARE / EXECUTE）
     * @return 判定结果，{@link BlockResult#blocked()} 为 true 时应拒绝执行并回传该错误码
     */
    public static BlockResult check(DeviceState state, Stage stage) {
        // 514103 电量低于 30%
        if (state.getBatteryPercent() < BATTERY_MIN_PERCENT) {
            return new BlockResult(ERR_BATTERY_LOW, HmsSimulator.AlarmType.BATTERY_LOW,
                    "飞行器电量 " + state.getBatteryPercent() + "% 低于 " + BATTERY_MIN_PERCENT + "%");
        }
        // 514121 急停按钮被按下
        if (state.isEmergencyStopPressed()) {
            return new BlockResult(ERR_EMERGENCY_STOP, null, "机场急停按钮被按下");
        }
        // 514173 低温 + 降雨≥中雨 → 桨叶结冰风险（比 514134 更精确，故先判）
        if (state.getEnvironmentTemperature() < ICING_TEMP_MAX_C
                && state.getRainfall() >= RAINFALL_MODERATE) {
            return new BlockResult(ERR_ICING_RISK, HmsSimulator.AlarmType.RAIN_HEAVY,
                    "环境温度 " + state.getEnvironmentTemperature() + "℃ 低于 "
                            + ICING_TEMP_MAX_C + "℃ 且降雨等级 " + state.getRainfall() + " ≥ 中雨");
        }
        // 514134 雨量过大
        if (state.getRainfall() >= RAINFALL_HEAVY_LEVEL) {
            return new BlockResult(ERR_RAIN_HEAVY, HmsSimulator.AlarmType.RAIN_HEAVY,
                    "降雨等级 " + state.getRainfall() + " ≥ " + RAINFALL_HEAVY_LEVEL);
        }
        // 514135 风速过大
        if (state.getWindSpeed() >= WIND_LIMIT_MPS) {
            return new BlockResult(ERR_WIND_HIGH, HmsSimulator.AlarmType.WIND_HIGH,
                    "风速 " + state.getWindSpeed() + " m/s ≥ " + WIND_LIMIT_MPS + " m/s");
        }
        // 514136 机场供电断开
        if (!state.isPowerSupplyNormal()) {
            return new BlockResult(ERR_POWER_LOST, HmsSimulator.AlarmType.POWER_LOST, "机场供电断开");
        }
        // 514137 环境温度低于 -20℃
        if (state.getEnvironmentTemperature() < TEMPERATURE_MIN_C) {
            return new BlockResult(ERR_TEMPERATURE_LOW, HmsSimulator.AlarmType.TEMPERATURE_LOW,
                    "环境温度 " + state.getEnvironmentTemperature() + "℃ 低于 " + TEMPERATURE_MIN_C + "℃");
        }
        // 514146 远程调试中
        if (state.isDebugMode()) {
            return new BlockResult(ERR_REMOTE_DEBUG, null, "机场处于远程调试中");
        }
        // 514155 飞机不在舱内
        if (!state.isDroneInDock()) {
            return new BlockResult(ERR_DRONE_NOT_IN_DOCK, null, "飞机不在舱内");
        }
        // 514168 机场未标定
        if (!state.isDockCalibrated()) {
            return new BlockResult(ERR_DOCK_NOT_CALIBRATED, null, "机场未标定");
        }
        return BlockResult.pass();
    }

    /**
     * 飞行期异常检查（任务已在执行中）。
     * <p>命中后应上报 {@code flighttask_progress(status=failed)} 携带对应 break_reason，
     * 并停止任务、进入返航。与准备期的「拒飞」是互斥的两条路径。
     *
     * @param state 设备状态
     * @return break_reason（0 表示无异常）
     */
    public static int checkInFlight(DeviceState state) {
        if (state.getWindSpeed() >= WIND_LIMIT_MPS) {
            return BREAK_REASON_WIND_HIGH;
        }
        if (state.getBatteryPercent() <= BATTERY_RTH_PERCENT) {
            return BREAK_REASON_BATTERY_LOW;
        }
        return 0;
    }
}
