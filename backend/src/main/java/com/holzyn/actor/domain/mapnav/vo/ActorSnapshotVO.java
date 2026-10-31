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

package com.holzyn.actor.domain.mapnav.vo;

import lombok.Data;

import java.util.List;

/**
 * 一个角色此刻在地图上的快照（{@code GET /map/actors} 的元素）。
 *
 * <p>为什么把轨迹一起给：前端要画出「他正沿这条路走」，而不是每 5 秒把点瞬移一次。
 * 有了折线 + 出发/到达游戏秒，前端就能在两个快照之间自己按弧长插值——误差被约束在
 * 「服务端算的那条线」上，而不是「前端猜的那条线」。</p>
 */
@Data
public class ActorSnapshotVO {

    /** 角色引用（npc:12） */
    private String ref;

    /** 角色名 */
    private String name;

    /** 当前 X（米） */
    private Double x;

    /** 当前 Z（米） */
    private Double z;

    /** 朝向（度，0=北） */
    private Double heading;

    /** 状态：static / walking / working */
    private String status;

    /** 当前活动（work/meal/sleep/errand/chat/chores） */
    private String activity;

    /** 是否在室内 */
    private Boolean indoor;

    /** 所在/前往的要素 code */
    private String featureCode;

    /** 这份坐标对应的世界游戏秒（对账用） */
    private Long gameSecond;

    /** 在途轨迹（静止时为 null） */
    private PathVO path;

    /**
     * 在途轨迹。
     */
    @Data
    public static class PathVO {

        /** 轨迹 ID */
        private Long id;

        /** 折线（米） */
        private List<double[]> points;

        /** 总长（米） */
        private Double lengthM;

        /** 出发游戏秒 */
        private Long departGameSecond;

        /** 到达游戏秒 */
        private Long arriveGameSecond;

        /** 终点引用 */
        private String toRef;
    }
}
