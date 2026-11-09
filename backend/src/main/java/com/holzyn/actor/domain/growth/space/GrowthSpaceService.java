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

package com.holzyn.actor.domain.growth.space;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.prompt.PromptMetricsService;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.growth.stage.GrowthGateService;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.HardGateResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 此刻可发生空间服务。
 * <p><b>职责</b>：</p>
 * <ul>
 * <li><b>决策时 ensure</b>（{@link #ensureSpace}）：决策前若空间缺失/TTL 过期 → AI 生成（或按 LOD
 * dormant 档程序化生成）——决策在空间内选（软引导 + forbidden 硬禁）；</li>
 * <li><b>感知只读段</b>（{@link #spaceSegment}）：注入 Prompt 用；
 * 有效空间直接渲染，缺失/过期返回程序化块（零 AI、不落库），绝不因注入触发 AI；</li>
 * <li><b>事件驱动重算</b>（{@link #triggerRegenerate}）：阶段跨越/生理越界/情绪漂移/重大事件/
 * 在场者变化/计划失效/关系跨档/环境剧变/LOD 升档/定期兜底——带同码去重窗口防风暴；</li>
 * <li><b>space_compliance</b>（{@link #pruneConflicts}）：AI 产出的候选与硬门控冲突 →
 * 程序化剔除 + 记告警（对齐设计 §5.2「不重试、不阻断，真实度不受损」）；</li>
 * <li><b>空间内行动率统计</b>（{@link #recordDecisionInSpace}）：决策落点是否在 possible_actions 内
 * （只统计不强制—— 「软引导」；硬约束开关 {@code space.hard-constraint} 开启时
 * 由决策侧强制采样兜底）。</li>
 * </ul>
 * <p><b>AI 生成输入</b>（对齐设计 §5.2 与 Prompt 规范 §5.8 分片组合）：档案分片 S1S2S4S5S8 +
 * 硬门控（{@code GrowthGateService.renderStageBlock}）+ 当前状态 + 相关记忆 ≤3 + 骨架/时刻。</p>
 * <p><b>架构不变量</b>：本类公开方法<b>不含</b>{@code @Transactional}（含 AI 调用，落库走仓库短事务）。</p>
 * <p><b>零阻断</b>：AI 失败/解析失败 → 程序化兜底空间（{@link ProgrammaticSpaceFactory}），世界照常跑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthSpaceService {

    /** 空间生成场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板 + AiCallTrace scene）。 */
    public static final String SCENE_SPACE_GENERATE = "growth_space_generate";

    /** 同码去重窗口（游戏秒，默认 30 游戏分钟：同触发码在窗口内不重复重算，防事件风暴）。 */
    private static final long DEDUPE_WINDOW_SECONDS = 30 * 60L;

    /** 单次重算的原始 AI 输出日志截断长度。 */
    private static final int RAW_LOG_LIMIT = 500;

    private final GrowthSpaceRepository spaceRepository;
    private final GrowthSpaceLogRepository spaceLogRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final GrowthProperties growthProperties;
    private final GrowthGateService growthGateService;
    private final BrainTierService brainTierService;
    private final GrowthMetricsService growthMetrics;
    private final PersonaService personaService;
    private final NpcStateService npcStateService;
    private final AgentMemoryService agentMemoryService;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final CurrentUserProvider currentUserProvider;
    private final UsageLogService usageLogService;
    private final PromptMetricsService promptMetrics;

    /**
     * 认知注意力档位提供器：空间生成记忆条数按注意力档位配额
     * （focus 3/ambient 2/dormant 1——最大输入场景的配额收窄，直接降输入 token）。
     * 字段注入 + @Lazy 破 bean 环；null=未装配按既有 ≤3 条行为。
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.attention.AttentionLevelProvider attentionLevelProvider;

    /**
     * 统一 AI 上下文注入器（角色视角调用注入世界观简报；null=未装配跳过）。
     * <p>2026-10-07 上下文注入根治：空间生成此前只有角色骨架/档案分片/硬门控/状态/记忆，
     * 缺世界观简报——「此刻可发生空间」应贴合世界（地理/势力/规则/文化），补注入。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 角色当前地点读口（权威坐标 → 要素 → 归属住户；null=未装配按未知地点处理）。
     * <p>：空间一次生成、TTL 6~12 游戏小时，角色走动后候选动作仍属于旧地点——
     * 「环境」于是只活在 prompt 叙述里。用这个读口判断「空间是否已经离开了生成它的那个地方」。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.NpcPlaceView npcPlaceView;

    //
    // 消费入口
    //

    /**
     * 决策时确保空间存在（决策前调用）：有效（TTL 未过）直接复用；缺失/过期 → 按 LOD 档位生成
     * （focus/ambient=AI 生成 + 程序化兜底；dormant=纯程序化零 AI）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 当前空间（恒非 null；任意失败走程序化兜底）
     */
    public GrowthSpace ensureSpace(Long projectId, String npcRef, Long gameSecond) {
        if (!growthProperties.isEnabled() || !growthProperties.getSpace().isEnabled()) {
            return programmaticSpace(projectId, npcRef, gameSecond, GrowthSpaceTrigger.PERIODIC, false);
        }
        GrowthSpace existing = spaceRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (isValid(existing, gameSecond)) {
            // 位置变化：空间是「上一个地点」里生成的 → 按新地点零 AI 重算，候选集跟着身体走。
            // 只在已到达（arrived）之后重算：还在路上时他既不在旧地也不在新地，
            // 每走一步就重算既无意义也浪费（步行途中每几秒就换一次坐标）。
            if (npcPlaceView != null && npcPlaceView.arrivedAfter(projectId, npcRef, existing.getGameSecond())) {
                GrowthSpace moved = programmaticSpace(projectId, npcRef, gameSecond,
                        GrowthSpaceTrigger.LOCATION_CHANGED, true);
                if (moved != null) {
                    log.info("[可发生空间] 位置变化，按新地点重算（零 AI）：{} 空间生成于 {} → 现在 {}",
                            npcRef, existing.getGameSecond(), gameSecond);
                    return moved;
                }
            }
            return existing;
        }
        return generate(projectId, npcRef, gameSecond, GrowthSpaceTrigger.PERIODIC);
    }

    /**
     * 感知只读空间段（注入 Prompt）：有效空间直接渲染；缺失/过期 → 程序化块（零 AI、不落库）。
     * <b>绝不因注入触发 AI 生成</b>（感知是高频热路径，AI 只由 ensure/trigger 触发）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 注入文本（含硬约束 + 空间块；无内容返回空串，零阻断）
     */
    public String spaceSegment(Long projectId, String npcRef, Long gameSecond) {
        try {
            StringBuilder sb = new StringBuilder();
            // 硬约束（发育阶段 + 禁止/受限）
            String hard = growthGateService.renderStageBlock(projectId, npcRef);
            if (hard != null && !hard.isBlank()) {
                sb.append("【发育阶段约束】\n").append(hard);
            }
            // 空间块
            GrowthSpace space = spaceRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            String block;
            // 库里那行可能是「上一个地点」的空间（位置刚变、还没到下一次决策）。
            // 感知是决策的输入，滞后一个 tick 就会让模型拿旧地点的选项做判断——这里读时按
            // 当前位置现算（只读，不写库），库里的重算仍由 ensureSpace 负责。
            if (space != null && npcPlaceView != null
                    && npcPlaceView.arrivedAfter(projectId, npcRef, space.getGameSecond())) {
                block = livePlaceBlock(npcPlaceView.at(projectId, npcRef));
                if (block == null || block.isBlank()) {
                    block = renderBlock(space);
                }
            } else if (isValid(space, gameSecond)) {
                block = renderBlock(space);
            } else {
                GrowthSpace programmatic = programmaticSpace(projectId, npcRef, gameSecond,
                        GrowthSpaceTrigger.PERIODIC, false);
                block = renderBlock(programmatic);
            }
            if (block != null && !block.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(block);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[可发生空间] 感知段渲染失败（返回空，零阻断）: {} 原因={}", npcRef, e.getMessage());
            return "";
        }
    }

    /**
     * 事件驱动重算（带同码去重窗口）：阶段跨越/生理越界/情绪漂移/重大事件/在场者变化/计划失效/
     * 关系跨档/环境剧变/LOD 升档/手动。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用
     *
     * @param trigger 触发码
     *
     * @param gameSecond 当前世界时刻
     *
     * @return 重算后的空间（去重命中返回现有空间）
     */
    public GrowthSpace triggerRegenerate(Long projectId, String npcRef, GrowthSpaceTrigger trigger,
                                         Long gameSecond) {
        if (!growthProperties.isEnabled() || !growthProperties.getSpace().isEnabled()) {
            return null;
        }
        GrowthSpace existing = spaceRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        // 同码去重：同一触发码在窗口内不重复重算（阶段跨越等天然低频，情绪漂移等可能高频）
        if (existing != null && trigger != GrowthSpaceTrigger.MANUAL
                && trigger.code().equals(existing.getTriggerCode())
                && existing.getGameSecond() != null && gameSecond != null
                && gameSecond - existing.getGameSecond() < DEDUPE_WINDOW_SECONDS) {
            return existing;
        }
        return generate(projectId, npcRef, gameSecond, trigger);
    }

    /**
     * 全项目空间定期兜底（WorldEngine 挂载：focus 每 6 游戏小时 / ambient 每 12 游戏小时，
     * 由 1/6 游戏小时放宽——成本理由见 {@code GrowthProperties.Space} 同名字段注释）。
     * 按 LOD 档位各自周期重算；dormant 跳过（零 AI）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻
     */
    public void periodicRecompute(Long projectId, Long gameSecond) {
        if (!growthProperties.isEnabled() || !growthProperties.getSpace().isEnabled()) {
            return;
        }
        int focusPeriod = growthProperties.getSpace().getPeriodicRecomputeGameMinutes();
        int ambientPeriod = growthProperties.getSpace().getAmbientPeriodicGameMinutes();
        long hourSeconds = 3600L;
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                    continue;
                }
                String tier = brainTierService.tierOf(projectId, ch.npcRef());
                if (BrainTierService.TIER_DORMANT.equals(tier)) {
                    continue;
                }
                int periodMinutes = BrainTierService.TIER_FOCUS.equals(tier) ? focusPeriod : ambientPeriod;
                if (periodMinutes <= 0) {
                    continue;
                }
                long lastSecond = spaceRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef())
                        .map(GrowthSpace::getGameSecond).orElse(0L);
                if (gameSecond - lastSecond < (long) periodMinutes * 60L) {
                    continue;
                }
                triggerRegenerate(projectId, ch.npcRef(), GrowthSpaceTrigger.PERIODIC, gameSecond);
            } catch (Exception e) {
                log.warn("[可发生空间] 定期兜底失败（跳过）: profileId={} 原因={}", ch.getId(), e.getMessage());
            }
        }
    }

    //
    // 生成
    //

    /** 生成空间（AI 优先 + 程序化兜底；按 LOD 档位：dormant 纯程序化）。 */
    private GrowthSpace generate(Long projectId, String npcRef, Long gameSecond, GrowthSpaceTrigger trigger) {
        try {
            // dormant 档：纯程序化（零 AI）
            if (BrainTierService.TIER_DORMANT.equals(brainTierService.tierOf(projectId, npcRef))) {
                return programmaticSpace(projectId, npcRef, gameSecond, trigger, true);
            }
            // AI 生成（失败 → 程序化兜底）
            GrowthSpace ai = aiGenerate(projectId, npcRef, gameSecond, trigger);
            if (ai != null) {
                return ai;
            }
            return programmaticSpace(projectId, npcRef, gameSecond, trigger, true);
        } catch (Exception e) {
            log.warn("[可发生空间] 生成异常（程序化兜底）: {} 原因={}", npcRef, e.getMessage());
            return programmaticSpace(projectId, npcRef, gameSecond, trigger, true);
        }
    }

    /** AI 生成空间（完整链路：骨架 + 分片 + 硬门控 + 状态 + 记忆 → 调用 → 解析 → space_compliance → 落库）。 */
    private GrowthSpace aiGenerate(Long projectId, String npcRef, Long gameSecond, GrowthSpaceTrigger trigger) {
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        if (ch == null) {
            return null;
        }
        Long uid = currentUserProvider.currentUserId();
        String prompt = renderGeneratePrompt(uid, projectId, ch, gameSecond);
        String system = promptTemplateService.systemMessage(uid, projectId, SCENE_SPACE_GENERATE, null);
        if (system.isBlank()) {
            system = "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
        }
        try {
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", prompt)),
                    PromptSpecRegistry.temperatureOf(SCENE_SPACE_GENERATE),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_SPACE_GENERATE),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_SPACE_GENERATE, ch.getId(), 1,
                    () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
            try {
                usageLogService.record(uid, projectId, ch.getId(), result.providerId(), result.model(),
                        SCENE_SPACE_GENERATE, result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(), 0);
            } catch (Exception usageError) {
                log.warn("[可发生空间] 用量入账失败（已忽略，不影响空间生成）: {}", usageError.getMessage());
            }
            GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse(extractJson(result.content()));
            if (doc == null || !doc.hasContent()) {
                growthMetrics.recordSpaceJsonFail();
                growthMetrics.recordSpaceAiFail();
                recordJsonFail();
                log.warn("[可发生空间] AI 输出无法解析/无内容（程序化兜底）: {} 原文={}", npcRef,
                        truncate(result.content(), RAW_LOG_LIMIT));
                return null;
            }
            growthMetrics.recordSpaceJsonOk();
            growthMetrics.recordSpaceAiOk();
            recordJsonOk();
            // space_compliance：候选与硬门控冲突 → 程序化剔除 + 记告警
            HardGateResult gate = growthGateService.gate(projectId, npcRef);
            GrowthSpaceParse.GrowthSpaceDoc pruned = pruneConflicts(gate, doc, npcRef);
            GrowthSpace space = new GrowthSpace();
            space.setProjectId(projectId);
            space.setNpcRef(npcRef);
            space.setGameSecond(gameSecond == null ? 0L : gameSecond);
            space.setTriggerCode(trigger.code());
            space.setStage(ch.getStage());
            space.setPadSnapshot(padSnapshotOf(ch));
            space.setHardGateJson(hardGateJsonOf(gate));
            space.setSpaceJson(pruned.toJson());
            space.setSource(GrowthSpace.SOURCE_AI);
            space.setTtlGameSecond(ttlOf(gameSecond, tierOfSafe(projectId, npcRef)));
            GrowthSpace saved = upsertSpace(projectId, npcRef, space);
            appendLog(saved);
            growthMetrics.recordTrigger(GrowthMetricsService.GrowthSpaceTriggerCode.of(trigger.code()));
            log.info("[可发生空间] AI 生成成功：{}（{} 阶段，{} 候选 {} 禁止，触发={}）", npcRef,
                    ch.getStage(), pruned.possibleActions().size(), pruned.forbidden().size(), trigger.code());
            return saved;
        } catch (Exception e) {
            growthMetrics.recordSpaceAiFail();
            log.warn("[可发生空间] AI 生成异常（程序化兜底）: {} 原因={}", npcRef, e.getMessage());
            return null;
        }
    }

    /** 程序化空间（dormant 档 / 失败兜底；persist=true 落库 + 记日志，false 仅返回渲染用）。 */
    private GrowthSpace programmaticSpace(Long projectId, String npcRef, Long gameSecond,
                                          GrowthSpaceTrigger trigger, boolean persist) {
        ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        if (ch == null) {
            ch = new ActorCharacter();
            ch.setId(NpcRefs.idOf(npcRef));
        }
        HardGateResult gate = growthGateService.gate(projectId, npcRef);
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        // 地点快照（未知地点 → buildAt 退回阶段池，与既有行为一致）
        com.holzyn.actor.domain.mapnav.service.NpcPlaceView.Place place =
                npcPlaceView == null ? null : npcPlaceView.at(projectId, npcRef);
        GrowthSpaceParse.GrowthSpaceDoc doc = ProgrammaticSpaceFactory.buildAt(
                place, ch.getName(), GrowthStage.of(ch.getStage()), gate, state, ch.getCurrentActivity());
        if (place != null && place.known()) {
            log.info("[可发生空间] 程序化空间按地点生成：{} 在 {}（{}，{}）候选 {} 项",
                    npcRef, place.name(), place.category() == null || place.category().isBlank()
                            ? "未分类" : place.category(),
                    place.atOwnHome() ? "自家" : (place.atOthersHome() ? "别人家" : "公共场所/经营场所"),
                    doc.possibleActions() == null ? 0 : doc.possibleActions().size());
        }
        GrowthSpace space = new GrowthSpace();
        space.setProjectId(projectId);
        space.setNpcRef(npcRef);
        space.setGameSecond(gameSecond == null ? 0L : gameSecond);
        space.setTriggerCode(trigger.code());
        space.setStage(ch.getStage());
        space.setHardGateJson(hardGateJsonOf(gate));
        space.setSpaceJson(doc.toJson());
        space.setSource(GrowthSpace.SOURCE_PROGRAMMATIC);
        space.setTtlGameSecond(ttlOf(gameSecond, tierOfSafe(projectId, npcRef)));
        if (persist) {
            GrowthSpace saved = upsertSpace(projectId, npcRef, space);
            appendLog(saved);
            growthMetrics.recordSpaceProgrammatic();
            growthMetrics.recordTrigger(GrowthMetricsService.GrowthSpaceTriggerCode.of(trigger.code()));
            return saved;
        }
        growthMetrics.recordSpaceProgrammatic();
        return space;
    }

    /**
     * 空间落库（<b>upsert 语义</b>：表含 (project_id, npc_ref) 唯一索引，一行一 NPC——
     * 已有行复用主键走 JPA 更新（保留 createdAt），无则插入）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用
     *
     * @param draft 新空间（未带 id）
     *
     * @return 落库后的空间（已带 id）
     */
    private GrowthSpace upsertSpace(Long projectId, String npcRef, GrowthSpace draft) {
        GrowthSpace existing = spaceRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (existing != null) {
            draft.setId(existing.getId());
        }
        return spaceRepository.save(draft);
    }

    /** 渲染空间生成 Prompt（对齐 Prompt 规范：分片 + 硬门控 + 状态 + 记忆，信息密度取最优）。 */
    private String renderGeneratePrompt(Long uid, Long projectId, ActorCharacter ch, Long gameSecond) {
        Map<String, Object> ph = new LinkedHashMap<>();
        // 骨架（姓名/年龄/阶段/地点/当前活动）
        StringBuilder skeleton = new StringBuilder();
        skeleton.append("姓名：").append(nullTo(ch.getName(), "无名"));
        if (ch.getAge() != null) {
            skeleton.append("｜年龄：").append(ch.getAge()).append(" 岁");
        }
        if (ch.getStage() != null) {
            GrowthStage st = GrowthStage.of(ch.getStage());
            skeleton.append("｜阶段：").append(st == null ? ch.getStage() : st.label());
        }
        // 所在（权威口径优先）：ch.getLocation() 是语义位置，可能是「正走在去某地的路上」这类旧叙述。
        // 实测：人站在镇中心广场，AI 生成的候选却是「进米行柜台讨碗水喝」「看米行门口堆的粮袋」——
        // 空间生成跟着语义叙述走，而不是跟着身体走。这里以权威坐标为准，语义位置只在坐标缺失时兜底。
        com.holzyn.actor.domain.mapnav.service.NpcPlaceView.Place place =
                npcPlaceView == null ? null : npcPlaceView.at(projectId, ch.npcRef());
        if (place != null && place.known()) {
            skeleton.append("｜所在：").append(place.name());
            StringBuilder tags = new StringBuilder();
            if (place.category() != null && !place.category().isBlank()) {
                tags.append(place.category());
            }
            if (place.atOwnHome()) {
                tags.append(tags.length() > 0 ? "·" : "").append("自家");
            } else if (place.atOthersHome()) {
                tags.append(tags.length() > 0 ? "·" : "").append("别人家");
            }
            if (tags.length() > 0) {
                skeleton.append("（").append(tags).append("）");
            }
        } else if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
            skeleton.append("｜位置：").append(ch.getLocation());
        }
        if (ch.getCurrentActivity() != null && !ch.getCurrentActivity().isBlank()) {
            skeleton.append("｜当前在做：").append(ch.getCurrentActivity());
        }
        ph.put("character_skeleton", skeleton.toString());
        // 世界观简报（2026-10-07 上下文注入根治：让「此刻可发生空间」贴合世界地理/势力/规则/文化）
        String worldBrief = aiContextInjector == null ? ""
                : aiContextInjector.worldBrief(projectId);
        ph.put("world_brief", worldBrief == null || worldBrief.isBlank() ? "（无世界观设定）" : worldBrief);
        // 档案分片 S1S2S4S5S8（对齐设计 §5.8 空间生成默认组合；2026-10-10 决策：不设字数硬边界）
        String shards = personaService.renderShardsForScene(ch.getId(), SCENE_SPACE_GENERATE);
        ph.put("persona_shards", shards == null || shards.isBlank() ? "（档案分片缺失）" : shards);
        // 硬门控（发育阶段约束）
        String hard = growthGateService.renderStageBlock(projectId, ch.npcRef());
        ph.put("hard_gate", hard == null || hard.isBlank() ? "（无硬约束）" : hard);
        // 当前状态
        String state = "";
        if (npcStateService != null) {
            try {
                state = npcStateService.stateSegment(projectId, ch.npcRef(), gameSecond);
            } catch (Exception e) {
                log.debug("[可发生空间] 状态段组装失败（跳过）: {}", e.getMessage());
            }
        }
        // 实测：状态段里的「当前所在：河神庙」来自角色语义位置（NPC 运行态），
        // 人已经被摆到阿珍家门口，模型仍按河神庙写候选（「在河神庙附近走走」）。所以在状态段前面
        // 压一句权威所在，并明确「以此为准」——否则同一份 prompt 里两个地点，模型挑错的那个。
        if (place != null && place.known()) {
            StringBuilder anchor = new StringBuilder("【所在】").append(place.name());
            if (place.category() != null && !place.category().isBlank()) {
                anchor.append("（").append(place.category());
                if (place.atOwnHome()) {
                    anchor.append("，自家");
                } else if (place.atOthersHome()) {
                    anchor.append("，别人家");
                }
                anchor.append("）");
            }
            anchor.append("——此刻就在这里，以此为准；下面的旧叙述或记忆里若有别的地点，以本行为准。\n");
            state = anchor + (state == null ? "" : state);
        }
        ph.put("current_state", state == null || state.isBlank() ? "（状态未知）" : state);        // 相关记忆 ≤3
        String memories = renderMemories(projectId, ch.npcRef());
        ph.put("memories", memories);
        // 游戏时刻
        String time = gameSecond == null ? "（时刻未知）"
                : WorldClockService.formatGameTime(gameSecond / 3600L);
        ph.put("game_time", time);
        String rendered = promptTemplateService.render(uid, projectId, SCENE_SPACE_GENERATE, ph);
        // 批5 世界与叙事（2026-10-10）：空间生成前置 V2.0 段1A+1C+2D+2E——
        // compose（世界观+角色核心卡）+ decisionStatePrefix（当前世界动态+当前状态），
        // AI 生成「此刻可发生空间」时知道世界与自身处境（未装配/无数据空串零阻断）
        String prefix = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, ch.npcRef(), gameSecond);
        prefix = prefix == null ? "" : prefix;
        return prefix.isBlank() ? rendered : prefix + "\n\n" + rendered;
    }

    /**
     * 相关记忆（默认 ≤3 条，Smallville 评分检索； 按注意力档位配额 focus 3/ambient 2/dormant 1，
     * 失败零阻断）。
     */
    private String renderMemories(Long projectId, String npcRef) {
        if (agentMemoryService == null) {
            return "（无相关记忆）";
        }
        try {
            int topK = spaceMemoryTopK(projectId, npcRef);
            if (topK <= 0) {
                return "（无近期相关记忆）";
            }
            List<ActorMemory> mems = agentMemoryService.retrieve(projectId, npcRef, null, topK);
            if (mems == null || mems.isEmpty()) {
                return "（无近期相关记忆）";
            }
            StringBuilder sb = new StringBuilder();
            for (ActorMemory m : mems) {
                if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("- ").append(truncate(m.getContent(), 120));
            }
            return sb.length() == 0 ? "（无近期相关记忆）" : sb.toString();
        } catch (Exception e) {
            log.debug("[可发生空间] 记忆检索失败（跳过）: {}", e.getMessage());
            return "（无相关记忆）";
        }
    }

    /**
     * 空间生成记忆条数配额：按注意力档位取 topK（focus 3/ambient 2/dormant 1，
     * 配置见 {@code holzyn.actor.growth.attention.space-memory-top-k-by-tier}）；无信号/开关关
     * 回退既有默认 3。零 AI、零事务。
     */
    private int spaceMemoryTopK(Long projectId, String npcRef) {
        try {
            if (attentionLevelProvider == null || growthProperties == null
                    || !growthProperties.isEnabled()
                    || !growthProperties.getAttention().isMemoryWeightEnabled()) {
                return 3;
            }
            String tier = attentionLevelProvider.tierOf(projectId, npcRef);
            if (tier == null) {
                return 3;
            }
            Integer v = growthProperties.getAttention().getSpaceMemoryTopKByTier().get(tier);
            return v == null || v <= 0 ? 3 : v;
        } catch (Exception e) {
            log.debug("[可发生空间] 注意力档位读取失败（回退 3 条）: ref={} : {}", npcRef, e.getMessage());
            return 3;
        }
    }

    //
    // space_compliance（候选与硬门控冲突剔除）
    //

    /**
     * space_compliance 校验：AI 产出的候选与硬门控 forbidden 冲突 → 程序化剔除 + 记告警
     * （不重试、不阻断——对齐设计 §5.2）。同时把硬门控禁止项并入空间 forbidden
     * （消费侧一眼可见硬边界）。
     *
     * @param gate 硬门控结果
     *
     * @param doc AI 产出的空间文档（不原地改，返回新对象）
     *
     * @param npcRef 角色引用（日志）
     *
     * @return 剔除冲突后的空间文档
     */
    public GrowthSpaceParse.GrowthSpaceDoc pruneConflicts(HardGateResult gate,
                                                          GrowthSpaceParse.GrowthSpaceDoc doc, String npcRef) {
        List<GrowthSpaceParse.ActionItem> keptActions = new ArrayList<>();
        for (GrowthSpaceParse.ActionItem a : doc.possibleActions()) {
            if (a == null || a.action() == null || a.action().isBlank()) {
                continue;
            }
            if (conflictsWithGate(gate, a.action())) {
                growthMetrics.recordSpaceComplianceIntercept();
                log.warn("[可发生空间] 候选与硬门控冲突，已剔除：{} 候选=「{}」", npcRef, a.action());
                continue;
            }
            keptActions.add(a);
        }
        // 硬门控禁止项并入 forbidden（去重：what 相同的保留一条）
        List<GrowthSpaceParse.ForbiddenItem> merged = new ArrayList<>(doc.forbidden());
        if (gate != null && gate.forbidden() != null) {
            for (HardGateResult.ForbiddenItem f : gate.forbidden()) {
                if (f.what() == null || f.what().isBlank()) {
                    continue;
                }
                boolean dup = false;
                for (GrowthSpaceParse.ForbiddenItem ex : merged) {
                    if (ex.what() != null && ex.what().equals(f.what())) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    merged.add(new GrowthSpaceParse.ForbiddenItem(f.what(), f.reason()));
                }
            }
        }
        return new GrowthSpaceParse.GrowthSpaceDoc(doc.inner(), doc.possibleStates(), keptActions, merged);
    }

    /** 候选行动是否与硬门控禁止项冲突（分段包含匹配：禁止项按顿号/逗号切段，任一段命中即冲突）。 */
    private boolean conflictsWithGate(HardGateResult gate, String action) {
        if (gate == null || gate.forbidden() == null || action == null) {
            return false;
        }
        for (HardGateResult.ForbiddenItem f : gate.forbidden()) {
            if (f.what() == null || f.what().isBlank()) {
                continue;
            }
            for (String seg : f.what().split("[、，,;；。]")) {
                String s = seg.trim();
                if (s.length() >= 2 && action.contains(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    //
    // 空间内行动率统计（软引导：只统计不强制；硬约束开关由决策侧强制）
    //

    /**
     * 决策落点记录：action_detail 是否命中当前空间 possible_actions（含匹配，非精确）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用
     *
     * @param actionDetail 决策行动描述
     *
     * @return true=在空间内
     */
    public boolean recordDecisionInSpace(Long projectId, String npcRef, String actionDetail) {
        boolean in = isActionInSpace(projectId, npcRef, actionDetail);
        if (in) {
            growthMetrics.recordDecisionInSpace();
        } else {
            growthMetrics.recordDecisionOutside();
        }
        return in;
    }

    /** 判定行动描述是否在空间候选内（含匹配：候选文本是行动文本的子串，或相反）。 */
    public boolean isActionInSpace(Long projectId, String npcRef, String actionDetail) {
        if (actionDetail == null || actionDetail.isBlank()) {
            return false;
        }
        GrowthSpace space = spaceRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
        if (space == null || space.getSpaceJson() == null) {
            return false;
        }
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse(space.getSpaceJson());
        if (doc == null || doc.possibleActions() == null || doc.possibleActions().isEmpty()) {
            return false;
        }
        String a = actionDetail.trim();
        for (GrowthSpaceParse.ActionItem item : doc.possibleActions()) {
            if (item.action() == null || item.action().isBlank()) {
                continue;
            }
            String cand = item.action().trim();
            if (cand.contains(a) || a.contains(cand)) {
                return true;
            }
        }
        return false;
    }

    //
    // 渲染 / 工具
    //

    /** 渲染空间注入块（Prompt 消费；上限 {@code space.inject-max-chars} 截断）。 */
    /**
     * 按当前位置现算的「此刻可发生空间」块（只读，不写库）。
     *
     * <p>用于「库里的空间属于上一个地点」这一刻：感知是决策输入，宁可现算一份地点池，
     * 也不让模型拿旧地点的选项做判断。地点未知时返回空串，由调用方回退旧块。</p>
     */
    private String livePlaceBlock(com.holzyn.actor.domain.mapnav.service.NpcPlaceView.Place place) {
        if (place == null || !place.known()) {
            return "";
        }
        List<String> pool = ProgrammaticSpaceFactory.poolAt(place);
        if (pool.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【此刻可发生空间】（按当前位置现算：")
                .append(place.name() == null ? "此处" : place.name()).append("）");
        sb.append("\n此刻可发生的行动：");
        int n = 0;
        for (String a : pool) {
            if (n >= 6) {
                break;
            }
            sb.append("\n").append(++n).append(". ").append(a);
        }
        List<GrowthSpaceParse.ForbiddenItem> forbid = ProgrammaticSpaceFactory.forbiddenAt(place);
        if (forbid != null && !forbid.isEmpty()) {
            sb.append("\n此处不宜：");
            int m = 0;
            for (GrowthSpaceParse.ForbiddenItem f : forbid) {
                if (m >= 3) {
                    break;
                }
                sb.append(m == 0 ? "" : "；").append(f.what());
                m++;
            }
        }
        return sb.toString();
    }

    public String renderBlock(GrowthSpace space) {        if (space == null || space.getSpaceJson() == null) {
            return "";
        }
        GrowthSpaceParse.GrowthSpaceDoc doc = GrowthSpaceParse.parse(space.getSpaceJson());
        if (doc == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【此刻可发生空间】");
        if (doc.inner() != null) {
            if (doc.inner().dominantThought() != null && !doc.inner().dominantThought().isBlank()) {
                sb.append("\n内心念头：").append(doc.inner().dominantThought());
            }
            if (doc.inner().bodyFeel() != null && !doc.inner().bodyFeel().isBlank()) {
                sb.append("\n身体感受：").append(doc.inner().bodyFeel());
            }
        }
        if (doc.possibleStates() != null && !doc.possibleStates().isEmpty()) {
            sb.append("\n此刻可能的心理状态：");
            for (int i = 0; i < doc.possibleStates().size(); i++) {
                GrowthSpaceParse.StateItem s = doc.possibleStates().get(i);
                sb.append("\n").append(i + 1).append(". ").append(s.state());
                if (s.weight() != null) {
                    sb.append("（倾向 ").append(Math.round(s.weight() * 100)).append("%）");
                }
                if (s.precondition() != null && !s.precondition().isBlank()) {
                    sb.append("【若 ").append(s.precondition()).append("】");
                }
            }
        }
        if (doc.possibleActions() != null && !doc.possibleActions().isEmpty()) {
            sb.append("\n此刻可发生的行动：");
            for (int i = 0; i < doc.possibleActions().size(); i++) {
                GrowthSpaceParse.ActionItem a = doc.possibleActions().get(i);
                sb.append("\n").append(i + 1).append(". ").append(a.action());
                if (a.target() != null && !a.target().isBlank()) {
                    sb.append("（目标：").append(a.target()).append("）");
                }
                if (a.precondition() != null && !a.precondition().isBlank()) {
                    sb.append("【若 ").append(a.precondition()).append("】");
                }
            }
            sb.append("\n说明：优先从上述可发生行动中选择，但允许反常（真实的人会做意外之事），"
                    + "唯独「不可发生」与【发育阶段约束】绝不可违逆。");
        }
        if (doc.forbidden() != null && !doc.forbidden().isEmpty()) {
            sb.append("\n此刻不可发生：");
            for (int i = 0; i < doc.forbidden().size(); i++) {
                GrowthSpaceParse.ForbiddenItem f = doc.forbidden().get(i);
                sb.append("\n").append(i + 1).append(". ").append(f.what());
                if (f.reason() != null && !f.reason().isBlank()) {
                    sb.append("（").append(f.reason()).append("）");
                }
            }
        }
        int max = growthProperties.getSpace().getInjectMaxChars();
        String text = sb.toString();
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 当前空间（调试接口）。 */
    public GrowthSpace currentSpace(Long projectId, String npcRef) {
        return spaceRepository.findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
    }

    /** 空间历史（调试接口）。 */
    public List<GrowthSpaceLog> historyOf(Long projectId, String npcRef) {
        return spaceLogRepository.findByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
    }

    /** 空间是否有效（存在且 TTL 未过）。 */
    private boolean isValid(GrowthSpace space, Long gameSecond) {
        if (space == null || space.getSpaceJson() == null || space.getSpaceJson().isBlank()) {
            return false;
        }
        if (space.getTtlGameSecond() == null || gameSecond == null) {
            return true; // 无 TTL/无基准 → 视为有效（零阻断）
        }
        return gameSecond < space.getTtlGameSecond();
    }

    /** TTL 计算（游戏秒；focus/ambient 共用 TTL 配置）。 */
    private Long ttlOf(Long gameSecond, String tier) {
        if (gameSecond == null) {
            return null;
        }
        return gameSecond + (long) growthProperties.getSpace().getTtlGameMinutes() * 60L;
    }

    /** LOD 档位（安全读取；dormant/ambient/focus）。 */
    private String tierOfSafe(Long projectId, String npcRef) {
        try {
            return brainTierService.tierOf(projectId, npcRef);
        } catch (Exception e) {
            return BrainTierService.TIER_AMBIENT;
        }
    }

    /** PAD 快照 JSON（情绪三值；审计）。 */
    private String padSnapshotOf(ActorCharacter ch) {
        try {
            ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(ch.getProjectId(), ch.npcRef())
                    .orElse(null);
            if (state == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pleasure", state.getEmotionPleasure());
            m.put("arousal", state.getEmotionArousal());
            m.put("dominance", state.getEmotionDominance());
            return PersonaServiceJson.write(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** 硬门控 JSON（审计/回放）。 */
    private String hardGateJsonOf(HardGateResult gate) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", gate.stage());
            m.put("age", gate.age());
            m.put("capacity", Math.round(gate.capacity() * 100.0) / 100.0);
            m.put("allowedAbilities", gate.allowedAbilities());
            List<Map<String, String>> forbidden = new ArrayList<>();
            if (gate.forbidden() != null) {
                for (HardGateResult.ForbiddenItem f : gate.forbidden()) {
                    Map<String, String> fm = new LinkedHashMap<>();
                    fm.put("what", f.what());
                    fm.put("reason", f.reason());
                    forbidden.add(fm);
                }
            }
            m.put("forbidden", forbidden);
            return PersonaServiceJson.write(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** 空间历史日志（失败只告警，绝不阻断空间生成）。 */
    private void appendLog(GrowthSpace space) {
        try {
            GrowthSpaceLog l = new GrowthSpaceLog();
            l.setProjectId(space.getProjectId());
            l.setNpcRef(space.getNpcRef());
            l.setGameSecond(space.getGameSecond());
            l.setTriggerCode(space.getTriggerCode());
            l.setSource(space.getSource());
            l.setStage(space.getStage());
            l.setSpaceJson(space.getSpaceJson());
            l.setHardGateJson(space.getHardGateJson());
            spaceLogRepository.save(l);
        } catch (Exception e) {
            log.warn("[可发生空间] 历史日志写入失败（跳过）: {} 原因={}", space.getNpcRef(), e.getMessage());
        }
    }

    /** 从 AI 输出提取 JSON（剥 Markdown 代码块 + 取首个花括号对）。 */
    private String extractJson(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl > 0) {
                text = text.substring(nl + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String nullTo(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Prompt 指标：空间生成格式合规（对齐 Prompt 工程规范 §5）。 */
    private void recordJsonOk() {
        if (promptMetrics != null) {
            try {
                promptMetrics.recordJsonOk(SCENE_SPACE_GENERATE);
            } catch (Exception ignored) {
                // 指标失败绝不影响业务
            }
        }
    }

    /** Prompt 指标：空间生成格式不合规。 */
    private void recordJsonFail() {
        if (promptMetrics != null) {
            try {
                promptMetrics.recordJsonFail(SCENE_SPACE_GENERATE, "空间 JSON 解析失败/无内容");
            } catch (Exception ignored) {
                // 指标失败绝不影响业务
            }
        }
    }

    /** 轻量 JSON 写入（避免与 persona 序列化口径耦合；失败返回 null）。 */
    private static final class PersonaServiceJson {
        static String write(Object o) {
            try {
                return com.holzyn.actor.domain.growth.persona.PersonaJson.mapper().writeValueAsString(o);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
