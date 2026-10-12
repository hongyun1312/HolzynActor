/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.unity.model;

import java.util.Map;

/**
 * Unity 对接消息信封（U 轨道 U1 服务端对接层，契约 v1 编解码模型，）。
 * <p>对应《HolzynActor_Unity对接_开发路径 .md》§3.2 envelope 模型：
 * {@code {type, seq, projectId, gameSecond, payload, traceId}}——seq 递增幂等、
 * 重连断点续传（对账）、traceId 贯通 HA 审计链路。</p>
 * <p><b>type 取值（Unity ↔ HA 双向）</b>：</p>
 * <ul>
 * <li>HA → Unity 下行：{@code heartbeat}（心跳）/ {@code connected}（连接确认）/
 * {@code scene_init}（REST /init 之外的下行提示，可选）/ {@code clock} /
 * <li>Unity → HA 上行：{@code hello}（握手）/ {@code heartbeat}（心跳）/
 * {@code clock_report}（时钟上报）/ {@code perception}（感知上报）/
 * {@code environment}（环境上报）/ {@code player_action}（玩家行动上报）/
 * {@code intervention}（干预命令，REST 优先，WS 兜底）。</li>
 * </ul>
 */
public record UnityEnvelope(
        /** 消息类型（见类注释 type 取值表） */
        String type,
        /** 发送方单调递增序号（幂等/断点续传对账；服务端下行 heartbeat 可不带） */
        Long seq,
        /** 项目 ID（上行必填；下行由服务端填充） */
        Long projectId,
        /** 游戏秒（时钟上报/感知上报携带；可空） */
        Long gameSecond,
        /** 业务载荷（Map；各 type 契约见 UnityProtocolService/各消费服务） */
        Map<String, Object> payload,
        /** 链路追踪 ID（Unity 端生成则贯通，缺失由服务端生成） */
        String traceId) {

    /**
     * 便捷构造：省略 traceId。
     *
     * @param type 消息类型
     *
     * @param seq 序号（可空）
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏秒（可空）
     *
     * @param payload 载荷（可空=空 Map）
     *
     * @return 信封
     */
    public static UnityEnvelope of(String type, Long seq, Long projectId, Long gameSecond, Map<String, Object> payload) {
        return new UnityEnvelope(type, seq, projectId, gameSecond, payload, null);
    }
}
