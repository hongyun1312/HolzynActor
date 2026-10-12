/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

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
 * 世界时钟服务。
 * <p>职责：承载「真实时间 → 游戏时间」的换算模型——每项目锚定世界起点
 * （worldStartAt 真实时刻，默认=项目创建时刻）+ 起始游戏时刻（worldStartGameHour，默认 0），
 * 按速率 rate（每真实小时推进的游戏小时数，默认 24=1真实小时1游戏日）把真实流逝映射为游戏时刻；
 * 提供时钟状态获取（懒创建时钟行）、速率/暂停/锚点更新与格式化；纯换算逻辑抽静态方法可单测。
 * <p>① 暂停冻结——暂停时记录 pausedAt，游戏时间冻结在暂停时刻，恢复时锚点前移暂停
 * 时长（时间不跳变）；② 世界历完整格式——秒级换算 gameSecondOf + formatWorldTime
 * （「XX历 YYYY年MM月DD日 HH时MM分SS秒」，历法名取世界观名+「历」，缺省「世界历」）。</p>
 * <p><b>时间口径统一</b>：世界时间 = <b>引擎推进位置</b>
 * （{@code actor_world_clock.last_game_second}，由 WorldEngine 按真实流逝 × rate 逐步推进并落库）。
 * 真实锚点换算（worldStartAt + worldStartGameHour + rate）<b>降级为诊断口径</b>
 * （VO 的 flowingGameSecond/flowingGameHour），唯一用途是观测「真实流逝推算值」与权威位置之间的
 * 落后量；锚点仍会在世界时间校准/重新打开世界时重基到权威位置，使两者在重基瞬间严格相等。
 * 历史缺陷：锚点换算曾是并行的第二时间源（引擎停止期间仍按速率虚跑），恢复基线后与引擎位置
 * 相差约 120 游戏日——AI 提示词时间感、深夜晚判定、时间线展示三处因此互不一致（详见
 * {@link #alignWorldTimeSeconds} 与 {@link #gameSecondAt} 的说明）。</p>
 * 推进执行（角色/人群/事件）由 {@link WorldSimulationJob} 承担。</p>
 * <p>开关：{@code HOLOZYN_ACTOR_WORLD_SIM_ENABLED}（默认 true）由推进任务消费。</p>
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
     * 世界历时间点 → 游戏总秒数（世界初始化第 5 步：AI 推断当前世界历时间点后落锚点）。
     * <p>规则与 formatWorldTime 同源：1 日 = 24 时，1 月 = 30 日，1 年 = 12 月 = 360 日；
     * 换算公式：gameSecond = (year-1)×年秒数 + (month-1)×月秒数 + (day-1)×日秒数 + hour×3600 + minute×60 + second。
     * 各分量越界时自动夹取（year 最小 1，month 1-12，day 1-30，hour 0-23，minute/second 0-59）。</p>
     *
     * @param year 年（>=1）
     *
     * @param month 月（1-12）
     *
     * @param day 日（1-30）
     *
     * @param hour 时（0-23）
     *
     * @param minute 分（0-59）
     *
     * @param second 秒（0-59）
     *
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

    /**
     * 沙盘状态服务（S2 clock 广播：时钟更新[速率/暂停]后推送；字段注入避免改构造器；
     * @Lazy 破 Bean 环：worldEventBus→本服务→沙盘状态→沙盘 Hub→worldEventBus，
     * 运行时（更新时钟时）经代理取真实 Bean）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /** 世界模拟总开关（默认 true；false 时推进任务跳过） */
    @Value("${holzyn.actor.world.sim-enabled:true}")
    private boolean simEnabled;

    /**
     * 引擎感知 tick 粒度（游戏分钟，默认 5：与 WorldEngine.tick-game-minutes 同源——
     * 时钟显示口径用：engineTickGameSeconds = tickGameMinutes×60 作前端插值钳制值）
     */
    @Value("${holzyn.actor.world.tick-game-minutes:5}")
    private int tickGameMinutes = 5;

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 游戏时刻换算：真实时间 → 自锚点起的游戏小时数。
     * <p>公式：gameHours = (now - startAt) 的真实小时数 × rate + startGameHour。
     * 锚点为空时按「以当前时刻为锚点」处理（返回起始游戏时刻）。
     * now 早于锚点时为负值（世界尚未开始，由调用方处理）。</p>
     *
     * @param now 当前真实时刻
     *
     * @param startAt 世界起点真实时刻（可空=以 now 为锚点）
     *
     * @param startGameHour 锚点对应的游戏起始小时数
     *
     * @param rate 速率（每真实小时推进的游戏小时数）
     *
     * @return 当前游戏小时数（自纪元起）
     * <p>起放宽为 public：角色扮演组件（agent 域）演化本拍上下文需跨包读取当前游戏时刻
     * （统一时间口径，修 /L-07）。</p>
     */
    public static long gameHourOf(LocalDateTime now, LocalDateTime startAt, long startGameHour, int rate) {
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
     *
     * @param cap 封顶值（<=0 视为不封顶）
     *
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
     * @param now 当前真实时刻
     *
     * @param startAt 世界起点真实时刻（可空=以 now 为锚点）
     *
     * @param startGameHour 锚点对应的游戏起始小时数
     *
     * @param rate 速率（每真实小时推进的游戏小时数）
     *
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
     *
     * @param calendar 历法名（如「暖绒市历」；空用「世界历」）
     *
     * @return 世界历完整时间文本
     */
    public static String formatWorldTime(long gameSecond, String calendar) {
        return formatWorldTime0(gameSecond, calendar, true);
    }

    /**
     * 世界历时间文本（<b>到分</b>）：「XX历 YYYY年MM月DD日 HH时MM分」（时间线节点展示用）。
     *
     * @param gameSecond 自纪元起的游戏总秒数
     *
     * @param calendar 历法名（空用「世界历」）
     *
     * @return 世界历时间文本（到分）
     */
    public static String formatWorldTimeMinute(long gameSecond, String calendar) {
        return formatWorldTime0(gameSecond, calendar, false);
    }

    /**
     * 世界历格式化核心（秒/分级别）。
     *
     * @param gameSecond 游戏总秒数（负数按 0 处理）
     *
     * @param calendar 历法名
     *
     * @param withSecond 是否含秒（false=到分）
     *
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
     *
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
     * 游戏时刻 → 当日小时内分钟。
     *
     * @param gameSecond 游戏总秒数（负数按 0 处理）
     *
     * @return 当日小时内分钟（0-59）
     */
    public static int minuteOfHour(long gameSecond) {
        return (int) Math.floorMod(gameSecond / 60L, 60L);
    }

    /**
     * 游戏时刻 → 当日分钟。
     *
     * @param gameSecond 游戏总秒数（负数按 0 处理）
     *
     * @return 当日分钟（0-1439）
     */
    public static int dayMinuteOf(long gameSecond) {
        return (int) Math.floorMod(gameSecond / 60L, 24L * 60L);
    }

    /**
     * 是否跨越游戏日边界（用于触发「每游戏日」级推进动作：角色行动/世界事件）。
     *
     * @param fromHour 起始游戏小时
     *
     * @param toHour 结束游戏小时
     *
     * @return true 表示跨越了至少一个游戏日（from/24 != to/24）
     */
    public static boolean crossesDay(long fromHour, long toHour) {
        return Math.floorDiv(fromHour, 24) != Math.floorDiv(toHour, 24);
    }

    /**
     * 游戏时刻格式化：第 X 日 + 时段描述（如「第 3 日·午后」）。
     *
     * @param gameHour 游戏小时数
     *
     * @return 格式化文本
     */
    public static String formatGameTime(long gameHour) {
        long day = Math.floorDiv(gameHour, 24) + 1;
        int hourOfDay = (int) Math.floorMod(gameHour, 24);
        return "第 " + day + " 日·" + periodText(hourOfDay);
    }

    /**
     * 时段描述（游戏小时 → 中文时段）。
     *
     * @param hourOfDay 当日小时（0-23）
     *
     * @return 时段文本
     */
    public static String periodText(int hourOfDay) {
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
     *
     * @param userId 当前用户 ID（项目归属校验）
     *
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
     * 引擎回补基准锚点重置为当前真实时刻。
     * <p><b>语义</b>：
     * 世界「关闭」（未激活/关闭沙盘/关闭应用）期间推进停止、last_game_second 冻结在关闭前进度；
     * 重新「打开」（激活闸门打开）时调用本方法把引擎回补基准 last_engine_run_at 重置为当前时刻——
     * 此后 WorldEngine 回补量 = 自打开起的真实流逝 × rate，<b>关闭期间落下的进度一笔勾销</b>，
     * 世界从冻结进度接续推进而非追赶。</p>
     * <p>与 {@link #updateClock} 内「暂停恢复 / world_running 关→开」的防跳变重置同语义
     * （那边由用户显式操作触发，这里由实时决策激活闸门触发——补齐激活路径的缺口：
     * 此前打开沙盘/手动常开不重置锚点，关闭后再打开会按「关闭前基准→现在」补推整段离线时间）。</p>
     *
     * @param projectId 项目 ID（null 忽略）
     */
    @Transactional
    public void anchorEngineToNow(Long projectId) {
        if (projectId == null) {
            return;
        }
        ActorWorldClock clock = requireClock(projectId);
        long engine = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
        clock.setLastEngineRunAt(LocalDateTime.now());
        // 时间口径统一：真实锚点一并重基到权威位置——锚点换算降级为诊断口径后仍须与
        // 权威位置在「重基瞬间」严格相等，否则诊断字段（clockLagGameSeconds）会混入历史偏离量，
        // 无法用于观测「本次运行内世界是否落后于真实流逝」。
        // ：重基结果与回补基准走**窄列更新**（updateConfig：
        // 只写配置列 + 武装基准 last_engine_run_at），不整实体回写——引擎每 tick 也在写本行，
        // 整实体覆盖会让双方各自把对方的旧快照写回去（见 ActorWorldClockRepository 窄列更新注释）。
        // 计算先落局部变量（窄更新前不修改受管实体）；updateConfig 后实例已被 clearAutomatically 脱管。
        LocalDateTime now = LocalDateTime.now();
        int rate = clock.getRate() == null || clock.getRate() <= 0 ? DEFAULT_RATE : clock.getRate();
        long remaining = engine % 3600L;
        clockRepository.updateConfig(projectId, clock.getRate(), clock.getPaused(), clock.getPausedAt(),
                clock.getWorldRunning(), now.minusSeconds(remaining / rate), engine / 3600L, now);
        log.info("[世界时钟] 引擎回补基准重置为当前（世界重新打开，不补离线进度）: project={} 世界时间={}",
                projectId, formatWorldTimeMinute(engine, calendarNameOf(projectId)));
    }

    /**
     * <b>世界时间校准</b>：把世界的「当前时间」设定为指定游戏秒（时间口径统一新增）。
     * <p>语义：把权威世界时间（引擎推进位置 last_game_second）设为 gameSecond，并把真实锚点
     * （worldStartAt + worldStartGameHour）重基到该时刻——使流动换算（诊断口径）在重基瞬间与权威
     * 位置严格相等；回补基准 last_engine_run_at 同步重置为当前（不补校准前的真实流逝）。</p>
     * <p>用途：① 世界初始化（AI 依据世界观推断出的世界历时间点即世界起点，此前只设锚点、引擎位置
     * 仍为 0 → 世界从 0001年01月01日 00时00分 起跑而时钟显示推断时间，是「双时间源」缺陷的起点）；
     * ② 设置页/接口的世界时间校准（锚点字段变更时按变更值校准，见 {@link #updateClock}）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 目标世界时间（游戏秒，自纪元起；负数按 0）
     *
     * @return 校准后的时钟 VO
     */
    @Transactional
    public WorldClockVO alignWorldTimeSeconds(Long projectId, long gameSecond) {
        ActorWorldClock clock = requireClock(projectId);
        long target = Math.max(0L, gameSecond);
        long before = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
        // ：校准走**窄列更新**——引擎位置列用
        // updateEnginePosition、配置列（含锚点重基/回补基准）用 updateConfig，不整实体回写。
        // 计算先落局部变量（窄更新前不修改受管实体）；落库后实例已被 clearAutomatically 脱管，
        // 回填仅供出参 VO/广播口径。
        LocalDateTime now = LocalDateTime.now();
        clockRepository.updateEnginePosition(projectId, target, target / 3600L, now, clock.getLastSummary());
        int rate = clock.getRate() == null || clock.getRate() <= 0 ? DEFAULT_RATE : clock.getRate();
        long remaining = target % 3600L;
        clockRepository.updateConfig(projectId, clock.getRate(), clock.getPaused(), clock.getPausedAt(),
                clock.getWorldRunning(), now.minusSeconds(remaining / rate), target / 3600L, now);
        clock.setLastGameSecond(target);
        clock.setLastGameHour(target / 3600L);
        clock.setLastEngineRunAt(now);
        clock.setLastSimTime(now);
        clock.setWorldStartGameHour(target / 3600L);
        clock.setWorldStartAt(now.minusSeconds(remaining / rate));
        log.info("[世界时钟] 世界时间校准：project={} {}→{} 游戏秒 = {}（锚点已重基，回补基准重置）",
                projectId, before, target, formatWorldTimeMinute(target, calendarNameOf(projectId)));
        try {
            if (sandboxStateService != null) {
                sandboxStateService.publishClock(projectId, clock.getPaused());
            }
        } catch (Exception e) {
            log.warn("[世界时钟] 沙盘 clock 广播失败（不影响校准）: project={} : {}", projectId, e.getMessage());
        }
        return toVO(clock);
    }

    /**
     * 真实锚点重基到指定世界时刻（内部工具，）：worldStartGameHour = 目标游戏小时；
     * worldStartAt = 当前真实时刻回退「目标时刻的小时内余秒 ÷ rate」——使流动换算在重基瞬间等于
     * 权威位置（小时内余秒按 rate 折算为真实秒，rate≤0 时余秒不折算）。
     * <p>后主要供 {@link #fastForward} 等世界停摆路径使用（停摆时整实体回写无并发竞争）；
     * 运行路径请以局部变量计算 + 窄列更新落库（见 {@link #anchorEngineToNow} 注释）。</p>
     *
     * @param clock 时钟实体（就地修改，调用方负责落库）
     *
     * @param gameSecond 权威世界时间（游戏秒）
     */
    private void rebaseAnchorTo(ActorWorldClock clock, long gameSecond) {
        int rate = clock.getRate() == null || clock.getRate() <= 0 ? DEFAULT_RATE : clock.getRate();
        long remaining = gameSecond % 3600L;
        clock.setWorldStartGameHour(gameSecond / 3600L);
        clock.setWorldStartAt(LocalDateTime.now().minusSeconds(remaining / rate));
    }

    /**
     * 世界时钟（速率/暂停/锚点/起始游戏时刻；传入字段为空则保持原值）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 当前用户 ID
     *
     * @param dto 更新入参
     *
     * @return 更新后的时钟 VO
     */
    @Transactional
    public WorldClockVO updateClock(Long projectId, Long userId, WorldClockDTO dto) {
        requireProject(projectId, userId);
        ActorWorldClock clock = clockRepository.findByProjectId(projectId)
                .orElseGet(() -> createClock(projectId));
        // ⚠️ 关键细节：**窄更新之前不修改受管实体**。
        // 原因：updateConfig 带 flushAutomatically=true，Hibernate 会在执行 JPQL 更新之前做脏检查刷写；
        // 若此前已 setRate/setPaused，实体会被整行写回（含引擎列 last_game_second/last_sim_time 的
        // 事务前快照）→ 等于绕开窄列更新继续制造丢失更新。故合并结果先只算在局部变量，
        // 窄更新落库后再回填到（已被 clearAutomatically 脱管的）实例，仅供出参 VO/广播口径。
        Integer rate = clock.getRate();
        Integer paused = clock.getPaused();
        LocalDateTime pausedAt = clock.getPausedAt();
        Integer worldRunning = clock.getWorldRunning();
        LocalDateTime startAt = clock.getWorldStartAt();
        Long startGameHour = clock.getWorldStartGameHour();
        LocalDateTime base = clock.getLastEngineRunAt();
        // 本次是否重设了引擎回补基准（决定窄更新要不要写 last_engine_run_at 列）
        boolean armed = false;
        if (dto.rate() != null) {
            if (dto.rate() <= 0 || dto.rate() > 8760) {
                throw new BizException(400, "速率须在 1~8760（1 真实小时推进的游戏小时数）之间");
            }
            rate = dto.rate();
        }
        if (dto.paused() != null) {
            boolean nowPaused = Boolean.TRUE.equals(dto.paused());
            boolean wasPaused = paused != null && paused == 1;
            if (nowPaused && !wasPaused) {
                // 暂停：记录暂停时刻，游戏时间冻结在此刻
                paused = 1;
                pausedAt = LocalDateTime.now();
            } else if (!nowPaused && wasPaused) {
                // 恢复：把锚点前移「暂停时长」，使游戏时间从冻结处继续（不跳变）
                if (pausedAt != null && startAt != null) {
                    long pauseSeconds = Duration.between(pausedAt, LocalDateTime.now()).getSeconds();
                    if (pauseSeconds > 0) {
                        startAt = startAt.plusSeconds(pauseSeconds);
                    }
                }
                paused = 0;
                pausedAt = null;
                // ：暂停恢复同时重置引擎回补基准——暂停期间引擎不推进，
                // 若不重置基准，恢复后引擎会按「暂停前基准→现在」补推整段冻结的游戏时间
                // （与「世界持续运行开启时重置回补基准」同语义：从恢复时刻起算，不补冻结期）。
                base = LocalDateTime.now();
                armed = true;
            }
        }
        if (dto.worldRunning() != null) {
            // B3（世界推进引擎）：「世界持续运行」常驻开关（0/1）
            // 开启（含关→开）：重置引擎回补基准为当前真实时刻——开启前积压的历史不再补推，
            // 从开启时刻起算（与「暂停恢复锚点前移」同样的防跳变语义）；关闭则引擎完全不推进该项目。
            boolean running = Integer.valueOf(1).equals(dto.worldRunning());
            boolean wasRunning = Integer.valueOf(1).equals(worldRunning);
            worldRunning = running ? 1 : 0;
            if (running && !wasRunning) {
                // 时间口径统一：锚点同步重基到权威位置（诊断口径在重基瞬间与权威位置相等）；
                // 重基结果并入下方 updateConfig 窄更新（startAt/startGameHour 列 + 武装基准）。
                // 计算先落局部变量——窄更新前不修改受管实体。
                long enginePos = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
                int r = clock.getRate() == null || clock.getRate() <= 0 ? DEFAULT_RATE : clock.getRate();
                startGameHour = enginePos / 3600L;
                startAt = LocalDateTime.now().minusSeconds((enginePos % 3600L) / r);
                base = LocalDateTime.now();
                armed = true;
                log.info("[世界时钟] 开启持续运行：项目={}（回补基准重置为当前，锚点重基）", projectId);
            }
        }
        // 世界时间校准：锚点字段（起始游戏时刻 / 锚点真实时刻）语义 =「此刻的世界时间」，
        // 而权威世界时间 = 引擎推进位置 ⇒ 变更「起始游戏时刻」时按该值校准世界时间（引擎位置随之
        // 前进/回退），否则只改诊断锚点会让设置页字段静默失效（用户改了世界时间却看不出变化）。
        // 回声容忍：设置页保存会把页面加载时读到的原值一并回传，值未变则不校准——否则每保存一次
        // 就会把世界时间拨回页面加载时刻（世界在这期间已推进）。
        Long newStartHour = dto.worldStartGameHour();
        boolean hourChanged = newStartHour != null && !newStartHour.equals(clock.getWorldStartGameHour());
        LocalDateTime calibratedSimAt = null;
        Long calibratedSecond = null;
        if (hourChanged) {
            long target = Math.max(0L, newStartHour) * 3600L;
            // 校准权威世界时间（引擎位置列）： 纪律——走窄列更新（updateEnginePosition），
            // 不整实体回写（引擎每 tick 也在写本行，整实体覆盖会让双方互相覆盖旧快照）。
            calibratedSecond = target;
            calibratedSimAt = LocalDateTime.now();
            clockRepository.updateEnginePosition(projectId, target, target / 3600L, calibratedSimAt,
                    clock.getLastSummary());
            // 锚点重基（时间口径统一）：诊断口径在重基瞬间与权威位置严格相等；
            // 回补基准同步重置为当前（不补校准前的真实流逝）——经 updateConfig 武装基准列落库。
            int r = clock.getRate() == null || clock.getRate() <= 0 ? DEFAULT_RATE : clock.getRate();
            startGameHour = target / 3600L;
            startAt = calibratedSimAt.minusSeconds((target % 3600L) / r);
            base = calibratedSimAt;
            armed = true;
            log.info("[世界时钟] 世界时间校准（起始游戏时刻变更）：项目={} → {} 游戏秒 = {}",
                    projectId, target, formatWorldTimeMinute(target, calendarNameOf(projectId)));
        } else {
            if (dto.worldStartAt() != null && !dto.worldStartAt().equals(clock.getWorldStartAt())) {
                // 只改真实锚点：世界时间权威仍是引擎位置（不变），仅调整「真实时刻 ↔ 世界时间」映射
                startAt = dto.worldStartAt();
                log.info("[世界时钟] 锚点真实时刻更新（世界时间仍以引擎位置为准）：项目={} 锚点={}",
                        projectId, dto.worldStartAt());
            }
        }
        // 长跑缺陷 根治：配置侧改为**窄列更新**（只写配置列），
        // 不再整实体回写——引擎每 tick 也在写这一行（位置/基准/摘要），整实体覆盖会让双方
        // 各自把对方的旧快照写回去，实测 rate 被反复打回旧值、日历投影跑偏。见仓储注释。
        // 只有「本次真的重设了回补基准」（暂停恢复 / 开启持续运行 / 世界时间校准）才写
        // last_engine_run_at，其余情况传 null 保持数据库现值，避免用事务前快照覆盖引擎刚写入的新基准。
        clockRepository.updateConfig(projectId, rate, paused, pausedAt, worldRunning, startAt, startGameHour,
                armed ? base : null);
        // 回填（实例已被 updateConfig 的 clearAutomatically 脱管 → 不会触发任何脏检查写回；
        // 仅供出参 VO / 广播口径）
        clock.setRate(rate);
        clock.setPaused(paused);
        clock.setPausedAt(pausedAt);
        clock.setWorldRunning(worldRunning);
        clock.setWorldStartAt(startAt);
        clock.setWorldStartGameHour(startGameHour);
        if (armed) {
            clock.setLastEngineRunAt(base);
        }
        if (calibratedSecond != null) {
            clock.setLastGameSecond(calibratedSecond);
            clock.setLastGameHour(calibratedSecond / 3600L);
            clock.setLastSimTime(calibratedSimAt);
        }
        log.info("[世界时钟] 更新：项目={} 速率={} 暂停={}", projectId, clock.getRate(), clock.getPaused());
        // S2 沙盘 clock 广播：暂停/速率/开关变化后推送（失败不影响时钟更新）
        try {
            if (sandboxStateService != null) {
                sandboxStateService.publishClock(projectId, clock.getPaused());
            }
        } catch (Exception e) {
            log.warn("[世界时钟] 沙盘 clock 广播失败（不影响更新）: project={} : {}", projectId, e.getMessage());
        }
        return toVO(clock);
    }

    /**
     * 专门测试接口·参数化拨时钟快进。
     * <p>语义：把<b>引擎推进位置</b>（last_game_second / last_game_hour）安全拨动到目标游戏时刻——
     * 与冒烟 H2 文件直连改时钟等价但 API 化（测试专用，仅 test.enabled 时由 TestRunService 调用）；
     * 拨动后重置引擎回补基准（last_engine_run_at=now），世界从新位置接续推进（不补拨动前的
     * 时间差），后续引擎自然跨日/触发补帧——与「快进会跳过中间日」的确定性拨动语义一致。</p>
     * <p><b>安全约束</b>（防并发竞争与时间线污染）：要求世界当前静止（paused=1 或
     * world_running=0）——否则引擎扫描线程可能在拨动事务并发回写时钟位置；目标只允许
     * ≥ 当前引擎位置（只能前进，不可回拨）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param targetGameSecond 绝对目标游戏秒（与相对参数二选一，优先）
     *
     * @param plusGameMinutes 相对推进游戏分钟（可 0/空）
     *
     * @param plusGameHours 相对推进游戏小时（可 0/空）
     *
     * @param plusGameDays 相对推进游戏日（可 0/空）
     *
     * @return 拨动后的时钟 VO（engineGameSecond 即新位置）
     */
    @Transactional
    public WorldClockVO fastForward(Long projectId, Long targetGameSecond, Long plusGameMinutes,
                                    Long plusGameHours, Long plusGameDays) {
        ActorWorldClock clock = requireClock(projectId);
        boolean paused = Integer.valueOf(1).equals(clock.getPaused());
        boolean stopped = !Integer.valueOf(1).equals(clock.getWorldRunning());
        if (!paused && !stopped) {
            throw new BizException(400, "快进前请先暂停世界或关闭持续运行——快进是时钟拨动，须世界静止以避免与引擎推进竞争");
        }
        long current = clock.getLastGameSecond() == null ? 0L : clock.getLastGameSecond();
        long target = -1;
        if (targetGameSecond != null) {
            target = targetGameSecond;
        } else {
            long plus = (plusGameMinutes == null ? 0L : plusGameMinutes) * 60L
                    + (plusGameHours == null ? 0L : plusGameHours) * 3600L
                    + (plusGameDays == null ? 0L : plusGameDays) * 86400L;
            if (plus <= 0) {
                throw new BizException(400, "快进需指定目标（targetGameSecond）或相对推进量（plusGameMinutes/Hours/Days 至少一项 > 0）");
            }
            target = current + plus;
        }
        if (target < current) {
            throw new BizException(400, "快进目标须 ≥ 当前引擎位置（" + current + " 游戏秒），不支持回拨");
        }
        if (target == current) {
            throw new BizException(400, "快进目标与当前引擎位置相同，无需拨动");
        }
        clock.setLastGameSecond(target);
        clock.setLastGameHour(target / 3600L);
        // 重置引擎回补基准：拨动后的世界从新位置按自然流逝接续，不补拨动跨度
        clock.setLastEngineRunAt(LocalDateTime.now());
        clock.setLastSimTime(LocalDateTime.now());
        // 时间口径统一：锚点随权威位置重基（诊断口径与权威位置在拨动后严格相等）
        rebaseAnchorTo(clock, target);
        clockRepository.save(clock);
        log.info("[世界时钟] 测试快进：project={} {}→{}（游戏秒）={} 历法时间",
                projectId, current, target, formatWorldTimeMinute(target, calendarNameOf(projectId)));
        try {
            if (sandboxStateService != null) {
                sandboxStateService.publishClock(projectId, clock.getPaused());
            }
        } catch (Exception e) {
            log.warn("[世界时钟] 沙盘 clock 广播失败（不影响快进）: project={} : {}", projectId, e.getMessage());
        }
        return toVO(clock);
    }

    /**
     * 内部工具：获取项目时钟行（不存在则按项目创建时刻懒创建），推进任务与 API 共用。
     * <p>返回前做 <b>last_game_second 惰性初始化</b>——存量行
     * 该列为 NULL，按 last_game_hour×3600 补值并落库（幂等迁移；新建行 prePersist 已默认 0）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 时钟行
     */
    @Transactional
    public ActorWorldClock requireClock(Long projectId) {
        ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElseGet(() -> createClock(projectId));
        if (clock.getLastGameSecond() == null) {
            long legacy = clock.getLastGameHour() == null ? 0L : clock.getLastGameHour();
            clock.setLastGameSecond(legacy * 3600L);
            clockRepository.save(clock);
            log.info("[世界时钟] last_game_second 惰性初始化：project={} = {}（原 last_game_hour={}）",
                    projectId, clock.getLastGameSecond(), legacy);
        }
        return clock;
    }

    /**
     * 懒创建时钟行：锚点=项目创建时刻，速率=配置默认，起始游戏时刻=0。
     * <p>B3（世界推进引擎）：新建时钟行默认「持续运行」开（worldRunning=1，
     * 新项目默认开，存量已有时钟行保持关）且回补基准=当前真实时刻
     * （首次推进从创建时刻起算，不补推创建前的历史）。</p>
     *
     * @param projectId 项目 ID
     *
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
        clock.setLastGameSecond(0L);
        clock.setPaused(0);
        clock.setWorldRunning(1);
        clock.setLastEngineRunAt(LocalDateTime.now());
        return clockRepository.save(clock);
    }

    /**
     * 实体转 VO（含实时换算）。
     * <p>暂停时游戏时间冻结在 pausedAt 时刻（不再随真实时间流动）。</p>
     *
     * @param clock 时钟实体
     *
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
        vo.setWorldRunning(Integer.valueOf(1).equals(clock.getWorldRunning()) ? 1 : 0);
        vo.setLastEngineRunAt(clock.getLastEngineRunAt());
        vo.setLastSimTime(clock.getLastSimTime());
        vo.setLastGameHour(clock.getLastGameHour());
        // 秒级模拟位置（存量 NULL 按 last_game_hour×3600 派生展示，不落库）
        long engineSecond = clock.getLastGameSecond() == null
                ? (clock.getLastGameHour() == null ? 0L : clock.getLastGameHour() * 3600L)
                : clock.getLastGameSecond();
        vo.setLastGameSecond(engineSecond);
        vo.setLastSummary(clock.getLastSummary());
        long startGameHour = clock.getWorldStartGameHour() == null ? 0L : clock.getWorldStartGameHour();
        int rate = clock.getRate() == null ? DEFAULT_RATE : clock.getRate();
        // 时间口径统一：权威世界时间 = 引擎推进位置（last_game_second）。
        // 此前 gameSecond/gameHour/day/hourOfDay/periodText/gameTimeText 取「真实锚点 × 速率」的
        // 流动换算值——与引擎位置是两个独立时间源：世界停止/重新打开/引擎落后于真实流逝时两者
        // 持续偏离（长跑实测恢复基线后相差约 120 游戏日：AI 提示词时间感与玩家可见时钟
        // 互相矛盾、时间线「现在」与沙盘时钟不同日）。统一后所有对外字段同源，前端插值仍走
        // engineGameSecond/engineRefRealTime/engineTickGameSeconds。
        long gameSecond = engineSecond;
        long gameHour = gameSecond / 3600L;
        vo.setGameHour(gameHour);
        vo.setGameSecond(gameSecond);
        // 前端实时时钟：分钟级便捷字段 + 实时插值锚点（refRealTime 为后端发送时刻的真实毫秒）
        vo.setGameMinute(minuteOfHour(gameSecond));
        vo.setGameDayMinute(dayMinuteOf(gameSecond));
        vo.setRefRealTime(System.currentTimeMillis());
        // 流动换算（诊断口径，不再是权威）：暂停冻结在暂停时刻；未暂停按真实流逝 × 速率推算。
        // 与引擎位置的差值 = 世界落后真实流逝的量（世界未开启/引擎被 AI 拖慢/应用关闭期间累积），
        // 供长跑观测与治理核验（此前这个差值被静默当成第二时间源，是缺陷根源）。
        LocalDateTime refNow = LocalDateTime.now();
        if (clock.getPaused() != null && clock.getPaused() == 1 && clock.getPausedAt() != null) {
            refNow = clock.getPausedAt();
        }
        long flowingSecond = gameSecondOf(refNow, clock.getWorldStartAt(), startGameHour, rate);
        vo.setFlowingGameSecond(flowingSecond);
        vo.setFlowingGameHour(flowingSecond / 3600L);
        vo.setClockLagGameSeconds(flowingSecond - gameSecond);
        // 世界时钟显示口径切换：前端「世界时钟」改为显示<b>引擎实际推进位置</b>
        // （engineGameSecond=lastGameSecond 同源）而非流动时钟换算时间——AI 生成慢/引擎停顿（网络
        // 挂起未修复前）时前端不虚快，显示世界的实际时间；engineRefRealTime 为引擎位置插值锚点，
        // engineTickGameSeconds=单次推进插值上限（=tick-game-minutes×60），前端插值钳制在引擎位置
        // +1 个感知 tick 内
        vo.setEngineGameSecond(engineSecond);
        vo.setEngineRefRealTime(System.currentTimeMillis());
        vo.setEngineTickGameSeconds(Math.max(1, tickGameMinutes) * 60);
        // 兼容字段保留（第几天/当日小时/时段）——与权威位置同源
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
     *
     * @param userId 当前用户 ID
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

    /**
     * 当前项目游戏时刻（小时数，自纪元起）。
     * <p>统一时间口径：决策/行动/事件/时间线一律游戏时间，真实时间只进日志层。</p>
     * <p><b>时间口径统一</b>：本方法改为返回<b>引擎推进位置</b>（== {@link #currentEngineGameHour}），
     * 与 {@link #currentEngineGameSecond} 同源。此前返回「真实锚点 × 速率」的流动换算值——那是与引擎
     * 位置并行的第二时间源，在世界停止/引擎落后期间持续偏离（恢复基线后实测相差约 120 游戏日），
     * 使同一决策链里一半组件看引擎时钟、一半看流动时钟（PlanningService/DecisionService/
     * PerceptionService/AutonomousChatService 等 15 处消费点口径互斥）。统一后全链同源，不再需要
     * 各处 sanitize 钳制（钳制保留为纵深防御）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 当前游戏小时数 = 引擎推进位置（时钟读取失败返回 null）
     */
    public Long currentGameHour(Long projectId) {
        return currentEngineGameHour(projectId);
    }

    /**
     * 当前项目<b>引擎推进位置</b>。
     * <p>秒级位置 last_game_second 为权威，本方法优先由其派生（floorDiv 3600），
     * 兼容列 last_game_hour 仅在秒级列为空时兜底。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 引擎最后推进到的游戏小时数（读取失败返回 null）
     */
    public Long currentEngineGameHour(Long projectId) {
        try {
            ActorWorldClock clock = requireClock(projectId);
            if (clock.getLastGameSecond() != null) {
                return clock.getLastGameSecond() / 3600L;
            }
            return clock.getLastGameHour();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前项目<b>引擎推进位置</b>。
     * <p>与 {@link #currentEngineGameHour} 同源（均取引擎离散模拟位置而非流动时钟），本方法返回
     * <b>last_game_second</b>（秒级）——5 游戏分钟步进后的细粒度位置；存量行惰性初始化
     * = last_game_hour×3600（requireClock 内幂等迁移）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 引擎最后推进到的游戏秒数（读取失败返回 null）
     */
    public Long currentEngineGameSecond(Long projectId) {
        try {
            ActorWorldClock clock = requireClock(projectId);
            return clock.getLastGameSecond();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 真实时刻 → <b>世界时间（游戏秒）</b>的统一映射（时间口径统一后的唯一换算口径）。
     * <p>口径：以「此刻真实时刻 ↔ 此刻权威世界时间（引擎推进位置）」为基准点，按 rate 反推过去时刻：
     * {@code gameSecond(at) = engineNow − (now − at) × rate}（负数按 0 收敛，未来时刻返回当前位置）。</p>
     * <p><b>为什么不再用锚点换算</b>：锚点换算是「真实流逝 × 速率」的纯推算，与世界是否真的在运行无关——
     * 世界停止、应用关闭、引擎被 AI 拖慢期间它继续虚跑，与引擎位置无限偏离（长跑实证：
     * 恢复 5 天前的基线后相差约 120 游戏日，时间线「现在」与沙盘世界时钟不同日）。以引擎位置为基准点
     * 反推可保证 {@code at = now} 时严格等于世界时钟显示值，历史时刻的偏差只反映「当时至今世界的落后量」。
     * 可反推窗口 = 引擎位置 ÷ rate（如引擎已推进 120 游戏日、rate=24 → 约 5 真实日），更早的时刻收敛到
     * 纪元 0（无法得知，按最保守处理）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param at 真实时刻（可空）
     *
     * @return 世界时间（游戏秒）；无时钟行/时刻为空返回 null
     */
    public Long gameSecondAt(Long projectId, LocalDateTime at) {
        if (projectId == null || at == null) {
            return null;
        }
        ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
        if (clock == null) {
            return null;
        }
        long engine = clock.getLastGameSecond() == null
                ? (clock.getLastGameHour() == null ? 0L : clock.getLastGameHour() * 3600L)
                : clock.getLastGameSecond();
        long elapsed = Duration.between(at, LocalDateTime.now()).getSeconds();
        if (elapsed <= 0) {
            return engine;
        }
        int rate = clock.getRate() == null ? DEFAULT_RATE : Math.max(0, clock.getRate());
        return Math.max(0L, engine - elapsed * rate);
    }

    /**
     * 指定真实时刻 → 游戏小时数（R3 staleness 用：把记忆的 last_access_at/created_at 换算为游戏时刻，
     * 与当前游戏时刻比较游戏日差——统一时间口径，倍速推进时 staleness 语义一致）。
     * <p>换算基准改为引擎推进位置（见 {@link #gameSecondAt}）。staleness 只比较两端差值，
     * 与基准点无关（同一基准点下差值 = 真实流逝 × 速率），故语义不变；基准点统一后「现在」端与
     * 世界时钟严格一致。</p>
     * <p>只读换算（不懒创建时钟行）：无时钟行返回 null（staleness 判定缺失时视为未过时）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param at 真实时刻（可空）
     *
     * @return 该时刻对应的游戏小时数（不可换算返回 null）
     */
    public Long gameHourAt(Long projectId, LocalDateTime at) {
        Long second = gameSecondAt(projectId, at);
        return second == null ? null : second / 3600L;
    }

    /**
     * 项目世界时间速率（游戏秒/真实秒）。
     * <p>只读查询（不懒创建时钟行）：无时钟行返回 null。供「真实流逝 × 速率」换算
     * （知识层生命周期衰减等场景——换算不受「早于世界起点 clamp」影响）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 速率（&gt;0）；无时钟行/异常返回 null
     */
    public Integer rateOf(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
            if (clock == null) {
                return null;
            }
            Integer rate = clock.getRate();
            return rate == null || rate <= 0 ? DEFAULT_RATE : rate;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 游戏时刻 → 项目世界时间文本（到分，B1 世界事件总线 SSE/查询展示用）。
     * <p>秒级展示由 game_hour×3600 派生（R2 口径：不另设秒级列）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameHour 游戏小时数（可空）
     *
     * @return 世界时间文本（如「世界历 0003年05月12日 14时00分」）；不可换算返回 null
     */
    public String gameTimeTextOf(Long projectId, Long gameHour) {
        if (gameHour == null) {
            return null;
        }
        return WorldClockService.formatWorldTimeMinute(gameHour * 3600L, calendarNameOf(projectId));
    }
}
