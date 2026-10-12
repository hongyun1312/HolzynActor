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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import lombok.Data;

/**
 * 宏观事件 VO。
 * <p>含触发时间/类型/指标变化/严重度/叙事/来源（rule|ai），供时间线/沙盘宏观面板/ 日报板块。</p>
 */
@Data
public class MacroEventVO {

    private Long id;
    private Long projectId;
    private Long gameDay;
    private String macroType;
    private String eventType;
    private String title;
    private Integer severity;
    private String indicator;
    private Double oldValue;
    private Double newValue;
    private Double magnitude;
    private String narrative;
    private String source;

    public static MacroEventVO from(ActorWorldMacroEvent e) {
        MacroEventVO vo = new MacroEventVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setGameDay(e.getGameDay());
        vo.setMacroType(e.getMacroType());
        vo.setEventType(e.getEventType());
        vo.setTitle(e.getTitle());
        vo.setSeverity(e.getSeverity());
        vo.setIndicator(e.getIndicator());
        vo.setOldValue(e.getOldValue());
        vo.setNewValue(e.getNewValue());
        vo.setMagnitude(e.getMagnitude());
        vo.setNarrative(e.getNarrative());
        vo.setSource(e.getSource());
        return vo;
    }
}
