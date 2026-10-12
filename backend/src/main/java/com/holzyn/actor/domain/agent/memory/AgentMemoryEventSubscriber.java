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

package com.holzyn.actor.domain.agent.memory;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 事件→记忆沉淀订阅者（B4 /，domain/agent/memory）——世界事件总线的记忆钩子。
 * <p>设计：{@code WorldEventBus.publish} 的「记忆沉淀钩子」
 * 以<b>订阅者</b>实现（而非事件总线反向依赖记忆服务，遵守 R1 依赖方向 runtime → 组件 → event）：
 * 每次 publish 后由本订阅者旁路消费，事件落库与记忆写入同事务、异常隔离不阻断主广播。</p>
 * <p>两条写入通道：</p>
 * <ul>
 * <li><b>参与者经历记忆</b>：visibility=private/location 且带参与角色 ref 的事件，
 * 按事件重要度（≥ {@code event-importance-threshold}，默认 2）为每位当事人写 kind=observation
 * 经历记忆；<b>跳过 action（ActionEngine.execute 已直写）与 evolution（WorldEvolutionService
 * finish 已直写当事人 fact）</b>，避免重复沉淀。</li>
 * <li><b>项目级大事记（，修）</b>：重大世界事件（kind=world/evolution）重要度
 * ≥ {@code project-event-importance-threshold}（默认 4）写 characterId=null+ref=null 的
 * 【世界大事记】——经既有 memoryContext 项目级注入「所有角色知晓」。</li>
 * </ul>
 * <p>开关：{@code holzyn.actor.memory.record-events}（默认 true，对应路线图 §5.6 开关体系）。</p>
 * <p>；依赖 agent/event 总线单向消费）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryEventSubscriber implements WorldEventSubscriber {

    private final WorldEventBus worldEventBus;
    private final AgentMemoryService agentMemoryService;

    /** 事件→记忆沉淀总开关（默认 true；false=事件不写经历/大事记记忆） */
    @Value("${holzyn.actor.memory.record-events:true}")
    private boolean recordEvents;

    /** 参与者经历记忆写入阈值（事件重要度 ≥ 该值才为当事人写经历，默认 2） */
    @Value("${holzyn.actor.memory.event-importance-threshold:2}")
    private int eventImportanceThreshold;

    /** 项目级大事记写入阈值（重大世界事件重要度 ≥ 该值才写【世界大事记】，默认 4） */
    @Value("${holzyn.actor.memory.project-event-importance-threshold:4}")
    private int projectEventImportanceThreshold;

    /** 经历/大事记内容截断上限（记忆条目保持精炼，控制注入 token） */
    private static final int CONTENT_MAX = 300;

    /**
     * 启动后注册为世界事件总线订阅者（与调试事件流同机制，旁路消费）。
     */
    @PostConstruct
    void hookWorldEventBus() {
        worldEventBus.subscribe(this);
    }

    /**
     * 事件回调：参与者经历记忆 + 项目级大事记；异常隔离不阻断主广播。
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!recordEvents || event == null || event.getProjectId() == null) {
            return;
        }
        try {
            recordParticipantExperiences(event);
        } catch (Exception e) {
            log.warn("[事件记忆] 参与者经历沉淀失败：event={} : {}", event.getId(), e.getMessage());
        }
        try {
            recordProjectChronicle(event);
        } catch (Exception e) {
            log.warn("[事件记忆] 项目级大事记沉淀失败：event={} : {}", event.getId(), e.getMessage());
        }
    }

    // ==================== 参与者经历记忆 ====================

    /**
     * 参与者经历记忆：visibility=private/location 且带参与角色 ref 的事件，按事件重要度
     * 为每位当事人写 observation 经历；跳过 action/evolution（发布方已直写，避免重复沉淀）。
     */
    private void recordParticipantExperiences(ActorEvent event) {
        int importance = event.getImportance() == null ? 3 : event.getImportance();
        if (importance < eventImportanceThreshold) {
            return;
        }
        // 跳过由发布方直写记忆的事件类型：action（ActionEngine 直写）、evolution（WorldEvolutionService 直写）
        String kind = event.getKind();
        if ("action".equals(kind) || "evolution".equals(kind)) {
            return;
        }
        // 仅 private/location 在场当事人写经历；global 已由项目级大事记覆盖，不逐人写
        String visibility = event.getVisibility();
        if (!"private".equals(visibility) && !"location".equals(visibility)) {
            return;
        }
        if (event.getCharacterIds() == null || event.getCharacterIds().isBlank()) {
            return;
        }
        String content = experienceText(event);
        for (String raw : event.getCharacterIds().split(",")) {
            String ref = raw.trim();
            if (ref.isEmpty()) {
                continue;
            }
            agentMemoryService.recordObservation(event.getProjectId(), ref, content, importance);
        }
    }

    /**
     * 经历记忆内容：『【事件经历】标题：内容首行』（精炼，前缀对齐演化归档的【演化事件】约定）。
     */
    static String experienceText(ActorEvent event) {
        String title = event.getTitle() == null || event.getTitle().isBlank()
                ? "事件" : event.getTitle().trim();
        String body = firstLine(event.getContent());
        String text = "【事件经历】" + title + (body.isBlank() || body.equals(title) ? "" : "：" + body);
        return truncate(text, CONTENT_MAX);
    }

    // ==================== 项目级大事记（，修） ====================

    /**
     * 项目级大事记：重大世界事件（kind=world/evolution）重要度≥阈值 → 写 characterId=null 项目级记忆
     * （经既有 memoryContext 项目级注入【世界大事记】出现，所有角色知晓）。
     */
    private void recordProjectChronicle(ActorEvent event) {
        int importance = event.getImportance() == null ? 3 : event.getImportance();
        if (importance < projectEventImportanceThreshold) {
            return;
        }
        String kind = event.getKind();
        if (!"world".equals(kind) && !"evolution".equals(kind)) {
            return;
        }
        agentMemoryService.recordProjectMemory(event.getProjectId(), chronicleText(event), importance);
    }

    /**
     * 大事记内容：『标题：内容』（完整事件正文，供【世界大事记】叙述；截断控制 token）。
     */
    static String chronicleText(ActorEvent event) {
        String title = event.getTitle() == null || event.getTitle().isBlank()
                ? "世界事件" : event.getTitle().trim();
        String content = event.getContent() == null || event.getContent().isBlank()
                ? "" : event.getContent().trim();
        String text = content.isBlank() ? title
                : (content.startsWith(title) ? content : title + "：" + content);
        return truncate(text, CONTENT_MAX);
    }

    // ==================== 私有辅助 ====================

    /**
     * 取正文首行（精炼展示；空返回空串）。
     */
    private static String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.trim().split("\\n")[0].trim();
    }

    /**
     * 截断到指定长度（null/空白返回原样）。
     */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
