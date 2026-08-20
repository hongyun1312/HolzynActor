package com.holzyn.actor.domain.world.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 世界观设定保存请求 DTO。
 * <p>职责：承载世界观结构化字段与自由长文本的保存入参（全部可选，保存即覆盖更新）。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param name        世界观名称
 * @param genre       题材（奇幻/科幻/都市/历史等）
 * @param era         时代背景
 * @param geography   地理/地图设定
 * @param factions    势力/阵营
 * @param magicSystem 规则体系（魔法/科技/规则）
 * @param culture     文化/风俗
 * @param history     历史背景
 * @param freeText    完整世界观自由文本（知识库注入源）
 */
public record WorldSettingDTO(
        @NotBlank(message = "世界观名称不能为空") @Size(max = 100, message = "世界观名称最长 100 字符") String name,
        @Size(max = 50, message = "题材最长 50 字符") String genre,
        @Size(max = 50, message = "时代背景最长 50 字符") String era,
        String geography,
        String factions,
        String magicSystem,
        String culture,
        String history,
        @Size(max = 5000, message = "自由文本最长 5000 字符") String freeText
) {
}