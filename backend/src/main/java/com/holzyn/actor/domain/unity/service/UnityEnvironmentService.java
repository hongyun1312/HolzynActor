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

package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.EnvironmentStimulusService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.service.EnvironmentContextService;
import com.holzyn.actor.domain.world.service.SeasonService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldFrameParse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unity 环境数据适配服务（U 轨道 U1 服务端对接层，§6 插件化预留切换点 2 兑现，）。
 * <p><b>环境数据源切换</b>：
 * Unity 上报 {@code environment{weather, temperature?, wind?}} → 本服务就地改写当前
 * active 世界帧 weather_json（condition=上报天气文本，temperature/wind 显式上报则覆盖）——
 * 与 {@code WeatherSwitchService}（沙盘天气切换）<b>同语义</b>：不新建帧、不依赖 AI；
 * 联动三件事：</p>
 * <ul>
 * <li><b>环境刺激</b>——事务提交后异步 diff（复用 EnvironmentStimulusService.onFrameChanged，
 * 帧指纹幂等），NPC 感知 Unity 端天气变化做出真实反应；</li>
 * <li><b>世界事件</b>——发布 kind=world / source=unity 事件（重要度 2：仅可视化，
 * NPC 反应唯一来源=环境刺激通道，防双触发）；</li>
 * <li><b>SSE weather 推送</b>——经 WorldSandboxSseHub（前端/Unity 下行同步更新天气表现）。</li>
 * </ul>
 * <p>季节/昼夜由 Unity 驱动的时钟自动推出（环境上下文计算零改动——loadFrameWeather
 * 天然读到被覆盖的帧天气）；断开后回退内部确定性计算（帧天气保持最后一次生效值，
 * 引擎内部计算作为 External 缺失时兜底）。</p>
 * <p>⚠️ 帧不存在（世界尚未推进出每日帧）时上报仅记日志不落库（与天气切换同口径：
 * 环境权威生效前提=已有世界帧载体）。</p>
 */
@Slf4j
@Service
public class UnityEnvironmentService {

    /** 天气变化事件类别/来源（kind=world 引擎世界事件；source=unity 区分 Unity 上报） */
    private static final String KIND_WEATHER = "world";
    private static final String SOURCE_WEATHER = "unity";

    /** 事件重要度 2：低于实时决策事件阈值 3 与帧紧急更新阈值 4——仅可视化不触发双通道决策（NPC 反应走环境刺激通道） */
    private static final int EVENT_IMPORTANCE = 2;

    /** 默认生效区域（帧内无此区域则取首个区域，仍无则新建「主区域」；与天气切换同口径） */
    private static final String REGION_DEFAULT = "主区域";

    /** 上报天气文本长度上限（防异常大文本写库） */
    private static final int CONDITION_MAX_LEN = 20;

    /** 异步联动执行器（虚拟线程：环境刺激含真实 AI 调用，不占 WS 容器线程） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final ActorWorldFrameRepository frameRepository;
    private final ObjectMapper objectMapper;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;
    private final WorldClockService worldClockService;

    /** 环境刺激服务（帧变化 → 天气 diff → NPC 环境刺激；字段注入避免改构造器，null=跳过） */
    @Autowired(required = false)
    private EnvironmentStimulusService environmentStimulusService;

    /** 沙盘 SSE 中心（weather 事件推送，Unity 下行同源；字段注入避免构造循环，null=跳过） */
    @Autowired(required = false)
    private WorldSandboxSseHub sandboxSseHub;

    /** 统一环境服务（体感气温计算/润色预生成；字段注入，null=跳过） */
    @Autowired(required = false)
    private EnvironmentContextService environmentContextService;

    public UnityEnvironmentService(ActorWorldFrameRepository frameRepository, ObjectMapper objectMapper,
                                   WorldEventBus worldEventBus, CurrentUserProvider currentUserProvider,
                                   WorldClockService worldClockService) {
        this.frameRepository = frameRepository;
        this.objectMapper = objectMapper;
        this.worldEventBus = worldEventBus;
        this.currentUserProvider = currentUserProvider;
        this.worldClockService = worldClockService;
    }

