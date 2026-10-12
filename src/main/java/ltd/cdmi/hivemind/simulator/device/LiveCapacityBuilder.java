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
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造 {@code live_capacity}（pushMode=1，随 state topic 在能力变化时上报）。
 * <p>相机索引按机型解析，与 drone OSD payloads 的 payload_index 同源。
 * 当前镜头类型和机场相机舱位来自直播模拟器的推流状态：未推流时用默认值
 * （镜头 normal、舱位 0=舱内），推流或镜头/舱位切换后反映当前值。</p>
 */
public final class LiveCapacityBuilder {

    /** 机场相机枚举（Dock 舱内/舱外相机，非飞行器负载） */
    public static final String DOCK_CAMERA_INDEX = "165-0-7";

    private static final String VIDEO_INDEX = "normal-0";
    private static final List<String> DRONE_SWITCHABLE = List.of("normal", "wide", "zoom", "ir");
    private static final List<String> DOCK_SWITCHABLE = List.of("normal");

    private LiveCapacityBuilder() {
    }

    /**
     * Dock 网关的直播能力：机场相机 + 飞行器主相机。
     * 飞行器休眠时从 device_list 移除，可用路数只计机场这一路。
     *
     * @param videoType      飞行器主相机当前镜头（normal/wide/zoom/ir）
     * @param cameraPosition 机场相机舱位：0=舱内, 1=舱外
     */
    public static Map<String, Object> forDock(RuntimeConfig runtimeConfig, DeviceState state,
                                              String videoType, int cameraPosition) {
        List<Map<String, Object>> devices = new ArrayList<>();
        devices.add(dockCameraDevice(runtimeConfig.getDockSn(), cameraPosition));
        if (state.isDroneActivated()) {
            devices.add(droneCameraDevice(runtimeConfig, videoType));
        }
        return wrap(devices);
    }

    /**
     * Pilot 网关的直播能力：只有飞行器主相机（遥控器本身不是舱内相机）。
     */
    public static Map<String, Object> forPilot(RuntimeConfig runtimeConfig, String videoType) {
        return wrap(List.of(droneCameraDevice(runtimeConfig, videoType)));
    }

    private static Map<String, Object> wrap(List<Map<String, Object>> devices) {
        int available = 0;
        int coexistMax = 0;
        for (Map<String, Object> device : devices) {
            available += (Integer) device.get("available_video_number");
            coexistMax += (Integer) device.get("coexist_video_number_max");
        }
        Map<String, Object> liveCapacity = new LinkedHashMap<>();
        liveCapacity.put("available_video_number", available);
        liveCapacity.put("coexist_video_number_max", coexistMax);
        liveCapacity.put("device_list", devices);
        return liveCapacity;
    }

    private static Map<String, Object> dockCameraDevice(String dockSn, int cameraPosition) {
        // Dock1 无 live_camera_change，舱位固定舱内；调用方传入的值仅 Dock2/Dock3 有意义
        Map<String, Object> camera = camera(DOCK_CAMERA_INDEX, "normal", DOCK_SWITCHABLE);
        camera.put("availabe_camera_positions", List.of(0, 1));
        camera.put("camera_position", cameraPosition);
        return device(dockSn, 1, List.of(camera));
    }

    private static Map<String, Object> droneCameraDevice(RuntimeConfig runtimeConfig, String videoType) {
        String cameraIndex = DefaultCameraResolver.requireDefaultCameraIndex(
                runtimeConfig.getDroneType(), "live_capacity");
        String type = (videoType == null || videoType.isBlank()) ? "normal" : videoType;
        return device(runtimeConfig.getDroneSn(), 1, List.of(camera(cameraIndex, type, DRONE_SWITCHABLE)));
    }

    private static Map<String, Object> camera(String cameraIndex, String videoType, List<String> switchable) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("video_index", VIDEO_INDEX);
        video.put("video_type", videoType);
        video.put("switchable_video_types", switchable);

        Map<String, Object> camera = new LinkedHashMap<>();
        camera.put("camera_index", cameraIndex);
        camera.put("available_video_number", 1);
        camera.put("coexist_video_number_max", 1);
        camera.put("video_list", List.of(video));
        return camera;
    }

    private static Map<String, Object> device(String sn, int videoNumber, List<Map<String, Object>> cameras) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("sn", sn);
        device.put("available_video_number", videoNumber);
        device.put("coexist_video_number_max", videoNumber);
        device.put("camera_list", cameras);
        return device;
    }

    /** Dock1 不支持舱内/舱外切换，live_capacity 里舱位保持默认 0。 */
    public static int effectiveCameraPosition(RuntimeConfig runtimeConfig, int cameraPosition) {
        if (runtimeConfig.getDeviceMode() != DeviceMode.PILOT
                && runtimeConfig.getDockType() == DockModel.DOCK1) {
            return 0;
        }
        return cameraPosition;
    }
}
