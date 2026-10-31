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

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 宏观社会演化 · 多周期调度器。
 * <p>职责：宏观层多周期 tick 编排 ——WorldEngine
 * 感知 tick 循环内调用 {@link #tickIfDue}，按游戏日小时分钟位判定：</p>
 * <ul>
 * <li><b>每日 00:05 槽</b>（tickDaily）：<b>全链联动源头</b>——季节/天气（最近帧）→
 * 收成系数（MacroHarvestEngine）→ 经济供需（六指标+科技突破加成）→ 民怨（生计压力）
 * → 普通 NPC 生计联动（状态参数 v2）→ 极端季节事件（大旱/洪涝/暴雪，阈值 AI 叙事）
 * → 丰年/歉收正向/负向环 + 前一游戏日入账汇总 + 人口统计模型 + 快照 + 帧简报；</li>
 * <li><b>每旬首日 00:10 槽</b>（tickFaction）：近一旬入账贡献 + 民怨/收成修正（当权势力
 * 侵蚀/收成正向）+ 关系状态机 + 赈济负反馈（开仓赈济）/民变风险事件 + 秘术突破压制
 * 弱势势力 + 快照 + 帧简报；</li>
 * <li><b>每月首日 00:15 槽</b>（tickMonthly）：<b>文化科技演化</b>——舆论传播模型
 * （入账+信息扩散聚合+事件冲击+自然衰减）、信仰演变、新习俗（超龄消退）、科技累积/
 * 里程碑突破/衰退失传 + 联动效果（产量/瘟疫/税收/秘术压制）+ 阈值 AI 叙事 + 快照。</li>
 * </ul>
 * <p>健壮性：每类型每游戏日仅触发一次（内存标记 + 快照 uk 双保险）；单周期失败隔离不阻断
 * 引擎推进；长离线回补时逐日自然物化（每跨日 tick 判定一次）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroScheduler {

    private final MacroProperties props;
    private final MacroStateService macroStateService;
    private final MacroEventService macroEventService;
    private final MacroFeedbackService macroFeedbackService;
    private final ActorWorldFrameRepository frameRepository;
    private final ActorWorldMacroEventRepository eventRepository;
    private final NpcStateService npcStateService;
    private final ObjectMapper objectMapper;

    /**
     * 全链路 trace 服务（：tick 输入→输出→回灌逐跳回放落库；字段注入保持构造器不变，
     * null=未装配[单测]跳过记录零阻断）
     */
    @Autowired(required = false)
    private MacroTraceService macroTraceService;

    /**
     * 中观组引擎（：势力三源派生/自发成组扫描；字段注入保持构造器不变，
     * null=未装配[单测]跳过——势力 tick 保持 前语义）
     */
    @Autowired(required = false)
    private MesoGroupEngine mesoGroupEngine;

    /** 中观协调服务 */
    @Autowired(required = false)
    private MesoCoordinationService mesoCoordinationService;

    /**
     * 二阶涌现检测服务（：每日 tick 扫描前一日聚集；字段注入保持构造器不变，
     * null=未装配[单测]跳过——零阻断）
     */
    @Autowired(required = false)
    private MacroEmergenceService macroEmergenceService;

    /**
     * 世界程度门禁（：休闲/轻度档不调度宏观层——"休闲世界里不应出现势力斗争与宏观调控"
     * 是设定冲突，不是性能问题，所以这里是<b>硬开关</b>而不是降频）。
     * <p>为什么用 {@code @Lazy}：{@code WorldToneService} 读目录库索引列，一旦与
     * 世界引擎侧的装配顺序纠缠就会形成 Bean 环（AGENTS §8.1.5）；懒注入 + null 容错
     * 让单测里 {@code new MacroScheduler(...)} 依然可用。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private WorldToneService worldToneService;

    /**
     * 每类型每游戏日已触发标记：projectId → 最后处理游戏日（内存态，重启丢失=跨日重触发，
     * 但快照 uk 幂等兜底不重复推进数值——引擎位置不回溯则无害）
     */
    private final Map<Long, Long> lastDailyDay = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastFactionDay = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastMonthlyDay = new ConcurrentHashMap<>();

    // ==================== R1 引擎 AI 异步化：宏观 tick 异步执行器 ====================
    // 实测（R1 冒烟）：00:05/00:10/00:15 宏观槽触发时，tickDaily 链在引擎线程同步跑
    // 宏观事件 AI 叙事 + 中观指令生成（10~105s/次），调度线程被占死、世界冻结（jstack 实证
    // scheduling-1 阻塞在 okhttp HTTPS 读，时钟停摆）。宏观 tick 是「每日/旬/月级重活」——与
    // 编年史/世界帧同口径：改为虚拟线程异步执行（引擎立即继续推进），标记在派发时先置（防下一
    // tick 重复触发）；失败隔离零阻断（与引擎「推进失败」隔离一致）。
    private volatile ExecutorService macroExecutor;

    private ExecutorService macroExecutor() {
        ExecutorService ex = macroExecutor;
        if (ex != null) {
            return ex;
        }
        synchronized (this) {
            if (macroExecutor == null) {
                macroExecutor = Executors.newVirtualThreadPerTaskExecutor();
                log.info("[宏观] 宏观 tick 异步执行器已创建（虚拟线程每任务一线程）");
            }
            return macroExecutor;
        }
    }

    /** 引擎停机：关闭宏观 tick 异步执行器（虚拟线程 daemon，仅及时释放资源）。 */
    @jakarta.annotation.PreDestroy
    public void shutdownMacroExecutor() {
        ExecutorService ex = macroExecutor;
        if (ex != null) {
            ex.shutdownNow();
            log.info("[宏观] 宏观 tick 异步执行器已关闭");
        }
    }

    /**
     * 多周期 tick 判定入口（WorldEngine 每 5 游戏分钟 tick 调用；分钟窗口内首 tick 触发，
     * 当日同类型幂等）。
     * <p>⚠️ R1：实际 tick 改为<b>异步执行</b>——宏观链含 AI 叙事/中观指令生成
     * （10~105s/次），在引擎线程同步跑会把调度线程占死；触发标记在
     * 派发时先置（防下一 tick 重复触发），任务在虚拟线程执行，失败隔离零阻断。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（引擎推进位置）
     */
    public void tickIfDue(Long projectId, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || gameSecond == null) {
            return;
        }
        // 世界程度门禁：休闲档（allowMacro=false）整个宏观层不调度——既不出势力斗争，
        // 也不出宏观事件/涌现，更不写快照；直接 return 比"降频"更符合设定（不该有的内容
        // 一次都不出现）。轻度档保留每日链（人口/经济/收成这类生活层）但跳过势力对抗链。
        boolean factionAllowed = true;
        if (worldToneService != null) {
            try {
                if (!worldToneService.allowMacro(projectId)) {
                    return;
                }
                factionAllowed = worldToneService.allowFactionEngine(projectId);
            } catch (RuntimeException e) {
                // 门禁读不到档位时 fail-open 到"改造前行为"，绝不能因为读不到就不推进世界
                log.debug("[宏观] 世界程度门禁读取失败，按标准档继续: project={} 原因={}", projectId, e.toString());
            }
        }
        long day = Math.floorDiv(gameSecond, 86400L);
        int minuteOfHour = (int) (Math.floorMod(gameSecond, 3600L) / 60);
        MacroProperties.Schedule s = props.getSchedule();
        Long lastDaily = lastDailyDay.get(projectId);
        // 每日 00:01~00:09 窗口（00:05 槽；容忍 tick 偏移）
        if (minuteOfHour >= 1 && minuteOfHour <= 9 && (lastDaily == null || lastDaily != day)) {
            lastDailyDay.put(projectId, day);
            dispatchMacroTick(projectId, day, gameSecond, "每日宏观 tick（00:05 槽）",
                    () -> tickDaily(projectId, day, gameSecond));
            return;
        }
        Long lastFaction = lastFactionDay.get(projectId);
        // 每旬首日 00:10~00:14 窗口（00:10 槽）
        if (factionAllowed && minuteOfHour >= 10 && minuteOfHour <= 14
                && Math.floorMod(day, s.getDaysPerXun()) == 0 && (lastFaction == null || lastFaction != day)) {
            lastFactionDay.put(projectId, day);
            dispatchMacroTick(projectId, day, gameSecond, "旬宏观 tick（00:10 槽）",
                    () -> tickFaction(projectId, day, gameSecond));
            return;
        }
        Long lastMonthly = lastMonthlyDay.get(projectId);
        // 每月首日 00:15~00:19 窗口
        if (minuteOfHour >= 15 && minuteOfHour <= 19
                && Math.floorMod(day, s.getDaysPerMonth()) == 0 && (lastMonthly == null || lastMonthly != day)) {
            lastMonthlyDay.put(projectId, day);
            dispatchMacroTick(projectId, day, gameSecond, "月宏观 tick（00:15 槽）",
                    () -> tickMonthly(projectId, day, gameSecond));
        }
    }

    /**
     * 异步派发宏观 tick（R1）：标记已在 tickIfDue 置位，任务在虚拟线程执行；
     * 失败隔离零阻断（宏观是每日/旬/月级数据，错过可由下一周期自然补做——幂等）。
     */
    private void dispatchMacroTick(Long projectId, Long day, Long gameSecond, String label, Runnable task) {
        log.info("[宏观] {} 异步派发: project={} day={} 时刻={}", label, projectId, day, gameSecond);
        macroTasksInFlight.incrementAndGet();
        ExecutorService ex = macroExecutor();
        try {
            ex.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("[宏观] {} 执行失败（隔离继续，下一周期幂等补做）: project={} day={} : {}",
                            label, projectId, day, e.getMessage());
                } finally {
                    macroTasksInFlight.decrementAndGet();
                }
            });
        } catch (Exception e) {
            macroTasksInFlight.decrementAndGet();
            log.warn("[宏观] {} 异步提交失败（已跳过）: project={} day={} : {}", label, projectId, day, e.getMessage());
        }
    }

    /** 宏观 tick 在途任务计数（测试 awaitIdle 用；异步派发后任务完成前 >0）。 */
    private final java.util.concurrent.atomic.AtomicInteger macroTasksInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 等待在途宏观 tick 任务排空（测试/调试用：异步派发后断言前调用，保证确定性）。
     *
     * @param timeoutMs 最大等待毫秒
     */
    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (macroTasksInFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ==================== 每日 tick ====================

    /**
     * 每日推进：前一游戏日入账汇总 → 季节/天气 → 收成系数 → 经济供需 →
     * 民怨 → 普通 NPC 生计联动 → 极端季节事件 → 丰年/歉收闭环 → 人口统计模型 → 快照/帧简报。
     */
    @Transactional
    public void tickDaily(Long projectId, Long day, Long gameSecond) {
        try {
            MacroState st = macroStateService.stateOf(projectId);
            st.setGameDay(day);
            MacroState.Population pop = st.getPopulation();
            MacroState.Economy eco = st.getEconomy();
            MacroProperties.Initial ini = props.getInitial();
            MacroTemplate tpl = templateOf(projectId);
            double priceBase = ini.getGrainPriceBase();

            // ① 收成链：季节（固定绑定）+ 最近帧天气 → 收成系数（季节×天气×连续累积）
            String season = SeasonService.seasonOf(day * 86400L);
            String weather = recentFrameWeather(projectId, day);
            int dry = eco.getDryStreak() == null ? 0 : eco.getDryStreak();
            int flood = eco.getFloodStreak() == null ? 0 : eco.getFloodStreak();
            int snow = eco.getSnowStreak() == null ? 0 : eco.getSnowStreak();
            MacroHarvestEngine.HarvestResult hr = MacroHarvestEngine.step(
                    new MacroHarvestEngine.HarvestInput(season, weather, dry, flood, snow,
                            tpl.seasonFactorScale(), day));
            eco.setSeason(season);
            eco.setWeatherCondition(weather);
            eco.setHarvestFactor(hr.harvestFactor());
            eco.setDryStreak(hr.dryStreak());
            eco.setFloodStreak(hr.floodStreak());
            eco.setSnowStreak(hr.snowStreak());

            // ② 前一游戏日入账汇总
            Map<String, Double> deltas = macroStateService.aggregateDeltas(
                    projectId, Math.max(0, day - 1), Math.max(0, day - 1), "economy");
            double marketDelta = deltas.getOrDefault("market_activity", 0.0);
            double prodAdd = deltas.getOrDefault("grain_production", 0.0);
            double taxAdd = deltas.getOrDefault("tax_revenue", 0.0);
            double curAdd = deltas.getOrDefault("currency_supply", 0.0);

            // ③ 经济供需模型
            double techHarvest = eco.getTechHarvestBonus() == null ? 0.0 : eco.getTechHarvestBonus();
            double techTax = eco.getTechTaxBonus() == null ? 0.0 : eco.getTechTaxBonus();
            double effProductionBase = ini.getGrainProductionBase() * (1.0 + techHarvest);
            double effTaxPerCapita = ini.getTaxPerCapita() * (1.0 + techTax);
            MacroEconomyEngine.EconomyInput in = new MacroEconomyEngine.EconomyInput(
                    pop.getTotal(), eco.getGrainStock(), effProductionBase,
                    priceBase, ini.getPerCapitaConsumption(), effTaxPerCapita,
                    marketDelta, hr.harvestFactor(), eco.getGrainPrice(), eco.getMarketActivity(),
                    eco.getBankDeposit(), props.getThresholds().getCaravanChance(), day);
            MacroEconomyEngine.EconomyResult r = MacroEconomyEngine.step(in);
            eco.setGrainStock(r.grainStock() + prodAdd);
            eco.setGrainProduction(r.grainProduction());
            eco.setGrainPrice(r.grainPrice());
            eco.setMarketActivity(r.marketActivity());
            eco.setTaxRevenue(r.taxRevenue() + taxAdd);
            eco.setBankDeposit(r.bankDeposit());
            eco.setCurrencySupply(r.currencySupply() + curAdd);
            double priceRatio = priceBase > 0 ? eco.getGrainPrice() / priceBase : 1.0;
            eco.setPriceRatio(priceRatio);

            // ④ 民怨（生计压力 → 社会情绪：粮价/短缺压力驱动 + 自然缓释 + 事件冲击）
            double grievance = st.getGrievance() == null ? 0.0 : st.getGrievance();
            grievance += r.economicStress() * 30.0 - 2.0; // 压力×30 − 每日自然缓释 2
            if (hr.drought()) {
                grievance += 8;
            }
            if (hr.flood()) {
                grievance += 6;
            }
            if (hr.blizzard()) {
                grievance += 10;
            }
            st.setGrievance(clamp(grievance, 0.0, 100.0));

            // ⑤ 极端季节事件（阈值 AI 叙事 + 因果链上下文；事件码=通用语义码
            // drought/flood/blizzard，展示名由模板 extremeEventName 承载）
            int seq = 0;
            if (hr.drought()) {
                emitWithContext(projectId, day, gameSecond, "economy",
                        "drought", "harvest_factor",
                        null, hr.harvestFactor(), 0.35, null, ++seq, st);
            }
            if (hr.flood()) {
                emitWithContext(projectId, day, gameSecond, "economy",
                        "flood", "harvest_factor",
                        null, hr.harvestFactor(), 0.3, null, ++seq, st);
            }
            if (hr.blizzard()) {
                emitWithContext(projectId, day, gameSecond, "economy",
                        "blizzard", "harvest_factor",
                        null, hr.harvestFactor(), 0.3, null, ++seq, st);
            }

            // ⑥ 经济规则事件（seq 继续）+ 丰年/歉收正向/负向环
            for (String evt : r.events()) {
                emitDailyEvent(projectId, day, gameSecond, evt, r, ++seq);
                if ("harvest_good".equals(evt)) {
                    eco.setMarketActivity(MacroEconomyEngine.clamp(eco.getMarketActivity() + 5.0, 5.0, 100.0));
                    eco.setBankDeposit(eco.getBankDeposit() + 100.0);
                    st.setGrievance(clamp((st.getGrievance() == null ? 0.0 : st.getGrievance()) - 5.0, 0.0, 100.0));
                } else if ("harvest_bad".equals(evt)) {
                    eco.setMarketActivity(MacroEconomyEngine.clamp(eco.getMarketActivity() - 3.0, 5.0, 100.0));
                    st.setGrievance(clamp((st.getGrievance() == null ? 0.0 : st.getGrievance()) + 5.0, 0.0, 100.0));
                }
            }
            // 收成结果记录（每旬势力 tick 用：丰年得人心/歉收失人心）
            double prodBaseRef = ini.getGrainProductionBase();
            if (prodBaseRef > 0) {
                eco.setHarvestOutcome(r.grainProduction() / prodBaseRef >= 1.1 ? 1
                        : r.grainProduction() / prodBaseRef <= 0.9 ? -1 : 0);
            }

            // ⑦ 人口统计模型
            double plagueChance = props.getThresholds().getPlagueChance()
                    * (eco.getTechPlagueReduction() == null ? 1.0 : eco.getTechPlagueReduction());
            MacroPopulationEngine.PopulationResult pr = MacroPopulationEngine.step(
                    pop.getTotal(), ini.getBirthRate(), ini.getDeathRate(), ini.getMigrationRate(),
                    r.economicStress(), plagueChance, day);
            pop.setTotal(pr.total());
            pop.setBirths(pr.births());
            pop.setDeaths(pr.deaths());
            pop.setMigrationNet(pr.migrationNet());
            for (String evt : pr.events()) {
                emitPopulationEvent(projectId, day, gameSecond, evt, pr, ++seq);
            }
            if (pr.migrationNet() <= -5) {
                macroEventService.emit(projectId, day, gameSecond, "population", "migration_wave",
                        "population", (double) pop.getTotal() - pr.migrationNet(), (double) pop.getTotal(),
                        Math.abs(pr.migrationNet()) / 300.0, null, ++seq);
            }

            // ⑧ 生计联动 v2：粮价 → 普通 NPC 状态参数（hunger/mood）
            if (npcStateService != null && priceRatio > 0) {
                try {
                    npcStateService.applyMacroLivelihoodAdjust(projectId, priceRatio);
                } catch (Exception e) {
                    log.warn("[宏观] 生计联动失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }

            // ⑨ 快照（daily 行）+ 帧「社会宏观」简报（回灌路①）
            macroStateService.saveSnapshot(projectId, st, ActorWorldMacroSnapshot.TICK_DAILY, gameSecond);
            macroFeedbackService.writeFrameSummary(projectId, st, ActorWorldMacroSnapshot.TICK_DAILY);
            // ⑨′ /：中观自发成组扫描 + 二阶涌现检测（窗口内同类行为聚集；
            // 未装配[单测]跳过零阻断）
            if (mesoGroupEngine != null) {
                try {
                    mesoGroupEngine.scanEmergentGroups(projectId, day - 1,
                            props.getSchedule().getDaysPerXun(), gameSecond);
                } catch (Exception e) {
                    log.warn("[宏观] 自发成组扫描失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }
            if (macroEmergenceService != null) {
                try {
                    macroEmergenceService.scanDaily(projectId, day, gameSecond);
                } catch (Exception e) {
                    log.warn("[宏观] 二阶涌现扫描失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }
            // ⑩ 全链路 trace：输入摘要 + 引擎参数 + 事件/回灌审计逐跳落库（失败零阻断）
            recordTrace(projectId, day, gameSecond, ActorWorldMacroSnapshot.TICK_DAILY,
                    traceDailyInput(day, season, weather, hr, deltas),
                    traceDailyParams(st, eco, pop, r, pr));
            lastDailyDay.put(projectId, day);
            log.info("[宏观] 每日 tick 完成: project={} day={} 季节={} 天气={} 收成系数={} 粮价={} 民怨={} 事件={}",
                    projectId, day, season, weather, hr.harvestFactor(), eco.getGrainPrice(),
                    Math.round(st.getGrievance() == null ? 0 : st.getGrievance()),
                    r.events().size() + pr.events().size() + (hr.drought() ? 1 : 0)
                            + (hr.flood() ? 1 : 0) + (hr.blizzard() ? 1 : 0));
        } catch (Exception e) {
            log.warn("[宏观] 每日 tick 失败（隔离继续）: project={} day={} : {}", projectId, day, e.getMessage());
        }
    }

    /** 安全取模板（解析失败/服务异常回退默认仙侠模板——零阻断）。 */
    private MacroTemplate templateOf(Long projectId) {
        try {
            MacroTemplate t = macroStateService.templateOf(projectId);
            if (t != null) {
                return t;
            }
        } catch (Exception ignored) {
            // 回退默认
        }
        return MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
    }

    /** 最近帧天气（≤目标日的最近一帧首区域 condition；无帧/解析失败返回 null——零阻断）。 */
    private String recentFrameWeather(Long projectId, Long day) {
        try {
            ActorWorldFrame f = frameRepository
                    .findTopByProjectIdAndGameDayLessThanEqualOrderByGameDayDesc(projectId, day)
                    .orElse(null);
            if (f == null || f.getWeatherJson() == null || f.getWeatherJson().isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(f.getWeatherJson());
            if (!root.isObject() || root.size() == 0) {
                return null;
            }
            // 首区域（有序对象首键——世界帧 weather_json 按场景/区域组织；Jackson 3 properties()）
            java.util.Iterator<Map.Entry<String, JsonNode>> it = root.properties().iterator();
            if (!it.hasNext()) {
                return null;
            }
            JsonNode region = it.next().getValue();
            if (region == null || !region.isObject()) {
                return null;
            }
            JsonNode cond = region.get("condition");
            return cond == null || !cond.isTextual() ? null : cond.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 带因果链上下文的宏观事件产出。 */
    private void emitWithContext(Long projectId, Long day, Long gameSecond, String macroType,
                                 String eventType, String indicator, Double oldValue, Double newValue,
                                 Double magnitude, String namedNpc, int seq, MacroState st) {
        Map<String, Object> ctx = contextOf(st, projectId, day);
        macroEventService.emitDetailed(projectId, day, gameSecond, macroType, eventType, indicator,
                oldValue, newValue, magnitude, namedNpc, seq, ctx);
    }

    /** 因果链上下文（季节/天气/收成系数/民怨/库存/入账——AI 叙事素材；单位取模板语义）。 */
    private Map<String, Object> contextOf(MacroState st, Long projectId, Long day) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        MacroState.Economy eco = st.getEconomy();
        MacroTemplate tpl = templateOf(projectId);
        ctx.put("季节", eco.getSeason() == null ? "未知" : SeasonService.seasonDisplayName(eco.getSeason()));
        ctx.put("天气", eco.getWeatherCondition() == null ? "未知" : eco.getWeatherCondition());
        if (eco.getHarvestFactor() != null) {
            ctx.put("收成系数", eco.getHarvestFactor());
        }
        if (st.getGrievance() != null) {
            ctx.put("民怨", Math.round(st.getGrievance()) + "/100");
        }
        ctx.put("库存", eco.getGrainStock() + " " + (tpl.resourceUnit().isBlank() ? "单位" : tpl.resourceUnit()));
        try {
            Map<String, Double> deltas = macroStateService.aggregateDeltas(
                    projectId, Math.max(0, day - 3), Math.max(0, day - 1), "economy");
            if (!deltas.isEmpty()) {
                ctx.put("近三日入账", deltas.toString());
            }
        } catch (Exception ignored) {
            // 入账不可用不阻断
        }
        return ctx;
    }

    /** 经济规则事件 → 宏观事件（含粮价暴涨阈值 AI 叙事 + 商队/钱庄；事件码=通用语义码）。 */
    private void emitDailyEvent(Long projectId, Long day, Long gameSecond, String evt,
                                MacroEconomyEngine.EconomyResult r, int seq) {
        String indicator;
        Double oldValue;
        Double newValue;
        Double magnitude;
        switch (evt) {
            case "harvest_good", "harvest_bad" -> {
                indicator = "grain_production";
                oldValue = null;
                newValue = r.grainProduction();
                magnitude = Math.abs(r.grainProduction() - 2.7) / 2.7;
            }
            case "price_surge" -> {
                indicator = "grain_price";
                oldValue = null;
                newValue = r.grainPrice();
                magnitude = r.priceChange();
            }
            case "caravan" -> {
                indicator = "market_activity";
                oldValue = null;
                newValue = r.marketActivity();
                magnitude = 0.05;
            }
            case "bank_run" -> {
                indicator = "bank_deposit";
                oldValue = null;
                newValue = r.bankDeposit();
                magnitude = 0.2;
            }
            default -> {
                return;
            }
        }
        macroEventService.emit(projectId, day, gameSecond, "economy", evt, indicator,
                oldValue, newValue, magnitude, null, seq);
    }

    /** 人口规则事件 → 宏观事件（瘟疫/饥荒/流民：具名 NPC 代表挂钩；事件码=通用语义码）。 */
    private void emitPopulationEvent(Long projectId, Long day, Long gameSecond, String evt,
                                     MacroPopulationEngine.PopulationResult pr, int seq) {
        switch (evt) {
            case "plague" -> {
                ActorCharacter npc = macroStateService.pickNamedNpc(projectId, day);
                macroStateService.markNamedNpcAffected(projectId, npc, "染上时疫，卧床休养（宏观瘟疫）");
                macroEventService.emit(projectId, day, gameSecond, "population", evt,
                        "population", (double) (pr.total() + pr.deaths()), (double) pr.total(),
                        0.05, npc == null ? null : npc.getName(), seq);
            }
            case "famine" -> {
                ActorCharacter npc = macroStateService.pickNamedNpc(projectId, day + 1);
                macroStateService.markNamedNpcAffected(projectId, npc, "饥荒难捱，面黄肌瘦（宏观饥荒）");
                macroEventService.emit(projectId, day, gameSecond, "population", evt,
                        "population", (double) (pr.total() + pr.deaths()), (double) pr.total(),
                        0.08, npc == null ? null : npc.getName(), seq);
            }
            case "refugee_influx" -> {
                ActorCharacter npc = macroStateService.pickNamedNpc(projectId, day + 2);
                macroStateService.markNamedNpcAffected(projectId, npc, "收留外来流民，家中多了几口人");
                macroEventService.emit(projectId, day, gameSecond, "population", evt,
                        "population", (double) (pr.total() - pr.migrationNet()), (double) pr.total(),
                        0.03, npc == null ? null : npc.getName(), seq);
            }
            default -> {
            }
        }
    }

    // ==================== 每旬 tick（势力 + 民怨/收成修正 + 赈济/民变闭环） ====================

    /** 每旬推进：近一旬入账贡献 → 影响力计算 + 民怨侵蚀/收成修正 + 关系状态机 → 赈济/民变 → 快照。 */
    @Transactional
    public void tickFaction(Long projectId, Long day, Long gameSecond) {
        try {
            MacroState st = macroStateService.stateOf(projectId);
            st.setGameDay(day);
            int xun = props.getSchedule().getDaysPerXun();
            Map<String, Double> contrib = macroStateService.aggregateDeltas(
                    projectId, Math.max(0, day - xun), Math.max(0, day - 1), "faction");
            Map<String, Integer> prev = new LinkedHashMap<>();
            for (MacroState.Faction f : st.getFactions().values()) {
                prev.put(f.getName(), (int) Math.round(f.getInfluence()));
            }
            double grievance = st.getGrievance() == null ? 0.0 : st.getGrievance();
            int harvestOutcome = st.getEconomy().getHarvestOutcome() == null
                    ? 0 : st.getEconomy().getHarvestOutcome();
            MacroFactionEngine.FactionInput in = new MacroFactionEngine.FactionInput(
                    st.getFactions(), contrib, Map.of(), prev, grievance, harvestOutcome, day);
            MacroFactionEngine.FactionResult fr = MacroFactionEngine.step(in);

            // 事件处理（seq 递增防幂等键撞车；事件码=通用语义码）
            int seq = 0;
            for (String[] ev : fr.events()) {
                String type = ev[0];
                String nameA = ev[1];
                MacroState.Faction f = st.getFactions().get(nameA);
                switch (type) {
                    case "relief" -> {
                        // 负反馈环：开仓 → 粮价回落 + 库存减 + 民怨降 + 当权影响力回升
                        MacroState.Economy eco = st.getEconomy();
                        eco.setGrainStock(Math.max(0.0, eco.getGrainStock() - 50.0));
                        eco.setGrainPrice(MacroEconomyEngine.round2(eco.getGrainPrice() * 0.9));
                        st.setGrievance(clamp(grievance - 25.0, 0.0, 100.0));
                        if (f != null) {
                            f.setInfluence(MacroEconomyEngine.clamp(f.getInfluence() + 3.0, 0.0, 100.0));
                        }
                        emitWithContext(projectId, day, gameSecond, "economy", "relief",
                                "grain_price", null, eco.getGrainPrice(), 0.1, null, ++seq, st);
                    }
                    case "revolt_risk" -> {
                        // 民怨≥80：怨气发泄 + 市集受挫（重大 AI 叙事 severity 5）
                        st.setGrievance(clamp(grievance - 15.0, 0.0, 100.0));
                        MacroState.Economy eco = st.getEconomy();
                        eco.setMarketActivity(MacroEconomyEngine.clamp(eco.getMarketActivity() - 10.0, 5.0, 100.0));
                        emitWithContext(projectId, day, gameSecond, "culture", "revolt_risk",
                                "grievance", grievance, st.getGrievance(), 0.3, null, ++seq, st);
                    }
                    default -> {
                        // 势力易主/家族决裂/家族联姻
                        double magnitude = 0.35;
                        macroEventService.emit(projectId, day, gameSecond, "faction", type,
                                ev[2] == null ? nameA : nameA + "↔" + ev[2],
                                null, f == null ? null : f.getInfluence(), magnitude, null, ++seq);
                    }
                }
            }
            // 秘术突破压制：影响力最低的势力受压制（通用语义——不指向任何具体势力名，
            // 弱势/边缘势力最易受秘术压制冲击；本旬一次，随后清零）
            MacroState.Economy eco = st.getEconomy();
            if (eco.getTechArrayBonus() != null && eco.getTechArrayBonus() > 0) {
                MacroState.Faction weakest = null;
                for (MacroState.Faction f : st.getFactions().values()) {
                    if (weakest == null || f.getInfluence() < weakest.getInfluence()) {
                        weakest = f;
                    }
                }
                if (weakest != null) {
                    weakest.setInfluence(MacroEconomyEngine.clamp(
                            weakest.getInfluence() - eco.getTechArrayBonus(), 0.0, 100.0));
                }
                eco.setTechArrayBonus(0.0);
            }
            macroStateService.saveSnapshot(projectId, st, ActorWorldMacroSnapshot.TICK_FACTION, gameSecond);
            macroFeedbackService.writeFrameSummary(projectId, st, ActorWorldMacroSnapshot.TICK_FACTION);
            // 全链路 trace：近旬入账贡献 + 民怨/收成输入 → 势力结果 + 事件/回灌审计
            Map<String, Object> fin = new LinkedHashMap<>();
            fin.put("contrib", contrib.isEmpty() ? Map.of() : contrib);
            fin.put("grievance", grievance);
            fin.put("harvestOutcome", harvestOutcome);
            Map<String, Object> fparams = new LinkedHashMap<>();
            fparams.put("grievance", st.getGrievance());
            Map<String, Object> factions = new LinkedHashMap<>();
            for (MacroState.Faction f : st.getFactions().values()) {
                factions.put(f.getName(), f.getInfluence());
            }
            fparams.put("factions", factions);
            recordTrace(projectId, day, gameSecond, ActorWorldMacroSnapshot.TICK_FACTION, fin, fparams);
            lastFactionDay.put(projectId, day);
            // ：每旬例行组织指令（组织对成员的日常期待；未装配[单测]跳过零阻断）
            if (mesoCoordinationService != null) {
                try {
                    mesoCoordinationService.issueRoutineDirectives(projectId, gameSecond);
                } catch (Exception e) {
                    log.warn("[宏观] 每旬例行指令下发失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }
            log.info("[宏观] 每旬势力 tick 完成: project={} day={} 势力={} 民怨={} 事件={}",
                    projectId, day, st.getFactions().size(), Math.round(st.getGrievance() == null ? 0 : st.getGrievance()),
                    fr.events().size());
        } catch (Exception e) {
            log.warn("[宏观] 每旬势力 tick 失败（隔离继续）: project={} day={} : {}", projectId, day, e.getMessage());
        }
    }

    // ==================== 每月 tick ====================

    /** 每月推进：文化（舆论/信仰/习俗）+ 科技（累积/突破/衰退失传）+ 阈值 AI 叙事 + 联动效果。 */
    @Transactional
    public void tickMonthly(Long projectId, Long day, Long gameSecond) {
        try {
            MacroState st = macroStateService.stateOf(projectId);
            st.setGameDay(day);
            int month = props.getSchedule().getDaysPerMonth();
            long from = Math.max(0, day - month);
            long to = Math.max(0, day - 1);

            // ============ ① 文化：入账聚合 + 社会级信息（宏观事件）聚合 ============
            Map<String, Double> cultureDeltas = macroStateService.aggregateDeltas(projectId, from, to, "culture");
            List<String> extremeEvents = new ArrayList<>();
            List<String> packetTopics = new ArrayList<>();
            try {
                List<ActorWorldMacroEvent> monthEvents = eventRepository
                        .findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, from, to);
                for (ActorWorldMacroEvent ev : monthEvents) {
                    extremeEvents.add(ev.getEventType());
                    String topic = topicOf(ev.getEventType());
                    if (topic != null && !packetTopics.contains(topic)) {
                        packetTopics.add(topic);
                    }
                }
            } catch (Exception e) {
                log.warn("[宏观] 月度宏观事件聚合失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
            // ⚠️ 前置修复（月度 tick NPE， 极端态）：输入集合字段空值防御归一化——
            // 快照反序列化异常/极端状态可能使 issues/beliefs/fashions/techLevels/techPrevLevels/
            // techNoInputMonths/population 为 null，全部归一为安全空集合后再喂引擎，
            // 引擎结果回写 st（下游 saveSnapshot/buildSummary/trace 恒安全），绝不抛 NPE
            List<MacroState.Issue> issueList = st.getIssues() == null ? new ArrayList<>() : st.getIssues();
            Map<String, Double> beliefs = st.getBeliefs() == null ? new LinkedHashMap<>() : st.getBeliefs();
            List<String> fashions = st.getFashions() == null ? new ArrayList<>() : st.getFashions();
            Map<String, Long> fashionSinceDay = st.getFashionSinceDay() == null
                    ? new LinkedHashMap<>() : st.getFashionSinceDay();
            Map<String, Double> techLevels = st.getTechLevels() == null ? new LinkedHashMap<>() : st.getTechLevels();
            Map<String, Integer> techNoInput = st.getTechNoInputMonths() == null
                    ? new LinkedHashMap<>() : st.getTechNoInputMonths();
            Map<String, Double> techPrev = st.getTechPrevLevels() == null
                    ? new LinkedHashMap<>() : st.getTechPrevLevels();
            Map<String, Long> occupations = (st.getPopulation() == null
                    || st.getPopulation().getOccupations() == null)
                    ? new LinkedHashMap<>() : st.getPopulation().getOccupations();
            MacroCultureEngine.CultureInput cin = new MacroCultureEngine.CultureInput(
                    issueList, beliefs, fashions, fashionSinceDay,
                    cultureDeltas, packetTopics, extremeEvents, day, day);
            MacroCultureEngine.CultureResult cr = MacroCultureEngine.step(cin);
            // 文化结果回写（归一化后的安全引用；null 原始态被安全空集合取代）
            st.setIssues(cr.issues());
            st.setBeliefs(cr.beliefs());
            st.setFashions(cr.fashions());
            st.setFashionSinceDay(cr.fashionSinceDay());

            // ============ ② 科技：入账聚合 + 人才加成 + 累积/突破/衰退失传 ============
            Map<String, Double> techDeltas = macroStateService.aggregateDeltas(projectId, from, to, "tech");
            MacroTechEngine.TechInput tin = new MacroTechEngine.TechInput(
                    techLevels, techDeltas, techNoInput, techPrev, occupations, day);
            MacroTechEngine.TechResult tr = MacroTechEngine.step(tin);
            st.setTechLevels(tr.techLevels());
            st.setTechNoInputMonths(tr.techNoInputMonths());

            // ============ ③ 事件产出（阈值 AI 叙事 + 因果上下文）+ 突破联动效果 ============
            // 事件码=通用语义码（opinion_shift/faith_crisis/new_custom/rumor/tech_breakthrough/tech_lost）
            int seq = 0;
            for (String evt : cr.events()) {
                if ("opinion_shift".equals(evt)) {
                    MacroState.Issue top = st.getIssues().stream()
                            .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity)).orElse(null);
                    emitWithContext(projectId, day, gameSecond, "culture", evt,
                            top == null ? null : top.getTopic(), null,
                            top == null ? null : top.getIntensity(), 0.3, null, ++seq, st);
                } else if ("faith_crisis".equals(evt)) {
                    String faith = st.getBeliefs().entrySet().stream()
                            .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("信仰");
                    emitWithContext(projectId, day, gameSecond, "culture", evt,
                            faith, null, st.getBeliefs().getOrDefault(faith, 0.0), 0.2, null, ++seq, st);
                } else if ("new_custom".equals(evt)) {
                    String fashion = st.getFashions().isEmpty() ? "新习俗" : st.getFashions().get(st.getFashions().size() - 1);
                    emitWithContext(projectId, day, gameSecond, "culture", evt,
                            fashion, null, null, 0.05, null, ++seq, st);
                } else if ("rumor".equals(evt)) {
                    emitWithContext(projectId, day, gameSecond, "culture", evt,
                            packetTopics.isEmpty() ? "街巷流言" : packetTopics.get(0),
                            null, null, 0.1, null, ++seq, st);
                }
            }
            MacroState.Economy eco = st.getEconomy();
            for (String evt : tr.events()) {
                if (evt.startsWith("tech_breakthrough:")) {
                    String dim = evt.substring("tech_breakthrough:".length());
                    // 联动效果：农业→产量+5% / 医术→瘟疫×0.7 / 工艺→税收+5% / 秘术→弱势势力压制（tickFaction 生效）
                    switch (dim) {
                        case "agriculture" -> eco.setTechHarvestBonus(
                                (eco.getTechHarvestBonus() == null ? 0.0 : eco.getTechHarvestBonus()) + 0.05);
                        case "medicine" -> eco.setTechPlagueReduction(0.7);
                        case "craft" -> eco.setTechTaxBonus(
                                (eco.getTechTaxBonus() == null ? 0.0 : eco.getTechTaxBonus()) + 0.05);
                        case "array" -> eco.setTechArrayBonus(
                                (eco.getTechArrayBonus() == null ? 0.0 : eco.getTechArrayBonus()) + 5.0);
                        default -> {
                        }
                    }
                    emitWithContext(projectId, day, gameSecond, "tech", evt,
                            templateOf(projectId).techName(dim),
                            null, st.getTechLevels().getOrDefault(dim, 0.0), 0.3, null, ++seq, st);
                } else if (evt.startsWith("tech_lost:")) {
                    String dim = evt.substring("tech_lost:".length());
                    emitWithContext(projectId, day, gameSecond, "tech", evt,
                            templateOf(projectId).techName(dim),
                            null, st.getTechLevels().getOrDefault(dim, 0.0), 0.4, null, ++seq, st);
                }
            }

            // ④ 上月值快照（下月突破判定基准）
            st.setTechPrevLevels(new LinkedHashMap<>(st.getTechLevels()));

            macroStateService.saveSnapshot(projectId, st, ActorWorldMacroSnapshot.TICK_MONTHLY, gameSecond);
            macroFeedbackService.writeFrameSummary(projectId, st, ActorWorldMacroSnapshot.TICK_MONTHLY);
            // 全链路 trace：文化/科技入账 + 话题/事件输入 → 议题/信仰/科技结果 + 审计
            Map<String, Object> min = new LinkedHashMap<>();
            min.put("cultureDeltas", cultureDeltas.isEmpty() ? Map.of() : cultureDeltas);
            min.put("techDeltas", techDeltas.isEmpty() ? Map.of() : techDeltas);
            min.put("packetTopics", packetTopics);
            min.put("extremeEvents", extremeEvents);
            Map<String, Object> mparams = new LinkedHashMap<>();
            Map<String, Object> issues = new LinkedHashMap<>();
            for (MacroState.Issue is : st.getIssues()) {
                issues.put(is.getTopic(), is.getIntensity());
            }
            mparams.put("issues", issues);
            mparams.put("beliefs", st.getBeliefs() == null ? Map.of() : st.getBeliefs());
            mparams.put("fashions", st.getFashions() == null ? List.of() : st.getFashions());
            mparams.put("techLevels", st.getTechLevels() == null ? Map.of() : st.getTechLevels());
            recordTrace(projectId, day, gameSecond, ActorWorldMacroSnapshot.TICK_MONTHLY, min, mparams);
            lastMonthlyDay.put(projectId, day);
            log.info("[宏观] 每月文化科技 tick 完成: project={} day={} 议题={} 科技={} 事件={}",
                    projectId, day, st.getIssues().size(), st.getTechLevels().size(),
                    cr.events().size() + tr.events().size());
        } catch (Exception e) {
            // ⚠️ 前置修复：打印完整堆栈（原仅 e.getMessage()——NPE 空值解包时 message 为 null，
            // 冒烟只见「异常 null」无法定位；堆栈可诊断性修复）
            log.warn("[宏观] 每月 tick 失败（隔离继续）: project={} day={} : {}", projectId, day, e.toString(), e);
        }
    }

    // ==================== 全链路 trace 辅助 ====================

    /** trace 落库（未装配跳过；内部已隔离失败，tick 主流程零阻断）。 */
    private void recordTrace(Long projectId, Long day, Long gameSecond, String tickType,
                             Map<String, Object> input, Map<String, Object> params) {
        if (macroTraceService == null) {
            return;
        }
        try {
            macroTraceService.record(projectId, day, gameSecond, tickType, input, params, true);
        } catch (Exception e) {
            log.warn("[宏观] trace 记录失败（隔离继续）: project={} day={} : {}", projectId, day, e.getMessage());
        }
    }

    /** 每日 tick 输入摘要（回放「入账→引擎」跳：入账聚合 + 季节/天气 + 连续累积串）。 */
    private Map<String, Object> traceDailyInput(Long day, String season, String weather,
                                                MacroHarvestEngine.HarvestResult hr,
                                                Map<String, Double> deltas) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("day", day);
        in.put("season", season);
        in.put("weather", weather == null ? "缺帧" : weather);
        in.put("dryStreak", hr.dryStreak());
        in.put("floodStreak", hr.floodStreak());
        in.put("snowStreak", hr.snowStreak());
        in.put("deltas", deltas.isEmpty() ? Map.of() : deltas);
        return in;
    }

    /** 每日 tick 引擎参数（收成/经济/民怨/人口——回放「引擎」跳）。 */
    private Map<String, Object> traceDailyParams(MacroState st, MacroState.Economy eco,
                                                 MacroState.Population pop,
                                                 MacroEconomyEngine.EconomyResult r,
                                                 MacroPopulationEngine.PopulationResult pr) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("harvestFactor", eco.getHarvestFactor());
        p.put("grainPrice", eco.getGrainPrice());
        p.put("priceRatio", eco.getPriceRatio());
        p.put("grainStock", eco.getGrainStock());
        p.put("marketActivity", eco.getMarketActivity());
        p.put("taxRevenue", eco.getTaxRevenue());
        p.put("bankDeposit", eco.getBankDeposit());
        p.put("currencySupply", eco.getCurrencySupply());
        p.put("grievance", st.getGrievance());
        p.put("population", pop.getTotal());
        p.put("births", pop.getBirths());
        p.put("deaths", pop.getDeaths());
        p.put("migrationNet", pop.getMigrationNet());
        return p;
    }

    /**
     * 宏观事件码 → 舆论议题话题（社会级信息聚合口径；事件码=通用语义码， 通用化；
     * 无映射返回 null）。
     */
    static String topicOf(String eventCode) {
        if (eventCode == null) {
            return null;
        }
        return switch (eventCode) {
            case "price_surge", "harvest_good", "harvest_bad", "relief" -> "物价";
            case "drought" -> "天旱";
            case "flood" -> "水患";
            case "blizzard" -> "雪灾";
            case "revolt_risk" -> "民怨";
            case "plague" -> "时疫";
            case "famine" -> "饥荒";
            case "opinion_shift" -> "流言";
            case "tech_breakthrough", "tech_lost" -> "技艺";
            case "faith_crisis" -> "信仰";
            default -> null;
        };
    }

    /** 钳制到 [min, max]。 */
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 配置引用（测试用）。 */
    public MacroProperties properties() {
        return props;
    }
}
