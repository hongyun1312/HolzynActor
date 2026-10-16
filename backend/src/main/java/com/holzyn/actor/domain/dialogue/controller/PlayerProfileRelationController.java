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

package com.holzyn.actor.domain.dialogue.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.dialogue.service.PlayerProfileService;
import com.holzyn.actor.domain.dialogue.vo.PlayerRelationVO;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 玩家-角色关系接口（M3：项目级）。
 * <p>职责：某项目内全部「玩家-角色关系行」读取 + 单角色关系行保存（好感/信任/阶段/备注——
 * 用户可编辑基线；state_summary/version 归 M6 沉淀写回，只读展示）。</p>
 * <p>架构纪律：零 AI 短事务（见 {@link PlayerProfileService}）；项目不存在抛 404。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/player-profile/relations")
public class PlayerProfileRelationController {

    /** 玩家档案服务 */
    @Autowired(required = false)
    private PlayerProfileService playerProfileService;

    /** 当前用户解析 */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 项目仓库（项目校验） */
    @Autowired(required = false)
    private ActorProjectRepository projectRepository;

    /**
     * 某项目全部玩家-角色关系行。
     *
     * @param projectId 项目 ID
     *
     * @return 关系 VO 列表（无数据返回空列表）
     */
    @GetMapping
    public R<List<PlayerRelationVO>> list(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(playerProfileService.relationsOf(projectId));
    }

    /**
     * 保存某项目+角色的关系行（affinity/trust/stage/note 选填；null 保持原值，空串清空）。
     *
     * @param projectId    项目 ID
     * @param characterRef 对谈角色引用（npc:{id}）
     * @param body         请求体 { affinity?, trust?, stage?, note? }
     *
     * @return 更新后的关系 VO
     */
    @PutMapping("/{characterRef}")
    public R<PlayerRelationVO> save(@PathVariable Long projectId, @PathVariable String characterRef,
                                    @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        Integer affinity = body == null ? null : intValue(body.get("affinity"));
        Integer trust = body == null ? null : intValue(body.get("trust"));
        String stage = body == null ? null : str(body.get("stage"));
        String note = body == null ? null : str(body.get("note"));
        return R.ok(playerProfileService.upsertRelation(projectId, currentUserProvider.currentUserId(),
                characterRef, affinity, trust, stage, note));
    }

    // ==================== 内部 ====================

    /** 项目校验（不存在/已删除 → 404）。 */
    private void requireProject(Long projectId) {
        if (projectRepository == null) {
            return;
        }
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }

    /** body 字符串取值（null 安全）。 */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** body 整型取值（null/非法返回 null）。 */
    private static Integer intValue(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
