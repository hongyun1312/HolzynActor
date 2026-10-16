/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.dialogue.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.dialogue.dto.DialogueChatRequestDTO;
import com.holzyn.actor.domain.dialogue.dto.DialogueConsolidationApplyDTO;
import com.holzyn.actor.domain.dialogue.service.DialogueChatService;
import com.holzyn.actor.domain.dialogue.service.DialogueConsolidationService;
import com.holzyn.actor.domain.dialogue.service.DialogueGreetingService;
import com.holzyn.actor.domain.dialogue.service.DialogueGroupChatService;
import com.holzyn.actor.domain.dialogue.service.DialogueGroupService;
import com.holzyn.actor.domain.dialogue.service.DialogueSessionService;
import com.holzyn.actor.domain.dialogue.service.DialogueUsageService;
import com.holzyn.actor.domain.dialogue.service.DialogueWorldbookService;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.vo.DialogueConsolidationResultVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueConsolidationVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueContextConfigVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueMessageVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueSessionVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueUsageVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueWorldbookVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 对谈接口（与「浮生」主模式同级的世界静止聊天模式）。
 * <p>职责：对谈会话 CRUD（创建即冻结）、消息读取、<b>AI 动态开场白</b>、<b>SSE 流式单聊回复</b>。</p>
 * <p><b>架构纪律</b>：chat/greeting 端点为 AI 调用（事务外，见 {@link DialogueChatService}/
 * {@link DialogueGreetingService}）；会话/消息 CRUD 为纯本地短事务。SSE 事件契约：
 * {@code meta(sessionId/characterRef)} → {@code token(delta)*} →
 * {@code done(messageId/content/characterRef/characterName/seq)} | {@code error(message)}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/dialogue")
public class DialogueController {

    /** 会话服务 */
    @Lazy
    @Autowired(required = false)
    private DialogueSessionService sessionService;

    /** 消息服务 */
    @Autowired(required = false)
    private com.holzyn.actor.domain.dialogue.service.DialogueMessageService messageService;

    /** 开场白服务 */
    @Lazy
    @Autowired(required = false)
    private DialogueGreetingService greetingService;

    /** 单聊编排服务 */
    @Lazy
    @Autowired(required = false)
    private DialogueChatService chatService;

    /** 群聊成员服务（M5：场景化成员/换场/拉人） */
    @Lazy
    @Autowired(required = false)
    private DialogueGroupService groupService;

    /** 群聊编排服务（M5：SSE 串行流式逐角色回应） */
    @Lazy
    @Autowired(required = false)
    private DialogueGroupChatService groupChatService;

    /** 沉淀服务（M6：确认卡片 preview + 应用 apply） */
    @Lazy
    @Autowired(required = false)
    private DialogueConsolidationService consolidationService;

    /** 世界书服务（M4：会话级关键词触发条目 CRUD） */
    @Autowired(required = false)
    private DialogueWorldbookService worldbookService;

    /** 上下文配置/用量服务（M4） */
    @Autowired(required = false)
    private DialogueUsageService usageService;

    /** 当前用户解析 */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** SSE 生命周期 */
    @Autowired(required = false)
    private SseLifecycle sseLifecycle;

    /** 项目仓库（项目校验） */
    @Autowired(required = false)
    private ActorProjectRepository projectRepository;

    // ==================== 会话 CRUD ====================

    /** 新建对谈会话（创建即冻结：运行中自动暂停 + 冻结时刻快照）。
     *  type=single → 指定角色单聊；type=group → 群聊（可选 locationId 场景地点，缺省自动选在场最多的地点）。 */
    @PostMapping("/sessions")
    public R<DialogueSessionVO> create(@PathVariable Long projectId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String type = body == null ? null : str(body.get("type"));
        String characterRef = body == null ? null : str(body.get("characterRef"));
        String title = body == null ? null : str(body.get("title"));
        Long locationId = body == null ? null : longValue(body.get("locationId"));
        if (type != null && "group".equals(type.trim())) {
            return R.ok(groupService.createGroup(projectId, currentUserProvider.currentUserId(), locationId, title));
        }
        return R.ok(sessionService.create(projectId, currentUserProvider.currentUserId(), type, characterRef, title));
    }

