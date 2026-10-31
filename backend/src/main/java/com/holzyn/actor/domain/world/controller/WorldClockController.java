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

package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldCommonKnowledgeService;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 世界时钟控制器。
 * <p>职责：提供世界时钟状态查询、更新（速率/暂停/锚点）与手动补推一轮接口；
 * 归属以当前登录用户为准（服务层校验）；统一返回 R&lt;T&gt;。</p>
 * <p>（统一调度壳）：手动推进直连 {@link WorldEngine}（原 WorldSimulationJob 薄壳已收编删除），
 * 暂停语义与自动推进统一（引擎内部 paused 跳过）。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorldClockController {

    /** 世界时钟服务 */
    private final WorldClockService worldClockService;

    /** 世界推进引擎（统一调度壳收编后手动推进直连的唯一推进入口） */
    private final WorldEngine worldEngine;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /** 世界常识摘要服务（V2.0 段 1A；required=false fail-open——未装配返回 reason 而非 500） */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private WorldCommonKnowledgeService worldCommonKnowledgeService;

    /**
     * 世界时钟状态（当前游戏时刻/速率/最近推进）。
     *
     * @param projectId 项目 ID
     *
     * @return 时钟 VO
     */
    @GetMapping("/worlds/{projectId}/world-clock")
    public R<WorldClockVO> get(@PathVariable("projectId") Long projectId) {
        return R.ok(worldClockService.getClock(projectId, currentUserProvider.currentUserId()));
    }

    /**
     * 世界时钟（速率/暂停/锚点；空字段保持原值）。
     *
     * @param projectId 项目 ID
     *
     * @param dto 更新入参
     *
     * @return 更新后的时钟 VO
     */
    @PutMapping("/worlds/{projectId}/world-clock")
    public R<WorldClockVO> update(@PathVariable("projectId") Long projectId, @Valid @RequestBody WorldClockDTO dto) {
        return R.ok(worldClockService.updateClock(projectId, currentUserProvider.currentUserId(), dto));
    }

    /**
     * 手动补推一轮（调试/演示用）：立即按当前流逝推进角色/人群/事件。
     * <p>手动推进为<b>用户显式操作</b>，绕过实时决策激活闸门（force=true）——
     * 未打开沙盘/演化页时仍可手动推进调试；仍遵守 paused / world_running 语义。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 推进结果（advancedHours/gameHour/actions/crowds/events/summary）
     */
    @PostMapping("/worlds/{projectId}/world-clock/advance")
    public R<Map<String, Object>> advance(@PathVariable("projectId") Long projectId) {
        // 归属校验（同时懒创建时钟行），随后执行推进（强制模式绕过激活闸门）
        worldClockService.getClock(projectId, currentUserProvider.currentUserId());
        return R.ok(worldEngine.advanceProjectForce(projectId, true));
    }

    /**
     * 世界常识摘要手动补建/刷新（V2.0 段 1A，冒烟/运维直驱）。
     * <p>依最新世界观设定 AI 提炼 300-500 字「该世界角色都知道的常识」并写库；
     * 新项目在 worldgen 落库/世界观保存后自动生成（ProjectService afterCommit），
     * 此端点供既有项目/修复场景手动触发；世界未变化时幂等跳过。</p>
     *
     * @param projectId 项目 ID
     *
     * @return {generated=是否生成/更新（false=跳过或降级）}
     */
    @PostMapping("/worlds/{projectId}/world-common-knowledge")
    public R<Map<String, Object>> regenerateCommonKnowledge(@PathVariable("projectId") Long projectId) {
        if (worldCommonKnowledgeService == null) {
            return R.ok(Map.of("generated", false, "reason", "服务未装配"));
        }
        boolean generated = worldCommonKnowledgeService.generate(
                currentUserProvider.currentUserId(), projectId);
        return R.ok(Map.of("generated", generated));
    }
}
