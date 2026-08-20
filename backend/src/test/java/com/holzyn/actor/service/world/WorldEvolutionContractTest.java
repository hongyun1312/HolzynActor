package com.holzyn.actor.service.world;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorEvolution;
import com.holzyn.actor.domain.world.entity.ActorEvolutionParticipant;
import com.holzyn.actor.domain.world.entity.ActorEvolutionTurn;
import com.holzyn.actor.domain.world.entity.ActorScene;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 世界演化/时间线契约测试（V2.1，不加载 Spring 上下文）。
 * <p>职责：验证新实体字段可读写与统一响应 R 的契约行为（默认值由 JPA @PrePersist 保证，
 * 此处验证显式赋值透传）。</p>
 */
class WorldEvolutionContractTest {

    /**
     * ActorEvent 字段读写（时间线事件契约）。
     */
    @Test
    void eventEntityFields() {
        ActorEvent e = new ActorEvent();
        e.setProjectId(10L);
        e.setKind("evolution");
        e.setTitle("深夜咖啡馆里的密谈");
        e.setContent("内容");
        e.setSource("evolution");
        e.setSceneId(3L);
        e.setEvolutionId(7L);
        assertEquals("evolution", e.getKind());
        assertEquals("evolution", e.getSource());
        assertEquals(10L, e.getProjectId());
        assertEquals(3L, e.getSceneId());
        assertEquals(7L, e.getEvolutionId());
    }

    /**
     * ActorEvolution 字段读写（演化会话契约）。
     */
    @Test
    void evolutionEntityFields() {
        ActorEvolution ev = new ActorEvolution();
        ev.setProjectId(10L);
        ev.setSceneId(3L);
        ev.setTitle("咖啡馆会面");
        ev.setMode("manual");
        ev.setStatus("running");
        ev.setTurnCount(3);
        assertEquals("running", ev.getStatus());
        assertEquals("manual", ev.getMode());
        assertEquals(3, ev.getTurnCount());
        assertEquals(3L, ev.getSceneId());
    }

    /**
     * 参与者/轮次实体字段读写。
     */
    @Test
    void participantAndTurnFields() {
        ActorEvolutionParticipant p = new ActorEvolutionParticipant();
        p.setEvolutionId(1L);
        p.setCharacterId(2L);
        p.setStatus("left");
        assertEquals("left", p.getStatus());

        ActorEvolutionTurn t = new ActorEvolutionTurn();
        t.setEvolutionId(1L);
        t.setCharacterId(2L);
        t.setRole("assistant");
        t.setType("action");
        t.setContent("走到窗前");
        assertEquals("action", t.getType());
    }

    /**
     * ActorScene 字段读写。
     */
    @Test
    void sceneEntityFields() {
        ActorScene s = new ActorScene();
        s.setProjectId(10L);
        s.setName("咖啡馆");
        s.setLocation("城东");
        s.setBackground("昏黄的灯光，雨声淅沥");
        assertEquals("咖啡馆", s.getName());
        assertEquals("城东", s.getLocation());
    }

    /**
     * 契约：演化接口成功响应 R 包装（code=200、error 为 null）。
     */
    @Test
    void evolutionResponseContract() {
        R<Map<String, Object>> r = R.ok(Map.of("evolutionId", 1L));
        assertEquals(200, r.getCode());
        assertNull(r.getError());
    }
}
