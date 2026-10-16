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

package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import com.holzyn.actor.domain.account.repository.LocalAccountRepository;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerProfile;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerProfileRepository;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerRelationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 玩家-角色关系服务（M1 只读；M3 增补玩家 persona 段）。
 * <p>职责：① 玩家名解析（玩家档案 → 账户昵称 → 「你」兜底）；② 渲染「与{玩家}的关系」段
 * （`actor_talk_player_relation` 好感/信任/阶段/备注/状态摘要 + 玩家档案 relation_settings
 * 的关系设定）；③ 渲染「关于{玩家}」段（玩家档案 persona 六维，M3）。均注入对谈上下文。</p>
 * <p><b>边界</b>：本服务只读渲染；玩家档案/关系设定/关系行的<b>写入口</b>=M3
 * {@link PlayerProfileService}（唯一），沉淀写回=M6 `DialogueConsolidationService`。</p>
 */
@Slf4j
@Service
public class DialogueRelationService {

    /** Jackson 3 序列化器（关系设定 JSON 解析） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 玩家档案仓库 */
    @Autowired(required = false)
    private ActorPlayerProfileRepository profileRepository;

    /** 玩家-角色关系仓库 */
    @Autowired(required = false)
    private ActorPlayerRelationRepository relationRepository;

    /** 本地账户仓库（玩家名兜底） */
    @Autowired(required = false)
    private LocalAccountRepository accountRepository;

    /**
     * 玩家名解析：玩家档案 name → 账户昵称 → 「你」兜底。
     *
     * @param userId 用户 ID
     *
     * @return 玩家名（恒非空）
     */
    public String playerNameOf(Long userId) {
        if (profileRepository != null && userId != null) {
            try {
                ActorPlayerProfile p = profileRepository.findByUserId(userId).orElse(null);
                if (p != null && p.getName() != null && !p.getName().isBlank()) {
                    return p.getName().trim();
                }
            } catch (Exception e) {
                log.debug("[对谈关系] 玩家档案读取失败（兜底账户昵称）: {}", e.getMessage());
            }
        }
        if (accountRepository != null && userId != null) {
            try {
                LocalAccount a = accountRepository.findByUserId(userId).orElse(null);
                if (a != null && a.getNickname() != null && !a.getNickname().isBlank()) {
                    return a.getNickname().trim();
                }
            } catch (Exception e) {
                log.debug("[对谈关系] 账户昵称读取失败（兜底「你」）: {}", e.getMessage());
            }
        }
        return "你";
    }

    /**
     * 渲染「与{玩家}的关系」段（对谈上下文 L0 关系位）。
     * <p>内容：关系设定（relation_settings 中该角色的过往/身份关系/态度）+ 当前
     * 好感/信任/阶段 + 备注 + AI 维护的状态摘要。缺数据整段跳过（fail-open）。</p>
     *
     * @param projectId    项目 ID
     * @param characterRef 对谈角色（npc:{id}）
     * @param playerName   玩家名
     *
     * @return 关系段文本（无数据返回空串）
     */
    public String relationSegmentOf(Long projectId, String characterRef, String playerName) {
        if (projectId == null || characterRef == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String relationSetting = relationSettingOf(projectId, characterRef);
        if (relationSetting != null && !relationSetting.isBlank()) {
            sb.append("关系设定：").append(relationSetting.trim());
        }
        ActorPlayerRelation r = relationOf(projectId, characterRef);
        if (r != null) {
            List<String> bits = new java.util.ArrayList<>();
            if (r.getStage() != null && !r.getStage().isBlank()) {
                bits.add("关系阶段：" + r.getStage().trim());
            }
            if (r.getAffinity() != null) {
                bits.add("好感" + r.getAffinity());
            }
            if (r.getTrust() != null) {
                bits.add("信任" + r.getTrust());
            }
            if (r.getStateSummary() != null && !r.getStateSummary().isBlank()) {
                bits.add("近况：" + r.getStateSummary().trim());
            }
            if (!bits.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(String.join("，", bits));
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        String who = playerName == null || playerName.isBlank() ? "你" : playerName;
        return "【与" + who + "的关系】" + sb;
    }

    /**
     * 渲染「关于{玩家}」段（对谈上下文 L0 玩家 persona 位，M3）。
     * <p>内容：玩家档案 persona 六维（身份/背景/性格/说话风格/目标/能力——与特殊 NPC 档案
     * 同构裁剪、无记忆/无状态/无自主行动），让角色知道「自己在跟谁说话」。缺数据整段跳过
     * （fail-open），键结构对齐 {@code PlayerProfileService} 六维约定。</p>
     *
     * @param userId     用户 ID
     * @param playerName 玩家名
     *
     * @return 玩家 persona 段文本（无数据返回空串）
     */
    public String playerPersonaSegmentOf(Long userId, String playerName) {
        if (profileRepository == null || userId == null) {
            return "";
        }
        try {
            ActorPlayerProfile p = profileRepository.findByUserId(userId).orElse(null);
            if (p == null || p.getPersonaJson() == null || p.getPersonaJson().isBlank()) {
                return "";
            }
            JsonNode root = MAPPER.readTree(p.getPersonaJson());
            if (!root.isObject()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            appendField(sb, "身份", root.path("identity"));
            appendField(sb, "背景", root.path("background"));
            appendField(sb, "性格", root.path("personality"));
            appendField(sb, "说话风格", root.path("voice"));
            appendField(sb, "目标", root.path("goals"));
            appendField(sb, "能力", root.path("ability"));
            if (sb.length() == 0) {
                return "";
            }
            String who = playerName == null || playerName.isBlank() ? "你" : playerName;
            return "【关于" + who + "】" + sb;
        } catch (Exception e) {
            log.debug("[对谈关系] 玩家 persona 解析失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 内部 ====================

    /** 玩家档案 relation_settings 中该角色的关系设定（无则空串）。 */
    private String relationSettingOf(Long projectId, String characterRef) {
        if (profileRepository == null) {
            return "";
        }
        try {
            ActorPlayerProfile p = profileRepository.findByUserId(1L).orElse(null);
            if (p == null || p.getRelationSettingsJson() == null || p.getRelationSettingsJson().isBlank()) {
                return "";
            }
            JsonNode root = MAPPER.readTree(p.getRelationSettingsJson());
            if (!root.isObject()) {
                return "";
            }
            JsonNode entry = root.path(characterRef);
            if (entry.isMissingNode() || entry.isNull()) {
                return "";
            }
            if (entry.isTextual()) {
                return entry.asText().trim();
            }
            if (entry.isObject()) {
                StringBuilder sb = new StringBuilder();
                appendField(sb, "过往", entry.path("past"));
                appendField(sb, "身份关系", entry.path("identity"));
                appendField(sb, "态度", entry.path("attitude"));
                return sb.toString();
            }
            return "";
        } catch (Exception e) {
            log.debug("[对谈关系] 关系设定解析失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    /** 关系行（无则 null）。 */
    private ActorPlayerRelation relationOf(Long projectId, String characterRef) {
        if (relationRepository == null) {
            return null;
        }
        try {
            return relationRepository.findByProjectIdAndCharacterRef(projectId, characterRef).orElse(null);
        } catch (Exception e) {
            log.debug("[对谈关系] 关系行读取失败（跳过）: {}", e.getMessage());
            return null;
        }
    }

    /** 追加字段（非空才加）。 */
    private static void appendField(StringBuilder sb, String label, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String v = node.asText();
        if (v == null || v.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("，");
        }
        sb.append(label).append("：").append(v.trim());
    }
}
