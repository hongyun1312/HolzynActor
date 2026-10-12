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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链式反应服务。
 * <p>职责（主开发方案《HolzynActor_实时行为决策系统_开发路径》§4.5 真人感五维度之
 * 「链式反应」）：特殊 NPC 行动执行后，触发<b>同地点其他特殊 NPC + 关系密切（好感或信任 ≥
 * {@code chain-related-threshold} 默认 60）的异地相关人物</b>（每行动上限 {@code chain-related-max}
 * 默认 3）经新刺激类型 {@link StimulusType#CHAIN_REACTION} 走<b>完整即时决策管线</b>
 * （探针→打断→决策→恢复，成本护栏与普通即时决策一致）；<b>一跳不递归</b>（链式产出的响应行动
 * 带 {@code _chainReaction} 标记，其执行不再触发链式）；<b>防抖</b>复用每角色每刺激源冷却
 * （CHAIN_REACTION 独立冷却键）。</p>
 * <p>/ /：链式=新刺激类型 + 同地点+关系网扩散（世界更真实）；开启后由本服务
 * <b>统一接管同地点触发</b>（RealTimeDecisionService 的 kind=action 事件分支在链式开启时跳过，
 * 防双决策精分）；<b>人群围观</b>：重大行动（决策 urgency ≥
 * {@code chain-crowd-importance} 默认 4）时同地点人群 NPC 程序化围观/议论/躲开（零 AI，落
 * thought_text + SSE），链式反应<b>不落 actor_event</b>（避免刷屏时间线，靠 SSE/日志可见）。</p>
 * <p>设计依据（联网调研）：Emergent Crowds Dynamics（arXiv:2508.15047，对话即信息传递）/
 * Emergent Coordinated Behaviors in Networked LLM Agents（arXiv:2510.25003，级联扩散：一跳 +
 * hop 上限 + 扇出限制防风暴）——链式反应一跳扩散 + 每角色每刺激源冷却 + 关系强度邻接是
 * 「行动引发周围连锁反应」的直接模板。</p>
 * <p>成本：链式触发本身零 AI（判定/解析纯程序化）；AI 成本由被触发角色各自的即时决策
 * （预算门控 + 冷却 + 探针）控制；人群围观零 AI。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainReactionService {

    /** 链式响应行动标记（写入响应行动 action_json：一跳防环判定依据） */
    static final String CHAIN_MARKER = "_chainReaction";

    /** 人群围观文本池（按围观序号确定性轮换） */
    private static final String[] CROWD_ONLOOK_TEXTS = {
            "凑过来围观：" , "远远地看着，与身边人交头接耳：" , "躲到一旁，悄悄张望："
    };

    private final RealtimeDecisionProperties props;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorCharacterRelationRepository relationRepository;

    /**
     * NPC 类型解析器（注入：链式反应属「只有特殊型才做」——npc:{id} 不再编码类型，
     * 「排除普通型」的过滤改用本解析器查 actor_profile.type；Spring 自动装配）
     */
    private final NpcTypeResolver npcTypeResolver;

    /**
     * 实时行为决策服务（链式刺激触发；@Lazy 破 Bean 环[经 AgentActionService→AgentRuntime→本服务→
     * 实时决策服务→AgentRuntime]；null=未装配跳过触发）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private RealTimeDecisionService realtimeDecisionService;

    /** 世界时钟（链式刺激的游戏时刻基准；null=未知，按 null 传递由上层回退） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 沙盘状态服务（人群围观 thought_text + SSE；null=未装配跳过围观落点） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /**
     * NPC 作息运行态访问器。
     * <p><b>为什么必须有它</b>：人群围观要把围观文本写进该 NPC 的"最近行动"，而
     * {@code state} / {@code last_action} / {@code daily_plan_json} 三个运行态字段已随
     * {@code actor_profile_ordinary} 物理废弃迁入 {@code actor_state}
     * （{@code activity_state} / {@code last_action} / {@code daily_plan_json}，运行态唯一家）——
     * 主表实体 {@link ActorCharacter} 上再无 {@code setLastAction} 之类入口，改写运行态只能经本访问器。</p>
     * <p>字段注入（非 final）保持既有构造器签名不变（存量单测零改动）；{@code null}=未装配 →
     * 退化为"只广播思考气泡、不改运行态"（零阻断，不影响主流程）。</p>
     */
    @Autowired(required = false)
    private NpcActivityStateService npcActivityStateService;

    /**
     * 行动执行后的链式反应入口（AgentActionService.execute 挂钩）。
     * <p>触发条件：链式开关开启 + 特殊 NPC 行动 + 非链式产出的行动（一跳防环）+ 激活闸门
     * （被触发角色的 onStimulus 内部再校验）。产出：① 同地点其他特殊 NPC → CHAIN_REACTION 即时决策；
     * ② 关系密切异地相关人物（≤上限）→ 同上；③ 重大行动人群围观（零 AI）。</p>
     *
     * @param ch 行动角色实体（null → 跳过）
     *
     * @param plan 已执行的行动计划（null → 跳过）
     *
     * @param decision 行动决策 JSON（null → 按无 urgency 处理）
     */
    public void onActionExecuted(ActorCharacter ch, ActorActionPlan plan, JsonNode decision) {
        if (!props.isChainReactionEnabled() || ch == null || ch.getProjectId() == null) {
            return;
        }
        Long projectId = ch.getProjectId();
        // ① 一跳防环：链式产出的响应行动不再传播（标记在行动 JSON）
        if (decision != null && decision.path(CHAIN_MARKER).asBoolean(false)) {
            log.debug("[链式] 链式响应行动不再传播（一跳）: char={} plan={}", ch.getId(),
                    plan == null ? "?" : plan.getId());
            return;
        }
        Long gameSecond = currentGameSecond(projectId);
        String location = ch.getLocation();
        String actorName = ch.getName() == null || ch.getName().isBlank() ? "有人" : ch.getName().trim();
        String actionText = decision == null ? "" : decision.path("action").asText("");
        if (actionText == null || actionText.isBlank()) {
            actionText = ch.getCurrentActivity() == null ? "做着一件要紧事" : ch.getCurrentActivity();
        }
        String stimulus = chainStimulusText(actorName, location, actionText);

        // ② 触发对象合并（同地点特殊 NPC + 关系密切异地相关人物，去重保序）
        Set<String> seen = new LinkedHashSet<>();
        List<String> targets = new ArrayList<>();
        List<ActorCharacter> chars = safeChars(projectId);
        for (String ref : resolveLocationRefs(chars, location, ch.getId())) {
            if (seen.add(ref)) {
                targets.add(ref);
            }
        }
        int urgency = decision == null ? 0 : decision.path("urgency").asInt(0);
        for (String ref : resolveRelatedRefs(relationRepository.findByProjectId(projectId),
                projectId, ch.getId(), props.getChainRelatedThreshold(), props.getChainRelatedMax())) {
            if (seen.add(ref)) {
                targets.add(ref);
            }
        }

        // ③ 人群围观（重大行动，零 AI）
        if (urgency >= props.getChainCrowdImportance()) {
            crowdOnlook(projectId, location, actorName, actionText, gameSecond);
        }

        if (targets.isEmpty()) {
            return;
        }
        // ④ 逐目标触发 CHAIN_REACTION 完整即时决策（相关人物=行动者，社交差异化注入）
        List<String> relatedNames = List.of(actorName);
        for (String ref : targets) {
            try {
                realtimeDecisionService.onChainReaction(projectId, ref, stimulus, gameSecond, relatedNames);
                log.info("[链式] 触发链式反应: project={} actor={} 目标={} 刺激={}",
                        projectId, actorName, ref, abbreviate(stimulus, 60));
            } catch (Exception e) {
                log.warn("[链式] 链式触发失败（隔离继续）: 目标={} : {}", ref, e.getMessage());
            }
        }
    }

    // ==================== 触发对象解析（纯逻辑，可单测） ====================

    /**
     * 同地点特殊 NPC 触发对象解析（排除行动者自身/已删除）。
     *
     * @param characters 项目特殊 NPC 列表（可空）
     *
     * @param location 行动者所在地点（可空）
     *
     * @param selfId 行动者角色 ID（排除自身）
     *
     * @return 同地点其他特殊 NPC 的 ref 列表
     */
    static List<String> resolveLocationRefs(List<ActorCharacter> characters, String location, Long selfId) {
        List<String> out = new ArrayList<>();
        if (characters == null || location == null || location.isBlank()) {
            return out;
        }
        for (ActorCharacter ch : characters) {
            if (ch == null || ch.getId() == null || ch.getId().equals(selfId)
                    || Integer.valueOf(1).equals(ch.getDeleted())) {
                continue;
            }
            if (RealTimeDecisionService.locationEquals(location, ch.getLocation())) {
                // ：npc:{id} 不再编码类型，此处为统一引用拼装（是否特殊型由下游 isSpecial 判定）
                out.add(NpcRefs.of(ch.getId()));
            }
        }
        return out;
    }

    /**
     * 关系密切异地相关人物解析：
     * 从项目关系表筛出与行动者有关、对方为特殊 NPC、好感/信任达标的行，
     * 按 max(好感, 信任) 降序截断；同地点者不在此层（已在位置层触发，调用方去重）。
     *
     * @param relations 项目关系行（可空）
     *
     * @param projectId 项目 ID
     *
     * @param selfId 行动者角色 ID（排除自身/自环）
     *
     * @param threshold 关系密切阈值（好感或信任 ≥ 此值）
     *
     * @param max 触发上限（按关系强度降序截断）
     *
     * @return 相关人物 ref 列表（npc:{id}，按关系强度降序 ≤ 上限）
     */
    List<String> resolveRelatedRefs(List<ActorCharacterRelation> relations, Long projectId,
                                    Long selfId, int threshold, int max) {
        List<String> out = new ArrayList<>();
        if (relations == null || selfId == null || max <= 0) {
            return out;
        }
        // 关系行按「max(好感,信任) 降序」排序后取前 max 名
        relations.stream()
                .filter(r -> r != null && r.getProjectId() != null && r.getProjectId().equals(projectId))
                .map(r -> counterpartOf(r, selfId))
                // ：npc:{id} 不再编码类型，此处按意图 B 处理——原 !startsWith("crowd:")
                // 是「排除普通型（只有特殊型才参与链式反应）」，改用类型解析器查 actor_profile.type
                .filter(c -> c != null && c.ref() != null && npcTypeResolver.isSpecial(projectId, c.ref())
                        && !c.ref().equals(NpcRefs.of(selfId)))
                .filter(c -> c.closeness() >= threshold)
                .sorted(Comparator.comparingInt(RelatedCandidate::closeness).reversed())
                .limit(max)
                .forEach(c -> {
                    if (!out.contains(c.ref())) {
                        out.add(c.ref());
                    }
                });
        return out;
    }

    /**
     * 关系行 → 相关候选人（对方 ref + 名称 + 亲密度=max(好感,信任)）。
     */
    private static RelatedCandidate counterpartOf(ActorCharacterRelation r, Long selfId) {
        boolean actorIsFrom = selfId.equals(r.getFromCharacterId());
        boolean actorIsTo = selfId.equals(r.getToCharacterId());
        if (!actorIsFrom && !actorIsTo) {
            return null;
        }
        // 对方正式 ref 优先（from_ref/to_ref 为 npc:{id} 形态；无则按 id 推断）
        String otherRef = actorIsFrom ? r.getToRef() : r.getFromRef();
        String otherName = actorIsFrom ? r.getToName() : r.getFromName();
        Long otherId = actorIsFrom ? r.getToCharacterId() : r.getFromCharacterId();
        if (otherRef == null || otherRef.isBlank()) {
            if (otherId != null && otherId > 0) {
                // ：npc:{id} 单前缀拼装统一走 NpcRefs.of（不再手写前缀字面量）
                otherRef = NpcRefs.of(otherId);
            } else {
                return null; // 幽灵端点（普通 NPC/名称兜底）不参与特殊 NPC 链式
            }
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——引用须能解析成 actor_profile 行
        // （即统一引用形态；是否特殊型由调用方 isSpecial 判定，普通型随后被排除）
        if (!NpcRefs.isNpcRef(otherRef)) {
            return null;
        }
        int affinity = r.getAffinity() == null ? 0 : r.getAffinity();
        int trust = r.getTrust() == null ? 0 : r.getTrust();
        int closeness = Math.max(affinity, trust);
        return new RelatedCandidate(otherRef, otherName, closeness);
    }

    /**
     * 链式刺激文本（{你看到}{行动者}在{地点}：{行动摘要}）。
     */
    static String chainStimulusText(String actorName, String location, String actionText) {
        StringBuilder sb = new StringBuilder("你看到");
        sb.append(actorName == null || actorName.isBlank() ? "有人" : actorName.trim());
        if (location != null && !location.isBlank()) {
            sb.append("在").append(location.trim());
        }
        sb.append("：");
        sb.append(actionText == null || actionText.isBlank() ? "做着一件要紧事" : abbreviate(actionText.trim(), 80));
        return sb.toString();
    }

    // ==================== 人群围观（零 AI） ====================

    /**
     * 重大行动人群围观：同地点人群 NPC（≤上限）程序化产出
     * 围观/议论/躲开文本，落 thought_text（source=m4_crowd）+ SSE 并刷新该 NPC 的"最近行动"。
     * <p>刷新目标由主表实体字段改为 {@code actor_state.last_action}（运行态唯一家）——
     * 只写 {@code last_action}，<b>不动</b>{@code activity_state}（围观不得覆盖作息状态机语义）。</p>
     */
    private void crowdOnlook(Long projectId, String location, String actorName, String actionText, Long gameSecond) {
        if (sandboxStateService == null || location == null || location.isBlank()) {
            return;
        }
        try {
            // ：单表合并后「人群 NPC」= actor_profile.type='common' 的行，不能再按全量查询取
            // （全量会把特殊型也当人群围观写 thought/lastAction）——类型值统一走 NpcRefs.TYPE_COMMON
            List<ActorCharacter> npcs = ordinaryNpcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(
                    projectId, NpcRefs.TYPE_COMMON, 0);
            int max = Math.max(0, props.getChainCrowdMax());
            int idx = 0;
            // ：围观文本要落到"最近行动"，而 last_action 已随 actor_profile_ordinary 物理废弃迁入
            // actor_state（运行态唯一家，主表实体上再无该字段）——循环内只往内存缓冲塞运行态快照
            // （零查库），循环结束一次性批量写回（对齐 NpcActivityStateService 的批量纪律，防逐 NPC 查库）。
            Map<String, NpcActivityStateService.ActivityUpdate> runStateUpdates = new LinkedHashMap<>();
            for (ActorCharacter n : npcs) {
                if (n == null || idx >= max) {
                    break;
                }
                if (!RealTimeDecisionService.locationEquals(location, n.getLocation())) {
                    continue;
                }
                String text = CROWD_ONLOOK_TEXTS[Math.floorMod(idx, CROWD_ONLOOK_TEXTS.length)]
                        + actorName + abbreviate(actionText, 40);
                // ：身份引用统一走 NpcRefs.of（不手写前缀字面量）
                String ref = NpcRefs.of(n.getId());
                sandboxStateService.publishThought(projectId, ref, text, "m4_crowd", gameSecond);
                // ：只刷新 last_action——state 传 null 表示"不改动 activity_state"，围观是零 AI 旁路
                // 增强，绝不能把该 NPC 的作息状态机状态（idle/walk/stop/talk/rest）覆盖成无关值
                if (ref != null) {
                    runStateUpdates.put(ref,
                            new NpcActivityStateService.ActivityUpdate(null, abbreviate(text, 200)));
                }
                idx++;
            }
            if (idx > 0) {
                // ：运行态写回 actor_state（原 ordinaryNpcRepository.saveAll 回写实体字段的路径已随
                // 主表运行态列移除而失效）；访问器未装配/写失败 → 仅广播不改运行态（零阻断）
                if (npcActivityStateService != null && !runStateUpdates.isEmpty()) {
                    npcActivityStateService.setActivities(projectId, runStateUpdates);
                }
                log.info("[链式] 重大行动人群围观: project={} loc={} 围观={} 行动={}", projectId, location, idx,
                        abbreviate(actionText, 40));
            }
        } catch (Exception e) {
            log.warn("[链式] 人群围观失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    // ==================== 私有辅助 ====================

    /** 项目特殊 NPC 列表（失败返回空列表；长跑抓修 排除已故角色）。 */
    private List<ActorCharacter> safeChars(Long projectId) {
        try {
            return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                    .stream().filter(ch -> ch != null && !ch.isDeceased()).toList();
        } catch (Exception e) {
            log.warn("[链式] 特殊 NPC 列表读取失败（跳过链式）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /** 当前游戏秒（引擎推进位置；失败返回 null）。 */
    private Long currentGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本摘要（超长截断；null 回退空串）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    /** 关系候选人记录（对方 ref/名称/亲密度）。 */
    private record RelatedCandidate(String ref, String name, int closeness) {
    }
}
