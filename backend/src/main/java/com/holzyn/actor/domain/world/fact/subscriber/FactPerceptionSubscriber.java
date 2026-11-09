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

package com.holzyn.actor.domain.world.fact.subscriber;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.agent.runtime.RealTimeDecisionService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.service.FactKnowledgeService;
import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.service.InfoDiffusionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 事实感知回流订阅者。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>消费 {@code kind=fact}：按 public/location/private 三档写 knowledge；
 *       对同地点其他特殊 NPC 发 {@code FACT_CHANGED} 刺激；private 事实生成传闻包。</li>
 *   <li>消费扩散到达事件（{@code kind=world_frame} + {@code sourceKey=diffusion:...}）：
 *       把“听说”写进该区域 NPC 的 knowledge（sourceType=rumor，置信度随距离降低）。</li>
 * </ol>
 *
 * <p><b>可见性铁律</b>：private 事实只给 {@code actorRef + attributes.parties}；
 * 地点档只给同地点；public 档才写全项目。任何档位都不把私密内容写进事件/刺激文本之外的地方。</p>
 *
 * <p><b>事务边界</b>：本类只做 DB 写入与事件订阅，不起 AI；FACT_CHANGED 走
 * {@link RealTimeDecisionService} 的异步刺激入口，不阻塞事实写入事务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactPerceptionSubscriber implements WorldEventSubscriber {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorldEventBus worldEventBus;

    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    @Autowired(required = false)
    private FactKnowledgeService factKnowledgeService;

    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    @Autowired(required = false)
    private RealTimeDecisionService realTimeDecisionService;

    @Autowired(required = false)
    private InfoDiffusionService infoDiffusionService;

    @Autowired(required = false)
    private ActorInfoPacketRepository infoPacketRepository;

    @Autowired(required = false)
    private WorldLoopProperties properties;

    /** 启动后注册为世界事件总线订阅者（与记忆/关系订阅者同机制）。 */
    @PostConstruct
    void hookWorldEventBus() {
        worldEventBus.subscribe(this);
    }

    @Override
    public void onEvent(ActorEvent event) {
        if (!enabled() || event == null || event.getProjectId() == null) {
            return;
        }
        try {
            if ("fact".equalsIgnoreCase(event.getKind())) {
                onFactEvent(event);
            } else if (isDiffusionArrival(event)) {
                onRumorArrival(event);
            }
        } catch (Exception e) {
            log.warn("[事实感知] 订阅处理失败（隔离继续）: event={} : {}", event.getId(), e.getMessage());
        }
    }

    // ==================== fact 事件 ====================

    private void onFactEvent(ActorEvent event) {
        JsonNode payload = payload(event);
        String factKey = text(payload, "factKey");
        String op = text(payload, "op");
        if (factKey == null || "expire".equalsIgnoreCase(op) || "void".equalsIgnoreCase(op)) {
            return;
        }
        if (worldFactQuery == null || factKnowledgeService == null) {
            return;
        }
        Optional<ActorWorldFact> found = worldFactQuery.byKey(event.getProjectId(), factKey);
        if (found.isEmpty() || !"active".equalsIgnoreCase(found.get().getStatus())) {
            return;
        }
        ActorWorldFact fact = found.get();
        List<ActorCharacter> chars = characters(event.getProjectId());
        // 对话效果写入时 FactCommand.actorRef 可能为空（EffectContext 不带 actor），
        // 用 attributes.parties 的第一位（承诺人/主体）兜底，保证传闻包有 subject_ref。
        String actorRef = firstNonBlank(text(payload, "actorRef"),
                firstRef(event.getCharacterIds()), firstParty(fact));
        long gameSecond = event.getGameHour() == null ? 0L : event.getGameHour() * 3600L;

        Set<String> recipients = new LinkedHashSet<>();
        if ("private".equalsIgnoreCase(fact.getVisibility())) {
            recipients.addAll(privateParties(fact, actorRef));
            for (String ref : recipients) {
                String sourceType = ref.equals(actorRef) ? "own" : "told";
                learn(event, fact, ref, sourceType, ref.equals(actorRef) ? null : actorRef,
                        100, null, gameSecond);
            }
            // 私密事实不向同地点扩散刺激，避免“不知道内容却作出反应”的泄露。
            publishRumor(event, fact, actorRef, gameSecond);
            return;
        }

        List<ActorCharacter> witnesses = witnesses(fact, chars);
        if ("public".equalsIgnoreCase(fact.getVisibility())) {
            for (ActorCharacter ch : chars) {
                String ref = NpcRefs.of(ch.getId());
                if (ref != null) {
                    learn(event, fact, ref, "public", null, 100, null, gameSecond);
                }
            }
        } else {
            for (ActorCharacter ch : witnesses) {
                String ref = NpcRefs.of(ch.getId());
                if (ref == null) {
                    continue;
                }
                String sourceType = ref.equals(actorRef) ? "own" : "perceive";
                learn(event, fact, ref, sourceType, null, 100, null, gameSecond);
            }
        }
        notifyWitnesses(event, fact, witnesses, actorRef, gameSecond);
    }

    /**
     * 同地点其他特殊 NPC 的 FACT_CHANGED 刺激（不含当事人/玩家）。
     * <p>public 档也只刺激同地点者，避免一次事实变更引发全项目 AI 风暴。</p>
     */
    private void notifyWitnesses(ActorEvent event, ActorWorldFact fact, List<ActorCharacter> witnesses,
                                 String actorRef, long gameSecond) {
        if (realTimeDecisionService == null || witnesses.isEmpty()) {
            return;
        }
        String text = "你注意到「" + displayName(fact) + "」变了：" + safe(fact.getStateText());
        List<String> refs = new ArrayList<>();
        for (ActorCharacter ch : witnesses) {
            if (ch == null || ch.isDeceased() || ch.isPlayer()) {
                continue;
            }
            String ref = NpcRefs.of(ch.getId());
            if (ref != null && !ref.equals(actorRef)) {
                refs.add(ref);
            }
        }
        if (!refs.isEmpty()) {
            realTimeDecisionService.onFactChanged(event.getProjectId(), refs, text, gameSecond);
        }
    }

    /**
     * private 事实生成传闻包：事件本身作为溯源，内容确定性拼装（零 AI）。
     */
    private void publishRumor(ActorEvent event, ActorWorldFact fact, String actorRef, long gameSecond) {
        if (infoDiffusionService == null || fact.getFactKey() == null) {
            return;
        }
        String subject = nameOf(actorRef);
        String content = "听说" + (subject == null ? "那边" : subject + "那边")
                + "出了件事：" + safe(fact.getStateText());
        try {
            infoDiffusionService.publishPacket(event.getProjectId(), event.getId(), content,
                    actorRef, "private", 0.8d, fact.getLocationName(), fact.getFactKey(), gameSecond);
        } catch (Exception e) {
            log.warn("[事实感知] 传闻包生成失败（不影响知识写入）: factKey={} : {}",
                    fact.getFactKey(), e.getMessage());
        }
    }

    // ==================== 传闻到达 ====================

    private void onRumorArrival(ActorEvent event) {
        if (worldFactQuery == null || factKnowledgeService == null || infoPacketRepository == null) {
            return;
        }
        JsonNode payload = payload(event);
        Long packetId = longValue(payload, "packetId");
        if (packetId == null) {
            packetId = packetIdFromSourceKey(event.getSourceKey());
        }
        if (packetId == null) {
            return;
        }
        ActorInfoPacket packet = infoPacketRepository.findById(packetId).orElse(null);
        if (packet == null || packet.getFactKey() == null || packet.getFactKey().isBlank()) {
            return;
        }
        Optional<ActorWorldFact> found = worldFactQuery.byKey(event.getProjectId(), packet.getFactKey());
        if (found.isEmpty() || !"active".equalsIgnoreCase(found.get().getStatus())) {
            return;
        }
        String region = firstNonBlank(text(payload, "region"), event.getLocationName());
        if (region == null) {
            return;
        }
        int level = intValue(payload, "distortionLevel", 1);
        int confidence = Math.max(40, 60 - level * 5);
        String distortion = distortionJson(event, region, level, packetId);
        for (ActorCharacter ch : characters(event.getProjectId())) {
            if (!region.equalsIgnoreCase(safe(ch.getLocation()).trim())) {
                continue;
            }
            String ref = NpcRefs.of(ch.getId());
            if (ref != null) {
                learn(event, found.get(), ref, "rumor", "diffusion:" + packetId,
                        confidence, distortion, event.getGameHour() == null ? 0L : event.getGameHour() * 3600L);
            }
        }
    }

    // ==================== 可见性/当事人 ====================

    /** attributes.parties 的第一位（对话承诺里是 speaker/承诺人）。 */
    private String firstParty(ActorWorldFact fact) {
        JsonNode attrs = parse(fact.getAttributesJson());
        JsonNode parties = attrs == null ? null : attrs.get("parties");
        if (parties != null && parties.isArray()) {
            for (JsonNode node : parties) {
                String ref = node == null || node.isNull() ? null : node.asText();
                if (ref != null && !ref.isBlank()) {
                    return ref.trim();
                }
            }
        }
        return null;
    }

    /** private 事实的当事人：actorRef + attributes.parties（宁少不滥）。 */
    private Set<String> privateParties(ActorWorldFact fact, String actorRef) {
        Set<String> refs = new LinkedHashSet<>();
        if (actorRef != null) {
            refs.add(actorRef);
        }
        JsonNode attrs = parse(fact.getAttributesJson());
        JsonNode parties = attrs == null ? null : attrs.get("parties");
        if (parties != null && parties.isArray()) {
            for (JsonNode node : parties) {
                String ref = node == null || node.isNull() ? null : node.asText();
                if (ref != null && !ref.isBlank()) {
                    refs.add(ref.trim());
                }
            }
        }
        return refs;
    }

    /** 同地点在场者（口径：actor_profile.location/location_id 优先，与一致）。 */
    private List<ActorCharacter> witnesses(ActorWorldFact fact, List<ActorCharacter> chars) {
        List<ActorCharacter> out = new ArrayList<>();
        for (ActorCharacter ch : chars) {
            if (ch == null || ch.isDeceased()) {
                continue;
            }
            if (atLocation(ch, fact)) {
                out.add(ch);
            }
        }
        return out;
    }

    private boolean atLocation(ActorCharacter ch, ActorWorldFact fact) {
        if (fact.getLocationId() != null && ch.getLocationId() != null
                && fact.getLocationId().equals(ch.getLocationId())) {
            return true;
        }
        String factLocation = fact.getLocationName();
        return factLocation != null && ch.getLocation() != null
                && factLocation.equalsIgnoreCase(ch.getLocation().trim());
    }

    private void learn(ActorEvent event, ActorWorldFact fact, String ref, String sourceType,
                       String sourceRef, int confidence, String distortion, long gameSecond) {
        if (ref == null || ref.isBlank() || factKnowledgeService == null || fact.getId() == null) {
            return;
        }
        if (worldFactQuery != null && worldFactQuery.knows(event.getProjectId(), fact.getFactKey(), ref)) {
            return;
        }
        factKnowledgeService.learnAt(event.getProjectId(), fact.getId(), ref, sourceType,
                sourceRef, confidence, distortion, gameSecond);
    }

    // ==================== 工具 ====================

    private boolean enabled() {
        return properties == null || properties.isEnabled();
    }

    private List<ActorCharacter> characters(Long projectId) {
        if (characterRepository == null) {
            return List.of();
        }
        return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).stream()
                .filter(ch -> ch != null && !ch.isDeceased())
                .toList();
    }

    private boolean isDiffusionArrival(ActorEvent event) {
        return "world_frame".equalsIgnoreCase(event.getKind())
                && event.getSourceKey() != null
                && event.getSourceKey().startsWith("diffusion:");
    }

    private String nameOf(String ref) {
        if (ref == null || characterRepository == null) {
            return null;
        }
        try {
            String id = ref.startsWith("npc:") ? ref.substring(4) : ref;
            Long numeric = Long.parseLong(id);
            return characterRepository.findById(numeric).map(ActorCharacter::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String displayName(ActorWorldFact fact) {
        return fact.getName() == null || fact.getName().isBlank() ? fact.getFactKey() : fact.getName();
    }

    private String distortionJson(ActorEvent event, String region, int level, Long packetId) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("level", level);
            map.put("region", region);
            map.put("packetId", packetId);
            map.put("sourceEventId", event.getId());
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode payload(ActorEvent event) {
        return parse(event == null ? null : event.getPayloadJson());
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        try {
            return Long.parseLong(node.get(field).asText());
        } catch (Exception e) {
            return null;
        }
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        Long value = longValue(node, field);
        return value == null ? fallback : value.intValue();
    }

    private static Long packetIdFromSourceKey(String sourceKey) {
        if (sourceKey == null) {
            return null;
        }
        String[] parts = sourceKey.split(":");
        if (parts.length < 5 || !"diffusion".equals(parts[0])) {
            return null;
        }
        try {
            return Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstRef(String refs) {
        if (refs == null || refs.isBlank()) {
            return null;
        }
        for (String part : refs.split(",")) {
            if (part != null && !part.isBlank()) {
                return part.trim();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
