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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import lombok.Data;

/**
 * 宏观入账流水 VO。
 * <p>含行动者/行动/折算指标与量/来源（rule|ai 判定），供调试回看与 验收可归因断言。</p>
 */
@Data
public class MacroDeltaVO {

    private Long id;
    private Long projectId;
    private Long gameDay;
    private String npcRef;
    private String npcName;
    private String actionType;
    private String actionText;
    private String targetMacro;
    private String indicator;
    private Double delta;
    private Integer aiJudged;
    private String source;

    public static MacroDeltaVO from(ActorWorldMacroDelta d) {
        MacroDeltaVO vo = new MacroDeltaVO();
        vo.setId(d.getId());
        vo.setProjectId(d.getProjectId());
        vo.setGameDay(d.getGameDay());
        vo.setNpcRef(d.getNpcRef());
        vo.setNpcName(d.getNpcName());
        vo.setActionType(d.getActionType());
        vo.setActionText(d.getActionText());
        vo.setTargetMacro(d.getTargetMacro());
        vo.setIndicator(d.getIndicator());
        vo.setDelta(d.getDelta());
        vo.setAiJudged(d.getAiJudged());
        vo.setSource(d.getSource());
        return vo;
    }
}
