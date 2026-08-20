package com.holzyn.actor.domain.project.dto;

/**
 * 世界初始化请求 DTO（2026-08-19 世界初始化）。
 *
 * @param rebuild true=全量重建（清空既有数据后重新生成）/ false=跳过已生成（幂等）
 */
public record WorldInitRequestDTO(Boolean rebuild) {
}
