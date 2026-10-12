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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学习组件实现（阶段 C 起，domain/agent/learning）——{@link LearningService} 的实现。
 * <p><b>为骨架</b>：反思/偏好/生态仍为占位；<b>起 updateRelation 委托
 * {@link RelationDynamicsService} 真实实现</b>（互动增减好感/信任 + 状态机 + relation 事件发布）；
 * <b>起 reflect/scheduleConsolidation 委托 {@link ReflectionService} 真实实现</b>
 * （反思节奏上限 + 洞察 + 证据指针 + 目标状态 + staleness 复核；WorldEngine 周期调用
 * scheduleConsolidation → 遍历项目特殊 NPC 逐一反思）。字段注入保持无参构造（存量单测零改动）。</p>
 */
@Slf4j
@Service
public class LearningServiceSkeleton implements LearningService {

    /** 动态关系服务（注入；null=未装配/单测直构时回退骨架占位） */
    @Autowired(required = false)
    private RelationDynamicsService relationDynamicsService;

    /** 反思服务（注入；null=未装配/单测直构时回退骨架占位） */
    @Autowired(required = false)
    private ReflectionService reflectionService;

    /** 特殊角色仓库（scheduleConsolidation 遍历项目内角色用） */
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;

    @Override
    public void reflect(String characterRef, Long gameHour) {
        if (reflectionService != null) {
            // ：委托反思服务真实实现（节奏上限/洞察/证据指针/目标状态/staleness 复核）
            try {
                Long projectId = resolveProjectOf(characterRef);
                reflectionService.reflect(projectId, characterRef, gameHour);
                return;
            } catch (Exception e) {
                log.warn("[Learning] 反思执行失败: ref={} : {}", characterRef, e.getMessage());
            }
        }
        log.info("[Learning] 反思骨架（未装配 ReflectionService）: ref={} gameHour={}", characterRef, gameHour);
        AiCallContext.recordStep(null, "agent_learning", "learning",
                "反思骨架：洞察+证据指针写记忆流留阶段 D（ref=" + characterRef + "）", 0);
    }

    @Override
    public void updateRelation(Long projectId, String fromRef, String toRef, int delta, String reason) {
        if (relationDynamicsService != null) {
            // ：委托动态关系服务真实实现（好感增减/状态机/relation 事件）
            relationDynamicsService.updateRelation(projectId, fromRef, toRef, delta, reason);
            return;
        }
        log.info("[Learning] 关系更新骨架（未装配 RelationDynamicsService）: project={} from={} to={} delta={} reason={}",
                projectId, fromRef, toRef, delta, reason);
        AiCallContext.recordStep(projectId, "agent_learning", "learning",
                "关系更新骨架：好感增减/状态机/变迁事件发布留阶段 D（" + fromRef + "→" + toRef + " " + delta + "）", 0);
    }

    @Override
    public void updatePreference(String characterRef, String behaviorKey, int delta) {
        log.info("[Learning] 偏好更新骨架（D 阶段实现）: ref={} behavior={} delta={}", characterRef, behaviorKey, delta);
        AiCallContext.recordStep(null, "agent_learning", "learning",
                "偏好更新骨架：行为频次→偏好留阶段 D（" + characterRef + " " + behaviorKey + "）", 0);
    }

    @Override
    public void snapshotEcosystem(Long projectId, Long gameHour) {
        log.info("[Learning] 生态快照骨架（D 阶段实现）: project={} gameHour={}", projectId, gameHour);
        AiCallContext.recordStep(projectId, "agent_learning", "learning",
                "生态快照骨架：群体分布/组织变迁/地点兴衰留阶段 D", 0);
    }

