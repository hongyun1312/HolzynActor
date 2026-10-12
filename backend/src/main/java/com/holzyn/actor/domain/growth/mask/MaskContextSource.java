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

package com.holzyn.actor.domain.growth.mask;

import java.util.List;

/**
 * 面具情境解析判定点接口。
 * <p><b>设计动机</b>：面具情境判定是高频布尔/分类判定
 * （「此刻对谁、在什么场合」）——程序化实现确定可测；判定点接口使 N 轨道
 * 接入时只替换本接口实现（NanoJev choice 题型：
 * states=关系/身份/地点快照，questions=情境码选择），<b>不重改面具主流程</b>。</p>
 * <p><b>判定输入</b>：自己 + 在场者/对话对象（可空=独处/无社交对象）+ 世界时刻。
 * <b>输出</b>：情境码列表（有序，消费方按序取最多 {@code maxContexts} 个并叠加取最大抑制）。</p>
 * <p>；判定点设计对齐 「唯一策略源+程序化兜底」范式）。</p>
 */
public interface MaskContextSource {

    /**
     * 解析当前面具情境码。
     *
     * @param projectId 项目 ID
     *
     * @param selfRef 本人统一引用（npc:{id}）
     *
     * @param partnerRefs 在场者/对话对象引用列表（可空=独处/无社交对象；玩家会话传空——
     * 玩家不落 actor_profile，由实现按「陌生外来者」语义处理）
     *
     * @param gameSecond 当前世界时刻（游戏秒）
     *
     * @return 情境码列表（含 {@link MaskContextCode#PUBLIC_PLACE} 或 {@link MaskContextCode#PRIVATE_PLACE}
     * 之一作为场合码；失败返回空列表=无面具，零阻断）
     */
    List<String> resolve(Long projectId, String selfRef, List<String> partnerRefs, Long gameSecond);
}
