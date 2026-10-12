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

import java.time.LocalDateTime;

/**
 * 每日世界帧视图。
 * <p>JSON 字段（weather/macro_events/environment_changes/info_packets/ongoing_events）保留原始
 * JSON 文本，供调试前端/后续 ~ 解析展示；source/status/emergency 标记生成来源与状态。</p>
 */
@Data
public class WorldFrameVO {

    /** 帧 ID */
    private Long frameId;

    /** 所属项目 ID */
    private Long projectId;

    /** 游戏日序号（自纪元起；显示为「第 gameDay+1 日」） */
    private Long gameDay;

    /** 季节 */
    private String season;

    /** 天气 JSON（按区域：{"A村": {"condition": "晴", "temperature": 15, "wind": "北风"}}） */
    private String weatherJson;

    /** 宏观事件 JSON（event_id/type/location/severity/summary/start_day/duration_days/affected_area） */
    private String macroEventsJson;

    /** 环境变化 JSON（scene/change/effect） */
    private String environmentChangesJson;

    /** 信息包摘要 JSON（actor_info_packet 实体化源数据） */
    private String infoPacketsJson;

    /** 前一日世界帧摘要（连续性） */
    private String previousDaySummary;

    /** 进行中事件 JSON（生成时输入缓存） */
    private String ongoingEventsJson;

    /** 生成来源：ai / mechanism / emergency */
    private String source;

    /** 状态：active / superseded */
    private String status;

    /** 是否紧急更新帧（0/1） */
    private Integer emergency;

    /** 生成时的游戏秒 */
    private Long generatedAtGameSecond;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