    /** 项目对谈会话列表（按更新时间倒序；keyword 可空=全量，M6 支持按标题/角色名/地点名搜索）。 */
    @GetMapping("/sessions")
    public R<List<DialogueSessionVO>> sessions(@PathVariable Long projectId,
                                               @RequestParam(required = false) String keyword) {
        requireProject(projectId);
        if (keyword != null && !keyword.isBlank()) {
            return R.ok(sessionService.search(projectId, keyword));
        }
        return R.ok(sessionService.list(projectId));
    }

    /** 单会话。 */
    @GetMapping("/sessions/{sessionId}")
    public R<DialogueSessionVO> one(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(sessionService.get(projectId, sessionId));
    }

    /** 重命名会话。 */
    @PostMapping("/sessions/{sessionId}/rename")
    public R<DialogueSessionVO> rename(@PathVariable Long projectId, @PathVariable Long sessionId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String title = body == null ? null : str(body.get("title"));
        sessionService.rename(projectId, sessionId, title);
        return R.ok(sessionService.get(projectId, sessionId));
    }

    /** 关闭会话（status=closed；历史保留可查阅）。 */
    @PostMapping("/sessions/{sessionId}/close")
    public R<Map<String, Object>> close(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        sessionService.close(projectId, sessionId);
        return R.ok(Map.of("closed", true));
    }

    /** 会话消息列表（seq 正序）。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public R<List<DialogueMessageVO>> messages(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(messageService.list(projectId, sessionId));
    }

    // ==================== 消息操作（编辑/删除/换一条，零 AI 短事务） ====================

    /** 编辑玩家消息（内容更新 + 作废其后所有消息——后续链失效）。 */
    @PostMapping("/sessions/{sessionId}/messages/{messageId}/edit")
    public R<DialogueMessageVO> edit(@PathVariable Long projectId, @PathVariable Long sessionId,
                                     @PathVariable Long messageId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        String content = body == null ? null : str(body.get("content"));
        return R.ok(messageService.edit(projectId, sessionId, messageId, content));
    }

    /** 删除消息（含其后所有消息——后续链作废）。 */
    @PostMapping("/sessions/{sessionId}/messages/{messageId}/delete")
    public R<Map<String, Object>> delete(@PathVariable Long projectId, @PathVariable Long sessionId,
                                         @PathVariable Long messageId) {
        requireProject(projectId);
        messageService.remove(projectId, sessionId, messageId);
        return R.ok(Map.of("deleted", true));
    }

    /** 换一条（swipe）：角色回复轮换到下一候选（metaJson.swipes；先用「重写」生成备选）。 */
    @PostMapping("/sessions/{sessionId}/messages/{messageId}/swipe")
    public R<DialogueMessageVO> swipe(@PathVariable Long projectId, @PathVariable Long sessionId,
                                      @PathVariable Long messageId) {
        requireProject(projectId);
        return R.ok(messageService.swipe(projectId, sessionId, messageId));
    }

    // ==================== 开场白 / 对话 / 重写（AI 调用，事务外） ====================

    /** AI 动态开场白（新会话首句；单聊落 assistant、群聊按在场角色逐条落 group，均带 kind=greeting）。 */
    @PostMapping("/sessions/{sessionId}/greeting")
    public R<Map<String, Object>> greeting(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        ActorDialogueSession session = sessionService.require(projectId, sessionId);
        if ("group".equals(session.getType())) {
            List<Map<String, Object>> members = greetingService.generateGroup(projectId, userId, sessionId);
            if (members == null) {
                throw new BizException(500, "开场白生成失败，请稍后重试或直接开始对话");
            }
            return R.ok(Map.of("members", members));
        }
        String content = greetingService.generate(projectId, userId, sessionId);
        if (content == null) {
            throw new BizException(500, "开场白生成失败，请稍后重试或直接开始对话");
        }
        return R.ok(Map.of("content", content));
    }

