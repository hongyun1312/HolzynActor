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

package com.holzyn.actor.domain.mapnav.service;

/**
 * 两套坐标域的唯一换算入口：**地图域**（{@code [0, mapSpan)}，权威位置/渲染/注入都用它）
 * 与**沙盘域**（以场景中心为原点，{@code [-sceneSize/2, +sceneSize/2)}，沙盘渲染与哈希派生用它）。
 *
 * <p><b>为什么要有这个类</b>：1.1.3 之前两处各写了一份换算，而且都假设「场景边长 == 地图边长」。
 * 真实世界不是这样：地图边长是 1024 米（{@code actor_world_map.size × metersPerCell}），
 * 沙盘场景边长是 500（{@code actor_sandbox_scene.scene_size}）。于是一条「地图 x=900」的位置
 * 被换算成沙盘 {@code 900 - 250 = 650}，而沙盘域的上界只有 250——人直接被放到场景外面，
 * 区域反查、附近角色、途中遭遇全部落空。这不是精度问题，是两套坐标系从未真正对齐。</p>
 *
 * <p><b>换算规则</b>：按比例映射中心点，而不是简单地减半个边长。
 * <pre>
 *   sandbox = (map / mapSpan - 0.5) × sceneSize
 *   map     = (sandbox / sceneSize + 0.5) × mapSpan
 * </pre>
 * 两者边长相等时退化成旧公式（减/加半个边长），所以对存量数据零影响。</p>
 */
public final class MapCoordinateSystem {

    /** 地图边长缺省（米）：生成器默认 128 格 × 8 米。 */
    public static final double DEFAULT_MAP_SPAN_M = 1024.0;

    /** 沙盘场景边长缺省。 */
    public static final double DEFAULT_SCENE_SIZE = 500.0;

    private MapCoordinateSystem() {
    }

    /**
     * 地图域一维坐标 → 沙盘域一维坐标。
     *
     * @param mapCoord  地图域坐标（米）
     * @param mapSpan   地图边长（米，≤0 用缺省）
     * @param sceneSize 沙盘边长（≤0 用缺省）
     * @return 沙盘域坐标（以场景中心为原点）
     */
    public static double mapToSandbox(double mapCoord, double mapSpan, double sceneSize) {
        double span = mapSpan > 0 ? mapSpan : DEFAULT_MAP_SPAN_M;
        double scene = sceneSize > 0 ? sceneSize : DEFAULT_SCENE_SIZE;
        return (mapCoord / span - 0.5) * scene;
    }

    /**
     * 沙盘域一维坐标 → 地图域一维坐标。
     *
     * @param sandboxCoord 沙盘域坐标（以场景中心为原点）
     * @param mapSpan      地图边长（米，≤0 用缺省）
     * @param sceneSize    沙盘边长（≤0 用缺省）
     * @return 地图域坐标（米）
     */
    public static double sandboxToMap(double sandboxCoord, double mapSpan, double sceneSize) {
        double span = mapSpan > 0 ? mapSpan : DEFAULT_MAP_SPAN_M;
        double scene = sceneSize > 0 ? sceneSize : DEFAULT_SCENE_SIZE;
        return (sandboxCoord / scene + 0.5) * span;
    }

    /**
     * 地图域点 → 沙盘域点。
     *
     * @param mapX      X（米）
     * @param mapZ      Z（米）
     * @param mapSpan   地图边长
     * @param sceneSize 沙盘边长
     * @return {x, z}（沙盘域）
     */
    public static double[] mapToSandbox(double mapX, double mapZ, double mapSpan, double sceneSize) {
        return new double[]{
                mapToSandbox(mapX, mapSpan, sceneSize),
                mapToSandbox(mapZ, mapSpan, sceneSize)};
    }

    /**
     * 沙盘域点 → 地图域点。
     *
     * @param sandboxX  X
     * @param sandboxZ  Z
     * @param mapSpan   地图边长
     * @param sceneSize 沙盘边长
     * @return {x, z}（地图域）
     */
    public static double[] sandboxToMap(double sandboxX, double sandboxZ, double mapSpan, double sceneSize) {
        return new double[]{
                sandboxToMap(sandboxX, mapSpan, sceneSize),
                sandboxToMap(sandboxZ, mapSpan, sceneSize)};
    }
}
