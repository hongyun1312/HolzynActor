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

package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.domain.growth.persona.PersonaJson;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 人格偏移评估 AI 输出解析器。
 * <p><b>输出契约</b>：</p>
 * <pre>
 * "shifts": [
 * ],
 * "summary": "一句话总评（30 字内）"
 * </pre>
 * <p><b>宽容解析</b>：允许 Markdown 围栏包裹、允许字段缺失
 * （dim/field/delta 缺省跳过该条）、delta 钳制 [-1,1]；解析失败返回空列表（调用方程序化兜底，
 * 零阻断）。</p>
 */
@Slf4j
public final class ShiftParse {

    /** OCEAN 合法字段（④ 维）。 */
    public static final Set<String> OCEAN_FIELDS = Set.of(
            "openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism");

    private ShiftParse() {
    }

    /** 解析后的单条偏移。 */
    public record ShiftItem(String dim, String field, double delta, String why) {
    }

    /**
     * 解析 AI 输出（宽松；失败返回空列表）。
     *
     * @param content AI 输出文本
     *
     * @return 偏移条目列表（已过滤非法 dim/field；delta 钳制 [-1,1]）
     */
    public static List<ShiftItem> parse(String content) {
        List<ShiftItem> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            // Markdown 围栏宽容：剥离 ```json ... ```
            String json = content.trim();
            if (json.startsWith("```")) {
                int first = json.indexOf('\n');
                int last = json.lastIndexOf("```");
                if (first > 0 && last > first) {
                    json = json.substring(first + 1, last).trim();
                }
            }
            var root = PersonaJson.mapper().readTree(json);
            var shifts = root.path("shifts");
            if (!shifts.isArray()) {
                return out;
            }
            for (var item : shifts) {
                try {
                    String dim = item.path("dim").asText(null);
                    String field = item.path("field").asText(null);
                    double delta = item.path("delta").asDouble(0);
                    String why = item.path("why").asText(null);
                    if (dim == null || field == null || field.isBlank()) {
                        continue;
                    }
                    dim = dim.trim().toLowerCase();
                    field = field.trim();
                    if (!isValid(dim, field)) {
                        continue;
                    }
                    delta = Math.max(-1, Math.min(1, delta));
                    if (Math.abs(delta) < 0.005) {
                        continue; // 噪声偏移忽略
                    }
                    out.add(new ShiftItem(dim, field, delta, why));
                } catch (Exception ignored) {
                    // 单条失败跳过
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[偏移解析] AI 输出解析失败（程序化兜底）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 维度/字段合法性（ocean 五键 / values 任意键[⑤ 维 valueWeights 键名自由]）。 */
    private static boolean isValid(String dim, String field) {
        return switch (dim) {
            case "ocean" -> OCEAN_FIELDS.contains(field);
            case "values" -> !field.isBlank();
            default -> false;
        };
    }
}