    /**
     * 环境上报入口（WS environment 消息消费）。
     *
     * @param projectId 项目 ID
     *
     * @param weather 天气文本（必填，如 晴/雨/雪/阴/雷雨/小雪…，≤20 字）
     *
     * @param temperature 气温（℃；可空=保留帧温）
     *
     * @param wind 风力描述（可空=保留帧风）
     *
     * @return 处理结果（status: invalid/no_frame/applied）
     */
    @Transactional
    public UnityEnvironmentResult applyEnvironmentReport(Long projectId, String weather, Double temperature, String wind) {
        if (projectId == null || weather == null || weather.isBlank()) {
            return new UnityEnvironmentResult("invalid", null, null, "environment 缺少有效 weather");
        }
        String cond = weather.trim();
        if (cond.length() > CONDITION_MAX_LEN) {
            cond = cond.substring(0, CONDITION_MAX_LEN);
        }
        // lambda 需 effectively final（cond 在上方可能被截断重赋值，此处固化日志快照）
        final String logCond = cond;
        ActorWorldFrame frame = frameRepository.findByProjectIdAndStatus(projectId, "active")
                .stream().findFirst()
                .orElseGet(() -> {
                    log.warn("[Unity环境] 无生效世界帧，上报仅记日志: project={} weather={}", projectId, logCond);
                    return null;
                });
        if (frame == null) {
            return new UnityEnvironmentResult("no_frame", null, null, "尚无生效世界帧（请先推进世界）");
        }
        Long nowSecond = worldClockService.currentEngineGameSecond(projectId);

        // ① 就地改写 weather_json（保留未上报字段；与天气切换同语义）
        ObjectNode weatherNode = parseWeather(frame.getWeatherJson());
        String region = REGION_DEFAULT;
        if (!weatherNode.has(REGION_DEFAULT)) {
            Iterator<Map.Entry<String, JsonNode>> it = weatherNode.properties().iterator();
            if (it.hasNext()) {
                region = it.next().getKey();
            } else {
                weatherNode.putObject(REGION_DEFAULT);
            }
        }
        ObjectNode entry = weatherNode.get(region) instanceof ObjectNode on ? on : weatherNode.putObject(region);
        String old = textOf(entry.get("condition"));
        entry.put("condition", cond);
        if (temperature != null) {
            entry.put("temperature", Math.round(temperature * 10.0) / 10.0);
        }
        if (wind != null && !wind.isBlank()) {
            entry.put("wind", wind.trim());
        }
        String oldWeatherJson = frame.getWeatherJson();
        String newWeatherJson = toJson(weatherNode);
        frame.setWeatherJson(newWeatherJson);
        frame.setEmergency(1); // 就地改写标记（对齐紧急更新语义，不新建帧）
        frameRepository.save(frame);
        log.info("[Unity环境] 帧天气覆盖：project={} 区域={} 「{}」→「{}」 温度={}℃ 风={} second={}",
                projectId, region, blankTo(old, "未知"), cond, temperature, wind, nowSecond);

        // ② 事务提交后异步联动（环境刺激 + 世界事件 + SSE 推送 + 润色预生成；毫秒级返回）
        // （lambda 需 effectively final：region/cond 可能被上文的截断/回退逻辑重赋值，此处固化快照）
        final String effRegion = region;
        final String effCond = cond;
        final Double effTemperature = temperature;
        final String effWind = textOf(entry.get("wind"));
        scheduleAfterCommit(() -> fireSideEffects(projectId, effRegion, effCond, effTemperature,
                effWind, oldWeatherJson, newWeatherJson, nowSecond));

        return new UnityEnvironmentResult("applied", cond, temperature,
                "环境上报已生效：「" + cond + "」（帧 weather_json 就地覆盖）");
    }

