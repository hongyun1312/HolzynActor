package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.domain.project.dto.ProjectImportDTO;
import com.holzyn.actor.domain.project.service.ProjectImportService;
import com.holzyn.actor.domain.project.vo.ProjectImportPreviewVO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectImportService 解析/校验逻辑单元测试（文件导入建项目）。
 * <p>职责：验证 AI 输出解析（含无角色 → hasCharacters=false、项目名回退）、
 * 角色数组解析跳过无姓名项、世界观名回退项目名。</p>
 */
class ProjectImportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 无角色文件：hasCharacters=false，项目名/世界观名回退。
     */
    @Test
    void parseNoCharactersSetsFlagFalse() throws Exception {
        String json = "{\"project\":{\"name\":\"星陨之都\",\"summary\":\"蒸汽与魔法交织的世界\"},"
                + "\"worldSetting\":{\"name\":\"\",\"genre\":\"奇幻\",\"era\":\"蒸汽纪元\",\"geography\":\"\",\"factions\":\"\","
                + "\"magicSystem\":\"\",\"culture\":\"\",\"history\":\"\",\"freeText\":\"完整世界观\"},\"characters\":[]}";
        ProjectImportPreviewVO preview = ProjectImportService.parsePreview(json, List.of("星陨之都.md"), objectMapper);
        assertFalse(preview.hasCharacters());
        assertTrue(preview.characters().isEmpty());
        assertEquals("星陨之都", preview.project().name());
        // 世界观名回退项目名
        assertEquals("星陨之都", preview.worldSetting().name());
    }

    /**
     * 含角色文件：hasCharacters=true，角色正确解析。
     */
    @Test
    void parseWithCharactersSetsFlagTrue() throws Exception {
        String json = "{\"project\":{\"name\":\"星陨之都\",\"summary\":\"...\"},"
                + "\"worldSetting\":{\"name\":\"星陨世界\",\"genre\":\"奇幻\"},"
                + "\"characters\":[{\"type\":\"special\",\"name\":\"艾莉\",\"title\":\"旅法师\",\"detail\":\"...\"}]}";
        ProjectImportPreviewVO preview = ProjectImportService.parsePreview(json, List.of("a.md"), objectMapper);
        assertTrue(preview.hasCharacters());
        assertEquals(1, preview.characters().size());
        assertEquals("艾莉", preview.characters().get(0).name());
        assertEquals("星陨世界", preview.worldSetting().name());
    }

    /**
     * 角色数组解析跳过无姓名项。
     */
    @Test
    void parseCharacterArraySkipsBlankName() throws Exception {
        String json = "[{\"name\":\"张三\"},{\"name\":\"\"},{\"title\":\"无名\"}]";
        var list = ProjectImportService.parseCharacterArray(objectMapper.readTree(json));
        assertEquals(1, list.size());
        assertEquals("张三", list.get(0).name());
    }

    /**
     * 程序化兜底：基于初稿扩展到不少于 1000 字（AI 不可用时保证功能可用）。
     */
    @Test
    void padWorldFieldEnsuresMinLength() {
        String padded = ProjectImportService.padWorldField("短地理", "星陨之都", "星陨世界", "地理/地图设定");
        assertTrue(padded.length() >= 1000);
        assertTrue(padded.contains("短地理"));
        // 空初稿也能生成
        String padded2 = ProjectImportService.padWorldField("", "项目", "世界", "历史背景");
        assertTrue(padded2.length() >= 1000);
    }

    /**
     * 角色数组文本解析：容忍模型输出的尾部逗号。
     */
    @Test
    void parseCharacterArrayTextToleratesTrailingComma() throws Exception {
        String content = "```json\n[{\"name\":\"张三\",\"importance\":3},]\n```";
        var list = ProjectImportService.parseCharacterArrayText(content, objectMapper);
        assertEquals(1, list.size());
        assertEquals("张三", list.get(0).name());
        assertEquals(3, list.get(0).importance());
    }
    /**
     * 段落边界截断：不腰斩句子、不超过上限。
     */
    @Test
    void truncateByParagraphKeepsSentenceBoundary() {
        String longText = "第一句：描述地理特征，山川地貌与气候分布。第二句：补充势力格局与主要阵营。第三句：继续展开详细设定内容。第四句：记录重大历史事件与时间脉络。第五句：文化风俗与节日传统。第六句：规则体系与魔法原理，内容较多用于测试截断逻辑。";
        String result = ProjectImportService.truncateByParagraph(longText, 60);
        assertTrue(result.length() <= 60);
        // 应在上限内最后一个完整句号处收尾，不腰斩句子
        assertTrue(result.endsWith("。"));
        // 短文本不截断
        assertEquals("短文本", ProjectImportService.truncateByParagraph("短文本", 3000));
    }
    /**
     * 世界观名回退项目名。
     */
    @Test
    void resolveWorldNameFallsBack() {
        assertEquals("项目A", ProjectImportService.resolveWorldName("项目A", null));
        assertEquals("项目A", ProjectImportService.resolveWorldName("项目A", "  "));
        assertEquals("世界B", ProjectImportService.resolveWorldName("项目A", "世界B"));
    }

    /**
     * 章节感知抽取（大文件适配）：geography 字段取到「地理设定」章节，保留原文地点名。
     */
    @Test
    void extractSectionPicksGeographySection() {
        String md = "# 测试世界\n\n## 基础信息\n\n题材为奇幻。\n\n## 地理设定\n\n"
                + "- **妖灵会馆总馆（昆仑秘境）**：位于昆仑山脉。\n"
                + "- **流石会馆（横断山区）**：横断山脉核心节点。\n\n"
                + "## 势力格局\n\n妖灵会馆主导。\n";
        String geo = ProjectImportService.extractSection(md, "geography");
        assertTrue(geo.contains("地理设定"));
        assertTrue(geo.contains("妖灵会馆总馆"));
        assertTrue(geo.contains("流石会馆"));
        // 不应混入无关章节的势力内容
        assertFalse(geo.contains("妖灵会馆主导"));
    }

    /**
     * 章节感知抽取（大文件适配核心）：位于旧 20000 字截断点之后的「历史脉络」章节也能被取到。
     */
    @Test
    void extractSectionFindsSectionBeyondOldLimit() {
        StringBuilder sb = new StringBuilder("# 世界\n## 基础信息\n");
        sb.append("基础设定。\n".repeat(3500)); // ≈21000 字，超过旧 FILE_MAX=20000
        assertTrue(sb.length() > 20000);
        String full = sb + "\n## 历史脉络\n\n太古纪元灵聚时代，人妖分化。\n\n## 势力格局\n\n多方博弈。\n";
        String hist = ProjectImportService.extractSection(full, "history");
        assertTrue(hist.contains("历史脉络"));
        assertTrue(hist.contains("太古纪元"));
        // 只返回相关章节，不返回整份文件
        assertTrue(hist.length() < full.length());
    }

    /**
     * 无标题 Markdown：回退文件头部（保持旧行为）。
     */
    @Test
    void extractSectionFallsBackToHeadWhenNoHeadings() {
        String plain = "只有一段没有标题的正文内容。";
        assertEquals(plain, ProjectImportService.extractSection(plain, "geography"));
        // 空白内容返回空串
        assertEquals("", ProjectImportService.extractSection("", "geography"));
    }

    /**
     * 章节切分：按 #~###### 标题正确切块。
     */
    @Test
    void splitSectionsSplitsByHeading() {
        String md = "# 标题1\n正文一\n## 标题2\n正文二\n### 标题3\n正文三\n";
        var sections = ProjectImportService.splitSections(md);
        assertEquals(3, sections.size());
        assertTrue(sections.get(1).startsWith("## 标题2"));
        assertTrue(sections.get(1).contains("正文二"));
        // 无标题时返回单块（整段内容视为一个章节），而非空列表
        var plain = ProjectImportService.splitSections("无标题正文");
        assertEquals(1, plain.size());
        assertEquals("无标题正文", plain.get(0));
    }

    /**
     * 自由文本字段直接返回文件整体（上限内），供后续对话/角色卡取完整世界观。
     */
    @Test
    void extractSectionFreeTextReturnsWholeContent() {
        String md = "# 世界\n## 地理设定\n\n地理内容。\n## 历史脉络\n\n历史内容。\n";
        String free = ProjectImportService.extractSection(md, "freeText");
        assertTrue(free.contains("地理内容"));
        assertTrue(free.contains("历史内容"));
    }
}