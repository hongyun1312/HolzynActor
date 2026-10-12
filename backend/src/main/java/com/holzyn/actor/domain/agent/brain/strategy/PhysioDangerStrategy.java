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

import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.physio.PhysioNeedService;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 决策策略链·生理危险强制（System 1 本能硬性，priority=100）。
 * <p>任一生理四需低于危险阈值 → 程序化强制生理行动（0 AI），任何触发源先于
 * 快通道/深思——快饿死的人不会因决策系统失效而继续发呆（与既有
 * {@code PhysioNeedService.dangerDecision} 同源；普通 NPC 经
 * {@code NpcStateService.dangerDrive} 同口径，行动文本/恢复关键词对齐）。</p>
 * <p><b>双类型适用</b>：SPECIAL（dangerDecision 决策 Map）与 COMMON
 * （dangerDrive 人群行动 Map）共用本策略。</p>
 */
@Slf4j
@Component
public class PhysioDangerStrategy implements BrainStrategy {

    /**
     * 生理需求耦合服务（特殊链危险强制；@Lazy 破环：physioNeedService→npcStateService→
     * Router→本策略 构成 Bean 环，启动实测抓修；运行时经代理取真实 Bean，null=未装配跳过）
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private PhysioNeedService physioNeedService;

    /** 状态模块服务（普通链危险强制；@Lazy 破 NpcStateService→Router→本策略 环） */
    @Autowired(required = false)
    @Lazy
    private NpcStateService npcStateService;

    @Override
    public String name() {
        return "physio_danger";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean applies(DecisionContext ctx) {
        return ctx != null && ctx.character() != null && ctx.projectId() != null;
    }

    @Override
    public Optional<Map<String, Object>> decide(DecisionContext ctx) {
        try {
            if (NpcKind.COMMON == ctx.kind()) {
                if (npcStateService == null) {
                    return Optional.empty();
                }
                var drive = npcStateService.dangerDrive(ctx.projectId(), ctx.character(), ctx.gameSecond());
                if (drive == null) {
                    return Optional.empty();
                }
                return Optional.of(CrowdActionCodec.toMap(drive));
            }
            if (physioNeedService == null) {
                return Optional.empty();
            }
            Map<String, Object> decision = physioNeedService.dangerDecision(ctx.projectId(), ctx.character());
            if (decision == null) {
                return Optional.empty();
            }
            AiCallContext.recordStep(ctx.projectId(), "agent_decision", "thinking",
                    "生理需求危险区强制（0 AI）：" + abbreviate(String.valueOf(decision.getOrDefault("action", "")), 40), 0);
            return Optional.of(decision);
        } catch (Exception e) {
            log.warn("[BrainStrategy] 生理危险强制失败（链继续）: ref={} : {}", ctx.character().getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 字符串截断（摘要展示用）。 */
    private static String abbreviate(String s, int max) {
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
