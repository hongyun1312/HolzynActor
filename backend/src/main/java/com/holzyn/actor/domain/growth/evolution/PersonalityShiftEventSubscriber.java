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

package com.holzyn.actor.domain.growth.evolution;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 重大事件 → 人格偏移评估订阅者。
 * <p><b>规则</b>：事件重要度 ≥ {@code major-event-importance}
 * （默认 4）且涉及本人（{@code character_id} / {@code character_ids} 命中）→ 异步触发该角色
 * 人格偏移评估（trigger={@code major_event}）。</p>
 * <p><b>节流</b>：同角色 60 游戏分钟内不重复触发（事件洪峰防 AI 调用爆炸；评估本身有证据门槛
 * 二次兜底）。</p>
 * <p>；WorldEventBus 旁路消费，异常隔离）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalityShiftEventSubscriber implements WorldEventSubscriber {

    private final WorldEventBus worldEventBus;
    private final PersonalityShiftService shiftService;
    private final GrowthProperties growthProperties;

    /** 触发阈值（事件重要度 ≥ 该值才触发评估，默认 4；与 AgentMemoryEventSubscriber 大事记阈值同档） */
    @Value("${holzyn.actor.growth.shift.major-event-importance:4}")
    private int majorEventImportance;

    /** 节流窗口（游戏分钟，默认 60） */
    @Value("${holzyn.actor.growth.shift.major-event-throttle-game-minutes:60}")
    private long throttleGameMinutes;

    /** 节流表：npcRef → 上次触发评估时刻（游戏秒；进程内，重启清零可接受） */
    private final ConcurrentMap<String, Long> lastTriggerByRef = new ConcurrentHashMap<>();

    /** 启动后注册为世界事件总线订阅者（与记忆沉淀订阅者同机制，旁路消费）。 */
    @PostConstruct
    void hookWorldEventBus() {
        worldEventBus.subscribe(this);
    }

    @Override
    public void onEvent(ActorEvent event) {
        if (!growthProperties.getShift().isEnabled() || event == null || event.getProjectId() == null) {
            return;
        }
        try {
            int imp = event.getImportance() == null ? 3 : event.getImportance();
            if (imp < majorEventImportance) {
                return;
            }
            long gameSecond = event.getGameHour() == null ? 0 : event.getGameHour() * 3600L;
            for (String ref : involvedRefs(event)) {
                if (ref == null || !ref.startsWith("npc:")) {
                    continue;
                }
                Long last = lastTriggerByRef.get(ref);
                long throttle = throttleGameMinutes * 60L;
                if (last != null && throttle > 0 && gameSecond - last < throttle) {
                    continue;
                }
                lastTriggerByRef.put(ref, gameSecond);
                shiftService.assessAsync(event.getProjectId(), ref, GrowthShiftLog.TRIGGER_MAJOR_EVENT, gameSecond);
                log.info("[人格演化] 重大事件触发评估：project={} ref={} 事件={}（重要度 {}）",
                        event.getProjectId(), ref, event.getTitle(), imp);
            }
        } catch (Exception e) {
            log.warn("[人格演化] 重大事件订阅消费失败（隔离）: event={} : {}", event.getId(), e.getMessage());
        }
    }

    /** 事件涉及角色引用（character_id 单值 + character_ids 逗号列表；统一 npc:{id} 形态）。 */
    private static java.util.List<String> involvedRefs(ActorEvent event) {
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        if (event.getCharacterId() != null) {
            refs.add(com.holzyn.actor.common.NpcRefs.of(event.getCharacterId()));
        }
        if (event.getCharacterIds() != null && !event.getCharacterIds().isBlank()) {
            for (String s : event.getCharacterIds().split(",")) {
                String t = s == null ? "" : s.trim();
                if (!t.isEmpty()) {
                    Long id = com.holzyn.actor.common.NpcRefs.parseLenient(t);
                    if (id != null) {
                        refs.add(com.holzyn.actor.common.NpcRefs.of(id));
                    }
                }
            }
        }
        return new java.util.ArrayList<>(refs);
    }
}
