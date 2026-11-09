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

package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.service.RelationRefs;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 动态关系/好感演化服务（阶段 D，domain/agent/learning）——学习组件「关系演化」真实实现。
 * <p>职责：互动（对话/行动/事件）经本服务增减
 * 状态机（{@link #stateOf}）重算 relation_state，并发布 kind=relation 世界事件（拓扑/事件流
 * 实时反映）；同时为角色关系手动 CRUD 提供端点解析复用（{@link RelationRefs}，修）。</p>
 * <p>启发式规则：</p>
 * <ul>
 * <li><b>对话</b>（群聊轮内 NPC 两两互动）：每轮每对 +{@code dialog-delta}（默认 1）双向；
 * 单聊一方为玩家（非角色）不建模，不增减；</li>
 * <li><b>行动</b>（特殊角色决策 target 命中项目内角色）：按行动 type 增减
 * （help/interact/trade 正向、fight/flee 负向、move/schedule/custom 中性 0）；</li>
 * <li><b>事件</b>（世界事件带 ≥2 参与角色，排除 action/evolution/relation 防双算/自触发）：
 * 每对 +{@code event-delta}（默认 1）双向；</li>
 * <li><b>AI 精修</b>：手动端点触发（code=relation_refine 模板，见 CharacterRelationService.aiRefine），
 * 把「当前状态+数值+角色档案」交 AI 综合判断后落库。</li>
 * </ul>
 * <p>开关：{@code holzyn.actor.learning.relations.enabled}（默认 true）。</p>
 * <p>；依赖 character 域仓库与 agent/event 总线，R1 单向）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationDynamicsService {

    /** 关系状态机阈值常量（集中可调）：好感 ≤ 该值=敌对 */
    static final int HOSTILE_MAX = -60;
    /** 好感 ≤ 该值=对手（-60 ~ -30） */
    static final int RIVAL_MAX = -30;
    /** 好感 ≥ 该值=相识（15 起） */
    static final int STRANGER_MIN = 15;
    /** 好感 > 该值且信任达标=朋友 */
    static final int FRIEND_MIN = 45;
    /** 朋友所需最低信任 */
    static final int FRIEND_TRUST = 40;
    /** 好感 > 该值且信任达标=亲密 */
    static final int INTIMATE_MIN = 70;
    /** 亲密所需最低信任 */
    static final int INTIMATE_TRUST = 60;

    private final ActorCharacterRelationRepository relationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 空间重算触发判定（关系跨档检测点——状态机重算后状态码变化 →
     * 决策模型 boolean 确认触发空间重算 RELATION_JUMP；异步提交零阻断；字段注入保持构造器不变）
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SpaceTriggerJudge spaceTriggerJudge;

    /** 动态关系总开关（默认 true；false=互动不增减好感/信任，手动 CRUD 仍可用） */
    @Value("${holzyn.actor.learning.relations.enabled:true}")
    private boolean relationsEnabled;

    /** 冲突/失信类触发词（负向通道：命中即把增量改为负向，见 {@link #shapeShift}） */
    private static final List<String> CONFLICT_WORDS = List.of(
            "冲突", "争吵", "争执", "斥责", "责骂", "翻脸", "闹翻", "断交", "绝交", "失信", "背信",
            "赖账", "欺骗", "撒谎", "偷", "抢", "殴打", "打架", "动手", "羞辱", "冷落", "记恨",
            "结怨", "闹到", "不欢而散", "撕破脸", "挨骂", "被拒");

    /** 互助/照拂类触发词（显著加权：±1 的小增量升级为 ±2） */
    private static final List<String> CARE_WORDS = List.of(
            "帮助", "帮衬", "搭把手", "照看", "照顾", "探望", "安慰", "劝", "送", "赠", "借给",
            "分给", "教", "护", "救", "接济", "替", "陪同", "守", "让路", "相让");

    /** 重大冲击类触发词（一次性大位移，允许 -8） */
    private static final List<String> MAJOR_WORDS = List.of(
            "绝交", "断交", "丧", "死", "病危", "救命", "成婚", "拜堂", "认亲", "弃", "卖身", "逐出");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 关系增量整形结果。
     *
     * @param affinity 整形后的好感增量
     *
     * @param trust 整形后的信任增量
     *
     * @param importance 本次关系事件重要度（1-5；写入 kind=relation 事件，供记忆沉淀与熵统计）
     *
     * @param salience 整形依据（中文短语，写进事件 payload 便于复核）
     */
    record RelationShift(int affinity, int trust, int importance, String salience) {
    }

    /**
     * 关系增量整形（纯静态可单测）：把「一阶计数器」式的小增量改成有显著性差异的演化。
     *
     * <p><b>为什么必须整形</b>：澄江镇二日实跑的关系审计是三维一致地失败——
     * 3134 次变化里 <b>91.1% 正好 +1</b>、<b>95.3% 正向</b>、118 对中 <b>32 对打满好感 100 后仍在继续 +1</b>，
     * 且 {@code kind=relation} 事件的 importance 恒为 3（方差为零）。这意味着「关系」不是关系，
     * 是一个只会上升的计数器：既无法表达冲突（负向通道缺失），也无法表达「已经很熟了」
     * （饱和后继续加），更谈不上显著性（Δ 全都一样大）。</p>
     *
     * <p><b>三条规则</b>（只作用于小增量区间，大增量视为调用方已给出量级，原样放行）：</p>
     * <ol>
     * <li><b>饱和转维</b>：好感已到 ±100 时，正向/负向增量不再堆在好感上，转成信任的增减
     * （{@code 好感 100 之后还能更熟，但那是"信不信得过"，不是"喜不喜欢"}）；</li>
     * <li><b>负向通道</b>：触发源命中冲突/失信词 → 增量<b>强制取负</b>，幅度按重要度取
     * 3/5/8（重大词 → 一次性 -8），让一次翻脸真的抵得上几十次寒暄；</li>
     * <li><b>显著加权</b>：±1 的小增量若来自互助/照拂 → 升为 ±2；来自重大冲击 → 升为 ±6。
     * 其余原样保留（避免把所有日常互动都放大成戏剧）。</li>
     * </ol>
     *
     * <p><b>为什么不改调用方</b>：{@code RelationDynamicsService.updateRelation} 是关系表的
     * 单一写入口（设计纪律：每张表只有一个领域服务写入）。整形放在这里一次收口，
     * 对话/行动/事件/效果层四条来源自动同口径，不需要四处改 delta 常量。</p>
     *
     * @param oldAffinity 当前好感（-100~100）
     *
     * @param affinity 调用方给的好感增量
     *
     * @param trust 调用方给的信任增量
     *
     * @param reason 变更原因（对话/行动/事件摘要）
     *
     * @return 整形结果（永不返回 null）
     */
    static RelationShift shapeShift(int oldAffinity, int affinity, int trust, String reason) {
        String text = reason == null ? "" : reason;
        boolean major = containsAny(text, MAJOR_WORDS);
        boolean conflict = major || containsAny(text, CONFLICT_WORDS);
        boolean care = !conflict && containsAny(text, CARE_WORDS);
        int importance = major ? 5 : conflict ? 4 : care ? 3 : 2;
        // ① 饱和转维：好感打满后，增量转成信任（真的还有东西可以变，而不是空转）
        if (oldAffinity >= 100 && affinity > 0) {
            int spill = Math.min(2, Math.max(1, affinity));
            return new RelationShift(0, trust + spill, 2, "好感打满→转信任");
        }
        if (oldAffinity <= -100 && affinity < 0) {
            int spill = Math.min(2, Math.max(1, -affinity));
            return new RelationShift(0, trust - spill, 4, "好感见底→转信任");
        }
        // ② 大增量：调用方已按情境给出量级（效果层/显式惩罚），原样放行
        if (Math.abs(affinity) > 3) {
            return new RelationShift(affinity, trust, importance, "显式量级");
        }
        int sign = Integer.signum(affinity);
        if (sign == 0) {
            return new RelationShift(0, trust, importance, "中性互动");
        }
        // ③ 负向通道：冲突/失信一律取负（哪怕调用方给的是正向小增量）
        if (conflict) {
            int mag = major ? 8 : importance >= 4 ? 5 : 3;
            return new RelationShift(-Math.max(Math.abs(affinity), mag), trust, importance,
                    major ? "重大冲突（-8）" : "冲突/失信（取负）");
        }
        // ④ 显著加权：只放大 ±1 的小增量（±2/±3 多来自有明确动作的调用方，保持原样）
        if (Math.abs(affinity) == 1) {
            if (care) {
                return new RelationShift(sign * 2, trust, importance, "互助/照拂（+1→±2）");
            }
            if (major) {
                return new RelationShift(sign * 6, trust, importance, "重大冲击（±6）");
            }
        }
        return new RelationShift(affinity, trust, importance, "常规");
    }

    /** 文本是否命中任一触发词（空文本/空词表返回 false）。 */
    private static boolean containsAny(String text, List<String> words) {
        if (text == null || text.isBlank() || words == null || words.isEmpty()) {
            return false;
        }
        for (String w : words) {
            if (w != null && !w.isEmpty() && text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 好感/信任更新（LearningService.updateRelation 真实实现）：
     * 解析端点 → 查找或创建关系行 → 增减（越界钳制）→ 状态机重算 → 发布 relation 事件。
     *
     * @param projectId 项目 ID
     *
     * @param fromRef 发起方统一角色引用（special:{id}/crowd:{id}/名称）
     *
     * @param toRef 目标方统一角色引用
     *
     * @param delta 好感增量（-100~100；0=仅刷新互动时间，不新建行）
     *
     * @param reason 变更原因（对话/行动/事件摘要）
     */
    @Transactional
    public void updateRelation(Long projectId, String fromRef, String toRef, int delta, String reason) {
        // 既有语义：信任随好感同向变化，幅度为好感一半（防信任过快饱和/崩塌）。
        updateRelation(projectId, fromRef, toRef, delta, delta / 2, reason);
    }

    /**
     * 好感/信任精确更新（效果层）：两个增量分别钳制，仍走同一套
     * 建行/镜像基线/状态机/事件逻辑，保持单一写入口。
     *
     * @param projectId     项目 ID
     * @param fromRef       发起方统一角色引用
     * @param toRef         目标方统一角色引用
     * @param affinityDelta 好感增量（-100~100）
     * @param trustDelta    信任增量（-100~100；与好感分开钳制）
     * @param reason        变更原因
     */
    @Transactional
    public void updateRelation(Long projectId, String fromRef, String toRef,
                               int affinityDelta, int trustDelta, String reason) {
        if (!relationsEnabled || projectId == null) {
            return;
        }
        String from = trimRef(fromRef);
        String to = trimRef(toRef);
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        Endpoint fromEp = resolveEndpoint(projectId, from);
        Endpoint toEp = resolveEndpoint(projectId, to);
        if (fromEp == null || toEp == null) {
            return;
        }
        // 幽灵端点（名称未命中任何真实角色）不参与动态关系建模——互动只作用于真实角色之间
        if (fromEp.ref() == null || toEp.ref() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ActorCharacterRelation rel = findRelation(projectId, fromEp, toEp);
        if (affinityDelta == 0 && trustDelta == 0) {
            // 中性互动：仅刷新最近互动时间（不新建行、不发布事件，避免噪声）
            if (rel != null) {
                rel.setLastInteractionAt(now);
                relationRepository.save(rel);
            }
            return;
        }
        if (rel == null) {
            rel = createRelation(projectId, fromEp, toEp, "未知关系", null);
            // 修（单向塌陷）：该方向的行首次建立时，若反向行已存在，则以反向行的
            // 好感/信任/关系类型为基线。否则同一对关系会从 0 起算——既造成「一边 55、一边 0」
            // 的单边塌陷（实测李小婉→钱串子 55→4），也让「新建行涨幅巨大」污染关系惯性口径。
            ActorCharacterRelation mirror = findRelation(projectId, toEp, fromEp);
            if (mirror != null) {
                int baseAffinity = mirror.getAffinity() == null ? 0 : mirror.getAffinity();
                int baseTrust = mirror.getTrust() == null ? 0 : mirror.getTrust();
                rel.setAffinity(baseAffinity);
                rel.setTrust(baseTrust);
                rel.setRelationState(stateOf(baseAffinity, baseTrust));
                if (mirror.getRelationType() != null && !mirror.getRelationType().isBlank()) {
                    rel.setRelationType(mirror.getRelationType());
                }
                relationRepository.save(rel);
            }
        }
        int oldAffinity = rel.getAffinity() == null ? 0 : rel.getAffinity();
        int oldTrust = rel.getTrust() == null ? 0 : rel.getTrust();
        // 关系显著性整形（单一写入口收口）：饱和转信任负向通道小增量显著加权。
        // 调用方（对话/行动/事件/效果层）仍按原口径给 delta，整形口径只在这里生效。
        RelationShift shift = shapeShift(oldAffinity, affinityDelta, trustDelta, reason);
        affinityDelta = shift.affinity();
        trustDelta = shift.trust();
        int newAffinity = clamp(oldAffinity + affinityDelta, -100, 100);
        // 信任独立钳制（既有 5 参入口传 delta/2，保持旧语义）
        int newTrust = clamp(oldTrust + trustDelta, 0, 100);
        String oldState = rel.getRelationState();
        String newState = stateOf(newAffinity, newTrust);
        rel.setAffinity(newAffinity);
        rel.setTrust(newTrust);
        rel.setRelationState(newState);
        rel.setLastInteractionAt(now);
        relationRepository.save(rel);
        // 事件驱动重算触发：关系跨档检测点——状态机重算后状态码变化 →
        // 异步提交判定（本方法在事务内，AI 判定在虚拟线程执行，不占本事务连接；
        // 决策模型 boolean 确认 + 程序化兜底 → 空间重算 RELATION_JUMP；零阻断）
        if (spaceTriggerJudge != null && oldState != null && !oldState.equals(newState)) {
            try {
                spaceTriggerJudge.onRelationJump(projectId, from, oldState, newState,
                        from + " 与 " + to + " 的关系从「" + oldState + "」变为「" + newState
                                + "」（好感 " + oldAffinity + "→" + newAffinity + "）", null);
            } catch (Exception e) {
                log.debug("[Learning] 关系跨档触发判定提交失败（跳过）: project={} : {}", projectId, e.getMessage());
            }
        }
        AiCallContext.recordStep(projectId, "agent_learning", "learning",
                "关系好感更新：" + from + "→" + to + " delta=" + affinityDelta + "/" + trustDelta
                        + "（affinity " + oldAffinity + "→" + newAffinity
                        + " trust " + oldTrust + "→" + newTrust + " 状态=" + rel.getRelationState()
                        + " 整形=" + shift.salience() + "）", 0);
        log.info("[Learning] 关系好感更新：project={} {}→{} delta={}/{}（affinity {}→{} 状态={} 整形={}）",
                projectId, from, to, affinityDelta, trustDelta, oldAffinity, newAffinity,
                rel.getRelationState(), shift.salience());
        publishRelationEvent(projectId, rel, fromEp, toEp, affinityDelta, reason, oldAffinity, oldTrust,
                shift.importance());
    }

    /**
     * 群聊轮互动：一对角色双向增减好感。
     *
     * @param projectId 项目 ID
     *
     * @param refA 参与方 A ref
     *
     * @param refB 参与方 B ref
     *
     * @param delta 好感增量
     *
     * @param reason 变更原因
     */
    public void applyDialogPairDelta(Long projectId, String refA, String refB, int delta, String reason) {
        if (refA == null || refB == null || refA.equals(refB)) {
            return;
        }
        // 先按镜像基线把两个方向的行都建好（0 增量），再统一叠加 delta。
        // 否则第二次 updateRelation 会把「同一次互动」既算进镜像基线、又再加一次 delta
        // （正向 1 → 反向先继承 1 再 +1 = 2），一次群聊轮被记成两次互动。
        seedDirection(projectId, refA, refB);
        seedDirection(projectId, refB, refA);
        updateRelation(projectId, refA, refB, delta, reason);
        updateRelation(projectId, refB, refA, delta, reason);
    }

    /**
     * 确保某方向的关系行存在（不加减好感）：缺行时以反向行为基线建行。
     * <p>群聊双向互动的「先建两边、再加 delta」步骤；与 {@link #updateRelation} 的镜像基线
     * 语义一致，但不会把本次 delta 重复计入基线。</p>
     *
     * @param projectId 项目 ID
     *
     * @param fromRef   来源 ref
     *
     * @param toRef     目标 ref
     */
    private void seedDirection(Long projectId, String fromRef, String toRef) {
        if (!relationsEnabled || projectId == null) {
            return;
        }
        String from = trimRef(fromRef);
        String to = trimRef(toRef);
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        Endpoint fromEp = resolveEndpoint(projectId, from);
        Endpoint toEp = resolveEndpoint(projectId, to);
        if (fromEp == null || toEp == null || fromEp.ref() == null || toEp.ref() == null) {
            return;
        }
        if (findRelation(projectId, fromEp, toEp) != null) {
            return;
        }
        ActorCharacterRelation seeded = createRelation(projectId, fromEp, toEp, "未知关系", null);
        ActorCharacterRelation mirror = findRelation(projectId, toEp, fromEp);
        if (mirror != null) {
            int baseAffinity = mirror.getAffinity() == null ? 0 : mirror.getAffinity();
            int baseTrust = mirror.getTrust() == null ? 0 : mirror.getTrust();
            seeded.setAffinity(baseAffinity);
            seeded.setTrust(baseTrust);
            seeded.setRelationState(stateOf(baseAffinity, baseTrust));
            if (mirror.getRelationType() != null && !mirror.getRelationType().isBlank()) {
                seeded.setRelationType(mirror.getRelationType());
            }
            relationRepository.save(seeded);
        }
    }

    /**
     * 行动挂钩：把「行动目标名称」解析为项目内角色 ref（特殊/普通 NPC；幽灵不建模返回 null），
     * 命中且非自身时应用单向好感增量（行动方 → 目标方）。
     *
     * @param projectId 项目 ID
     *
     * @param actorRef 行动方 ref
     *
     * @param targetName 决策 target 字段（角色名/地点名，仅角色名命中才建模）
     *
     * @param delta 按行动 type 映射的好感增量
     *
     * @param reason 变更原因
     */
    public void applyActionDelta(Long projectId, String actorRef, String targetName, int delta, String reason) {
        String targetRef = resolveTargetRef(projectId, targetName);
        if (targetRef == null || targetRef.equals(actorRef)) {
            return;
        }
        updateRelation(projectId, actorRef, targetRef, delta, reason);
    }

    /**
     * 把行动目标名称解析为项目内角色 ref（特殊/普通 NPC 名称匹配；不命中返回 null）。
     */
    public String resolveTargetRef(Long projectId, String targetName) {
        Endpoint ep = resolveEndpoint(projectId, targetName);
        return ep == null || ep.ref() == null ? null : ep.ref();
    }

    /**
     * 关系状态机（静态纯逻辑，可单测）：affinity+trust 双维度定状态。
     * <p>映射：</p>
     * <ul>
     * <li>hostile 敌对（affinity ≤ -60）；</li>
     * <li>stranger 陌生（affinity &lt; 15）；</li>
     * <li>acquaintance 相识（15 ≤ affinity ≤ 45，或高好感但信任不足）；</li>
     * <li>friend 朋友（affinity &gt; 45 且 trust ≥ 40）；</li>
     * <li>intimate 亲密（affinity &gt; 70 且 trust ≥ 60）。</li>
     * </ul>
     *
     * @param affinity 好感（-100~100）
     *
     * @param trust 信任（0~100）
     *
     * @return 关系状态键
     */
    public static String stateOf(int affinity, int trust) {
        if (affinity <= HOSTILE_MAX) {
            return "hostile";
        }
        if (affinity <= RIVAL_MAX) {
            return "rival";
        }
        if (affinity < STRANGER_MIN) {
            return "stranger";
        }
        if (affinity > INTIMATE_MIN && trust >= INTIMATE_TRUST) {
            return "intimate";
        }
        if (affinity > FRIEND_MIN && trust >= FRIEND_TRUST) {
            return "friend";
        }
        return "acquaintance";
    }

    /**
     * 关系状态中文标签（展示用；未知回退「未知」）。
     */
    public static String stateLabel(String state) {
        if (state == null) {
            return "未知";
        }
        return switch (state) {
            case "hostile" -> "敌对";
            case "rival" -> "对手";
            case "stranger" -> "陌生";
            case "acquaintance" -> "相识";
            case "friend" -> "朋友";
            case "intimate" -> "亲密";
            default -> state;
        };
    }

    // ==================== 私有辅助 ====================

    /** 端点解析（名称优先正式 ref）；npc:{id} 不再编码类型，此处按意图 A 处理（单表仓库） */
    private Endpoint resolveEndpoint(Long projectId, String refOrName) {
        RelationRefs.Endpoint ep = RelationRefs.resolve(projectId, refOrName, characterRepository);
        if (ep == null) {
            return null;
        }
        // 已软删除角色不再参与动态关系：否则关系行/关系事件会把"不存在的人"继续带回世界。
        if (ep.id() != null) {
            com.holzyn.actor.domain.npc.profile.entity.ActorCharacter ch =
                    characterRepository.findById(ep.id()).orElse(null);
            if (ch != null && Integer.valueOf(1).equals(ch.getDeleted())) {
                return null;
            }
        }
        return new Endpoint(ep.ref(), ep.id(), ep.name());
    }

    /** 查找项目内匹配 from→to 方向的关系行（正式 ref 优先精确匹配） */
    private ActorCharacterRelation findRelation(Long projectId, Endpoint from, Endpoint to) {
        for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
            if (RelationRefs.endpointMatches(r.getFromRef(), r.getFromCharacterId(), r.getFromName(), toEndpoint(from))
                    && RelationRefs.endpointMatches(r.getToRef(), r.getToCharacterId(), r.getToName(), toEndpoint(to))) {
                return r;
            }
        }
        return null;
    }

    /** Endpoint → RelationRefs.Endpoint（统一类型） */
    private static RelationRefs.Endpoint toEndpoint(Endpoint e) {
        return new RelationRefs.Endpoint(e.ref(), e.id(), e.name());
    }

    /** 创建关系行（按端点存储：special 存 id+名+ref；crowd 存名+ref；幽灵存名） */
    private ActorCharacterRelation createRelation(Long projectId, Endpoint from, Endpoint to, String type, String desc) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setProjectId(projectId);
        RelationRefs.Store fs = RelationRefs.storeOf(toEndpoint(from));
        RelationRefs.Store ts = RelationRefs.storeOf(toEndpoint(to));
        r.setFromCharacterId(fs.characterId());
        r.setFromName(fs.name());
        r.setFromRef(fs.ref());
        r.setToCharacterId(ts.characterId());
        r.setToName(ts.name());
        r.setToRef(ts.ref());
        r.setRelationType(type == null || type.isBlank() ? "未知关系" : type);
        r.setDescription(desc);
        return relationRepository.save(r);
    }

    /**
     * 发布「关系被人工改动」事件（kind=relation，源=manual）。
     *
     * <p><b>为什么补这一条</b>：关系拓扑工作台的实时刷新订阅的是世界事件流
     * （{@code /api/worlds/{id}/world-events/stream}，按 {@code kind=relation} 过滤），
     * 而此前只有 AI 互动引起的动态变化会发事件——手动新增/编辑/删除关系时，
     * 另一个标签页（或另一位协作者）的画布不会更新，"实时"看起来是坏的。
     * 这里把手动路径也接上同一条总线：<b>纯新增</b>，不改任何既有字段语义，
     * 且下游 {@code LearningRelationEventSubscriber}／{@code CognitiveAttentionService} 本就
     * 显式跳过 kind=relation，不会产生自触发回路。</p>
     *
     * @param projectId 项目 ID
     *
     * @param rel 关系行（已落库）
     *
     * @param action 动作：create / update / delete
     */
    public void publishManualRelationEvent(Long projectId, ActorCharacterRelation rel, String action) {
        if (rel == null) {
            return;
        }
        try {
            List<String> refs = new ArrayList<>();
            if (rel.getFromRef() != null && !rel.getFromRef().isBlank()) {
                refs.add(rel.getFromRef());
            }
            if (rel.getToRef() != null && !rel.getToRef().isBlank()) {
                refs.add(rel.getToRef());
            }
            String fromName = rel.getFromName() == null || rel.getFromName().isBlank() ? "未知" : rel.getFromName();
            String toName = rel.getToName() == null || rel.getToName().isBlank() ? "未知" : rel.getToName();
            String act = action == null || action.isBlank() ? "update" : action;
            String verb = switch (act) {
                case "create" -> "新增";
                case "delete" -> "删除";
                default -> "更新";
            };
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("relationId", rel.getId());
            payload.put("action", act);
            payload.put("fromRef", rel.getFromRef() == null ? fromName : rel.getFromRef());
            payload.put("toRef", rel.getToRef() == null ? toName : rel.getToRef());
            payload.put("fromName", fromName);
            payload.put("toName", toName);
            payload.put("relationType", rel.getRelationType());
            payload.put("affinity", rel.getAffinity());
            payload.put("trust", rel.getTrust());
            payload.put("relationState", rel.getRelationState());
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId, EventSpec.builder()
                    .kind("relation")
                    .source("manual")
                    .characterRefs(refs)
                    .visibility("global")
                    .title("关系" + verb + "：" + fromName + (act.equals("create") ? " → " : " ↔ ") + toName)
                    .content("类型=" + (rel.getRelationType() == null ? "未填" : rel.getRelationType())
                            + "，好感=" + rel.getAffinity() + "，信任=" + rel.getTrust()
                            + "，状态=" + stateLabel(rel.getRelationState()))
                    .payloadJson(toJson(payload))
                    .sourceKey("manual:rel:" + rel.getId() + ":" + act + ":" + System.nanoTime())
                    .build());
        } catch (Exception e) {
            log.warn("[关系CRUD] 手动关系事件发布失败: relation={} : {}", rel.getId(), e.getMessage());
        }
    }

    /** 发布关系变迁事件（kind=relation，源=learning；source_key 唯一防幂等折叠） */
    private void publishRelationEvent(Long projectId, ActorCharacterRelation rel, Endpoint from, Endpoint to,
                                      int delta, String reason, int oldAffinity, int oldTrust,
                                      int importance) {
        try {
            List<String> refs = new ArrayList<>();
            if (from.ref() != null) {
                refs.add(from.ref());
            }
            if (to.ref() != null) {
                refs.add(to.ref());
            }
            if (refs.isEmpty()) {
                return; // 两端均为幽灵（无正式角色），不发布参与事件
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("relationId", rel.getId());
            payload.put("fromRef", from.ref() == null ? from.name() : from.ref());
            payload.put("toRef", to.ref() == null ? to.name() : to.ref());
            payload.put("fromName", from.name());
            payload.put("toName", to.name());
            payload.put("delta", delta);
            payload.put("affinity", rel.getAffinity());
            payload.put("trust", rel.getTrust());
            payload.put("relationState", rel.getRelationState());
            payload.put("reason", reason == null ? "" : reason);
            // 重要度随触发源分化：此前恒为 3 → kind=relation 事件 importance 方差为零，
            // 记忆沉淀与叙事熵统计都拿不到「哪次关系变化才要紧」的信号。
            payload.put("importance", importance);
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId, EventSpec.builder()
                    .kind("relation")
                    .source("learning")
                    .characterRefs(refs)
                    .visibility("global")
                    .importance(importance)
                    .title("关系变化：" + displayName(from) + " 对 " + displayName(to))
                    .content("好感 " + oldAffinity + "→" + rel.getAffinity() + "（Δ" + delta + "），信任 "
                            + oldTrust + "→" + rel.getTrust() + "，状态=" + stateLabel(rel.getRelationState())
                            + (reason == null || reason.isBlank() ? "" : "；" + reason))
                    .payloadJson(toJson(payload))
                    .sourceKey("learning:rel:" + rel.getId() + ":" + System.nanoTime())
                    .build());
        } catch (Exception e) {
            log.warn("[Learning] 关系变迁事件发布失败: relation={} : {}", rel.getId(), e.getMessage());
        }
    }

    /** 端点显示名（名称快照优先，null 回退「未知」） */
    private static String displayName(Endpoint e) {
        return e.name() == null || e.name().isBlank() ? "未知" : e.name();
    }

    /** 引用去空白（null/空白返回 null） */
    private static String trimRef(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    /** 数值钳制到 [min, max] */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** payload → JSON（失败返回空对象串，不阻断） */
    private static String toJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 端点轻量封装（与 RelationRefs.Endpoint 对齐，供本服务内部使用） */
    private record Endpoint(String ref, Long id, String name) {
    }
}
