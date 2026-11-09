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

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.player.PlayerCharacterService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import com.holzyn.actor.domain.world.dialog.model.DialogDigestRequest;
import com.holzyn.actor.domain.world.dialog.model.DialogRound;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话回写世界（domain/world/dialog）。
 *
 * <p><b>每轮做三件事</b>：① 落一条 {@code kind=dialog} 事件（sourceKey 按最后一条消息 ID 幂等，
 * 根事件 correlation 由总线回填自身 ID）；② 把参与者坐标对齐到会话地点（写入口
 * {@code NpcPositionService}）；③ 记录轮末情境供 after_dialog 决策注入。</p>
 *
 * <p><b>时间成本</b>：旧对话在暂停态逐轮拨 2 游戏分钟；新对谈单聊处于冻结态，轮中不拨钟，
 * 关闭时按轮数一次性补推（见 {@link ConversationTimeCostService}）。</p>
 *
 * <p><b>批量抽取</b>：玩家会话每 {@code extract-batch-rounds}（默认 6）轮触发一次
 * {@code dialog_digest}（记忆 + claims 一次 AI 双输出）；无玩家自主群聊只在剧集收尾抽一次。
 * 抽取在虚拟线程异步执行，失败只 WARN，不阻断聊天。</p>
 *
 * <p><b>开关</b>：{@code world-loop.enabled=false} 时全部 no-op；{@code dialog.enabled=false}
 * 时也不写事件/位置/时间（严格关开关回归）。</p>
 */
@Slf4j
@Service
public class DialogWorldService {

    /** 事件内容摘要上限。 */
    static final int EVENT_SUMMARY_MAX = 300;

    /** 事件标题地点名上限。 */
    static final int LOCATION_TITLE_MAX = 40;

    @Autowired(required = false)
    private WorldLoopProperties properties;

    @Autowired(required = false)
    private WorldEventBus worldEventBus;

    @Autowired(required = false)
    private PlayerCharacterService playerCharacterService;

    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    @Autowired(required = false)
    private DialogPositionSyncService positionSyncService;

    @Autowired(required = false)
    private ConversationTimeCostService timeCostService;

    @Autowired(required = false)
    private DialogSituationBuffer situationBuffer;

    @Autowired(required = false)
    private DialogDigestService digestService;

    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 会话 → 玩家侧轮数（新对谈关闭补推时间；旧对话逐轮拨钟不依赖它）。 */
    private final Map<String, Integer> rounds = new ConcurrentHashMap<>();

    /** 会话 → 最近一次 dialog 事件 ID（digest 事实日志的 source_event_id）。 */
    private final Map<String, Long> lastEventIds = new ConcurrentHashMap<>();

    /** 会话 → 最近一轮快照（关闭补抽时 participantRefs 不能丢）。 */
    private final Map<String, DialogRound> lastRounds = new ConcurrentHashMap<>();

    /** 已抽取幂等键（sessionKey:lastMessageId）。 */
    private final Set<String> digested = ConcurrentHashMap.newKeySet();

    /** 上次抽取尝试的轮次（失败后每 6 轮再试，避免每轮重试烧 AI）。 */
    private final Map<String, Integer> digestAttemptRound = new ConcurrentHashMap<>();

    /** 异步抽取线程池（虚拟线程，不阻塞 SSE/编排）。 */
    private final ExecutorService digestExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 开关是否生效（总开关 + dialog 段）。
     *
     * @return true=启用
     */
    public boolean enabled() {
        return properties != null && properties.isEnabled()
                && properties.getDialog() != null && properties.getDialog().isEnabled();
    }

