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
 * 机库批量动作执行结果。
 *
 * @param action  动作名（power_on / power_off / connect / online / offline / set_location / apply_identity）
 * @param total   参与实例数
 * @param success 成功数
 * @param failed  失败数
 * @param results 逐实例结果
 */
public record FleetBatchResult(
        String action,
        int total,
        int success,
        int failed,
        List<FleetActionResult> results
) {

    /**
     * 单实例动作结果。
     *
     * @param id      实例标识
     * @param name    机库名称
     * @param success 是否成功
     * @param message 成功时为摘要，失败时为原因
     */
    public record FleetActionResult(String id, String name, boolean success, String message) {
    }

    public static FleetBatchResult of(String action, List<FleetActionResult> results) {
        int success = (int) results.stream().filter(FleetActionResult::success).count();
        return new FleetBatchResult(action, results.size(), success, results.size() - success, results);
    }
}
