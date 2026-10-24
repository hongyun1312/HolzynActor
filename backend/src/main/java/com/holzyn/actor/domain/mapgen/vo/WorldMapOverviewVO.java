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

package com.holzyn.actor.domain.mapgen.vo;

import lombok.Data;

import java.util.Map;

/**
 * 地图概览（前端「地图」页顶部那块：名字、状态、进度、尺度、统计）。
 * <p>与渲染包分开的理由：概览是一个几十字节的小对象，首页/侧边栏、轮询、SSE 收尾都要它；
 * 渲染包是几 MB 的几何数据，只在真正要画图时拉一次。混在一起会让「打开首页」变成「下载整张地图」。</p>
 */
@Data
public class WorldMapOverviewVO {

    /** 地图 ID */
    private Long id;

    /** 项目 ID */
    private Long projectId;

    /** 地图名（「青屿镇」） */
    private String name;

    /** 状态：pending / running / ready / failed */
    private String status;

    /** 当前阶段（S0..S5） */
    private String stage;

    /** 进度 0~100 */
    private Integer progress;

    /** 边长（格） */
    private Integer size;

    /** 每格米数 */
    private Double metersPerCell;

    /** 地图宽（米） */
    private Double widthMeters;

    /** 地图高（米） */
    private Double heightMeters;

    /** 题材档 */
    private String genreProfile;

    /** 地形贴图版本（前端靠它破缓存） */
    private Integer terrainVersion;

    /** 地形贴图 URL（带版本号，可直接给 Texture 用） */
    private String terrainUrl;

    /** 写实底图 URL（4096² 主图，带版本号；前端按缩放切 LOD） */
    private String compositeUrl;

    /** 夜景写实底图 URL */
    private String compositeNightUrl;

    /** 写实底图三档 LOD：{@code {"4096":url,"2048":url,"1024":url}} */
    private Map<String, String> lods;

    /** 夜景三档 LOD */
    private Map<String, String> nightLods;

    /** 写实底图像素边长（4096；前端换算 米→像素 用） */
    private Integer compositePixels;

    /** 管线版本 */
    private Integer pipelineVersion;

    /** 统计（陆地占比/建筑数/人口/农田/路网分量…） */
    private Map<String, Object> stats;

    /** 世界档案摘要（地图名/叙述/地标/禁忌清单） */
    private Map<String, Object> profile;

    /** 更新时间（yyyy-MM-dd HH:mm:ss） */
    private String updatedAt;
}
