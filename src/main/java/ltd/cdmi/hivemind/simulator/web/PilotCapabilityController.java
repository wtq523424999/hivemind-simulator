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
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;

import ltd.cdmi.hivemind.simulator.device.osd.DroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.DroneStateBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 遥控器上云（单兵）能力清单。
 *
 * <p>供前端「单兵无人机」面板展示：哪些遥控器可搭配哪些飞机、每种飞机的行为属性
 * （domain/type/sub_type、默认负载、是否热成像、OSD/State 字段集、可用能力）。
 * 全部由 SDK 枚举与本仓库的 Builder 支持矩阵推导，不在前端另存一份静态表，
 * 避免 SDK 升级或新增机型后前端与设备实际行为漂移。</p>
 *
 * <p>关于能力的边界：Pilot 侧只有航线的 HTTP 列表/上传/下载，没有
 * {@code flighttask_prepare} / {@code flighttask_execute}，任务由飞手在遥控器上手动执行。
 * 因此这里列出的能力不含自动任务派发。</p>
 */
@RestController
@RequestMapping("/api")
public class PilotCapabilityController {

    private final RuntimeConfig runtimeConfig;
    private final List<DroneOsdBuilder> droneOsdBuilders;
    private final List<DroneStateBuilder> droneStateBuilders;

    public PilotCapabilityController(RuntimeConfig runtimeConfig,
                                     List<DroneOsdBuilder> droneOsdBuilders,
                                     List<DroneStateBuilder> droneStateBuilders) {
        this.runtimeConfig = runtimeConfig;
        this.droneOsdBuilders = droneOsdBuilders;
        this.droneStateBuilders = droneStateBuilders;
    }

    /**
     * 单兵能力清单。
     * <p>返回结构：
     * <ul>
     *   <li>{@code controllers[]}：每个遥控器的型号标识与可搭配飞机</li>
     *   <li>{@code drones[]}：每种单兵飞机的行为属性</li>
     *   <li>{@code current}：当前运行配置（模式、遥控器、飞机），便于前端高亮</li>
     *   <li>{@code limitations}：单兵模式不支持的能力（避免平台误以为可自动派发）</li>
     * </ul>
     */
    @GetMapping("/pilot/capabilities")
    public Map<String, Object> capabilities() {
        List<Map<String, Object>> controllers = new ArrayList<>();
        for (RcModel rc : RcModel.values()) {
            List<String> drones = new ArrayList<>();
            for (DroneModel drone : DroneModel.values()) {
                if (DeviceCompatibility.isCompatible(rc, drone)) {
                    drones.add(drone.name());
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", rc.name());
            item.put("label", rc.displayName());
            item.put("shortName", rc.shortName());
            item.put("modelKey", rc.modelKey());
            item.put("domain", rc.domain());
            item.put("sn", rc.defaultSn());
            item.put("droneKeys", drones);
            controllers.add(item);
        }

        List<Map<String, Object>> drones = new ArrayList<>();
        Set<DroneModel> supported = new LinkedHashSet<>();
        for (RcModel rc : RcModel.values()) {
            for (DroneModel drone : DroneModel.values()) {
                if (DeviceCompatibility.isCompatible(rc, drone)) {
                    supported.add(drone);
                }
            }
        }
        for (DroneModel drone : supported) {
            drones.add(describeDrone(drone));
        }

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("deviceMode", runtimeConfig.getDeviceMode().name());
        current.put("controllerType", runtimeConfig.getControllerType().name());
        current.put("droneType", runtimeConfig.getDroneType().name());
        current.put("controllerSn", runtimeConfig.getControllerSn());
        current.put("droneSn", runtimeConfig.getDroneSn());

        List<String> limitations = List.of(
                "无 flighttask_prepare / flighttask_execute：航线由平台经 HTTP 下发，飞手在遥控器手动执行",
                "无机场能力：无舱盖/推杆/充电/空调/起降对中，无机场侧 OSD",
                "上行网关为遥控器 SN（domain=2），飞机为子设备（domain=0）"
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("controllers", controllers);
        result.put("drones", drones);
        result.put("current", current);
        result.put("limitations", limitations);
        return result;
    }

    /** 描述单兵飞机的行为属性（全部推导自型号枚举与 Builder 支持矩阵）。 */
    private Map<String, Object> describeDrone(DroneModel drone) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", drone.name());
        item.put("label", drone.displayName());
        item.put("shortName", drone.shortName());
        item.put("modelKey", drone.modelKey());
        item.put("domain", drone.domain());
        item.put("type", drone.type());
        item.put("subType", drone.subType());
        item.put("defaultSn", drone.defaultSn());

        PayloadType camera = DefaultCameraResolver.defaultCameraFor(drone);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", camera != null ? camera.name() : null);
        payload.put("cameraIndex", camera != null ? camera.cameraIndex() : null);
        payload.put("thermal", camera != null && camera.isThermal());
        item.put("defaultPayload", payload);

        item.put("osdBuilder", builderFamily(drone));
        item.put("stateBuilder", stateFamily(drone));
        item.put("capabilities", describeCapabilities(drone));
        return item;
    }

    /**
     * 该机型具备的能力。
     * <p>这里描述的是"单兵模式下设备端可被平台使用"的通道，全部为协议事实：
     * OSD / State / DRC 实时控制 / 直播 / 媒体 HTTP 回传 / 地图与态势。</p>
     */
    private List<String> describeCapabilities(DroneModel drone) {
        List<String> caps = new ArrayList<>();
        caps.add("OSD 遥测（thing/product/{sn}/osd）");
        caps.add("State 属性（pushMode=1）");
        caps.add("DRC 实时控制（摇杆/镜头/云台）");
        caps.add("直播推流（live_capacity / live_status）");
        caps.add("媒体 HTTP 回传（快传/上传回调）");
        caps.add("地图元素与态势感知（HTTP/WS）");
        if (hasSelectablePayload(drone)) {
            caps.add("可换装通用云台负载");
        }
        return caps;
    }

    /** 是否属于可换装通用云台的机型（M350/M300→H20 系列，M400→H30 系列）。 */
    private boolean hasSelectablePayload(DroneModel drone) {
        return drone == DroneModel.M350_RTK
                || drone == DroneModel.M300_RTK
                || drone == DroneModel.M400;
    }

    /** 该机型由哪个 OSD Builder 构造（不可用时返回 null）。 */
    private String builderFamily(DroneModel drone) {
        for (DroneOsdBuilder builder : droneOsdBuilders) {
            if (builder.supports(drone)) {
                return builder.aircraftFamily();
            }
        }
        return null;
    }

    /** 该机型由哪个 State Builder 构造（不可用时返回 null）。 */
    private String stateFamily(DroneModel drone) {
        for (DroneStateBuilder builder : droneStateBuilders) {
            if (builder.supports(drone)) {
                return builder.aircraftFamily();
            }
        }
        return null;
    }
}
