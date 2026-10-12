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

package com.holzyn.actor.domain.worldmap.vo;

import lombok.Data;

/**
 * 世界地图生成结果 VO（POST /world-map/generate 返回）。
 */
@Data
public class WorldMapGenerateVO {

    /** 项目 ID */
    private Long projectId;

    /** 场景 ID */
    private Long sceneId;

    /** 场景版本（每次生成 +1） */
    private Integer sceneVersion;

    /** 生成来源：ai / mechanism */
    private String source;

    /** 地图名 */
    private String mapName;

    /** 地点区域数 */
    private int regionCount;

    /** 道路数 */
    private int roadCount;

    /** 建筑摆放数 */
    private int placementCount;

    /** 住所数（同居共宅：一间住所多人） */
    private int homeCount;

    /** 入住角色数 */
    private int memberCount;

    /** 结果消息 */
    private String message;
}