    /**
     * 玩家侧一轮对话（新对谈/旧对话单聊与群聊共用）。
     *
     * @param round 本轮快照
     */
    public void onPlayerRound(DialogRound round) {
        if (!enabled() || round == null || round.getProjectId() == null || round.getSessionId() == null) {
            return;
        }
        String key = sessionKey(round);
        round.setSessionKey(key);
        try {
            enrichPlayer(round);
            ActorEvent event = publish(round, key);
            if (event != null && event.getId() != null) {
                lastEventIds.put(key, event.getId());
            }
            lastRounds.put(key, round);
            if (positionSyncService != null) {
                long gs = gameSecond(round);
                positionSyncService.sync(round.getProjectId(), round.getLocationId(), round.getLocationName(),
                        round.getParticipantRefs(), gs);
            }
            if (situationBuffer != null) {
                situationBuffer.remember("conversation:" + round.getSessionId(),
                        round.getSummary(), presentNames(round));
            }
            int n = rounds.merge(key, 1, Integer::sum);
            if (round.isFrozen()) {
                // 冻结态：轮中不动世界钟，关闭时按轮数补推
                log.debug("[对话回写] 冻结会话轮次累计：session={} rounds={}", key, n);
            } else if (timeCostService != null) {
                timeCostService.onPlayerRound(round.getProjectId());
            }
            maybeDigest(round, key, event == null ? null : event.getId(), false);
        } catch (Exception e) {
            log.warn("[对话回写] 本轮处理失败（fail-open，不影响对话）: project={} session={} : {}",
                    round.getProjectId(), key, e.getMessage());
        }
    }

    /**
     * 无玩家自主群聊的一轮：只发 dialog 事件（不进玩家 6 轮抽取；剧集结束单独抽）。
     *
     * @param round 本轮快照（autonomous=true, roundIndex=beat）
     */
    public void onAutonomousRound(DialogRound round) {
        if (!enabled() || round == null || round.getProjectId() == null || round.getSessionId() == null) {
            return;
        }
        String key = sessionKey(round);
        try {
            ActorEvent event = publish(round, key);
            if (event != null && event.getId() != null) {
                lastEventIds.put(key, event.getId());
            }
        } catch (Exception e) {
            log.warn("[对话回写] 自主群聊事件发布失败（不影响闲聊）: project={} session={} : {}",
                    round.getProjectId(), key, e.getMessage());
        }
    }

    /**
     * 新对谈会话关闭：补推冻结态时间成本 + 最后一次批量抽取。
     *
     * @param projectId 项目 ID
     * @param channel   链路（dialogue/conversation）
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param frozen    是否冻结态会话
     */
    public void onSessionClosed(Long projectId, String channel, Long sessionId, Long userId, boolean frozen) {
        if (!enabled() || projectId == null || sessionId == null) {
            return;
        }
        String key = channel + ":" + sessionId;
        int n = rounds.remove(key);
        DialogRound prev = lastRounds.remove(key);
        if (frozen && n > 0 && timeCostService != null) {
            timeCostService.onSessionClosed(projectId, n, channel);
        }
        Long eventId = lastEventIds.remove(key);
        digestAttemptRound.remove(key);
        DialogRound round = DialogRound.builder()
                .channel(channel).projectId(projectId).userId(userId).sessionId(sessionId)
                .sessionKey(key)
                // 关闭补抽必须带上最近一轮的参与者/地点，否则 digest 因参与者为空直接跳过
                .participantRefs(prev == null ? null : prev.getParticipantRefs())
                .playerRef(prev == null ? null : prev.getPlayerRef())
                .locationId(prev == null ? null : prev.getLocationId())
                .locationName(prev == null ? null : prev.getLocationName())
                // 关闭补抽沿用最近一轮 lastMessageId：既让 dialog:{msgId}:op:{i} 幂等键可追溯，
                // 也避免与已达 6 轮时同一条消息的 digest 重复落事实。
                .lastMessageId(prev == null ? null : prev.getLastMessageId())
                .gameSecond(currentGameSecond(projectId)).build();
        maybeDigest(round, key, eventId, true);
    }