    /**
     * 周期巩固入口（起真实实现：由 WorldEngine 每跨 K 游戏小时调用）——
     * 遍历项目内特殊 NPC（brainProfile reflection_enabled 且非 programmatic）逐一反思；
     * run_mode=minimal 时反思服务内部跳过（系统自动能力降级）；单角色失败隔离。
     * <p><b>遗留根治① 守卫修正</b>：AgentRuntime 决策链学习步骤以
     * {@code gameHour=null} 占位调用本方法（每角色每轮决策链一次）。旧守卫检查
     * {@code projectId==null} 挡不住该占位调用（projectId 非空）→ 每轮决策链都触发全员反思
     * 尝试（注释语义与实际守卫不一致），叠加与 tick 共享预算 ⇒ 反思预算被饿死。
     * 现守卫改为 {@code gameHour==null} 早退——与注释语义对齐：反思只由 WorldEngine 每游戏日
     * 周期（真实 gameHour）触发。</p>
     * <p><b>遗留根治② 独立预算窗口</b>：周期循环前后经
     * {@code ReflectionService.openCycleBudget/closeCycleBudget} 开/关<b>反思独立预算命名空间</b>
     * （BudgetGuard.beginReflection，浮动预算=特殊 NPC 数 × 每角色每日上限）——反思与引擎 tick
     * 决策预算完全隔离，互不饿死。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameHour 当前游戏小时（null=决策链路占位调用，直接跳过不触发反思）
     */
    @Override
    public void scheduleConsolidation(Long projectId, Long gameHour) {
        if (gameHour == null) {
            // AgentRuntime 学习步骤以 null gameHour 调用：不触发反思（反思由 WorldEngine 周期触发）
            log.debug("[Learning] 周期巩固：gameHour=null（决策链路占位调用），跳过反思");
            return;
        }
        if (reflectionService == null || characterRepository == null) {
            log.info("[Learning] 周期巩固骨架（未装配反思/角色仓库）: project={} gameHour={}", projectId, gameHour);
            AiCallContext.recordStep(projectId, "agent_learning", "learning",
                    "周期巩固骨架：反思/关系/偏好/生态调度留阶段 D", 0);
            return;
        }
        // 反思独立预算窗口：周期内反思消费独立命名空间，不与 tick 决策竞争
        reflectionService.openCycleBudget(projectId);
        try {
            List<ActorCharacter> characters = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
            int reflected = 0;
            for (ActorCharacter ch : characters) {
                try {
                    // ：npc:{id} 不再编码类型，此处按意图 B 处理——周期巩固只对「特殊型」反思（单表合并后
                    // 上面按项目查出的行同时含普通型，须按行自带的 actor_profile.type 过滤，否则普通型被空跑一遍）
                    if (NpcRefs.isCommonType(ch.getType())) {
                        continue;
                    }
                    if (reflectionService.reflect(projectId, NpcRefs.of(ch.getId()), gameHour)) {
                        reflected++;
                    }
                } catch (Exception e) {
                    log.warn("[Learning] 角色反思失败（隔离继续）: char={} : {}", ch.getId(), e.getMessage());
                }
            }
            log.info("[Learning] 周期巩固完成：project={} gameHour={} 反思执行 {} 个角色", projectId, gameHour, reflected);
            AiCallContext.recordStep(projectId, "agent_learning", "learning",
                    "周期巩固：反思 " + reflected + " 个角色（节奏上限 ≤2/游戏日）", 0);
        } finally {
            reflectionService.closeCycleBudget(projectId);
        }
    }

    /** 由角色引用解析项目（统一身份 npc:{id} → 查 actor_profile 行；失败返回 null） */
    private Long resolveProjectOf(String characterRef) {
        if (characterRef == null || characterRef.isBlank() || characterRepository == null) {
            return null;
        }
        // ：npc:{id} 不再编码类型，此处按意图 A 处理——引用能解析成 actor_profile 行（special/common
        // 都成立），解析主键统一走 NpcRefs.idOf（保持原有非法 → null 的处理）
        Long id = NpcRefs.idOf(characterRef);
        if (id == null) {
            return null;
        }
        return characterRepository.findById(id).map(ActorCharacter::getProjectId).orElse(null);
    }
}
