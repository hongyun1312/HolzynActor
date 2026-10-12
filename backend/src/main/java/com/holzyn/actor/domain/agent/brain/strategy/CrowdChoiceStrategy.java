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

/**
 * 决策策略链·普通 NPC 快选择（System 1 快通道，priority=80，COMMON）。
 * <p>普通 NPC 的「此刻做什么」：空间候选/需求候选集由 NanoJev crowd_decision
 * 亚秒选择（熵最高的平坦分布恰是「真人做琐碎决定的随意感」）；NanoJev 未启用/
 * 失败 → AI 建议式回退（crowd AI，预算门控在调用方）；全失败 → 链继续（概率化）。</p>
 * <p><b>行为兼容</b>：内部逻辑 = 既有 {@code NpcStateService.crowdChoiceDrive}
 * 原样委托（NanoJev→AI 建议链路搬移），不新增/改变决策行为。</p>
 */
@Slf4j
@Component
public class CrowdChoiceStrategy implements BrainStrategy {

    /** 状态模块服务（普通 NPC 快选择委托；@Lazy 破 NpcStateService→Router→本策略 环） */
    @Autowired(required = false)
    @Lazy
    private NpcStateService npcStateService;

    @Override
    public String name() {
        return "crowd_choice";
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        return ctx != null && NpcKind.COMMON == ctx.kind();
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        if (npcStateService == null) {
            return Optional.empty();
        }
        try {
            var drive = npcStateService.crowdChoiceDrive(
                    ctx.projectId(), ctx.character(), ctx.gameSecond(), ctx.environmentText());
            return drive == null ? Optional.empty() : Optional.of(CrowdActionCodec.toMap(drive));
        } catch (Exception e) {
            log.warn("[BrainStrategy] 普通 NPC 快选择失败（链继续）: ref={} : {}", ctx.character().getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
