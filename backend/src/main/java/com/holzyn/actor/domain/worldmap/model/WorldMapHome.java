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

import java.util.ArrayList;
import java.util.List;

/**
 * 世界地图生成 · 住所（一间房 = 一个家，可住多人）。
 * <p>members 表达谁住在这（同居共宅：配偶/情侣/亲子/兄弟姐妹/亲属同住）；每角色恰在一个
 * 住所的 members 中。</p>
 */
@Data
public class WorldMapHome {

    /** 家名（「沈家大宅」「李记面馆后屋」） */
    private String name;

    /** 所属地点 ID（actor_world_location.id） */
    private Long locationId;

    /** 家坐标 X（0..scene_size 地图坐标） */
    private double posX;

    /** 家坐标 Z（0..scene_size 地图坐标） */
    private double posZ;

    /** 主屋标签（「正房」） */
    private String roomLabel;

    /** 方位（「村西」） */
    private String region;

    /** 补充说明（「书房在西厢」） */
    private String note;

    /** 住户（同居共宅：一间住所多人） */
    private List<WorldMapHomeMember> members = new ArrayList<>();
}
