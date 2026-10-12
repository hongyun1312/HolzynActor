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

package com.holzyn.actor.domain.growth.lod;

import org.springframework.stereotype.Component;

/**
 * 无相机兜底实现。
 * <p>当前 Web 环境没有 Unity 相机：{@code hasActiveCamera=false}、{@code distanceMeters=null}
 * ——相机距离升档条件自动跳过，LOD 完全由「重要度 + 事件/同地/关系」驱动。
 * U2 Unity 客户端接入时提供 {@code @Primary} 实现替换本兜底。</p>
 */
@Component
public class NoopCameraDistanceProvider implements CameraDistanceProvider {

    @Override
    public Double distanceMeters(Long projectId, String npcRef) {
        return null; // 无相机
    }

    @Override
    public boolean hasActiveCamera(Long projectId) {
        return false;
    }
}
