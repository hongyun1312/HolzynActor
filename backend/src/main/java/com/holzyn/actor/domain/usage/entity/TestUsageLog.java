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

package com.holzyn.actor.domain.usage.entity;

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
 * 长跑测试「AI 调用用量明细」实体，对应表 {@code actor_test_usage_log}。
 * <h2>⚠️ 性质声明：本表是<b>测试设施</b>，不是领域概念</h2>
 * <p>本表的唯一职责是服务长跑测试的计量：把每一次 AI 调用（主模型对话 / 决策模型 / embedding /
 * Judge）的「用时 / 请求输入 / 模型输出 / 输入 token / 输出 token / 缓存 token」按<b>轮次标签</b>
 * 完整留档，供事后逐条复核与「每游戏日 token 消耗」聚合。</p>
 * <p>因此：</p>
 * <ul>
 * <li><b>不登记</b>进 {@code docs/架构/概念注册表.md}（登记表只收领域概念；本表无领域语义、
 * 不被任何业务读写路径查询）；</li>
 * <li><b>不新增</b>对外查询接口/前端页（：查询入口 = 脚本 + Markdown 报告，
 * <li>不参与 {@code actor_usage_log}（业务用量聚合，含 cost 列）与 {@code actor_ai_call_log}
 * （调试埋点，含 detail_json）的任何一条现有读写路径——三者互不替代、互不覆盖。</li>
 * </ul>
 * <h2>为什么另起一张表（而不是扩展 actor_usage_log / actor_ai_call_log）</h2>
 * <p>「方案 B：新建独立测试表，仅用于存储测试数据」。理由是三者口径正交，混表会互相污染：</p>
 * <ul>
 * <li>{@code actor_usage_log}：业务用量（有 cost、无原文），聚合口径按项目/场景/模型，
 * 改造它会动到「AI 用量统计」页与管理后台；</li>
 * <li>{@code actor_ai_call_log}：调试埋点（有原文，但 system/user/response 各按
 * {@code holzyn.actor.debug.ai-log.max-prompt-chars}（默认 20000 字符）<b>截断</b>，
 * 且无 run_label、无游戏秒、无 base_url）；</li>
 * <li>本表：测试专属——<b>全文不截断</b>、
 * （按游戏日聚合）、<b>只记 token 不记金额</b>。</li>
 * </ul>
 * <h2>与既有表的关系（同一次调用写两张表是<b>有意</b>的）</h2>
 * <p>埋点点位在 {@code AiCallTraceService.submitAsync}（所有 AI 调用的唯一汇聚点），
 * 打开 {@code holzyn.actor.test-usage.enabled=true} 后：本次调用既照旧写
 * {@code actor_ai_call_log}（保持既有可观测性与 Judge 抽样可用），又额外写本表一份
 * （供本轮计量）。两张表各自独立失败、各自只告警，都不影响 AI 主流程。</p>
 * <h2>写入纪律</h2>
 * <ul>
 * <li>本表只在 {@code AiCallTraceService} 的<b>虚拟线程异步写回</b>里插入，绝不进入任何
 * {@code @Transactional} 的 AI 调用方法（AI 调用不得持事务）；</li>
 * <li>每次插入由 {@code SimpleJpaRepository} 自带事务包住，独立提交；写失败仅 WARN，
 * <b>绝不</b>让计量影响 AI 结果；</li>
 * <li>只 INSERT，本期不 DELETE/UPDATE（无保留策略，测试期需要可全量留存）。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_test_usage_log")
public class TestUsageLog {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 轮次标签。
     * <p>隔离口径：报告脚本一律先按 run_label 过滤，绝不与其他轮次的数据混算。
     * 配置项 {@code holzyn.actor.test-usage.run-label}；未配置时写入 {@code UNLABELED}。</p>
     */
    @Column(name = "run_label", length = 48, nullable = false)
    private String runLabel;

    /** 本次 AI 调用的真实起始时刻（由 trace 的 startMs 换算，本地时区） */
    @Column(name = "call_at")
    private LocalDateTime callAt;

