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

package com.holzyn.actor.domain.dev.service;

import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.perception.PerceptionService;
import com.holzyn.actor.domain.agent.runtime.StimulusLogService;
import com.holzyn.actor.domain.agent.thinking.PlanningService;
import com.holzyn.actor.domain.debug.service.DebugTraceService;
import com.holzyn.actor.domain.dialogue.service.DialogueMessageService;
import com.holzyn.actor.domain.dialogue.service.DialogueSessionService;
import com.holzyn.actor.domain.dialogue.vo.DialogueMessageVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueSessionVO;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.memory.vo.MemoryVO;
import com.holzyn.actor.domain.memory.vo.MemoryWorkingVO;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发者模式「NPC 全息详情」聚合服务。
 *
 * <p><b>为什么需要一个聚合接口</b>：一个角色「现在是什么状态」在数据上散落在 6 个模块——
 * 档案（actor_profile）、生理与情绪（actor_npc_state）、感知（PerceptionService 实时拼装）、
 * 目标（actor_character_goal）、决策链路（actor_ai_call_log 按 trace 聚合）、
 * 记忆（actor_memory + 核心块）、对话（actor_dialogue_*）。前端逐个拉会得到「6 个各自加载、
 * 各自报错、互相不等时」的页面；这里一次拉齐，**单块失败只降级该块**（写进 errors），
 * 整页不白屏——这正是一个「排查用」页面该有的行为。</p>
 *
 * <p><b>只读约定</b>：本服务全程不写库，也不触发任何 AI 调用（AGENTS §8.1.1 之外的额外保证：
 * 观测页面绝不能改变被观测的世界）。</p>
 *
 * <p>角色引用统一走 {@link NpcRefs}：对外是 {@code npc:{id}}，入参兼容
 * {@code special:}/{@code crowd:}/纯数字历史写法。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevNpcHologramService {

    /** 记忆块最多展示条数 */
    private static final int MEMORY_LIMIT = 30;

    /** 决策链路最多展示条数 */
    private static final int TRACE_LIMIT = 10;

    /** 对话会话最多展示条数 */
    private static final int SESSION_LIMIT = 5;

    /** 刺激日志最多展示条数 */
    private static final int STIMULUS_LIMIT = 30;

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final ActorMemoryRepository memoryRepository;
    private final NpcStateService npcStateService;
    private final PerceptionService perceptionService;
    private final PlanningService planningService;
    private final AgentMemoryService memoryService;
    private final CoreMemoryService coreMemoryService;
    private final DebugTraceService traceService;
    private final DialogueSessionService dialogueSessionService;
    private final DialogueMessageService dialogueMessageService;
    private final StimulusLogService stimulusLogService;
    private final WorldClockService worldClockService;
    private final ObjectMapper objectMapper;

    /**
     * 角色列表（特殊型 + 普通型，带状态摘要）。
     *
     * @param projectId 项目（世界）ID
     *
     * @return 角色行列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long projectId) {
        requireMounted(projectId);
        List<ActorCharacter> characters = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        Map<String, ActorNpcState> stateByRef = new LinkedHashMap<>();
        try {
            for (ActorNpcState st : npcStateService.listStates(projectId)) {
                if (st.getNpcRef() != null) {
                    stateByRef.put(st.getNpcRef(), st);
                }
            }
        } catch (Exception e) {
            log.debug("[开发者模式] 角色列表状态读取失败（仅影响摘要）：{}", e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActorCharacter c : characters) {
            String ref = NpcRefs.of(c.getId());
            ActorNpcState st = stateByRef.get(ref);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("ref", ref);
            row.put("name", c.getName());
            row.put("type", c.getType());
            row.put("typeLabel", NpcRefs.isCommonType(c.getType()) ? "普通型" : "特殊型");
            row.put("title", c.getTitle());
            row.put("occupation", c.getOccupation());
            row.put("age", c.getAge());
            row.put("stage", c.getStage());
            row.put("brainTier", c.getBrainTier());
            row.put("importance", c.getImportance());
            row.put("isProtagonist", c.getIsProtagonist());
            row.put("status", c.getStatus());
            row.put("location", c.getLocation());
            row.put("locationId", c.getLocationId());
            row.put("currentActivity", c.getCurrentActivity());
            if (st != null) {
                row.put("activityState", st.getActivityState());
                row.put("lastAction", st.getLastAction());
                row.put("urgencyLevel", st.getUrgencyLevel());
                row.put("urgencyText", st.getUrgencyText());
                row.put("mood", st.getMood());
                row.put("dominantEmotion", st.getDominantEmotion());
                row.put("emotionPleasure", st.getEmotionPleasure());
                row.put("emotionArousal", st.getEmotionArousal());
                row.put("emotionDominance", st.getEmotionDominance());
                row.put("hunger", st.getHunger());
                row.put("energy", st.getEnergy());
                row.put("hasState", true);
            } else {
                row.put("hasState", false);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * NPC 全息聚合（六个信息块；单块失败只降级该块）。
     *
     * @param projectId 项目（世界）ID
     *
     * @param rawRef 角色引用（npc:{id} / 历史前缀 / 纯数字）
     *
     * @param gameSecond 当前游戏时刻（秒；空则内部取引擎时钟）
     *
     * @return 聚合 Map（profile / state / perception / goals / decisions / memories / dialogues / stimuli / meta）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> hologram(Long projectId, String rawRef, Long gameSecond) {
        requireMounted(projectId);
        Long id = NpcRefs.parseLenient(rawRef);
        ActorCharacter character = id == null ? null
                : characterRepository.findByIdAndProjectId(id, projectId).orElse(null);
        if (character == null) {
            throw new com.holzyn.actor.common.BizException(404, "角色不存在或不属于该世界：" + rawRef);
        }
        String ref = NpcRefs.of(character.getId());
        long gs = gameSecond == null ? safeGameSecond(projectId) : gameSecond;

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        out.put("profile", block(errors, "profile", () -> profileOf(character, ref)));
        out.put("state", block(errors, "state", () -> stateOf(projectId, ref)));
        out.put("position", block(errors, "position", () -> positionOf(character, projectId, gs)));
        out.put("perception", block(errors, "perception", () -> perceptionOf(projectId, ref, gs)));
        out.put("goals", block(errors, "goals", () -> goalsOf(projectId, ref)));
        out.put("decisions", block(errors, "decisions", () -> decisionsOf(character)));
        out.put("memories", block(errors, "memories", () -> memoriesOf(projectId, ref)));
        out.put("dialogues", block(errors, "dialogues", () -> dialoguesOf(projectId, character, ref)));
        out.put("stimuli", block(errors, "stimuli", () -> stimuliOf(projectId, ref)));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("projectId", projectId);
        meta.put("ref", ref);
        meta.put("characterId", character.getId());
        meta.put("gameSecond", gs);
        meta.put("gameTimeText", safeGameTimeText(projectId, gs));
        meta.put("generatedAt", java.time.LocalDateTime.now().toString());
        meta.put("errors", errors);
        out.put("meta", meta);
        return out;
    }

    // ==================== 各信息块 ====================

    /**
     * 档案块：基础信息 + 11 维人格 JSON（解析成对象，前端可直接折叠展示）。
     */
    private Map<String, Object> profileOf(ActorCharacter c, String ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("ref", ref);
        m.put("name", c.getName());
        m.put("type", c.getType());
        m.put("typeLabel", NpcRefs.isCommonType(c.getType()) ? "普通型" : "特殊型");
        m.put("title", c.getTitle());
        m.put("detail", c.getDetail());
        m.put("avatarUrl", c.getAvatarUrl());
        m.put("gender", c.getGender());
        m.put("race", c.getRace());
        m.put("subRace", c.getSubRace());
        m.put("affiliation", c.getAffiliation());
        m.put("occupation", c.getOccupation());
        m.put("age", c.getAge());
        m.put("birthGameSecond", c.getBirthGameSecond());
        m.put("stage", c.getStage());
        m.put("brainTier", c.getBrainTier());
        m.put("isProtagonist", c.getIsProtagonist());
        m.put("importance", c.getImportance());
        m.put("status", c.getStatus());
        m.put("currentActivity", c.getCurrentActivity());
        m.put("location", c.getLocation());
        m.put("locationId", c.getLocationId());
        m.put("personaVersion", c.getPersonaVersion());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        m.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        m.put("persona", parseJson(c.getPersonaJson()));
        return m;
    }

    /**
     * 生理与情绪块（需求 / PAD / 主导情绪 / 紧急度 / 生理基线）。
     */
    private Map<String, Object> stateOf(Long projectId, String ref) {
        ActorNpcState st = stateRepository.findByProjectIdAndNpcRef(projectId, ref).orElse(null);
        if (st == null) {
            return Map.of("exists", false);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exists", true);
        m.put("npcType", st.getNpcType());
        m.put("activityState", st.getActivityState());
        m.put("lastAction", st.getLastAction());
        m.put("needs", needsOf(st));
        m.put("mood", st.getMood());
        m.put("health", st.getHealth());
        // 注意：Map.of 不接受 null 值——PAD 三门可能是历史库的空值，必须用 LinkedHashMap 装配
        Map<String, Object> pad = new LinkedHashMap<>();
        pad.put("pleasure", st.getEmotionPleasure());
        pad.put("arousal", st.getEmotionArousal());
        pad.put("dominance", st.getEmotionDominance());
        m.put("pad", pad);
        m.put("dominantEmotion", st.getDominantEmotion());
        m.put("emotionIntensity", st.getEmotionIntensity());
        Map<String, Object> expressed = new LinkedHashMap<>();
        expressed.put("pleasure", st.getExpressedPleasure());
        expressed.put("arousal", st.getExpressedArousal());
        expressed.put("dominance", st.getExpressedDominance());
        m.put("expressedPad", expressed);
        m.put("urgencyLevel", st.getUrgencyLevel());
        m.put("urgencyText", st.getUrgencyText());
        m.put("envModifier", st.getEnvModifier());
        m.put("gameSecond", st.getGameSecond());
        m.put("lastEatGameSecond", st.getLastEatGameSecond());
        m.put("lastSleepGameSecond", st.getLastSleepGameSecond());
        m.put("updatedAt", st.getUpdatedAt() == null ? null : st.getUpdatedAt().toString());
        m.put("physio", parseJson(st.getPhysioJson()));
        m.put("physioCoef", parseJson(st.getPhysioCoefJson()));
        m.put("dailyPlan", parseJson(st.getDailyPlanJson()));
        m.put("maskSnapshot", parseJson(st.getMaskSnapshotJson()));
        return m;
    }

    /**
     * 需求块（SCUM 式 8 项需求 + 中文档位）。
     */
    private Map<String, Object> needsOf(ActorNpcState st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hunger", need("饥饿", st.getHunger()));
        m.put("thirst", need("口渴", st.getThirst()));
        m.put("energy", need("精力", st.getEnergy()));
        m.put("bladder", need("膀胱", st.getBladder()));
        m.put("hygiene", need("清洁", st.getHygiene()));
        m.put("social", need("社交", st.getSocial()));
        m.put("fun", need("趣味", st.getFun()));
        m.put("comfort", need("舒适", st.getComfort()));
        return m;
    }

    /**
     * 单个需求值 + 档位文案（复用状态模块的档位口径，避免两处阈值）。
     *
     * @param name 需求中文名（与 {@link NpcStateService#needDescriptor(String, int)} 口径一致）
     *
     * @param value 需求值（0~100，可空）
     *
     * @return {value, label}
     */
    private Map<String, Object> need(String name, Integer value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", value == null ? "" : NpcStateService.needDescriptor(name, value));
        return m;
    }

    /**
     * 位置块（档案上的位置快照 + 感知到的地点名 + 当前活动）。
     */
    private Map<String, Object> positionOf(ActorCharacter c, Long projectId, long gameSecond) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", c.getLocation());
        m.put("locationId", c.getLocationId());
        m.put("currentActivity", c.getCurrentActivity());
        m.put("gameSecond", gameSecond);
        m.put("gameTimeText", safeGameTimeText(projectId, gameSecond));
        try {
            var perception = perceptionService.buildPerception(projectId, NpcRefs.of(c.getId()), gameSecond);
            if (perception != null) {
                m.put("perceivedLocationName", perception.locationName());
                m.put("ownState", perception.ownState());
            }
        } catch (Exception e) {
            m.put("perceivedLocationName", null);
        }
        return m;
    }

    /**
     * 感知块（当前感知到的世界：时间/地点/自身状态/可见事件/关系/目标/计划/知识）。
     */
    private Object perceptionOf(Long projectId, String ref, long gameSecond) {
        var vo = perceptionService.buildPerception(projectId, ref, gameSecond);
        Map<String, Object> m = new LinkedHashMap<>();
        if (vo == null) {
            return Map.of("available", false, "reason", "感知服务未返回内容（世界可能未挂载）");
        }
        m.put("available", true);
        m.put("gameHour", vo.gameHour());
        m.put("gameTimeText", vo.gameTimeText());
        m.put("locationName", vo.locationName());
        m.put("ownState", vo.ownState());
        m.put("visibleEvents", vo.visibleEvents());
        m.put("relationSummary", vo.relationSummary());
        m.put("goalsSummary", vo.goalsSummary());
        m.put("planSummary", vo.planSummary());
        m.put("knowledgeHints", vo.knowledgeHints());
        return m;
    }

    /**
     * 目标块（active/finished 全部目标 + 当前计划文本）。
     */
    private Object goalsOf(Long projectId, String ref) {
        List<ActorCharacterGoal> goals = planningService.listGoals(projectId, ref);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ActorCharacterGoal g : goals) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("title", g.getTitle());
            m.put("description", g.getDescription());
            m.put("status", g.getStatus());
            m.put("priority", g.getPriority());
            m.put("finishedAt", g.getFinishedAt() == null ? null : g.getFinishedAt().toString());
            m.put("createdAt", g.getCreatedAt() == null ? null : g.getCreatedAt().toString());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("count", items.size());
        try {
            out.put("planToday", planningService.planToday(projectId, ref));
        } catch (Exception e) {
            out.put("planToday", "");
        }
        return out;
    }

    /**
     * 决策块（最近决策链路：一次决策 = 感知→思考→行动→学习 的 trace）。
     */
    private Object decisionsOf(ActorCharacter c) {
        return traceService.characterTraces(c.getId(), TRACE_LIMIT);
    }

    /**
     * 记忆块（核心块 + L2 长期记忆 + 工作记忆；服务层越权校验失败时回退仓库直读）。
     */
    private Object memoriesOf(Long projectId, String ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("core", coreMemoryService.detail(projectId, ref));
        } catch (Exception e) {
            out.put("core", null);
        }
        try {
            PageResult<MemoryVO> page = memoryService.list(projectId, null, ref, 1, MEMORY_LIMIT);
            out.put("longTerm", page.getList());
            out.put("longTermTotal", page.getTotal());
        } catch (Exception e) {
            out.put("longTerm", fallbackMemories(projectId, ref));
            out.put("longTermTotal", -1);
        }
        try {
            PageResult<MemoryWorkingVO> working = memoryService.listWorking(projectId, null, ref, null, 1, MEMORY_LIMIT);
            out.put("working", working.getList());
        } catch (Exception e) {
            out.put("working", List.of());
        }
        return out;
    }

    /**
     * 记忆回退读法（绕过用户越权校验，只取该角色最新 N 条未删记忆）。
     */
    private List<Map<String, Object>> fallbackMemories(Long projectId, String ref) {
        List<ActorMemory> all = memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActorMemory m : all) {
            if (m.getCharacterRef() != null && !m.getCharacterRef().equals(ref)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("type", m.getType());
            row.put("content", m.getContent());
            row.put("importance", m.getImportance());
            row.put("version", m.getVersion());
            row.put("stale", m.isStale());
            row.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            rows.add(row);
            if (rows.size() >= MEMORY_LIMIT) {
                break;
            }
        }
        return rows;
    }

    /**
     * 对话块（该角色参与的最近会话 + 最新会话的最近消息）。
     */
    private Object dialoguesOf(Long projectId, ActorCharacter c, String ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<DialogueSessionVO> sessions = new ArrayList<>();
        for (DialogueSessionVO s : dialogueSessionService.list(projectId)) {
            String sr = s.characterRef();
            if (sr == null) {
                continue;
            }
            Long sid = NpcRefs.parseLenient(sr);
            if (sid != null && sid.equals(c.getId())) {
                sessions.add(s);
            }
        }
        sessions.sort(Comparator.comparing(DialogueSessionVO::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<DialogueSessionVO> top = sessions.size() > SESSION_LIMIT ? sessions.subList(0, SESSION_LIMIT) : sessions;
        out.put("sessions", top);
        out.put("sessionCount", sessions.size());
        if (!top.isEmpty()) {
            try {
                List<DialogueMessageVO> msgs = dialogueMessageService.list(projectId, top.get(0).id());
                int from = Math.max(0, msgs.size() - 20);
                out.put("recentMessages", msgs.subList(from, msgs.size()));
            } catch (Exception e) {
                out.put("recentMessages", List.of());
            }
        } else {
            out.put("recentMessages", List.of());
        }
        return out;
    }

    /**
     * 刺激块（该角色最近收到的刺激与护栏判定结果——「为什么这次没触发 AI」的证据）。
     */
    private Object stimuliOf(Long projectId, String ref) {
        List<StimulusLogService.Entry> all = stimulusLogService.recent(projectId, 200);
        List<StimulusLogService.Entry> mine = new ArrayList<>();
        for (StimulusLogService.Entry e : all) {
            if (ref.equals(e.getNpcRef())) {
                mine.add(e);
                if (mine.size() >= STIMULUS_LIMIT) {
                    break;
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StimulusLogService.Entry e : mine) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gameSecond", e.getGameSecond());
            m.put("realTime", e.getRealTime() == null ? null : e.getRealTime().toString());
            m.put("type", e.getType());
            m.put("stimulusText", e.getStimulusText());
            m.put("result", e.getResult());
            m.put("detail", e.getDetail());
            rows.add(m);
        }
        return rows;
    }

    // ==================== 工具 ====================

    /**
     * 校验世界已挂载（未挂载时角色表根本不在当前连接上）。
     *
     * <p>为什么必须显式判定：路由数据源在「未挂载」时指向目录库，而 {@code actor_profile}
     * 只存在于世界库——不判定就会把「世界没打开」变成一句「Table not found」的 500，
     * 让开发者以为服务坏了。给出 409 + 人话提示，页面直接告诉用户下一步做什么。</p>
     *
     * @param projectId 世界 ID
     */
    private void requireMounted(Long projectId) {
        if (projectId == null) {
            throw new com.holzyn.actor.common.BizException(400, "缺少世界 ID（projectId）");
        }
        try {
            worldClockService.requireClock(projectId);
        } catch (Exception e) {
            throw new com.holzyn.actor.common.BizException(409,
                    "世界 " + projectId + " 未挂载：请先在世界画廊里打开该世界，再回到开发者控制台");
        }
    }

    /**
     * 单块执行器：异常只记录不抛出（观测页面不能因为某一块坏了就整页白屏）。
     *
     * @param errors 错误收集器
     *
     * @param name 块名
     *
     * @param supplier 块内容
     *
     * @return 块内容（失败为 null）
     */
    private static Object block(List<Map<String, Object>> errors, String name, java.util.function.Supplier<Object> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("block", name);
            err.put("message", String.valueOf(e.getMessage()));
            errors.add(err);
            log.debug("[开发者模式] NPC 全息块 {} 读取失败：{}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 当前游戏时刻（秒）；时钟不可用返回 0（页面显示「未知时间」而不报错）。
     */
    private long safeGameSecond(Long projectId) {
        try {
            Long gs = worldClockService.currentEngineGameSecond(projectId);
            return gs == null ? 0L : gs;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 世界时间文本（失败返回空串）。
     */
    private String safeGameTimeText(Long projectId, long gameSecond) {
        try {
            return worldClockService.gameTimeTextOf(projectId, gameSecond);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * JSON 文本 → 对象（解析失败返回原文，避免「档案 JSON 坏了就什么都不显示」）。
     */
    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
