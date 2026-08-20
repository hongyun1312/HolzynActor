package com.holzyn.actor.domain.action.service;

import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.action.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.action.entity.ActorActionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 行动定时执行任务（scheduled 触发源，P2 落地，P4 世界时钟驱动基础）。
 * <p>职责：每分钟轮询 actor_action_plan 中 status=planned 且 planned_time 已到期的决策，
 * 调用 ActionEngine 模拟执行。</p>
 * <p>vP5-7.11：世界暂停的项目跳过执行——暂停时世界时间冻结、角色不应继续行动，
 * 避免「暂停期间时间线仍生成角色行动事件」。</p>
 * <p>所属模块：service/action（行动子域-定时调度）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionScheduledJob {

    /** 行动决策仓库 */
    private final ActorActionPlanRepository planRepository;

    /** 行动引擎 */
    private final ActionEngine actionEngine;

    /** 角色仓库（解析计划归属项目，判断世界是否暂停） */
    private final ActorCharacterRepository characterRepository;

    /** 世界时钟仓库（查询项目时钟暂停状态） */
    private final ActorWorldClockRepository clockRepository;

    /**
     * 定时轮询到期计划并执行（每 60 秒）。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void runDuePlans() {
        try {
            List<ActorActionPlan> due = planRepository.findByStatusAndPlannedTimeLessThanEqual("planned", LocalDateTime.now());
            for (ActorActionPlan plan : due) {
                // 世界暂停的项目跳过执行（暂停 = 世界时间冻结，角色不应继续行动）
                if (isWorldPaused(plan.getCharacterId())) {
                    log.info("行动定时执行跳过（世界已暂停）：plan={} char={}", plan.getId(), plan.getCharacterId());
                    continue;
                }
                log.info("定时执行行动决策: plan={} char={}", plan.getId(), plan.getCharacterId());
                actionEngine.execute(plan);
            }
        } catch (Exception e) {
            log.warn("行动定时任务执行失败: {}", e.getMessage());
        }
    }

    /**
     * 判断角色所属项目世界是否暂停（无角色/无时钟视为未暂停）。
     *
     * @param characterId 角色 ID
     * @return true 表示世界已暂停，应跳过执行
     */
    private boolean isWorldPaused(Long characterId) {
        if (characterId == null) {
            return false;
        }
        Long projectId = characterRepository.findById(characterId)
                .map(ActorCharacter::getProjectId).orElse(null);
        if (projectId == null) {
            return false;
        }
        return clockRepository.findByProjectId(projectId)
                .map(c -> Integer.valueOf(1).equals(c.getPaused()))
                .orElse(false);
    }
}
