package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WorldClockService「世界时间单一权威」单元测试（2026-09-25 长跑第 3 轮抓修·时间口径统一）。
 *
 * <p>缺陷背景（第 3 轮长跑实证）：世界时间曾有两个并行来源——① 引擎推进位置
 * （{@code last_game_second}，AI 提示词时间感 / 角色作息 / 昼夜光照 / 时间线事件都用它）；
 * ② 真实锚点 × 速率的「流动换算」（世界时钟 API、设置页、时间线记忆节点用它）。世界停止
 * （未激活/应用关闭/被 AI 拖慢）期间②继续虚跑、①冻结，恢复 5 天前的基线后两者相差约
 * 120 游戏日：AI 眼里是 0001年01月01日 00时，玩家看到的是 0001年05月03日 19时，同一决策链里
 * 一半组件看引擎时钟、一半看流动时钟。本测试锁定统一后的不变量：</p>
 * <ul>
 *   <li>对外一切世界时间字段（gameSecond/gameHour/day/hourOfDay/periodText/gameTimeText）与
 *       {@code currentGameHour} 一律等于引擎推进位置；</li>
 *   <li>流动换算降级为诊断字段（flowingGameSecond/flowingGameHour/clockLagGameSeconds），
 *       仅用于观测世界落后真实流逝的量；</li>
 *   <li>真实时刻 → 世界时间（gameSecondAt/gameHourAt）以引擎位置为基准点反推，at=now 时严格等于
 *       世界时钟显示值；</li>
 *   <li>世界时间校准（alignWorldTimeSeconds / 起始游戏时刻变更）同时移动权威位置与锚点，且设置页
 *       回声（值未变）不得把世界时间拨回。</li>
 * </ul>
 */
class WorldClockUnifiedTimeTest {

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

    /** 恢复基线的真实场景：引擎位置 = 冻结进度（3 小时 3 分 30 秒），锚点停在 5 真实日前。 */
    private ActorWorldClock restoredBaselineClock() {
        ActorWorldClock c = new ActorWorldClock();
        c.setProjectId(1L);
        c.setRate(24);
        c.setPaused(0);
        c.setWorldRunning(1);
        c.setLastGameSecond(11_010L);
        c.setLastGameHour(3L);
        c.setWorldStartGameHour(0L);
        c.setWorldStartAt(LocalDateTime.now().minusDays(5));
        return c;
    }

