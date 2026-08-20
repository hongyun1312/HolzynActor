package com.holzyn.actor.domain.knowledge.vo;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;

import java.time.LocalDateTime;

/**
 * 知识文档视图对象（KnowledgeDocVO）。
 * <p>职责：向前端返回知识文档数据，补充分块数/向量化状态等聚合信息。</p>
 * <p>所属模块：model/vo（视图对象层-知识库子域）</p>
 *
 * @param id               文档主键
 * @param projectId        项目 ID
 * @param characterId      角色级归属（空=项目级）
 * @param characterName    角色级归属角色名（空=项目级，供前端展示）
 * @param title            文档标题
 * @param content          文档全文
 * @param chunkCount       分块数（未向量化=0）
 * @param vectorized       是否已向量化（embedding 可用且非空）
 * @param embeddingModel   向量化所用 embedding 模型（展示用，可空）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 */
public record KnowledgeDocVO(
        Long id,
        Long projectId,
        Long characterId,
        String characterName,
        String title,
        String content,
        int chunkCount,
        boolean vectorized,
        String embeddingModel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param d             文档实体
     * @param characterName 角色名（可空）
     * @param chunkCount    分块数
     * @param vectorized    是否已向量化
     * @param embeddingModel embedding 模型名（可空）
     * @return VO 对象
     */
    public static KnowledgeDocVO of(ActorKnowledgeDoc d, String characterName, int chunkCount,
                                    boolean vectorized, String embeddingModel) {
        return new KnowledgeDocVO(d.getId(), d.getProjectId(), d.getCharacterId(), characterName,
                d.getTitle(), d.getContent(), chunkCount, vectorized, embeddingModel,
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
