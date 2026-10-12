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

package com.holzyn.actor.domain.crowd.vo;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;

import java.time.LocalDateTime;

/**
 * 普通型 NPC 视图对象。
 * <p>职责：向前端返回单条普通型 NPC 的完整档案（含状态机字段），供表格渲染/编辑回显。
 * 分类字段（categoryL1/L2）已废弃，改为 种族/次级种族/归属/职业/所在地 等字段。</p>
 * <p><b>口径</b>：普通型 NPC 是统一主表 {@code actor_profile} 中 {@code type='common'} 的行；
 * 其中 {@code state}/{@code lastAction} 两个运行态字段<b>不在主表</b>（已从 {@code actor_profile_ordinary}
 * 迁出），权威在 {@code actor_state.activity_state} / {@code actor_state.last_action}，
 * 由 {@code NpcActivityStateService} 读取后<b>显式传入本 VO</b>——VO 不自行查库、也不从实体取。</p>
 *
 * @param id 主键（即统一身份 {@code npc:{id}} 的数字部分）
 *
 * @param projectId 项目 ID
 *
 * @param name 名称
 *
 * @param gender 性别
 *
 * @param race 种族（一级）
 *
 * @param subRace 次级种族（二级）
 *
 * @param age 年龄
 *
 * @param affiliation 归属
 *
 * @param location 当前所在地
 *
 * @param locationId 归一化地点 ID（，引用 actor_world_location.id，未命中 null）
 *
 * @param occupation 职业（可空）
 *
 * @param detail 角色详情
 *
 * @param state 当前状态（idle/walk/stop/talk/rest；来源 actor_state.activity_state，无行时 idle）
 *
 * @param lastAction 最近行动描述（来源 actor_state.last_action，可空）
 *
 * @param createdAt 创建时间
 *
 * @param updatedAt 更新时间
 */
public record OrdinaryNpcVO(
        Long id,
        Long projectId,
        String name,
        String gender,
        String race,
        String subRace,
        Integer age,
        String affiliation,
        String location,
        Long locationId,
        String occupation,
        String detail,
        String state,
        String lastAction,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体 + 运行态构造 VO（运行态由调用方从 {@code NpcActivityStateService} 取好后传入）。
     * <p>为什么不在这里读：{@code state}/{@code lastAction} 已不在主表实体上（收敛到
     * {@code actor_state}），逐行查库会把列表页变成 N+1；调用方用批量方法一次取全后回填。</p>
     *
     * @param e 普通型 NPC 主表实体（actor_profile，type=common）
     *
     * @param state 活动状态（来源 actor_state.activity_state；空 → idle）
     *
     * @param lastAction 最近行动描述（来源 actor_state.last_action；可空）
     *
     * @return VO 对象
     */
    public static OrdinaryNpcVO of(ActorCharacter e, String state, String lastAction) {
        return new OrdinaryNpcVO(
                e.getId(), e.getProjectId(), e.getName(), e.getGender(), e.getRace(), e.getSubRace(), e.getAge(),
                e.getAffiliation(), e.getLocation(), e.getLocationId(), e.getOccupation(), e.getDetail(),
                state == null || state.isBlank() ? "idle" : state, lastAction, e.getCreatedAt(), e.getUpdatedAt());
    }
}
