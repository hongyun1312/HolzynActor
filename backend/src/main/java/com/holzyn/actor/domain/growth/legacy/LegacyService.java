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

package com.holzyn.actor.domain.growth.legacy;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestone;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 身后事 · 遗物/遗产转移服务。
 * <p><b>不做经济系统</b>：遗产是档案文本级（① 维 property + ⑤ 维 valueWeights 中的资源项），
 * 不建资产负债表——本服务把亡者档案中的财产文本按继承顺序转移到受让人档案，并留全事件/记忆/
 * 里程碑审计链。</p>
 * <p><b>继承顺序</b>：配偶 → 长子/长女 → 父母 → 族中公产（无在世血亲时，不落个人——
 * 写一条"遗产归族中"的编年史即可）。</p>
 * <p><b>DECEASED 谱系标记</b>：死亡不删谱系边（actor_growth_lineage 只增不改）——亡者的
 * 出生/婚姻/亲子信息由谱系持续承载；本服务在死亡链路中额外把亡者与继承人的
 * 关系留痕为 milestone + 记忆（身后事闭环）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyService {

    private final ActorCharacterRepository characterRepository;
    private final LineageService lineageService;
    private final PersonaService personaService;
    private final GrowthMilestoneRepository milestoneRepository;
    private final AgentMemoryService agentMemoryService;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;
    private final GrowthProperties growthProperties;

    /**
     * 执行身后事（由 {@code DevelopmentalStageService.executeDeath} 死亡链路调用）：
     * 遗产转移（文本级）+ 继承人记忆/里程碑/事件留痕。零阻断（任何失败只告警）。
     *
     * @param projectId 项目 ID
     *
     * @param deceased 亡者档案行
     *
     * @param gameSecond 死亡时刻
     */
    public void transferOnDeath(Long projectId, ActorCharacter deceased, Long gameSecond) {
        if (deceased == null || !growthProperties.getLegacy().isEnabled()) {
            return;
        }
        String ref = deceased.npcRef();
        // ① 遗产文本（亡者档案 ① 维 property + ⑤ 维资源）
        String legacyText = propertyTextOf(deceased);
        if (legacyText == null || legacyText.isBlank()) {
            return; // 无遗产可转
        }
        // ② 继承人（按配置顺序：spouse → eldest_child → parent → clan）
        Optional<String> heirRef = Optional.empty();
        for (String order : growthProperties.getLegacy().getInheritOrder()) {
            switch (order == null ? "" : order) {
                case "spouse" -> {
                    heirRef = lineageService.spouseOf(projectId, ref);
                    if (heirRef.isPresent() && !lineageService.alive(projectId, heirRef.get())) {
                        heirRef = Optional.empty();
                    }
                }
                case "eldest_child" -> heirRef = lineageService.childrenOf(projectId, ref).stream()
                        .filter(c -> lineageService.alive(projectId, c)).findFirst();
                case "parent" -> heirRef = lineageService.parentEdgesOf(projectId, ref).stream()
                        .map(e -> e.getFromRef())
                        .filter(p -> p != null && lineageService.alive(projectId, p))
                        .findFirst();
                default -> heirRef = Optional.empty(); // clan：无个人继承人
            }
            if (heirRef.isPresent()) {
                break;
            }
        }
        if (heirRef.isEmpty()) {
            // 族中公产：编年史留痕，不落个人
            writeClanRecord(projectId, deceased, legacyText, gameSecond);
            return;
        }
        String heir = heirRef.get();
        ActorCharacter heirCh = characterRepository.findById(NpcRefs.idOf(heir)).orElse(null);
        if (heirCh == null) {
            return;
        }
        // ③ 遗产写入受让人档案（① 维 property.notes + heirlooms 追加）
        appendLegacyToHeir(heirCh, deceased.getName(), legacyText);
        // ④ 审计链：继承人记忆（importance=4）+ 里程碑（kind=marker）+ 事件（kind=inheritance）
        agentMemoryService.recordObservation(projectId, heir,
                "继承了" + deceased.getName() + "的遗物与产业：" + legacyText, 4);
        GrowthMilestone m = new GrowthMilestone();
        m.setProjectId(projectId);
        m.setNpcRef(heir);
        m.setGameSecond(gameSecond);
        m.setKind(GrowthMilestone.KIND_MARKER);
        m.setTitle("继承遗产");
        m.setImportance(3);
        m.setDetail("程序化记录：" + heirCh.getName() + " 继承 " + deceased.getName() + " 的遗物/产业：" + legacyText);
        try {
            milestoneRepository.save(m);
        } catch (Exception e) {
            log.warn("[身后事] 遗产里程碑写入失败（隔离继续）: {}", e.getMessage());
        }
        try {
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind("inheritance")
                            .source("growth")
                            .characterId(heirCh.getId())
                            .characterRefs(List.of(heir, ref))
                            .visibility("global")
                            .title("继承遗物")
                            .content(deceased.getName() + "身后，其遗物产业由" + heirCh.getName() + "继承（" + legacyText + "）。")
                            .importance(3)
                            .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                            .sourceKey("growth-inheritance:" + deceased.getId())
                            .build());
        } catch (Exception e) {
            log.warn("[身后事] 继承事件发布失败（隔离继续）: {}", e.getMessage());
        }
        log.info("[身后事] 遗产转移：{} → {}（{}）", deceased.getName(), heirCh.getName(), legacyText);
    }

    /** 提取亡者档案的遗产文本（① 维 property 字段串行化 + ⑤ 维资源备注）。 */
    private String propertyTextOf(ActorCharacter ch) {
        PersonaProfile p = personaService.profileOf(ch.getId());
        if (p == null || p.getDims() == null) {
            return null;
        }
        PersonaDims.Identity identity = p.getDims().getIdentity();
        if (identity == null || identity.getProperty() == null) {
            return null;
        }
        PersonaDims.Property prop = identity.getProperty();
        StringBuilder sb = new StringBuilder();
        if (prop.getHouse() != null && !prop.getHouse().isBlank()) {
            sb.append(prop.getHouse());
        }
        if (prop.getLandMu() != null) {
            if (sb.length() > 0) sb.append("、");
            sb.append("田 ").append(prop.getLandMu()).append(" 亩");
        }
        if (prop.getGrainShi() != null) {
            if (sb.length() > 0) sb.append("、");
            sb.append("存粮 ").append(prop.getGrainShi()).append(" 石");
        }
        if (prop.getMoneyGuan() != null) {
            if (sb.length() > 0) sb.append("、");
            sb.append("钱 ").append(prop.getMoneyGuan()).append(" 贯");
        }
        if (prop.getHeirlooms() != null && !prop.getHeirlooms().isEmpty()) {
            if (sb.length() > 0) sb.append("、");
            sb.append("传家物：").append(String.join("、", prop.getHeirlooms()));
        }
        if (sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    /** 遗产写入受让人档案（① 维 property.heirlooms 追加 + notes 说明来源）。 */
    private void appendLegacyToHeir(ActorCharacter heirCh, String deceasedName, String legacyText) {
        try {
            PersonaProfile p = personaService.profileOrEmpty(heirCh.getId());
            if (p.getDims() == null) {
                p.setDims(new PersonaProfile.Dims());
            }
            PersonaDims.Identity identity = p.getDims().getIdentity();
            if (identity == null) {
                identity = new PersonaDims.Identity();
                p.getDims().setIdentity(identity);
            }
            PersonaDims.Property prop = identity.getProperty();
            if (prop == null) {
                prop = new PersonaDims.Property();
                identity.setProperty(prop);
            }
            prop.getHeirlooms().add(deceasedName + "遗物（" + legacyText + "）");
            String prevNotes = prop.getNotes() == null ? "" : prop.getNotes();
            prop.setNotes((prevNotes.isBlank() ? "" : prevNotes + "；") + "承自" + deceasedName + "：" + legacyText);
            personaService.savePersona(heirCh.getId(), p, "inheritance");
        } catch (Exception e) {
            log.warn("[身后事] 遗产写入受让人档案失败（隔离继续）: {} 原因={}", heirCh.npcRef(), e.getMessage());
        }
    }

    /** 无在世继承人 → 族中公产编年史留痕。 */
    private void writeClanRecord(Long projectId, ActorCharacter deceased, String legacyText, Long gameSecond) {
        try {
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind("inheritance")
                            .source("growth")
                            .characterId(deceased.getId())
                            .characterRefs(List.of(deceased.npcRef()))
                            .visibility("global")
                            .title("遗物归族")
                            .content(deceased.getName() + "身后无近亲承接，其遗物产业（" + legacyText
                                    + "）归入族中公产，由族中议定处置。")
                            .importance(3)
                            .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                            .sourceKey("growth-inheritance-clan:" + deceased.getId())
                            .build());
        } catch (Exception e) {
            log.warn("[身后事] 族中公产记录失败（隔离继续）: {}", e.getMessage());
        }
    }
}
