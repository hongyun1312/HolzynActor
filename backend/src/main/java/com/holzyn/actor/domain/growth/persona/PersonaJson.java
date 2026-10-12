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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 档案 JSON 编解码。
 * <p>职责：为 11 维档案提供<b>唯一</b>的 Jackson 口径——Java 侧 camelCase 字段 ↔ JSON 侧 snake_case 键
 * （与 {@code growth/persona_schema_v2.json} 及基准夹具完全一致）。</p>
 * <p><b>为何不直接用全局 ObjectMapper</b>：全局 mapper 的命名策略服务其它领域（多为 camelCase），
 * 档案契约要求 snake_case（与既有 {@code possible_states}/{@code hard_gate_json} 等 JSON 列一致）；
 * 独立口径可避免"改全局策略 → 别处 JSON 列集体变更"的连锁风险。</p>
 * <p><b>Jackson 口径对齐</b>：项目统一使用 Jackson 3（{@code tools.jackson}，101 个文件）；
 * 本类同样走 Jackson 3 的 {@link JsonMapper}，注解仍用 {@code com.fasterxml.jackson.annotation.*}
 * （Jackson 3 的注解包未迁移）。Jackson 3 默认不因未知属性报错，故无需额外关闭。</p>
 */
public final class PersonaJson {

    /** 档案专用 mapper（snake_case 命名策略；空值控制由模型类上的 {@code @JsonInclude} 承担）。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private PersonaJson() {
    }

    /**
     * 取档案专用 mapper（只读使用；禁止修改其配置）。
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 反序列化档案 JSON。
     *
     * @param json JSON 文本（可空）
     *
     * @return 档案；json 为空返回 null
     *
     * @throws IllegalArgumentException JSON 非法时抛出（调用方按"零阻断"降级处理）
     */
    public static PersonaProfile read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, PersonaProfile.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("档案 JSON 解析失败：" + e.getOriginalMessage(), e);
        }
    }

    /**
     * 序列化档案（落库/审计/few-shot 输出用）。
     *
     * @param profile 档案（可空）
     *
     * @return JSON 文本；profile 为空返回 null
     */
    public static String write(PersonaProfile profile) {
        if (profile == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(profile);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("档案 JSON 序列化失败：" + e.getOriginalMessage(), e);
        }
    }

    /**
     * 宽松解析：失败返回 null 而不抛异常（用于运行期读取历史档案，绝不因脏数据阻断世界运行）。
     *
     * @param json JSON 文本
     *
     * @return 档案或 null
     */
    public static PersonaProfile readQuietly(String json) {
        try {
            return read(json);
        } catch (RuntimeException e) {
            return null;
        }
    }
}