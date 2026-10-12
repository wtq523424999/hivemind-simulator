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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.mqtt.PilotTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.TopicSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * pushMode=1 状态变化上报。
 * <p>DJI 约定：{@code thing/product/{device_sn}/state} 不定频，只在属性变化时推送变化字段。
 * 上线时的全量初始值仍由 {@link DockOnlineService#publishDockState()} /
 * {@link PilotOnlineService} 各自推送；本服务只负责变化后的增量上报。</p>
 */
@Service
public class StatePushService {

    private static final Logger log = LoggerFactory.getLogger(StatePushService.class);

    private final MqttClientManager mqtt;
    private final DeviceState state;
    private final RuntimeConfig runtimeConfig;
    private final DockTopicSchema dockTopicSchema;

    public StatePushService(MqttClientManager mqtt, DeviceState state, RuntimeConfig runtimeConfig,
                            DockTopicSchema dockTopicSchema) {
        this.mqtt = mqtt;
        this.state = state;
        this.runtimeConfig = runtimeConfig;
        this.dockTopicSchema = dockTopicSchema;
    }

    /**
     * 向当前网关的 state topic 推送变化字段。
     * <p>设备未上线或 MQTT 未连接时跳过（变化已写入本地状态，下次上线全量推送会带上新值）。
     * data 为空时不发，避免无变化的空包。</p>
     *
     * @param data 仅包含本次变化的 pushMode=1 字段
     */
    public void publishGatewayState(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        if (!state.isOnline()) {
            log.debug("设备未上线，跳过 state 变化上报: {}", data.keySet());
            return;
        }
        if (!mqtt.isConnected()) {
            log.warn("MQTT 未连接，跳过 state 变化上报: {}", data.keySet());
            return;
        }

        String gatewaySn = runtimeConfig.getGatewaySn();
        TopicSchema schema = runtimeConfig.getDeviceMode() == DeviceMode.PILOT
                ? new PilotTopicSchema(runtimeConfig.getControllerType())
                : dockTopicSchema;

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tid", UUID.randomUUID().toString());
        envelope.put("bid", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("gateway", gatewaySn);
        envelope.put("data", data);

        String topic = schema.topic(schema.state(), gatewaySn);
        mqtt.publishJson(topic, envelope);
        log.info("已上报 state 变化: fields={} topic={}", data.keySet(), topic);
    }
}
