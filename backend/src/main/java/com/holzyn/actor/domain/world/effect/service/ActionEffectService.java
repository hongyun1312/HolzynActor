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

package com.holzyn.actor.domain.world.effect.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.action.AgentActionService;
import com.holzyn.actor.domain.npc.goal.entity.ActorActionPlan;
import com.holzyn.actor.domain.npc.goal.repository.ActorActionPlanRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.adjudicate.model.ActionVerdict;
import com.holzyn.actor.domain.world.effect.model.EffectContext;
import com.holzyn.actor.domain.world.effect.model.EffectReport;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行动裁决效果落地。
 *
 * <p><b>顺序</b>：先由 {@link AgentActionService#executeWithVerdict} 完成原行动并发布 action 事件，
 * 拿到 {@code plan.eventId} 后再统一应用 effects——满足 fact_log.source_event_id 非空。
 * 两者在同一个外层事务里：任一效果 op 失败 → 行动/状态/事件/效果整批回滚，外层再以新事务
 * 写 {@code EFFECT_FAILED:{i}}（不落可见失败事件）。</p>
 *
 * <p>开关关闭时本类不会被调用（{@code ActionAdjudicationService} 透传旧 execute）；
 * 效果为空时不做额外写入，保持行为。</p>
 */
@Slf4j
@Service
public class ActionEffectService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private AgentActionService agentActionService;

    /** 效果入口依赖 AgentRuntime（schedule.plan），而 AgentRuntime 又反向依赖裁决链；
     *  @Lazy 打破启动期环。 */
    @Lazy
    @Autowired(required = false)
    private EffectApplier effectApplier;

    @Autowired(required = false)
    private ActorActionPlanRepository planRepository;

    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 执行行动并把裁决 effects 落地（同一事务）。
     *
     * @param plan    行动计划
     * @param verdict 裁决（可空=旧路径）
     */
    @Transactional
    public void execute(ActorActionPlan plan, ActionVerdict verdict) {
        if (agentActionService == null || plan == null) {
            return;
        }
        agentActionService.executeWithVerdict(plan, verdict);
        if (verdict == null || !verdict.executable() || verdict.effects().isEmpty()) {
            return;
        }
        if (!"done".equals(plan.getStatus()) || effectApplier == null) {
            return;
        }
        ActorCharacter ch = characterRepository == null ? null
                : characterRepository.findById(plan.getCharacterId()).orElse(null);
        if (ch == null || ch.getProjectId() == null) {
            return;
        }
        long gameSecond = currentGameSecond(ch.getProjectId());
        EffectContext ctx = new EffectContext(ch.getProjectId(), plan.getEventId(), null,
                NpcRefs.of(ch.getId()), plan.getId(), ch.getLocation(), gameSecond);
        EffectReport report = effectApplier.applyWithReport(verdict.effects(), ctx);
        plan.setEffectsJson(reportJson(verdict, report));
        if (planRepository != null) {
            planRepository.save(plan);
        }
        log.info("[效果] 行动效果落地: plan={} ops={} skipped={}", plan.getId(),
                verdict.effects().size(), report.results().stream()
                        .filter(r -> com.holzyn.actor.domain.world.effect.model.EffectResult.SKIPPED_NO_INVENTORY
                                .equals(r.status())).count());
    }

    private long currentGameSecond(Long projectId) {
        if (worldClockService == null) {
            return 0L;
        }
        try {
            Long gs = worldClockService.currentEngineGameSecond(projectId);
            return gs == null ? 0L : gs;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * effects_json 结果格式：保留原 ops 并附逐条状态，便于审计“哪些落了、哪些因物品域跳过”。
     */
    private String reportJson(ActionVerdict verdict, EffectReport report) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("ops", verdict.effects());
            root.put("results", report.results());
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[效果] effects_json 序列化失败（保留草稿）: {}", e.getMessage());
            return planFallback(verdict);
        }
    }

    private String planFallback(ActionVerdict verdict) {
        try {
            return MAPPER.writeValueAsString(verdict.effects());
        } catch (Exception ignored) {
            return "[]";
        }
    }
}
