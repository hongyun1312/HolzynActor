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

package com.holzyn.actor.domain.agent.roleplay;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件→无玩家群聊回应轮订阅者（社会对话层修复 B 层，domain/agent/roleplay）。
 * <p><b>定位</b>： NPC 间自主对话的「事件回应轮」统一触发入口——世界事件总线每次
 * publish 后旁路消费（对齐 {@code LearningRelationEventSubscriber}/{@code AgentMemoryEventSubscriber}
 * 订阅者模式，遵守 R1 依赖方向 agent 域内部单向消费；总线对订阅者异常隔离，零阻断）。</p>
 * <p><b>为什么收敛到订阅者</b>：修复前事件回应轮仅两处直挂（跨日引擎事件 WorldEngine +
 * 手动/AI 注入事件 WorldEventService），情绪/关系/行动/成长等事件不触发——长跑第 1/2 轮实证
 * 「村里有事没人议论」。统一订阅后<b>任何</b>重要度≥阈值、带地点的事件都会在所在地点触发
 * 一轮 NPC 内群回应，形成「村里有事 → 有人议论」的常态因果。</p>
 * <p><b>过滤护栏</b>（防自触发/防高频打爆预算）：</p>
 * <ul>
 * <li>总开关 {@code autonomous-chat.enabled} + {@code event-response-enabled}（沿用既有）；</li>
 * <li>重要度 ≥ {@code event-importance-threshold}（默认 2：行动/情绪/成长等 imp≥2 都触发，
 * 低重要度琐事不扰动议论）；</li>
 * <li><b>kind=roleplay 跳过</b>（无玩家群聊自身发布的逐轮/收尾事件，防「议论→事件→议论」自触发环）；</li>
 * <li><b>kind=relation 跳过</b>（关系事件是闲聊的<b>输出</b>而非输入——群聊收尾的关系影响写回会
 * 发布 relation 事件，不跳过会形成「闲聊→关系→再闲聊」反馈环；口径对齐 LearningRelationEventSubscriber）；</li>
 * <li>必须带地点（locationId 或 locationName）——无地点事件无法定位 NPC 内群（runEpisode 也会跳过）；</li>
 * <li><b>同地点最小间隔</b>{@code event-response-min-gap-game-hours}（默认 1 游戏小时；B 层
 * ）——同一地点每小时至多议论一场，把「事件→议论」摊平到全天；</li>
 * <li><b>同地点人数前置</b>（B 层）——同地点合格 NPC &lt;2 时本轮本就不会开，
 * 直接跳过且<b>不占当日额度</b>（先查后记账，对齐 A 层「仅成功开轮才计额」）；</li>
 * <li>每日上限 {@code event-response-daily-cap}（默认 40/项目/游戏日，内存计数，
 * 兜底护栏——软上限，超出当日不再触发新的回应轮）。</li>
 * </ul>
 * <p><b>userId 解析</b>：事件实体不落 userId，经 {@link CurrentUserProvider} 解析（单用户回退 1L），
 * 对齐 RealTimeDecisionService 口径。</p>
 * <p>；依赖 agent/event 总线单向消费）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomousChatEventSubscriber implements WorldEventSubscriber {

    private final WorldEventBus worldEventBus;
    private final AutonomousChatService autonomousChatService;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /**
     * 总开关（与 AutonomousChatService.enabled 同源配置，默认 true；false=订阅者完全不介入，
     * 含不做每日额度记账）
     */
    @Value("${holzyn.actor.roleplay.autonomous-chat.enabled:true}")
    private boolean chatEnabled = true;

    /** 事件回应轮总开关（与 AutonomousChatService.eventResponseEnabled 同源配置，默认 true） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.event-response-enabled:true}")
    private boolean eventResponseEnabled;

    /** 事件回应轮最小事件重要度（默认 2；B 层 imp≥2 才触发所在地点回应轮） */
    @Value("${holzyn.actor.roleplay.autonomous-chat.event-importance-threshold:2}")
    private int eventImportanceThreshold = 2;

    /**
     * 事件回应轮每日上限（默认 40/项目/游戏日；B 层 成本护栏，防高频事件打爆预算；
     * B 层 冒烟调参 12 → 40：12 在开跑首 5 秒内即被一批行动事件烧光，此后整日沉默。
     * 真实节奏约束改由 {@link #eventResponseMinGapHours} 承担，本上限退回兜底角色）
     */
    @Value("${holzyn.actor.roleplay.autonomous-chat.event-response-daily-cap:40}")
    private int eventResponseDailyCap = 40;

    /**
     * 事件回应轮「同地点最小间隔」（游戏小时，默认 1；B 层 ——把事件→议论
     * 摊平到全天，每地点每游戏小时至多 1 场，杜绝一批事件瞬间烧光额度、其余时间全村沉默）
     */
    @Value("${holzyn.actor.roleplay.autonomous-chat.event-response-min-gap-game-hours:1}")
    private int eventResponseMinGapHours = 1;

    /**
     * 自触发/反馈环排除的 kind：roleplay=无玩家群聊自身输出（逐轮/收尾事件）；
     * relation=闲聊的关系影响写回输出（输出非输入，防「闲聊→关系→再闲聊」环）。
     */
    private static final List<String> SKIP_KINDS = List.of("roleplay", "relation");

    /** 事件回应轮每日计数：key=projectId:gameDay → 当日已触发数（内存软上限，重启清零可接受） */
    private final ConcurrentHashMap<String, Long> eventRoundsPerDay = new ConcurrentHashMap<>();

    /**
     * 事件回应轮「同地点最近一次议论的游戏小时」：key=projectId:地点键（id:{locationId} 或
     * name:{地点名}）→ 最近触发小时。B 层，用于 {@link #eventResponseMinGapHours}
     * 间隔判定（把议论摊平到全天）；按游戏日一并过期清理，重启清零可接受。
     */
    private final ConcurrentHashMap<String, Long> lastEventRoundHour = new ConcurrentHashMap<>();

    /**
     * 启动后注册为世界事件总线订阅者（与记忆/关系订阅者同机制，旁路消费）。
     */
    @PostConstruct
    void hookWorldEventBus() {
        worldEventBus.subscribe(this);
        log.info("[无玩家群聊] 事件回应轮订阅者已注册（importance≥{}、带地点、非 roleplay/relation、日上限 {}、同地点间隔 {} 游戏小时）",
                eventImportanceThreshold, eventResponseDailyCap, eventResponseMinGapHours);
    }

    /**
     * 事件回调：过滤（开关/重要度/kind/地点/同地点间隔/同地点人数/日上限）→ 异步触发所在地点事件回应轮。
     * 仅入队虚拟线程（triggerEventResponseAsync），不阻塞事件发布方事务；异常隔离不阻断主广播。
     * <p><b>B 层 冒烟修复（先查后记账）</b>：额度只在「同地点确有 ≥2 名合格 NPC、
     * 真能开出一轮议论」时才扣减——修复前以事件到达为依据先扣额度，实测 12 点额度里 9 点
     * 被「同地点仅 1 人」的轮次白烧，当日舆论随之中断。</p>
     *
     * @param event 已落库的世界事件
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!chatEnabled || !eventResponseEnabled || event == null || event.getProjectId() == null) {
            return;
        }
        try {
            Long projectId = event.getProjectId();
            int importance = event.getImportance() == null ? 3 : event.getImportance();
            if (importance < eventImportanceThreshold) {
                log.debug("[无玩家群聊] 事件回应轮跳过（重要度 {} < 阈值 {}）: event={}",
                        importance, eventImportanceThreshold, event.getId());
                return;
            }
            String kind = event.getKind();
            if (kind != null && SKIP_KINDS.contains(kind)) {
                log.debug("[无玩家群聊] 事件回应轮跳过（kind={} 自触发/反馈环排除）: event={}", kind, event.getId());
                return;
            }
            boolean hasLocation = event.getLocationId() != null
                    || (event.getLocationName() != null && !event.getLocationName().isBlank());
            if (!hasLocation) {
                log.debug("[无玩家群聊] 事件回应轮跳过（无地点，无法定位 NPC 内群）: event={}", event.getId());
                return;
            }
            Long gameHour = event.getGameHour();
            // ① 同地点最小间隔（B 层）：同一地点每小时至多议论一场，把节奏摊平到全天
            String locKey = projectId + ":" + locationKey(event);
            if (gameHour != null && eventResponseMinGapHours > 0) {
                Long last = lastEventRoundHour.get(locKey);
                if (last != null && gameHour - last < eventResponseMinGapHours) {
                    log.debug("[无玩家群聊] 事件回应轮跳过（同地点间隔不足 {} 游戏小时）: project={} 地点={} 事件={}",
                            eventResponseMinGapHours, projectId, event.getLocationName(), event.getId());
                    return;
                }
            }
            // ② 前置条件（先查后记账）：同地点合格 NPC <2 → 本轮本就不会开，不占当日额度
            if (!autonomousChatService.canTriggerEventResponse(projectId,
                    event.getLocationId(), event.getLocationName())) {
                log.debug("[无玩家群聊] 事件回应轮跳过（同地点不足 2 名 NPC，不占当日额度）: project={} 地点={} 事件={}",
                        projectId, event.getLocationName(), event.getId());
                return;
            }
            // ③ 每日软上限（gameHour 缺失时总线已按引擎位置解析，理论上恒有；仍缺失则只触发不计额）
            if (gameHour != null) {
                long gameDay = Math.floorDiv(gameHour, 24L);
                String key = projectId + ":" + gameDay;
                long count = eventRoundsPerDay.merge(key, 1L, Long::sum);
                if (count > Math.max(1, eventResponseDailyCap)) {
                    // 降噪（B 层）：达到上限只播报一次（首度过线 info），后续 debug——
                    // 前每起事件都打一行 info，实测 1 分钟刷 20+ 行无信息量日志
                    if (count == Math.max(1, eventResponseDailyCap) + 1L) {
                        log.info("[无玩家群聊] 事件回应轮已达当日上限 {}，本日起不再议论: project={} 地点={} 事件={}",
                                eventResponseDailyCap, projectId, event.getLocationName(), event.getId());
                    } else {
                        log.debug("[无玩家群聊] 事件回应轮跳过（当日已达上限 {}）: project={} 地点={} 事件={}",
                                eventResponseDailyCap, projectId, event.getLocationName(), event.getId());
                    }
                    return;
                }
                purgeStaleEventRounds(projectId, gameDay);
            }
            if (gameHour != null) {
                lastEventRoundHour.put(locKey, gameHour);
            }
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            log.info("[无玩家群聊] 事件回应轮触发（订阅者）: project={} 地点={} 重要度={} 事件={}",
                    projectId, event.getLocationName(), importance, shortText(event.getContent()));
            autonomousChatService.triggerEventResponseAsync(projectId, userId,
                    event.getLocationId(), event.getLocationName(), gameHour, event.getContent());
        } catch (Exception e) {
            log.warn("[无玩家群聊] 事件回应轮订阅失败（零阻断）: event={} : {}",
                    event == null ? null : event.getId(), e.getMessage());
        }
    }

    /**
     * 地点键（同地点间隔判定用）：locationId 优先（id:{id}）；无 id 退化地点名（name:{名}）。
     * 无地点事件已在 {@link #onEvent} 前置过滤，此处仅作防御性兜底。
     */
    private static String locationKey(ActorEvent event) {
        if (event.getLocationId() != null) {
            return "id:" + event.getLocationId();
        }
        String name = event.getLocationName();
        return "name:" + (name == null ? "" : name.trim());
    }

    /**
     * 清理本项目早于「前一个游戏日」的计数键（回应轮计数 + 同地点间隔键，保留当日与前一日，
     * 防日界并发误删）。
     */
    private void purgeStaleEventRounds(Long projectId, long currentDay) {
        try {
            String prefix = projectId + ":";
            List<String> stale = new ArrayList<>();
            for (String key : eventRoundsPerDay.keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String[] parts = key.split(":");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    long day = Long.parseLong(parts[1]);
                    if (day < currentDay - 1) {
                        stale.add(key);
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字日段（理论不可达），保留不清理
                }
            }
            for (String key : stale) {
                eventRoundsPerDay.remove(key);
            }
        } catch (Exception e) {
            log.debug("[无玩家群聊] 回应轮计数键清理失败（忽略）: {}", e.getMessage());
        }
    }

    /** 事件内容摘要截断上限（日志用）。 */
    private static final int SUMMARY_MAX = 120;

    /** 截断文本（null/空白返回原样）。 */
    private static String shortText(String s) {
        if (s == null || s.length() <= SUMMARY_MAX) {
            return s;
        }
        return s.substring(0, SUMMARY_MAX);
    }
}
