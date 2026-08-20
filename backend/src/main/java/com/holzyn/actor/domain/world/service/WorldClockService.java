package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 世界时钟服务（P4-2 世界持续模拟核心之一，A-C7 P2 扩展）。
 * <p>职责：承载「真实时间 → 游戏时间」的换算模型——每项目锚定世界起点
 * （worldStartAt 真实时刻，默认=项目创建时刻）+ 起始游戏时刻（worldStartGameHour，默认 0），
 * 按速率 rate（每真实小时推进的游戏小时数，默认 24=1真实小时1游戏日）把真实流逝映射为游戏时刻；
 * 提供时钟状态获取（懒创建时钟行）、速率/暂停/锚点更新与格式化；纯换算逻辑抽静态方法可单测。
 * <p>vP5-7.11：① 暂停冻结——暂停时记录 pausedAt，游戏时间冻结在暂停时刻，恢复时锚点前移暂停
 * 时长（时间不跳变）；② 世界历完整格式——秒级换算 gameSecondOf + formatWorldTime
 * （「XX历 YYYY年MM月DD日 HH时MM分SS秒」，历法名取世界观名+「历」，缺省「世界历」）。</p>
 * 推进执行（角色/人群/事件）由 {@link WorldSimulationJob} 承担。</p>
 * <p>开关：{@code HOLOZYN_ACTOR_WORLD_SIM_ENABLED}（默认 true）由推进任务消费。</p>
 * <p>所属模块：service/world（世界子域-世界时钟）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldClockService {

    /** 默认速率：每真实小时推进的游戏小时数（=1 真实小时推进 1 游戏日） */
    private static final int DEFAULT_RATE = 24;

    /** 世界历常量：1 日 = 24 游戏小时（秒数） */
    static final long SECONDS_PER_DAY = 86400L;
    /** 世界历常量：1 月 = 30 日 */
    static final long SECONDS_PER_MONTH = 30L * SECONDS_PER_DAY;
    /** 世界历常量：1 年 = 12 月 = 360 日 */
    static final long SECONDS_PER_YEAR = 12L * SECONDS_PER_MONTH;

    /** 缺省历法名（世界观未命名时使用） */
    private static final String DEFAULT_CALENDAR = "世界历";

    /**
     * 世界历时间点 → 游戏总秒数（2026-08-19 世界初始化第 5 步：AI 推断当前世界历时间点后落锚点）。
     * <p>规则与 formatWorldTime 同源：1 日 = 24 时，1 月 = 30 日，1 年 = 12 月 = 360 日；
     * 换算公式：gameSecond = (year-1)×年秒数 + (month-1)×月秒数 + (day-1)×日秒数 + hour×3600 + minute×60 + second。
     * 各分量越界时自动夹取（year 最小 1，month 1-12，day 1-30，hour 0-23，minute/second 0-59）。</p>
     *
     * @param year   年（>=1）
     * @param month  月（1-12）
     * @param day    日（1-30）
     * @param hour   时（0-23）
     * @param minute 分（0-59）
     * @param second 秒（0-59）
     * @return 自纪元起的游戏总秒数
     */
    public static long calendarToGameSecond(int year, int month, int day, int hour, int minute, int second) {
        long y = Math.max(1, year);
        long mo = Math.max(1, Math.min(12, month));
        long d = Math.max(1, Math.min(30, day));
        long h = Math.max(0, Math.min(23, hour));
        long mi = Math.max(0, Math.min(59, minute));
        long s = Math.max(0, Math.min(59, second));
        return (y - 1) * SECONDS_PER_YEAR
                + (mo - 1) * SECONDS_PER_MONTH
                + (d - 1) * SECONDS_PER_DAY
                + h * 3600L
                + mi * 60L
                + s;
    }

    private final ActorWorldClockRepository clockRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;

    /** 世界模拟总开关（默认 true；false 时推进任务跳过） */
    @Value("${holzyn.actor.world.sim-enabled:true}")
    private boolean simEnabled;

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 游戏时刻换算：真实时间 → 自锚点起的游戏小时数。
     * <p>公式：gameHours = (now - startAt) 的真实小时数 × rate + startGameHour。
     * 锚点为空时按「以当前时刻为锚点」处理（返回起始游戏时刻）。
     * now 早于锚点时为负值（世界尚未开始，由调用方处理）。</p>
     *
     * @param now             当前真实时刻
     * @param startAt         世界起点真实时刻（可空=以 now 为锚点）
     * @param startGameHour   锚点对应的游戏起始小时数
     * @param rate            速率（每真实小时推进的游戏小时数）
     * @return 当前游戏小时数（自纪元起）
     */
    static long gameHourOf(LocalDateTime now, LocalDateTime startAt, long startGameHour, int rate) {
        long start = startGameHour;
        if (startAt == null) {
            return start;
        }
        if (rate <= 0) {
            return start;
        }
        // 用分钟计算避免浮点精度漂移：真实分钟 × rate / 60 得到游戏小时
        long minutes = Duration.between(startAt, now).toMinutes();
        return (long) Math.floor(minutes * (double) rate / 60.0) + start;
    }

    /**
     * 单次补算封顶：限制一次性推进的游戏小时数（防长时间离线后雪崩）。
     *
     * @param elapsed 流逝的游戏小时数
     * @param cap     封顶值（<=0 视为不封顶）
     * @return 封顶后的步长
     */
    static long capElapsed(long elapsed, long cap) {
        if (elapsed <= 0) {
            return 0;
        }
        return cap > 0 ? Math.min(elapsed, cap) : elapsed;
    }

    /**
     * 游戏时刻换算（<b>秒级</b>）：真实时间 → 自锚点起的游戏总秒数。
     * <p>公式：gameSeconds = startGameHour×3600 + 真实流逝秒 × rate
     * （rate 为每真实小时推进的游戏小时数，即 1 真实秒推进 rate 游戏秒）。
     * 锚点为空时按「以当前时刻为锚点」处理（返回起始游戏时刻）；now 早于锚点返回起始时刻（世界未开始）。</p>
     *
     * @param now             当前真实时刻
     * @param startAt         世界起点真实时刻（可空=以 now 为锚点）
     * @param startGameHour   锚点对应的游戏起始小时数
     * @param rate            速率（每真实小时推进的游戏小时数）
     * @return 当前游戏总秒数（自纪元起）
     */
    static long gameSecondOf(LocalDateTime now, LocalDateTime startAt, long startGameHour, int rate) {
        long startSeconds = startGameHour * 3600L;
        if (startAt == null || rate <= 0) {
            return startSeconds;
        }
        long seconds = Duration.between(startAt, now).getSeconds();
        if (seconds < 0) {
            return startSeconds; // 世界尚未开始：返回起始时刻
        }
        return startSeconds + seconds * rate;
    }

    /**
     * 世界历时间文本（秒级 → 完整格式）：「XX历 YYYY年MM月DD日 HH时MM分SS秒」。
     * <p>历法：1 日 = 24 时，1 月 = 30 日，1 年 = 12 月 = 360 日；
     * gameSecond=0 → 历法 0001年01月01日 00时00分00秒。</p>
     *
     * @param gameSecond 自纪元起的游戏总秒数
     * @param calendar   历法名（如「暖绒市历」；空用「世界历」）
     * @return 世界历完整时间文本
     */
    static String formatWorldTime(long gameSecond, String calendar) {
        return formatWorldTime0(gameSecond, calendar, true);
    }

    /**
     * 世界历时间文本（<b>到分</b>）：「XX历 YYYY年MM月DD日 HH时MM分」（时间线节点展示用）。
     *
     * @param gameSecond 自纪元起的游戏总秒数
     * @param calendar   历法名（空用「世界历」）
     * @return 世界历时间文本（到分）
     */
    static String formatWorldTimeMinute(long gameSecond, String calendar) {
        return formatWorldTime0(gameSecond, calendar, false);
    }

    /**
     * 世界历格式化核心（秒/分级别）。
     *
     * @param gameSecond 游戏总秒数（负数按 0 处理）
     * @param calendar   历法名
     * @param withSecond 是否含秒（false=到分）
     * @return 格式化文本
     */
    private static String formatWorldTime0(long gameSecond, String calendar, boolean withSecond) {
        if (gameSecond < 0) {
            gameSecond = 0;
        }
        long sec = gameSecond % 60;
        long min = (gameSecond / 60) % 60;
        long hour = (gameSecond / 3600) % 24;
        long dayOfMonth = (gameSecond / SECONDS_PER_DAY) % 30 + 1;
        long month = (gameSecond / SECONDS_PER_MONTH) % 12 + 1;
        long year = gameSecond / SECONDS_PER_YEAR + 1;
        String cal = (calendar == null || calendar.isBlank()) ? DEFAULT_CALENDAR : calendar;
        if (withSecond) {
            return String.format("%s %04d年%02d月%02d日 %02d时%02d分%02d秒", cal, year, month, dayOfMonth, hour, min, sec);
        }
        return String.format("%s %04d年%02d月%02d日 %02d时%02d分", cal, year, month, dayOfMonth, hour, min);
    }

    /**
     * 项目历法名：世界观名称 + 「历」（如「暖绒市」→「暖绒市历」）；无世界观名用「世界历」。
     *
     * @param projectId 项目 ID
     * @return 历法名
     */
    public String calendarNameOf(Long projectId) {
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (ws != null && ws.getName() != null && !ws.getName().isBlank()) {
            return ws.getName().trim() + "历";
        }
        return DEFAULT_CALENDAR;
    }

    /**
     * 是否跨越游戏日边界（用于触发「每游戏日」级推进动作：角色行动/世界事件）。
     *
     * @param fromHour 起始游戏小时
     * @param toHour   结束游戏小时
     * @return true 表示跨越了至少一个游戏日（from/24 != to/24）
     */
    static boolean crossesDay(long fromHour, long toHour) {
        return Math.floorDiv(fromHour, 24) != Math.floorDiv(toHour, 24);
    }

    /**
     * 游戏时刻格式化：第 X 日 + 时段描述（如「第 3 日·午后」）。
     *
     * @param gameHour 游戏小时数
     * @return 格式化文本
     */
    static String formatGameTime(long gameHour) {
        long day = Math.floorDiv(gameHour, 24) + 1;
        int hourOfDay = (int) Math.floorMod(gameHour, 24);
        return "第 " + day + " 日·" + periodText(hourOfDay);
    }

    /**
     * 时段描述（游戏小时 → 中文时段）。
     *
     * @param hourOfDay 当日小时（0-23）
     * @return 时段文本
     */
    static String periodText(int hourOfDay) {
        int h = Math.floorMod(hourOfDay, 24);
        if (h >= 0 && h <= 5) return "深夜";
        if (h >= 6 && h <= 7) return "清晨";
        if (h >= 8 && h <= 11) return "上午";
        if (h >= 12 && h <= 13) return "正午";
        if (h >= 14 && h <= 17) return "午后";
        if (h >= 18 && h <= 19) return "傍晚";
        if (h >= 20 && h <= 22) return "夜晚";
        return "深夜";
    }

    // ==================== 实例方法 ====================

    /**
     * 获取项目世界时钟状态（首次访问懒创建时钟行，锚点默认=项目创建时刻）。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户 ID（项目归属校验）
     * @return 时钟 VO（含实时换算的游戏时刻）
     */
    @Transactional
    public WorldClockVO getClock(Long projectId, Long userId) {
        requireProject(projectId, userId);
        ActorWorldClock clock = clockRepository.findByProjectId(projectId)
                .orElseGet(() -> createClock(projectId));
        return toVO(clock);
    }

    /**
     * 更新世界时钟（速率/暂停/锚点/起始游戏时刻；传入字段为空则保持原值）。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户 ID
     * @param dto       更新入参
     * @return 更新后的时钟 VO
     */
    @Transactional
    public WorldClockVO updateClock(Long projectId, Long userId, WorldClockDTO dto) {
        requireProject(projectId, userId);
        ActorWorldClock clock = clockRepository.findByProjectId(projectId)
                .orElseGet(() -> createClock(projectId));
        if (dto.rate() != null) {
            if (dto.rate() <= 0 || dto.rate() > 8760) {
                throw new BizException(400, "速率须在 1~8760（1 真实小时推进的游戏小时数）之间");
            }
            clock.setRate(dto.rate());
        }
        if (dto.paused() != null) {
            boolean nowPaused = Boolean.TRUE.equals(dto.paused());
            boolean wasPaused = clock.getPaused() != null && clock.getPaused() == 1;
            if (nowPaused && !wasPaused) {
                // 暂停：记录暂停时刻，游戏时间冻结在此刻
                clock.setPaused(1);
                clock.setPausedAt(LocalDateTime.now());
            } else if (!nowPaused && wasPaused) {
                // 恢复：把锚点前移「暂停时长」，使游戏时间从冻结处继续（不跳变）
                LocalDateTime pausedAt = clock.getPausedAt();
                if (pausedAt != null && clock.getWorldStartAt() != null) {
                    long pauseSeconds = Duration.between(pausedAt, LocalDateTime.now()).getSeconds();
                    if (pauseSeconds > 0) {
                        clock.setWorldStartAt(clock.getWorldStartAt().plusSeconds(pauseSeconds));
                    }
                }
                clock.setPaused(0);
                clock.setPausedAt(null);
            }
        }
        if (dto.worldStartAt() != null) {
            clock.setWorldStartAt(dto.worldStartAt());
        }
        if (dto.worldStartGameHour() != null) {
            clock.setWorldStartGameHour(dto.worldStartGameHour());
        }
        clockRepository.save(clock);
        log.info("[世界时钟] 更新：项目={} 速率={} 暂停={}", projectId, clock.getRate(), clock.getPaused());
        return toVO(clock);
    }

    /**
     * 内部工具：获取项目时钟行（不存在则按项目创建时刻懒创建），推进任务与 API 共用。
     *
     * @param projectId 项目 ID
     * @return 时钟行
     */
    @Transactional
    public ActorWorldClock requireClock(Long projectId) {
        return clockRepository.findByProjectId(projectId).orElseGet(() -> createClock(projectId));
    }

    /**
     * 懒创建时钟行：锚点=项目创建时刻，速率=配置默认，起始游戏时刻=0。
     *
     * @param projectId 项目 ID
     * @return 新建时钟行
     */
    private ActorWorldClock createClock(Long projectId) {
        ActorProject project = projectRepository.findById(projectId).orElse(null);
        ActorWorldClock clock = new ActorWorldClock();
        clock.setProjectId(projectId);
        clock.setRate(DEFAULT_RATE);
        clock.setWorldStartAt(project == null ? LocalDateTime.now() : project.getCreatedAt());
        clock.setWorldStartGameHour(0L);
        clock.setLastGameHour(0L);
        clock.setPaused(0);
        return clockRepository.save(clock);
    }

    /**
     * 实体转 VO（含实时换算）。
     * <p>暂停时游戏时间冻结在 pausedAt 时刻（不再随真实时间流动）。</p>
     *
     * @param clock 时钟实体
     * @return VO 对象
     */
    public WorldClockVO toVO(ActorWorldClock clock) {
        WorldClockVO vo = new WorldClockVO();
        vo.setProjectId(clock.getProjectId());
        vo.setRate(clock.getRate());
        vo.setWorldStartAt(clock.getWorldStartAt());
        vo.setWorldStartGameHour(clock.getWorldStartGameHour());
        vo.setPaused(clock.getPaused());
        vo.setPausedAt(clock.getPausedAt());
        vo.setLastSimTime(clock.getLastSimTime());
        vo.setLastGameHour(clock.getLastGameHour());
        vo.setLastSummary(clock.getLastSummary());
        long startGameHour = clock.getWorldStartGameHour() == null ? 0L : clock.getWorldStartGameHour();
        int rate = clock.getRate() == null ? DEFAULT_RATE : clock.getRate();
        // 暂停：时间冻结在暂停时刻；未暂停：随真实时间流动
        LocalDateTime refNow = LocalDateTime.now();
        if (clock.getPaused() != null && clock.getPaused() == 1 && clock.getPausedAt() != null) {
            refNow = clock.getPausedAt();
        }
        long gameSecond = gameSecondOf(refNow, clock.getWorldStartAt(), startGameHour, rate);
        long gameHour = gameSecond / 3600L;
        vo.setGameHour(gameHour);
        vo.setGameSecond(gameSecond);
        // 兼容字段保留（第几天/当日小时/时段）
        vo.setDay(Math.floorDiv(gameHour, 24) + 1);
        vo.setHourOfDay((int) Math.floorMod(gameHour, 24));
        vo.setPeriodText(periodText((int) Math.floorMod(gameHour, 24)));
        // 世界历完整格式（历法名 = 世界观名+历，缺省世界历）
        String calendar = calendarNameOf(clock.getProjectId());
        vo.setCalendarName(calendar);
        vo.setGameTimeText(formatWorldTime(gameSecond, calendar));
        return vo;
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户 ID
     */
    private void requireProject(Long projectId, Long userId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 世界模拟总开关（供推进任务查询）。
     *
     * @return true 表示开启
     */
    public boolean isSimEnabled() {
        return simEnabled;
    }
}
