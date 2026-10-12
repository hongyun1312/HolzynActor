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

package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import com.holzyn.actor.ai.nanojev.NanoJevState;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionGate;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionScenes;
import com.holzyn.actor.ai.nanojev.decision.NanoJevDecisionTemplates;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 空间重算触发判定 + 关系变化量评估。
 * <p><b>定位</b>：把
 * 已声明但<b>零调用方</b>的三个空间重算触发码（{@link GrowthSpaceTrigger#EMOTION_SHIFT} /
 * 接线到真实检测点——情绪评估写回 / 关系状态机跨档 / 面具情境切换。检测到<b>程序化变化信号</b>
 * 后，由 NanoJev（space_trigger 场景，boolean「这次变化是否值得重新审视空间」）语义确认，
 * 失败/未启用 → 程序化阈值兜底（变化显著即触发）；触发
 * {@link GrowthSpaceService#triggerRegenerate}（带既有同码去重窗口，失败零阻断）。</p>
 * <p><b>关系变化量（演化判定四项之一）</b>：{@link #evaluateRelationDeltaAsync}——世界事件
 * 参与角色两两评估影响幅度（score 4 档）与方向（choice 增进/恶化/无变化），按幅度×方向
 * 对好感做平滑更新（替代固定增量 event-delta），NanoJev 失败/未启用 → 回退固定增量。</p>
 * <p><b>执行模型</b>：所有判定异步虚拟线程执行（检测点可能在事务内——更新关系是
 * {@code @Transactional}，情绪写回也在异步线程；含 NanoJev 调用，<b>不带
 * {@code @Transactional}</b>，AiCallOutsideTransactionTest 反射守护）。任何失败零阻断。</p>
 */
@Slf4j
@Component
public class SpaceTriggerJudge {

    /** 异步执行器（虚拟线程，每任务一线程，用完即弃） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** qid：情绪漂移触发判定 */
    public static final String QID_EMOTION_SHIFT = "emotion_shift";
    /** qid：关系跨档触发判定 */
    public static final String QID_RELATION_JUMP = "relation_jump";
    /** qid：面具情境切换触发判定 */
    public static final String QID_MASK_CONTEXT_CHANGE = "mask_context_change";
    /** qid：关系影响幅度（score） */
    public static final String QID_DELTA_MAGNITUDE = "delta_magnitude";
    /** qid：关系影响方向（choice） */
    public static final String QID_DELTA_DIRECTION = "delta_direction";

    /** 关系幅度档位 → 好感增量比例（平滑更新：无=0 / 小=1 / 中=3 / 大=6；替代固定增量 1） */
    private static final int[] DELTA_SCALE = {0, 1, 3, 6};

    // ==================== 内存观测 ====================

    /** 触发判定轨迹上限（纯内存零副作用） */
    private static final int TRIGGER_CAP = 200;

    /** 触发判定轨迹（trigger → npcRef → 是否触发 → 兜底与否 → gameSecond，最近在前） */
    private final java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> triggerStats =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** 最近触发判定轨迹（调试「重算触发卡」数据源；max≤500）。 */
    public java.util.List<Map<String, Object>> recentTriggers(int max) {
        int n = Math.max(1, Math.min(500, max));
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.Iterator<Map<String, Object>> it = triggerStats.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        return out;
    }

    /** NanoJev 判定门 */
    @Autowired(required = false)
    private NanoJevDecisionGate nanoJevDecisionGate;

    /**
     * 此刻可发生空间（触发重算消费方；null=未装配跳过）。
     * <p><b>@Lazy 破循环</b>： 检测点全挂后形成
     * worldEventBusImpl → cognitiveAttentionService → npcStateService} 全环——空间服务较重建构
     * 开销大且触发重算只在判定采纳后发生，本边注入惰性代理即可打破（沿 relationDynamics @Lazy 先例）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private GrowthSpaceService growthSpaceService;

    /** 世界时钟（当前游戏秒；null=未知） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 生命周期配置（程序化阈值：emotionShiftThreshold 等） */
    @Autowired(required = false)
    private GrowthProperties growthProperties;

    // ==================== 检测点挂钩（三处） ====================

    /**
     * 情绪漂移检测点（挂在 {@code NpcStateService.doEvaluateEmotion} 情绪 AI 评估写回后，
     * 异步线程内）：情绪标签/强度较上次显著变化 → NanoJev boolean 确认 → 空间重算
     * EMOTION_SHIFT；NanoJev 失败/未启用 → 程序化阈值（标签变化即触发）兜底。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用 npc:{id}
     *
     * @param emotionBefore 本次评估前的情绪标签（可空）
     *
     * @param st 评估后的状态行（含新情绪）
     *
     * @param gameSecond 当前游戏秒（可空）
     */
    public void onEmotionEval(Long projectId, String npcRef, String emotionBefore, ActorNpcState st,
                              Long gameSecond) {
        if (projectId == null || npcRef == null || st == null) {
            return;
        }
        // 程序化变化信号：情绪标签变化（或强度/心情显著漂移）
        String emotionAfter = st.getDominantEmotion() == null ? "calm" : st.getDominantEmotion();
        boolean signal = emotionBefore != null && !emotionBefore.equals(emotionAfter);
        if (!signal) {
            double threshold = spaceConfig() == null ? 0.35 : spaceConfig().getEmotionShiftThreshold();
            Double pBefore = null; // 检测点不传 PAD 前值（由调用方在快照时比较）；此处仅标签信号
            if (pBefore != null && st.getEmotionPleasure() != null
                    && Math.abs(st.getEmotionPleasure() - pBefore) > threshold) {
                signal = true;
            }
        }
        if (!signal) {
            return;
        }
        String changeText = npcRef + " 情绪变化：" + (emotionBefore == null ? "?" : emotionBefore)
                + " → " + emotionAfter;
        submitTriggerCheck(projectId, npcRef, QID_EMOTION_SHIFT, changeText, gameSecond,
                GrowthSpaceTrigger.EMOTION_SHIFT);
    }

    /**
     * 关系跨档检测点（挂在 {@code RelationDynamicsService.updateRelation} 状态机重算后——
     * 调用方在事务内，本方法仅异步提交）：关系状态码变化 → NanoJev boolean 确认 →
     * 空间重算 RELATION_JUMP。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 关系涉及角色引用（任一方；空间按角色重算）
     *
     * @param oldState 旧关系状态码
     *
     * @param newState 新关系状态码
     *
     * @param changeText 变化描述（可空=自动拼）
     *
     * @param gameSecond 当前游戏秒（可空）
     */
    public void onRelationJump(Long projectId, String npcRef, String oldState, String newState,
                               String changeText, Long gameSecond) {
        if (projectId == null || npcRef == null) {
            return;
        }
        if (oldState == null || oldState.equals(newState)) {
            return;
        }
        String text = changeText == null || changeText.isBlank()
                ? npcRef + " 的一段关系从「" + oldState + "」变为「" + newState + "」"
                : changeText;
        submitTriggerCheck(projectId, npcRef, QID_RELATION_JUMP, text, gameSecond,
                GrowthSpaceTrigger.RELATION_JUMP);
    }

    /**
     * 面具情境切换检测点（挂在 {@code MaskService.resolveContexts} 结果变化后——调用方高频，
     * 本方法按「上次情境码」内存去重，变化才异步提交）：情境码变化 → NanoJev boolean 确认 →
     * 空间重算 MASK_CONTEXT_CHANGE。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用 npc:{id}
     *
     * @param oldCode 旧情境码（可空）
     *
     * @param newCode 新情境码（可空）
     *
     * @param gameSecond 当前游戏秒（可空）
     */
    public void onMaskContextChange(Long projectId, String npcRef, String oldCode, String newCode,
                                    Long gameSecond) {
        if (projectId == null || npcRef == null || newCode == null || newCode.equals(oldCode)) {
            return;
        }
        String text = npcRef + " 所处情境切换：从「" + (oldCode == null ? "?" : oldCode)
                + "」变为「" + newCode + "」";
        submitTriggerCheck(projectId, npcRef, QID_MASK_CONTEXT_CHANGE, text, gameSecond,
                GrowthSpaceTrigger.MASK_CONTEXT_CHANGE);
    }

    /**
     * 关系变化量评估（演化判定四项之一，挂在 {@code LearningRelationEventSubscriber} 世界事件
     * 参与角色两两挂钩后，异步执行）：NanoJev（relation_delta 场景：幅度 score + 方向 choice
     * 同 state 一次前向）→ 幅度×方向平滑更新好感；失败/未启用 → 回退固定增量（event-delta）。
     * 注意：不重复发世界事件（好感更新本身已由 RelationDynamicsService 内部处理）。
     *
     * @param projectId 项目 ID
     *
     * @param refA 参与角色 A
     *
     * @param refB 参与角色 B
     *
     * @param eventTitle 事件标题（原因文本）
     *
     * @param eventContent 事件正文（判定情境）
     *
     * @param fallbackDelta 回退固定增量（原 event-delta）
     *
     * @param gameSecond 当前游戏秒（可空）
     */
    public void evaluateRelationDeltaAsync(Long projectId, String refA, String refB,
                                           String eventTitle, String eventContent,
                                           int fallbackDelta, Long gameSecond) {
        if (projectId == null || refA == null || refB == null) {
            return;
        }
        // 调用风暴抓修（2026-10-07 实测）：一次群聊收尾事件可产生 120+ 对两两评估，全部
        // 异步提交会让 NanoJev 排队到 60s 超时雪崩（同事件 360 次调用 260 次超时失败）。
        // 关系评估是「幅度×方向」的平滑细化，并发超过上限时直接回退固定增量
        // （fallbackDelta）——失败语义与 NanoJev 调用失败一致（零阻断、不丢关系更新），
        // 只是不再排队等待；限流只约束「在途 NanoJev 评估数」，不丢任务也不阻塞调用方。
        if (!relationDeltaGate.tryAcquire()) {
            log.debug("[空间触发] 关系变化量评估并发已满（限流回退固定增量）: project={} {}↔{} delta={}",
                    projectId, refA, refB, fallbackDelta);
            // 回退路径同样异步执行（applyDialogPairDelta 内部 @Transactional；检测点可能在事件总线
            // 事务内，同步执行会嵌套持锁——沿 evaluateRelationDeltaAsync 的异步执行模型）
            executor.submit(() -> {
                try {
                    applyFallbackDelta(projectId, refA, refB, eventTitle, fallbackDelta, gameSecond);
                } catch (Exception e) {
                    log.warn("[空间触发] 关系固定增量回退失败（零阻断）: project={} {}↔{} : {}",
                            projectId, refA, refB, e.getMessage());
                }
            });
            return;
        }
        executor.submit(() -> {
            try {
                evaluateRelationDeltaSync(projectId, refA, refB, eventTitle, eventContent,
                        fallbackDelta, gameSecond);
            } catch (Exception e) {
                log.warn("[空间触发] 关系变化量评估失败（回退固定增量）: project={} {}↔{} : {}",
                        projectId, refA, refB, e.getMessage());
            } finally {
                relationDeltaGate.release();
            }
        });
    }

    /**
     * 关系变化量评估并发闸（在途 NanoJev 评估数上限；默认 4）。
     * <p>超过上限的评估请求不回退排队、不阻塞提交方——直接走固定增量（{@link #applyFallbackDelta}），
     * 与 NanoJev 调用失败的回退语义一致，但避免「无限并发 → 排队 → 60s 超时 → 雪崩」。</p>
     */
    private static final int RELATION_DELTA_MAX_IN_FLIGHT = 4;

    /** 关系变化量评估并发信号量（公平；可配置见 {@code holzyn.actor.growth.space.relation-delta-concurrency}）。 */
    private final java.util.concurrent.Semaphore relationDeltaGate =
            new java.util.concurrent.Semaphore(relationDeltaMaxInFlight());

    /** 并发上限读取（默认 4；可经配置覆盖，<=0 视为 1）。 */
    private static int relationDeltaMaxInFlight() {
        try {
            String v = System.getProperty("holzyn.actor.growth.space.relation-delta-concurrency");
            if (v != null && !v.isBlank()) {
                int n = Integer.parseInt(v.trim());
                return Math.max(1, n);
            }
        } catch (Exception ignored) {
            // 配置非法 → 默认值
        }
        return RELATION_DELTA_MAX_IN_FLIGHT;
    }

    /** 回退固定增量（关系更新本体；与 NanoJev 失败路径同语义，不丢更新）。 */
    private void applyFallbackDelta(Long projectId, String refA, String refB,
                                    String eventTitle, int fallbackDelta, Long gameSecond) {
        if (relationDynamics == null) {
            return;
        }
        try {
            relationDynamics.applyDialogPairDelta(projectId, refA, refB, fallbackDelta,
                    "共同经历事件：" + (eventTitle == null || eventTitle.isBlank() ? "事件" : eventTitle.trim()));
        } catch (Exception e) {
            log.warn("[空间触发] 关系固定增量回退失败（零阻断）: project={} {}↔{} : {}",
                    projectId, refA, refB, e.getMessage());
        }
    }

    // ==================== 内部实现 ====================

    /** 提交触发判定（异步虚拟线程：NanoJev boolean + 程序化阈值兜底 → 空间重算）。 */
    private void submitTriggerCheck(Long projectId, String npcRef, String qid, String changeText,
                                    Long gameSecond, GrowthSpaceTrigger trigger) {
        executor.submit(() -> {
            try {
                boolean worth = decideTrigger(projectId, npcRef, qid, changeText, gameSecond);
                // 内存观测：触发判定轨迹（调试「重算触发卡」）
                pushTrigger(trigger, npcRef, qid, worth, gameSecond, isNanoJevActive());
                if (worth) {
                    triggerRegenerateSafe(projectId, npcRef, trigger, gameSecond);
                } else {
                    log.debug("[空间触发] {} 判定不触发重算: ref={}", trigger.code(), npcRef);
                }
            } catch (Exception e) {
                log.warn("[空间触发] 触发判定失败（零阻断）: ref={} trigger={} : {}",
                        npcRef, trigger.code(), e.getMessage());
            }
        });
    }

    /** NanoJev 场景是否活跃（用于轨迹标注：true=语义判定，false=程序化兜底）。 */
    private boolean isNanoJevActive() {
        return nanoJevDecisionGate != null
                && nanoJevDecisionGate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER);
    }

    /** 触发轨迹写（满上限丢最旧）。 */
    private void pushTrigger(GrowthSpaceTrigger trigger, String npcRef, String qid, boolean worth,
                             Long gameSecond, boolean nanoJevActive) {
        try {
            triggerStats.addLast(Map.of(
                    "trigger", trigger.code(), "label", trigger.label(), "qid", qid,
                    "npcRef", npcRef, "worth", worth, "nanoJevActive", nanoJevActive,
                    "gameSecond", gameSecond, "at", java.time.LocalDateTime.now().toString()));
            while (triggerStats.size() > TRIGGER_CAP) {
                triggerStats.pollFirst();
            }
        } catch (Exception ignored) {
            // 观测失败零副作用
        }
    }

    /**
     * 触发判定（NanoJev boolean 语义确认 + 程序化阈值兜底）：
     * 场景未启用/调用失败/升级 → 程序化阈值兜底（变化信号已由调用方确认，默认触发）；
     * NanoJev 采纳 false → 不触发（模型认为琐碎变化不值得重算）。
     */
    private boolean decideTrigger(Long projectId, String npcRef, String qid, String changeText,
                                  Long gameSecond) {
        if (nanoJevDecisionGate == null
                || !nanoJevDecisionGate.isEnabled(NanoJevDecisionScenes.SPACE_TRIGGER)) {
            return true; // 场景未启用 → 程序化阈值兜底（变化信号存在即触发）
        }
        Map<String, NanoJevQuestion> questions = NanoJevState.newQuestions();
        questions.put(qid, NanoJevDecisionTemplates.spaceTriggerQuestion(changeText));
        NanoJevState state = NanoJevState.text(npcRef, truncate(changeText, 200), questions);
        NanoJevDecisionGate.BooleanOutcome out = nanoJevDecisionGate.booleanDecision(
                projectId, NpcRefs.idOf(npcRef), NanoJevDecisionScenes.SPACE_TRIGGER, state, qid);
        if (!out.adopted()) {
            return true; // 失败/升级 → 程序化阈值兜底
        }
        return out.value();
    }

    /** 空间重算（失败零阻断；同码去重由 triggerRegenerate 内部窗口处理）。 */
    private void triggerRegenerateSafe(Long projectId, String npcRef, GrowthSpaceTrigger trigger,
                                       Long gameSecond) {
        if (growthSpaceService == null) {
            return;
        }
        try {
            Long gs = gameSecond;
            if (gs == null && worldClockService != null) {
                gs = worldClockService.currentEngineGameSecond(projectId);
            }
            growthSpaceService.triggerRegenerate(projectId, npcRef, trigger, gs);
            log.info("[空间触发] {} 空间重算: ref={}", trigger.code(), npcRef);
        } catch (Exception e) {
            log.warn("[空间触发] {} 空间重算失败（零阻断）: ref={} : {}", trigger.code(), npcRef, e.getMessage());
        }
    }

    /** 关系变化量评估（同步体，异步线程内执行）。 */
    private void evaluateRelationDeltaSync(Long projectId, String refA, String refB,
                                           String eventTitle, String eventContent,
                                           int fallbackDelta, Long gameSecond) {
        int delta = fallbackDelta;
        if (nanoJevDecisionGate != null
                && nanoJevDecisionGate.isEnabled(NanoJevDecisionScenes.RELATION_DELTA)) {
            String stateText = "角色A与角色B 共同经历了事件：「" + truncate(eventTitle, 60)
                    + "」。" + truncate(eventContent == null ? "" : eventContent, 180);
            Map<String, NanoJevQuestion> questions = NanoJevState.newQuestions();
            questions.put(QID_DELTA_MAGNITUDE, NanoJevDecisionTemplates.relationDeltaMagnitudeQuestion());
            questions.put(QID_DELTA_DIRECTION, NanoJevDecisionTemplates.relationDeltaDirectionQuestion());
            NanoJevState state = NanoJevState.text(refA + "|" + refB, stateText, questions);
            Map<String, Object> results = nanoJevDecisionGate.mixedDecisions(
                    projectId, NpcRefs.idOf(refA), NanoJevDecisionScenes.RELATION_DELTA, state,
                    Map.of(QID_DELTA_MAGNITUDE, NanoJevQuestion.TYPE_SCORE,
                            QID_DELTA_DIRECTION, NanoJevQuestion.TYPE_CHOICE));
            Object mag = results.get(QID_DELTA_MAGNITUDE);
            Object dir = results.get(QID_DELTA_DIRECTION);
            if (mag instanceof NanoJevDecisionGate.ScoreOutcome so && so.adopted()
                    && dir instanceof NanoJevDecisionGate.ChoiceOutcome co && co.adopted()) {
                int scale = so.level() >= 0 && so.level() < DELTA_SCALE.length
                        ? DELTA_SCALE[so.level()] : fallbackDelta;
                delta = switch (co.chosen() == null ? "" : co.chosen()) {
                    case "improve" -> Math.max(fallbackDelta, scale);
                    case "worsen" -> -Math.max(fallbackDelta, scale);
                    default -> 0; // 无变化 → 不动好感
                };
                log.debug("[空间触发] 关系变化量：{}↔{} 幅度={} 方向={} delta={}",
                        refA, refB, so.level(), co.chosen(), delta);
            }
        }
        // 应用好感更新（走 RelationDynamicsService 既有门面；delta=0 仅刷新互动时间——
        // 该门面内部 @Transactional 且含关系跨档检测挂钩，与 触发判定闭环）
        if (relationDynamics != null && delta != 0) {
            relationDynamics.applyDialogPairDelta(projectId, refA, refB, delta,
                    "共同经历事件：" + truncate(eventTitle == null ? "事件" : eventTitle, 50));
        }
    }

    /** 程序化阈值兜底（emotionShiftThreshold；未装配返回 null）。 */
    private GrowthProperties.Space spaceConfig() {
        if (growthProperties == null || !growthProperties.isEnabled()) {
            return null;
        }
        return growthProperties.getSpace();
    }

    /**
     * 关系动态服务（字段注入保持构造器不变；@Lazy 打破 bean 循环：
     * RelationDynamicsService（关系跨档检测挂钩）↔ SpaceTriggerJudge（好感应用）—— @Lazy 先例）
     */
    @Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.learning.RelationDynamicsService relationDynamics;

    /** 截断（null/空返回 null；超长截断）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
