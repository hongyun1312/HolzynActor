package com.holzyn.actor.domain.npc.state.config;

import com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor;
import com.holzyn.actor.domain.npc.state.repository.ActorEmotionAnchorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PAD 基础情绪锚点启动种子单元测试（状态模块 M2，EmotionAnchorSeeder）。
 * <p>职责：验证——7 锚点内置定义（数值对齐主开发方案 §7.1）/缺失插入/数值不一致同步更新/一致跳过幂等。</p>
 * <p>设计依据：《NPC状态模块_主开发方案_V1.0》§7.1（PAD 空间与基础情绪锚点表）。</p>
 */
class EmotionAnchorSeederTest {

    private ActorEmotionAnchorRepository repo;
    private EmotionAnchorSeeder seeder;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(ActorEmotionAnchorRepository.class);
        when(repo.findByCode(any())).thenReturn(Optional.empty());
        seeder = new EmotionAnchorSeeder(repo);
    }

    @Test
    void anchors_containsSevenWithDesignValues() {
        var anchors = EmotionAnchorSeeder.anchors();
        assertEquals(7, anchors.size());
        // calm 锚点数值 = 设计文档 §7.1（同时是实体默认值对齐基准）
        EmotionAnchorSeeder.AnchorDef calm = anchors.get("calm");
        assertNotNull(calm);
        assertEquals("平静", calm.name());
        assertEquals(0.10d, calm.pleasure(), 0.001);
        assertEquals(-0.10d, calm.arousal(), 0.001);
        assertEquals(0.00d, calm.dominance(), 0.001);
        // 其余锚点
        assertTrue(anchors.containsKey("happy"));
        assertTrue(anchors.containsKey("angry"));
        assertTrue(anchors.containsKey("sad"));
        assertTrue(anchors.containsKey("fearful"));
        assertTrue(anchors.containsKey("surprised"));
        assertTrue(anchors.containsKey("disgusted"));
        assertEquals(0.80d, anchors.get("happy").pleasure(), 0.001);
        assertEquals(-0.50d, anchors.get("angry").pleasure(), 0.001);
        assertEquals(0.35d, anchors.get("disgusted").arousal(), 0.001);
    }

    @Test
    void run_insertsAllWhenMissing() {
        seeder.run(null);
        verify(repo, Mockito.times(7)).save(any(ActorEmotionAnchor.class));
    }

    @Test
    void run_skipsWhenIdentical() {
        // 全部 7 码均已存在且与内置一致 → 零插入零更新
        when(repo.findByCode(any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            EmotionAnchorSeeder.AnchorDef def = EmotionAnchorSeeder.anchors().get(code);
            ActorEmotionAnchor existing = new ActorEmotionAnchor();
            existing.setCode(code);
            existing.setName(def.name());
            existing.setPleasure(def.pleasure());
            existing.setArousal(def.arousal());
            existing.setDominance(def.dominance());
            existing.setDescription(def.description());
            return Optional.of(existing);
        });
        seeder.run(null);
        verify(repo, never()).save(any(ActorEmotionAnchor.class));
    }

    @Test
    void run_updatesWhenValueChanged() {
        // calm 已存在但数值与内置不一致 → 仅同步 calm（其余 6 码一致跳过）
        when(repo.findByCode(any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            EmotionAnchorSeeder.AnchorDef def = EmotionAnchorSeeder.anchors().get(code);
            ActorEmotionAnchor existing = new ActorEmotionAnchor();
            existing.setCode(code);
            existing.setName(def.name());
            // 仅 calm 的 pleasure 篡改为 0.99（触发同步更新）
            existing.setPleasure("calm".equals(code) ? 0.99d : def.pleasure());
            existing.setArousal(def.arousal());
            existing.setDominance(def.dominance());
            existing.setDescription(def.description());
            return Optional.of(existing);
        });
        seeder.run(null);
        // 仅 calm 被保存，且数值已同步为内置 0.10（其余 6 码一致跳过）
        verify(repo, Mockito.times(1)).save(Mockito.argThat(a ->
                a.getCode().equals("calm") && Math.abs(a.getPleasure() - 0.10d) < 0.001));
    }
}
