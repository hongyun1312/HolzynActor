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

package com.holzyn.actor.domain.growth.stage;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestone;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.space.GrowthSpaceRepository;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.growth.space.GrowthSpaceTrigger;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.npc.state.service.PhysioProfile;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全生命周期阶段机服务。
 * <p><b>职责</b>：</p>
 * <ul>
 * <li><b>出生时刻反推</b>：存量 20 NPC 无 {@code birth_game_second}——反推公式
 * {@code birth = 当前世界时钟 − 年龄 × 年秒数}；年龄来源=既有 {@code age} 列 → 档案文本提取
 * （{@code 岁} 正则）→ 类型默认值；</li>
 * <li><b>日级维护</b>：年龄刷新 + 阶段检查 + 生理系数重算（机能曲线 → {@code physio_coef_json}）+
 * 死亡掷点（开关默认关）；</li>
 * <li><b>阶段跨越</b>：{@code actor_profile.stage} 更新 + {@code actor_growth_milestone} 留痕 +
 * AI 阶段叙事（异步，{@code growth_stage_transition_narrative}）+ 空间重算触发
 * （{@code stage_transition}）+ 生理系数刷新；</li>
 * <li><b>死亡全链路</b>：DECEASED 阶段 + 退出运行 + 里程碑 +
 * AI 死亡叙事（异步）+ 世界事件（kind=death）+ 关系对象丧亲记忆（importance=5）；</li>
 * <li><b>年龄快进测试接口</b>：直接改 {@code birth_game_second} + 触发阶段重算与里程碑。</li>
 * </ul>
 * <p><b>架构不变量</b>：本类公开方法<b>不含</b>{@code @Transactional}——AI 叙事在虚拟线程异步执行，
 * 写库走各仓库短事务（对齐「任何含 AI 调用的方法都不得带 @Transactional」）。</p>
 * <p><b>零阻断</b>：任何一步失败只告警，绝不抛给世界引擎（日检是引擎 tick 的一环）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentalStageService {

    /** 阶段跨越叙事场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板） */
    public static final String SCENE_STAGE_TRANSITION = "growth_stage_transition_narrative";
    /** 死亡叙事场景码 */
    public static final String SCENE_DEATH_CHRONICLE = "growth_death_chronicle";

    /** 年龄提取正则（档案文本「62 岁」/「今年 62」/「二十有三」不在此范围，仅数字岁） */
    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{1,3})\\s*岁");
    /** 年龄合理区间（提取值超出即视为噪声） */
    private static final int AGE_MAX_SANE = 130;

    /** 叙事 AI 单次调用超时由网关统一看门狗承担（130s）；本异步池只承载调度 */
    private final ExecutorService narrativeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorWorldClockRepository worldClockRepository;
    private final GrowthMilestoneRepository milestoneRepository;
    private final GrowthSpaceRepository spaceRepository;
    private final GrowthGateService growthGateService;
    private final GrowthSpaceService growthSpaceService;
    private final GrowthProperties growthProperties;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final CurrentUserProvider currentUserProvider;
    private final UsageLogService usageLogService;
    private final WorldEventBus worldEventBus;
    private final AgentMemoryService agentMemoryService;
    /** 人格演化（阶段跨越 → 强制偏移评估：成年里程碑必然伴随人格沉淀；null=未装配[单测]跳过） */
    private final com.holzyn.actor.domain.growth.evolution.PersonalityShiftService personalityShiftService;
    /** 身后事 */
    private final com.holzyn.actor.domain.growth.legacy.LegacyService legacyService;
    /** 婴儿档案分阶段揭示 */
    private final com.holzyn.actor.domain.growth.infant.InfantRevealService infantRevealService;
    /** 沙盘 SSE（npc:stage_transition / npc:death 下行；UnityWsHub 旁路映射） */
    private final com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub sandboxSseHub;

    /**
     * 谱系服务（治理批次 0 · 卡5：死亡 → 婚姻终止[丧偶]——婚姻边 active=0 + 亡偶 MARRIAGE 关系行
     * 终止；字段注入避免改构造器，null=未装配[单测]跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.lineage.LineageService lineageService;

    /**
     * 统一 AI 上下文注入器（阶段叙事/死亡叙事属「涉及该角色」的叙事生成——AI 以世界叙事者
     * 视角讲述该角色的成长/死亡，前置世界观简报 + 角色核心卡；null=未装配跳过注入，不影响原调用）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 死亡掷点随机源（测试可经 forceDeath 绕过；不注入 seed——死亡是运行期事件） */
    private final Random random = new Random();

    //
    // 出生时刻反推
    //

    /**
     * 对缺失 {@code birth_game_second} 的 NPC 反推出生时刻（幂等：已反推/已故跳过）。
     * <p>年龄来源优先级：① {@code actor_profile.age}（普通 NPC 迁移自旧表）→
     * ② 档案/详情文本提取（{@code 岁} 正则，特殊 NPC）→ ③ 类型默认（special 30 / common 25）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 反推基准时刻（世界时钟当前值；调用方传入保证口径一致）
     *
     * @return 本次反推的人数
     */
    public int backfillBirthSeconds(Long projectId, Long gameSecond) {
        if (projectId == null || gameSecond == null) {
            return 0;
        }
        long yearSeconds = growthProperties.getAging().getGameSecondsPerYear();
        int done = 0;
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                if (ch.getBirthGameSecond() != null) {
                    continue;
                }
                if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                    continue; // 已故不反推
                }
                int age = resolveAge(ch);
                // 出生时刻允许为负（角色出生早于世界引擎 0 时刻）；绝不钳制——
                // ageOf 派生口径 = (now - birth)/年长，钳到 0 会破坏年龄派生一致性
                long birth = gameSecond - (long) age * yearSeconds;
                ch.setBirthGameSecond(birth);
                ch.setAge(age);
                ch.setStage(GrowthStage.ofAge(age).name());
                characterRepository.save(ch);
                done++;
                log.info("[阶段机] 出生时刻反推：profileId={} 年龄={} birth={} 阶段={}",
                        ch.getId(), age, birth, ch.getStage());
            } catch (Exception e) {
                log.warn("[阶段机] 出生反推失败（跳过）: profileId={} 原因={}", ch.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[阶段机] 出生反推完成：projectId={} 反推 {} 人", projectId, done);
        }
        return done;
    }

    /** 解析年龄（既有列 → 档案文本提取 → 类型默认；恒 0~130）。 */
    private int resolveAge(ActorCharacter ch) {
        if (ch.getAge() != null && ch.getAge() >= 0 && ch.getAge() <= AGE_MAX_SANE) {
            return ch.getAge();
        }
        Integer extracted = extractAgeFromPersona(ch);
        if (extracted != null) {
            return extracted;
        }
        return "common".equalsIgnoreCase(ch.getType())
                ? growthProperties.getBackfill().getDefaultAgeCommon()
                : growthProperties.getBackfill().getDefaultAgeSpecial();
    }

    /** 从档案/详情文本提取年龄（扫描 detail + _legacy.detail + 身份维文本；首个命中）。 */
    private Integer extractAgeFromPersona(ActorCharacter ch) {
        List<String> texts = new ArrayList<>();
        if (ch.getDetail() != null && !ch.getDetail().isBlank()) {
            texts.add(ch.getDetail());
        }
        PersonaProfile p = PersonaJson.readQuietly(ch.getPersonaJson());
        if (p != null) {
            if (p.getLegacy() != null) {
                if (p.getLegacy().getDetail() != null) {
                    texts.add(p.getLegacy().getDetail());
                }
                if (p.getLegacy().getAnchorText() != null) {
                    texts.add(p.getLegacy().getAnchorText());
                }
            }
            if (p.getDims() != null && p.getDims().getIdentity() != null) {
                PersonaProfile.Dims d = p.getDims();
                if (d.getIdentity().getIdentityTitle() != null) {
                    texts.add(d.getIdentity().getIdentityTitle());
                }
            }
        }
        for (String text : texts) {
            Matcher m = AGE_PATTERN.matcher(text);
            while (m.find()) {
                try {
                    int age = Integer.parseInt(m.group(1));
                    if (age >= 1 && age <= AGE_MAX_SANE) {
                        return age;
                    }
                } catch (NumberFormatException ignored) {
                    // 继续扫描
                }
            }
        }
        return null;
    }

    //
    // 年龄派生（权威：birth_game_second）
    //

    /**
     * 由出生时刻派生年龄（游戏年，向下取整；birth 缺失返回既有 age 列或 null）。
     *
     * @param ch 角色行
     *
     * @param gameSecond 当前世界时刻（游戏秒）
     *
     * @return 年龄（游戏年）；无法派生返回 null
     */
    public Integer ageOf(ActorCharacter ch, Long gameSecond) {
        if (ch.getBirthGameSecond() != null && gameSecond != null) {
            long yearSeconds = growthProperties.getAging().getGameSecondsPerYear();
            long age = (gameSecond - ch.getBirthGameSecond()) / yearSeconds;
            return (int) Math.max(0, age);
        }
        return ch.getAge();
    }

    //
    // 日级维护（挂 WorldEngine 日级周期）
    //

    /**
     * 日级维护（每游戏日一次，挂 WorldEngine ③ 周期；失败隔离不阻断引擎）：
     * 反推 → 年龄刷新 → 阶段检查 → 生理系数重算 → 死亡掷点。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻（游戏秒）
     */
    public void dailyMaintenance(Long projectId, Long gameSecond) {
        if (!growthProperties.isEnabled()) {
            return;
        }
        if (growthProperties.getBackfill().isEnabled()) {
            try {
                backfillBirthSeconds(projectId, gameSecond);
            } catch (Exception e) {
                log.warn("[阶段机] 日检-出生反推失败（隔离继续）: projectId={} 原因={}", projectId, e.getMessage());
            }
        }
        // ① 年龄刷新（日级，actor_profile.age 是派生缓存列）
        try {
            refreshAges(projectId, gameSecond);
        } catch (Exception e) {
            log.warn("[阶段机] 日检-年龄刷新失败（隔离继续）: projectId={} 原因={}", projectId, e.getMessage());
        }
        // ② 阶段检查（跨越 → 里程碑/叙事/空间重算）
        try {
            checkStageTransitions(projectId, gameSecond);
        } catch (Exception e) {
            log.warn("[阶段机] 日检-阶段检查失败（隔离继续）: projectId={} 原因={}", projectId, e.getMessage());
        }
        // ③ 生理系数重算（机能曲线 → physio_coef_json，衰老进入衰减计算）
        try {
            refreshPhysioCoefs(projectId);
        } catch (Exception e) {
            log.warn("[阶段机] 日检-生理系数重算失败（隔离继续）: projectId={} 原因={}", projectId, e.getMessage());
        }
        // ④ 死亡掷点（开关默认关；forceDeath 测试接口可绕过）
        if (growthProperties.getDeath().isEnabled()) {
            try {
                rollDeaths(projectId, gameSecond);
            } catch (Exception e) {
                log.warn("[阶段机] 日检-死亡掷点失败（隔离继续）: projectId={} 原因={}", projectId, e.getMessage());
            }
        }
    }

    /** 年龄列刷新（幂等：与派生值一致则跳过）。 */
    private void refreshAges(Long projectId, Long gameSecond) {
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                continue;
            }
            Integer age = ageOf(ch, gameSecond);
            if (age != null && !age.equals(ch.getAge())) {
                ch.setAge(age);
                characterRepository.save(ch);
            }
        }
    }

    //
    // 阶段跨越
    //

    /**
     * 阶段检查与跨越（日级；与 {@code actor_state.stage_at_last_tick} 比对口径一致）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻（游戏秒）
     *
     * @return 本次跨越列表（测试/日志用）
     */
    public List<StageTransition> checkStageTransitions(Long projectId, Long gameSecond) {
        List<StageTransition> transitions = new ArrayList<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                    continue;
                }
                if (ch.getBirthGameSecond() == null) {
                    continue;
                }
                int age = ageOf(ch, gameSecond);
                GrowthStage expected = GrowthStage.ofAge(age);
                String from = ch.getStage();
                if (expected.name().equals(from)) {
                    continue;
                }
                String fromLabel = from == null || from.isBlank() ? "未知" : from;
                ch.setStage(expected.name());
                ch.setAge(age);
                characterRepository.save(ch);
                // 运行态标记同步（actor_state.stage_at_last_tick，阶段机消费方比对基准）
                stateRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef())
                        .ifPresent(state -> {
                            state.setStageAtLastTick(expected.name());
                            stateRepository.save(state);
                        });
                StageTransition t = new StageTransition(ch.npcRef(), fromLabel, expected.name(), age);
                transitions.add(t);
                log.info("[阶段机] 阶段跨越：{} {}→{}（{} 岁）", ch.getName(), fromLabel, expected.label(), age);
                // 里程碑留痕（标题 + 程序化详情；AI 叙事异步补齐）
                writeStageMilestone(projectId, ch, fromLabel, expected, gameSecond);
                // 生理系数即时重算（阶段变化必然伴随机能变化）
                refreshPhysioCoef(projectId, ch);
                // 空间重算触发（stage_transition 触发码；空间服务内部零阻断）
                try {
                    growthSpaceService.triggerRegenerate(projectId, ch.npcRef(), GrowthSpaceTrigger.STAGE_TRANSITION,
                            gameSecond);
                } catch (Exception e) {
                    log.warn("[阶段机] 阶段跨越-空间重算触发失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
                }
                // ：阶段跨越 → 人格偏移强制评估（异步；人生阶段变化必然伴随
                // 人格沉淀——设计 §5.5「阶段跨越强制一次」；内部零阻断）
                if (personalityShiftService != null) {
                    try {
                        personalityShiftService.assessAsync(projectId, ch.npcRef(),
                                com.holzyn.actor.domain.growth.evolution.GrowthShiftLog.TRIGGER_STAGE_TRANSITION,
                                gameSecond);
                    } catch (Exception e) {
                        log.warn("[阶段机] 阶段跨越-人格评估触发失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
                    }
                }
                // ：阶段跨越 → ① 婴儿档案分阶段揭示（：TODDLER 揭示 ⑦⑧、
                // CHILD 揭示 ⑤⑥、ADOLESCENT 揭示 ⑨⑪——复用 AI 补全缺失维，只许填缺）；
                // ② npc:stage_transition SSE/Unity 下行
                if (infantRevealService != null) {
                    try {
                        // 逐段揭示：跨越多阶段的快进/加速（如 INFANT→YOUTH 直跳）时，
                        // 对中间经过的 TODDLER/CHILD/ADOLESCENT 各触发一次揭示——
                        // 与真实日级推进逐一跨越的口径一致（每段揭示只填缺、幂等可重试）。
                        revealCrossedStages(projectId, ch.getId(), fromLabel, expected.name());
                    } catch (Exception e) {
                        log.warn("[阶段机] 阶段跨越-婴儿档案揭示触发失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
                    }
                }
                try {
                    publishStageTransitionDownlink(projectId, ch, fromLabel, expected.name(), age, gameSecond);
                } catch (Exception e) {
                    log.warn("[阶段机] 阶段跨越-下行发布失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("[阶段机] 阶段检查异常（跳过）: profileId={} 原因={}", ch.getId(), e.getMessage());
            }
        }
        return transitions;
    }

    /**
     * 阶段跨越逐段揭示——from 之后至 to 的每个阶段各触发一次婴儿档案揭示。
     * <p>真实日级推进时每次只跨一个阶段，本循环退化为单次调用；年龄快进/长跑加速直跳多阶段时，
     * 保证 TODDLER/CHILD/ADOLESCENT 的档案揭示不会因跳级而丢失。</p>
     */
    private void revealCrossedStages(Long projectId, Long profileId, String fromStage, String toStage) {
        GrowthStage from = GrowthStage.of(fromStage);
        GrowthStage to = GrowthStage.of(toStage);
        if (from == null || to == null) {
            return;
        }
        boolean crossing = false;
        for (GrowthStage s : GrowthStage.values()) {
            if (s == from) {
                crossing = true;
                continue;
            }
            if (crossing) {
                infantRevealService.revealOnStageTransition(projectId, profileId, s.name());
            }
            if (s == to) {
                break;
            }
        }
    }

    /** 阶段跨越 SSE/Unity 下行（npc:stage_transition → npc_stage_transition 信封）。 */
    private void publishStageTransitionDownlink(Long projectId, ActorCharacter ch, String fromStage,
                                                String toStage, int age, Long gameSecond) {
        if (sandboxSseHub == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("npcRef", ch.npcRef());
        data.put("npcName", ch.getName());
        data.put("fromStage", fromStage);
        data.put("toStage", toStage);
        data.put("age", age);
        data.put("gameSecond", gameSecond);
        sandboxSseHub.publish(projectId, com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub
                .EVENT_NPC_STAGE_TRANSITION, data);
    }

    /** 写阶段跨越里程碑（AI 叙事异步补齐 detail；失败不阻断跨越本身）。 */
    private void writeStageMilestone(Long projectId, ActorCharacter ch, String fromStage,
                                     GrowthStage toStage, Long gameSecond) {
        try {
            GrowthMilestone m = new GrowthMilestone();
            m.setProjectId(projectId);
            m.setNpcRef(ch.npcRef());
            m.setGameSecond(gameSecond);
            m.setKind(GrowthMilestone.KIND_STAGE_TRANSITION);
            m.setTitle(ch.getName() + "进入" + toStage.label());
            m.setStageFrom(fromStage);
            m.setStageTo(toStage.name());
            m.setImportance(3);
            m.setDetail("程序化记录：" + ch.getName() + " 由「" + fromStage + "」进入「"
                    + toStage.label() + "」（" + (ch.getAge() == null ? "?" : ch.getAge()) + " 岁），"
                    + toStage.summary());
            GrowthMilestone saved = milestoneRepository.save(m);
            // AI 阶段叙事（异步、失败不阻断；叙事结果回填 milestone.detail + 世界事件）
            final Long milestoneId = saved.getId();
            narrativeExecutor.submit(() -> {
                try {
                    String narrative = stageTransitionNarrative(projectId, ch, fromStage, toStage);
                    if (narrative != null && !narrative.isBlank()) {
                        saved.setDetail(narrative);
                        milestoneRepository.save(saved);
                        // 阶段跨越 → 世界事件（kind=evolution 语义最贴近；importance 3）
                        publishStageEvent(projectId, ch, fromStage, toStage, narrative, gameSecond);
                    }
                } catch (Exception e) {
                    log.warn("[阶段机] 阶段叙事失败（里程碑已保留程序化详情）: milestoneId={} 原因={}",
                            milestoneId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[阶段机] 里程碑写入失败（跳过）: profileId={} 原因={}", ch.getId(), e.getMessage());
        }
    }

    /** 阶段叙事 AI 调用（异步执行体；含 AI 调用，禁止 @Transactional 的调用栈内执行）。 */
    private String stageTransitionNarrative(Long projectId, ActorCharacter ch, String fromStage, GrowthStage toStage) {
        Long uid = currentUserProvider.currentUserId();
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("name", ch.getName());
        ph.put("from_stage", fromStage);
        ph.put("to_stage", toStage.label());
        ph.put("age", ch.getAge() == null ? "?" : ch.getAge());
        ph.put("stage_summary", toStage.summary());
        String prompt = promptTemplateService.render(uid, projectId, SCENE_STAGE_TRANSITION, ph);
        // 上下文注入（2026-10-07）：阶段叙事涉及该角色——AI 以世界叙事者视角讲述
        // 该角色的成长里程碑，前置世界观简报 + 角色核心卡（未装配/失败零阻断）
        // 批5 世界与叙事（2026-10-10）：升级为 V2.0 段1A+1C+2D+2E（memoryChainContext：
        // compose 世界观+角色卡 + decisionStatePrefix 当前世界动态+当前状态）——
        // 叙事者知道此刻时节天气与该角色当下状态，成长里程碑写得更真实
        String injected = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, ch.npcRef(), null);
        String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
        String system = promptTemplateService.systemMessage(uid, projectId, SCENE_STAGE_TRANSITION, null);
        if (system.isBlank()) {
            system = "你是这个世界的叙事者，用朴素真切的中文口吻讲述。";
        }
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", system),
                new AiChatRequest.ChatMessage("user", finalPrompt)),
                PromptSpecRegistry.temperatureOf(SCENE_STAGE_TRANSITION),
                PromptSpecRegistry.maxOutputTokensOf(SCENE_STAGE_TRANSITION),
                true);
        AiChatResult result = AiCallContext.run(SCENE_STAGE_TRANSITION, ch.getId(), 1,
                () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
        try {
            usageLogService.record(uid, projectId, ch.getId(), result.providerId(), result.model(),
                    SCENE_STAGE_TRANSITION, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
        } catch (Exception e) {
            log.warn("[阶段机] 叙事用量入账失败（已忽略）: {}", e.getMessage());
        }
        return result.content() == null ? null : result.content().trim();
    }

    /** 阶段跨越世界事件（kind=evolution；失败不阻断）。 */
    private void publishStageEvent(Long projectId, ActorCharacter ch, String fromStage, GrowthStage toStage,
                                   String narrative, Long gameSecond) {
        try {
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind("evolution")
                            .source("growth")
                            .characterId(ch.getId())
                            .characterRefs(List.of(ch.npcRef()))
                            .visibility("global")
                            .title(ch.getName() + "进入" + toStage.label())
                            .content(narrative == null || narrative.isBlank()
                                    ? ch.getName() + " 由「" + fromStage + "」进入「" + toStage.label() + "」。"
                                    : narrative)
                            .importance(3)
                            .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                            .sourceKey("growth-stage:" + ch.getId() + ":" + toStage.name() + ":" + (gameSecond / 86400L))
                            .build());
        } catch (Exception e) {
            log.warn("[阶段机] 阶段事件发布失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
    }

    //
    // 生理系数重算（衰老 → 状态模块）
    //

    /**
     * 全项目生理系数重算（日级/启动）：从 {@code physio_json}（基准，幂等源）出发，
     * 叠加机能曲线年龄修正 → 写回 {@code physio_coef_json}。每 tick 衰减只读系数，零改动。
     *
     * @param projectId 项目 ID
     */
    public void refreshPhysioCoefs(Long projectId) {
        for (ActorNpcState state : stateRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                ActorCharacter ch = characterRepository.findById(NpcRefs.idOf(state.getNpcRef())).orElse(null);
                if (ch == null) {
                    continue;
                }
                refreshPhysioCoef(projectId, ch);
            } catch (Exception e) {
                log.warn("[阶段机] 生理系数重算失败（跳过）: {} 原因={}", state.getNpcRef(), e.getMessage());
            }
        }
    }

    /** 单角色生理系数重算（基准 physio_json + 年龄修正；幂等）。 */
    private void refreshPhysioCoef(Long projectId, ActorCharacter ch) {
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef()).orElse(null);
        if (state == null) {
            return;
        }
        int age = ch.getAge() == null ? 0 : Math.max(0, ch.getAge());
        double capacity = growthGateService.capacityOf(age);
        // 基准系数（physio_json 幂等源）→ 叠加年龄修正 → 写回
        PhysioProfile base = PhysioProfile.fromPhysioJson(state.getPhysioJson());
        PhysioProfile aged = base.withAgeModifiers(growthGateService.ageModifiers(capacity));
        state.setPhysioCoefJson(aged.toJson());
        stateRepository.save(state);
    }

    //
    // 死亡
    //

    /**
     * 日级死亡掷点（仅 {@code death.enabled=true} 时由 dailyMaintenance 调用）。
     * 概率 = 阶段基线年死亡率 × 健康修正 × 季节修正 × 机能修正，折算为日概率。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前世界时刻
     */
    private void rollDeaths(Long projectId, Long gameSecond) {
        long yearSeconds = growthProperties.getAging().getGameSecondsPerYear();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            try {
                if (GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
                    continue;
                }
                double dailyProb = deathProbability(ch, gameSecond) / 365.0;
                if (dailyProb > 0 && random.nextDouble() < dailyProb) {
                    executeDeath(projectId, ch, gameSecond, "年迈体衰（日检自然死亡）");
                }
            } catch (Exception e) {
                log.warn("[阶段机] 死亡掷点异常（跳过）: profileId={} 原因={}", ch.getId(), e.getMessage());
            }
        }
    }

    /**
     * 死亡年概率（纯函数，可单测）：阶段基线 × 健康修正 × 季节修正 × 机能修正。
     *
     * @param ch 角色行
     *
     * @param gameSecond 当前世界时刻（季节判定用）
     *
     * @return 年死亡概率（0~0.5 钳制）
     */
    public double deathProbability(ActorCharacter ch, Long gameSecond) {
        if (ch == null || ch.getStage() == null) {
            return 0;
        }
        double base = growthProperties.getDeath().getBaseRateByStage()
                .getOrDefault(ch.getStage(), 0.001);
        // 健康修正：health 100 → ×(1−w)，health 0 → ×(1+w)（w=healthModifierWeight 默认 0.5）
        double w = growthProperties.getDeath().getHealthModifierWeight();
        double health = 100;
        ActorNpcState state = stateRepository.findByProjectIdAndNpcRef(ch.getProjectId(), ch.npcRef()).orElse(null);
        if (state != null && state.getHealth() != null) {
            health = state.getHealth();
        }
        double healthFactor = 1.0 + (100.0 - health) / 100.0 * w;
        // 季节修正：冬季/酷暑 ×1.3（月位判定，对齐 SeasonService 冬夏语义）
        double seasonFactor = 1.0;
        if (growthProperties.getDeath().isSeasonModifierEnabled() && gameSecond != null) {
            int month = (int) ((gameSecond / 2_592_000L) % 12);
            if (month == 11 || month == 0 || month == 1 || month == 5 || month == 6 || month == 7) {
                seasonFactor = 1.3;
            }
        }
        // 机能修正：年迈者机能低 → 风险再放大（diseaseRisk 倍率 0.8~2.2）
        int age = ch.getAge() == null ? 0 : Math.max(0, ch.getAge());
        double capacity = growthGateService.capacityOf(age);
        double capacityFactor = growthGateService.ageModifiers(capacity).getOrDefault("diseaseRisk", 1.0);
        return Math.max(0, Math.min(0.5, base * healthFactor * seasonFactor * capacityFactor));
    }

    /**
     * 执行死亡（测试接口 force-death / 日检掷点共用；完整身后事链路）。
     *
     * @param projectId 项目 ID
     *
     * @param ch 角色行
     *
     * @param gameSecond 当前世界时刻
     *
     * @param reason 死亡原因（程序化文案/测试说明）
     */
    public void executeDeath(Long projectId, ActorCharacter ch, Long gameSecond, String reason) {
        if (ch == null) {
            return;
        }
        String name = ch.getName() == null ? "npc:" + ch.getId() : ch.getName();
        String stageBefore = ch.getStage();
        // ① 阶段置 DECEASED（退出世界运行的标记）
        ch.setStage(GrowthStage.DECEASED.name());
        characterRepository.save(ch);
        // ② 运行态同步 + 空间清除（不再消费空间）
        stateRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef()).ifPresent(state -> {
            state.setStageAtLastTick(GrowthStage.DECEASED.name());
            stateRepository.save(state);
        });
        try {
            spaceRepository.findByProjectIdAndNpcRef(projectId, ch.npcRef()).ifPresent(spaceRepository::delete);
        } catch (Exception e) {
            log.warn("[阶段机] 死亡-空间清除失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
        // ③ 里程碑（importance 5）+ AI 死亡叙事异步回填
        GrowthMilestone m = new GrowthMilestone();
        m.setProjectId(projectId);
        m.setNpcRef(ch.npcRef());
        m.setGameSecond(gameSecond);
        m.setKind(GrowthMilestone.KIND_DEATH);
        m.setTitle(name + "离世");
        m.setStageFrom(stageBefore);
        m.setStageTo(GrowthStage.DECEASED.name());
        m.setImportance(5);
        m.setDetail("程序化记录：" + name + " 离世（" + reason + "）。身后事与遗物将由谱系系统（G4）承接。");
        GrowthMilestone saved = milestoneRepository.save(m);
        log.info("[阶段机] 死亡：{}（{}→已故，原因={}）", name, stageBefore, reason);
        final Long milestoneId = saved.getId();
        narrativeExecutor.submit(() -> {
            try {
                String narrative = deathChronicle(projectId, ch, reason);
                if (narrative != null && !narrative.isBlank()) {
                    saved.setDetail(narrative);
                    milestoneRepository.save(saved);
                }
            } catch (Exception e) {
                log.warn("[阶段机] 死亡叙事失败（里程碑已保留程序化详情）: milestoneId={} 原因={}",
                        milestoneId, e.getMessage());
            }
        });
        // ④ 世界事件（kind=death，importance 5）
        try {
            worldEventBus.publish(currentUserProvider.currentUserId(), projectId,
                    EventSpec.builder()
                            .kind("death")
                            .source("growth")
                            .characterId(ch.getId())
                            .characterRefs(List.of(ch.npcRef()))
                            .visibility("global")
                            .title(name + "离世")
                            .content(name + " 离世（" + reason + "）。"
                                    + (stageBefore == null ? "" : "生前处于" + stageBefore + "阶段。"))
                            .importance(5)
                            .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                            .sourceKey("growth-death:" + ch.getId())
                            .build());
        } catch (Exception e) {
            log.warn("[阶段机] 死亡事件发布失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
        // ⑤ 丧亲记忆：全部相关角色获得 importance=5 的记忆（对齐设计 §4.7 身后事）
        try {
            List<ActorCharacterRelation> relations = relationRepository.findByProjectId(projectId);
            for (ActorCharacterRelation r : relations) {
                if (r == null) {
                    continue;
                }
                boolean from = ch.getId().equals(r.getFromCharacterId());
                boolean to = ch.getId().equals(r.getToCharacterId());
                if (!from && !to) {
                    continue;
                }
                String otherRef = from ? r.getToRef() : r.getFromRef();
                String otherName = from ? r.getToName() : r.getFromName();
                if (otherRef == null || otherRef.isBlank()) {
                    continue;
                }
                agentMemoryService.recordObservation(projectId, otherRef,
                        "得知" + name + "去世（" + reason + "），" + (otherName == null ? "" : otherName + " ") + "悲恸不已",
                        5);
            }
        } catch (Exception e) {
            log.warn("[阶段机] 丧亲记忆写入失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
        // ⑥ 身后事 + npc:death SSE/Unity 下行
        if (legacyService != null) {
            try {
                legacyService.transferOnDeath(projectId, ch, gameSecond);
            } catch (Exception e) {
                log.warn("[阶段机] 身后事执行失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
            }
        }
        try {
            publishDeathDownlink(projectId, ch, reason, gameSecond);
        } catch (Exception e) {
            log.warn("[阶段机] 死亡-下行发布失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
        // ⑦ 治理批次 0 · 卡5：婚姻终止（丧偶）——婚姻边 active=0 + 亡偶 MARRIAGE 关系行终止
        terminateMarriageOf(projectId, ch);
    }

    /**
     * 治理批次 0 · 卡5：死亡侧婚姻终止（丧偶）。
     * <p>executeDeath 此前不调 {@code LineageService.terminateMarriage}（该方法零调用方）→
     * 丧偶者永远 {@code isMarried=true} 不能再婚。本步：
     * ① 谱系婚姻边 active=0（历史保留）；② 关系表 MARRIAGE 行终止（双向删行——亡偶不得残留
     * 婚配关系行）。失败零阻断（与身后事同语义，绝不影响死亡主链路）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param ch 亡者角色行
     */
    private void terminateMarriageOf(Long projectId, ActorCharacter ch) {
        if (ch == null || ch.npcRef() == null) {
            return;
        }
        if (lineageService != null) {
            try {
                lineageService.terminateMarriage(projectId, ch.npcRef(), "widow");
            } catch (Exception e) {
                log.warn("[阶段机] 死亡-婚姻终止失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
            }
        }
        try {
            List<ActorCharacterRelation> marriageRows = relationRepository.findByProjectId(projectId).stream()
                    .filter(r -> r != null && "MARRIAGE".equals(r.getRelationType())
                            && (ch.getId().equals(r.getFromCharacterId())
                            || ch.getId().equals(r.getToCharacterId())))
                    .toList();
            if (!marriageRows.isEmpty()) {
                relationRepository.deleteAll(marriageRows);
            }
        } catch (Exception e) {
            log.warn("[阶段机] 死亡-婚配关系行终止失败（跳过）: {} 原因={}", ch.npcRef(), e.getMessage());
        }
    }

    /** 死亡 SSE/Unity 下行（npc:death → npc_death 信封）。 */
    private void publishDeathDownlink(Long projectId, ActorCharacter ch, String reason, Long gameSecond) {
        if (sandboxSseHub == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("npcRef", ch.npcRef());
        data.put("npcName", ch.getName());
        data.put("reason", reason == null ? "寿终正寝" : reason);
        data.put("gameSecond", gameSecond);
        sandboxSseHub.publish(projectId, com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub
                .EVENT_NPC_DEATH, data);
    }

    /** 死亡叙事 AI 调用（异步执行体）。 */
    private String deathChronicle(Long projectId, ActorCharacter ch, String reason) {
        Long uid = currentUserProvider.currentUserId();
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("name", ch.getName());
        ph.put("reason", reason == null ? "寿终正寝" : reason);
        ph.put("age", ch.getAge() == null ? "?" : ch.getAge());
        String prompt = promptTemplateService.render(uid, projectId, SCENE_DEATH_CHRONICLE, ph);
        // 上下文注入（2026-10-07）：死亡叙事涉及该角色——AI 以世界叙事者视角讲述
        // 该角色的身后事，前置世界观简报 + 角色核心卡（未装配/失败零阻断）
        // 批5 世界与叙事（2026-10-10）：升级为 V2.0 段1A+1C+2D+2E（memoryChainContext）——
        // 叙事者知道此刻时节天气与该角色离世前的状态，身后事写得沉静真实
        String injected = aiContextInjector == null ? ""
                : aiContextInjector.memoryChainContext(projectId, ch.npcRef(), null);
        String finalPrompt = injected.isBlank() ? prompt : injected + "\n\n" + prompt;
        String system = promptTemplateService.systemMessage(uid, projectId, SCENE_DEATH_CHRONICLE, null);
        if (system.isBlank()) {
            system = "你是这个世界的叙事者，用朴素真切的中文口吻讲述。";
        }
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", system),
                new AiChatRequest.ChatMessage("user", finalPrompt)),
                PromptSpecRegistry.temperatureOf(SCENE_DEATH_CHRONICLE),
                PromptSpecRegistry.maxOutputTokensOf(SCENE_DEATH_CHRONICLE),
                true);
        AiChatResult result = AiCallContext.run(SCENE_DEATH_CHRONICLE, ch.getId(), 1,
                () -> aiChatGateway.chatCompletion(uid, projectId, null, req));
        try {
            usageLogService.record(uid, projectId, ch.getId(), result.providerId(), result.model(),
                    SCENE_DEATH_CHRONICLE, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
        } catch (Exception e) {
            log.warn("[阶段机] 死亡叙事用量入账失败（已忽略）: {}", e.getMessage());
        }
        return result.content() == null ? null : result.content().trim();
    }

    //
    // 测试接口支持（年龄快进 / 强制死亡）
    //

    /**
     * 年龄快进（测试接口）：直接把 {@code birth_game_second} 拨到「目标年龄对应时刻」，
     * 并触发阶段重算 + 里程碑 + 生理系数刷新。
     *
     * @param projectId 项目 ID
     *
     * @param profileId 角色 ID
     *
     * @param targetAge 目标年龄（游戏年，0~130）
     *
     * @param gameSecond 当前世界时刻（基准）
     *
     * @return 快进后的阶段码；失败返回 null
     */
    public String fastForwardAge(Long projectId, Long profileId, int targetAge, Long gameSecond) {
        ActorCharacter ch = characterRepository.findById(profileId).orElse(null);
        if (ch == null || gameSecond == null) {
            return null;
        }
        int age = Math.max(0, Math.min(AGE_MAX_SANE, targetAge));
        long yearSeconds = growthProperties.getAging().getGameSecondsPerYear();
        // 出生时刻允许为负（同上：年龄快进到早于世界 0 时刻时保留负 birth，保证 ageOf 派生一致）
        ch.setBirthGameSecond(gameSecond - (long) age * yearSeconds);
        ch.setAge(age);
        characterRepository.save(ch);
        List<StageTransition> transitions = checkStageTransitions(projectId, gameSecond);
        refreshPhysioCoef(projectId, ch);
        // 返回快进后的实际阶段（checkStageTransitions 可能已更新 DB 行——重新读取，避免陈旧引用）
        String stage = characterRepository.findById(profileId).map(ActorCharacter::getStage).orElse(null);
        log.info("[阶段机] 年龄快进：profileId={} → {} 岁（阶段 {}）", profileId, age, stage);
        return stage == null ? ch.getStage() : stage;
    }

    /**
     * 强制死亡（测试接口）：无视 {@code death.enabled} 直接执行完整死亡链路。
     *
     * @param projectId 项目 ID
     *
     * @param profileId 角色 ID
     *
     * @param gameSecond 当前世界时刻
     *
     * @param reason 死亡原因（可空）
     *
     * @return true=已执行
     */
    public boolean forceDeath(Long projectId, Long profileId, Long gameSecond, String reason) {
        ActorCharacter ch = characterRepository.findById(profileId).orElse(null);
        if (ch == null || GrowthStage.DECEASED.name().equalsIgnoreCase(ch.getStage())) {
            return false;
        }
        executeDeath(projectId, ch, gameSecond, reason == null || reason.isBlank() ? "测试强制死亡" : reason);
        return true;
    }

    /** 阶段跨越结果记录（测试/日志）。 */
    public record StageTransition(String npcRef, String stageFrom, String stageTo, int age) {
    }
}
