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

import java.util.List;

/**
 * 新建机库的全流程结果（登记 → 创建实例 → 连接 MQTT → 注册上线）。
 *
 * <p>与 {@link FleetBringupResult} 的区别：bringup 作用于"已经存在的实例"，
 * 本结果覆盖"先把实例拉起来"这一前置阶段，并记录实例创建信息
 * （容器名、宿主机端口），这些是 bringup 阶段还不知道的。</p>
 *
 * @param id          机库标识
 * @param name        机库名称
 * @param success     全流程是否成功
 * @param failedStep  失败步骤（成功时为 null）
 * @param message     结论摘要
 * @param instance    最终落库的机库登记项
 * @param containerName 容器名（未创建容器时为 null）
 * @param hostPort    宿主机映射端口（未创建容器时为 null）
 * @param steps       逐步明细
 */
public record FleetProvisionResult(
        String id,
        String name,
        boolean success,
        String failedStep,
        String message,
        FleetInstance instance,
        String containerName,
        Integer hostPort,
        List<Step> steps
) {

    /**
     * 单个步骤结果。
     *
     * @param step    步骤标识（validate / provision / register / connect / online / location）
     * @param label   中文说明
     * @param success 是否成功
     * @param detail  成功摘要或失败原因
     * @param skipped 是否跳过（未启用自动创建、已满足条件等）
     */
    public record Step(String step, String label, boolean success, String detail, boolean skipped) {
    }

    public static FleetProvisionResult ok(String id, String name, FleetInstance instance,
                                          String containerName, Integer hostPort,
                                          List<Step> steps, String message) {
        return new FleetProvisionResult(id, name, true, null, message, instance,
                containerName, hostPort, List.copyOf(steps));
    }

    public static FleetProvisionResult fail(String id, String name, String failedStep,
                                            String message, FleetInstance instance,
                                            String containerName, Integer hostPort,
                                            List<Step> steps) {
        return new FleetProvisionResult(id, name, false, failedStep, message, instance,
                containerName, hostPort, List.copyOf(steps));
    }
}
