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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroSnapshotRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.vo.MacroDeltaVO;
import com.holzyn.actor.domain.world.vo.MacroEventVO;
import com.holzyn.actor.domain.world.vo.MacroSnapshotVO;
import com.holzyn.actor.domain.world.vo.MacroStateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 宏观社会演化 · 宏观状态服务。
 * <p>职责：宏观状态的权威持有者——内存态（每项目一个 {@link MacroState}）+ 快照持久化
 * （actor_world_macro_snapshot，tick 时点全量落库，回看/恢复用）+ 初始快照（世界观
 * 确定性推导）+ 入账流水聚合（每日经济/每旬势力汇总）+ 具名 NPC 代表挂钩（减员/增员事件
 * 确定性选取具名普通 NPC 标记影响，）+ 快照滚动上限清理。</p>
 * <p>加载语义：惰性——首次访问从该项目最新 daily 快照行反序列化恢复；无快照 → 按世界观
 * 默认值构建（300 口人/模板骨架势力/初始议题/四维科技）并落 init 快照行（当前游戏日）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroStateService {

    private final MacroProperties props;
    private final ActorWorldMacroSnapshotRepository snapshotRepository;
    private final ActorWorldMacroDeltaRepository deltaRepository;
    private final ActorWorldMacroEventRepository eventRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final WorldClockService worldClockService;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final MacroTemplateService macroTemplateService;
    private final ObjectMapper objectMapper;

    /**
     * 中观组引擎（注入：势力三源通用派生——世界观解析/模板骨架/运行时涌现；
     * 字段注入保持构造器不变，null=未装配回退模板骨架通用命名[单测零改动]）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MesoGroupEngine mesoGroupEngine;

    /**
     * 作息运行态访问器（{@code actor_state}；普通 NPC 的 lastAction 已从档案迁出）。
     * <p><b>为什么用字段注入</b>：本类由 {@code MacroStateServiceTest} 直接 new 构造（9 参数），
     * 构造器参数会破坏该单测；字段注入保持构造器不变（对齐本类既有 {@code mesoGroupEngine} 范式），
     * {@code null}=未装配时降级为"跳过运行态写入"（零阻断）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    /** 进程内存态：projectId → 宏观状态（单实例约定，重启从快照恢复） */
    private final Map<Long, MacroState> states = new java.util.concurrent.ConcurrentHashMap<>();

    // ==================== 状态加载/初始化 ====================

    /**
     * 获取项目宏观状态（惰性加载 + 跨日自动推进基准；并发安全 computeIfAbsent）。
     */
    public MacroState stateOf(Long projectId) {
        return states.computeIfAbsent(projectId, this::loadOrInit);
    }

    /** 惰性加载：最新 daily 快照恢复 → 缺失按世界观默认构建 + 落 init 行。 */
    private MacroState loadOrInit(Long projectId) {
        Optional<ActorWorldMacroSnapshot> latest =
                snapshotRepository.findTopByProjectIdAndTickTypeOrderByGameDayDesc(projectId,
                        ActorWorldMacroSnapshot.TICK_DAILY);
        if (latest.isPresent() && latest.get().getIndicatorsJson() != null) {
            MacroState st = fromJson(latest.get().getIndicatorsJson());
            if (st != null) {
                st.setProjectId(projectId);
                log.debug("[宏观] 从快照恢复状态: project={} day={}", projectId, st.getGameDay());
                return st;
            }
        }
        // 无快照 → 世界观默认构建（模板语义：人口池/骨架势力/初始议题/四维科技）+ 落 init 行
        MacroState st = defaultState(projectId);
        Long day = currentGameDay(projectId);
        if (day != null) {
            st.setGameDay(day);
            saveSnapshot(projectId, st, ActorWorldMacroSnapshot.TICK_INIT, day * 86400L);
            log.info("[宏观] 宏观状态初始化（世界观默认值）: project={} day={} 人口={}",
                    projectId, day, st.getPopulation().getTotal());
        }
        return st;
    }

    /**
     * 手动初始化/重置宏观状态（POST /macro/initialize）：世界观默认值 + 可选覆盖参数 → 内存态
     * 重建 + 落 init 快照行（当前游戏日；覆盖已推进状态前请谨慎——一般只在项目初始化/测试时调用）。
     *
     * @param projectId 项目 ID
     *
     * @param overrides 覆盖参数（population/grainPriceBase/grainStock/grainProductionBase/
     * marketActivity/bankDeposit/currencySupply 可空）
     *
     * @return 初始化后的状态
     */
    @Transactional
    public MacroState initialize(Long projectId, Map<String, Object> overrides) {
        MacroState st = defaultState(projectId);
        if (overrides != null) {
            applyOverrides(st, overrides);
        }
        Long day = currentGameDay(projectId);
        if (day != null) {
            st.setGameDay(day);
        }
        states.put(projectId, st);
        saveSnapshot(projectId, st, ActorWorldMacroSnapshot.TICK_INIT,
                day == null ? 0L : day * 86400L);
        log.info("[宏观] 宏观状态手动初始化: project={} 人口={} 粮价基准={}",
                projectId, st.getPopulation().getTotal(), st.getEconomy().getGrainPrice());
        return st;
    }

    /** 覆盖参数应用（数值覆盖，非法忽略）。 */
    private void applyOverrides(MacroState st, Map<String, Object> overrides) {
        if (overrides.containsKey("population") && overrides.get("population") instanceof Number n) {
            st.getPopulation().setTotal(Math.max(1L, n.longValue()));
        }
        MacroState.Economy e = st.getEconomy();
        if (overrides.containsKey("grainPriceBase") && overrides.get("grainPriceBase") instanceof Number n) {
            e.setGrainPrice(Math.max(1.0, n.doubleValue()));
        }
        if (overrides.containsKey("grainStock") && overrides.get("grainStock") instanceof Number n) {
            e.setGrainStock(Math.max(0.0, n.doubleValue()));
        }
        if (overrides.containsKey("grainProductionBase") && overrides.get("grainProductionBase") instanceof Number n) {
            e.setGrainProduction(Math.max(0.0, n.doubleValue()));
        }
        if (overrides.containsKey("marketActivity") && overrides.get("marketActivity") instanceof Number n) {
            e.setMarketActivity(MacroEconomyEngine.clamp(n.doubleValue(), 5.0, 100.0));
        }
        if (overrides.containsKey("bankDeposit") && overrides.get("bankDeposit") instanceof Number n) {
            e.setBankDeposit(Math.max(0.0, n.doubleValue()));
        }
        if (overrides.containsKey("currencySupply") && overrides.get("currencySupply") instanceof Number n) {
            e.setCurrencySupply(Math.max(0.0, n.doubleValue()));
        }
    }

    /**
     * 世界观默认状态（农耕硬编码 → 起按用户指示「对应的修改与重构」：农耕
     * 古代硬编码语义全部模板化——人口池/职业分布/初始议题/禁忌/流行由世界观模板
     * {@link MacroTemplate} 决定，文明形态 × 力量体系双维度，模板为通用语义无特定世界内容）。
     */
    public MacroState defaultState(Long projectId) {
        MacroState st = new MacroState();
        st.setProjectId(projectId);
        MacroProperties.Initial ini = props.getInitial();
        // 模板解析（手动覆盖优先，否则按世界观设定文本关键词推导）
        MacroTemplate tpl = templateOf(projectId);
        st.setTemplateId(tpl.id());

        // 人口（模板文明形态 → 职业分布）
        st.getPopulation().setTotal(ini.getPopulation());
        st.getPopulation().setOccupations(occupationsOf(tpl));

        // 经济（六指标初始）
        MacroState.Economy eco = st.getEconomy();
        eco.setGrainStock(ini.getGrainStock());
        eco.setGrainProduction(ini.getGrainProductionBase());
        eco.setGrainPrice(ini.getGrainPriceBase());
        eco.setMarketActivity(ini.getMarketActivity());
        eco.setTaxRevenue(ini.getPopulation() * ini.getTaxPerCapita() * (ini.getMarketActivity() / 100.0));
        eco.setBankDeposit(ini.getBankDeposit());
        eco.setCurrencySupply(ini.getCurrencySupply());
        // 初始粮价自洽：按供需模型由初始库存/产量推导（prevPrice=基准，产出该状态下的均衡价——
        // 首日 tick 不再出现「价格跳变 → 误报粮价暴涨」）
        try {
            MacroEconomyEngine.EconomyResult initR = MacroEconomyEngine.step(new MacroEconomyEngine.EconomyInput(
                    ini.getPopulation(), ini.getGrainStock(), ini.getGrainProductionBase(),
                    ini.getGrainPriceBase(), ini.getPerCapitaConsumption(), ini.getTaxPerCapita(),
                    0.0, null, ini.getGrainPriceBase(), ini.getMarketActivity(),
                    ini.getBankDeposit(), 0.0, 0L));
            eco.setGrainPrice(initR.grainPrice());
        } catch (Exception e) {
            log.warn("[宏观] 初始粮价推导失败（保留基准价）: {}", e.getMessage());
        }

        // 势力（去硬编码：由 MesoGroupEngine 三源通用派生——世界观文本解析/模板骨架/
        // 运行时涌现 → actor_meso_group（type≠adhoc）→ MacroState.factions 派生缓存；
        // 测试世界 6 势力名不再作为任何代码常量；未装配/失败回退模板骨架通用命名）
        Map<String, MacroState.Faction> factions = new LinkedHashMap<>();
        if (mesoGroupEngine != null) {
            try {
                factions = mesoGroupEngine.seedInitialGroups(projectId, tpl);
            } catch (Exception e) {
                log.warn("[宏观] 中观组初始化失败（回退模板骨架）: project={} : {}", projectId, e.getMessage());
            }
        }
        if (factions.isEmpty()) {
            factions = templateFactions(tpl);
        }
        st.setFactions(factions);

        // 文化（初始议题/信仰/禁忌/流行——全部由模板承载， 起代码零硬编码：
        // 议题/信仰/禁忌/流行 = tpl.initialIssues/initialBeliefs/initialTaboos/initialFashions）
        List<MacroState.Issue> issues = new ArrayList<>();
        int issueIdx = 0;
        for (String t : tpl.initialIssues()) {
            // 首个议题强度略高（全村关注度基线），其余 0.4
            issues.add(issue(t, 0.0, issueIdx++ == 0 ? 0.5 : 0.4));
        }
        st.setIssues(issues);
        st.setBeliefs(new LinkedHashMap<>(tpl.initialBeliefs()));
        st.setTaboos(new ArrayList<>(tpl.initialTaboos()));
        st.setFashions(new ArrayList<>(tpl.initialFashions()));

        // 科技
        Map<String, Double> tech = new LinkedHashMap<>();
        tech.put("agriculture", 35.0);
        tech.put("craft", 30.0);
        tech.put("medicine", 45.0);
        tech.put("array", 20.0);
        st.setTechLevels(tech);
        st.setTechPrevLevels(new LinkedHashMap<>(tech));
        st.setTechNoInputMonths(new LinkedHashMap<>());
        return st;
    }

    /** 模板职业分布（文明形态 → 职业名/人数；农耕=村落推导值，工商/信息=近现代职业）。 */
    private static Map<String, Long> occupationsOf(MacroTemplate tpl) {
        Map<String, Long> occ = new LinkedHashMap<>();
        if (MacroTemplate.CIV_AGRARIAN.equals(tpl.civilization())) {
            occ.put("农户", 160L);
            occ.put("工匠", 35L);
            occ.put("商贩", 25L);
            occ.put("医者", 6L);
            occ.put("猎户", 12L);
            occ.put("渔夫", 10L);
            occ.put("采药人", 8L);
            occ.put("樵夫", 8L);
            occ.put("塾师", 4L);
            occ.put("其他", 32L);
        } else if (MacroTemplate.CIV_INDUSTRIAL.equals(tpl.civilization())) {
            occ.put("工人", 140L);
            occ.put("商贩", 50L);
            occ.put("职员", 40L);
            occ.put("医者", 8L);
            occ.put("教师", 6L);
            occ.put("工匠", 30L);
            occ.put("其他", 26L);
        } else {
            occ.put("能源工人", 120L);
            occ.put("技师", 50L);
            occ.put("商人", 40L);
            occ.put("医者", 8L);
            occ.put("教师", 6L);
            occ.put("工程师", 40L);
            occ.put("其他", 36L);
        }
        return occ;
    }

    /**
     * 项目当前模板：手动覆盖优先 → 世界观设定文本推导（无文本回退默认仙侠模板）。
     *
     * @param projectId 项目 ID
     *
     * @return 模板（恒非空）
     */
    public MacroTemplate templateOf(Long projectId) {
        if (macroTemplateService.isManual(projectId)) {
            return macroTemplateService.forProject(projectId);
        }
        return macroTemplateService.of(macroTemplateService.deriveFromSetting(worldSettingText(projectId)));
    }

    /**
     * 世界观设定文本（最新版本多字段拼接：题材/时代/地理/势力/力量体系/文化/历史/自由文本；
     * 缺省空串——回退默认模板）。
     */
    private String worldSettingText(Long projectId) {
        if (projectId == null || worldSettingRepository == null) {
            return "";
        }
        try {
            ActorWorldSetting s = worldSettingRepository
                    .findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
            if (s == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            append(sb, s.getName());
            append(sb, s.getGenre());
            append(sb, s.getEra());
            append(sb, s.getGeography());
            append(sb, s.getFactions());
            append(sb, s.getMagicSystem());
            append(sb, s.getCulture());
            append(sb, s.getHistory());
            append(sb, s.getFreeText());
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 拼接设定段（空段跳过）。 */
    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(part);
        }
    }

    /**
     * 手动覆盖项目模板（POST /macro/template）：写入 MacroState.templateId 并落 init 快照
     * （权威持久化）；进程覆盖记录同步（重启后由快照恢复模板语义）。
     *
     * @param projectId 项目 ID
     *
     * @param templateId 模板 id
     *
     * @return 生效模板
     */
    @Transactional
    public MacroTemplate setTemplate(Long projectId, String templateId) {
        MacroTemplate tpl = macroTemplateService.override(projectId, templateId);
        MacroState st = stateOf(projectId);
        st.setTemplateId(tpl.id());
        Long day = st.getGameDay() == null ? currentGameDay(projectId) : st.getGameDay();
        st.setGameDay(day);
        saveSnapshot(projectId, st, ActorWorldMacroSnapshot.TICK_INIT, day * 86400L);
        log.info("[宏观] 项目模板覆盖生效: project={} template={}", projectId, tpl.id());
        return tpl;
    }

    /** 模板读接口 VO（当前模板 + 可选模板全集）。 */
    public Map<String, Object> templateVO(Long projectId) {
        MacroTemplate tpl = templateOf(projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", macroTemplateService.templateVO(tpl));
        m.put("manual", macroTemplateService.isManual(projectId));
        List<Map<String, Object>> all = new ArrayList<>();
        for (MacroTemplate t : MacroTemplate.builtins().values()) {
            all.add(macroTemplateService.templateVO(t));
        }
        m.put("available", all);
        return m;
    }

    /** 议题构造。 */
    private static MacroState.Issue issue(String topic, double orientation, double intensity) {
        MacroState.Issue i = new MacroState.Issue();
        i.setTopic(topic);
        i.setOrientation(orientation);
        i.setIntensity(intensity);
        return i;
    }

    /** 模板骨架势力兜底。 */
    private static Map<String, MacroState.Faction> templateFactions(MacroTemplate tpl) {
        Map<String, MacroState.Faction> out = new LinkedHashMap<>();
        if (tpl == null || tpl.groupTemplates() == null) {
            return out;
        }
        for (MacroTemplate.GroupTemplate g : tpl.groupTemplates()) {
            MacroState.Faction f = new MacroState.Faction();
            f.setName(g.name());
            f.setLeaderRef(null);
            f.setInfluence(g.influence());
            f.setMemberCount(5L);
            f.setRelations(new LinkedHashMap<>());
            out.put(g.name(), f);
        }
        return out;
    }

    // ==================== 快照持久化 ====================

    /**
     * 保存快照行（uk project+day+tickType 幂等 upsert；超出滚动上限删最旧行）。
     */
    @Transactional
    public void saveSnapshot(Long projectId, MacroState st, String tickType, Long gameSecond) {
        Long day = st.getGameDay();
        ActorWorldMacroSnapshot row = snapshotRepository
                .findByProjectIdAndGameDayAndTickType(projectId, day, tickType)
                .orElseGet(ActorWorldMacroSnapshot::new);
        row.setProjectId(projectId);
        row.setGameDay(day);
        row.setTickType(tickType);
        row.setIndicatorsJson(toJson(st));
        row.setSummary(buildSummary(st, tickType));
        snapshotRepository.save(row);
        trimSnapshots(projectId);
    }

    /** 滚动上限清理：超出 maxPerProject 时按最旧删除（保留 init 行优先——先删 daily 旧行）。 */
    private void trimSnapshots(Long projectId) {
        long max = props.getSnapshotMaxPerProject();
        if (max <= 0) {
            return;
        }
        int guard = 0; // 防死循环护栏（异常状态下最多迭代 10000 次）
        while (snapshotRepository.countByProjectId(projectId) > max && guard++ < 10000) {
            Optional<ActorWorldMacroSnapshot> oldest = snapshotRepository.findTopByProjectIdOrderByGameDayAsc(projectId);
            if (oldest.isEmpty()) {
                break;
            }
            snapshotRepository.delete(oldest.get());
        }
    }

    /** 人类可读简报。 */
    public String buildSummary(MacroState st, String tickType) {
        StringBuilder sb = new StringBuilder();
        MacroTemplate tpl = macroTemplateService.of(st.getTemplateId());
        String unit = tpl.resourceUnit().isBlank() ? "单位" : tpl.resourceUnit();
        String cur = tpl.currencyUnit().isBlank() ? "钱" : tpl.currencyUnit();
        sb.append("第 ").append((st.getGameDay() == null ? 0 : st.getGameDay()) + 1).append(" 游戏日宏观简报：");
        sb.append("人口 ").append(st.getPopulation().getTotal()).append(" 口");
        if (tickType.equals(ActorWorldMacroSnapshot.TICK_DAILY) || tickType.equals(ActorWorldMacroSnapshot.TICK_INIT)) {
            sb.append("（本期出生 ").append(st.getPopulation().getBirths())
                    .append("、死亡 ").append(st.getPopulation().getDeaths())
                    .append("、净迁徙 ").append(st.getPopulation().getMigrationNet()).append("）");
            MacroState.Economy eco = st.getEconomy();
            sb.append("；").append(tpl.coreResource()).append("价 ").append(eco.getGrainPrice())
                    .append(" ").append(tpl.priceUnit());
            sb.append("（库存 ").append(eco.getGrainStock()).append(" ").append(unit)
                    .append("、产量 ").append(eco.getGrainProduction()).append(" ").append(unit)
                    .append("/日）");
            // ：季节/天气/收成系数/民怨（全链联动因果链呈现）
            if (eco.getSeason() != null) {
                sb.append("；").append(SeasonService.seasonDisplayName(eco.getSeason())).append("季");
            }
            if (eco.getWeatherCondition() != null) {
                sb.append("·").append(eco.getWeatherCondition());
            }
            if (eco.getHarvestFactor() != null) {
                sb.append("·收成系数 ").append(eco.getHarvestFactor());
            }
            if (st.getGrievance() != null) {
                sb.append("；民怨 ").append(Math.round(st.getGrievance()));
            }
            sb.append("；市集活跃度 ").append(Math.round(eco.getMarketActivity()));
            sb.append("；税收 ").append(eco.getTaxRevenue()).append(" ").append(cur)
                    .append("/日；储备 ").append(eco.getBankDeposit()).append(" ").append(cur);
        } else if (tickType.equals(ActorWorldMacroSnapshot.TICK_FACTION)) {
            if (st.getFactions() != null) {
                MacroState.Faction top = st.getFactions().values().stream()
                        .max(java.util.Comparator.comparingDouble(MacroState.Faction::getInfluence)).orElse(null);
                sb.append("；势力格局：");
                if (top != null) {
                    sb.append("「").append(top.getName()).append("」影响力最高（")
                            .append(Math.round(top.getInfluence())).append("）");
                }
                sb.append("；关系状态：");
                int n = 0;
                for (MacroState.Faction f : st.getFactions().values()) {
                    if (f == null || f.getRelations() == null) {
                        continue;
                    }
                    for (Map.Entry<String, Integer> r : f.getRelations().entrySet()) {
                        if (n < 3) {
                            sb.append(f.getName()).append("↔").append(r.getKey()).append("=")
                                    .append(MacroFactionEngine.relationState(r.getValue())).append("，");
                            n++;
                        }
                    }
                }
                if (sb.charAt(sb.length() - 1) == '，') {
                    sb.deleteCharAt(sb.length() - 1);
                }
            }
        } else if (tickType.equals(ActorWorldMacroSnapshot.TICK_MONTHLY)) {
            // ⚠️ 前置修复：集合字段空值防御（极端态/快照异常下 issues/techLevels 可能为 null）
            sb.append("；舆论：");
            if (st.getIssues() != null) {
                st.getIssues().stream().limit(2).forEach(i -> sb.append("「")
                        .append(i.getTopic()).append("」强度 ")
                        .append(Math.round(i.getIntensity() * 100)).append("%、"));
                if (sb.charAt(sb.length() - 1) == '、') {
                    sb.deleteCharAt(sb.length() - 1);
                }
            } else {
                sb.append("（无议题）");
            }
            sb.append("；科技：");
            if (st.getTechLevels() != null) {
                // null 值防御：getOrDefault 在「键存在但值为 null」时返回 null，直接拼 double 会解包 NPE
                Double med = st.getTechLevels().get("medicine");
                Double agr = st.getTechLevels().get("agriculture");
                Double cra = st.getTechLevels().get("craft");
                Double arr = st.getTechLevels().get("array");
                sb.append("医术 ").append(med == null ? 0.0 : med)
                        .append("、农业 ").append(agr == null ? 0.0 : agr)
                        .append("、工艺 ").append(cra == null ? 0.0 : cra)
                        .append("、法阵 ").append(arr == null ? 0.0 : arr);
            } else {
                sb.append("（无科技）");
            }
        }
        return sb.toString();
    }

    // ==================== 入账流水聚合 ====================

    /**
     * 某项目某区间的入账聚合（按指标名求和）。
     *
     * @param projectId 项目 ID
     *
     * @param fromDay 起始游戏日（含）
     *
     * @param toDay 结束游戏日（含）
     *
     * @param targetMacro 目标子系统（economy/faction/culture/tech；null=全部）
     *
     * @return 指标名 → 合计增量（economy: market_activity/grain_production/tax_revenue/currency_supply；
     * faction: 势力名；culture: 议题话题；tech: 维度名）
     */
    public Map<String, Double> aggregateDeltas(Long projectId, Long fromDay, Long toDay, String targetMacro) {
        List<ActorWorldMacroDelta> rows;
        if (targetMacro != null) {
            rows = deltaRepository.findByProjectIdAndGameDayBetweenAndTargetMacro(projectId, fromDay, toDay, targetMacro);
        } else {
            rows = deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, fromDay, toDay);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (ActorWorldMacroDelta d : rows) {
            out.merge(d.getIndicator(), d.getDelta() == null ? 0.0 : d.getDelta(), Double::sum);
        }
        return out;
    }

    // ==================== 具名 NPC 代表挂钩 ====================

    /**
     * 确定性选取一名具名普通 NPC（池中代表个体；daySeed 哈希轮选）。
     * <p>普通 NPC = 统一主表 {@code actor_profile} 中 {@code type='common'} 且未软删的行
     * （历史 {@code actor_profile_ordinary} 已物理废弃）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param daySeed 游戏日序号
     *
     * @return 普通 NPC 实体（无则 null）
     */
    public ActorCharacter pickNamedNpc(Long projectId, long daySeed) {
        List<ActorCharacter> npcs =
                ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, NpcRefs.TYPE_COMMON, 0);
        if (npcs.isEmpty()) {
            return null;
        }
        int idx = (int) (MacroPopulationEngine.jitter(daySeed, 37) * npcs.size());
        return npcs.get(Math.min(idx, npcs.size() - 1));
    }

    /**
     * 标记具名普通 NPC 受宏观事件影响（减员/流民等：lastAction 写入事件语义，不改变作息状态机字段）。
     * <p>{@code lastAction} 已从档案迁到 {@code actor_state.last_action}，
     * 经 {@link NpcActivityStateService#setLastAction} 写入（只刷最近行动、不动 activity_state）；
     * 未装配/写入失败仅告警不阻断宏观主流程。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npc 具名普通 NPC（type=common）
     *
     * @param reason 事件语义文本（写入 last_action）
     */
    @Transactional
    public void markNamedNpcAffected(Long projectId, ActorCharacter npc, String reason) {
        if (npc == null || npc.getProjectId() == null) {
            return;
        }
        try {
            if (npcActivityStateService != null) {
                npcActivityStateService.setLastAction(projectId, NpcRefs.of(npc.getId()), reason);
            }
            log.debug("[宏观] 具名普通 NPC 受宏观事件影响: project={} npc={} 影响={}", projectId, npc.getName(), reason);
        } catch (Exception e) {
            log.warn("[宏观] 具名 NPC 标记失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    // ==================== 序列化 ====================

    /** 状态 → JSON（Jackson 3 tools.databind）。 */
    public String toJson(MacroState st) {
        try {
            return objectMapper.writeValueAsString(st);
        } catch (Exception e) {
            log.warn("[宏观] 状态序列化失败（快照留空）: {}", e.getMessage());
            return null;
        }
    }

    /** JSON → 状态（非法返回 null）。 */
    public MacroState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MacroState.class);
        } catch (Exception e) {
            log.warn("[宏观] 状态反序列化失败（回退默认）: {}", e.getMessage());
            return null;
        }
    }

    /** 当前引擎推进的游戏日（缺失回退 0）。 */
    public Long currentGameDay(Long projectId) {
        try {
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            if (sec != null) {
                return Math.floorDiv(sec, 86400L);
            }
            Long hour = worldClockService.currentEngineGameHour(projectId);
            return hour == null ? 0L : Math.floorDiv(hour, 24L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 取配置。 */
    public MacroProperties properties() {
        return props;
    }

    /** 当前状态（只读视图副本——防外部误改内存态）。 */
    public MacroState snapshotOf(Long projectId) {
        MacroState st = stateOf(projectId);
        return fromJson(toJson(st));
    }

    // ==================== 读接口组装 ====================

    /** 当前宏观状态 VO（GET /macro/state）。 */
    public MacroStateVO stateVO(Long projectId) {
        MacroState st = stateOf(projectId);
        MacroStateVO vo = new MacroStateVO();
        vo.setProjectId(projectId);
        vo.setGameDay(st.getGameDay());
        vo.setPopulation(st.getPopulation().getTotal());
        vo.setGrainPrice(st.getEconomy().getGrainPrice());
        vo.setMarketActivity(st.getEconomy().getMarketActivity());
        vo.setGrainStock(st.getEconomy().getGrainStock());
        vo.setBankDeposit(st.getEconomy().getBankDeposit());
        vo.setTemplateId(st.getTemplateId());
        vo.setGrievance(st.getGrievance());
        vo.setSeason(st.getEconomy().getSeason());
        vo.setHarvestFactor(st.getEconomy().getHarvestFactor());
        st.getFactions().values().stream()
                .max(java.util.Comparator.comparingDouble(MacroState.Faction::getInfluence))
                .ifPresent(f -> vo.setTopFaction(f.getName()));
        st.getIssues().stream()
                .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity))
                .ifPresent(i -> vo.setTopIssue(i.getTopic()));
        vo.setSummary(buildSummary(st, ActorWorldMacroSnapshot.TICK_DAILY));
        vo.setIndicators(toJsonNode(st));
        return vo;
    }

    /** 快照列表 VO（GET /macro/snapshot；tickType 可空=全部，limit 可空=100）。 */
    public List<MacroSnapshotVO> snapshotVOs(Long projectId, String tickType, Long fromDay, Long toDay, Integer limit) {
        List<ActorWorldMacroSnapshot> rows;
        long from = fromDay == null ? 0L : fromDay;
        long to = toDay == null ? Long.MAX_VALUE : toDay;
        if (tickType != null && !tickType.isBlank()) {
            rows = snapshotRepository.findByProjectIdAndGameDayBetweenAndTickTypeOrderByGameDayAsc(
                    projectId, from, to, tickType);
        } else {
            rows = snapshotRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAscTickTypeAsc(
                    projectId, from, to);
        }
        int max = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<MacroSnapshotVO> out = new ArrayList<>();
        for (ActorWorldMacroSnapshot r : rows) {
            out.add(MacroSnapshotVO.from(r, parseIndicators(r.getIndicatorsJson())));
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /** 宏观事件列表 VO（GET /macro/events；severity 可空=全部；limit 可空=100）。 */
    public List<MacroEventVO> eventVOs(Long projectId, Long fromDay, Long toDay, Integer severity, Integer limit) {
        List<ActorWorldMacroEvent> rows;
        long from = fromDay == null ? 0L : fromDay;
        long to = toDay == null ? Long.MAX_VALUE : toDay;
        if (severity != null) {
            rows = eventRepository.findByProjectIdAndGameDayBetweenAndSeverityGreaterThanEqualOrderByGameDayAsc(
                    projectId, from, to, severity);
        } else {
            rows = eventRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, from, to);
        }
        int max = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<MacroEventVO> out = new ArrayList<>();
        for (ActorWorldMacroEvent r : rows) {
            out.add(MacroEventVO.from(r));
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /** 入账流水 VO（GET /macro/deltas；day 可空=全部；npcRef 可空=全部）。 */
    public List<MacroDeltaVO> deltaVOs(Long projectId, Long day, String npcRef, Integer limit) {
        List<ActorWorldMacroDelta> rows;
        if (npcRef != null && !npcRef.isBlank()) {
            rows = deltaRepository.findTop100ByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
        } else if (day != null) {
            rows = deltaRepository.findByProjectIdAndGameDay(projectId, day);
        } else {
            rows = deltaRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, 0L, Long.MAX_VALUE);
        }
        int max = limit == null ? 200 : Math.max(1, Math.min(limit, 500));
        List<MacroDeltaVO> out = new ArrayList<>();
        for (ActorWorldMacroDelta r : rows) {
            out.add(MacroDeltaVO.from(r));
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /** 指标 JSON 解析（快照行 → Map；非法返回空 Map）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseIndicators(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) {
                return objectMapper.convertValue(node, Map.class);
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 状态 → JsonNode（读接口 indicators 载荷）。 */
    private JsonNode toJsonNode(MacroState st) {
        try {
            return objectMapper.readTree(toJson(st));
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
