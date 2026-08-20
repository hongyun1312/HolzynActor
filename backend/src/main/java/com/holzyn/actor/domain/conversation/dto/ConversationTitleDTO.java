package com.holzyn.actor.domain.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * 对话标题生成请求 DTO（AI 重写专属标题）。
 * <p>职责：承载前端「AI 重写标题」按钮的入参——角色 / 所在地 / 世界时间，服务端据此
 * 调用 AI 生成简短有画面感的对话标题；所有字段可空（缺省补默认文案）。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param characterId  参与角色 ID（可空）
 * @param location     对话所在地（可空，空=远程通讯）
 * @param gameTimeText 世界时间快照（可空）
 */
public record ConversationTitleDTO(
        Long characterId,
        @Size(max = 200, message = "对话所在地最长 200 字符") String location,
        @Size(max = 120, message = "世界时间快照最长 120 字符") String gameTimeText
) {
}
