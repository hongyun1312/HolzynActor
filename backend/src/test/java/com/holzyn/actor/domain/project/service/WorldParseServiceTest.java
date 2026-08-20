package com.holzyn.actor.domain.project.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldParseService 纯逻辑单元测试（2026-08-19 新建项目解析重构）。
 * <p>职责：验证严格格式判定（标题命中 ≥5/7 类）、完整分段提取（嵌套子标题块）、
 * AI 分段输出解析（缺字段兜底）与程序化扩写兜底（≥1500 字）。</p>
 */
class WorldParseServiceTest {

    /** 严格格式：Markdown 标题命中 5/7 类 → true。 */
    @Test
    void isStrictFormatHitsFiveOfSeven() {
        String content = """
                # 地理设定
                山海交错，灵气充沛之地。
                ## 东大陆
                东方大陆的详细地貌描述。
                # 势力格局
                两大阵营对峙千年。
                # 规则体系
                灵气修炼与阵法体系。
                # 社会文化
                民风淳朴，崇尚自然。
                # 历史脉络
                自上古神战至今的千年历史。
                """;
        assertTrue(WorldParseService.isStrictFormat(content), "命中 5/7 类标题即视为严格格式");
    }

    /** 非严格格式：标题命中 <5/7 类 → false。 */
    @Test
    void isStrictFormatMissesThreshold() {
        String content = """
                # 背景
                这是一个关于勇者与魔王的故事。
                # 世界观
                剑与魔法的世界。
                # 主角介绍
                勇者阿强，热血少年。
                """;
        assertFalse(WorldParseService.isStrictFormat(content), "仅命中 2/7 类不算严格格式");
    }

    /** 无 Markdown 标题：按不明确格式处理（false）。 */
    @Test
    void isStrictFormatNoHeadings() {
        assertFalse(WorldParseService.isStrictFormat("没有标题，只有正文内容的一段文字。"),
                "无标题不视为严格格式");
    }

    /** 完整分段提取：从最高相关度标题取到下一个同级标题，含嵌套子标题与正文。 */
    @Test
    void extractFullSectionCapturesNestedBlock() {
        String content = """
                # 地理设定
                山海交错之地，灵气充沛。
                ## 东大陆
                东大陆是一望无际的草原与密林。
                ## 西大陆
                西大陆是连绵的雪山。
                # 势力格局
                两大阵营对峙。
                """;
        String section = WorldParseService.extractFullSection(content,
                new String[]{"地理", "地图", "地貌", "生态", "版图", "世界构造"});
        assertTrue(section.contains("# 地理设定"));
        assertTrue(section.contains("山海交错之地，灵气充沛。"));
        assertTrue(section.contains("## 东大陆"));
        assertTrue(section.contains("东大陆是一望无际的草原与密林。"));
        assertTrue(section.contains("西大陆是连绵的雪山。"));
        assertFalse(section.contains("势力格局"), "不应越界到下一个同级标题");
    }

    /** 完整分段提取：同一类别多个同级章节块全部收集（如 核心NPC角色 + 主要配角）。 */
    @Test
    void extractFullSectionCapturesMultipleBlocks() {
        String content = """
                ## 核心NPC角色
                ### 九月
                九月是九尾狐。
                ### 敖烈
                敖烈是白龙马。
                ## 主要配角
                ### 嫦娥
                嫦娥住在月宫。
                ## 补充设定
                补充内容。
                """;
        String section = WorldParseService.extractFullSection(content,
                new String[]{"角色", "人物", "NPC", "主角", "配角", "人物设定"});
        assertTrue(section.contains("## 核心NPC角色"));
        assertTrue(section.contains("### 九月"));
        assertTrue(section.contains("九月是九尾狐。"));
        assertTrue(section.contains("## 主要配角"));
        assertTrue(section.contains("嫦娥住在月宫。"));
        assertFalse(section.contains("补充设定"), "不应包含其他类别");
    }

