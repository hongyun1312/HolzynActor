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

package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionTemplates;
import com.holzyn.actor.ai.nanojev.decision.NanoJevEscalationClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社交意愿门控。
 * <p><b>定位（N 轨道设计 §2.1/§4.3）</b>：世界不再有求必应——NanoJev {@code boolean}
 * 判定「此刻是否愿意停下回应/接话」，身体疲惫/情绪低落/正忙/对方陌生或关系冷淡/场合不合适
 * → 拒绝、沉默、敷衍成为常态选项；被拒绝后的世界（记忆/关系/情绪连锁）才是真实的社交网络。
 * <b>实测：社交意愿一致率 92.3% ≥80% → 本场景是 落地清单首位。</b></p>
 * <p><b>统一框架</b>：</p>
 * <ul>
 * <li><b>开关</b>：判定层总开关 && 场景开关 && NanoJev client 可用（fail-closed）；</li>
 * <li><b>采样</b>：sample（按分布加权随机——p_true=0.8 时也有 20% 拒绝，真人式的偶尔反常）；</li>
 * <li><b>升级策略</b>：top-1 概率 &lt; 0.5（分布平坦，0.6B
 * 拿不准）→ <b>升 Qwen 同题重问</b>（Qwen 深度理解社交意图）；Qwen 失败 → fail-open 愿意
 * （维持现状，零阻断）；</li>
 * <li><b>回退</b>：NanoJev 未启用/失败 → 愿意（不拦截=现状有求必应，门控是新增层，失败不降级体验）。</li>
 * </ul>
 * <p><b>判定时机</b>：对话开始前/搭话瞬间（单聊开场、被打招呼、群聊新轮次、
 * 自主对话节拍）；对话一旦开始中途不重复判定（真人开了口不会每句话重新决定理不理你）。</p>
 * <p><b>架构不变量</b>：本类含 AI 调用（NanoJev + 升级 Qwen），公开方法一律<b>不带
 * {@code @Transactional}</b>（AiCallOutsideTransactionTest 扩展守护）。</p>
 * <p>+ ai/nanojev/decision（判定基础设施）</p>
 */
@Slf4j
@Component
public class SocialWillingnessGate {

    /** 判定场景码（PromptSpecRegistry 登记 nanojev_social_willingness v2；逐场景开关键） */
    public static final String SCENE = NanoJevDecisionScenes.SOCIAL_WILLINGNESS;

    /** 升级重问场景码（运行时 Qwen 同题重问记账） */
    public static final String ESCALATION_SCENE = "nanojev_social_willingness_escalation";

    /** state 内问题键 */
    public static final String QID = "willing";

    /** 判定结果（调用方据此决定回应/拒绝与连锁）。 */
    public record WillingnessResult(
            boolean willing,
            /** nanojev / qwen_escalation / fail_open（未启用/失败/升级失败） */
            String source,
            
            String disposition,
            Double top1Prob,
            Double entropy,
            long latencyMs) {
    }

    /** 统一判定基础设施 */
    @Autowired(required = false)
    private NanoJevDecisionGate decisionGate;

    /** 升级重问客户端（可空=升级时直接 fail-open） */
    @Autowired(required = false)
    private NanoJevEscalationClient escalationClient;

    /**
     * 单角色门控判定：此刻是否愿意回应/接话。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（trace/用量归属；可空）
     *
     * @param npcName 角色名（升级重问上下文；可空）
     *
     * @param stateText 情境文本（调用方组装：当前行动/身体与情绪状态/对方是谁与关系/场合）
     *
     * @return 判定结果（willing=false=拒绝；任何失败 fail-open=愿意）
     */
    public WillingnessResult evaluate(Long projectId, Long characterId, String npcName, String stateText) {
        if (decisionGate == null || !decisionGate.isEnabled(SCENE)) {
            log.debug("[社交意愿] 未启用（判定层关/场景关/NanoJev 不可用），fail-open 愿意：characterId={}",
                    characterId);
            return new WillingnessResult(true, "fail_open", NanoJevDecisionGate.DISP_DISABLED, null, null, 0);
        }
        String stateId = "w" + (characterId == null ? 0 : characterId);
        NanoJevState state = buildState(stateId, stateText);
        NanoJevDecisionGate.BooleanOutcome out = decisionGate.booleanDecision(
                projectId, characterId, SCENE, state, QID);
        if (out == null || !out.adopted()) {
            if (out != null && NanoJevDecisionGate.DISP_ESCALATED.equals(out.disposition())) {
                // 升级：Qwen 同题重问（0.6B 拿不准 → 35B 深度理解）；Qwen 失败 fail-open 愿意
                Boolean qwenWilling = askQwen(projectId, characterId, npcName, stateText);
                if (qwenWilling != null) {
                    log.info("[社交意愿] 升级 Qwen 判定：characterId={} willing={}（top1Prob={} 熵={}）",
                            characterId, qwenWilling, out.top1Prob(), out.entropy());
                    return new WillingnessResult(qwenWilling, "qwen_escalation",
                            NanoJevDecisionGate.DISP_ESCALATED, out.top1Prob(), out.entropy(), out.latencyMs());
                }
                log.info("[社交意愿] 升级 Qwen 失败，fail-open 愿意：characterId={}", characterId);
                return new WillingnessResult(true, "fail_open", NanoJevDecisionGate.DISP_ESCALATED,
                        out.top1Prob(), out.entropy(), out.latencyMs());
            }
            // 未启用/失败 → fail-open 愿意（现状有求必应，门控失败不降级体验）
            return new WillingnessResult(true, "fail_open",
                    out == null ? NanoJevDecisionGate.DISP_FAILED : out.disposition(),
                    out == null ? null : out.top1Prob(), out == null ? null : out.entropy(),
                    out == null ? 0 : out.latencyMs());
        }
        return new WillingnessResult(out.value(), "nanojev", NanoJevDecisionGate.DISP_ADOPTED,
                out.top1Prob(), out.entropy(), out.latencyMs());
    }

