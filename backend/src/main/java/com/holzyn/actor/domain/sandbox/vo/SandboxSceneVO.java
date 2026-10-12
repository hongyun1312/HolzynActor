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

import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 沙盘场景视图对象（世界沙盘 S1，读接口）。
 * <p>职责：向沙盘页展示场景定义摘要（模板/风格/尺寸/种子/来源/版本），不含全量 layout_json。</p>
 */
@Data
public class SandboxSceneVO {

    /** 场景 ID */
    private Long id;

    /** 场景名 */
    private String name;

    /** 预设模板标识（village/town/forest/…或 custom） */
    private String templateCode;

    /** 程序地形风格（plain/mountain/hills/seaside/desert/snow 或 custom） */
    private String styleCode;

    /** 场景边长（500 的整数倍） */
    private Integer sceneSize;

    /** 程序地形随机种子（可复现） */
    private Long seed;

    /** 来源：template / manual / ai_generated */
    private String source;

    /** 场景定义版本 */
    private Integer version;

    /**
     * 布局坐标图（S3 补全：解析后的 layout_json——{scene, rivers[], roads[], placements[]}；
     * 前端像素地形/河路色带渲染数据源；无 layout_json 时为 null）
     */
    private Object layout;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 时间 */
    private LocalDateTime updatedAt;

    /** 实体转 VO */
    public static SandboxSceneVO of(ActorSandboxScene e) {
        return of(e, null);
    }

    /** 实体转 VO（layout 由调用方传入解析结果；null 表示不携带） */
    public static SandboxSceneVO of(ActorSandboxScene e, Object layout) {
        if (e == null) {
            return null;
        }
        SandboxSceneVO vo = new SandboxSceneVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setTemplateCode(e.getTemplateCode());
        vo.setStyleCode(e.getStyleCode());
        vo.setSceneSize(e.getSceneSize());
        vo.setSeed(e.getSeed());
        vo.setSource(e.getSource());
        vo.setVersion(e.getVersion());
        vo.setLayout(layout);
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }
}
