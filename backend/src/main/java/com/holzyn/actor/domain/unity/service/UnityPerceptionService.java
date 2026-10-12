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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.agent.runtime.StimulusType;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unity 感知刺激服务（U 轨道 U1 服务端对接层，）——新刺激源
 * {@link StimulusType#UNITY_PERCEPTION} 的接入适配。
 * <p><b>目标导向为主 + 可选广播</b>：</p>
 * <ul>
 * <li><b>目标导向</b>（默认）：Unity 在玩家与特定 NPC 产生交集时上报该 NPC
 * （perception_report{targetNpcRefs:[...]}）→ HA 只触发这些 NPC 的即时决策——
 * 克制、成本可控、语义精准（谁被刺激谁反应，像真人「只有与你产生交集的人才注意到你」）；</li>
 * <li><b>可选广播</b>（broadcast=true）：Unity 想触发「全场景注意」类时刻（玩家大喊/重大事件）
 * 时可显式广播视野内 NPC 列表——受<b>项目级广播冷却</b>（独立档，默认 60 游戏分钟）与
 * <b>单次目标数上限</b>（默认 20）双重约束防刷屏/AI 雪崩。</li>
 * </ul>
 * <p>复用链路：逐目标 {@link RealTimeDecisionService#onStimulus}——激活闸门（Unity 独占激活自动
 * 兼容）/ 每角色每源冷却（默认 10 游戏分钟）/ 引擎预算预检 / 游戏时刻钳制 / 异步触发全链路
 * 照常生效，零新通道。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnityPerceptionService {

    /** Unity 对接配置（感知开关/广播冷却/广播上限） */
    private final UnityProperties props;

    /** 实时决策服务（onStimulus 目标导向触发） */
    private final RealTimeDecisionService realTimeDecisionService;

    /**
     * NPC 类型解析器（：npc:{id} 不再编码类型，"仅特殊型"改查 actor_profile.type；
     * 字段注入避免改构造器破坏存量单测，null=回退按引用合法即放行）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcTypeResolver npcTypeResolver;

    /** 每项目广播级冷却（projectId → 最近广播的游戏秒；游戏时间口径，重启丢失可接受） */
    private final ConcurrentHashMap<Long, Long> lastBroadcastGameSecond = new ConcurrentHashMap<>();

    /**
     * 感知上报入口（WS perception 消息消费）。
     *
     * @param projectId 项目 ID
     *
     * @param targetNpcRefs 目标 NPC 引用列表（统一 {@code npc:{id}}，仅特殊型生效）
     *
     * @param text 刺激文本（如「玩家朝你走来，目光停留在你身上」）
     *
     * @param gameSecond 游戏秒（可空=实时决策内部钳制到引擎位置）
     *
     * @param broadcast 是否广播级（true=全场景注意；false=目标导向）
     *
     * @return 处理结果（status: disabled/invalid/skipped_broadcast/triggered；triggeredNpcRefs 实际触发数）
     */
    public UnityPerceptionResult onPerceptionReport(Long projectId, List<String> targetNpcRefs,
                                                    String text, Long gameSecond, Boolean broadcast) {
        boolean isBroadcast = Boolean.TRUE.equals(broadcast);
        if (!props.isEnabled()) {
            return new UnityPerceptionResult("disabled", 0, "Unity 感知上报未启用");
        }
        if (projectId == null || targetNpcRefs == null || targetNpcRefs.isEmpty()) {
            return new UnityPerceptionResult("invalid", 0, "perception 缺少 targetNpcRefs");
        }
        // 广播级：项目级广播冷却（独立档，防全场景刷屏）
        if (isBroadcast) {
            int cooldownGameSec = props.getPerception().getBroadcastCooldownGameMinutes() * 60;
            Long nowSec = gameSecond == null ? Long.valueOf(0) : gameSecond;
            Long last = lastBroadcastGameSecond.get(projectId);
            if (last != null && cooldownGameSec > 0 && nowSec - last < cooldownGameSec) {
                log.debug("[Unity感知] 广播冷却期内跳过: project={} 距上次={} 秒", projectId, nowSec - last);
                return new UnityPerceptionResult("skipped_broadcast", 0,
                        "广播冷却期内跳过（" + (nowSec - last) + " 游戏秒）");
            }
            lastBroadcastGameSecond.put(projectId, nowSec);
        }

        // 过滤：仅特殊 NPC（common 走分层概率化零 AI——onStimulus 内部也会忽略，此处提前过滤少打日志）
        int broadcastCap = props.getPerception().getBroadcastMaxTargets();
        List<String> targets = new ArrayList<>();
        for (String ref : targetNpcRefs) {
            // ：npc:{id} 不再编码类型，此处按意图 B 处理（仅特殊型进 Unity 感知；解析器未装配按引用合法即放行）
            if (ref != null && NpcRefs.isNpcRef(ref)
                    && (npcTypeResolver == null || npcTypeResolver.isSpecial(projectId, ref))) {
                targets.add(ref);
                if (isBroadcast && targets.size() >= broadcastCap) {
                    log.info("[Unity感知] 广播目标数超上限截断: project={} 保留 {} 个（上限 {}）",
                            projectId, targets.size(), broadcastCap);
                    break;
                }
            }
        }
        if (targets.isEmpty()) {
            return new UnityPerceptionResult("invalid", 0, "targetNpcRefs 无有效特殊 NPC");
        }
        String stimulusText = (text == null || text.isBlank())
                ? (isBroadcast ? "有人引起了全场注意" : "你注意到有人朝你走来")
                : text.trim();

        // 逐目标触发（复用既有仲裁：激活/冷却/预算/钳制/异步全链路）
        int triggered = 0;
        for (String ref : targets) {
            try {
                realTimeDecisionService.onStimulus(projectId, ref, StimulusType.UNITY_PERCEPTION,
                        stimulusText, gameSecond);
                triggered++;
            } catch (Exception e) {
                log.warn("[Unity感知] 目标刺激触发失败（隔离继续）: project={} ref={} : {}", projectId, ref, e.getMessage());
            }
        }
        log.info("[Unity感知] 上报处理完成: project={} 目标={} 实际触发={} 广播={} 文本={}",
                projectId, targets.size(), triggered, isBroadcast, abbreviate(stimulusText));
        return new UnityPerceptionResult("triggered", triggered,
                "已触发 " + triggered + "/" + targets.size() + " 个目标 NPC 即时决策");
    }

    /** 刺激文本截断（日志用）。 */
    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    /**
     * 感知上报处理结果（测试/日志断言用）。
     *
     * @param status 状态：disabled/invalid/skipped_broadcast/triggered
     *
     * @param triggered 实际触发目标数
     *
     * @param message 说明
     */
    public record UnityPerceptionResult(String status, int triggered, String message) {
        /** 便捷判断：是否实际触发 */
        public boolean wasTriggered() {
            return "triggered".equals(status);
        }
    }
}
