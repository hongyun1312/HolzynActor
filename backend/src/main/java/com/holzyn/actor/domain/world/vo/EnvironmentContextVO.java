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

import java.util.ArrayList;
import java.util.List;

/**
 * 统一环境上下文 VO。
 * <p>职责：承载「季节 + 时刻 + 天气」确定性计算的环境状态（昼夜晨昏/光照/体感气温/
 * 环境状态文本/感官标签），供三处注入（感知段/对话段/决策情境）与
 * GET /environment/context 读接口共用；desc 可经 environment_desc 模板 AI 润色
 * （source=ai-cached）或确定性模板组合（source=template）。</p>
 * <p>结构对齐设计文档 §5.2 ⑤ 输出结构（season/month/gameHour/phase/sunrise/sunset/
 * weather/tempC/feelsLikeC/desc/sensory）。</p>
 */
@Data
public class EnvironmentContextVO {

    /** 项目 ID */
    private Long projectId;

    /** 计算基准游戏秒（调用方传入或引擎推进位置） */
    private Long gameSecond;

    /** 游戏月（1-12） */
    private Integer month;

    /** 季节 code（spring/summer/autumn/winter） */
    private String season;

    /** 季节中文名（春/夏/秋/冬） */
    private String seasonName;

    /** 当日小时（0-23） */
    private Integer hourOfDay;

    /** 时刻阶段（night/dawn/day/dusk） */
    private String phase;

    /** 时刻阶段中文名（夜/晨/昼/昏） */
    private String phaseName;

    /** 当日日出（HH:MM，季节查表+每日波动） */
    private String sunrise;

    /** 当日日落（HH:MM，季节查表+每日波动） */
    private String sunset;

    /** 当前天气（active 帧主区域 condition；无帧/无区域=null） */
    private String weather;

    /** 风向风力（active 帧主区域 wind；可空） */
    private String wind;

    /** 气温（℃：帧温度优先，缺失=季基准+天气修正） */
    private Double tempC;

    /** 体感气温（℃：气温 + 时段修正） */
    private Double feelsLikeC;

    /** 环境状态文本（AI 润色缓存 or 确定性模板组合） */
    private String desc;

    /** 感官标签（寒冷刺骨/天色全黑/有风/下着雪…） */
    private List<String> sensory = new ArrayList<>();

    /** 描述来源：template（确定性模板）/ ai-cached（AI 润色缓存命中） */
    private String source;

    /** 季节色调（骨架 colorTone） */
    private String colorTone;
}
