package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 场景/地点控制器（V2.1 世界演化）。
 * <p>职责：提供项目场景（地点）的增删改查与 AI 自动填充（vP5-7.6），
 * 供世界演化「选择指定场景（地点）」使用。</p>
 * <p>所属模块：controller/world（世界演化子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SceneController {

    /** 场景服务 */
    private final SceneService sceneService;

    /** 当前用户解析器（AI 调用凭据归属） */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 项目场景列表。
     *
     * @param projectId 项目 ID
     * @return 场景视图列表
     */
    @GetMapping("/projects/{projectId}/scenes")
    public R<List<Map<String, Object>>> list(@PathVariable("projectId") Long projectId) {
        return R.ok(sceneService.list(projectId));
    }

    /**
     * 新增场景。
     *
     * @param projectId 项目 ID
     * @param body      入参：{name 必填, description, location, background}
     * @return 新增后的场景视图
     */
    @PostMapping("/projects/{projectId}/scenes")
    public R<Map<String, Object>> create(@PathVariable("projectId") Long projectId, @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(sceneService.create(projectId, body));
    }

    /**
     * AI 自动填充场景：基于「世界观设定 + 角色档案」生成一批场景（含来源依据）。
     *
     * @param projectId 项目 ID
     * @param body      入参：{count? 生成数量 1~10，默认 3}
     * @return 本次新增的场景视图列表
     */
    @PostMapping("/projects/{projectId}/scenes/ai-generate")
    public R<List<Map<String, Object>>> aiGenerate(@PathVariable("projectId") Long projectId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(sceneService.aiGenerate(projectId, currentUserProvider.currentUserId(), body));
    }

    /**
     * 编辑场景。
     *
     * @param id   场景主键
     * @param body 入参
     * @return 更新后的场景视图
     */
    @PutMapping("/scenes/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(sceneService.update(id, body));
    }

    /**
     * 删除场景。
     *
     * @param id 场景主键
     * @return 成功响应
     */
    @DeleteMapping("/scenes/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        sceneService.delete(id);
        return R.ok(null);
    }
}
