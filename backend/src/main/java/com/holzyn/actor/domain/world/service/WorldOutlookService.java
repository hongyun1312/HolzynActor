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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 潜在世界预测注入（P1 ③-2，world_outlook）：角色「脑内预演世界走向」。
 * <p><b>语义</b>：既有 {@code ForesightService} 是「如果我做 X 会怎样」的行动后果推演（行动级）；
 * 本服务补上「<b>如果我不做任何事，世界会怎样</b>」的世界演化预测（世界级）——角色决策多一层
 * 「世界演化预期」上下文（明天粮价/集市/势力/人心的可能走向），对齐 GLAM「利用宏观引擎预测能力」+
 * T5「agent 需显式世界模型才能超越上下文」。</p>
 * <p><b>形态（用户 Q1-Q5 确认）</b>：</p>
 * <ul>
 * <li><b>趋势摘要（程序化零 AI）</b> {@link #trendSummary}：对比最近 N 日
 * {@code actor_world_macro_snapshot}（daily tick）历史快照 → 粮价/民怨/势力影响力/议题强度
 * 环比方向文本（事实锚点，不凭空脑补）；</li>
 * <li><b>AI 预测段（缓存至次日）</b> {@link #outlookSegment}：程序化趋势摘要 + 当前宏观简报 →
 * AI 生成【未来一天世界走向】段（≤80 字预测）；<b>按游戏日缓存</b>（同日多次决策不重复调用 AI），
 * 世界时钟推进到下一游戏日才失效重算；AI 失败/超时/解析失败 → <b>fail-open</b> 回退趋势摘要
 * （非 null，零阻断）。</li>
 * </ul>
 * <p><b>三通道注入（用户 Q4）</b>：AI 深思（action_gen {{world_outlook}} 占位符，经
 * {@code PromptService.buildActionGenPrompt} 8 参重载）/ 决策模型 快判（情境文本首部拼程序化
 * 趋势摘要，零 AI）/ 前瞻推演（ForesightService 宏观段升级含【未来一天世界走向】）。</p>
 * <p><b>架构不变量</b>：含 AI 方法不带 {@code @Transactional}；依赖字段注入 required=false + @Lazy
 * 破 Bean 环（构造器仅仓库+ObjectMapper）；null=未装配[单测]按能力缺失降级（返回 null/趋势摘要，
 * 零影响）。</p>
 */
@Slf4j
@Service
public class WorldOutlookService {

    /** AI 预测场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_WORLD_OUTLOOK = "world_outlook";

    /** 快照 tick 类型：每日（趋势对比数据源）。 */
    private static final String TICK_DAILY = ActorWorldMacroSnapshot.TICK_DAILY;

    /** 趋势窗口（最近 N 个 daily 快照；含今日，实际对比=窗口内连续日环比）。 */
    private static final int TREND_WINDOW_DAYS = 3;

    /** AI 预测段最大长度（字符，超长截断防撑爆决策 Prompt）。 */
    private static final int OUTLOOK_MAX_LEN = 80;

    /** 趋势摘要最大长度（字符）。 */
    private static final int TREND_MAX_LEN = 200;

    /** 预测段装配标题（决策 Prompt 内可读标识）。 */
    private static final String OUTLOOK_HEADER = "【未来一天世界走向】";

    /** 总开关。 */
    @Value("${holzyn.actor.world-outlook.enabled:true}")
    private boolean enabled = true;

    /** 快照仓库（历史 daily 快照趋势对比）。 */
    private final ActorWorldMacroSnapshotRepository snapshotRepository;

    /** Jackson 序列化器（指标 JSON 解析）。 */
    private final ObjectMapper objectMapper;

    /** 宏观状态服务（当前宏观简报；null=未装配降级趋势/预测）。 */
    @Autowired(required = false)
    @Lazy
    private MacroStateService macroStateService;

    /** 世界时钟（游戏日缓存键；null=未装配降级为每次重算）。 */
    @Autowired(required = false)
    @Lazy
    private WorldClockService worldClockService;

    /** 模板渲染（AI 预测 prompt）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（world_outlook 属「世界视角」）：AI 预测请求前置 段1A【世界观】+ 段2D
     * 【当前世界动态】（`worldContextPrefix`，世界历时刻/季节/天气），预演贴合世界设定与
     * 当下世界；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** AI 网关（AI 预测）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 预测）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /** 缓存：projectId → 已生成的预测段（含生成时的游戏日，跨日失效）。 */
    private final Map<Long, CachedOutlook> CACHE = new ConcurrentHashMap<>();

    /**
     * 构造器（仓库+序列化器全量构造注入；服务字段注入保持构造器不变）。
     */
    public WorldOutlookService(ActorWorldMacroSnapshotRepository snapshotRepository, ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 程序化趋势摘要（零 AI） ====================

    /**
     * 程序化趋势摘要：对比最近 N 日 daily 快照，计算粮价/民怨/势力影响力/议题强度环比方向
     * （事实锚点文本，零 AI）。无快照/数据不足/异常 → null（调用方省略段，零阻断）。
     *
     * @param projectId 项目 ID
     *
     * @return 趋势摘要文本（如「粮价连涨 3 日（76→82→88）；民怨攀升（45→48→52）；
     *         沈氏宗族影响力上升」）；无数据/失败返回 null
     */
    public String trendSummary(Long projectId) {
        if (!enabled || projectId == null) {
            return null;
        }
        try {
            List<MacroState> recent = recentDailyStates(projectId, TREND_WINDOW_DAYS);
            if (recent.size() < 2) {
                return null; // 数据不足（<2 日），无从对比趋势
            }
            return abbreviate(buildTrendText(recent), TREND_MAX_LEN);
        } catch (Exception e) {
            log.warn("[世界走向] 趋势摘要计算失败（省略趋势段）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * 最近 N 个 daily 快照状态（从旧到新排列；解析 indicators_json 为 MacroState）。
     * 快照缺失/解析失败的行跳过；返回不足 N 时按实际。
     */
    private List<MacroState> recentDailyStates(Long projectId, int n) {
        List<ActorWorldMacroSnapshot> rows = snapshotRepository.findByProjectIdOrderByGameDayDescTickTypeAsc(projectId);
        List<ActorWorldMacroSnapshot> daily = new ArrayList<>();
        for (ActorWorldMacroSnapshot r : rows) {
            if (TICK_DAILY.equals(r.getTickType())) {
                daily.add(r);
                if (daily.size() >= n) {
                    break;
                }
            }
        }
        // 从旧到新排列（趋势对比基准：latest vs 前一日）
        java.util.Collections.reverse(daily);
        List<MacroState> out = new ArrayList<>();
        for (ActorWorldMacroSnapshot r : daily) {
            MacroState st = parseState(r);
            if (st != null) {
                out.add(st);
            }
        }
        return out;
    }

    /** 快照行 → MacroState（indicators_json = MacroState 完整 JSON；解析失败返回 null）。 */
    private MacroState parseState(ActorWorldMacroSnapshot r) {
        if (r == null || r.getIndicatorsJson() == null || r.getIndicatorsJson().isBlank()) {
            return null;
        }
        try {
            if (macroStateService != null) {
                return macroStateService.fromJson(r.getIndicatorsJson());
            }
            return objectMapper.readValue(r.getIndicatorsJson(), MacroState.class);
        } catch (Exception e) {
            log.debug("[世界走向] 快照解析失败（跳过该行）: id={} : {}", r.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 趋势文本：逐日环比 粮价（升/降/平+幅度）/ 民怨 / 势力影响力 top / 议题强度 top。
     * 至少 2 日才有对比；逐段追加「（旧→新）」。
     */
    private String buildTrendText(List<MacroState> states) {
        StringBuilder sb = new StringBuilder();
        sb.append("近 ").append(states.size()).append(" 日趋势：");
        // 粮价
        String price = priceTrend(states);
        if (price != null) {
            sb.append(price);
        }
        // 民怨
        String grieve = grieveTrend(states);
        if (grieve != null) {
            appendJoin(sb, grieve);
        }
        // 势力影响力 top
        String faction = factionTrend(states);
        if (faction != null) {
            appendJoin(sb, faction);
        }
        // 议题强度 top
        String issue = issueTrend(states);
        if (issue != null) {
            appendJoin(sb, issue);
        }
        return sb.toString();
    }

    /** 追加分段（逗号分隔；空段跳过）。 */
    private static void appendJoin(StringBuilder sb, String segment) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '：') {
            sb.append("；");
        }
        sb.append(segment);
    }

    /** 粮价趋势（升/降/平 + 首末值）。 */
    private static String priceTrend(List<MacroState> states) {
        MacroState first = states.get(0);
        MacroState last = states.get(states.size() - 1);
        double a = first.getEconomy() == null ? 0 : first.getEconomy().getGrainPrice();
        double b = last.getEconomy() == null ? 0 : last.getEconomy().getGrainPrice();
        String dir = dir(a, b);
        if (dir == null) {
            return "粮价平稳（" + fmt(a) + "）";
        }
        return "粮价" + dir + "（" + fmt(a) + "→" + fmt(b) + "）";
    }

    /** 民怨趋势。 */
    private static String grieveTrend(List<MacroState> states) {
        Double a = states.get(0).getGrievance();
        Double b = states.get(states.size() - 1).getGrievance();
        if (a == null || b == null) {
            return null;
        }
        String dir = dir(a, b);
        if (dir == null) {
            return "民怨平稳（" + Math.round(a) + "）";
        }
        return "民怨" + dir + "（" + Math.round(a) + "→" + Math.round(b) + "）";
    }

    /** 势力影响力 top 趋势。 */
    private static String factionTrend(List<MacroState> states) {
        MacroState.Faction a = topFaction(states.get(0));
        MacroState.Faction b = topFaction(states.get(states.size() - 1));
        if (a == null || b == null) {
            return null;
        }
        String name = b.getName();
        String dir = dir(a.getInfluence(), b.getInfluence());
        if (dir == null) {
            return "「" + name + "」影响力平稳（" + Math.round(a.getInfluence()) + "）";
        }
        return "「" + name + "」影响力" + dir + "（" + Math.round(a.getInfluence())
                + "→" + Math.round(b.getInfluence()) + "）";
    }

    /** 议题强度 top 趋势。 */
    private static String issueTrend(List<MacroState> states) {
        MacroState.Issue a = topIssue(states.get(0));
        MacroState.Issue b = topIssue(states.get(states.size() - 1));
        if (a == null || b == null) {
            return null;
        }
        String topic = b.getTopic();
        String dir = dir(a.getIntensity(), b.getIntensity());
        if (dir == null) {
            return "议题「" + topic + "」热度平稳";
        }
        return "议题「" + topic + "」热度" + dir;
    }

    /** 势力影响力最高者。 */
    private static MacroState.Faction topFaction(MacroState st) {
        if (st == null || st.getFactions() == null || st.getFactions().isEmpty()) {
            return null;
        }
        return st.getFactions().values().stream()
                .max(java.util.Comparator.comparingDouble(MacroState.Faction::getInfluence))
                .orElse(null);
    }

    /** 议题强度最高者。 */
    private static MacroState.Issue topIssue(MacroState st) {
        if (st == null || st.getIssues() == null || st.getIssues().isEmpty()) {
            return null;
        }
        return st.getIssues().stream()
                .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity))
                .orElse(null);
    }

    /** 方向判定（b 相对 a）：升/降/持平（阈值 1e-9 内视为平）。 */
    private static String dir(double a, double b) {
        double delta = b - a;
        if (Math.abs(delta) <= 1e-9) {
            return null; // 平稳
        }
        return delta > 0 ? "上升" : "下降";
    }

    /** 数值保留 2 位小数。 */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ==================== AI 预测段（缓存至次日，fail-open） ====================

    /**
     * 【未来一天世界走向】段：程序化趋势摘要 + 当前宏观简报 → AI 生成 ≤80 字预测。
     * <p><b>缓存</b>：按游戏日缓存（同日多次决策复用，不重复调用 AI）；世界时钟推进到
     * 下一游戏日才失效重算。</p>
     * <p><b>fail-open</b>：开关关/未装配/AI 失败/超时/解析失败/趋势不可用 → 回退趋势摘要
     * （非 null 保证调用方有段可用）；全无 → null（省略段零阻断）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 【未来一天世界走向】段（含标题）；不可用返回 null
     */
    public String outlookSegment(Long projectId) {
        if (!enabled || projectId == null) {
            return null;
        }
        try {
            Long day = gameDay(projectId);
            CachedOutlook cached = day == null ? null : CACHE.get(projectId);
            if (cached != null && day.equals(cached.gameDay())) {
                return cached.segment(); // 同日复用
            }
            String segment = buildSegment(projectId);
            if (day != null && segment != null) {
                CACHE.put(projectId, new CachedOutlook(day, segment));
            }
            return segment;
        } catch (Exception e) {
            log.warn("[世界走向] 预测段生成异常（fail-open 省略）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 当前游戏日（世界时钟不可用返回 null——不缓存，每次重算）。 */
    private Long gameDay(Long projectId) {
        try {
            if (worldClockService == null) {
                return null;
            }
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            return sec == null ? null : Math.floorDiv(sec, 86400L);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成预测段：趋势摘要 + 当前宏观简报 → AI 预测（scene=world_outlook）。
     * AI 失败/未装配 → 回退趋势摘要（fail-open）。
     */
    private String buildSegment(Long projectId) {
        String trend = trendSummary(projectId);
        String macro = currentMacroSummary(projectId);
        if (trend == null && macro == null) {
            return null; // 无任何事实输入，无从预测
        }
        String prediction = aiOutlook(projectId, trend, macro);
        if (prediction == null || prediction.isBlank()) {
            // fail-open：AI 失败 → 回退程序化趋势摘要（仍有事实锚点）
            log.debug("[世界走向] AI 预测失败，回退趋势摘要: project={}", projectId);
            prediction = trend == null || trend.isBlank() ? macro : trend;
        }
        if (prediction == null || prediction.isBlank()) {
            return null;
        }
        return OUTLOOK_HEADER + abbreviate(prediction.trim(), OUTLOOK_MAX_LEN);
    }

    /** 当前宏观简报（buildSummary daily；失败/未装配返回 null）。 */
    private String currentMacroSummary(Long projectId) {
        try {
            if (macroStateService == null) {
                return null;
            }
            MacroState st = macroStateService.snapshotOf(projectId);
            if (st == null) {
                return null;
            }
            String s = macroStateService.buildSummary(st, TICK_DAILY);
            return s == null || s.isBlank() ? null : abbreviate(s, 200);
        } catch (Exception e) {
            log.debug("[世界走向] 宏观简报获取失败（省略）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AI 预测：scene=world_outlook，输入=趋势摘要+当前宏观简报 → 输出 ≤80 字一句话预测。
     * 失败/超时/异常 → null（fail-open 回退趋势）。
     */
    private String aiOutlook(Long projectId, String trend, String macro) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null;
        }
        try {
            Long userId = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            Map<String, Object> placeholders = new LinkedHashMap<>();
            placeholders.put("trend_summary", trend == null || trend.isBlank() ? "（无）" : trend);
            placeholders.put("macro_summary", macro == null || macro.isBlank() ? "（无）" : macro);
            String prompt = promptTemplateService.render(userId, projectId, SCENE_WORLD_OUTLOOK, placeholders);
            // 批6 收口（world_outlook 属「世界视角」）：前置 段1A【世界观】+ 段2D【当前世界动态】——
            // 预演「明天世界会怎样」先知道世界的基本设定与此刻世界历时刻/季节/天气；未装配/无数据 → 原样（fail-open）
            String prefix = aiContextInjector == null ? "" : aiContextInjector.worldContextPrefix(projectId, null);
            String finalPrompt = prefix.isBlank() ? prompt : prefix + "\n\n" + prompt;
            String system = promptTemplateService.systemMessage(userId, projectId, SCENE_WORLD_OUTLOOK, null);
            if (system == null || system.isBlank()) {
                system = "你只输出一句不超过 80 字的世界走向预判，不输出任何其他文字。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", finalPrompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_WORLD_OUTLOOK),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_WORLD_OUTLOOK),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_WORLD_OUTLOOK, null, 1,
                    () -> aiChatGateway.chatCompletion(userId, projectId, null, req));
            try {
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                        SCENE_WORLD_OUTLOOK, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.debug("[世界走向] 用量入账失败（忽略）: {}", e.getMessage());
            }
            String content = result.content();
            return content == null || content.isBlank() ? null : content.trim();
        } catch (Exception e) {
            log.debug("[世界走向] AI 预测失败（fail-open）: {}", e.getMessage());
            return null;
        }
    }

    /** 清空缓存（冒烟/调试用；测试断言缓存失效也可用）。 */
    public void clearCache() {
        CACHE.clear();
    }

    /** 字符串截断。 */
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

    /** 缓存条目（生成时的游戏日 + 预测段文本）。 */
    private record CachedOutlook(Long gameDay, String segment) {
    }
}
