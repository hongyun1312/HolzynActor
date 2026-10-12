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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目季节骨架配置 VO。
 * <p>职责：GET /climate-config 读接口返回——固定季节绑定表（seasonMap：游戏月 → 季节，
 * 「固定死的」，展示口径）+ 四季骨架（内置默认 or 项目覆盖）+ 来源标记。</p>
 */
@Data
public class ClimateConfigVO {

    /** 项目 ID */
    private Long projectId;

    /** 游戏月 → 季节固定绑定表（1-12；展示口径，不可修改） */
    private Map<Integer, String> seasonMap = new LinkedHashMap<>();

    /** 四季骨架（season → {baseTempC, weatherTendency, daylightOffsetMin, colorTone}） */
    private Map<String, Object> skeleton = new LinkedHashMap<>();

    /** 来源：default（内置默认）/ custom（项目覆盖） */
    private String source;

    /** 配置版本（custom 时有效） */
    private Integer version;
}
