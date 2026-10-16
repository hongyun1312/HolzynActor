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

package com.holzyn.actor.domain.character.vo;

import java.util.List;

/**
 * 角色关系拓扑图视图对象（CharacterRelationGraphVO）。
 * <p>职责：向前端返回「全角色网络图」所需数据。节点 = ①项目下全部未删除 NPC 角色 +
 * ②项目下全部普通型 NPC（kind=crowd，每个普通 NPC 以具体人名独立成节点）+ ③关系表引用但
 * 两表都不存在的「幽灵角色」（kind=ghost，仅名称，id 兜底存 0）；关系边 = actor_character_relation
 * （端点按 NPC→普通型 NPC→幽灵 顺序解析为唯一 key，自环过滤）。供 AntV v5 力导向布局渲染；
 * 选中角色/幽灵节点时右上角卡片查询详情。</p>
 * <p>字段命名对齐 GraphData：nodes + relations。</p>
 */
public record CharacterRelationGraphVO(
        /** 全部节点（NPC/普通型 NPC/幽灵三类） */
        List<NodeVO> nodes,
        /** 关系边（from→to，带双方解析后的 key 与名称） */
        List<EdgeVO> relations
) {

    /**
     * 图节点。
     *
     * @param id 唯一 key：npc-&lt;角色id&gt; / crowd-&lt;普通型NPC id&gt; / ghost-&lt;角色名&gt;
     *
     * @param name 显示名（NPC=角色名；普通型=普通 NPC 姓名；幽灵=关系表引用名）
     *
     * @param kind 节点类型：npc / crowd / ghost（前端区分样式）
     *
     * @param type NPC 类型（special/common）；普通型/幽灵为 null
     *
     * @param importance 重要度 1~5（NPC 有；其余 null）
     *
     * @param isProtagonist 是否主角（NPC：1=是；其余 null）
     *
     * @param title NPC 头衔（可空）
     *
     * @param detail NPC 详细信息 / 普通型 NPC 角色详情（展示用，后端截断防止大 payload）
     *
     * @param crowdName 普通型 NPC 归属（势力/组织/村落，kind=crowd 有）
     *
     * @param occupation 普通型 NPC 职业（可空）
     *
     * @param state 普通型 NPC 当前状态（idle/walk/…；可空）
     *
     * @param lastAction 普通型 NPC 最近行动描述（可空）
     *
     * @param meta 节点附加语义（归属/家族/所在地/档案速览；<b>UI V3.0 只增不改新增</b>，
     *             未装配/降级时为空对象而非 null——旧消费方忽略本字段即可）
     */
    public record NodeVO(
            String id,
            String name,
            String kind,
            String type,
            Integer importance,
            Integer isProtagonist,
            String title,
            String detail,
            String crowdName,
            String occupation,
            String state,
            String lastAction,
            NodeMeta meta
    ) {
    }

    /**
     * 节点附加语义（UI V3.0 · 关系工作台「社会结构总览」只增不改新增）。
     *
     * <p><b>为什么要有它</b>：重做前的关系拓扑只有「名字 + 重要度 + 圆点」可编码，画出来
     * 是一盘散点（实测：17 节点 / 0 关系时整块画布只剩孤立圆），界面让人怀疑「世界是不是坏了」。
     * 而「谁和谁是一拨人」这件事世界内核里本来就有答案——中观组织（{@code actor_meso_membership}
     * + {@code actor_meso_group}，势力/宗族/行会/自发成组）与血缘谱系（{@code actor_growth_lineage}）。
     * 本记录把这两类<b>真实归属</b>连同档案速览一次带给前端，使「分成哪几拨人」按世界事实分区，
     * 而不是靠图算法凭空猜。</p>
     *
     * <p><b>口径</b>：组织归属优先取成员关系；无成员关系时按 {@code actor_profile.affiliation}
     * 文本匹配组织名兜底——与 {@code WorldGraphQueryService} 的「角色所属组织」同源，
     * 保证关系拓扑里的圈子与世界图谱里的势力对得上。</p>
     *
     * @param avatarUrl 头像地址（{@code actor_profile.avatar_url}；可空=前端用姓名首字徽章兜底）
     *
     * @param gender 性别（可空）
     *
     * @param age 年龄（可空；随世界时间增龄）
     *
     * @param stage 生命周期阶段（婴儿/幼儿/…/耄耋；可空）
     *
     * @param affiliation 归属文本（原 {@code actor_profile.affiliation}；各类节点统一给出）
     *
     * @param occupation 职业（各类节点统一给出；与节点顶层 {@code occupation} 同值，
     *                   那里只为兼容旧消费方面保留）
     *
     * @param location 当前所在地点名（可空）
     *
     * @param locationId 当前所在地点 ID（可空）
     *
     * @param groupId 主要中观组织 ID（可空=无归属；不编造）
     *
     * @param groupName 主要中观组织名（如「村中宗族」；可空）
     *
     * @param groupType 组织类型（family 宗族 / cult 祭祀 / guild 行会 / community 群落 /
     *                  adhoc 自发成组；可空）
     *
     * @param groupRole 组内角色（leader 首领 / elder 长老 / core 核心 / member 普通 /
     *                  affiliate 外围；可空）
     *
     * @param familyId 血缘家族 ID（谱系连通分量；可空=无任何谱系边，不编造）
     *
     * @param familyName 血缘家族称谓（如「沈氏一族」；可空）
     */
    public record NodeMeta(
            String avatarUrl,
            String gender,
            Integer age,
            String stage,
            String affiliation,
            String occupation,
            String location,
            Long locationId,
            Long groupId,
            String groupName,
            String groupType,
            String groupRole,
            String familyId,
            String familyName
    ) {

        /** 空附加语义（降级/未装配时的唯一兜底，前端无需判空）。 */
        public static final NodeMeta EMPTY = new NodeMeta(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 关系边。
     *
     * @param id 关系主键
     *
     * @param fromKey 发起方解析后的节点 key（npc-/crowd-/ghost-）
     *
     * @param toKey 目标方解析后的节点 key
     *
     * @param fromName 发起方角色名
     *
     * @param toName 目标方角色名
     *
     * @param relationType 关系类型（决定边配色）
     *
     * @param description 关系描述（悬浮提示展示）
     *
     * @param fromRef 发起方正式 characterRef（special:{id}/crowd:{id}；幽灵=null， 修）
     *
     * @param toRef 目标方正式 characterRef（同上）
     *
     * @param affinity 好感 -100~100（动态字段）
     *
     * @param trust 信任 0~100（动态字段）
     *
     * @param relationState 关系状态键（stranger/acquaintance/friend/intimate/rival/hostile）
     *
     * @param relationStateLabel 关系状态中文标签（展示）
     *
     * @param lastInteractionAt 最近互动时间（可空）
     */
    public record EdgeVO(
            Long id,
            String fromKey,
            String toKey,
            String fromName,
            String toName,
            String relationType,
            String description,
            String fromRef,
            String toRef,
            Integer affinity,
            Integer trust,
            String relationState,
            String relationStateLabel,
            String lastInteractionAt
    ) {
    }
}
