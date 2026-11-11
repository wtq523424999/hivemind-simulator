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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机库清单持久化测试（TC-FLEET-001~006）。
 *
 * <p>覆盖：文件不存在时降级为空清单、写入后可读回、版本号归一化、
 * 损坏文件不阻断启动、空清单安全、清单目录不存在时自动创建。</p>
 */
class FleetStoreTest {

    private FleetInstance sample() {
        return new FleetInstance("dock-1", "成都高新机库", "127.0.0.1", 9090,
                "6QCDL870020139", "1581F6Q8D241P00BFH3Q", "DOCK3", "M4TD",
                30.670815, 104.071523, 500.0, "测试用", true);
    }

    @DisplayName("TC-FLEET-001：清单文件不存在时返回空清单（不阻断启动）")
    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path dir) {
        FleetStore store = new FleetStore(dir.resolve("fleet.json"));
        FleetConfig config = store.load();

        assertNotNull(config);
        assertTrue(config.instances().isEmpty());
        assertEquals(FleetConfig.CURRENT_VERSION, config.versionOrDefault());
    }

    @DisplayName("TC-FLEET-002：写入后可原样读回")
    @Test
    void saveThenLoadRoundTrip(@TempDir Path dir) {
        FleetStore store = new FleetStore(dir.resolve("fleet.json"));
        assertTrue(store.save(new FleetConfig(1, List.of(sample()))));

        FleetConfig loaded = store.load();
        assertEquals(1, loaded.instances().size());
        FleetInstance instance = loaded.instances().get(0);
        assertEquals("dock-1", instance.id());
        assertEquals("成都高新机库", instance.name());
        assertEquals(9090, instance.port());
        assertEquals("6QCDL870020139", instance.dockSn());
        assertEquals(30.670815, instance.latitude());
        assertTrue(instance.enabledOrDefault());
    }

    @DisplayName("TC-FLEET-003：保存时版本号归一化为当前版本")
    @Test
    void saveNormalizesVersion(@TempDir Path dir) {
        FleetStore store = new FleetStore(dir.resolve("fleet.json"));
        store.save(new FleetConfig(null, List.of(sample())));

        assertEquals(FleetConfig.CURRENT_VERSION, store.load().versionOrDefault());
    }

    @DisplayName("TC-FLEET-004：文件损坏时返回空清单，不抛异常")
    @Test
    void loadReturnsEmptyOnCorruptedFile(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("fleet.json");
        java.nio.file.Files.writeString(path, "{ this is not json");
        FleetStore store = new FleetStore(path);

        FleetConfig config = store.load();
        assertNotNull(config);
        assertTrue(config.instances().isEmpty());
    }

    @DisplayName("TC-FLEET-005：保存 null 清单等价于空清单")
    @Test
    void saveNullBecomesEmpty(@TempDir Path dir) {
        FleetStore store = new FleetStore(dir.resolve("fleet.json"));
        assertTrue(store.save(null));
        assertTrue(store.load().instances().isEmpty());
    }

    @DisplayName("TC-FLEET-006：父目录不存在时自动创建")
    @Test
    void saveCreatesParentDirectory(@TempDir Path dir) {
        Path nested = dir.resolve("a/b/c/fleet.json");
        FleetStore store = new FleetStore(nested);
        assertTrue(store.save(new FleetConfig(1, List.of(sample()))));
        assertTrue(java.nio.file.Files.exists(nested));
    }

    @DisplayName("TC-FLEET-007：缺少 enabled 字段的旧清单视为启用")
    @Test
    void missingEnabledDefaultsToTrue() {
        FleetInstance legacy = new FleetInstance("dock-1", "旧机库", "127.0.0.1", 9090,
                null, null, null, null, null, null, null, null, null);
        assertTrue(legacy.enabledOrDefault());
    }

    @DisplayName("TC-FLEET-008：baseUrl 兼容带协议前缀与裸主机名")
    @Test
    void baseUrlNormalization() {
        FleetInstance bare = new FleetInstance("a", "a", "127.0.0.1", 9091,
                null, null, null, null, null, null, null, null, true);
        assertEquals("http://127.0.0.1:9091", bare.baseUrl());
        assertEquals("http://127.0.0.1:9091/", bare.consoleUrl());

        FleetInstance withScheme = new FleetInstance("b", "b", "http://host.docker.internal", 9092,
                null, null, null, null, null, null, null, null, true);
        assertEquals("http://host.docker.internal:9092", withScheme.baseUrl());
    }
}
