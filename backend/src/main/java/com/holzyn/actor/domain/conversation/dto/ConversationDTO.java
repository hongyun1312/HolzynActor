package com.holzyn.actor.domain.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 会话创建请求 DTO。
 * <p>职责：承载单聊/群聊会话的创建入参（mode 必填，角色 ID 列表可空）。</p>
 * <p>所属模块：model/dto（数据传输层）</p>
 *
 * @param mode          模式：single 单聊 / group 群聊
 * @param title         会话标题（可选，缺省自动生成）
 * @param characterIds  参与角色 ID 列表（单聊传 1 个，群聊可多个）
 * @param location      对话所在地（场景地点；可选，空=通过手机等远程通讯软件对话，直接影响 NPC 回答）
 * @param gameTimeText  对话发生时的世界时间快照（可选，来自世界时钟 gameTimeText）
 */
public record ConversationDTO(
        @NotBlank(message = "会话模式不能为空") @Size(max = 10, message = "会话模式最长 10 字符") String mode,
        @Size(max = 100, message = "会话标题最长 100 字符") String title,
        List<Long> characterIds,
        @Size(max = 200, message = "对话所在地最长 200 字符") String location,
        @Size(max = 120, message = "世界时间快照最长 120 字符") String gameTimeText
) {
}