    /**
     * 调用落库时刻的引擎游戏秒（{@code actor_world_clock.last_game_second} 只读快照，可空）。
     * <p>口径：<b>调用结束/落库时</b>的游戏秒。调用起点 = 本值 − {@code durationMs × rate / 1000}
     * （rate 从长跑轮询日志 {@code logs/longrun-polls.tsv} 取对应时刻的值）。
     * 单次调用内时钟最多前进 {@code 用时 × rate} 游戏秒（rate=60、用时 3 真实秒 → 180 游戏秒
     * = 3 游戏分钟），对「按游戏日聚合」无影响。</p>
     */
    @Column(name = "game_second")
    private Long gameSecond;

    /** 调用场景码（action / dialog / plan_day / growth_space_generate / embedding / decision_* …） */
    @Column(length = 48)
    private String scene;

    /** 环节类型（默认 ai_call；感知/记忆等非 AI 环节另有取值） */
    @Column(name = "step_type", length = 30)
    private String stepType;

    /** 同一业务动作的 trace 串联 ID（B5） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 第几次尝试（重试计数） */
    @Column
    private Integer attempt = 0;

    /** 项目 ID */
    @Column(name = "project_id")
    private Long projectId;

    /** 角色 ID（可空：embedding/世界级调用无角色） */
    @Column(name = "character_id")
    private Long characterId;

    /** 供应商配置主键（关联 actor_model_provider.id；缺省 provider 时为空） */
    @Column(name = "provider_id")
    private Long providerId;

    /** 实际使用的模型名（deepseek-flash / qwen3.6-35b-q4-un / 决策模型 / 向量模型 …） */
    @Column(length = 100)
    private String model;

    /** 供应商地址（区分官方/内网网关；缺省 provider 时为空） */
    @Column(name = "base_url", length = 255)
    private String baseUrl;

    /** 调用用时（毫秒） */
    @Column(name = "duration_ms")
    private Integer durationMs = 0;

    /** 输入 token 数 */
    @Column(name = "token_in")
    private Integer tokenIn = 0;

    /** 输出 token 数 */
    @Column(name = "token_out")
    private Integer tokenOut = 0;

    /** 输入中命中 prompt cache 的 token 数（DeepSeek prompt_cache_hit_tokens） */
    @Column(name = "cache_hit_tokens")
    private Integer cacheHitTokens = 0;

    /** 输入中未命中缓存、需全量计算的 token 数 */
    @Column(name = "cache_miss_tokens")
    private Integer cacheMissTokens = 0;

    /** 调用状态（success / failed / degraded） */
    @Column(length = 20)
    private String status;

    /** 失败信息（成功时为空） */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * 请求输入·系统提示词（<b>全文，不截断</b>）。
     * <p>与 {@code user_prompt} 合起来即用户要求的「请求输入」；分开存是为了事后能按角色/场景
     * 稳定 diff（系统提示词是模板化的，用户提示词是变化的）。</p>
     */
    @Column(name = "system_prompt", columnDefinition = "LONGTEXT")
    private String systemPrompt;

    /** 请求输入·用户提示词（<b>全文，不截断</b>） */
    @Column(name = "user_prompt", columnDefinition = "LONGTEXT")
    private String userPrompt;

    /** 模型输出原文（<b>全文，不截断</b>；流式调用为拼接后的完整文本） */
    @Column(name = "response_text", columnDefinition = "LONGTEXT")
    private String responseText;

    /** 请求输入字符数（含系统提示词，便于不做全文查询也能估 token） */
    @Column(name = "request_chars")
    private Integer requestChars = 0;

    /** 模型输出字符数 */
    @Column(name = "response_chars")
    private Integer responseChars = 0;

    /** 落库时刻 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充时间与默认值，保证 NOT NULL 列有值。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (gameSecond == null) gameSecond = -1L;
        if (tokenIn == null) tokenIn = 0;
        if (tokenOut == null) tokenOut = 0;
        if (cacheHitTokens == null) cacheHitTokens = 0;
        if (cacheMissTokens == null) cacheMissTokens = 0;
        if (durationMs == null) durationMs = 0;
        if (requestChars == null) requestChars = 0;
        if (responseChars == null) responseChars = 0;
        if (attempt == null) attempt = 0;
    }
}
