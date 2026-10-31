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

package com.holzyn.actor.domain.growth.lod;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认知 LOD 服务。
 * <p><b>档位</b>：{@code dormant}（纯程序化，零 AI）/ {@code ambient}（AI 空间 + 轻频）/
 * {@code focus}（AI 空间 + 实时）。</p>
 * <p><b>升降档触发</b>：</p>
 * <ul>
 * <li>升档（任一命中 → focus）：重要度 ≥4（特殊 NPC 常驻）；近期被事件涉及（importance ≥3，
 * {@code eventPromoteWindowGameMinutes} 窗口内）；与 focus 角色<b>同地点</b>；
 * 与 focus 角色<b>关系跳数 ≤1</b>；相机距离 < {@code cameraNearMeters}（有相机时）；手动覆盖；</li>
 * <li>降档：离开焦点条件后<b>滞回窗口</b>（{@code downgradeHysteresisGameMinutes}，防抖动）才降回默认档；</li>
 * <li>默认档：special=focus / common=ambient（{@code default-tier-*} 可配）。</li>
 * </ul>
 * <p><b>调度口径</b>：ambient 与 focus 都参与每小时
 * 决策调度，差别在空间定期兜底频率（focus 1h / ambient 6h）与空间来源（dormant=程序化零 AI）。
 * dormant 当前环境默认不可达（除非手动覆盖/配置默认档= dormant/有相机且远离）。</p>
 * <p><b>接线</b>：{@code WorldEngine} 每小时调用 {@link #refreshProject}——计算 + 持久化
 * {@code actor_profile.brain_tier} + 返回升档清单（升档由引擎触发空间重算 LOD_PROMOTE，
 * 避免与 {@code GrowthSpaceService} 循环依赖）；降档不触发重算（空间 TTL 自然收敛）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrainTierService {

    /** 档位码（与 actor_profile.brain_tier 存库值一致）。 */
    public static final String TIER_DORMANT = "dormant";
    public static final String TIER_AMBIENT = "ambient";
    public static final String TIER_FOCUS = "focus";

    /** 手动覆盖（调试接口热更新：npcRef → 档位码；进程内，重启清零）。 */
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    /** 滞回跟踪：npcRef → {当前生效档, 升档游戏时刻}（降档需要离开焦点条件满滞回窗口）。 */
    private final Map<String, TierTrack> tracks = new ConcurrentHashMap<>();

    /**
     * 注意力语义通道跟踪：npcRef → {建议档位, 生效至游戏秒}（，：
     * 规则硬基线 + 决策模型 双语义通道——注意力只做语义调整，规则（重要度/事件/同地/关系/相机）
     * 仍是硬基线；语义升档/降档带保持窗口防每 tick 抖动）。
     */
    private final Map<String, SemanticTrack> semantic = new ConcurrentHashMap<>();

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorEventRepository eventRepository;
    private final GrowthProperties growthProperties;
    private final CameraDistanceProvider cameraDistanceProvider;

    /**
     * 计算某 NPC 当前档位（读缓存语义：不持久化；决策/空间服务消费）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @return 档位码（dormant/ambient/focus）
     */
    public String tierOf(Long projectId, String npcRef) {
        String ov = overrides.get(npcRef);
        if (ov != null) {
            return ov;
        }
        TierTrack t = tracks.get(npcRef);
        if (t != null) {
            return t.tier;
        }
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        if (ch == null) {
            return TIER_DORMANT;
        }
        String tier = defaultTierOf(ch);
        tracks.put(npcRef, new TierTrack(tier, null));
        return tier;
    }

    /** 默认档位（special=focus / common=ambient；DECEASED=dormant）。 */
    private String defaultTierOf(ActorCharacter ch) {
        if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
            return TIER_DORMANT;
        }
        boolean special = !"common".equalsIgnoreCase(ch.getType());
        String def = special
                ? growthProperties.getLod().getDefaultTierSpecial()
                : growthProperties.getLod().getDefaultTierCommon();
        return normalizeTier(def, special ? TIER_FOCUS : TIER_AMBIENT);
    }

    private String normalizeTier(String tier, String fallback) {
        if (TIER_FOCUS.equals(tier) || TIER_AMBIENT.equals(tier) || TIER_DORMANT.equals(tier)) {
            return tier;
        }
        return fallback;
    }

    /**
     * 全项目 LOD 刷新（WorldEngine 每小时调用）：计算 + 持久化 brain_tier + 返回升档清单。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻（滞回窗口/事件窗口基准）
     *
     * @return 升档清单（{@code npcRef} 列表；调用方应触发 LOD_PROMOTE 空间重算）
     */
    public List<String> refreshProject(Long projectId, Long gameSecond) {
        List<String> promotions = new ArrayList<>();
        if (!growthProperties.isEnabled() || !growthProperties.getLod().isEnabled()) {
            return promotions;
        }
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                String computed = computeTier(projectId, ch, gameSecond);
                String previous = ch.getBrainTier() == null ? defaultTierOf(ch) : ch.getBrainTier();
                if (!computed.equals(previous)) {
                    ch.setBrainTier(computed);
                    characterRepository.save(ch);
                    log.info("[认知LOD] 档位变化：{} {}→{}", ch.npcRef(), previous, computed);
                }
                // 升档（ambient/focus 提升或默认档提升）→ 记录并返回（引擎触发空间重算）
                boolean upgraded = isHigher(computed, previous);
                TierTrack track = tracks.computeIfAbsent(ch.npcRef(), k -> new TierTrack(computed, null));
                track.tier = computed;
                if (upgraded) {
                    track.promotedAtGameSecond = gameSecond;
                    promotions.add(ch.npcRef());
                } else if (computed.equals(defaultTierOf(ch))) {
                    // 回到默认档：清除升档痕迹（下次升档重新计窗口）
                    track.promotedAtGameSecond = null;
                }
            } catch (Exception e) {
                log.warn("[认知LOD] 档位计算失败（跳过）: profileId={} 原因={}", ch.getId(), e.getMessage());
            }
        }
        if (!promotions.isEmpty()) {
            log.info("[认知LOD] 本次升档 {} 人：{}", promotions.size(), promotions);
        }
        return promotions;
    }

    /** 计算档位（纯判定；升档条件任一命中 → focus，否则回默认档）。 */
    private String computeTier(Long projectId, ActorCharacter ch, Long gameSecond) {
        String ov = overrides.get(ch.npcRef());
        if (ov != null) {
            return ov;
        }
        String def = defaultTierOf(ch);
        if (TIER_DORMANT.equals(def)) {
            return TIER_DORMANT;
        }
        // ① 规则升档条件（任一命中 → focus）——规则硬基线
        if (promoteToFocus(projectId, ch, gameSecond)) {
            return TIER_FOCUS;
        }
        // ② 降档滞回：当前是 focus（来自规则升档）但条件已失 → 满滞回窗口才降回默认
        TierTrack track = tracks.get(ch.npcRef());
        if (track != null && TIER_FOCUS.equals(track.tier) && track.promotedAtGameSecond != null
                && gameSecond != null) {
            long windowSeconds = (long) growthProperties.getLod().getDowngradeHysteresisGameMinutes() * 60L;
            if (gameSecond - track.promotedAtGameSecond < windowSeconds) {
                return TIER_FOCUS; // 滞回期内保持 focus
            }
        }
        // ③ 注意力语义通道：语义升档窗口内 → focus；语义降档（仅普通 NPC，
        // 且当前生效档来自升档）→ ambient。规则硬基线优先（①② 已先返回 focus）。
        if (gameSecond != null) {
            SemanticTrack sem = semantic.get(ch.npcRef());
            if (sem != null && gameSecond < sem.untilGameSecond) {
                if (TIER_FOCUS.equals(sem.tier)) {
                    return TIER_FOCUS; // 注意力高 → 语义升档（保持窗口防抖动）
                }
                if (TIER_AMBIENT.equals(sem.tier) && "common".equalsIgnoreCase(ch.getType())
                        && track != null && TIER_FOCUS.equals(track.tier)) {
                    return TIER_AMBIENT; // 注意力低（普通 NPC）→ 语义降档回 ambient
                }
            }
        }
        return def;
    }

    /**
     * 注意力语义通道入口（，每 tick 微决策/事件相关度批量结果消费）：
     * 语义跟踪（level≥3 高关注 → 语义升档 focus；level≤1 低关注且普通 NPC → 语义降档
     * ambient；level=2 中性 → 清语义痕迹），并即刻重算持久化（仅在档位变化时落库，
     * 防每 tick 写放大）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @param level 注意力档位（0=无 / 1=低 / 2=中 / 3=高；决策模型 score 档位）
     *
     * @param gameSecond 当前世界时刻（可空=不设置语义窗口，仅清痕迹）
     */
    public void applyAttention(Long projectId, String npcRef, int level, Long gameSecond) {
        if (growthProperties == null || !growthProperties.isEnabled() || !growthProperties.getLod().isEnabled()
                || !growthProperties.getLod().isSemanticEnabled()) {
            return;
        }
        try {
            ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
            if (ch == null) {
                return;
            }
            boolean special = !"common".equalsIgnoreCase(ch.getType());
            if (level >= 3) {
                semantic.put(npcRef, new SemanticTrack(TIER_FOCUS,
                        (gameSecond == null ? 0L : gameSecond)
                                + (long) growthProperties.getLod().getSemanticFocusGameMinutes() * 60L));
            } else if (level <= 1 && !special) {
                semantic.put(npcRef, new SemanticTrack(TIER_AMBIENT,
                        (gameSecond == null ? 0L : gameSecond)
                                + (long) growthProperties.getLod().getSemanticDemoteGameMinutes() * 60L));
            } else {
                semantic.remove(npcRef); // 中性：清语义痕迹
            }
            // 语义变化即刻生效：重算当前档位并持久化（仅档位变化时落库）
            long base = gameSecond == null ? 0L : gameSecond;
            String computed = computeTier(projectId, ch, base);
            String previous = ch.getBrainTier() == null ? defaultTierOf(ch) : ch.getBrainTier();
            if (!computed.equals(previous)) {
                ch.setBrainTier(computed);
                characterRepository.save(ch);
                log.info("[认知LOD] 注意力语义通道：{} {}→{}（attention level={}）", npcRef, previous, computed, level);
            }
            TierTrack t = tracks.computeIfAbsent(npcRef, k -> new TierTrack(computed, null));
            t.tier = computed;
        } catch (Exception e) {
            log.debug("[认知LOD] 注意力语义通道应用失败（跳过）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /** 升档判定：重要度 ≥4 / 近期被事件涉及 / 与 focus 同地 / 与 focus 关系跳数 ≤1 / 相机近距离。 */
    private boolean promoteToFocus(Long projectId, ActorCharacter ch, Long gameSecond) {
        // ① 重要度 ≥4（特殊 NPC 常驻焦点）
        if (ch.getImportance() != null && ch.getImportance() >= 4) {
            return true;
        }
        // ② 近期被事件涉及（importance ≥3，窗口内；事件表按 id 倒序扫最近 100 条）
        Long windowStart = gameSecond == null ? null
                : gameSecond - (long) growthProperties.getLod().getEventPromoteWindowGameMinutes() * 60L;
        if (windowStart != null && recentlyInvolvedInEvent(projectId, ch.getId(), windowStart)) {
            return true;
        }
        // ③ 与 focus 角色同地点
        if (sameLocationWithFocus(projectId, ch)) {
            return true;
        }
        // ④ 与 focus 角色关系跳数 ≤1（直接关系即可，一跳由同地点覆盖大部分场景）
        if (directRelationWithFocus(projectId, ch)) {
            return true;
        }
        // ⑤ 相机近距离（有相机时；Web 环境无相机自动跳过）
        if (cameraDistanceProvider != null && cameraDistanceProvider.hasActiveCamera(projectId)) {
            Double d = cameraDistanceProvider.distanceMeters(projectId, ch.npcRef());
            if (d != null && d <= growthProperties.getLod().getCameraNearMeters()) {
                return true;
            }
        }
        return false;
    }

    /** 近期事件涉及判定（扫最近 100 条，character_ids/character_id 命中）。 */
    private boolean recentlyInvolvedInEvent(Long projectId, Long profileId, Long windowStartGameSecond) {
        try {
            List<ActorEvent> recent = eventRepository.findByProjectIdOrderByIdDesc(projectId);
            int scanned = 0;
            for (ActorEvent e : recent) {
                if (++scanned > 100) {
                    break;
                }
                if (e.getImportance() == null || e.getImportance() < 3) {
                    continue;
                }
                if (e.getGameHour() != null && windowStartGameSecond != null
                        && e.getGameHour() * 3600L < windowStartGameSecond) {
                    break; // 事件按 id 倒序 ≈ 时间倒序；更早的事件不再考虑
                }
                if (profileId.equals(e.getCharacterId())) {
                    return true;
                }
                if (e.getCharacterIds() != null && e.getCharacterIds().contains("npc:" + profileId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[认知LOD] 事件扫描失败（跳过升档判定）: {}", e.getMessage());
        }
        return false;
    }

    /** 与 focus 角色同地点判定。 */
    private boolean sameLocationWithFocus(Long projectId, ActorCharacter ch) {
        if (ch.getLocationId() == null) {
            return false;
        }
        for (ActorCharacter other : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (other.getId().equals(ch.getId()) || !TIER_FOCUS.equals(tierOf(projectId, other.npcRef()))) {
                continue;
            }
            if (other.getLocationId() != null && other.getLocationId().equals(ch.getLocationId())) {
                return true;
            }
        }
        return false;
    }

    /** 与 focus 角色直接关系判定（关系跳数 ≤1）。 */
    private boolean directRelationWithFocus(Long projectId, ActorCharacter ch) {
        List<ActorCharacterRelation> relations = relationRepository.findByProjectId(projectId);
        for (ActorCharacterRelation r : relations) {
            if (r == null) {
                continue;
            }
            boolean from = ch.getId().equals(r.getFromCharacterId());
            boolean to = ch.getId().equals(r.getToCharacterId());
            if (!from && !to) {
                continue;
            }
            Long otherId = from ? r.getToCharacterId() : r.getFromCharacterId();
            if (otherId == null) {
                continue;
            }
            ActorCharacter other = characterRepository.findById(otherId).orElse(null);
            if (other != null && TIER_FOCUS.equals(tierOf(projectId, other.npcRef()))) {
                return true;
            }
        }
        return false;
    }

    /** 档位高低比较（升档判定：focus > ambient > dormant）。 */
    private boolean isHigher(String a, String b) {
        return rank(a) > rank(b);
    }

    private int rank(String tier) {
        return switch (tier == null ? TIER_DORMANT : tier) {
            case TIER_FOCUS -> 2;
            case TIER_AMBIENT -> 1;
            default -> 0;
        };
    }

    /** 手动覆盖（调试接口）：npcRef → 档位；null 清除覆盖。 */
    public void setOverride(String npcRef, String tier) {
        if (tier == null || tier.isBlank() || "clear".equalsIgnoreCase(tier)) {
            overrides.remove(npcRef);
            return;
        }
        overrides.put(npcRef, normalizeTier(tier, TIER_AMBIENT));
        // 覆盖即刻生效：清滞回痕迹
        tracks.remove(npcRef);
    }

    /** 全部覆盖（调试页展示）。 */
    public Map<String, String> overrides() {
        return new ConcurrentHashMap<>(overrides);
    }

    /** 滞回跟踪条目。 */
    private static final class TierTrack {
        String tier;
        Long promotedAtGameSecond;

        TierTrack(String tier, Long promotedAtGameSecond) {
            this.tier = tier;
            this.promotedAtGameSecond = promotedAtGameSecond;
        }
    }

    /** 注意力语义跟踪条目。 */
    private static final class SemanticTrack {
        String tier;
        long untilGameSecond;

        SemanticTrack(String tier, long untilGameSecond) {
            this.tier = tier;
            this.untilGameSecond = untilGameSecond;
        }
    }
}
