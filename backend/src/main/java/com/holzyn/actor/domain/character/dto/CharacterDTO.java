package com.holzyn.actor.domain.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 角色新增/更新请求 DTO。
 * <p>职责：承载角色基础档案入参（name 必填；type/importance 有取值约束）。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param type          类型：special 特殊型 / common 普通型
 * @param name          角色姓名（必填）
 * @param title         头衔
 * @param detail        角色详细信息（用户自行输入，角色卡生成的核心输入源）
 * @param avatarUrl     头像 URL
 * @param isProtagonist 是否主角（0/1）
 * @param importance    重要度（1-5）
 */
public record CharacterDTO(
        @Size(max = 10, message = "类型最长 10 字符") String type,
        @NotBlank(message = "角色姓名不能为空") @Size(max = 50, message = "角色姓名最长 50 字符") String name,
        @Size(max = 50, message = "头衔最长 50 字符") String title,
        @Size(max = 20000, message = "角色详细信息最长 20000 字符") String detail,
        String avatarUrl,
        @Min(value = 0, message = "isProtagonist 最小为 0") @Max(value = 1, message = "isProtagonist 最大为 1") Integer isProtagonist,
        @Min(value = 1, message = "重要度最小为 1") @Max(value = 5, message = "重要度最大为 5") Integer importance
) {
}