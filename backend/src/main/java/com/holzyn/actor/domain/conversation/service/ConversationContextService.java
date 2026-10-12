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

package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话上下文注入服务。
 * <p>职责：在对话组装前生成「记忆 + 知识 + 环境」上下文片段——
 * ① 长期记忆：按重要度注入该角色事实记忆 + 项目级记忆（世界大事记，所有角色可见）；
 * ② RAG 知识片段：按最近一条用户消息检索 top-k（项目级 + 角色级合并，embedding 不可用降级文本）；
 * ③ 人群环境摘要：聚合项目下各人群组最近集体行动快照，让对话可感知人群环境。
 * 注入优先级遵循设计：角色卡 > 记忆 > RAG 片段 > 人群环境摘要 > 对话历史（角色卡由既有 system_prompt 承载，
 * 本服务产出记忆 + RAG + 环境段，以额外 system 消息前置注入，向后兼容不重生成角色卡）。</p>
 * <p>开关：{@code HOLOZYN_ACTOR_RAG_ENABLED}（RAG+环境）/ {@code HOLOZYN_ACTOR_MEMORY_ENABLED}（记忆），
 * 独立控制；记忆关闭时行为与 完全一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextService {

    /** RAG 检索 top-k（每轮注入的知识片段数） */
    private static final int TOP_K = 3;

    private final KnowledgeRetrievalService retrievalService;
    private final ActorCrowdRuntimeRepository crowdRuntimeRepository;
    /** 记忆组件（接线：旧 MemoryService 已删，直连 AgentMemoryService） */
    private final AgentMemoryService memoryService;

    /** NPC 类型解析器 */
    private final NpcTypeResolver npcTypeResolver;

    /** RAG 总开关 */
    @Value("${holzyn.actor.rag.enabled:true}")
    private boolean ragEnabled;

    /**
     * 生成对话上下文片段（长期记忆 + RAG 知识 + 人群环境摘要，优先级：记忆 > RAG > 人群环境）。
     *
     * @param userId 归属用户 ID（AI/embedding 凭据归属）
     *
     * @param projectId 项目 ID
     *
     * @param characterId 当前角色 ID（可空=仅项目级记忆/知识）
     *
     * @param lastUserText 最近一条用户消息（作检索 query）
     *
     * @return 上下文文本（无可用内容返回空串）
     */
    public String buildContext(Long userId, Long projectId, Long characterId, String lastUserText) {
        return buildContextInternal(userId, projectId, characterId, null, lastUserText, null);
    }

    /**
     * 生成对话上下文片段（去重版：excludeTexts=最近会话历史窗口文本，供记忆注入侧剔除重复）。
     */
    public String buildContext(Long userId, Long projectId, Long characterId, String lastUserText,
                               List<String> excludeTexts) {
        return buildContextInternal(userId, projectId, characterId, null, lastUserText, excludeTexts);
    }

    /**
     * 生成对话上下文片段（character_ref 版，B5：普通型 NPC 进会话用）。
     * <p>普通型 ref（actor_profile.type=common）→ 记忆经 {@link AgentMemoryService#memoryContextByRef}
     * 注入（B4 character_ref 双轨），RAG 知识按项目级检索（普通型无角色级知识）；
     * 特殊型 ref → 等价于 {@link #buildContext}。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @param lastUserText 最近一条用户消息
     *
     * @return 上下文文本（无可用内容返回空串）
     */
    public String buildContextByRef(Long userId, Long projectId, String characterRef, String lastUserText) {
        Long specialId = null;
        String crowdRef = null;
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type）
        if (characterRef != null && npcTypeResolver.isCommon(projectId, characterRef)) {
            crowdRef = characterRef.trim();
        } else if (characterRef != null && NpcRefs.isNpcRef(characterRef)) {
            specialId = NpcRefs.idOf(characterRef);
        }
        return buildContextInternal(userId, projectId, specialId, crowdRef, lastUserText, null);
    }

    /**
     * 生成对话上下文片段（character_ref 版， 去重版）。
     */
    public String buildContextByRef(Long userId, Long projectId, String characterRef, String lastUserText,
                                    List<String> excludeTexts) {
        Long specialId = null;
        String crowdRef = null;
        // ：npc:{id} 不再编码类型，此处按意图 B 处理（是否普通型查 actor_profile.type）
        if (characterRef != null && npcTypeResolver.isCommon(projectId, characterRef)) {
            crowdRef = characterRef.trim();
        } else if (characterRef != null && NpcRefs.isNpcRef(characterRef)) {
            specialId = NpcRefs.idOf(characterRef);
        }
        return buildContextInternal(userId, projectId, specialId, crowdRef, lastUserText, excludeTexts);
    }

    /**
     * 上下文组装核心：crowdRef 非空走 ref 记忆注入，否则按 characterId。
     */
    private String buildContextInternal(Long userId, Long projectId, Long characterId,
                                        String crowdRef, String lastUserText, List<String> excludeTexts) {
        StringBuilder sb = new StringBuilder();
        // ① 长期记忆：角色卡之后、RAG 之前；记忆开关关闭时返回空串，行为与 一致
        // ：注入侧剔除与历史窗口文本重叠的记忆（excludeTexts=最近 N 轮对话文本）；
        //   query=最近用户消息（情境文本）——tier 判定（scene=memory_tier）与 L2 语义检索共用，
        //   修复「对话注入空查询→tier 恒 LOW + L2 相关性=0」缺陷（2026-09-30 长跑实测 72/72 空跑）
        String memory = crowdRef != null
                ? memoryService.memoryContextByRef(userId, projectId, crowdRef, lastUserText, excludeTexts)
                : memoryService.memoryContext(userId, projectId, characterId, lastUserText, excludeTexts);
        if (!memory.isBlank()) {
            sb.append(memory);
        }
        if (!ragEnabled) {
            return sb.toString().trim();
        }
        // ② RAG 知识片段（仅在存在可检索内容时注入；crowd 按项目级检索=characterId null）
        if (lastUserText != null && !lastUserText.isBlank()) {
            try {
                List<KnowledgeHit> hits =
                        retrievalService.search(userId, projectId, characterId, lastUserText, TOP_K);
                if (!hits.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("【知识参考】以下是与当前话题相关的世界设定片段，回答时可参考，不得编造超出这些片段的知识：\n");
                    for (KnowledgeHit h : hits) {
                        // RAG 适配：片段带来源标题（对齐感知路径「标题：文本」）——AI 可识别知识类别
                        // （世界设定·地理/世界地点志/知识领域等），也便于事后核对 AI 引用来源（消费链证据）
                        String title = h.title() == null || h.title().isBlank() ? "知识" : h.title().trim();
                        // P1 ②-2：stale 文档命中标注「可能过时」（AI 引用时自行斟酌）
                        sb.append("- [").append(title).append("] ")
                                .append(h.stale() ? "〔可能过时〕" : "").append(h.text()).append("\n");
                    }
                }
            } catch (Exception e) {
                // 检索失败不阻断对话（降级：无知识注入）
                log.warn("[对话注入] 知识检索失败，跳过: {}", e.getMessage());
            }
        }
        // ③ 人群环境摘要（背景板独立，供对话感知人群环境）
        String env = buildCrowdEnv(projectId);
        if (!env.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("【当前环境】").append(env);
        }
        return sb.toString().trim();
    }

    /**
     * 聚合项目普通型 NPC 的环境摘要（项目级调度快照，无快照返回空串）。
     *
     * @param projectId 项目 ID
     *
     * @return 环境摘要文本（无快照返回空串）
     */
    private String buildCrowdEnv(Long projectId) {
        ActorCrowdRuntime rt = crowdRuntimeRepository.findByProjectId(projectId).orElse(null);
        if (rt == null || rt.getLatestSummary() == null || rt.getLatestSummary().isBlank()) {
            return "";
        }
        return rt.getLatestSummary();
    }
}
