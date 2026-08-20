package com.holzyn.actor.domain.character.entity;

import com.holzyn.actor.common.JsonColumnConverter;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色卡实体，对应表 actor_character_card。
 * <p>职责：存储角色卡的结构化 JSON（personaJson，设计文档 §九 Schema）与
 * 渲染后的对话系统 Prompt（systemPrompt），按角色版本化（重新生成会新增版本）。</p>
 * <p>所属模块：model/entity（实体层）</p>
 */
@Data
@Entity
@Table(name = "actor_character_card")
public class ActorCharacterCard {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属角色 ID（关联 actor_character.id） */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 版本号（自增） */
    private Integer version = 1;

    /** 结构化角色卡 JSON（MySQL JSON 列，String 承载）。
     *  2026-08-18 追加 JsonColumnConverter：H2 JSON 列读回为「双重编码」字符串，
     *  转换器在实体读取路径上解包一层，保证 personaJson 始终是单层 JSON 文本。 */
    @Convert(converter = JsonColumnConverter.class)
    @Column(name = "persona_json", columnDefinition = "json")
    private String personaJson;

    /** 渲染后的对话系统 Prompt */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /** 来源：generated 生成 / manual 手动 / edited 编辑 */
    @Column(length = 20, nullable = false)
    private String source = "generated";

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
        if (version == null) version = 1;
        if (source == null) source = "generated";
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}