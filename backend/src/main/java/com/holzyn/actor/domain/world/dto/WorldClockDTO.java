package com.holzyn.actor.domain.world.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;

/**
 * 世界时钟更新请求 DTO（P4-2）。
 * <p>职责：承载世界时钟的更新入参——速率（1~8760）、暂停开关、真实锚点与起始游戏时刻；
 * 全部字段可选，空字段表示保持原值。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param rate              速率（每真实小时推进的游戏小时数，1~8760）
 * @param paused            暂停开关（true=暂停推进）
 * @param worldStartAt      真实时刻锚点（默认=项目创建时刻）
 * @param worldStartGameHour 锚点对应的游戏起始小时数
 */
public record WorldClockDTO(
        @Min(value = 1, message = "速率最小为 1") @Max(value = 8760, message = "速率最大为 8760") Integer rate,
        Boolean paused,
        LocalDateTime worldStartAt,
        Long worldStartGameHour
) {
}
