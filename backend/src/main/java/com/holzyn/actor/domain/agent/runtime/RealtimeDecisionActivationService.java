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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时决策激活闸门。
 * <p>需求（用户指示）：后端启动不应自动运行实时行为决策系统——<b>默认关</b>，
 * 只有打开前端页面、实际启动了世界演化 / 世界沙盘等功能时，才运行实时行为决策等系统；
 * 世界在「未激活」时自动停止，再次打开时从上次进度（last_game_second）延续推进。</p>
 * <p>实现：激活 = 手动常开（DB 持久化） ∪ 沙盘页打开（内存） ∪ 演化会话进行（内存）。</p>
 * <ul>
 * <li><b>手动常开</b>：`actor_world_engine.realtime_decision_enabled`（默认 0=关），用户手动开关
 * （沙盘页/仪表盘），持久化（本会话内重启后按 {@code holzyn.actor.world.runtime.auto-resume}
 * 决定是否恢复——<b>起默认 false=启动全静止，DB 常开标记仅当前会话 setManual 后生效</b>）；
 * 手动常开优先于页面自动态（任一成立即激活）；</li>
 * <li><b>沙盘页打开</b>：前端沙盘页挂载时 markOpen("sandbox")、离开时 markClose("sandbox")
 * （页面生命周期驱动，进程内存态）；</li>
 * <li><b>演化会话进行</b>：前端演化页打开/创建演化时 markOpen("evolution")、离开时 markClose
 * （进程内存态）；</li>
 * <li><b>消费方</b>：WorldEngine.advanceProject（未激活→跳过推进，等同 world_running=0）、
 * RealTimeDecisionService 三入口（未激活→不触发即时决策）、EngineScheduler.runDuePlans
 * （未激活→不执行到期计划）、EvolutionDramatizeTrigger（未激活→不自动演绎）；
 * <b>全模块门控</b>：记忆批量巩固 cron / L1 模糊维护 / 归档维护 /
 * L1 工作记忆维护等后台调度按项目激活跳过——未运行世界的记忆蒸馏、批量巩固、L3 核心
 * 自编辑等一律不发生，世界静止=数据静止。</li>
 * </ul>
 * <p>单实例运行约定：内存态重启丢失可接受（重启后默认全关，由前端页面再次打开时自动激活——
 * 恰为用户要求的「后端关闭即停、再打开才继续」语义）；进度延续由 last_game_second 持久化保证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeDecisionActivationService {

    /** 激活原因：沙盘页 */
    public static final String REASON_SANDBOX = "sandbox";

    /** 激活原因：世界演化 */
    public static final String REASON_EVOLUTION = "evolution";

    /**
     * 激活原因：Unity 连接（U1 服务端对接层 <b>独占激活</b>——连接期间
     * 世界由 Unity 时钟独占驱动，其他激活源不参与判定；断开后回退多源判定）
     */
    public static final String REASON_UNITY = "unity";

    /** 沙盘页打开态（projectId → 是否打开；页面生命周期驱动，重启清空=默认关） */
    private final ConcurrentHashMap<Long, Boolean> sandboxActive = new ConcurrentHashMap<>();

    /** 演化会话进行态（projectId → 是否打开；页面生命周期驱动，重启清空=默认关） */
    private final ConcurrentHashMap<Long, Boolean> evolutionActive = new ConcurrentHashMap<>();

    /**
     * Unity 连接态（projectId → 是否连接；WS 连接生命周期驱动，重启清空=默认关；
     * U1 独占语义：连接期间 isActive 只看本源）
     */
    private final ConcurrentHashMap<Long, Boolean> unityConnected = new ConcurrentHashMap<>();

    /** 手动常开态缓存（projectId → 是否常开；autoResume=true 时启动懒加载自 DB，否则仅本会话 setManual 写入） */
    private final ConcurrentHashMap<Long, Boolean> manualEnabledCache = new ConcurrentHashMap<>();

    /**
     * 世界运行时门控（用户需求）：重启后是否自动恢复上次「手动常开」状态。
     * <p>默认 <b>false=启动时所有世界一律静止</b>——DB 常开标记不自动生效，手动常开仅当前会话
     * 显式 setManual 后生效（配合引擎侧全模块门控，实现「刚启动后端所有模块不运行」）；
     * true=重启后按 actor_world_engine.realtime_decision_enabled 懒加载恢复。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.world.runtime.auto-resume:false}")
    private boolean autoResume;

    /** 测试/配置入口：覆盖 autoResume（同包测试用）。 */
    void setAutoResume(boolean value) {
        this.autoResume = value;
    }

    private final ActorWorldEngineRepository engineRepository;

    /** 世界时钟服务（激活时重置引擎回补基准——世界静止/接续语义；null=未装配[单测]跳过） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /**
     * 当前是否激活（引擎推进与实时决策的<b>唯一判定口径</b>）：
     * <b>Unity 连接（独占）</b>∪ 手动常开 ∪ 沙盘页打开 ∪ 演化会话进行。
     * <p>U1 独占语义：Unity 连接期间世界由 Unity 时钟独占驱动——
     * 激活判定只看 Unity 源，其他源（沙盘页/演化/手动常开）不参与判定；
     * 断开后回退多源判定（任一成立即激活）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return true=激活（世界可推进）；false=未激活（世界暂停）
     */
    public boolean isActive(Long projectId) {
        if (projectId == null) {
            return false;
        }
        if (Boolean.TRUE.equals(unityConnected.get(projectId))) {
            return true;
        }
        return isManualEnabled(projectId)
                || Boolean.TRUE.equals(sandboxActive.get(projectId))
                || Boolean.TRUE.equals(evolutionActive.get(projectId));
    }

    /**
     * 页面级激活（打开沙盘页/演化页时调用，幂等）。
     * <p><b>世界静止语义</b>：打开即「重新接续」——重置引擎回补基准为当前
     * 真实时刻，世界从冻结进度（last_game_second）接续推进，<b>不补打开前关闭期间落下的进度</b>。</p>
     *
     * @param projectId 项目 ID
     *
     * @param reason 激活原因：sandbox / evolution
     */
    public void markOpen(Long projectId, String reason) {
        if (projectId == null || reason == null) {
            return;
        }
        switch (reason) {
            case REASON_SANDBOX -> sandboxActive.put(projectId, true);
            case REASON_EVOLUTION -> evolutionActive.put(projectId, true);
            default -> log.warn("[实时决策激活] 未知激活原因：{}", reason);
        }
        log.info("[实时决策激活] 页面打开：project={} reason={} → active={}", projectId, reason, isActive(projectId));
        // 世界静止/接续语义：打开即重置引擎回补基准（不补离线进度）
        resetEngineAnchor(projectId);
    }

    /**
     * 页面级去激活（离开沙盘页/演化页时调用，幂等）。
     *
     * @param projectId 项目 ID
     *
     * @param reason 激活原因：sandbox / evolution
     */
    public void markClose(Long projectId, String reason) {
        if (projectId == null || reason == null) {
            return;
        }
        switch (reason) {
            case REASON_SANDBOX -> sandboxActive.put(projectId, false);
            case REASON_EVOLUTION -> evolutionActive.put(projectId, false);
            default -> log.warn("[实时决策激活] 未知关闭原因：{}", reason);
        }
        log.info("[实时决策激活] 页面关闭：project={} reason={} → active={}", projectId, reason, isActive(projectId));
    }

    /**
     * Unity 连接激活（U1 服务端对接层 WS 连接建立后调用，幂等）。
     * <p><b>独占语义</b>：连接期间 isActive 只看本源——世界由 Unity 时钟独占驱动；
     * 同时重置引擎回补基准（连接即接续，不补离线进度，与沙盘打开同语义）。</p>
     *
     * @param projectId 项目 ID
     */
    public void markUnityConnected(Long projectId) {
        if (projectId == null) {
            return;
        }
        unityConnected.put(projectId, true);
        log.info("[实时决策激活] Unity 连接：project={} → active={}（独占激活）", projectId, isActive(projectId));
        // 世界静止/接续语义：连接即重置引擎回补基准（不补离线进度）
        resetEngineAnchor(projectId);
    }

    /**
     * Unity 断连去激活（U1：WS 连接关闭后调用，幂等——同一项目最后一个连接关闭才生效）。
     * <p>断开后回退多源判定：若沙盘页/演化/手动常开任一仍激活，世界继续推进
     * （时钟权威回退内部自走）；否则世界静止（进度冻结在 last_game_second）。</p>
     *
     * @param projectId 项目 ID
     */
    public void markUnityDisconnected(Long projectId) {
        if (projectId == null) {
            return;
        }
        unityConnected.put(projectId, false);
        log.info("[实时决策激活] Unity 断开：project={} → active={}（回退多源判定）", projectId, isActive(projectId));
    }

    /**
     * Unity 连接态（状态视图/外部时钟判定用）。
     *
     * @param projectId 项目 ID
     *
     * @return true=至少一个 Unity 连接
     */
    public boolean isUnityConnected(Long projectId) {
        return projectId != null && Boolean.TRUE.equals(unityConnected.get(projectId));
    }

    /**
     * 手动常开开关（沙盘页/仪表盘手动开关，持久化；手动优先于页面自动态）。
     * <p><b>世界静止语义</b>：开启即「世界开始运行」——重置引擎回补基准为当前，
     * 从开启时刻起算推进（不补开启前积压）；关闭=世界静止（进度冻结）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param enabled true=常开（世界持续推进）；false=关（仅页面打开时激活）
     */
    public void setManual(Long projectId, boolean enabled) {
        if (projectId == null) {
            return;
        }
        ActorWorldEngine engine = engineRepository.findByProjectId(projectId).orElseGet(() -> {
            ActorWorldEngine ne = new ActorWorldEngine();
            ne.setProjectId(projectId);
            return ne;
        });
        engine.setRealtimeDecisionEnabled(enabled ? 1 : 0);
        engineRepository.save(engine);
        manualEnabledCache.put(projectId, enabled);
        log.info("[实时决策激活] 手动开关：project={} enabled={} → active={}", projectId, enabled, isActive(projectId));
        if (enabled) {
            // 世界静止/接续语义：手动开启即重置引擎回补基准（不补开启前积压）
            resetEngineAnchor(projectId);
        }
    }

    /**
     * 引擎回补基准重置（世界静止/接续语义核心）：把 last_engine_run_at 设为当前真实时刻，
     * 使 WorldEngine 回补量从「此刻」起算——关闭/未激活期间落下的进度不补推。
     * 委托 {@code WorldClockService.anchorEngineToNow}（未装配则跳过，不影响激活态本身）。
     *
     * @param projectId 项目 ID
     */
    private void resetEngineAnchor(Long projectId) {
        if (worldClockService != null) {
            try {
                worldClockService.anchorEngineToNow(projectId);
            } catch (Exception e) {
                // 锚点重置失败不影响激活（世界仍可推进，仅可能补一段离线时间）
                log.warn("[实时决策激活] 引擎回补基准重置失败（跳过）: project={} : {}", projectId, e.getMessage());
            }
        }
    }

    /**
     * 手动常开态（启动全静止语义：autoResume=false 时不读 DB，仅本会话 setManual 生效）。
     *
     * @param projectId 项目 ID
     *
     * @return true=手动常开
     */
    public boolean isManualEnabled(Long projectId) {
        if (projectId == null) {
            return false;
        }
        Boolean cached = manualEnabledCache.get(projectId);
        if (cached != null) {
            return cached;
        }
        // 世界运行时门控：autoResume=false（默认）时启动不自动恢复 DB 常开标记——
        // 所有世界启动即静止，手动常开仅在本会话显式 setManual 后生效
        if (!autoResume) {
            return false;
        }
        boolean value = engineRepository.findByProjectId(projectId)
                .map(e -> Integer.valueOf(1).equals(e.getRealtimeDecisionEnabled()))
                .orElse(false);
        manualEnabledCache.put(projectId, value);
        return value;
    }

    /** 沙盘页打开态（状态视图用） */
    public boolean isSandboxActive(Long projectId) {
        return projectId != null && Boolean.TRUE.equals(sandboxActive.get(projectId));
    }

    /** 演化会话进行态（状态视图用） */
    public boolean isEvolutionActive(Long projectId) {
        return projectId != null && Boolean.TRUE.equals(evolutionActive.get(projectId));
    }
}
