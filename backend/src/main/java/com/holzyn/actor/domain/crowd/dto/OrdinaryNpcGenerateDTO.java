package com.holzyn.actor.domain.crowd.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 普通型 NPC AI 批量生成入参。
 * <p>职责：承载「AI 依据世界观批量生成普通型 NPC」的数量参数（1~500，每批 30 自动分批）。</p>
 * <p>所属模块：model/dto（请求模型层）</p>
 *
 * @param count 生成数量（1~500）
 */
public record OrdinaryNpcGenerateDTO(
        @NotNull(message = "生成数量不能为空")
        @Min(value = 1, message = "生成数量最少 1 个")
        @Max(value = 500, message = "生成数量最多 500 个")
        Integer count
) {
}
