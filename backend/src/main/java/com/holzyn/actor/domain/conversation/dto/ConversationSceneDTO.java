package com.holzyn.actor.domain.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * 对话场景更新请求 DTO（编辑对话所在地 / 世界时间）。
 * <p>职责：承载会话创建后对「对话场景」的修改入参——所在地与发生时间均为可选项，
 * 传 null 表示不修改，传空字符串表示清空（所在地清空后按「远程通讯」处理）。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param location     对话所在地（可空；空串=清空为远程通讯）
 * @param gameTimeText 对话发生时的世界时间快照（可空）
 */
public record ConversationSceneDTO(
        @Size(max = 200, message = "对话所在地最长 200 字符") String location,
        @Size(max = 120, message = "世界时间快照最长 120 字符") String gameTimeText
) {
}
