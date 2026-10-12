package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 势力数值引擎单元测试（宏观社会演化系统 M1，domain/world/service，纯静态）。
 * <p>职责：验证影响力演进（贡献/经济加权 + 向均值回归）、关系分漂移与状态机派生
 * （hostile/tense/neutral/friendly/allied）、事件触发（faction_split/faction_alliance/power_shift）、
 * 钳制与确定性。</p>
 */
class MacroFactionEngineTest {

    /** 两势力最小格局（王氏宗族/庙宇祭司，初始关系 25=中立偏友好）。 */
    private Map<String, MacroState.Faction> twoFactions() {
        Map<String, MacroState.Faction> m = new LinkedHashMap<>();
        MacroState.Faction shen = new MacroState.Faction();
        shen.setName("王氏宗族");
        shen.setInfluence(60.0);
        shen.setMemberCount(60L);
        shen.setRelations(new LinkedHashMap<>());
        MacroState.Faction wuzhu = new MacroState.Faction();
        wuzhu.setName("庙宇祭司一系");
        wuzhu.setInfluence(55.0);
        wuzhu.setMemberCount(5L);
        wuzhu.setRelations(new LinkedHashMap<>());
        shen.getRelations().put("庙宇祭司一系", 25);
        wuzhu.getRelations().put("王氏宗族", 25);
        m.put("王氏宗族", shen);
        m.put("庙宇祭司一系", wuzhu);
        return m;
    }

    private MacroFactionEngine.FactionInput input(Map<String, MacroState.Faction> factions,
                                                  Map<String, Double> contrib,
                                                  Map<String, Integer> prev) {
        return new MacroFactionEngine.FactionInput(factions, contrib, Map.of(), prev, 0.0, 0, 5L);
    }

    /**
     * 入账贡献（M2：参战/效力 → 势力影响力）推高目标势力影响力；无贡献按 0.98 向均值回归。
     */
    @Test
    void contributionRaisesInfluence() {
        Map<String, MacroState.Faction> factions = twoFactions();
        Map<String, Integer> prev = Map.of("王氏宗族", 60, "庙宇祭司一系", 55);
        MacroFactionEngine.FactionResult r = MacroFactionEngine.step(input(factions,
                Map.of("王氏宗族", 40.0), prev));
        assertTrue(r.factions().get("王氏宗族").getInfluence() > 60.0,
                "大额贡献应推高影响力，实际 " + r.factions().get("王氏宗族").getInfluence());
        // 无贡献方按 0.98 回归（略降）
        assertTrue(r.factions().get("庙宇祭司一系").getInfluence() < 55.0,
                "无贡献方应略降，实际 " + r.factions().get("庙宇祭司一系").getInfluence());
    }

    /**
     * 影响力钳制 [0,100]。
     */
    @Test
    void influenceClamped() {
        Map<String, MacroState.Faction> factions = twoFactions();
        factions.get("王氏宗族").setInfluence(99.0);
        Map<String, Integer> prev = Map.of("王氏宗族", 99, "庙宇祭司一系", 55);
        MacroFactionEngine.FactionResult r = MacroFactionEngine.step(input(factions,
                Map.of("王氏宗族", 100.0), prev));
        assertTrue(r.factions().get("王氏宗族").getInfluence() <= 100.0);
    }

    /**
     * 关系状态机：分数 → hostile/tense/neutral/friendly/allied 五态。
     */
    @Test
    void relationStateMachine() {
        assertEquals("hostile", MacroFactionEngine.relationState(-80));
        assertEquals("tense", MacroFactionEngine.relationState(-40));
        assertEquals("neutral", MacroFactionEngine.relationState(0));
        assertEquals("friendly", MacroFactionEngine.relationState(40));
        assertEquals("allied", MacroFactionEngine.relationState(70));
    }

    /**
     * 敌对势力（score≤-30）→ 高概率恶化 → 家族决裂事件；友好（≥30）→ 联姻事件概率。
     */
    @Test
    void hostileRelationsDegradeWithConflictEvent() {
        Map<String, MacroState.Faction> factions = twoFactions();
        factions.get("王氏宗族").getRelations().put("庙宇祭司一系", -40);
        factions.get("庙宇祭司一系").getRelations().put("王氏宗族", -40);
        Map<String, Integer> prev = Map.of("王氏宗族", 60, "庙宇祭司一系", 55);
        boolean sawConflict = false;
        for (long day = 0; day < 20; day++) {
            Map<String, MacroState.Faction> fresh = twoFactions();
            fresh.get("王氏宗族").getRelations().put("庙宇祭司一系", -40);
            fresh.get("庙宇祭司一系").getRelations().put("王氏宗族", -40);
            MacroFactionEngine.FactionResult r = MacroFactionEngine.step(
                    new MacroFactionEngine.FactionInput(fresh, Map.of(), Map.of(), prev, 0.0, 0, day));
            for (String[] ev : r.events()) {
                if (ev[0].equals("faction_split")) {
                    sawConflict = true;
                }
            }
        }
        assertTrue(sawConflict, "20 旬内敌对势力应至少触发一次冲突事件（40% 概率）");
    }

