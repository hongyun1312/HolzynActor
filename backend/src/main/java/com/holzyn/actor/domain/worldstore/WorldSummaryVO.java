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

package com.holzyn.actor.domain.worldstore;

import java.time.LocalDateTime;

/**
 * 存档列表卡片（V1.1.2-beta.1，{@code GET /api/worlds} 的返回元素）。
 *
 * <p>为什么把磁盘事实（{@code dirPresent}/{@code dbPresent}/{@code lockPresent}）和目录库摘要
 * 放在同一个 VO：分档后"索引说世界存在"和"磁盘上真的存在"是两件事，而用户看到的卡片必须
 * 一次说清三件事——是什么世界、多大、还能不能打开。让前端分别请求两个接口再拼，等于把
 * "三者不一致时怎么显示"的判断散到前端。</p>
 *
 * <p>{@code worldStatus} 是权威状态（落库值）；磁盘事实只用于解释它为什么是这个值。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 *
 * @param id               目录库 {@code actor_project.id}（本机槽位，可因导入重编号）
 * @param worldUid         世界身份（= 目录名，跨机器唯一，永不变）
 * @param name             世界名（显示名，可中文）
 * @param summary          概要（可空）
 * @param coverUrl         封面文件名（相对世界目录，如 {@code cover.png}；可空 = 使用预备背面）
 * @param worldStatus      状态落库值（closed/mounted/broken/missing/incompatible）
 * @param worldStatusLabel 状态中文标签（直接显示，前端不再维护映射表）
 * @param mountable        当前是否可挂载（= 状态为 closed）
 * @param mounted          是否是当前已挂载世界
 * @param dirPresent       磁盘上是否存在该世界目录
 * @param dbPresent        磁盘上是否存在世界库文件
 * @param lockPresent      是否存在 H2 锁文件（进程异常退出残留的迹象）
 * @param gameSecond       世界时间快照（关闭时回写，可空）
 * @param characterCount   角色数快照（可空）
 * @param sizeBytes        世界目录体积快照（可空）
 * @param lastOpenedAt     最近一次挂载时间（可空）
 * @param lastClosedAt     最近一次卸载时间（可空）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @param schemaVersion    建库时的应用版本（诊断用，可空）
 * @param initStatus       世界初始化完成度快照（NOT_STARTED/PARTIAL/DONE/DEGRADED；可空=未算过）
 * @param initDone         已完成初始化步骤数（可空）
 * @param initTotal        初始化步骤总数（可空）
 *
 * @param genre            世界类别（固定八类之一的中文标签；可空 = 历史世界还没选过）
 *
 * @param tone             世界程度编码（casual/light/standard/hard；可空 = 按标准档处理）
 */
public record WorldSummaryVO(
        Long id,
        String worldUid,
        String name,
        String summary,
        String coverUrl,
        String worldStatus,
        String worldStatusLabel,
        boolean mountable,
        boolean mounted,
        boolean dirPresent,
        boolean dbPresent,
        boolean lockPresent,
        Long gameSecond,
        Long characterCount,
        Long sizeBytes,
        LocalDateTime lastOpenedAt,
        LocalDateTime lastClosedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String schemaVersion,
        String initStatus,
        Integer initDone,
        Integer initTotal,
        String genre,
        String tone) {
}
