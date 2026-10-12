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

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.config.BehaviorDiversityProperties;
import com.holzyn.actor.domain.world.entity.ActorMacroDiversity;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.repository.ActorMacroDiversityRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宏观社会演化 · 行为多样性注入器。
 * <p>职责：让特殊 NPC 的 AI 决策不再「千人一面」——决策前注入三类噪声（合成【状态涟漪】段）：
 * 同一情境不同角色给出有差异、可解释、可回放的行动：</p>
 * <ul>
 * <li><b>① 偏好波动（OCEAN 派生）</b>：每角色稳定的偏好基线（无显式人格库时由 actorRef
 * 确定性派生——同一角色每次一致；显式 traits 可覆盖）+ 日内波动；幅度受人格稳定度钳制
 * （高尽责性/高一致性角色波动小——不破坏长期人格一致性）；</li>
 * <li><b>② 情绪干扰（PAD 偏离基线）</b>：NpcState 的愉悦/情绪强度偏离基线 → 行动倾向
 * （愉悦低→谨慎保守、愉悦高→积极冒进）；</li>
 * <li><b>③ 社会参照（同组+关系）</b>：同组成员近期行为（入账流水窗口内同类行为 ≥2 人）
 * → 从众方向（社会性趋同）；</li>
 * <li>噪声强度随游戏时间指数衰减（半衰期可配），档位联动（programmatic 关闭 / light 弱幅度 /
 * full·max 全开）；每次注入落 actor_macro_diversity 记录（可回放、可关闭、可回滚）。</li>
 * </ul>
 * <p>通用性：全部确定性纯函数（无任何测试世界内容），单测覆盖：偏好/情绪/社会/衰减/钳制/
 * 记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorDiversityService {

    private final BehaviorDiversityProperties props;
    private final ActorMacroDiversityRepository diversityRepository;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorMesoGroupRepository groupRepository;
    private final ActorWorldMacroDeltaRepository deltaRepository;
    private final WorldClockService worldClockService;
    private final ObjectMapper objectMapper;

    /** 状态服务（情绪/生理状态源；字段注入保持构造器不变，null=情绪通道跳过） */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** AI 强度档位（字段注入保持构造器不变，null=按 full 全开） */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    // ==================== 决策前注入（AgentRuntime.resolveDecision 挂钩） ====================

    /**
     * 决策前注入：三类噪声合成【状态涟漪】段（可空=零注入——档位关闭/无噪声）。
     *
     * @param projectId 项目 ID
     *
     * @param actorRef 统一角色引用（special:{id}）
     *
     * @param gameSecond 游戏秒（可空=取世界时钟当前值；衰减/社会参照窗口依据）
     *
     * @param traits 显式人格基线（可选：openness/conscientiousness/extraversion/agreeableness/
     * neuroticism 0~1；空=actorRef 确定性派生稳定基线）
     *
     * @return 注入段文本（null=不注入）
     */
    @Transactional
    public String inject(Long projectId, String actorRef, Long gameSecond, Map<String, Double> traits) {
        if (!props.isEnabled() || projectId == null || actorRef == null) {
            return null;
        }
        String level = aiIntensityGate == null ? AiIntensityGate.FULL : aiIntensityGate.intensityOf(projectId);
        if (AiIntensityGate.PROGRAMMATIC.equals(level)) {
            return null;
        }
        double mult = AiIntensityGate.LIGHT.equals(level) ? props.getLightMultiplier() : 1.0;
        long sec = gameSecond == null ? currentSecond(projectId) : gameSecond;
        double hourOfDay = (sec % 86400L) / 3600.0;
        double decay = Math.pow(0.5, hourOfDay / Math.max(1.0, props.getDecayHours())); // 半衰期衰减

        Map<String, Double> t = normalizeTraits(traits, actorRef);
        List<String> channels = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        StringBuilder sb = new StringBuilder("【状态涟漪】");
        int used = 6;

        // ① 偏好波动（OCEAN 派生；幅度受人格稳定度钳制 + 日内衰减）
        double pref = preferenceStrength(t, mult) * decay;
        if (pref > 0.01) {
            channels.add("preference");
            String bias = preferenceBias(actorRef, t);
            String line = "\n· 今日心绪略偏「" + bias + "」（意愿 " + Math.round(pref * 100) + "%）";
            notes.add("OCEAN 偏好波动");
            if (append(sb, line, used, props.getMaxContextLength())) {
                used = sb.length();
            }
        }

        // ② 情绪干扰（PAD 偏离基线）
        double emo = emotionStrength(projectId, actorRef, mult) * decay;
        if (emo > 0.01) {
            channels.add("emotion");
            String emoDesc = emotionBias(projectId, actorRef);
            String line = "\n· 情绪正在影响判断（" + emoDesc + "，干扰 " + Math.round(emo * 100) + "%）";
            notes.add("PAD 情绪偏离基线");
            if (append(sb, line, used, props.getMaxContextLength())) {
                used = sb.length();
            }
        }

        // ③ 社会参照（同组近期行为从众）
        String social = socialReference(projectId, actorRef, sec);
        double socialStr = social == null ? 0.0 : props.getSocialReferenceStrength() * mult;
        if (social != null) {
            channels.add("social");
            String line = "\n· " + social;
            notes.add("同组行为从众");
            if (append(sb, line, used, props.getMaxContextLength())) {
                used = sb.length();
            }
        }

        if (channels.isEmpty()) {
            return null;
        }
        String seg = sb.toString().trim();
        record(projectId, actorRef, sec, channels, notes, seg, strengthOf(pref, emo, socialStr));
        return seg;
    }

    // ==================== 三类噪声（确定性纯函数，可单测） ====================

    /** 偏好波动强度：基线 × 稳定度钳制 × 档位系数（0~1）。 */
    private double preferenceStrength(Map<String, Double> t, double mult) {
        return calcPreferenceStrength(t, props.getPreferenceStrength(), mult);
    }

    /** 偏好波动强度静态核心（可单测）：基线 × (1 − 尽责性×稳定度钳制) × 档位系数。 */
    static double calcPreferenceStrength(Map<String, Double> t, double base, double mult) {
        double conscientious = t.getOrDefault("conscientiousness", 0.5);
        double stability = 1.0 - Math.max(0.0, Math.min(1.0, conscientious)) * 0.7;
        return Math.max(0.0, Math.min(1.0, base * stability * mult));
    }

    /** 偏好方向（角色稳定基线：开放性高→探索、尽责性高→守序、外倾高→社交、神经质高→避险）。 */
    static String preferenceBias(String actorRef, Map<String, Double> t) {
        double openness = t.getOrDefault("openness", 0.5);
        double extraversion = t.getOrDefault("extraversion", 0.5);
        double neuroticism = t.getOrDefault("neuroticism", 0.5);
        double pick = stableHash(actorRef) % 100 / 100.0;
        if (openness > 0.65 && pick < 0.6) {
            return "好奇探索";
        }
        if (extraversion > 0.65 && pick < 0.7) {
            return "与人来往";
        }
        if (neuroticism > 0.6 && pick < 0.6) {
            return "谨慎避险";
        }
        return pick < 0.5 ? "稳妥守序" : "守住本分";
    }

    /** 情绪干扰强度：愉悦偏离基线 + 情绪强度（0~1）。 */
    double emotionStrength(Long projectId, String actorRef, double mult) {
        ActorNpcState st = stateOf(projectId, actorRef);
        if (st == null || st.getEmotionPleasure() == null) {
            return 0.0;
        }
        double base = 0.10; // PAD 愉悦基线（actor_npc_state 默认 0.10）
        double pleasureDev = Math.abs(st.getEmotionPleasure() - base);
        double intensity = st.getEmotionIntensity() == null ? 0.0
                : Math.min(1.0, st.getEmotionIntensity() / 100.0);
        double s = (pleasureDev * 0.8 + intensity * 0.4) / 2.0;
        return Math.max(0.0, Math.min(1.0, s * props.getEmotionWeight() * mult));
    }

    /** 情绪方向描述（愉悦偏低→消极保守；偏高→积极冒进）。 */
    String emotionBias(Long projectId, String actorRef) {
        ActorNpcState st = stateOf(projectId, actorRef);
        if (st == null || st.getEmotionPleasure() == null) {
            return "情绪平稳";
        }
        double p = st.getEmotionPleasure();
        return p < 0.0 ? "心情低落，行事偏保守" : (p > 0.25 ? "兴致正高，倾向大胆" : "情绪平稳");
    }

    /**
     * 社会参照：同组成员窗口内同类行为 ≥2 人 → 从众方向文本（null=无参照）。
     * 窗口 [day-window, day-1] 入账流水按 {@link MacroBehaviorClass} 归一，统计「本角色所在组
     * 其他成员」的行为类多数。
     */
    String socialReference(Long projectId, String actorRef, Long gameSecond) {
        if (projectId == null || actorRef == null) {
            return null;
        }
        long day = gameSecond == null ? 0 : Math.floorDiv(gameSecond, 86400L);
        long from = Math.max(0, day - props.getSocialWindowDays());
        long to = Math.max(0, day - 1);
        // 本角色所属组 → 组内其他成员
        Set<String> mates = new LinkedHashSet<>();
        for (ActorMesoMembership m : membershipRepository
                .findByProjectIdAndActorRefAndStatus(projectId, actorRef, "active")) {
            ActorMesoGroup g = groupRepository.findById(m.getGroupId()).orElse(null);
            if (g == null || !"active".equals(g.getStatus())) {
                continue;
            }
            for (ActorMesoMembership om : membershipRepository
                    .findByGroupIdAndStatusOrderByIdAsc(g.getId(), "active")) {
                if (!actorRef.equals(om.getActorRef())) {
                    mates.add(om.getActorRef());
                }
            }
        }
        if (mates.isEmpty()) {
            return null;
        }
        // 窗口内组员行为归一 → 多数行为类
        Map<String, Set<String>> classActors = new LinkedHashMap<>();
        for (ActorWorldMacroDelta d : deltaRepository
                .findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, from, to)) {
            if (d.getNpcRef() == null || !mates.contains(d.getNpcRef())) {
                continue;
            }
            String cls = MacroBehaviorClass.classify(d.getActionType(), d.getActionText(), d.getIndicator());
            if (cls == null) {
                continue;
            }
            classActors.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(d.getNpcRef());
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Set<String>> e : classActors.entrySet()) {
            if (e.getValue().size() >= props.getSocialMinActors() && e.getValue().size() > bestN) {
                best = e.getKey();
                bestN = e.getValue().size();
            }
        }
        if (best == null) {
            return null;
        }
        return "同组 " + bestN + " 人近期都在「" + MacroBehaviorClass.displayOf(best) + "」，你被带着随大流";
    }

    // ==================== 记录 / 辅助 ====================

    /** 注入记录落库（可回放/可关闭；trace 贯通）。 */
    private void record(Long projectId, String actorRef, Long gameSecond, List<String> channels,
                        List<String> notes, String segment, double strength) {
        try {
            ActorMacroDiversity d = new ActorMacroDiversity();
            d.setProjectId(projectId);
            d.setActorRef(actorRef);
            d.setGameDay(Math.floorDiv(gameSecond, 86400L));
            d.setChannel(String.join(",", channels));
            d.setStrength(strength);
            d.setSource(String.join(" + ", notes));
            d.setExpiresGameSecond(gameSecond + (long) (props.getDecayHours() * 3600.0));
            d.setContextJson(objectMapper.writeValueAsString(Map.of("segment", segment)));
            d.setTraceId(AiCallContext.currentTraceId());
            diversityRepository.save(d);
        } catch (Exception e) {
            log.warn("[多样性] 注入记录落库失败（隔离继续）: project={} ref={} : {}", projectId, actorRef, e.getMessage());
        }
    }

    /** 人格基线：显式优先，否则由 actorRef 确定性派生稳定基线（同一角色每次一致）。 */
    static Map<String, Double> normalizeTraits(Map<String, Double> traits, String actorRef) {
        Map<String, Double> out = new LinkedHashMap<>();
        double seed = stableHash(actorRef) % 1000 / 1000.0;
        out.put("openness", first(traits, "openness", 0.3 + seed * 0.5));
        out.put("conscientiousness", first(traits, "conscientiousness", 0.3 + (stableHash(actorRef + "c") % 1000) / 1000.0 * 0.5));
        out.put("extraversion", first(traits, "extraversion", 0.3 + (stableHash(actorRef + "e") % 1000) / 1000.0 * 0.5));
        out.put("agreeableness", first(traits, "agreeableness", 0.3 + (stableHash(actorRef + "a") % 1000) / 1000.0 * 0.5));
        out.put("neuroticism", first(traits, "neuroticism", 0.2 + (stableHash(actorRef + "n") % 1000) / 1000.0 * 0.4));
        return out;
    }

    private static double first(Map<String, Double> traits, String key, double fallback) {
        if (traits != null && traits.containsKey(key) && traits.get(key) != null) {
            return Math.max(0.0, Math.min(1.0, traits.get(key)));
        }
        return fallback;
    }

    /** 稳定字符串哈希（角色稳定基线种子；非负）。 */
    static long stableHash(String s) {
        long h = 1125899906842597L;
        if (s == null) {
            return 0;
        }
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return Math.abs(h);
    }

    /** 段内追加（长度预算保护；返回是否成功）。 */
    private static boolean append(StringBuilder sb, String line, int used, int budget) {
        if (used + line.length() > budget) {
            return false;
        }
        sb.append(line);
        return true;
    }

    private static double strengthOf(double pref, double emo, double social) {
        return Math.max(0.0, Math.min(1.0, Math.max(pref, Math.max(emo, social))));
    }

    /** 状态安全取（未装配/异常返回 null=情绪通道跳过）。 */
    private ActorNpcState stateOf(Long projectId, String actorRef) {
        if (npcStateService == null || projectId == null || actorRef == null) {
            return null;
        }
        try {
            return npcStateService.stateFor(projectId, actorRef);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前游戏秒（世界时钟；异常回退 0）。 */
    private long currentSecond(Long projectId) {
        try {
            Long s = worldClockService.currentEngineGameSecond(projectId);
            return s == null ? 0L : s;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 读接口：项目注入记录（调试面板 GET /macro/diversity）。 */
    public List<Map<String, Object>> listVOs(Long projectId, String actorRef, Integer limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (projectId == null) {
            return out;
        }
        List<ActorMacroDiversity> rows = actorRef == null || actorRef.isBlank()
                ? diversityRepository.findByProjectIdOrderByIdDesc(projectId)
                : diversityRepository.findByProjectIdAndActorRefOrderByIdDesc(projectId, actorRef);
        int n = 0;
        for (ActorMacroDiversity d : rows) {
            if (limit != null && n++ >= limit) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("projectId", d.getProjectId());
            m.put("actorRef", d.getActorRef());
            m.put("gameDay", d.getGameDay());
            m.put("channel", d.getChannel());
            m.put("strength", d.getStrength());
            m.put("source", d.getSource());
            m.put("expiresGameSecond", d.getExpiresGameSecond());
            m.put("contextJson", d.getContextJson());
            m.put("traceId", d.getTraceId());
            m.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }
}
