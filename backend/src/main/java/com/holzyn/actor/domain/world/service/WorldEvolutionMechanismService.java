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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.roleplay.RoleplayService;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorEvolution;
import com.holzyn.actor.domain.world.entity.ActorEvolutionParticipant;
import com.holzyn.actor.domain.world.entity.ActorEvolutionTurn;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.world.entity.ActorScene;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.ActorEvolutionParticipantRepository;
import com.holzyn.actor.domain.world.repository.ActorEvolutionRepository;
import com.holzyn.actor.domain.world.repository.ActorEvolutionTurnRepository;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorSceneRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 世界演化机制服务（拆壳，世界域能力提供方；旧 WorldEvolutionService 已物理删除）。
 * <p>职责：实现「场景化世界演化」引擎的世界域<b>机制</b>（创建/推进/加入退场/删除/流式播放循环/
 * 收尾归档/查询）——AI 编排（选角/逐轮编排/逐拍调度与生成）一律委托角色扮演组件
 * {@link RoleplayService}（六组件架构：AI 编排收敛于 agent 域，本服务为世界域机制承载）。</p>
 * <p>要点：</p>
 * <ul>
 * <li>① 创建/选角：手动指定或委托 {@link RoleplayService#selectEvolutionCast} AI 选角；</li>
 * <li>② 逐轮推进：委托 {@link RoleplayService#orchestrateEvolution} AI 编排（角色言行/环境/加入退场/收尾判定）；</li>
 * <li>③ 流式播放循环（群聊式连续演化）：每拍委托 {@link RoleplayService#scheduleEvolutionBeat} 选拍、
 * {@link RoleplayService#generateEvolutionBeat} 生成本拍，本服务负责播放循环/并发锁/加入退场落库；</li>
 * <li>④ 收尾归档：AI 生成事件（标题+内容）写 actor_event（时间线）+ 给每位参与者（含已退场）写
 * <b>角色级事实记忆</b>（仅当事人知道，非项目级）。</li>
 * </ul>
 * <p>数据来源：actor_evolution / participant / turn、actor_event、actor_memory、世界观、角色卡。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldEvolutionMechanismService {

    /** 归档事件生成输出最大 token */
    private static final int ARCHIVE_MAX_TOKENS = 1024;

    /** 归档角色级记忆的重要度（高，优先注入该角色对话） */
    private static final int ARCHIVE_MEMORY_IMPORTANCE = 4;

    /** 归档事件 AI 温度 */
    private static final double ARCHIVE_TEMPERATURE = 0.4;

    /** 流式并发锁：防止同一演化被多个播放流同时推进（单实例内存锁） */
    private final ConcurrentHashMap<Long, AtomicBoolean> streamLocks = new ConcurrentHashMap<>();

    private final ActorEvolutionRepository evolutionRepository;
    private final ActorEvolutionParticipantRepository participantRepository;
    private final ActorEvolutionTurnRepository turnRepository;
    private final ActorSceneRepository sceneRepository;
    private final ActorEventRepository eventRepository;
    private final ActorMemoryRepository memoryRepository;
    private final ActorCharacterRepository characterRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.persona.PersonaService personaService;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldClockService worldClockService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;
    private final WorldEventBus worldEventBus;
    private final ObjectMapper objectMapper;
    /** 角色扮演组件（接线：演化 AI 编排唯一入口，旧壳已拆；本服务为世界域机制承载） */
    private final RoleplayService roleplayService;

    /**
     * 世界帧服务（世界演化与游戏时间系统：场景化演化重构——三处注入当前世界帧「今日世界状态」
     * 作导演编排事实源 + 自动演绎背景组装；@Autowired(required=false) 字段注入避免改构造器[存量单测]，
     * null=未装配时世界帧注入降级为空段不阻断演化）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldFrameService worldFrameService;

    /** 演化最大轮次数（超过强制收尾，保证不会无限演化） */
    @Value("${holzyn.actor.evolution.max-turns:20}")
    private int maxTurns;

    // ==================== 创建 ====================

    /**
     * 开始一次世界演化。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @return 演化会话视图（含参与者）
     */
    @Transactional
    public Map<String, Object> start(Long userId, Long projectId, Map<String, Object> body) {
        requireProject(projectId);
        String mode = body == null || str(body.get("mode")) == null ? "manual" : str(body.get("mode"));
        Long requestedSceneId = asLong(body == null ? null : body.get("sceneId"));
        String requestedBackground = body == null ? null : str(body.get("background"));
        List<Long> requestedCharacterIds = asLongList(body == null ? null : body.get("characterIds"));

        // 用户指定了全部要素或显式 manual：直接用；否则 AI 自动选择缺失要素
        Long sceneId = requestedSceneId;
        String background = requestedBackground;
        String title = null;
        List<Long> characterIds = requestedCharacterIds;
        if ("ai".equals(mode) || characterIds == null || characterIds.isEmpty() || sceneId == null) {
            // 场景化演化重构：AI 选角注入当前世界帧「今日世界状态」（导演上帝视角事实源）
            Map<String, Object> ai = aiSelect(userId, projectId, requestedSceneId, requestedBackground,
                    requestedCharacterIds, frameSummary(projectId));
            sceneId = ai.get("sceneId") == null ? null : asLong(ai.get("sceneId"));
            if (background == null || background.isBlank()) background = str(ai.get("background"));
            if (title == null) title = str(ai.get("title"));
            if (characterIds == null || characterIds.isEmpty()) characterIds = asLongList(ai.get("characterIds"));
        }
        if (characterIds == null || characterIds.isEmpty()) {
            throw new BizException(400, "至少选择一位参与角色（或使用 AI 自动选择）");
        }
        if (background == null || background.isBlank()) {
            background = "默认情境：角色们因各自的原因聚集在这里，展开一段自然的故事。";
        }
        if (title == null || title.isBlank()) {
            title = sceneName(sceneId) + " · 世界演化";
        }

        // 校验角色属于项目
        for (Long cid : characterIds) {
            requireCharacterInProject(cid, projectId);
        }

        ActorEvolution evolution = new ActorEvolution();
        evolution.setProjectId(projectId);
        evolution.setSceneId(sceneId);
        evolution.setTitle(title.trim());
        evolution.setBackground(background.trim());
        evolution.setMode("ai".equals(mode) ? "ai" : "manual");
        evolution = evolutionRepository.save(evolution);

        for (Long cid : characterIds) {
            ActorEvolutionParticipant p = new ActorEvolutionParticipant();
            p.setEvolutionId(evolution.getId());
            p.setCharacterId(cid);
            p.setStatus("active");
            participantRepository.save(p);
        }
        // 初始系统消息
        addTurn(evolution.getId(), null, "system", "system",
                "演化开始：" + title + "\n场景：" + (sceneName(sceneId)) + "\n背景：" + background
                        + "\n在场角色：" + characterNames(characterIds));

        log.info("[演化] 开始：项目={} 演化={} 场景={} 角色={}", projectId, evolution.getId(),
                sceneName(sceneId), characterIds);
        return detail(evolution.getId());
    }

    /**
     * AI 自动选择场景/背景/角色（模式 ai 或缺失要素时）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param requestedSceneId 用户指定场景（可空）
     *
     * @param requestedBackground 用户指定背景（可空）
     *
     * @param requestedCharacterIds 用户指定角色（可空）
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」摘要
     */
    private Map<String, Object> aiSelect(Long userId, Long projectId, Long requestedSceneId,
                                         String requestedBackground, List<Long> requestedCharacterIds,
                                         String worldFrameSummary) {
        // 接线：演化 AI 选角恒委托角色扮演组件（旧壳 AI 体已删；六组件架构 AI 编排收敛 agent 域）
        return roleplayService.selectEvolutionCast(userId, projectId, requestedSceneId,
                requestedBackground, requestedCharacterIds, worldFrameSummary);
    }

    // ==================== 推进 ====================

    /**
     * 推进一轮世界演化（AI 编排角色言行 + 加入/退场 + 收尾判定）。
     *
     * @param userId 归属用户 ID
     *
     * @param evolutionId 演化会话 ID
     *
     * @return 本轮结果（turns/joins/leaves/finished/...）
     */
    @Transactional
    public Map<String, Object> turn(Long userId, Long evolutionId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        if (!"running".equals(evolution.getStatus())) {
            throw new BizException(400, "演化已结束，无法继续推进");
        }
        List<ActorEvolutionParticipant> active = participantRepository
                .findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .filter(p -> "active".equals(p.getStatus())).toList();
        if (active.isEmpty()) {
            throw new BizException(400, "场景内没有在场角色，无法推进（可加入角色或结束演化）");
        }
        int fromCount = evolution.getTurnCount() == null ? 0 : evolution.getTurnCount();
        long turnStartMs = System.currentTimeMillis();
        // 控制台日志（对齐 ChatService [对话] 格式）：推进开始，便于观察自动/手动推进节奏
        log.info("[演化] 任务开始：演化={} 第{}轮 在场角色={}个 标题={}", evolutionId, fromCount + 1,
                active.size(), evolution.getTitle());

        // 场景化演化重构：逐轮编排注入当前世界帧「今日世界状态」（导演上帝视角事实源）
        Map<String, Object> plan = orchestrate(userId, evolution, active, frameSummary(evolution.getProjectId()));
        List<Map<String, Object>> turns = castList(plan.get("messages"));
        for (Map<String, Object> t : turns) {
            Long cid = asLong(t.get("characterId"));
            if (cid == null) continue;
            String type = "text".equals(str(t.get("type"))) ? "text" : "action";
            String content = str(t.get("content"));
            if (content == null || content.isBlank()) continue;
            addTurn(evolutionId, cid, "assistant", type, content.trim());
        }
        // 场景环境变化
        String sceneEvent = str(plan.get("sceneEvent"));
        if (sceneEvent != null && !sceneEvent.isBlank()) {
            addTurn(evolutionId, null, "system", "system", "【环境】" + sceneEvent.trim());
        }

        // 加入/退场（带理由，有理有据）
        List<Map<String, Object>> joins = castList(plan.get("joins"));
        for (Map<String, Object> j : joins) {
            Long cid = asLong(j.get("characterId"));
            if (cid == null) continue;
            requireCharacterInProject(cid, evolution.getProjectId());
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolutionId, cid).orElseGet(() -> {
                        ActorEvolutionParticipant np = new ActorEvolutionParticipant();
                        np.setEvolutionId(evolutionId);
                        np.setCharacterId(cid);
                        return np;
                    });
            p.setStatus("active");
            p.setLeaveAt(null);
            participantRepository.save(p);
            String reason = str(j.get("reason"));
            addTurn(evolutionId, cid, "system", "system",
                    (charName(cid) + " 登场：" + (reason == null || reason.isBlank() ? "来到了现场。" : reason)));
        }
        List<Map<String, Object>> leaves = castList(plan.get("leaves"));
        for (Map<String, Object> l : leaves) {
            Long cid = asLong(l.get("characterId"));
            if (cid == null) continue;
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolutionId, cid).orElse(null);
            if (p != null && "active".equals(p.getStatus())) {
                p.setStatus("left");
                p.setLeaveAt(LocalDateTime.now());
                participantRepository.save(p);
                String reason = str(l.get("reason"));
                addTurn(evolutionId, cid, "system", "system",
                        (charName(cid) + " 退场：" + (reason == null || reason.isBlank() ? "离开了现场。" : reason)));
            }
        }

        // 轮次计数与收尾判定
        int nextCount = (evolution.getTurnCount() == null ? 0 : evolution.getTurnCount()) + 1;
        evolution.setTurnCount(nextCount);
        evolutionRepository.save(evolution);

        int remaining = (int) participantRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .filter(p -> "active".equals(p.getStatus())).count();
        boolean shouldFinish = shouldFinish(Boolean.TRUE.equals(plan.get("shouldFinish")), remaining, nextCount, maxTurns);
        String finishReason = str(plan.get("finishReason"));
        if (shouldFinish) {
            if (remaining <= 1 && (finishReason == null || finishReason.isBlank())) {
                finishReason = "场景中只剩下一位角色，对话难以继续，故事自然收束。";
            }
            if (nextCount >= maxTurns && (finishReason == null || finishReason.isBlank())) {
                finishReason = "本轮演化已达到剧情上限，就此收尾。";
            }
            addTurn(evolutionId, null, "system", "system",
                    "【收尾】" + (finishReason == null || finishReason.isBlank() ? "剧情自然收束。" : finishReason));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evolutionId", evolutionId);
        out.put("turnCount", nextCount);
        out.put("turns", turns);
        out.put("sceneEvent", sceneEvent);
        out.put("joins", joins);
        out.put("leaves", leaves);
        out.put("shouldFinish", shouldFinish);
        out.put("finishReason", finishReason);
        out.put("summary", str(plan.get("summary")));
        out.put("remainingCharacters", remaining);
        out.put("finished", false);
        if (shouldFinish) {
            Map<String, Object> archive = finish(userId, evolutionId);
            out.put("finished", true);
            out.put("archive", archive);
        }
        // 控制台日志（对齐 ChatService [对话] 格式）：推进结束，输出本轮统计便于调试
        log.info("[演化] 任务结束：演化={} 第{}轮 耗时={}ms 消息={}条 环境变化={} 加入={} 退场={} 收尾={} 摘要={}",
                evolutionId, nextCount, System.currentTimeMillis() - turnStartMs, turns.size(),
                sceneEvent == null || sceneEvent.isBlank() ? 0 : 1, joins.size(), leaves.size(),
                shouldFinish, str(plan.get("summary")));
        return out;
    }

    /**
     * AI 编排一轮（演化导演）。
     * <p>接线：演化 AI 编排<b>恒委托</b>角色扮演组件 {@link RoleplayService#orchestrateEvolution}
     * （六组件架构 AI 编排收敛 agent 域；旧壳 AI 体已随旧 WorldEvolutionService 物理删除，不再有回退分支）。
     * 编排注入当前世界帧摘要（worldFrameSummary），导演读世界帧作事实源。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param evolution 演化会话
     *
     * @param active 在场角色
     *
     * @param worldFrameSummary 当前世界帧「今日世界状态」摘要（可空）
     *
     * @return 编排计划（messages/sceneEvent/joins/leaves/shouldFinish/finishReason/summary）
     */
    private Map<String, Object> orchestrate(Long userId, ActorEvolution evolution,
                                            List<ActorEvolutionParticipant> active,
                                            String worldFrameSummary) {
        return roleplayService.orchestrateEvolution(userId, evolution.getProjectId(), evolution.getId(),
                evolution.getSceneId(), evolution.getBackground(),
                active.stream().map(ActorEvolutionParticipant::getCharacterId).toList(),
                worldFrameSummary);
    }

    /**
     * 解析 AI 编排输出（静态纯逻辑，可单测）。
     * <p>规则：只保留在场角色的 messages；空 messages 时用首位在场角色兜底一句推进；
     * joins/leaves 输出 {characterId, reason}；shouldFinish/finishReason/summary 透传。</p>
     *
     * @param json AI 输出文本
     *
     * @param activeIds 当前在场角色 ID 列表
     *
     * @return 编排计划 Map（无有效 JSON 返回 null）
     */
    static Map<String, Object> parsePlan(String json, List<Long> activeIds) {
        if (json == null) {
            return null;
        }
        try {
            String extracted = JsonUtil.extractJson(json);
            if (extracted == null) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(extracted);
            Map<String, Object> plan = new LinkedHashMap<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (JsonNode m : node.path("messages")) {
                Long cid = m.path("characterId").asLong(-1);
                if (cid <= 0 || activeIds == null || !activeIds.contains(cid)) {
                    continue;
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("characterId", cid);
                msg.put("type", "text".equals(m.path("type").asText("text")) ? "text" : "action");
                msg.put("content", m.path("content").asText(""));
                if (msg.get("content") != null && !String.valueOf(msg.get("content")).isBlank()) {
                    messages.add(msg);
                }
            }
            if (messages.isEmpty() && activeIds != null && !activeIds.isEmpty()) {
                messages.add(Map.of("characterId", activeIds.get(0), "type", "text", "content", "……"));
            }
            plan.put("messages", messages);
            plan.put("sceneEvent", node.path("sceneEvent").asText(""));
            plan.put("joins", nodeArrayOf(node, "joins"));
            plan.put("leaves", nodeArrayOf(node, "leaves"));
            plan.put("shouldFinish", node.path("shouldFinish").asBoolean(false));
            plan.put("finishReason", node.path("finishReason").asText(""));
            plan.put("summary", node.path("summary").asText(""));
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 joins/leaves 数组（静态，{characterId, reason}）。
     *
     * @param node JSON 节点
     *
     * @return 解析后的列表
     */
    private static List<Map<String, Object>> nodeArrayOf(JsonNode node, String field) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : node.path(field)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("characterId", item.path("characterId").asLong(-1));
            m.put("reason", item.path("reason").asText(""));
            out.add(m);
        }
        return out;
    }

    // ==================== 手动加入/退场 ====================

    /**
     * 手动加入一位角色（用户操作，系统消息说明）。
     *
     * @param userId 归属用户 ID
     *
     * @param evolutionId 演化会话 ID
     *
     * @param characterId 角色 ID
     */
    @Transactional
    public void join(Long userId, Long evolutionId, Long characterId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        if (!"running".equals(evolution.getStatus())) {
            throw new BizException(400, "演化已结束，无法加入角色");
        }
        requireCharacterInProject(characterId, evolution.getProjectId());
        ActorEvolutionParticipant p = participantRepository
                .findByEvolutionIdAndCharacterId(evolutionId, characterId).orElseGet(() -> {
                    ActorEvolutionParticipant np = new ActorEvolutionParticipant();
                    np.setEvolutionId(evolutionId);
                    np.setCharacterId(characterId);
                    return np;
                });
        p.setStatus("active");
        p.setLeaveAt(null);
        participantRepository.save(p);
        addTurn(evolutionId, characterId, "system", "system", charName(characterId) + " 受邀加入现场（玩家操作）。");
        log.info("[演化] 手动加入角色：演化={} 角色={}", evolutionId, characterId);
    }

    /**
     * 手动退场一位角色（用户操作，系统消息说明）。
     *
     * @param userId 归属用户 ID
     *
     * @param evolutionId 演化会话 ID
     *
     * @param characterId 角色 ID
     */
    @Transactional
    public void leave(Long userId, Long evolutionId, Long characterId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        if (!"running".equals(evolution.getStatus())) {
            throw new BizException(400, "演化已结束，无法操作角色");
        }
        ActorEvolutionParticipant p = participantRepository
                .findByEvolutionIdAndCharacterId(evolutionId, characterId)
                .orElseThrow(() -> new BizException(404, "该角色不在演化中"));
        if ("active".equals(p.getStatus())) {
            p.setStatus("left");
            p.setLeaveAt(LocalDateTime.now());
            participantRepository.save(p);
            addTurn(evolutionId, characterId, "system", "system", charName(characterId) + " 离开现场（玩家操作）。");
        }
        log.info("[演化] 手动退场角色：演化={} 角色={}", evolutionId, characterId);
    }

    /**
     * 删除演化会话。
     * <p>级联清理参与者与轮次消息；已归档的事件（时间线节点）保留（保留编年史，避免事件孤儿化）。
     * 演化正在播放中禁止删除。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param evolutionId 演化会话 ID
     */
    @Transactional
    public void delete(Long userId, Long evolutionId) {
        ActorEvolution evolution = requireEvolution(evolutionId, userId);
        AtomicBoolean lock = streamLocks.get(evolutionId);
        if (lock != null && lock.get()) {
            throw new BizException(400, "演化正在播放中，请先停止播放再删除");
        }
        // 级联清理：参与者 + 轮次消息 + 演化会话本体（归档事件保留在时间线）
        participantRepository.deleteByEvolutionId(evolutionId);
        turnRepository.deleteByEvolutionId(evolutionId);
        evolutionRepository.delete(evolution);
        streamLocks.remove(evolutionId);
        log.info("[演化] 删除：演化={} 项目={}", evolutionId, evolution.getProjectId());
    }

    // ==================== 群聊式连续演化 ====================

    /**
     * 连续演化流式播放（与群聊运行逻辑一致）：
     * <p>循环「调度 → 选最有发言/行动欲望的角色 → 该角色流式发言或行动 → 可选场景变化/加入退场」，
     * 一拍一拍持续推进，<b>不按轮次计算</b>。停止条件仅两个：
     * ① 用户手动停止（SSE 连接关闭）；② 场景只剩 1 名角色（自动收尾归档）。
     * 每个调度决策与每条发言/行动都通过 SSE 事件推给前端（schedule/message-start/token/done/system/finished/error）。</p>
     *
     * @param emitter SSE 发射器
     *
     * @param evolutionId 演化会话 ID
     *
     * @param userId 归属用户 ID（请求线程捕获传入，流式线程无 SecurityContext）
     *
     * @param alive 连接存活标志（超时/断开后置 false，循环据此停止）
     */
    public void stream(SseEmitter emitter, Long evolutionId, Long userId, AtomicBoolean alive) {
        ActorEvolution evolution = requireEvolution(evolutionId, userId);
        if (!"running".equals(evolution.getStatus())) {
            pushEventSafe(emitter, "error", Map.of("message", "演化已结束，无法继续播放"));
            emitter.complete();
            return;
        }
        // 并发互斥：同一演化只允许一个播放流推进（防多 Tab/双击重复推进）
        AtomicBoolean lock = streamLocks.computeIfAbsent(evolutionId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            pushEventSafe(emitter, "error", Map.of("message", "该演化已在播放中，请先停止"));
            emitter.complete();
            return;
        }
        long streamStart = System.currentTimeMillis();
        int beats = 0;
        log.info("[演化-流] 任务开始：演化={} 标题={}", evolutionId, evolution.getTitle());
        try {
            while (alive.get()) {
                // 停止条件①：场景只剩 1 名角色 → 自动收尾归档（用户需求：除手动停止或只剩一人外不停止）
                List<ActorEvolutionParticipant> active = participantRepository
                        .findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                        .filter(p -> "active".equals(p.getStatus())).toList();
                if (active.size() <= 1) {
                    log.info("[演化-流] 场景只剩 {} 名角色，自动收尾归档：演化={}", active.size(), evolutionId);
                    Map<String, Object> archive = finish(userId, evolutionId);
                    pushEventSafe(emitter, "finished", archive == null ? Map.of() : archive);
                    break;
                }
                if (!alive.get()) {
                    break;
                }
                // ① 调度：选发言人 + 决定对话/行动 + 可选场景变化/加入退场（一次 AI 调用）
                // 接线：逐拍调度恒委托角色扮演组件（六组件架构 AI 编排收敛 agent 域，旧壳已删）
                // 场景化演化重构：逐拍调度注入当前世界帧「今日世界状态」（导演上帝视角事实源）
                Map<String, Object> decision = roleplayService.scheduleEvolutionBeat(userId,
                        evolution.getProjectId(), evolution.getId(), evolution.getSceneId(),
                        evolution.getBackground(),
                        active.stream().map(ActorEvolutionParticipant::getCharacterId).toList(),
                        evolution.getTurnCount(), frameSummary(evolution.getProjectId()));
                if (decision == null || !alive.get()) {
                    break;
                }
                pushEvent(emitter, "schedule", decision);
                // ② 场景环境变化（低频：模型仅在有意义变化时输出）→ 系统消息
                String sceneEvent = str(decision.get("sceneEvent"));
                if (sceneEvent != null && !sceneEvent.isBlank()) {
                    String sys = "【环境】" + sceneEvent.trim();
                    addTurn(evolutionId, null, "system", "system", sys);
                    pushEvent(emitter, "system", Map.of("content", sys));
                }
                // ③ 加入/退场（低频、有理有据）→ 系统消息
                applyJoinsLeaves(userId, evolution, castList(decision.get("joins")), castList(decision.get("leaves")), emitter);
                // ④ 该角色发言/行动（流式，与群聊一致； 接线：本拍生成恒委托角色扮演组件）
                Long cid = asLong(decision.get("characterId"));
                if (cid != null) {
                    String beatType = "action".equals(str(decision.get("beatType"))) ? "action" : "text";
                    boolean ok = roleplayService.generateEvolutionBeat(emitter, evolution.getId(),
                            evolution.getProjectId(), evolution.getSceneId(), evolution.getBackground(),
                            cid, userId, beatType, alive, str(decision.get("reason")),
                            frameSummary(evolution.getProjectId()));
                    if (!ok) {
                        // 连接已断开或本拍生成失败：停止本流（避免对断开的连接继续推送/空转）
                        break;
                    }
                }
                // ⑤ 推进计数（节拍数，非轮次上限——演化不会因达到上限而停止）
                beats++;
                int nextCount = (evolution.getTurnCount() == null ? 0 : evolution.getTurnCount()) + 1;
                evolution.setTurnCount(nextCount);
                evolutionRepository.save(evolution);
            }
            if (alive.get()) {
                log.info("[演化-流] 任务结束：演化={} 共推进 {} 节拍 耗时={}ms", evolutionId, beats,
                        System.currentTimeMillis() - streamStart);
            } else {
                log.info("[演化-流] 任务被手动停止：演化={} 已推进 {} 节拍", evolutionId, beats);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            if (!errMsg.contains("SSE 连接已断开")) {
                log.warn("[演化-流] 任务异常：演化={} : {}", evolutionId, e.getMessage());
                pushEventSafe(emitter, "error", Map.of("message", friendlyEvolutionError(e)));
            }
        } finally {
            lock.set(false);
            emitter.complete();
        }
    }

    /**
     * 应用调度中的加入/退场（低频、有理有据），并推送系统消息。
     *
     * @param userId 归属用户 ID
     *
     * @param evolution 演化会话
     *
     * @param joins 加入列表
     *
     * @param leaves 退场列表
     *
     * @param emitter SSE 发射器
     */
    private void applyJoinsLeaves(Long userId, ActorEvolution evolution, List<Map<String, Object>> joins,
                                  List<Map<String, Object>> leaves, SseEmitter emitter) {
        for (Map<String, Object> j : joins) {
            Long cid = asLong(j.get("characterId"));
            if (cid == null) {
                continue;
            }
            requireCharacterInProject(cid, evolution.getProjectId());
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolution.getId(), cid).orElseGet(() -> {
                        ActorEvolutionParticipant np = new ActorEvolutionParticipant();
                        np.setEvolutionId(evolution.getId());
                        np.setCharacterId(cid);
                        return np;
                    });
            p.setStatus("active");
            p.setLeaveAt(null);
            participantRepository.save(p);
            String reason = str(j.get("reason"));
            String sys = charName(cid) + " 登场：" + (reason == null || reason.isBlank() ? "来到了现场。" : reason);
            addTurn(evolution.getId(), cid, "system", "system", sys);
            pushEvent(emitter, "system", Map.of("content", sys));
        }
        for (Map<String, Object> l : leaves) {
            Long cid = asLong(l.get("characterId"));
            if (cid == null) {
                continue;
            }
            ActorEvolutionParticipant p = participantRepository
                    .findByEvolutionIdAndCharacterId(evolution.getId(), cid).orElse(null);
            if (p != null && "active".equals(p.getStatus())) {
                p.setStatus("left");
                p.setLeaveAt(LocalDateTime.now());
                participantRepository.save(p);
                String reason = str(l.get("reason"));
                String sys = charName(cid) + " 退场：" + (reason == null || reason.isBlank() ? "离开了现场。" : reason);
                addTurn(evolution.getId(), cid, "system", "system", sys);
                pushEvent(emitter, "system", Map.of("content", sys));
            }
        }
    }

    /**
     * 推送 SSE 事件（连接断开时抛出 RuntimeException 终止循环）。
     *
     * @param emitter SSE 发射器
     *
     * @param name 事件名
     *
     * @param data 事件数据
     */
    private void pushEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            throw new RuntimeException("SSE 连接已断开", e);
        }
    }

    /**
     * 推送 SSE 事件（安全版：忽略连接断开异常，供开始/锁冲突等前置校验使用）。
     */
    private void pushEventSafe(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignored) {
            // 前端已断开时忽略推送失败
        }
    }

    /**
     * 生成失败的用户友好提示。
     *
     * @param e 异常
     *
     * @return 中文提示
     */
    private String friendlyEvolutionError(Exception e) {
        if (e instanceof BizException be) {
            return be.getMessage();
        }
        if (e instanceof com.holzyn.actor.ai.AiCallException ae) {
            return ae.getMessage();
        }
        return "演化播放失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
    }

    // ==================== 重大世界帧事件触发演绎 ====================

    /**
     * 重大世界帧事件触发演绎（主方案 §4.5：world_frame 事件重要度 ≥ 阈值时自动创建一场演化会话
     * 去「演绎」该事件，导演式呈现；由 {@link EvolutionDramatizeTrigger} 事件总线订阅者调用）。
     * <p>创建语义：标题=「【演绎】{事件标题}」；background=「演绎目标：
     * {事件内容}」+ 当前世界帧「今日世界状态」摘要；AI 选角（selectEvolutionCast，模式=ai，场景/角色
     * 由 AI 依事件与世界观选择）；创建即 running，用户可在演化页手动播放/推进；失败抛出由触发方隔离。</p>
     *
     * @param projectId 项目 ID
     *
     * @param event 触发的世界帧事件（kind=world_frame/source=engine，非空）
     *
     * @return 演化会话详情（含参与者）
     */
    @Transactional
    public Map<String, Object> startDramatization(Long projectId, ActorEvent event) {
        String title = "【演绎】" + (event.getTitle() == null || event.getTitle().isBlank()
                ? "世界帧事件" : event.getTitle().trim());
        StringBuilder background = new StringBuilder("演绎目标：");
        if (event.getContent() != null && !event.getContent().isBlank()) {
            background.append(event.getContent().trim());
        } else {
            background.append("重现并展开这场世界事件（事件详情缺失，请基于事件标题与当前世界状态编排）。");
        }
        String summary = frameSummary(projectId);
        if (!summary.isBlank()) {
            background.append("\n\n【当前世界状态】\n").append(summary);
        }
        log.info("[演化] 重大世界帧事件触发演绎：project={} event={} 标题={}", projectId, event.getId(), title);
        return start(currentUserProvider.currentUserId(), projectId,
                Map.of("mode", "ai", "title", title, "background", background.toString()));
    }

    // ==================== 结束归档 ====================

    /**
     * 结束演化并归档：AI 生成事件（标题+内容）写入 actor_event；给每位参与者（含已退场）
     * 写入<b>角色级事实记忆</b>（仅当事人知道，防止不相干角色知晓这段经历）。
     *
     * @param userId 归属用户 ID
     *
     * @param evolutionId 演化会话 ID
     */
    @Transactional
    public Map<String, Object> finish(Long userId, Long evolutionId) {
        // 显式 userId 归属校验：本方法会被演化流式线程调用（无 SecurityContext，不能走 requireEvolution(id)）
        ActorEvolution evolution = requireEvolution(evolutionId, userId);
        if ("finished".equals(evolution.getStatus()) && evolution.getEventId() != null) {
            return Map.of("event", eventVO(eventRepository.findById(evolution.getEventId()).orElse(null)),
                    "memoryCount", 0, "already", true);
        }
        // ① AI 生成归档事件
        String archiveJson = archiveEvent(userId, evolution);
        ActorEvent event = new ActorEvent();
        event.setProjectId(evolution.getProjectId());
        // 场景化演化重构（主方案 §4.5）：演化归档事件 kind 统一为 world_frame 子类
        // （source=evolution，保留 evolutionId 追溯——时间线与世界帧事件体系同类别）
        event.setKind("world_frame");
        event.setSceneId(evolution.getSceneId());
        event.setEvolutionId(evolution.getId());
        event.setSource("evolution");
        // 收口：AI 输出的标题长度不可控，而本方法在事件总线发布开关关闭时会
        // 用同一实体**直写落库**（绕过总线的截断），故此处按列宽截断（title 200）。
        event.setTitle(com.holzyn.actor.common.ActorEventFields.truncate(
                str(JsonUtil.extractField(archiveJson, "title")),
                com.holzyn.actor.common.ActorEventFields.TITLE_MAX));
        event.setContent(str(JsonUtil.extractField(archiveJson, "content")));
        if (event.getContent() == null || event.getContent().isBlank()) {
            event.setContent("（该次世界演化未留下明确记录）");
        }
        event.setGameHour(currentGameHour(evolution.getProjectId()));

        // B1（世界事件总线）：演化归档统一经 publish（含 visibility=global + 参与者 character_refs +
        // payload=归档摘要 + 幂等键 evolution:{id}，修断链2 归档侧）——时间线多出演化节点、事件流广播。
        // 事件发布开关关闭时 publish 返回 null → 直写落库兜底（演化归档必须持久化，不依赖开关）。
        List<ActorEvolutionParticipant> participants = participantRepository
                .findByEvolutionIdOrderByIdAsc(evolutionId);
        ActorEvent published = publishEvolutionEvent(userId, evolution, event, participants, archiveJson);
        event = published != null ? published : eventRepository.save(event);

        // ② 给每位参与者（含已退场）写角色级事实记忆（仅当事人，participants 已在上方取出）
        int memoryCount = 0;
        String memoryContent = "【演化事件】" + (event.getTitle() == null ? "" : event.getTitle() + "：")
                + event.getContent();
        for (ActorEvolutionParticipant p : participants) {
            ActorMemory memory = new ActorMemory();
            memory.setProjectId(evolution.getProjectId());
            memory.setCharacterId(p.getCharacterId());
            memory.setType(ActorMemoryTypes.TYPE_SEMANTIC);
            memory.setContent(memoryContent);
            memory.setImportance(ARCHIVE_MEMORY_IMPORTANCE);
            memoryRepository.save(memory);
            memoryCount++;
        }

        // ③ 更新演化状态
        evolution.setStatus("finished");
        evolution.setEventId(event.getId());
        evolution.setAiSummary(event.getContent());
        evolution.setFinishedAt(LocalDateTime.now());
        evolutionRepository.save(evolution);

        log.info("[演化] 结束归档：演化={} 事件={} 记忆={} 条（仅当事人）", evolutionId, event.getId(), memoryCount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", eventVO(event));
        out.put("memoryCount", memoryCount);
        return out;
    }

    /**
     * 演化归档发布到世界事件总线（B1）。
     * <p>（主方案 §4.5）：kind 统一为 <b>world_frame</b>子类、source=<b>evolution</b>（保留区分）、
     * visibility=global（大事记级，全项目知晓）、characterRefs=参与者（special:{id}，含已退场）、
     * sceneId/evolutionId 追溯保留、payload=归档 JSON（title/content）、game_hour 沿用归档时刻、
     * source_key=evolution:{evolutionId}（幂等，防重复归档发布）。
     * ⚠️ source=evolution 同时作为 EvolutionDramatizeTrigger 的防环依据（归档事件不触发演绎）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param evolution 演化会话
     *
     * @param event 已组装的归档事件实体（字段取自 archiveJson）
     *
     * @param participants 参与者列表（含已退场）
     *
     * @param archiveJson 归档 AI 输出 JSON（title/content）
     *
     * @return 发布成功的事件实体；开关关闭/失败返回 null（由调用方直写兜底）
     */
    private ActorEvent publishEvolutionEvent(Long userId, ActorEvolution evolution, ActorEvent event,
                                             List<ActorEvolutionParticipant> participants, String archiveJson) {
        try {
            List<String> refs = participants.stream()
                    .map(p -> com.holzyn.actor.common.NpcRefs.of(p.getCharacterId()))
                    .toList();
            return worldEventBus.publish(userId, evolution.getProjectId(), EventSpec.builder()
                    .kind("world_frame")
                    .source("evolution")
                    .characterRefs(refs)
                    .sceneId(evolution.getSceneId())
                    .evolutionId(evolution.getId())
                    .visibility("global")
                    .title(event.getTitle())
                    .content(event.getContent())
                    .payloadJson(archiveJson)
                    .importance(4)
                    .gameHour(event.getGameHour())
                    .sourceKey("evolution:" + evolution.getId())
                    .build());
        } catch (Exception e) {
            log.warn("[演化] 归档事件发布失败，直写兜底：演化={} : {}", evolution.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * AI 生成归档事件（标题+内容）。
     *
     * @param userId 归属用户 ID
     *
     * @param evolution 演化会话
     *
     * @return JSON 文本 {title, content}
     */
    private String archiveEvent(Long userId, ActorEvolution evolution) {
        List<ActorEvolutionTurn> turns = turnRepository.findByEvolutionIdOrderByIdAsc(evolution.getId());
        String transcript = turns.stream()
                .map(t -> (t.getCharacterId() == null ? "【" + typeLabel(t.getType()) + "】" : charName(t.getCharacterId()))
                        + "：" + t.getContent())
                .collect(Collectors.joining("\n"));
        if (transcript.length() > 6000) {
            transcript = transcript.substring(transcript.length() - 6000);
        }
        String prompt = """
                你是一位世界编年史官。请根据下列【演化经过】，将这次世界演化整理归档为一条「世界事件」，输出一个 JSON 对象，禁止输出其他文字。

                —— 输出结构 ——
                { "title": "事件标题（一句话，如：深夜咖啡馆里的密谈）", "content": "事件内容（120字内：时间地点、发生了什么、关键对话/行为、结果与后续）" }

                —— 要求 ——
                1. 只整理客观发生的事，不添加未发生的设定。
                2. content 要能让一个「当时在场的人」据此回忆并讲述，不必复述全部对话。

                —— 世界观 ——
                %s

                —— 演化经过 ——
                %s
                """.formatted(
                worldSettingRepository.findTopByProjectIdOrderByVersionDesc(evolution.getProjectId())
                        .map(s -> s.getFreeText()).orElse("（未提供）"),
                transcript.isBlank() ? "（无内容）" : transcript);

        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", "你只输出严格的 JSON，不输出任何其他文字。"),
                new AiChatRequest.ChatMessage("user", prompt)), ARCHIVE_TEMPERATURE, ARCHIVE_MAX_TOKENS, true);
        try {
            AiChatResult result = AiCallContext.run("world_event", null, () ->
                    aiChatGateway.chatCompletion(userId, evolution.getProjectId(), null, req));
            // A4 修复：用量 scene 与 trace scene（world_event）对齐
            usageLogService.record(userId, evolution.getProjectId(), null, result.providerId(), result.model(), "world_event",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            String json = JsonUtil.extractJson(result.content());
            if (json != null) {
                return json;
            }
        } catch (Exception e) {
            log.warn("[演化] 归档 AI 调用失败，使用摘要兜底: {}", e.getMessage());
        }
        // 兜底：用演化标题 + 最后一条摘要
        return "{\"title\":\"" + (evolution.getTitle() == null ? "世界演化" : evolution.getTitle())
                + "\",\"content\":\"" + (evolution.getBackground() == null ? "" : evolution.getBackground().replace("\"", ""))
                + "\"}";
    }

    // ==================== 查询 ====================

    /**
     * 项目演化会话列表（进行中优先）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @return 演化会话视图列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId, Long projectId) {
        requireProject(projectId);
        return evolutionRepository.findByProjectIdOrderByStatusAscIdDesc(projectId).stream()
                .map(this::summaryVO).toList();
    }

    /**
     * 演化会话详情（含参与者与轮次消息）。
     *
     * @param userId 归属用户 ID
     *
     * @param evolutionId 演化会话 ID
     *
     * @return 详情视图
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long evolutionId) {
        ActorEvolution evolution = requireEvolution(evolutionId);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", evolution.getId());
        vo.put("projectId", evolution.getProjectId());
        vo.put("sceneId", evolution.getSceneId());
        vo.put("sceneName", sceneName(evolution.getSceneId()));
        vo.put("title", evolution.getTitle());
        vo.put("background", evolution.getBackground());
        vo.put("mode", evolution.getMode());
        vo.put("status", evolution.getStatus());
        vo.put("turnCount", evolution.getTurnCount());
        vo.put("eventId", evolution.getEventId());
        vo.put("aiSummary", evolution.getAiSummary());
        vo.put("finishedAt", evolution.getFinishedAt());
        vo.put("createdAt", evolution.getCreatedAt());
        vo.put("participants", participantRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .map(p -> {
                    // 必须用可变 Map：leaveAt 对在场参与者为 null，Map.of 遇 null 值会抛 NPE
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("characterId", p.getCharacterId());
                    m.put("name", charName(p.getCharacterId()));
                    m.put("status", p.getStatus());
                    m.put("joinAt", p.getJoinAt());
                    m.put("leaveAt", p.getLeaveAt());
                    return m;
                })
                .toList());
        vo.put("turns", turnRepository.findByEvolutionIdOrderByIdAsc(evolutionId).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("characterId", t.getCharacterId());
                    m.put("characterName", t.getCharacterId() == null ? null : charName(t.getCharacterId()));
                    m.put("role", t.getRole());
                    m.put("type", t.getType());
                    m.put("content", t.getContent());
                    m.put("createdAt", t.getCreatedAt());
                    return m;
                })
                .toList());
        return vo;
    }

    /**
     * 演化会话摘要视图（列表用）。
     *
     * @param evolution 演化会话
     *
     * @return 摘要视图
     */
    private Map<String, Object> summaryVO(ActorEvolution evolution) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", evolution.getId());
        m.put("projectId", evolution.getProjectId());
        m.put("sceneId", evolution.getSceneId());
        m.put("sceneName", sceneName(evolution.getSceneId()));
        m.put("title", evolution.getTitle());
        m.put("mode", evolution.getMode());
        m.put("status", evolution.getStatus());
        m.put("turnCount", evolution.getTurnCount());
        m.put("eventId", evolution.getEventId());
        m.put("aiSummary", evolution.getAiSummary());
        m.put("finishedAt", evolution.getFinishedAt());
        m.put("createdAt", evolution.getCreatedAt());
        m.put("participantCount", participantRepository.findByEvolutionIdOrderByIdAsc(evolution.getId()).size());
        return m;
    }

    // ==================== 辅助 ====================

    /**
     * 收尾判定（静态纯逻辑，可单测）。
     * <p>规则：AI 判定收束 / 在场角色 ≤ 1 / 达到轮次上限（保证不会无限演化），任一成立即收尾。</p>
     *
     * @param aiFinish AI 是否判定剧情收束
     *
     * @param remaining 当前在场角色数
     *
     * @param turnCount 已推进轮次数
     *
     * @param maxTurns 轮次上限
     *
     * @return true 表示应开始收尾
     */
    static boolean shouldFinish(boolean aiFinish, int remaining, int turnCount, int maxTurns) {
        return aiFinish || remaining <= 1 || (maxTurns > 0 && turnCount >= maxTurns);
    }

    /**
     * 写入一条演化轮次消息。
     */
    private void addTurn(Long evolutionId, Long characterId, String role, String type, String content) {
        ActorEvolutionTurn turn = new ActorEvolutionTurn();
        turn.setEvolutionId(evolutionId);
        turn.setCharacterId(characterId);
        turn.setRole(role);
        turn.setType(type);
        turn.setContent(content);
        turn.setGameHour(currentGameHourOf(evolutionId));
        turnRepository.save(turn);
    }

    /**
     * 当前游戏时刻（小时数）。
     * <p>时间口径统一：委托 {@link WorldClockService#currentGameHour}（=引擎推进位置，
     * 世界权威时间），不再按真实锚点 × 速率流动换算。</p>
     */
    private Long currentGameHour(Long projectId) {
        try {
            return worldClockService.currentGameHour(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 演化会话的当前游戏时刻（通过演化查项目）。
     */
    private Long currentGameHourOf(Long evolutionId) {
        try {
            ActorEvolution ev = evolutionRepository.findById(evolutionId).orElse(null);
            return ev == null ? null : currentGameHour(ev.getProjectId());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前世界帧「今日世界状态」紧凑摘要。
     * <p>上帝视角直读 active 帧（WorldFrameService.activeFrameSummary，纯文本零 AI、总长截断 400）；
     * 无 active 帧 / 服务未装配 / 异常 → 返回空串（三处注入侧静默降级为空段，不阻断演化）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 紧凑摘要文本（可空串）
     */
    private String frameSummary(Long projectId) {
        if (worldFrameService == null || projectId == null) {
            return "";
        }
        try {
            return worldFrameService.activeFrameSummary(projectId);
        } catch (Exception e) {
            log.warn("[演化] 世界帧摘要获取失败（降级空段）：project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /**
     * 角色人设摘要（从角色卡提取关键信息；无卡时用档案 detail）。
     *
     * @param characterId 角色 ID
     *
     * @return 人设摘要文本
     */
    private String personaSummary(Long characterId) {
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null) return "";
        String card = (personaService == null ? null : personaService.latestPersonaJson(characterId).orElse(null));
        if (card != null && card != null) {
            try {
                JsonNode p = objectMapper.readTree(card);
                StringBuilder sb = new StringBuilder();
                sb.append("身份：").append(com.holzyn.actor.domain.growth.persona.PersonaBits.textOr(p, "无头衔", "dims", "identity", "identity_title"))

                        .append("；社会阶层：").append(com.holzyn.actor.domain.growth.persona.PersonaBits.textOr(p, "未知", "dims", "identity", "social_class"));

                sb.append("\n性格特质：").append(String.join("、", com.holzyn.actor.domain.growth.persona.PersonaBits.texts(p, "dims", "personality", "traits_summary")));

                sb.append("\n目标：").append(String.join("、", com.holzyn.actor.domain.growth.persona.PersonaBits.texts(p, "dims", "values", "life_goals")));

                sb.append("\n说话风格：").append(com.holzyn.actor.domain.growth.persona.PersonaBits.textOr(p, "自然", "dims", "voice", "register"));
                return sb.toString();
            } catch (Exception e) {
                // 忽略，降级
            }
        }
        return "档案：" + (ch.getDetail() == null ? "" : ch.getDetail());
    }

    /**
     * 组装场景背景（演化场景 + 演化背景叠加）。
     */
    private String buildSceneBackground(ActorEvolution evolution) {
        StringBuilder sb = new StringBuilder();
        if (evolution.getSceneId() != null) {
            sceneRepository.findById(evolution.getSceneId()).ifPresent(s -> {
                sb.append("地点：").append(s.getName());
                if (s.getDescription() != null) sb.append("（").append(s.getDescription()).append("）");
                if (s.getBackground() != null) sb.append("\n场景背景：").append(s.getBackground());
            });
        } else {
            sb.append("全局（世界）演化，无固定地点。");
        }
        if (evolution.getBackground() != null && !evolution.getBackground().isBlank()) {
            sb.append("\n本场设定：").append(evolution.getBackground());
        }
        return sb.toString();
    }

    /**
     * 场景名解析。
     *
     * @param sceneId 场景 ID（可空）
     *
     * @return 场景名（无则「全局」）
     */
    private String sceneName(Long sceneId) {
        if (sceneId == null) return "全局（世界）";
        return sceneRepository.findById(sceneId).map(ActorScene::getName).orElse("场景 " + sceneId);
    }

    /**
     * 角色名解析。
     *
     * @param characterId 角色 ID
     *
     * @return 角色名（无则「角色」）
     */
    private String charName(Long characterId) {
        if (characterId == null) return "系统";
        return characterRepository.findById(characterId).map(ActorCharacter::getName).orElse("角色 " + characterId);
    }

    /**
     * 角色名列表（中文顿号连接）。
     */
    private String characterNames(List<Long> ids) {
        return ids.stream().map(this::charName).collect(Collectors.joining("、"));
    }

    /**
     * 消息类型中文标签。
     */
    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "action" -> "行动";
            case "system" -> "系统";
            case "event" -> "事件";
            default -> "对话";
        };
    }

    /**
     * 事件视图。
     */
    private Map<String, Object> eventVO(ActorEvent e) {
        if (e == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("projectId", e.getProjectId());
        m.put("kind", e.getKind());
        m.put("title", e.getTitle());
        m.put("content", e.getContent());
        m.put("sceneId", e.getSceneId());
        m.put("evolutionId", e.getEvolutionId());
        m.put("source", e.getSource());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    /**
     * 查询演化会话（归属校验）。
     */
    private ActorEvolution requireEvolution(Long evolutionId) {
        ActorEvolution evolution = evolutionRepository.findById(evolutionId)
                .orElseThrow(() -> new BizException(404, "演化会话不存在"));
        requireProject(evolution.getProjectId());
        return evolution;
    }

    /**
     * 查询演化会话（显式 userId 归属校验，异步/流式线程安全）。
     *
     * @param evolutionId 演化会话 ID
     *
     * @param userId 归属用户 ID（请求线程捕获传入）
     *
     * @return 演化会话实体
     */
    private ActorEvolution requireEvolution(Long evolutionId, Long userId) {
        ActorEvolution evolution = evolutionRepository.findById(evolutionId)
                .orElseThrow(() -> new BizException(404, "演化会话不存在"));
        requireProject(evolution.getProjectId(), userId);
        return evolution;
    }

    /**
     * 校验项目归属当前用户。
     */
    private void requireProject(Long projectId) {
        requireProject(projectId, currentUserProvider.currentUserId());
    }

    /**
     * 校验项目归属指定用户（异步/流式线程用显式 userId 版本，规避无 SecurityContext 回退演示用户）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID
     */
    private void requireProject(Long projectId, Long userId) {
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 校验角色属于项目。
     */
    private void requireCharacterInProject(Long characterId, Long projectId) {
        characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                .orElseThrow(() -> new BizException(404, "角色不存在或无权访问"));
    }

    /**
     * Long 取值辅助。
     */
    private Long asLong(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Long 列表取值辅助。
     */
    private List<Long> asLongList(Object v) {
        if (v instanceof List<?> list) {
            List<Long> out = new ArrayList<>();
            for (Object o : list) {
                Long x = asLong(o);
                if (x != null) out.add(x);
            }
            return out;
        }
        return null;
    }

    /**
     * 字符串取值辅助。
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * List 转 Object 列表。
     */
    private List<Map<String, Object>> castList(Object v) {
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    m.forEach((k, val) -> map.put(String.valueOf(k), val));
                    out.add(map);
                }
            }
            return out;
        }
        return List.of();
    }
}
