package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldClockService.fastForward 单元测试（O2.5 ① 阶段控制：参数化拨时钟快进）。
 * <p>验证：目标/相对推进换算、静止前提校验、回拨拒绝、回补基准重置、时钟位置落库。</p>
 */
class WorldClockFastForwardTest {

    private ActorWorldClockRepository clockRepository;
    private ActorProjectRepository projectRepository;
    private ActorWorldSettingRepository settingRepository;
    private WorldClockService service;
    private ActorWorldClock clock;

    @BeforeEach
    void setUp() {
        clockRepository = Mockito.mock(ActorWorldClockRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        settingRepository = Mockito.mock(ActorWorldSettingRepository.class);
        service = new WorldClockService(clockRepository, projectRepository, settingRepository);
        clock = new ActorWorldClock();
        clock.setProjectId(1L);
        clock.setLastGameSecond(86400L);          // D1 00:00
        clock.setLastGameHour(24L);
        clock.setPaused(1);                        // 暂停=允许快进
        clock.setWorldRunning(1);
        clock.setRate(24);
        clock.setWorldStartAt(LocalDateTime.now().minusDays(1));
        clock.setWorldStartGameHour(0L);
        when(clockRepository.findByProjectId(1L)).thenReturn(Optional.of(clock));
        when(clockRepository.save(any(ActorWorldClock.class))).thenAnswer(inv -> inv.getArgument(0));
        when(settingRepository.findTopByProjectIdOrderByVersionDesc(any())).thenReturn(Optional.empty());
    }

    /** 绝对目标：直接拨到 targetGameSecond，重置回补基准，lastGameHour 同步。 */
    @Test
    void fastForwardAbsoluteTarget() {
        var vo = service.fastForward(1L, 172800L, null, null, null);
        assertEquals(172800L, clock.getLastGameSecond());
        assertEquals(48L, clock.getLastGameHour());
        assertEquals(172800L, vo.getLastGameSecond());
        verify(clockRepository).save(clock);
        // 回补基准重置为当前（防拨动后按旧基准补推）
        assertEquals(LocalDateTime.now().toLocalDate(), clock.getLastEngineRunAt().toLocalDate());
    }

    /** 相对推进：日/时/分累计换算（1 日 + 2 时 + 30 分 = 90000 秒）。 */
    @Test
    void fastForwardRelativePlus() {
        service.fastForward(1L, null, 30L, 2L, 1L);
        assertEquals(86400L + 86400L + 7200L + 1800L, clock.getLastGameSecond());
    }

    /** 非静止世界（未暂停且持续运行）→ 400 拒绝（防与引擎推进竞争）。 */
    @Test
    void fastForwardRequiresStaticWorld() {
        clock.setPaused(0);
        BizException e = assertThrows(BizException.class,
                () -> service.fastForward(1L, 172800L, null, null, null));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("世界静止"));
    }

    /** 回拨拒绝：目标 < 当前 → 400。 */
    @Test
    void fastForwardRejectsBackward() {
        BizException e = assertThrows(BizException.class,
                () -> service.fastForward(1L, 1000L, null, null, null));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("不支持回拨"));
    }

    /** 无任何目标/相对参数 → 400。 */
    @Test
    void fastForwardRequiresParams() {
        BizException e = assertThrows(BizException.class,
                () -> service.fastForward(1L, null, null, null, null));
        assertEquals(400, e.getCode());
    }

    /** 目标与当前相同 → 400（无谓拨动）。 */
    @Test
    void fastForwardRejectsNoOp() {
        BizException e = assertThrows(BizException.class,
                () -> service.fastForward(1L, 86400L, null, null, null));
        assertEquals(400, e.getCode());
    }

    /** 世界未开启持续运行（worldRunning=0）→ 视为静止允许快进。 */
    @Test
    void fastForwardAllowedWhenWorldStopped() {
        clock.setPaused(0);
        clock.setWorldRunning(0);
        var vo = service.fastForward(1L, 100000L, null, null, null);
        assertEquals(100000L, vo.getLastGameSecond());
    }
}
