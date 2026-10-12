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

package com.holzyn.actor.domain.agent.brain.strategy;

import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 决策策略链·概率化需求满足（Sims 概率模型，priority=40，COMMON）。
 * <p>普通 NPC 的认知层：需求值 &lt; 阈值进入候选，处理概率 p=(100-value)/100
 * （100→0%、50→50%、10→90%，钳制 [0.05,0.95]），需求-地点对象评分加权随机选一；
 * urgency≥2 强制最紧急需求；无候选返回空（链空 → 调用方作息状态机兜底）。</p>
 * <p><b>行为兼容</b>：内部逻辑 = 既有 {@code NpcStateService.probabilisticDrive}
 * 原样委托（含生理危险分支——本策略在链内位于危险强制之后，危险已由链首拦截）。</p>
 */
@Slf4j
@Component
public class ProbabilisticStrategy implements BrainStrategy {

    /** 状态模块服务（概率化驱动委托；@Lazy 破 NpcStateService→Router→本策略 环） */
    @Autowired(required = false)
    @Lazy
    private NpcStateService npcStateService;

    @Override
    public String name() {
        return "probabilistic";
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        // 仅普通 NPC；概率化驱动未启用时链跳过（对齐调用方 isProbabilisticDriveEnabled 门控）
        return ctx != null && NpcKind.COMMON == ctx.kind()
                && npcStateService != null && npcStateService.isProbabilisticDriveEnabled();
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        if (npcStateService == null) {
            return Optional.empty();
        }
        try {
            var drive = npcStateService.probabilisticDrive(
                    ctx.projectId(), ctx.character(), ctx.gameSecond(), new Random());
            return drive == null ? Optional.empty() : Optional.of(CrowdActionCodec.toMap(drive));
        } catch (Exception e) {
            log.warn("[BrainStrategy] 概率化需求满足失败（链继续）: ref={} : {}", ctx.character().getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
