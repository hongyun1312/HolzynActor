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

package com.holzyn.actor.domain.mapgen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色日程锦点（表 actor_npc_routine）——「让角色像真人生活」的最小数据面。
 *
 * <p>一个角色一天被切成若干段（{@code startMinute..endMinute}，0 点起的分钟数），
 * 每段对应一个真实的落点（家 / 工作地 / 集市 / 田 / 码头 / 井台）与一句活动描述。
 * 前端按世界时钟在段与段之间插值，角色就会在图上沿着「家 → 工作地 → 集市 → 夜归」走动，
 * 夜里回屋、白天出门——这比一个不动的小圆点真实得多。</p>
 *
 * <p>为什么不用 AI 直接生成「行为流」：日程的结构（几点起床、几点吃饭、干多久活）是
 * 人类学上的稳定常量，应该由程序按角色职业/家庭角色确定性地排；AI 适合写「今天为什么
 * 提前收工」这类事件，那是运行期的事，不该混进地图生成。这里保证同种子同日程，可复现。</p>
 */
@Data
@Entity
@Table(name = "actor_npc_routine")
public class ActorNpcRoutine {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 归属地图 */
    @Column(name = "map_id", nullable = false)
    private Long mapId;

    /** 角色统一引用（npc:{id} / special:{id}） */
    @Column(name = "npc_ref", nullable = false, length = 64)
    private String npcRef;

    /** 角色名（冗余：日程面板不必再 join 角色表） */
    @Column(name = "npc_name", length = 100)
    private String npcName;

    /** 当天第几段（0 起，按时间升序） */
    @Column(nullable = false)
    private Integer seq;

    /** 起始分钟（0~1439） */
    @Column(name = "start_minute", nullable = false)
    private Integer startMinute;

    /** 结束分钟（0~1440；1440=次日 0 点） */
    @Column(name = "end_minute", nullable = false)
    private Integer endMinute;

    /** 落点类型：home / work / market / field / shore / well / social */
    @Column(name = "place_kind", length = 24)
    private String placeKind;

    /** 落点的世界名字（「镇中水井」；没有名字时为空） */
    @Column(name = "place_ref", length = 100)
    private String placeRef;

    /** 落点对应的地图要素 code（可空：田块/岸线可能没有单一要素） */
    @Column(name = "feature_code", length = 64)
    private String featureCode;

    /** 落点 X（米） */
    @Column(name = "pos_x")
    private Double posX;

    /** 落点 Z（米） */
    @Column(name = "pos_z")
    private Double posZ;

    /** 活动（work / meal / sleep / errand / rest / chat） */
    @Column(length = 40)
    private String activity;

    /** 人读描述（「在码头补网」） */
    @Column(length = 100)
    private String label;

    /** 是否在室内（1=屋里，0=露天） */
    private Integer indoor;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (indoor == null) {
            indoor = 0;
        }
        if (seq == null) {
            seq = 0;
        }
    }
}
