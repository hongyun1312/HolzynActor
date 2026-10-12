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

package com.holzyn.actor.domain.sandbox.vo;

import lombok.Data;

/**
 * 沙盘天气切换结果视图。
 * <p>职责：返回天气切换后的生效天气（条件/温度/风/生效区域）+ 游戏时刻 + <b>季节字段</b>
 * （季节/月份/时刻阶段/日出日落/体感气温——时间季节系统 起，切换按季节修正气温，
 * 前端天气面板直接展示）。</p>
 * <p>数据源：当前 active 世界帧 weather JSON 的「主区域」（无主区域取首个区域）+
 * 季节服务（游戏月固定绑定 + 日出日落查表 + 体感修正）。</p>
 */
@Data
public class WeatherVO {

    /** 归属项目 ID */
    private Long projectId;

    /** 生效区域名（默认「主区域」；帧内无主区域取首个区域） */
    private String region;

    /** 天气条件（晴 / 雨 / 雪 / 阴） */
    private String condition;

    /** 温度 */
    private Double temperature;

    /** 风（如「北风」；切换保留原风） */
    private String wind;

    /** 切换时的引擎游戏秒（WorldClockService.currentEngineGameSecond） */
    private Long switchedAtGameSecond;

    /** 提示文案（幂等切换=「天气未变化」） */
    private String message;

    // ==================== 时间季节系统：季节字段 ====================

    /** 季节 code（spring/summer/autumn/winter；按切换时刻游戏月固定绑定） */
    private String season;

    /** 季节中文名（春/夏/秋/冬） */
    private String seasonName;

    /** 游戏月（1-12） */
    private Integer month;

    /** 时刻阶段（night/dawn/day/dusk） */
    private String phase;

    /** 时刻阶段中文名（夜/晨/昼/昏） */
    private String phaseName;

    /** 当日日出（HH:MM） */
    private String sunrise;

    /** 当日日落（HH:MM） */
    private String sunset;

    /** 体感气温（℃：气温 + 时段修正） */
    private Double feelsLikeC;
}
