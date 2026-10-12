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

package com.holzyn.actor.domain.growth.guard;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.mask.MaskService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.growth.stage.GrowthAbility;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 三条 + 一条新护栏统一校验服务。
 * <p><b>规则</b>（stage_compliance / space_compliance /
 * developmental_knowledge_bound 三条，mask 留； mask_compliance）：</p>
 * <ul>
 * <li><b>stage_compliance</b>（硬阻断）：决策/对话输出与阶段能力冲突 → 拦截。
 * 落地形态：① 对话——INFANT（无 {@code dialogue_output}）强制对话被硬阻断，
 * 返回程序化婴儿回应（咿呀/哭闹），AI 调用不发生；② 决策——INFANT 非 continue 决策
 * 一律改 continue（婴儿没有自主行动）。</li>
 * <li><b>space_compliance</b>（硬阻断 + 剔除）：决策行动与硬门控 forbidden 冲突 → 改 continue
 * + 记拦截；空间候选冲突已在生成期剔除（{@code GrowthSpaceService.pruneConflicts}）。
 * 另：{@code space.hard-constraint=true}（调试/评估开关）时，行动不在 possible_actions 内
 * 也拦截（默认软引导不拦截）。</li>
 * <li><b>developmental_knowledge_bound</b>（硬阻断）：决策行动命中档案「知识盲区」
 * （⑥ 维 {@code knowledge_blind_spots} 短语）→ 改 continue + 记拦截——角色不可能
 * 做他根本不懂的事。</li>
 * <li><b>mask_compliance</b>：决策行动命中<b>当前面具情境的禁止表达</b>
 * （⑪ 维 {@code forbiddenExpressions}，经面具表演化后生效）→ 改 continue + 记拦截；
 * <b>独处/私人场所不设防</b>（{@link MaskService#isUndefended}）——人在独处时不需要面具。
 * 对话侧由 {@code GrowthGuardService.dialogueViolationReason} 事后判定（软：指标 + 破绽化，
 * 不替换 AI 文本——替换程序化文本比偶尔失态更假）。</li>
 * </ul>
 * <p><b>接线</b>：{@code DecisionService} 决策解析后调 {@link #validateDecision}；
 * {@code RoleplayService} 对话入口调 {@link #dialogueBlockReason}。全部走指标计数
 * （{@code GrowthMetricsService} 拦截计数器，验收「拦截数→0」观测口径）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthGuardService {

    /** 决策校验结果。 */
    public record DecisionGuardResult(boolean blocked, String reason) {
        public static DecisionGuardResult ok() {
            return new DecisionGuardResult(false, null);
        }

        public static DecisionGuardResult block(String reason) {
            return new DecisionGuardResult(true, reason);
        }
    }

    private final GrowthGateService growthGateService;
    private final GrowthSpaceService growthSpaceService;
    private final GrowthMetricsService growthMetrics;
    private final GrowthProperties growthProperties;
    private final MaskService maskService;

    /**
     * 决策护栏校验（决策解析后、执行前调用）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色行
     *
     * @param actionType 决策 action_type（continue/start_action/move_to/speak_to/wait）
     *
     * @param actionDetail 决策行动描述
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 校验结果（blocked=true 时调用方应改 continue 并记录）
     */
    public DecisionGuardResult validateDecision(Long projectId, ActorCharacter ch,
                                                String actionType, String actionDetail, Long gameSecond) {
        if (ch == null) {
            return DecisionGuardResult.ok();
        }
        if ("continue".equalsIgnoreCase(actionType)) {
            return DecisionGuardResult.ok(); // continue 天然合规（继续当前行为）
        }
        // ① stage_compliance：INFANT 无自主行动能力
        if (growthProperties.getGuardrails().isStageComplianceEnabled()
                && GrowthStage.INFANT.name().equalsIgnoreCase(ch.getStage())) {
            growthMetrics.recordStageComplianceIntercept();
            return DecisionGuardResult.block("婴儿阶段无自主行动能力（stage_compliance）");
        }
        HardGateResult gate = growthGateService.gate(projectId, ch.npcRef());
        // ② space_compliance：行动与硬门控 forbidden 冲突 → 拦截
        if (growthProperties.getGuardrails().isSpaceComplianceEnabled()
                && actionDetail != null && !actionDetail.isBlank()) {
            if (conflictsWithForbidden(gate, actionDetail)) {
                growthMetrics.recordSpaceComplianceIntercept();
                return DecisionGuardResult.block("行动与硬门控禁止项冲突（space_compliance）");
            }
            // 硬约束开关（默认关）：行动必须在空间候选内
            if (growthProperties.getSpace().isHardConstraint()
                    && !growthSpaceService.isActionInSpace(projectId, ch.npcRef(), actionDetail)) {
                growthMetrics.recordSpaceComplianceIntercept();
                return DecisionGuardResult.block("硬约束开启：行动不在此刻可发生空间内（space_compliance）");
            }
        }
        // ③ developmental_knowledge_bound：行动命中知识盲区 → 拦截
        if (growthProperties.getGuardrails().isKnowledgeBoundEnabled()
                && actionDetail != null && !actionDetail.isBlank()
                && hitsKnowledgeBlindSpot(ch, actionDetail)) {
            growthMetrics.recordKnowledgeBoundIntercept();
            return DecisionGuardResult.block("行动超出本人知识边界（developmental_knowledge_bound）");
        }
        // ④ mask_compliance：行动命中当前面具情境禁止表达 → 拦截（独处/私人不设防）
        if (growthProperties.getGuardrails().isMaskComplianceEnabled()
                && actionDetail != null && !actionDetail.isBlank() && maskService != null) {
            try {
                List<String> contexts = maskService.resolveContexts(projectId, ch.npcRef(), null, gameSecond);
                if (!maskService.isUndefended(contexts)
                        && hitsForbiddenExpressions(maskService.forbiddenOf(projectId, ch.npcRef(), contexts),
                        actionDetail)) {
                    growthMetrics.recordMaskComplianceIntercept();
                    return DecisionGuardResult.block("行动违背当前面具情境的禁止表达（mask_compliance）");
                }
            } catch (Exception e) {
                log.debug("[护栏] mask_compliance 判定失败（放行，零阻断）: {}", e.getMessage());
            }
        }
        return DecisionGuardResult.ok();
    }

    /**
     * 对话侧 mask_compliance 事后判定：AI 回复文本命中<b>当前对话情境</b>禁止表达
     * → 返回违规说明（调用方记指标 + 破绽化处理）；放行返回 null。
     * <p><b>为什么对话用「软」</b>：替换 AI 文本为程序化回应比偶尔失态更假（真实的人会失态，
     * 失态本身就是破绽事件）；硬阻断只用于决策（改 continue 不产生可见文本）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色行
     *
     * @param partnerRefs 对话对象引用（群聊=在场成员；单聊=空列表[玩家]；null=未知）
     *
     * @param gameSecond 世界时刻
     *
     * @param replyText AI 生成回复正文
     *
     * @return 违规说明（null=放行）；仅 mask 开关开且非独处/私人情境时判定
     */
    public String dialogueViolationReason(Long projectId, ActorCharacter ch, List<String> partnerRefs,
                                          Long gameSecond, String replyText) {
        if (ch == null || replyText == null || replyText.isBlank() || maskService == null
                || !growthProperties.getGuardrails().isMaskComplianceEnabled()) {
            return null;
        }
        try {
            List<String> contexts = maskService.resolveContexts(projectId, ch.npcRef(), partnerRefs, gameSecond);
            if (maskService.isUndefended(contexts)) {
                return null;
            }
            if (hitsForbiddenExpressions(maskService.forbiddenOf(projectId, ch.npcRef(), contexts), replyText)) {
                return "回复违背当前面具情境（" + String.join("、", contexts) + "）的禁止表达（mask_compliance）";
            }
        } catch (Exception e) {
            log.debug("[护栏] 对话 mask_compliance 判定失败（放行，零阻断）: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 对话硬阻断判定（对话入口调用）：阶段无 {@code dialogue_output} 能力 → 返回阻断原因，
     * 否则返回 null（放行）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色行
     *
     * @return 阻断原因（null=放行）
     */
    public String dialogueBlockReason(Long projectId, ActorCharacter ch) {
        if (ch == null || !growthProperties.getGuardrails().isStageComplianceEnabled()) {
            return null;
        }
        GrowthStage stage = GrowthStage.of(ch.getStage());
        if (stage == null) {
            return null;
        }
        HardGateResult gate = growthGateService.gate(projectId, ch.npcRef());
        if (gate.denies(GrowthAbility.DIALOGUE_OUTPUT.name())) {
            return "该角色当前阶段无语言输出能力（" + stage.label() + "），对话被阶段护栏拦截";
        }
        return null;
    }

    /** 行动描述是否与硬门控禁止项冲突（分段包含匹配，与空间生成期同口径）。 */
    private boolean conflictsWithForbidden(HardGateResult gate, String action) {
        if (gate == null || gate.forbidden() == null || action == null) {
            return false;
        }
        for (HardGateResult.ForbiddenItem f : gate.forbidden()) {
            if (f.what() == null || f.what().isBlank()) {
                continue;
            }
            for (String seg : f.what().split("[、，,;；。]")) {
                String s = seg.trim();
                if (s.length() >= 2 && action.contains(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 行动描述是否命中档案知识盲区（⑥ 维 knowledge_blind_spots；短语分段匹配）。 */
    private boolean hitsKnowledgeBlindSpot(ActorCharacter ch, String action) {
        try {
            PersonaProfile persona = PersonaJson.readQuietly(ch.getPersonaJson());
            if (persona == null || persona.getDims() == null || persona.getDims().getAbility() == null) {
                return false;
            }
            PersonaDims.Ability ability = persona.getDims().getAbility();
            List<String> blindSpots = ability.getKnowledgeBlindSpots();
            if (blindSpots == null || blindSpots.isEmpty()) {
                return false;
            }
            for (String spot : blindSpots) {
                if (spot == null || spot.isBlank()) {
                    continue;
                }
                for (String seg : spot.split("[、，,;；。]")) {
                    String s = seg.trim();
                    if (s.length() >= 2 && action.contains(s)) {
                        log.debug("[护栏] 知识盲区命中：{} 行动「{}」含盲区词「{}」", ch.npcRef(), action, s);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[护栏] 知识盲区判定失败（放行）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
        return false;
    }

    /** 便捷：盲区短语分段（调试/测试断言用）。 */
    public static List<String> segmentsOf(String phrase) {
        List<String> out = new ArrayList<>();
        if (phrase == null || phrase.isBlank()) {
            return out;
        }
        for (String seg : phrase.split("[、，,;；。]")) {
            String s = seg.trim();
            if (s.length() >= 2) {
                out.add(s);
            }
        }
        return out;
    }

    /** 便捷引用（调试接口展示用）。 */
    public String refOf(Long profileId) {
        return NpcRefs.of(profileId);
    }

    /** 文本是否命中禁止表达清单（短语分段包含匹配，与空间/知识盲区同口径）。 */
    private static boolean hitsForbiddenExpressions(List<String> forbidden, String text) {
        if (forbidden == null || forbidden.isEmpty()) {
            return false;
        }
        for (String f : forbidden) {
            if (f == null || f.isBlank()) {
                continue;
            }
            for (String seg : f.split("[、，,;；。]")) {
                String s = seg.trim();
                if (s.length() >= 2 && text.contains(s)) {
                    return true;
                }
            }
        }
        return false;
    }
}
