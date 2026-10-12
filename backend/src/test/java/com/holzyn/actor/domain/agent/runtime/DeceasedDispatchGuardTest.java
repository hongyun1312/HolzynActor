package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.service.WorldFrameParse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 已故角色（DECEASED）退出世界运行 · 回归测试（长跑抓修 2026-09-24）。
 *
 * <p><b>背景</b>：长跑测试实锤——雷大壮（npc:6）山匪袭村重伤离世后仍被每小时决策/即时决策/
 * 环境刺激/独处判定等入口继续调度（调度名单只过滤 deleted、不过滤 DECEASED 阶段），产出
 * 「死人煮粥」式荒谬行动（Judge 4 样本 3 个命中，评分 40.7→16.0）并浪费 AI 预算。
 * 修复 = {@code ActorCharacter.isDeceased()} 统一判定 + 全部调度入口名单过滤。</p>
 *
 * <p>本类守护：① 实体语义；② 环境刺激目标排除已故；③ 独处判定中已故角色不阻断他人独处。</p>
 */
class DeceasedDispatchGuardTest {

    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private RealtimeDecisionProperties props;
    private SolitudeService solitudeService;
    private RealTimeDecisionService realTimeDecisionService;
    private EnvironmentStimulusService envService;

    @BeforeEach
    void setUp() {
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        props = new RealtimeDecisionProperties();
        solitudeService = new SolitudeService(characterRepository, ordinaryNpcRepository, props,
                new NpcTypeResolver(characterRepository));
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());

        WorldEventBus worldEventBus = Mockito.mock(WorldEventBus.class);
        realTimeDecisionService = Mockito.mock(RealTimeDecisionService.class);
        ActorWorldFrameRepository frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        BudgetGuard budgetGuard = Mockito.mock(BudgetGuard.class);
        when(budgetGuard.tryConsume(any())).thenReturn(true);
        envService = new EnvironmentStimulusService(props, worldEventBus, realTimeDecisionService,
                characterRepository, ordinaryNpcRepository, frameRepository, budgetGuard);
    }

    // ==================== ① 实体语义 ====================

    @Test
    void isDeceased_stageBased() {
        ActorCharacter dead = new ActorCharacter();
        dead.setStage("DECEASED");
        assertTrue(dead.isDeceased(), "stage=DECEASED → 已故");

        ActorCharacter alive = new ActorCharacter();
        alive.setStage("PRIME");
        assertFalse(alive.isDeceased(), "stage=PRIME → 在世");

        ActorCharacter noStage = new ActorCharacter();
        assertFalse(noStage.isDeceased(), "stage=null → 在世（保守不拦）");
    }

    // ==================== ② 环境刺激目标排除已故 ====================

    private ActorCharacter newChar(Long id, String name, String location, String stage) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setName(name);
        c.setLocation(location);
        c.setProjectId(1L);
        c.setDeleted(0);
        c.setStage(stage);
        return c;
    }

    private static WorldFrameParse.FrameJson weatherFrame(Map<String, WorldFrameParse.WeatherEntry> weather) {
        return new WorldFrameParse.FrameJson(1L, weather, List.of(), List.of(), List.of(), null, new ArrayList<>());
    }

    @Test
    void onFrameChanged_globalWeatherExcludesDeceased() {
        // 已故角色 npc:2 与在世角色 npc:1 同区域——只有在世者应收到环境刺激
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(
                        newChar(1L, "甲", "集市", "PRIME"),
                        newChar(2L, "乙（已故）", "村口牌坊", "DECEASED")));
        Map<String, WorldFrameParse.WeatherEntry> prevW = new LinkedHashMap<>();
        prevW.put("青禾村", new WorldFrameParse.WeatherEntry("晴", 15d, null));
        Map<String, WorldFrameParse.WeatherEntry> currW = new LinkedHashMap<>();
        currW.put("青禾村", new WorldFrameParse.WeatherEntry("暴雨", 15d, null));
        envService.onFrameChanged(1L, weatherFrame(prevW), weatherFrame(currW), 100000L);
        verify(realTimeDecisionService).onStimulus(eq(1L), eq("npc:1"),
                eq(StimulusType.ENVIRONMENT), anyString(), eq(100000L));
        verify(realTimeDecisionService, never()).onStimulus(eq(1L), eq("npc:2"),
                eq(StimulusType.ENVIRONMENT), anyString(), any());
    }

    // ==================== ③ 独处判定排除已故 ====================

    @Test
    void isAlone_deceasedOtherAtSameLocationDoesNotBlock() {
        // 已故角色与自身同地点——不应阻断「独处」判定（死人不算在场者）
        ActorCharacter me = new ActorCharacter();
        me.setId(1L);
        me.setLocation("茶馆");
        me.setDeleted(0);
        me.setStage("PRIME");
        ActorCharacter dead = new ActorCharacter();
        dead.setId(2L);
        dead.setLocation("茶馆");
        dead.setDeleted(0);
        dead.setStage("DECEASED");
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(eq(1L), eq(0)))
                .thenReturn(List.of(me, dead));
        when(ordinaryNpcRepository.findByProjectIdOrderByIdAsc(eq(1L))).thenReturn(List.of());
        assertTrue(solitudeService.isAlone(1L, "npc:1"),
                "同地点仅有已故角色 → 仍视为独处（修复后行为）");
    }
}
