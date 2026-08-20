package com.holzyn.actor.domain.usage.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 用量统计控制器（P2 管理后台「AI 用量」）。
 * <p>职责：提供用户级用量聚合接口 GET /api/usage/stats，支持按项目/模型/场景/日期范围筛选，
 * 返回总量卡片 + 各维度分组 + 明细（供首页「AI 用量」Tab 展示）。</p>
 * <p>归属隔离：一律以当前会话用户为准（CurrentUserProvider）。</p>
 * <p>所属模块：controller/usage（用量子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UsageController {

    /** 用量统计服务 */
    private final UsageService usageService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 用量统计（用户级聚合）。
     *
     * @param projectId 项目筛选（可空）
     * @param scene     场景筛选（可空）
     * @param model     模型筛选（可空）
     * @param startDate 起始日期（可空）
     * @param endDate   结束日期（可空）
     * @return {summary, byProject, byScene, byModel, byDate, detail}
     */
    @GetMapping("/usage/stats")
    public R<Map<String, Object>> stats(@RequestParam(name = "projectId", required = false) Long projectId,
                                        @RequestParam(name = "scene", required = false) String scene,
                                        @RequestParam(name = "model", required = false) String model,
                                        @RequestParam(name = "startDate", required = false) String startDate,
                                        @RequestParam(name = "endDate", required = false) String endDate) {
        return R.ok(usageService.stats(currentUserProvider.currentUserId(), projectId, scene, model, startDate, endDate));
    }
}