package com.holzyn.actor.domain.world.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 事件冷存储映射单元测试。
 *
 * <p>职责：锁定「归档是热表行的完整拷贝」——任何一列漏拷，被淘汰事件就永久缺一块，
 * 而这些列正是编年史提炼/因果回放/审计取证要用的（含因果链三列与 decision_source）。</p>
 */
class ActorEventArchiveTest {

    /** 全字段拷贝 + 归档元数据（event_id / game_day / archived_at）。 */
    @Test
    void ofCopiesAllBusinessColumns() {
        ActorEvent e = new ActorEvent();
        e.setId(42L);
        e.setProjectId(143L);
        e.setKind("action");
        e.setSource("action");
        e.setDecisionSource("AI");
        e.setCharacterId(7L);
        e.setCharacterIds("npc:7,npc:9");
        e.setSceneId(3L);
        e.setEvolutionId(5L);
        e.setLocationId(11L);
        e.setLocationName("河神庙");
        e.setVisibility("location");
        e.setTitle("陈阿伯：去河边挑水");
        e.setContent("挑水回家");
        e.setPayloadJson("{\"type\":\"move\"}");
        e.setImportance(4);
        e.setGameHour(172800L);
        e.setSourceKey("plan:99");
        e.setCauseEventId(41L);
        e.setCorrelationId("41");
        e.setDepth(1);
        LocalDateTime created = LocalDateTime.of(2026, 11, 9, 10, 0);
        e.setCreatedAt(created);
        e.setUpdatedAt(created);
        LocalDateTime archived = LocalDateTime.of(2026, 11, 9, 12, 30);

        ActorEventArchive a = ActorEventArchive.of(e, 720924L, archived);

        assertEquals(42L, a.getEventId());
        assertEquals(143L, a.getProjectId());
        assertEquals("action", a.getKind());
        assertEquals("action", a.getSource());
        assertEquals("AI", a.getDecisionSource());
        assertEquals(7L, a.getCharacterId());
        assertEquals("npc:7,npc:9", a.getCharacterIds());
        assertEquals(3L, a.getSceneId());
        assertEquals(5L, a.getEvolutionId());
        assertEquals(11L, a.getLocationId());
        assertEquals("河神庙", a.getLocationName());
        assertEquals("location", a.getVisibility());
        assertEquals("陈阿伯：去河边挑水", a.getTitle());
        assertEquals("挑水回家", a.getContent());
        assertEquals("{\"type\":\"move\"}", a.getPayloadJson());
        assertEquals(4, a.getImportance());
        assertEquals(172800L, a.getGameHour());
        assertEquals("plan:99", a.getSourceKey());
        assertEquals(41L, a.getCauseEventId(), "因果链父事件必须保留（否则回放会断链）");
        assertEquals("41", a.getCorrelationId());
        assertEquals(1, a.getDepth());
        assertEquals(created, a.getEventCreatedAt());
        assertEquals(created, a.getEventUpdatedAt());
        assertEquals(720924L, a.getGameDay());
        assertEquals(archived, a.getArchivedAt());
    }

    /** 空事件 / 空归档时刻：null 安全，归档时刻兜底为当前时间。 */
    @Test
    void ofHandlesNullEventAndNullArchivedAt() {
        assertNull(ActorEventArchive.of(null, 1L, null));
        ActorEvent e = new ActorEvent();
        e.setId(1L);
        e.setProjectId(2L);
        ActorEventArchive a = ActorEventArchive.of(e, null, null);
        assertEquals(1L, a.getEventId());
        assertNull(a.getGameDay());
        org.junit.jupiter.api.Assertions.assertNotNull(a.getArchivedAt());
    }
}
