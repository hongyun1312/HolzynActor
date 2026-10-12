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

package com.holzyn.actor.common;

import java.util.Set;

/**
 * 普通型 NPC「标准字段字典」的分类字段口径（<b>唯一口径</b>）。
 * <p>分类字段只能从 种族/归属/职业 三者中选（分类体系重构决策：
 * 不再维护独立的 AI 职业分类体系，改为从这三个标准字段里挑主/次两个做聚合）。</p>
 * <p>阶段④收口：该白名单原先只写在 {@code OrdinaryNpcService} 内部，
 * <b>导入</b>路径（{@code HolzynImportService}）把外部包的 {@code field}/
 * {@code primaryField}/{@code secondaryField} 原样落库，既可超长（列宽 30）
 * 也可写入白名单外的字段名，让"人群分类"在导入后静默失效。
 * 现把归一化与列宽约束收敛到本类，三条写入路径共用。</p>
 */
public final class NpcClassifyFields {

    /** 种族字段 */
    public static final String RACE = "race";
    /** 归属字段 */
    public static final String AFFILIATION = "affiliation";
    /** 职业字段 */
    public static final String OCCUPATION = "occupation";

    /** 允许的分类字段集合 */
    public static final Set<String> ALL = Set.of(RACE, AFFILIATION, OCCUPATION);

    /** 字段名列宽上限（{@code actor_field_dict.field} / {@code actor_crowd_runtime.primary_field} 等） */
    public static final int FIELD_MAX = 30;
    /** 一级值列宽上限（{@code actor_field_dict.level1}） */
    public static final int LEVEL1_MAX = 50;
    /** 二级值列宽上限（{@code actor_field_dict.level2}，仅 race 使用） */
    public static final int LEVEL2_MAX = 50;
    /** 出处说明列宽上限（{@code actor_field_dict.source}，文本列放宽） */
    public static final int SOURCE_MAX = 1000;

    private NpcClassifyFields() {
    }

    /**
     * 字段名归一：trim + 小写，不在白名单内返回 null。
     *
     * @param field 原始字段名（可空）
     *
     * @return {@code race}/{@code affiliation}/{@code occupation} 之一；非法返回 null
     */
    public static String normalize(String field) {
        if (field == null) {
            return null;
        }
        String f = field.trim().toLowerCase();
        return ALL.contains(f) ? f : null;
    }
}