package com.holzyn.actor.domain.knowledge.entity;

import com.holzyn.actor.common.JsonColumnConverter;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库文档实体，对应表 actor_knowledge_doc。
 * <p>职责：存储项目级/角色级知识文档（标题 + 全文），embedding 列为 P3 RAG 的分块向量
 * （JSON 数组 [{text, embedding}]，应用层余弦检索，不引入外部向量库）。</p>
 * <p>双粒度：character_id 为空 = 项目级知识（所有角色可检索）；非空 = 角色级知识（仅该角色）。</p>
 * <p>所属模块：model/entity（实体层-知识库子域）</p>
 */
@Data
@Entity
@Table(name = "actor_knowledge_doc", indexes = {
        @Index(name = "idx_knowledge_project", columnList = "project_id"),
        @Index(name = "idx_knowledge_character", columnList = "character_id")
})
public class ActorKnowledgeDoc {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id，数据隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 关联角色 ID（可空 = 项目级知识；非空 = 角色级知识） */
    @Column(name = "character_id")
    private Long characterId;

    /** 文档标题 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 文档全文 */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 分块向量 JSON（P3 RAG：[{text, embedding:[...]}, ...]；空数组 = 未向量化/降级文本检索）。
     *  2026-08-18 追加 JsonColumnConverter：H2 JSON 列读回双重编码解包（同 persona_json）。 */
    @Convert(converter = JsonColumnConverter.class)
    @Column(columnDefinition = "json")
    private String embedding;

    /** 状态：0草稿/1生效 */
    private Integer status = 1;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = 1;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
