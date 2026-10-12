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

package com.holzyn.actor.domain.evaluation.vo;

import com.holzyn.actor.domain.evaluation.entity.ActorEvalSample;

import java.time.LocalDateTime;

/**
 * 评估样本 VO。
 */
public record EvalSampleVO(
        Long id,
        Long evalRunId,
        Integer seq,
        String source,
        String npcRef,
        String npcName,
        String scene,
        String callStatus,
        Long gameSecond,
        String stimulusText,
        String contextText,
        String responseText,
        String traceId,
        LocalDateTime createdAt) {

    /** 实体 → VO。 */
    public static EvalSampleVO of(ActorEvalSample e) {
        if (e == null) {
            return null;
        }
        return new EvalSampleVO(e.getId(), e.getEvalRunId(), e.getSeq(), e.getSource(), e.getNpcRef(),
                e.getNpcName(), e.getScene(), e.getCallStatus(), e.getGameSecond(), e.getStimulusText(),
                e.getContextText(), e.getResponseText(), e.getTraceId(), e.getCreatedAt());
    }
}