    /** 事务提交后异步联动（无事务时同步执行——对齐天气切换 scheduleAfterCommit 语义）。 */
    private void scheduleAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> runSafe(task));
                }
            });
        } else {
            executor.execute(() -> runSafe(task));
        }
    }

    /** 提交后联动集合（SSE 推送独立异步最先提交 → 环境刺激 → 事件 → 润色；全部隔离失败不影响上报本身）。 */
    private void fireSideEffects(Long projectId, String region, String cond, Double temperature,
                                 String wind, String oldWeatherJson, String newWeatherJson, Long nowSecond) {
        // ② SSE weather 推送——独立异步任务**最先提交**（最高实时性：不排队在环境刺激/事件
        // 处理之后——onFrameChanged 含 DB 写与 AI 触发链，引擎大事务/感知重试风暴期间
        // 实测可阻塞 10s+，排队会连带延迟实时天气推送与 Unity 下行）
        if (sandboxSseHub != null) {
            executor.execute(() -> runSafe(
                    () -> pushWeather(projectId, region, cond, temperature, wind, nowSecond)));
        } else {
            log.warn("[Unity环境] sandboxSseHub 未装配，SSE weather 推送跳过: project={}", projectId);
        }
        // ① 环境刺激挂钩：帧天气变化 diff → NPC 感知（复用 onFrameChanged：帧指纹幂等）
        if (environmentStimulusService != null) {
            try {
                long gameDay = Math.floorDiv(nowSecond == null ? 0L : nowSecond, 86400L);
                environmentStimulusService.onFrameChanged(projectId,
                        WorldFrameParse.parse("{\"weather\":" + oldWeatherJson + "}", gameDay),
                        WorldFrameParse.parse("{\"weather\":" + newWeatherJson + "}", gameDay),
                        nowSecond);
            } catch (Exception e) {
                log.warn("[Unity环境] 环境刺激挂钩失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        // ③ 世界事件（重要度 2：仅可视化；NPC 反应唯一来源=环境刺激通道；含 DB 写可能被
        // 引擎大事务短暂阻塞——不再影响 ② 的实时推送）
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind(KIND_WEATHER)
                    .source(SOURCE_WEATHER)
                    .visibility("global")
                    .title("天气变化：" + cond)
                    .content(region + "天气由「" + blankTo(oldWeatherJson, "未知") + "」变为「" + cond + "」"
                            + (temperature != null ? "，气温约 " + temperature + "℃" : ""))
                    .importance(EVENT_IMPORTANCE)
                    .gameHour(nowSecond == null ? 0L : nowSecond / 3600L)
                    .sourceKey("unity-env:" + projectId + ":" + nowSecond + ":" + cond
                            + ":" + (System.nanoTime() % 100000L))
                    .build());
        } catch (Exception e) {
            log.warn("[Unity环境] 世界事件发布失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
        // ④ 环境描写 AI 润色预生成（异步零阻塞；预算内）
        if (environmentContextService != null) {
            try {
                environmentContextService.warmupAsync(projectId, nowSecond);
            } catch (Exception e) {
                log.warn("[Unity环境] 润色预生成失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
    }

    /** 独立异步天气推送（fireSideEffects 最先提交；载荷同天气切换口径，SSE 与 Unity 下行同源）。 */
    private void pushWeather(Long projectId, String region, String cond, Double temperature,
                             String wind, Long nowSecond) {
        String season = SeasonService.seasonOf(nowSecond);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("condition", cond);
        m.put("temperature", temperature);
        m.put("wind", wind);
        m.put("gameSecond", nowSecond);
        m.put("season", season);
        m.put("seasonName", SeasonService.seasonDisplayName(season));
        m.put("feelsLikeC", temperature == null ? null
                : Math.round((temperature + phaseOffset(nowSecond)) * 10.0) / 10.0);
        m.put("message", "天气变化：" + cond);
        m.put("source", SOURCE_WEATHER);
        sandboxSseHub.publish(projectId, WorldSandboxSseHub.EVENT_WEATHER, m);
        log.info("[Unity环境] SSE weather 已推送: project={} region={} condition={} second={}", projectId, region, cond, nowSecond);
    }

    /** 时段体感修正（对齐 EnvironmentContextService.PHASE_FEELS_LIKE_OFFSET 口径）。 */
    private static int phaseOffset(Long gameSecond) {
        long s = gameSecond == null ? 0L : gameSecond;
        int hour = (int) Math.floorMod(s / 3600L, 24L);
        if (hour >= 20 || hour <= 5) return -3;
        if (hour >= 6 && hour <= 8) return -1;
        if (hour >= 17 && hour <= 19) return -2;
        return 0;
    }

    // ==================== 工具 ====================

    /** 解析 weather_json（非法/为空返回空 ObjectNode）。 */
    private ObjectNode parseWeather(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
            return objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("[Unity环境] weather_json 解析失败，按空重建: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BizException(500, "天气序列化失败");
        }
    }

    private static String textOf(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String t = n.asText();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private void runSafe(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("[Unity环境] 提交后联动任务失败（隔离继续）: {}", e.getMessage());
        }
    }

    /** 执行器关闭（应用退出释放虚拟线程池）。 */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * 环境上报处理结果（测试/日志断言用）。
     *
     * @param status 状态：invalid/no_frame/applied
     *
     * @param condition 生效天气文本（无效时为 null）
     *
     * @param temperature 生效气温（未上报时为 null）
     *
     * @param message 说明
     */
    public record UnityEnvironmentResult(String status, String condition, Double temperature, String message) {
        /** 便捷判断：是否生效 */
        public boolean applied() {
            return "applied".equals(status);
        }
    }
}
