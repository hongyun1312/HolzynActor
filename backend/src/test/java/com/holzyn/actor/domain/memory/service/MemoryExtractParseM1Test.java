package com.holzyn.actor.domain.memory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆抽取新契约解析单元测试（记忆系统重构 M1，2026-09-12）。
 * <p>职责：验证 memory_extract 契约扩展——target（character/world/both）双通道路由字段、
 * operation（add/update/invalidate）操作判定字段、kind 扩展（fact/episodic/event/entity）、
 * 旧契约兼容（缺省回退 target=character、operation=add）、非法值归一化。</p>
 * <p>设计依据：《记忆系统重构与世界时间季节系统_设计方案_V1.0》§3.4。</p>
 */
class MemoryExtractParseM1Test {

    /**
     * 新契约完整解析：target/operation/kind/content/importance 逐字段提取。
     */
    @Test
    void parsesFullContract() {
        String ai = "[{\"target\":\"world\",\"operation\":\"add\",\"kind\":\"event\","
                + "\"content\":\"村口古树遭雷击，乡邻议论纷纷\",\"importance\":4}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size());
        MemoryExtractParse.ExtractedMemory em = out.get(0);
        assertEquals("world", em.target());
        assertEquals("add", em.operation());
        assertEquals("event", em.kind());
        assertEquals("村口古树遭雷击，乡邻议论纷纷", em.content());
        assertEquals(4, em.importance());
        assertTrue(em.isWorldTargeted());
        assertFalse(em.isCharacterTargeted());
    }

    /**
     * target=both：双写路由判定（世界+角色双侧都路由）。
     */
    @Test
    void bothTargetRoutesToBothSides() {
        String ai = "[{\"target\":\"both\",\"operation\":\"add\",\"kind\":\"episodic\","
                + "\"content\":\"我和村长一起扑灭了粮仓的火\",\"importance\":4}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size());
        assertTrue(out.get(0).isWorldTargeted());
        assertTrue(out.get(0).isCharacterTargeted());
    }

    /**
     * 旧契约兼容：无 target/operation 字段 → 回退 character/add（既有行为不回归）。
     */
    @Test
    void legacyContractFallsBackToCharacterAdd() {
        String ai = "[{\"kind\":\"fact\",\"content\":\"李雷在城东市场开了药材铺\",\"importance\":4}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size());
        assertEquals("character", out.get(0).target());
        assertEquals("add", out.get(0).operation());
        assertEquals("fact", out.get(0).kind());
        assertFalse(out.get(0).isWorldTargeted());
        assertTrue(out.get(0).isCharacterTargeted());
    }

    /**
     * kind 扩展解析：episodic/event/entity 合法值识别，未知值回退 fact。
     */
    @Test
    void parsesExtendedKinds() {
        String ai = "[{\"kind\":\"episodic\",\"content\":\"我冒雨去看了热闹\"},"
                + "{\"kind\":\"entity\",\"content\":\"张屠户改行卖布了\"},"
                + "{\"kind\":\"unknown\",\"content\":\"未知类型\"}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(3, out.size());
        assertEquals("episodic", out.get(0).kind());
        assertEquals("entity", out.get(1).kind());
        assertEquals("fact", out.get(2).kind(), "未知 kind 应回退 fact");
    }

    /**
     * operation 非法值回退 add；target 非法值回退 character。
     */
    @Test
    void normalizesIllegalTargetAndOperation() {
        String ai = "[{\"target\":\"banana\",\"operation\":\"delete\",\"content\":\"测试\"}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(1, out.size());
        assertEquals("character", out.get(0).target());
        assertEquals("add", out.get(0).operation());
    }

    /**
     * update/invalidate 操作解析（Mem0 式操作判定契约）。
     */
    @Test
    void parsesUpdateAndInvalidateOperations() {
        String ai = "[{\"operation\":\"update\",\"content\":\"张屠户的铺子从卖肉改成了卖布\",\"importance\":4},"
                + "{\"operation\":\"invalidate\",\"content\":\"我和他的误会已解除\",\"importance\":3}]";
        List<MemoryExtractParse.ExtractedMemory> out = MemoryExtractParse.parseExtract(ai);
        assertEquals(2, out.size());
        assertEquals("update", out.get(0).operation());
        assertEquals("invalidate", out.get(1).operation());
    }
}
