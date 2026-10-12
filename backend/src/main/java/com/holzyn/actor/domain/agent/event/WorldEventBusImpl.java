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

package com.holzyn.actor.domain.agent.event;

import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 世界事件总线实现（B1，domain/agent/event）——自治环通信主干落地。
 * <p>职责：<ul>
 * <li><b>publish</b>：统一落 {@code actor_event}（project_id+source_key 幂等去重，修）→
 * 滚动保护（每项目最近 {@code events-max-per-project} 条，防 H2 膨胀）→
 * 广播（前端 SSE {@link WorldEventSseHub} + 引擎 WS 预留 {@link WorldEventWsHub} + 订阅者
 * {@link WorldEventSubscriber}，调试事件流与业务事件流同源消分叉 -5）；</li>
 * <li><b>query</b>：按项目 + 类别/来源/地点/角色 ref（可见性过滤 global/location/private）+
 * 游戏时间窗过滤——感知 /时间线/前端/引擎共用同一口径（修 时间线以 game_hour 排序）；</li>
 * <li><b>subscribe</b>：订阅者登记（旁路消费，异常隔离）。</li></ul>
 * 事件时刻统一用 {@code game_hour}（缺省由 {@link WorldClockService#currentGameHour} 从世界时钟解析，
 * 秒级展示派生 formatWorldTime(game_hour*3600)，R2 不另设秒级列）。</p>
 * <p>开关：{@code holzyn.actor.agent.events-enabled}（默认 true）；false 时 publish 返回 null
 * （行动/人群/事件注入回到 B1 前行为，演化归档/手动事件由调用方直写兜底）。</p>
 * <p>依赖方向（R1）：agent 域单向依赖既有域（world 时钟/事件仓库）；既有域仅经 publish 回调本总线。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldEventBusImpl implements WorldEventBus {

    /** 查询默认条数上限 */
    private static final int DEFAULT_QUERY_LIMIT = 100;
    /** 查询条数上限（防单次拉爆） */
    private static final int MAX_QUERY_LIMIT = 500;
    /** 单次滚动清理条数上限（防大事务） */
    private static final int MAX_RETENTION_DELETE_PER_RUN = 1000;

    // ==================== 收口：字段口径收敛到 ActorEventFields ====================
    // 白名单枚举与列宽上限原先是本类私有常量，导致**导入**与**时间线兜底直写**两条路径
    // 各自原样落库（可超长/可写脏枚举值）——外部数据反噬业务。现统一见
    // {@link com.holzyn.actor.common.ActorEventFields}，本类的归一化方法全部委托给它。

    private final ActorEventRepository eventRepository;
    private final WorldClockService worldClockService;
    private final WorldEventSseHub sseHub;
    private final WorldEventWsHub wsHub;

    /** 订阅者集合（旁路消费，并发安全） */
    private final List<WorldEventSubscriber> subscribers = new CopyOnWriteArrayList<>();

    /** 事件发布总开关（默认 true=B1 行为生效；false=回到 B1 前） */
    @Value("${holzyn.actor.agent.events-enabled:true}")
    private boolean eventsEnabled;

    /** 每项目事件保留上限（默认 5000；0=不限制） */
    @Value("${holzyn.actor.agent.events-max-per-project:5000}")
    private int eventsMaxPerProject;

    /**
     * 认知注意力（事件×NPC 相关度批量——事件发布时异步评估全 NPC 相关度，
     * 高相关 → 语义升档 + L1 记忆；AI 在虚拟线程执行，不占本事务连接；字段注入，构造器不变）。
     * <p><b>@Lazy 破循环</b>：注意力服务经状态服务/即时决策等最终依赖事件总线，本字段注入
     * 惰性代理即可打破 {@code worldEventBus → cognitiveAttentionService → … → worldEventBus}
     * 全环（调用发生在发布事务外的异步线程，惰性代理无感知）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.attention.CognitiveAttentionService cognitiveAttentionService;

    /**
     * 意外事件判定（accident 场景默认关仅调试开——事件发布时异步批量判定
     * 全 NPC 是否遭遇小意外；AI 在虚拟线程执行，不占本事务连接；字段注入，构造器不变）。
     * <b>@Lazy 破循环</b>：同 {@link #cognitiveAttentionService}（即时决策路径回依赖事件总线）。
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.evolution.AccidentJudge accidentJudge;

    @Override
    @Transactional
    public ActorEvent publish(Long userId, Long projectId, EventSpec spec) {
        if (!eventsEnabled) {
            log.debug("[事件总线] 发布开关关闭（events-enabled=false），跳过：project={} kind={}",
                    projectId, spec == null ? null : spec.getKind());
            return null;
        }
        if (projectId == null || spec == null) {
            return null;
        }
        // ① 幂等：project_id + source_key 已存在 → 返回已有事件，不重复发布（，同事务校验）
        String sourceKey = spec.getSourceKey();
        if (sourceKey != null && !sourceKey.isBlank()) {
            Optional<ActorEvent> existing = eventRepository.findTopByProjectIdAndSourceKey(projectId, sourceKey);
            if (existing.isPresent()) {
                log.debug("[事件总线] 幂等命中，跳过重复发布：project={} sourceKey={}", projectId, sourceKey);
                return existing.get();
            }
        }

        // ② 组装并落库
        ActorEvent event = new ActorEvent();
        event.setProjectId(projectId);
        event.setKind(normalizeKind(spec.getKind()));
        event.setSource(normalizeSource(spec.getSource()));
        event.setCharacterId(spec.getCharacterId());
        event.setCharacterIds(joinRefs(spec.getCharacterRefs(), spec.getCharacterId()));
        event.setSceneId(spec.getSceneId());
        event.setEvolutionId(spec.getEvolutionId());
        event.setLocationId(spec.getLocationId());
        event.setLocationName(truncate(spec.getLocationName(), 100));
        event.setVisibility(normalizeVisibility(spec.getVisibility()));
        event.setTitle(truncate(spec.getTitle(), 200));
        event.setContent(spec.getContent());
        event.setPayloadJson(spec.getPayloadJson());
        event.setImportance(normalizeImportance(spec.getImportance()));
        event.setGameHour(resolveGameHour(projectId, spec.getGameHour()));
        event.setSourceKey(truncate(sourceKey, 100));
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        event = eventRepository.save(event);

        // ③ 滚动保护：每项目事件数超限清理最旧（路线图风险表「每项目最近 5000 条」保护）
        enforceRetention(projectId);

        // ④ 广播（SSE + WS 预留 + 订阅者）
        broadcast(event);
        // ⑤ 认知注意力：事件×NPC 相关度批量——异步虚拟线程执行
        // （AI 调用在事务外，不占本事务连接；仅 importance≥2 的事件值得注意力评估；
        // 高相关 → 语义升档 focus + L1 感知记忆「此事与我有关」；失败零阻断）
        if (cognitiveAttentionService != null) {
            try {
                cognitiveAttentionService.eventRelevanceAsync(projectId, event);
            } catch (Exception e) {
                log.debug("[事件总线] 事件相关度批量触发失败（零阻断）: evt={} : {}", event.getId(), e.getMessage());
            }
        }
        // ⑥ 意外事件判定：accident 场景默认关仅调试开——异步批量判定全 NPC
        // 是否遭遇小意外（boolean 低概率采样；命中 → L1 记忆 + WORLD_EVENT 即时决策；零阻断）
        if (accidentJudge != null) {
            try {
                accidentJudge.onEvent(projectId, event);
            } catch (Exception e) {
                log.debug("[事件总线] 意外判定触发失败（零阻断）: evt={} : {}", event.getId(), e.getMessage());
            }
        }
        return event;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActorEvent> query(Long projectId, EventQuery query) {
        if (projectId == null) {
            return List.of();
        }
        EventQuery q = query == null ? EventQuery.recent(DEFAULT_QUERY_LIMIT) : query;
        int limit = q.limit() > 0 ? Math.min(q.limit(), MAX_QUERY_LIMIT) : DEFAULT_QUERY_LIMIT;
        return eventRepository.findByProjectIdOrderByIdDesc(projectId).stream()
                .filter(e -> matchKind(e, q.kind()))
                .filter(e -> matchSource(e, q.source()))
                .filter(e -> matchCharacter(e, q))
                .filter(e -> matchLocation(e, q))
                .filter(e -> matchGameHour(e, q.fromGameHour(), q.toGameHour()))
                .limit(limit)
                .toList();
    }

    @Override
    public void subscribe(WorldEventSubscriber subscriber) {
        if (subscriber != null && !subscribers.contains(subscriber)) {
            subscribers.add(subscriber);
        }
    }

    // ==================== 广播 ====================

    /**
     * 广播：前端 SSE（项目级）+ 引擎 WS 预留 + 订阅者（异常隔离，不影响落库）。
     */
    private void broadcast(ActorEvent event) {
        try {
            Map<String, Object> payload = WorldEventViews.toMap(event, worldClockService);
            sseHub.broadcast(event.getProjectId(), payload);
        } catch (Exception e) {
            log.warn("[事件总线] SSE 广播失败：event={} : {}", event.getId(), e.getMessage());
        }
        try {
            wsHub.publishWorldEvent(event);
        } catch (Exception e) {
            log.warn("[事件总线] WS 预留推送失败：event={} : {}", event.getId(), e.getMessage());
        }
        for (WorldEventSubscriber sub : subscribers) {
            try {
                sub.onEvent(event);
            } catch (Exception ignored) {
                // 订阅者异常不影响主广播
            }
        }
    }

    // ==================== 过滤与归一（静态纯逻辑，可单测） ====================

    /**
     * 类别过滤。
     */
    static boolean matchKind(ActorEvent e, String kind) {
        if (kind == null || kind.isBlank()) {
            return true;
        }
        return normalizeKind(e.getKind()).equals(normalizeKind(kind));
    }

    /**
     * 来源过滤。
     */
    static boolean matchSource(ActorEvent e, String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        return normalizeSource(e.getSource()).equals(normalizeSource(source));
    }

    /**
     * 地点过滤：<ul>
     * <li>角色视角查询（带 characterRef）时，locationId 仅作为「location 可见性在场匹配」的输入
     * （在 {@link #matchCharacter} 内处理），不做严格过滤——global/private 事件不受地点限制；</li>
     * <li>无角色视角时，locationId 为严格过滤：仅命中同地点事件（locationId 为 null 的事件视为不可定位，不命中）。</li></ul>
     */
    static boolean matchLocation(ActorEvent e, EventQuery q) {
        if (q == null || q.locationId() == null) {
            return true;
        }
        if (q.characterRef() != null && !q.characterRef().isBlank()) {
            return true; // 角色视角：location 匹配交给 matchCharacter
        }
        return e.getLocationId() != null && e.getLocationId().equals(q.locationId());
    }

    /**
     * 角色视角可见性过滤：
     * 不传 characterRef 全部可见；global 全可见；private 仅当事人；location 对当事人或（带 locationId 时）同地点在场者可见。
     */
    static boolean matchCharacter(ActorEvent e, EventQuery q) {
        if (q == null || q.characterRef() == null || q.characterRef().isBlank()) {
            return true;
        }
        String vis = normalizeVisibility(e.getVisibility());
        if ("global".equals(vis)) {
            return true;
        }
        boolean participant = e.getCharacterIds() != null && containsRef(e.getCharacterIds(), q.characterRef());
        if ("private".equals(vis)) {
            return participant;
        }
        // location：当事人 或 同地点在场者
        if (participant) {
            return true;
        }
        return q.locationId() != null && e.getLocationId() != null && e.getLocationId().equals(q.locationId());
    }

    /**
     * 游戏时间窗过滤（修 时间线/事件统一 game_hour 口径）。
     */
    static boolean matchGameHour(ActorEvent e, Long fromGameHour, Long toGameHour) {
        if (e.getGameHour() == null) {
            return true;
        }
        if (fromGameHour != null && e.getGameHour() < fromGameHour) {
            return false;
        }
        return toGameHour == null || e.getGameHour() <= toGameHour;
    }

    /**
     * kind 归一化：空白/非法回退 event（口径见 {@link com.holzyn.actor.common.ActorEventFields}）。
     */
    static String normalizeKind(String kind) {
        return com.holzyn.actor.common.ActorEventFields.normalizeKind(kind);
    }

    /**
     * source 归一化：空白/非法回退 manual（口径见 {@link com.holzyn.actor.common.ActorEventFields}）。
     */
    static String normalizeSource(String source) {
        return com.holzyn.actor.common.ActorEventFields.normalizeSource(source);
    }

    /**
     * visibility 归一化：空白/非法回退 location（缺省）。
     */
    static String normalizeVisibility(String visibility) {
        return com.holzyn.actor.common.ActorEventFields.normalizeVisibility(visibility);
    }

    /**
     * 事件重要度归一化（B4 /）：非法/空回退 3；越界钳制到 1~5。
     */
    static Integer normalizeImportance(Integer importance) {
        return com.holzyn.actor.common.ActorEventFields.normalizeImportance(importance);
    }

    /**
     * 参与角色 ref 拼接：characterRefs + characterId（special:{id}）去重合并，逗号分隔写入 character_ids。
     */
    static String joinRefs(List<String> refs, Long characterId) {
        List<String> all = new ArrayList<>();
        if (refs != null) {
            for (String r : refs) {
                if (r != null && !r.isBlank() && !all.contains(r.trim())) {
                    all.add(r.trim());
                }
            }
        }
        if (characterId != null) {
            String single = com.holzyn.actor.common.NpcRefs.of(characterId);
            if (!all.contains(single)) {
                all.add(single);
            }
        }
        return all.isEmpty() ? null : String.join(",", all);
    }

    /**
     * character_ids 文本是否包含指定 ref。
     */
    static boolean containsRef(String characterIds, String ref) {
        if (characterIds == null || ref == null) {
            return false;
        }
        String target = ref.trim();
        for (String part : characterIds.split(",")) {
            if (part != null && part.trim().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 游戏时刻解析：显式 gameHour 优先，缺省从世界时钟解析（统一时间口径）。
     * <p>（状态循环修复）：缺省回退<b>引擎推进位置</b>（currentEngineGameHour=
     * last_game_hour）而非实时流动时钟（currentGameHour）——流动时钟按速率×真实流逝插值，
     * 引擎停止/慢速时远超模拟位置（24h 压测实证事件时刻被推到 87 游戏天），经决策链流入
     * actor_state 造成状态冻结；引擎不可读时再回退流动时钟（最后手段，仅时间戳展示场景）。</p>
     */
    private Long resolveGameHour(Long projectId, Long gameHour) {
        if (gameHour != null) {
            return gameHour;
        }
        Long engine = worldClockService.currentEngineGameHour(projectId);
        if (engine != null) {
            return engine;
        }
        return worldClockService.currentGameHour(projectId);
    }

    /**
     * 滚动保护：每项目事件数超限清理最旧（eventsMaxPerProject<=0 不限制；单次最多清理 1000 防大事务）。
     */
    private void enforceRetention(Long projectId) {
        if (eventsMaxPerProject <= 0) {
            return;
        }
        long count = eventRepository.countByProjectId(projectId);
        if (count <= eventsMaxPerProject) {
            return;
        }
        long excess = count - eventsMaxPerProject;
        List<ActorEvent> oldest = eventRepository.findByProjectIdOrderByIdAsc(projectId);
        int toDelete = (int) Math.min(Math.min(excess, oldest.size()), MAX_RETENTION_DELETE_PER_RUN);
        if (toDelete <= 0) {
            return;
        }
        try {
            eventRepository.deleteAll(oldest.subList(0, toDelete));
            log.info("[事件总线] 滚动保护：project={} 超限 {} 条，清理最旧 {} 条", projectId, excess, toDelete);
        } catch (Exception e) {
            log.warn("[事件总线] 滚动保护清理失败：project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 字符串截断（标题/地点/幂等键字段长度约束；null/空白返回 null）。
     * <p>实现委托 {@link com.holzyn.actor.common.Texts#truncate}，
     * 与导入/时间线路径共用同一份列宽口径（如 {@code ActorEventFields.TITLE_MAX=200}）。</p>
     */
    private String truncate(String s, int max) {
        return com.holzyn.actor.common.Texts.truncate(s, max);
    }
}
