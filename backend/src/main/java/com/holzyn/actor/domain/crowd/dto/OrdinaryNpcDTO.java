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

package com.holzyn.actor.domain.crowd.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 普通型 NPC 档案入参。
 * <p>职责：承载单条普通型 NPC 的完整档案字段——名称（必填）/性别/种族/次级种族/年龄/归属/
 * 当前所在地/职业（可空）/角色详情；AI 生成草稿与手动新增/编辑共用。
 * 分类不再由 category 字段承载（废弃），改为按 归属/职业/种族/所在地 等字段聚合；
 * 「关系」单独存 actor_character_relation，不在本 DTO。</p>
 * <p><b>口径</b>：本 DTO 只承载<b>档案列</b>（写入统一主表 {@code actor_profile}，
 * 服务层显式落 {@code type='common'}）；<b>不含</b>{@code state}/{@code lastAction}/{@code dailyPlanJson}
 * 三个运行态字段——它们已迁入 {@code actor_state}，由 {@code NpcActivityStateService} 读写
 * （前端如需展示经 VO 回填，不接受前端直改运行态）。</p>
 *
 * @param name 名称（必填，AI 生成需符合世界观命名习惯）
 *
 * @param gender 性别
 *
 * @param race 种族（一级，取自字段字典 race.level1）
 *
 * @param subRace 次级种族（二级，取自字段字典 race.level2）
 *
 * @param age 年龄
 *
 * @param affiliation 归属（势力/组织/村落等，取自字段字典 affiliation）
 *
 * @param location 当前所在地（地点，优先取自世界观地点表）
 *
 * @param occupation 职业（取自字段字典 occupation，可空）
 *
 * @param detail 角色详情
 */
public record OrdinaryNpcDTO(
        @NotBlank(message = "名称不能为空") String name,
        String gender,
        String race,
        String subRace,
        @Min(value = 0, message = "年龄不能为负") @Max(value = 10000, message = "年龄超出合理范围") Integer age,
        String affiliation,
        String location,
        String occupation,
        String detail
) {
}