    /**
     * 批量门控判定（群聊/自主对话每轮一次前向）：每个角色一个 state（≤32 官方上限）。
     * <p>返回 {@code key → 是否愿意}；升级项逐人升 Qwen（升级者少，不影响批量主链路）；
     * 任何失败 fail-open 愿意。</p>
     *
     * @param projectId 项目 ID
     *
     * @param items 键（成员引用/任意调用方键）→ 角色情境（characterId/npcName/stateText）
     *
     * @return 键 → 是否愿意回应
     */
    public Map<String, Boolean> evaluateBatch(Long projectId, Map<String, SocialContext> items) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        // ① 批量 boolean（一次前向）
        Map<String, NanoJevState> states = new LinkedHashMap<>();
        Map<String, SocialContext> byKey = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, SocialContext> en : items.entrySet()) {
            String key = en.getKey();
            SocialContext ctx = en.getValue();
            String stateId = "b" + (i++);
            states.put(key, buildState(stateId, ctx == null ? "（无情境）" : ctx.stateText()));
            byKey.put(key, ctx);
        }
        Map<String, NanoJevDecisionGate.BooleanOutcome> outcomes = decisionGate == null
                ? Map.of() : decisionGate.booleanDecisions(projectId, SCENE, states, QID);
        // ② 逐项收敛（升级 → Qwen；其余 fail-open）
        for (String key : byKey.keySet()) {
            SocialContext ctx = byKey.get(key);
            NanoJevDecisionGate.BooleanOutcome oc = outcomes.get(key);
            if (oc != null && oc.adopted()) {
                out.put(key, oc.value());
                continue;
            }
            if (oc != null && NanoJevDecisionGate.DISP_ESCALATED.equals(oc.disposition())) {
                Boolean qwenWilling = askQwen(projectId, ctx == null ? null : ctx.characterId(),
                        ctx == null ? null : ctx.npcName(), ctx == null ? null : ctx.stateText());
                out.put(key, qwenWilling == null || qwenWilling);
                continue;
            }
            out.put(key, true); // 未启用/失败 → fail-open 愿意
        }
        return out;
    }

    /** 批量判定的单项上下文。 */
    public record SocialContext(Long characterId, String npcName, String stateText) {
    }

    /** 程序化简短拒绝文本（拒绝时写入消息正文/沙盘呈现；reason 可空=通用措辞）。 */
    public String denialText(String npcName, String reason) {
        String name = npcName == null || npcName.isBlank() ? "对方" : npcName;
        if (reason != null && !reason.isBlank()) {
            return "（" + name + reason + "）";
        }
        return "（" + name + "没有理会。）";
    }

    /** 构造判定 state（情境文本 + boolean 问题；情境空时给占位防契约校验失败）。 */
    private static NanoJevState buildState(String stateId, String stateText) {
        String text = stateText == null || stateText.isBlank() ? "（角色此刻的情境未知）" : stateText.trim();
        Map<String, com.holzyn.actor.ai.nanojev.NanoJevQuestion> questions = NanoJevState.newQuestions();
        questions.put(QID, NanoJevDecisionTemplates.socialWillingnessQuestion());
        return NanoJevState.text(stateId, text, questions);
    }

    /** Qwen 同题重问（升级路径；失败返回 null=fail-open）。 */
    private Boolean askQwen(Long projectId, Long characterId, String npcName, String stateText) {
        if (escalationClient == null) {
            return null;
        }
        try {
            NanoJevEscalationClient.ParsedAnswer ans = escalationClient.ask(
                    projectId, characterId, ESCALATION_SCENE, npcName, stateText,
                    NanoJevDecisionTemplates.socialWillingnessQuestion());
            if (ans == null || ans.answer() == null) {
                return null;
            }
            return Boolean.TRUE.equals(ans.answer());
        } catch (Exception e) {
            log.warn("[社交意愿] 升级重问异常（fail-open 愿意）：{}", e.getMessage());
            return null;
        }
    }
}
