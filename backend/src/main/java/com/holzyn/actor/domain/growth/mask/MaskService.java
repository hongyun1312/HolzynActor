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

package com.holzyn.actor.domain.growth.mask;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.evolution.GrowthShiftLog;
import com.holzyn.actor.domain.growth.evolution.GrowthShiftLogRepository;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 社会面具服务。
 * <p><b>职责</b>：</p>
 * <ul>
 * <li><b>情境解析</b>：经 {@link MaskContextSource} 判定点 得当前情境码；
 * <b>多情境叠加取最大抑制强度</b>（设计 §5.6）；</li>
 * <li><b>破绽检测</b>：经 {@link MaskBreachDecision} 判定点 ——触发物命中 → 临时降抑制
 * （真实的人会在最不该失态的时候失态）；</li>
 * <li><b>表达 PAD 计算</b>：{@code emotion_*}（真实 PAD，不变）× (1 − 生效抑制) → 写回
 * {@code actor_state.expressed_*} 三列 + {@code mask_snapshot_json}（：表达入口现算 +
 * 写回持久化）；独处/私人场所=真实 PAD（面具放下）；</li>
 * <li><b>双段注入文本</b>：{@link #dualSegment}——【内心与表达】段（真实 PAD 与表达 PAD 分离可见，
 * 验收⑤），由感知漏斗唯一注入点追加到所有消费场景；</li>
 * <li><b>面具强度双向演化</b>：破绽命中 → 该情境抑制 −0.05（松动，向真实
 * 靠拢）；评估周期内高频社交无破绽 → +0.05（固化，封顶预设值）——贴合「长期压抑会变成习惯/
 * 第二层人格」；全部落 {@code actor_growth_shift_log}（dim=mask，source=breach/programmatic）；</li>
 * <li><b>护栏支撑</b>：{@link #forbiddenOf}（当前情境禁止表达清单，mask_compliance 消费）。</li>
 * </ul>
 * <p><b>阶段上限</b>：面具能力随阶段解锁（INFANT 无面具 → ANCIENT 上限 0.6，见
 * {@link MaskContextCode#stageMaxSuppression}）；生效抑制再受 {@code mask.max-suppression}（0.80）
 * 封顶——完全无表情的人不真实。</p>
 * <p><b>架构不变量</b>：本类公开方法<b>不含</b>{@code @Transactional}（表达 PAD 计算在感知热路径，
 * 写库走仓库短事务）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaskService {

    /** 表达 PAD 计算结果（prompt 注入与调试展示用）。 */
    public record ExpressedResult(
            List<String> contexts,
            double suppression,
            boolean breach,
            String breachTrigger,
            double breachDrop,
            Double realPleasure, Double realArousal, Double realDominance,
            Double expressedPleasure, Double expressedArousal, Double expressedDominance,
            String snapshotJson) {
    }

    private final MaskContextSource contextSource;
    private final MaskBreachDecision breachDecision;
    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final ActorGrowthMaskRepository maskRepository;
    private final GrowthShiftLogRepository shiftLogRepository;
    private final GrowthProperties growthProperties;
    private final GrowthMetricsService growthMetrics;
    private final WorldSandboxSseHub sandboxSseHub;

    /**
     * 空间重算触发判定（面具情境切换检测点——情境码变化 → NanoJev boolean 确认
     * 触发空间重算 MASK_CONTEXT_CHANGE；异步提交零阻断；字段注入保持构造器不变）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SpaceTriggerJudge spaceTriggerJudge;

    /** 上次情境码（内存去重：npcRef → 情境码；情境切换才触发判定，防高频对话重复评估） */
    private final java.util.Map<String, String> lastContextCodes = new java.util.concurrent.ConcurrentHashMap<>();

    //
    // 情境解析
    //

    /**
     * 当前面具情境码（有序；失败返回空列表=无面具，零阻断）。
     *
     * @param projectId 项目 ID
     *
     * @param selfRef 本人统一引用
     *
     * @param partnerRefs 在场者/对话对象（null=独处/决策；空列表=有非角色对象在场[玩家]）
     *
     * @param gameSecond 世界时刻
     */
    public List<String> resolveContexts(Long projectId, String selfRef, List<String> partnerRefs, Long gameSecond) {
        if (!growthProperties.getMask().isEnabled()) {
            return List.of();
        }
        try {
            return contextSource.resolve(projectId, selfRef, partnerRefs, gameSecond);
        } catch (Exception e) {
            log.warn("[面具] 情境解析委托失败（返回空，零阻断）: ref={} 原因={}", selfRef, e.getMessage());
            return List.of();
        }
    }

    //
    // 表达 PAD 计算
    //

    /**
     * 表达 PAD 全链路计算（表达入口调用：决策/单聊/群聊/自主对话组装 prompt 前）：
     * 情境解析 → 破绽检测 → 生效抑制（情境叠加取最大 × 阶段上限 × 全局封顶 − 破绽降幅）→
     * 表达 PAD = 真实 PAD × (1 − 生效抑制) → 写回 {@code actor_state}（表达三列 + 面具快照）→
     * 破绽演化（−0.05，冷却内跳过）。
     *
     * @param projectId 项目 ID
     *
     * @param selfRef 本人统一引用
     *
     * @param gameSecond 世界时刻
     *
     * @param partnerRefs 在场者/对话对象（见 {@link #resolveContexts}）
     *
     * @param contextText 破绽检测文本（对话输入/行动文本；可空）
     *
     * @return 计算结果（失败返回零面具结果：表达=真实，零阻断）
     */
    public ExpressedResult computeExpressed(Long projectId, String selfRef, Long gameSecond,
                                            List<String> partnerRefs, String contextText) {
        if (!growthProperties.getMask().isEnabled()) {
            return noMask(projectId, selfRef, List.of());
        }
        try {
            ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(selfRef)).orElse(null);
            ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, selfRef).orElse(null);
            if (ch == null || state == null) {
                return noMask(projectId, selfRef, List.of());
            }
            List<String> contexts = resolveContexts(projectId, selfRef, partnerRefs, gameSecond);
            // 事件驱动重算触发：面具情境切换检测点——主情境码较上次变化 →
            // 异步提交判定（NanoJev boolean 确认 + 程序化兜底 → 空间重算 MASK_CONTEXT_CHANGE；
            // 内存去重防高频对话重复评估；零阻断）
            if (spaceTriggerJudge != null && !contexts.isEmpty()) {
                try {
                    String code = contexts.get(0);
                    String prev = lastContextCodes.put(selfRef, code);
                    if (prev != null && !prev.equals(code)) {
                        spaceTriggerJudge.onMaskContextChange(projectId, selfRef, prev, code, gameSecond);
                    }
                } catch (Exception e) {
                    log.debug("[面具] 情境切换触发判定提交失败（跳过）: ref={} : {}", selfRef, e.getMessage());
                }
            }
            // 破绽检测（触发物命中 → 临时降抑制）
            var breach = breachDecision.detect(projectId, selfRef, contextText, gameSecond);
            double suppression = effectiveSuppression(projectId, ch, contexts, breach.triggered() ? breach.suppressionDelta() : 0);
            // 表达 PAD = 真实 × (1 − 抑制)；负值同样向 0 收敛（压抑=对外显得平静）
            double realP = state.getEmotionPleasure() == null ? 0.10 : state.getEmotionPleasure();
            double realA = state.getEmotionArousal() == null ? -0.10 : state.getEmotionArousal();
            double realD = state.getEmotionDominance() == null ? 0.00 : state.getEmotionDominance();
            double k = 1 - suppression;
            double expP = realP * k;
            double expA = realA * k;
            double expD = realD * k;
            // 写回（短事务）
            state.setExpressedPleasure(round3(expP));
            state.setExpressedArousal(round3(expA));
            state.setExpressedDominance(round3(expD));
            String snapshot = snapshotJson(ch, contexts, suppression, breach, gameSecond);
            state.setMaskSnapshotJson(snapshot);
            stateRepository.save(state);
            growthMetrics.recordMaskExpressedCompute();
            if (breach.triggered()) {
                growthMetrics.recordMaskBreachHit();
                evolveOnBreach(projectId, ch, contexts, suppression, breach, gameSecond);
            }
            return new ExpressedResult(contexts, suppression, breach.triggered(), breach.trigger(),
                    breach.suppressionDelta(), round3(realP), round3(realA), round3(realD),
                    round3(expP), round3(expA), round3(expD), snapshot);
        } catch (Exception e) {
            log.warn("[面具] 表达 PAD 计算失败（按无面具=表达真实，零阻断）: ref={} 原因={}", selfRef, e.getMessage());
            return noMask(projectId, selfRef, List.of());
        }
    }

    /**
     * 生效抑制强度（纯函数，可单测）：
     * 情境叠加取最大（表优先 + 档案兜底）→ × 阶段上限钳制 → × 全局封顶 → − 破绽降幅（下限 0）。
     */
    public double effectiveSuppression(Long projectId, ActorCharacter ch, List<String> contexts, double breachDrop) {
        double stageCap = MaskContextCode.stageMaxSuppression(ch == null ? null : ch.getStage());
        double globalCap = growthProperties.getMask().getMaxSuppression();
        double maxSup = 0;
        if (contexts != null) {
            for (String code : contexts) {
                if (!MaskContextCode.isValid(code)) {
                    continue;
                }
                double s = suppressionOf(projectId, ch, code);
                maxSup = Math.max(maxSup, s);
            }
        }
        double capped = Math.min(Math.min(maxSup, stageCap), globalCap);
        return Math.max(0, round3(capped - breachDrop));
    }

    /**
     * 单情境抑制强度（表优先 + 档案 ⑪ 维兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色行
     *
     * @param code 情境码
     *
     * @return 抑制强度 0-1（未知情境/无值 → 0）
     */
    public double suppressionOf(Long projectId, ActorCharacter ch, String code) {
        if (ch == null || code == null) {
            return 0;
        }
        // ① 运行态表（演化后权威）
        try {
            var row = maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(projectId, ch.npcRef(), code);
            if (row.isPresent() && row.get().getEffectiveSuppression() != null) {
                return row.get().getEffectiveSuppression();
            }
        } catch (Exception e) {
            log.debug("[面具] 面具表读取失败（回退档案）: {}", e.getMessage());
        }
        // ② 档案 ⑪ 维（预设基线）
        PersonaProfile p = PersonaJson.readQuietly(ch.getPersonaJson());
        if (p != null && p.getDims() != null && p.getDims().getMask() != null) {
            PersonaDims.Mask m = p.getDims().getMask();
            Double v = m.getContextSuppression() == null ? null : m.getContextSuppression().get(code);
            return v == null ? 0 : Math.max(0, Math.min(1, v));
        }
        return 0;
    }

    //
    // 面具强度双向演化
    //

    /**
     * 破绽演化（对话侧 mask_compliance 软修正共用）：mask_compliance 判定对话输出违背禁止表达时
     * 调用——视作一次「失态」破绽事件（真实的人违背表达规则本身就是面具被撑破），对当前情境中
     * 抑制最高的情境 −步长；冷却内跳过。落 shift_log（trigger=breach，source=breach）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 本人统一引用
     *
     * @param gameSecond 世界时刻
     *
     * @param reason 失态说明（如「对权威场合当面顶撞」）
     */
    public void evolveOnViolation(Long projectId, String npcRef, Long gameSecond, String reason) {
        try {
            if (!growthProperties.getMask().isEnabled() || !growthProperties.getMask().isBreachEnabled()
                    || npcRef == null || !npcRef.startsWith("npc:")) {
                return;
            }
            ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
            if (ch == null) {
                return;
            }
            // 当前情境（无对象 → 按场合）；抑制最高者即破绽作用对象
            List<String> contexts = resolveContexts(projectId, npcRef, null, gameSecond);
            applyBreachEvolution(projectId, ch, contexts, gameSecond, reason == null ? "失态（违背禁止表达）" : reason);
        } catch (Exception e) {
            log.warn("[面具] 违规演化失败（跳过，零阻断）: ref={} 原因={}", npcRef, e.getMessage());
        }
    }

    /**
     * 破绽演化：本次破绽作用于「生效抑制最高的情境」（破绽把面具撑破的地方），该情境抑制 −步长，
     * 冷却内跳过演化（临时降抑制仍生效）；落 shift_log（dim=mask，trigger=breach，source=breach）。
     */
    private void evolveOnBreach(Long projectId, ActorCharacter ch, List<String> contexts, double suppression,
                                MaskBreachDecision.BreachResult breach, Long gameSecond) {
        try {
            if (!growthProperties.getMask().isBreachEnabled()) {
                return;
            }
            applyBreachEvolution(projectId, ch, contexts, gameSecond,
                    "破绽「" + breach.trigger() + "」命中，面具松动");
        } catch (Exception e) {
            log.warn("[面具] 破绽演化失败（跳过，零阻断）: ref={} 原因={}", ch.npcRef(), e.getMessage());
        }
    }

    /** 破绽演化的公共执行体（建行 → 冷却判定 → −步长 → 落账 → 下行）。 */
    private void applyBreachEvolution(Long projectId, ActorCharacter ch, List<String> contexts,
                                      Long gameSecond, String reason) {
        double step = growthProperties.getMask().getBreachEvolutionStep();
        long cooldown = growthProperties.getMask().getBreachCooldownGameMinutes() * 60L;
        String targetContext = contexts == null ? null : highestSuppressionContext(projectId, ch, contexts);
        if (targetContext == null) {
            targetContext = MaskContextCode.PRIVATE_PLACE;
        }
        var rowOpt = maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(projectId, ch.npcRef(), targetContext);
        if (rowOpt.isEmpty()) {
            // 无运行态行：从档案预设建行后演化（表优先范式：建行即接管）
            createMaskRow(projectId, ch, targetContext, gameSecond);
        }
        var row = maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(projectId, ch.npcRef(), targetContext);
        if (row.isEmpty()) {
            return;
        }
        ActorGrowthMask m = row.get();
        // 冷却判定
        if (m.getLastBreachGameSecond() != null && gameSecond != null
                && gameSecond - m.getLastBreachGameSecond() < cooldown) {
            return;
        }
        double old = m.getEffectiveSuppression() == null ? 0 : m.getEffectiveSuppression();
        double next = Math.max(0.05, Math.round((old - step) * 1000.0) / 1000.0);
        m.setEffectiveSuppression(next);
        m.setLastBreachGameSecond(gameSecond);
        m.setLastShiftGameSecond(gameSecond);
        maskRepository.save(m);
        writeShiftLog(projectId, ch, gameSecond, GrowthShiftLog.TRIGGER_BREACH, GrowthShiftLog.DIM_MASK,
                targetContext, String.valueOf(old), String.valueOf(next), round3(next - old),
                reason, GrowthShiftLog.SOURCE_BREACH, null);
        publishMaskChange(projectId, ch.npcRef(), targetContext, next, "breach");
    }

    /**
     * 面具固化（评估周期兜底，由人格演化引擎周期挂载调用）：该角色<b>评估周期内无破绽</b>且
     * 生效抑制低于预设 → +步长（封顶预设值）——长期维持面具的人面具会越来越稳固；
     * 落 shift_log（trigger=cycle，source=programmatic）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色行
     *
     * @param gameSecond 世界时刻
     */
    public void solidifyMasks(Long projectId, ActorCharacter ch, Long gameSecond) {
        if (!growthProperties.getMask().isEnabled() || !growthProperties.getMask().isSolidifyEnabled()) {
            return;
        }
        double step = growthProperties.getMask().getBreachEvolutionStep();
        for (ActorGrowthMask m : maskRepository.findByProjectIdAndNpcRefOrderByContextCodeAsc(projectId, ch.npcRef())) {
            try {
                double preset = m.getPresetSuppression() == null ? 0 : m.getPresetSuppression();
                double eff = m.getEffectiveSuppression() == null ? 0 : m.getEffectiveSuppression();
                if (preset <= 0) {
                    continue;
                }
                // 无破绽判定：最近破绽时刻早于最近演化时刻（或从未破绽）
                boolean noBreachSince = m.getLastBreachGameSecond() == null
                        || (m.getLastShiftGameSecond() != null
                        && m.getLastBreachGameSecond() < m.getLastShiftGameSecond());
                if (!noBreachSince || eff >= preset) {
                    continue;
                }
                double next = Math.min(preset, Math.round((eff + step) * 1000.0) / 1000.0);
                m.setEffectiveSuppression(next);
                m.setLastShiftGameSecond(gameSecond);
                maskRepository.save(m);
                writeShiftLog(projectId, ch, gameSecond, GrowthShiftLog.TRIGGER_CYCLE, GrowthShiftLog.DIM_MASK,
                        m.getContextCode(), String.valueOf(eff), String.valueOf(next), round3(next - eff),
                        "长期无破绽，面具固化", GrowthShiftLog.SOURCE_PROGRAMMATIC, null);
                publishMaskChange(projectId, ch.npcRef(), m.getContextCode(), next, "solidify");
            } catch (Exception e) {
                log.warn("[面具] 面具固化失败（跳过，零阻断）: ref={} 原因={}", ch.npcRef(), e.getMessage());
            }
        }
    }

    //
    // 双段注入
    //

    /**
     * 【内心与表达】双段注入文本（表达入口调用；内部现算表达 PAD 并写回）。
     * <p>格式：真实情绪（内心）/ 表达情绪（对外）/ 当前情境与抑制 / 允许与禁止表达；
     * 无面具（面具关/独处且无抑制）时返回空串=不注入（零噪音）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param selfRef 本人统一引用
     *
     * @param gameSecond 世界时刻
     *
     * @param partnerRefs 在场者/对话对象
     *
     * @param contextText 破绽检测文本
     *
     * @return 注入段文本（空串=不注入）
     */
    public String dualSegment(Long projectId, String selfRef, Long gameSecond,
                              List<String> partnerRefs, String contextText) {
        if (!growthProperties.getMask().isEnabled()) {
            return "";
        }
        ExpressedResult r = computeExpressed(projectId, selfRef, gameSecond, partnerRefs, contextText);
        // 无面具（面具关/独处或私人场所无抑制）→ 不注入（零噪音）：真实 PAD 即表达 PAD，无需双段
        if (r == null || r.contexts() == null || r.contexts().isEmpty() || r.suppression() <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【内心与表达】\n");
        sb.append("内心真实情绪：愉悦 ").append(fmt(r.realPleasure()))
                .append(" / 唤醒 ").append(fmt(r.realArousal()))
                .append(" / 支配 ").append(fmt(r.realDominance()))
                .append("（内心真实感受，除非破绽，否则不直接外露）\n");
        sb.append("表达情绪：愉悦 ").append(fmt(r.expressedPleasure()))
                .append(" / 唤醒 ").append(fmt(r.expressedArousal()))
                .append(" / 支配 ").append(fmt(r.expressedDominance()))
                .append("（当前情境下对外呈现，表达应与之相符）\n");
        List<String> names = new ArrayList<>();
        for (String c : r.contexts()) {
            if (MaskContextCode.PUBLIC_PLACE.equals(c) || MaskContextCode.PRIVATE_PLACE.equals(c)) {
                continue; // 场合码并入情境名展示
            }
            names.add(MaskContextCode.labelOf(c));
        }
        if (names.isEmpty()) {
            names.add("独处/无对象");
        }
        sb.append("当前面具情境：").append(String.join("、", names))
                .append("（").append(MaskContextCode.labelOf(r.contexts().get(0))).append("）")
                .append("｜抑制强度 ").append(pct(r.suppression()));
        if (r.breach()) {
            sb.append("｜破绽命中：").append(r.breachTrigger()).append("（抑制 −").append(pct(r.breachDrop())).append("）");
        }
        sb.append("\n");
        // 允许/禁止表达（当前情境取第一个有效情境码的清单；避免超长——单段 ≤2 行）
        String allow = joinedExpressions(projectId, selfRef, r.contexts(), true);
        String forbid = joinedExpressions(projectId, selfRef, r.contexts(), false);
        if (!allow.isBlank()) {
            sb.append("允许表达：").append(allow).append("\n");
        }
        if (!forbid.isBlank()) {
            sb.append("禁止表达：").append(forbid).append("\n");
        }
        return sb.toString().trim();
    }

    //
    // 护栏支撑（mask_compliance 消费）
    //

    /**
     * 当前情境禁止表达清单（mask_compliance 校验输入；独处/私人场所无清单=不设防）。
     *
     * @param projectId 项目 ID
     *
     * @param selfRef 本人统一引用
     *
     * @param contexts 当前情境码（resolveContexts 结果）
     *
     * @return 禁止表达短语列表（空=该情境无禁止）
     */
    public List<String> forbiddenOf(Long projectId, String selfRef, List<String> contexts) {
        return expressionsOf(projectId, selfRef, contexts, false);
    }

    /**
     * 当前情境允许表达清单（prompt 注入辅助；调试展示）。
     */
    public List<String> allowedOf(Long projectId, String selfRef, List<String> contexts) {
        return expressionsOf(projectId, selfRef, contexts, true);
    }

    /** 是否「不设防」情境（独处/私人场所/无情境）：mask_compliance 跳过校验。 */
    public boolean isUndefended(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return true;
        }
        for (String c : contexts) {
            if (!MaskContextCode.PRIVATE_PLACE.equals(c)) {
                return false;
            }
        }
        return true; // 只有私人场所
    }

    //
    // 调试视图
    //

    /** 面具状态（调试页「面具状态」卡数据源）。 */
    public Map<String, Object> statusOf(Long projectId, String selfRef) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", growthProperties.getMask().isEnabled());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActorGrowthMask m : maskRepository.findByProjectIdAndNpcRefOrderByContextCodeAsc(projectId, selfRef)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("contextCode", m.getContextCode());
            x.put("contextLabel", MaskContextCode.labelOf(m.getContextCode()));
            x.put("presetSuppression", m.getPresetSuppression());
            x.put("effectiveSuppression", m.getEffectiveSuppression());
            x.put("allowed", m.getAllowedJson());
            x.put("forbidden", m.getForbiddenJson());
            x.put("lastBreachGameSecond", m.getLastBreachGameSecond());
            x.put("lastShiftGameSecond", m.getLastShiftGameSecond());
            rows.add(x);
        }
        out.put("rows", rows);
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, selfRef).orElse(null);
        if (state != null) {
            // 空值容忍（Map.of 不允许 null）：未运行/未评估情绪的角色情绪列为 null 时不炸
            out.put("realPad", padMap(state.getEmotionPleasure(), state.getEmotionArousal(),
                    state.getEmotionDominance()));
            out.put("expressedPad", padMap(state.getExpressedPleasure(), state.getExpressedArousal(),
                    state.getExpressedDominance()));
            out.put("snapshot", state.getMaskSnapshotJson());
        }
        return out;
    }

    /** 空值容忍的 PAD 视图（null → null，前端显示 —）。 */
    private static Map<String, Object> padMap(Double p, Double a, Double d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pleasure", p);
        m.put("arousal", a);
        m.put("dominance", d);
        return m;
    }

    //
    // 内部工具
    //

    /** 无面具结果（面具关/数据缺失）：表达 = 真实。 */
    private ExpressedResult noMask(Long projectId, String selfRef, List<String> contexts) {
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, selfRef).orElse(null);
        if (state == null) {
            return new ExpressedResult(contexts, 0, false, null, 0, null, null, null, null, null, null, null);
        }
        double p = state.getEmotionPleasure() == null ? 0.10 : state.getEmotionPleasure();
        double a = state.getEmotionArousal() == null ? -0.10 : state.getEmotionArousal();
        double d = state.getEmotionDominance() == null ? 0 : state.getEmotionDominance();
        return new ExpressedResult(contexts, 0, false, null, 0, round3(p), round3(a), round3(d),
                round3(p), round3(a), round3(d), null);
    }

    /** 情境中抑制最高的情境码（破绽演化的作用对象）。 */
    private String highestSuppressionContext(Long projectId, ActorCharacter ch, List<String> contexts) {
        String best = null;
        double max = -1;
        for (String c : contexts) {
            double s = suppressionOf(projectId, ch, c);
            if (s > max) {
                max = s;
                best = c;
            }
        }
        return best;
    }

    /** 从档案预设建面具运行态行（表优先：建行即接管该情境）。 */
    private void createMaskRow(Long projectId, ActorCharacter ch, String contextCode, Long gameSecond) {
        PersonaProfile p = PersonaJson.readQuietly(ch.getPersonaJson());
        PersonaDims.Mask m = p == null || p.getDims() == null ? null : p.getDims().getMask();
        double preset = 0;
        List<String> allowed = List.of();
        List<String> forbidden = List.of();
        if (m != null) {
            Double v = m.getContextSuppression() == null ? null : m.getContextSuppression().get(contextCode);
            preset = v == null ? 0 : v;
            allowed = m.getAllowedExpressions() == null ? List.of()
                    : m.getAllowedExpressions().getOrDefault(contextCode, List.of());
            forbidden = m.getForbiddenExpressions() == null ? List.of()
                    : m.getForbiddenExpressions().getOrDefault(contextCode, List.of());
        }
        ActorGrowthMask row = new ActorGrowthMask();
        row.setProjectId(projectId);
        row.setNpcRef(ch.npcRef());
        row.setContextCode(contextCode);
        row.setPresetSuppression(preset);
        row.setEffectiveSuppression(preset);
        row.setAllowedJson(joinJson(allowed));
        row.setForbiddenJson(joinJson(forbidden));
        row.setLastShiftGameSecond(gameSecond);
        maskRepository.save(row);
    }

    /** 情境表达清单（表优先 + 档案兜底；allowed=true 取允许，false 取禁止）。 */
    private List<String> expressionsOf(Long projectId, String selfRef, List<String> contexts, boolean allowed) {
        List<String> out = new ArrayList<>();
        if (contexts == null || selfRef == null) {
            return out;
        }
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(selfRef)).orElse(null);
        if (ch == null) {
            return out;
        }
        for (String code : contexts) {
            if (!MaskContextCode.isValid(code)) {
                continue;
            }
            List<String> list = null;
            try {
                var row = maskRepository.findFirstByProjectIdAndNpcRefAndContextCode(projectId, selfRef, code);
                if (row.isPresent()) {
                    String json = allowed ? row.get().getAllowedJson() : row.get().getForbiddenJson();
                    list = parseJsonList(json);
                }
            } catch (Exception e) {
                log.debug("[面具] 面具表清单读取失败（回退档案）: {}", e.getMessage());
            }
            if (list == null) {
                PersonaProfile p = PersonaJson.readQuietly(ch.getPersonaJson());
                if (p != null && p.getDims() != null && p.getDims().getMask() != null) {
                    PersonaDims.Mask m = p.getDims().getMask();
                    Map<String, List<String>> map = allowed ? m.getAllowedExpressions() : m.getForbiddenExpressions();
                    list = map == null ? List.of() : map.getOrDefault(code, List.of());
                } else {
                    list = List.of();
                }
            }
            for (String s : list) {
                if (s != null && !s.isBlank() && !out.contains(s)) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    /** 清单转注入文本（逗号连接；空=空串）。 */
    private String joinedExpressions(Long projectId, String selfRef, List<String> contexts, boolean allowed) {
        return String.join("、", expressionsOf(projectId, selfRef, contexts, allowed));
    }

    /** 面具快照 JSON（调试/前端展示）。 */
    private String snapshotJson(ActorCharacter ch, List<String> contexts, double suppression,
                                MaskBreachDecision.BreachResult breach, Long gameSecond) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contexts", contexts);
        m.put("suppression", suppression);
        m.put("stageCap", MaskContextCode.stageMaxSuppression(ch.getStage()));
        m.put("breach", breach.triggered());
        m.put("breachTrigger", breach.trigger());
        m.put("breachDrop", breach.suppressionDelta());
        m.put("gameSecond", gameSecond);
        try {
            return PersonaJson.mapper().writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写人格偏移日志（mask 维演化留痕，验收② 证据链可反查）。 */
    private void writeShiftLog(Long projectId, ActorCharacter ch, Long gameSecond, String trigger, String dim,
                               String field, String oldValue, String newValue, double delta,
                               String reason, String source, String evidenceJson) {
        try {
            GrowthShiftLog log = new GrowthShiftLog();
            log.setProjectId(projectId);
            log.setNpcRef(ch.npcRef());
            log.setGameSecond(gameSecond == null ? 0 : gameSecond);
            log.setTriggerCode(trigger);
            log.setDim(dim);
            log.setField(field);
            log.setOldValue(oldValue);
            log.setNewValue(newValue);
            log.setDelta(delta);
            log.setStageCap(MaskContextCode.stageMaxSuppression(ch.getStage()) + "");
            log.setAiReason(reason);
            log.setSource(source);
            log.setEvidenceJson(evidenceJson);
            shiftLogRepository.save(log);
            growthMetrics.recordShiftEntry();
        } catch (Exception e) {
            log.warn("[面具] 偏移日志写入失败（跳过）: {}", e.getMessage());
        }
    }

    /** 面具变化下行。 */
    private void publishMaskChange(Long projectId, String npcRef, String contextCode, double suppression, String cause) {
        try {
            if (sandboxSseHub != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("npcRef", npcRef);
                data.put("contextCode", contextCode);
                data.put("contextLabel", MaskContextCode.labelOf(contextCode));
                data.put("suppression", suppression);
                data.put("cause", cause);
                sandboxSseHub.publish(projectId, WorldSandboxSseHub.EVENT_NPC_MASK_CHANGE, data);
            }
        } catch (Exception e) {
            log.debug("[面具] 面具变化下行失败（跳过）: {}", e.getMessage());
        }
    }

    /** 简单 JSON 数组序列化（清单落表；Jackson 3 异常为运行时异常，失败回退 "[]"）。 */
    private static String joinJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return PersonaJson.mapper().writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 简单 JSON 数组解析（失败返回 null=回退档案）。 */
    @SuppressWarnings("unchecked")
    private static List<String> parseJsonList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            List<Object> arr = PersonaJson.mapper().readValue(json, List.class);
            List<String> out = new ArrayList<>();
            for (Object o : arr) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String fmt(Double v) {
        if (v == null) {
            return "?";
        }
        return (v > 0 ? "+" : "") + String.format("%.2f", v);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
