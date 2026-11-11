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

import io.swagger.v3.oas.annotations.tags.Tag;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单实例摘要端点，供机库群控制台（{@link FleetController}）聚合多机库状态使用。
 *
 * <p>为什么单独做一个端点，而不是让控制台分别调 {@code /api/device-info}、
 * {@code /api/connection}、{@code /api/location}、{@code /api/state}、{@code /api/task}：</p>
 * <ul>
 *   <li>N 个机库 × 3~5 次请求，聚合一轮就是十几到几十次 HTTP；合并成 1 次后线性下降；</li>
 *   <li>各接口口径分散（有的字段在 state、有的在 device-info），合并后由实例自己保证一致性，
 *       控制台不需要知道每个字段出自哪个接口；</li>
 *   <li>智能选库需要电量/在舱/任务占用，这些字段此前只散落在 /api/state 与 /api/task，
 *       没有对外的聚合视图。</li>
 * </ul>
 *
 * <p>这是<b>新增</b>端点，不改动任何既有接口；旧版本实例没有它时，
 * {@link FleetService} 会自动回退到分接口请求。</p>
 */
@Tag(name = "机库群管理", description = "单实例摘要（供多机库控制台聚合状态）")
@RestController
@RequestMapping("/api/fleet")
public class FleetNodeController {

    private final RuntimeConfig runtimeConfig;
    private final DeviceState state;
    private final MqttClientManager mqtt;
    private final WaylineTaskSimulator waylineTaskSimulator;

    public FleetNodeController(RuntimeConfig runtimeConfig, DeviceState state,
                               MqttClientManager mqtt, WaylineTaskSimulator waylineTaskSimulator) {
        this.runtimeConfig = runtimeConfig;
        this.state = state;
        this.mqtt = mqtt;
        this.waylineTaskSimulator = waylineTaskSimulator;
    }

    /**
     * 本实例摘要：设备身份 + 连接状态 + 机场坐标 + 飞行器可用性 + 任务占用。
     *
     * <p>字段命名与 {@link FleetInstanceStatus} 对应，聚合方可直接映射，无需再解析各业务接口。</p>
     */
    @GetMapping("/node-summary")
    public Map<String, Object> nodeSummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        // ===== 设备身份 =====
        result.put("dockSn", runtimeConfig.getDockSn());
        result.put("droneSn", runtimeConfig.getDroneSn());
        result.put("dockType", runtimeConfig.getDockType() == null ? null : runtimeConfig.getDockType().shortName());
        result.put("droneType", runtimeConfig.getDroneType() == null ? null : runtimeConfig.getDroneType().shortName());
        result.put("deviceMode", runtimeConfig.getDeviceMode() == null ? null : runtimeConfig.getDeviceMode().name());
        // 遥控器身份：PILOT 模式下这才是网关（domain=2），控制台需要单独展示
        result.put("controllerSn", runtimeConfig.getControllerSn());
        result.put("controllerType", runtimeConfig.getControllerType() == null
                ? null : runtimeConfig.getControllerType().shortName());
        result.put("gatewaySn", runtimeConfig.getGatewaySn());

        // ===== 连接与电源 =====
        result.put("mqttConnected", mqtt.isConnected());
        result.put("online", state.isOnline());
        result.put("powered", state.isPowered());

        // ===== 机场坐标（选库距离计算的依据）=====
        result.put("latitude", runtimeConfig.getLocationLatitude());
        result.put("longitude", runtimeConfig.getLocationLongitude());
        result.put("height", runtimeConfig.getLocationHeight());

        // ===== 飞行器可用性（选库过滤依据）=====
        result.put("batteryPercent", state.getBatteryPercent());
        result.put("droneInDock", state.isDroneInDock());
        result.put("droneActivated", state.isDroneActivated());
        result.put("chargeState", state.getDroneChargeState());
        result.put("dockModeCode", state.getDockModeCode());
        result.put("droneModeCode", state.getDroneModeCode());

        // ===== 任务占用（选库过滤依据）=====
        Map<String, Object> task = waylineTaskSimulator.getTaskStatus();
        Object active = task.get("active");
        Object flightId = task.get("flight_id");
        result.put("taskActive", active);
        result.put("flightId", flightId);

        return result;
    }
}
