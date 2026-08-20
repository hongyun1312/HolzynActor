package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.memory.service.MemoryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoryService 纯逻辑单元测试（P4-1 长期记忆）。
 * <p>职责：验证注入排序（重要度+新）、预算滚动淘汰、文本重叠去重（Dice 二元组）与会话摘要间隔判断。</p>
 */
class MemoryServiceTest {

    /**
     * 构造一条记忆。
     *
     * @param importance 重要度
     * @param createdAt  创建时间
     * @param content    内容
     * @return 记忆实体
     */
    private static ActorMemory mem(int importance, LocalDateTime createdAt, String content) {
        ActorMemory m = new ActorMemory();
        m.setImportance(importance);
        m.setCreatedAt(createdAt);
        m.setContent(content);
        return m;
    }

    /**
     * 注入排序：重要度 desc + 较新优先，且 topK 截断。
     */
    @Test
    void rankForInjectionSortsByImportanceThenNewest() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 14, 10, 0);
        List<ActorMemory> all = List.of(
                mem(3, t.minusHours(3), "c3"),
                mem(5, t.minusHours(1), "a5"),
                mem(5, t.minusHours(5), "b5"),
                mem(1, t, "d1"));
        List<ActorMemory> ranked = MemoryService.rankForInjection(all, 2);
        assertEquals(2, ranked.size(), "topK=2 应只返回 2 条");
        assertEquals("a5", ranked.get(0).getContent(), "重要度最高且最新应排第一");
        assertEquals("b5", ranked.get(1).getContent(), "同重要度下较新的优先");
    }

    /**
     * 注入排序：空列表/非正 topK 返回空。
     */
    @Test
    void rankForInjectionHandlesEmpty() {
        assertTrue(MemoryService.rankForInjection(null, 8).isEmpty());
        assertTrue(MemoryService.rankForInjection(List.of(), 8).isEmpty());
        assertTrue(MemoryService.rankForInjection(List.of(mem(5, LocalDateTime.now(), "x")), 0).isEmpty());
    }

    /**
     * 预算淘汰：超预算时删除「重要度低 + 旧」的记忆，保留高价值。
     */
    @Test
    void evictToBudgetRemovesLowValueOldest() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 14, 10, 0);
        List<ActorMemory> all = List.of(
                mem(5, t, "重要且新"),
                mem(1, t.minusDays(1), "低价值且旧"),
                mem(2, t.minusHours(2), "较低价值"));
        List<ActorMemory> toDelete = MemoryService.evictToBudget(all, 2);
        assertEquals(1, toDelete.size(), "预算 2 超 1 条应删 1 条");
        assertEquals("低价值且旧", toDelete.get(0).getContent(), "应先删重要度最低且最旧的");
    }

    /**
     * 预算淘汰：未超预算 / 空列表 / 边界（恰好等于预算）不删除。
     */
    @Test
    void evictToBudgetNoDeleteWithinBudget() {
        LocalDateTime t = LocalDateTime.now();
        assertTrue(MemoryService.evictToBudget(null, 50).isEmpty());
        assertTrue(MemoryService.evictToBudget(List.of(), 50).isEmpty());
        assertEquals(0, MemoryService.evictToBudget(List.of(mem(1, t, "a"), mem(2, t, "b")), 2).size(),
                "恰好等于预算不删除");
        assertEquals(2, MemoryService.evictToBudget(
                List.of(mem(1, t, "a"), mem(2, t, "b"), mem(3, t, "c")), 1).size(),
                "预算为 1 时应删除 2 条低价值");
    }

    /**
     * 文本重叠度：相同文本=1；完全不同=0；部分重叠介于两者之间。
     */
    @Test
    void overlapScoreBasics() {
        assertEquals(1.0, MemoryService.overlapScore("李雷喜欢钓鱼", "李雷喜欢钓鱼"), 1e-9);
        assertEquals(0.0, MemoryService.overlapScore("李雷喜欢钓鱼", "今天天气晴朗"), 1e-9);
        double partial = MemoryService.overlapScore("李雷喜欢钓鱼", "李雷喜欢钓鱼，常去城东湖边");
        assertTrue(partial > 0 && partial < 1, "部分重叠应介于 0~1 之间，实际 " + partial);
        assertEquals(0.0, MemoryService.overlapScore("", "任意"), 1e-9);
        assertEquals(0.0, MemoryService.overlapScore("  ", null), 1e-9);
    }

    /**
     * 二次去重：达到阈值判定重复；低于阈值不重复；已有列表为空不重复。
     */
    @Test
    void isDuplicateRespectsThreshold() {
        List<String> existing = List.of("李雷在城东市场开了一家药材铺");
        assertTrue(MemoryService.isDuplicate("李雷在城东市场开了一家药材铺", existing, 0.6),
                "完全相同的记忆应判定重复");
        assertFalse(MemoryService.isDuplicate("林安担任城主护卫队长", existing, 0.6),
                "完全不同的记忆不应判定重复");
        assertFalse(MemoryService.isDuplicate("任意新事实", null, 0.6), "无已有记忆不重复");
        assertFalse(MemoryService.isDuplicate("任意新事实", List.of(), 0.6));
    }

    /**
     * 分段去重：整条 Dice 低于阈值但子句高度重叠时仍判重（拦截「表述不同但事实相同」的重复）。
     */
    @Test
    void isDuplicateCatchesSubClauseOverlap() {
        // 新事实的「师傅叫玄铁」子句与已有记忆的「师傅叫玄铁」片段高度重叠，应判重
        List<String> existing = List.of("林安的师傅叫玄铁，早年隐居云雾山，多年不露面，林安也多年未见。");
        String similar = "我得知林安的师傅叫玄铁，隐居在云雾山，林安从未向人提及此事，因此感到警觉。";
        assertTrue(MemoryService.isDuplicate(similar, existing, 0.6),
                "子句级重叠应判定重复（整条 Dice 可能低于阈值）");
    }

    /**
     * 子句切分：按中文/英文标点切分并保留标点，空文本返回空。
     */
    @Test
    void splitClausesSplitsByPunctuation() {
        List<String> clauses = MemoryService.splitClauses("师傅叫玄铁。他隐居在云雾山，多年不露面！");
        assertEquals(3, clauses.size());
        assertTrue(clauses.get(0).contains("玄铁。"));
        assertTrue(clauses.get(1).contains("云雾山，"));
        assertTrue(MemoryService.splitClauses("").isEmpty());
        assertTrue(MemoryService.splitClauses(null).isEmpty());
    }

    /**
     * 摘要间隔判断：第 10/20 轮（整数倍）触发；非倍数/不足间隔/非法间隔不触发。
     */
    @Test
    void shouldGenerateSummaryAtIntervalBoundaries() {
        assertTrue(MemoryService.shouldGenerateSummary(10, 10), "恰好 10 轮应触发");
        assertTrue(MemoryService.shouldGenerateSummary(20, 10), "20 轮（第 2 个间隔）应触发");
        assertFalse(MemoryService.shouldGenerateSummary(9, 10), "不足间隔不触发");
        assertFalse(MemoryService.shouldGenerateSummary(11, 10), "非整数倍不触发");
        assertFalse(MemoryService.shouldGenerateSummary(5, 0), "非法间隔（0）不触发");
        assertFalse(MemoryService.shouldGenerateSummary(0, 10), "无已完成回复不触发");
    }

    // ==================== 记忆门控（寒暄/无实质信息跳过） ====================

    /**
     * 构造一条消息。
     */
    private static com.holzyn.actor.domain.conversation.entity.ActorMessage msg(String role, String content) {
        com.holzyn.actor.domain.conversation.entity.ActorMessage m =
                new com.holzyn.actor.domain.conversation.entity.ActorMessage();
        m.setRole(role);
        m.setContent(content);
        m.setStatus("done");
        return m;
    }

    /**
     * 寒暄文本判定：空/极短/含寒暄词且短 → 寒暄；长文本 → 非寒暄。
     */
    @Test
    void isGreetingTextJudgesGreetings() {
        assertTrue(MemoryService.isGreetingText(null, 20), "null 视为寒暄");
        assertTrue(MemoryService.isGreetingText("  ", 20), "空白视为寒暄");
        assertTrue(MemoryService.isGreetingText("嗯", 20), "单字回复视为寒暄");
        assertTrue(MemoryService.isGreetingText("你好", 20), "寒暄词+短视为寒暄");
        assertTrue(MemoryService.isGreetingText("你好呀，在吗", 20), "寒暄词+≤阈值视为寒暄");
        assertFalse(MemoryService.isGreetingText("我在城东市场找到了一家药材铺", 20), "长文本非寒暄");
        // 含寒暄词但超长（>20 字）→ 非寒暄（如「你好」之后带了实质信息）
        assertFalse(MemoryService.isGreetingText("你好，我打听到师傅的下落，他可能隐居在云雾山附近了", 20),
                "超阈值即使含寒暄词也算实质");
        // 群聊阈值更宽松：8 字内含寒暄才算寒暄，8 字以上即使含寒暄词也视为实质
        assertTrue(MemoryService.isGreetingText("你好呀", 8));
        assertFalse(MemoryService.isGreetingText("你好呀，大家都在吗", 8), "群聊 >8 字含寒暄词视为实质");
    }

    /**
     * 逐轮门控：最近一轮为寒暄 → 不提取；为实质内容 → 提取；无用户消息 → 不提取。
     */
    @Test
    void lastRoundHasSubstanceGatesByLatestUser() {
        // 最近一轮寒暄（单聊阈值 20）
        List<com.holzyn.actor.domain.conversation.entity.ActorMessage> greeting = List.of(
                msg("user", "你好呀"),
                msg("assistant", "嗯，你好。有什么事情吗？"));
        assertFalse(MemoryService.lastRoundHasSubstance(greeting, false, 20, 8),
                "单聊寒暄轮应跳过提取");
        // 同一「含寒暄词但 8~20 字之间」的用户消息：单聊（阈值 20）算寒暄，群聊（阈值 8）算实质
        List<com.holzyn.actor.domain.conversation.entity.ActorMessage> boundary = List.of(
                msg("user", "你好呀，大家都在忙什么"),
                msg("assistant", "在商量去云雾山的事。"));
        assertFalse(MemoryService.lastRoundHasSubstance(boundary, false, 20, 8),
                "单聊阈值 20：10 字含寒暄词算寒暄");
        assertTrue(MemoryService.lastRoundHasSubstance(boundary, true, 20, 8),
                "群聊阈值 8：10 字含寒暄词算实质（阈值更宽松）");
        // 实质轮
        List<com.holzyn.actor.domain.conversation.entity.ActorMessage> substantive = List.of(
                msg("user", "我打听到师傅的线索了，他可能在云雾山隐居"),
                msg("assistant", "是吗！这个消息太重要了。"));
        assertTrue(MemoryService.lastRoundHasSubstance(substantive, false, 20, 8),
                "实质轮应提取");
        // 空/无用户消息
        assertFalse(MemoryService.lastRoundHasSubstance(List.of(), false, 20, 8));
        assertFalse(MemoryService.lastRoundHasSubstance(null, false, 20, 8));
    }

    /**
     * 会话结束兜底门控：窗口内任一实质消息 → 整段有价值（结尾寒暄不影响）。
     */
    @Test
    void segmentHasSubstanceJudgesWholeWindow() {
        // 整段寒暄 → 不补提
        List<com.holzyn.actor.domain.conversation.entity.ActorMessage> allGreeting = List.of(
                msg("user", "你好"),
                msg("assistant", "嗯。"),
                msg("user", "在吗"),
                msg("assistant", "在。"));
        assertFalse(MemoryService.segmentHasSubstance(allGreeting, false, 20, 8),
                "整段寒暄不应补提");
        // 中间有实质、结尾寒暄 → 应补提（结尾「再见」不影响整段价值）
        List<com.holzyn.actor.domain.conversation.entity.ActorMessage> mixed = List.of(
                msg("user", "我找到了师傅的藏身之处，在云雾山"),
                msg("assistant", "太好了，我们明天出发吧。"),
                msg("user", "好的，再见"));
        assertTrue(MemoryService.segmentHasSubstance(mixed, false, 20, 8),
                "整段有实质内容即使结尾寒暄也应补提");
        assertFalse(MemoryService.segmentHasSubstance(null, false, 20, 8));
        assertFalse(MemoryService.segmentHasSubstance(List.of(), false, 20, 8));
    }
}
