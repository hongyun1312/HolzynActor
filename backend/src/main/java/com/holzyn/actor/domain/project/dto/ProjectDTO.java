package com.holzyn.actor.domain.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 项目创建/更新请求 DTO。
 * <p>职责：承载项目新增与编辑的入参（name 必填，其余可选）。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param name      项目名称（必填）
 * @param code      项目编码（可选）
 * @param summary   项目概要（可选）
 * @param coverUrl  封面图 URL（可选）
 */
public record ProjectDTO(
        @NotBlank(message = "项目名称不能为空") @Size(max = 100, message = "项目名称最长 100 字符") String name,
        @Size(max = 50, message = "项目编码最长 50 字符") String code,
        String summary,
        String coverUrl
) {
}