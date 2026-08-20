package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.conversation.entity.ActorConversation;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.conversation.repository.ActorConversationRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.action.service.ActionEngine;
import com.holzyn.actor.domain.conversation.service.WorldEventService;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 世界持续模拟任务（P4-2 核心，A-C7 P2「世界持续模拟」）。
 * <p>职责：按周期扫描活跃项目（最近有对话的项目），计算自上次推进以来流逝的游戏小时数
 * （封顶单次补算，防长时间离线后雪崩），按游戏时刻逐步推进三类内容——
 * ① 特殊型角色行动：跨游戏日边界对高重要度（≥3）角色走 ActionEngine scheduled 触发
 *    （AI 决策 + 程序化兜底），其余角色程序化状态更新（零 AI 成本）；
 * ② 普通型 NPC：按游戏小时驱动 OrdinaryNpcService 程序化状态机（复用现有程序化路径）；
 * ③ 世界事件：每跨一个游戏日生成 1 条世界事件并注入最近活跃会话（触发在场角色回应）。
 * AI 成本控制：每 tick AI 调用上限（默认 5），超限走程序化；AI 不可用不阻断推进。
 * 多实例防重：单实例运行约定 + 内存防重入标志（与现有 Job 一致）。</p>
 * <p>开关：{@code HOLOZYN_ACTOR_WORLD_SIM_ENABLED}（默认 true）。</p>
 * <p>所属模块：service/world（世界子域-世界模拟）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldSimulationJob {

    /** 推进结果记录（模拟单步产出统计与摘要） */
    private record SimResult(int actions, int crowds, int events, String summary) {
    }

    private final ActorWorldClockRepository clockRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorConversationRepository conversationRepository;
    private final ActorCharacterRepository characterRepository;
    private final WorldClockService worldClockService;
    private final OrdinaryNpcService ordinaryNpcService;
    private final WorldEventService worldEventService;
    private final ActionEngine actionEngine;

    /** 世界模拟总开关（默认 true） */
    @Value("${holzyn.actor.world.sim-enabled:true}")
    private boolean simEnabled;

    /** 单次补算封顶（游戏小时，默认 24，防离线后雪崩） */
    @Value("${holzyn.actor.world.max-catch-up-hours:24}")
    private int maxCatchUpHours;

    /** 每 tick AI 调用上限（角色行动 + 世界事件生成，默认 5） */
    @Value("${holzyn.actor.world.max-ai-calls:5}")
    private int maxAiCalls;

    /** 活跃项目窗口（真实分钟，默认 30：仅推进最近有对话的项目） */
    @Value("${holzyn.actor.world.active-window-minutes:30}")
    private int activeWindowMinutes;

    /** 推进防重入标志（单实例运行约定 + 内存互斥） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时扫描并推进活跃项目（默认每 3 真实分钟，可配 scan-interval-ms）。
     */
    @Scheduled(fixedDelayString = "${holzyn.actor.world.scan-interval-ms:180000}")
    public void scanAndSimulate() {
        if (!simEnabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime activeSince = LocalDateTime.now().minusMinutes(activeWindowMinutes);
            List<Long> activeProjectIds = conversationRepository.findDistinctProjectIdByLastMessageAtAfter(activeSince);
            log.info("[世界模拟] 扫描：活跃项目 {} 个", activeProjectIds.size());
            for (Long projectId : activeProjectIds) {
                try {
                    advanceProject(projectId);
                } catch (Exception e) {
                    // 单项目推进失败不阻断其他项目
                    log.warn("[世界模拟] 项目 {} 推进失败: {}", projectId, e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 推进单个项目一轮（定时扫描与手动 advance 共用）。
     * <p>计算自上次推进以来流逝的游戏小时（封顶），按步推进角色/人群/事件并更新时钟位置。</p>
     *
     * @param projectId 项目 ID
     * @return 推进结果 Map（paused/advancedHours/gameHour/actions/crowds/events/summary）
     */
    public Map<String, Object> advanceProject(Long projectId) {
        ActorProject project = projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
        Long userId = project.getUserId();
        ActorWorldClock clock = worldClockService.requireClock(projectId);

        // 暂停：不推进
        if (clock.getPaused() != null && clock.getPaused() == 1) {
            return Map.of("projectId", projectId, "paused", true, "advancedHours", 0);
        }

        // 计算自上次推进以来流逝的游戏小时（封顶单次补算）
        long fromHour = clock.getLastGameHour() == null ? 0L : clock.getLastGameHour();
        long nowHour = WorldClockService.gameHourOf(LocalDateTime.now(), clock.getWorldStartAt(),
                clock.getWorldStartGameHour() == null ? 0L : clock.getWorldStartGameHour(),
                clock.getRate() == null ? 24 : clock.getRate());
        long elapsed = nowHour - fromHour;
        long step = WorldClockService.capElapsed(elapsed, maxCatchUpHours);
        if (step <= 0) {
            return Map.of("projectId", projectId, "advancedHours", 0, "gameHour", fromHour);
        }

        // 逐步推进三类内容
        long toHour = fromHour + step;
        SimResult result = simulate(projectId, userId, fromHour, toHour);

        // 更新时钟推进位置与摘要
        clock.setLastSimTime(LocalDateTime.now());
        clock.setLastGameHour(toHour);
        clock.setLastSummary(result.summary());
        clockRepository.save(clock);

        log.info("[世界模拟] 项目={} 推进 {} 游戏小时（{}→{}） 角色={} 人群={} 事件={} 摘要={}",
                projectId, step, fromHour, toHour, result.actions(), result.crowds(), result.events(), result.summary());
        return Map.of("projectId", projectId, "advancedHours", step, "gameHour", toHour,
                "actions", result.actions(), "crowds", result.crowds(), "events", result.events(),
                "summary", result.summary());
    }

    /**
     * 按游戏步长推进角色行动 / 人群 / 世界事件（含 AI 成本控制与程序化兜底）。
     *
     * @param projectId 项目 ID
     * @param userId    项目归属用户 ID（AI 调用凭据归属）
     * @param fromHour  起始游戏小时
     * @param toHour    结束游戏小时
     * @return 推进统计与摘要
     */
    private SimResult simulate(Long projectId, Long userId, long fromHour, long toHour) {
        int aiCalls = 0;
        int actions = 0, crowds = 0, events = 0;
        int gameHourOfDay = (int) Math.floorMod(toHour, 24);
        // 是否跨越游戏日边界（触发「每游戏日」级推进动作）
        boolean dayCrossed = WorldClockService.crossesDay(fromHour, toHour);

        // ① 角色行动：跨游戏日边界对高重要度角色走 AI（超上限程序化）；其余角色程序化状态更新
        List<ActorCharacter> characters = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorCharacter ch : characters) {
            boolean high = isHighImportance(ch);
            if (dayCrossed && high && aiCalls < maxAiCalls) {
                try {
                    String situation = "当前游戏时刻：" + WorldClockService.formatGameTime(toHour)
                            + "；角色当前状态：" + (ch.getCurrentActivity() == null || ch.getCurrentActivity().isBlank()
                            ? "空闲" : ch.getCurrentActivity());
                    actionEngine.trigger(ch.getId(), userId, "scheduled", null, "世界时钟推进至游戏日边界", null, situation);
                    aiCalls++;
                    actions++;
                } catch (Exception e) {
                    // AI 决策失败：程序化兜底（不阻断推进）
                    log.warn("[世界模拟] 角色行动失败，程序化兜底: char={} : {}", ch.getId(), e.getMessage());
                    programmaticUpdate(ch, gameHourOfDay);
                    actions++;
                }
            } else {
                // 非高重要度 / 未到日边界 / AI 超限：程序化状态更新（零 AI 成本）
                programmaticUpdate(ch, gameHourOfDay);
                actions++;
            }
        }

        // ② 普通型 NPC：按游戏小时驱动状态机（程序化，零 AI 成本）
        try {
            ordinaryNpcService.scheduleProgrammaticByGameHour(projectId, gameHourOfDay);
            crowds++;
        } catch (Exception e) {
            log.warn("[世界模拟] 普通型 NPC 推进失败: project={} : {}", projectId, e.getMessage());
        }

        // ③ 世界事件：每跨一个游戏日生成 1 条并注入最近活跃会话（触发在场角色回应）
        if (dayCrossed && aiCalls < maxAiCalls) {
            try {
                ActorConversation target = conversationRepository
                        .findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId)
                        .stream().findFirst().orElse(null);
                if (target != null) {
                    worldEventService.inject(target.getId(), userId, Map.of("generate", true));
                    aiCalls++;
                    events++;
                }
            } catch (Exception e) {
                // 事件生成失败不阻断推进（世界仍有角色/人群在动）
                log.warn("[世界模拟] 世界事件生成失败: project={} : {}", projectId, e.getMessage());
            }
        }

        String summary = "游戏推进至" + WorldClockService.formatGameTime(toHour)
                + "，角色行动 " + actions + " 次，普通居民调度 " + crowds + " 次，世界事件 " + events + " 起。";
        return new SimResult(actions, crowds, events, summary);
    }

    /**
     * 是否高重要度角色（≥3，决定是否投入 AI 行动决策成本）。
     *
     * @param ch 角色实体
     * @return true 表示高重要度
     */
    private boolean isHighImportance(ActorCharacter ch) {
        return ch.getImportance() != null && ch.getImportance() >= 3;
    }

    /**
     * 程序化角色状态更新：按游戏时段更新 current_activity（零 AI 成本）。
     *
     * @param ch         角色实体
     * @param hourOfDay  当日游戏小时（0-23）
     */
    private void programmaticUpdate(ActorCharacter ch, int hourOfDay) {
        ch.setCurrentActivity(programmaticActivity(ch.getLocation(), hourOfDay));
        characterRepository.save(ch);
    }

    /**
     * 生成程序化行动描述（静态可测）：深夜歇息、白天按位置忙于日常。
     * <p>歇息区间对齐人群状态机作息：23 时及 0-5 时（夜晚就寝），其余时段清醒活动。</p>
     *
     * @param location  角色位置（可空）
     * @param hourOfDay 当日小时（0-23）
     * @return 行动描述文本
     */
    static String programmaticActivity(String location, int hourOfDay) {
        int h = Math.floorMod(hourOfDay, 24);
        String period = WorldClockService.periodText(h);
        if (h >= 23 || h <= 5) {
            return period + "在驻地歇息就寝";
        }
        String loc = location == null || location.isBlank() ? "驻地" : location;
        return period + "在" + loc + "忙于日常事务";
    }
}
