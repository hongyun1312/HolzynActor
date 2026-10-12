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

package com.holzyn.actor.domain.unity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Unity 对接（U 轨道 U1 服务端对接层）配置，。
 * <p>对应《HolzynActor_Unity对接_开发路径 .md》§4.3 配置草案：
 * 时钟源切换（internal|unity）/ 坐标换算基准（1 格=N 米+原点偏移）/ 重连容忍窗口 /
 * 感知上报开关与冷却 / 心跳看门狗。</p>
 * <p>⚠️ 冒烟教训：嵌套档位类禁用 record（构造器绑定依赖 -parameters
 * 编译标志，项目未启用 → 绑定静默失败全部回退默认）；一律用普通 POJO（@Getter/@Setter）
 * 与项目既有风格一致；注册方式与项目既有配置类一致 = @Component + @ConfigurationProperties。</p>
 */
@Component
@ConfigurationProperties(prefix = "holzyn.actor.unity")
@Getter
@Setter
public class UnityProperties {

    /** Unity 对接总开关（false=WS 端点不注册/上报不生效，行为回退现状） */
    private boolean enabled = true;

    /** WebSocket 端点前缀（完整路径 = ws-path + "/{projectId}"，默认 /ws/unity/{projectId}） */
    private String wsPath = "/ws/unity";

    /** 时钟段（外部时钟权威：Unity 上报驱动，见 ExternalClockService） */
    private Clock clock = new Clock();

    /** 坐标段（HA 网格 ↔ Unity 世界坐标换算，见 CoordinateTransformer） */
    private Coordinate coordinate = new Coordinate();

    /** 重连段（断线容忍窗口） */
    private Reconnect reconnect = new Reconnect();

    /** 感知段（UNITY_PERCEPTION 刺激源：目标导向 + 可选广播） */
    private Perception perception = new Perception();

    /** 心跳段（服务端下行心跳 + 失联看门狗） */
    private Heartbeat heartbeat = new Heartbeat();

    /** 时钟配置：Unity 连接时时钟权威=Unity（上报驱动），断开回退内部 */
    @Getter
    @Setter
    public static class Clock {
        /** 时钟源：unity（外部时钟权威，连接时生效）/ internal（内部自走，回退） */
        private String source = "unity";
        /**
         * 建议倍率（1 真实秒 = N 游戏秒；demo 建议 60=1 真实秒 1 游戏分钟，1 游戏日≈24 真实分钟；
         * 仅作为 U2 客户端参考与启动提示，实际推进以 Unity 上报 gameSecond 为准）
         */
        private int rate = 60;
        /** 单次上报推进封顶（游戏秒，默认 86400=24 游戏小时：防 Unity 端异常大跳/重连补报雪崩） */
        private long maxCatchUpGameSeconds = 86400L;
    }

    /** 坐标配置：HA 网格 ↔ Unity 世界坐标（1 格 = N 米 + 原点偏移） */
    @Getter
    @Setter
    public static class Coordinate {
        /** 1 HA 格 = N Unity 米（默认 0.2：500 网格 ↔ 100m 场景，原点对齐场景中心） */
        private double meterPerCell = 0.2;
        /** 原点偏移 X（Unity 世界坐标 = HA 网格 × meterPerCell + offset） */
        private double originOffsetX = 0;
        /** 原点偏移 Z */
        private double originOffsetZ = 0;
    }

    /** 重连配置：断线容忍窗口（超时后按静止处理） */
    @Getter
    @Setter
    public static class Reconnect {
        /** 重连容忍窗口（秒，默认 60：窗口内重连=接续推进；超时未连=世界静止） */
        private int maxWindowSeconds = 60;
    }

    /** 感知配置：UNITY_PERCEPTION 刺激源 */
    @Getter
    @Setter
    public static class Perception {
        /** 感知上报→即时决策总开关（false=上报仅记日志不触发决策） */
        private boolean enabled = true;
        /** 目标导向冷却（游戏分钟，默认 10：与即时决策既有每源冷却同量级，项目级兜底） */
        private int cooldownGameMinutes = 10;
        /** 广播级冷却（游戏分钟，默认 60：broadcast=true 全场景刺激的独立冷却档，防刷屏） */
        private int broadcastCooldownGameMinutes = 60;
        /** 广播单次目标数上限（默认 20：超限截断，防视野广播 AI 雪崩） */
        private int broadcastMaxTargets = 20;
    }

    /** 心跳配置：连接活性看门狗 */
    @Getter
    @Setter
    public static class Heartbeat {
        /** 服务端下行心跳间隔（秒，默认 15） */
        private int intervalSeconds = 15;
        /** 失联判定（秒，默认 45：超过该值未收到客户端任何消息 → 断开连接并触发世界静止） */
        private int timeoutSeconds = 45;
    }
}
