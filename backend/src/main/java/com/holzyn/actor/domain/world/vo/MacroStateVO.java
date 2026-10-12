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

package com.holzyn.actor.domain.world.vo;

import lombok.Data;

/**
 * 宏观状态 VO。
 * <p>当前宏观状态摘要（行为参数联动/调试/前端宏观面板头部）：核心指标 + 简报文本 +
 * 已推进到的游戏日；indicators 为全量五段（曲线面板直接消费）。</p>
 */
@Data
public class MacroStateVO {

    private Long projectId;
    private Long gameDay;
    private Long population;
    private Double grainPrice;
    private Double marketActivity;
    private Double grainStock;
    private Double bankDeposit;
    private String topFaction;
    private String topIssue;
    private String summary;
    private Object indicators;
    /** 世界观模板 id（双维度模板抽象；前端显示模板名用） */
    private String templateId;
    /** 民怨（0-100；生计→舆论因果链核心指标） */
    private Double grievance;
    /** 本日季节（spring/summer/autumn/winter） */
    private String season;
    /** 收成系数（季节×天气×连续累积） */
    private Double harvestFactor;

    /** 是否已初始化（无状态时 false，前端提示先初始化） */
    private boolean initialized = true;
}
