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
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.EmergenceProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorMacroEmergenceEvent;
import com.holzyn.actor.domain.world.entity.ActorMacroEmergenceMember;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorMacroEmergenceEventRepository;
import com.holzyn.actor.domain.world.repository.ActorMacroEmergenceMemberRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 二阶涌现检测服务。
 * <p>职责：<b>二阶涌现检测</b>——个体行为汇聚成集体现象：每日扫描窗口内入账流水
 * ，按 {@link MacroBehaviorClass} 归一为
 * 行为类，同游戏日 3 个以上<b>不同角色</b>同类行为 → 涌现事件（囤积潮/迁徙潮/抗议潮…）：</p>
 * <ul>
 * <li><b>达标判定</b>：minMembers 绝对门槛 + 可选比例模式（≥20% 具名角色，小村庄口径）双条件；
 * 同行为类冷却（cooldownDays，防重复误报）；</li>
 * <li><b>权重</b>：weight = memberCount × typeBase（囤积/迁徙/抗议/逃离 ×2.0，普通 ×1.5）——
 * 比单点入账更高宏观影响；</li>
 * <li><b>叙事</b>：规则文本兜底 + AI 叙事（scene=macro_emergence，预算门控失败回退规则文本）；</li>
 * <li><b>叠加入账</b>：涌现写 source=emergence 的 delta 行（次日聚合吸收——比单点入账
 * 更强的经济/舆论影响），且本服务扫描时排除 emergence 行（防自我强化循环）；</li>
 * <li><b>回灌</b>：复用宏观事件回灌（信息包扩散 + 帧「社会宏观」板块追加）。</li>
 * </ul>
 * <p>通用性：行为类与关键词、显示名、权重全部通用语义（无任何测试世界内容）；
 * 单测覆盖：分组/达标/冷却/叠加/排除自引用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroEmergenceService {

    /** 涌现叠加入账来源标记（区别于 rule/ai；次日聚合吸收，本服务扫描排除防自强化） */
    public static final String SOURCE_EMERGENCE = "emergence";

    private final EmergenceProperties props;
    private final ActorWorldMacroDeltaRepository deltaRepository;
    private final ActorMacroEmergenceEventRepository eventRepository;
    private final ActorMacroEmergenceMemberRepository memberRepository;

    /**
     * NPC 主表仓库。
     * <p>{@code actor_profile_ordinary} 已物理废弃，普通型 NPC 并入统一主表 {@code actor_profile}
     * （{@code type='common'} 区分），故本字段类型换成合并后的 {@link ActorCharacterRepository}——
     * <b>字段名沿用、但它不是"普通型专用仓库"</b>：任何"只数/只取普通型"的地方都必须显式带
     * {@link NpcRefs#TYPE_COMMON} 过滤（本类仅 {@link #namedCount} 用到，已按此口径写明）。</p>
     */
    private final ActorCharacterRepository npcRepository;
    private final MacroStateService macroStateService;
    private final MacroFeedbackService macroFeedbackService;
    private final ObjectMapper objectMapper;

    /** AI 叙事 */
    @Autowired(required = false)
    private MacroAiService macroAiService;

    // ==================== 每日检测 ====================

    /**
     * 每日扫描（MacroScheduler.tickDaily 末尾挂钩；隔离失败零阻断）。
     * 窗口对齐入账「本日入账次日生效」语义：扫描 [day-window, day-1] 已完成日流水，
     * 涌现叠加行（source=emergence）写入 day 当日，次日 tick 聚合吸收。
     *
     * @param projectId 项目 ID
     *
     * @param day 当前游戏日
     *
     * @param gameSecond 游戏秒（可空）
     */
    @Transactional
    public void scanDaily(Long projectId, Long day, Long gameSecond) {
        if (!props.isEnabled() || projectId == null || day == null) {
            return;
        }
        try {
            long from = Math.max(0, day - props.getWindowDays());
            long to = Math.max(0, day - 1);
            // 拷贝后可过滤（JPA 返回列表可能不可变）
            List<ActorWorldMacroDelta> rows = new ArrayList<>(deltaRepository
                    .findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, from, to));
            if (rows.isEmpty()) {
                return;
            }
            // 排除涌现叠加行（防自我强化循环）
            rows.removeIf(r -> SOURCE_EMERGENCE.equals(r.getSource()));
            Map<String, List<ActorWorldMacroDelta>> byClass = groupByBehavior(rows);
            if (byClass.isEmpty()) {
                return;
            }
            long namedTotal = namedCount(projectId);
            int emitted = 0;
            for (Map.Entry<String, List<ActorWorldMacroDelta>> e : byClass.entrySet()) {
                if (emitted >= props.getMaxPerDay()) {
                    break;
                }
                if (emitIfQualified(projectId, day, gameSecond, e.getKey(), e.getValue(), namedTotal)) {
                    emitted++;
                }
            }
        } catch (Exception e) {
            log.warn("[宏观] 二阶涌现检测失败（隔离继续）: project={} day={}", projectId, day, e);
        }
    }

    /** 流水行 → 行为类分组（可单测静态；无法归一的行不参与聚集统计）。 */
    static Map<String, List<ActorWorldMacroDelta>> groupByBehavior(List<ActorWorldMacroDelta> rows) {
        Map<String, List<ActorWorldMacroDelta>> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (ActorWorldMacroDelta r : rows) {
            String cls = MacroBehaviorClass.classify(r.getActionType(), r.getActionText(), r.getIndicator());
            if (cls == null) {
                continue;
            }
            out.computeIfAbsent(cls, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    // ==================== 达标判定 + 落库 ====================

    /** 单行为类达标 → 涌现事件落库（事件+成员明细+叠加入账+回灌）；未达标返回 false。 */
    private boolean emitIfQualified(Long projectId, Long day, Long gameSecond, String behaviorClass,
                                    List<ActorWorldMacroDelta> rows, long namedTotal) {
        // 参与成员（不同角色去重，保留首条证据行）
        Map<String, ActorWorldMacroDelta> members = new LinkedHashMap<>();
        for (ActorWorldMacroDelta r : rows) {
            String ref = r.getNpcRef();
            if (ref == null || ref.isBlank()) {
                continue;
            }
            members.putIfAbsent(ref, r);
        }
        int memberCount = members.size();
        if (memberCount < props.getMinMembers()) {
            return false;
        }
        // 比例模式：≥ 具名角色总数 × ratio（小村庄口径；namedTotal=0 时仅绝对门槛）
        if (props.isRatioEnabled() && namedTotal > 0
                && memberCount < Math.ceil(namedTotal * props.getRatio())) {
            return false;
        }
        // 冷却判定：同行为类最近事件冷却至 ≥ 当前日 → 跳过（防重复误报）
        ActorMacroEmergenceEvent last = eventRepository
                .findTopByProjectIdAndBehaviorClassOrderByIdDesc(projectId, behaviorClass).orElse(null);
        if (last != null && last.getCooledUntilDay() != null && last.getCooledUntilDay() >= day) {
            return false;
        }

        String type = behaviorClass;
        double weight = memberCount * props.typeBaseOf(behaviorClass);
        int severity = memberCount >= 6 ? 4 : 3;
        String ruleText = ruleNarrative(projectId, day, behaviorClass, memberCount, members.size());
        String narrative = ruleText;
        String source = "rule";
        if (macroAiService != null) {
            try {
                MacroAiService.Narrative ai = macroAiService.narrateEmergence(
                        projectId, day, type, behaviorClass, memberCount, ruleText, communityName(projectId));
                if (ai != null && ai.content() != null && !ai.content().isBlank()) {
                    narrative = ai.content();
                    source = "ai";
                }
            } catch (Exception ex) {
                log.warn("[宏观] 涌现 AI 叙事失败（回退规则文本）: project={} : {}", projectId, ex.getMessage());
            }
        }

        ActorMacroEmergenceEvent ev = new ActorMacroEmergenceEvent();
        ev.setProjectId(projectId);
        ev.setGameDay(day);
        ev.setGameSecond(gameSecond);
        ev.setEmergenceType(type);
        ev.setBehaviorClass(behaviorClass);
        ev.setMemberCount(memberCount);
        ev.setWeight(weight);
        ev.setEvidenceJson(toJson(evidenceOf(members, props.getMaxEvidenceMembers())));
        ev.setNarrative(narrative);
        ev.setSource(source);
        ev.setSeverity(severity);
        ev.setCooledUntilDay(day + props.getCooldownDays());
        eventRepository.save(ev);

        // 参与成员明细（截断护栏内）
        int n = 0;
        for (Map.Entry<String, ActorWorldMacroDelta> me : members.entrySet()) {
            if (n++ >= props.getMaxEvidenceMembers()) {
                break;
            }
            ActorWorldMacroDelta d = me.getValue();
            ActorMacroEmergenceMember m = new ActorMacroEmergenceMember();
            m.setProjectId(projectId);
            m.setEmergenceId(ev.getId());
            m.setActorRef(me.getKey());
            m.setActionType(d.getActionType());
            m.setIndicator(d.getIndicator());
            m.setDelta(d.getDelta());
            m.setGameDay(d.getGameDay());
            memberRepository.save(m);
        }

        // 叠加入账（source=emergence；次日聚合吸收——比单点入账更强的经济/舆论影响）
        superposeDelta(projectId, day, behaviorClass, weight);
        // 回灌：信息包扩散 + 帧「社会宏观」板块（复用宏观事件回灌）
        feedBack(projectId, day, ev);

        log.info("[宏观] 二阶涌现检出: project={} day={} type={} 成员={} 权重={} 来源={}",
                projectId, day, type, memberCount, weight, source);
        return true;
    }

    // ==================== 规则文本 / 叠加 / 回灌 ====================

    /**
     * 涌现规则叙事（无 AI 兜底；核心资源/社区名取模板语义——不硬编码任何世界的物资名）。
     */
    String ruleNarrative(Long projectId, Long day, String behaviorClass, int memberCount, int evidenceCount) {
        String core = "物资";
        String community = "本境";
        try {
            MacroTemplate tpl = macroStateService.templateOf(projectId);
            core = tpl.coreResource();
            community = tpl.communityName();
        } catch (Exception e) {
            // 模板不可用回退通用默认
        }
        String verb = switch (behaviorClass) {
            case "hoarding" -> "都在囤积" + core + "备荒，" + community + "的存" + core + "被一抢而空";
            case "migration" -> "都收拾行囊准备离乡，出" + community + "的人越来越多";
            case "protest" -> "都聚在一处抱怨生计，气氛越来越紧绷";
            case "fleeing" -> "都在逃难避祸，" + community + "内外人心惶惶";
            case "trading_spree" -> "都在赶集买卖，" + community + "集市热闹非凡";
            case "social_wave" -> "都在串门聚会，" + community + "闲谈声此起彼伏";
            case "rest_break" -> "都在歇脚休整，田间地头的劳作身影明显少了";
            default -> "不约而同地做起了同一件事";
        };
        return "第" + (day + 1) + "日，" + MacroBehaviorClass.displayOf(behaviorClass) + "渐成风气："
                + memberCount + " 人" + verb + "（窗口内同类行为 " + evidenceCount + " 条）。";
    }

    /**
     * 涌现 → 叠加入账（economy/market_activity 口径——囤积抽干市面/交易潮推热市集/抗议挫伤
     * 市集；其余行为类仅事件+叙事+回灌，不机械改状态；source=emergence 次日聚合吸收）。
     */
    private void superposeDelta(Long projectId, Long day, String behaviorClass, double weight) {
        double factor = switch (behaviorClass) {
            case "trading_spree" -> 0.4;
            case "hoarding" -> -0.4;
            case "protest" -> -0.6;
            default -> 0.0;
        };
        if (factor == 0.0) {
            return;
        }
        try {
            ActorWorldMacroDelta d = new ActorWorldMacroDelta();
            d.setProjectId(projectId);
            d.setGameDay(day);
            d.setNpcRef("system:emergence");
            d.setNpcName("集体行为");
            d.setActionType(behaviorClass);
            d.setActionText(MacroBehaviorClass.displayOf(behaviorClass) + "成风");
            d.setTargetMacro("economy");
            d.setIndicator("market_activity");
            d.setDelta(MacroEconomyEngine.round2(factor * weight));
            d.setAiJudged(0);
            d.setSource(SOURCE_EMERGENCE);
            deltaRepository.save(d);
        } catch (Exception e) {
            log.warn("[宏观] 涌现叠加入账失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 回灌：复用宏观事件回灌（信息包扩散 + 帧「社会宏观」板块追加；infoId 用涌现事件 id 防撞号）。 */
    private void feedBack(Long projectId, Long day, ActorMacroEmergenceEvent ev) {
        try {
            ActorWorldMacroEvent e = new ActorWorldMacroEvent();
            e.setId(ev.getId());
            e.setProjectId(projectId);
            e.setGameDay(day);
            e.setMacroType("emergence");
            e.setEventType(ev.getEmergenceType());
            e.setTitle(MacroBehaviorClass.displayOf(ev.getEmergenceType()) + "成风");
            e.setSeverity(ev.getSeverity());
            e.setNarrative(ev.getNarrative());
            e.setSource(ev.getSource());
            macroFeedbackService.onMacroEvent(projectId, day, e, null);
        } catch (Exception ex) {
            log.warn("[宏观] 涌现回灌失败（隔离继续）: project={} : {}", projectId, ex.getMessage());
        }
    }

    // ==================== 读接口（VO 组装） ====================

    /** 涌现事件列表（按 id 倒序；读接口 GET /macro/emergence）。 */
    public List<Map<String, Object>> listVOs(Long projectId, Integer limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (projectId == null) {
            return out;
        }
        List<ActorMacroEmergenceEvent> events = eventRepository.findByProjectIdOrderByIdDesc(projectId);
        int n = 0;
        for (ActorMacroEmergenceEvent ev : events) {
            if (limit != null && n++ >= limit) {
                break;
            }
            out.add(eventVO(ev, null));
        }
        return out;
    }

    /** 涌现事件详情（含参与成员明细；GET /macro/emergence/{eid}）。 */
    public Map<String, Object> detailVO(Long projectId, Long emergenceId) {
        ActorMacroEmergenceEvent ev = eventRepository.findById(emergenceId).orElse(null);
        if (ev == null || !projectId.equals(ev.getProjectId())) {
            return null;
        }
        List<Map<String, Object>> members = new ArrayList<>();
        for (ActorMacroEmergenceMember m : memberRepository.findByEmergenceIdOrderByIdAsc(emergenceId)) {
            Map<String, Object> mo = new LinkedHashMap<>();
            mo.put("actorRef", m.getActorRef());
            mo.put("actionType", m.getActionType());
            mo.put("indicator", m.getIndicator());
            mo.put("delta", m.getDelta());
            mo.put("gameDay", m.getGameDay());
            members.add(mo);
        }
        return eventVO(ev, members);
    }

    /** 事件 → VO（members 可空=列表视图）。 */
    private Map<String, Object> eventVO(ActorMacroEmergenceEvent ev, List<Map<String, Object>> members) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ev.getId());
        m.put("projectId", ev.getProjectId());
        m.put("gameDay", ev.getGameDay());
        m.put("emergenceType", ev.getEmergenceType());
        m.put("emergenceTypeDisplay", MacroBehaviorClass.displayOf(ev.getEmergenceType()));
        m.put("behaviorClass", ev.getBehaviorClass());
        m.put("memberCount", ev.getMemberCount());
        m.put("weight", ev.getWeight());
        m.put("severity", ev.getSeverity());
        m.put("narrative", ev.getNarrative());
        m.put("source", ev.getSource());
        m.put("evidenceJson", ev.getEvidenceJson());
        m.put("cooledUntilDay", ev.getCooledUntilDay());
        m.put("createdAt", ev.getCreatedAt() == null ? null : ev.getCreatedAt().toString());
        m.put("members", members);
        return m;
    }

    // ==================== 辅助 ====================

    /**
     * 具名角色总数（比例模式分母；失败回退 0=仅绝对门槛）。
     * <p><b>口径选择：普通型 NPC（人群）人数</b>。理由（两层）：</p>
     * <ol>
     * <li><b>历史口径就是普通型</b>： 之前本字段是 {@code ActorOrdinaryNpcRepository}，
     * {@code countByProjectId} 数的是 {@code actor_profile_ordinary} 的行数（人群，不是特殊型
     * 角色数）——单表合并把该仓库类型换成了统一主表仓库、派生方法一并物理废弃，因此这里必须把
     * "普通型"这层语义<b>显式写回</b>：{@code countByProjectIdAndType(projectId, TYPE_COMMON)}。
     * 若误用全量（special+common）作分母，会把分母抬高一截 → 比例模式下小村庄口径更难达标（行为漂移）。</li>
     * <li><b>语义自洽</b>：比例模式的设计意图是"小村庄里两成居民同时做同一件事即成涌现"
     * （见 {@code EmergenceProperties.ratio} 注释），而二阶涌现的成员来自宏观流水
     * {@code actor_world_macro_delta}——其中人群调度（{@code OrdinaryNpcService} →
     * {@code MacroLedgerService.recordCrowdAction}）是主要来源，用居民总数作分母才成立。</li>
     * </ol>
     * <p>软删除口径与旧 {@code countByProjectId} 保持一致（<b>不</b>过滤 {@code deleted}）：
     * 人群重建期间分母稳定，避免比例门槛随删除标记抖动。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 普通型 NPC 行数；异常/未装配回退 0（0=仅按绝对门槛 minMembers 判定）
     */
    private long namedCount(Long projectId) {
        try {
            return npcRepository.countByProjectIdAndType(projectId, NpcRefs.TYPE_COMMON);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 项目社区名（模板语义；失败回退通用名）。 */
    private String communityName(Long projectId) {
        try {
            return macroStateService.templateOf(projectId).communityName();
        } catch (Exception e) {
            return "本境";
        }
    }

    /** 参与证据摘要（npcRef+行动+折算量+日；截断护栏内）。 */
    private List<Map<String, Object>> evidenceOf(Map<String, ActorWorldMacroDelta> members, int cap) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, ActorWorldMacroDelta> e : members.entrySet()) {
            if (n++ >= cap) {
                break;
            }
            ActorWorldMacroDelta d = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("npcRef", e.getKey());
            m.put("actionType", d.getActionType());
            m.put("indicator", d.getIndicator());
            m.put("delta", d.getDelta());
            m.put("day", d.getGameDay());
            out.add(m);
        }
        return out;
    }

    /** 对象 → JSON。 */
    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}

