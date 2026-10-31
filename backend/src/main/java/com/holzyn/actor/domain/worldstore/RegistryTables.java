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

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 目录库（registry）拥有的 16 张表（V1.1.2-beta.1）。
 *
 * <p>= 12 张全局/双性质镜像（{@link MirrorTables}）+ 3 张观测日志 + 1 张 {@code actor_schema_meta}。
 * 世界库的 113 张表是它的超集（世界库要能独立还原一个世界），所以"这张表在目录库里有没有"
 * 不能靠"实体有没有映射"来判断——113 个实体全都映射了。</p>
 *
 * <p><b>这个清单存在的唯一原因</b>：启动期的 {@code DatabaseStructureValidator} 会拿"实体映射"
 * 当期望结构去和"当前库"比对，并对缺失表执行 {@code CREATE TABLE}。切到目录库之后，
 * 它会把 97 张世界表当成"缺失"在目录库里建出来——目录库从此混进世界数据，而分档的意义
 * 就是这两个库永不混杂。因此它必须知道自己只应该校验这 16 张。</p>
 *
 * <p>清单与 {@code db/migration/global/V100} 的一致性由 {@code RegistryTablesContractTest}
 * 守护（解析迁移文件逐表比对）：加表必须同时改迁移与本清单，否则测试红。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public final class RegistryTables {

    /**
     * 目录库 16 张表（顺序仅便于阅读：12 镜像 → 3 观测 → 1 元数据）。
     *
     * <p>与 {@link MirrorTables#ALL} 的 12 张刻意重复列出而不是简单拼接：镜像集合表达的是
     * "要同步哪些表"，本集合表达的是"目录库有哪些表"，两者关注点不同（观测/元数据表在目录库
     * 里但**不**镜像进世界库的只读副本——它们按 {@code project_id} 天然分流）。</p>
     */
    public static final List<String> ALL = List.of(
            "sys_user",
            "actor_local_account",
            "actor_setting",
            "actor_emotion_anchor",
            "actor_growth_stage_constraint",
            "actor_growth_stage_gate",
            "actor_dialogue_config",
            "actor_talk_player_profile",
            "actor_worldgen_workflow",
            "actor_model_provider",
            "actor_prompt_template",
            "actor_project",
            "actor_ai_call_log",
            "actor_usage_log",
            "actor_test_usage_log",
            "actor_schema_meta");

    /** 集合视图（判成员用） */
    public static final Set<String> SET = Set.copyOf(ALL);

    private RegistryTables() {
    }

    /**
     * 是否目录库拥有的表。
     *
     * @param table 表名（大小写不敏感）
     *
     * @return true = 该表属于目录库
     */
    public static boolean isRegistryTable(String table) {
        return table != null && SET.contains(table.toLowerCase(Locale.ROOT));
    }
}
