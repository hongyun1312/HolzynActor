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

import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生理小动作服务。
 * <p>职责（主开发方案《HolzynActor_实时行为决策系统_开发路径》§4.5 真人感五维度之
 * 「生理小动作细节」）：状态写回时<b>程序化生成小动作文本（零 AI）</b>——疲劳→打哈欠/揉眼、
 * 饥饿→揉肚子、口渴→舔唇、膀胱告急→不安挪动、健康告急→捂胸口、情绪→叹气/攥拳/哼曲等，
 * 无状态触发时小概率随机点缀（理衣襟/掸袖/看天色等）；落点 thought_text（想法气泡）+ SSE
 * （经 {@code WorldSandboxStateService.publishThought}，去重由沙盘服务负责）。</p>
 * <p><b>特殊 NPC 与人群 NPC 都适用</b>（状态行 npc_type=special/crowd 皆可），
 * 按状态触发 + 小概率随机点缀；触发节流：每 NPC 每 {@code micro-motion-cooldown-game-minutes}
 * （默认 30 游戏分钟）最多一次（防 SSE 刷屏，24 小时不间断运行的成本护栏）。</p>
 * <p>设计依据（联网调研）：Mateas & Stern ABL（2002，等待/空闲时并行小动作=可信 agent
 * 关键细节）/ MoCap Online idle 动画指南（次生运动 4~8s 周期+随机、idle 池 3~5 变体）/
 * Generative nonverbal facial behavior 综述（状态条件化小动作生成）——小动作是「像真人」的
 * 最廉价表达层，纯程序化零 AI 成本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MicroMotionService {

    /** 小动作生成冷却表：key=projectId:npcRef → 上次生成游戏秒（进程内存态，重启丢失可接受） */
    private final ConcurrentHashMap<String, Long> lastMotionSecond = new ConcurrentHashMap<>();

    /** 冷却表条目上限（防长期运行内存膨胀，24 小时不间断运行护栏） */
    private static final int CACHE_LIMIT = 10000;

    /** 随机点缀小动作池（无状态触发时小概率出现；对齐 idle 池 3~5 变体） */
    private static final String[] RANDOM_MOTIONS = {
            "理了理衣襟", "掸了掸衣袖上的尘土", "抬头看了看天色", "踢了踢脚边的石子", "出神地望了一会儿远方"
    };

    /** 健康告急小动作 */
    private static final String MOTION_HEALTH = "捂着胸口，脸色发白";
    /** 疲劳小动作 */
    private static final String MOTION_FATIGUE = "打了个哈欠，揉了揉眼睛";
    /** 饥饿小动作 */
    private static final String MOTION_HUNGER = "摸了摸咕咕直叫的肚子";
    /** 口渴小动作 */
    private static final String MOTION_THIRST = "舔了舔干裂的嘴唇";
    /** 膀胱告急小动作 */
    private static final String MOTION_BLADDER = "不安地挪了挪身子";

    /** 情绪 → 小动作映射（主导情绪标签归一化匹配；对齐状态模块主导情绪 code） */
    private static final Map<String, String> EMOTION_MOTIONS = Map.ofEntries(
            Map.entry("joy", "嘴角不自觉地扬起，哼起不成调的小曲"),
            Map.entry("happy", "嘴角不自觉地扬起，哼起不成调的小曲"),
            Map.entry("sad", "轻轻叹了口气"),
            Map.entry("sorrow", "轻轻叹了口气"),
            Map.entry("anger", "攥了攥拳头"),
            Map.entry("angry", "攥了攥拳头"),
            Map.entry("fear", "不自觉地缩了缩肩膀"),
            Map.entry("afraid", "不自觉地缩了缩肩膀"),
            Map.entry("anxious", "来回搓了搓手"),
            Map.entry("anxiety", "来回搓了搓手"),
            Map.entry("surprise", "微微瞪大了眼睛")
    );

    private final RealtimeDecisionProperties props;

    /** 沙盘状态服务（thought_text 写入 + SSE 广播；null=未装配跳过落点，仅判定零成本） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService sandboxStateService;

    /**
     * 按当前状态程序化生成小动作并落点（零 AI）。
     * <p>触发优先级：健康告急 → 生理需求最低者（饥饿/口渴/膀胱/疲劳）→ 主导情绪 → 小概率随机点缀；
     * 节流：每 NPC 每冷却窗口一次（key=projectId:npcRef，游戏时间基准）；落点 thought_text
     * （source=m4_micro）+ SSE（publishThought 同内容去重）。</p>
     *
     * @param projectId 项目 ID（null → 跳过）
     *
     * @param npcRef NPC 引用（special:{id} / crowd:{id}；null → 跳过）
     *
     * @param st 状态行（null → 跳过；只读取值）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟未知，按 0 基准防同刻重复）
     */
    public void maybeMicroMotion(Long projectId, String npcRef, ActorNpcState st, Long gameSecond) {
        if (!props.isMicroMotionEnabled() || projectId == null || npcRef == null || st == null) {
            return;
        }
        // 冷却节流（防刷屏）：每 NPC 每 micro-motion-cooldown-game-minutes 一次
        Long now = gameSecond == null ? 0L : gameSecond;
        String key = projectId + ":" + npcRef;
        int cooldown = props.getMicroMotionCooldownGameMinutes() > 0
                ? props.getMicroMotionCooldownGameMinutes() * 60 : 0;
        Long last = lastMotionSecond.get(key);
        if (cooldown > 0 && last != null && now - last < cooldown) {
            return;
        }
        String motion = resolveMotion(st);
        if ((motion == null || motion.isBlank()) && !randomGate()) {
            return;
        }
        if (motion == null || motion.isBlank()) {
            motion = randomMotion(npcRef);
        }
        lastMotionSecond.put(key, now);
        enforceLimit();
        publish(projectId, npcRef, motion, gameSecond);
    }

    /**
     * 随机点缀门控（无状态触发时按 micro-motion-random-probability 概率点缀；
     * 概率 <=0 关闭、>=1 恒开）。
     */
    private boolean randomGate() {
        double p = props.getMicroMotionRandomProbability();
        return p > 0 && Math.random() < p;
    }

    /**
     * 小动作文本解析（纯静态，可单测）：健康告急 → 生理需求 → 情绪 → 随机点缀。
     *
     * @param st 状态行（只读取值）
     *
     * @return 小动作文本（无触发返回 null）
     */
    static String resolveMotion(ActorNpcState st) {
        if (st == null) {
            return null;
        }
        // ① 健康告急最优先（身体不适，小动作最明显）
        if (val(st.getHealth(), 100) < 25) {
            return MOTION_HEALTH;
        }
        // ② 生理需求最低者优先（饥饿/口渴/膀胱/疲劳）
        String need = mostUrgentNeed(st);
        if (need != null) {
            return need;
        }
        // ③ 主导情绪 → 小动作
        String emotion = st.getDominantEmotion();
        if (emotion != null && !emotion.isBlank()) {
            String m = EMOTION_MOTIONS.get(emotion.trim().toLowerCase());
            if (m != null) {
                return m;
            }
        }
        // ④ 小概率随机点缀（让世界更活；确定性种子按 ref 保持同一角色风格稳定）
        return null;
    }

    /** 生理需求最紧急者小动作（值最低者；全高于阈值返回 null）。 */
    private static String mostUrgentNeed(ActorNpcState st) {
        int hunger = val(st.getHunger(), 70);
        int thirst = val(st.getThirst(), 70);
        int bladder = val(st.getBladder(), 50);
        int energy = val(st.getEnergy(), 80);
        if (hunger <= thirst && hunger <= bladder && hunger <= energy && hunger < 25) {
            return MOTION_HUNGER;
        }
        if (thirst <= bladder && thirst <= energy && thirst < 25) {
            return MOTION_THIRST;
        }
        if (bladder <= energy && bladder < 25) {
            return MOTION_BLADDER;
        }
        if (energy < 25) {
            return MOTION_FATIGUE;
        }
        return null;
    }

    /** 随机点缀小动作（外部调用方按概率触发；同一 ref 种子稳定）。 */
    static String randomMotion(String npcRef) {
        int idx = Math.floorMod(npcRef == null ? 0 : npcRef.hashCode(), RANDOM_MOTIONS.length);
        return RANDOM_MOTIONS[Math.abs(idx) % RANDOM_MOTIONS.length];
    }

    /** 落点：thought_text（想法气泡）+ SSE；沙盘服务未装配/失败静默（小动作为旁路增强）。 */
    private void publish(Long projectId, String npcRef, String motion, Long gameSecond) {
        if (sandboxStateService == null) {
            return;
        }
        try {
            sandboxStateService.publishThought(projectId, npcRef, motion, "m4_micro", gameSecond);
        } catch (Exception e) {
            log.debug("[小动作] 想法气泡写入失败（静默）: ref={} : {}", npcRef, e.getMessage());
        }
    }

    /** 冷却表条目上限保护（惰性清理最旧一条，防内存膨胀）。 */
    private void enforceLimit() {
        if (lastMotionSecond.size() > CACHE_LIMIT) {
            lastMotionSecond.keySet().stream().findFirst().ifPresent(lastMotionSecond::remove);
        }
    }

    /** 状态值（null 回退默认）。 */
    private static int val(Integer v, int fallback) {
        return v == null ? fallback : v;
    }
}
