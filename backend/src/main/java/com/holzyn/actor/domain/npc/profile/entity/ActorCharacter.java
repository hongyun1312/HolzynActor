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

package com.holzyn.actor.domain.npc.profile.entity;

import com.holzyn.actor.common.JsonColumnConverter;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 角色统一主表实体，对应表 actor_profile。
 * <p><b>本次改造的定位转变</b>：本表由"特殊型角色表"升级为<b>全 NPC 单一主表</b>——
 * 原 {@code actor_profile_ordinary}（普通型 NPC）与 {@code actor_profile_card}（角色卡）两张表<b>物理废弃</b>，
 * 其字段与数据全部并入本表；{@code type} 列（special / common）不再表示"在哪张表"，仅作为
 * 路由与展示属性。</p>
 * <p><b>统一身份</b>：全系统 NPC 引用统一为 {@code npc:{id}}（见 {@code common/NpcRefs}），
 * 取代历史 {@code special:{id}} / {@code crowd:{id}} 双前缀（单表后两套 id 空间合一，
 * 双前缀既冗余又会撞车）。</p>
 * <p><b>字段分层</b>：</p>
 * <ul>
 * <li><b>路由/结构列</b>（可索引，供既有模块按列检索）：name/title/type/gender/race/sub_race/
 * affiliation/occupation/location/location_id/current_activity/importance/is_protagonist/status；</li>
 * <li><b>档案列</b>：{@code persona_json}（11 维 v2 规范视图的<b>无归属字段容器</b>，权威在档案的维度）+
 * <li><b>派生列</b>：{@code age}（由 birth_game_second 派生，日级刷新）、{@code stage}（当前发育阶段码，
 * 阶段推进时更新）、{@code brain_tier}；</li>
 * <li><b>不在本表</b>：运行态（作息状态机 state/last_action/daily_plan_json、生理与情绪值）
 * 一律收敛到 {@code actor_state}（运行时唯一家）；③ 生理基线的权威是
 * {@code actor_state} 的 per-NPC 参数，不是本表。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "actor_profile", indexes = {
        @Index(name = "idx_profile_project_type", columnList = "project_id,type"),
        @Index(name = "idx_profile_stage", columnList = "stage")
})
public class ActorCharacter {

    /** 主键（自增；即统一身份 {@code npc:{id}} 的数字部分） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 类型：special 特殊型 / common 普通型（仅路由与展示属性，不再表示表归属） */
    @Column(length = 10, nullable = false)
    private String type = "special";

    /** 角色姓名 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 头衔（普通型可为空） */
    @Column(length = 50)
    private String title;

    /** 角色详细信息（用户输入 / AI 生成的原始档案文本；11 维建档的锚定输入之一） */
    @Column(columnDefinition = "LONGTEXT")
    private String detail;

    /** 头像 URL */
    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    // ==================== 原普通型 NPC 并入的结构列（统一检索口径） ====================

    /** 性别（依世界观种族而定） */
    @Column(length = 20)
    private String gender;

    /** 种族（一级，取自字段字典 race.level1） */
    @Column(length = 50)
    private String race;

    /** 次级种族（二级，与种族构成两级：人族→汉族） */
    @Column(name = "sub_race", length = 50)
    private String subRace;

    /** 归属（势力/组织/村落/家族等） */
    @Column(length = 100)
    private String affiliation;

    /** 职业 */
    @Column(length = 50)
    private String occupation;

    // ==================== 档案 / 派生列 ====================

    /**
     * 11 维结构化档案 JSON（契约见 {@code growth/persona_schema_v2.json}）。
     * <p>只承载"无既有模块归属"的维度与字段（②外貌体质/④人格/⑦语言指纹/⑪面具 + ①财产声望/⑤价值权重/
     * ⑥技能/⑨创伤触发物/⑩基因）；权威在既有模块的维度（③生理基线等）<b>不在此复制</b>，
     * 由渲染器经投影从模块取数。</p>
     */
    @Convert(converter = JsonColumnConverter.class)
    @Column(name = "persona_json", columnDefinition = "json")
    private String personaJson;

    /** 档案版本号（对齐 actor_profile_revision.version） */
    @Column(name = "persona_version")
    private Integer personaVersion = 1;

    /** 年龄（游戏年；由 birth_game_second 派生，日级刷新，展示与检索用） */
    private Integer age;

    /** 出生时刻 */
    @Column(name = "birth_game_second")
    private Long birthGameSecond;

    /** 当前发育阶段码 */
    @Column(length = 16)
    private String stage;

    /** 认知 LOD 档位：dormant / ambient / focus */
    @Column(name = "brain_tier", length = 16)
    private String brainTier;

    // ==================== 状态与审计列（沿用） ====================

    /** 是否主角：0否/1是 */
    @Column(name = "is_protagonist")
    private Integer isProtagonist = 0;

    /** 重要度（1-5，决定 AI 投入） */
    private Integer importance = 1;

    /** 状态：0草稿/1正常 */
    private Integer status = 0;

    /** 软删除标记 */
    private Integer deleted = 0;

    /** 当前行动描述（行动引擎模拟执行后更新；沙盘/Unity/前端高频读的缓存列） */
    @Column(name = "current_activity", length = 255)
    private String currentActivity;

    /** 当前位置（行动引擎模拟执行后更新） */
    @Column(length = 255)
    private String location;

    /** 归一化地点 ID（：引用 actor_world_location.id；写入时按名称匹配，未命中保留 null） */
    @Column(name = "location_id")
    private Long locationId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 是否普通型 NPC（type=common）。
     *
     * @return true=普通型
     */
    @Transient
    public boolean isCommon() {
        return "common".equalsIgnoreCase(type);
    }

    /**
     * 是否特殊型 NPC（type=special）。
     *
     * @return true=特殊型
     */
    @Transient
    public boolean isSpecial() {
        return !isCommon();
    }

    /**
     * 统一身份引用。
     *
     * @return {@code npc:{id}}；id 为空时返回 null
     */
    @Transient
    public String npcRef() {
        return id == null ? null : "npc:" + id;
    }

    /**
     * 是否已故（stage=DECEASED）。
     * <p><b>长跑测试抓修</b>：已故角色（雷大壮，山匪袭村重伤不治）仍在参与世界
     * 运行——每小时决策/即时决策/环境刺激/独处判定/链式反应/冲突仲裁各入口用
     * {@code findByProjectIdAndDeletedOrderByIdAsc} 取名单<b>只过滤 deleted、不过滤 DECEASED</b>，
     * 产出「死人煮粥」式荒谬行动并浪费 AI 预算。本方法为统一判定入口：调度/决策名单一律
     *
     * @return true=已故（不再参与世界运行，仅存于他人记忆/关系/编年史）
     */
    @Transient
    public boolean isDeceased() {
        return "DECEASED".equalsIgnoreCase(stage);
    }

    /**
     * 插入前回调：填充时间与默认值。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (type == null) type = "special";
        if (isProtagonist == null) isProtagonist = 0;
        if (importance == null) importance = 1;
        if (status == null) status = 0;
        if (deleted == null) deleted = 0;
        if (personaVersion == null) personaVersion = 1;
    }

    /**
     * 前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}