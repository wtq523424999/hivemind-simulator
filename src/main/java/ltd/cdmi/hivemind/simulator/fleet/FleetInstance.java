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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 机库（模拟器实例）登记项。
 *
 * <p>一条记录描述一个独立的模拟器进程：它监听的控制台地址、接入模式（机场上云 / 遥控器上云）、
 * 自身对外声明的设备身份（机场/遥控器为网关，飞行器为子设备）、坐标与机型。控制台通过 HTTP
 * 访问该实例的 REST 接口完成开机、注册、上线、改坐标等操作，
 * 因此这里只保存"如何找到它"和"它应该是什么"，不保存运行期状态——
 * 运行期状态（MQTT 连接、在线、上报位置）每次由 {@link FleetService} 实时拉取，避免陈旧数据。</p>
 *
 * <p>SN 可以为空：实例启用唯一 SN 模式时，真实 SN 由实例自己生成并持久化，
 * 控制台展示时以远端 {@code /api/device-info} 返回值为准；这里的 sn 仅用于
 * "希望这台实例使用固定 SN"的场景（对应实例的 SN 手动覆盖）。</p>
 *
 * @param id         实例标识（控制台内部使用，创建后不可变）
 * @param name       机库显示名称
 * @param host       控制台主机名或 IP（从本机访问其他容器时通常是宿主机 IP 或容器名）
 * @param port       控制台端口（实例容器内 9090，宿主机映射端口随实例不同）
 * @param dockSn     期望的机场 SN（可空，空表示由实例自行决定）
 * @param droneSn    期望的飞行器 SN（可空）
 * @param dockType   机场机型枚举名（可空，空表示沿用实例当前机型）
 * @param droneType  飞行器机型枚举名（可空）
 * @param latitude   机场纬度（可空，空表示不改动实例位置）
 * @param longitude  机场经度（可空）
 * @param height     机场海拔（可空）
 * @param note       备注
 * @param enabled    是否纳入批量操作（停用的实例只展示、不参与批量动作）
 * @param selfInstance 是否登记为"当前控制台所在的这个实例"。
 *                 为 true 时：浏览器侧的 consoleUrl 仍用 host/port（可能是对外映射端口），
 *                 但服务端动作直接走内部回环 {@code 127.0.0.1:server.port}，
 *                 避免对外端口在容器内不可达（Docker 端口映射只作用在宿主机上）。
 * @param deviceMode 接入模式（{@code DOCK}=机场上云、{@code PILOT}=遥控器上云；空表示 DOCK）
 * @param controllerType 遥控器机型枚举名（PILOT 模式生效，空表示沿用实例当前机型）
 * @param controllerSn   期望的遥控器 SN（PILOT 模式的网关身份，空表示由实例自行决定）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FleetInstance(
        String id,
        String name,
        String host,
        int port,
        String dockSn,
        String droneSn,
        String dockType,
        String droneType,
        Double latitude,
        Double longitude,
        Double height,
        String note,
        Boolean enabled,
        Boolean selfInstance,
        String deviceMode,
        String controllerType,
        String controllerSn
) {

    /**
     * 兼容不含 deviceMode/controllerType/controllerSn 的构造器（旧配置文件与既有调用方）。
     */
    public FleetInstance(String id, String name, String host, int port,
                         String dockSn, String droneSn, String dockType, String droneType,
                         Double latitude, Double longitude, Double height,
                         String note, Boolean enabled, Boolean selfInstance) {
        this(id, name, host, port, dockSn, droneSn, dockType, droneType,
                latitude, longitude, height, note, enabled, selfInstance,
                null, null, null);
    }

    /**
     * 兼容不含 selfInstance 字段的构造器（旧配置文件与既有调用方）。
     */
    public FleetInstance(String id, String name, String host, int port,
                         String dockSn, String droneSn, String dockType, String droneType,
                         Double latitude, Double longitude, Double height,
                         String note, Boolean enabled) {
        this(id, name, host, port, dockSn, droneSn, dockType, droneType,
                latitude, longitude, height, note, enabled, null, null, null, null);
    }

    /** 接入模式（归一化为大写；未登记时为 DOCK，与实例默认模式一致）。 */
    public String deviceModeOrDefault() {
        return deviceMode == null || deviceMode.isBlank() ? "DOCK" : deviceMode.trim().toUpperCase();
    }

    /**
     * 是否为遥控器上云（单兵）模式。
     * <p>刻意不命名为 {@code isPilot}：Jackson 会把 {@code is*} 布尔方法当成 bean 属性
     * 序列化成 {@code "pilot"} 写进 fleet.json，而该字段不是 record 组件，读回时
     * 因"未知字段"整份清单解析失败。</p>
     */
    public boolean pilot() {
        return "PILOT".equals(deviceModeOrDefault());
    }

    /** 兼容旧配置文件（缺少 enabled 字段时视为启用）。 */
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    /** 是否显式标记为当前控制台实例。 */
    public boolean selfFlag() {
        return Boolean.TRUE.equals(selfInstance);
    }

    /** 控制台基础地址，供服务端反向调用该实例的 REST 接口。 */
    public String baseUrl() {
        String normalizedHost = host == null ? "" : host.trim();
        if (normalizedHost.startsWith("http://") || normalizedHost.startsWith("https://")) {
            return stripTrailingSlash(normalizedHost) + (port > 0 ? ":" + port : "");
        }
        return "http://" + normalizedHost + ":" + (port > 0 ? port : 9090);
    }

    /** 浏览器访问该实例控制台的地址（与 {@link #baseUrl()} 相同，语义上供前端直接打开）。 */
    public String consoleUrl() {
        return baseUrl() + "/";
    }

    /**
     * 服务端调用该实例时使用的地址。
     * <p>标记为本机实例时改走内部回环：容器部署下对外端口是宿主机映射端口，
     * 在容器内部访问 {@code host:port} 通常不通，而 {@code 127.0.0.1:server.port} 一定通。</p>
     *
     * @param selfPort 当前进程实际监听端口
     */
    public String internalBaseUrl(int selfPort) {
        return selfFlag() ? "http://127.0.0.1:" + selfPort : baseUrl();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
