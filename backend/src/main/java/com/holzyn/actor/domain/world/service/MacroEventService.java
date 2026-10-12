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

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 宏观社会演化 · 宏观事件服务。
 * <p>职责：宏观事件的统一产出与发布（设计 §六 回灌 Schema：{macroType, indicator, oldValue,
 * <ul>
 * <li><b>双通道叙事</b>：常规变化走规则模板
 * （确定性文本），重大变化（粮价波动 &gt;20%、势力影响力位移 &gt;30%）→ 独立预算内
 * LLM 完整叙事（scene=macro_event），失败/预算耗尽回退规则模板；</li>
 * <li><b>落库</b>：actor_world_macro_event（source=rule|ai，幂等键 macro:{projectId}:{gameDay}:
 * <li><b>发布</b>：经 WorldEventBus 发布 kind=macro / source=macro / global 的 actor_event
 * （幂等）→ 时间线 SSE + 编年史即时提炼（WorldMemoryService 订阅，severity≥阈值自动成条）
 * + NPC 即时决策刺激（RealTimeDecisionService 订阅，severity≥3 触发）；</li>
 * <li><b>回灌联动</b>：委托 MacroFeedbackService——信息包（扩散源头）+ 帧「社会宏观」板块追加
 * （回灌路③①）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroEventService {

    /** 事件发布 kind（actor_event.kind=macro；编年史即时提炼不排除此 kind） */
    public static final String EVENT_KIND = "macro";

    private final MacroProperties props;
    private final ActorWorldMacroEventRepository eventRepository;
    private final MacroAiService macroAiService;
    private final MacroFeedbackService macroFeedbackService;
    private final MacroStateService macroStateService;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 中观协调服务（：重大事件 → 组织指令下发；字段注入保持构造器不变，
     * null=未装配[单测]跳过零阻断）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MesoCoordinationService mesoCoordinationService;

    /**
     * 产出并发布一条宏观事件（规则引擎触发统一入口；无因果上下文版本=emitDetailed 的简化委托）。
     */
    public ActorWorldMacroEvent emit(Long projectId, Long gameDay, Long gameSecond, String macroType,
                                     String eventType, String indicator, Double oldValue, Double newValue,
                                     Double magnitude, String namedNpc, int seq) {
        return emitDetailed(projectId, gameDay, gameSecond, macroType, eventType, indicator,
                oldValue, newValue, magnitude, namedNpc, seq, null);
    }

    /**
     * 产出并发布一条宏观事件（规则引擎触发统一入口）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日
     *
     * @param gameSecond 游戏秒
     *
     * @param macroType 子系统（economy/faction/population/culture/tech…）
     *
     * @param eventType 事件类型（丰年/歉收/粮价暴涨/大旱/洪涝/暴雪/开仓赈济/民变风险/
     * 舆论转向/技艺突破/技艺失传/信仰危机/新习俗兴起/谣言成风/势力易主/瘟疫减员…）
     *
     * @param indicator 指标名（可空）
     *
     * @param oldValue 旧值（可空）
     *
     * @param newValue 新值（可空）
     *
     * @param magnitude 变化幅度（阈值判定用；可空）
     *
     * @param namedNpc 受影响的具名普通 NPC 名（可空；减员/增员事件带出）
     *
     * @param seq 当日同类事件序号（幂等键组成）
     *
     * @param context 因果链上下文（/：season/weather/harvestFactor/grievance/民怨/
     * 入账摘要等，注入 AI 叙事提示词；可空）
     *
     * @return 落库的事件（幂等跳过返回 null）
     */
    @Transactional
    public ActorWorldMacroEvent emitDetailed(Long projectId, Long gameDay, Long gameSecond, String macroType,
                                             String eventType, String indicator, Double oldValue, Double newValue,
                                             Double magnitude, String namedNpc, int seq,
                                             Map<String, Object> context) {
        if (projectId == null || gameDay == null) {
            return null;
        }
        String sourceKey = "macro:" + projectId + ":" + gameDay + ":" + eventType + ":" + seq;
        Optional<ActorWorldMacroEvent> exists = eventRepository.findByProjectIdAndSourceKey(projectId, sourceKey);
        if (exists.isPresent()) {
            return null; // 幂等（同 tick 重复触发防双发）
        }
        // 严重度分级（1-5；饥荒/瘟疫/钱庄挤兑/势力易主/极端季节事件=4 级重大，民变=5）
        int severity = severityOf(eventType, magnitude);
        // 规则模板草稿（确定性文本，按世界观模板语义渲染）
        String ruleText = ruleTemplate(projectId, eventType, indicator, oldValue, newValue, magnitude, namedNpc, gameDay);
        String source = "rule";
        String narrative = ruleText;
        // 标题=通用中文显示名
        String title = MacroTemplate.displayName(eventType);
        // 阈值触发 AI 叙事
        if (needsAiNarrative(eventType, magnitude)) {
            MacroAiService.Narrative ai = macroAiService.narrateDetailed(
                    currentUserProvider == null ? 1L : currentUserProvider.currentUserId(),
                    projectId, gameDay, macroType, eventType, indicator, oldValue, newValue, ruleText, context);
            if (ai != null) {
                narrative = ai.content();
                // AI 未给标题 → 通用显示名（缺码回退原码，恒非空；不落裸事件码）
                title = ai.title() == null || ai.title().isBlank()
                        ? MacroTemplate.displayName(eventType) : ai.title();
                source = "ai";
            }
        }
        ActorWorldMacroEvent ev = new ActorWorldMacroEvent();
        ev.setProjectId(projectId);
        ev.setGameDay(gameDay);
        ev.setGameSecond(gameSecond);
        ev.setMacroType(macroType);
        ev.setEventType(eventType);
        ev.setTitle(title);
        ev.setSeverity(severity);
        ev.setIndicator(indicator);
        ev.setOldValue(oldValue);
        ev.setNewValue(newValue);
        ev.setMagnitude(magnitude);
        ev.setNarrative(narrative);
        ev.setSource(source);
        ev.setSourceKey(sourceKey);
        eventRepository.save(ev);

        // 发布 kind=macro 事件（时间线/编年史/即时决策刺激链路）
        publish(projectId, gameDay, gameSecond, ev, namedNpc);
        // 回灌：信息包（扩散源头）+ 帧「社会宏观」板块追加
        macroFeedbackService.onMacroEvent(projectId, gameDay, ev, namedNpc);
        // ：中观协调——重大事件 → 组织指令下发（未装配[单测]跳过零阻断）
        // ⚠️ R1 + 纪律：指令生成含 AI（generateDirective，10~105s）——
        // 改经 AfterCommit 提交后执行（事务内→延迟到提交后；事务外→立即），AI 不再持本事务
        // 的写锁；宏观 tick 本身已由 MacroScheduler 异步化（引擎线程零阻塞）。
        if (mesoCoordinationService != null) {
            try {
                com.holzyn.actor.common.AfterCommit.runAfterCommit("macro-meso-directives", () -> {
                    try {
                        mesoCoordinationService.onMacroEvent(projectId, ev, gameSecond);
                    } catch (Exception e) {
                        log.warn("[宏观] 中观指令下发失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("[宏观] 中观指令下发调度失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        log.info("[宏观] 宏观事件产出并发布: project={} day={} type={} severity={} source={}",
                projectId, gameDay, eventType, severity, source);
        return ev;
    }

    /** 发布 kind=macro 事件（幂等键带事件 id；global 可见性——全村皆闻）。 */
    private void publish(Long projectId, Long gameDay, Long gameSecond, ActorWorldMacroEvent ev, String namedNpc) {
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind(EVENT_KIND)
                    .source("macro")
                    .visibility("global")
                    .title(abbrev(ev.getTitle() == null ? ev.getEventType() : ev.getTitle(), 200)
                            + "·第" + (gameDay + 1) + "日" + (namedNpc == null ? "" : "（" + namedNpc + "）"))
                    .content(ev.getNarrative())
                    .importance(ev.getSeverity())
                    .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                    .sourceKey("macro:" + projectId + ":" + gameDay + ":evt:" + ev.getId())
                    .build());
        } catch (Exception e) {
            log.warn("[宏观] 宏观事件发布失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    // ==================== 严重度/阈值/AI 判定 ====================

    /**
     * 严重度分级（规则映射；重大事件=4，异常巨变=5；幅度微小时仅常规事件降级——重大事件类型
     * 不受影响；事件码=通用语义码， 通用化）。
     */
    static int severityOf(String eventCode, Double magnitude) {
        int base = switch (eventCode) {
            case "price_surge", "bank_run", "plague", "famine", "power_shift",
                    "drought", "flood", "blizzard", "tech_breakthrough", "tech_lost" -> 4;
            case "revolt_risk" -> 5;
            case "faction_split", "refugee_influx", "faith_crisis", "relief", "opinion_shift" -> 3;
            case "harvest_good", "harvest_bad", "faction_alliance", "caravan", "migration_wave",
                    "new_custom", "rumor" -> 2;
            default -> 2;
        };
        if (magnitude != null) {
            if (magnitude > 0.5) {
                base = Math.min(5, base + 1);
            } else if (magnitude < 0.1 && base <= 2) {
                base = Math.max(1, base - 1);
            }
        }
        return base;
    }

    /**
     * 阈值触发 AI 叙事判定（阈值 + / 新场景：极端季节事件/舆论转向/科技突破/
     * 信仰危机/民变风险/新习俗兴起—— 「5 类新场景 + 因果链上下文」；
     * 事件码=通用语义码）。
     */
    boolean needsAiNarrative(String eventCode, Double magnitude) {
        if ("price_surge".equals(eventCode)) {
            return magnitude == null || magnitude > props.getThresholds().getPriceChange();
        }
        if ("power_shift".equals(eventCode)) {
            return magnitude == null || magnitude > props.getThresholds().getFactionShift();
        }
        return switch (eventCode) {
            case "bank_run", "plague", "famine", "faction_split", "refugee_influx",
                    "drought", "flood", "blizzard", "opinion_shift", "tech_breakthrough",
                    "faith_crisis", "revolt_risk", "new_custom", "tech_lost" -> true;
            default -> false;
        };
    }

    /**
     * 规则模板草稿（无 AI 兜底；按世界观模板语义渲染——核心资源/计价单位/社区名/事件叙事
     * 全部由 {@link MacroTemplate} 提供，xianxia 模板=通用仙侠村落文本）。
     */
    String ruleTemplate(Long projectId, String eventType, String indicator, Double oldValue,
                        Double newValue, Double magnitude, String namedNpc, Long gameDay) {
        MacroTemplate tpl = null;
        if (macroStateService != null) {
            try {
                tpl = macroStateService.templateOf(projectId);
            } catch (Exception ignored) {
                // 模板解析失败回退默认
            }
        }
        if (tpl == null) {
            tpl = MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        }
        String dayText = "第" + (gameDay == null ? 0 : gameDay + 1) + "日";
        String magText = magnitude == null ? "" : "约" + Math.round(magnitude * 100) + "%";
        // 具名 NPC 前缀（瘟疫/饥荒事件的「，XX病倒了/饿得面黄肌瘦」由模板文本承载——{npc} 占位）
        String npc = namedNpc == null ? "" : "，" + namedNpc;
        String base = tpl.renderEventText(eventType, dayText, npc, indicator, magText);
        // 数值补充（可选）
        if (indicator != null && (newValue != null || oldValue != null)) {
            base += "（" + indicator + (oldValue == null ? "" : "：" + oldValue + "→" + newValue) + "）";
        }
        return base;
    }

    /** 配置引用（测试/读接口用）。 */
    public MacroProperties properties() {
        return props;
    }

    /** 文本截断（中文按字符）。 */
    private static String abbrev(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
