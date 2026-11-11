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

import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicChannel;
import ltd.cdmi.dji.cloudapi.sdk.protocol.topic.TopicTemplate;

/**
 * Pilot 上云（RC 作为网关）的 MQTT Topic 模式。
 * <p>实现 {@link TopicSchema} 接口，提供 Pilot 上云的 Topic 通道。</p>
 * <p>与机场上云的关键差异：拓扑上行走 {@code thing/product} 前缀，而不是 {@code sys/product}：
 * <ul>
 *   <li>上行 update_topo：{@code thing/product/{gateway_sn}/status}</li>
 *   <li>下行回复：{@code sys/product/{gateway_sn}/status_reply}</li>
 * </ul>
 * <p>核实依据：DJI Cloud API Pilot 上云设备拓扑更新文档，各遥控器型号一致，不按型号区分：
 * <ul>
 *   <li>DJI RC：{@code 10.pilot-to-cloud/00.mqtt/30.others/20.rc/10.device.md}</li>
 *   <li>DJI RC Pro 行业版：{@code 10.pilot-to-cloud/00.mqtt/20.rc-pro/10.device.md}</li>
 * </ul>
 * 两份文档均为「Topic: thing/product/{gateway_sn}/status」（Direction: up）与
 * 「Topic: sys/product/{gateway_sn}/status_reply」（Direction: down）。</p>
 * <p>注意：Pilot 上云的 Topic 总览表（{@code 00.topic-definition.md}）列出的是
 * {@code sys/product/{gateway_sn}/status}，与各型号设备文档不一致。此处按型号设备文档实现，
 * 因为那份文档由各机型逐条给出 Method/Data 结构，且平台侧实际回复固定走
 * {@code sys/.../status_reply}。</p>
 * <p>其他通道（osd/state/services/...）与机场上云一致，使用 TopicSchema 默认实现。</p>
 */
public class PilotTopicSchema implements TopicSchema {

    /** thing/product 前缀的 status 模板（Pilot 上云拓扑上行） */
    private static final String THING_PRODUCT_STATUS = TopicTemplate.thingProduct(TopicChannel.STATUS.suffix());

    @Override
    public String status() {
        return THING_PRODUCT_STATUS;
    }

    @Override
    public String statusReply() {
        return TopicTemplate.STATUS_REPLY;
    }
}