    private void stubClock(ActorWorldClock c) {
        when(projectRepo.findByIdAndUserIdAndDeleted(1L, 1L, 0)).thenReturn(Optional.of(project()));
        when(projectRepo.findById(1L)).thenReturn(Optional.of(project()));
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));
        when(clockRepo.save(any(ActorWorldClock.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== ① 对外世界时间字段 = 引擎推进位置 ====================

    /**
     * 恢复基线（锚点 5 真实日前）：对外时间字段必须是引擎位置，而不是流动换算值。
     * <p>这是缺陷的直接回归：统一前十字段全部取流动换算 → 0001年05月03日 19 时（约 120 游戏日之后），
     * 与 AI 提示词时间感（引擎位置 0001年01月01日 03 时）互相矛盾。</p>
     */
    @Test
    void voWorldTimeFollowsEnginePositionNotFlowingConversion() {
        ActorWorldClock c = restoredBaselineClock();
        stubClock(c);

        WorldClockVO vo = service().toVO(c);

        assertEquals(11_010L, vo.getGameSecond(), "世界时间（秒）= 引擎推进位置");
        assertEquals(3L, vo.getGameHour(), "世界时间（小时）= 引擎位置派生");
        assertEquals(1L, vo.getDay(), "第 1 日（引擎位置 3 小时）");
        assertEquals(3, vo.getHourOfDay(), "当日 03 时");
        assertEquals("深夜", vo.getPeriodText(), "03 时=深夜（与 AI 作息/光照同源）");
        assertEquals("世界历 0001年01月01日 03时03分30秒", vo.getGameTimeText(),
                "世界历文本=引擎位置（统一前为 5 真实日 × 24 = 约 0001年05月03日）");
        assertEquals(11_010L, vo.getEngineGameSecond(), "引擎位置字段与权威时间同源（前端插值口径）");
        assertEquals(3, vo.getGameMinute().intValue(), "分钟内便捷字段同源");
        assertEquals((int) (11_010L / 60L % 1440), vo.getGameDayMinute().intValue(), "当日分钟同源");
    }

    /**
     * 流动换算降级为诊断口径：flowingGameSecond 保留「真实流逝 × 速率」的推算值，
     * clockLagGameSeconds = 流动值 − 权威值（世界落后真实流逝的量），供长跑观测推进是否掉速。
     */
    @Test
    void flowingConversionIsDiagnosticOnly() {
        ActorWorldClock c = restoredBaselineClock();
        stubClock(c);

        WorldClockVO vo = service().toVO(c);

        assertNotNull(vo.getFlowingGameSecond(), "流动换算值仍可取（诊断）");
        assertEquals(vo.getFlowingGameSecond(), vo.getFlowingGameHour() * 3600L,
                "流动秒/小时同源");
        assertEquals(vo.getFlowingGameSecond() - vo.getGameSecond(), vo.getClockLagGameSeconds(),
                "滞后量 = 流动值 − 引擎位置");
        assertTrue(vo.getClockLagGameSeconds() > 10_000_000L,
                "5 真实日未运行 ⇒ 滞后约 1036 万游戏秒（≈120 游戏日），断言值=" + vo.getClockLagGameSeconds());
    }

    /**
     * 暂停时流动口径冻结在暂停时刻（诊断值不虚跑），权威时间仍为引擎位置。
     */
    @Test
    void pausedClockFreezesFlowingDiagnostic() {
        ActorWorldClock c = restoredBaselineClock();
        c.setPaused(1);
        c.setPausedAt(LocalDateTime.now().minusHours(2));
        c.setWorldStartAt(LocalDateTime.now().minusHours(4));
        c.setWorldStartGameHour(0L);
        stubClock(c);

        WorldClockVO vo = service().toVO(c);

        assertEquals(11_010L, vo.getGameSecond(), "权威时间=引擎位置（暂停不改变世界实际时间）");
        assertEquals(2L * 3600L * 24L, vo.getFlowingGameSecond().longValue(),
                "流动口径冻结在暂停时刻：锚点后 4 真实小时中只计到暂停时刻的 2 小时 × 24");
    }

    // ==================== ② currentGameHour 全链同源 ====================

    /**
     * {@code currentGameHour} 与 {@code currentEngineGameHour} 同源：全部消费方（PlanningService/
     * DecisionService/PerceptionService/AutonomousChatService/WorldEventService 等）拿到同一个时刻，
     * 秒级列优先于兼容派生列（两列不一致时以秒级为准）。
     */
    @Test
    void currentGameHourUnifiedWithEnginePosition() {
        ActorWorldClock c = restoredBaselineClock();
        c.setLastGameHour(999L); // 故意让兼容派生列与秒级列不一致
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));

        assertEquals(3L, service().currentEngineGameHour(1L).longValue(), "秒级权威优先（11010/3600=3）");
        assertEquals(3L, service().currentGameHour(1L).longValue(), "currentGameHour 与引擎位置同源（统一口径）");
    }

    /**
     * 兼容派生列为空（存量行）时按秒级位置派生，不返回 null——避免消费方退化为「时刻未知」。
     */
    @Test
    void currentEngineGameHourFallsBackToSecondColumn() {
        ActorWorldClock c = new ActorWorldClock();
        c.setProjectId(1L);
        c.setRate(24);
        c.setLastGameHour(null);
        c.setLastGameSecond(7_200L);
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));

        assertEquals(2L, service().currentEngineGameHour(1L).longValue(), "7200 秒=2 游戏小时");
    }

    // ==================== ③ 真实时刻 → 世界时间（统一映射） ====================

    /**
     * gameSecondAt 以引擎位置为基准点反推：at=now 时严格等于世界时钟显示值；
     * 过去时刻按真实流逝 × 速率回退；未来时刻返回当前位置；超出可反推窗口收敛到 0。
     */
    @Test
    void gameSecondAtIsAnchoredOnEnginePosition() {
        ActorWorldClock c = restoredBaselineClock();
        c.setLastGameSecond(10_000_000L);
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));
        LocalDateTime now = LocalDateTime.now();

        Long atNow = service().gameSecondAt(1L, now);
        assertTrue(Math.abs(atNow - 10_000_000L) <= 100L,
                "at=now ⇒ 等于引擎位置（允许调用间隔秒级误差），实际=" + atNow);

        Long halfHourAgo = service().gameSecondAt(1L, now.minusMinutes(30));
        long expected = 10_000_000L - 1_800L * 24L;
        assertTrue(Math.abs(halfHourAgo - expected) <= 100L,
                "30 真实分钟 × 24 ≈ 43200 游戏秒（组合会话窗口折算），实际=" + halfHourAgo);

        assertEquals(10_000_000L, service().gameSecondAt(1L, now.plusMinutes(5)).longValue(),
                "未来时刻返回当前位置（世界时间不超前权威位置）");
        assertEquals(0L, service().gameSecondAt(1L, now.minusDays(30)).longValue(),
                "超出可反推窗口（引擎位置 ÷ rate ≈ 4.8 真实日）⇒ 收敛到纪元 0");
    }

    /**
     * gameHourAt（R3 staleness 基准）：换算基准改为引擎位置后，「时间差」语义不变
     * ——同一基准点下差值 = 真实流逝 × 速率（staleness 只比较两端差值）。
     */
    @Test
    void gameHourAtKeepsStalenessDifferenceSemantics() {
        ActorWorldClock c = restoredBaselineClock();
        c.setLastGameSecond(10_000_000L);
        when(clockRepo.findByProjectId(1L)).thenReturn(Optional.of(c));
        LocalDateTime now = LocalDateTime.now();

        long nowHour = service().gameHourAt(1L, now);
        long hourAgo = service().gameHourAt(1L, now.minusHours(1));
        assertEquals(24L, nowHour - hourAgo, "1 真实小时 × rate24 = 24 游戏小时（staleness 阈值语义不变）");
    }

    // ==================== ④ 世界时间校准与回声容忍 ====================

    /**
     * 世界时间校准（世界初始化/设置页「起始游戏时刻」）：同时移动权威位置并把锚点重基到该时刻
     * ——重基瞬间流动诊断值与权威位置相等（此后两者的差只反映本次运行内的落后量）。
     */
    @Test
    void alignWorldTimeMovesAuthorityAndRebasesAnchor() {
        ActorWorldClock c = restoredBaselineClock();
        stubClock(c);

        WorldClockVO vo = service().alignWorldTimeSeconds(1L, 1_060_000L);

        assertEquals(1_060_000L, c.getLastGameSecond(), "权威世界时间=校准值");
        assertEquals(1_060_000L / 3600L, c.getLastGameHour(), "兼容派生列同步");
        assertEquals(1_060_000L / 3600L, c.getWorldStartGameHour(), "锚点游戏时刻=校准值的小时部分");
        assertNotNull(c.getLastEngineRunAt(), "回补基准重置（不补校准前的真实流逝）");
        assertEquals("世界历 0001年01月13日 06时26分40秒", vo.getGameTimeText(), "世界历文本=校准值");
        assertTrue(Math.abs(vo.getClockLagGameSeconds()) <= 60L,
                "重基后流动口径与权威位置相等（小时内余秒按 rate 折算的取整误差 < rate 秒），实际="
                        + vo.getClockLagGameSeconds());
        assertTrue(Duration.between(c.getWorldStartAt(), LocalDateTime.now()).getSeconds() <= 70L,
                "锚点真实时刻重基到当前（仅回退小时内余秒 1600÷24=66 真实秒）");
    }

    /**
     * 设置页保存「起始游戏时刻」变更 ⇒ 世界时间随之校准（字段不再静默失效）。
     */
    @Test
    void updateClockStartHourChangeCalibratesWorldTime() {
        ActorWorldClock c = restoredBaselineClock();
        stubClock(c);

        WorldClockVO vo = service().updateClock(1L, 1L, new WorldClockDTO(null, null, null, null, 500L));

        assertEquals(500L * 3600L, c.getLastGameSecond(), "起始游戏时刻变更 ⇒ 引擎位置校准到该时刻");
        assertEquals(500L, vo.getGameHour(), "对外世界时间同步");
        assertEquals(500L, c.getWorldStartGameHour(), "锚点同步");
    }

    /**
     * 设置页回声容忍：保存时把页面加载读到的原值一并回传（值未变）⇒ 不得校准——
     * 否则每保存一次就把世界时间拨回页面加载时刻（世界在期间已推进）。
     */
    @Test
    void updateClockEchoOfUnchangedStartHourDoesNotRewindWorld() {
        ActorWorldClock c = restoredBaselineClock();
        c.setWorldStartGameHour(3L); // 重基后的自然状态：锚点小时 = 引擎小时
        stubClock(c);

        service().updateClock(1L, 1L, new WorldClockDTO(24, null, null, null, 3L));

        assertEquals(11_010L, c.getLastGameSecond(), "值未变 ⇒ 世界时间保持引擎当前位置（不回拨）");
    }

    /**
     * 只改真实锚点时刻：权威世界时间不变（世界时间以引擎位置为准），仅调整诊断映射。
     */
    @Test
    void updateClockAnchorOnlyKeepsWorldTime() {
        ActorWorldClock c = restoredBaselineClock();
        c.setWorldStartGameHour(3L);
        stubClock(c);

        LocalDateTime newAnchor = LocalDateTime.now().minusHours(6);
        service().updateClock(1L, 1L, new WorldClockDTO(null, null, null, newAnchor, null));

        assertEquals(11_010L, c.getLastGameSecond(), "只改锚点不动世界时间");
        assertEquals(newAnchor, c.getWorldStartAt(), "锚点真实时刻已更新");
        assertEquals(3L, c.getWorldStartGameHour(), "锚点游戏时刻保持");
    }

    /**
     * 关闭「世界持续运行」后重新开启：锚点重基到权威位置（诊断口径在重启瞬间归零），
     * 世界时间本身不跳变（仍为冻结进度）。
     */
    @Test
    void turnOnRunningRebasesAnchorWithoutMovingWorldTime() {
        ActorWorldClock c = restoredBaselineClock();
        c.setWorldRunning(0);
        c.setWorldStartGameHour(0L);
        c.setWorldStartAt(LocalDateTime.now().minusDays(5));
        stubClock(c);

        WorldClockVO vo = service().updateClock(1L, 1L, new WorldClockDTO(null, null, 1, null, null));

        assertEquals(11_010L, vo.getGameSecond(), "世界时间=冻结进度（不补离线时间）");
        assertEquals(3L, c.getWorldStartGameHour(), "锚点重基到权威位置");
        assertTrue(Duration.between(c.getWorldStartAt(), LocalDateTime.now()).getSeconds() <= 60L,
                "锚点真实时刻重基到当前");
    }
}