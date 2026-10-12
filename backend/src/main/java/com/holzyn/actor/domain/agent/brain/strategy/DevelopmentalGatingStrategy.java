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
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.service.CrowdDecisionParse;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 决策策略链·发育阶段门控（O6 发育演化，priority=90）。
 * <p><b>语义</b>：受限阶段角色（TODDLER/CHILD/ADOLESCENT/ELDER/ANCIENT）的
 * <b>空闲决策</b>由本策略接管——按「阶段 × 时段」从候选池
 * （{@link StageBehaviorTemplates}，dormant LOD 纯程序化语义）产出阶段合理行为：
 * 孩子去做孩子该做的事（玩耍/读书/帮家里打下手），老人去做老人该做的事
 * （遛弯/晒太阳/看顾孙辈），而不是被 AI 写成「思考家国大事」或护栏拦截后
 * continue 发呆。</p>
 * <p><b>刺激让路（Q2 确认）</b>：有外部刺激时（特殊链 {@code situation} 非空 /
 * 普通链 {@code environmentText} 非空）本策略不命中、链继续——孩子被母亲叫
 * 吃饭要响应、被雷雨惊到要跑回家，但平时按习惯活（与真人一致）。
 * 生理危险（priority=100）恒优先：快饿死的孩子同样先去吃饭。</p>
 * <p><b>AI 润色（Q7 确认）</b>：候选池先确定性选出行为骨架，命中概率
 * （特殊 40% / 普通 20%，可配）时经 scene=growth_stage_behavior 把骨架展开为
 * 带个人化细节的行动文本（同一骨架不同的人/不同的时候说出来的不一样）；
 * AI 失败/空白/超长/<b>与硬门控禁止项冲突</b>（space_compliance 同口径）→
 * 回退骨架原文，零阻断。产出仍走调用方后处理（三护栏双保险）。</p>
 * <p><b>双链接入（Q4 确认）</b>：SPECIAL（ReAct 规范 Map）与 COMMON
 * （CrowdAction Map）共用本策略；INFANT 由链外婴儿状态机接管（不适用），
 * 成年阶段（YOUTH~MIDDLE）能力全开放（不适用）。</p>
 * <p><b>架构不变量</b>：依赖全部字段注入 {@code @Autowired(required=false)}+{@code @Lazy}
 * （策略被 Router 注入、Router 被决策服务注入，任何服务依赖都构成潜在 Bean 环，
 * 一律延迟解析；null=未装配[单测]按对应能力缺失降级）。</p>
 */
@Slf4j
@Component
public class DevelopmentalGatingStrategy implements BrainStrategy {

    /** AI 润色场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_STAGE_BEHAVIOR = "growth_stage_behavior";

    /** 润色文本长度上限（字符；超限回退骨架——阶段行为是一句话，不是一段话）。 */
    private static final int POLISH_MAX_CHARS = 100;

    /** 候选池随机源（测试可经 ReflectionTestUtils 覆盖固定 seed 验证确定性）。 */
    private final Random random = new Random();

    /** 生命周期配置（阶段门控开关/润色概率；null=未装配跳过）。 */
    @Autowired(required = false)
    @Lazy
    private GrowthProperties growthProperties;

    /** 阶段硬门控（唯一策略源：AI 润色产物的越界预检）。 */
    @Autowired(required = false)
    @Lazy
    private GrowthGateService growthGateService;

    /** 世界时钟（特殊链 gameSecond 兜底：ctx.gameSecond() 为 null 时读引擎位置）。 */
    @Autowired(required = false)
    @Lazy
    private WorldClockService worldClockService;

