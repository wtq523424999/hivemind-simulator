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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机场环境参数持久化测试。
 *
 * <p>回归背景：环境参数此前只存在于内存的 {@link DeviceState}，容器重启后全部回到默认值；
 * 而拒飞判定（风速≥9 m/s → 514135、环境温度&lt;-20℃ → 514137）依赖这些值，
 * 联调时重启即丢失配置无法复现。</p>
 */
class DockEnvStateStoreTest {

    /** 用临时目录替代默认配置目录，避免污染用户主目录 */
    private DockEnvStateStore newStore(DeviceState state, Path dir) {
        System.setProperty("simulator.config.dir", dir.toString());
        try {
            return new DockEnvStateStore(state);
        } finally {
            System.clearProperty("simulator.config.dir");
        }
    }

    @DisplayName("环境参数写入后可被新实例读回（模拟进程重启）")
    @Test
    void persistedEnvironmentIsRestoredByNewInstance(@TempDir Path dir) {
        DeviceState state = new DeviceState();
        state.setDockTemperature(33.5);
        state.setDockHumidity(66.0);
        state.setWindSpeed(11.5);
        state.setDroneWindSpeed(4.5);
        state.setWindDirection(5);
        state.setRainfall(2);
        state.setEnvironmentTemperature(41.5);
        state.setPowerSupplyNormal(false);
        state.setEmergencyStopPressed(true);
        state.setDockCalibrated(false);

        newStore(state, dir).persist();
        assertTrue(Files.exists(dir.resolve(DockEnvStateStore.CONFIG_FILE)), "应生成环境参数文件");

        // 新进程：全新 DeviceState 走 restore，值应与持久化前一致
        DeviceState restored = new DeviceState();
        newStore(restored, dir).restore();

        assertEquals(33.5, restored.getDockTemperature(), 1e-9);
        assertEquals(66.0, restored.getDockHumidity(), 1e-9);
        assertEquals(11.5, restored.getWindSpeed(), 1e-9);
        assertEquals(4.5, restored.getDroneWindSpeed(), 1e-9);
        assertEquals(5, restored.getWindDirection());
        assertEquals(2, restored.getRainfall());
        assertEquals(41.5, restored.getEnvironmentTemperature(), 1e-9);
        assertFalse(restored.isPowerSupplyNormal());
        assertTrue(restored.isEmergencyStopPressed());
        assertFalse(restored.isDockCalibrated());
    }

    @DisplayName("文件不存在时保持 DeviceState 默认值，不抛异常")
    @Test
    void missingFileKeepsDefaults(@TempDir Path dir) {
        DeviceState state = new DeviceState();
        newStore(state, dir).restore();

        assertEquals(25.0, state.getDockTemperature(), 1e-9, "默认机场温度 25℃");
        assertEquals(50.0, state.getDockHumidity(), 1e-9, "默认湿度 50%");
        assertEquals(3.0, state.getWindSpeed(), 1e-9, "默认风速 3 m/s");
        assertEquals(25.0, state.getEnvironmentTemperature(), 1e-9, "默认环境温度 25℃");
        assertEquals(0, state.getRainfall(), "默认无雨");
        assertTrue(state.isPowerSupplyNormal(), "默认供电正常");
        assertTrue(state.isDockCalibrated(), "默认已标定");
    }

    @DisplayName("旧文件缺少新增字段时保留内存当前值（不重置为 0）")
    @Test
    void legacyFileMissingFieldsKeepsCurrentValues(@TempDir Path dir) throws Exception {
        // 只写两个字段，模拟新增字段之前生成的旧文件
        Files.writeString(dir.resolve(DockEnvStateStore.CONFIG_FILE),
                "{\"windSpeed\": 7.5, \"rainfall\": 1}");

        DeviceState state = new DeviceState();
        state.setEnvironmentTemperature(18.0);   // 旧文件里没有该字段，应保留
        state.setDockCalibrated(false);          // 同上
        newStore(state, dir).restore();

        assertEquals(7.5, state.getWindSpeed(), 1e-9, "文件中存在的字段应恢复");
        assertEquals(1, state.getRainfall(), "文件中存在的字段应恢复");
        assertEquals(18.0, state.getEnvironmentTemperature(), 1e-9, "缺失字段应保留内存当前值");
        assertFalse(state.isDockCalibrated(), "缺失字段应保留内存当前值");
    }

    @DisplayName("损坏的配置文件不阻断启动，降级为默认值")
    @Test
    void corruptedFileFallsBackToDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DockEnvStateStore.CONFIG_FILE), "{not-json");

        DeviceState state = new DeviceState();
        assertDoesNotThrow(() -> newStore(state, dir).restore(), "解析失败不应抛异常");
        assertEquals(3.0, state.getWindSpeed(), 1e-9, "降级为默认风速");
    }
}
