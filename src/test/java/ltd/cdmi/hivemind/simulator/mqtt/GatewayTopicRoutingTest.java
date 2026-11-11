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

package ltd.cdmi.hivemind.simulator.mqtt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关无关 topic 分发测试。
 * <p>单兵（遥控器上云）与机场上云共用同一套 MQTT 通道，仅网关 SN 不同（domain=2 vs domain=3）。
 * 接入模式可在运行时切换，因此下行监听不能绑定在启动时的某一个 SN 上。</p>
 */
class GatewayTopicRoutingTest {

    @Test
    @DisplayName("产品 topic 的 SN 段被替换为通配符作为别名")
    void buildsGatewayAgnosticAlias() {
        assertEquals("thing/product/+/services",
                MqttClientManager.gatewayAgnosticTopic("thing/product/7UUXN1Q00A008W/services"));
        assertEquals("sys/product/+/status",
                MqttClientManager.gatewayAgnosticTopic("sys/product/1581F5RCD002001/status"));
    }

    @Test
    @DisplayName("非产品 topic 与已是通配的 topic 不产生别名")
    void skipsNonProductTopics() {
        assertNull(MqttClientManager.gatewayAgnosticTopic("thing/product/+/services"));
        assertNull(MqttClientManager.gatewayAgnosticTopic("custom/topic"));
        assertNull(MqttClientManager.gatewayAgnosticTopic("thing/product"));
    }

    @Test
    @DisplayName("通配匹配支持单层 + 与末尾 #")
    void matchesWildcards() {
        assertTrue(MqttClientManager.topicMatches("thing/product/+/services",
                "thing/product/RC-SN/services"));
        assertTrue(MqttClientManager.topicMatches("thing/product/+/drc/#",
                "thing/product/RC-SN/drc/down"));
        assertFalse(MqttClientManager.topicMatches("thing/product/+/services",
                "thing/product/RC-SN/events"));
        assertFalse(MqttClientManager.topicMatches("thing/product/+/services",
                "thing/product/RC-SN/extra/services"));
        // # 只能在末尾
        assertFalse(MqttClientManager.topicMatches("thing/product/#/services",
                "thing/product/RC-SN/services"));
    }

    @Test
    @DisplayName("精确 topic 与通配别名同时命中时，同一监听器只收到一次消息")
    void exactAndWildcardDeliverOnce() throws Exception {
        MqttClientManager mqtt = newManager();
        AtomicInteger calls = new AtomicInteger();
        MqttClientManager.MqttMessageListener listener = (topic, payload) -> calls.incrementAndGet();

        mqtt.addListener("thing/product/DOCK-SN/services", listener);
        String alias = MqttClientManager.gatewayAgnosticTopic("thing/product/DOCK-SN/services");
        assertNotNull(alias, "应生成网关无关别名");

        // 模拟 broker 投递一条精确命中该监听器的消息
        invokeMessageArrived(mqtt, "thing/product/DOCK-SN/services", "{}");

        assertEquals(1, calls.get(), "精确 topic 与别名命中同一监听器时应只处理一次");
    }

    private void invokeMessageArrived(MqttClientManager mqtt, String topic, String payload) throws Exception {
        var method = MqttClientManager.class.getDeclaredMethod(
                "messageArrived", String.class, org.eclipse.paho.client.mqttv3.MqttMessage.class);
        method.setAccessible(true);
        method.invoke(mqtt, topic, new org.eclipse.paho.client.mqttv3.MqttMessage(payload.getBytes()));
    }

    private MqttClientManager newManager() {
        return new MqttClientManager(
                new ltd.cdmi.hivemind.simulator.config.MqttProperties(
                        "127.0.0.1", 1883, "u", "p", "sim-", "mon-"),
                new ltd.cdmi.hivemind.simulator.config.SimulatorProperties(
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Location(30.0, 104.0, 500.0),
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Log(100),
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Live(false, "", "", null),
                        new ltd.cdmi.hivemind.simulator.config.SimulatorProperties.Media("", false, 0, false, 0),
                        null, null, null, null, null),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(ltd.cdmi.hivemind.simulator.config.RuntimeConfig.class),
                new DockTopicSchema(),
                new ltd.cdmi.hivemind.simulator.diagnostic.MessageLogStore(
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test
    @DisplayName("同一监听器在 SN 变化后重复注册不会重复分发")
    void listenerRegisteredOncePerTopic() {
        MqttClientManager mqtt = newManager();

        AtomicInteger calls = new AtomicInteger();
        MqttClientManager.MqttMessageListener listener = (topic, payload) -> calls.incrementAndGet();

        // 模拟机场 SN 与遥控器 SN 两次注册同一处理器
        mqtt.addListener("thing/product/DOCK-SN/services", listener);
        mqtt.addListener("thing/product/RC-SN/services", listener);
        assertEquals(0, calls.get(), "注册阶段不应触发消息处理");

        // 通过反射读取内部监听表，确认别名键只存在一份监听器
        try {
            var field = MqttClientManager.class.getDeclaredField("listeners");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var listeners = (java.util.Map<String, java.util.List<MqttClientManager.MqttMessageListener>>)
                    field.get(mqtt);
            assertEquals(1, listeners.get("thing/product/+/services").size(),
                    "别名键下应只有一个监听器实例");
        } catch (ReflectiveOperationException e) {
            fail("读取监听表失败: " + e.getMessage());
        }
    }
}