    /**
     * 无玩家自主群聊剧集收尾：抽一次 claims/记忆。
     *
     * @param round 剧集快照（sessionId=会话 ID, participantRefs, locationName, gameSecond）
     */
    public void onAutonomousEpisodeEnd(DialogRound round) {
        if (!enabled() || round == null || round.getProjectId() == null || round.getSessionId() == null) {
            return;
        }
        String key = sessionKey(round);
        // 收尾事件（archiveEpisode）可能已落 kind=roleplay/evolution；这里用已记录的 dialog 事件 ID
        maybeDigest(round, key, lastEventIds.get(key), true);
    }

    // ==================== 事件/位置/情境 ====================

    /** 发布 dialog 事件（失败返回 null，不阻断）。 */
    ActorEvent publish(DialogRound round, String key) {
        if (worldEventBus == null) {
            return null;
        }
        String summary = truncate(round.getSummary(), EVENT_SUMMARY_MAX);
        String title = "对话 · " + truncate(locationOf(round), LOCATION_TITLE_MAX);
        String sourceKey = sourceKey(round);
        EventSpec spec = EventSpec.builder()
                .kind("dialog")
                .source("dialog")
                .visibility(visibility(round))
                .title(title)
                .content(summary)
                .characterRefs(round.getParticipantRefs())
                .locationId(round.getLocationId())
                .locationName(round.getLocationName())
                .importance(round.getImportance() == null ? 2 : round.getImportance())
                .gameHour(gameHour(round))
                .sourceKey(sourceKey)
                .build();
        ActorEvent event = worldEventBus.publish(round.getUserId(), round.getProjectId(), spec);
        if (worldLoopPropertiesDebug()) {
            log.debug("[对话回写] dialog 事件: key={} id={}", sourceKey, event == null ? null : event.getId());
        }
        return event;
    }

    /** 玩家角色与参与者补全（幂等创建玩家行；地点缺失回退第一个 NPC 的所在地）。 */
    private void enrichPlayer(DialogRound round) {
        List<String> refs = round.getParticipantRefs() == null
                ? new ArrayList<>() : new ArrayList<>(round.getParticipantRefs());
        if ((round.getPlayerRef() == null || round.getPlayerRef().isBlank())
                && playerCharacterService != null && round.getUserId() != null) {
            try {
                ActorCharacter player = playerCharacterService
                        .ensurePlayerCharacter(round.getProjectId(), round.getUserId());
                if (player != null && player.getId() != null) {
                    round.setPlayerRef(NpcRefs.of(player.getId()));
                }
            } catch (Exception e) {
                log.debug("[对话回写] 玩家角色解析失败（事件不含玩家）: {}", e.getMessage());
            }
        }
        if (round.getPlayerRef() != null && !round.getPlayerRef().isBlank()) {
            refs.add(round.getPlayerRef());
        }
        round.setParticipantRefs(new ArrayList<>(new LinkedHashSet<>(refs)));

        if ((round.getLocationName() == null || round.getLocationName().isBlank())) {
            ActorCharacter first = firstCharacter(refs);
            if (first != null) {
                round.setLocationName(first.getLocation());
                if (round.getLocationId() == null) {
                    round.setLocationId(first.getLocationId());
                }
            }
        }
    }

