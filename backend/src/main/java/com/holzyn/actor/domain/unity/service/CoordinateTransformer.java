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

package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.domain.unity.config.UnityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 坐标换算器（U 轨道 U1 服务端对接层，§6 插件化预留切换点 3 兑现，）。
 * <p>HA 网格（actor_sandbox_state/摆放 posX·posZ，青禾村场景中心 (0,0)）↔ Unity 世界坐标
 * 双向换算：{@code unity = ha × meterPerCell + originOffset}；y 直通（前期无高度）。
 * 默认映射 1 格 = 0.2 米（500 网格 ↔ 100m 场景，原点对齐场景中心 (0,0)），
 * 映射规则可配（holzyn.actor.unity.coordinate.*）。</p>
 * <p>换算为纯函数（同输入同输出），静态方法可单测；实例方法读配置供服务注入使用。</p>
 */
@Slf4j
@Component
public class CoordinateTransformer {

    /** Unity 对接配置（meter-per-cell + 原点偏移） */
    private final UnityProperties props;

    public CoordinateTransformer(UnityProperties props) {
        this.props = props;
    }

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * HA 网格坐标 → Unity 世界坐标（单轴）。
     *
     * @param haCoord HA 网格坐标（米/格单位）
     *
     * @param meterPerCell 1 格 = N 米（<=0 视为 1:1 占位）
     *
     * @param originOffset 原点偏移（Unity 坐标 = ha × meterPerCell + offset）
     *
     * @return Unity 世界坐标
     */
    public static double toUnityAxis(double haCoord, double meterPerCell, double originOffset) {
        double m = meterPerCell > 0 ? meterPerCell : 1.0;
        return haCoord * m + originOffset;
    }

    /**
     * Unity 世界坐标 → HA 网格坐标（单轴，双向互逆）。
     *
     * @param unityCoord Unity 世界坐标（米）
     *
     * @param meterPerCell 1 格 = N 米（<=0 视为 1:1 占位）
     *
     * @param originOffset 原点偏移（与 {@link #toUnityAxis} 同源）
     *
     * @return HA 网格坐标
     */
    public static double toHaAxis(double unityCoord, double meterPerCell, double originOffset) {
        double m = meterPerCell > 0 ? meterPerCell : 1.0;
        return (unityCoord - originOffset) / m;
    }

    /**
     * HA 网格坐标 → Unity 世界坐标（三维数组，y 直通）。
     *
     * @param haPos [x, y, z]（HA 网格；长度 <3 视为 [0,0,0]）
     *
     * @param meterPerCell 1 格 = N 米
     *
     * @param originOffsetX 原点偏移 X
     *
     * @param originOffsetZ 原点偏移 Z
     *
     * @return [x, y, z]（Unity 世界坐标）
     */
    public static double[] toUnity(double[] haPos, double meterPerCell,
                                   double originOffsetX, double originOffsetZ) {
        double x = haPos != null && haPos.length > 0 ? haPos[0] : 0d;
        double y = haPos != null && haPos.length > 1 ? haPos[1] : 0d;
        double z = haPos != null && haPos.length > 2 ? haPos[2] : 0d;
        return new double[]{
                toUnityAxis(x, meterPerCell, originOffsetX),
                y,
                toUnityAxis(z, meterPerCell, originOffsetZ)
        };
    }

    /**
     * Unity 世界坐标 → HA 网格坐标（三维数组，y 直通）。
     *
     * @param unityPos [x, y, z]（Unity 世界坐标）
     *
     * @param meterPerCell 1 格 = N 米
     *
     * @param originOffsetX 原点偏移 X
     *
     * @param originOffsetZ 原点偏移 Z
     *
     * @return [x, y, z]（HA 网格）
     */
    public static double[] toHa(double[] unityPos, double meterPerCell,
                                double originOffsetX, double originOffsetZ) {
        double x = unityPos != null && unityPos.length > 0 ? unityPos[0] : 0d;
        double y = unityPos != null && unityPos.length > 1 ? unityPos[1] : 0d;
        double z = unityPos != null && unityPos.length > 2 ? unityPos[2] : 0d;
        return new double[]{
                toHaAxis(x, meterPerCell, originOffsetX),
                y,
                toHaAxis(z, meterPerCell, originOffsetZ)
        };
    }

    // ==================== 实例方法（读配置） ====================

    /**
     * HA 网格坐标 → Unity 世界坐标（读配置映射规则）。
     *
     * @param haPos [x, y, z]（HA 网格）
     *
     * @return Unity 世界坐标
     */
    public double[] toUnity(double[] haPos) {
        return toUnity(haPos, meterPerCell(), originOffsetX(), originOffsetZ());
    }

    /**
     * Unity 世界坐标 → HA 网格坐标（读配置映射规则）。
     *
     * @param unityPos [x, y, z]（Unity 世界坐标）
     *
     * @return HA 网格坐标
     */
    public double[] toHa(double[] unityPos) {
        return toHa(unityPos, meterPerCell(), originOffsetX(), originOffsetZ());
    }

    /** 当前配置：1 格 = N 米（<=0 回退 1:1 占位） */
    public double meterPerCell() {
        return props.getCoordinate().getMeterPerCell();
    }

    /** 当前配置：原点偏移 X */
    public double originOffsetX() {
        return props.getCoordinate().getOriginOffsetX();
    }

    /** 当前配置：原点偏移 Z */
    public double originOffsetZ() {
        return props.getCoordinate().getOriginOffsetZ();
    }

    /** 当前配置摘要（调试页/日志）。 */
    public String configSummary() {
        return "1格=" + meterPerCell() + "米 原点(" + originOffsetX() + "," + originOffsetZ() + ")";
    }
}
