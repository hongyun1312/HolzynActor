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

package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.vo.WorldClockVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对谈冻结语义服务（零 AI）。
 * <p>职责：进入对谈时把世界冻结到「当下」——<b>运行中自动暂停</b>（复用
 * {@link WorldClockService#updateClock} 的 paused 语义）+ <b>快照冻结时刻</b>
 * （权威引擎位置 + 角色冻结状态[位置/情绪] + 同地点在场快照 + 世界动态行）。
 * 聊天期间角色与世界状态一律以会话冻结快照为「当下」；离开保持暂停（手动恢复）。</p>
 * <p><b>fail-open</b>：暂停操作失败不阻断进入聊天（仅 WARN）；时钟/状态服务缺失时
 * 快照字段逐位降级（null/空串），会话仍可创建。</p>
 */
@Slf4j
@Service
public class DialogueFreezeService {

    /** 世界时钟服务（运行态读取 + 暂停；null=跳过暂停与时间快照） */
    @Lazy
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 统一 AI 上下文注入器（世界动态行=时间·季节·天气；null=跳过动态行） */
    @Lazy
    @Autowired(required = false)
    private AiContextInjector contextInjector;

    /** 角色主表仓库（角色名 + 同地点在场快照） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    /** NPC 状态服务（冻结角色状态=位置/情绪；null=跳过状态位） */
    @Lazy
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /**
     * 冻结快照（进入对谈时的世界与角色状态）。
     *
     * @param gameSecond        冻结时刻（权威世界时间秒；可能 null=时钟缺失）
     * @param worldDynamics     世界动态行（时间 · 季节 · 天气；可能空串）
     * @param characterName     对谈角色名
     * @param characterLocation 角色冻结位置
     * @param characterMood     角色冻结心情
     * @param presentRefs       同地点在场角色引用（M5 群聊场景用）
     * @param wasRunning        进入前世界是否运行中（诊断）
     */
    public record FreezeSnapshot(Long gameSecond, String worldDynamics, String characterName,
                                 String characterLocation, String characterMood, List<String> presentRefs,
                                 boolean wasRunning) {
    }

    /**
     * 进入对谈冻结：运行中自动暂停 + 快照冻结时刻/角色状态/在场。
     *
     * @param projectId    项目 ID
     * @param userId       当前用户 ID
     * @param characterRef 对谈角色（npc:{id}；单聊必填）
     *
     * @return 冻结快照（字段逐位降级 fail-open）
     */
    public FreezeSnapshot freeze(Long projectId, Long userId, String characterRef) {
        boolean wasRunning = false;
        // ① 读取运行态：paused / worldRunning
        if (worldClockService != null) {
            try {
                WorldClockVO clock = worldClockService.getClock(projectId, userId);
                wasRunning = clock != null
                        && Integer.valueOf(0).equals(clock.getPaused())
                        && Integer.valueOf(1).equals(clock.getWorldRunning());
            } catch (Exception e) {
                log.warn("[对谈冻结] 读取世界运行态失败（跳过）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ② 运行中 → 自动暂停（复用既有暂停能力；失败 fail-open 不阻断进入聊天）
        if (wasRunning && worldClockService != null) {
            try {
                worldClockService.updateClock(projectId, userId, new WorldClockDTO(null, true, null, null, null));
                log.info("[对谈冻结] 进入对谈自动暂停世界: project={}", projectId);
            } catch (Exception e) {
                log.warn("[对谈冻结] 自动暂停失败（fail-open 不阻断进入聊天）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ③ 快照：权威引擎位置 + 世界动态行 + 角色状态 + 在场
        Long gameSecond = worldClockService == null ? null : safeGameSecond(projectId);
        String worldDynamics = contextInjector == null ? "" : safeWorldDynamics(projectId);
        String characterName = characterNameOf(characterRef);
        String location = locationOf(projectId, characterRef);
        String mood = moodOf(projectId, characterRef);
        List<String> presentRefs = presentRefsOf(projectId, characterRef, location);
        return new FreezeSnapshot(gameSecond, worldDynamics, characterName, location, mood, presentRefs, wasRunning);
    }

    /** 权威引擎位置（读取失败返回 null）。 */
    private Long safeGameSecond(Long projectId) {
        try {
            return worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            log.debug("[对谈冻结] 冻结时刻读取失败（降级 null）: {}", e.getMessage());
            return null;
        }
    }

    /** 世界动态行（时间 · 季节 · 天气；读取失败空串）。 */
    private String safeWorldDynamics(Long projectId) {
        try {
            String line = contextInjector.worldDynamicsLine(projectId);
            return line == null ? "" : line;
        } catch (Exception e) {
            log.debug("[对谈冻结] 世界动态行读取失败（降级空串）: {}", e.getMessage());
            return "";
        }
    }

    /** 角色名（解析失败返回 null）。 */
    private String characterNameOf(String characterRef) {
        if (characterRepository == null || characterRef == null) {
            return null;
        }
        Long id = NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        try {
            return characterRepository.findById(id).map(ActorCharacter::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 角色冻结位置（读角色主表 location；读取失败返回 null）。 */
    private String locationOf(Long projectId, String characterRef) {
        if (characterRepository == null || characterRef == null) {
            return null;
        }
        Long id = NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        try {
            ActorCharacter ch = characterRepository.findById(id).orElse(null);
            return ch == null ? null : ch.getLocation();
        } catch (Exception e) {
            log.debug("[对谈冻结] 角色位置读取失败（降级 null）: {}", e.getMessage());
            return null;
        }
    }

    /** 角色冻结心情（读取失败返回 null）。 */
    private String moodOf(Long projectId, String characterRef) {
        ActorNpcState st = stateOf(projectId, characterRef);
        if (st == null) {
            return null;
        }
        return st.getDominantEmotion();
    }

    /** 角色状态（读取失败返回 null）。 */
    private ActorNpcState stateOf(Long projectId, String characterRef) {
        if (npcStateService == null || characterRef == null) {
            return null;
        }
        try {
            return npcStateService.stateFor(projectId, characterRef);
        } catch (Exception e) {
            log.debug("[对谈冻结] 角色状态读取失败（降级 null）: {}", e.getMessage());
            return null;
        }
    }

    /** 同地点在场角色引用（M5 群聊场景快照；best-effort，失败返回空表）。 */
    private List<String> presentRefsOf(Long projectId, String characterRef, String location) {
        if (characterRepository == null || location == null || location.isBlank()) {
            return List.of();
        }
        Long selfId = NpcRefs.idOf(characterRef);
        List<String> out = new ArrayList<>();
        try {
            List<ActorCharacter> all = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            for (ActorCharacter c : all) {
                if (c.getId() != null && c.getId().equals(selfId)) {
                    continue;
                }
                if (location.equals(c.getLocation())) {
                    String ref = NpcRefs.of(c.getId());
                    if (ref != null) {
                        out.add(ref);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[对谈冻结] 在场快照失败（降级空表）: {}", e.getMessage());
            return List.of();
        }
        return out;
    }
}
