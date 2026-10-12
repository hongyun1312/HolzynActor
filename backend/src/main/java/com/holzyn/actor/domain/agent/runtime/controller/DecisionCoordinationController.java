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

package com.holzyn.actor.domain.agent.runtime.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.runtime.ActionCoordinatorService;
import com.holzyn.actor.domain.agent.runtime.ForesightService;
import com.holzyn.actor.domain.agent.runtime.StateDeltaService;
import com.holzyn.actor.domain.agent.runtime.TomInference;
import com.holzyn.actor.domain.agent.runtime.vo.ConflictRecordVO;
import com.holzyn.actor.domain.agent.runtime.vo.ForesightRecordVO;
import com.holzyn.actor.domain.agent.runtime.vo.ResourceOccupancyVO;
import com.holzyn.actor.domain.agent.runtime.vo.StateDeltaVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 决策与协调控制器（，domain/agent/runtime/controller）。
 * <p>职责：为调试前端「决策与协调 」页与主前端角色页提供只读回看/预览接口——
 * 前瞻推演记录与触发预览、 state delta 回看、 冲突记录与资源占用、ToM 档位预览。
 * 统一返回 R&lt;T&gt;；全部只读（前瞻/冲突/delta 的写入由运行时自动发生，无手动写接口）。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DecisionCoordinationController {

    /** 前瞻推演服务 */
    private final ForesightService foresightService;

    /** state delta 服务 */
    private final StateDeltaService stateDeltaService;

    /** 冲突协调服务 */
    private final ActionCoordinatorService coordinatorService;

    // ==================== 前瞻推演 ====================

    /**
     * 前瞻推演记录列表（调试回放；npcRef 可空=全项目）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（可空）
     *
     * @param limit 条数上限（可空=不截断）
     *
     * @return 前瞻记录 VO 列表
     */
    @GetMapping("/projects/{projectId}/decision/foresight")
    public R<List<ForesightRecordVO>> foresightList(@PathVariable Long projectId,
                                                    @RequestParam(required = false) String npcRef,
                                                    @RequestParam(required = false) Integer limit) {
        List<ForesightRecordVO> out = new ArrayList<>();
        for (var e : foresightService.list(projectId, npcRef, limit)) {
            out.add(ForesightRecordVO.of(e));
        }
        return R.ok(out);
    }

    /**
     * 前瞻触发+段落预览（不开会话不落库；调试/冒烟用）。
     *
     * @param projectId 项目 ID
     *
     * @param stimulusText 刺激文本（可空）
     *
     * @param importance 事件重要度（可空）
     *
     * @param urgent 是否紧急（可空）
     *
     * @param type 刺激类型 code（可空）
     *
     * @return 预览结果（triggered/reason/section/阈值）
     */
    @GetMapping("/projects/{projectId}/decision/foresight/preview")
    public R<Map<String, Object>> foresightPreview(@PathVariable Long projectId,
                                                   @RequestParam(required = false) String stimulusText,
                                                   @RequestParam(required = false) Integer importance,
                                                   @RequestParam(required = false) Boolean urgent,
                                                   @RequestParam(required = false) String type) {
        return R.ok(foresightService.preview(projectId, null, stimulusText, importance, urgent, type));
    }

    //

    /**
     * state delta 回看（npcRef 可空=全项目）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（可空）
     *
     * @param limit 条数上限（可空）
     *
     * @return delta VO 列表
     */
    @GetMapping("/projects/{projectId}/decision/state-deltas")
    public R<List<StateDeltaVO>> stateDeltaList(@PathVariable Long projectId,
                                                @RequestParam(required = false) String npcRef,
                                                @RequestParam(required = false) Integer limit) {
        List<StateDeltaVO> out = new ArrayList<>();
        for (var e : stateDeltaService.list(projectId, npcRef, limit)) {
            out.add(StateDeltaVO.of(e, StateDeltaService.changedFields(e.getChangesJson(),
                    new tools.jackson.databind.ObjectMapper())));
        }
        return R.ok(out);
    }

    // ==================== 冲突检测/资源/ToM ====================

    /**
     * 冲突检测记录列表（npcRef 可空=全项目）。
     */
    @GetMapping("/projects/{projectId}/decision/conflicts")
    public R<List<ConflictRecordVO>> conflictList(@PathVariable Long projectId,
                                                  @RequestParam(required = false) String npcRef,
                                                  @RequestParam(required = false) Integer limit) {
        List<ConflictRecordVO> out = new ArrayList<>();
        for (var e : coordinatorService.listConflicts(projectId, npcRef, limit)) {
            out.add(ConflictRecordVO.of(e));
        }
        return R.ok(out);
    }

    /**
     * 资源占用登记列表（全项目）。
     */
    @GetMapping("/projects/{projectId}/decision/occupancy")
    public R<List<ResourceOccupancyVO>> occupancyList(@PathVariable Long projectId,
                                                      @RequestParam(required = false) Integer limit) {
        List<ResourceOccupancyVO> out = new ArrayList<>();
        for (var e : coordinatorService.listOccupancy(projectId, limit)) {
            out.add(ResourceOccupancyVO.of(e));
        }
        return R.ok(out);
    }

    /**
     * ToM 推断深度档位预览（调试/冒烟：按角色卡 OCEAN 宜人性×神经质）。
     *
     * @param projectId 项目 ID（占位，未使用——档位只依赖角色卡；保留路径一致性）
     *
     * @param personaJson 角色卡 persona_json 文本（可空=常规推断）
     *
     * @return 档位与标签
     */
    @GetMapping("/projects/{projectId}/decision/tom")
    public R<Map<String, Object>> tomPreview(@PathVariable Long projectId,
                                             @RequestParam(required = false) String personaJson) {
        String tier = TomInference.tierOf(personaJson);
        return R.ok(Map.of(
                "tier", tier,
                "label", TomInference.labelOf(tier),
                "tiers", Map.of(
                        TomInference.TIER_OVER_INTERPRET, "过度解读（宜人性≥4 且神经质≥4）",
                        TomInference.TIER_NORMAL, "常规推断",
                        TomInference.TIER_LITERAL, "直接理解（宜人性≤2 且神经质≤2）")));
    }
}
