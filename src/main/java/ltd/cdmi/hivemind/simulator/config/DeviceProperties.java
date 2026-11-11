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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首启设备身份配置（{@code simulator.device.*}）。
 *
 * <p>为什么独立成类而不加进 {@link SimulatorProperties}：后者是 9 组件的 record，
 * 增加组件会改掉规范构造器签名，几十处测试与调用方都要跟着改；
 * 而这块配置语义上也独立——它只在"没有 live-config.json 时"提供初值，
 * 与其它 simulator.* 配置没有耦合。</p>
 *
 * <p>用途：多机库场景由 provisioner 注入
 * {@code SIMULATOR_DEVICE_MODE} / {@code SIMULATOR_CONTROLLER_TYPE}，
 * 让新建的遥控器实例首次启动就是遥控器身份，不必等控制台下发。</p>
 *
 * @param mode           接入模式：{@code DOCK}（机场上云）/ {@code PILOT}（遥控器上云），空表示 DOCK
 * @param controllerType 遥控器机型枚举名（PILOT 生效，如 {@code RC_PLUS}），空表示 SDK 默认机型
 */
@ConfigurationProperties(prefix = "simulator.device")
public record DeviceProperties(
        String mode,
        String controllerType
) {
}
