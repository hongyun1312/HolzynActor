package com.holzyn.actor.domain.conversation.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.conversation.dto.ConversationDTO;
import com.holzyn.actor.domain.conversation.dto.ConversationSceneDTO;
import com.holzyn.actor.domain.conversation.dto.ConversationTitleDTO;
import com.holzyn.actor.domain.conversation.dto.SendMessageDTO;
import com.holzyn.actor.domain.conversation.vo.ConversationVO;
import com.holzyn.actor.domain.conversation.vo.MessageVO;
import com.holzyn.actor.domain.conversation.service.ChatService;
import com.holzyn.actor.domain.conversation.service.ConversationService;
import com.holzyn.actor.domain.conversation.service.WorldEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 对话引擎控制器（A-C4）。
 * <p>职责：提供会话 CRUD、消息发送与 SSE 流式通道（单聊/群聊），
 * 以及世界事件注入与「无玩家轮次」自主推进（P4 预留）。
 * 链路：POST /messages 落库 → GET /stream 以 SseEmitter 流式推送。</p>
 * <p>所属模块：controller/conversation（对话子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatService chatService;
    private final WorldEventService worldEventService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 创建会话（单聊/群聊）。
     *
     * @param projectId 项目 ID
     * @param dto       会话入参（mode + characterIds）
     * @return 创建后的会话
     */
    @PostMapping("/projects/{projectId}/conversations")
    public R<ConversationVO> create(@PathVariable("projectId") Long projectId, @Valid @RequestBody ConversationDTO dto) {
        return R.ok(conversationService.create(projectId, dto));
    }

    /**
     * AI 生成对话专属标题（创建弹窗「AI 重写」按钮）。
     * <p>依据角色/所在地/世界时间生成简短标题；未配置可用 AI API 时返回 400，前端回退规则标题。</p>
     *
     * @param projectId 项目 ID
     * @param dto       入参（characterId/location/gameTimeText 均可空）
     * @return { title }
     */
    @PostMapping("/projects/{projectId}/conversations/generate-title")
    public R<Map<String, Object>> generateTitle(@PathVariable("projectId") Long projectId,
                                                @RequestBody(required = false) ConversationTitleDTO dto) {
        ConversationTitleDTO req = dto == null ? new ConversationTitleDTO(null, null, null) : dto;
        return R.ok(Map.of("title", chatService.generateTitle(projectId, req.characterId(),
                req.location(), req.gameTimeText())));
    }

    /**
     * 更新会话「对话场景」（对话所在地 / 世界时间快照）。
     * <p>创建后可随时调整对话发生的地点与时间，保存后影响后续 NPC 回答（ChatService 按最新场景注入）。</p>
     *
     * @param id  会话主键
     * @param dto 场景入参（location/gameTimeText 可空；location 空串=清空为远程通讯）
     * @return 更新后的会话 VO
     */
    @PutMapping("/conversations/{id}/scene")
    public R<ConversationVO> updateScene(@PathVariable("id") Long id,
                                         @RequestBody(required = false) ConversationSceneDTO dto) {
        ConversationSceneDTO req = dto == null ? new ConversationSceneDTO(null, null) : dto;
        return R.ok(conversationService.updateScene(id, req.location(), req.gameTimeText()));
    }

    /**
     * 会话详情。
     *
     * @param id 会话主键
     * @return 会话详情（含成员角色 ID）
     */
    @GetMapping("/conversations/{id}")
    public R<ConversationVO> detail(@PathVariable("id") Long id) {
        return R.ok(conversationService.detail(id));
    }

    /**
     * 项目会话列表（按更新时间倒序）。
     *
     * @param projectId 项目 ID
     * @return 会话列表
     */
    @GetMapping("/projects/{projectId}/conversations")
    public R<List<ConversationVO>> list(@PathVariable("projectId") Long projectId) {
        return R.ok(conversationService.list(projectId));
    }

    /**
     * 历史消息（按创建顺序升序）。
     *
     * @param id 会话主键
     * @return 消息列表
     */
    @GetMapping("/conversations/{id}/messages")
    public R<List<MessageVO>> messages(@PathVariable("id") Long id) {
        return R.ok(conversationService.messages(id));
    }

    /**
     * 发送消息：单聊写 user + assistant 占位；群聊仅写 user 消息（发言人由编排动态确定）。
     *
     * @param id  会话主键
     * @param dto 消息入参（content 必填，forceCharacterId 可选指定发言人）
     * @return userMessageId / assistantMessageId（单聊）/ mode / group
     */
    @PostMapping("/conversations/{id}/messages")
    public R<Map<String, Object>> sendMessage(@PathVariable("id") Long id, @Valid @RequestBody SendMessageDTO dto) {
        return R.ok(chatService.sendMessage(id, dto.content(), dto.forceCharacterId()));
    }

    /**
     * 删除会话（归属校验）。
     *
     * @param id 会话主键
     * @return 删除确认
     */
    @DeleteMapping("/conversations/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        conversationService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * SSE 流式对话通道（EventSource 消费，不包 R）。
     * <p>事件：message-start（群聊新角色）/ token（增量，带 characterId）/ done / error；
     * 群聊模式下由编排服务逐角色生成；forceCharacterId 指定群聊首轮发言人。</p>
     *
     * @param id               会话主键
     * @param forceCharacterId 群聊指定发言人（可选）
     * @return SseEmitter 流
     */
    @GetMapping(value = "/conversations/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable("id") Long id,
                             @RequestParam(name = "forceCharacterId", required = false) Long forceCharacterId) {
        return chatService.stream(id, forceCharacterId);
    }

    /**
     * 注入世界事件：手填文本或 AI 按 world_event 模板生成，插入 type=event 消息并触发在场角色回应。
     *
     * @param id   会话主键
     * @param body 入参：{text? 手填事件文本, generate? 是否 AI 生成}
     * @return eventMessageId / mode / reaction（前端应打开 SSE 触发角色回应）
     */
    @PostMapping("/conversations/{id}/world-event")
    public R<Map<String, Object>> worldEvent(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(worldEventService.inject(id, currentUserProvider.currentUserId(), body));
    }

    /**
     * 「无玩家轮次」自主推进（P4 世界模拟预留，P2 验证接口）：
     * 无需玩家消息，打开 SSE 后调度器自主推进一轮群聊。
     *
     * @param id 会话主键
     * @return mode / autonomous
     */
    @PostMapping("/conversations/{id}/advance")
    public R<Map<String, Object>> advance(@PathVariable("id") Long id) {
        return R.ok(worldEventService.advance(id, currentUserProvider.currentUserId()));
    }
}