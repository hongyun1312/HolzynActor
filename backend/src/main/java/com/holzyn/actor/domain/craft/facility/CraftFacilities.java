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

package com.holzyn.actor.domain.craft.facility;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设施类型匹配（R-CRAFT-01「设施可用」的唯一判定入口）。
 *
 * <p><b>为什么需要同义词表</b>：配方写的是「铁匠炉」，而世界里的设施事实可能叫
 * 「铁匠铺」「张家锻炉」——世界是 AI 生成的中文语境，名字不会和配方一字不差。
 * 若要求逐字相等，规则会把「明明有炉子」判成 {@code NO_FACILITY}，这是最容易让人
 * 觉得世界「不真实」的一类误判。这里只做**确定性**的别名包含匹配，不调 AI、不猜。</p>
 *
 * <p>口径只有这一处：规则层与结算层都调 {@link #matches}，不允许各写一份包含判断
 * （AGENTS §8.1.6 单一判定入口）。</p>
 */
public final class CraftFacilities {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 设施类型 → 同义词（全部小写；中文不分大小写，英文统一小写比较）。
     *
     * <p>刻意保持短：别名越多越容易误匹配（「炉」能匹配到「香炉」）。只收本项目世界里
     * 真实会出现的说法，新增靠这一处扩展。</p>
     */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "铁匠炉", List.of("铁匠炉", "铁匠铺", "锻炉", "铁炉", "forge", "smithy"),
            "灶台", List.of("灶台", "厨房", "灶", "厨", "kitchen", "hearth"),
            "木工台", List.of("木工台", "木工房", "作坊", "工坊", "workshop", "workbench"),
            "石磨", List.of("石磨", "磨坊", "磨盘", "mill"));

    private CraftFacilities() {
    }

    /**
     * 从设施事实的 {@code attributes_json} 里取类型（兼容 type / facilityType / facility_type）。
     *
     * @param attributesJson 事实属性 JSON（可空）
     *
     * @return 类型；取不到返回 null
     */
    public static String factTypeOf(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode attrs = MAPPER.readTree(attributesJson);
            for (String field : List.of("type", "facilityType", "facility_type", "kind")) {
                JsonNode node = attrs.get(field);
                if (node != null && !node.isNull()) {
                    String value = node.asText("").trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * 设施是否满足配方的类型要求。
     *
     * @param requiredType 配方要求的类型（空=随处可做，恒满足）
     * @param factType     设施事实上的类型（可空）
     * @param facilityName 设施/地点名（可空）
     *
     * @return true=满足
     */
    public static boolean matches(String requiredType, String factType, String facilityName) {
        if (requiredType == null || requiredType.isBlank()) {
            return true;
        }
        String required = normalize(requiredType);
        if (required.isEmpty()) {
            return true;
        }
        if (factType != null && required.equals(normalize(factType))) {
            return true;
        }
        String name = normalize(facilityName);
        for (String alias : aliasesOf(required)) {
            if (name.contains(alias)) {
                return true;
            }
            if (factType != null && normalize(factType).contains(alias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某类型的同义词表（含类型自身）。
     *
     * @param requiredType 类型
     *
     * @return 同义词（小写）；未知类型返回只含自身的单元素列表
     */
    public static List<String> aliasesOf(String requiredType) {
        String required = normalize(requiredType);
        List<String> aliases = ALIASES.get(required);
        if (aliases != null) {
            return aliases;
        }
        for (Map.Entry<String, List<String>> entry : ALIASES.entrySet()) {
            if (entry.getValue().contains(required)) {
                return entry.getValue();
            }
        }
        return List.of(required);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
