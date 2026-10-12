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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色统一档案 v2 · 顶层模型。
 * <p>职责：承载一次"11 维规范视图"的完整内容——11 维数据（{@link PersonaDims}）+ 分维来源声明
 * （{@link PersonaSource}，落库分派与写入权限的唯一依据）+ 旧数据留存（{@code _legacy}，仅审计不注入）。</p>
 * <p><b>为什么它是"规范视图"而不是"存储形态"</b>：见 {@link PersonaDims} 类注释。落库由
 * {@code PersonaPersistService}（阶段②）按 {@code sources} 分派到各模块权威位置。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "profileId", "projectId", "personaVersion", "generatedBy", "generatedScene",
        "generatedGameSecond", "dims", "sources", "legacy"})
public class PersonaProfile {

    /** 契约版本（固定 2；演进只允许增量新增可选字段） */
    @JsonProperty("schema_version")
    private Integer schemaVersion = PersonaSchema.SCHEMA_VERSION;

    /** actor_profile.id（统一身份 npc:{id} 的数字部分） */
    @JsonProperty("profile_id")
    private Long profileId;

    /** 归属项目 ID */
    @JsonProperty("project_id")
    private Long projectId;

    /** 档案版本号（对齐 actor_profile_revision.version） */
    @JsonProperty("persona_version")
    private Integer personaVersion = 1;

    /** 生成者：dev / ai_bootstrap / ai_worldgen / ai_migrate / evolution / manual */
    @JsonProperty("generated_by")
    private String generatedBy;

    /** 生成场景码（persona_bootstrap_fill_a/b、character_card_gen、ordinary_npc_gen…） */
    @JsonProperty("generated_scene")
    private String generatedScene;

    /** 生成时的世界时刻（审计） */
    @JsonProperty("generated_game_second")
    private Long generatedGameSecond;

    /** 11 维数据（MODULE/DER 维可缺省，渲染时由 {@link PersonaRenderContext} 补齐） */
    private Dims dims = new Dims();

    /**
     * 分维来源声明（11 键；落库分派与写入权限的唯一依据）。
     * <p><b>键名必须是 {@code _sources}（下划线开头）</b>：契约
     * 《角色档案 Schema 契约与分维来源》§3 与 `persona_schema_v2.json` 均以下划线开头命名
     * 这两个元数据块，且 AI 模板里"照抄"的固定块也是 {@code _sources}。此处显式声明 {@code @JsonProperty}
     * 而不是依赖 SNAKE_CASE 命名策略——策略只会把它变成 {@code sources}，会让 AI 输出的
     * {@code _sources} 声明<b>被静默忽略</b>（历史缺陷：建档服务因此永远回退默认声明并每次告警）。</p>
     */
    @JsonProperty("_sources")
    private Map<String, PersonaSource> sources = new LinkedHashMap<>();

    /**
     * 旧数据原样保留（审计/回溯；不参与注入）。
     * <p>同 {@link #sources}：契约与夹具均用 {@code _legacy}，必须显式声明键名。</p>
     */
    @JsonProperty("_legacy")
    private Legacy legacy;

    /**
     * 11 维数据容器（键名与 JSON Schema 完全一致）。
     * <p>取值为 null 表示该维"未在档案内存储"（来源为 MODULE/DER，或建档尚未覆盖该维），
     * <b>不等同于空维</b>——渲染器会尝试从权威模块投影补齐。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"identity", "appearance", "physio", "personality", "values", "ability", "voice", "habits",
            "experience", "development", "mask"})
    public static class Dims {
        /** ① 身份与社会 */
        private PersonaDims.Identity identity;
        /** ② 外貌与体质 */
        private PersonaDims.Appearance appearance;
        /** ③ 生理基线（权威在 NPC 状态模块） */
        private PersonaDims.Physio physio;
        /** ④ 气质与人格 */
        private PersonaDims.Personality personality;
        /** ⑤ 价值观与动机 */
        private PersonaDims.Values values;
        /** ⑥ 能力与知识 */
        private PersonaDims.Ability ability;
        /** ⑦ 语言与表达 */
        private PersonaDims.Voice voice;
        /** ⑧ 习惯与日常 */
        private PersonaDims.Habits habits;
        /** ⑨ 经历与创伤 */
        private PersonaDims.Experience experience;
        /** ⑩ 发育·遗传·谱系 */
        private PersonaDims.Development development;
        /** ⑪ 社会面具 */
        private PersonaDims.Mask mask;

