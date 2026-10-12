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

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色扮演上下文服务（B5 普通 NPC 参与通道立基，domain/agent/roleplay）。
 * <p>职责：为对话侧（单聊/群聊）解析「以角色为视角」的 system_prompt 与名称——<b>成员类型化</b>
 * （方案 A，闭环评估 §7.4）：</p>
 * <ul>
 * <li><b>special（特殊型）</b>：取该角色 {@code actor_profile.persona_json} 的 11 维档案<b>分片渲染</b>
 * ；</li>
 * <li><b>common（普通居民，历史值 crowd）</b>：用<b>居民档案渲染</b>——姓名/性别/种族/次级种族/年龄/归属/
 * 职业/所在地/角色详情 + 世界观片段（项目概要节选），让居民以「本人身份」自然对话
 * （修 L-09 普通 NPC 只是背景字符串）。</li>
 * </ul>
 * <p>世界观片段：取项目概要 summary 前 {@link #WORLD_SNIPPET} 字（无概要则省略），
 * 防止居民 OOC 的同时不喧宾夺主。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleplayContextService {

    /** 世界观片段长度（字符，取自项目概要前 N 字） */
    static final int WORLD_SNIPPET = 200;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    /** 角色主表仓库 */
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final ActorProjectRepository projectRepository;

    /**
     * 解析成员的角色 system_prompt（成员类型化渲染）。
     *
     * @param projectId 项目 ID（世界观片段来源）
     *
     * @param memberType 成员类型（{@code common}/历史 {@code crowd} → 居民档案渲染；{@code special} → 11 维档案分片）
     *
     * @param memberId 成员对象 ID
     *
     * @return 角色 system_prompt（解析失败降级通用世界观提示）
     */
    public String resolveCharacterPrompt(Long projectId, String memberType, Long memberId) {
        if (memberId == null) {
            return fallback();
        }
        // ：npc:{id} 不再编码类型，且 member_type 库内已统一写 common（历史行可能仍是 crowd）；
        // 因此一律经 NpcRefs.normalizeType 归一后判定，避免"普通居民被当特殊角色渲染"。
        if (com.holzyn.actor.common.NpcRefs.isCommonType(memberType)) {
            ActorCharacter npc = npcRepository.findById(memberId).orElse(null);
            if (npc == null) {
                return fallback();
            }
            return renderCrowdPrompt(npc, worldSnippet(projectId));
        }
        // ：角色卡 system_prompt 已废弃（角色卡表物理删除）——改为按"单聊场景"分片渲染 11 维档案；
        // 空档案回退既有兜底文本（数据缺失兜底，不是兼容层）。
        String personaShards = personaService == null ? "" : personaService.renderShardsForScene(memberId, "dialog_system");
        if (personaShards == null || personaShards.isBlank()) {
            return fallback();
        }
        // Prompt 工程规范 §1：system 以「你是{name}，绝不能跳出角色」角色锚定开场（SYSTEM 层），
        // 再跟 11 维档案分片（ROLE_PROFILE 层）——与普通 NPC 的 renderCrowdPrompt / 工具类请求
        // （system=规则句）风格一致；锚定句与档案均跨请求逐字节稳定，不伤前缀缓存。
        String name = characterRepository.findById(memberId).map(ActorCharacter::getName).orElse(null);
        String anchor = (name == null || name.isBlank()) ? "你是一位角色，绝不能跳出角色。\n"
                : "你是" + name + "，绝不能跳出角色。\n";
        return anchor + personaShards;
    }

    /**
     * 解析成员显示名（成员类型化）。
     *
     * @param memberType 成员类型
     *
     * @param memberId 成员对象 ID（恒为 actor_profile.id）
     *
     * @return 成员名（解析失败返回 null）
     */
    public String characterName(String memberType, Long memberId) {
        if (memberId == null) {
            return null;
        }
        // ：单表后 special 与 common 的名称同源，无需再按类型分叉取数（原两分支实为同一张表）。
        return characterRepository.findById(memberId).map(ActorCharacter::getName).orElse(null);
    }

    /**
     * 普通 NPC 档案渲染（B5，静态可测）：姓名/性别/种族/次级种族/年龄/归属/职业/所在地/详情 + 世界观片段。
     *
     * @param npc 普通 NPC 档案
     *
     * @param worldSnippet 世界观片段（可空=省略）
     *
     * @return 渲染后的角色 system_prompt
     */
    public static String renderCrowdPrompt(ActorCharacter npc, String worldSnippet) {
        if (npc == null) {
            return "你是这个世界中的一位普通居民，请保持角色身份与世界观一致地进行对话。";
        }
        StringBuilder sb = new StringBuilder("你是").append(nameOf(npc)).append("——");
        // 身份要素：性别/种族（含次级种族）/年龄/归属/职业/所在地，存在才拼，避免空字段堆砌
        List<String> bits = identityBits(npc);
        sb.append(String.join("，", bits));
        // 角色详情（本人背景/性格/谋生方式等）
        if (npc.getDetail() != null && !npc.getDetail().isBlank()) {
            sb.append("。\n关于你：").append(npc.getDetail().trim());
        }
        // 世界观片段（防 OOC 的背景约束，节选）
        if (worldSnippet != null && !worldSnippet.isBlank()) {
            sb.append("\n世界观背景：").append(worldSnippet.trim());
        }
        sb.append("\n请以这个普通居民的身份自然地与对方对话：说符合你身份、性格和处境的话，"
                + "可以闲聊、询问、抱怨、分享见闻，但不要编造与你的身份/经历无关的超纲设定。");
        return sb.toString();
    }

    /**
     * 身份要素列表：性别/种族（含次级种族）/年龄/归属/职业/所在地（空值跳过）。
     */
    private static List<String> identityBits(ActorCharacter npc) {
        List<String> bits = new ArrayList<>();
        if (npc.getGender() != null && !npc.getGender().isBlank()) {
            bits.add(npc.getGender().trim());
        }
        String race = raceOf(npc);
        if (race != null) {
            bits.add(race);
        }
        if (npc.getAge() != null) {
            bits.add(npc.getAge() + "岁");
        }
        if (npc.getAffiliation() != null && !npc.getAffiliation().isBlank()) {
            bits.add("隶属" + npc.getAffiliation().trim());
        }
        if (npc.getOccupation() != null && !npc.getOccupation().isBlank()) {
            bits.add("职业为" + npc.getOccupation().trim());
        }
        if (npc.getLocation() != null && !npc.getLocation().isBlank()) {
            bits.add("现居" + npc.getLocation().trim());
        }
        if (bits.isEmpty()) {
            bits.add("一位普通居民");
        }
        return bits;
    }

    /**
     * 世界观片段：项目概要 summary 前 N 字（无概要/空白返回 null）。
     *
     * @param projectId 项目 ID
     *
     * @return 世界观片段（可空）
     */
    String worldSnippet(Long projectId) {
        if (projectId == null) {
            return null;
        }
        String summary = projectRepository.findById(projectId)
                .map(ActorProject::getSummary).orElse(null);
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String s = summary.trim();
        return s.length() <= WORLD_SNIPPET ? s : s.substring(0, WORLD_SNIPPET) + "…";
    }

    /**
     * 种族全称（含次级种族：人族→汉族 拼为「汉族」；无次级种族用一级种族）。
     */
    private static String raceOf(ActorCharacter npc) {
        String race = npc.getRace() == null ? null : npc.getRace().trim();
        String sub = npc.getSubRace() == null ? null : npc.getSubRace().trim();
        if (sub != null && !sub.isEmpty() && !sub.equals(race)) {
            return sub;
        }
        return race == null || race.isEmpty() ? null : race;
    }

    /**
     * 名称兜底（无姓名显示「一位居民」）。
     */
    private static String nameOf(ActorCharacter npc) {
        return npc.getName() == null || npc.getName().isBlank() ? "一位居民" : npc.getName().trim();
    }

    /**
     * 降级提示（成员解析失败）。
     */
    private static String fallback() {
        return "你是这个世界中的一位角色，请保持角色身份与世界观一致地进行对话。";
    }
}
