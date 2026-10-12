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

/**
 * 相机距离提供器接口。
 * <p><b>设计意图</b>：设计文档 §5.4 升档触发含「Unity 相机距离阈值（复用 U1 坐标 1 格=0.2 米）」；
 * 当前 Web 环境没有相机， =「重要度 + 事件/对话/同地/关系驱动，预留相机接口」。
 * 本接口即预留点：U2 Unity 客户端接入时实现（{@code distanceMeters} 返回相机到 NPC 的米数），
 * 在 {@code BrainTierService} 内作为升档条件之一参与判定，Web 环境返回 null（无相机）自动跳过。</p>
 */
public interface CameraDistanceProvider {

    /**
     * 相机到某 NPC 的距离（米）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @return 距离（米）；无相机/未装配返回 null（不参与判定）
     */
    Double distanceMeters(Long projectId, String npcRef);

    /**
     * 当前是否有活动相机（Web 环境无 Unity 相机 → false；有相机时距离判定才生效）。
     *
     * @param projectId 项目 ID
     *
     * @return true=有活动相机
     */
    boolean hasActiveCamera(Long projectId);
}
