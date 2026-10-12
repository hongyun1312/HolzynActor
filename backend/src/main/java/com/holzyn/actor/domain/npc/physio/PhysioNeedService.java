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

package com.holzyn.actor.domain.npc.physio;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.space.SpaceChoiceMapper;
import com.holzyn.actor.domain.growth.space.SpaceDecisionCandidates;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 生理需求→行动决策耦合服务。
 * <p><b>定位</b>：把 {@code actor_state} 需求衰减转化为<b>实际行动</b>——这是 P1/P2 两轮
 * 长跑共证的头号真实性缺陷（核心角色 3 游戏小时无进食/如厕/清洗，行动全为氛围轻行动）的根治。
 * 普通 NPC 程序化路径（{@code NpcStateService.probabilisticAction}）已有需求满足机制且实测健康，
 * 本服务把<b>同一套生理约束</b>接入核心角色 AI 决策路径与普通 NPC 空间候选路径：</p>
 * <ul>
 * <li><b>低值区</b>（hunger&lt;45 / thirst&lt;45 / bladder&lt;35 / hygiene&lt;50）→ 生理行动
 * <b>候选注入</b>决策候选池（{@link SpaceDecisionCandidates.SpaceCandidate}，带「（倾向 x%）」
 * 情境权重，x=需求缺口比例），由既有空间候选选择链路（NanoJev space_decision choice）选择；
 * 进食候选仅<b>三餐窗口</b>内注入（饮水/如厕/清洗不受窗口限制）；</li>
 * <li><b>危险区</b>（hunger&lt;20 / thirst&lt;20 / bladder&lt;15 / hygiene&lt;25）→ <b>程序化强制</b>
 * （0 AI）：任何决策触发源（周期/打断/兜底）都在 AI 之前命中本检查，直接产出生理行动；
 * 核心角色另经 {@code StimulusType.PHYSIOLOGICAL_URGE} 实时打断通道「放下手头事」；</li>
 * <li><b>配比分层</b>：生理候选命中时，候选池通用「歇一歇/找人说说话」轻行动被抑制
 * （「继续当前」恒在）——饿了的人不会先放空（次对接文档 §6.1 轻/实质行动配比控制）。</li>
 * </ul>
 * <p><b>设计依据（2026-10-02 联网调研）</b>：马斯洛需求层次作为 LLM 智能体动机驱动
 * （AgentSociety, arXiv 2502.08691）／驱力行为模型：需求衰减→驱力→适应性决策
 * （Drive-Based Behavior Modeling, SBGames）／Sims 动机系统（需求衰减 + 自主满足，
 * 动机层次「先处理低层需求」）／NEEDSIM（需求优先于日程调度——本服务危险区强制即此语义）。
 * 行动文本与 {@code NpcStateService.onNpcAction} 关键词恢复表对齐（用餐/饮水/如厕/洗漱），
 * 满足需求后状态自然回升、离开低值/危险区，形成「衰减→行动→恢复」闭环。</p>
 * <p><b>架构纪律</b>：纯程序化 0 AI（无 NanoJev/chat 调用）→ 无 {@code @Transactional} 约束；
 * 不持有领域权威（需求值权威=actor_state，配置权威=NpcStateProperties.Physio）；读状态经
 * {@code NpcStateService.stateFor} 门面、地点经 {@code NpcStateService.locationNames}（同一
 * 匹配口径）；静态纯函数可单测。</p>
 */
@Slf4j
@Service
public class PhysioNeedService {

    /** 需求键→行动类型（与 onNpcAction 恢复关键词映射对齐：eat/drink/toilet/wash）。 */
    private static final String[] NEEDS = {"饥饿", "口渴", "膀胱", "清洁"};

    /** 状态模块配置（physio 块参数真源；null=未装配[单测]跳过） */
    @Autowired(required = false)
    private NpcStateProperties stateProperties;

    /** 状态门面（需求值读取；null=未装配跳过） */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** 世界时钟（游戏时刻/小时读取；null=未装配跳过） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 危险需求（最紧急者）。
     *
     * @param need 需求键（饥饿/口渴/膀胱/清洁）
     *
     * @param actionType 行动类型（eat/drink/toilet/wash）
     *
     * @param value 当前需求值
     */
    public record DangerNeed(String need, String actionType, int value) {
    }

