package com.holzyn.actor.domain.crowd.service;

import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 普通型 NPC 定时推进任务（普通型人群重构后）。
 * <p>职责：定时推进「开启定时调度」项目（默认每 5 分钟一次）下<b>全部</b>普通型 NPC，
 * 走程序化状态机路径（零 AI 成本、无需登录用户，适合无人值守）；两级 AI 调度由页面手动触发
 * （见 OrdinaryNpcService.scheduleWithAi）。</p>
 * <p>幂等说明：每次推进更新 last_schedule_at；单实例运行，若将来多实例部署需加分布式锁。</p>
 * <p>所属模块：service/crowd（普通型人群子域-定时调度）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrowdScheduledJob {

    /** 定时推进周期（毫秒）：默认 5 分钟，可调小便于观察 */
    private static final long FIXED_DELAY_MS = 300_000L;

    private final ActorCrowdRuntimeRepository runtimeRepository;
    private final OrdinaryNpcService ordinaryNpcService;

    /**
     * 定时推进启用项目的全部普通型 NPC（程序化状态机，每 5 分钟）。
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void advanceEnabledProjects() {
        try {
            List<ActorCrowdRuntime> runtimes = runtimeRepository.findByEnabledOrderByIdAsc(1);
            if (runtimes.isEmpty()) {
                return;
            }
            log.info("[人群定时推进] 开始：启用项目 {} 个", runtimes.size());
            for (ActorCrowdRuntime rt : runtimes) {
                try {
                    ordinaryNpcService.scheduleProgrammatic(rt.getProjectId());
                } catch (Exception e) {
                    // 单个项目推进失败不影响其他项目
                    log.warn("[人群定时推进] 项目 {} 推进失败: {}", rt.getProjectId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[人群定时推进] 任务执行失败: {}", e.getMessage());
        }
    }
}
