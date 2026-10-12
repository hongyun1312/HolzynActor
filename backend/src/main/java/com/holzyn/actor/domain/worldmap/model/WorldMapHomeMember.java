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

package com.holzyn.actor.domain.worldmap.model;

import lombok.Data;

/**
 * 世界地图生成 · 住户（谁住在某间住所，同居共宅的一员）。
 */
@Data
public class WorldMapHomeMember {

    /** 角色统一引用（npc:{id}） */
    private String characterRef;

    /** 家中身份（户主 / 配偶 / 子女 / 亲属 / 同住） */
    private String role;

    /** 个人房间（「正房东间」） */
    private String roomLabel;

    /** 是否户主（机制生成内部填充；AI 输出以 role=户主 为准） */
    private boolean owner;
}
