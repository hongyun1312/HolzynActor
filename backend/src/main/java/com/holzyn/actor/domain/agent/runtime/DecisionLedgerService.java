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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionLog;
import com.holzyn.actor.domain.agent.runtime.entity.ActorWorldEngineTickStat;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.entity.ActorWorldChronicle;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策台账服务（P0-03 决策源可追溯 · 度量基座；P0-02 诚实化止血的落库口）。
 * <p>职责三件：</p>
 * <ul>
 * <li><b>决策点台账</b>（{@code actor_decision_log}）：每个决策点一行（AI/兜底/预算降级/缺失），
 * 使「任意游戏日三类数量与占比之和 = 该日决策点总数」可精确统计（审计 P0-02 验收 1）；</li>
 * <li><b>决策缺失留痕</b>（{@code recordMissing}）：决策失败时编年史写显式「该时段无决策」记录，
 * current_activity 不伪造（审计 P0-02 止血 (a)）；</li>
 * <li><b>预算消耗持久化</b>（{@code persistTickStat}）：每轮推进的预算/已用/各来源决策数落
 * {@code actor_world_engine_tick_stat}，修「BudgetGuard 预算耗尽只在内存、endTick 即 remove」</li>
 * </ul>
 * <p><b>事务纪律</b>：各方法为轻量短事务（无 AI/网络调用），从非事务的引擎编排
 * （{@code WorldEngine.advanceProject}）与决策循环（{@code AgentRuntime.loop}）中安全调用——
 * 对齐「AI 调用在事务外、写阶段毫秒级短事务提交」的项目纪律。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionLedgerService {

    /** 决策点台账仓库 */
    private final ActorDecisionLogRepository decisionLogRepository;

    /** tick 预算统计仓库 */
    private final ActorWorldEngineTickStatRepository tickStatRepository;

    /** 编年史仓库（决策缺失记录） */
    private final ActorWorldChronicleRepository chronicleRepository;

    /** 行动文本摘要上限（列宽） */
    private static final int ACTION_TEXT_MAX = 255;

    /**
     * 落一个决策点台账行（AI/程序化兜底/预算耗尽降级/决策缺失 共用）。
     * <p>写入方：{@code AgentRuntime} 决策循环（engine_tick/manual/stimulus 等）与
     * {@code WorldEngine}（决策缺失）。轻量短事务；失败不阻断主流程（台账是观测旁路）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空=项目级决策）
     *
     * @param characterRef 统一角色引用（special:{id}/crowd:{id}，可空）
     *
     * @param gameSecond 决策感知时刻（游戏秒，可空=无时刻上下文）
     *
     * @param source 决策来源（AI/AI_RETRY/FALLBACK_PROGRAMMATIC/BUDGET_EXHAUSTED/DECISION_MISSING）
     *
     * @param actionText 决策行动文本摘要（可空）
     *
     * @param triggerType 触发源（engine_tick/manual/after_dialog/event/stimulus，可空）
     */
    @Transactional
    public void record(Long projectId, Long characterId, String characterRef, Long gameSecond,
                       DecisionSource source, String actionText, String triggerType) {
        if (projectId == null) {
            return;
        }
        // null 来源=旧路径未打标 → 视为 AI（台账完整性：三类之和 = 决策点总数，不因未打标漏行）
        DecisionSource effective = source == null ? DecisionSource.AI : source;
        try {
            ActorDecisionLog row = new ActorDecisionLog();
            row.setProjectId(projectId);
            row.setCharacterId(characterId);
            row.setCharacterRef(characterRef);
            row.setGameSecond(gameSecond);
            row.setGameDay(gameSecond == null ? null : Math.floorDiv(gameSecond, 86400L));
            row.setDecisionSource(effective.name());
            row.setActionText(truncate(actionText, ACTION_TEXT_MAX));
            row.setTriggerType(triggerType);
            decisionLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[决策台账] 决策点台账写入失败（隔离继续）: project={} ref={} : {}",
                    projectId, characterRef, e.getMessage());
        }
    }

    /**
     * 决策缺失留痕（P0-02 止血 (a)）：决策失败时<b>不伪造</b>新活动文本（current_activity
     * 保持上一小时不变），落台账行（DECISION_MISSING）+ 编年史显式「该时段无决策」记录。
     * <p>由 {@code WorldEngine.dispatchOneCharacter} 的 catch 调用；轻量短事务，失败隔离。</p>
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色实体（非空）
     *
     * @param gameSecond 决策感知时刻（游戏秒）
     *
     * @param reason 缺失原因（异常消息/中断/提交失败等）
     */
    @Transactional
    public void recordMissing(Long projectId, ActorCharacter ch, long gameSecond, String reason) {
        if (projectId == null || ch == null || ch.getId() == null) {
            return;
        }
        String reasonText = reason == null || reason.isBlank() ? "AI 决策异常" : reason;
        record(projectId, ch.getId(), NpcRefs.of(ch.getId()), gameSecond,
                DecisionSource.DECISION_MISSING, "决策缺失（" + reasonText + "）", "engine_tick");
        try {
            ActorWorldChronicle entry = new ActorWorldChronicle();
            entry.setProjectId(projectId);
            entry.setGameDay(Math.floorDiv(gameSecond, 86400L));
            entry.setGameHour(Math.floorDiv(gameSecond, 3600L));
            entry.setTitle("决策缺失记录");
            entry.setContent("游戏时刻 " + WorldClockService.formatGameTime(gameSecond / 3600L)
                    + "：角色「" + (ch.getName() == null ? ch.getId() : ch.getName())
                    + "」本时段无决策记录（原因：" + reasonText + "），当前活动保持上一时段不变。");
            entry.setImportance(1);
            entry.setSource("decision_missing");
            entry.setDecisionSource(DecisionSource.DECISION_MISSING.name());
            entry.setInvolvedCharacters("[\"" + NpcRefs.of(ch.getId()) + "\"]");
            chronicleRepository.save(entry);
        } catch (Exception e) {
            log.warn("[决策台账] 决策缺失编年史写入失败（隔离继续）: project={} char={} : {}",
                    projectId, ch.getId(), e.getMessage());
        }
    }

    /**
     * 持久化本轮推进的预算统计（P0-03 修法 2：预算/已用/降级决策数落库，防 endTick 后丢失）。
     * <p>各来源决策数来自台账按 {@code [fromSecond, toSecond)} 窗口查询（本轮的决策行
     * 都在该窗口内；异步在途行稍晚落库不计入本轮统计，台账本身完整）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param fromSecond 本轮推进起始游戏秒（含）
     *
     * @param toSecond 本轮推进到的游戏秒（不含，作为窗口上界）
     *
     * @param budgetTotal 本 tick AI 预算总量
     *
     * @param budgetUsed 本 tick 实际 AI 预算消耗数
     */
    @Transactional
    public void persistTickStat(Long projectId, Long fromSecond, Long toSecond,
                                int budgetTotal, int budgetUsed) {
        if (projectId == null || toSecond == null) {
            return;
        }
        try {
            long[] counts = breakdown(projectId, fromSecond, toSecond);
            ActorWorldEngineTickStat stat = new ActorWorldEngineTickStat();
            stat.setProjectId(projectId);
            stat.setGameSecond(toSecond);
            stat.setBudgetTotal(budgetTotal);
            stat.setBudgetUsed(budgetUsed);
            stat.setExhaustedDecisions((int) counts[3]);
            stat.setAiDecisions((int) counts[0]);
            stat.setFallbackDecisions((int) counts[1]);
            stat.setMissingDecisions((int) counts[2]);
            tickStatRepository.save(stat);
        } catch (Exception e) {
            log.warn("[决策台账] tick 预算统计持久化失败（隔离继续）: project={} : {}",
                    projectId, e.getMessage());
        }
    }

    /**
     * 按窗口统计各来源决策数：[0]=AI+AI_RETRY，[1]=FALLBACK_PROGRAMMATIC，[2]=DECISION_MISSING，
     * [3]=BUDGET_EXHAUSTED。
     */
    private long[] breakdown(Long projectId, Long fromSecond, Long toSecond) {
        long[] out = new long[4];
        List<ActorDecisionLogRepository.SourceCount> rows =
                decisionLogRepository.countBySourceBetween(projectId, fromSecond, toSecond);
        for (ActorDecisionLogRepository.SourceCount sc : rows) {
            DecisionSource src = DecisionSources.parse(sc.getSource());
            long cnt = sc.getCnt() == null ? 0 : sc.getCnt();
            if (src == null) {
                continue;
            }
            switch (src) {
                case AI, AI_RETRY -> out[0] += cnt;
                case FALLBACK_PROGRAMMATIC -> out[1] += cnt;
                case DECISION_MISSING -> out[2] += cnt;
                case BUDGET_EXHAUSTED -> out[3] += cnt;
            }
        }
        return out;
    }

    /**
     * 某游戏日的决策来源统计（AI/兜底/预算降级/缺失 各数量 + 总数 + 占比；审计 P0-02 验收 1）。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日（自纪元起）
     *
     * @return 统计 Map（projectId/gameDay/total/bySource/ai/fallback/missing/unit）
     */
    public Map<String, Object> dayStats(Long projectId, Long gameDay) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("gameDay", gameDay);
        long total = decisionLogRepository.countByProjectIdAndGameDay(projectId, gameDay);
        out.put("total", total);
        Map<String, Long> bySource = new LinkedHashMap<>();
        long ai = 0, fallback = 0, missing = 0;
        for (ActorDecisionLogRepository.SourceCount sc : decisionLogRepository.countBySource(projectId, gameDay)) {
            String name = sc.getSource();
            long cnt = sc.getCnt() == null ? 0 : sc.getCnt();
            bySource.put(name, cnt);
            DecisionSource src = DecisionSources.parse(name);
            if (src == null) {
                continue;
            }
            switch (src) {
                case AI, AI_RETRY -> ai += cnt;
                case FALLBACK_PROGRAMMATIC, BUDGET_EXHAUSTED -> fallback += cnt;
                case DECISION_MISSING -> missing += cnt;
            }
        }
        out.put("bySource", bySource);
        out.put("ai", ai);
        out.put("fallback", fallback);
        out.put("missing", missing);
        out.put("aiRatio", ratio(ai, total));
        out.put("fallbackRatio", ratio(fallback, total));
        out.put("missingRatio", ratio(missing, total));
        out.put("unit", "决策点");
        return out;
    }

    /**
     * 某项目最近 N 轮 tick 预算统计（最新在前）。
     *
     * @param projectId 项目 ID
     *
     * @param limit 条数上限
     *
     * @return 统计列表（Map 形式）
     */
    public List<Map<String, Object>> recentTickStats(Long projectId, int limit) {
        return tickStatRepository.findTopByProjectIdOrderByGameSecondDesc(projectId, limit).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("gameSecond", s.getGameSecond());
                    m.put("budgetTotal", s.getBudgetTotal());
                    m.put("budgetUsed", s.getBudgetUsed());
                    m.put("exhaustedDecisions", s.getExhaustedDecisions());
                    m.put("aiDecisions", s.getAiDecisions());
                    m.put("fallbackDecisions", s.getFallbackDecisions());
                    m.put("missingDecisions", s.getMissingDecisions());
                    m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
                    return m;
                }).toList();
    }

    /** 占比（百分数，一位小数；total=0 返回 0）。 */
    private static double ratio(long part, long total) {
        return total <= 0 ? 0d : Math.round(part * 1000d / total) / 10d;
    }

    /** 字符串截断（列宽保护）。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
