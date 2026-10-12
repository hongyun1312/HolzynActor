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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信息感知三层过滤 + 【世界动态】段注入。
 * <p>职责：NPC 感知查询时对可见信息包应用
 * <b>空间 → 社会 → 认知</b>三层过滤，并把结果注入决策/对话/人群 AI 决策的【世界动态】段
 * （≤3 条、每条 ≤60 字、总预算 300—— 双轨全 NPC、 社会层空则放行+或关系、
 * / 认知失真 AI 改写+缓存收敛）。</p>
 * <ul>
 * <li><b>空间边界</b>：NPC 所在地
 * ∈ 扩散区域全集 且 信息包已扩散到达该区域（actor_info_diffusion 行）且未过期；</li>
 * <li><b>社会边界</b>：visibility_condition（profession/affiliation/location）为空或全空 → 放行；
 * 非空 → 三字段<b>或</b>关系命中（普通 NPC 用 occupation/affiliation/location，特殊用
 * title/name/location，包含匹配容错），全部未命中 → 该 NPC 不知晓；</li>
 * <li><b>认知失真</b>：失真规则非空且可见 → {@link InfoDistortionService#distortForNpc}
 * AI 改写（（NPC×包×游戏日）缓存收敛，失败回退原文），失真文本随注入生成不落库。</li>
 * </ul>
 * <p>注入顺序（主方案 §4.4：核心 → 状态 → <b>世界动态</b>→ 当前所见 → L1）——PerceptionService
 * 在状态段后、当前所见前插入本段；人群 AI 决策（npc_crowd_decision）经 NpcStateService 注入同段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfoPerceptionService {

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorInfoPacketRepository packetRepository;
    private final ActorInfoDiffusionRepository diffusionRepository;
    private final InfoDiffusionService diffusionService;
    private final InfoDistortionService distortionService;
    private final WorldInfoProperties props;
    private final ObjectMapper objectMapper;

    // ==================== 对外模型 ====================

    /** NPC 三维过滤后可见的信息包（含失真文本与到达上下文，供注入与调试预览）。 */
    public record PerceivedPacket(Long packetId, String infoId, String origin, String region,
                                  String content, String perceivedText, Double reliability,
                                  String relayedBy, Long reachedGameSecond, Double distanceKm) {
    }

    /** 社会层过滤条件（来自信息包 visibility_condition JSON）。 */
    record VisibilityCondition(List<String> profession, List<String> affiliation, List<String> location) {
    }

    /** 解析出的 NPC 身份/所在地（三层过滤输入）。 */
    record NpcInfo(String ref, String name, String title, String occupation, String affiliation,
                   String location, Long locationId) {
    }

    // ==================== 入口：可见信息包（三层过滤） ====================

    /**
     * 三层过滤：返回该 NPC 当前可感知的信息包（空间→社会→认知；按到达时刻降序、可信度降序，
     * 截断至 inject-max-packets 条；每条含失真文本与到达上下文）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（{@code npc:{id}}）
     *
     * @param gameSecond 游戏秒（可空=不过期过滤）
     *
     * @return 可见信息包列表（≤inject-max-packets；无可感知返回空列表）
     */
    public List<PerceivedPacket> visiblePackets(Long projectId, String characterRef, Long gameSecond) {
        if (projectId == null || characterRef == null || characterRef.isBlank()) {
            return List.of();
        }
        try {
            NpcInfo npc = resolve(projectId, characterRef);
            if (npc == null) {
                return List.of();
            }
            // 区域全集 + NPC 双轨匹配区域（地点名精确 → affected_area 原文包含兜底）
            List<String> regions = diffusionService.regionsOfProject(projectId);
            Map<Long, String> idToName = locationIdToName(projectId);
            List<String> matched = matchedRegions(npc.location(), npc.locationId(), regions, idToName);
            if (matched.isEmpty()) {
                return List.of();
            }
            long day = gameSecond == null ? Long.MAX_VALUE : Math.floorDiv(gameSecond, 86400L);
            Map<String, double[]> coords = diffusionService.coordinatesOf(projectId);
            double fallbackKm = props.getDistanceFallbackKm();

            List<ActorInfoPacket> packets = packetRepository.findByProjectIdAndStatus(projectId, "active");
            List<PerceivedPacket> out = new ArrayList<>();
            for (ActorInfoPacket packet : packets) {
                // 过期防御（扩散引擎已维护 expired，此处双保险）：
                // gameSecond 为空（如调试预览 npc-visible）→ 不做过期过滤（MaxValue 哨兵不参与比较）
                if (gameSecond != null && packet.getValidUntilDay() != null && day > packet.getValidUntilDay()) {
                    continue;
                }
                // 空间层：扩散到达区域 ∩ NPC 匹配区域；扩散关时退化为「origin 在场即知晓」
                Map<String, ActorInfoDiffusion> reached;
                if (!props.isDiffusionEnabled()) {
                    reached = Map.of();
                    if (packet.getOrigin() != null && matched.contains(packet.getOrigin())) {
                        ActorInfoDiffusion fake = new ActorInfoDiffusion();
                        fake.setRegion(packet.getOrigin());
                        fake.setReachedGameSecond(day * 86400L);
                        fake.setRelayedBy("消息源头");
                        reached = Map.of(packet.getOrigin(), fake);
                    }
                } else {
                    reached = reachedMap(packet.getId());
                }
                ActorInfoDiffusion hit = bestHit(matched, reached);
                if (hit == null || hit.getRegion() == null) {
                    continue;
                }
                // 社会层
                VisibilityCondition vc = parseVisibility(packet.getVisibilityConditionJson());
                if (!socialPass(vc, npc)) {
                    continue;
                }
                // 认知层（失真随注入生成不落库）
                double distKm = InfoDiffusionService.distanceKm(
                        coords.get(packet.getOrigin()), coords.get(hit.getRegion()), fallbackKm);
                String relayed = hit.getRelayedBy() == null || hit.getRelayedBy().isBlank()
                        ? InfoDiffusionService.relayedBy(packet.getId(),
                        diffusionService.parseStringList(packet.getSpreadChannelsJson()), hit.getRegion())
                        : hit.getRelayedBy();
                String spreadContext = "消息经" + relayed + "从" + (packet.getOrigin() == null
                        || packet.getOrigin().isBlank() ? "他处" : packet.getOrigin())
                        + "传来，距事发地" + InfoDiffusionService.formatLi(distKm);
                String npcLabel = npcLabel(npc);
                String perceived = distortionService.distortForNpc(projectId, npc.ref(), npcLabel,
                        packet.getId(), packet.getContent(),
                        diffusionService.parseStringList(packet.getDistortionRulesJson()), spreadContext, day);
                out.add(new PerceivedPacket(packet.getId(), packet.getInfoId(), packet.getOrigin(),
                        hit.getRegion(), packet.getContent(), perceived, packet.getReliability(),
                        relayed, hit.getReachedGameSecond(), distKm));
            }
            out.sort(Comparator.comparing(PerceivedPacket::reachedGameSecond,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(PerceivedPacket::reliability,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            return out.size() > props.getInjectMaxPackets()
                    ? out.subList(0, props.getInjectMaxPackets()) : out;
        } catch (Exception e) {
            log.warn("[信息感知] 三层过滤失败（返回空）: project={} ref={} : {}", projectId, characterRef, e.getMessage());
            return List.of();
        }
    }

    /**
     * 【世界动态】段：
     * 可见信息包 ≤3 条、每条 ≤60 字、总预算 inject-budget-tokens（字符）；无内容返回空串。
     * 供 PerceptionService（决策/对话）与 NpcStateService（人群 AI 决策）调用。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用
     *
     * @param gameSecond 游戏秒（可空）
     *
     * @return 【世界动态】段文本（无可注入内容返回空串）
     */
    public String worldDynamicsSegment(Long projectId, String characterRef, Long gameSecond) {
        if (!props.isInjectEnabled()) {
            return "";
        }
        List<PerceivedPacket> visible = visiblePackets(projectId, characterRef, gameSecond);
        if (visible.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【世界动态】");
        int used = 5; // 段头占位预算
        int budget = props.getInjectBudgetTokens() <= 0 ? 300 : props.getInjectBudgetTokens();
        for (PerceivedPacket p : visible) {
            String text = abbreviate(p.perceivedText() == null ? p.content() : p.perceivedText(),
                    props.getMaxInjectTextLength());
            String relayed = p.relayedBy() == null || p.relayedBy().isBlank() ? "传闻" : p.relayedBy();
            String line = "\n· " + (p.region() == null ? "某地" : p.region()) + "：" + text
                    + "（经" + relayed + "传来）";
            if (used + line.length() > budget) {
                break; // 预算截断（丢弃多余条）
            }
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * NPC 所在地 → 匹配区域：<b>① 地点名精确</b>——locationId 解析出的地点名或
     * location 字符串在区域全集中；精确未命中再走 <b>② affected_area 原文包含匹配</b>——区域串与
     * NPC 所在地双向包含（容错 AI 区域名与地点名不一致）。
     *
     * @param npcLocation NPC 所在地名（可空）
     *
     * @param npcLocationId NPC 归一化地点 ID（可空）
     *
     * @param regions 扩散区域全集
     *
     * @param idToName 地点 ID → 地点名（可空）
     *
     * @return 匹配区域列表（可能多个；无匹配返回空）
     */
    static List<String> matchedRegions(String npcLocation, Long npcLocationId, List<String> regions,
                                       Map<Long, String> idToName) {
        List<String> out = new ArrayList<>();
        if (regions == null || regions.isEmpty()) {
            return out;
        }
        // ① 地点名精确匹配（locationId 优先，其次 location 字符串）
        String exact = null;
        if (npcLocationId != null && idToName != null && idToName.containsKey(npcLocationId)) {
            exact = idToName.get(npcLocationId);
        }
        if (exact == null && npcLocation != null && !npcLocation.isBlank()) {
            exact = npcLocation;
        }
        if (exact != null) {
            if (regions.contains(exact)) {
                out.add(exact);
            } else {
                // ② 原文包含匹配
                for (String r : regions) {
                    if (r != null && (npcLocation != null && (npcLocation.contains(r) || r.contains(npcLocation)))) {
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 社会层过滤：visibility_condition 为空或三字段全空
     * → 放行；否则任一字段命中即放行（profession 匹配特殊 NPC 的 title/name、普通 occupation；
     * affiliation 匹配普通 affiliation；location 匹配 NPC 所在地；包含匹配容错），全未命中 → 拦截。
     */
    static boolean socialPass(VisibilityCondition vc, NpcInfo npc) {
        if (vc == null || npc == null) {
            return true;
        }
        boolean any = false;
        if (notEmpty(vc.profession())) {
            any = true;
            if (matchesAny(vc.profession(), labels(npc.title(), npc.name(), npc.occupation()))) {
                return true;
            }
        }
        if (notEmpty(vc.affiliation())) {
            any = true;
            if (matchesAny(vc.affiliation(), labels(npc.affiliation()))) {
                return true;
            }
        }
        if (notEmpty(vc.location())) {
            any = true;
            if (matchesAny(vc.location(), labels(npc.location()))) {
                return true;
            }
        }
        return !any; // 全空 → 放行；有字段但未命中 → 拦截
    }

    /** 条件列表与标签列表包含匹配（双向包含容错）。 */
    private static boolean matchesAny(List<String> conditions, List<String> labels) {
        if (conditions == null) {
            return false;
        }
        for (String c : conditions) {
            if (c == null || c.isBlank()) {
                continue;
            }
            for (String label : labels) {
                if (label != null && !label.isBlank() && (c.contains(label) || label.contains(c))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 非空标签列表（过滤 null/空白，防 List.of 抛 NPE）。 */
    private static List<String> labels(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    // ==================== 私有辅助 ====================

    /** 解析角色身份。 */
    private NpcInfo resolve(Long projectId, String characterRef) {
        // ：npc:{id} 不再编码类型，此处按意图 A/B 处理（单表按 id 取行，字段口径由行内 type 决定）
        Long id = NpcRefs.idOf(characterRef);
        ActorCharacter ch = id == null ? null : characterRepository.findById(id).orElse(null);
        if (ch == null) {
            return null;
        }
        if (NpcRefs.isCommonType(ch.getType())) {
            return new NpcInfo(characterRef, ch.getName(), null, ch.getOccupation(), ch.getAffiliation(),
                    ch.getLocation(), ch.getLocationId());
        }
        return new NpcInfo(characterRef, ch.getName(), ch.getTitle(), null, null,
                ch.getLocation(), ch.getLocationId());
    }

    /** 地点 ID → 地点名（区域精确匹配 track1 用）。 */
    private Map<Long, String> locationIdToName(Long projectId) {
        Map<Long, String> out = new LinkedHashMap<>();
        List<ActorWorldLocation> locations = locationRepository.findByProjectId(projectId);
        for (ActorWorldLocation l : locations) {
            if (l.getId() != null && l.getName() != null && !l.getName().isBlank()) {
                out.put(l.getId(), l.getName());
            }
        }
        return out;
    }

    /** 某包扩散行 → {区域 → 扩散行}。 */
    private Map<String, ActorInfoDiffusion> reachedMap(Long packetId) {
        Map<String, ActorInfoDiffusion> out = new LinkedHashMap<>();
        List<ActorInfoDiffusion> rows = diffusionRepository.findByPacketId(packetId);
        for (ActorInfoDiffusion d : rows) {
            if (d.getRegion() != null && !d.getRegion().isBlank()) {
                out.put(d.getRegion(), d);
            }
        }
        return out;
    }

    /** 匹配区域 ∩ 已到达区域中「最近到达」的那条（同信息多区域时以最新者为准）。 */
    private ActorInfoDiffusion bestHit(List<String> matched, Map<String, ActorInfoDiffusion> reached) {
        ActorInfoDiffusion best = null;
        for (String m : matched) {
            ActorInfoDiffusion d = reached.get(m);
            if (d == null) {
                continue;
            }
            if (best == null || (d.getReachedGameSecond() != null
                    && (best.getReachedGameSecond() == null || d.getReachedGameSecond() > best.getReachedGameSecond()))) {
                best = d;
            }
        }
        return best;
    }

    /** 信息包 visibility_condition JSON → 结构化条件（非法/空返回 null=社会层放行）。 */
    private VisibilityCondition parseVisibility(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            return new VisibilityCondition(
                    stringList(node.get("profession")),
                    stringList(node.get("affiliation")),
                    stringList(node.get("location")));
        } catch (Exception e) {
            return null;
        }
    }

    /** NPC 身份标签（失真改写受众上下文：姓名·头衔/职业）。 */
    private static String npcLabel(NpcInfo npc) {
        StringBuilder sb = new StringBuilder();
        if (npc.name() != null && !npc.name().isBlank()) {
            sb.append(npc.name());
        }
        String role = npc.title() != null && !npc.title().isBlank() ? npc.title() : npc.occupation();
        if (role != null && !role.isBlank()) {
            if (sb.length() > 0) {
                sb.append("·");
            }
            sb.append(role);
        }
        return sb.length() == 0 ? "（未知身份）" : sb.toString();
    }

    /** 列表非空判定。 */
    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    /** JSON 数组文本 → 字符串列表（容错空）。 */
    private List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        node.forEach(n -> {
            String t = n == null ? "" : n.asText("").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        });
        return out;
    }

    /** 文本截断（null 回退 null）。 */
    private String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
