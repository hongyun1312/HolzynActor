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

package com.holzyn.actor.domain.growth.infant;

import com.holzyn.actor.domain.growth.persona.PersonaBootstrapService;
import com.holzyn.actor.domain.growth.persona.PersonaSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 新生儿档案分阶段揭示服务。
 * <p><b>语义</b>：新生儿出生时只建 ①②③④⑩ 维（遗传基底 + 生理 + 身份 + 外貌继承），
 * ⑤⑥⑦⑧⑨⑪ 维<b>留空</b>——婴儿的人格/价值观/习惯/经历/面具在出生时不可观察，提前给
 * 完整成人人格会破坏真实度。本服务在阶段跨越时逐段揭示：</p>
 * <ul>
 * <li><b>TODDLER（2 岁）</b>：⑦ 语言指纹（会说话才显现说话风格）+ ⑧ 习惯与日常（作息成型）；</li>
 * <li><b>CHILD（7 岁）</b>：⑤ 价值观与动机（道德观雏形）+ ⑥ 能力与知识（识字/技能启蒙）；</li>
 * <li><b>ADOLESCENT（12 岁）</b>：⑨ 经历与创伤（有足够人生阅历才谈得上）+ ⑪ 社会面具
 * （自我意识觉醒后才会戴面具）。</li>
 * </ul>
 * <p><b>实现</b>：复用 已交付的「AI 补全缺失维」链路（{@link PersonaBootstrapService#completeDims}，
 * 只许填缺、不许改已有），在异步虚拟线程执行（AI 调用秒级，不占引擎线程）；AI 失败零阻断
 * （该阶段档案保持空缺，下一阶段跨越可重试）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfantRevealService {

    /** 揭示异步池（虚拟线程；与阶段叙事同款）。 */
    private final ExecutorService revealExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final PersonaBootstrapService personaBootstrapService;

    /**
     * 阶段跨越揭示入口（由 {@code DevelopmentalStageService} 阶段跨越后调用，异步执行）。
     *
     * @param projectId 项目 ID
     *
     * @param profileId 角色 ID
     *
     * @param toStage 目标阶段码
     */
    public void revealOnStageTransition(Long projectId, Long profileId, String toStage) {
        if (profileId == null || toStage == null) {
            return;
        }
        List<String> dims = switch (toStage) {
            case "TODDLER" -> List.of(PersonaSchema.Dims.VOICE, PersonaSchema.Dims.HABITS);
            case "CHILD" -> List.of(PersonaSchema.Dims.VALUES, PersonaSchema.Dims.ABILITY);
            case "ADOLESCENT" -> List.of(PersonaSchema.Dims.EXPERIENCE, PersonaSchema.Dims.MASK);
            default -> List.of();
        };
        if (dims.isEmpty()) {
            return;
        }
        final List<String> target = List.copyOf(dims);
        revealExecutor.submit(() -> {
            try {
                var result = personaBootstrapService.completeDims(projectId, profileId, target);
                log.info("[婴儿揭示] 阶段跨越揭示完成：profileId={} 阶段={} 目标维={} 结果={}",
                        profileId, toStage, target, result == null ? "失败（零阻断）" : "成功");
            } catch (Exception e) {
                log.warn("[婴儿揭示] 阶段跨越揭示异常（零阻断，下一阶段可重试）: profileId={} 阶段={} 原因={}",
                        profileId, toStage, e.getMessage());
            }
        });
    }
}
