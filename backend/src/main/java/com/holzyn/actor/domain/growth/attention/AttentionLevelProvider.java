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

package com.holzyn.actor.domain.growth.attention;

/**
 * 注意力档位提供器接口。
 * <p><b>定位</b>：把「认知注意力」的实时状态（认知 LOD 档位 / 群聊参与度档位 / 相机注意力档位）
 * 暴露给<b>消费方</b>——记忆注入（{@code MemoryInjectionRouter/MemoryInjectorService} 排序加权 +
 * 数量配额）、群聊发言欲望（{@code RoleplayService} desire 加分）、空间生成（{@code GrowthSpaceService}
 * 记忆条数配额）。</p>
 * <p><b>为什么用接口</b>：模块边界纪律——记忆/角色扮演/空间子域<b>只依赖本接口</b>（field 注入 +
 * {@code @Autowired(required=false)} + {@code @Lazy} 破环），不直接依赖 {@code CognitiveAttentionService}
 * 实现，避免 memory ↔ growth/attention 的构造环在编译期固化；实现方（{@code CognitiveAttentionService}）
 * 委托 {@code BrainTierService}（认知 LOD 档位）并自持参与度统计与相机聚焦（程序化，零新增 AI 调用）。</p>
 * <p>实现语义（供消费方理解返回值）：
 * <ul>
 * <li>{@link #tierOf}——认知 LOD 档位（dormant/ambient/focus）；</li>
 * <li>{@link #participationLevel}——群聊参与度档位（0~3：近 6 游戏小时发言/在场加权次数分档）；</li>
 * <li>{@link #cameraAttentionLevel}——相机注意力档位（0~3：Unity 相机距离连续映射；无相机=0）。</li>
 * </ul>
 * 全部方法<b>零 AI 调用、零事务</b>（内存态只读查询），任何异常按最保守值返回（档位=null/0），
 * 消费方必须把 null/0 当「无注意力信号」处理，绝不影响主链路。</p>
 */
public interface AttentionLevelProvider {

    /** 参与度统计种类：成功发言（权重 1） */
    String PARTICIPATION_SPOKE = "spoke";

    /** 参与度统计种类：在场参与（权重 0.5） */
    String PARTICIPATION_ATTENDED = "attended";

    /**
     * 记录一次群聊参与：发言者=spoke、在场其他成员=attended，
     * 滑动窗口（近 {@code participation-window-game-hours} 游戏小时）内加权计数，内存态零副作用。
     * 实现方必须容错（开关关/参数非法静默跳过）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @param gameSecond 当前游戏秒
     */
    void recordParticipation(Long projectId, String npcRef, String kind, Long gameSecond);

    /**
     * 认知 LOD 档位（委托 BrainTierService；无实现/异常返回 null——消费方按「无信号」处理）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @return dormant/ambient/focus；null=不可用
     */
    String tierOf(Long projectId, String npcRef);

    /**
     * 群聊参与度档位（0~3：近 {@code participation-window-game-hours} 游戏小时内
     * 发言(spoke)×1 + 在场(attended)×0.5 的加权次数按步长分档）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @return 0~3（0=无参与度信号；开关关/无记录也返回 0）
     */
    int participationLevel(Long projectId, String npcRef);

    /**
     * 相机注意力档位（0~3：距离 ≤ camera-near-meters→3、≤ camera-mid-meters→2、
     * ≤ camera-far-meters→1、否则 0；无相机/未装配/开关关=0）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @return 0~3（0=无相机注意力信号）
     */
    int cameraAttentionLevel(Long projectId, String npcRef);
}
