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

import java.util.List;

/**
 * 机库清单持久化快照（{@code fleet.json}）。
 *
 * <p>与 {@code live-config.json} 分开存放：机库清单属于"控制台级的编排配置"，
 * 而 {@code live-config.json} 属于"单实例的设备配置"，两者生命周期与写入方都不同，
 * 混在一个文件里会在多实例共享 config 卷时互相覆盖。</p>
 *
 * @param version   格式版本，便于后续迁移（当前为 1）
 * @param instances 实例列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FleetConfig(
        Integer version,
        List<FleetInstance> instances
) {

    /** 当前清单格式版本 */
    public static final int CURRENT_VERSION = 1;

    public FleetConfig {
        if (instances == null) {
            instances = List.of();
        }
    }

    public static FleetConfig empty() {
        return new FleetConfig(CURRENT_VERSION, List.of());
    }

    public int versionOrDefault() {
        return version == null ? CURRENT_VERSION : version;
    }
}
