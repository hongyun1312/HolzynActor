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
 * 在途移动 VO（GET /api/projects/{id}/movements；位置移动与地图生成系统 · M2）。
 * <p>载荷：移动中角色引用 + 起止坐标（沙盘中心化域，与沙盘 SSE 一致）+ 出发/到达游戏秒 +
 * 进度（0~1）+ 速度 + 触发源 + 目标地点。供沙盘页 / 新首页渲染「移动中角色 + 轨迹」。</p>
 */
@Data
public class MovementVO {

    /** 项目 ID */
    private Long projectId;

    /** NPC 引用（统一 npc:{id}） */
    private String npcRef;

    /** NPC 名称（读接口富化；无则引用兜底） */
    private String npcName;

    /** 起点坐标 X（沙盘中心化） */
    private Double fromX;

    /** 起点坐标 Z */
    private Double fromZ;

    /** 目标坐标 X */
    private Double toX;

    /** 目标坐标 Z */
    private Double toZ;

    /** 出发游戏秒 */
    private Long fromGameSecond;

    /** 预计到达游戏秒 */
    private Long arriveGameSecond;

    /** 移动进度（0~1；按当前游戏秒计算） */
    private Double progress;

    /** 步行速度（米/游戏秒） */
    private Double speed;

    /** 触发源：plan / decision / physio / home / sandbox */
    private String reason;

    /** 目标地点 ID（可空=坐标直行） */
    private Long targetLocationId;

    /** 目标地点名（可空） */
    private String targetLocationName;
}
