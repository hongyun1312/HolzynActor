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
 */
public record CharacterVO(
        Long id,
        Long projectId,
        String type,
        String name,
        String title,
        String detail,
        String avatarUrl,
        Integer isProtagonist,
        Integer importance,
        Integer status,
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
        return new CharacterVO(c.getId(), c.getProjectId(), c.getType(), c.getName(), c.getTitle(), c.getDetail(),
                c.getAvatarUrl(), c.getIsProtagonist(), c.getImportance(), c.getStatus(), hasCard,
                c.getLocation(), c.getLocationId(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}