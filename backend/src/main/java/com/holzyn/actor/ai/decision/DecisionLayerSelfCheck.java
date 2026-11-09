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

package com.holzyn.actor.ai.decision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 判定层启动自检：关闭/半配时打 WARN，别让「静默 fail-closed」被当成「模型没生效」。
 *
 * <p><b>缺陷证据</b>：澄江镇二日实跑里 {@code HOLOZYN_ACTOR_AI_DECISION_ENABLED} 默认 {@code false}，
 * 漏设即整层判定静默关掉——没有 AI 调用、没有异常、没有日志，只在数据里留下
 * 「判定点全程走规则兜底」这个结果。排查时最费时间的一类问题就是这种
 * <b>配置缺失伪装成功能正常</b>。</p>
 *
 * <p>本自检只读配置、不打网络、不写库：</p>
 * <ul>
 * <li>{@code enabled=false} → WARN（说明影响：判定层零调用、全部走程序化/规则兜底）；</li>
 * <li>{@code enabled=true} 但 {@code baseUrl} 为空 → WARN（客户端 {@code isEnabled()=false}，
 * 同样是静默降级）；</li>
 * <li>配置齐全 → INFO（把生效的 adapter/model/端点打出来，便于对账）。</li>
 * </ul>
 *
 * <p>不抛异常、不阻断启动：世界能不能跑不取决于判定层，缺了它只是慢一点、笨一点。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionLayerSelfCheck implements ApplicationRunner {

    /** 判定层配置（{@code holzyn.actor.ai.decision.*}） */
    private final DecisionProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (properties == null) {
            log.warn("[判定层自检] 未装配 DecisionProperties（{}）——判定层全部判定点将 fail-closed 静默回退",
                    "holzyn.actor.ai.decision.*");
            return;
        }
        String hint = "；环境变量名：HOLOZYN_ACTOR_AI_DECISION_ENABLED / _BASE_URL / _API_KEY / _MODEL_ID";
        if (!properties.isEnabled()) {
            log.warn("[判定层自检] 判定层【未启用】{}——决策模型判定点全部 fail-closed："
                    + "AI 零调用、无异常、无告警，行为等价于「只有规则兜底」。"
                    + "若这不是预期，请设置 holzyn.actor.ai.decision.enabled=true 并配好端点{}",
                    "holzyn.actor.ai.decision.enabled=false", hint);
            return;
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            log.warn("[判定层自检] 判定层已开启但端点为空（holzyn.actor.ai.decision.base-url）——"
                    + "客户端 isEnabled()=false，调用仍会静默降级为规则兜底{}", hint);
            return;
        }
        log.info("[判定层自检] 判定层已启用：mode={} adapter={} model={} baseUrl={} 并发上限={}",
                properties.getMode(), blank(properties.getAdapter()), blank(properties.getModelId()),
                properties.getBaseUrl(), properties.getMaxConcurrent());
    }

    /**
     * 空白兜底显示（日志用）。
     *
     * @param v 配置值（可空）
     *
     * @return 非空字符串
     */
    private static String blank(String v) {
        return v == null || v.isBlank() ? "（默认）" : v;
    }
}