    /** 参与者名字列表（情境/事件用）。 */
    private List<String> presentNames(DialogRound round) {
        List<String> names = new ArrayList<>();
        if (round.getParticipantRefs() == null) {
            return names;
        }
        for (String ref : round.getParticipantRefs()) {
            String name = nameOf(ref);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private ActorCharacter firstCharacter(List<String> refs) {
        if (characterRepository == null) {
            return null;
        }
        for (String ref : refs) {
            Long id = NpcRefs.idOf(ref);
            if (id == null) {
                continue;
            }
            try {
                ActorCharacter ch = characterRepository.findById(id).orElse(null);
                if (ch != null) {
                    return ch;
                }
            } catch (Exception ignored) {
                // 单个解析失败继续下一个
            }
        }
        return null;
    }

    private String nameOf(String ref) {
        ActorCharacter ch = firstCharacter(List.of(ref));
        return ch == null ? null : ch.getName();
    }

    // ==================== 抽取 ====================

    /** 达到批量轮次（或强制关闭）时异步抽取；已抽过的最后消息 ID 不重复。 */
    private void maybeDigest(DialogRound round, String key, Long eventId, boolean force) {
        if (digestService == null) {
            return;
        }
        if (round.isAutonomous() && !force) {
            return;
        }
        if (!round.isHasNewMessage() && !force) {
            return;
        }
        int batch = properties.getDialog().getExtractBatchRounds();
        int n = rounds.getOrDefault(key, 0);
        if (!force && n > 0 && n % Math.max(1, batch) != 0) {
            return;
        }
        if (force && n == 0 && !round.isHasNewMessage()) {
            return;
        }
        Long lastId = round.getLastMessageId();
        String digestKey = key + ":" + (lastId == null ? "close" : lastId);
        if (digested.contains(digestKey)) {
            return;
        }
        Integer attempted = digestAttemptRound.get(key);
        if (!force && attempted != null && attempted >= n) {
            return;
        }
        digestAttemptRound.put(key, n);
        DialogDigestRequest req = DialogDigestRequest.builder()
                .channel(round.getChannel())
                .projectId(round.getProjectId())
                .userId(round.getUserId())
                .sessionId(round.getSessionId())
                .sessionKey(key)
                .participantRefs(round.getParticipantRefs())
                .playerRef(round.getPlayerRef())
                .locationName(round.getLocationName())
                .gameSecond(gameSecond(round))
                .eventId(eventId)
                .lastMessageId(lastId)
                .newDialogue("dialogue".equals(round.getChannel()))
                .build();
        digestExecutor.execute(() -> {
            try {
                boolean done = digestService.digest(req);
                if (done) {
                    digested.add(digestKey);
                } else {
                    log.debug("[对话回写] digest 无落库（可能无承诺/记忆或 AI 失败）: key={}", digestKey);
                }
            } catch (Exception e) {
                log.warn("[对话回写] digest 异步执行失败（下次触发重试）: key={} : {}", digestKey, e.getMessage());
            }
        });
    }

    // ==================== 工具 ====================

    private String sessionKey(DialogRound round) {
        if (round.getSessionKey() != null && !round.getSessionKey().isBlank()) {
            return round.getSessionKey();
        }
        return (round.getChannel() == null ? "dialog" : round.getChannel()) + ":" + round.getSessionId();
    }

    /** 事件幂等键（autonomous 用 beat；其余用最后一条消息 ID；必须含 channel，防两套会话表 ID 撞车）。 */
    private String sourceKey(DialogRound round) {
        String channel = round.getChannel() == null ? "dialogue" : round.getChannel();
        if (round.isAutonomous()) {
            return "dialog:auto:" + channel + ":" + round.getSessionId() + ":"
                    + (round.getRoundIndex() == null ? 0 : round.getRoundIndex());
        }
        long last = round.getLastMessageId() == null ? 0L : round.getLastMessageId();
        return "dialog:round:" + channel + ":" + round.getSessionId() + ":" + last;
    }

    private String visibility(DialogRound round) {
        if (round.isAutonomous()) {
            return "location";
        }
        return properties.getDialog().isAnnounceToLocation() ? "location" : "private";
    }

    private String locationOf(DialogRound round) {
        return round.getLocationName() == null || round.getLocationName().isBlank()
                ? "此地" : round.getLocationName();
    }

    private long gameSecond(DialogRound round) {
        if (round.getGameSecond() != null) {
            return round.getGameSecond();
        }
        return currentGameSecond(round.getProjectId());
    }

    private long gameHour(DialogRound round) {
        if (round.getGameHour() != null) {
            return round.getGameHour();
        }
        return gameSecond(round) / 3600L;
    }

    private long currentGameSecond(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return 0L;
        }
        try {
            Long gs = worldClockService.currentEngineGameSecond(projectId);
            return gs == null ? 0L : gs;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private boolean worldLoopPropertiesDebug() {
        return log.isDebugEnabled();
    }
}
