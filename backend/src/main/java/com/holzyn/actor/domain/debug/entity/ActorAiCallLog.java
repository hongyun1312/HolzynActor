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

package com.holzyn.actor.domain.debug.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 调用全量 trace 实体，对应表 actor_ai_call_log（调试可视化前端专用， + B5 扩展）。
 * <p>职责：记录每次 AI 调用的完整调用链（场景/项目/角色/供应商/模型 + 完整 system/user Prompt
 * + 模型输出 + 状态[success/failed/degraded] + 错误 + 重试 + token 用量 + 耗时），
 * 供调试前端「AI Trace」页回溯"AI 到底看到了什么、输出了什么、为何失败/降级"（决策链路追踪的数据基础）。</p>
 * <p><b>B5（决策链路立基，）</b>：新增 trace_id（业务动作级决策链路贯穿——一次
 * 行动决策/对话回复/跨日事件润色内的全部 AI 调用共享同一 trace_id，经 {@code AiCallContext.withTrace}
 * 包裹）、attempt、
 * step_type（环节类型：ai_call 缺省 / perception 感知组装 / action 行动执行 / event 事件发布等非 AI 环节
 * 也记一行，content 存环节摘要，token=0，让一次决策链路含非 AI 步骤）。</p>
 * <p><b>AI 调用详情 JSON</b>：新增 detail_json 列
 * （LONGTEXT，存整条 AI 调用详情 JSON——时间轴/业务上下文/供应商/请求参数/输入 raw+messages+摘要/
 * 输出 raw+text+摘要/token 用量/状态与错误/retry/context/remark），由 {@code AiCallTrace.toDetailJson()}
 * 组装后在 {@code AiCallTraceService.submitAsync} 一并写入；同时以同名 JSON 文件输出到
 * {@code <data-dir>/ai-call-logs/}（落库与文件双份存储，命名 ai-call-{id}-{scene}.json）。
 * 现有摘要列（system_prompt/user_prompt/response/token 等）保留供索引查询。</p>
 * <p>埋点位置：AiProviderRouter.chatCompletion / chatCompletionStream 统一入口（全部业务调用必经），
 * 由 AiCallTraceService 异步写库（虚拟线程，写库失败仅告警不阻断主流程）。</p>
 * <p>保留策略：<b>滚动保留</b>（P1-5 数据膨胀最小治理）——创建时间早于
 * {@code holzyn.actor.debug.ai-log.retention.days}（默认 7 天）的历史记录由
 * {@code AiCallLogRetentionService} 定时批量删除，文件副本同步清理；0=关闭滚动删除。</p>
 */
@Data
@Entity
@Table(name = "actor_ai_call_log")
public class ActorAiCallLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 决策链路 trace_id（B5：一次业务动作内全部 AI 调用/非 AI 环节共享，可空=未包裹的业务动作） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 重试尝试序号 */
    @Column(name = "attempt")
    private Integer attempt = 1;

    /**
     * 环节类型（B5：ai_call 缺省 / perception 感知 / action 行动执行 / event 事件发布 等非 AI 环节）
     * <p><b>阶段④ 加宽</b>：20 → 30。原 20 已是全仓库最紧的编码列——
     * 现存最长值 {@code archive_maintenance} / {@code working_maintenance}（各 19 字符，
     * MemoryConsolidationService）只剩 1 字符余量；且本列写入失败是<b>静默</b>的
     * （{@code AiCallTraceService} 捕获后仅 warn「不影响主流程」），下一个 {@code *_maintenance}
     * 风格环节名就会悄悄丢 trace 而不报错。加宽到 30 与 {@code scene} 列同宽，留足余量。</p>
     */
    @Column(name = "step_type", length = 30)
    private String stepType = "ai_call";

    /** 环节摘要（非 AI 环节存描述+耗时；AI 调用为空） */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 归属项目（无项目调用=null，如文件解析/导入） */
    @Column(name = "project_id")
    private Long projectId;

    /** 角色 ID（普通 NPC 暂 null，后续六组件落地时扩展 character_ref） */
    @Column(name = "character_id")
    private Long characterId;

    /**
     * 调用场景：dialog/action/card_gen/relation_gen/memory/crowd_ai/field_dict/npc_gen/world_event/...（与 UsageService.sceneName 同源）；
     * 48 字符余量容纳最长场景码 growth_stage_transition_narrative
     */
    @Column(length = 48)
    private String scene;

    /** API 配置 ID（关联 actor_model_provider.id，可空） */
    @Column(name = "provider_id")
    private Long providerId;

    /** 实际模型名 */
    @Column(length = 100)
    private String model;

    /** 系统提示词全文（多个 system 消息以分隔符拼接） */
    @Column(name = "system_prompt", columnDefinition = "LONGTEXT")
    private String systemPrompt;

    /** 用户消息全文（多条 user 消息以分隔符拼接） */
    @Column(name = "user_prompt", columnDefinition = "LONGTEXT")
    private String userPrompt;

    /** 模型输出全文（流式=最终拼接结果） */
    @Column(columnDefinition = "LONGTEXT")
    private String response;

    /** 调用状态：success 成功 / failed 失败 / degraded AI 失败后程序化兜底 */
    @Column(length = 20)
    private String status;

    /** 失败原因（截断存储） */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 本次调用重试次数 */
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /** 输入 token（无则本地估算） */
    @Column(name = "token_in")
    private Integer tokenIn = 0;

    /** 输出 token */
    @Column(name = "token_out")
    private Integer tokenOut = 0;

    /** 输入中命中上下文缓存的 token（DeepSeek prompt_cache_hit_tokens） */
    @Column(name = "cache_hit")
    private Integer cacheHit = 0;

    /** 输入中未命中缓存的 token */
    @Column(name = "cache_miss")
    private Integer cacheMiss = 0;

    /** 调用耗时（毫秒） */
    @Column(name = "duration_ms")
    private Integer durationMs = 0;

    /** AI 调用详情 JSON（整条调用详情结构化落库，与 data/ai-call-logs/ 文件同内容；LONGTEXT 存 JSON 字符串） */
    @Column(name = "detail_json", columnDefinition = "LONGTEXT")
    private String detailJson;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = "success";
        if (retryCount == null) retryCount = 0;
        if (attempt == null) attempt = 1;
        if (stepType == null || stepType.isBlank()) stepType = "ai_call";
        if (tokenIn == null) tokenIn = 0;
        if (tokenOut == null) tokenOut = 0;
        if (cacheHit == null) cacheHit = 0;
        if (cacheMiss == null) cacheMiss = 0;
        if (durationMs == null) durationMs = 0;
    }
}
