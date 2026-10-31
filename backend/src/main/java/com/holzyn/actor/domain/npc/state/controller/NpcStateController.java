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

package com.holzyn.actor.domain.npc.state.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateHistoryService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.npc.state.vo.NpcStateHistoryVO;
import com.holzyn.actor.domain.npc.state.vo.NpcStateVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * NPC 内部状态读接口。
 * <p>职责：向角色页「状态」Tab / 调试页提供 NPC 内部状态只读视图（需求/心情/情绪/健康/urgency）——
 * 决策侧状态权威 actor_state 的展示读接口；写入一律走 NpcStateService（衰减/行动挂钩/情绪评估）。
 * 增历史曲线读接口（actor_state_history 快照，调试前端状态页/主前端角色页曲线数据源）。</p>
 * <p>端点：GET /api/worlds/{id}/npc-state（项目全部状态行）、
 * GET /api/worlds/{id}/npc-state/{npcRef}（单 NPC 状态行，npcRef=npc:{id}）、
 */
@Slf4j
@RestController
@RequestMapping("/api/worlds/{projectId}/npc-state")
@RequiredArgsConstructor
public class NpcStateController {

    private final NpcStateService npcStateService;
    private final ActorProjectRepository projectRepository;

    /** 历史快照服务（字段注入保持构造器精简；null=历史接口不可用） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NpcStateHistoryService historyService;

    /**
     * 项目全部 NPC 内部状态（读接口：需求/心情/情绪/urgency 快照；供调试/批量展示）。
     *
     * @param projectId 项目 ID
     *
     * @return NpcStateVO 列表（按 id 升序）
     */
    @GetMapping
    public R<List<NpcStateVO>> list(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(npcStateService.listStates(projectId).stream().map(NpcStateVO::of).toList());
    }

    /**
     * 单 NPC 内部状态（角色页「状态」Tab 读接口；读取前惰性建行）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     */
    @GetMapping("/{npcRef}")
    public R<NpcStateVO> one(@PathVariable Long projectId, @PathVariable String npcRef) {
        requireProject(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（能否解析成 actor_profile 行）
        if (npcRef == null || !NpcRefs.isNpcRef(npcRef)) {
            throw new BizException(400, "npcRef 格式不正确（应为 npc:{id}）");
        }
        ActorNpcState st = npcStateService.stateFor(projectId, npcRef);
        if (st == null) {
            throw new BizException(404, "NPC 状态不存在或参数非法");
        }
        return R.ok(NpcStateVO.of(st));
    }

    /**
     * 单 NPC 状态历史快照。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef NPC 引用（统一 {@code npc:{id}}）
     *
     * @param limit 返回条数上限（可空/&lt;=0=全部；默认 168 对齐保留上限）
     *
     * @return NpcStateHistoryVO 列表（时间升序）
     */
    @GetMapping("/history")
    public R<List<NpcStateHistoryVO>> history(@PathVariable Long projectId,
                                              @RequestParam("npcRef") String npcRef,
                                              @RequestParam(required = false) Integer limit) {
        requireProject(projectId);
        // ：npc:{id} 不再编码类型，此处按意图 A 处理（能否解析成 actor_profile 行）
        if (npcRef == null || !NpcRefs.isNpcRef(npcRef)) {
            throw new BizException(400, "npcRef 格式不正确（应为 npc:{id}）");
        }
        if (historyService == null) {
            throw new BizException(500, "历史快照服务未装配");
        }
        return R.ok(historyService.listHistory(projectId, npcRef, limit).stream()
                .map(NpcStateHistoryVO::of).toList());
    }

    /** 项目校验（不存在/已删除 → 404） */
    private void requireProject(Long projectId) {
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }
}