    /**
     * 危险需求判定（纯静态）：任一需求低于危险阈值 → 返回<b>值最低</b>的危险需求；
     * 无危险返回 null。
     *
     * @param st 状态行（只读取值）
     *
     * @param p physio 配置块（null/未启用 → 恒 null）
     *
     * @return 最紧急危险需求（null=无）
     */
    public static DangerNeed dangerNeedOf(ActorNpcState st, NpcStateProperties.Physio p) {
        if (st == null || p == null || !p.isEnabled()) {
            return null;
        }
        DangerNeed best = null;
        for (String need : NEEDS) {
            int v = valueOf(st, need);
            if (v < dangerThreshold(p, need) && (best == null || v < best.value())) {
                best = new DangerNeed(need, actionTypeOf(need), v);
            }
        }
        return best;
    }

    /**
     * 低值区生理候选组装（纯静态）：需求低于低值阈值 → 生理行动候选
     * （带 target 地点 + 「（倾向 x%）」权重，x=需求缺口/阈值比例）。
     * <p>进食候选仅三餐窗口内注入（hourOfDay=null 时间未知时宽松放行，零阻断）；
     * 饮水/如厕/清洗不受窗口限制。</p>
     *
     * @param st 状态行（只读取值）
     *
     * @param p physio 配置块（null/未启用 → 空表）
     *
     * @param hourOfDay 当天小时（0-23；null=时间未知宽松放行）
     *
     * @param locations 世界观地点名列表（可空=无地点匹配，候选无 target）
     *
     * @param rng 随机源（地点选取；可测）
     *
     * @return 生理候选表（键 physio0…；可空）
     */
    public static List<SpaceDecisionCandidates.SpaceCandidate> lowCandidates(
            ActorNpcState st, NpcStateProperties.Physio p, Integer hourOfDay,
            List<String> locations, Random rng) {
        if (st == null || p == null || !p.isEnabled()) {
            return List.of();
        }
        List<SpaceDecisionCandidates.SpaceCandidate> out = new ArrayList<>();
        int idx = 0;
        if (valueOf(st, "饥饿") < p.getLowHunger() && isMealHour(hourOfDay, p)) {
            out.add(buildCandidate("physio" + idx++, "饥饿", p.getLowHunger(), st, locations, rng));
        }
        if (valueOf(st, "口渴") < p.getLowThirst()) {
            out.add(buildCandidate("physio" + idx++, "口渴", p.getLowThirst(), st, locations, rng));
        }
        if (valueOf(st, "膀胱") < p.getLowBladder()) {
            out.add(buildCandidate("physio" + idx++, "膀胱", p.getLowBladder(), st, locations, rng));
        }
        if (valueOf(st, "清洁") < p.getLowHygiene()) {
            out.add(buildCandidate("physio" + idx++, "清洁", p.getLowHygiene(), st, locations, rng));
        }
        return out;
    }

    /**
     * 是否有低值区需求（纯静态）：配比分层用——生理候选命中则抑制通用轻行动。
     *
     * @param st 状态行（只读取值）
     *
     * @param p physio 配置块
     *
     * @param hourOfDay 当天小时（null=宽松按窗口内）
     *
     * @return true=存在任一低值需求（含窗口门控后的进食）
     */
    public static boolean hasLowNeed(ActorNpcState st, NpcStateProperties.Physio p, Integer hourOfDay) {
        if (st == null || p == null || !p.isEnabled()) {
            return false;
        }
        return (valueOf(st, "饥饿") < p.getLowHunger() && isMealHour(hourOfDay, p))
                || valueOf(st, "口渴") < p.getLowThirst()
                || valueOf(st, "膀胱") < p.getLowBladder()
                || valueOf(st, "清洁") < p.getLowHygiene();
    }

