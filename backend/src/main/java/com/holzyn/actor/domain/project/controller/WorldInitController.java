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
import com.holzyn.actor.common.R;
import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.service.ProjectService;
import com.holzyn.actor.domain.project.service.WorkflowLog;
import com.holzyn.actor.domain.project.service.WorldInitService;
import com.holzyn.actor.domain.project.service.WorldMetaSuggestService;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.domain.project.vo.WorldInitResultVO;
import com.holzyn.actor.domain.project.vo.WorldInitStatusVO;
import com.holzyn.actor.domain.project.vo.WorldMetaSuggestVO;
import com.holzyn.actor.domain.worldstore.WorldLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 世界初始化向导控制器。
 *
 * <p><b>为什么新增这个控制器而不是往 {@link WorldWorkflowController} 里塞</b>：
 * 那个控制器只承载「一键跑完整链路」（{@code POST /api/worlds/{id}/init/stream}），
 * 语义是"从第一步跑到底"；向导需要的是<b>单步执行</b>（只跑第 N 步）、
 * <b>状态查询</b>（画廊角标/详情横幅/仪表盘入口都读它）、<b>跳过可选步</b>、
 * 以及新建世界时的<b>世界名/简介</b>能力。把这些混进一键链路会让两个语义互相污染
 * （例如"单步失败要不要中止后续"，两种入口答案不同）。</p>
 *
 * <p>SSE 事件约定与既有工作流一致：{@code log}={level,message,time}、
 * {@code stage}={name,index,total}、{@code result}=本步结果、{@code error}={message}。
 * 单步跑完后前端再查一次 init-status 拿最新整表状态（避免在这个 SSE 里塞
 * LocalDateTime 字段——本控制器的 ObjectMapper 是裸实例，没装 time 模块）。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 */
@RestController
@RequestMapping("/api/worlds")
@Slf4j
@RequiredArgsConstructor
public class WorldInitController {

    /** 世界初始化服务（单步执行 / 跳过 / 状态） */
    private final WorldInitService initService;

    /** 项目服务（写世界名与世界简介） */
    private final ProjectService projectService;

    /** 世界名与简介建议服务（AI） */
    private final WorldMetaSuggestService metaSuggestService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /** SSE 生命周期统一工具 */
    private final SseLifecycle sseLifecycle;

    /** 目录库世界元信息（画廊卡片读的那份快照；只读单测可能不装配） */
    @Autowired(required = false)
    private WorldLifecycleService lifecycle;

    /** SSE 单步执行器（异步执行，避免阻塞请求线程） */
    private final ExecutorService stepExecutor = Executors.newFixedThreadPool(2);

    /** JSON 序列化器（SSE 事件数据；只序列化无时间类型的载荷） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询世界初始化状态（"这个世界到底初始化完了没有"的唯一判定入口）。
     *
     * <p>画廊卡片角标、世界详情页顶部横幅、仪表盘初始化入口全部读这个接口；
     * 状态是<b>实时数据校验</b>（表里有没有地点/角色/关系/时钟）与
     * {@code actor_world_init_step} 状态行的合成结果，不依赖"用户点过完成按钮"。</p>
     *
     * @param id 项目 ID
     *
     * @return 初始化状态（逐步状态 + doneCount/complete/degraded + 中文结论 message）
     */
    @GetMapping("/{id}/init-status")
    public R<WorldInitStatusVO> status(@PathVariable("id") Long id) {
        return R.ok(initService.status(id));
    }

    /**
     * 世界类别 / 世界程度的可选项（新建向导与「修改世界」表单的下拉数据源）。
     *
     * <p>为什么不把这些标签硬编码在前端：类别是<b>枚举</b>（后端 {@code WorldCategory.require()}
     * 会拒绝八类之外的任何取值），程度还会驱动宏观调度与提示词边界。前后端各存一份清单，
     * 只要有人改了后端枚举而忘了前端，用户就会提交一个必然被 400 拒绝的值。</p>
     *
     * @return {@code {categories:[{code,label}], tones:[{code,label,summary,boundary}]}}
     */
    @GetMapping("/world-options")
    public R<Map<String, Object>> worldOptions() {
        java.util.List<Map<String, String>> categories = new java.util.ArrayList<>();
        for (com.holzyn.actor.domain.world.model.WorldCategory c
                : com.holzyn.actor.domain.world.model.WorldCategory.values()) {
            categories.add(Map.of("code", c.code(), "label", c.label()));
        }
        java.util.List<Map<String, String>> tones = new java.util.ArrayList<>();
        for (com.holzyn.actor.domain.world.model.WorldTone t
                : com.holzyn.actor.domain.world.model.WorldTone.values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("code", t.code());
            row.put("label", t.label());
            row.put("summary", t.summary());
            row.put("boundary", t.boundary());
            tones.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", categories);
        out.put("tones", tones);
        return R.ok(out);
    }

    /**
     * 执行单个初始化步骤（SSE，初始化向导「执行本步」）。
     *
     * <p>严格顺序：说明里"只跑第 N 步"不等于"可以乱序"——服务层会先校验第 N 步之前的
     * 必需步骤是否都已完成，未完成直接 400「请先完成第 K 步」。</p>
     *
     * @param id   项目 ID
     *
     * @param body 请求体：{@code {"stepCode":"LOCATIONS","rebuild":false}}
     *
     * @return SSE 事件流（log / stage / result / error）
     */
    @PostMapping(value = "/{id}/init/step/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStep(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        SseEmitter emitter = sseLifecycle.create(0L, 0L, null);
        Long userId = currentUserProvider.currentUserId();
        String stepCode = text(body, "stepCode");
        boolean rebuild = truthy(body, "rebuild");
        if (stepCode == null || stepCode.isBlank()) {
            sendError(emitter, "缺少步骤编码 stepCode");
            return emitter;
        }
        stepExecutor.execute(() -> {
            try {
                WorkflowLog wf = new SseWorkflowLog(emitter);
                WorldInitResultVO result = initService.runStep(userId, id, stepCode, rebuild, wf);
                sseLifecycle.sendJson(emitter, SseLifecycle.EVENT_RESULT,
                        objectMapper.writeValueAsString(result));
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, e.getMessage() == null ? "本步初始化失败" : e.getMessage());
            }
        });
        return emitter;
    }

