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

package com.holzyn.actor.domain.assistant.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.assistant.dto.AssistantChatRequestDTO;
import com.holzyn.actor.domain.assistant.service.AssistantChatService;
import com.holzyn.actor.domain.assistant.service.AssistantDocsRagService;
import com.holzyn.actor.domain.assistant.service.AssistantInterventionService;
import com.holzyn.actor.domain.assistant.service.AssistantSessionService;
import com.holzyn.actor.domain.assistant.service.AssistantToolExecutor;
import com.holzyn.actor.domain.assistant.vo.AssistantMessageVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 项目级 AI 助手接口。
 * <p>职责：会话 CRUD（按项目隔离）、消息读取、<b>SSE 流式对话</b>（POST chat）、
 * 工具清单调试、docs 索引状态与手动重建。</p>
 * <p><b>架构纪律</b>：chat 端点为长连接流式（AI 调用在事务外，见 {@link AssistantChatService}）；
 * 会话/消息 CRUD 为纯本地短事务。SSE 事件契约：
 * {@code meta(mode/sessionId)} → {@code tool(name/summary)*} → {@code token(delta)*} →
 * {@code done(content/card/sources/quickFollows/toolCalls)} | {@code error(message)}。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantSessionService sessionService;
    private final AssistantChatService chatService;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantDocsRagService docsRagService;
    private final AssistantInterventionService interventionService;
    private final CurrentUserProvider currentUserProvider;
    private final SseLifecycle sseLifecycle;

    /** 对话流式任务执行器（虚拟线程；SSE 生成不阻塞请求线程，token 即时 flush） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== 会话 CRUD ====================

    /** 项目会话列表（按更新时间倒序）。 */
    @GetMapping("/projects/{projectId}/sessions")
    public R<List<AssistantSessionVO>> sessions(@PathVariable Long projectId) {
        return R.ok(sessionService.list(projectId));
    }

    /** 新建会话。 */
    @PostMapping("/projects/{projectId}/sessions")
    public R<AssistantSessionVO> create(@PathVariable Long projectId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : str(body.get("title"));
        String mode = body == null ? null : str(body.get("defaultMode"));
        AssistantSessionVO vo = sessionService.create(projectId, currentUserProvider.currentUserId(), title, mode);
        String confirmMode = body == null ? null : str(body.get("confirmMode"));
        if (confirmMode != null && !confirmMode.isBlank()) {
            sessionService.updateConfirmMode(projectId, vo.id(), confirmMode);
            vo = sessionService.get(projectId, vo.id());
        }
        return R.ok(vo);
    }

    /** 单会话。 */
    @GetMapping("/projects/{projectId}/sessions/{sessionId}")
    public R<AssistantSessionVO> one(@PathVariable Long projectId, @PathVariable Long sessionId) {
        return R.ok(sessionService.get(projectId, sessionId));
    }

    /** 删除会话（级联消息）。 */
    @DeleteMapping("/projects/{projectId}/sessions/{sessionId}")
    public R<Map<String, Object>> delete(@PathVariable Long projectId, @PathVariable Long sessionId) {
        sessionService.delete(projectId, sessionId);
        return R.ok(Map.of("deleted", true));
    }

    /** 更新会话默认回答模式。 */
    @PostMapping("/projects/{projectId}/sessions/{sessionId}/mode")
    public R<AssistantSessionVO> updateMode(@PathVariable Long projectId, @PathVariable Long sessionId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        String mode = body == null ? null : str(body.get("mode"));
        if (mode == null || (!"narrative".equals(mode) && !"fact".equals(mode))) {
            throw new BizException(400, "mode 取值应为 narrative 或 fact");
        }
        sessionService.updateDefaultMode(projectId, sessionId, mode);
        return R.ok(sessionService.get(projectId, sessionId));
    }

    /** 更新会话干预确认粒度（strict 严格 / standard 标准 / trust 信任，M4）。 */
    @PostMapping("/projects/{projectId}/sessions/{sessionId}/confirm-mode")
    public R<AssistantSessionVO> updateConfirmMode(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String mode = body == null ? null : str(body.get("mode"));
        if (mode == null || (!"strict".equals(mode) && !"standard".equals(mode) && !"trust".equals(mode))) {
            throw new BizException(400, "mode 取值应为 strict / standard / trust");
        }
        sessionService.updateConfirmMode(projectId, sessionId, mode);
        return R.ok(sessionService.get(projectId, sessionId));
    }

    // ==================== M4：干预确认 / 取消 ====================

    /** 确认并执行待确认干预计划（复用沙盘既有服务）。 */
    @PostMapping("/projects/{projectId}/sessions/{sessionId}/interventions/{planId}/confirm")
    public R<Map<String, Object>> confirmIntervention(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                      @PathVariable Long planId) {
        AssistantInterventionService.ConfirmResult r = interventionService.confirm(projectId, sessionId, planId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", r.status());
        out.put("operation", r.operation());
        out.put("operationLabel", com.holzyn.actor.domain.assistant.service.AssistantToolExecutor.opLabel(r.operation()));
        out.put("detail", r.detail());
        out.put("resultText", r.resultText());
        out.put("result", r.result());
        return R.ok(out);
    }

    /** 取消待确认干预计划。 */
    @PostMapping("/projects/{projectId}/sessions/{sessionId}/interventions/{planId}/cancel")
    public R<Map<String, Object>> cancelIntervention(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                     @PathVariable Long planId) {
        AssistantInterventionService.ConfirmResult r = interventionService.cancel(projectId, sessionId, planId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", r.status());
        out.put("operation", r.operation());
        out.put("detail", r.detail());
        out.put("resultText", r.resultText());
        return R.ok(out);
    }

    /** 修改后执行（M5，设计 §6.2）：修改待确认计划参数后直接执行。 */
    @PostMapping("/projects/{projectId}/sessions/{sessionId}/interventions/{planId}/revise")
    public R<Map<String, Object>> reviseIntervention(@PathVariable Long projectId, @PathVariable Long sessionId,
                                                     @PathVariable Long planId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body == null ? null
                : (body.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : null);
        AssistantInterventionService.ConfirmResult r = interventionService.revise(projectId, sessionId, planId, params);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", r.status());
        out.put("operation", r.operation());
        out.put("operationLabel", com.holzyn.actor.domain.assistant.service.AssistantToolExecutor.opLabel(r.operation()));
        out.put("detail", r.detail());
        out.put("resultText", r.resultText());
        out.put("result", r.result());
        return R.ok(out);
    }

    /** 会话消息列表（时间正序）。 */
    @GetMapping("/projects/{projectId}/sessions/{sessionId}/messages")
    public R<List<AssistantMessageVO>> messages(@PathVariable Long projectId, @PathVariable Long sessionId) {
        return R.ok(sessionService.messages(projectId, sessionId));
    }

    // ==================== 对话（SSE 流式） ====================

    /**
     * 一轮对话（SSE 流式）。
     * <p><b>M5 打磨（流式实时）</b>：生成在<b>虚拟线程</b>执行、controller 立即返回 emitter——
     * 修复旧实现：在请求线程内同步跑完整个对话，token 事件被缓冲到连接关闭才一次性送达浏览器
     * （表现为「一股脑全部显示」而非逐 token 实时输出）。异步后每个 token 事件即时 flush。</p>
     *
     * @return SseEmitter（事件契约见类注释）
     */
    @PostMapping(value = "/projects/{projectId}/sessions/{sessionId}/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long projectId, @PathVariable Long sessionId,
                           @RequestBody(required = false) AssistantChatRequestDTO dto) {
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        String question = dto == null ? null : dto.question();
        if (question == null || question.isBlank()) {
            sseLifecycle.fail(emitter, "问题不能为空");
            return emitter;
        }
        String mode = dto == null ? null : dto.mode();
        // 会话/项目隔离校验（不存在抛 404）——快速校验放请求线程，生成放虚拟线程
        try {
            sessionService.get(projectId, sessionId);
        } catch (BizException e) {
            sseLifecycle.fail(emitter, e.getMessage());
            return emitter;
        }
        executor.execute(() -> {
            try {
                chatService.chat(projectId, sessionId, question, mode,
                        (name, data) -> sseLifecycle.send(emitter, name, data));
                emitter.complete();
            } catch (BizException e) {
                sseLifecycle.fail(emitter, e.getMessage());
            } catch (Exception e) {
                log.warn("[助手] 对话异常: project={} session={} : {}", projectId, sessionId, e.getMessage());
                sseLifecycle.fail(emitter, "对话处理失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        });
        return emitter;
    }

    // ==================== 工具 / 索引调试 ====================

    /** 工具清单（调试/前端展示）。 */
    @GetMapping("/tools")
    public R<List<Map<String, Object>>> tools() {
        return R.ok(toolExecutor.specs().stream()
                .map(s -> Map.<String, Object>of("name", s.name(), "description", s.description()))
                .toList());
    }

    /** docs 索引状态。 */
    @GetMapping("/docs-index/status")
    public R<Map<String, Object>> docsIndexStatus() {
        return R.ok(docsRagService.status());
    }

    /** docs 索引手动重建。 */
    @PostMapping("/docs-index/rebuild")
    public R<Map<String, Object>> docsIndexRebuild(@RequestBody(required = false) Map<String, Object> body) {
        Long projectId = body == null ? null : longv(body.get("projectId"));
        return R.ok(docsRagService.rebuild(projectId == null ? 1L : projectId));
    }

    /** body 字符串取值（null 安全）。 */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** body Long 取值（null/非法 → null）。 */
    private static Long longv(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
