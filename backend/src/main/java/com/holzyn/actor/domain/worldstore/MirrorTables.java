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
import java.util.Set;

/**
 * 全局只读镜像表清单（12 张，{@code worlds/<uid>/world.mv.db} 内由目录库覆盖写入）。
 *
 * <p>为什么是这 12 张：这 12 张表是"世界库要独立可读"所必需的全局数据——实体映射在两边
 * 都存在，世界库缺了它们，任何一条按 {@code userId}/{@code projectId} 关联的查询都会在
 * 挂载后返回空。其余 101 张都是世界自己的数据。</p>
 *
 * <p>为什么清单要硬编码在这里而不是每次读 JSON：运行期读 JSON 多一个启动失败点
 * （文件丢失/被改坏），而"表清单变了"这件事必须是代码变更 + 测试变更，不能靠改数据文件
 * 悄悄生效。{@code WorldMirrorContractTest} 会把它与生成器产出的
 * {@code db/worldsplit-tables.json} 逐项比对，两边不一致即红。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public final class MirrorTables {

    /** {@code actor_project}：世界库只镜像"自己那一行"，不是整表 */
    public static final String OWN_ROW_TABLE = "actor_project";

    /**
     * 12 张镜像表（顺序即同步顺序：先全局字典/账户，再双性质配置，最后 actor_project）。
     *
     * <p>顺序有实际意义：同步中途失败时，先写的字典类表至少是完整的，不会出现
     * "actor_project 已有行、但它引用的 model_provider 还没有"的半截状态。</p>
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
            OWN_ROW_TABLE);

    /** 集合视图（判成员用） */
    public static final Set<String> SET = Set.copyOf(ALL);

    private MirrorTables() {
    }

    /**
     * 是否镜像表。
     *
     * @param table 表名（大小写不敏感）
     *
     * @return true = 该表在世界库内只读
     */
    public static boolean isMirror(String table) {
        return table != null && SET.contains(table.toLowerCase(java.util.Locale.ROOT));
    }
}