        /**
         * 按维键取该维对象（渲染器与校验器统一入口，避免散落的 switch）。
         *
         * @param dimKey 维键（{@link PersonaSchema.Dims} 常量）
         *
         * @return 该维对象；未知键返回 null
         */
        public Object byKey(String dimKey) {
            return switch (dimKey == null ? "" : dimKey) {
                case PersonaSchema.Dims.IDENTITY -> identity;
                case PersonaSchema.Dims.APPEARANCE -> appearance;
                case PersonaSchema.Dims.PHYSIO -> physio;
                case PersonaSchema.Dims.PERSONALITY -> personality;
                case PersonaSchema.Dims.VALUES -> values;
                case PersonaSchema.Dims.ABILITY -> ability;
                case PersonaSchema.Dims.VOICE -> voice;
                case PersonaSchema.Dims.HABITS -> habits;
                case PersonaSchema.Dims.EXPERIENCE -> experience;
                case PersonaSchema.Dims.DEVELOPMENT -> development;
                case PersonaSchema.Dims.MASK -> mask;
                default -> null;
            };
        }

        /**
         * 该维是否在档案内存储了内容（null = 未存储）。
         *
         * @param dimKey 维键
         *
         * @return true=档案内有该维对象
         */
        public boolean has(String dimKey) {
            return byKey(dimKey) != null;
        }
    }

    /** 旧数据留存（审计/回溯；不参与注入）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"detail", "cardPersonaJson", "ordinaryDetail", "personaVersion", "anchorText", "legacyAgeNote"})
    public static class Legacy {
        /** 旧 actor_profile.detail / actor_profile_ordinary.detail 原值 */
        private String detail;
        /** 旧 actor_profile_card.persona_json 原值（字符串，未解析） */
        @JsonProperty("card_persona_json")
        private String cardPersonaJson;
        /** 旧 actor_profile_ordinary.detail 原值（与 detail 分列保留，便于迁移审计） */
        @JsonProperty("ordinary_detail")
        private String ordinaryDetail;
        /**
         * 生成本份档案时的档案版本号（AI 两段式建档写入；= 本次落库后 {@code actor_profile.persona_version} 的预估值，
         * 与 {@link PersonaProfile#personaVersion} 同口径，便于回溯"第几版是谁生成的"）。
         */
        @JsonProperty("persona_version")
        private Integer personaVersion;
        /**
         * 本次生成的锚定文本（开发者已写的角色 detail / 调用方显式传入的 anchorText）。
         * <p>作用：回答"这份档案是基于什么生成的"——AI 建档的内容争议、漂移排查，都从这段原文回溯。</p>
         */
        @JsonProperty("anchor_text")
        private String anchorText;
        /** 迁移备注（如年龄/OCEAN 取值来源差异） */
        @JsonProperty("legacy_age_note")
        private String legacyAgeNote;
    }

    /**
     * 新建一份空的 11 维视图（建档起点；各维按需填充）。
     *
     * @param projectId 项目 ID
     *
     * @param profileId 角色主表 ID
     *
     * @return 空档案（schemaVersion=2、personaVersion=1、sources 为默认声明）
     */
    public static PersonaProfile empty(Long projectId, Long profileId) {
        PersonaProfile p = new PersonaProfile();
        p.setProjectId(projectId);
        p.setProfileId(profileId);
        p.setSources(PersonaSchema.defaultSources());
        return p;
    }

    /**
     * 分维来源声明（{@code _sources} 单条）。
     * <p>声明某一维的权威归属、字段分派、参与分片、允许写入者与消费方——是"档案不再独立"的制度化表达：
     * 写入方被限定（③ 生理基线只能由建档与阶段跨越写），消费即投影（MODULE 维渲染时向模块取数）。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"kind", "module", "fieldsModule", "fieldsPersona", "shards", "writers", "consumers"})
    public static class PersonaSource {
        /** 来源类型：MODULE / PERSONA / DER / MIXED */
        private String kind;
        /** 权威模块/表名（无既有模块时为空串） */
        private String module;
        /** 该维中归属模块的字段名 */
        @JsonProperty("fields_module")
        private List<String> fieldsModule = new ArrayList<>();
        /** 该维中落档案的字段名 */
        @JsonProperty("fields_persona")
        private List<String> fieldsPersona = new ArrayList<>();
        /** 该维参与哪些注入分片（S1~S11） */
        private List<String> shards = new ArrayList<>();
        /** 允许写入该维的主体 */
        private List<String> writers = new ArrayList<>();
        /** 消费方 */
        private List<String> consumers = new ArrayList<>();

        /** 便捷构造。 */
        public static PersonaSource of(String kind, String module, List<String> shards) {
            PersonaSource s = new PersonaSource();
            s.setKind(kind);
            s.setModule(module);
            s.setShards(shards == null ? new ArrayList<>() : new ArrayList<>(shards));
            return s;
        }

        /** 是否权威在既有模块（渲染时需投影）。 */
        public boolean moduleBacked() {
            return "MODULE".equals(kind) || "MIXED".equals(kind);
        }
    }
}