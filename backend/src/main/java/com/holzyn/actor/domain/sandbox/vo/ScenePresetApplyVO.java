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
 * 沙盘场景预设应用结果视图对象（世界沙盘 S3 补全，POST /scenes/{id}/preset）。
 * <p>职责：返回一次预设应用的结果摘要（场景/摆放/河/路数量与涉及资产），
 * 供前端展示与冒烟验证。</p>
 */
@Data
public class ScenePresetApplyVO {

    /** 预设名（qinghe-village-v1 等） */
    private String presetName;

    /** 预设显示名 */
    private String name;

    /** 场景 ID */
    private Long sceneId;

    /** 场景名 */
    private String sceneName;

    /** 布局边长（米） */
    private Integer sceneSize;

    /** 地形风格（hills 等） */
    private String styleCode;

    /** 地形种子 */
    private Long seed;

    /** 摆放条数 */
    private int placementCount;

    /** 河流条数 */
    private int riverCount;

    /** 道路条数 */
    private int roadCount;

    /** 涉及资产标识列表（去重，按出现顺序） */
    private List<String> assetIds;

    /** 消息 */
    private String message;
}
