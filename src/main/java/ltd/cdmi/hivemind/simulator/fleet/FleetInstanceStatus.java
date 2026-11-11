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

package ltd.cdmi.hivemind.simulator.fleet;

/**
 * 机库实例实时状态（内存投影，不持久化）。
 *
 * <p>由 {@link FleetService} 从目标实例的 {@code /api/fleet/node-summary}（单次请求取全量）
 * 聚合而来；对旧版本实例（无该端点）自动回退到分别请求
 * {@code /api/device-info} + {@code /api/connection} + {@code /api/location}。</p>
 *
 * <p>除展示用途外，{@code batteryPercent / droneInDock / taskActive} 是平台侧
 * 「按距离智能选库」的过滤依据：不可达、不在舱、电量过低、正在执行任务的机库
 * 都不应进入候选集。因此这些字段必须来自实时上报，不能由清单静态值推断。</p>
 *
 * @param id              实例标识
 * @param name            机库显示名称
 * @param consoleUrl      浏览器访问地址
 * @param self            是否为当前正在访问的这个实例
 * @param reachable       实例控制台是否可达
 * @param error           不可达原因（可达时为 null）
 * @param dockSn          当前生效机场 SN（优先取远端实时值）
 * @param droneSn         当前生效飞行器 SN
 * @param dockType        机场机型枚举名（简称，如 Dock3）
 * @param droneType       飞行器机型枚举名（简称，如 M4TD）
 * @param deviceMode      接入模式（DOCK / PILOT）
 * @param mqttConnected   MQTT 是否已连接
 * @param online          设备是否已上线
 * @param powered         是否已开机
 * @param latitude        机场纬度
 * @param longitude       机场经度
 * @param height          机场海拔
 * @param configuredDockSn 清单中登记的期望机场 SN（未登记为 null）
 * @param batteryPercent  飞行器电量百分比（可空：未激活/未知）
 * @param droneInDock     飞行器是否在舱（可空：旧实例不支持）
 * @param droneActivated  飞行器是否已激活（可空）
 * @param taskActive      是否有执行中的航线任务（可空：旧实例不支持）
 * @param flightId        当前任务 flight_id（无任务为 null）
 * @param dockModeCode    机场 mode_code（可空：0=空闲 4=作业中）
 * @param droneModeCode   飞行器 mode_code（可空）
 * @param chargeState     飞行器充电状态（0=未充 1=充电中 2=充满）
 * @param controllerSn    遥控器 SN（PILOT 模式的网关身份；DOCK 模式为实例配置值，仅供展示）
 * @param controllerType  遥控器机型枚举名（PILOT 模式网关机型）
 * @param gatewaySn       当前生效的网关 SN（DOCK 模式=机场 SN，PILOT 模式=遥控器 SN）
 */
public record FleetInstanceStatus(
        String id,
        String name,
        String consoleUrl,
        boolean self,
        boolean reachable,
        String error,
        String dockSn,
        String droneSn,
        String dockType,
        String droneType,
        String deviceMode,
        Boolean mqttConnected,
        Boolean online,
        Boolean powered,
        Double latitude,
        Double longitude,
        Double height,
        String configuredDockSn,
        Integer batteryPercent,
        Boolean droneInDock,
        Boolean droneActivated,
        Boolean taskActive,
        String flightId,
        Integer dockModeCode,
        Integer droneModeCode,
        Integer chargeState,
        String controllerSn,
        String controllerType,
        String gatewaySn
) {

    /**
     * 遥控器字段引入前的兼容构造器（controllerSn/controllerType/gatewaySn 为 null）。
     */
    public FleetInstanceStatus(String id, String name, String consoleUrl, boolean self,
                               boolean reachable, String error, String dockSn, String droneSn,
                               String dockType, String droneType, String deviceMode,
                               Boolean mqttConnected, Boolean online, Boolean powered,
                               Double latitude, Double longitude, Double height,
                               String configuredDockSn, Integer batteryPercent,
                               Boolean droneInDock, Boolean droneActivated, Boolean taskActive,
                               String flightId, Integer dockModeCode, Integer droneModeCode,
                               Integer chargeState) {
        this(id, name, consoleUrl, self, reachable, error, dockSn, droneSn, dockType, droneType,
                deviceMode, mqttConnected, online, powered, latitude, longitude, height,
                configuredDockSn, batteryPercent, droneInDock, droneActivated, taskActive,
                flightId, dockModeCode, droneModeCode, chargeState, null, null, null);
    }

    /** 当前生效网关 SN（DOCK=机场，PILOT=遥控器）；未知时回落到机场 SN。 */
    public String effectiveGatewaySn() {
        if (gatewaySn != null && !gatewaySn.isBlank()) {
            return gatewaySn;
        }
        return pilotMode() && controllerSn != null ? controllerSn : dockSn;
    }

    /**
     * 是否为遥控器上云（单兵）模式。
     * <p>不命名为 {@code isPilot}：Jackson 会把 {@code is*} 布尔方法当成 bean 属性
     * 额外序列化出 {@code "pilot"} 字段，前端按 {@code deviceMode} 判断更明确。</p>
     */
    public boolean pilotMode() {
        return "PILOT".equalsIgnoreCase(deviceMode);
    }

    /** 不可达实例的占位状态（坐标回落到清单登记值，便于排查"配错地址"与"没配坐标"两类问题）。 */
    public static FleetInstanceStatus unreachable(FleetInstance instance, boolean self, String error) {
        return new FleetInstanceStatus(
                instance.id(), instance.name(), instance.consoleUrl(), self, false, error,
                null, null, null, null, instance.deviceModeOrDefault(), null, null, null,
                instance.latitude(), instance.longitude(), instance.height(),
                instance.dockSn(),
                null, null, null, null, null, null, null, null,
                instance.controllerSn(), instance.controllerType(), null);
    }

    /**
     * 是否可作为智能选库的候选机库。
     *
     * <p>判据刻意保守：宁可少推荐，也不要把不能起飞的机库推给用户。
     * 只看"能确认的条件"——字段为 null（实例版本较旧、无从得知）时不因此排除，
     * 避免升级期误伤；明确为 false/超限的才排除。</p>
     *
     * @return true=可作为候选
     */
    public boolean dispatchable() {
        if (!reachable || !Boolean.TRUE.equals(online)) {
            return false;
        }
        // 遥控器上云（单兵）没有机库任务能力：航线由飞手在遥控器上执行，
        // 平台无法通过 flighttask_prepare 派发，因此不进入机库候选集
        if (pilotMode()) {
            return false;
        }
        if (latitude == null || longitude == null) {
            return false;
        }
        // 有任务在执行 → 不可再接单
        if (Boolean.TRUE.equals(taskActive)) {
            return false;
        }
        // 明确不在舱 → 不可起飞（null 表示未知，不排除）
        if (Boolean.FALSE.equals(droneInDock)) {
            return false;
        }
        // 电量低于 30% → 不足以支撑一次巡检往返（null 不排除）
        if (batteryPercent != null && batteryPercent < 30) {
            return false;
        }
        return true;
    }
}
