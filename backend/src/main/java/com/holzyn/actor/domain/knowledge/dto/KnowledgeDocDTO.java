package com.holzyn.actor.domain.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识文档新增/编辑入参（P3 知识库 RAG）。
 * <p>职责：承载知识文档的标题、全文与角色级归属（character_id 空=项目级）。</p>
 * <p>所属模块：model/dto（请求模型层-知识库子域）</p>
 *
 * @param title       文档标题（必填）
 * @param content     文档全文（新建必填；编辑可空=不修改正文）
 * @param characterId 角色级归属（可空=项目级知识；非空=仅该角色检索）
 */
public record KnowledgeDocDTO(
        @NotBlank(message = "文档标题不能为空") @Size(max = 100, message = "标题长度不能超过 100") String title,
        String content,
        Long characterId
) {
}
