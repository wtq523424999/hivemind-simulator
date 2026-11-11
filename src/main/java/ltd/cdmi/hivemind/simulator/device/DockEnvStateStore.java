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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 机场环境参数的持久化存储（温度/湿度/风速/降雨/环境温度等可模拟环境值）。
 *
 * <p>为什么需要单独持久化：{@link DeviceState} 是纯内存对象，进程重启后所有字段回到默认值，
 * 用户在控制台调过的环境参数会全部丢失。而环境参数直接影响拒飞判定（风速≥9 m/s → 514135、
 * 环境温度&lt;-20℃ → 514137 等），联调时需要它在重启后仍然可复现。</p>
 *
 * <p>与 {@link LiveConfigStore} 分开存放：那份配置存的是设备身份与连接参数（SN/坐标/MQTT/直播），
 * 改错会直接导致设备上不了线；环境参数是纯业务模拟值，单独一个文件便于整体删除重置，
 * 也避免身份文件的写入频率被高频环境调整带高。</p>
 *
 * <p>文件位置与 live-config.json 同目录（{@link LiveConfigStore#resolveConfigDir()}），
 * 因此多实例部署时随各自的 config volume 隔离。</p>
 */
@Component
public class DockEnvStateStore {

    private static final Logger log = LoggerFactory.getLogger(DockEnvStateStore.class);

    /** 环境参数文件名（与 live-config.json 同目录） */
    public static final String CONFIG_FILE = "dock-env-state.json";

    private final DeviceState state;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configPath;

    public DockEnvStateStore(DeviceState state) {
        this.state = state;
        this.configPath = LiveConfigStore.resolveConfigDir().resolve(CONFIG_FILE);
    }

    /**
     * 持久化的环境参数快照。
     *
     * <p>全部用包装类型：旧文件缺字段时为 null，表示"保持当前内存值"，
     * 避免新增字段后老文件把该字段重置为 0。</p>
     */
    public record DockEnvState(Double dockTemperature, Double dockHumidity, Double windSpeed,
                               Double droneWindSpeed, Integer windDirection, Integer rainfall,
                               Double environmentTemperature, Boolean powerSupplyNormal,
                               Boolean emergencyStopPressed, Boolean dockCalibrated) {
    }

    /** 启动时恢复上次保存的环境参数；文件不存在时保持 DeviceState 的默认值。 */
    @PostConstruct
    public void restore() {
        if (!Files.exists(configPath)) {
            log.debug("环境参数文件不存在，使用默认值: {}", configPath);
            return;
        }
        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            DockEnvState saved = mapper.readValue(json, DockEnvState.class);
            apply(saved);
            log.info("环境参数已恢复: windSpeed={}, dockTemperature={}, environmentTemperature={}, rainfall={}",
                    state.getWindSpeed(), state.getDockTemperature(),
                    state.getEnvironmentTemperature(), state.getRainfall());
        } catch (IOException e) {
            log.warn("恢复环境参数失败，使用默认值: {} - {}", configPath, e.getMessage());
        }
    }

    private void apply(DockEnvState saved) {
        if (saved == null) return;
        if (saved.dockTemperature() != null) state.setDockTemperature(saved.dockTemperature());
        if (saved.dockHumidity() != null) state.setDockHumidity(saved.dockHumidity());
        if (saved.windSpeed() != null) state.setWindSpeed(saved.windSpeed());
        if (saved.droneWindSpeed() != null) state.setDroneWindSpeed(saved.droneWindSpeed());
        if (saved.windDirection() != null) state.setWindDirection(saved.windDirection());
        if (saved.rainfall() != null) state.setRainfall(saved.rainfall());
        if (saved.environmentTemperature() != null) state.setEnvironmentTemperature(saved.environmentTemperature());
        if (saved.powerSupplyNormal() != null) state.setPowerSupplyNormal(saved.powerSupplyNormal());
        if (saved.emergencyStopPressed() != null) state.setEmergencyStopPressed(saved.emergencyStopPressed());
        if (saved.dockCalibrated() != null) state.setDockCalibrated(saved.dockCalibrated());
    }

    /**
     * 把当前环境参数写入文件。
     * <p>写入失败仅告警，不影响内存中的状态更新（与 {@link LiveConfigStore} 一致）。</p>
     */
    public synchronized void persist() {
        try {
            Files.createDirectories(configPath.getParent());
            DockEnvState snapshot = new DockEnvState(
                    state.getDockTemperature(), state.getDockHumidity(),
                    state.getWindSpeed(), state.getDroneWindSpeed(), state.getWindDirection(),
                    state.getRainfall(), state.getEnvironmentTemperature(),
                    state.isPowerSupplyNormal(), state.isEmergencyStopPressed(), state.isDockCalibrated());
            Files.writeString(configPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),
                    StandardCharsets.UTF_8);
            log.debug("环境参数已持久化: {}", configPath);
        } catch (IOException e) {
            log.warn("保存环境参数失败: {} - {}", configPath, e.getMessage());
        }
    }
}
