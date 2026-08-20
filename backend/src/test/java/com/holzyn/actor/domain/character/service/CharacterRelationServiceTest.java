package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CharacterRelationService AI 关系输出解析逻辑单元测试。
 * <p>验证 {@link CharacterRelationService#parseDrafts}：顶层数组/对象包裹/Markdown 代码块、
 * 自环过滤、重复去重、类型缺省、字段截断、空输入边界。</p>
 */
class CharacterRelationServiceTest {

    /** 顶层 JSON 数组 */
    @Test
    void parseDraftsReadsTopLevelArray() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "[{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"传剑\"},"
                        + "{\"from\":\"雪球\",\"to\":\"奶糖\",\"relationType\":\"挚友\",\"description\":\"互相扶持\"}]");
        assertEquals(2, drafts.size());
        assertEquals("奶糖", drafts.get(0).from());
        assertEquals("暖阳", drafts.get(0).to());
        assertEquals("师徒", drafts.get(0).relationType());
        assertEquals("传剑", drafts.get(0).description());
    }

    /** 顶层对象含 relations 数组（兼容某些模型返回 {relations:[...]}） */
    @Test
    void parseDraftsReadsRelationsFieldObject() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "{\"relations\":[{\"from\":\"林晚\",\"to\":\"沈夜\",\"relationType\":\"师徒\",\"description\":\"传剑\"}]}");
        assertEquals(1, drafts.size());
        assertEquals("林晚", drafts.get(0).from());
    }

    /** Markdown 代码块包裹 + 首尾说明文字 */
    @Test
    void parseDraftsStripsCodeFenceAndSurroundingText() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "以下是识别到的关系：\n```json\n[{\"from\":\"奶糖\",\"to\":\"雪球\",\"relationType\":\"挚友\",\"description\":\"\"}]\n```\n完毕");
        assertEquals(1, drafts.size());
        assertEquals("挚友", drafts.get(0).relationType());
    }

    /** 自环（from==to）过滤；from/to 缺失过滤；同对同类型去重 */
    @Test
    void parseDraftsFiltersSelfLoopAndDeduplicates() {
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "[{\"from\":\"奶糖\",\"to\":\"奶糖\",\"relationType\":\"自我\",\"description\":\"自环\"},"
                        + "{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"a\"},"
                        + "{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"b\"},"
                        + "{\"from\":\"\",\"to\":\"暖阳\",\"relationType\":\"师徒\",\"description\":\"缺from\"},"
                        + "{\"from\":\"雪球\",\"to\":\"暖阳\",\"relationType\":\"\",\"description\":\"缺类型\"}]");
        assertEquals(2, drafts.size());
        // 去重后保留第一条
        assertEquals("a", drafts.get(0).description());
        // 缺类型 → 默认「未知关系」
        assertEquals("未知关系", drafts.get(1).relationType());
    }

    /** 关系类型与描述超长截断（type≤50 / desc≤255） */
    @Test
    void parseDraftsTruncatesOverlongFields() {
        String longType = "非".repeat(80);
        String longDesc = "描".repeat(400);
        List<RelationDraftVO> drafts = CharacterRelationService.parseDrafts(
                "[{\"from\":\"奶糖\",\"to\":\"暖阳\",\"relationType\":\"" + longType + "\",\"description\":\"" + longDesc + "\"}]");
        assertEquals(1, drafts.size());
        assertEquals(50, drafts.get(0).relationType().length());
        assertEquals(255, drafts.get(0).description().length());
    }

    /** 空输入 / 非法 JSON / 空数组 → 空列表 */
    @Test
    void parseDraftsHandlesEmptyAndInvalidInput() {
        assertTrue(CharacterRelationService.parseDrafts(null).isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("   ").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("不是 JSON").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("[]").isEmpty());
        assertTrue(CharacterRelationService.parseDrafts("{\"relations\":[]}").isEmpty());
    }
}
