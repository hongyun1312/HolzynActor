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

package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 决策旁路校验器·自省-行为一致性校验（自省漂移检测，候选 A）。
 * <p><b>语义</b>：角色反思「我该谨慎」≠ 实际决策「真的谨慎了」——KAPRO 基准揭示的
 * 认知-行为鸿沟。本校验器在决策链命中产出决策后，把<b>最近反思洞察</b>与<b>已产出决策</b>
 * 交给 AI 判定是否一致：不一致 = <b>自省漂移</b>（明知故犯），写 trace（stepType=self_check）
 * 供审计，并由反思闭环（CODE_REFLECTION 模板 {{self_drift_summary}} 段）在下次反思时
 * 「看到自己的漂移」——闭环：反思 → 决策 → 校验 → 再反思。</p>
 * <p><b>旁路语义（用户 Q 确认）</b>：本类实现 {@link DecisionPostCheck}（旁路校验器），
 * 同时实现 {@link BrainStrategy} 以满足策略链可插拔注册语义——但 {@link #applies} 恒 false、
 * {@link #decide} 恒 empty（<b>永不作为产出策略进入决策链</b>）；真正入口是
 * {@link #check}，由 {@link BrainStrategyRouter} 在链命中产出决策后自动调用。</p>
 * <p><b>触发口径（用户 Q 确认）</b>：概率抽样（默认 30% 可配）+ 仅特殊 NPC +
 * 仅非 programmatic 档（程序化决策无 AI 意志可校验）+ 近 1 游戏日内有反思洞察才校验
 * （无反思无从校验）。判定=AI（scene=self_check，输出 {"consistent":bool,"reason":str}）；
 * AI 失败/超时/解析失败 → <b>fail-open 不标记</b>（降级，不干扰主流程）。</p>
 * <p><b>架构不变量</b>：含 AI 调用，本类公开方法<b>一律不带 {@code @Transactional}</b>；
 * 依赖全部字段注入 {@code @Autowired(required=false)}+{@code @Lazy} 破 Bean 环
 * （与既有策略同风格）；null=未装配[单测]按能力缺失降级（跳过校验零影响）。</p>
 */
@Slf4j
@Component
public class SelfCheckStrategy implements BrainStrategy, DecisionPostCheck {

    /** AI 判定场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_SELF_CHECK = "self_check";

    /** 抽样随机源（测试可经 ReflectionTestUtils 覆盖固定 seed 验证确定性）。 */
    private final Random random = new Random();

    /** 校验总开关。 */
    @Value("${holzyn.actor.self-check.enabled:true}")
    private boolean enabled = true;

    /** 概率抽样率（0~1；命中才校验）。 */
    @Value("${holzyn.actor.self-check.sample-rate:0.3}")
    private double sampleRate = 0.3;

    /** 反思洞察取用条数（最近 N 条 reflection 记忆）。 */
    @Value("${holzyn.actor.self-check.reflection-top-n:3}")
    private int reflectionTopN = 3;

    /** 反思窗口（近 N 游戏日内有反思才校验）。 */
    @Value("${holzyn.actor.self-check.reflection-window-game-days:1}")
    private long reflectionWindowGameDays = 1;

    /** 记忆仓库（直查 type=reflection 反思洞察；null=未装配跳过）。 */
    @Autowired(required = false)
    @Lazy
    private ActorMemoryRepository memoryRepository;

    /** 世界时钟（反思窗口换算：actor_memory.createdAt 真实时刻 → 游戏小时）。 */
    @Autowired(required = false)
    @Lazy
    private WorldClockService worldClockService;

    /** Prompt 渲染（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /** AI 网关（AI 判定；真实网关/测试 mock）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 判定）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 判定记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（self_check 属批4 记忆链·角色视角）：一致性判定请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== BrainStrategy（产出侧占位：恒不产出） ====================

    @Override
    public String name() {
        return "self_check";
    }

    @Override
    public int priority() {
        // 旁路校验器：不参与产出链排序（applies 恒 false，priority 仅 trace 展示用）
        return 0;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        // 旁路校验器永不作为产出策略进链（链命中由 Router 在产出后回调 check）
        return false;
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        return Optional.empty();
    }

    // ==================== DecisionPostCheck（旁路校验入口） ====================

    @Override
    public void check(DecisionContext ctx, Map<String, Object> decision) {
        if (!enabled || ctx == null || ctx.character() == null || decision == null || decision.isEmpty()) {
            return;
        }
        // 门控：仅特殊 NPC（普通 NPC 无反思机制，无从校验）；仅非 programmatic 档
        // （程序化决策=生理强制/continue 兜底，无 AI 意志可言，不构成「明知故犯」）
        if (NpcKind.SPECIAL != ctx.kind()) {
            return;
        }
        if ("programmatic".equals(ctx.brainLevel())) {
            return;
        }
        // 概率抽样（命中才校验——自省漂移是采样观测，非全量审计）
        if (random.nextDouble() >= sampleRate) {
            return;
        }
        long startMs = System.currentTimeMillis();
        try {
            // ① 直查最近反思洞察（近 reflectionWindowGameDays 游戏日，top-N；无反思 → 跳过）
            List<String> insights = recentReflectionInsights(ctx);
            if (insights.isEmpty()) {
                return;
            }
            // ② AI 判定（scene=self_check）：反思 vs 决策 → 一致/漂移 + 理由
            SelfCheckVerdict verdict = judge(ctx, insights, decision);
            long durationMs = System.currentTimeMillis() - startMs;
            if (verdict == null) {
                // fail-open：AI 判定失败/超时/解析失败 → 不标记（降级，不干扰主流程）
                log.debug("[自省校验] AI 判定失败降级（不标记）: ref={}", ctx.character().npcRef());
                return;
            }
            // ③ 写 trace（stepType=self_check；漂移/一致都留痕，供审计与反思闭环）
            String decisionText = extractDecisionText(decision);
            // content 前缀带 ref=npc:{id}（AiCallContext.recordStep 无 characterId 形参，
            // 反思闭环按 content 前缀检索本角色漂移记录）
            String content = "ref=" + ctx.character().npcRef() + " " + (verdict.consistent()
                    ? "自省校验：与最近反思一致（反思=「" + abbreviate(insights.get(0), 30)
                    + "」决策=「" + abbreviate(decisionText, 30) + "」）"
                    : "自省漂移：反思=「" + abbreviate(insights.get(0), 30)
                    + "」决策=「" + abbreviate(decisionText, 30) + "」（" + abbreviate(verdict.reason(), 40) + "）");
            AiCallContext.recordStep(ctx.projectId(), "agent_decision", "self_check", content, durationMs);
            log.info("[自省校验] {}: ref={} 反思={} 决策={}",
                    verdict.consistent() ? "一致" : "漂移", ctx.character().npcRef(),
                    abbreviate(insights.get(0), 30), abbreviate(decisionText, 30));
        } catch (Exception e) {
            // 旁路校验器异常隔离：绝不干扰主决策流程
            log.warn("[自省校验] 异常（隔离）: ref={} : {}", ctx.character().npcRef(), e.getMessage());
        }
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 反思窗口判定：actor_memory 创建时刻换算游戏小时，落在近 N 游戏日内。
     *
     * @param projectId 项目 ID（记忆创建时刻换算游戏小时用）
     *
     * @param createdAt 记忆创建时刻（真实时钟；null=无法换算按不在窗口）
     *
     * @param currentGameHour 当前游戏小时（null=无法判定按不在窗口）
     *
     * @return true=在窗口内
     */
    boolean withinReflectionWindow(Long projectId, LocalDateTime createdAt, Long currentGameHour) {
        if (projectId == null || createdAt == null || currentGameHour == null || worldClockService == null) {
            return false;
        }
        try {
            Long createdGameHour = worldClockService.gameHourAt(projectId, createdAt);
            if (createdGameHour == null) {
                return false;
            }
            long diffHours = Math.abs(currentGameHour - createdGameHour);
            return diffHours < reflectionWindowGameDays * 24L;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判定结果（内部规范）。
     *
     * @param consistent true=与反思一致；false=自省漂移
     *
     * @param reason 判定理由（AI 输出；可空）
     */
    public record SelfCheckVerdict(boolean consistent, String reason) {
    }

    /** 字符串截断（摘要展示用）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    // ==================== 私有辅助 ====================

    /**
     * 直查最近反思洞察（type=reflection，近窗口游戏日内，top-N，新在前）。
     * 无反思/仓库未装配 → 空列表（调用方跳过校验）。
     */
    private List<String> recentReflectionInsights(DecisionContext ctx) {
        if (memoryRepository == null || ctx.character() == null) {
            return List.of();
        }
        Long currentGameHour = currentGameHour(ctx);
        List<ActorMemory> reflections = memoryRepository
                .findByProjectIdAndCharacterIdAndTypeAndDeletedOrderByCreatedAtDesc(
                        ctx.projectId(), ctx.character().getId(), ActorMemoryTypes.TYPE_REFLECTION, 0);
        if (reflections.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (ActorMemory m : reflections) {
            if (out.size() >= Math.max(1, reflectionTopN)) {
                break;
            }
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            // 窗口过滤：只取近 reflectionWindowGameDays 游戏日内的反思（陈旧反思不参与本次校验）
            if (!withinReflectionWindow(ctx.projectId(), m.getCreatedAt(), currentGameHour)) {
                continue;
            }
            out.add(m.getContent().trim());
        }
        return out;
    }

    /** 当前游戏小时（引擎位置口径；失败回退世界时钟当前值，再失败 null）。 */
    private Long currentGameHour(DecisionContext ctx) {
        if (worldClockService == null) {
            return null;
        }
        try {
            Long gs = worldClockService.currentEngineGameSecond(ctx.projectId());
            if (gs != null) {
                return gs / 3600L;
            }
        } catch (Exception e) {
            log.debug("[自省校验] 引擎时刻读取失败（回退）: {}", e.getMessage());
        }
        try {
            return worldClockService.currentGameHour(ctx.projectId());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * AI 判定：scene=self_check，输入=最近反思洞察 + 已产出决策 → 输出
     * {"consistent":bool,"reason":str}。失败/超时/解析失败 → null（fail-open）。
     */
    private SelfCheckVerdict judge(DecisionContext ctx, List<String> insights, Map<String, Object> decision) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null;
        }
        try {
            Long uid = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            Map<String, Object> placeholders = new LinkedHashMap<>();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < insights.size(); i++) {
                sb.append(i + 1).append(". ").append(insights.get(i)).append('\n');
            }
            placeholders.put("recent_insights", sb.toString().trim());
            placeholders.put("decision", decisionTextOf(decision));
            String prompt = promptTemplateService.render(uid, ctx.projectId(), SCENE_SELF_CHECK, placeholders);
            // 批6 收口（self_check 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
            // 「反思与决策是否一致」先知道世界/身份/此刻处境；未装配 → 原样（fail-open）
            if (aiContextInjector != null && ctx.character() != null) {
                String cctx = aiContextInjector.memoryChainContext(ctx.projectId(), ctx.character().npcRef(), null);
                if (cctx != null && !cctx.isBlank()) {
                    prompt = cctx + "\n\n" + prompt;
                }
            }
            String system = promptTemplateService.systemMessage(uid, ctx.projectId(), SCENE_SELF_CHECK, null);
            if (system == null || system.isBlank()) {
                system = "你只输出严格的 JSON 对象，不输出任何其他文字。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", prompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_SELF_CHECK),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_SELF_CHECK),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_SELF_CHECK, ctx.character().getId(), 1,
                    () -> aiChatGateway.chatCompletion(uid, ctx.projectId(), null, req));
            try {
                usageLogService.record(uid, ctx.projectId(), ctx.character().getId(), result.providerId(),
                        result.model(), SCENE_SELF_CHECK, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.debug("[自省校验] 用量入账失败（忽略）: {}", e.getMessage());
            }
            return parseVerdict(result.content());
        } catch (Exception e) {
            log.debug("[自省校验] AI 判定失败（fail-open）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析判定 JSON（{"consistent":bool,"reason":str}）。失败/非法 → null。
     */
    static SelfCheckVerdict parseVerdict(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            String json = JsonUtil.extractJson(content);
            if (json == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.has("consistent")) {
                return null;
            }
            boolean consistent = node.path("consistent").asBoolean(false);
            String reason = node.path("reason").asText(null);
            return new SelfCheckVerdict(consistent, reason);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 决策展示文本（特殊链 ReAct 键集：action/actionDetail/type；普通链 CrowdAction：
     * action/text。首非空命中）。
     */
    static String extractDecisionText(Map<String, Object> decision) {
        if (decision == null) {
            return null;
        }
        for (String key : List.of("action", "actionDetail", "text", "action_text", "reason")) {
            Object v = decision.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    /** 判定输入决策文本（复用提取，空 → 占位）。 */
    private static String decisionTextOf(Map<String, Object> decision) {
        String t = extractDecisionText(decision);
        return t == null ? "（无行动描述）" : t;
    }
}
