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

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案渲染上下文 · 模块投影输入。
 * <p>职责：把「权威在既有模块、档案里允许缺省」的那些字段，在渲染时喂给 {@link PersonaRenderer}——
 * 这是"档案消费既有模块数据"的落点。渲染规则：<b>档案内有值优先，缺失则回退本上下文</b>。</p>
 * <p><b>字段来源（阶段②由 PersonaProjectionService 统一装配）</b>：</p>
 * <ul>
 * current_activity/birth_game_second/stage）；</li>
 * <li>谱系：{@code actor_relation}（relation_type 扩展后的 PARENT_CHILD/MARRIAGE/SIBLING）；</li>
 * <li>知识：{@code actor_knowledge_doc} + RAG 向量库（knowledge_domains/blind_spots）；</li>
 * <li>日程：计划模块（plan_day / daily_plan_json）；</li>
 * <li>派生（DER）：机能曲线、死亡风险、阶段中文名。</li>
 * </ul>
 */
@Data
public class PersonaRenderContext {

    // ---------- ① 身份与社会 · actor_profile 结构列 ----------

    /** 姓名（主表列） */
    private String name;
    /** 性别 */
    private String gender;
    /** 种族（一级） */
    private String race;
    /** 次级种族（二级） */
    private String subRace;
    /** 头衔（主表列；档案 identityTitle 缺失时回退） */
    private String title;
    /** 归属（势力/宗族/村落） */
    private String affiliation;
    /** 职业 */
    private String occupation;
    /** 当前所在地 */
    private String location;
    /** 当前行动描述（主表列；S1「当前处境」用） */
    private String currentActivity;
    /** 年龄（游戏年；由出生时刻派生，展示用） */
    private Integer ageYears;
    /** 出生标签（如「青禾历前 62 年 3 月 11 日（今 62 岁）」；日历服务派生） */
    private String birthLabel;

    // ---------- ⑩ 发育·遗传·谱系 · 主表列 + actor_relation + DER ----------

    /** 阶段中文名（如「老年期（ELDER）」； DevelopmentalStageService 派生） */
    private String stageLabel;
    /** 阶段进入时刻标签（如「青禾历 53 年 1 月（60 岁）」） */
    private String stageEnteredLabel;
    /** 当前机能比 0-1（DER：机能曲线） */
    private Double capacityRatio;
    /** 机能峰值年龄（DER） */
    private Double peakAge;
    /** 基线年死亡率（DER） */
    private Double deathRiskAnnual;
    /** 谱系投影（权威 actor_relation）：father/mother/spouse/children/siblings/grandchildren → 描述文本 */
    private Map<String, Object> lineage = new LinkedHashMap<>();

    // ---------- ⑤ 价值观与动机 · actor_goal ----------

    /** 长期目标（actor_goal priority=2 投影） */
    private List<String> lifeGoals = new ArrayList<>();
    /** 中期目标（actor_goal priority=4 投影） */
    private List<String> currentGoals = new ArrayList<>();

    // ---------- ⑥ 能力与知识 · actor_knowledge_doc + 向量库 ----------

    /** 知识领域（知识模块投影） */
    private List<String> knowledgeDomains = new ArrayList<>();
    /** 知识盲区（知识模块投影） */
    private List<String> knowledgeBlindSpots = new ArrayList<>();

    // ---------- ⑧ 习惯与日常 · 计划模块 ----------

    /** 当前日程（计划模块投影；档案 habits.schedule 缺失时回退） */
    private List<String> schedule = new ArrayList<>();

    // ---------- ③ 生理基线 · 权威在 NPC 状态模块（actor_state.physio_json） ----------

    /**
     * 生理基线投影（③ 维）。
     * <p><b>这是"生理基线不落档案"的实现</b>：权威是 {@code actor_state.physio_json}（状态模块参数），
     * 渲染时由 {@code PersonaProjectionService} 读入本字段；{@code persona_json.dims.physio} 通常缺省，
     * 渲染器优先取档案、缺失则用本投影——因此档案里<b>不会</b>出现第二份生理基线。</p>
     */
    private PersonaDims.Physio physio;

    // ---------- ⑪ 社会面具 · 当前情境 ----------

    /**
     * 当前情境码列表（如 {@code ["before_authority","public_place"]}）。
     * <p>由在场者与地点推导；为空时 S8 分片退化为
     * 「抑制强度最高的 3 个情境 + 破绽」，避免把 8 个情境全塞进 Prompt。</p>
     */
    private List<String> maskContexts = new ArrayList<>();

    // ---------- 附加说明（审计/调试） ----------

    /** 额外投影（键为维键或自由键；渲染器不直接消费，供调试页展示投影来源） */
    private Map<String, Object> extra = new LinkedHashMap<>();

    /**
     * 便捷工厂：只填最常用的结构列（单测与调试用）。
     *
     * @param name 姓名
     *
     * @param gender 性别
     *
     * @param race 种族
     *
     * @return 上下文
     */
    public static PersonaRenderContext of(String name, String gender, String race) {
        PersonaRenderContext c = new PersonaRenderContext();
        c.setName(name);
        c.setGender(gender);
        c.setRace(race);
        return c;
    }

    /** 取档案值优先、上下文回退（渲染器统一口径）。 */
    static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    /** 取列表优先、上下文回退（空列表视为缺失）。 */
    static List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        return fallback == null ? List.of() : fallback;
    }
}