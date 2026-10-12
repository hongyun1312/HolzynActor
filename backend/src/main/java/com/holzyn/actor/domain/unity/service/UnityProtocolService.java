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

package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.unity.model.UnityEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unity 对接协议编解码与校验（U 轨道 U1 服务端对接层，契约 v1，）。
 * <p>职责：envelope{type,seq,projectId,gameSecond,payload,traceId} 的 JSON 编解码、
 * 上行类型白名单校验、traceId 生成；seq 幂等/断点续传对账由
 * {@link UnitySessionRegistry}（连接级状态）承担。</p>
 * <p>下行（HA → Unity）type 常量与《HolzynActor_Unity对接_开发路径 .md》§3.3
 * 数据契约 v1 对齐：clock/weather/event/npc_position/npc_state/npc_thought/npc_talk/
 * npc_join/npc_leave（action 语义由 npc_state.actionText + event 承载，U2 精化）；</p>
 * <p>上行（Unity → HA）type：hello/heartbeat/clock_report/perception/environment/
 * player_action/intervention。</p>
 */
@Slf4j
@Service
public class UnityProtocolService {

    // ==================== 下行 type 常量（HA → Unity，见开发路径 §3.3） ====================

    /** 连接确认（连接建立后立即下发） */
    public static final String DOWN_CONNECTED = "connected";
    /** 服务端心跳（intervalSeconds 间隔下行） */
    public static final String DOWN_HEARTBEAT = "heartbeat";
    /** 时钟同步（lastGameSecond/rate/暂停态） */
    public static final String DOWN_CLOCK = "clock";
    /** 天气/环境变化（weather 载荷同沙盘 SSE） */
    public static final String DOWN_WEATHER = "weather";
    /** 世界事件（载荷同沙盘 scene:event → WorldEventViews） */
    public static final String DOWN_EVENT = "event";
    /** NPC 位置/朝向更新（移动插值目标） */
    public static final String DOWN_NPC_POSITION = "npc_position";
    /** NPC 动作/状态切换（actionText/statusText/内部状态） */
    public static final String DOWN_NPC_STATE = "npc_state";
    /** NPC 想法气泡（optional） */
    public static final String DOWN_NPC_THOUGHT = "npc_thought";
    /** NPC 对话消息（单聊/群聊/自主，文字气泡） */
    public static final String DOWN_NPC_TALK = "npc_talk";
    /** NPC 进入场景 */
    public static final String DOWN_NPC_JOIN = "npc_join";
    /** NPC 离开场景 */
    public static final String DOWN_NPC_LEAVE = "npc_leave";
    /** 干预命令执行结果（WS intervention 兜底回执；REST 路径无此消息） */
    public static final String DOWN_INTERVENTION_RESULT = "intervention_result";
    /**
     * 人格偏移（npc:personality_shift → 此信封；载荷 {npcRef,npcName,dim,field,old,new,delta,why}，
     * Unity 侧可驱动模型表现/关系变化）
     */
    public static final String DOWN_NPC_PERSONALITY_SHIFT = "npc_personality_shift";
    /**
     * 面具情境变化（npc:mask_context_change → 此信封；载荷 {npcRef,contextCode,contextLabel,
     * suppression,cause}，Unity 侧可驱动表情/语气切换）
     */
    public static final String DOWN_NPC_MASK_CHANGE = "npc_mask_change";
    /**
     * 新生儿出生（npc:birth → 此信封；载荷 {npcRef,npcName,gender,motherRef,fatherRef,gameSecond}，
     * Unity 侧可驱动新生儿入场景/挂件）
     */
    public static final String DOWN_NPC_BIRTH = "npc_birth";
    /**
     * 阶段跨越（npc:stage_transition → 此信封；载荷 {npcRef,npcName,fromStage,toStage,age,gameSecond}，
     * Unity 侧可驱动模型/动画切换）
     */
    public static final String DOWN_NPC_STAGE_TRANSITION = "npc_stage_transition";
    /**
     * 死亡（npc:death → 此信封；载荷 {npcRef,npcName,reason,gameSecond}，
     * Unity 侧可驱动角色退场/灵堂表现）
     */
    public static final String DOWN_NPC_DEATH = "npc_death";
    /** 协议错误回执（type 非法/解析失败/projectId 不一致等） */
    public static final String DOWN_ERROR = "error";