    /** Prompt 渲染（AI 润色）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /** AI 网关（AI 润色；真实网关/测试 mock）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 润色）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 润色记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /**
     * 统一 AI 上下文注入器（阶段行为润色属「角色视角」生成——AI 以该角色视角把行为骨架
     * 展开为个人化行动，前置世界观简报 + 角色核心卡；null=未装配跳过注入，不影响原调用）
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    @Override
    public String name() {
        return "stage_gating";
    }

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        if (ctx == null || ctx.character() == null || ctx.projectId() == null) {
            return false;
        }
        if (growthProperties != null && !growthProperties.isEnabled()) {
            return false;
        }
        return StageBehaviorTemplates.isRestricted(GrowthStage.of(ctx.character().getStage()));
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        try {
            ActorCharacter ch = ctx.character();
            GrowthStage stage = GrowthStage.of(ch.getStage());
            if (stage == null || !StageBehaviorTemplates.isRestricted(stage)) {
                return Optional.empty();
            }
            // ① 刺激让路：有外部刺激 → 链继续。刺激判定走触发源语义——
            // 特殊链：ctx.hasStimulus()=true（STIMULUS 即时通道；引擎主决策即使带感知情境
            // 文本也传 false——感知不是刺激，孩子看到世界≠被刺激）；
            // 普通链：environmentText 非空（环境刺激变体）。
            if (NpcKind.SPECIAL == ctx.kind() && Boolean.TRUE.equals(ctx.hasStimulus())) {
                return Optional.empty();
            }
            if (NpcKind.COMMON == ctx.kind() && ctx.environmentText() != null
                    && !ctx.environmentText().isBlank()) {
                return Optional.empty();
            }
            // ② 时段 + 候选池骨架（0 AI）
            int hour = hourOfDay(ctx);
            StageBehaviorTemplates.BehaviorOption base = StageBehaviorTemplates.pick(stage, hour, random);
            if (base == null) {
                return Optional.empty();
            }
            String text = base.text();
            boolean polished = false;
            // ③ AI 润色（概率命中；失败/越界回退骨架，零阻断）
            double rate = polishRate(ctx.kind());
            if (rate > 0 && random.nextDouble() < rate) {
                String polishedText = polish(ctx, stage, hour, base);
                if (polishedText != null) {
                    text = polishedText;
                    polished = true;
                }
            }
            String reason = base.reason() + (polished ? "，AI 润色细节" : "，程序化骨架（0 AI）");
            // ④ 产出（双链契约；trace 记策略命中来源与骨架/润色形态）
            AiCallContext.recordStep(ctx.projectId(), "agent_decision", "brain_strategy",
                    "发育阶段行为（" + stage.label() + "×" + StageBehaviorTemplates.periodLabel(
                            StageBehaviorTemplates.periodOf(hour)) + "）「"
                            + abbreviate(text, 40) + "」" + (polished ? "（AI 润色）" : "（程序化骨架）"),
                    0);
            log.info("[BrainStrategy] stage_gating 命中: ref={} stage={} 时段={} 润色={} 行动={}",
                    ch.npcRef(), stage.name(), StageBehaviorTemplates.periodOf(hour), polished, text);
            if (NpcKind.COMMON == ctx.kind()) {
                return Optional.of(CrowdActionCodec.toMap(new CrowdDecisionParse.CrowdAction(
                        text, base.actionType(), base.target(), reason)));
            }
            return Optional.of(specialDecisionMap(text, reason));
        } catch (Exception e) {
            log.warn("[BrainStrategy] stage_gating 失败（链继续）: ref={} : {}",
                    ctx == null || ctx.character() == null ? "?" : ctx.character().npcRef(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 特殊链 ReAct 规范决策 Map（对齐 {@code DecisionReActParse.parse} 键集）。 */
    private Map<String, Object> specialDecisionMap(String text, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionType", "start_action");
        m.put("actionDetail", text);
        m.put("reasoning", reason);
        m.put("priority", 2);
        m.put("expectedDurationMinutes", 30);
        m.put("target", "");
        // —— 映射旧键（下游执行链兼容；type 推断对齐 DecisionReActParse.legacyType） ——
        m.put("type", legacyTypeOf(text));
        m.put("action", text);
        m.put("reason", reason);
        m.put("urgency", 2);
        m.put("duration", 30);
        m.put("params", Map.of());
        return m;
    }

    /** action_detail → 旧 type 枚举推断（与 {@code DecisionReActParse.legacyType} 同口径）。 */
    private static String legacyTypeOf(String detail) {
        String d = detail == null ? "" : detail;
        if (d.contains("前往") || d.contains("去") || d.contains("到") || d.contains("赴")
                || d.contains("回到") || d.contains("赶往")) {
            return "move";
        }
        if (d.contains("找") || d.contains("见") || d.contains("聊") || d.contains("问")
                || d.contains("商量")) {
            return "speak";
        }
        if (d.contains("吃") || d.contains("喝") || d.contains("用膳") || d.contains("进食")) {
            return "rest";
        }
        return "custom";
    }

    /** 时段判定用小时（ctx.gameSecond 优先；null → 引擎时钟兜底；再失败 → 白昼宽松）。 */
    private int hourOfDay(DecisionContext ctx) {
        Long gs = ctx.gameSecond();
        if (gs == null && worldClockService != null) {
            try {
                gs = worldClockService.currentEngineGameSecond(ctx.projectId());
            } catch (Exception e) {
                log.debug("[BrainStrategy] stage_gating 引擎时刻读取失败（按白天宽松）: {}", e.getMessage());
            }
        }
        if (gs == null) {
            return 12;
        }
        return (int) ((gs / 3600L) % 24L);
    }

    /** AI 润色概率（特殊 40% / 普通 20%；配置可调）。 */
    private double polishRate(NpcKind kind) {
        if (growthProperties == null) {
            return 0;
        }
        if (NpcKind.COMMON == kind) {
            return growthProperties.getStageGating().getPolishRateCommon();
        }
        return growthProperties.getStageGating().getPolishRateSpecial();
    }

