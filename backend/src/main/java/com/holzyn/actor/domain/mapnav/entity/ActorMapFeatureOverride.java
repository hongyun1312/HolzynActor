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

package com.holzyn.actor.domain.mapnav.entity;

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
 * 用户对某个地图要素的手改（表 {@code actor_map_feature_override}）。
 *
 * <p><b>为什么不直接改写 {@code actor_map_feature}</b>：那张表是 S4 的产物，重跑一次就被覆盖。
 * 把手改写回本体，用户辛苦拖了十分钟，一次「重掷种子」就没了。改成覆盖层之后：</p>
 * <ul>
 *   <li>「清除手改」= 删覆盖行，要素本体从未被改过，零风险回滚；</li>
 *   <li>重跑 S3/S4 后按 {@code feature_code} 重放，匹配不到就丢弃并提示（不静默丢失）；</li>
 *   <li>运行时视图 = 本体 ⊕ 覆盖，只有一处合成逻辑。</li>
 * </ul>
 *
 * <p><b>为什么键是 {@code feature_code} 而不是 {@code feature_id}</b>：id 每次重跑都变，
 * code 按「类型 + 序号」确定性生成，同种子重跑稳定——这是覆盖层能重放的唯一基础。</p>
 */
@Data
@Entity
@Table(name = "actor_map_feature_override")
public class ActorMapFeatureOverride {

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

    /** 要素编码（重放键） */
    @Column(name = "feature_code", nullable = false, length = 64)
    private String featureCode;

    /** 手改后的中心 X（米） */
    @Column(name = "pos_x", nullable = false)
    private Double posX;

    /** 手改后的中心 Z（米） */
    @Column(name = "pos_z", nullable = false)
    private Double posZ;

    /** 手改后的朝向（度） */
    private Double rotation = 0.0;

    /** 门坐标 X（米，由要素几何重算） */
    @Column(name = "door_x")
    private Double doorX;

    /** 门坐标 Z（米） */
    @Column(name = "door_z")
    private Double doorZ;

    /** 门朝向（度） */
    @Column(name = "door_dir")
    private Double doorDir;

    /** 修订号（每次保存 +1，前端据此判断是否需要重拉） */
    private Integer revision = 1;

    /** 备注（「整院挪到东街口」） */
    @Column(length = 200)
    private String note;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 插入前回调。 */
    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (rotation == null) {
            rotation = 0.0;
        }
        if (revision == null) {
            revision = 1;
        }
    }
}