    // ==================== 上行 type 常量（Unity → HA） ====================

    /** 连接握手（客户端告知版本/能力，服务端回 connected） */
    public static final String UP_HELLO = "hello";
    /** 客户端心跳（保持活性；可携带最新 gameSecond） */
    public static final String UP_HEARTBEAT = "heartbeat";
    /** 时钟上报 {gameSecond, rate?} → 外部时钟权威推进 */
    public static final String UP_CLOCK_REPORT = "clock_report";
    /** 感知上报 {targetNpcRefs:[...], text, gameSecond, broadcast?} → UNITY_PERCEPTION 刺激 */
    public static final String UP_PERCEPTION = "perception";
    /** 环境上报 {weather, temperature?, wind?} → 帧天气覆盖 + 刺激 + SSE */
    public static final String UP_ENVIRONMENT = "environment";
    /** 玩家行动上报 {action: walk|talk|trigger|interrupt, targetNpcRef?, text?} → 日志留痕 + 对话/干预通道 */
    public static final String UP_PLAYER_ACTION = "player_action";
    /** 干预命令（WS 兜底；REST /intervene/* 为推荐路径） */
    public static final String UP_INTERVENTION = "intervention";

    /** 上行类型白名单（未知 type 拒绝） */
    public static final Set<String> UPSTREAM_TYPES = Set.of(
            UP_HELLO, UP_HEARTBEAT, UP_CLOCK_REPORT, UP_PERCEPTION,
            UP_ENVIRONMENT, UP_PLAYER_ACTION, UP_INTERVENTION);

    /** Jackson 3（Spring Boot 4 默认） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解码信封（上行消息入口）：非法 JSON / 缺 type 抛 BizException(400)（handler 捕获后回错误信封）。
     *
     * @param json 原始文本
     *
     * @return 信封（payload 缺省空 Map；seq/traceId 可空）
     */
    @SuppressWarnings("unchecked")
    public UnityEnvelope decode(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException(400, "Unity 消息为空");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            String type = map.get("type") == null ? null : String.valueOf(map.get("type"));
            if (type == null || type.isBlank()) {
                throw new BizException(400, "Unity 消息缺少 type 字段");
            }
            Long seq = map.get("seq") == null ? null : ((Number) map.get("seq")).longValue();
            Long projectId = map.get("projectId") == null ? null : ((Number) map.get("projectId")).longValue();
            Long gameSecond = map.get("gameSecond") == null ? null : ((Number) map.get("gameSecond")).longValue();
            Map<String, Object> payload = map.get("payload") instanceof Map m ? (Map<String, Object>) m : new LinkedHashMap<>();
            String traceId = map.get("traceId") == null ? null : String.valueOf(map.get("traceId"));
            return new UnityEnvelope(type, seq, projectId, gameSecond, payload, traceId);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Unity 消息 JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 编码信封（下行消息出口）：对象 → JSON 文本（失败抛 BizException(500)）。
     *
     * @param envelope 信封
     *
     * @return JSON 文本
     */
    public String encode(UnityEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new BizException(500, "Unity 消息编码失败: " + e.getMessage());
        }
    }

    /**
     * 上行类型校验：白名单外返回 false（handler 回错误信封）。
     *
     * @param type 消息类型
     *
     * @return true=允许
     */
    public boolean isUpstreamType(String type) {
        return type != null && UPSTREAM_TYPES.contains(type);
    }

    /**
     * 生成链路追踪 ID（envelope.traceId 贯通审计链路；客户端未带时服务端生成）。
     *
     * @return 追踪 ID（"ut-" + 32 位随机）
     */
    public String newTraceId() {
        return "ut-" + UUID.randomUUID().toString().replace("-", "");
    }
}
