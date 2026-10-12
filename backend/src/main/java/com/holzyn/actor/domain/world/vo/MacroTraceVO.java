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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroTrace;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 宏观全链路 trace VO。
 * <p>input/params/events/feedback 四跳回放字段（JSON 解析后对象）：
 * 输入摘要 → 引擎参数 → 产出事件 → 回灌落点；eventIds 为真实事件主键列表（事件表回看跳转）。</p>
 */
@Data
public class MacroTraceVO {

    private Long id;
    private Long projectId;
    private Long gameDay;
    private Long gameSecond;
    private String tickType;
    private Map<String, Object> input;
    private Map<String, Object> params;
    private List<Long> eventIds;
    private Map<String, Object> feedback;
    private java.time.LocalDateTime createdAt;

    public static MacroTraceVO from(ActorWorldMacroTrace e, Map<String, Object> input,
                                    Map<String, Object> params, List<Long> eventIds,
                                    Map<String, Object> feedback) {
        MacroTraceVO vo = new MacroTraceVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setGameDay(e.getGameDay());
        vo.setGameSecond(e.getGameSecond());
        vo.setTickType(e.getTickType());
        vo.setInput(input);
        vo.setParams(params);
        vo.setEventIds(eventIds);
        vo.setFeedback(feedback);
        vo.setCreatedAt(e.getCreatedAt());
        return vo;
    }
}
