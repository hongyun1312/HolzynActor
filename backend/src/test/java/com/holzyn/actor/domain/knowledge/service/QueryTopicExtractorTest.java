package com.holzyn.actor.domain.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QueryTopicExtractor 单元测试（知识检索质量深化，2026-09-29_04）。
 * <p>职责：验证话题词索引构建（2~4 字高频窗口 / 标题加权 / 停用词边界过滤 / 纯数字排除 /
 * 低频排除 / 词表上限）与话题词提取（query 包含匹配 / 最长优先 / 去包含 / 上限 / 空输入）。</p>
 */
class QueryTopicExtractorTest {

    // ==================== 索引构建 ====================

    /** 正文高频窗口入索引（出现 ≥2 次、长度 2~4）。 */
    @Test
    void buildTermIndexExtractsFrequentWindows() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of(), List.of("云来茶馆临街而建，云来茶馆茶香四溢。"));
        assertTrue(index.containsKey("云来茶馆"), "4 字窗口应入索引");
        assertTrue(index.containsKey("云来茶"), "3 字窗口应入索引");
        assertTrue(index.containsKey("茶馆"), "2 字窗口应入索引");
        assertEquals(2, index.get("云来茶馆"));
    }

    /** 标题窗口加权（×3）：标题词优先于同频正文词。 */
    @Test
    void buildTermIndexWeightsTitles() {
        // 正文「势力」出现 2 次；标题「世界设定·势力」的「势力」窗口 ×3 → 总频 5
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of("世界设定·势力"), List.of("势力之争，势力分布。"));
        assertEquals(5, index.get("势力"), "标题窗口 ×3 + 正文 2 次 = 5");
    }

    /** 停用词边界过滤：以停用词开头/结尾的窗口丢弃（「什么样的」整段丢弃）。 */
    @Test
    void buildTermIndexFiltersStopCharBoundaries() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of(), List.of("什么样的地方，什么样的地方。"));
        assertFalse(index.containsKey("什么样"), "以停用词开头的窗口应丢弃");
        assertFalse(index.containsKey("什么"), "以停用词结尾的窗口应丢弃");
        assertFalse(index.containsKey("么样"), "以停用词开头的窗口应丢弃");
    }

    /** 实体词内部含停用词字符不受影响（「云来茶馆」中部「来」为停用词但非边界）。 */
    @Test
    void buildTermIndexKeepsEntityWithInnerStopChar() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of(), List.of("云来茶馆 云来茶馆"));
        assertTrue(index.containsKey("云来茶馆"), "边界非停用词的实体词应保留");
    }

    /** 纯数字窗口不入索引（「2026」无主题意义）。 */
    @Test
    void buildTermIndexFiltersPureDigits() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of(), List.of("2026 2026 2026"));
        assertTrue(index.isEmpty(), "纯数字窗口全部丢弃");
    }

    /** 低频窗口不入索引（单次出现多为叙述词）。 */
    @Test
    void buildTermIndexFiltersLowFrequency() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of(), List.of("唯一词只出现一次。"));
        assertFalse(index.containsKey("唯一词"), "频次 1 < 下限 2 应排除");
    }

    /** 标题锚词保底：内容高频词洪水（如人物知识卡逐张入库）时，标题词不受 top-N 淘汰（冒烟驱动）。 */
    @Test
    void buildTermIndexProtectsTitleAnchorTerms() {
        List<String> noise = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            noise.add("噪音词" + i + " 噪音词" + i); // 每个词频次 2（低于标题权重）
        }
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of("世界设定·势力"), noise);
        assertTrue(index.containsKey("势力"), "标题锚词频次=标题权重 3 → 保底入索引（不被 400 个噪音词挤出）");
        assertEquals(3, index.get("势力"));
        assertTrue(index.size() <= QueryTopicExtractor.MAX_TERMS, "索引总容量仍受上限约束");
    }

    /** 空输入 → 空索引。 */
    @Test
    void buildTermIndexEmptyInputs() {
        assertTrue(QueryTopicExtractor.buildTermIndex(null, null).isEmpty());
        assertTrue(QueryTopicExtractor.buildTermIndex(List.of(), List.of()).isEmpty());
    }

    // ==================== 话题词提取 ====================

    /** query 包含匹配 + 最长优先：同时命中「云来茶馆/云来茶/茶馆」时取最长。 */
    @Test
    void extractTopicsLongestFirst() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of(), List.of("云来茶馆 云来茶馆 云来茶馆"));
        List<String> topics = QueryTopicExtractor.extractTopics("云来茶馆是个什么样的地方", index, 3);
        assertEquals(List.of("云来茶馆"), topics, "短词被长词包含应剔除，只剩最长实体词");
    }

    /** 去包含：互不包含的话题词可共存（受上限约束）。 */
    @Test
    void extractTopicsKeepsIndependentTopics() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of("世界设定·势力", "世界设定·历史"), List.of());
        List<String> topics = QueryTopicExtractor.extractTopics("青禾村有哪些势力和历史", index, 5);
        assertTrue(topics.contains("势力"), "标题锚定词应命中");
        assertTrue(topics.contains("历史"), "标题锚定词应命中");
    }

    /** 上限约束：只返回前 maxTopics 个。 */
    @Test
    void extractTopicsRespectsLimit() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of("世界设定·地理", "世界设定·势力", "世界设定·文化"), List.of());
        List<String> topics = QueryTopicExtractor.extractTopics("地理势力和文化", index, 2);
        assertEquals(2, topics.size(), "超出上限的候选被截断");
        // 三个候选等长等频，返回前 2 个（顺序不稳定，只校验成员）
        assertTrue(topics.contains("地理") && topics.contains("势力")
                        || topics.contains("地理") && topics.contains("文化")
                        || topics.contains("势力") && topics.contains("文化"),
                "返回的 2 个应是 {地理,势力,文化} 中的任意两个");
    }

    /** 空 query / 空索引 / 上限 ≤0 → 空列表。 */
    @Test
    void extractTopicsEmptyInputs() {
        Map<String, Integer> index = QueryTopicExtractor.buildTermIndex(
                List.of("世界设定·地理"), List.of());
        assertTrue(QueryTopicExtractor.extractTopics("", index, 2).isEmpty());
        assertTrue(QueryTopicExtractor.extractTopics(null, index, 2).isEmpty());
        assertTrue(QueryTopicExtractor.extractTopics("地理", Map.of(), 2).isEmpty());
        assertTrue(QueryTopicExtractor.extractTopics("地理", index, 0).isEmpty());
    }
}
