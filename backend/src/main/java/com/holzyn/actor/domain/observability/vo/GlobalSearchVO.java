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

package com.holzyn.actor.domain.observability.vo;

import java.util.List;

/**
 * 全局搜索结果视图（只读）。
 *
 * <p>职责：把「角色 / 地点 / 事件 / 知识 / 项目」五类实体的关键词命中收拢为一个
 * 分组容器，供前端全局搜索面板直接渲染并跳转。</p>
 *
 * <p>与前端本地索引的关系：前端用 fuse.js 对**已加载列表**做模糊匹配（零延迟、可离线），
 * 本端点作为**服务端兜底**——覆盖前端未加载的分页数据、并支持按类型过滤与统一排序；
 * 两者结果在前端合并去重（同 type+id 以服务端为准）。</p>
 *
 * @param projectId 项目 ID
 * @param q         原始查询串（已 trim）
 * @param types     实际检索的类型集合
 * @param total     命中总数（分组内 items 之和）
 * @param truncated 是否因 limit 截断（true 表示还有未返回的命中）
 * @param groups    分组结果（按 {@link HitType} 声明顺序，空分组不返回）
 */
public record GlobalSearchVO(
        Long projectId,
        String q,
        List<String> types,
        int total,
        boolean truncated,
        List<Group> groups
) {

    /**
     * 命中类型。
     *
     * <p>name() 即接口约定的 type 字面量（character / location / event / knowledge / project），
     * label 为界面中文名。</p>
     */
    public enum HitType {

        /** 角色（特殊 NPC / 普通人群人物卡） */
        CHARACTER("character", "角色"),
        /** 世界地点 */
        LOCATION("location", "地点"),
        /** 世界事件 / 角色行动 / 记忆里程碑 */
        EVENT("event", "事件"),
        /** 知识库文档 */
        KNOWLEDGE("knowledge", "知识"),
        /** 项目 */
        PROJECT("project", "项目");

        private final String code;
        private final String label;

        HitType(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /** 接口类型字面量 */
        public String code() {
            return code;
        }

        /** 界面中文名 */
        public String label() {
            return label;
        }

        /**
         * 把接口字面量解析为枚举（忽略大小写与空白）。
         *
         * @param raw 字面量
         *
         * @return 枚举；无法识别时返回 null（调用方跳过该类型而不是整体报错）
         */
        public static HitType parse(String raw) {
            if (raw == null) {
                return null;
            }
            String v = raw.trim().toLowerCase();
            for (HitType t : values()) {
                if (t.code.equals(v)) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * 一组命中。
     *
     * @param type  类型字面量
     * @param label 中文名
     * @param count 本组命中数
     * @param items 命中项（按相关度/时间倒序）
     */
    public record Group(String type, String label, int count, List<Hit> items) {
    }

    /**
     * 单条命中。
     *
     * @param type    类型字面量
     * @param id      实体 ID
     * @param title   标题（命中主体）
     * @param snippet 摘要片段（命中字段的截断文本，可为空）
     * @param meta    元信息（如角色类型 / 事件时间 / 地点类型，可为空）
     * @param route   前端跳转路径（项目内既有深链，不含 query）
     */
    public record Hit(String type, Long id, String title, String snippet, String meta, String route) {
    }
}
