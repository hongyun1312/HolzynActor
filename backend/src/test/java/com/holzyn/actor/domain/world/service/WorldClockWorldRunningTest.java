package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldClockService「世界持续运行」开关（B3 F3 world_running）单元测试。
 * <p>职责：验证 ① 存量行开启持续运行时重置引擎回补基准（不补推开启前历史）；
 * ② 关闭时置 0 且不回写基准；③ 新建时钟行默认持续运行开（新项目默认开，用户决策）。</p>
 */
class WorldClockWorldRunningTest {

    private final ActorWorldClockRepository clockRepo = Mockito.mock(ActorWorldClockRepository.class);
    private final ActorProjectRepository projectRepo = Mockito.mock(ActorProjectRepository.class);
    private final ActorWorldSettingRepository settingRepo = Mockito.mock(ActorWorldSettingRepository.class);

    private WorldClockService service() {
        return new WorldClockService(clockRepo, projectRepo, settingRepo);
    }

    private ActorProject project() {
        ActorProject p = new ActorProject();
        p.setId(1L);
        p.setUserId(1L);
        p.setDeleted(0);
        return p;
    }

    private ActorWorldClock clock(int worldRunning) {
        ActorWorldClock c = new ActorWorldClock();
        c.setProjectId(1L);
        c.setRate(24);
        c.setWorldStartAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        c.setWorldStartGameHour(0L);
        c.setPaused(0);
        c.setWorldRunning(worldRunning);
        c.setLastGameHour(0L);
        return c;
    }

    /**
     * 关闭 → 开启：world_running 置 1 且引擎回补基准重置为当前（防补推开启前积压历史）。
     */
    @Test
    void turnOnRunningResetsBaseline() {
        ActorWorldClock c = clock(0);
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));
        when(clockRepo.save(any(ActorWorldClock.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldClockVO vo = service().updateClock(1L, 1L, new WorldClockDTO(null, null, 1, null, null));
        assertEquals(1, vo.getWorldRunning(), "开启持续运行");
        assertNotNull(c.getLastEngineRunAt(), "开启时重置回补基准");
    }

    /**
     * 开启 → 关闭：world_running 置 0（引擎完全不推进该项目）。
     */
    @Test
    void turnOffRunning() {
        ActorWorldClock c = clock(1);
        c.setLastEngineRunAt(LocalDateTime.of(2026, 8, 21, 10, 0));
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));
        when(clockRepo.save(any(ActorWorldClock.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldClockVO vo = service().updateClock(1L, 1L, new WorldClockDTO(null, null, 0, null, null));
        assertEquals(0, vo.getWorldRunning(), "关闭持续运行");
        assertNotNull(c.getLastEngineRunAt(), "关闭仅置 0，不回写基准（重新开启时再重置）");
    }

    /**
     * 新建时钟行默认「持续运行」开（新项目默认开，存量已有时钟行保持关由列 NULL 表达）。
     */
    @Test
    void newClockDefaultsToRunning() {
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.empty());
        when(projectRepo.findById(1L)).thenReturn(Optional.of(project()));
        when(clockRepo.save(any(ActorWorldClock.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldClockVO vo = service().getClock(1L, 1L);
        assertEquals(1, vo.getWorldRunning(), "新建时钟行默认持续运行开");
        assertNotNull(vo.getLastEngineRunAt(), "新建时钟行回补基准=创建时刻");
    }

    /**
     * 存量行 world_running 为 NULL 时 VO 归 0（存量默认关，需手动开启）。
     */
    @Test
    void legacyNullRunningShowsOff() {
        ActorWorldClock c = clock(0);
        c.setWorldRunning(null);
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));
        when(clockRepo.save(any(ActorWorldClock.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldClockVO vo = service().updateClock(1L, 1L, new WorldClockDTO(null, null, null, null, null));
        assertEquals(0, vo.getWorldRunning(), "存量 NULL 视为关");
        assertNull(c.getLastEngineRunAt(), "不更新则基准保持不变");
    }

    // ============ 2026-09-27 第 2 轮长跑缺陷 P0-6 回归：配置写入必须走窄列更新 ============

    /**
     * P0-6 回归①（纯改速率）：配置侧只写配置列（{@code updateConfig}），<b>不再整实体 save</b>——
     * 原实现整实体回写会把引擎 tick 同期写入的位置/基准覆盖成事务前快照（第 2 轮长跑实测：
     * 暂停态把 rate 设成 8，重开世界后被打回 6；设成 6 又被打回 8；日历偏移 −196s → +1207s）。
     * 纯改速率未重设基准 → {@code lastEngineRunAt} 传 null（保持库内现值，不用旧快照覆盖）。
     */
    @Test
    void rateOnlyChangeUsesNarrowUpdateAndKeepsBaseline() {
        ActorWorldClock c = clock(1);
        c.setLastEngineRunAt(LocalDateTime.of(2026, 8, 21, 10, 0));
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));

        WorldClockVO vo = service().updateClock(1L, 1L, new WorldClockDTO(8, null, null, null, null));

        assertEquals(8, vo.getRate(), "速率生效");
        verify(clockRepo, never()).save(any(ActorWorldClock.class));
        verify(clockRepo).updateConfig(eq(1L), eq(8), eq(0), isNull(), eq(1),
                eq(LocalDateTime.of(2026, 1, 1, 0, 0)), eq(0L), isNull());
    }

    /**
     * P0-6 回归②（暂停恢复）：只有本方法真的重设了回补基准（暂停恢复 / 开启持续运行），
     * 才把 {@code last_engine_run_at} 写下去（非 null）——保证「恢复后不补冻结期」语义仍成立。
     */
    @Test
    void unpausePassesFreshBaselineInNarrowUpdate() {
        ActorWorldClock c = clock(1);
        c.setPaused(1);
        LocalDateTime pausedAt = LocalDateTime.of(2026, 8, 21, 10, 0);
        c.setPausedAt(pausedAt);
        c.setLastEngineRunAt(LocalDateTime.of(2026, 8, 21, 10, 0));
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));

        service().updateClock(1L, 1L, new WorldClockDTO(null, false, null, null, null));

        ArgumentCaptor<LocalDateTime> base = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(clockRepo).updateConfig(eq(1L), anyInt(), eq(0), isNull(), anyInt(), any(), any(), base.capture());
        assertNotNull(base.getValue(), "恢复时写入新基准（不补冻结期）");
        assertTrue(base.getValue().isAfter(pausedAt), "基准=恢复时刻（晚于暂停时刻）");
    }
}
