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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.sse.SseLifecycle;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.service.WorkflowLog;
import com.holzyn.actor.domain.project.service.WorldInitService;
import com.holzyn.actor.domain.project.vo.WorldInitResultVO;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import com.holzyn.actor.domain.worldgen.dto.CreateWorldGenTaskRequest;
import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenTask;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 完整项目串接编排器。
 * <p>职责：build 工作流 confirm(mode=full) 落库世界观后，编排「完整项目生成」流水线：</p>
 * <ol>
 * <li><b>角色生成</b>：自动创建并等待 character 工作流（阵容 + 逐角色深度 4 维）完成；
 * 等待期间轮询任务状态（每 2s，上限 4 小时）；失败 → 串接中断（可去任务控制台
 * retry/skip 后重新确认续跑）；</li>
 * <li><b>角色自动落库</b>：character 完成后调用 {@code WorldGenCharacterService.persistCharacters}
 * （actor_profile 角色行，幂等）；</li>
 * <li><b>故事规划</b>（_06）：自动创建并等待 story-plan 工作流（故事引擎三层力学 →
 * 风格档案 → 世界氛围提取，落库由脚本节点自动完成）；<b>失败不阻塞</b>——导演视角档案，
 * 世界运行不依赖，SSE 提示可后续补跑；</li>
 * <li><b>世界初始化 6 步</b>：调用 {@link WorldInitService#runInit}（建档[两段式幂等] /
 * 普通 NPC / 关系拓扑 / 世界时间 / 知识向量化 + 人物知识卡，进度经 pipeline SSE 转发）；</li>
 * <li><b>世界诞生编年史</b>：全部完成后写一条 source=worldgen 编年史（<b>不进角色感知</b>，零 OOC）。</li>
 * </ol>
 * <p>进度通道：以 buildTaskId 为 key 复用 {@link WorldGenTaskSseHub}（前端订阅
 * {@code GET /api/worldgen/pipelines/{buildTaskId}/stream}，事件约定与任务 SSE 一致）。</p>
 * <p>事务说明：本服务编排线程全程无事务（各阶段内部自管；AI 调用均事务外）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldGenPipelineService {

    /** 串接线程池（独立于任务 runner，防相互阻塞） */
    private static final ExecutorService PIPELINE_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "worldgen-pipeline");
        t.setDaemon(true);
        return t;
    });

    /** character 任务等待轮询间隔（毫秒；实例字段便于测试注入，默认 2s） */
    private long pollIntervalMs = 2000;

    /** character 任务等待上限（4 小时：阵容 + N 角色深度生成耗时随阵容规模增长） */
    private static final long CHAR_TASK_WAIT_MAX_MS = 4 * 60 * 60 * 1000L;

    private final ActorWorldgenTaskRepository taskRepository;
    private final WorldGenTaskService taskService;
    private final WorldGenCharacterService characterService;
    private final WorldInitService worldInitService;
    private final WorldMemoryService worldMemoryService;
    private final WorldGenTaskSseHub sseHub;
    private final CurrentUserProvider currentUserProvider;
    private final ActorWorldSettingRepository worldSettingRepository;

    /**
     * story-plan 工作流专用服务（story-plan 工作流 _06 字段注入——
     * 保持构造器签名不变，测试直接 new 时为空 → 流水线跳过故事规划阶段，零破坏）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldGenStoryPlanService storyPlanService;

    /**
     * 启动完整项目流水线（confirm(mode=full) 落库后调用）。
     * <p>同步创建 character 任务并返回其 ID（前端可据此回看角色生成任务）；串接后续
     * 阶段在独立线程异步执行，进度经 pipeline SSE（key=buildTaskId）广播。</p>
     *
     * @param projectId 项目 ID
     *
     * @param buildTaskId build 任务 ID（pipeline SSE channel key）
     *
     * @return character 任务 ID
     */
    public Long startFullPipeline(Long projectId, Long buildTaskId) {
        Long userId = currentUserProvider.currentUserId();
        // ① 创建 character 任务（世界设定自动注入）
        Map<String, String> inputs = characterService.buildCharacterInputs(projectId);
        Long charTaskId = taskService.createTask(new CreateWorldGenTaskRequest(
                projectId, WorldGenCharacterService.CODE_CHARACTER_V1, inputs,
                CreateWorldGenTaskRequest.MODE_WORLDVIEW_ONLY));
        sseHub.log(buildTaskId, "[完整项目] 世界观已落库，开始角色生成（character 工作流 #" + charTaskId + "）");
        PIPELINE_POOL.submit(() -> runFullPipeline(userId, projectId, buildTaskId, charTaskId));
        return charTaskId;
    }

    /**
     * 完整项目流水线主体（异步线程）：character 等待 → 角色落库 → 世界初始化 6 步 → 编年史。
     *
     * @param userId 归属用户 ID（请求线程取出，异步线程不再依赖请求上下文）
     *
     * @param projectId 项目 ID
     *
     * @param buildTaskId build 任务 ID（SSE channel key）
     *
     * @param charTaskId character 任务 ID
     */
    void runFullPipeline(Long userId, Long projectId, Long buildTaskId, Long charTaskId) {
        // ① 等待 character 任务完成
        String status = awaitTask(charTaskId);
        if (!WorldGenStatus.SUCCEEDED.equals(status)) {
            String reason = status == null ? "任务不存在" : "任务状态=" + status;
            log.warn("[完整项目] 角色生成失败：project={}, charTask={}, {}", projectId, charTaskId, reason);
            sseHub.fail(buildTaskId, "角色生成失败（任务 #" + charTaskId + "，" + reason
                    + "）——可在角色任务控制台重试/跳过节点后，重新执行「确认落库」续跑");
            return;
        }
        sseHub.log(buildTaskId, "[完整项目] 角色生成完成，开始自动落库角色…");
        // ② 角色自动落库（幂等）
        try {
            Map<String, Object> persisted = characterService.persistCharacters(charTaskId);
            sseHub.log(buildTaskId, "[完整项目] 角色落库完成：新建 " + persisted.get("created")
                    + " 位（跳过已存在 " + persisted.get("skipped") + " 位）");
        } catch (Exception e) {
            log.warn("[完整项目] 角色落库失败：project={}, error={}", projectId, e.getMessage());
            sseHub.fail(buildTaskId, "角色落库失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            return;
        }
        // ②·5 故事规划：创建 story-plan 任务 → 等待完成；
        // 失败/未装配<b>不阻塞</b>世界创建（导演视角档案，世界运行不依赖；落库由工作流
        // 脚本节点自动完成，SSE 提示可到故事任务控制台 retry/skip 后续跑）
        runStoryPlanStage(projectId, buildTaskId);
        // ③ 世界初始化 6 步（建档/普通 NPC/关系/时钟/知识；进度经 pipeline SSE 转发）
        try {
            WorldInitResultVO init = worldInitService.runInit(userId, projectId, false,
                    new PipelineWorkflowLog(buildTaskId, sseHub));
            sseHub.log(buildTaskId, "[完整项目] 世界初始化完成：地点 " + init.locations()
                    + " / 建档 " + init.cards() + " / 普通 NPC " + init.npcs()
                    + " / 关系 " + init.relations() + " / 世界时间 " + init.gameTimeText()
                    + " / 向量化 " + init.vectorized());
        } catch (Exception e) {
            log.warn("[完整项目] 世界初始化失败：project={}, error={}", projectId, e.getMessage());
            sseHub.fail(buildTaskId, "世界初始化失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage())
                    + "——可到项目空间「世界初始化」手动重跑补齐");
            return;
        }
        // ④ 世界诞生编年史（source=worldgen，不进角色感知，零 OOC；失败不阻断收尾）
        try {
            String worldName = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                    .map(ActorWorldSetting::getName).orElse("未知世界");
            boolean wrote = worldMemoryService.recordWorldGenChronicle(projectId,
                    "世界「" + worldName + "」诞生",
                    "世界「" + worldName + "」经由创意发散生成完整创建：世界观设定、核心角色、居民、关系与时间秩序均已就绪。");
            sseHub.log(buildTaskId, "[完整项目] 世界诞生记录已写入编年史（"
                    + (wrote ? "新增" : "已存在跳过") + "，仅存档、不进角色感知）");
        } catch (Exception e) {
            log.warn("[完整项目] 编年史记录失败（不阻断收尾）：project={}, error={}", projectId, e.getMessage());
        }
        sseHub.log(buildTaskId, "[完整项目] 世界「" + worldSettingRepository
                .findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorWorldSetting::getName).orElse("") + "」已完整生成：世界观 + 角色 + 居民 + 关系 + 时钟全部就绪");
        sseHub.complete(buildTaskId, SseLifecycle.EVENT_RESULT, Map.of(
                "status", WorldGenStatus.SUCCEEDED,
                "projectId", projectId,
                "characterTaskId", charTaskId));
    }

    /**
     * 故事规划阶段：创建 story-plan 任务（世界设定 +
     * 角色摘要自动注入）→ 等待终态。落库由工作流脚本节点自动完成；失败不阻塞（导演视角档案）。
     *
     * @param projectId 项目 ID
     *
     * @param buildTaskId build 任务 ID（SSE channel key）
     */
    private void runStoryPlanStage(Long projectId, Long buildTaskId) {
        if (storyPlanService == null) {
            sseHub.log(buildTaskId, "[完整项目] 故事规划阶段未装配（storyPlanService 缺失），跳过——不影响世界创建");
            return;
        }
        Long storyTaskId;
        try {
            Map<String, String> inputs = storyPlanService.buildStoryInputs(projectId);
            storyTaskId = taskService.createTask(new CreateWorldGenTaskRequest(
                    projectId, WorldGenStoryPlanService.CODE_STORY_PLAN_V1, inputs,
                    CreateWorldGenTaskRequest.MODE_WORLDVIEW_ONLY));
            sseHub.log(buildTaskId, "[完整项目] 开始故事规划（story-plan 工作流 #" + storyTaskId
                    + "：故事引擎 → 风格档案 → 世界氛围提取）…");
        } catch (Exception e) {
            log.warn("[完整项目] 故事规划任务创建失败（不阻塞）：project={}, error={}", projectId, e.getMessage());
            sseHub.log(buildTaskId, "[完整项目] 故事规划任务创建失败（不阻塞世界创建）："
                    + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            return;
        }
        String status = awaitTask(storyTaskId);
        if (!WorldGenStatus.SUCCEEDED.equals(status)) {
            log.warn("[完整项目] 故事规划未完成（不阻塞）：project={}, storyTask={}, status={}",
                    projectId, storyTaskId, status);
            sseHub.log(buildTaskId, "[完整项目] 故事规划未完成（任务 #" + storyTaskId + "，"
                    + (status == null ? "任务不存在" : "状态=" + status)
                    + "）——不阻塞世界创建；可到故事任务控制台重试/跳过节点后重新确认续跑");
        } else {
            sseHub.log(buildTaskId, "[完整项目] 故事规划完成：故事引擎 + 风格档案 + 世界氛围片段已落库"
                    + "（导演视角档案，角色不感知，零 OOC）");
        }
    }

    /**
     * 轮询等待任务终态（上限 {@link #CHAR_TASK_WAIT_MAX_MS}）。
     *
     * @param taskId 任务 ID
     *
     * @return 终态状态（succeeded/failed/canceled）；超时/任务不存在返回 null
     */
    String awaitTask(Long taskId) {
        long deadline = System.currentTimeMillis() + CHAR_TASK_WAIT_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            ActorWorldgenTask t = taskRepository.findById(taskId).orElse(null);
            if (t == null) {
                return null;
            }
            if (WorldGenStatus.isTaskTerminal(t.getStatus())) {
                return t.getStatus();
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warn("[完整项目] character 任务等待超时：task={}", taskId);
        return null;
    }

    /**
     * 世界初始化进度转发器：把 runInit 的日志/阶段转发到 pipeline SSE（key=buildTaskId）。
     */
    private static final class PipelineWorkflowLog implements WorkflowLog {

        private final Long buildTaskId;
        private final WorldGenTaskSseHub sseHub;

        PipelineWorkflowLog(Long buildTaskId, WorldGenTaskSseHub sseHub) {
            this.buildTaskId = buildTaskId;
            this.sseHub = sseHub;
        }

        @Override
        public void info(String message) {
            sseHub.log(buildTaskId, "[完整项目] " + message);
        }

        @Override
        public void stage(String name, int index, int total) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("index", index);
            data.put("total", total);
            sseHub.broadcast(buildTaskId, SseLifecycle.EVENT_STAGE, data);
        }
    }
}
