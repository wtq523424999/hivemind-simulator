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

import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pushMode=1 状态变化上报：只在设备在线且有变化字段时，向网关 state topic 发增量。
 */
class StatePushServiceTest {

    private RuntimeConfig runtimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                new SimulatorProperties(
                        new SimulatorProperties.Location(30.67, 104.07, 500.0),
                        new SimulatorProperties.Log(2000),
                        new SimulatorProperties.Live(false, "", "", null),
                        new SimulatorProperties.Media("", false, 0, false, 0),
                        null, null, null, null, null),
                new LiveConfigStore());
    }

    @DisplayName("TC-STATE-001：在线时向 thing/product/{gateway}/state 只推变化字段")
    @Test
    @SuppressWarnings("unchecked")
    void publishesChangedFieldsOnGatewayStateTopic() {
        RuntimeConfig config = runtimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        StatePushService service = new StatePushService(mqtt, state, config, new DockTopicSchema());

        service.publishGatewayState(Map.of("silent_mode", 1));

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mqtt).publishJson(topic.capture(), payload.capture());
        assertEquals("thing/product/" + config.getGatewaySn() + "/state", topic.getValue());
        Map<String, Object> envelope = (Map<String, Object>) payload.getValue();
        assertEquals(config.getGatewaySn(), envelope.get("gateway"));
        assertEquals(Map.of("silent_mode", 1), envelope.get("data"));
        assertNotNull(envelope.get("tid"));
        assertNotNull(envelope.get("bid"));
    }

    @DisplayName("TC-STATE-002：设备未上线或无变化字段时不发 state")
    @Test
    void skipsWhenOfflineOrEmpty() {
        DeviceState state = new DeviceState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        StatePushService service = new StatePushService(mqtt, state, runtimeConfig(), new DockTopicSchema());

        service.publishGatewayState(Map.of("silent_mode", 1));
        service.publishGatewayState(Map.of());
        Mockito.verify(mqtt, Mockito.never()).publishJson(Mockito.anyString(), Mockito.any());
    }
}
