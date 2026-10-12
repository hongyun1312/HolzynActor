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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 独处判定服务。
 * <p>职责（主开发方案《HolzynActor_实时行为决策系统_开发路径》§4.5 真人感五维度之
 * 「独处行为」）：判定某特殊 NPC 此刻是否「独自一人」——<b>同地点无其他特殊 NPC 且无人群 NPC</b>
 * ；独处时由决策通道注入【独处】段（周期 ENGINE_TICK 与
 * 即时 STIMULUS 双轨，），让 AI 自行决定独处时的真实行为（发呆/自娱/自言自语/整理/
 * 小憩等，保留 AI 丰富度）；AI 不可用时由既有程序化兜底承接（0 AI 成本）。</p>
 * <p>设计依据（联网调研）：Simulating Human-like Daily Activities with
 * Desire-driven Autonomy（arXiv:2412.06435，欲望驱动活动候选评估）与 Affordable Generative Agents
 * （arXiv:2402.02053，分层计划+原子动作池）——独处行为是「可信日常」的重要组成部分；本实现
 * 以<b>注入上下文 + 保留 AI 决策</b>形态落地（用户选择），程序化独处动作池由 AgentRuntime
 * 既有程序化决策兜底承载。</p>
 * <p>成本：纯文本判定 + 文本段组装，零 AI；判定失败降级返回「非独处」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolitudeService {

    /** 独处段文本（进入决策情境；明确「独自一人」让 AI 选择独处行为） */
    private static final String SOLITUDE_SEGMENT = "【独处】此刻四周没有旁人，只有你独自一人";

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final RealtimeDecisionProperties props;

    /**
     * NPC 类型解析器（注入：独处行为属「只有特殊型才做」——npc:{id} 不再编码类型，
     * 是否特殊型必须查 actor_profile.type，Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /**
     * 独处判定：同地点（角色 location 名归一化相等）无其他特殊 NPC 且无人群 NPC。
     * <p>特殊+人群都算「在场有人」——只有两者都不在场才算独处；
     * 人群 NPC 位置取 actor_ordinary_npc.location（普通 NPC 实体冗余字段，与沙盘同口径）。</p>
     *
     * @param projectId 项目 ID（null → false）
     *
     * @param npcRef 角色引用（npc:{id}；非特殊型/未装配 → false）
     *
     * @return true 表示该角色此刻独处
     */
    public boolean isAlone(Long projectId, String npcRef) {
        // ：npc:{id} 不再编码类型，此处按意图 B 处理——独处判定属「只有特殊型才做」的行为
        // （普通型走人群程序化），是否特殊型改用 NpcTypeResolver 查 actor_profile.type 判定；
        // 解析失败回退 special（与历史「非 special 前缀才排除」的保守方向一致）
        if (!props.isSolitudeEnabled() || projectId == null || npcRef == null
                || !npcTypeResolver.isSpecial(projectId, npcRef)) {
            return false;
        }
        // ：解析主键统一走 NpcRefs.idOf（保持原有 null → false 的处理）
        Long id = NpcRefs.idOf(npcRef);
        if (id == null) {
            return false;
        }
        String ownLocation = null;
        List<ActorCharacter> chars;
        try {
            // 长跑抓修：排除已故角色（死人不在场，不阻断他人独处）
            chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                    .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        } catch (Exception e) {
            log.debug("[独处] 特殊角色列表读取失败（按非独处处理）: project={} : {}", projectId, e.getMessage());
            return false;
        }
        for (ActorCharacter ch : chars) {
            if (ch == null) {
                continue;
            }
            if (id.equals(ch.getId())) {
                ownLocation = ch.getLocation();
                continue;
            }
            if (Integer.valueOf(1).equals(ch.getDeleted())) {
                continue;
            }
            if (locationEquals(ownLocation, ch.getLocation())) {
                return false; // 有其他特殊 NPC 在场
            }
        }
        if (ownLocation == null || ownLocation.isBlank()) {
            // 自身位置未知 → 保守视为非独处（不注入，避免误判）
            return false;
        }
        try {
            List<ActorCharacter> npcs = ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId);
            for (ActorCharacter n : npcs) {
                if (n != null && locationEquals(ownLocation, n.getLocation())) {
                    return false; // 有人群 NPC 在场
                }
            }
        } catch (Exception e) {
            log.debug("[独处] 人群列表读取失败（按非独处处理）: project={} : {}", projectId, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * 独处段文本：独处且开关开启 → 【独处】段；否则空串。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（npc:{id}）
     *
     * @return 独处段文本（非独处/开关关/失败返回空串）
     */
    public String solitudeSegment(Long projectId, String npcRef) {
        return isAlone(projectId, npcRef) ? SOLITUDE_SEGMENT : "";
    }

    /**
     * 地点名归一化相等（trim + 忽略大小写；与 RealTimeDecisionService.locationEquals 同口径）。
     */
    static boolean locationEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }
}