    /**
     * 是否三餐窗口内（纯静态）：仅约束进食；hourOfDay=null 时间未知时宽松放行（零阻断）。
     *
     * @param hourOfDay 当天小时（0-23；null=宽松放行）
     *
     * @param p physio 配置块（null=宽松放行）
     *
     * @return true=窗口内（或无窗口配置/时间未知）
     */
    public static boolean isMealHour(Integer hourOfDay, NpcStateProperties.Physio p) {
        if (hourOfDay == null || p == null || p.getMealWindows() == null || p.getMealWindows().isEmpty()) {
            return true;
        }
        for (NpcStateProperties.Physio.MealWindow w : p.getMealWindows()) {
            if (w != null && w.contains(hourOfDay)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 危险区程序化强制决策（核心角色）：任一需求低于危险阈值 → 直接产出生理行动
     * 内部规范决策 Map（新键+旧键双写，与空间候选选择产物同构，下游执行链零改动；
     * priority/urgency 抬到 5=健康告急优先）；无危险返回 null（调用方走原决策链）。
     * <p><b>0 AI</b>：本方法只做纯计算，任何决策触发源（周期/打断/程序化兜底）都先于
     * AI 检查——快饿死的人不会因决策系统失效而继续发呆。</p>
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色实体
     *
     * @return 内部规范决策 Map（null=无危险需求）
     */
    public Map<String, Object> dangerDecision(Long projectId, ActorCharacter ch) {
        if (ch == null || projectId == null) {
            return null;
        }
        try {
            ActorNpcState st = stateOf(projectId, ch);
            DangerNeed d = dangerNeedOf(st, physio());
            if (d == null) {
                return null;
            }
            List<String> locations = npcStateService == null ? List.of()
                    : npcStateService.locationNames(projectId);
            String target = NpcStateService.pickTarget(locations, d.actionType(), new Random());
            String actionText = actionTextOf(d.need(), target);
            SpaceDecisionCandidates.SpaceCandidate chosen = new SpaceDecisionCandidates.SpaceCandidate(
                    "physio_danger", actionText, actionText, target, null, 1.0, false);
            Map<String, Object> decision = SpaceChoiceMapper.toDecisionMap(
                    chosen, ch.getCurrentActivity(), List.of(actionText));
            if (decision == null) {
                return null;
            }
            // 健康告急优先：priority/urgency 抬到 5（toDecisionMap 对非通用项默认 3）
            decision.put("priority", 5);
            decision.put("urgency", 5);
            return decision;
        } catch (Exception e) {
            log.warn("[生理需求] 危险区强制决策失败（回退原决策链）: char={} : {}", ch.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 低值区生理候选（实例）：取状态行/时刻/地点后委托静态 {@link #lowCandidates}。
     * 供核心角色与普通 NPC 的空间候选路径注入（{@code SpaceDecisionCandidates.candidatesFrom}
     * 3 参重载 extra 列表）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色实体
     *
     * @return 生理候选表（可空=无低值需求/未装配）
     */
    public List<SpaceDecisionCandidates.SpaceCandidate> injectableCandidates(Long projectId, ActorCharacter ch) {
        if (ch == null || projectId == null) {
            return List.of();
        }
        try {
            ActorNpcState st = stateOf(projectId, ch);
            List<String> locations = npcStateService == null ? List.of()
                    : npcStateService.locationNames(projectId);
            return lowCandidates(st, physio(), hourOfDay(projectId), locations, new Random());
        } catch (Exception e) {
            log.warn("[生理需求] 低值候选组装失败（跳过注入）: char={} : {}", ch.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 轻行动抑制判定（实例）：低值区需求存在且 {@code light-action-suppress-enabled} 开 →
     * 候选池通用「歇一歇/找人说说话」不再注入（配比分层）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色实体
     *
     * @return true=抑制通用轻行动
     */
    public boolean suppressLight(Long projectId, ActorCharacter ch) {
        NpcStateProperties.Physio p = physio();
        if (ch == null || projectId == null || p == null || !p.isLightActionSuppressEnabled()) {
            return false;
        }
        try {
            return hasLowNeed(stateOf(projectId, ch), p, hourOfDay(projectId));
        } catch (Exception e) {
            log.warn("[生理需求] 轻行动抑制判定失败（不抑制）: char={} : {}", ch.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 危险区打断刺激文本（实例）：感知 tick 检测到危险需求 → 供
     * {@code RealTimeDecisionService.onStimulus(PHYSIOLOGICAL_URGE)} 使用；
     * 无危险返回 null（不触发）。
     *
     * @param st 状态行
     *
     * @return 刺激文本（null=无危险）
     */
    public String dangerStimulusText(ActorNpcState st) {
        DangerNeed d = dangerNeedOf(st, physio());
        return dangerStimulusTextOf(d);
    }

    /**
     * 危险需求 → 打断刺激文本（纯静态；null=无危险）。
     *
     * @param d 危险需求（null → null）
     *
     * @return 刺激文本（null=无危险）
     */
    public static String dangerStimulusTextOf(DangerNeed d) {
        if (d == null) {
            return null;
        }
        return switch (d.need()) {
            case "饥饿" -> "饿得发慌了，必须马上找东西吃";
            case "口渴" -> "渴得嗓子冒烟，必须马上找水喝";
            case "膀胱" -> "尿意涨到极限，必须马上找地方如厕";
            case "清洁" -> "浑身难受，必须马上找地方清洗";
            default -> "身体不适到了极限，必须马上处理";
        };
    }

    // ==================== 私有辅助（纯函数） ====================

    /** 状态行读取（经状态模块门面；null=未装配返回 null）。 */
    private ActorNpcState stateOf(Long projectId, ActorCharacter ch) {
        if (npcStateService == null || ch == null) {
            return null;
        }
        return npcStateService.stateFor(projectId, NpcRefs.of(ch.getId()));
    }

    /** physio 配置块（null=未装配/未启用时各静态函数自行处理）。 */
    private NpcStateProperties.Physio physio() {
        return stateProperties == null ? null : stateProperties.getPhysio();
    }

    /** 引擎推进位置的当天小时（null=不可读）。 */
    private Integer hourOfDay(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return null;
        }
        try {
            Long h = worldClockService.currentEngineGameHour(projectId);
            return h == null ? null : (int) (h % 24L);
        } catch (Exception e) {
            log.debug("[生理需求] 游戏时刻读取失败（按未知）: {}", e.getMessage());
            return null;
        }
    }

    /** 需求值读取（null 回退默认值；与 NpcStateService.val 同口径）。 */
    private static int valueOf(ActorNpcState st, String need) {
        Integer v = switch (need) {
            case "饥饿" -> st.getHunger();
            case "口渴" -> st.getThirst();
            case "膀胱" -> st.getBladder();
            case "清洁" -> st.getHygiene();
            default -> null;
        };
        int dflt = switch (need) {
            case "膀胱" -> 50;
            case "清洁" -> 90;
            default -> 70;
        };
        return v == null ? dflt : v;
    }

    /** 危险阈值（按需求键）。 */
    private static double dangerThreshold(NpcStateProperties.Physio p, String need) {
        return switch (need) {
            case "饥饿" -> p.getDangerHunger();
            case "口渴" -> p.getDangerThirst();
            case "膀胱" -> p.getDangerBladder();
            case "清洁" -> p.getDangerHygiene();
            default -> 0;
        };
    }

    /**
     * 需求键 → 行动类型（公开：与 onNpcAction 恢复关键词映射对齐，供测试与消费方复用）。
     */
    public static String actionTypeOf(String need) {
        return switch (need) {
            case "饥饿" -> "eat";
            case "口渴" -> "drink";
            case "膀胱" -> "toilet";
            case "清洁" -> "wash";
            default -> "custom";
        };
    }

    /**
     * 需求键 + 目标地点 → 行动文本（公开：普通 NPC 概率化路径复用同一文本口径；
     * 含 onNpcAction 恢复关键词：用餐/饮水/如厕/洗漱/充饥）。
     */
    public static String actionTextOf(String need, String target) {
        return switch (need) {
            case "饥饿" -> target == null ? "觅食充饥" : "前往" + target + "用餐";
            case "口渴" -> target == null ? "寻水饮用" : "前往" + target + "饮水";
            case "膀胱" -> target == null ? "寻找如厕之处" : "前往" + target + "如厕";
            case "清洁" -> target == null ? "寻水洗漱" : "前往" + target + "洗漱";
            default -> "忙于日常事务";
        };
    }

    /** 单个生理候选组装（权重=需求缺口/阈值比例，钳制 0~1，作「（倾向 x%）」情境信息）。 */
    private static SpaceDecisionCandidates.SpaceCandidate buildCandidate(
            String key, String need, double threshold, ActorNpcState st,
            List<String> locations, Random rng) {
        int value = valueOf(st, need);
        String target = NpcStateService.pickTarget(locations, actionTypeOf(need), rng);
        String actionText = actionTextOf(need, target);
        double gap = threshold <= 0 ? 0 : Math.max(0, Math.min(1, (threshold - value) / threshold));
        long pct = Math.round(gap * 100);
        String question = actionText + "（倾向 " + pct + "%）";
        return new SpaceDecisionCandidates.SpaceCandidate(key, actionText, question,
                target, null, gap, false);
    }
}
