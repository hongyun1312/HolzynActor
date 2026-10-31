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

package com.holzyn.actor.domain.agent.thinking.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标/计划控制器（阶段 D，domain/agent/thinking/controller）。
 * <p>职责：特殊 NPC 目标（actor_character_goal）的读/写/AI 生成/状态流转 + 当日计划只读聚合——
 * 供角色页「目标/计划」Tab 展示与操作；characterRef 统一 special:{id}（目标仅特殊 NPC，crowd 无目标表）。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GoalController {

    private final PlanningService planningService;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final ActorProjectRepository projectRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 目标列表（全部状态）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（special）
     *
     * @return 目标列表
     */
    @GetMapping("/worlds/{projectId}/characters/{characterId}/goals")
    public R<List<ActorCharacterGoal>> listGoals(@PathVariable("projectId") Long projectId,
                                                 @PathVariable("characterId") Long characterId) {
        requireCharacterInProject(characterId, projectId);
        return R.ok(planningService.listGoals(projectId, com.holzyn.actor.common.NpcRefs.of(characterId)));
    }

    /**
     * 手动创建目标。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @return 新建目标
     */
    @PostMapping("/worlds/{projectId}/characters/{characterId}/goals")
    public R<ActorCharacterGoal> createGoal(@PathVariable("projectId") Long projectId,
                                            @PathVariable("characterId") Long characterId,
                                            @RequestBody Map<String, Object> body) {
        requireCharacterInProject(characterId, projectId);
        ActorCharacterGoal g = planningService.createGoal(projectId, com.holzyn.actor.common.NpcRefs.of(characterId),
                body == null ? null : (String) body.get("title"),
                body == null ? null : (String) body.get("description"),
                body == null ? null : toInt(body.get("priority")));
        return R.ok(g);
    }

    /**
     * AI 生成目标（goal_gen 模板，scene=goal_gen 用量；无 active 目标时生成，失败静默）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     *
     * @return 新建目标数
     */
    @PostMapping("/worlds/{projectId}/characters/{characterId}/goals/generate")
    public R<Integer> generateGoal(@PathVariable("projectId") Long projectId,
                                   @PathVariable("characterId") Long characterId) {
        requireCharacterInProject(characterId, projectId);
        return R.ok(planningService.generateGoal(projectId, com.holzyn.actor.common.NpcRefs.of(characterId), null));
    }

    /**
     * 目标（状态/标题/优先级；空字段保持原值）。
     *
     * @param goalId 目标 ID
     *
     * @return 更新后的目标
     */
    @PutMapping("/goals/{goalId}")
    public R<ActorCharacterGoal> updateGoal(@PathVariable("goalId") Long goalId, @RequestBody Map<String, Object> body) {
        ActorCharacterGoal g = planningService.updateGoal(goalId,
                body == null ? null : (String) body.get("status"),
                body == null ? null : (String) body.get("title"),
                body == null ? null : toInt(body.get("priority")));
        return R.ok(g);
    }

    /**
     * 删除目标（软删）。
     *
     * @param goalId 目标 ID
     *
     * @return 操作结果
     */
    @DeleteMapping("/goals/{goalId}")
    public R<Map<String, Object>> deleteGoal(@PathVariable("goalId") Long goalId) {
        planningService.deleteGoal(goalId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        return R.ok(out);
    }

    /**
     * 角色「目标/计划」聚合（角色页 Tab 数据源）：目标列表 + 当日计划（当前时段 + 全文）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID
     */
    @GetMapping("/worlds/{projectId}/characters/{characterId}/goal-plan")
    public R<Map<String, Object>> goalPlan(@PathVariable("projectId") Long projectId,
                                           @PathVariable("characterId") Long characterId) {
        requireCharacterInProject(characterId, projectId);
        String ref = com.holzyn.actor.common.NpcRefs.of(characterId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goals", planningService.listGoals(projectId, ref));
        out.put("goalsSummary", planningService.goalsSummary(projectId, ref));
        out.put("planSummary", planningService.planSummary(projectId, ref, null));
        out.put("planToday", planningService.planToday(projectId, ref));
        return R.ok(out);
    }

    /** 角色属于项目校验（特殊角色；越权 404） */
    private void requireCharacterInProject(Long characterId, Long projectId) {
        characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new com.holzyn.actor.common.BizException(404, "角色不存在或无权访问"));
    }

    /** Object → Integer（null/非法返回 null） */
    private Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
