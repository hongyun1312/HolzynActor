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

package com.holzyn.actor.domain.project.model;

import java.util.Optional;

/**
 * 世界初始化步骤定义（顺序即执行顺序，唯一权威）。
 *
 * <p>为什么要有这个枚举：初始化流程从「8 步一次跑完、失败静默跳过」改为
 * 「按固定顺序逐步执行 + 严格门禁」后，"第几步是什么、是否必需、能不能跳过"
 * 必须只有一处定义——否则前端步骤条、后端门禁、状态表编码三处各写一份，
 * 加一步就要改三处，且漏改不会被任何测试发现。</p>
 *
 * <p><b>必需 vs 可选</b>：必需步骤（世界观设定 / 地点 / 角色卡 / 普通 NPC / 关系拓扑 /
 * 世界时间）决定「这个世界能不能跑起来」，缺一个世界就是空壳，故未完成即判「未初始化完成」；
 * 可选步骤（家族与基因 / 世界地图与住所 / 宏观模板 / 知识向量化）缺了世界仍可运行，
 * 未完成只标「降级」，不阻塞流程，且允许用户显式跳过。</p>
 *
 * <p><b>顺序约束（硬门禁）</b>：执行第 N 步前，所有 {@code index < N} 的<b>必需</b>步骤
 * 必须已完成（状态 DONE，或实时数据已满足）。可选步骤不参与门禁——否则用户想跳过
 * 「家族与基因」就永远走不到「世界时间」，那不叫严格，叫卡死。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 */
public enum WorldInitStep {

    /** ① 世界观设定：世界运行的全部内容都从它派生，必须最先完成 */
    WORLDVIEW("worldview", "世界观设定", true, 1,
            "编辑并保存世界观设定（核心字段与自由文本）。这是后续所有步骤的唯一输入源。"),

    /** ② 世界观地点：从地理设定提取结构化地点（地图、住所、活动都依赖它） */
    LOCATIONS("locations", "世界观地点", true, 2,
            "根据世界观「地理设定」由 AI 提取结构化地点并入库（需要先完成第 1 步）。"),

    /** ③ 角色卡：主要角色的结构化档案（11 维）与 Prompt */
    CHARACTERS("characters", "角色卡", true, 3,
            "为项目内角色生成结构化角色档案（11 维）。没有角色时请先在角色页创建角色。"),

    /** ④ 普通 NPC：字段字典 + 若干普通居民 */
    NPCS("npcs", "普通 NPC", true, 4,
            "生成普通型 NPC 的字段字典，再生成普通居民（需要先有地点与角色卡）。"),

    /** ⑤ 关系拓扑：全局关系网络（家族聚类与决策都依赖它） */
    RELATIONS("relations", "关系拓扑", true, 5,
            "生成全局关系拓扑并入库（同族/合作等关系是家族与决策的输入）。"),

    /** ⑥ 家族与基因：婚姻/亲子边与基因建档（可选） */
    FAMILY("family", "家族与基因", false, 6,
            "补齐性别/年龄/阶段，造婚姻与亲子关系边，并做基因建档（可选，缺了不影响世界运行）。"),

    /** ⑦ 世界地图与住所：地图生成 + 同居共宅分配（可选，机制兜底） */
    WORLD_MAP("world_map", "世界地图与住所", false, 7,
            "生成世界地图区域与角色住所（可选；AI 不可用时自动走机制兜底）。"),

    /** ⑧ 世界时间：由历史脉络推断当前历时间（必需，时钟是引擎推进的起点） */
    WORLD_TIME("world_time", "世界时间", true, 8,
            "根据历史脉络与时代背景推断当前世界历时间并设置（不从零开始）。"),

    /** ⑨ 宏观社会模板：文明形态×力量体系推导（可选） */
    MACRO("macro", "宏观社会模板", false, 9,
            "推导宏观社会模板并落 init 快照（可选；仪表盘宏观视图依赖它）。"),

    /** ⑩ 知识向量化：世界设定与人物知识卡入向量库（可选，需已配置向量服务） */
    KNOWLEDGE("knowledge", "知识向量化", false, 10,
            "把世界观设定与角色知识卡同步为知识文档并向量化（可选；未配置向量服务会自动降级）。");

    /** 步骤编码（落库 actor_world_init_step.step_code，前后端接口共用） */
    private final String code;

    /** 步骤中文名（向导步骤条与日志显示） */
    private final String label;

    /** 是否必需步骤（true=未完成即判定「世界未初始化完成」） */
    private final boolean required;

    /** 执行顺序（1 开始；严格递增，决定门禁与展示顺序） */
    private final int index;

    /** 步骤说明（向导当前步面板的引导文案） */
    private final String desc;

    WorldInitStep(String code, String label, boolean required, int index, String desc) {
        this.code = code;
        this.label = label;
        this.required = required;
        this.index = index;
        this.desc = desc;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean required() {
        return required;
    }

    public int index() {
        return index;
    }

    public String desc() {
        return desc;
    }

    /**
     * 按编码解析步骤。
     *
     * @param code 步骤编码（大小写不敏感，允许首尾空白）
     *
     * @return 命中的步骤（未知编码返回空）
     */
    public static Optional<WorldInitStep> of(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(java.util.Locale.ROOT);
        for (WorldInitStep step : values()) {
            if (step.code.equals(normalized)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /** 步骤总数（前端进度分母与状态快照 init_total 同源） */
    public static int total() {
        return values().length;
    }
}
