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

package com.holzyn.actor.domain.growth.persona;

import com.holzyn.actor.common.JsonColumnConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色档案版本历史表实体。
 * <p><b>职责</b>：只增不改地保留 11 维档案的<b>每一版快照</b>——{@code PersonaService.savePersona}
 * 每次落库成功后追加一行（新内容 + 新版本号 + 写入来源），供「版本历史」查看与「回滚到某版」使用。
 * 此前 每次保存只覆盖 {@code actor_profile.persona_json} + {@code persona_version} 自增，
 * 旧版本内容不保留，{@code CharacterCardService.versionHistory} 只能返回"最新一版"——本表落地后
 * 版本历史才是真历史。</p>
 * <p><b>写入口径</b>：快照内容 = 落库后的 {@code persona_json}（已按 {@code _sources} 分派、
 * 剥离模块权威维的存储态）——与主表同一份数据，保证"版本 N 回滚 = 恢复当时的完整档案视图"。
 * ③ 生理基线等模块权威维不在快照内（权威仍在 {@code actor_state}，回滚档案不会回滚运行态参数——
 * 回滚语义是「档案预设回到旧版」，运行态由世界继续演化，两者本就分层）。</p>
 * <p><b>行数治理</b>：每保存一次一行（含 AI 建档、AI 补全、手动编辑、回滚），版本号即自然序列；
 * 上限治理由「每项目最近 N 条」的归档策略另行承担（主对接文档 §5 待办），本表不做物理清理。</p>
 */
@Data
@Entity
@Table(name = "actor_profile_revision", indexes = {
        @Index(name = "idx_profile_revision_ref", columnList = "profile_id,persona_version")
})
public class ActorProfileRevision {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id；按项目归档/清理用） */
    @Column(name = "project_id")
    private Long projectId;

    /** 角色主表 ID（actor_profile.id，即统一身份 npc:{id} 的数字部分） */
    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    /** 该版的档案版本号（= 落库后 actor_profile.persona_version；同一角色内唯一） */
    @Column(name = "persona_version", nullable = false)
    private Integer personaVersion;

    /** 该版的 11 维档案 JSON 快照（存储态：已分派、已剥离模块权威维） */
    @Convert(converter = JsonColumnConverter.class)
    @Column(name = "persona_json", columnDefinition = "json")
    private String personaJson;

    /** 写入来源标记（generated / manual / edited / restored / complete_dims，对齐 savePersona 的 source 入参） */
    @Column(length = 32)
    private String source;

    /** 档案内 generated_by（ai_bootstrap / manual …；与来源并列，便于按"谁写的"审计） */
    @Column(name = "generated_by", length = 32)
    private String generatedBy;

    /** 档案内 generated_scene（persona_bootstrap_fill_a/b / manual …） */
    @Column(name = "generated_scene", length = 40)
    private String generatedScene;

    /** 备注（回滚/补全等操作的说明文字，可空） */
    @Column(length = 255)
    private String remark;

    /** 创建时间（该版写入时刻） */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 插入前回调：填充创建时间。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
