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

package com.holzyn.actor.domain.world.adjudicate.model;

import java.util.List;

/**
 * 一次行动裁决的不可变结果（§4.5/§4.7）。
 *
 * @param verdict         结论（落库前不得为 UNSURE）
 * @param reasonCode      原因码（失败/降级必填；成功为 OK）
 * @param observedText    旁人可观察到的结果文本（失败尤其需要）
 * @param durationMinutes 行动耗时（分钟；0=不改变既有节奏）
 * @param effects 效果草稿（才应用；只落 effects_json）
 * @param adjudicatedBy   裁决者（rule / ai / fallback）
 */
public record ActionVerdict(Verdict verdict, String reasonCode, String observedText,
                            int durationMinutes, List<EffectOp> effects, String adjudicatedBy) {

    public ActionVerdict {
        effects = effects == null ? List.of() : List.copyOf(effects);
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "OK" : reasonCode;
        observedText = observedText == null ? "" : observedText;
        adjudicatedBy = adjudicatedBy == null || adjudicatedBy.isBlank() ? "rule" : adjudicatedBy;
    }

    /** 规则成功。 */
    public static ActionVerdict success(String ruleId) {
        return new ActionVerdict(Verdict.SUCCESS, "OK", "", 0, List.of(), "rule:" + ruleId);
    }

    /**
     * 规则成功并附带确定性效果（交易结算用）。
     *
     * <p>为什么让规则产出效果：成交必须原子（扣货 + 收钱 + 报价 + 流水），
     * 由规则算好 {@code market.trade} 再交给 {@code EffectApplier} 在同一个事务里落地，
     * 比在规则里直接写库更符合「效果统一入口」的既有纪律。</p>
     *
     * @param ruleId  规则 ID
     * @param effects 效果草稿
     *
     * @return 成功裁决
     */
    public static ActionVerdict success(String ruleId, List<EffectOp> effects) {
        return new ActionVerdict(Verdict.SUCCESS, "OK", "", 0, effects, "rule:" + ruleId);
    }

    /** 规则失败。 */
    public static ActionVerdict fail(String ruleId, String reasonCode, String observedText) {
        return new ActionVerdict(Verdict.FAIL, reasonCode, observedText, 0, List.of(), "rule:" + ruleId);
    }

    /** 规则无法判定（仅规则层内部使用）。 */
    public static ActionVerdict unsure(String ruleId, String reasonCode, String observedText) {
        return new ActionVerdict(Verdict.UNSURE, reasonCode, observedText, 0, List.of(), "rule:" + ruleId);
    }

    /** 非规则裁决（AI / 失败降级）。 */
    public static ActionVerdict of(Verdict verdict, String reasonCode, String observedText,
                                   int durationMinutes, List<EffectOp> effects, String adjudicatedBy) {
        return new ActionVerdict(verdict, reasonCode, observedText, durationMinutes, effects, adjudicatedBy);
    }

    /** 是否允许继续执行原行动（FAIL 之外都继续）。 */
    public boolean executable() {
        return verdict != Verdict.FAIL;
    }
}
