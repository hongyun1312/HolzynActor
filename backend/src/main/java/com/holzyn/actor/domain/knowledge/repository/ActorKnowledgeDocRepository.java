package com.holzyn.actor.domain.knowledge.repository;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文档仓库（actor_knowledge_doc）。
 * <p>职责：提供按项目/角色查询知识文档的访问能力，支撑 RAG 检索与文档管理。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorKnowledgeDocRepository extends JpaRepository<ActorKnowledgeDoc, Long> {

    /**
     * 查询某项目的全部知识文档（按创建顺序）。
     *
     * @param projectId 项目 ID
     * @return 文档列表
     */
    List<ActorKnowledgeDoc> findByProjectIdOrderByIdAsc(Long projectId);

    /**
     * 按 id + 项目查询文档（归属校验）。
     *
     * @param id        文档主键
     * @param projectId 项目 ID
     * @return 匹配的文档（可能为空）
     */
    Optional<ActorKnowledgeDoc> findByIdAndProjectId(Long id, Long projectId);
}
