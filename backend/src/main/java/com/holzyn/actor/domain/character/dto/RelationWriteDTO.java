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

package com.holzyn.actor.domain.character.dto;

/**
 * 角色关系手动写入请求（阶段 D，用户：手动增删改 CRUD）。
 * <p>端点支持 characterRef（special:{id}/crowd:{id}，修 幽灵歧义）与名称兜底：
 * 提交 fromRef/toRef 或 fromName/toName（至少其一）；纯名称端点若命中项目内角色
 * 自动转正式 ref，否则以幽灵（id=0+名称）存储。</p>
 *
 * @param fromRef 发起方 characterRef（special:{id}/crowd:{id}；可空，空则按名称/保留原端点）
 *
 * @param toRef 目标方 characterRef（同上）
 *
 * @param fromName 发起方名称快照（ref 缺失时必填；幽灵端点存名称）
 *
 * @param toName 目标方名称快照（同上）
 *
 * @param relationType 关系类型（亲属/师徒/敌对等；可空回退「未知关系」）
 *
 * @param description 关系描述（可空）
 *
 * @param affinity 好感度 -100~100（可空=0）
 *
 * @param trust 信任度 0~100（可空=0）
 *
 * @param relationState 关系状态（stranger/acquaintance/friend/intimate/rival/hostile；可空=按好感/信任计算）
 */
public record RelationWriteDTO(String fromRef, String toRef, String fromName, String toName,
                               String relationType, String description,
                               Integer affinity, Integer trust, String relationState) {
}