    /**
     * 影响力位移 &gt;30% → 势力易主重大事件（阈值判定；M2 触发 AI 叙事）。
     */
    @Test
    void bigShiftTriggersTakeoverEvent() {
        Map<String, MacroState.Faction> factions = twoFactions();
        Map<String, Integer> prev = Map.of("王氏宗族", 20, "庙宇祭司一系", 55);
        MacroFactionEngine.FactionResult r = MacroFactionEngine.step(
                new MacroFactionEngine.FactionInput(factions, Map.of("王氏宗族", 100.0), Map.of(), prev, 0.0, 0, 5L));
        boolean saw = false;
        for (String[] ev : r.events()) {
            if (ev[0].equals("power_shift") && ev[1].equals("王氏宗族")) {
                saw = true;
            }
        }
        assertTrue(saw, "影响力位移超阈值应触发势力易主事件");
    }

    // ==================== M3 民怨/收成因果链（粮价→生计→势力/舆论末端） ====================

    /**
     * 高民怨（&gt;30 起）侵蚀当权势力影响力（每点 −0.04）；民怨 ≥80 → 民变风险事件。
     */
    @Test
    void grievanceErodesRulingAndTriggersRevolt() {
        Map<String, MacroState.Faction> factions = twoFactions(); // 王氏 60 为当权
        Map<String, Integer> prev = Map.of("王氏宗族", 60, "庙宇祭司一系", 55);
        MacroFactionEngine.FactionResult r = MacroFactionEngine.step(
                new MacroFactionEngine.FactionInput(factions, Map.of(), Map.of(), prev, 85.0, 0, 5L));
        assertTrue(r.factions().get("王氏宗族").getInfluence() < 60.0,
                "高民怨应侵蚀当权势力影响力，实际 " + r.factions().get("王氏宗族").getInfluence());
        boolean revolt = r.events().stream().anyMatch(ev -> ev[0].equals("revolt_risk"));
        assertTrue(revolt, "民怨 ≥80 应触发民变风险事件");
    }

    /**
     * 民怨 ≥50 且当权影响力 ≥40 → 开仓赈济事件（负反馈环入口）。
     */
    @Test
    void reliefEventWhenGrievanceHigh() {
        Map<String, MacroState.Faction> factions = twoFactions(); // 王氏 60
        Map<String, Integer> prev = Map.of("王氏宗族", 60, "庙宇祭司一系", 55);
        MacroFactionEngine.FactionResult r = MacroFactionEngine.step(
                new MacroFactionEngine.FactionInput(factions, Map.of(), Map.of(), prev, 60.0, 0, 5L));
        boolean relief = r.events().stream().anyMatch(ev -> ev[0].equals("relief"));
        assertTrue(relief, "民怨 ≥50 且当权有实力应触发开仓赈济");
        // 低民怨不触发
        Map<String, MacroState.Faction> calm = twoFactions();
        MacroFactionEngine.FactionResult r2 = MacroFactionEngine.step(
                new MacroFactionEngine.FactionInput(calm, Map.of(), Map.of(), prev, 10.0, 0, 5L));
        boolean none = r2.events().stream().noneMatch(ev -> ev[0].equals("relief") || ev[0].equals("revolt_risk"));
        assertTrue(none, "低民怨不应触发赈济/民变");
    }

    /**
     * 丰年（harvestOutcome=1）→ 当权势力影响力 +1.5（得人心正向环；与平年同种子对比）。
     */
    @Test
    void harvestOutcomeAffectsRuling() {
        Map<String, MacroState.Faction> good = twoFactions();
        Map<String, MacroState.Faction> neutral = twoFactions();
        Map<String, Integer> prev = Map.of("王氏宗族", 60, "庙宇祭司一系", 55);
        MacroFactionEngine.FactionResult r1 = MacroFactionEngine.step(
                new MacroFactionEngine.FactionInput(good, Map.of(), Map.of(), prev, 0.0, 1, 5L));
        MacroFactionEngine.FactionResult r2 = MacroFactionEngine.step(
                new MacroFactionEngine.FactionInput(neutral, Map.of(), Map.of(), prev, 0.0, 0, 5L));
        assertTrue(r1.factions().get("王氏宗族").getInfluence()
                        > r2.factions().get("王氏宗族").getInfluence(),
                "同种子下丰年应比平年高 1.5 影响力：丰年 " + r1.factions().get("王氏宗族").getInfluence()
                        + " vs 平年 " + r2.factions().get("王氏宗族").getInfluence());
    }
}
