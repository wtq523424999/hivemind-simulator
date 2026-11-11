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
 * 机库启用工作流（bring-up）结果。
 *
 * <p>与 {@link FleetBatchResult} 的区别：批量动作对每台机库只做一件事，
 * 各步相互独立；本结果记录的是**一台机库上按顺序执行的多个步骤**，
 * 前一步失败会中止后续步骤（例如没连上 MQTT 就没必要尝试上线）。</p>
 *
 * @param id        实例标识
 * @param name      机库名称
 * @param success   整个工作流是否全部成功
 * @param failedStep 失败步骤名（全部成功时为 null）
 * @param message   结论摘要
 * @param steps     逐步执行明细
 */
public record FleetBringupResult(
        String id,
        String name,
        boolean success,
        String failedStep,
        String message,
        List<Step> steps
) {

    /**
     * 单个步骤结果。
     *
     * @param step    步骤名（power_on / connect / online / apply_identity）
     * @param label   步骤中文说明
     * @param success 是否成功
     * @param detail  成功摘要或失败原因
     * @param skipped 是否因前序步骤失败而跳过
     */
    public record Step(String step, String label, boolean success, String detail, boolean skipped) {
    }

    public static FleetBringupResult success(String id, String name, List<Step> steps) {
        return new FleetBringupResult(id, name, true, null,
                "全部步骤成功（" + steps.size() + " 步）", List.copyOf(steps));
    }

    public static FleetBringupResult failed(String id, String name, String failedStep,
                                            String message, List<Step> steps) {
        return new FleetBringupResult(id, name, false, failedStep, message, List.copyOf(steps));
    }
}
