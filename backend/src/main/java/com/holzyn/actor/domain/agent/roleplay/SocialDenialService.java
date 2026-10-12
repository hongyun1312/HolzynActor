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

package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.learning.RelationDynamicsService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 社交拒绝连锁。
 * <p><b>（逐题确认）</b>：拒绝后触发连锁——① NPC 记忆 L1 写一条
 * （「刚才 XX 找我说话，我没理会」）② 对搭话者好感微降（-2，经既有关系写方；
 * 搭话者是玩家则跳过——玩家不落 actor_profile、无关系行）③ NPC 情绪异步评估一次
 * ④ 沙盘想法气泡显示拒绝理由（正忙/疲惫等）；<b>不发世界事件</b>（琐碎拒绝不该刷编年史）。</p>
 * <p><b>架构不变量</b>：本类只做旁路副作用（写记忆/好感/情绪/沙盘均为短操作或异步），
 * 公开方法不带 {@code @Transactional}（情绪评估是异步 AI 调用路径）；任何副作用失败
 * 仅告警不阻断对话主流程。</p>
 */
@Slf4j
@Component
public class SocialDenialService {

    /** 拒绝后对搭话者好感降低量 */
    public static final int AFFINITY_DROP = -2;

    @Autowired(required = false)
    private AgentMemoryService agentMemoryService;

    @Autowired(required = false)
    private RelationDynamicsService relationDynamicsService;

    @Autowired(required = false)
    private NpcStateService npcStateService;

    @Autowired(required = false)
    private WorldSandboxSseHub sandboxSseHub;

    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 拒绝连锁入口。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 被拒方（NPC）引用 npc:{id}
     *
     * @param npcType NPC 类型（special/common；情绪评估用）
     *
     * @param partnerRef 搭话者引用（npc:{id}；玩家会话传 null=无关系行）
     *
     * @param partnerName 搭话者名（可空；记忆文本用）
     *
     * @param denialText 程序化拒绝文本（沙盘想法展示；可空）
     *
     * @param gameSecond 当前世界时刻（游戏秒；可空=时钟当前）
     */
    public void onDenial(Long projectId, String npcRef, String npcType,
                         String partnerRef, String partnerName,
                         String denialText, Long gameSecond) {
        if (projectId == null || npcRef == null) {
            return;
        }
        Long gs = gameSecond;
        if (gs == null && worldClockService != null) {
            try {
                gs = worldClockService.currentEngineGameSecond(projectId);
            } catch (Exception e) {
                log.debug("[社交拒绝] 世界时钟读取失败（跳过）: {}", e.getMessage());
            }
        }
        // ① 记忆 L1（NPC 侧：被搭话但没理会）
        writeMemory(projectId, npcRef, partnerName, gs);
        // ② 对搭话者好感微降（仅 npc:npc；玩家跳过）
        if (partnerRef != null && partnerRef.startsWith("npc:")) {
            adjustAffinity(projectId, npcRef, partnerRef, partnerName, gs);
        }
        // ③ 情绪异步评估一次（拒绝是轻微情绪事件）
        evaluateEmotion(projectId, npcRef, npcType, gs);
        // ④ 沙盘想法气泡（拒绝理由；失败静默）
        publishThought(projectId, npcRef, denialText, gs);
    }

    /** ① 记忆 L1：被搭话没理会（importance 3=值得记住的日常事件；写入失败静默）。 */
    private void writeMemory(Long projectId, String npcRef, String partnerName, Long gs) {
        if (agentMemoryService == null) {
            return;
        }
        try {
            String who = partnerName == null || partnerName.isBlank() ? "有人" : partnerName;
            String content = "刚才" + who + "找我说话，我没理会"
                    + (gs != null ? "" : "");
            agentMemoryService.recordObservation(projectId, npcRef, content, 3);
        } catch (Exception e) {
            log.debug("[社交拒绝] 记忆写入失败（静默）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /** ② 对搭话者好感微降（经 RelationDynamicsService 既有写方；失败静默）。 */
    private void adjustAffinity(Long projectId, String npcRef, String partnerRef, String partnerName, Long gs) {
        if (relationDynamicsService == null) {
            return;
        }
        try {
            relationDynamicsService.updateRelation(projectId, npcRef, partnerRef, AFFINITY_DROP,
                    "搭话被无视（社交意愿拒绝）");
            log.debug("[社交拒绝] 好感 {}：{} → {}（{}）", AFFINITY_DROP, npcRef, partnerRef, partnerName);
        } catch (Exception e) {
            log.debug("[社交拒绝] 好感调整失败（静默）: {} → {} : {}", npcRef, partnerRef, e.getMessage());
        }
    }

    /** ③ 情绪异步评估一次（拒绝是轻微负面社交事件；冷却/门控在服务内）。 */
    private void evaluateEmotion(Long projectId, String npcRef, String npcType, Long gs) {
        if (npcStateService == null || npcRef == null) {
            return;
        }
        try {
            String type = NpcRefs.normalizeType(npcType);
            npcStateService.evaluateEmotion(projectId, npcRef, type, "社交拒绝", "被人搭话，没有理会", gs);
        } catch (Exception e) {
            log.debug("[社交拒绝] 情绪评估挂钩失败（静默）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /** ④ 沙盘想法气泡（拒绝理由；失败静默）。 */
    private void publishThought(Long projectId, String npcRef, String denialText, Long gs) {
        if (sandboxSseHub == null) {
            return;
        }
        try {
            String thought = denialText == null || denialText.isBlank()
                    ? "（正忙，没顾上理会。）" : "（" + stripBrackets(denialText) + "）";
            sandboxSseHub.publishNpcThought(projectId, npcRef, thought, "social_denial", gs);
        } catch (Exception e) {
            log.debug("[社交拒绝] 沙盘想法推送失败（静默）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /** 剥拒绝文本外侧括号（沙盘想法展示时避免双层括号）。 */
    private static String stripBrackets(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.startsWith("（") && t.endsWith("）")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