    /** 一轮对谈（SSE 流式回复）。 */
    @PostMapping(value = "/sessions/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long projectId, @PathVariable Long sessionId,
                           @RequestBody(required = false) DialogueChatRequestDTO dto) {
        requireProject(projectId);
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        String content = dto == null ? null : dto.content();
        if (content == null || content.isBlank()) {
            sseLifecycle.fail(emitter, "发言不能为空");
            return emitter;
        }
        try {
            // 会话/项目隔离校验（不存在抛 404）
            sessionService.get(projectId, sessionId);
            chatService.chat(projectId, currentUserProvider.currentUserId(), sessionId, content,
                    (name, data) -> sseLifecycle.send(emitter, name, data));
            emitter.complete();
        } catch (BizException e) {
            sseLifecycle.fail(emitter, e.getMessage());
        } catch (Exception e) {
            log.warn("[对谈] 对话异常: project={} session={} : {}", projectId, sessionId, e.getMessage());
            sseLifecycle.fail(emitter, "对话处理失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return emitter;
    }

    /** 重写一条角色回复（SSE 流式；body.messageId 可空=取会话最后一条角色回复）。 */
    @PostMapping(value = "/sessions/{sessionId}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@PathVariable Long projectId, @PathVariable Long sessionId,
                                 @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        Long messageId = body == null ? null : longValue(body.get("messageId"));
        try {
            // 会话/项目隔离校验（不存在抛 404）
            sessionService.get(projectId, sessionId);
            chatService.regenerate(projectId, currentUserProvider.currentUserId(), sessionId, messageId,
                    (name, data) -> sseLifecycle.send(emitter, name, data));
            emitter.complete();
        } catch (BizException e) {
            sseLifecycle.fail(emitter, e.getMessage());
        } catch (Exception e) {
            log.warn("[对谈] 重写异常: project={} session={} : {}", projectId, sessionId, e.getMessage());
            sseLifecycle.fail(emitter, "重写失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return emitter;
    }

    // ==================== 群聊（M5：场景化成员 / 换场 / 拉人 / 群聊对话） ====================

    /** 群聊成员列表（present 在场在前、manual 拉人在后）。 */
    @GetMapping("/sessions/{sessionId}/group/members")
    public R<List<DialogueGroupMemberVO>> groupMembers(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(groupService.members(projectId, sessionId));
    }

    /** 拉人（临时加入，manual 标记；换场保留）。 */
    @PostMapping("/sessions/{sessionId}/group/members")
    public R<List<DialogueGroupMemberVO>> groupInvite(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return R.ok(groupService.invite(projectId, sessionId, str(body == null ? null : body.get("characterRef"))));
    }

    /** 移除成员（在场或拉人均可；在场成员换场时按地点重算回来）。 */
    @PostMapping("/sessions/{sessionId}/group/members/remove")
    public R<Map<String, Object>> groupRemove(@PathVariable Long projectId, @PathVariable Long sessionId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        groupService.removeMember(projectId, sessionId, str(body == null ? null : body.get("characterRef")));
        return R.ok(Map.of("removed", true));
    }

    /** 换场（切地点重算在场成员；manual 拉人成员保留）。 */
    @PutMapping("/sessions/{sessionId}/group/location")
    public R<Map<String, Object>> groupChangeLocation(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        DialogueSessionVO session = groupService.changeLocation(projectId, sessionId,
                longValue(body == null ? null : body.get("locationId")));
        return R.ok(Map.of("session", session, "members", groupService.members(projectId, sessionId)));
    }

    /** 一轮群聊（SSE 串行流式逐角色回应：meta → group-member → token → group-member-done | error → done）。 */
    @PostMapping(value = "/sessions/{sessionId}/group/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter groupChat(@PathVariable Long projectId, @PathVariable Long sessionId,
                                @RequestBody(required = false) DialogueChatRequestDTO dto) {
        requireProject(projectId);
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        String content = dto == null ? null : dto.content();
        if (content == null || content.isBlank()) {
            sseLifecycle.fail(emitter, "发言不能为空");
            return emitter;
        }
        try {
            sessionService.get(projectId, sessionId);
            groupChatService.groupChat(projectId, currentUserProvider.currentUserId(), sessionId, content,
                    (name, data) -> sseLifecycle.send(emitter, name, data));
            emitter.complete();
        } catch (BizException e) {
            sseLifecycle.fail(emitter, e.getMessage());
        } catch (Exception e) {
            log.warn("[群聊] 对话异常: project={} session={} : {}", projectId, sessionId, e.getMessage());
            sseLifecycle.fail(emitter, "对话处理失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return emitter;
    }

    // ==================== 沉淀（M6：确认卡片 preview + 应用 apply） ====================

    /** 沉淀确认卡片：AI 抽取每位对谈角色的关系 delta + NPC 记忆要点（只读不落库，供确认展示）。 */
    @PostMapping("/sessions/{sessionId}/consolidate/preview")
    public R<DialogueConsolidationVO> consolidatePreview(@PathVariable Long projectId,
                                                         @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(consolidationService.preview(projectId, currentUserProvider.currentUserId(), sessionId));
    }

    /** 应用沉淀（用户确认后）：关系写回 + NPC 记忆 + 会话版本留痕（零 AI 短事务）。 */
    @PostMapping("/sessions/{sessionId}/consolidate/apply")
    public R<DialogueConsolidationResultVO> consolidateApply(
            @PathVariable Long projectId, @PathVariable Long sessionId,
            @RequestBody(required = false) DialogueConsolidationApplyDTO dto) {
        requireProject(projectId);
        List<DialogueConsolidationApplyDTO.CharacterCandidate> candidates =
                dto == null || dto.characters() == null ? List.of() : dto.characters();
        return R.ok(consolidationService.apply(projectId, currentUserProvider.currentUserId(),
                sessionId, candidates));
    }

    // ==================== 世界书（M4：会话级关键词触发条目） ====================

    /** 会话世界书条目列表（含停用；按 priority 升序）。 */
    @GetMapping("/sessions/{sessionId}/worldbook")
    public R<List<DialogueWorldbookVO>> worldbookList(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(worldbookService.list(projectId, sessionId));
    }

    /** 新增/更新世界书条目（同会话同关键词唯一：已存在 → 更新内容与开关）。 */
    @PostMapping("/sessions/{sessionId}/worldbook")
    public R<DialogueWorldbookVO> worldbookUpsert(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return R.ok(worldbookService.upsert(projectId, sessionId, str(body == null ? null : body.get("keyword")),
                str(body == null ? null : body.get("content")), bool(body == null ? null : body.get("enabled")),
                intValue(body == null ? null : body.get("priority"))));
    }

    /** 更新已存在条目（归属校验）。 */
    @PutMapping("/sessions/{sessionId}/worldbook/{entryId}")
    public R<DialogueWorldbookVO> worldbookUpdate(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                  @PathVariable Long entryId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return R.ok(worldbookService.update(projectId, sessionId, entryId, str(body == null ? null : body.get("keyword")),
                str(body == null ? null : body.get("content")), bool(body == null ? null : body.get("enabled")),
                intValue(body == null ? null : body.get("priority"))));
    }

    /** 删除世界书条目（归属校验）。 */
    @DeleteMapping("/sessions/{sessionId}/worldbook/{entryId}")
    public R<Map<String, Object>> worldbookDelete(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                  @PathVariable Long entryId) {
        requireProject(projectId);
        worldbookService.remove(projectId, sessionId, entryId);
        return R.ok(Map.of("deleted", true));
    }

    // ==================== 上下文配置 / 用量（M4） ====================

    /** 上下文配置（作者注释 + 滚动窗口摘要水位 + 世界书条目数）。 */
    @GetMapping("/sessions/{sessionId}/context-config")
    public R<DialogueContextConfigVO> contextConfig(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(usageService.contextConfigOf(projectId, sessionId));
    }

    /** 更新作者注释配置（位置/频率）；作者注释空串=清除。 */
    @PutMapping("/sessions/{sessionId}/context-config")
    public R<DialogueContextConfigVO> updateContextConfig(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        sessionService.updateAuthorNote(projectId, sessionId, str(body == null ? null : body.get("authorNote")),
                intValue(body == null ? null : body.get("authorNoteDepth")),
                intValue(body == null ? null : body.get("authorNoteFrequency")));
        return R.ok(usageService.contextConfigOf(projectId, sessionId));
    }

    /** 上下文用量估算（消息/段字符 + 估算 token；真实用量见 actor_usage_log scene=dialogue_chat）。 */
    @GetMapping("/sessions/{sessionId}/usage")
    public R<DialogueUsageVO> usage(@PathVariable Long projectId, @PathVariable Long sessionId) {
        requireProject(projectId);
        return R.ok(usageService.usageOf(projectId, sessionId));
    }

    // ==================== 内部 ====================

    /** 项目校验（不存在/已删除 → 404）。 */
    private void requireProject(Long projectId) {
        if (projectRepository == null) {
            return;
        }
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }

    /** body 字符串取值（null 安全）。 */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** body 长整型取值（null/非法返回 null）。 */
    private static Long longValue(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** body 布尔取值（null/非法返回 null）。 */
    private static Boolean bool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** body 整型取值（null/非法返回 null）。 */
    private static Integer intValue(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
