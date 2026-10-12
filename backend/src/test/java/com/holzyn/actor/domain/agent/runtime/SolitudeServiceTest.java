package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 独处判定服务单元测试（实时行为决策系统 M4，domain/agent/runtime，同包）。
 * <p>职责：验证独处判定（Q10 用户决策：同地点无其他特殊 NPC 且无人群 NPC 才算独处）——
 * 无人在场 / 有其他特殊 NPC / 有人群 NPC / 开关关闭 / 自身位置未知 等分支；独处段文本。</p>
 */
class SolitudeServiceTest {

    private RealtimeDecisionProperties props;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private SolitudeService service;

    @BeforeEach
    void setUp() {
        props = new RealtimeDecisionProperties();
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        // G1a：构造器末参为 NpcTypeResolver（npc:{id} 不再编码类型，是否特殊型查 actor_profile.type）
        service = new SolitudeService(characterRepository, ordinaryNpcRepository, props,
                new NpcTypeResolver(characterRepository));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());
    }

    private ActorCharacter charOf(Long id, String location) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setLocation(location);
        ch.setDeleted(0);
        return ch;
    }

    private ActorCharacter crowdOf(Long id, String location) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        n.setLocation(location);
        return n;
    }

    @Test
    void isAlone_aloneWhenNoOneElse() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "竹林茅舍")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(eq(1L))).thenReturn(List.of());
        assertTrue(service.isAlone(1L, "npc:1"), "同地点无其他特殊 NPC 且无人群 → 独处");
    }

    @Test
    void isAlone_notAloneWithOtherSpecial() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "茶馆"), charOf(2L, "茶馆")));
        assertFalse(service.isAlone(1L, "npc:1"), "有其他特殊 NPC 同地点 → 非独处");
    }

    @Test
    void isAlone_notAloneWithCrowd() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "茶馆")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(eq(1L)))
                .thenReturn(List.of(crowdOf(12L, " 茶馆 ")));
        assertFalse(service.isAlone(1L, "npc:1"), "有人群 NPC 同地点（归一化）→ 非独处");
    }

    @Test
    void isAlone_differentLocationStillAlone() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "竹林茅舍"), charOf(2L, "茶馆")));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(eq(1L)))
                .thenReturn(List.of(crowdOf(12L, "集市")));
        assertTrue(service.isAlone(1L, "npc:1"), "他人都在异地点 → 独处");
    }

    @Test
    void isAlone_disabledReturnsFalse() {
        props.setSolitudeEnabled(false);
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "竹林茅舍")));
        assertFalse(service.isAlone(1L, "npc:1"), "独处开关关 → 判定 false（不注入）");
    }

    @Test
    void isAlone_unknownOwnLocationNotAlone() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, null)));
        assertFalse(service.isAlone(1L, "npc:1"), "自身位置未知 → 保守非独处");
    }

    @Test
    void solitudeSegment_returnsTextWhenAlone() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "竹林茅舍")));
        assertEquals("【独处】此刻四周没有旁人，只有你独自一人", service.solitudeSegment(1L, "npc:1"));
    }

    @Test
    void solitudeSegment_emptyWhenNotAlone() {
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(charOf(1L, "茶馆"), charOf(2L, "茶馆")));
        assertEquals("", service.solitudeSegment(1L, "npc:1"), "非独处 → 空串（不注入）");
    }
}
