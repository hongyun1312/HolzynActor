package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorMacroDeltaRule;
import com.holzyn.actor.domain.world.repository.ActorMacroDeltaRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 可扩展入账协议注册表单元测试（宏观社会演化系统 M6.5a，domain/world/service，同包）。
 * <p>职责：验证种子匹配（M2 映射迁移语义一致）、关键词命中抑制纯类型规则（防双入账）、
 * 占位符（{{faction}}/{{topIssue}}）、DB 项目覆盖、热更新缓存失效、关键词 JSON 工具。</p>
 */
class MacroDeltaRegistryTest {

    private ActorMacroDeltaRuleRepository ruleRepository;
    private MacroDeltaRegistry registry;

    @BeforeEach
    void setUp() {
        ruleRepository = Mockito.mock(ActorMacroDeltaRuleRepository.class);
        registry = new MacroDeltaRegistry(ruleRepository);
        when(ruleRepository.findAllByOrderByIdAsc()).thenReturn(List.of()); // 空库 → 种子落库
        when(ruleRepository.findByProjectIdOrderByIdAsc(anyLong())).thenReturn(List.of());
    }

    /** trade 类型 → 经济三指标（M2 语义一致）。 */
    @Test
    void tradeTypeHitsEconomyThreeIndicators() {
        List<MacroDeltaRegistry.Hit> hits = MacroDeltaRegistry.seedLookup("在集市经营米行", "trade");
        assertEquals(3, hits.size());
        assertTrue(hits.stream().anyMatch(h -> "market_activity".equals(h.indicator()) && h.delta() == 0.6));
        assertTrue(hits.stream().anyMatch(h -> "currency_supply".equals(h.indicator()) && h.delta() == 0.8));
        assertTrue(hits.stream().anyMatch(h -> "tax_revenue".equals(h.indicator()) && h.delta() == 0.2));
    }

    /** 农作关键词 → 产量独占（不双入账：generic work 被关键词命中抑制）。 */
    @Test
    void farmingKeywordHitsProductionOnly() {
        List<MacroDeltaRegistry.Hit> hits = MacroDeltaRegistry.seedLookup("在田里种田插秧", "work");
        assertEquals(1, hits.size(), "农作应只入 grain_production，不双入 market_activity");
        assertEquals("grain_production", hits.get(0).indicator());
        assertEquals(0.5, hits.get(0).delta(), 0.001);
    }

    /** 非农作 work 关键词（打铁）→ 仅 generic work 市集活跃度（关键词集不相交）。 */
    @Test
    void genericWorkKeywordHitsMarketOnly() {
        List<MacroDeltaRegistry.Hit> hits = MacroDeltaRegistry.seedLookup("在铁匠铺打铁", "work");
        assertEquals(1, hits.size(), "非农作 work 应只入 market_activity");
        assertEquals("market_activity", hits.get(0).indicator());
        assertEquals(0.2, hits.get(0).delta(), 0.001);
    }

    /** fight → 势力占位符 {{faction}}（调用方解析领袖归属）。 */
    @Test
    void fightHitsFactionPlaceholder() {
        List<MacroDeltaRegistry.Hit> hits = MacroDeltaRegistry.seedLookup("率村民剿匪", "fight");
        assertEquals(1, hits.size());
        assertEquals("faction", hits.get(0).targetMacro());
        assertEquals(MacroDeltaRegistry.PH_FACTION, hits.get(0).indicator());
    }

    /** preach/social → 文化最强议题占位符；treat/study → 科技维度。 */
    @Test
    void preachSocialTreatStudyTargets() {
        List<MacroDeltaRegistry.Hit> preach = MacroDeltaRegistry.seedLookup("在庙里布道诵经", "preach");
        assertEquals("culture", preach.get(0).targetMacro());
        assertEquals(MacroDeltaRegistry.PH_TOP_ISSUE, preach.get(0).indicator());
        List<MacroDeltaRegistry.Hit> social = MacroDeltaRegistry.seedLookup("去茶馆闲谈", "social");
        assertEquals("culture", social.get(0).targetMacro());
        List<MacroDeltaRegistry.Hit> treat = MacroDeltaRegistry.seedLookup("诊脉开方", "treat");
        assertEquals("tech", treat.get(0).targetMacro());
        assertEquals("medicine", treat.get(0).indicator());
        List<MacroDeltaRegistry.Hit> study = MacroDeltaRegistry.seedLookup("研习炼气", "study");
        assertEquals("tech", study.get(0).targetMacro());
        assertEquals("array", study.get(0).indicator());
    }

    /** 无命中（进食/未知类型）→ 空列表不入账。 */
    @Test
    void unmatchedActionNoHits() {
        assertTrue(MacroDeltaRegistry.seedLookup("在厨房做饭", "custom").isEmpty());
        assertTrue(MacroDeltaRegistry.seedLookup("在灶台做饭", "eat").isEmpty());
        assertTrue(MacroDeltaRegistry.seedLookup(null, null).isEmpty());
    }