    /** 完整分段提取：父标题命中时整块提取（含子标题正文）；嵌套命中不重复拼接。 */
    @Test
    void extractFullSectionNoDuplicateFromNestedMatch() {
        String content = """
                ## 角色信息
                ### 主角·九月
                九月是主角。
                ### 配角·敖烈
                敖烈是配角。
                """;
        String section = WorldParseService.extractFullSection(content,
                new String[]{"角色", "主角", "配角"});
        // 「主角·九月」「配角·敖烈」本身也命中关键词，但属于父块「角色信息」，不得重复拼接
        assertEquals(1, countOccurrences(section, "### 主角·九月"), "嵌套命中不得重复");
        assertEquals(1, countOccurrences(section, "九月是主角。"), "正文不重复");
        assertTrue(section.contains("敖烈是配角。"));
    }

    /** 严格格式/提取对 Windows \r\n 换行免疫（2026-08-19 修复：标题行尾部 \r 不再导致判定与提取失败）。 */
    @Test
    void strictFormatAndExtractionHandleCrLf() {
        String content = "# 地理设定\r\n山海交错之地。\r\n# 势力格局\r\n两大阵营对峙。\r\n"
                + "# 规则体系\r\n灵气修炼。\r\n# 社会文化\r\n民风淳朴。\r\n# 历史脉络\r\n千年历史。\r\n";
        assertTrue(WorldParseService.isStrictFormat(content), "\\r\\n 换行下仍识别严格格式");
        String section = WorldParseService.extractFullSection(content, new String[]{"地理", "地图"});
        assertTrue(section.contains("山海交错之地。"), "\\r\\n 换行下仍能提取正文");
    }

    /** 判定只看标题：正文提及关键词不算严格格式命中（避免正文污染）。 */
    @Test
    void strictFormatIgnoresBodyKeywordMentions() {
        String content = """
                # 第一章 起源
                这片大陆的地理环境极其复杂。
                # 第二章 争端
                各方势力的博弈持续千年。
                # 第三章 修行
                规则体系随时代演进。
                """;
        assertFalse(WorldParseService.isStrictFormat(content), "正文提及关键词不算标题命中");
    }

    /** 子串出现次数统计。 */
    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int from = 0;
        while ((from = s.indexOf(sub, from)) >= 0) {
            count++;
            from += sub.length();
        }
        return count;
    }

    /** 完整分段提取：无命中返回空串。 */
    @Test
    void extractFullSectionNoMatch() {
        String content = "# 角色信息\n只有角色，没有地理。";
        assertEquals("", WorldParseService.extractFullSection(content, new String[]{"地理", "地貌"}));
    }

    /** AI 分段输出解析：完整 JSON 正确取字段。 */
    @Test
    void parseSegmentsParsesAllFields() {
        String json = """
                {"projectName":"星陨之都","projectSummary":"一个奇幻都市","worldName":"星陨之都世界观",
                 "genre":"奇幻","era":"现代都市","geography":"城邦与天际浮岛","factions":"星陨议会与夜行众",
                 "magicSystem":"星辉能量体系","culture":"以星象为尊的文化","history":"千年星陨史",
                 "supplement":"补充设定内容","characters":"主角：洛星，星辉使。反派：夜枭。"}
                """;
        var seg = WorldParseService.parseSegments(json);
        assertEquals("星陨之都", seg.projectName());
        assertEquals("星陨之都世界观", seg.worldName());
        assertEquals("城邦与天际浮岛", seg.geography());
        assertEquals("补充设定内容", seg.supplement());
        assertEquals("主角：洛星，星辉使。反派：夜枭。", seg.characters());
    }

    /** AI 分段输出解析：缺字段兜底为空串，不抛错。 */
    @Test
    void parseSegmentsMissingFieldsFallback() {
        var seg = WorldParseService.parseSegments("{\"projectName\":\"P\"}");
        assertEquals("P", seg.projectName());
        assertEquals("", seg.worldName());
        assertEquals("", seg.geography());
        assertEquals("", seg.characters());
    }

    /** 程序化扩写兜底：不足 1500 字时扩展到 ≥1500 字且保留原文。 */
    @Test
    void padSegmentEnsuresMinLength() {
        String base = "地理设定原文。";
        String padded = WorldParseService.padSegment(base, "星陨之都", "星陨之都世界观", "地理设定");
        assertTrue(padded.length() >= 1500, "兜底后应达到分段最小字数 1500，实际 " + padded.length());
        assertTrue(padded.startsWith(base), "应保留原有内容");
    }
}
