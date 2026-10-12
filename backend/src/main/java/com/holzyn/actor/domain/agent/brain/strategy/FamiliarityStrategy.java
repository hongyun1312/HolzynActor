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
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 决策策略链·MUSE 式策略切换（FamiliarityStrategy，P1 ①-3）。
 * <p><b>语义（MUSE 元认知「能力感知→策略选择」）</b>：角色<b>持续评估自己面对当前情境的能力水平，
 * 并据此选择策略</b>——情境陌生/自认没把握 → <b>保守策略偏好</b>；拿手领域 + 见多识广 + 高自信 →
 * <b>激进策略偏好</b>。MUSE 框架实证：陌生、分布外任务上按能力切换策略的 agent 显著优于
 * 纯 prompt 方案。对齐参考文档 §2.1（c）：「情境陌生度 → 保守偏好；多次成功经验 → 激进」。</p>
 * <p><b>实现形态（用户 Q1-Q6 确认）</b>：<b>调制器策略</b>——{@link #decide} 只计算策略档位并把
 * 【策略偏好】段经 {@link AiCallContext#holdStrategyPreference} 持有，<b>恒返回空（永不产出决策，
 * 链继续）</b>；priority=85（介于 Deliberate[60] 与 PhysioDanger[100] 之间，且先于
 * SpaceChoice[80]/Deliberate[60]）保证 SpaceChoice（NanoJev 快判）与 Deliberate（AI 深思）
 * 双通道都能读到偏好段——AI 仍是决策者，只是被「保守/激进」引导（真实的人面对新事是
 * 「谨慎地想」而非「按脚本退避」，保留 AI 创造力）。</p>
 * <p><b>熟悉度三源（用户 Q3 确认，全程序化 0 额外 AI 兜底）</b>：
 * ① <b>情境领域识别</b>：情境文本 × 角色领域名（selfEfficacy ∪ skills）三级匹配（直接包含/
 * 二元组/主题关键词）——判定「是不是我熟的行当」；
 * ② <b>经历卷</b>：episodic 记忆条数 + actor_event 参与数 → 低/中/高（<5 / 5-19 / ≥20）；
 * ③ <b>自评信心</b>：selfEfficacy[领域]（1-5，<b>自评卡优先</b>）；无自评回退兄弟领域自评
 * （同主题）→ 客观 skills 折算（/2 取整）；仍无 → 按保守（无把握）。</p>
 * <p><b>档位规则 + AI 复核（用户 Q4 确认）</b>：确定性规则先出<b>初始档位</b>（领域未识别→保守；
 * 领域识别但信心 ≤2 → 保守；信心 ≥4 且经历卷高 → 激进；其余 → 均衡）；初始档位非均衡时
 * <b>概率抽样</b>（review-sample-rate 默认 0.5）交 AI 复核（scene={@code muse_strategy_check}，
 * 输出 {"posture":"conservative|balanced|aggressive","reason"}）——AI 可维持/调整；
 * AI 失败/超时/解析失败 → <b>fail-open 回退初始档位</b>（白盒保底，零阻断）。</p>
 * <p><b>注入（用户 Q5 确认，holder 双通道）</b>：最终档位非均衡时组装【策略偏好】段 →
 * {@link AiCallContext#holdStrategyPreference}（action_gen {{strategy_preference}} 占位符 /
 * NanoJev 快判情境文本首部，对齐主动记忆检索先例）；均衡 → 不注入（行为零变化），trace 留痕。
 * <b>例行决策不门控 hasStimulus</b>：每次 SPECIAL 非 programmatic 决策都评估（冻结世界纪律下
 * 冒烟走 MANUAL 触发同样生效）；例行感知情境同样参与领域识别，陌生→保守/熟悉+自信→激进。</p>
 * <p><b>架构不变量</b>：含 AI 调用（档位复核），公开方法<b>一律不带 {@code @Transactional}</b>；
 * 依赖全部字段注入 {@code @Autowired(required=false)}+{@code @Lazy} 破 Bean 环；null=未装配
 * [单测]按能力缺失降级（跳过评估零影响）。</p>
 */
@Slf4j
@Component
public class FamiliarityStrategy implements BrainStrategy {

    /** 策略名（trace/日志标识）。 */
    public static final String NAME = "muse_familiarity";

    /** AI 档位复核场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_MUSE_STRATEGY_CHECK = "muse_strategy_check";

    /** 决策链路 trace 环节类型（stepType=muse_strategy，审计/复盘依据）。 */
    public static final String STEP_TYPE_MUSE_STRATEGY = "muse_strategy";

    /** 情境文本截断上限（字符，防撑爆 AI 复核输入）。 */
    private static final int SITUATION_MAX = 300;

    /** 经历卷档位阈值：< LOW_LOW=低；LOW_LOW~HIGH_LOW-1=中；≥ HIGH_LOW=高（见多识广）。 */
    private static final int VOLUME_LOW = 5;
    private static final int VOLUME_HIGH = 20;

    /** 抽样随机源（测试可经 ReflectionTestUtils 覆盖固定 seed 验证确定性）。 */
    private final Random random = new Random();

    /** 总开关。 */
    @Value("${holzyn.actor.muse-strategy.enabled:true}")
    private boolean enabled = true;

    /** AI 档位复核抽样率（0~1；仅初始档位非均衡时抽；命中才调 AI）。 */
    @Value("${holzyn.actor.muse-strategy.review-sample-rate:0.5}")
    private double reviewSampleRate = 0.5;

    /** 档案服务（读 persona selfEfficacy/skills；null=未装配跳过）。 */
    @Autowired(required = false)
    @Lazy
    private PersonaService personaService;

    /** 长期记忆仓库（episodic 记忆计数=经历卷一部分；null=未装配跳过）。 */
    @Autowired(required = false)
    @Lazy
    private ActorMemoryRepository memoryRepository;

    /** 项目事件仓库（参与事件计数=经历卷一部分；null=未装配跳过）。 */
    @Autowired(required = false)
    @Lazy
    private ActorEventRepository eventRepository;

    /** Prompt 渲染（AI 档位复核）。 */
    @Autowired(required = false)
    @Lazy
    private PromptTemplateService promptTemplateService;

    /** AI 网关（AI 档位复核；真实网关/测试 mock）。 */
    @Autowired(required = false)
    @Lazy
    private AiSpringChatGateway aiChatGateway;

    /** 用量记账（AI 档位复核）。 */
    @Autowired(required = false)
    @Lazy
    private UsageLogService usageLogService;

    /** 当前用户（AI 档位复核记账归属）。 */
    @Autowired(required = false)
    @Lazy
    private CurrentUserProvider currentUserProvider;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（muse_strategy_check 属批4 记忆链·角色视角）：档位复核请求前置
     * 段1A【世界观】+段1C【角色核心卡】+段2D【当前世界动态】+段2E【当前状态】
     * （{@code memoryChainContext}）；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    @Lazy
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    // ==================== BrainStrategy（调制器：永不产出决策） ====================

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        // 介于 Deliberate(60) 与 PhysioDanger(100) 之间，且先于 SpaceChoice(80)/Deliberate(60)——
        // 保证双通道（NanoJev 快判 / AI 深思）都能读到本策略持有的【策略偏好】段。
        return 85;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        // 仅核心角色；programmatic 档不经 AI（0 AI 语义，与既有脑力门控一致）
        return ctx != null && NpcKind.SPECIAL == ctx.kind()
                && !"programmatic".equals(ctx.brainLevel());
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        // 调制器：只计算偏好并持有，恒不产出决策（链继续）
        if (!enabled || ctx == null || ctx.character() == null || ctx.projectId() == null) {
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();
        try {
            Long charId = ctx.character().getId();
            String situation = ctx.situation() == null ? "" : ctx.situation().trim();
            if (situation.isBlank()) {
                return Optional.empty(); // 无情境无从评估
            }
            PersonaDims.Ability ab = readAbility(charId);
            Map<String, Integer> se = ab == null || ab.getSelfEfficacy() == null
                    ? Map.of() : ab.getSelfEfficacy();
            Map<String, Double> skills = ab == null || ab.getSkills() == null
                    ? Map.of() : ab.getSkills();
            if (se.isEmpty() && skills.isEmpty()) {
                return Optional.empty(); // 无任何能力领域可识别：无从判断熟悉度
            }
            // ① 领域识别（情境文本 × 领域名：直接包含/二元组/主题关键词）
            DomainMatch match = recognizeDomain(situation, unionKeys(se, skills));
            // ② 经历卷（episodic 记忆 + 事件参与；可观测白盒）
            int volume = experienceVolume(ctx, charId);
            ExperienceLevel level = ExperienceLevel.of(volume);
            // ③ 初始档位（确定性规则：陌生→保守；低信心→保守；高信心+见多识广→激进）
            Posture initial = initialPosture(match, se, skills, level);
            // ④ 概率抽样 AI 复核（仅初始档位非均衡；失败回退初始——fail-open 白盒保底）
            Posture finalPosture = initial;
            if (initial != Posture.BALANCED && random.nextDouble() < reviewSampleRate) {
                Posture reviewed = review(ctx, charId, situation, match, se, level, initial);
                if (reviewed != null) {
                    finalPosture = reviewed;
                }
            }
            // ⑤ trace（初始/最终档位 + 依据，审计/复盘）
            recordTrace(ctx, match, se, skills, volume, level, initial, finalPosture,
                    System.currentTimeMillis() - startMs);
            // ⑥ 最终档位非均衡 → 组装【策略偏好】段 → holder（SpaceChoice/Deliberate 双通道读取）
            if (finalPosture != Posture.BALANCED) {
                String segment = buildSegment(ctx.character(), finalPosture, match, se, skills, level);
                if (segment != null && !segment.isBlank()) {
                    AiCallContext.holdStrategyPreference(segment);
                    log.info("[MUSE 策略切换] 注入【策略偏好】: ref={} 档位={} 领域={}",
                            ctx.character().npcRef(), finalPosture.label,
                            match == null ? "未识别（陌生）" : match.domain());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            // 调制器异常隔离：绝不干扰决策主流程
            log.warn("[MUSE 策略切换] 评估失败（跳过，零阻断）: ref={} : {}",
                    ctx.character().npcRef(), e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 纯逻辑（可单测） ====================

    /** 策略档位（保守/均衡/激进）。 */
    public enum Posture {
        CONSERVATIVE("保守"),
        BALANCED("均衡"),
        AGGRESSIVE("激进");

        /** 中文标签（trace/段文案用）。 */
        public final String label;

        Posture(String label) {
            this.label = label;
        }

        /** 按输出值解析（conservative/balanced/aggressive）；非法返回 null（fail-open 回退）。 */
        public static Posture fromValue(String v) {
            if (v == null) {
                return null;
            }
            return switch (v.trim().toLowerCase()) {
                case "conservative" -> CONSERVATIVE;
                case "balanced" -> BALANCED;
                case "aggressive" -> AGGRESSIVE;
                default -> null;
            };
        }
    }

    /** 经历卷档位（<5=低 / 5-19=中 / ≥20=高）。 */
    public enum ExperienceLevel {
        LOW("尚浅"),
        MEDIUM("一般"),
        HIGH("丰富");

        /** 中文标签（trace/段文案用）。 */
        public final String label;

        ExperienceLevel(String label) {
            this.label = label;
        }

        /** 按经历卷数值分档（白盒阈值）。 */
        public static ExperienceLevel of(int volume) {
            if (volume < VOLUME_LOW) {
                return LOW;
            }
            if (volume < VOLUME_HIGH) {
                return MEDIUM;
            }
            return HIGH;
        }
    }

    /**
     * 情境领域识别结果。
     *
     * @param domain 命中的领域名（selfEfficacy ∪ skills 之一）
     *
     * @param score 匹配分（直接包含 +4/二元组 +2/主题关键词 +1；分越高越像「本行」）
     */
    public record DomainMatch(String domain, int score) {
    }

    /**
     * 情境领域识别（三级匹配）：情境文本 vs 角色领域名——
     * ① 直接包含领域名（+4）② 领域名二元组（相邻两字）包含（+2）③ 领域主题关键词命中（+1）。
     * 取分最高者；全部 0 分 → null（陌生情境）。
     *
     * @param situation 当前情境文本
     *
     * @param domains 角色领域名集合（selfEfficacy ∪ skills 键；LinkedHashSet 保序=se 优先）
     *
     * @return 命中领域（null=未识别）
     */
    public static DomainMatch recognizeDomain(String situation, Set<String> domains) {
        if (situation == null || situation.isBlank() || domains == null || domains.isEmpty()) {
            return null;
        }
        String s = situation;
        DomainMatch best = null;
        for (String d : domains) {
            if (d == null || d.isBlank()) {
                continue;
            }
            int score = 0;
            if (s.contains(d)) {
                score += 4;
            }
            for (String g : bigrams(d)) {
                if (s.contains(g)) {
                    score += 2;
                }
            }
            for (String theme : themesOf(d)) {
                for (String k : THEMES.get(theme).keywords()) {
                    if (s.contains(k)) {
                        score += 1;
                    }
                }
            }
            if (score > 0 && (best == null || score > best.score())) {
                best = new DomainMatch(d, score);
            }
        }
        return best;
    }

    /**
     * 初始档位（确定性规则，MUSE「陌生→保守/熟悉+自信→激进」直接落代码）：
     * 领域未识别（陌生情境）→ 保守；领域识别但信心 ≤2 → 保守；信心 ≥4 且经历卷高 → 激进；
     * 其余（含信心 3、信心 ≥4 但经历卷不足、信心 2< c <4）→ 均衡。
     *
     * @param match 领域识别结果（null=陌生情境）
     *
     * @param se 能力自评卡（领域 → 1-5）
     *
     * @param skills 客观技能（领域 → 熟练度）
     *
     * @param level 经历卷档位
     *
     * @return 初始档位
     */
    public static Posture initialPosture(DomainMatch match, Map<String, Integer> se,
                                         Map<String, Double> skills, ExperienceLevel level) {
        if (match == null) {
            return Posture.CONSERVATIVE; // 陌生情境 → 保守（首次遭遇的未知，MUSE 核心）
        }
        Integer confidence = confidenceOf(match, se, skills);
        if (confidence == null) {
            return Posture.CONSERVATIVE; // 有领域但无任何能力依据 → 保守（无把握）
        }
        if (confidence <= 2) {
            return Posture.CONSERVATIVE; // 自认没把握 → 保守
        }
        if (confidence >= 4 && level == ExperienceLevel.HIGH) {
            return Posture.AGGRESSIVE; // 高信心 + 见多识广（多次成功经验）→ 激进
        }
        return Posture.BALANCED;
    }

    /**
     * 自评信心（1-5）：① selfEfficacy[命中领域] 优先（自评卡核心输入）；
     * ② 无 → 兄弟领域自评（与命中领域共享同一主题的最大值）；③ 无 → 客观 skills 折算
     * （/2 四舍五入钳制 1-5）；④ 全无 → null（按保守降级）。
     *
     * @param match 领域识别结果（null → null）
     *
     * @param se 能力自评卡
     *
     * @param skills 客观技能
     *
     * @return 自评信心（null=无任何能力依据）
     */
    public static Integer confidenceOf(DomainMatch match, Map<String, Integer> se,
                                       Map<String, Double> skills) {
        if (match == null || match.domain() == null) {
            return null;
        }
        Integer c = se.get(match.domain());
        if (c != null) {
            return c;
        }
        // 兄弟领域（同一主题）自评最大值
        if (se != null && !se.isEmpty()) {
            int max = 0;
            for (Map.Entry<String, Integer> e : se.entrySet()) {
                if (e.getKey() != null && shareTheme(e.getKey(), match.domain())
                        && e.getValue() != null && e.getValue() > max) {
                    max = e.getValue();
                }
            }
            if (max > 0) {
                return max;
            }
        }
        Double sk = skills == null ? null : skills.get(match.domain());
        if (sk != null) {
            return Math.max(1, Math.min(5, (int) Math.round(sk / 2.0)));
        }
        return null;
    }

    /**
     * 两领域是否共享主题（MUSE 领域识别辅助：跨技能名/自评名的主题归一——「农事估产」与
     * 「农事耕作」同属农事主题，自评可互用）。
     *
     * @param a 领域名
     *
     * @param b 领域名
     *
     * @return true=共享至少一个主题
     */
    public static boolean shareTheme(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        Set<String> ta = themesOf(a);
        if (ta.isEmpty()) {
            return false;
        }
        Set<String> tb = themesOf(b);
        for (String t : ta) {
            if (tb.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 组装【策略偏好】段（注入决策 prompt；均衡返回空串=不注入）。
     *
     * @param ch 角色实体（取姓名；可空）
     *
     * @param posture 最终档位（仅非均衡调用）
     *
     * @param match 领域识别结果
     *
     * @param se 能力自评卡
     *
     * @param skills 客观技能
     *
     * @param level 经历卷档位
     *
     * @return 段文本（均衡/空依据返回空串）
     */
    public static String buildSegment(ActorCharacter ch, Posture posture, DomainMatch match,
                                      Map<String, Integer> se, Map<String, Double> skills,
                                      ExperienceLevel level) {
        if (posture == null || posture == Posture.BALANCED) {
            return "";
        }
        String domain = match == null ? null : match.domain();
        Integer confidence = confidenceOf(match, se, skills);
        String cText = confidence == null ? "?/5" : confidence + "/5";
        String levelText = level == null ? "尚浅" : level.label;
        if (posture == Posture.CONSERVATIVE) {
            if (domain == null) {
                return "【策略偏好】眼前这件事你从没经历过（陌生情境、心里没底），更倾向谨慎行事："
                        + "先观察、多打听、多留退路，不冒进，除非有十足把握再出手。";
            }
            return "【策略偏好】面对「" + domain + "」这类事你自认把握不大（自评 " + cText
                    + "、经历" + levelText + "），更倾向谨慎行事：先观察、多留余地、不冒进，"
                    + "除非有十足把握再出手。";
        }
        // AGGRESSIVE
        return "【策略偏好】「" + domain + "」正是你的本行（自评 " + cText + "、经历" + levelText
                + "），你胸有成竹——认准了可以更果断大胆些，不必畏手畏脚。";
    }

    /**
     * 解析 AI 档位复核 JSON（{"posture":"conservative|balanced|aggressive","reason":str}）。
     * 失败/非法/缺失 → null（fail-open 回退初始档位）。
     */
    public static Posture parseReview(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            String json = JsonUtil.extractJson(content);
            if (json == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(json);
            if (node == null || !node.isObject() || !node.has("posture")) {
                return null;
            }
            return Posture.fromValue(node.path("posture").asText(null));
        } catch (Exception e) {
            return null;
        }
    }

    /** 字符串截断（摘要展示/输入收敛用）。 */
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

    // ==================== 主题词表（领域识别三级匹配的第三级） ====================

    /**
     * 领域主题（锚词判定领域归属，关键词匹配情境文本）。
     *
     * @param anchors 锚词：领域名包含任一锚词 → 该领域归入本主题（用于兄弟领域共享/信心互用）
     *
     * @param keywords 情境关键词：情境文本包含任一关键词 → 本主题命中（领域匹配计分）
     */
    public record Theme(List<String> anchors, List<String> keywords) {
    }

    /** 内置领域主题词表（MUSE 领域识别：中文领域名与情境散文间的词汇桥）。 */
    public static final Map<String, Theme> THEMES = Map.ofEntries(
            Map.entry("农", new Theme(List.of("农", "田", "耕", "种", "粮", "庄稼", "收成", "地"),
                    List.of("田", "庄稼", "收成", "粮", "农事", "耕种", "虫害", "麦", "稻", "秋收", "农活"))),
            Map.entry("商", new Theme(List.of("商", "账", "价", "铺", "市", "买卖", "货"),
                    List.of("商", "买卖", "价钱", "货物", "账", "铺子", "行商", "利钱", "讨价还价"))),
            Map.entry("医", new Theme(List.of("医", "药", "诊", "脉", "病"),
                    List.of("医", "药", "病", "伤", "脉", "诊治", "疫病", "郎中", "药方"))),
            Map.entry("武", new Theme(List.of("武", "拳", "刀", "剑", "镖"),
                    List.of("武", "拳脚", "刀剑", "打斗", "比武", "防身", "镖局"))),
            Map.entry("文", new Theme(List.of("书", "文", "字", "诗", "笔墨", "文章"),
                    List.of("书", "字", "诗", "文章", "字据", "契约", "笔墨", "写"))),
            Map.entry("工", new Theme(List.of("工", "匠", "手艺", "造", "修", "木", "铁"),
                    List.of("手艺", "工匠", "木匠", "铁匠", "修", "造", "织"))),
            Map.entry("理", new Theme(List.of("村务", "族", "规矩", "处置", "断", "管", "账册"),
                    List.of("村务", "族务", "族谱", "纠纷", "排解", "公断", "处置", "规矩", "账册"))),
            Map.entry("鉴", new Theme(List.of("鉴", "古", "铜", "瓷", "玉", "宝"),
                    List.of("古", "铜", "瓷", "玉", "器物", "鉴赏", "辨", "真伪"))),
            Map.entry("艺", new Theme(List.of("戏", "曲", "琴", "画", "舞", "乐"),
                    List.of("戏", "曲", "琴", "画", "舞", "乐"))),
            Map.entry("行", new Theme(List.of("路", "行", "远", "旅", "驿"),
                    List.of("远行", "赶路", "旅途", "驿", "行路"))));

    /**
     * 领域名 → 归属主题集合（锚词包含判定；用于兄弟领域共享/信心互用与主题关键词计分）。
     */
    public static Set<String> themesOf(String domain) {
        Set<String> out = new LinkedHashSet<>();
        if (domain == null || domain.isBlank()) {
            return out;
        }
        for (Map.Entry<String, Theme> e : THEMES.entrySet()) {
            for (String anchor : e.getValue().anchors()) {
                if (anchor != null && domain.contains(anchor)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    /** 相邻二元组（"农事耕作" → 农事/事耕/耕作；领域名二级匹配）。 */
    public static List<String> bigrams(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.length() < 2) {
            return out;
        }
        for (int i = 0; i + 2 <= s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    // ==================== 私有辅助 ====================

    /**
     * 读角色档案 ability（selfEfficacy/skills）；无档案/解析失败/未装配 → null（跳过评估）。
     */
    private PersonaDims.Ability readAbility(Long charId) {
        if (personaService == null || charId == null) {
            return null;
        }
        try {
            String json = personaService.latestPersonaJson(charId).orElse(null);
            if (json == null || json.isBlank()) {
                return null;
            }
            PersonaProfile profile = PersonaJson.readQuietly(json);
            if (profile == null || profile.getDims() == null) {
                return null;
            }
            return profile.getDims().getAbility();
        } catch (Exception e) {
            log.debug("[MUSE 策略切换] 档案读取失败（跳过评估）: char={} : {}", charId, e.getMessage());
            return null;
        }
    }

    /**
     * 经历卷：episodic 记忆条数 + actor_event 参与数（旧列 character_id ∪ 新列 character_ids）。
     * 仓库未装配/查询失败 → 该源计 0（零阻断）。
     */
    private int experienceVolume(DecisionContext ctx, Long charId) {
        int n = 0;
        if (memoryRepository != null) {
            try {
                n += (int) memoryRepository.countByProjectIdAndCharacterIdAndTypeAndDeleted(
                        ctx.projectId(), charId, ActorMemoryTypes.TYPE_EPISODIC, 0);
            } catch (Exception e) {
                log.debug("[MUSE 策略切换] 记忆计数失败（计 0）: {}", e.getMessage());
            }
        }
        if (eventRepository != null) {
            try {
                n += (int) eventRepository.countByProjectIdAndCharacterRef(
                        ctx.projectId(), charId, NpcRefs.of(charId));
            } catch (Exception e) {
                log.debug("[MUSE 策略切换] 事件计数失败（计 0）: {}", e.getMessage());
            }
        }
        return n;
    }

    /**
     * AI 档位复核（scene=muse_strategy_check）：输入=情境 + 领域/自评/经历卷 + 初始档位 →
     * 输出档位。失败/超时/解析失败 → null（fail-open 回退初始档位）。
     */
    private Posture review(DecisionContext ctx, Long charId, String situation, DomainMatch match,
                           Map<String, Integer> se, ExperienceLevel level, Posture initial) {
        if (promptTemplateService == null || aiChatGateway == null || usageLogService == null) {
            return null;
        }
        try {
            Long userId = currentUserProvider != null ? currentUserProvider.currentUserId() : 1L;
            Map<String, Object> placeholders = new LinkedHashMap<>();
            placeholders.put("situation", abbreviate(situation, SITUATION_MAX));
            placeholders.put("domain_info", domainInfoOf(match, se, level));
            placeholders.put("initial_posture", initial.label + "（"
                    + (match == null ? "陌生情境" : "领域「" + match.domain() + "」") + "）");
            String prompt = promptTemplateService.render(userId, ctx.projectId(), SCENE_MUSE_STRATEGY_CHECK,
                    placeholders);
            // 批6 收口（muse_strategy_check 属批4 记忆链·角色视角）：前置 段1A+1C+2D+2E——
            // 「该不该调档」先知道世界/身份/此刻处境；未装配 → 原样（fail-open）
            if (aiContextInjector != null && ctx.character() != null) {
                String cctx = aiContextInjector.memoryChainContext(ctx.projectId(), ctx.character().npcRef(), null);
                if (cctx != null && !cctx.isBlank()) {
                    prompt = cctx + "\n\n" + prompt;
                }
            }
            String system = promptTemplateService.systemMessage(userId, ctx.projectId(),
                    SCENE_MUSE_STRATEGY_CHECK, null);
            if (system == null || system.isBlank()) {
                system = "你只输出严格的 JSON 对象，不输出任何其他文字。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", prompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_MUSE_STRATEGY_CHECK),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_MUSE_STRATEGY_CHECK),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_MUSE_STRATEGY_CHECK, charId, 1,
                    () -> aiChatGateway.chatCompletion(userId, ctx.projectId(), null, req));
            try {
                usageLogService.record(userId, ctx.projectId(), charId, result.providerId(), result.model(),
                        SCENE_MUSE_STRATEGY_CHECK, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception e) {
                log.debug("[MUSE 策略切换] 用量入账失败（忽略）: {}", e.getMessage());
            }
            return parseReview(result.content());
        } catch (Exception e) {
            log.debug("[MUSE 策略切换] AI 档位复核失败（fail-open 回退初始档位）: {}", e.getMessage());
            return null;
        }
    }

    /** 复核输入「程序化识别」文本（领域/自评信心/经历卷）。 */
    private static String domainInfoOf(DomainMatch match, Map<String, Integer> se, ExperienceLevel level) {
        if (match == null) {
            return "领域=未识别（陌生情境）\n自评信心=无\n经历卷=" + level.label;
        }
        Integer c = se.get(match.domain());
        return "领域=" + match.domain() + "（匹配分 " + match.score() + "）\n自评信心="
                + (c == null ? "无（兄弟领域/客观技能兜底）" : c + "/5")
                + "\n经历卷=" + level.label;
    }

    /** 决策链路 trace（content 前缀 ref=npc:{id}，对齐 SelfCheckStrategy 检索口径）。 */
    private void recordTrace(DecisionContext ctx, DomainMatch match, Map<String, Integer> se,
                             Map<String, Double> skills, int volume, ExperienceLevel level,
                             Posture initial, Posture finalPosture, long durationMs) {
        try {
            Integer confidence = confidenceOf(match, se, skills);
            String detail = "策略档位=" + finalPosture.label
                    + "（初始=" + initial.label
                    + (initial != finalPosture ? "，AI 复核调整" : "") + "）"
                    + " 领域=" + (match == null ? "未识别（陌生）" : match.domain())
                    + " 信心=" + (confidence == null ? "?" : confidence + "/5")
                    + " 经历卷=" + level.label + "(" + volume + ")"
                    + " 注入=" + (finalPosture != Posture.BALANCED ? "是" : "否");
            AiCallContext.recordStep(ctx.projectId(), "agent_decision", STEP_TYPE_MUSE_STRATEGY,
                    "ref=" + ctx.character().npcRef() + " " + detail, durationMs);
        } catch (Exception e) {
            log.debug("[MUSE 策略切换] trace 写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** 领域名并集（se 优先保序，供领域识别扫描与平局优先）。 */
    private static Set<String> unionKeys(Map<String, Integer> se, Map<String, Double> skills) {
        Set<String> out = new LinkedHashSet<>();
        if (se != null) {
            out.addAll(se.keySet());
        }
        if (skills != null) {
            out.addAll(skills.keySet());
        }
        return out;
    }
}
