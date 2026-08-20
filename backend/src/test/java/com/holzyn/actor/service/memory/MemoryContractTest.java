package com.holzyn.actor.service.memory;

import com.holzyn.actor.common.R;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.memory.entity.ActorMemory;
import com.holzyn.actor.domain.memory.vo.MemoryVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 记忆契约测试（P4-1 记忆 API 契约，不加载 Spring 上下文）。
 * <p>职责：验证记忆 VO 字段映射与统一响应 R&lt;PageResult&lt;MemoryVO&gt;&gt; 的契约行为。</p>
 */
class MemoryContractTest {

    /**
     * MemoryVO 字段映射：实体字段完整透传（含角色级/项目级）。
     */
    @Test
    void memoryVoMapsEntityFields() {
        ActorMemory m = new ActorMemory();
        m.setId(7L);
        m.setProjectId(3L);
        m.setCharacterId(9L);
        m.setKind("fact");
        m.setContent("李雷在城东市场开药材铺");
        m.setImportance(4);
        m.setDeleted(1); // 软删字段不应出现在 VO
        m.setCreatedAt(LocalDateTime.of(2026, 8, 14, 10, 30));
        MemoryVO vo = MemoryVO.of(m);
        assertEquals(7L, vo.getId());
        assertEquals(3L, vo.getProjectId());
        assertEquals(9L, vo.getCharacterId());
        assertEquals("fact", vo.getKind());
        assertEquals("李雷在城东市场开药材铺", vo.getContent());
        assertEquals(4, vo.getImportance());
        assertEquals(LocalDateTime.of(2026, 8, 14, 10, 30), vo.getCreatedAt());
        // 软删等审计字段不在 VO 中（无对应 getter，字段映射天然排除）
    }

    /**
     * 项目级记忆（characterId 空）VO：空角色 ID 正常透传，供前端区分项目级记忆。
     */
    @Test
    void memoryVoSupportsProjectLevel() {
        ActorMemory m = new ActorMemory();
        m.setId(1L);
        m.setProjectId(2L);
        m.setKind("summary");
        m.setContent("世界树枯萎引发各国动荡");
        m.setImportance(5);
        MemoryVO vo = MemoryVO.of(m);
        assertNull(vo.getCharacterId(), "项目级记忆角色 ID 应为空");
        assertEquals("summary", vo.getKind());
    }

    /**
     * 契约：记忆 API 成功响应 R 包装分页结果（code=200、data 非空、error 为 null）。
     */
    @Test
    void memoryListResponseContract() {
        PageResult<MemoryVO> page = PageResult.of(List.of(new MemoryVO(), new MemoryVO()), 2, 1, 20);
        R<PageResult<MemoryVO>> r = R.ok(page);
        assertEquals(200, r.getCode());
        assertEquals("ok", r.getMessage());
        assertNotNull(r.getData());
        assertEquals(2, r.getData().getList().size());
        assertEquals(1, r.getData().getTotalPages(), "2 条每页 20 应 1 页");
        assertNull(r.getError());
    }
}
