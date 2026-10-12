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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基因位点集数据模型。
 * <p>结构（对齐参考文档 §4.1 {@code gene_loci_json}）：</p>
 * <ul>
 * <li>{@code loci}：15 个位点的等位基因对（每个位点固定 2 个等位基因，孟德尔重组输入）；</li>
 * <li>{@code derived_traits}：由位点效应值派生的一次性特征（OCEAN 五维 + TEMPO/LEARN/APTITUDE 五维，
 * {@code MendelianRecombinator#computeTraits} 产出）——OCEAN 五维即遗传度框架的 midparent 输入。</li>
 * </ul>
 * <p>序列化口径与 {@code PersonaJson} 同款 snake_case（项目内 JSON 列统一约定）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenomeData {

    /** 15 个位点等位基因对 */
    private List<Locus> loci = new ArrayList<>();

    /** 派生特征（OCEAN_OPENNESS… / TEMPO_AROUSAL…，0~1） */
    @JsonProperty("derived_traits")
    private Map<String, Double> derivedTraits = new LinkedHashMap<>();

    /** 单个位点（等位基因对）。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Locus {
        /** 位点 ID（OCEAN_O_1…） */
        @JsonProperty("locus_id")
        private String locusId;
        /** 两个等位基因（如 ["A1","A3"]） */
        private List<String> alleles = new ArrayList<>();
        /** 效应类型：additive / dominant（冗余，便于展示与审计） */
        private String effect;

        public static Locus of(String locusId, String a, String b) {
            Locus l = new Locus();
            l.setLocusId(locusId);
            l.setAlleles(new ArrayList<>(List.of(a, b)));
            GeneticLoci.LocusDef def = GeneticLoci.of(locusId);
            l.setEffect(def == null ? GeneticLoci.EFFECT_ADDITIVE : def.effect());
            return l;
        }
    }

    /**
     * 从父母双方各自取一个等位基因拼出的新位点（孟德尔重组产物）。
     *
     * @param locusId 位点 ID
     *
     * @param fromA 父源等位基因
     *
     * @param fromB 母源等位基因
     *
     * @return 新位点
     */
    public static Locus childLocus(String locusId, String fromA, String fromB) {
        return Locus.of(locusId, fromA, fromB);
    }

    /** 新建空基因组（随机初始化请走 {@link MendelianRecombinator#randomGenome}）。 */
    public static GenomeData empty() {
        return new GenomeData();
    }
}
