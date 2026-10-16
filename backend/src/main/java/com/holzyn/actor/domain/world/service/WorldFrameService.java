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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.WorldFrameProperties;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.vo.WorldFrameVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 每日世界帧服务。
 * <p>职责：</p>
 * <ul>
 * <li><b>每日生成</b>：每游戏日 00:00（WorldEngine 记忆周期挂钩调用）生成 1 帧结构化世界状态
 * （天气/宏观事件/信息包/环境变化），幂等（uk project_id+game_day，同日不重复生成=缓存 24h 语义）；</li>
 * <li><b>双通道</b>：AI 主通道（world_frame_daily 模板，JSON + 温度 0.3，<b>独立每日预算</b>——
 * 不占/不被占用 BudgetGuard tick 预算，每日首帧保证 AI 尝试）+ 机制兜底（天气轮换 + 1 条
 * 宏观事件 + 1 条衍生信息包，AI 失败/校验失败/开关关时使用）；</li>
 * <li><b>边界校验</b>：AI 输出经 {@link WorldFrameValidator}（禁 NPC 行为/世界观越界/信息包字段
 * 全或丢弃），校验失败回退机制兜底帧（世界演化不中断）；</li>
 * <li><b>落库与事件</b>：帧行（旧 active 置 superseded）+ 信息包实体化 actor_info_packet（紧急
 * 就地更新时删旧建新）+ 宏观事件<b>逐条</b>发布 actor_event（kind=world_frame、source=engine、
 * global、幂等键 frame:{projectId}:{gameDay}:{eventId}；无宏观事件发帧摘要兜底 1 条）→
 * 沙盘 SSE scene:event 实时可见；</li>
 * <li><b>紧急更新</b>：重大事件（重要度 ≥ {@link WorldFrameProperties#getEmergencyImportanceThreshold()}
 * 或手动 POST）→ 只重生成受影响宏观事件与信息包（天气/环境/前日摘要保持），<b>就地更新</b>当前
 * active 帧（同 game_day，uk 保持成立），限频 ≤1 次/游戏日（内存 Map，重启丢失可接受），超限 400；</li>
 * <li><b>自动触发</b>：注册 WorldEventBus 订阅者——重大事件自动紧急更新（忽略 kind=world_frame
 * 自身事件，防自触发循环；异步虚拟线程不阻塞事件发布方）。</li>
 * </ul>
 * <p>产出消费：actor_info_packet 由信息扩散引擎（InfoDiffusionService）按小时规则扩散，
 * NPC 感知三层过滤后注入【世界动态】段—— 仅建数据，不接感知。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldFrameService implements WorldEventSubscriber {

    /** AI 调用场景（trace/用量记账 scene=world_frame_daily，对齐内置模板编码） */
    private static final String SCENE_FRAME = "world_frame_daily";

    /** AI 生成失败最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 事件类别：世界帧（actor_event.kind） */
    private static final String KIND_WORLD_FRAME = "world_frame";

    /**
     * 「尚未生成世界帧」的说明文案（UI V2.0 §8.4：未生成是正常状态，200 + generated=false + 本说明）。
     * <p>用世界内语言说清「什么时候会有」，不暴露 gameDay 等内部术语。公开给接口契约测试 pin 文案。</p>
     */
    public static final String HINT_NO_FRAME = "世界还没过完第一个游戏日——第一份世界帧会在第一个游戏日 00:00 自动生成";

    /** 机制天气轮换池（按游戏日轮换，保证多样性与可预测） */
    private static final String[] MECHANISM_WEATHER = {"晴", "多云", "小雨", "阴", "微风晴"};

    /** 机制宏观事件模板池（按游戏日轮换；世界帧只描述世界状态，不带任何 NPC 行为） */
    private static final String[] MECHANISM_EVENT_TEMPLATES = {
            "第%d日：%s一带商旅往来如常，集市照常开张，偶有远方客商带来零星消息。",
            "第%d日：%s上空云层渐厚，入夜后风声大作，早起的人注意到路面有些湿滑。",
            "第%d日：%s的集市出现一批来路不明的货物，商贩们压低声音议论着它们的来历。",
            "第%d日：%s附近河道水位略涨，渡口摆渡的船夫比平时谨慎了几分。",
            "第%d日：%s的告示栏贴出新的通告，往来行人驻足阅读，随后各自散去。"
    };

    private final ActorWorldFrameRepository frameRepository;
    private final ActorInfoPacketRepository packetRepository;
    private final ActorInfoDiffusionRepository diffusionRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorCharacterRepository characterRepository;
    private final WorldEventBus worldEventBus;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final WorldFrameProperties props;
    private final ObjectMapper objectMapper;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 世界时钟（当前游戏秒回退；null=未装配） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（world_frame_daily 属「世界视角」）：AI 帧生成请求前置 段1A【世界观】+
     * 段2D【当前世界动态】（`worldContextPrefix`，世界历时刻/季节/天气），帧贴合世界设定与
     * 当下世界；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * 环境刺激服务（实时行为决策系统 环境刺激：帧生成/紧急更新落库后挂钩 diff——
     * 天气/环境变化对比前帧 → 特殊 NPC 即时决策 + 人群轻量 AI；@Lazy 破 Bean 环
     * [环境刺激→实时决策→AgentRuntime→引擎→世界帧服务]，null=未装配跳过）
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.EnvironmentStimulusService environmentStimulusService;

    /**
     * 季节服务（时间季节系统：帧季节上下文/机制帧季节天气气温/季节更替事件；
     * 字段注入保持构造器不变，null=未装配回退内置默认骨架）
     */
    @Autowired(required = false)
    private SeasonService seasonService;

    /**
     * 统一环境服务（时间季节系统：帧生成后异步预生成当日环境描写润色；
     * 字段注入保持构造器不变，null=未装配跳过润色预生成）
     */
    @Autowired(required = false)
    private EnvironmentContextService environmentContextService;

    /**
     * AI 强度闸门（微层接线， 世界帧 AI 主通道按项目档位生效——
     * programmatic=零 AI 全机制帧 / light|full=标准（帧独立预算每日≤1 次由幂等天然约束）/
     * max=全 AI 必调；字段注入保持构造器不变）
     */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /** 季节系统总开关（默认 true；false=帧不加季节上下文/不发布季节更替事件） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.climate.enabled:true}")
    private boolean climateEnabled;

    /**
     * 紧急更新限频表：projectId → 上次紧急更新所在游戏日（进程内存态；对齐 冷却先例，
     * 单实例运行约定下重启丢失可接受，零表结构变更）
     */
    private final ConcurrentHashMap<Long, Long> lastEmergencyGameDay = new ConcurrentHashMap<>();

    /** 紧急更新异步执行器（虚拟线程，不阻塞事件发布方/引擎推进） */
    private final ExecutorService emergencyExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 启动注册：注册为世界事件总线旁路订阅者（重大事件自动紧急更新；对齐 RealTimeDecisionService
     * 模式，总线对订阅者异常隔离，不影响主广播）。
     */
    @PostConstruct
    void register() {
        worldEventBus.subscribe(this);
        log.info("[世界帧] 已注册世界事件总线订阅者（重大事件自动紧急更新）");
    }

    // ==================== 每日生成（引擎记忆周期挂钩） ====================

    /**
     * 每日世界帧生成入口（WorldEngine 记忆周期每游戏日 00:00 调用）：
     * 幂等检查（uk project_id+game_day 已存在 → 跳过，缓存 24h 语义）→
     * AI 主通道（独立预算）→ 边界校验 → 失败机制兜底 → 落库 + 信息包实体化 + 发布宏观事件。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 当前游戏日序号（自纪元起，floorDiv(gameSecond, 86400)）
     *
     * @param gameSecond 当前游戏秒（生成时刻审计）
     */
    @Transactional
    public void generateDailyFrameIfNeeded(Long projectId, Long gameDay, Long gameSecond) {
        if (projectId == null || gameDay == null) {
            return;
        }
        // 幂等/缓存 24h：当日帧已存在（含紧急就地更新后的帧）→ 不重复生成不重复调 AI
        if (frameRepository.findByProjectIdAndGameDay(projectId, gameDay).isPresent()) {
            log.debug("[世界帧] 当日帧已存在，跳过生成: project={} gameDay={}", projectId, gameDay);
            return;
        }
        ActorWorldFrame previous = previousFrame(projectId, gameDay);
        try {
            generateFrame(projectId, gameDay, gameSecond, previous, null);
        } catch (DataIntegrityViolationException e) {
            // 并发双生成兜底（uk 冲突）：以已存在帧为准，不阻断引擎推进
            log.warn("[世界帧] 并发生成冲突（uk 幂等兜底）: project={} gameDay={}", projectId, gameDay);
        }
    }

    /**
     * 世界帧生成主流程（每日生成/紧急更新共用骨架）：
     * AI 主通道（独立预算，不占 tick 预算）→ 边界校验 → 失败回退机制兜底 → 归一化 → 落库。
     *
     * @param projectId 项目 ID
     *
     * @param gameDay 游戏日序号
     *
     * @param gameSecond 游戏秒
     *
     * @param previous 前一日帧（可空=首日）
     *
     * @param emergency 紧急更新输入（null=每日生成）
     *
     * @return 落库后的帧
     */
    private ActorWorldFrame generateFrame(Long projectId, Long gameDay, Long gameSecond,
                                          ActorWorldFrame previous, EmergencyInput emergency) {
        boolean ownsTrace = AiCallContext.currentTraceId() == null;
        if (ownsTrace) {
            AiCallContext.beginTrace();
        }
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            WorldFrameParse.FrameJson frame = null;
            String source = "mechanism";
            // ① AI 主通道（开关开启时尝试；独立每日预算——每日 ≤1 次调用由幂等天然约束；
            // 微层接线：programmatic 档零 AI——直接机制帧）
            if (props.isAiEnabled() && (aiIntensityGate == null || aiIntensityGate.allowsAi(projectId))) {
                try {
                    WorldFrameParse.FrameJson aiFrame = emergency == null
                            ? tryAiDailyFrame(userId, projectId, gameDay, gameSecond, previous)
                            : tryAiEmergencyFrame(userId, projectId, gameDay, gameSecond, previous, emergency);
                    if (aiFrame != null) {
                        // ② 边界校验：违规（NPC 行为/越界关键词）→ 回退机制兜底帧
                        WorldFrameValidator.ValidationResult vr = WorldFrameValidator.validate(
                                characterNames(projectId), props.getBannedKeywords(), aiFrame);
                        if (vr.passed()) {
                            frame = aiFrame;
                            source = emergency != null ? "emergency" : "ai";
                        } else {
                            log.warn("[世界帧] AI 帧边界校验失败，回退机制兜底帧: project={} day={} 原因={}",
                                    projectId, gameDay, vr.reason());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[世界帧] AI 帧生成异常，回退机制兜底帧: project={} day={} : {}",
                            projectId, gameDay, e.getMessage());
                }
            }
            // ③ 机制兜底（AI 关/失败/校验失败）
            if (frame == null) {
                frame = emergency == null
                        ? mechanismDailyFrame(projectId, gameDay, previous)
                        : mechanismEmergencyFrame(projectId, gameDay, previous, emergency);
                if (emergency != null) {
                    source = "emergency";
                }
                AiCallContext.recordStep(projectId, "world_frame", "mechanism",
                        "世界帧机制兜底生成（第" + (gameDay + 1) + "日" + (emergency != null ? " 紧急" : "")
                                + "，零 AI 成本）", 0);
            }
            // ④ 归一化 + 落库（帧 + 信息包实体化 + 旧帧 superseded/紧急就地更新 + 发布事件）
            WorldFrameParse.FrameJson norm = WorldFrameValidator.normalize(frame, gameDay);
            // 实时行为决策系统环境刺激：
            // 帧生成/紧急更新落库后对比前一帧 → 天气/环境变化 → 特殊 NPC 即时决策 + 人群轻量 AI。
            // ⚠️ 前一帧取<b>落库前解析快照</b>（紧急就地更新时前一帧实体与当前帧是同一持久化
            // 实例，落库后实体内容已变——须先解析快照再落库）。帧内容指纹幂等（同一帧状态只
            // diff 一次）；失败隔离不影响帧落库主流程。
            WorldFrameParse.FrameJson previousSnapshot = previous == null
                    ? null : parseFrameJson(previous);
            ActorWorldFrame persisted = persistFrame(projectId, gameDay, gameSecond, norm, source, emergency != null);
            if (environmentStimulusService != null) {
                try {
                    environmentStimulusService.onFrameChanged(projectId, previousSnapshot, norm, gameSecond);
                } catch (Exception e) {
                    log.warn("[世界帧] 环境刺激 diff 挂钩失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }
            // 时间季节系统：① 季节更替检测——新帧季节 vs 前帧季节（存量帧无
            // season 列按 game_day 派生）不同 → 发布世界事件（重要度 3：触发实时决策通道 NPC
            // 即时决策 + 落事件流/编年史素材；kind=world 非 world_frame 防紧急更新自触发）；
            // ② 环境描写 AI 润色异步预生成（当日 4 时段，预算+缓存门控，零同步延迟）。
            if (climateEnabled) {
                try {
                    String newSeason = SeasonService.seasonOf(gameSecond);
                    String prevSeason = previousSeasonOf(previous);
                    if (prevSeason != null && newSeason != null && !prevSeason.equals(newSeason)) {
                        publishSeasonChange(projectId, gameDay, prevSeason, newSeason, gameSecond);
                    }
                } catch (Exception e) {
                    log.warn("[世界帧] 季节更替事件发布失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }
            if (environmentContextService != null) {
                try {
                    environmentContextService.warmupAsync(projectId, gameSecond);
                } catch (Exception e) {
                    log.warn("[世界帧] 环境润色预生成挂钩失败（隔离继续）: project={} : {}", projectId, e.getMessage());
                }
            }
            return persisted;
        } finally {
            if (ownsTrace) {
                AiCallContext.endTrace();
            }
        }
    }

    // ==================== 紧急更新（手动接口 + 事件总线自动） ====================

    /**
     * 紧急更新入口（POST /world-frame/emergency 手动 + 事件总线自动共用）：
     * 限频检查（≤1 次/游戏日，超限 400）→ 当前 active 帧存在性 → 就地重生成受影响部分并落库。
     *
     * @param projectId 项目 ID
     *
     * @param eventType 重大事件类型（可空，如「战争爆发」）
     *
     * @param location 事件地点（可空）
     *
     * @param summary 事件摘要（可空）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     *
     * @return 更新后的当前帧 VO
     */
    @Transactional
    public WorldFrameVO emergencyUpdate(Long projectId, String eventType, String location, String summary,
                                        Long gameSecond) {
        if (projectId == null) {
            throw new BizException(400, "项目 ID 不能为空");
        }
        ActorWorldFrame current = findActive(projectId);
        if (current == null) {
            throw new BizException(404, "当前无生效世界帧，无法紧急更新（待每日 00:00 首帧生成）");
        }
        Long nowSecond = gameSecond != null ? gameSecond : currentEngineGameSecond(projectId);
        long day = Math.floorDiv(nowSecond == null ? 0L : nowSecond, 86400L);
        // 限频：≤1 次/游戏日（内存 Map；紧急更新即使回退机制也占当日额度）
        Long last = lastEmergencyGameDay.get(projectId);
        if (last != null && last == day) {
            throw new BizException(400, "紧急世界帧更新超出每日限制（" + props.getMaxEmergencyPerDay() + " 次/游戏日）");
        }
        lastEmergencyGameDay.put(projectId, day);

        EmergencyInput input = new EmergencyInput(eventType, location, summary);
        ActorWorldFrame updated = generateFrame(projectId, current.getGameDay(), nowSecond, current, input);
        log.info("[世界帧] 紧急更新完成：project={} day={} 类型={} 地点={}",
                projectId, updated.getGameDay(), eventType, location);
        return toVO(updated, projectId);
    }

    /**
     * 世界事件总线订阅回调（自动紧急更新）：事件重要度 ≥ 阈值（默认 4）且非世界帧自身事件
     * （防自触发循环）→ 异步虚拟线程紧急更新（只重生成受影响信息包与宏观事件）。
     *
     * @param event 已落库的世界事件
     */
    @Override
    public void onEvent(ActorEvent event) {
        if (!shouldTriggerEmergency(event, props)) {
            return;
        }
        Long projectId = event.getProjectId();
        emergencyExecutor.submit(() -> {
            try {
                emergencyUpdate(projectId, event.getKind(), event.getLocationName(), event.getContent(), null);
            } catch (BizException be) {
                log.debug("[世界帧] 自动紧急更新跳过: project={} : {}", projectId, be.getMessage());
            } catch (Exception e) {
                log.warn("[世界帧] 自动紧急更新失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        });
    }

    /**
     * 是否应自动触发紧急更新（静态纯逻辑可单测）：事件重要度 ≥ 阈值（默认 4）且
     * 非 world_frame 自身事件（防自触发循环——紧急更新重生成的宏观事件不得再次触发紧急更新）。
     *
     * @param event 已落库的世界事件（可空）
     *
     * @param props 世界帧配置（紧急更新阈值）
     *
     * @return true 表示应触发
     */
    static boolean shouldTriggerEmergency(ActorEvent event, WorldFrameProperties props) {
        if (event == null || event.getProjectId() == null || props == null) {
            return false;
        }
        // 防自触发循环：world_frame 自身事件不再触发紧急更新
        if (KIND_WORLD_FRAME.equals(event.getKind())) {
            return false;
        }
        Integer importance = event.getImportance();
        return importance != null && importance >= props.getEmergencyImportanceThreshold();
    }

    // ==================== 读接口 ====================

    /**
     * 当前生效世界帧（无 active 帧 → 200 + {@code generated=false}，非 404）。
     *
     * <p><b>契约（UI V2.0 §8.4，2026-10-15 改判）</b>：改前无 active 帧抛 404「当前无生效世界帧」，
     * 结果前端每次首访首页都在 DevTools 里报一条红色 4xx（实测 6 条/轮采集），且"世界里还没有
     * 世界帧"与"接口挂了"在前端无法区分。世界帧在首个游戏日 00:00 才诞生，**未生成是正常状态**，
     * 故改为恒 200 + {@code generated} 显式字段 + {@code hint} 说明原因。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 帧 VO（未生成时 {@code generated=false}、{@code hint} 非空、其余字段为空）
     */
    @Transactional(readOnly = true)
    public WorldFrameVO getActiveFrame(Long projectId) {
        ActorWorldFrame f = findActive(projectId);
        if (f == null) {
            WorldFrameVO pending = new WorldFrameVO();
            pending.setGenerated(false);
            pending.setProjectId(projectId);
            pending.setHint(HINT_NO_FRAME);
            return pending;
        }
        return toVO(f, projectId);
    }

    /**
     * 世界帧历史（game_day 降序，limit 截断默认 30 上限 100）。
     *
     * @param projectId 项目 ID
     *
     * @param limit 条数上限（可空=30）
     *
     * @return 帧 VO 列表
     */
    @Transactional(readOnly = true)
    public List<WorldFrameVO> listFrames(Long projectId, Integer limit) {
        int max = limit == null ? 30 : Math.min(Math.max(1, limit), 100);
        return frameRepository.findByProjectIdOrderByGameDayDesc(projectId).stream()
                .limit(max)
                .map(f -> toVO(f, projectId))
                .toList();
    }

    /**
     * 当前生效世界帧的「今日世界状态」紧凑摘要（世界演化与游戏时间系统：场景化世界演化重构——
     * 演化编排 start/turn/stream 注入世界帧作事实源）。
     * <p>上帝视角直读 active 帧四段 JSON（天气/宏观事件/信息包/环境变化）组装（{@link WorldFrameSummary}，
     * 纯文本零 AI、逐段降级、总长截断 400）；无 active 帧或解析失败返回空串（演化注入侧静默降级，
     * 不阻断演化——主方案 §4.5「背景注入当前世界帧，无帧不阻断」）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 紧凑摘要文本（无帧/失败返回空串）
     */
    @Transactional(readOnly = true)
    public String activeFrameSummary(Long projectId) {
        try {
            ActorWorldFrame f = findActive(projectId);
            if (f == null) {
                return "";
            }
            return WorldFrameSummary.build(f);
        } catch (Exception e) {
            log.warn("[世界帧] 世界帧摘要组装失败（降级空串）：project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    // ==================== AI 主通道 ====================

    /**
     * AI 每日帧生成（world_frame_daily 模板 + json_object + 温度 0.3，独立每日预算）：
     * 输入=世界观 + 前一日帧摘要 + 进行中事件 + 地点列表 + 当前宏观状态；输出=严格 JSON。
     *
     * @return FrameJson（失败/无 JSON 返回 null，调用方回退机制兜底）
     */
    private WorldFrameParse.FrameJson tryAiDailyFrame(Long userId, Long projectId, Long gameDay,
                                                      Long gameSecond, ActorWorldFrame previous) {
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("world_setting", worldSettingText(projectId));
        placeholders.put("previous_day_summary", previousDaySummaryText(previous));
        placeholders.put("ongoing_events", ongoingEventsText(previous));
        placeholders.put("locations", String.join("、", locationNames(projectId)));
        placeholders.put("current_state", "第 " + (gameDay + 1) + " 游戏日，游戏时刻 "
                + WorldClockService.formatGameTime(gameSecond == null ? 0L : gameSecond / 3600L));
        // 时间季节系统：季节上下文（季节名/气温区间/天气倾向/日出日落/季节更替提示）
        placeholders.put("season_context", seasonContextText(projectId, gameSecond, previous));
        placeholders.put("emergency_hint", "");
        return callAiFrame(userId, projectId, gameDay, placeholders);
    }

    /**
     * AI 紧急帧生成（同模板，emergency_hint 注入紧急事件；只重生成受影响宏观事件与信息包，
     * 天气/环境保持原帧——解析后由 {@link #mergeEmergencyFrame} 合并）。
     */
    private WorldFrameParse.FrameJson tryAiEmergencyFrame(Long userId, Long projectId, Long gameDay,
                                                          Long gameSecond, ActorWorldFrame current,
                                                          EmergencyInput input) {
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("world_setting", worldSettingText(projectId));
        placeholders.put("previous_day_summary", previousDaySummaryText(current));
        placeholders.put("ongoing_events", ongoingEventsText(current));
        placeholders.put("locations", String.join("、", locationNames(projectId)));
        placeholders.put("current_state", "第 " + (gameDay + 1) + " 游戏日，当前生效帧天气："
                + abbreviate(current.getWeatherJson(), 200));
        placeholders.put("season_context", seasonContextText(projectId, gameSecond, current));
        placeholders.put("emergency_hint", buildEmergencyHint(input));
        WorldFrameParse.FrameJson ai = callAiFrame(userId, projectId, gameDay, placeholders);
        if (ai == null) {
            return null;
        }
        return mergeEmergencyFrame(current, ai, input, gameDay);
    }

    /** 紧急更新输入 → 提示词段。 */
    private String buildEmergencyHint(EmergencyInput input) {
        StringBuilder sb = new StringBuilder("紧急事件：");
        if (input.eventType() != null && !input.eventType().isBlank()) {
            sb.append(input.eventType());
        } else {
            sb.append("重大事件");
        }
        if (input.location() != null && !input.location().isBlank()) {
            sb.append("，发生地点：").append(input.location());
        }
        if (input.summary() != null && !input.summary().isBlank()) {
            sb.append("，事件摘要：").append(input.summary());
        }
        sb.append("。请只重生成与该事件相关的宏观事件与信息包（event_id 建议带 e 后缀以示紧急），"
                + "天气与环境变化保持与当前一致（可省略）。");
        return sb.toString();
    }

    /**
     * 紧急帧合并：AI 只产出受影响部分——天气/环境/前日摘要取原帧，宏观事件/信息包取 AI
     * （AI 为空时由机制紧急帧兜底该部分）。
     */
    private WorldFrameParse.FrameJson mergeEmergencyFrame(ActorWorldFrame current,
                                                          WorldFrameParse.FrameJson ai,
                                                          EmergencyInput input, Long gameDay) {
        WorldFrameParse.FrameJson fallback = mechanismEmergencyFrame(current.getProjectId(), gameDay,
                current, input);
        Map<String, WorldFrameParse.WeatherEntry> weather = parseWeatherFrom(current.getWeatherJson());
        List<WorldFrameParse.MacroEvent> macroEvents = ai.macroEvents() != null && !ai.macroEvents().isEmpty()
                ? ai.macroEvents() : fallback.macroEvents();
        List<WorldFrameParse.InfoPackage> packets = ai.infoPackages() != null && !ai.infoPackages().isEmpty()
                ? ai.infoPackages() : fallback.infoPackages();
        List<WorldFrameParse.EnvChange> env = parseEnvFrom(current.getEnvironmentChangesJson());
        return new WorldFrameParse.FrameJson(gameDay, weather, macroEvents, packets, env,
                ai.previousDaySummary() != null ? ai.previousDaySummary() : abbreviate(current.getPreviousDaySummary(), 1000),
                ai.ongoingEvents() != null ? ai.ongoingEvents() : new ArrayList<>());
    }

    /**
     * AI 调用公共骨架（world_frame_daily 模板渲染 → json_object 调用 → trace/用量 → 解析）。
     *
     * @return FrameJson（失败/无 JSON 返回 null）
     */
    private WorldFrameParse.FrameJson callAiFrame(Long userId, Long projectId, Long gameDay,
                                                  Map<String, Object> placeholders) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return AiCallContext.run(SCENE_FRAME, null, attempt, () -> {
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_WORLD_FRAME_DAILY, placeholders);
                    // 批6 收口（每日世界帧属「世界视角」）：前置 段1A【世界观】+ 段2D 当前世界动态（零阻断）
                    String prefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = prefix.isBlank() ? prompt : prefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(userId, projectId,
                            PromptTemplateService.CODE_WORLD_FRAME_DAILY, null);
                    if (sys == null || sys.isBlank()) {
                        sys = "你只输出严格的 JSON 对象，不输出任何其他文字。";
                    }
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            props.getTemperature(), props.getMaxTokens(), true);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            SCENE_FRAME, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    String json = JsonUtil.extractJson(result.content());
                    if (json == null) {
                        throw new BizException("AI 未返回有效 JSON");
                    }
                    return WorldFrameParse.parse(json, gameDay);
                });
            } catch (Exception e) {
                log.warn("[世界帧] AI 帧生成第 {} 次失败: project={} day={} : {}",
                        attempt, projectId, gameDay, e.getMessage());
            }
        }
        return null;
    }

    // ==================== 机制兜底通道 ====================

    /**
     * 机制每日帧（零 AI 成本，确定性）：天气按游戏日轮换 + 1 条宏观事件（模板池轮换，选点
     * 轮换地点）+ 由该事件衍生 1 条信息包 + 前日摘要延续。
     */
    private WorldFrameParse.FrameJson mechanismDailyFrame(Long projectId, Long gameDay,
                                                          ActorWorldFrame previous) {
        List<String> locations = locationNames(projectId);
        String location = pickLocation(locations, gameDay);
        String template = MECHANISM_EVENT_TEMPLATES[Math.floorMod(gameDay, MECHANISM_EVENT_TEMPLATES.length)];
        String summary = String.format(template, gameDay + 1, location);
        // 时间季节系统：机制帧天气按季节倾向确定性选择（游戏日取模加权）+ 气温按季基准修正
        String season = SeasonService.seasonOf(gameDay * 86400L);
        String condition = seasonService == null
                ? mechanismWeatherFallback(season, gameDay)
                : seasonService.pickMechanismWeather(projectId, season, gameDay);
        SeasonService.SeasonSkeleton sk = seasonService == null
                ? SeasonService.defaultSkeleton().get(season)
                : seasonService.skeletonOf(projectId, season);
        Double temperature = Math.round(SeasonService.seasonAdjustedTemp(season, sk.baseTempMid(), condition) * 10.0) / 10.0;
        String wind = SeasonService.windOf(season);
        Map<String, WorldFrameParse.WeatherEntry> weather = new LinkedHashMap<>();
        weather.put(location, new WorldFrameParse.WeatherEntry(condition, temperature, wind));
        List<WorldFrameParse.MacroEvent> macroEvents = List.of(new WorldFrameParse.MacroEvent(
                "E-" + gameDay + "-001", "日常异动", location, 2, summary, gameDay, 3L,
                List.of(location)));
        List<WorldFrameParse.InfoPackage> packets = List.of(new WorldFrameParse.InfoPackage(
                "I-" + gameDay + "-001-1", location,
                location + "传出日常异动，往来商旅偶有议论。", 0.6, List.of("商队"), 1.0,
                gameDay + 3, List.of("距离越远，越可能夸大"), null));
        return new WorldFrameParse.FrameJson(gameDay, weather, macroEvents, packets, new ArrayList<>(),
                previousDaySummaryText(previous), ongoingEventsList(previous));
    }

    /**
     * 机制紧急帧（AI 失败/关断时）：天气/环境保持原帧（就地语义），宏观事件=紧急事件 1 条
     * （重要度 4），信息包=由该事件衍生 1 条。
     */
    private WorldFrameParse.FrameJson mechanismEmergencyFrame(Long projectId, Long gameDay,
                                                              ActorWorldFrame current, EmergencyInput input) {
        String location = input.location() != null && !input.location().isBlank()
                ? input.location() : pickLocation(locationNames(projectId), gameDay);
        String type = input.eventType() != null && !input.eventType().isBlank()
                ? input.eventType() : "重大事件";
        String summary = input.summary() != null && !input.summary().isBlank()
                ? input.summary()
                : "第" + (gameDay + 1) + "日：" + location + "一带突发" + type + "，远近震动，消息正在传开。";
        List<WorldFrameParse.MacroEvent> macroEvents = List.of(new WorldFrameParse.MacroEvent(
                "E-" + gameDay + "-001e", type, location, 4, summary, gameDay, 5L,
                List.of(location)));
        List<WorldFrameParse.InfoPackage> packets = List.of(new WorldFrameParse.InfoPackage(
                "I-" + gameDay + "-001e-1", location, summary, 0.7, List.of("信使"), 0.8,
                gameDay + 3, List.of("距离越远，越可能夸大"), null));
        Map<String, WorldFrameParse.WeatherEntry> weather = parseWeatherFrom(current.getWeatherJson());
        List<WorldFrameParse.EnvChange> env = parseEnvFrom(current.getEnvironmentChangesJson());
        return new WorldFrameParse.FrameJson(gameDay, weather, macroEvents, packets, env,
                abbreviate(current.getPreviousDaySummary(), 1000), ongoingEventsList(current));
    }

    // ==================== 落库与事件发布 ====================

    /**
     * 落库：帧行（每日=新建 + 旧 active 置 superseded；紧急=就地更新同一行）→ 信息包实体化
     * （删旧建新）→ 宏观事件逐条发布 actor_event（kind=world_frame）。
     */
    private ActorWorldFrame persistFrame(Long projectId, Long gameDay, Long gameSecond,
                                         WorldFrameParse.FrameJson frame, String source, boolean emergency) {
        ActorWorldFrame target;
        if (emergency) {
            // 紧急就地更新：同一帧行（uk 保持成立）
            target = findActive(projectId);
            if (target == null) {
                throw new BizException(404, "当前无生效世界帧，无法紧急更新");
            }
        } else {
            // 每日生成：旧 active → superseded（保留历史可查）
            List<ActorWorldFrame> actives = frameRepository.findByProjectIdAndStatus(projectId, "active");
            for (ActorWorldFrame old : actives) {
                if (!old.getGameDay().equals(gameDay)) {
                    old.setStatus("superseded");
                    frameRepository.save(old);
                }
            }
            target = new ActorWorldFrame();
            target.setProjectId(projectId);
            target.setGameDay(gameDay);
        }
        target.setWeatherJson(toJson(frame.weather()));
        target.setMacroEventsJson(toJson(frame.macroEvents()));
        target.setEnvironmentChangesJson(toJson(frame.environmentChanges()));
        target.setInfoPacketsJson(toJson(frame.infoPackages()));
        target.setPreviousDaySummary(frame.previousDaySummary());
        target.setOngoingEventsJson(toJson(frame.ongoingEvents()));
        // 时间季节系统：季节按生成时刻游戏月固定绑定（不信任 AI 输出—— 「固定死的」）
        target.setSeason(SeasonService.seasonOf(gameSecond));
        target.setSource(source);
        target.setStatus("active");
        target.setGeneratedAtGameSecond(gameSecond);
        if (emergency) {
            target.setEmergency(1);
        }
        frameRepository.save(target);

        // 信息包实体化（删旧建新；每日生成 frameId 全新无旧行，紧急就地更新替换当日包）
        replacePackets(projectId, target.getId(), frame.infoPackages());

        // 宏观事件逐条发布（幂等键 frame:{projectId}:{gameDay}:{eventId}；无宏观事件发帧摘要兜底）
        publishFrameEvents(projectId, gameDay, frame, gameSecond, source);
        return target;
    }

    /**
     * 信息包实体化：删旧（同 frame_id）建新。：删包时级联清理该包的扩散状态行
     * （actor_info_diffusion 孤儿行不再参与感知空间过滤/回放）。
     */
    private void replacePackets(Long projectId, Long frameId, List<WorldFrameParse.InfoPackage> packages) {
        if (frameId == null) {
            return;
        }
        List<ActorInfoPacket> old = packetRepository.findByFrameId(frameId);
        if (!old.isEmpty()) {
            // （世界演化与游戏时间系统）：删旧包前级联清理其扩散行（紧急就地更新替换信息包时，
            // 旧包扩散状态失去意义——过滤/回放均以新包为准）
            for (ActorInfoPacket p : old) {
                if (p.getId() == null) {
                    continue;
                }
                List<ActorInfoDiffusion> ds = diffusionRepository.findByPacketId(p.getId());
                if (!ds.isEmpty()) {
                    diffusionRepository.deleteAll(ds);
                }
            }
            packetRepository.deleteAll(old);
        }
        if (packages == null || packages.isEmpty()) {
            return;
        }
        int kept = 0;
        for (WorldFrameParse.InfoPackage p : packages) {
            if (kept >= props.getMaxPacketsPerFrame()) {
                log.debug("[世界帧] 信息包超出每帧上限，丢弃多余包: project={} frame={} 上限={}",
                        projectId, frameId, props.getMaxPacketsPerFrame());
                break;
            }
            ActorInfoPacket e = new ActorInfoPacket();
            e.setFrameId(frameId);
            e.setProjectId(projectId);
            e.setInfoId(abbreviate(p.infoId(), 50));
            e.setOrigin(abbreviate(p.origin(), 100));
            e.setContent(p.content());
            e.setReliability(p.reliability());
            e.setSpreadChannelsJson(toJson(p.spreadChannels()));
            e.setSpreadSpeedDaysPerKm(p.spreadSpeedDaysPerKm());
            e.setValidUntilDay(p.validUntilDay());
            e.setDistortionRulesJson(toJson(p.distortionRules()));
            e.setVisibilityConditionJson(p.visibilityCondition() == null ? null
                    : toJson(p.visibilityCondition()));
            e.setStatus("active");
            packetRepository.save(e);
            kept++;
        }
    }

    /** 宏观事件逐条发布 actor_event（kind=world_frame、source=engine、global、幂等键带 eventId）。 */
    private void publishFrameEvents(Long projectId, Long gameDay, WorldFrameParse.FrameJson frame,
                                    Long gameSecond, String source) {
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        List<WorldFrameParse.MacroEvent> events = frame.macroEvents();
        if (events == null || events.isEmpty()) {
            // 无宏观事件 → 帧摘要兜底 1 条（重要度 2=信息级）
            worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind(KIND_WORLD_FRAME)
                    .source("engine")
                    .visibility("global")
                    .title("世界帧·第" + (gameDay + 1) + "日")
                    .content("第" + (gameDay + 1) + "日世界帧已生成（来源=" + source + "），今日无重大宏观事件。")
                    .importance(2)
                    .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                    .sourceKey("frame:" + projectId + ":" + gameDay + ":summary")
                    .build());
            return;
        }
        for (WorldFrameParse.MacroEvent e : events) {
            String title = (e.type() == null ? "世界帧事件" : e.type())
                    + (e.location() == null || e.location().isBlank() ? "" : "·" + e.location());
            String eventId = e.eventId() == null ? "E-" + gameDay + "-" + e.hashCode() : e.eventId();
            worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind(KIND_WORLD_FRAME)
                    .source("engine")
                    .visibility("global")
                    .locationName(e.location())
                    .title(abbreviate(title, 200))
                    .content(e.summary())
                    .importance(clamp(e.severity(), 1, 5))
                    .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                    .sourceKey("frame:" + projectId + ":" + gameDay + ":" + abbreviate(eventId, 50))
                    .build());
        }
    }

    // ==================== 素材组装（提示词输入） ====================

    /** 世界观文本（最高版本 world_setting：名称/题材/地理/规则/文化 + freeText 截断）。 */
    private String worldSettingText(Long projectId) {
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElse(null);
        if (ws == null) {
            return "（未配置世界观设定）";
        }
        StringBuilder sb = new StringBuilder();
        if (ws.getName() != null && !ws.getName().isBlank()) {
            sb.append("世界观名称：").append(ws.getName()).append("；");
        }
        if (ws.getGenre() != null && !ws.getGenre().isBlank()) {
            sb.append("题材：").append(ws.getGenre()).append("；");
        }
        if (ws.getEra() != null && !ws.getEra().isBlank()) {
            sb.append("时代：").append(ws.getEra()).append("；");
        }
        appendField(sb, "地理", ws.getGeography());
        appendField(sb, "势力", ws.getFactions());
        appendField(sb, "规则体系", ws.getMagicSystem());
        appendField(sb, "文化", ws.getCulture());
        appendField(sb, "历史", ws.getHistory());
        if (ws.getFreeText() != null && !ws.getFreeText().isBlank()) {
            sb.append("完整设定：").append(abbreviate(ws.getFreeText(), 800));
        }
        return sb.toString();
    }

    /** 前一日帧摘要（连续性输入；无前帧=首日说明）。 */
    private String previousDaySummaryText(ActorWorldFrame previous) {
        if (previous == null) {
            return "（本项目首日，无前一日世界帧）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(previous.getGameDay() + 1).append("日天气：")
                .append(abbreviate(previous.getWeatherJson(), 150));
        sb.append("；宏观事件：").append(abbreviate(previous.getMacroEventsJson(), 300));
        if (previous.getInfoPacketsJson() != null && !previous.getInfoPacketsJson().isBlank()) {
            sb.append("；信息包：").append(abbreviate(previous.getInfoPacketsJson(), 300));
        }
        return sb.toString();
    }

    /** 进行中事件文本（前一帧宏观事件 JSON 截断）。 */
    private String ongoingEventsText(ActorWorldFrame previous) {
        if (previous == null || previous.getMacroEventsJson() == null
                || previous.getMacroEventsJson().isBlank()) {
            return "（无进行中事件）";
        }
        return abbreviate(previous.getMacroEventsJson(), 400);
    }

    /** 进行中事件列表（机制帧输入缓存：解析前一帧宏观事件 JSON → OngoingEvent 列表）。 */
    private List<WorldFrameParse.OngoingEvent> ongoingEventsList(ActorWorldFrame previous) {
        List<WorldFrameParse.OngoingEvent> out = new ArrayList<>();
        if (previous == null || previous.getMacroEventsJson() == null
                || previous.getMacroEventsJson().isBlank()) {
            return out;
        }
        WorldFrameParse.FrameJson prev = WorldFrameParse.parse(
                "{\"macro_events\":" + previous.getMacroEventsJson() + "}", 0L);
        for (WorldFrameParse.MacroEvent e : prev.macroEvents()) {
            out.add(new WorldFrameParse.OngoingEvent(e.type(), e.location(), e.summary(), e.startDay()));
        }
        return out;
    }

    /** 前一日帧（game_day 小于当前日的最新一帧）。 */
    private ActorWorldFrame previousFrame(Long projectId, Long gameDay) {
        return frameRepository.findByProjectIdOrderByGameDayDesc(projectId).stream()
                .filter(f -> f.getGameDay() < gameDay)
                .findFirst()
                .orElse(null);
    }

    /** 当前生效帧（active；无 → null）。 */
    private ActorWorldFrame findActive(Long projectId) {
        return frameRepository.findByProjectIdAndStatus(projectId, "active").stream()
                .findFirst().orElse(null);
    }

    /** 项目特殊 NPC 名称列表（规则① 禁 NPC 行为校验素材）。 */
    private List<String> characterNames(Long projectId) {
        return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).stream()
                .map(ActorCharacter::getName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }

    /** 项目地点名列表。 */
    private List<String> locationNames(Long projectId) {
        return locationRepository.findByProjectId(projectId).stream()
                .map(ActorWorldLocation::getName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }

    /** 机制选点（按游戏日轮换；无地点回退「城中」）。 */
    private String pickLocation(List<String> locations, Long gameDay) {
        if (locations == null || locations.isEmpty()) {
            return "城中";
        }
        return locations.get(Math.floorMod(gameDay, locations.size()));
    }

    /** 从存储的 weather JSON 解析天气 Map（失败回退空）。 */
    private Map<String, WorldFrameParse.WeatherEntry> parseWeatherFrom(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        WorldFrameParse.FrameJson f = WorldFrameParse.parse("{\"weather\":" + json + "}", 0L);
        return f.weather();
    }

    /**
     * 帧实体 → FrameJson 解析（环境刺激 diff 用：四段 JSON 列合并后解析；
     * 失败回退空帧，diff 侧静默降级）。
     */
    private WorldFrameParse.FrameJson parseFrameJson(ActorWorldFrame f) {
        try {
            tools.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            root.set("weather", parseJsonNode(f.getWeatherJson()));
            root.set("macro_events", parseJsonNode(f.getMacroEventsJson()));
            root.set("info_packages", parseJsonNode(f.getInfoPacketsJson()));
            root.set("environment_changes", parseJsonNode(f.getEnvironmentChangesJson()));
            return WorldFrameParse.parse(objectMapper.writeValueAsString(root), f.getGameDay());
        } catch (Exception e) {
            return WorldFrameParse.FrameJson.empty(f == null ? 0L : f.getGameDay());
        }
    }

    /** 单段 JSON 列解析（null/非法返回 MissingNode，WorldFrameParse 容错为空段）。 */
    private tools.jackson.databind.JsonNode parseJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.missingNode();
        }
    }

    /** 从存储的环境 JSON 解析环境变化列表（失败回退空）。 */
    private List<WorldFrameParse.EnvChange> parseEnvFrom(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        WorldFrameParse.FrameJson f = WorldFrameParse.parse("{\"environment_changes\":" + json + "}", 0L);
        return f.environmentChanges();
    }

    /** 当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。 */
    private Long currentEngineGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 时间季节系统：季节上下文/更替事件 ====================

    /**
     * 帧生成 AI 提示词季节上下文段：季节名（游戏月）+ 气温区间 + 天气倾向 + 日出日落 +
     * 季节更替提示（前帧季节不同）。零 AI 纯文本组装；季节服务未装配回退内置默认骨架。
     */
    private String seasonContextText(Long projectId, Long gameSecond, ActorWorldFrame previous) {
        try {
            String season = SeasonService.seasonOf(gameSecond);
            SeasonService.SeasonSkeleton sk = seasonService == null
                    ? SeasonService.defaultSkeleton().get(season)
                    : seasonService.skeletonOf(projectId, season);
            long gameDay = Math.floorDiv(gameSecond == null ? 0L : gameSecond, 86400L);
            StringBuilder sb = new StringBuilder("当前季节：")
                    .append(SeasonService.seasonDisplayName(season))
                    .append("（游戏月 ").append(SeasonService.monthOf(gameSecond)).append("）")
                    .append("；气温区间 ").append(sk.tempRangeText())
                    .append("；天气倾向：").append(tendencyText(sk))
                    .append("；日出 ").append(SeasonService.minuteToText(
                            SeasonService.sunriseMinuteOf(sk, season, gameDay)))
                    .append(" 日落 ").append(SeasonService.minuteToText(
                            SeasonService.sunsetMinuteOf(sk, season, gameDay)));
            String prevSeason = previousSeasonOf(previous);
            if (prevSeason != null && !prevSeason.equals(season)) {
                sb.append("；⚠️ 昨日仍是").append(SeasonService.seasonDisplayName(prevSeason))
                        .append("，今日起季节更替为").append(SeasonService.seasonDisplayName(season))
                        .append("——请让今日天气/宏观事件贴合新季节的特征与变化");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[世界帧] 季节上下文组装失败（降级空串）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /** 天气倾向文本（"晴40/雨35/阴20/风5"；空回退空串）。 */
    private static String tendencyText(SeasonService.SeasonSkeleton sk) {
        StringBuilder sb = new StringBuilder();
        sk.weatherTendency().forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(k).append(v);
        });
        return sb.toString();
    }

    /** 前帧季节（存量帧无 season 列 → 按 game_day 时刻派生；无前帧返回 null）。 */
    private static String previousSeasonOf(ActorWorldFrame previous) {
        if (previous == null) {
            return null;
        }
        if (previous.getSeason() != null && !previous.getSeason().isBlank()) {
            return previous.getSeason().trim();
        }
        Long day = previous.getGameDay();
        if (day == null) {
            return null;
        }
        return SeasonService.seasonOf(day * 86400L);
    }

    /**
     * 季节更替事件发布（重要度 3：≥ 实时决策事件阈值——global 触发特殊 NPC 全员即时决策
     * 「感知季节变化」；< 帧紧急更新阈值 4 防紧急帧自触发；落 actor_event 作事件流/编年史素材）。
     * 幂等键 season:{projectId}:{gameDay}（每游戏日至多 1 条，天然去重）。
     */
    private void publishSeasonChange(Long projectId, Long gameDay, String prevSeason, String newSeason,
                                     Long gameSecond) {
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        String prevName = SeasonService.seasonDisplayName(prevSeason);
        String newName = SeasonService.seasonDisplayName(newSeason);
        worldEventBus.publish(userId, projectId, EventSpec.builder()
                .kind("world")
                .source("engine")
                .visibility("global")
                .title(newName + "日已至")
                .content("季节由" + prevName + "更替为" + newName + "：" + SeasonService.SEASON_FEATURES
                        .getOrDefault(newSeason, "天时流转"))
                .importance(3)
                .gameHour(gameSecond == null ? null : gameSecond / 3600L)
                .sourceKey("season:" + projectId + ":" + gameDay)
                .build());
        log.info("[世界帧] 季节更替事件已发布：project={} day={} {}→{}", projectId, gameDay, prevName, newName);
    }

    /** 机制帧天气兜底（季节服务未装配时按旧轮换池选择）。 */
    private static String mechanismWeatherFallback(String season, Long gameDay) {
        return MECHANISM_WEATHER[Math.floorMod(gameDay == null ? 0L : gameDay, MECHANISM_WEATHER.length)];
    }

    // ==================== VO 与私有辅助 ====================

    /** 帧 → VO（含解析后的结构化字段，供读接口）。 */
    private WorldFrameVO toVO(ActorWorldFrame f, Long projectId) {
        WorldFrameVO vo = new WorldFrameVO();
        vo.setGenerated(true);
        vo.setFrameId(f.getId());
        vo.setProjectId(f.getProjectId() == null ? projectId : f.getProjectId());
        vo.setGameDay(f.getGameDay());
        vo.setSeason(f.getSeason());
        vo.setWeatherJson(f.getWeatherJson());
        vo.setMacroEventsJson(f.getMacroEventsJson());
        vo.setEnvironmentChangesJson(f.getEnvironmentChangesJson());
        vo.setInfoPacketsJson(f.getInfoPacketsJson());
        vo.setPreviousDaySummary(f.getPreviousDaySummary());
        vo.setOngoingEventsJson(f.getOngoingEventsJson());
        vo.setSource(f.getSource());
        vo.setStatus(f.getStatus());
        vo.setEmergency(f.getEmergency());
        vo.setGeneratedAtGameSecond(f.getGeneratedAtGameSecond());
        vo.setCreatedAt(f.getCreatedAt());
        return vo;
    }

    /** 字段到摘要（非空才追加）。 */
    private void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(abbreviate(value, 400)).append("；");
        }
    }

    /** 对象 → JSON（失败回退 "null" 不抛异常）。 */
    private String toJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本截断（null 回退 null）。 */
    private String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    /** 数值钳制到 [min, max]。 */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 紧急更新输入（手动接口 body 或事件总线事件三要素）。 */
    record EmergencyInput(String eventType, String location, String summary) {
    }
}
