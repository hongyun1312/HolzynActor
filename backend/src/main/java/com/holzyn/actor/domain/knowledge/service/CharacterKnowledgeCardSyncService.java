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

package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 人物知识卡同步器（知识检索质量深化， _04）。
 * <p>职责：把角色级知识从「知识领域/盲区短语清单」提升为<b>结构化人物知识卡</b>——
 * 为每个角色（特殊型 + 普通型）幂等同步一份角色级知识文档（title=「人物知识卡」，
 * characterId 非空），内容 = 身份/年龄/出身/背景/性格/常去地点/技能/目标/知识领域/盲区
 * 的结构化摘要（数据源 = {@code actor_profile} 列 + {@code persona_json} 各维 + 既有
 * 角色级知识文档），向量化后供对话/感知 RAG 检索——问及该角色背景/性格/人际话题时
 * AI 命中的是<b>结构化知识卡</b>而非 26~57 字的短语清单（检索质量与引用密度提升）。</p>
 * <p><b>幂等语义</b>：按 (projectId, characterId, title=人物知识卡) 匹配既有文档——
 * 不存在新建；内容一致跳过（不重复 embedding）；内容变化更新 + 重新向量化。</p>
 * <p><b>事务约束</b>：本类所有方法<b>不标注 @Transactional</b>——向量化含 embedding AI 调用，
 * 遵循「AI 在事务外、写阶段短事务」架构约束；事务内调用方必须经 afterCommit 注册
 * <p>挂载点：① WorldInitService 世界初始化第 6 步（全角色统一补卡，覆盖旧路径角色卡生成）；
 * ② PersonaService.savePersona 事务提交后（档案成长实时刷新）；③ KnowledgeController 手动
 * 建端点（既有项目/修复）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterKnowledgeCardSyncService {

    /** 人物知识卡文档标题（角色级；识别前缀，感知/对话注入带 [人物知识卡] 来源标注） */
    public static final String TITLE_CARD = "人物知识卡";

    /** 既有角色级知识文档标题（知识领域/盲区权威，卡片引用其内容） */
    static final String TITLE_DOMAINS = "知识领域";
    static final String TITLE_BLIND = "知识盲区";

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson，与 PersonaJson 同源） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorCharacterRepository characterRepository;
    private final ActorKnowledgeDocRepository docRepository;
    private final KnowledgeVectorizerService vectorizerService;

    /**
     * 全角色同步人物知识卡（单角色失败不阻断其余；返回本次新建/更新数）。
     *
     * @param userId 归属用户 ID（embedding 凭据归属）
     *
     * @param projectId 项目 ID
     *
     * @return 本次新建/更新的知识卡数（内容未变跳过不计数）
     */
    public int syncAllCards(Long userId, Long projectId) {
        if (projectId == null) {
            return 0;
        }
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        int synced = 0;
        for (ActorCharacter ch : chars) {
            if (ch == null || ch.getId() == null) {
                continue;
            }
            try {
                synced += syncCharacterKnowledgeCard(userId, projectId, ch.getId());
            } catch (Exception e) {
                log.warn("[人物知识卡] 同步失败（跳过）: 项目={} 角色={} : {}",
                        projectId, ch.getId(), e.getMessage());
            }
        }
        log.info("[人物知识卡] 全量同步结束：项目={} 角色={} 本次={}", projectId, chars.size(), synced);
        return synced;
    }

    /**
     * 同步单个角色的人物知识卡（幂等 upsert + 向量化）。
     *
     * @param userId 归属用户 ID（embedding 凭据归属）
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @return 1=新建/更新；0=跳过（无内容/内容未变/角色不存在）
     */
    public int syncCharacterKnowledgeCard(Long userId, Long projectId, Long characterId) {
        if (projectId == null || characterId == null) {
            return 0;
        }
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null || !projectId.equals(ch.getProjectId())
                || Integer.valueOf(1).equals(ch.getDeleted())) {
            return 0;
        }
        List<ActorKnowledgeDoc> projectDocs = docRepository.findByProjectIdOrderByIdAsc(projectId);
        String content = buildCard(ch, projectDocs);
        if (content == null || content.isBlank()) {
            // 无任何可卡内容（空角色）→ 不建空卡（零噪音）
            return 0;
        }
        ActorKnowledgeDoc doc = findCard(projectDocs, characterId);
        if (doc != null && content.equals(doc.getContent())) {
            // 幂等：内容未变 → 不重复向量化（embedding 用量与检索缓存零扰动）
            return 0;
        }
        if (doc == null) {
            doc = new ActorKnowledgeDoc();
            doc.setProjectId(projectId);
            doc.setCharacterId(characterId);
            doc.setTitle(TITLE_CARD);
            doc.setStatus(1);
        }
        doc.setContent(content);
        doc = docRepository.saveAndFlush(doc);
        // 向量化（本方法无事务；embedding 未配置/失败由 vectorizerService 内部降级，不阻断）
        try {
            vectorizerService.vectorize(userId, projectId, doc.getId(), TITLE_CARD, characterId, content);
        } catch (Exception ex) {
            log.warn("[人物知识卡] 向量化失败（文档已落库，走文本降级）: 项目={} 角色={} : {}",
                    projectId, characterId, ex.getMessage());
        }
        log.info("[人物知识卡] 已同步：项目={} 角色={}（{}）字数={}",
                projectId, characterId, ch.getName() == null ? characterId : ch.getName(), content.length());
        return 1;
    }

    /** 取该角色既有知识卡（无 → null）。 */
    private static ActorKnowledgeDoc findCard(List<ActorKnowledgeDoc> projectDocs, Long characterId) {
        if (projectDocs == null) {
            return null;
        }
        for (ActorKnowledgeDoc d : projectDocs) {
            if (d != null && characterId.equals(d.getCharacterId()) && TITLE_CARD.equals(d.getTitle())) {
                return d;
            }
        }
        return null;
    }

    // ==================== 卡片组装（纯逻辑，可单测） ====================

    /**
     * 组装人物知识卡文本（纯逻辑静态方法，可单测）。
     * <p>数据源：{@code actor_profile} 结构列 + {@code persona_json}（Jackson 3 路径提取，
     * 不依赖 growth 模块类型——避免 knowledge→growth 依赖环）+ 该角色既有知识文档
     * （知识领域/盲区权威，卡片引用其全文）。空字段逐段跳过；全空返回空串。</p>
     *
     * @param ch 角色实体（null/无归属 → 空串）
     *
     * @param projectDocs 项目全部知识文档（可空；取该角色级知识领域/盲区）
     *
     * @return 结构化知识卡文本（每段一行「【标签】内容」；全空返回空串）
     */
    static String buildCard(ActorCharacter ch, List<ActorKnowledgeDoc> projectDocs) {
        if (ch == null || ch.getProjectId() == null || ch.getId() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // ① 身份（actor_profile 结构列）
        String identity = identityLine(ch);
        if (!identity.isBlank()) {
            sb.append("【身份】").append(identity).append("\n");
        }
        JsonNode root = parsePersona(ch.getPersonaJson());
        // ② 别名（identity.aliases）
        appendList(sb, "别名", textList(root, "dims", "identity", "aliases"));
        // ③ 年龄（actor_profile.age）
        if (ch.getAge() != null) {
            sb.append("【年龄】").append(ch.getAge()).append("岁\n");
        }
        // ④ 出身（experience.origin）
        appendText(sb, "出身", textOf(root, "dims", "experience", "origin"));
        // ⑤ 背景（experience.keyEvents → 逐条；无 → actor_profile.detail 兜底）
        List<String> keyEvents = keyEventsText(root);
        if (!keyEvents.isEmpty()) {
            appendList(sb, "背景", keyEvents);
        } else if (ch.getDetail() != null && !ch.getDetail().isBlank()) {
            appendText(sb, "背景", ch.getDetail());
        }
        // ⑥ 性格（personality.temperamentType + traitsSummary）
        appendText(sb, "性格", joinNonBlank(
                textOf(root, "dims", "personality", "temperamentType"),
                textOf(root, "dims", "personality", "traitsSummary")));
        // ⑦ 常去地点（actor_profile.location）
        if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            sb.append("【常去地点】").append(ch.getLocation().trim()).append("\n");
        }
        // ⑧ 技能（ability.skills 键 + ability.talents）
        List<String> skills = skillKeys(root);
        if (!skills.isEmpty()) {
            appendList(sb, "技能", skills);
        }
        // ⑨ 目标（values.lifeGoals/currentGoals/coreDesires/coreFears）
        List<String> goals = textList(root, "dims", "values", "lifeGoals");
        goals.addAll(textList(root, "dims", "values", "currentGoals"));
        goals.addAll(textList(root, "dims", "values", "coreDesires"));
        goals.addAll(textList(root, "dims", "values", "coreFears"));
        if (!goals.isEmpty()) {
            appendList(sb, "目标", goals);
        }
        // ⑩ 知识领域/盲区（该角色级知识文档权威内容）
        String domains = charDocContent(projectDocs, ch.getId(), TITLE_DOMAINS);
        if (!domains.isBlank()) {
            appendText(sb, "知识领域", domains);
        }
        String blind = charDocContent(projectDocs, ch.getId(), TITLE_BLIND);
        if (!blind.isBlank()) {
            appendText(sb, "知识盲区", blind);
        }
        return sb.toString().trim();
    }

    /** 身份行：「头衔姓名（性别·种族·归属·职业）」各段可选。 */
    private static String identityLine(ActorCharacter ch) {
        StringBuilder sb = new StringBuilder();
        if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
            sb.append(ch.getTitle().trim());
        }
        if (ch.getName() != null && !ch.getName().isBlank()) {
            sb.append(ch.getName().trim());
        }
        List<String> tags = new ArrayList<>();
        addTag(tags, ch.getGender());
        addTag(tags, ch.getRace());
        addTag(tags, ch.getSubRace());
        addTag(tags, ch.getAffiliation());
        addTag(tags, ch.getOccupation());
        if (!tags.isEmpty()) {
            sb.append("（").append(String.join("·", tags)).append("）");
        }
        return sb.toString().trim();
    }

    private static void addTag(List<String> tags, String v) {
        if (v != null && !v.isBlank()) {
            tags.add(v.trim());
        }
    }

    /** keyEvents 逐条渲染（「age 岁：what」或「what」）。 */
    private static List<String> keyEventsText(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode arr = path(root, "dims", "experience", "keyEvents");
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode e : arr) {
            if (e == null || !e.isObject()) {
                continue;
            }
            String what = e.path("what").asText("").trim();
            if (what.isBlank()) {
                continue;
            }
            if (e.path("age").isIntegralNumber()) {
                out.add(e.path("age").asInt() + "岁：" + what);
            } else {
                out.add(what);
            }
        }
        return out;
    }

    /** ability.skills 键列表 + ability.talents。 */
    private static List<String> skillKeys(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode skills = path(root, "dims", "ability", "skills");
        if (skills != null && skills.isObject()) {
            skills.properties().forEach(e -> out.add(e.getKey()));
        }
        out.addAll(textList(root, "dims", "ability", "talents"));
        return out;
    }

    /** 取该角色级知识文档内容（标题精确匹配；无 → 空串）。 */
    private static String charDocContent(List<ActorKnowledgeDoc> projectDocs, Long characterId, String title) {
        if (projectDocs == null) {
            return "";
        }
        for (ActorKnowledgeDoc d : projectDocs) {
            if (d != null && characterId.equals(d.getCharacterId()) && title.equals(d.getTitle())
                    && d.getContent() != null) {
                return d.getContent().trim();
            }
        }
        return "";
    }

    /** 解析 persona_json（空/非法 → null；零阻断）。 */
    private static JsonNode parsePersona(String personaJson) {
        if (personaJson == null || personaJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(personaJson);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按路径取字符串节点（缺省空串）。 */
    private static String textOf(JsonNode root, String... path) {
        JsonNode n = path(root, path);
        return n == null ? "" : n.asText("").trim();
    }

    /** 按路径取字符串数组（元素去空；非数组 → 空列表）。 */
    private static List<String> textList(JsonNode root, String... path) {
        List<String> out = new ArrayList<>();
        JsonNode n = path(root, path);
        if (n == null || !n.isArray()) {
            return out;
        }
        for (JsonNode e : n) {
            if (e != null && e.isTextual() && !e.asText().isBlank()) {
                out.add(e.asText().trim());
            }
        }
        return out;
    }

    /** 按路径取节点（任一层缺失 → null）。 */
    private static JsonNode path(JsonNode root, String... path) {
        JsonNode cur = root;
        for (String p : path) {
            if (cur == null || !cur.isObject()) {
                return null;
            }
            cur = cur.get(p);
        }
        return cur;
    }

    /** 非空字符串拼接（「、」分隔）。 */
    private static String joinNonBlank(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p.trim());
            }
        }
        return String.join("、", out);
    }

    /** 「【标签】内容」行（空内容跳过）。 */
    private static void appendText(StringBuilder sb, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sb.append("【").append(label).append("】").append(content.trim()).append("\n");
    }

    /** 「【标签】条目1、条目2」行（空列表跳过）。 */
    private static void appendList(StringBuilder sb, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        appendText(sb, label, String.join("、", items));
    }
}
