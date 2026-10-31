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

package com.holzyn.actor.domain.character.vo;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import java.time.LocalDateTime;

/**
 * 角色视图对象（CharacterVO）。
 * <p>职责：向前端返回角色基础档案（含是否已有角色卡标记）。</p>
 *
 * <p><b>为什么带 npcRef / stage / age（2026-10-25 家族谱系页全空白的事故）</b>：
 * 谱系页要先列出「本项目全部角色」让用户选一个「本人视角」，选中后拿到的必须是统一身份引用
 * {@code npc:{id}}（谱系/基因接口的 {@code npcRef} 入参）。改前本 VO 没有 npcRef，
 * 前端只能拿 {@code ch.npcRef}（undefined）当选择值 → 选项能显示、但选完 {@code npcRef} 仍是空
 * → {@code v-if="npcRef"} 恒假 → 页面永远停在「请选择一位角色」，且一个谱系请求都不发。
 * stage/age 是选择器标签（「阿禾（壮年 32岁）」）的数据源，缺了只能显示「— ?岁」。</p>
 *
 * <p>字段口径：{@code npcRef} = {@link ActorCharacter#npcRef()}（与 {@code common/NpcRefs} 同源，
 * 前端不得自己拼 {@code 'npc:' + id}）；{@code stage}/{@code age} 直接取实体列
 * （成长阶段机维护，未计算时可为 null）。</p>
 */
public record CharacterVO(
        Long id,
        Long projectId,
        /** 统一身份引用 {@code npc:{id}}（谱系/基因/记忆等接口的入参） */
        String npcRef,
        String type,
        String name,
        String title,
        String detail,
        String avatarUrl,
        Integer isProtagonist,
        Integer importance,
        Integer status,
        /** 成长阶段（INFANT…ANCIENT/DECEASED；未计算为 null） */
        String stage,
        /** 年龄（岁；未计算为 null） */
        Integer age,
        Boolean hasCard,
        String location,
        Long locationId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param c 角色实体
     *
     * @param hasCard 是否已有角色卡
     *
     * @return VO 对象
     */
    public static CharacterVO of(ActorCharacter c, Boolean hasCard) {
        return new CharacterVO(c.getId(), c.getProjectId(), c.npcRef(), c.getType(), c.getName(), c.getTitle(),
                c.getDetail(), c.getAvatarUrl(), c.getIsProtagonist(), c.getImportance(), c.getStatus(),
                c.getStage(), c.getAge(), hasCard, c.getLocation(), c.getLocationId(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}