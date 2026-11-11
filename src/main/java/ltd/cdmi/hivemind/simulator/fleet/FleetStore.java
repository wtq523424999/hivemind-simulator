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

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 机库清单持久化存储（{@code fleet.json}）。
 *
 * <p>文件位置与 {@code live-config.json} 同目录（见 {@link LiveConfigStore#resolveConfigDir()}）：
 * Docker 部署时是 {@code SIMULATOR_CONFIG_DIR} 挂载的 config volume，重启后清单不丢失。</p>
 *
 * <p>容错策略与 {@link LiveConfigStore} 一致：文件不存在或解析失败时返回空清单（仅告警，不阻断启动）；
 * 写入失败仅告警，不影响内存中的清单。所有读写加锁，避免控制台并发操作时互相覆盖。</p>
 */
@Component
public class FleetStore {

    private static final Logger log = LoggerFactory.getLogger(FleetStore.class);

    /** 清单文件名（与 live-config.json 同目录） */
    public static final String CONFIG_FILE = "fleet.json";

    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public FleetStore() {
        this(LiveConfigStore.resolveConfigDir().resolve(CONFIG_FILE));
    }

    /**
     * 指定清单路径的构造器（测试与自定义部署使用）。
     *
     * @param configPath fleet.json 的完整路径
     */
    public FleetStore(Path configPath) {
        this.configPath = configPath;
        log.info("机库清单持久化路径: {}", configPath.toAbsolutePath());
    }

    /** 清单文件路径（供诊断接口展示）。 */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * 读取清单。
     *
     * @return 清单快照；文件不存在或读取失败时返回空清单
     */
    public synchronized FleetConfig load() {
        if (!Files.exists(configPath)) {
            log.debug("机库清单不存在，返回空清单: {}", configPath);
            return FleetConfig.empty();
        }
        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            FleetConfig config = mapper.readValue(json, FleetConfig.class);
            if (config == null) {
                return FleetConfig.empty();
            }
            return new FleetConfig(config.versionOrDefault(), config.instances());
        } catch (IOException e) {
            log.warn("加载机库清单失败，返回空清单: {} - {}", configPath, e.getMessage());
            return FleetConfig.empty();
        }
    }

    /**
     * 写入清单。
     *
     * @param config 清单快照
     * @return true=写入成功；false=写入失败（已记录告警，调用方内存状态仍可继续使用）
     */
    public synchronized boolean save(FleetConfig config) {
        FleetConfig snapshot = config == null
                ? FleetConfig.empty()
                : new FleetConfig(FleetConfig.CURRENT_VERSION, config.instances());
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
            log.debug("机库清单已持久化: {}", configPath);
            return true;
        } catch (IOException e) {
            log.warn("保存机库清单失败: {} - {}", configPath, e.getMessage());
            return false;
        }
    }
}
