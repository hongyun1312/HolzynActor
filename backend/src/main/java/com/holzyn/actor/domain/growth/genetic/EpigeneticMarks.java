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

package com.holzyn.actor.domain.growth.genetic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表观修饰因子数据模型。
 * <p><b></b>：双源（父母 L3 核心记忆 + 世界事件，importance≥4，孕前
 * 90 游戏日窗口）+ 单代 + 留痕（本 JSON 即审计留痕）+ 出生到幼儿期末线性衰减。</p>
 * <p><b>四类事件修正（对齐参考文档 §4.3）</b>：</p>
 * <ul>
 * <li>创伤/战乱（war/trauma/violence）→ 子代神经质表达 ×1.10~1.30；</li>
 * <li>饥荒（starvation/famine）→ 子代代谢相关特质 ×1.05~1.15；</li>
 * <li>孤立（social_isolation）→ 子代外向性 ×0.85~0.95；</li>
 * <li>丰饶/庆典（abundance/celebration）→ 子代宜人性 ×1.05~1.10。</li>
 * </ul>
 * <p>有效期为出生到幼儿期末（约 7 游戏年），到期后线性衰减至 1.0；本类只承载"印记"本身，
 * 衰减计算在消费方（新生儿档案构建）按 {@code valid_until_game_second} 线性插值。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EpigeneticMarks {

    /** 修饰因子列表（空数组=无表观印记） */
    private List<Mark> marks = new ArrayList<>();

    /** 单条修饰印记。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Mark {
        /** 目标特征键（OCEAN_NEUROTICISM / OCEAN_EXTRAVERSION / OCEAN_AGREEABLENESS / METABOLISM） */
        private String target;
        /** 表达因子（>1 上调，<1 下调；消费方与 1.0 差值的线性衰减） */
        private Double factor;
        /** 来源事件类型（trauma_war / famine / social_isolation / abundance） */
        @JsonProperty("source_event")
        private String sourceEvent;
        /** 来源事件描述（人类可读，审计用） */
        @JsonProperty("source_desc")
        private String sourceDesc;
        /** 印记到期时刻（游戏秒；出生时 = 出生 + 7 游戏年） */
        @JsonProperty("valid_until_game_second")
        private Long validUntilGameSecond;
        /** 印记生效起点（出生时刻） */
        @JsonProperty("issued_game_second")
        private Long issuedGameSecond;

        public static Mark of(String target, double factor, String sourceEvent, String sourceDesc,
                              Long issuedGameSecond, Long validUntilGameSecond) {
            Mark m = new Mark();
            m.setTarget(target);
            m.setFactor(factor);
            m.setSourceEvent(sourceEvent);
            m.setSourceDesc(sourceDesc);
            m.setIssuedGameSecond(issuedGameSecond);
            m.setValidUntilGameSecond(validUntilGameSecond);
            return m;
        }
    }

    /** 便捷构造：空印记集。 */
    public static EpigeneticMarks empty() {
        return new EpigeneticMarks();
    }
}
