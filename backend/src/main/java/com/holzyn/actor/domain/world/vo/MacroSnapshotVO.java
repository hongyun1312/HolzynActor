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

import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import lombok.Data;

import java.util.Map;

/**
 * 宏观快照 VO。
 * <p>indicators 为解析后的指标 JSON 对象（population/economy/faction/culture/tech 五段），
 * 供 沙盘宏观面板曲线回看；summary 为人类可读简报。</p>
 */
@Data
public class MacroSnapshotVO {

    private Long id;
    private Long projectId;
    private Long gameDay;
    private String tickType;
    private Map<String, Object> indicators;
    private String summary;

    public static MacroSnapshotVO from(ActorWorldMacroSnapshot e, Map<String, Object> indicators) {
        MacroSnapshotVO vo = new MacroSnapshotVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setGameDay(e.getGameDay());
        vo.setTickType(e.getTickType());
        vo.setIndicators(indicators);
        vo.setSummary(e.getSummary());
        return vo;
    }
}
