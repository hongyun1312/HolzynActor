package com.holzyn.actor.domain.conversation.service;

import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
import com.holzyn.actor.domain.memory.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话上下文注入服务（A-C4，P3 阶段二 + P4-1 长期记忆）。
 * <p>职责：在对话组装前生成「记忆 + 知识 + 环境」上下文片段——
 * ① 长期记忆（P4-1）：按重要度注入该角色事实记忆 + 项目级记忆（世界大事记，所有角色可见）；
 * ② RAG 知识片段：按最近一条用户消息检索 top-k（项目级 + 角色级合并，embedding 不可用降级文本）；
 * ③ 人群环境摘要：聚合项目下各人群组最近集体行动快照，让对话可感知人群环境。
 * 注入优先级遵循设计：角色卡 > 记忆 > RAG 片段 > 人群环境摘要 > 对话历史（角色卡由既有 system_prompt 承载，
 * 本服务产出记忆 + RAG + 环境段，以额外 system 消息前置注入，向后兼容不重生成角色卡）。</p>
 * <p>开关：{@code HOLOZYN_ACTOR_RAG_ENABLED}（RAG+环境）/ {@code HOLOZYN_ACTOR_MEMORY_ENABLED}（记忆），
 * 独立控制；记忆关闭时行为与 P3 完全一致。</p>
 * <p>所属模块：service/conversation（对话子域-上下文注入）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextService {

    /** RAG 检索 top-k（每轮注入的知识片段数） */
    private static final int TOP_K = 3;

    private final KnowledgeRetrievalService retrievalService;
    private final ActorCrowdRuntimeRepository crowdRuntimeRepository;
    private final MemoryService memoryService;

    /** RAG 总开关（false 时不检索知识也不注入环境，行为与 P2 完全一致） */
    @Value("${holzyn.actor.rag.enabled:true}")
    private boolean ragEnabled;

    /**
     * 生成对话上下文片段（长期记忆 + RAG 知识 + 人群环境摘要，优先级：记忆 > RAG > 人群环境）。
     *
     * @param userId      归属用户 ID（AI/embedding 凭据归属）
     * @param projectId   项目 ID
     * @param characterId 当前角色 ID（可空=仅项目级记忆/知识）
     * @param lastUserText 最近一条用户消息（作检索 query）
     * @return 上下文文本（无可用内容返回空串）
     */
    public String buildContext(Long userId, Long projectId, Long characterId, String lastUserText) {
        StringBuilder sb = new StringBuilder();
        // ① 长期记忆（P4-1）：角色卡之后、RAG 之前；记忆开关关闭时返回空串，行为与 P3 一致
        String memory = memoryService.memoryContext(userId, projectId, characterId);
        if (!memory.isBlank()) {
            sb.append(memory);
        }
        if (!ragEnabled) {
            return sb.toString().trim();
        }
        // ② RAG 知识片段（仅在存在可检索内容时注入）
        if (lastUserText != null && !lastUserText.isBlank()) {
            try {
                List<KnowledgeRetrievalService.KnowledgeHit> hits =
                        retrievalService.search(userId, projectId, characterId, lastUserText, TOP_K);
                if (!hits.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("【知识参考】以下是与当前话题相关的世界设定片段，回答时可参考，不得编造超出这些片段的知识：\n");
                    for (KnowledgeRetrievalService.KnowledgeHit h : hits) {
                        sb.append("- ").append(h.text()).append("\n");
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
