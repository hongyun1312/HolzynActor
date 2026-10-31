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

package com.holzyn.actor.domain.agent.learning;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件→关系好感订阅者（阶段 D，domain/agent/learning）——世界事件总线的「关系演化钩子」。
 * <p>设计（对齐 B4 的 {@code AgentMemoryEventSubscriber} 模式）：以<b>订阅者</b>实现而非让事件总线
 * 反向依赖学习组件（遵守 R1 依赖方向 runtime → 组件 → event）；每次 publish 后旁路消费，
 * 异常隔离不阻断主广播。</p>
 * <p>启发式规则：世界事件带 ≥2 参与角色时，参与者两两
 * 好感 +{@code event-delta}（默认 1）双向——「共同经历事件让彼此更亲近」；</p>
 * <ul>
 * <li><b>跳过 kind=action</b>（行动已由 AgentActionService 直接挂钩，避免双算）；</li>
 * <li><b>跳过 kind=evolution</b>（演化归档为叙述性节点，不建模人际关系）；</li>
 * <li><b>跳过 kind=relation</b>（关系事件是 的输出而非输入，避免自触发反馈环）；</li>
 * <li>重要度 ≥ {@code event-importance-threshold}（默认 2）才生效（低重要度琐事不扰动关系）。</li>
 * </ul>
 * <p>开关：{@code holzyn.actor.learning.relations.enabled}（默认 true）。</p>
 * <p>；依赖 agent/event 总线单向消费）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRelationEventSubscriber implements WorldEventSubscriber {

    private final WorldEventBus worldEventBus;
    private final RelationDynamicsService relationDynamicsService;

    /**
     * 空间重算触发判定（关系变化量评估——世界事件参与角色两两经 决策模型
     * score 幅度 + choice 方向平滑更新好感，失败回退固定增量；异步执行零阻断；
     * 字段注入保持构造器不变）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SpaceTriggerJudge spaceTriggerJudge;

    /** 动态关系总开关（与 RelationDynamicsService 同源，默认 true） */
    @Value("${holzyn.actor.learning.relations.enabled:true}")
    private boolean relationsEnabled;

    /** 事件参与对好感增量（默认 1；可配置调大/调小灵敏度） */
    @Value("${holzyn.actor.learning.relations.event-delta:1}")
    private int eventDelta;

    /** 事件参与建模的重要度阈值（事件重要度 ≥ 该值才增减好感，默认 2） */
    @Value("${holzyn.actor.learning.relations.event-importance-threshold:2}")
    private int eventImportanceThreshold;

    /** 需要跳过的发布方直挂 kind（action=行动已挂钩 / evolution=叙述节点 / relation= 自身输出） */
    private static final List<String> SKIP_KINDS = List.of("action", "evolution", "relation");

    /**
     * 启动后注册为世界事件总线订阅者（与记忆订阅者同机制，旁路消费）。
     */
    @PostConstruct
    void hookWorldEventBus() {
        worldEventBus.subscribe(this);
    }

    /**
     * 事件回调：参与角色两两增减好感（排除 action/evolution/relation，防双算/自触发）。
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!relationsEnabled || event == null || event.getProjectId() == null) {
            return;
        }
        try {
            int importance = event.getImportance() == null ? 3 : event.getImportance();
            if (importance < eventImportanceThreshold) {
                return;
            }
            if (event.getKind() != null && SKIP_KINDS.contains(event.getKind())) {
                return;
            }
            List<String> refs = parseRefs(event.getCharacterIds());
            if (refs.size() < 2) {
                return;
            }
            String title = event.getTitle() == null || event.getTitle().isBlank()
                    ? "事件" : event.getTitle().trim();
            String reason = "共同经历事件：" + title;
            // 参与者两两双向增减（共同经历让彼此更亲近）
            // ：关系变化量评估——决策模型（relation_delta 场景：幅度 score +
            // 方向 choice 同 state 一次前向）按幅度×方向平滑更新好感（替代固定增量 event-delta），
            // 失败/未启用 → 回退固定增量（零阻断；异步执行，不占本订阅调用方事务）
            for (int i = 0; i < refs.size(); i++) {
                for (int j = i + 1; j < refs.size(); j++) {
                    if (spaceTriggerJudge != null) {
                        spaceTriggerJudge.evaluateRelationDeltaAsync(
                                event.getProjectId(), refs.get(i), refs.get(j), title,
                                event.getContent() == null ? "" : event.getContent(),
                                eventDelta, event.getGameHour() == null ? null : event.getGameHour() * 3600L);
                    } else {
                        relationDynamicsService.applyDialogPairDelta(
                                event.getProjectId(), refs.get(i), refs.get(j), eventDelta, reason);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Learning] 事件→关系好感订阅失败：event={} : {}", event.getId(), e.getMessage());
        }
    }

    /**
     * 解析 character_ids 逗号分隔的角色 ref 列表（去空白去空）。
     */
    static List<String> parseRefs(String characterIds) {
        List<String> refs = new ArrayList<>();
        if (characterIds == null || characterIds.isBlank()) {
            return refs;
        }
        for (String raw : characterIds.split(",")) {
            String ref = raw.trim();
            if (!ref.isEmpty()) {
                refs.add(ref);
            }
        }
        return refs;
    }
}
