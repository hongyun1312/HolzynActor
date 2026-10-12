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

package com.holzyn.actor.domain.growth.persona;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.knowledge.service.CharacterKnowledgeCardSyncService;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色档案服务。
 * <p><b>职责</b>：11 维档案的统一读写与渲染门面——取代历史 {@code CharacterCardService}
 * 对 {@code actor_profile_card} 表的读写（该表在 物理废弃，其 {@code persona_json} 并入
 * <p><b>三段链路</b>：</p>
 * <ol>
 * <li><b>投影</b>：{@link PersonaProjectionService} 从既有模块装配 {@link PersonaRenderContext}；</li>
 * <li><b>渲染</b>：{@link PersonaRenderer} 按场景分片输出（决策/对话/计划各取所需，信息密度取最优）。</li>
 * </ol>
 * <p><b>写</b>：{@link #savePersona} 按 {@code _sources} 分派落库——<b>这是"档案不独立于模块"的关键</b>：
 * ③ 生理基线写入 {@code actor_state.physio_json}（并立即换算生效系数）、⑤ 目标写入 {@code actor_goal}、
 * ⑥ 知识领域/盲区写入 {@code actor_knowledge_doc}，只有 PERSONA 字段留在 {@code persona_json}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaService {

    /** 角色主表仓库 */
    private final ActorCharacterRepository characterRepository;
    /** 档案投影服务 */
    private final PersonaProjectionService projectionService;
    /** 档案渲染器 */
    private final PersonaRenderer renderer;
    /** 档案校验器 */
    private final PersonaSchemaValidator validator;
    /** NPC 状态服务（③ 生理基线的落库目标） */
    private final NpcStateService npcStateService;
    /** 目标仓库（⑤ 目标落库目标） */
    private final ActorCharacterGoalRepository goalRepository;
    /** 知识文档仓库（⑥ 知识边界落库目标） */
    private final ActorKnowledgeDocRepository knowledgeDocRepository;
    /** 人物知识卡同步器（知识检索质量深化：档案保存后提交事务再同步角色知识卡 + 向量化） */
    private final CharacterKnowledgeCardSyncService knowledgeCardSyncService;
    /** 当前用户解析器（afterCommit 时请求上下文可能已清理，userId 须事务内取出） */
    private final CurrentUserProvider currentUserProvider;
    /** 档案版本历史服务 */
    private final PersonaRevisionService revisionService;

    //
    // 读
    //

    /**
     * 读取原始档案 JSON（{@code actor_profile.persona_json}）。
     *
     * @return JSON 文本；无档案返回 null
     */
    @Transactional(readOnly = true)
    public String personaJsonOf(Long profileId) {
        if (profileId == null) {
            return null;
        }
        return characterRepository.findById(profileId).map(ActorCharacter::getPersonaJson).orElse(null);
    }

    /**
     * 读取"最新档案 JSON"，语义等价于历史 {@code cardRepository.findTopByCharacterIdOrderByVersionDesc(id)}
     * 的 {@code .map(ActorCharacterCard::getPersonaJson)}——让既有链式调用
     * （{@code .isPresent()} / {@code .orElse(null)} / {@code .ifPresent(...)}）可原样迁移。
     *
     * @return 档案 JSON；无档案或空返回 {@code Optional.empty()}
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> latestPersonaJson(Long profileId) {
        String json = personaJsonOf(profileId);
        if (json == null || json.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(json);
    }

    /**
     * 读取档案对象（解析失败返回 null 并记告警——零阻断）。
     *
     * @return 档案对象；无档案或不可解析返回 null
     */
    @Transactional(readOnly = true)
    public PersonaProfile profileOf(Long profileId) {
        String json = personaJsonOf(profileId);
        if (json == null || json.isBlank()) {
            return null;
        }
        PersonaProfile p = PersonaJson.readQuietly(json);
        if (p == null) {
            log.warn("[档案] persona_json 解析失败（已忽略，不阻断）：profileId={}", profileId);
        }
        return p;
    }

    /**
     * 读取档案对象；不存在时返回一份空档案（保证调用方拿到可渲染对象而非 null）。
     *
     * @return 档案（至少含 schema_version 与默认分维来源声明）
     */
    @Transactional(readOnly = true)
    public PersonaProfile profileOrEmpty(Long profileId) {
        PersonaProfile p = profileOf(profileId);
        if (p != null) {
            return p;
        }
        ActorCharacter ch = profileId == null ? null : characterRepository.findById(profileId).orElse(null);
        return PersonaProfile.empty(ch == null ? null : ch.getProjectId(), profileId);
    }

    /**
     * 装配渲染上下文（模块投影）。
     *
     * @return 投影上下文
     */
    public PersonaRenderContext contextOf(Long profileId) {
        return projectionService.contextOf(profileId);
    }

    //
    // 渲染
    //

    /**
     * 按场景渲染分片档案文本（Prompt 注入主入口）。
     *
     * @param scene Prompt 场景码（如 action_gen / dialog_system；未登记回退默认组合）
     *
     * @return 分片文本；无档案时返回空串（调用方据此跳过该段）
     */
    public String renderShardsForScene(Long profileId, String scene) {
        PersonaProfile p = profileOf(profileId);
        if (p == null) {
            return "";
        }
        return renderer.renderShardsForScene(p, contextOf(profileId), scene);
    }

    /**
     * 渲染全量 11 维（前端「档案 11 维」只读区块 / 调试页 / 单人场景）。
     *
     * @return 11 维文本；无档案时返回空串
     */
    public String renderFull(Long profileId) {
        PersonaProfile p = profileOf(profileId);
        if (p == null) {
            return "";
        }
        return renderer.renderFull(p, contextOf(profileId));
    }

    /**
     * 用<b>指定档案 JSON</b>渲染全量 11 维。
     * <p>与 {@link #renderFull(Long)} 的区别：内容源不是当前 {@code actor_profile.persona_json}，
     * 而是调用方给定的快照 JSON（如 {@code actor_profile_revision} 的旧版）；模块投影（③ 生理基线 /
     * ⑤ 目标 / ⑥ 知识 / ⑧ 日程 / ⑩ 谱系）仍取<b>当前运行态</b>——回看旧版档案时运行态本来就是"现在"。</p>
     *
     * @param personaJson 档案 JSON 文本（可空/非法 → 空串）
     *
     * @param profileId actor_profile.id（投影上下文来源）
     *
     * @return 11 维文本；无法解析时返回空串
     */
    public String renderFullOf(String personaJson, Long profileId) {
        if (personaJson == null || personaJson.isBlank()) {
            return "";
        }
        PersonaProfile p = PersonaJson.readQuietly(personaJson);
        if (p == null) {
            return "";
        }
        return renderer.renderFull(p, contextOf(profileId));
    }

    /**
     * 校验档案并给出 11 维覆盖率（含模块投影判定）。
     *
     * @return 校验结果；无档案时全部维 missing
     */
    public PersonaValidationResult validate(Long profileId) {
        return validator.validate(profileOf(profileId), contextOf(profileId));
    }

    //
    // 写（按 _sources 分派）
    //

    /**
     * 保存 11 维档案并按分维来源声明分派落库。
     * <p><b>分派规则</b>：</p>
     * <ul>
     * <li>{@code dims.physio} → {@code actor_state.physio_json} + 立即换算生效系数（状态模块联动）；</li>
     * <li>其余 PERSONA 字段 → {@code actor_profile.persona_json}（本方法的主要产物）。</li>
     * </ul>
     * <p>③ 生理基线<b>不会</b>进入 persona_json（保持"权威在状态模块"的单一来源）。</p>
     *
     * @param profile 11 维档案（须含 schema_version=2）
     *
     * @param source 来源标记（generated / manual / edited；写入审计字段）
     *
     * @return 保存后的档案版本号；profileId 非法返回 0
     */
    @Transactional
    public int savePersona(Long profileId, PersonaProfile profile, String source) {
        if (profileId == null || profile == null) {
            return 0;
        }
        ActorCharacter ch = characterRepository.findById(profileId).orElse(null);
        if (ch == null) {
            return 0;
        }
        // ① ③ 生理基线 → 状态模块（per-NPC 参数 + 生效系数）
        dispatchPhysio(ch, profile);
        // ② ⑤ 目标 → actor_goal
        dispatchGoals(ch, profile);
        // ③ ⑥ 知识边界 → actor_knowledge_doc
        dispatchKnowledge(ch, profile);
        // ④ PERSONA 字段 → actor_profile.persona_json（写入前剥离 ③ 维，避免双份权威）
        PersonaProfile stored = detachModuleOwnedDims(profile);
        int version = (ch.getPersonaVersion() == null ? 0 : ch.getPersonaVersion()) + 1;
        stored.setProfileId(profileId);
        stored.setProjectId(ch.getProjectId());
        stored.setPersonaVersion(version);
        if (stored.getSources() == null || stored.getSources().isEmpty()) {
            stored.setSources(PersonaSchema.defaultSources());
        }
        ch.setPersonaJson(PersonaJson.write(stored));
        ch.setPersonaVersion(version);
        characterRepository.save(ch);
        // ：版本历史追加（与档案写入同事务提交——历史与主表永不分裂；失败只告警不阻断保存）
        revisionService.snapshot(ch.getProjectId(), profileId, version, ch.getPersonaJson(), source,
                stored.getGeneratedBy(), stored.getGeneratedScene(), null);
        // ⑤ 人物知识卡同步（知识检索质量深化）：写卡/向量化含 AI embedding，须事务提交后执行
        // （架构不变量：AI 在事务外）；userId 事务内取出（afterCommit 时请求上下文可能已清理）
        final Long userId = currentUserProvider.currentUserId();
        final Long pid = ch.getProjectId();
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        knowledgeCardSyncService.syncCharacterKnowledgeCard(userId, pid, profileId);
                    } catch (Exception e) {
                        log.warn("[人物知识卡] 提交后同步失败（不阻断档案保存）: profileId={} : {}",
                                profileId, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            // 无活动事务/注册失败 → 知识卡同步跳过（档案保存不阻断；世界初始化第 6 步可补卡）
            log.warn("[人物知识卡] 提交后同步注册失败（跳过，世界初始化第 6 步可补卡）: {}", e.getMessage());
        }
        log.info("[档案] 已保存：profileId={} version={} source={} 覆盖={}", profileId, version, source,
                validator.validate(stored, null).getCoveredDimCount() + "/11");
        return version;
    }

    /**
     * 剥离"权威在既有模块"的维度，保证 persona_json 内不出现第二份数据。
     * <p>当前剥离 ③ 生理基线（权威 {@code actor_state}）与 ⑩ 谱系（权威 {@code actor_relation}）；
     * ⑤ 目标与 ⑥ 知识领域保留在档案内作为<b>预设初值与渲染快照</b>（其权威在模块，模块缺失时档案兜底）。</p>
     *
     * @param profile 原始 11 维视图
     *
     * @return 可落档案的视图（原地修改后返回）
     */
    private PersonaProfile detachModuleOwnedDims(PersonaProfile profile) {
        if (profile.getDims() != null) {
            profile.getDims().setPhysio(null);
            if (profile.getDims().getDevelopment() != null) {
                // ⑩ 谱系
                profile.getDims().getDevelopment().setLineage(null);
                // 治理批次 0 · 卡9：⑩ genome/epigenetic_marks 权威 actor_growth_genome（出生路径
                // 唯一写方）——建档 AI 输出不得死拷贝进 persona_json（表侧写方不受 detach 影响）
                profile.getDims().getDevelopment().setGenome(null);
                profile.getDims().getDevelopment().setEpigeneticMarks(null);
            }
        }
        return profile;
    }

    /** ③ 生理基线 → 状态模块。 */
    private void dispatchPhysio(ActorCharacter ch, PersonaProfile profile) {
        PersonaDims.Physio ph = profile.getDims() == null ? null : profile.getDims().getPhysio();
        if (ph == null) {
            return;
        }
        String ref = NpcRefs.of(ch.getId());
        if (ref == null || ch.getProjectId() == null) {
            return;
        }
        ActorNpcState st = npcStateService.ensureState(ch.getProjectId(), NpcRefs.normalizeType(ch.getType()),
                ref, null);
        if (st == null) {
            return;
        }
        Integer endurance = profile.getDims().getAppearance() == null ? null
                : profile.getDims().getAppearance().getEndurance();
        npcStateService.applyPhysioBaseline(st, PersonaJson.mapper().writeValueAsString(ph),
                profile.getPersonaVersion(), endurance);
    }

    /** ⑤ 目标 → actor_goal（按标题幂等 upsert）。 */
    private void dispatchGoals(ActorCharacter ch, PersonaProfile profile) {
        PersonaDims.Values v = profile.getDims() == null ? null : profile.getDims().getValues();
        if (v == null || ch.getProjectId() == null || ch.getId() == null) {
            return;
        }
        String ref = NpcRefs.of(ch.getId());
        upsertGoals(ch.getProjectId(), ref, v.getLifeGoals(), 2);
        upsertGoals(ch.getProjectId(), ref, v.getCurrentGoals(), 4);
        // 治理批次 0 · 卡14（B-18 孤儿目标）：删除方向同步——档案已移除的种子目标软删（deleted=1，保留行）
        softDeleteRemovedSeedGoals(ch.getProjectId(), ref, v.getLifeGoals(), v.getCurrentGoals());
    }

    /**
     * 治理批次 0 · 卡14（B-18 孤儿目标）：软删档案已移除的种子目标。
     * <p>dispatchGoals 此前只 upsert 不删除 → 档案移除目标后 actor_goal 行残留 deleted=0（孤儿）。
     * 删除口径（防误伤，G-GOAL-02 守护）：只匹配<b>种子特征</b>行——priority∈{2,4}
     * （⑤ 档案目标落库优先级）且 description==title（种子行由 {@link #upsertGoals} 写入
     * description=title 快照；运行期目标[PlanningService 创建]为 AI/用户描述，天然不匹配）——
     * 且 title 不在档案 ⑤ 集合内才软删。软删语义对齐全库 {@code AndDeleted(...,0)} 查询，禁止物理删。</p>
     */
    private void softDeleteRemovedSeedGoals(Long projectId, String agentRef,
                                            List<String> lifeGoals, List<String> currentGoals) {
        if (projectId == null || agentRef == null) {
            return;
        }
        Set<String> kept = new HashSet<>();
        collect(kept, lifeGoals);
        collect(kept, currentGoals);
        for (ActorCharacterGoal g : goalRepository.findByProjectIdAndAgentRefAndDeleted(projectId, agentRef, 0)) {
            if (g == null || g.getTitle() == null) {
                continue;
            }
            String title = g.getTitle().trim();
            boolean seed = g.getPriority() != null && (g.getPriority() == 2 || g.getPriority() == 4)
                    && g.getDescription() != null && g.getDescription().trim().equals(title);
            if (!seed || kept.contains(title)) {
                continue;
            }
            g.setDeleted(1);
            goalRepository.save(g);
            log.info("[档案] 目标软删（档案已移除）：ref={} title={}", agentRef, title);
        }
    }

    /** 收集非空目标标题（去空白）。 */
    private static void collect(Set<String> into, List<String> titles) {
        if (titles == null) {
            return;
        }
        for (String t : titles) {
            if (t != null && !t.isBlank()) {
                into.add(t.trim());
            }
        }
    }

    /** 目标幂等写入（同标题已存在则跳过）。 */
    private void upsertGoals(Long projectId, String agentRef, List<String> titles, int priority) {
        if (titles == null || titles.isEmpty()) {
            return;
        }
        List<ActorCharacterGoal> existing =
                goalRepository.findByProjectIdAndAgentRefAndDeleted(projectId, agentRef, 0);
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            boolean dup = existing.stream().anyMatch(g -> title.trim().equals(g.getTitle()));
            if (dup) {
                continue;
            }
            ActorCharacterGoal g = new ActorCharacterGoal();
            g.setProjectId(projectId);
            g.setAgentRef(agentRef);
            g.setTitle(title.trim());
            g.setDescription(title.trim());
            g.setPriority(priority);
            g.setStatus("active");
            goalRepository.save(g);
        }
    }

    /** ⑥ 知识领域/盲区 → actor_knowledge_doc（一个 NPC 两份文档：知识领域、知识盲区）。 */
    private void dispatchKnowledge(ActorCharacter ch, PersonaProfile profile) {
        PersonaDims.Ability ab = profile.getDims() == null ? null : profile.getDims().getAbility();
        if (ab == null || ch.getProjectId() == null || ch.getId() == null) {
            return;
        }
        writeKnowledgeDoc(ch, "知识领域", ab.getKnowledgeDomains());
        writeKnowledgeDoc(ch, "知识盲区", ab.getKnowledgeBlindSpots());
    }

    /** 写入/更新一份知识文档（按标题幂等）。 */
    private void writeKnowledgeDoc(ActorCharacter ch, String title, List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        String content = String.join("、", contents);
        List<ActorKnowledgeDoc> docs = knowledgeDocRepository.findByProjectIdOrderByIdAsc(ch.getProjectId()).stream()
                .filter(d -> d != null && ch.getId().equals(d.getCharacterId()) && title.equals(d.getTitle()))
                .toList();
        ActorKnowledgeDoc doc = docs.isEmpty() ? new ActorKnowledgeDoc() : docs.get(0);
        doc.setProjectId(ch.getProjectId());
        doc.setCharacterId(ch.getId());
        doc.setTitle(title);
        doc.setContent(content);
        doc.setStatus(1);
        knowledgeDocRepository.save(doc);
    }
}