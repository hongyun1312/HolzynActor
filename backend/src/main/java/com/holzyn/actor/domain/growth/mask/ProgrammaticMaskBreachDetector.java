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
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 面具破绽程序化检测。
 * <p><b>触发物来源与命中规则</b>：</p>
 * <ul>
 * <li>档案 ⑪ 维 {@code dims.mask.breaches}：{@code trigger}（触发物）+ {@code suppressionDelta}
 * （负值=抑制下降；如「被提次子 −0.5」）；</li>
 * <li>档案 ⑨ 维 {@code dims.experience.traumas[].triggers}：创伤触发物（默认降幅 −0.3，
 * 对齐附录 A 沈砚秋「孩童咳嗽声 −0.3」语义）；</li>
 * <li>酒精状态：本人当前行动含 饮酒/喝酒/醉酒/酒醉/酌 词 → 降幅 −0.3（设计 §5.6 酒精破绽）；</li>
 * <li>降幅求和钳制 [0, 0.8]（不出现抑制被压成负数）。</li>
 * </ul>
 * <p>判定点设计（N 轨道预留）：本类被 {@link MaskService} 经 {@link MaskBreachDecision} 接口调用；
 * 之后 决策模型 接入只替换实现，不重改面具主流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgrammaticMaskBreachDetector implements MaskBreachDecision {

    /** 创伤触发物默认降幅（档案未给 suppressionDelta 时） */
    private static final double TRAUMA_DEFAULT_DROP = 0.3;
    /** 酒精触发默认降幅 */
    private static final double ALCOHOL_DROP = 0.3;
    /** 酒精状态关键词（命中本人当前行动） */
    private static final String[] ALCOHOL_WORDS = {"饮酒", "喝酒", "醉酒", "酒醉", "酌酒", "吃酒", "喝醉"};

    private final ActorCharacterRepository characterRepository;

    /**
     * 决策模型 面具破绽判定（默认关场景；开启且采纳时优先，失败/升级回退本程序化实现；
     * 字段注入保持构造器不变，null=未装配[单测]直接走程序化）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.MaskBreachJudge decisionJudge;

    @Override
    public BreachResult detect(Long projectId, String npcRef, String contextText, Long gameSecond) {
        try {
            if (npcRef == null || !npcRef.startsWith("npc:")) {
                return BreachResult.none();
            }
            ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
            if (ch == null) {
                return BreachResult.none();
            }
            // ：决策模型 破绽判定优先（默认关场景；采纳即用，否则回退程序化）
            if (decisionJudge != null) {
                try {
                    BreachResult nano = decisionJudge.detect(projectId, npcRef, ch.getName(),
                            contextText, buildTriggerSummary(ch, contextText));
                    if (nano != null) {
                        return nano;
                    }
                } catch (Exception e) {
                    log.warn("[面具] 决策模型 破绽判定异常（回退程序化）: ref={} : {}", npcRef, e.getMessage());
                }
            }
            double totalDrop = 0;
            String hitTrigger = null;
            // ① 档案 ⑪ 面具破绽条目
            PersonaProfile persona = PersonaJson.readQuietly(ch.getPersonaJson());
            if (persona != null && persona.getDims() != null && persona.getDims().getMask() != null) {
                List<PersonaDims.Breach> breaches = persona.getDims().getMask().getBreaches();
                if (breaches != null) {
                    for (PersonaDims.Breach b : breaches) {
                        if (b == null || b.getTrigger() == null || b.getTrigger().isBlank()) {
                            continue;
                        }
                        if (contextText != null && matches(contextText, b.getTrigger())) {
                            double drop = b.getSuppressionDelta() == null ? TRAUMA_DEFAULT_DROP
                                    : Math.abs(b.getSuppressionDelta());
                            totalDrop += drop;
                            if (hitTrigger == null) {
                                hitTrigger = b.getTrigger();
                            }
                        }
                    }
                }
            }
            // ② 档案 ⑨ 创伤触发物（默认降幅）
            if (persona != null && persona.getDims() != null && persona.getDims().getExperience() != null) {
                List<PersonaDims.Trauma> traumas = persona.getDims().getExperience().getTraumas();
                if (traumas != null) {
                    for (PersonaDims.Trauma t : traumas) {
                        if (t == null || t.getTriggers() == null) {
                            continue;
                        }
                        for (String trigger : t.getTriggers()) {
                            if (trigger != null && !trigger.isBlank() && contextText != null
                                    && matches(contextText, trigger)) {
                                totalDrop += TRAUMA_DEFAULT_DROP;
                                if (hitTrigger == null) {
                                    hitTrigger = trigger;
                                }
                            }
                        }
                    }
                }
            }
            // ③ 酒精状态（本人当前行动命中）
            if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
                for (String w : ALCOHOL_WORDS) {
                    if (ch.getCurrentActivity().contains(w)) {
                        totalDrop += ALCOHOL_DROP;
                        if (hitTrigger == null) {
                            hitTrigger = "酒意上头";
                        }
                        break;
                    }
                }
            }
            if (totalDrop <= 0) {
                return BreachResult.none();
            }
            double capped = Math.min(0.8, totalDrop);
            log.debug("[面具] 破绽命中：ref={} 触发物={} 降幅={}", npcRef, hitTrigger, capped);
            return BreachResult.hit(hitTrigger, capped);
        } catch (Exception e) {
            log.warn("[面具] 破绽检测失败（按无破绽，零阻断）: ref={} 原因={}", npcRef, e.getMessage());
            return BreachResult.none();
        }
    }

    /**
     * 触发物摘要（决策模型 判定输入：档案 ⑪ breaches + ⑨ traumas triggers + 酒精状态；
     * 无触发物返回 null）。
     */
    private String buildTriggerSummary(ActorCharacter ch, String contextText) {
        try {
            PersonaProfile persona = PersonaJson.readQuietly(ch.getPersonaJson());
            java.util.List<String> triggers = new java.util.ArrayList<>();
            if (persona != null && persona.getDims() != null && persona.getDims().getMask() != null) {
                List<PersonaDims.Breach> breaches = persona.getDims().getMask().getBreaches();
                if (breaches != null) {
                    for (PersonaDims.Breach b : breaches) {
                        if (b != null && b.getTrigger() != null && !b.getTrigger().isBlank()) {
                            triggers.add(b.getTrigger().trim());
                        }
                    }
                }
            }
            if (persona != null && persona.getDims() != null && persona.getDims().getExperience() != null) {
                List<PersonaDims.Trauma> traumas = persona.getDims().getExperience().getTraumas();
                if (traumas != null) {
                    for (PersonaDims.Trauma t : traumas) {
                        if (t != null && t.getTriggers() != null) {
                            for (String tg : t.getTriggers()) {
                                if (tg != null && !tg.isBlank()) {
                                    triggers.add(tg.trim());
                                }
                            }
                        }
                    }
                }
            }
            if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
                for (String w : ALCOHOL_WORDS) {
                    if (ch.getCurrentActivity().contains(w)) {
                        triggers.add("正在饮酒");
                        break;
                    }
                }
            }
            return triggers.isEmpty() ? null : String.join("、", triggers);
        } catch (Exception e) {
            log.warn("[面具] 触发物摘要构造失败（回退程序化判定）: {}", e.getMessage());
            return null;
        }
    }

    /** 短语分段包含匹配。 */
    private static boolean matches(String text, String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return false;
        }
        for (String seg : phrase.split("[、，,;；。]")) {
            String s = seg.trim();
            if (s.length() >= 2 && text.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