    /**
     * 跳过某个可选初始化步骤（初始化向导「跳过此步」）。
     *
     * <p>必需步骤服务层会拒绝（400），可选步骤（家族/地图/宏观/向量化）跳过后
     * 世界标记为「降级完成」而不是「未完成」。</p>
     *
     * @param id   项目 ID
     *
     * @param body 请求体：{@code {"stepCode":"WORLD_MAP"}}
     *
     * @return 跳过后的最新初始化状态
     */
    @PostMapping("/{id}/init/skip")
    public R<WorldInitStatusVO> skip(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        String stepCode = text(body, "stepCode");
        if (stepCode == null || stepCode.isBlank()) {
            throw new BizException(400, "缺少步骤编码 stepCode");
        }
        return R.ok(initService.skipStep(id, stepCode));
    }

    /**
     * 根据当前世界观设定生成世界名与世界简介建议（不写库）。
     *
     * <p>含 AI 调用，故本方法<b>不</b>开事务（AGENTS §8.1.1）；前端拿到结果回填表单，
     * 用户可改写后再调 {@code POST /api/worlds/{id}/meta} 落库。</p>
     *
     * @param id 项目 ID
     *
     * @return 建议的世界名（6-14 字）与简介（60-100 字）
     */
    @PostMapping("/{id}/meta-suggest")
    public R<WorldMetaSuggestVO> metaSuggest(@PathVariable("id") Long id) {
        return R.ok(metaSuggestService.suggest(currentUserProvider.currentUserId(), id));
    }

    /**
     * 保存世界名与世界简介（同时写世界库项目、世界观名称与目录库画廊快照）。
     *
     * @param id   项目 ID
     *
     * @param body 请求体：{@code {"name":"...","summary":"..."}}
     *
     * @return 更新后的项目
     */
    @PostMapping("/{id}/meta")
    public R<ProjectVO> saveMeta(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        String name = trimToNull(text(body, "name"));
        String summary = trimToNull(text(body, "summary"));
        if (name == null) {
            throw new BizException(400, "世界名不能为空");
        }
        if (name.length() < 2 || name.length() > WorldMetaSuggestService.NAME_MAX) {
            throw new BizException(400, "世界名需 2-" + WorldMetaSuggestService.NAME_MAX + " 个字符");
        }
        if (summary != null && summary.length() > WorldMetaSuggestService.SUMMARY_MAX) {
            throw new BizException(400, "世界简介最多 " + WorldMetaSuggestService.SUMMARY_MAX + " 个字符");
        }
        ProjectVO vo = projectService.applyWorldMeta(id, name, summary);
        // 画廊列表真正读的是目录库快照，这里同步一次（世界未挂载/未建索引时静默跳过）
        if (lifecycle != null) {
            try {
                lifecycle.updateMeta(id, name, summary, null);
            } catch (Exception e) {
                log.warn("[世界元信息] 目录库快照同步失败（画廊可能显示旧名字）: 项目={} : {}", id, e.getMessage());
            }
        }
        return R.ok(vo);
    }

    /** 读取字符串字段（去首尾空白，空串按 null 处理） */
    private static String text(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 读取布尔字段（兼容 true / "true" / 1） */
    private static boolean truthy(Map<String, Object> body, String key) {
        if (body == null) {
            return false;
        }
        Object v = body.get(key);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * SSE 错误事件推送 + 关闭（统一事件名 error + 终止）。
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
            log.warn("[初始化向导-SSE] 错误推送失败: {}", e.getMessage());
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成/断开，忽略
        }
    }

    /**
     * SSE 单步日志回调：把每行后端日志与阶段进度推送给前端控制台。
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
                log.warn("[初始化向导-SSE] 日志推送失败: {}", e.getMessage());
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
                log.warn("[初始化向导-SSE] 阶段推送失败: {}", e.getMessage());
            }
        }
    }
}