    /**
     * AI 润色（scene=growth_stage_behavior）：把行为骨架展开为带个人化细节的
     * 一句话行动。失败/空白/超长/与硬门控禁止项冲突 → null（调用方回退骨架）。
     */
    private String polish(DecisionContext ctx, GrowthStage stage, int hour,
                          StageBehaviorTemplates.BehaviorOption base) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null;
        }
        try {
            Long uid = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            ActorCharacter ch = ctx.character();
            String location = ch.getLocation() == null || ch.getLocation().isBlank() ? "家中" : ch.getLocation();
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("name", ch.getName() == null || ch.getName().isBlank() ? "这个孩子" : ch.getName());
            ph.put("age", ch.getAge() == null ? "?" : ch.getAge());
            ph.put("stage_label", stage.label());
            ph.put("stage_summary", stage.summary());
            ph.put("period", StageBehaviorTemplates.periodLabel(StageBehaviorTemplates.periodOf(hour)));
            ph.put("location", location);
            ph.put("base_action", base.text());
            String prompt = promptTemplateService.render(uid, ctx.projectId(), SCENE_STAGE_BEHAVIOR, ph);
            // 上下文注入（2026-10-07）：阶段行为润色属「角色视角」生成——AI 以该角色视角
            // 把行为骨架展开为个人化行动，前置世界观简报 + 角色核心卡（未装配/失败零阻断）
            // 批5 世界与叙事（2026-10-10）：升级为 V2.0 段1A+1C+2D+2E（memoryChainContext：
            // compose + decisionStatePrefix）——润色者知道此刻时节天气与该角色当下状态，
            // 行为更贴合真人处境
            String injected = aiContextInjector == null ? ""
                    : aiContextInjector.memoryChainContext(ctx.projectId(), ch.npcRef(), ctx.gameSecond());
            String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
            String system = promptTemplateService.systemMessage(uid, ctx.projectId(), SCENE_STAGE_BEHAVIOR, null);
            if (system == null || system.isBlank()) {
                system = "你只输出一句话行动描述，不输出任何其他文字。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_STAGE_BEHAVIOR),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_STAGE_BEHAVIOR),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_STAGE_BEHAVIOR, ch.getId(), 1,
                    () -> aiChatGateway.chatCompletion(uid, ctx.projectId(), null, req));
            try {
                usageLogService.record(uid, ctx.projectId(), ch.getId(), result.providerId(), result.model(),
                        SCENE_STAGE_BEHAVIOR, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.debug("[BrainStrategy] stage_gating 润色用量入账失败（忽略）: {}", e.getMessage());
            }
            String text = result.content() == null ? null : result.content().trim();
            if (text == null || text.isBlank() || text.length() > POLISH_MAX_CHARS) {
                return null;
            }
            // 越界预检（与护栏 space_compliance 同口径：与硬门控禁止项分段包含冲突 → 回退）
            if (growthGateService != null && conflictsWithForbidden(
                    growthGateService.gate(ctx.projectId(), ch.npcRef()), text)) {
                log.info("[BrainStrategy] stage_gating 润色越界回退骨架: ref={} 文本={}", ch.npcRef(), text);
                return null;
            }
            return text;
        } catch (Exception e) {
            log.warn("[BrainStrategy] stage_gating 润色失败（回退骨架）: ref={} : {}",
                    ctx.character().npcRef(), e.getMessage());
            return null;
        }
    }

    /**
     * 与硬门控禁止项冲突判定（策略内 AI 润色预检）。
     * <p>比护栏 space_compliance 更强：除原文分段包含匹配外，还剥离禁止项前导否定词
     * （不能/不得/禁止…）后匹配核心短语——AI 润色文本是行动描述，不会带「不能」前缀，
     * 原文匹配对「不能独自远行」这类约束几乎必然漏检。两通道任一命中即冲突。</p>
     */
    private static boolean conflictsWithForbidden(HardGateResult gate, String action) {
        if (gate == null || gate.forbidden() == null || action == null) {
            return false;
        }
        for (HardGateResult.ForbiddenItem f : gate.forbidden()) {
            if (f.what() == null || f.what().isBlank()) {
                continue;
            }
            for (String seg : f.what().split("[、，,;；。]")) {
                String s = seg.trim();
                if (s.length() < 2) {
                    continue;
                }
                if (action.contains(s)) {
                    return true;
                }
                String core = stripProhibition(s);
                if (core != null && core.length() >= 2 && action.contains(core)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 剥离前导否定词（「不能独自远行」→「独自远行」；非否定开头返回 null）。 */
    private static String stripProhibition(String s) {
        for (String p : PROHIBITION_PREFIXES) {
            if (s.startsWith(p)) {
                return s.substring(p.length()).trim();
            }
        }
        return null;
    }

    /** 禁止项前导否定词表（约束表原文口径）。 */
    private static final String[] PROHIBITION_PREFIXES = {
            "不能", "不得", "禁止", "不可", "不许", "不要", "不应", "勿"
    };

    /** 字符串截断（摘要展示用）。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
