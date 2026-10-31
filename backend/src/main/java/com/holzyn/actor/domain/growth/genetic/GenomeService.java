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

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 基因档案唯一写入口（G4 遗传谱系 · 开局家族建档）。
 *
 * <p><b>为什么要有这个服务</b>：`actor_growth_genome` 此前只有 {@link OffspringGenerator} 两处
 * `genomeRepository.save()`（出生合成 + 惰性推断）。开局家族建档也要给"从没有父母"的创始人写基因行，
 * 若直接在 {@code FamilyGenesisService} 里再 save 一次，就出现第二个写入口（违反 AGENTS 8.1.4
 * 「每张业务表只允许一个领域服务写入」）。故把「按 OCEAN 惰性推断 + 落库」的既有逻辑从
 * {@link OffspringGenerator#genomeOf} 原样搬到这里，双方共用：出生走 {@link #ensureInherited}，
 * 开局/修复走 {@link #ensureInferred}，读取走 {@link #find}。</p>
 *
 * <p><b>口径（与出生路径完全一致，不得偏离）</b>：
 * <ul>
 *   <li>无基因行 → {@link MendelianRecombinator#inferGenome}（按角色既有 OCEAN 反向拟合 15 位点，
 *       保证"任何角色都可成为父母"，且子代从真实人格遗传）；</li>
 *   <li>有父母 → {@link MendelianRecombinator#recombine}（各取一个等位基因 + 从头突变），
 *       突变率取 {@code growth.reproduction.mutationRateMin~Max} 区间随机；</li>
 *   <li>表观印记默认空数组（出生路径按父母创伤事件生成，开局创始人没有可考事件）；</li>
 *   <li>{@code source}：{@code inferred}（按 OCEAN 推断）/ {@code inherited}（父母重组）。</li>
 * </ul></p>
 *
 * <p><b>零阻断</b>：任何解析/落库异常都只记 warn 并返回 null，绝不打断世界初始化或启动补齐
 * （世界初始化链路的铁律：单个角色的档案问题不允许炸整批）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenomeService {

    /** 来源：按既有 OCEAN 程序化推断（创始人 / 无父母者）。 */
    public static final String SOURCE_INFERRED = "inferred";
    /** 来源：父母孟德尔重组（真实子嗣）。 */
    public static final String SOURCE_INHERITED = "inherited";

    /** 空表观印记（与出生路径的兜底串一致）。 */
    private static final String EMPTY_EPIGENETICS = "{\"marks\":[]}";
    /** 群体均值（OCEAN 缺维兜底）。 */
    private static final double POPULATION_MEAN = 0.5;

    private final ActorGrowthGenomeRepository genomeRepository;
    private final PersonaService personaService;
    private final GrowthProperties growthProperties;

    /**
     * 读某角色的基因行（不创建）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一身份引用 {@code npc:{id}}
     *
     * @return 基因行（无则 empty）
     */
    public Optional<ActorGrowthGenome> find(Long projectId, String npcRef) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return Optional.empty();
        }
        return genomeRepository.findByProjectIdAndNpcRef(projectId, npcRef);
    }

    /**
     * 取角色基因组（无基因行 → 按既有 OCEAN 推断并落库，幂等）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色
     *
     * @return 基因组；角色为空或异常返回 null
     */
    public GenomeData genomeOf(Long projectId, ActorCharacter ch) {
        if (projectId == null || ch == null || ch.getId() == null) {
            return null;
        }
        ActorGrowthGenome row = genomeRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef()).orElse(null);
        if (row != null && row.getGeneLociJson() != null && !row.getGeneLociJson().isBlank()) {
            try {
                return PersonaJson.mapper().readValue(row.getGeneLociJson(), GenomeData.class);
            } catch (Exception e) {
                log.warn("[遗传] 基因行解析失败（按 OCEAN 重新推断）: {} 原因={}", ch.npcRef(), e.getMessage());
            }
        }
        return ensureInferred(projectId, ch);
    }

    /**
     * 确保角色有基因行：无行则按 OCEAN 推断并落库（幂等，已有行直接返回解析结果）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色
     *
     * @return 基因组（异常返回 null）
     */
    public GenomeData ensureInferred(Long projectId, ActorCharacter ch) {
        if (projectId == null || ch == null || ch.getId() == null) {
            return null;
        }
        try {
            Random random = new Random(projectId * 31L + ch.getId());
            GenomeData inferred = MendelianRecombinator.inferGenome(oceanOf(ch), random);
            ActorGrowthGenome g = new ActorGrowthGenome();
            g.setProjectId(projectId);
            g.setNpcRef(ch.npcRef());
            g.setGeneLociJson(writeGenome(inferred));
            g.setEpigeneticsJson(EMPTY_EPIGENETICS);
            g.setSource(SOURCE_INFERRED);
            genomeRepository.save(g);
            log.info("[遗传] 基因建档（按 OCEAN 推断）：project={}, ref={}, name={}", projectId, ch.npcRef(), ch.getName());
            return inferred;
        } catch (Exception e) {
            log.warn("[遗传] 基因建档失败（跳过）: project={}, ref={} 原因={}", projectId,
                    ch == null ? null : ch.npcRef(), e.getMessage());
            return null;
        }
    }

    /**
     * 按父母孟德尔重组给孩子建档（幂等：孩子已有基因行则原样返回）。
     *
     * @param projectId 项目 ID
     *
     * @param child 孩子
     *
     * @param father 父（可空 → 该侧等位基因随机，单亲/孤儿兜底）
     *
     * @param mother 母（可空，同上）
     *
     * @param random 随机源（调用方传世界种子，保证可复现）
     *
     * @return 基因组（异常返回 null）
     */
    public GenomeData ensureInherited(Long projectId, ActorCharacter child, ActorCharacter father,
                                      ActorCharacter mother, Random random) {
        if (projectId == null || child == null || child.getId() == null) {
            return null;
        }
        ActorGrowthGenome existing = genomeRepository.findByProjectIdAndNpcRef(projectId, child.npcRef()).orElse(null);
        if (existing != null && existing.getGeneLociJson() != null && !existing.getGeneLociJson().isBlank()) {
            try {
                return PersonaJson.mapper().readValue(existing.getGeneLociJson(), GenomeData.class);
            } catch (Exception e) {
                log.warn("[遗传] 既有基因行解析失败（重新合成）: {} 原因={}", child.npcRef(), e.getMessage());
            }
        }
        try {
            GenomeData fatherGenome = father == null ? null : genomeOf(projectId, father);
            GenomeData motherGenome = mother == null ? null : genomeOf(projectId, mother);
            double min = growthProperties.getReproduction().getMutationRateMin();
            double max = growthProperties.getReproduction().getMutationRateMax();
            double mutationRate = min + random.nextDouble() * Math.max(0.0, max - min);
            GenomeData childGenome = MendelianRecombinator.recombine(motherGenome, fatherGenome, mutationRate, random);
            ActorGrowthGenome row = new ActorGrowthGenome();
            row.setProjectId(projectId);
            row.setNpcRef(child.npcRef());
            row.setGeneLociJson(writeGenome(childGenome));
            row.setEpigeneticsJson(EMPTY_EPIGENETICS);
            row.setSource(SOURCE_INHERITED);
            row.setSourceParentA(father == null ? null : father.getId());
            row.setSourceParentB(mother == null ? null : mother.getId());
            genomeRepository.save(row);
            log.info("[遗传] 基因建档（父母重组）：project={}, child={}, father={}, mother={}",
                    projectId, child.npcRef(), father == null ? "-" : father.npcRef(),
                    mother == null ? "-" : mother.npcRef());
            return childGenome;
        } catch (Exception e) {
            log.warn("[遗传] 父母重组失败（跳过）: project={}, child={} 原因={}", projectId,
                    child == null ? null : child.npcRef(), e.getMessage());
            return null;
        }
    }

    /**
     * 从档案 ④ 维取 OCEAN（缺失维按 0.5 群体均值）。
     *
     * @param ch 角色
     *
     * @return OCEAN 五维（键为 {@link GeneticLoci#oceanKeys()}）
     */
    public Map<String, Double> oceanOf(ActorCharacter ch) {
        Map<String, Double> out = new LinkedHashMap<>();
        PersonaProfile p = ch == null || ch.getId() == null ? null : personaService.profileOf(ch.getId());
        PersonaDims.Ocean ocean = p == null || p.getDims() == null || p.getDims().getPersonality() == null
                ? null : p.getDims().getPersonality().getOcean();
        for (String key : GeneticLoci.oceanKeys()) {
            double v = POPULATION_MEAN;
            if (ocean != null) {
                v = switch (key) {
                    case GeneticLoci.KEY_OCEAN_OPENNESS -> ocean.getOpenness() == null ? POPULATION_MEAN : ocean.getOpenness();
                    case GeneticLoci.KEY_OCEAN_CONSCIENTIOUSNESS -> ocean.getConscientiousness() == null
                            ? POPULATION_MEAN : ocean.getConscientiousness();
                    case GeneticLoci.KEY_OCEAN_EXTRAVERSION -> ocean.getExtraversion() == null
                            ? POPULATION_MEAN : ocean.getExtraversion();
                    case GeneticLoci.KEY_OCEAN_AGREEABLENESS -> ocean.getAgreeableness() == null
                            ? POPULATION_MEAN : ocean.getAgreeableness();
                    default -> ocean.getNeuroticism() == null ? POPULATION_MEAN : ocean.getNeuroticism();
                };
            }
            out.put(key, Math.max(0, Math.min(1, v)));
        }
        return out;
    }

    /**
     * 落一行基因档案（出生路径复用：调用方已算好基因组，只需按统一口径落库）。
     *
     * <p>给 {@link OffspringGenerator} 用——它先建孩子档案行拿到 id，再把父母重组结果落库；
     * 这样"基因表写入口"仍然只有本服务一处（AGENTS 8.1.4）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 孩子统一身份引用
     *
     * @param genome 基因组（null 则不落库）
     *
     * @param source 来源（{@link #SOURCE_INHERITED} / {@link #SOURCE_INFERRED}）
     *
     * @param fatherId 父 id（可空）
     *
     * @param motherId 母 id（可空）
     *
     * @return 落库行；无需落库或失败返回 null
     */
    public ActorGrowthGenome saveRow(Long projectId, String npcRef, GenomeData genome, String source,
                                     Long fatherId, Long motherId) {
        if (projectId == null || npcRef == null || genome == null) {
            return null;
        }
        try {
            ActorGrowthGenome row = genomeRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElseGet(ActorGrowthGenome::new);
            row.setProjectId(projectId);
            row.setNpcRef(npcRef);
            row.setGeneLociJson(writeGenome(genome));
            if (row.getEpigeneticsJson() == null || row.getEpigeneticsJson().isBlank()) {
                row.setEpigeneticsJson(EMPTY_EPIGENETICS);
            }
            row.setSource(source == null || source.isBlank() ? SOURCE_INHERITED : source);
            row.setSourceParentA(fatherId);
            row.setSourceParentB(motherId);
            return genomeRepository.save(row);
        } catch (Exception e) {
            log.warn("[遗传] 基因行落库失败（跳过）: project={}, ref={} 原因={}", projectId, npcRef, e.getMessage());
            return null;
        }
    }

    /**
     * 解析既有基因行 → 基因组（空行/解析失败返回 null）。
     *
     * @param row 基因行
     *
     * @return 基因组或 null
     */
    public GenomeData parse(ActorGrowthGenome row) {
        if (row == null || row.getGeneLociJson() == null || row.getGeneLociJson().isBlank()) {
            return null;
        }
        try {
            return PersonaJson.mapper().readValue(row.getGeneLociJson(), GenomeData.class);
        } catch (Exception e) {
            log.warn("[遗传] 基因行解析失败: {} 原因={}", row.getNpcRef(), e.getMessage());
            return null;
        }
    }

    /**
     * 基因组 → JSON 文本。
     *
     * @param genome 基因组
     *
     * @return JSON（序列化失败抛 {@link IllegalStateException}，由调用方兜底）
     */
    private String writeGenome(GenomeData genome) {
        try {
            return PersonaJson.mapper().writeValueAsString(genome);
        } catch (Exception e) {
            throw new IllegalStateException("基因组序列化失败: " + e.getMessage(), e);
        }
    }
}
