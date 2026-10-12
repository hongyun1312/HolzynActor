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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.ActorGrowthLineage;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestone;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaSchema;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.growth.romance.AiDecisionSourceSupport;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorCoreMemoryRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 新生儿生成器。
 * <p><b>七步流程</b>：</p>
 * <ol>
 * <li><b>校验</b>：父母在世 + 已婚（MARRIAGE 边）+ 孕期已到预产期 + 人口上限；</li>
 * <li><b>遗传合成</b>：{@link MendelianRecombinator#recombine}（孟德尔 15 位点 + 从头突变）+
 * <li><b>人格基底</b>：遗传度框架（：{@code child = 0.5 + h×(midparent−0.5) + N(0,σ)}）→
 * 子代 OCEAN 四舍五入写入 ④ 维；</li>
 * <li><b>关系模板</b>：谱系亲子边（father/mother）+ 社会关系 PARENT_CHILD/SIBLING 边；</li>
 * <li><b>阶段初始化</b>：{@code actor_profile} 行（INFANT/birth=当前时刻）+ {@code actor_state} 行
 * （婴儿生理）+ 基因行（source=inherited）；</li>
 * <li><b>初始记忆</b>：父母「喜得贵子/千金」observation（importance=5）+ 出生里程碑；</li>
 * <li><b>世界广播</b>：世界事件（kind=birth）+ 编年史入口 + SSE {@code npc:birth}（join 旁路）
 * + Unity {@code npc_birth} 下行。</li>
 * </ol>
 * <p><b>分阶段揭示</b>：出生时只填 ① 身份（AI 起名 + 继承描述）、② 外貌（AI 按父母特征继承）、
 * ③ 生理基线（程序化：父母基底 + 基因 TEMPO/代谢修正 + 表观印记）、④ 人格（遗传度框架产物）、
 * ⑩ 发育·遗传·谱系（基因 + 谱系）；⑤⑥⑦⑧⑨⑪ 维<b>留空</b>，由阶段跨越到 TODDLER/CHILD/ADOLESCENT 时
 * 复用 「AI 补全缺失维」链路逐步揭示（{@code InfantRevealService}）。</p>
 * <p><b>架构不变量</b>：本类公开方法<b>不含</b>{@code @Transactional}——Step 3 含 AI 起名/外貌
 * 调用（秒级），写库走各仓库短事务；任何一步失败只告警，已完成的写入保留（部分完成状态可重入）。
 * 全程零阻断（不抛给世界引擎）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OffspringGenerator {

    /**
     * 新生儿 AI 建档场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板，
     * 输出 {given_name, identity, appearance}，AI 按父母与世界环境起名/继承外貌）
     */
    public static final String SCENE_NEWBORN_PERSONA = "growth_newborn_persona";

    /** 新生儿性别随机源（50/50；不注入 seed——出生是运行期事件） */
    private final Random random = new Random();

    /** 程序化兜底起名（AI 失败时按性别取用；中性常用名，避免重名——各世界文化命名由 AI 优先承担） */
    private static final List<String> GIVEN_NAMES_MALE = List.of(
            "青山", "远之", "承安", "文渊", "景行", "知行", "怀瑾", "子墨", "长庚", "砚舟");
    private static final List<String> GIVEN_NAMES_FEMALE = List.of(
            "阿棠", "灵萱", "晚晴", "知微", "月白", "听雨", "青梧", "念慈", "云锦", "小满");

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final ActorGrowthGenomeRepository genomeRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final ActorEventRepository eventRepository;
    private final ActorCoreMemoryRepository coreMemoryRepository;
    private final GrowthMilestoneRepository milestoneRepository;
    private final LineageService lineageService;
    private final PersonaService personaService;
    private final GrowthProperties growthProperties;
    private final AgentMemoryService agentMemoryService;
    private final WorldEventBus worldEventBus;
    private final WorldSandboxSseHub sandboxSseHub;
    private final CurrentUserProvider currentUserProvider;
    private final AiDecisionSourceSupport aiSupport;

    //
    // 公开入口
    //

    /**
     * 生成新生儿（七步全链路；幂等可重入——失败重跑不会产生重复新生儿，见各步去重）。
     *
     * @param projectId 项目 ID
     *
     * @param motherRef 母亲 npcRef（npc:{id}）
     *
     * @param fatherRef 父亲 npcRef（可空=单亲/未知，遗传单源兜底）
     *
     * @param gameSecond 分娩时刻（世界时钟；预产期已到的判定由调用方完成）
     *
     * @return 新生儿档案行；失败返回 null（零阻断）
     */
    public ActorCharacter createOffspring(Long projectId, String motherRef, String fatherRef, Long gameSecond) {
        if (projectId == null || motherRef == null || gameSecond == null) {
            log.warn("[新生儿] 参数非法（projectId/motherRef/gameSecond 不能为空），已跳过");
            return null;
        }
        long start = System.currentTimeMillis();
        ActorCharacter mother = characterRepository.findById(NpcRefs.idOf(motherRef)).orElse(null);
        ActorCharacter father = fatherRef == null ? null
                : characterRepository.findById(NpcRefs.idOf(fatherRef)).orElse(null);
        // Step 1 校验（零阻断：任何一项不过直接返回 null）
        if (mother == null || "DECEASED".equalsIgnoreCase(mother.getStage())) {
            log.warn("[新生儿] 母亲缺失或已故，已跳过：motherRef={}", motherRef);
            return null;
        }
        if (fatherRef != null && father == null) {
            log.warn("[新生儿] 父亲缺失，已跳过：fatherRef={}", fatherRef);
            return null;
        }
        long population = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .stream().filter(c -> !"DECEASED".equalsIgnoreCase(c.getStage())).count();
        int cap = growthProperties.getReproduction().getPopulationCap();
        if (population >= cap) {
            log.warn("[新生儿] 人口已达上限（{}），本次分娩不落地（程序化门控）：projectId={}",
                    cap, projectId);
            return null;
        }
        // Step 2 遗传合成（孟德尔重组 + 表观修饰）
        ActorGrowthGenome genome = synthesizeGenome(projectId, mother, father, gameSecond);
        if (genome == null) {
            log.warn("[新生儿] 遗传合成失败，已跳过：motherRef={}", motherRef);
            return null;
        }
        // Step 3 人格基底
        Map<String, Double> childOcean = childOceanOf(genome);
        // Step 4 新生儿档案行（actor_profile：INFANT + birth + 结构列）
        ActorCharacter child = createProfileRow(projectId, mother, father, genome, childOcean, gameSecond);
        if (child == null) {
            log.warn("[新生儿] 档案行创建失败，已跳过：motherRef={}", motherRef);
            return null;
        }
        // Step 4b 关系模板（谱系亲子边 + 社会关系 PARENT_CHILD/SIBLING）
        try {
            lineageService.addParentChild(projectId, motherRef, child.npcRef(),
                    ActorGrowthLineage.REL_MOTHER, gameSecond);
            if (father != null) {
                lineageService.addParentChild(projectId, father.npcRef(), child.npcRef(),
                        ActorGrowthLineage.REL_FATHER, gameSecond);
            }
            bindRelations(projectId, mother, father, child, gameSecond);
        } catch (Exception e) {
            log.warn("[新生儿] 关系模板失败（隔离继续）: {} 原因={}", child.npcRef(), e.getMessage());
        }
        // Step 5 运行态初始化（actor_state：婴儿生理基线 + 位置跟随母亲）
        try {
            initState(projectId, child, mother, genome, childOcean, gameSecond);
        } catch (Exception e) {
            log.warn("[新生儿] 运行态初始化失败（隔离继续）: {} 原因={}", child.npcRef(), e.getMessage());
        }
        // Step 6 初始记忆（父母喜得 + 家族知晓）
        try {
            recordBirthMemories(projectId, mother, father, child, gameSecond);
        } catch (Exception e) {
            log.warn("[新生儿] 初始记忆写入失败（隔离继续）: {} 原因={}", child.npcRef(), e.getMessage());
        }
        // Step 7 世界广播（里程碑 + 事件 + SSE/Unity 下行）
        try {
            broadcastBirth(projectId, mother, father, child, gameSecond);
        } catch (Exception e) {
            log.warn("[新生儿] 世界广播失败（隔离继续）: {} 原因={}", child.npcRef(), e.getMessage());
        }
        // 母亲产后状态（孕期清空 + 冷却基准）
        try {
            stateRepository.findByProjectIdAndNpcRef(projectId, motherRef).ifPresent(st -> {
                st.setPregnancyFromRef(null);
                st.setPregnancyStartGameSecond(null);
                st.setPregnancyDueGameSecond(null);
                st.setLastBirthGameSecond(gameSecond);
                stateRepository.save(st);
            });
        } catch (Exception e) {
            log.warn("[新生儿] 母亲产后状态更新失败（隔离继续）: {} 原因={}", motherRef, e.getMessage());
        }
        log.info("[新生儿] 出生完成：{}（{}，母亲={} 父亲={}）耗时={}ms", child.getName(), child.npcRef(),
                mother.getName(), father == null ? "无" : father.getName(),
                System.currentTimeMillis() - start);
        return child;
    }

    //
    // Step 2 遗传合成
    //

    /**
     * 遗传合成：父母基因组（缺则程序化推断）→ 孟德尔重组 → 表观印记 → 基因行（未落库）。
     * <p>返回的行只含数据（geneLociJson/epigeneticsJson），npcRef 与父母引用由调用方在
     * 档案行创建后补齐并落库（Step 4）。</p>
     *
     * @return 基因行（未落库）；合成失败返回 null
     */
    private ActorGrowthGenome synthesizeGenome(Long projectId, ActorCharacter mother, ActorCharacter father,
                                               Long gameSecond) {
        try {
            GenomeData motherGenome = genomeOf(projectId, mother);
            GenomeData fatherGenome = father == null ? null : genomeOf(projectId, father);
            double mutationRate = growthProperties.getReproduction().getMutationRateMin()
                    + random.nextDouble() * (growthProperties.getReproduction().getMutationRateMax()
                    - growthProperties.getReproduction().getMutationRateMin());
            GenomeData childGenome = MendelianRecombinator.recombine(motherGenome, fatherGenome, mutationRate, random);
            // 表观印记
            EpigeneticMarks marks = EpigeneticModifier.apply(
                    collectParentEvents(projectId, mother, father, gameSecond),
                    gameSecond - (long) growthProperties.getReproduction().getEpigeneticsWindowGameDays() * 86400L,
                    gameSecond,
                    growthProperties.getReproduction().getEpigeneticsWindowGameDays(),
                    growthProperties.getAging().getGameSecondsPerYear());
            ActorGrowthGenome row = new ActorGrowthGenome();
            row.setProjectId(projectId);
            row.setGeneLociJson(writeGenome(childGenome));
            try {
                row.setEpigeneticsJson(PersonaJson.mapper().writeValueAsString(marks));
            } catch (Exception e) {
                row.setEpigeneticsJson("{\"marks\":[]}");
            }
            return row;
        } catch (Exception e) {
            log.warn("[新生儿] 遗传合成异常（零阻断）: 原因={}", e.getMessage());
            return null;
        }
    }

    /** 取角色基因组（无基因行 → 按既有 OCEAN 程序化推断并落库——任何角色都可成为父母）。 */
    private GenomeData genomeOf(Long projectId, ActorCharacter ch) {
        ActorGrowthGenome row = genomeRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef()).orElse(null);
        if (row != null && row.getGeneLociJson() != null && !row.getGeneLociJson().isBlank()) {
            try {
                return PersonaJson.mapper().readValue(row.getGeneLociJson(), GenomeData.class);
            } catch (Exception e) {
                log.warn("[遗传] 基因行解析失败（按 OCEAN 重新推断）: {} 原因={}", ch.npcRef(), e.getMessage());
            }
        }
        Map<String, Double> ocean = oceanOf(ch);
        GenomeData inferred = MendelianRecombinator.inferGenome(ocean, random);
        ActorGrowthGenome g = new ActorGrowthGenome();
        g.setProjectId(projectId);
        g.setNpcRef(ch.npcRef());
        g.setGeneLociJson(writeGenome(inferred));
        g.setEpigeneticsJson("{\"marks\":[]}");
        g.setSource("inferred");
        genomeRepository.save(g);
        return inferred;
    }

    /** 从档案 ④ 维取 OCEAN（缺失维按 0.5 群体均值）。 */
    private Map<String, Double> oceanOf(ActorCharacter ch) {
        Map<String, Double> out = new LinkedHashMap<>();
        PersonaProfile p = personaService.profileOf(ch.getId());
        PersonaDims.Ocean ocean = p == null || p.getDims() == null || p.getDims().getPersonality() == null
                ? null : p.getDims().getPersonality().getOcean();
        for (String key : GeneticLoci.oceanKeys()) {
            double v = 0.5;
            if (ocean != null) {
                v = switch (key) {
                    case GeneticLoci.KEY_OCEAN_OPENNESS -> ocean.getOpenness() == null ? 0.5 : ocean.getOpenness();
                    case GeneticLoci.KEY_OCEAN_CONSCIENTIOUSNESS -> ocean.getConscientiousness() == null
                            ? 0.5 : ocean.getConscientiousness();
                    case GeneticLoci.KEY_OCEAN_EXTRAVERSION -> ocean.getExtraversion() == null
                            ? 0.5 : ocean.getExtraversion();
                    case GeneticLoci.KEY_OCEAN_AGREEABLENESS -> ocean.getAgreeableness() == null
                            ? 0.5 : ocean.getAgreeableness();
                    default -> ocean.getNeuroticism() == null ? 0.5 : ocean.getNeuroticism();
                };
            }
            out.put(key, Math.max(0, Math.min(1, v)));
        }
        return out;
    }

    /** 收集父母孕前重大事件。 */
    private List<EpigeneticModifier.ParentEvent> collectParentEvents(Long projectId, ActorCharacter mother,
                                                                     ActorCharacter father, Long gameSecond) {
        List<EpigeneticModifier.ParentEvent> events = new java.util.ArrayList<>();
        long windowStart = gameSecond
                - (long) growthProperties.getReproduction().getEpigeneticsWindowGameDays() * 86400L;
        // 源一：世界事件（actor_event，importance≥4 且涉及任一方，game_hour 落窗口）
        try {
            for (ActorEvent e : eventRepository.findByProjectIdOrderByIdDesc(projectId)) {
                if (e == null || e.getImportance() == null || e.getImportance() < 4) {
                    continue;
                }
                String refs = e.getCharacterIds() == null ? "" : e.getCharacterIds();
                boolean involved = refs.contains(mother.npcRef())
                        || (father != null && refs.contains(father.npcRef()));
                if (!involved) {
                    continue;
                }
                long eventSecond = e.getGameHour() == null ? -1 : e.getGameHour() * 3600L;
                if (eventSecond >= 0 && (eventSecond < windowStart || eventSecond > gameSecond)) {
                    continue;
                }
                String text = (e.getTitle() == null ? "" : e.getTitle())
                        + "：" + (e.getContent() == null ? "" : e.getContent());
                events.add(new EpigeneticModifier.ParentEvent(text, e.getImportance(),
                        eventSecond >= 0 ? eventSecond : null));
            }
        } catch (Exception ex) {
            log.warn("[遗传] 世界事件源收集失败（降级 L3 单源）: {}", ex.getMessage());
        }
        // 源二：L3 核心记忆（importance≥4；蒸馏块无时刻，视为窗口内）
        try {
            for (com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory m
                    : coreMemoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(
                    projectId, mother.npcRef(), 0)) {
                if (m.getImportance() != null && m.getImportance() >= 4 && m.getContent() != null
                        && !m.getContent().isBlank()) {
                    events.add(new EpigeneticModifier.ParentEvent(m.getContent(), m.getImportance(), null));
                }
            }
            if (father != null) {
                for (com.holzyn.actor.domain.npc.memory.entity.ActorCoreMemory m
                        : coreMemoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByImportanceDesc(
                        projectId, father.npcRef(), 0)) {
                    if (m.getImportance() != null && m.getImportance() >= 4 && m.getContent() != null
                            && !m.getContent().isBlank()) {
                        events.add(new EpigeneticModifier.ParentEvent(m.getContent(), m.getImportance(), null));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[遗传] L3 核心记忆源收集失败（降级世界事件单源）: {}", ex.getMessage());
        }
        return events;
    }

    //
    // Step 3/4 人格基底与档案行
    //

    /** 子代 OCEAN（遗传度框架：child = 0.5 + h×(midparent−0.5) + N(0,σ)）。 */
    private Map<String, Double> childOceanOf(ActorGrowthGenome genomeRow) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            GenomeData g = PersonaJson.mapper().readValue(genomeRow.getGeneLociJson(), GenomeData.class);
            double h = growthProperties.getReproduction().getHeritability();
            double sigma = growthProperties.getReproduction().getGaussianNoiseSigma();
            for (String key : GeneticLoci.oceanKeys()) {
                double midparent = g.getDerivedTraits() == null ? 0.5
                        : g.getDerivedTraits().getOrDefault(key, 0.5);
                out.put(key, Math.round(MendelianRecombinator.childOcean(midparent, h, sigma, random) * 100.0) / 100.0);
            }
        } catch (Exception e) {
            for (String key : GeneticLoci.oceanKeys()) {
                out.put(key, 0.5);
            }
        }
        return out;
    }

    /** 创建新生儿档案行。 */
    private ActorCharacter createProfileRow(Long projectId, ActorCharacter mother, ActorCharacter father,
                                            ActorGrowthGenome genome, Map<String, Double> childOcean,
                                            Long gameSecond) {
        try {
            // 性别 50/50（真实出生比例）
            boolean isMale = random.nextBoolean();
            // AI 起名 + 外貌继承
            NewbornNaming naming = nameAndAppearance(projectId, mother, father, isMale, gameSecond);
            ActorCharacter child = new ActorCharacter();
            child.setProjectId(projectId);
            child.setType("common");
            child.setName(naming.fullName());
            child.setGender(isMale ? "男" : "女");
            child.setRace(mother.getRace() == null ? (father == null ? null : father.getRace()) : mother.getRace());
            child.setSubRace(mother.getSubRace() == null ? (father == null ? null : father.getSubRace()) : mother.getSubRace());
            child.setAffiliation(mother.getAffiliation() == null ? (father == null ? null : father.getAffiliation())
                    : mother.getAffiliation());
            child.setLocation(mother.getLocation());
            child.setLocationId(mother.getLocationId());
            child.setBirthGameSecond(gameSecond);
            child.setAge(0);
            child.setStage(com.holzyn.actor.domain.growth.stage.GrowthStage.INFANT.name());
            child.setImportance(1);
            child.setStatus(1);
            child.setCurrentActivity("襁褓中安睡");
            ActorCharacter saved = characterRepository.save(child);
            // 基因行落库（parent 引用 + inherited）
            genome.setNpcRef(saved.npcRef());
            genome.setSourceParentA(mother.getId());
            genome.setSourceParentB(father == null ? null : father.getId());
            genome.setSource("inherited");
            genomeRepository.save(genome);
            // 11 维档案
            writeBirthPersona(saved, mother, father, naming, childOcean, gameSecond);
            return saved;
        } catch (Exception e) {
            log.warn("[新生儿] 档案行创建异常（零阻断）: 原因={}", e.getMessage());
            return null;
        }
    }

    /** 出生档案。 */
    private void writeBirthPersona(ActorCharacter child, ActorCharacter mother, ActorCharacter father,
                                   NewbornNaming naming, Map<String, Double> ocean, Long gameSecond) {
        try {
            PersonaProfile p = PersonaProfile.empty(child.getProjectId(), child.getId());
            PersonaProfile.Dims dims = new PersonaProfile.Dims();
            // ① 身份与社会
            PersonaDims.Identity identity = new PersonaDims.Identity();
            identity.setIdentityTitle("婴孩");
            identity.setLivelihood("（尚在襁褓，由父母抚养）");
            identity.getStatusTags().add(isMale(child) ? "男婴" : "女婴");
            dims.setIdentity(identity);
            // ② 外貌（AI 继承描述或兜底）
            PersonaDims.Appearance appearance = new PersonaDims.Appearance();
            appearance.setAppearanceNotes(naming.appearanceNotes());
            appearance.setBuild("婴儿");
            dims.setAppearance(appearance);
            // ③ 生理基线（程序化：父母基底 + 基因 TEMPO/代谢修正 + 表观印记）——由基因与父母推断
            dims.setPhysio(inferInfantPhysio(child, mother, father));
            // ④ 气质与人格
            PersonaDims.Personality personality = new PersonaDims.Personality();
            PersonaDims.Ocean o = new PersonaDims.Ocean();
            o.setOpenness(ocean.get(GeneticLoci.KEY_OCEAN_OPENNESS));
            o.setConscientiousness(ocean.get(GeneticLoci.KEY_OCEAN_CONSCIENTIOUSNESS));
            o.setExtraversion(ocean.get(GeneticLoci.KEY_OCEAN_EXTRAVERSION));
            o.setAgreeableness(ocean.get(GeneticLoci.KEY_OCEAN_AGREEABLENESS));
            o.setNeuroticism(ocean.get(GeneticLoci.KEY_OCEAN_NEUROTICISM));
            personality.setOcean(o);
            personality.setTemperamentType("（尚在发育，遗传倾向）");
            dims.setPersonality(personality);
            // ⑩ 发育·遗传·谱系（基因 + 谱系投影）
            PersonaDims.Development dev = new PersonaDims.Development();
            dev.setStage(com.holzyn.actor.domain.growth.stage.GrowthStage.INFANT.name());
            dev.setStageEnteredGameSecond(gameSecond);
            dims.setDevelopment(dev);
            p.setDims(dims);
            p.setGeneratedBy("genetic");
            p.setGeneratedScene(SCENE_NEWBORN_PERSONA);
            p.setGeneratedGameSecond(gameSecond);
            try {
                personaService.savePersona(child.getId(), p, "genetic");
            } catch (Exception e) {
                log.warn("[新生儿] 档案落库失败（隔离继续）: {} 原因={}", child.npcRef(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[新生儿] 出生档案构建异常（隔离继续）: 原因={}", e.getMessage());
        }
    }

    /** 婴儿生理基线（程序化：父母基底 + 基因修正 + 表观印记；写入 ③ 维供状态模块消费）。 */
    private PersonaDims.Physio inferInfantPhysio(ActorCharacter child, ActorCharacter mother, ActorCharacter father) {
        PersonaDims.Physio physio = new PersonaDims.Physio();
        // 作息型：父母之一继承（婴儿多睡，但相位遗传）
        PersonaDims.Physio motherPhysio = physioOf(mother);
        PersonaDims.Physio fatherPhysio = father == null ? null : physioOf(father);
        physio.setChronotype(random.nextBoolean()
                ? (motherPhysio != null && motherPhysio.getChronotype() != null ? motherPhysio.getChronotype() : "intermediate")
                : (fatherPhysio != null && fatherPhysio.getChronotype() != null ? fatherPhysio.getChronotype() : "intermediate"));
        // 代谢：基因 TEMPO/表观印记修正（饥荒印记 → 代谢囤积型）
        physio.setMetabolism("fast");
        physio.setSleepNeedHours(16.0);
        physio.setMealCycleHours(3.0);
        physio.setMealsPerDay(6);
        physio.setFertilityState("未育龄");
        physio.setLibido("无（婴孩）");
        physio.setPhysioNotes("新生儿：高睡眠需求、高频进食、代谢快；生理参数随成长由阶段机与档案补全调整。");
        return physio;
    }

    private PersonaDims.Physio physioOf(ActorCharacter ch) {
        if (ch == null) {
            return null;
        }
        com.holzyn.actor.domain.npc.state.service.PhysioProfile profile =
                com.holzyn.actor.domain.npc.state.service.PhysioProfile.fromPhysioJson(
                        stateRepository.findByProjectIdAndNpcRef(ch.getProjectId(), ch.npcRef())
                                .map(ActorNpcState::getPhysioJson).orElse(null));
        PersonaDims.Physio physio = new PersonaDims.Physio();
        physio.setChronotype(profile == null ? null : profile.chronotype());
        return physio;
    }

    /** AI 起名 + 外貌继承（scene=growth_newborn_persona；失败程序化兜底）。 */
    private NewbornNaming nameAndAppearance(Long projectId, ActorCharacter mother, ActorCharacter father,
                                            boolean isMale, Long gameSecond) {
        String surname = surnameOf(father == null ? mother : father);
        // AI 调用
        try {
            String content = aiSupport.call(projectId, mother.getId(), SCENE_NEWBORN_PERSONA, AiDecisionSourceSupport.ph(
                    "surname", surname,
                    "gender", isMale ? "男" : "女",
                    "father_name", father == null ? "（未知）" : father.getName(),
                    "mother_name", mother.getName(),
                    "father_appearance", appearanceTextOf(father),
                    "mother_appearance", appearanceTextOf(mother),
                    "father_occupation", father == null ? "（未知）" : father.getOccupation(),
                    "mother_occupation", mother.getOccupation()));
            if (content != null) {
                JsonNode node = parseJson(content);
                if (node != null) {
                    String given = node.has("given_name") ? node.get("given_name").asText("").trim() : "";
                    if (!given.isBlank()) {
                        String appearance = node.has("appearance") ? node.get("appearance").asText("").trim() : "";
                        return new NewbornNaming(surname + given, isMale, appearance);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[新生儿] AI 起名失败（程序化兜底）: {}", e.getMessage());
        }
        List<String> pool = isMale ? GIVEN_NAMES_MALE : GIVEN_NAMES_FEMALE;
        String given = pool.get(random.nextInt(pool.size()));
        String appearance = "相貌尚幼，依稀看得出几分" + (father == null ? "母亲" : "父母")
                + "的影子（程序化兜底描述，档案补全时细化）。";
        return new NewbornNaming(surname + given, isMale, appearance);
    }

    private static JsonNode parseJson(String content) {
        String json = AiDecisionSourceSupport.extractJson(content);
        if (json == null) {
            return null;
        }
        try {
            return PersonaJson.mapper().readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 姓氏（取父母姓名首字——随家族传承；未知回退空，单名兜底）。 */
    private String surnameOf(ActorCharacter ch) {
        String name = ch == null ? null : ch.getName();
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.substring(0, 1);
    }

    private String appearanceTextOf(ActorCharacter ch) {
        if (ch == null) {
            return "（未知）";
        }
        PersonaProfile p = personaService.profileOf(ch.getId());
        if (p == null || p.getDims() == null || p.getDims().getAppearance() == null) {
            return "（档案未详）";
        }
        PersonaDims.Appearance a = p.getDims().getAppearance();
        StringBuilder sb = new StringBuilder();
        if (a.getFaceFeatures() != null) sb.append(a.getFaceFeatures()).append("；");
        if (a.getBuild() != null) sb.append(a.getBuild()).append("；");
        if (a.getHair() != null) sb.append(a.getHair());
        return sb.length() == 0 ? "（档案未详）" : sb.toString();
    }

    private boolean isMale(ActorCharacter ch) {
        return "男".equals(ch.getGender());
    }

    /** AI 起名结果（程序化兜底同构）。 */
    private record NewbornNaming(String fullName, boolean male, String appearanceNotes) {
    }

    //
    // Step 4b 关系模板 / Step 5 运行态 / Step 6 记忆 / Step 7 广播
    //

    /** 社会关系边：PARENT_CHILD（双向）+ 与既有子女的 SIBLING 边。 */
    private void bindRelations(Long projectId, ActorCharacter mother, ActorCharacter father,
                               ActorCharacter child, Long gameSecond) {
        bindRelation(projectId, mother, child, "PARENT_CHILD", "母子", gameSecond);
        bindRelation(projectId, child, mother, "PARENT_CHILD", "子女", gameSecond);
        if (father != null) {
            bindRelation(projectId, father, child, "PARENT_CHILD", "父子", gameSecond);
            bindRelation(projectId, child, father, "PARENT_CHILD", "子女", gameSecond);
        }
        // 与既有子女互为兄弟姐妹
        for (String siblingRef : lineageService.childrenOf(projectId, mother.npcRef())) {
            if (siblingRef.equals(child.npcRef())) {
                continue;
            }
            characterRepository.findById(NpcRefs.idOf(siblingRef)).ifPresent(sib -> {
                bindRelation(projectId, sib, child, "SIBLING", "兄弟姐妹", gameSecond);
                bindRelation(projectId, child, sib, "SIBLING", "兄弟姐妹", gameSecond);
            });
        }
    }

    private void bindRelation(Long projectId, ActorCharacter from, ActorCharacter to,
                              String type, String desc, Long gameSecond) {
        try {
            List<ActorCharacterRelation> existing = relationRepository.findByProjectId(projectId).stream()
                    .filter(r -> r != null && from.getId().equals(r.getFromCharacterId())
                            && to.getId().equals(r.getToCharacterId()) && type.equals(r.getRelationType()))
                    .toList();
            if (!existing.isEmpty()) {
                return;
            }
            ActorCharacterRelation r = new ActorCharacterRelation();
            r.setProjectId(projectId);
            r.setFromCharacterId(from.getId());
            r.setToCharacterId(to.getId());
            r.setFromRef(from.npcRef());
            r.setToRef(to.npcRef());
            r.setFromName(from.getName());
            r.setToName(to.getName());
            r.setRelationType(type);
            r.setDescription(desc);
            r.setAffinity(type.equals("PARENT_CHILD") ? 60 : 50);
            r.setTrust(type.equals("PARENT_CHILD") ? 70 : 55);
            r.setRelationState("intimate");
            relationRepository.save(r);
        } catch (Exception e) {
            log.warn("[新生儿] 关系边写入失败（隔离继续）: {}→{} : {}", from.getName(), to.getName(), e.getMessage());
        }
    }

    /** 运行态初始化（actor_state：婴儿生理；位置跟随母亲由 InfantBehaviorService 每小时同步）。 */
    private void initState(Long projectId, ActorCharacter child, ActorCharacter mother,
                           ActorGrowthGenome genome, Map<String, Double> ocean, Long gameSecond) {
        stateRepository.findByProjectIdAndNpcRef(projectId, child.npcRef()).ifPresentOrElse(st -> {
            st.setStageAtLastTick(com.holzyn.actor.domain.growth.stage.GrowthStage.INFANT.name());
            stateRepository.save(st);
        }, () -> {
            ActorNpcState st = new ActorNpcState();
            st.setProjectId(projectId);
            st.setNpcType("common");
            st.setNpcRef(child.npcRef());
            st.setStageAtLastTick(com.holzyn.actor.domain.growth.stage.GrowthStage.INFANT.name());
            st.setHunger(60);
            st.setThirst(60);
            st.setEnergy(80);
            st.setBladder(70);
            st.setHealth(100);
            st.setMood(75);
            st.setGameSecond(gameSecond);
            // 婴儿生理系数（physio_json：高睡眠/高频进食——与 ③ 维口径一致）
            Map<String, Object> physio = new LinkedHashMap<>();
            physio.put("chronotype", "intermediate");
            physio.put("sleep_need_hours", 16.0);
            physio.put("meal_cycle_hours", 3.0);
            physio.put("metabolism", "fast");
            try {
                st.setPhysioJson(PersonaJson.mapper().writeValueAsString(physio));
            } catch (Exception e) {
                // 生理 JSON 写入失败不阻断
            }
            stateRepository.save(st);
        });
    }

    /** 初始记忆（父母喜得 + 家族知晓；importance=5 与 4）。 */
    private void recordBirthMemories(Long projectId, ActorCharacter mother, ActorCharacter father,
                                     ActorCharacter child, Long gameSecond) {
        String genderWord = isMale(child) ? "子" : "女";
        agentMemoryService.recordObservation(projectId, mother.npcRef(),
                "诞下" + genderWord + "「" + child.getName() + "」，母子平安", 5);
        if (father != null) {
            agentMemoryService.recordObservation(projectId, father.npcRef(),
                    "喜得" + genderWord + "「" + child.getName() + "」", 5);
        }
        // 家族知晓（祖辈/兄弟姐妹）
        for (String kinRef : lineageService.childrenOf(projectId, mother.npcRef())) {
            if (kinRef.equals(child.npcRef())) {
                continue;
            }
            characterRepository.findById(NpcRefs.idOf(kinRef)).ifPresent(kin ->
                    agentMemoryService.recordObservation(projectId, kin.npcRef(),
                            "家中添了" + (isMale(child) ? "弟弟" : "妹妹") + "「" + child.getName() + "」", 3));
        }
        // 新生儿自己的出生记忆（长大后可追溯；出生地不做预设——由世界设定承载）
        agentMemoryService.recordObservation(projectId, child.npcRef(),
                "我出生了，" + (mother == null ? "" : mother.getName() + "（母）")
                        + (father == null ? "" : "、" + father.getName() + "（父）") + "家中", 3);
    }

    /** 世界广播（里程碑 + 世界事件 + SSE/Unity 下行；失败隔离）。 */
    private void broadcastBirth(Long projectId, ActorCharacter mother, ActorCharacter father,
                                ActorCharacter child, Long gameSecond) {
        // 里程碑（KIND_BIRTH）
        GrowthMilestone m = new GrowthMilestone();
        m.setProjectId(projectId);
        m.setNpcRef(child.npcRef());
        m.setGameSecond(gameSecond);
        m.setKind(GrowthMilestone.KIND_BIRTH);
        m.setTitle(child.getName() + "出生");
        m.setStageFrom(null);
        m.setStageTo(com.holzyn.actor.domain.growth.stage.GrowthStage.INFANT.name());
        m.setImportance(4);
        m.setDetail("程序化记录：" + child.getName() + "（" + (isMale(child) ? "男" : "女") + "婴）出生，母="
                + mother.getName() + (father == null ? "" : "，父=" + father.getName())
                + "。新生儿的遗传基底与初始档案已建立，人格/习惯/经历将随成长逐步揭示。");
        milestoneRepository.save(m);
        // 世界事件（kind=birth，importance=4）
        try {
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind("birth")
                            .source("growth")
                            .characterId(child.getId())
                            .characterRefs(java.util.Arrays.asList(child.npcRef(),
                                    mother.npcRef(), father == null ? child.npcRef() : father.npcRef()))
                            .visibility("global")
                            .title(child.getName() + "出生")
                            .content(mother.getName() + (father == null ? "" : "与" + father.getName())
                                    + "家中添了" + (isMale(child) ? "一个男孩" : "一个女孩") + "，取名" + child.getName() + "。")
                            .importance(4)
                            .gameHour(gameSecond / 3600L)
                            .sourceKey("growth-birth:" + child.getId())
                            .build());
        } catch (Exception e) {
            log.warn("[新生儿] 出生事件发布失败（隔离继续）: {}", e.getMessage());
        }
        // SSE：npc:join（新生儿进入世界）+ npc:birth（专用事件）
        try {
            sandboxSseHub.publishNpcJoin(projectId, child.npcRef(), "common", child.getName());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("npcRef", child.npcRef());
            data.put("npcName", child.getName());
            data.put("gender", child.getGender());
            data.put("motherRef", mother.npcRef());
            data.put("fatherRef", father == null ? null : father.npcRef());
            data.put("gameSecond", gameSecond);
            sandboxSseHub.publish(projectId, WorldSandboxSseHub.EVENT_NPC_BIRTH, data);
        } catch (Exception e) {
            log.warn("[新生儿] SSE 下行失败（隔离继续）: {}", e.getMessage());
        }
    }

    /** 基因组 JSON 序列化（宽容兜底）。 */
    private String writeGenome(GenomeData g) {
        try {
            return PersonaJson.mapper().writeValueAsString(g);
        } catch (Exception e) {
            return "{\"loci\":[],\"derived_traits\":{}}";
        }
    }
}