    /** M3：移动行为按文本入账（走路/赶路/前往 → economy.market_activity）。 */
    @Test
    void moveKeywordsBookToMarketActivity() {
        List<MacroDeltaRegistry.Hit> walk = MacroDeltaRegistry.seedLookup("正走在路上，去医馆", "move");
        assertFalse(walk.isEmpty(), "M3 移动关键词应命中");
        assertEquals("economy", walk.get(0).targetMacro());
        assertEquals("market_activity", walk.get(0).indicator());
        List<MacroDeltaRegistry.Hit> hurry = MacroDeltaRegistry.seedLookup("赶路去集市", "move");
        assertFalse(hurry.isEmpty(), "赶路应命中");
    }

    /** DB 项目覆盖：同签名项目行覆盖全局行（delta 不同）；热更新失效重建。 */
    @Test
    void projectOverrideAndInvalidate() {
        ActorMacroDeltaRule global = rule("trade", "[\"经商\"]", "market_activity", 0.6);
        ActorMacroDeltaRule project = rule("trade", "[\"经商\"]", "market_activity", 0.9);
        when(ruleRepository.findByProjectIdOrderByIdAsc(MacroDeltaRegistry.GLOBAL_PROJECT))
                .thenReturn(List.of(global));
        when(ruleRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of(project));
        List<MacroDeltaRegistry.Hit> hits = registry.lookup(100L, "经商买卖", "trade");
        assertTrue(hits.stream().anyMatch(h -> h.indicator().equals("market_activity") && h.delta() == 0.9),
                "项目行应覆盖全局行");
        // 热更新：清缓存后再次加载（仍返回项目行；无效化本身不改变数据）
        registry.invalidate(100L);
        hits = registry.lookup(100L, "经商买卖", "trade");
        assertEquals(0.9, hits.stream().filter(h -> h.indicator().equals("market_activity"))
                .findFirst().orElseThrow().delta(), 0.001);
    }

    /** 自定义规则行（actionType=custom）→ 纯关键词命中。 */
    @Test
    void customRuleMatchesKeywordOnly() {
        ActorMacroDeltaRule custom = new ActorMacroDeltaRule();
        custom.setActionType("custom");
        custom.setKeywords("[\"炼丹\"]");
        custom.setTargetMacro("tech");
        custom.setIndicator("array");
        custom.setDelta(0.2);
        custom.setEnabled(true);
        List<MacroDeltaRegistry.Hit> hits = MacroDeltaRegistry.match(List.of(custom), "在丹房炼丹", "custom");
        assertEquals(1, hits.size());
        assertEquals("array", hits.get(0).indicator());
        assertTrue(MacroDeltaRegistry.match(List.of(custom), "在集市卖菜", "custom").isEmpty(),
                "未命中关键词不入账");
    }

    /** 关键词 JSON 工具：数组往返、非法 JSON/空 → 空列表。 */
    @Test
    void keywordJsonUtilities() {
        String json = MacroDeltaRegistry.toKeywordsJson(new String[]{"经商", "经营"});
        assertEquals(List.of("经商", "经营"), MacroDeltaRegistry.parseKeywords(json));
        assertTrue(MacroDeltaRegistry.parseKeywords(null).isEmpty());
        assertTrue(MacroDeltaRegistry.parseKeywords("不是数组").isEmpty());
        assertTrue(MacroDeltaRegistry.parseKeywords("[broken").isEmpty());
        assertTrue(MacroDeltaRegistry.keywordHit("[\"米行\"]", "在米行卖米"));
        assertFalse(MacroDeltaRegistry.keywordHit("[\"米行\"]", "在药铺抓药"));
    }

    /** 列表读：全局 + 项目行（调试面板）。 */
    @Test
    void listRulesCombinesGlobalAndProject() {
        ActorMacroDeltaRule g = rule("trade", "[\"经商\"]", "market_activity", 0.6);
        ActorMacroDeltaRule p = rule("treat", "[\"诊脉\"]", "medicine", 0.1);
        when(ruleRepository.findByProjectIdOrderByIdAsc(MacroDeltaRegistry.GLOBAL_PROJECT))
                .thenReturn(List.of(g));
        when(ruleRepository.findByProjectIdOrderByIdAsc(100L)).thenReturn(List.of(p));
        List<ActorMacroDeltaRule> all = registry.listRules(100L);
        assertEquals(2, all.size());
    }

    private static ActorMacroDeltaRule rule(String type, String keywords, String indicator, double delta) {
        ActorMacroDeltaRule r = new ActorMacroDeltaRule();
        r.setActionType(type);
        r.setKeywords(keywords);
        r.setTargetMacro("economy");
        r.setIndicator(indicator);
        r.setDelta(delta);
        r.setEnabled(true);
        return r;
    }
}
