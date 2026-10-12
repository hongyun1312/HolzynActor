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

import java.util.List;

/**
 * 世界沙盘总览视图对象（世界沙盘 S1，读接口 GET /api/projects/{id}/sandbox/overview）。
 * <p>职责：沙盘页一次拿全——项目沙盘场景列表（含主场景）+ 全部 NPC 沙盘状态
 * （特殊 + 普通人群，含名称/坐标/动作等），供空态页与位置模拟展示。</p>
 */
@Data
public class SandboxOverviewVO {

    /** 项目 ID */
    private Long projectId;

    /** 场景总数 */
    private int sceneCount;

    /** 主场景（首个场景；无则 null） */
    private SandboxSceneVO mainScene;

    /** 全部场景 */
    private List<SandboxSceneVO> scenes;

    /** 全部 NPC 沙盘状态（特殊 + 普通人群） */
    private List<SandboxStateVO> states;

    /** 状态总数 */
    private int stateCount;

    /** 全部资产摆放记录（S3，跨场景，按场景 + 排序稳定排列） */
    private List<ScenePlacementVO> placements;

    /** 摆放总数 */
    private int placementCount;
}
