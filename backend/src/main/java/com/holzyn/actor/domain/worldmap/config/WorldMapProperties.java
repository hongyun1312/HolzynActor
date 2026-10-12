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

package com.holzyn.actor.domain.worldmap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 位置移动与地图生成系统配置（前缀 holzyn.actor.world-map；env 松弛绑定：点→下划线、连字符删除）。
 * <p>M1 地图生成 + 住所；M2 连续移动；M3 途中交互共用本配置类。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.world-map")
public class WorldMapProperties {

    /** 地图生成/查询总开关（false=生成走机制兜底、查询照常；零 AI） */
    private boolean enabled = true;

    /** 步行速度（米/游戏秒，M2 移动换算；默认 0.05 ≈ 现实 1.4m/s 换算到 rate=24 基准） */
    private double walkSpeedGameMetersPerSecond = 0.05;

    /** 有路走路、无路直线（M2；预留配置，M1 不消费） */
    private boolean roadFollowEnabled = true;

    /** 途中可遇可交互（M3；预留配置，M1/M2 不消费） */
    private boolean encounterEnabled = true;

    /** 途中交互冷却（游戏小时，M3；预留配置，M1/M2 不消费） */
    private int encounterCooldownGameHours = 1;

    /** 引擎 tick 插值推进总开关（M2；false=移动行照写但不插值推进——调试/回退用） */
    private boolean tickUpdateEnabled = true;

    // ==================== M1 地图生成参数 ====================

    /** AI 生成最大重试次数（含首次尝试之外的重试） */
    private int generateAiRetries = 2;

    /** 地图坐标边缘留白（格=米；区域/住所中心离边界的最近距离，防贴边） */
    private double margin = 40.0;

    /** 区域最小半径（格=米；防 AI 输出过小区域） */
    private double minRegionRadius = 8.0;

    /** 区域最大半径（格=米；防 AI 输出占满全图） */
    private double maxRegionRadius = 90.0;

    /** 住所与住所最小间距（格=米；防两间房叠一起） */
    private double homeMinDistance = 12.0;

    /** 区域重叠最小间距余量（格=米；两区域圆心距 ≥ r1+r2+margin 才算不重叠） */
    private double regionGap = 4.0;
}
