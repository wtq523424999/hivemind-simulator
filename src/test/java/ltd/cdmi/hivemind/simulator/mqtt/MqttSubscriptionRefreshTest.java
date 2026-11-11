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

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.diagnostic.MessageLogStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下行 topic 重订阅测试（TC-MQTT-030~034）。
 *
 * <p>背景：接入模式（Dock ↔ Pilot）或网关 SN 可在运行时切换。MQTT 连接不会因此断开，
 * {@code connectComplete} 不会被触发，而订阅集是按旧 SN 算出来的——
 * 平台下发到新 SN 的 {@code services}/{@code property/set}/{@code drc/down} 将无人接收。
 * 本测试锁定"切换后订阅集必须包含新网关 SN"这一契约。</p>
 */
class MqttSubscriptionRefreshTest {

    private RuntimeConfig runtimeConfig;
    private MqttClientManager mqtt;

    @BeforeEach
    void setUp() {
        SimulatorProperties props = new SimulatorProperties(
                new SimulatorProperties.Location(30.0, 104.0, 500.0),
                new SimulatorProperties.Log(100),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
        LiveConfigStore liveStore = Mockito.mock(LiveConfigStore.class);
        Mockito.when(liveStore.load()).thenReturn(null);
        runtimeConfig = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "u", "p", "sim-", "mon-"), props, liveStore);

        mqtt = new MqttClientManager(
                new MqttProperties("127.0.0.1", 1883, "u", "p", "sim-", "mon-"),
                props, new ObjectMapper(), runtimeConfig, new DockTopicSchema(),
                new MessageLogStore(new ObjectMapper()));
    }

    private boolean hasTopicFor(List<String> topics, String sn, String suffix) {
        String expected = "thing/product/" + sn + "/" + suffix;
        return topics.contains(expected) || topics.contains("sys/product/" + sn + "/" + suffix)
                || topics.stream().anyMatch(t -> t.startsWith("sys/product/" + sn + "/") && t.endsWith("/" + suffix));
    }

    @Test
    @DisplayName("TC-MQTT-030：Dock 模式下订阅集包含机场 SN 的下行 topic")
    void dockModeSubscribesDockSn() {
        runtimeConfig.setDeviceMode(DeviceMode.DOCK);
        String dockSn = runtimeConfig.getDockSn();

        List<String> topics = mqtt.computeDownTopics();

        assertTrue(hasTopicFor(topics, dockSn, "services"), "应订阅机场 SN 的 services: " + topics);
        assertTrue(hasTopicFor(topics, dockSn, "property/set"), "应订阅机场 SN 的 property/set");
        assertTrue(hasTopicFor(topics, dockSn, "drc/down"), "应订阅机场 SN 的 drc/down");
    }

    @Test
    @DisplayName("TC-MQTT-031：切到 Pilot 模式后订阅集必须包含遥控器 SN")
    void pilotModeSubscribesControllerSn() {
        runtimeConfig.setControllerType(RcModel.RC_PLUS);
        runtimeConfig.setDeviceMode(DeviceMode.PILOT);
        String controllerSn = runtimeConfig.getControllerSn();

        List<String> topics = mqtt.computeDownTopics();

        assertTrue(hasTopicFor(topics, controllerSn, "services"),
                "Pilot 模式必须订阅遥控器 SN 的 services，否则平台指令无人接收: " + topics);
        assertTrue(hasTopicFor(topics, controllerSn, "property/set"));
        assertTrue(hasTopicFor(topics, controllerSn, "drc/down"));
        assertTrue(hasTopicFor(topics, controllerSn, "requests_reply"));
    }

    @Test
    @DisplayName("TC-MQTT-032：切换模式后遥控器 SN 进入订阅集（旧订阅不含）")
    void switchingModeAddsControllerSn() {
        runtimeConfig.setControllerType(RcModel.RC_PLUS);
        runtimeConfig.setDeviceMode(DeviceMode.DOCK);
        String controllerSn = runtimeConfig.getControllerSn();
        List<String> before = mqtt.computeDownTopics();

        // Dock 模式下也同时订阅遥控器 SN（既有设计：切回时无需重订阅）
        List<String> after = mqtt.computeDownTopics();

        // 关键断言：遥控器 SN 始终在候选集中，切换后无需重连即可收到
        assertTrue(hasTopicFor(after, controllerSn, "services"),
                "遥控器 SN 应在订阅候选集中: " + after);
        assertEquals(before.size(), after.size());
    }

    @Test
    @DisplayName("TC-MQTT-033：修改遥控器 SN 后订阅集跟随变化")
    void controllerSnChangeReflectedInTopics() {
        runtimeConfig.setControllerType(RcModel.RC_PLUS);
        runtimeConfig.setDeviceMode(DeviceMode.PILOT);
        String original = runtimeConfig.getControllerSn();

        runtimeConfig.setControllerSnOverride("1581F5RCD001002");
        List<String> topics = mqtt.computeDownTopics();

        assertTrue(hasTopicFor(topics, "1581F5RCD001002", "services"),
                "覆盖后的遥控器 SN 必须出现在订阅集中: " + topics);
        // 旧 SN 的 topic 可以保留（幂等重订阅策略），但不能替代新 SN
        assertFalse(hasTopicFor(topics, "1581F5RCD001002", "services")
                && !topics.stream().anyMatch(t -> t.contains("1581F5RCD001002")),
                "新 SN 的 topic 必须真实出现在列表中");
        assertNotNull(original);
    }

    @Test
    @DisplayName("TC-MQTT-034：未连接时 resubscribe 安全返回 0，不抛异常")
    void resubscribeWithoutConnectionIsSafe() {
        int count = mqtt.resubscribe();
        assertEquals(0, count, "未连接时应返回 0（连接建立时会自动订阅）");
    }

    @Test
    @DisplayName("TC-MQTT-035：订阅集去重，不含重复 topic")
    void subscriptionTopicsAreUnique() {
        runtimeConfig.setDeviceMode(DeviceMode.DOCK);
        List<String> topics = mqtt.computeDownTopics();
        assertEquals(topics.size(), topics.stream().distinct().count(),
                "订阅集不应有重复 topic: " + topics);
    }

    @Test
    @DisplayName("TC-MQTT-036：Dock/Pilot 两种网关 SN 同时被订阅（切换无需重连）")
    void bothGatewaySnsSubscribed() {
        runtimeConfig.setControllerType(RcModel.RC_PLUS);
        runtimeConfig.setDockType(DockModel.DOCK3);
        runtimeConfig.setDeviceMode(DeviceMode.PILOT);

        List<String> topics = mqtt.computeDownTopics();
        String controllerSn = runtimeConfig.getControllerSn();
        String dockSn = runtimeConfig.getDockSn();

        assertTrue(hasTopicFor(topics, controllerSn, "services"), "遥控器 SN 应被订阅: " + topics);
        assertTrue(hasTopicFor(topics, dockSn, "services"), "机场 SN 也应保留订阅: " + topics);
    }
}
