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

package com.holzyn.actor.domain.project.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.dto.WorldInitRequestDTO;
import com.holzyn.actor.domain.project.service.WorkflowLog;
import com.holzyn.actor.domain.project.service.WorldInitService;
import com.holzyn.actor.domain.project.service.WorldParseService;
import com.holzyn.actor.domain.project.vo.WorldInitResultVO;
import com.holzyn.actor.domain.project.vo.WorldParseResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 新建项目「工作流」控制器（新建项目解析重构）。
 * <p>职责：提供两个 SSE 流式端点——
 * ① {@code POST /api/worlds/import/workflow}：文件解析工作流（分段/扩写/建项目落表/知识库存储/角色分离入库），
 * 逐条推送后端解析日志（log 事件）+ 阶段进度（stage 事件），完成后推送 result（含新建 projectId）；
 * ② {@code POST /api/worlds/{id}/init/stream}：世界初始化工作流（地点/角色卡/字段字典+普通NPC/关系/世界时间/知识向量化），
 * 同样逐条推送日志与阶段，完成后推送 result。
 * 前端「文件解析」页与「世界初始化」页的控制台日志区域即消费这两个端点的 SSE 事件。</p>
 * <p>SSE 事件约定：{@code log}={level,message,time}（一行后端日志）；{@code stage}={name,index,total}；
 * {@code result}=各工作流结果 VO；{@code error}={message}。</p>
 */
@RestController
@RequestMapping("/api/worlds")
@Slf4j
@RequiredArgsConstructor
public class WorldWorkflowController {

    /** 解析工作流服务 */
    private final WorldParseService parseService;

    /** 世界初始化服务 */
    private final WorldInitService initService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /** SSE 工作流执行器（异步执行，避免阻塞请求线程） */
    private final ExecutorService workflowExecutor = Executors.newFixedThreadPool(2);

    /** JSON 序列化器（SSE 事件数据） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** SSE 生命周期统一工具（：统一超时/心跳/清理/事件名） */
    private final com.holzyn.actor.common.sse.SseLifecycle sseLifecycle;

    /**
     * 文件解析工作流（SSE）：分段 →（可选）扩写 → 建项目落世界观表 → 知识库存储 → 角色分离入库。
     *
     * @param files 多文件（txt/md，单文件 ≤5MB）
     *
     * @param expand 是否自动 AI 扩写不足 1500 字的分段（默认 false=不扩写，由用户决定；默认不触发扩写）
     *
     * @return SSE 事件流（log / stage / result / error）
     */
    @PostMapping(value = "/import/workflow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter parseWorkflow(@RequestParam("files") MultipartFile[] files,
                                    @RequestParam(value = "expand", required = false, defaultValue = "false") boolean expand) {
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        // 关键：必须在请求线程获取 userId（SecurityContext 是 ThreadLocal，不会自动传入异步线程）
        Long userId = currentUserProvider.currentUserId();
        workflowExecutor.execute(() -> {
            try {
                if (files == null || files.length == 0) {
                    throw new BizException(400, "请至少上传一个文件");
                }
                List<String> texts = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    String name = f.getOriginalFilename();
                    if (!isTextFile(name)) {
                        throw new BizException(400, "仅支持 txt / md / markdown 文本文件：" + name);
                    }
                    try {
                        byte[] bytes = f.getBytes();
                        texts.add(new String(bytes, StandardCharsets.UTF_8));
                        String type = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1) : "unknown";
                        log.info("[文件解析] 收到上传文件：name={}, type={}, size={}B, chars={}",
                                name, type, bytes.length, texts.get(texts.size() - 1).length());
                    } catch (Exception e) {
                        throw new BizException(400, "文件读取失败：" + name);
                    }
                    names.add(name);
                }
                if (texts.isEmpty()) {
                    throw new BizException(400, "未读取到有效文件内容");
                }
                WorkflowLog wf = new SseWorkflowLog(emitter);
                WorldParseResultVO result = parseService.parseAndCreate(userId, texts, names, expand, wf);
                sseLifecycle.sendJson(emitter, SseLifecycle.EVENT_RESULT,
                        objectMapper.writeValueAsString(result));
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e.getMessage() == null ? "解析失败" : e.getMessage());
            }
        });
        return emitter;
    }

    /**
     * 世界初始化工作流（SSE）：地点 → 角色卡 → 字段字典+普通NPC → 关系拓扑 → 世界时间 → 知识向量化。
     *
     * @param id 项目 ID
     *
     * @param body 请求体（rebuild 可选）
     *
     * @return SSE 事件流（log / stage / result / error）
     */
    @PostMapping(value = "/{id}/init/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter initWorkflow(@PathVariable Long id, @RequestBody(required = false) WorldInitRequestDTO body) {
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        Long userId = currentUserProvider.currentUserId();
        boolean rebuild = body != null && Boolean.TRUE.equals(body.rebuild());
        workflowExecutor.execute(() -> {
            try {
                WorkflowLog wf = new SseWorkflowLog(emitter);
                WorldInitResultVO result = initService.runInit(userId, id, rebuild, wf);
                sseLifecycle.sendJson(emitter, SseLifecycle.EVENT_RESULT,
                        objectMapper.writeValueAsString(result));
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e.getMessage() == null ? "世界初始化失败" : e.getMessage());
            }
        });
        return emitter;
    }

    /**
     * 判断是否为文本文件（txt/md/markdown）。
     *
     * @param name 文件名
     *
     * @return true 表示支持
     */
    private boolean isTextFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /**
     * SSE 错误事件推送 + 关闭（：统一事件名 error + 终止）。
     *
     * @param emitter SSE 发射器
     *
     * @param message 错误信息
     */
    private void sendError(SseEmitter emitter, String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", message);
            sseLifecycle.sendJson(emitter, SseLifecycle.EVENT_ERROR,
                    objectMapper.writeValueAsString(err));
        } catch (Exception e) {
            log.warn("[工作流-SSE] 错误推送失败: {}", e.getMessage());
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成/断开，忽略
        }
    }

    /**
     * SSE 工作流日志回调：把每行后端日志与阶段进度推送给前端控制台。
     * <p>事件格式：{@code log}={level,message,time}；{@code stage}={name,index,total}（统一常量）。</p>
     */
    private class SseWorkflowLog implements WorkflowLog {

        private final SseEmitter emitter;
        private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");

        SseWorkflowLog(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void info(String message) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("level", "info");
                data.put("message", message);
                data.put("time", LocalTime.now().format(timeFmt));
                sseLifecycle.sendJson(emitter, SseLifecycle.EVENT_LOG,
                        objectMapper.writeValueAsString(data));
            } catch (Exception e) {
                log.warn("[工作流-SSE] 日志推送失败: {}", e.getMessage());
            }
        }

        @Override
        public void stage(String name, int index, int total) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", name);
                data.put("index", index);
                data.put("total", total);
                sseLifecycle.sendJson(emitter, SseLifecycle.EVENT_STAGE,
                        objectMapper.writeValueAsString(data));
            } catch (Exception e) {
                log.warn("[工作流-SSE] 阶段推送失败: {}", e.getMessage());
            }
        }
    }
}
