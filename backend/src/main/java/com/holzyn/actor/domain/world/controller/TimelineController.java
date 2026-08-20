package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间线控制器（V2.1 时间线聚合接口）。
 * <p>职责：提供项目「编年史」统一时间线查询（事件/行动/记忆聚合）、手动新增事件、
 * AI 从世界观识别生成事件。</p>
 * <p>所属模块：controller/world（时间线/事件子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TimelineController {

    /** 时间线服务 */
    private final TimelineService timelineService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 项目统一时间线（事件/行动/记忆聚合，时间倒序）。
     *
     * @param projectId   项目 ID
     * @param types       类型过滤（event/action/memory，逗号分隔，空=全部）
     * @param characterId 角色过滤（可空）
     * @param startDate   起始日期（可空，yyyy-MM-dd）
     * @param endDate     结束日期（可空）
     * @return 统一时间线节点列表
     */
    @GetMapping("/projects/{projectId}/timeline")
    public R<List<Map<String, Object>>> timeline(
            @PathVariable("projectId") Long projectId,
            @RequestParam(name = "types", required = false) String types,
            @RequestParam(name = "characterId", required = false) Long characterId,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate) {
        Set<String> typeSet = types == null || types.isBlank() ? null
                : java.util.Arrays.stream(types.split(",")).map(String::trim).collect(java.util.stream.Collectors.toSet());
        return R.ok(timelineService.aggregate(projectId, typeSet, characterId, startDate, endDate));
    }

    /**
     * 手动新增事件（持久化到 actor_event，source=manual）。
     *
     * @param projectId 项目 ID
     * @param body      入参：{title?, content 必填, kind?, characterId?}
     * @return 新增的事件视图
     */
    @PostMapping("/projects/{projectId}/events")
    public R<Map<String, Object>> createEvent(@PathVariable("projectId") Long projectId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(timelineService.createEvent(projectId, body));
    }

    /**
     * AI 从世界观/项目概况识别生成事件（source=ai）。
     *
     * @param projectId 项目 ID
     * @return 生成的事件视图
     */
    @PostMapping("/projects/{projectId}/events/ai-generate")
    public R<Map<String, Object>> aiGenerate(@PathVariable("projectId") Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(timelineService.aiGenerateEvent(userId, projectId));
    }
}
