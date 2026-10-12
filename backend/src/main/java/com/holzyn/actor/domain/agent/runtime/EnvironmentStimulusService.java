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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.event.WorldEventSubscriber;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.service.NpcStateService;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldFrameParse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 环境刺激服务 ——刺激源④环境变化接入。
 * <p>定位：
 * 消费<b>世界帧产出</b>——天气/环境变化（帧 diff）+ 信息包扩散到达
 * （区域传闻）——把「环境变化」作为即时决策刺激源接入：特殊 NPC 全量/区域即时决策
 * （{@link StimulusType#ENVIRONMENT}），人群走轻量 AI（预算门控）。对齐目标「角色像真人」：
 * 天气骤变/环境异动时角色应有即时反应（收衣/避雨/议论/查看），而不只是【世界动态】段的被动注入。</p>
 * <p><b>检测双轨</b>：</p>
 * <ul>
 * <li><b>帧 diff</b>：{@link #onFrameChanged}——WorldFrameService 帧生成/紧急更新落库后挂钩，
 * 对比前一帧与当前帧的天气/环境变化 → 变化条目（≤ {@code environment-max-diff-per-frame}）；</li>
 * <li><b>每小时边界复查</b>：{@link #checkHourly}——WorldEngine 主决策周期每小时边界挂钩，
 * 对比当前 active 帧与上次 diff 快照（内存指纹幂等），捕捉未走帧钩子的就地变化（安全网）。</li>
 * <li><b>扩散到达</b>：{@link #onEvent}——WorldEventBus 订阅 kind=world_frame 且
 * sourceKey 前缀 {@code diffusion:} 的「区域传闻版」到达事件 → 区域匹配 NPC 即时决策。</li>
 * </ul>
 * <p><b>触发对象</b>：全局性刺激（天气/无区域变化）→ 全部特殊 NPC 即时决策；
 * 区域性刺激（环境变化带 scene / 扩散到达 region）→ 该区域特殊 NPC；人群走轻量 AI
 * （{@link NpcStateService#decideCrowdAction} 环境变体重载，预算门控，≤ {@code environment-crowd-max}）。</p>
 * <p><b>护栏全量</b>：① 环境刺激独立冷却（ENVIRONMENT 独立冷却键 + 独立时长 30 分钟，
 * RealTimeDecisionService 内实现）② 单帧 diff 条数上限（≤5）③ 同帧同类型去重（帧指纹幂等）
 * ④ 每小时复查与帧 diff 幂等去重（同一帧状态只 diff 一次）⑤ 每日环境刺激 AI 预算（默认 30/日）
 * ⑥ 既有冷却/重要度阈值/人群分层/预算门控/链式防抖全量生效（单测验证）。</p>
 * <p>宏观事件不做帧 diff（避免双触发）： 事件通道已消费 kind=world_frame 宏观事件
 * （severity≥3 + visibility=global → 全部特殊 NPC 即时决策），帧 diff 只补天气/环境变化缺口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvironmentStimulusService implements WorldEventSubscriber {

    /** 世界帧事件类别（与 WorldFrameService.KIND_WORLD_FRAME 同源） */
    private static final String KIND_WORLD_FRAME = "world_frame";

    /** 扩散到达事件 sourceKey 前缀（与 InfoDiffusionService 幂等键同源） */
    private static final String DIFFUSION_PREFIX = "diffusion:";

    /** 刺激文本摘要上限（reason/日志用） */
    private static final int TEXT_ABBREVIATE = 160;

    /** JSON 解析器（实体 → FrameJson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 项目环境刺激统计配置键：projectId → {gameDay, usedAI}（每日环境刺激 AI 预算计数，内存态） */
    private static final class DailyBudget {
        long gameDay = -1;
        int used;
    }

    /** 每项目帧 diff 快照：projectId → {fingerprint, lastFrame}（幂等去重基准） */
    private static final class FrameSnapshot {
        String fingerprint;
        WorldFrameParse.FrameJson lastFrame;
    }

    private final RealtimeDecisionProperties props;
    private final WorldEventBus worldEventBus;
    private final RealTimeDecisionService realTimeDecisionService;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorWorldFrameRepository frameRepository;
    private final BudgetGuard budgetGuard;

    /** 世界时钟（当前游戏秒回退；null=未装配） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /** 状态模块人群分层驱动（人群轻量 AI 决策；null=未装配跳过人群反应） */
    @Autowired(required = false)
    private NpcStateService npcStateService;

    /** 刺激日志服务（调试前端刺激日志页数据源；null=未装配跳过记录） */
    @Autowired(required = false)
    private StimulusLogService stimulusLogService;

    /** 帧 diff 快照表（内存态，单实例运行约定，重启丢失可接受——重启后首帧观察只记指纹不触发） */
    private final ConcurrentHashMap<Long, FrameSnapshot> frameSnapshots = new ConcurrentHashMap<>();

    /** 每日环境刺激 AI 预算计数表（内存态） */
    private final ConcurrentHashMap<Long, DailyBudget> dailyBudgets = new ConcurrentHashMap<>();

    // ==================== R1 引擎 AI 异步化：人群反应异步执行器 ====================
    // 实测（R1 冒烟）：08:00 边界天气帧变化 → reactCrowds 在引擎线程同步跑
    // decideCrowdAction（内部经 ensureSpace 触发 26~57s/次的同步空间 AI 生成），20 个普通 NPC
    // 串行 = 引擎线程被占死 8~19 分钟（jstack 实证 scheduling-1 阻塞在 okhttp HTTPS 读）。
    // 预算仍在引擎线程预扣（保持 tick 窗口记账语义），人群决策+执行提交到虚拟线程异步完成
    // （与 R1 预热/补发批次同口径：不进 tick 屏障、迟到落库可接受、失败隔离零阻断）。
    private volatile ExecutorService crowdExecutor;

    private ExecutorService crowdExecutor() {
        ExecutorService ex = crowdExecutor;
        if (ex != null) {
            return ex;
        }
        synchronized (this) {
            if (crowdExecutor == null) {
                crowdExecutor = Executors.newVirtualThreadPerTaskExecutor();
                log.info("[环境刺激] 人群反应异步执行器已创建（虚拟线程每任务一线程）");
            }
            return crowdExecutor;
        }
    }

    /** 引擎停机：关闭人群反应异步执行器（虚拟线程 daemon，仅及时释放资源）。 */
    @jakarta.annotation.PreDestroy
    public void shutdownCrowdExecutor() {
        ExecutorService ex = crowdExecutor;
        if (ex != null) {
            ex.shutdownNow();
            log.info("[环境刺激] 人群反应异步执行器已关闭");
        }
    }

    /**
     * 启动注册：注册为世界事件总线旁路订阅者（扩散到达事件 → 环境刺激；对齐
     * RealTimeDecisionService 模式，总线对订阅者异常隔离）。
     */
    @PostConstruct
    void register() {
        if (props.isEnvironmentEnabled()) {
            worldEventBus.subscribe(this);
            log.info("[环境刺激] 已注册世界事件总线订阅者（扩散到达 → 环境刺激）");
        }
    }

    // ==================== 检测双轨入口 ====================

    /**
     * 帧变化挂钩（WorldFrameService 帧生成/紧急更新落库后调用）—— 检测主通道。
     * <p>对比前一帧与当前帧（天气/环境变化）→ 变化条目 → 逐条发射环境刺激；
     * 帧内容指纹幂等（同一帧状态只 diff 一次，每日生成与每小时复查不重复触发）。
     * ⚠️ 前一帧须为<b>落库前快照</b>（紧急就地更新时前一帧实体与当前帧是同一持久化实例，
     * 落库后实体内容已变——WorldFrameService 在 persistFrame 前解析快照传入）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param previous 前一帧解析快照（每日生成=前一日帧；紧急更新=更新前快照；可空=首日/无基线）
     *
     * @param current 当前帧（刚生成/更新的解析结果）
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     */
    public void onFrameChanged(Long projectId, WorldFrameParse.FrameJson previous,
                               WorldFrameParse.FrameJson current, Long gameSecond) {
        if (!props.isEnvironmentEnabled() || projectId == null || current == null) {
            return;
        }
        Long nowSecond = gameSecond != null ? gameSecond : currentEngineGameSecond(projectId);
        try {
            String fingerprint = fingerprintOf(current);
            FrameSnapshot snap = frameSnapshots.get(projectId);
            if (snap != null && snap.fingerprint != null && snap.fingerprint.equals(fingerprint)) {
                return; // 同一帧状态已 diff 过（幂等）
            }
            WorldFrameParse.FrameJson prev = previous != null ? previous
                    : (snap != null && snap.lastFrame != null ? snap.lastFrame
                    : WorldFrameParse.FrameJson.empty(0L));
            List<EnvChangeEntry> changes = diffFrames(prev, current, props.getEnvironmentMaxDiffPerFrame());
            FrameSnapshot ns = new FrameSnapshot();
            ns.fingerprint = fingerprint;
            ns.lastFrame = current;
            frameSnapshots.put(projectId, ns);
            if (changes.isEmpty()) {
                log.debug("[环境刺激] 帧变化无环境差异，跳过: project={}", projectId);
                return;
            }
            log.info("[环境刺激] 帧变化检测 {} 条: project={}", changes.size(), projectId);
            emitChanges(projectId, changes, nowSecond);
        } catch (Exception e) {
            log.warn("[环境刺激] 帧变化 diff 失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 每小时边界复查（WorldEngine 主决策周期每小时挂钩）—— 检测次通道（安全网）。
     * <p>解析当前 active 帧 → onFrameChanged（内部帧指纹幂等：指纹未变直接返回；变了则以
     * 上次 diff 快照为基线重新 diff 发射——捕捉未走帧钩子的就地变化，如未来新写入路径）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（引擎推进位置）
     */
    public void checkHourly(Long projectId, Long gameSecond) {
        if (!props.isEnvironmentEnabled() || projectId == null) {
            return;
        }
        try {
            ActorWorldFrame active = findActive(projectId);
            if (active == null) {
                return;
            }
            onFrameChanged(projectId, null, parseFrame(active), gameSecond);
        } catch (Exception e) {
            log.warn("[环境刺激] 每小时复查失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 世界事件总线订阅回调（扩散到达 → 环境刺激）。
     * <p>过滤：kind=world_frame 且 sourceKey 前缀 diffusion:（信息包扩散到达「区域传闻版」事件，
     * importance=1—— 事件通道阈值 3 不触发，本服务特判接入）；区域=事件 locationName。
     * 对齐 （帧 diff + 扩散到达双轨）。</p>
     *
     * @param event 已落库的世界事件
     */
    @Override
    public void onEvent(com.holzyn.actor.domain.world.entity.ActorEvent event) {
        if (!props.isEnvironmentEnabled() || event == null || event.getProjectId() == null) {
            return;
        }
        if (!KIND_WORLD_FRAME.equalsIgnoreCase(safe(event.getKind()))
                || event.getSourceKey() == null || !event.getSourceKey().startsWith(DIFFUSION_PREFIX)) {
            return;
        }
        Long projectId = event.getProjectId();
        String region = event.getLocationName();
        String text = "你听到传闻：" + (event.getTitle() == null || event.getTitle().isBlank()
                ? safe(event.getContent()) : event.getTitle());
        Long gameSecond = event.getGameHour() == null ? null : event.getGameHour() * 3600L;
        Long nowSecond = gameSecond != null ? gameSecond : currentEngineGameSecond(projectId);
        log.info("[环境刺激] 扩散到达 → 环境刺激: project={} region={} 事件={}", projectId, region, event.getId());
        emitChange(projectId, "diffusion:" + event.getSourceKey(), text, region, nowSecond);
    }

    // ==================== 刺激发射（实例） ====================

    /** 逐条发射帧变化条目（含每日预算/目标解析/人群轻量 AI）。 */
    private void emitChanges(Long projectId, List<EnvChangeEntry> changes, Long gameSecond) {
        for (EnvChangeEntry c : changes) {
            emitChange(projectId, c.key(), c.text(), c.region(), gameSecond);
        }
    }

    /**
     * 发射单条环境刺激：护栏⑤ 每日 AI 预算 → 特殊 NPC 目标解析（全局/区域）→
     * 逐角色 RealTimeDecisionService.onStimulus（内部再经激活闸门/冷却/预算预检）→
     * 人群轻量 AI（预算门控，≤ environment-crowd-max）。
     */
    private void emitChange(Long projectId, String key, String text, String region, Long gameSecond) {
        // 护栏⑤：每日环境刺激 AI 预算（超限跳过，0=不设限）
        if (!consumeEnvironmentDaily(projectId, gameSecond)) {
            log.info("[环境刺激] 每日 AI 预算耗尽，跳过刺激: project={} 条目={}", projectId, abbreviate(text, 60));
            logStimulus(projectId, gameSecond, "environment", null, text, "daily_budget", "每日环境刺激 AI 预算耗尽");
            return;
        }
        // 特殊 NPC 目标解析：全局（region 空）→ 全部；区域 → 匹配区域
        // 长跑抓修：排除已故角色（死人不应被环境刺激打扰）
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        boolean global = region == null || region.isBlank();
        List<ActorCharacter> targets = new ArrayList<>();
        for (ActorCharacter ch : chars) {
            if (ch == null || Integer.valueOf(1).equals(ch.getDeleted()) || ch.isDeceased()) {
                continue;
            }
            if (global || matchesRegion(ch.getLocation(), region)) {
                targets.add(ch);
            }
        }
        if (targets.isEmpty() && !global) {
            log.debug("[环境刺激] 无区域匹配特殊 NPC，跳过: project={} region={}", projectId, region);
        }
        for (ActorCharacter ch : targets) {
            String ref = com.holzyn.actor.common.NpcRefs.of(ch.getId());
            // ⚠️ 冒烟抓修：此处<b>不</b>记 "triggered" 刺激日志——onStimulus 内部
            // 还会被激活闸门/冷却/预算预检拦截，乐观记录会与 RealTimeDecisionService 的真实结果
            // 重复（同一刺激显示 triggered+budget 两条）；真实结果由 RealTimeDecisionService 记录
            realTimeDecisionService.onStimulus(projectId, ref, StimulusType.ENVIRONMENT, text, gameSecond);
        }
        // 人群轻量 AI（预算门控；受影响区域人群，上限 environment-crowd-max）
        reactCrowds(projectId, region, text, gameSecond);
    }

    /**
     * 人群轻量 AI 反应。
     * <p>受影响区域人群（全局刺激取前 environment-crowd-max 个有位置者）逐人：
     * 场景 AI 门控（isCrowdAiEligible）+ BudgetGuard.tryConsume → decideCrowdAction（环境变体重载）→
     * applyCrowdAction 真执行；预算不足/未装配跳过（人群反应是旁路增强，不阻断环境刺激主流程）。</p>
     * <p>⚠️ R1：人群决策+执行改为<b>异步执行</b>——decideCrowdAction 内部经
     * {@code ensureSpace} 可能触发 26~57s/次的同步空间 AI 生成（实测 08:00 边界天气帧变化 → 20 个
     * 普通 NPC 串行 = 引擎线程被占死 8~19 分钟）；预算仍在引擎线程预扣（保持 tick 窗口记账语义），
     * 决策在虚拟线程完成，迟到落库可接受（与 R1 预热/补发批次同口径）。</p>
     */
    private void reactCrowds(Long projectId, String region, String text, Long gameSecond) {
        if (npcStateService == null || ordinaryNpcRepository == null) {
            return;
        }
        try {
            List<ActorCharacter> npcs = ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId);
            if (npcs.isEmpty()) {
                return;
            }
            boolean global = region == null || region.isBlank();
            int limit = Math.max(0, props.getEnvironmentCrowdMax());
            int done = 0;
            for (ActorCharacter n : npcs) {
                if (done >= limit) {
                    break;
                }
                if (!global && !matchesRegion(n.getLocation(), region)) {
                    continue;
                }
                if (!npcStateService.isCrowdAiEligible(projectId, com.holzyn.actor.common.NpcRefs.of(n.getId()))) {
                    continue;
                }
                if (budgetGuard == null || !budgetGuard.tryConsume(projectId)) {
                    log.debug("[环境刺激] 人群预算不足，跳过 AI 反应: crowd:{}", n.getId());
                    continue;
                }
                done++;
                submitCrowdReaction(projectId, n, text, gameSecond);
            }
        } catch (Exception e) {
            log.warn("[环境刺激] 人群反应失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 异步提交单个普通 NPC 的环境刺激反应（R1）。
     * <p>预算已在引擎线程预扣；任务内重查角色实体（避免脱管实体跨线程问题），决策+执行在虚拟线程
     * 完成；失败隔离零阻断（人群反应是旁路增强）。</p>
     */
    private void submitCrowdReaction(Long projectId, ActorCharacter n, String text, Long gameSecond) {
        final Long npcId = n.getId();
        final String npcRef = com.holzyn.actor.common.NpcRefs.of(npcId);
        ExecutorService ex = crowdExecutor();
        try {
            ex.submit(() -> {
                try {
                    // 重查实体：引擎线程传入的实体可能已脱管（异步跨线程语义，读最新行最稳）
                    ActorCharacter fresh = ordinaryNpcRepository.findById(npcId).orElse(n);
                    var drive = npcStateService.decideCrowdAction(projectId, fresh, gameSecond, text);
                    if (drive != null) {
                        npcStateService.applyCrowdAction(projectId, fresh, drive, gameSecond);
                        logStimulus(projectId, gameSecond, "environment", npcRef, text,
                                "crowd_ai", "人群轻量 AI 反应");
                    }
                } catch (Exception e) {
                    log.warn("[环境刺激] 人群异步反应失败（隔离继续）: project={} ref={} : {}",
                            projectId, npcRef, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[环境刺激] 人群异步反应提交失败（跳过该 NPC）: project={} ref={} : {}",
                    projectId, npcRef, e.getMessage());
        }
    }

    // ==================== 静态纯逻辑（可单测） ====================

    /** 环境变化条目（key=幂等去重键 / text=刺激文本 / region=影响区域[空=全局]）。 */
    public record EnvChangeEntry(String key, String text, String region) {
    }

    /**
     * 帧 diff（纯静态可单测）：对比前一帧与当前帧的天气/环境变化。
     * <ul>
     * <li><b>天气</b>：按区域对比 condition（+温度差 ≥5°）变化 → 「天气变化：{区域}：{旧} → {新}」；
     * 区域视为变化（前帧无该区域）;</li>
     * <li><b>环境变化</b>：当前帧新增（前帧无同 scene+change）的条目 →
     * 「环境变化：{scene}：{change}（影响：{effect}）」；消失/未变不触发；</li>
     * <li>按 天气→环境变化 顺序排列，总数截断到 maxEntries（护栏②）；宏观事件不做 diff
     * 。</li>
     * </ul>
     *
     * @param prev 前一帧（可空=空帧）
     *
     * @param curr 当前帧（可空=空帧）
     *
     * @param maxEntries 条目上限（<=0 视为不截断）
     *
     * @return 变化条目列表（有序，可能为空）
     */
    public static List<EnvChangeEntry> diffFrames(WorldFrameParse.FrameJson prev, WorldFrameParse.FrameJson curr,
                                                  int maxEntries) {
        List<EnvChangeEntry> out = new ArrayList<>();
        WorldFrameParse.FrameJson p = prev == null ? WorldFrameParse.FrameJson.empty(0L) : prev;
        WorldFrameParse.FrameJson c = curr == null ? WorldFrameParse.FrameJson.empty(0L) : curr;
        // ① 天气变化（按区域）
        Map<String, WorldFrameParse.WeatherEntry> cw = c.weather() == null ? Map.of() : c.weather();
        Map<String, WorldFrameParse.WeatherEntry> pw = p.weather() == null ? Map.of() : p.weather();
        for (Map.Entry<String, WorldFrameParse.WeatherEntry> e : cw.entrySet()) {
            if (out.size() >= maxEntries && maxEntries > 0) {
                break;
            }
            String region = e.getKey();
            WorldFrameParse.WeatherEntry cur = e.getValue();
            WorldFrameParse.WeatherEntry old = pw.get(region);
            if (cur == null || cur.condition() == null || cur.condition().isBlank()) {
                continue;
            }
            boolean changed = old == null
                    || !safe(old.condition()).equals(safe(cur.condition()))
                    || tempGap(old.temperature(), cur.temperature()) >= 5;
            if (!changed) {
                continue;
            }
            String from = old == null ? "（前帧无此区域）"
                    : old.condition() + tempText(old.temperature());
            String to = cur.condition() + tempText(cur.temperature());
            String text = "天气变化：" + safe(region) + "：" + from + " → " + to;
            // ⚠️ 天气变化按<b>全局</b>定位（region=null）：天气是天空级现象，覆盖整个区域——
            // 区域名（如「青禾村」）与 NPC 具体所在地（如「集市」）未必匹配，若按区域匹配会漏触发；
            // 对齐 「全局性刺激（天气骤变）→ 全部特殊 NPC 即时决策」（文本仍含区域名）
            out.add(new EnvChangeEntry("weather:" + safe(region), text, null));
        }
        // ② 环境变化（当前帧新增条目）
        List<WorldFrameParse.EnvChange> ce = c.environmentChanges() == null ? new ArrayList<>() : c.environmentChanges();
        List<WorldFrameParse.EnvChange> pe = p.environmentChanges() == null ? new ArrayList<>() : p.environmentChanges();
        Set<String> prevKeys = new LinkedHashSet<>();
        for (WorldFrameParse.EnvChange old : pe) {
            if (old != null) {
                prevKeys.add(envKey(old));
            }
        }
        for (WorldFrameParse.EnvChange e : ce) {
            if (out.size() >= maxEntries && maxEntries > 0) {
                break;
            }
            if (e == null || e.change() == null || e.change().isBlank()) {
                continue;
            }
            if (prevKeys.contains(envKey(e))) {
                continue; // 已存在的环境变化不重复触发
            }
            String scene = e.scene() == null || e.scene().isBlank() ? "" : e.scene();
            String text = "环境变化：" + (scene.isBlank() ? "某处" : scene) + "：" + e.change().trim()
                    + (e.effect() == null || e.effect().isBlank() ? "" : "（影响：" + e.effect().trim() + "）");
            out.add(new EnvChangeEntry("env:" + envKey(e), text, scene));
        }
        return out;
    }

    /**
     * 区域匹配（纯静态可单测）：角色所在地名与变化区域的双向包含/相等判定——
     * 对齐「同地点」的宽松语义（如角色在「青禾村·集市」，变化区域「青禾村」→ 匹配）。
     *
     * @param location 角色所在地名（可空）
     *
     * @param region 变化区域名（可空）
     *
     * @return true 表示该角色位于受影响区域（空白区域视为全局匹配由调用方处理，此处返回 false）
     */
    public static boolean matchesRegion(String location, String region) {
        if (region == null || region.isBlank() || location == null || location.isBlank()) {
            return false;
        }
        String loc = location.trim();
        String reg = region.trim();
        return loc.equalsIgnoreCase(reg)
                || loc.contains(reg)
                || reg.contains(loc);
    }

    // ==================== 私有辅助 ====================

    /** 每日环境刺激 AI 预算消耗（护栏⑤）：跨游戏日自动重置；0=不设限。 */
    private boolean consumeEnvironmentDaily(Long projectId, Long gameSecond) {
        int max = props.getEnvironmentDailyMax();
        if (max <= 0) {
            return true;
        }
        long day = gameSecond == null ? 0L : Math.floorDiv(gameSecond, 86400L);
        DailyBudget b = dailyBudgets.computeIfAbsent(projectId, k -> new DailyBudget());
        if (b.gameDay != day) {
            b.gameDay = day;
            b.used = 0;
        }
        if (b.used >= max) {
            return false;
        }
        b.used++;
        return true;
    }

    /**
     * 帧内容指纹（幂等去重基准）：gameDay + 四段 JSON 序列化哈希——紧急就地更新改变内容
     * → 指纹变化 → 重新 diff 发射；内容未变（同日重复生成同状态）→ 指纹不变不重复触发。
     */
    private static String fingerprintOf(WorldFrameParse.FrameJson f) {
        try {
            String json = MAPPER.writeValueAsString(f);
            return (f.gameDay() == null ? 0L : f.gameDay()) + ":" + Integer.toHexString(json.hashCode());
        } catch (Exception e) {
            return (f.gameDay() == null ? 0L : f.gameDay()) + ":err";
        }
    }

    /** 实体 → FrameJson（四段 JSON 合并解析；失败回退空帧）。 */
    private static WorldFrameParse.FrameJson parseFrame(ActorWorldFrame f) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.set("weather", parseNode(f.getWeatherJson()));
            root.set("macro_events", parseNode(f.getMacroEventsJson()));
            root.set("info_packages", parseNode(f.getInfoPacketsJson()));
            root.set("environment_changes", parseNode(f.getEnvironmentChangesJson()));
            return WorldFrameParse.parse(MAPPER.writeValueAsString(root), f.getGameDay());
        } catch (Exception e) {
            return WorldFrameParse.FrameJson.empty(f == null ? 0L : f.getGameDay());
        }
    }

    /** 单段 JSON 列解析（null/非法返回 MissingNode，WorldFrameParse 容错为空段）。 */
    private static JsonNode parseNode(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.missingNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.missingNode();
        }
    }

    /** 环境变化条目幂等键（scene|change 归一）。 */
    private static String envKey(WorldFrameParse.EnvChange e) {
        return safe(e.scene()) + "|" + safe(e.change());
    }

    /** 温度差（缺省视为 0）。 */
    private static double tempGap(Double a, Double b) {
        if (a == null && b == null) {
            return 0;
        }
        double x = a == null ? 0 : a;
        double y = b == null ? 0 : b;
        return Math.abs(x - y);
    }

    /** 温度文本（null 回退空）。 */
    private static String tempText(Double t) {
        return t == null ? "" : " " + t.intValue() + "°";
    }

    /** 当前生效帧（active；无 → null）。 */
    private ActorWorldFrame findActive(Long projectId) {
        return frameRepository.findByProjectIdAndStatus(projectId, "active").stream()
                .findFirst().orElse(null);
    }

    /** 当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。 */
    private Long currentEngineGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 刺激日志记录（调试前端刺激日志页数据源；失败静默）。 */
    private void logStimulus(Long projectId, Long gameSecond, String type, String npcRef, String text,
                             String result, String detail) {
        if (stimulusLogService != null) {
            try {
                stimulusLogService.log(projectId, gameSecond, type, npcRef, text, result, detail);
            } catch (Exception e) {
                log.debug("[环境刺激] 刺激日志记录失败（跳过）: {}", e.getMessage());
            }
        }
    }

    /** 安全非空（null 回退空串）。 */
    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** 文本摘要。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
