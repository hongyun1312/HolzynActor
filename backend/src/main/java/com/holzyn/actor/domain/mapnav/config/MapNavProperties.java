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

package com.holzyn.actor.domain.mapnav.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 地图移动与导航配置（前缀 {@code holzyn.actor.mapnav}；env 松弛绑定：点→下划线、连字符删除）。
 *
 * <p><b>为什么速度单位是「米 / 游戏分钟」而不是「米 / 秒」</b>：游戏时间是被压缩的
 * （前端 1 真实秒 = 1 游戏分钟），人眼看到的是「游戏分钟」里的位移。用真实步速（1.4 m/s）
 * 会让 300 米走 40 游戏分钟、真实 40 秒，而且日程全来不及；用「米 / 游戏分钟」直接对齐日程，
 * 20 m/游戏分钟 ≈ 300 米 15 分钟，图上 15 秒走完，拐弯看得清。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.mapnav")
public class MapNavProperties {

    /** 总开关（false=不推进位置，地图页退化为只读） */
    private boolean enabled = true;

    /** 基准步速（米 / 游戏分钟）——村道/巷子 */
    private double walkSpeed = 20.0;

    /** 主街步速（米 / 游戏分钟） */
    private double streetSpeed = 24.0;

    /** 田埂土路步速（米 / 游戏分钟） */
    private double trackSpeed = 16.0;

    /** 无路处（院内/田里）步速（米 / 游戏分钟） */
    private double offroadSpeed = 12.0;

    /** 最短行程（游戏分钟）：门口挪两步也要有一点点「走过去」的时间 */
    private int minTravelMinutes = 1;

    /** 最长行程（游戏分钟）：超过就按上限走，避免一次长途吃掉整段日程 */
    private int maxTravelMinutes = 45;

    /** 判定「到点了」的容差（米） */
    private double arriveToleranceMeters = 1.2;

    /** 覆盖层重放：匹配不到要素时是否保留覆盖（true=保留，等下次重生成再试） */
    private boolean keepOrphanOverrides = true;

    /** 编辑校验：建筑到路中心线的净空（米） */
    private double roadClearanceMeters = 0.3;

    /** 编辑校验：建筑之间最小净空（米） */
    private double buildingGapMeters = 0.3;

    /** 编辑校验：允许的最大坡度（0~1） */
    private double maxBuildSlope = 0.32;

    /** 编辑校验：门到最近道路的最大距离（米；超过则自动补接入路） */
    private double maxDoorToRoadMeters = 25.0;

    /** 编辑校验：距地图边界的最小边距（米） */
    private double mapMarginMeters = 4.0;
}
