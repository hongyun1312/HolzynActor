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

import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 宏观社会演化 · 微观入账服务。
 * <p>职责：NPC 行动（经商/打工/参战/布道/社交等）→ 宏观指标折算入账（设计 §六 入账 Schema）：
 * ① <b>可扩展入账协议</b>（：规则映射从硬编码表迁移为 DB 配置驱动 MacroDeltaRegistry——
 * 插行即入账、改行即生效[热更新]，本项目优先覆盖全局默认；字段注入保持构造器不变，
 * 未装配回退纯种子匹配与 语义完全一致）；</li>
 * ② <b>重大行动 AI 判定叠加</b>（urgency≥4）：事务提交后异步（虚拟线程）走
 * scene=macro_impact（独立每日预算），AI 判定额外折算入账（ai_judged=1），失败/耗尽回退
 * 规则映射（零阻断）——⚠️ 对齐 S4 教训：HTTP/同步 AI 严禁在事务内调用（拖死持锁）；</li>
 * ③ 每日汇总由 MacroScheduler 经济 tick 按「前一游戏日」聚合（因果链：本日行动 → 次日宏观）。
 * 入账流水行只增不改（审计）， 文化/科技 delta 由 演化规则消费。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroLedgerService {

    private final MacroProperties props;
    private final ActorWorldMacroDeltaRepository deltaRepository;
    private final MacroStateService macroStateService;
    private final MacroAiService macroAiService;
    private final WorldClockService worldClockService;

    /**
     * 可扩展入账协议注册表（：DB 规则表驱动 + 热更新；字段注入保持构造器不变，
     * null=未装配回退纯种子匹配[ 语义]——单测 mock 场景零改动）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MacroDeltaRegistry macroDeltaRegistry;

    /** 异步虚拟线程执行器（重大行动 AI 判定；对齐记忆系统异步抽取模式） */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 入账条目（规则映射产出） */
    private record Entry(String targetMacro, String indicator, double delta) {
    }

    /**
     * 特殊 NPC 行动入账（AgentActionService.execute 挂钩；规则映射同步写流水 +
     * 重大行动事务提交后异步 AI 判定叠加）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 行动者引用（special:{id}）
     *
     * @param npcName 行动者名称
     *
     * @param actionText 行动文本（决策 action）
     *
     * @param actionType 行动类型（决策 type）
     *
     * @param target 行动目标（可空）
     *
     * @param urgency 决策紧急度（≥4 → AI 判定叠加）
     *
     * @param gameSecond 游戏秒（入账日依据）
     */
    public void recordAction(Long projectId, String npcRef, String npcName, String actionText,
                             String actionType, String target, int urgency, Long gameSecond) {
        if (projectId == null || !props.isEnabled()) {
            return;
        }
        try {
            List<Entry> entries = ruleEntries(projectId, actionText, actionType, npcRef, npcName);
            Long day = dayOf(projectId, gameSecond);
            for (Entry e : entries) {
                saveDelta(projectId, day, gameSecond, npcRef, npcName, actionType, actionText,
                        e.targetMacro(), e.indicator(), e.delta(), 0, "rule");
            }
            // 重大行动 → 事务提交后异步 AI 判定（预算内；失败回退规则映射已入账）
            if (urgency >= 4 && macroAiService != null) {
                scheduleAiJudge(projectId, npcRef, npcName, actionText, actionType, target, urgency,
                        day, gameSecond);
            }
        } catch (Exception e) {
            log.warn("[宏观] 行动入账失败（隔离继续）: project={} ref={} : {}", projectId, npcRef, e.getMessage());
        }
    }

    /**
     * 普通 NPC 行动入账（OrdinaryNpcService 驱动/程序化路径挂钩；actionType 可为空，
     * 按行动文本关键词映射）。
     */
    public void recordCrowdAction(Long projectId, String npcRef, String npcName, String actionText,
                                  String actionType, Long gameSecond) {
        if (projectId == null || !props.isEnabled()) {
            return;
        }
        try {
            List<Entry> entries = ruleEntries(projectId, actionText, actionType, npcRef, npcName);
            Long day = dayOf(projectId, gameSecond);
            for (Entry e : entries) {
                saveDelta(projectId, day, gameSecond, npcRef, npcName, actionType, actionText,
                        e.targetMacro(), e.indicator(), e.delta(), 0, "rule");
            }
        } catch (Exception e) {
            log.warn("[宏观] 人群行动入账失败（隔离继续）: project={} ref={} : {}", projectId, npcRef, e.getMessage());
        }
    }

    /** 事务提交后异步 AI 判定（虚拟线程；无活动事务/非事务上下文直接异步执行）。 */
    private void scheduleAiJudge(Long projectId, String npcRef, String npcName, String actionText,
                                 String actionType, String target, int urgency, Long day, Long gameSecond) {
        Runnable task = () -> {
            try {
                MacroAiService.ImpactJudgment j = macroAiService.judgeImpact(1L, projectId, day,
                        npcRef, npcName, actionText, actionType, target, urgency, gameSecond);
                if (j != null && j.hasImpact()) {
                    saveDelta(projectId, day, gameSecond, npcRef, npcName, actionType, actionText,
                            j.targetMacro(), j.indicator(), j.delta(), 1, "ai");
                    log.debug("[宏观] 重大行动 AI 判定入账: project={} ref={} {} → {}:{}=+{}",
                            projectId, npcRef, actionText, j.targetMacro(), j.indicator(), j.delta());
                }
            } catch (Exception e) {
                log.warn("[宏观] 重大行动 AI 判定失败（规则映射已入账，隔离继续）: project={} : {}",
                        projectId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    EXECUTOR.execute(task);
                }
            });
        } else {
            EXECUTOR.execute(task);
        }
    }

    /** 落流水行。 */
    private void saveDelta(Long projectId, Long day, Long gameSecond, String npcRef, String npcName,
                           String actionType, String actionText, String targetMacro, String indicator,
                           double delta, int aiJudged, String source) {
        if (indicator == null || indicator.isBlank()) {
            return;
        }
        ActorWorldMacroDelta d = new ActorWorldMacroDelta();
        d.setProjectId(projectId);
        d.setGameDay(day);
        d.setGameSecond(gameSecond);
        d.setNpcRef(npcRef);
        d.setNpcName(npcName);
        d.setActionType(actionType);
        d.setActionText(abbrev(actionText, 255));
        d.setTargetMacro(targetMacro);
        d.setIndicator(indicator);
        d.setDelta(delta);
        d.setAiJudged(aiJudged);
        d.setSource(source);
        deltaRepository.save(d);
    }

    // ==================== 可扩展入账协议 ====================

    /**
     * 行动类型/文本 → 宏观指标折算（起委托 {@link MacroDeltaRegistry}：DB 规则表驱动 +
     * 热更新；未装配回退纯种子匹配——与 语义完全一致，单测零改动）。
     * 势力入账 indicator={{faction}} 先按领袖归属解析行动者势力；文化入账 indicator=
     * {{topIssue}} 解析为当前最强舆论议题；解析失败/无归属该条跳过。
     */
    List<Entry> ruleEntries(Long projectId, String actionText, String actionType, String npcRef, String npcName) {
        List<MacroDeltaRegistry.Hit> hits = macroDeltaRegistry != null
                ? macroDeltaRegistry.lookup(projectId, actionText, actionType)
                : MacroDeltaRegistry.seedLookup(actionText, actionType);
        List<Entry> out = new ArrayList<>();
        for (MacroDeltaRegistry.Hit h : hits) {
            String indicator = h.indicator();
            if (MacroDeltaRegistry.PH_FACTION.equals(indicator)) {
                indicator = factionNameOf(projectId, npcRef);
                if (indicator == null) {
                    continue; // 无势力归属 → 该条跳过
                }
            } else if (MacroDeltaRegistry.PH_TOP_ISSUE.equals(indicator)) {
                indicator = strongestIssue(projectId);
            }
            if (indicator == null || indicator.isBlank()) {
                continue;
            }
            out.add(new Entry(h.targetMacro(), indicator, h.delta()));
        }
        return out;
    }

    /** 行动者势力解析（按领袖引用匹配；无归属返回 null=势力不入账）。 */
    private String factionNameOf(Long projectId, String npcRef) {
        if (npcRef == null || macroStateService == null) {
            return null;
        }
        MacroState st = macroStateService.stateOf(projectId);
        for (MacroState.Faction f : st.getFactions().values()) {
            if (npcRef.equals(f.getLeaderRef())) {
                return f.getName();
            }
        }
        return null;
    }

    /** 最强议题话题。 */
    private String strongestIssue(Long projectId) {
        try {
            MacroState st = macroStateService.stateOf(projectId);
            return st.getIssues().stream()
                    .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity))
                    .map(MacroState.Issue::getTopic)
                    .orElse("时局");
        } catch (Exception e) {
            return "时局";
        }
    }

    /** 文本截断。 */
    private static String abbrev(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /** 入账游戏日解析（优先传入秒；缺省取引擎推进位置——行动由引擎驱动，统一单一时间源）。 */
    Long dayOf(Long projectId, Long gameSecond) {
        if (gameSecond != null) {
            return Math.floorDiv(gameSecond, 86400L);
        }
        try {
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            return sec == null ? 0L : Math.floorDiv(sec, 86400L);
        } catch (Exception e) {
            return 0L;
        }
    }
}
