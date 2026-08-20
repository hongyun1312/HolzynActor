package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldSimulationJob;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 世界时钟控制器（P4-2 世界持续模拟 API）。
 * <p>职责：提供世界时钟状态查询、更新（速率/暂停/锚点）与手动补推一轮接口；
 * 归属以当前登录用户为准（服务层校验）；统一返回 R&lt;T&gt;。</p>
 * <p>所属模块：controller/world（世界子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorldClockController {

    /** 世界时钟服务 */
    private final WorldClockService worldClockService;

    /** 世界模拟推进任务 */
    private final WorldSimulationJob worldSimulationJob;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 世界时钟状态（当前游戏时刻/速率/最近推进）。
     *
     * @param projectId 项目 ID
     * @return 时钟 VO
     */
    @GetMapping("/projects/{projectId}/world-clock")
    public R<WorldClockVO> get(@PathVariable("projectId") Long projectId) {
        return R.ok(worldClockService.getClock(projectId, currentUserProvider.currentUserId()));
    }

    /**
     * 更新世界时钟（速率/暂停/锚点；空字段保持原值）。
     *
     * @param projectId 项目 ID
     * @param dto       更新入参
     * @return 更新后的时钟 VO
     */
    @PutMapping("/projects/{projectId}/world-clock")
    public R<WorldClockVO> update(@PathVariable("projectId") Long projectId, @Valid @RequestBody WorldClockDTO dto) {
        return R.ok(worldClockService.updateClock(projectId, currentUserProvider.currentUserId(), dto));
    }

    /**
     * 手动补推一轮（调试/演示用）：立即按当前流逝推进角色/人群/事件。
     *
     * @param projectId 项目 ID
     * @return 推进结果（advancedHours/gameHour/actions/crowds/events/summary）
     */
    @PostMapping("/projects/{projectId}/world-clock/advance")
    public R<Map<String, Object>> advance(@PathVariable("projectId") Long projectId) {
        // 归属校验（同时懒创建时钟行），随后执行推进
        worldClockService.getClock(projectId, currentUserProvider.currentUserId());
        return R.ok(worldSimulationJob.advanceProject(projectId));
    }
}
