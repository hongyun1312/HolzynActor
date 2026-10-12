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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.EnvironmentStimulusService;
import com.holzyn.actor.domain.sandbox.service.WorldSandboxSseHub;
import com.holzyn.actor.domain.sandbox.vo.WeatherVO;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 沙盘天气切换服务（世界沙盘 S4 · 干预，domain/world/service）。
 * <p>职责：把用户指定的天气（晴/雨/雪/阴）确定性写入当前 active 世界帧 weather JSON
 * （就地更新，不新建帧、不依赖 AI），并联动三件事：</p>
 * <ul>
 * <li><b>环境刺激</b>——经 {@link EnvironmentStimulusService#onFrameChanged} 帧变化 diff
 * （天气条目变化 → 环境刺激 → 特殊 NPC 全量/区域即时决策 + 人群轻量 AI，NPC 感知天气变化做出反应）；</li>
 * <li><b>世界事件</b>——发布一条 kind=world / source=sandbox 事件（重要度 2：
 * 低于实时决策事件阈值 3 与帧紧急更新阈值 4——只进事件流可视化，不触发 NPC 双通道决策防双触发；
 * 环境刺激通道是 NPC 反应的唯一来源）；</li>
 * <li><b>沙盘 SSE</b>——经 {@link WorldSandboxSseHub} 推送 {@code weather} 事件
 * （前端立即更新天气显示与雨/雪粒子，无需轮询）。</li>
 * </ul>
 * <p>设计预留：后期对接「时间季节系统」（第二轮，设计文档 §9 路线图）时，本服务的条件校验处
 * 可叠加季节 → 天气骨架约束（如冬季可雨雪、夏季多雷雨、禁雷暴等），前端 WeatherVO 亦可扩展季节字段——
 * 本轮仅做条件切换与可视化/NPC 感知，不实现季节逻辑。</p>
 */
@Service
public class WeatherSwitchService {

    private static final Logger log = LoggerFactory.getLogger(WeatherSwitchService.class);

    /** 允许的天气条件（确定性集合；后期季节系统接入后在此叠加季节约束校验） */
    public static final Set<String> ALLOWED_CONDITIONS = Set.of("晴", "雨", "雪", "阴");

    /** 默认生效区域（帧内无此区域则取首个区域，仍无则新建「主区域」） */
    private static final String REGION_DEFAULT = "主区域";

    /** 天气变化事件类别/来源（kind=world 引擎世界事件；source=sandbox 沙盘干预） */
    private static final String KIND_WEATHER = "world";
    private static final String SOURCE_WEATHER = "sandbox";

    /** 事件重要度 2：低于实时决策事件阈值 3 与帧紧急更新阈值 4——仅可视化不触发双通道决策（NPC 反应走环境刺激通道） */
    private static final int EVENT_IMPORTANCE = 2;

    private final ActorWorldFrameRepository frameRepository;
    private final ObjectMapper objectMapper;
    private final WorldEventBus worldEventBus;
    private final CurrentUserProvider currentUserProvider;
    private final WorldClockService worldClockService;

    /** 环境刺激服务（帧变化 → 天气 diff → NPC 环境刺激；字段注入避免改构造器破坏存量单测，null=跳过） */
    @Autowired(required = false)
    private EnvironmentStimulusService environmentStimulusService;

    /** 沙盘 SSE 中心（weather 事件推送；字段注入避免构造循环，null=跳过） */
    @Autowired(required = false)
    private WorldSandboxSseHub sandboxSseHub;

    /** 季节服务 */
    @Autowired(required = false)
    private SeasonService seasonService;

    /** 统一环境服务 */
    @Autowired(required = false)
    private EnvironmentContextService environmentContextService;

    /** 季节系统总开关（默认 true；false=切换不做季节气温修正/不发布反常天气事件） */
    @Value("${holzyn.actor.climate.enabled:true}")
    private boolean climateEnabled;

    /** 天气切换总开关（默认 true；false=业务拒绝 400，对齐既有可配置总开关模式） */
    @Value("${holzyn.actor.sandbox.weather-switch-enabled:true}")
    private boolean weatherSwitchEnabled;

    public WeatherSwitchService(ActorWorldFrameRepository frameRepository, ObjectMapper objectMapper,
                                WorldEventBus worldEventBus, CurrentUserProvider currentUserProvider,
                                WorldClockService worldClockService) {
        this.frameRepository = frameRepository;
        this.objectMapper = objectMapper;
        this.worldEventBus = worldEventBus;
        this.currentUserProvider = currentUserProvider;
        this.worldClockService = worldClockService;
    }

    /**
     * 切换当前 active 世界帧的天气（就地更新 weather_json 的指定区域 condition，保留温度/风）。
     *
     * @param projectId 项目 ID
     *
     * @param condition 目标天气（晴 / 雨 / 雪 / 阴）
     *
     * @return WeatherVO（含生效区域/天气/温度/风/切换时刻；同天气幂等返回「天气未变化」）
     */
    @Transactional
    public WeatherVO switchWeather(Long projectId, String condition) {
        if (!weatherSwitchEnabled) {
            throw new BizException(400, "天气切换已关闭（holzyn.actor.sandbox.weather-switch-enabled=false）");
        }
        String cond = condition == null ? "" : condition.trim();
        if (!ALLOWED_CONDITIONS.contains(cond)) {
            throw new BizException(400, "不支持的天气条件：" + condition
                    + "（可选：" + String.join(" / ", ALLOWED_CONDITIONS) + "）");
        }
        ActorWorldFrame frame = frameRepository.findByProjectIdAndStatus(projectId, "active")
                .stream().findFirst()
                .orElseThrow(() -> new BizException(404, "尚无生效世界帧，请先推进世界以生成每日世界帧"));
        Long nowSecond = worldClockService.currentEngineGameSecond(projectId);

        ObjectNode weather = parseWeather(frame.getWeatherJson());
        String region = REGION_DEFAULT;
        if (weather.has(REGION_DEFAULT)) {
            region = REGION_DEFAULT;
        } else {
            // 无主区域 → 取首个区域（Jackson 3 properties() 返回可迭代条目）
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = weather.properties().iterator();
            if (it.hasNext()) {
                region = it.next().getKey();
            }
        }
        ObjectNode entry = weather.get(region) instanceof ObjectNode on ? on : weather.putObject(region);
        String old = textOf(entry.get("condition"));
        if (cond.equals(old)) {
            // 幂等：天气未变化，不落库不发布（零副作用）
            log.info("[天气切换] 幂等跳过（已是 {}）: project={}", cond, projectId);
            return toVO(projectId, region, cond, doubleOf(entry.get("temperature")),
                    textOf(entry.get("wind")), nowSecond, "天气未变化（已是「" + cond + "」）");
        }

        // ⚠️ 时间季节系统 叠加点（S4 预留落地）：季节 → 天气骨架约束——反季节天气（雪在非冬季）
        // 允许切换但按季节修正气温 + 发布「反常天气」事件（用户 决策：允许但修正+异象化）。
        String season = SeasonService.seasonOf(nowSecond);
        boolean snow = "雪".equals(cond);
        boolean anomaly = snow && !SeasonService.WINTER.equals(season);
        SeasonService.SeasonSkeleton sk = seasonService == null
                ? SeasonService.defaultSkeleton().get(season)
                : seasonService.skeletonOf(projectId, season);
        double mid = sk.baseTempMid();
        Double oldTemp = doubleOf(entry.get("temperature"));
        Double newTemp;
        if (snow) {
            // 雪：按季节修正气温（冬=季基准-8；非冬反常=固定 5℃ 骤降温）
            newTemp = Math.round(SeasonService.snowSwitchTemp(season, mid) * 10.0) / 10.0;
        } else {
            // 非雪：保留帧温（S4 语义），缺失按季基准+天气修正填充
            newTemp = oldTemp != null ? oldTemp
                    : Math.round(SeasonService.seasonAdjustedTemp(season, mid, cond) * 10.0) / 10.0;
        }

        // 就地改写（保留风；温度按季节修正：雪必改，非雪保留帧温/缺失按季基准填充——
        // 保留帧温时不改写 temperature 字段，保持原 JSON 数值表示）
        entry.put("condition", cond);
        if (snow || oldTemp == null) {
            entry.put("temperature", newTemp);
        }
        String oldWeatherJson = frame.getWeatherJson();
        String newWeatherJson = toJson(weather);
        frame.setWeatherJson(newWeatherJson);
        frame.setEmergency(1); // 就地改写标记（对齐紧急更新语义，不新建帧）
        frameRepository.save(frame);
        log.info("[天气切换] {} 天气 {} → {}（区域={}，温度 {}℃，季节={}）: project={} second={}",
                region, blankTo(old, "未知"), cond, region, newTemp, season, projectId, nowSecond);

        // ① 环境刺激挂钩：帧天气变化 diff → NPC 感知。
        // ⚠️ S4 冒烟抓修：必须<b>事务提交后异步</b>执行——onFrameChanged 内部
        // reactCrowds 等会<b>同步调用真实 AI</b>（本地模型 ~30 tok/s，每人 5~15s），若留在 HTTP
        // 事务线程内会把天气切换请求拖死 60s+ 且长时间持有 actor_world_frame 行锁（H2 锁超时 500
        // 实证）；afterCommit 注册 + 虚拟线程执行器 = 帧提交落库后才通知 NPC，用户操作毫秒级返回。
        // 单测/非事务调用（无事务同步）退化为同步触发，保证行为可测。
        if (environmentStimulusService != null) {
            Long gameDay = frame.getGameDay();
            scheduleAfterCommit(() -> fireEnvironmentStimulus(projectId, oldWeatherJson, newWeatherJson,
                    gameDay, nowSecond));
        }

        // ② 世界事件（重要度 2：仅可视化；NPC 反应唯一来源=环境刺激通道）
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            worldEventBus.publish(userId, projectId, EventSpec.builder()
                    .kind(KIND_WEATHER)
                    .source(SOURCE_WEATHER)
                    .visibility("global")
                    .title("天气变化：" + cond)
                    .content(region + "天气由「" + blankTo(old, "未知") + "」变为「" + cond + "」"
                            + "，气温约 " + newTemp + "℃")
                    .importance(EVENT_IMPORTANCE)
                    .gameHour(nowSecond / 3600L)
                    .sourceKey("weather:" + projectId + ":" + nowSecond + ":" + cond
                            + ":" + (System.nanoTime() % 100000L))
                    .build());
            // ②-b 反常天气事件
            if (climateEnabled && anomaly) {
                worldEventBus.publish(userId, projectId, EventSpec.builder()
                        .kind(KIND_WEATHER)
                        .source(SOURCE_WEATHER)
                        .visibility("global")
                        .title("反常天气：" + SeasonService.seasonDisplayName(season) + "日飞雪")
                        .content(region + "竟在" + SeasonService.seasonDisplayName(season) + "天飘起雪来，"
                                + "气温骤降至 " + newTemp + "℃，村民纷纷驻足议论，"
                                + "说是从未见过的异象（莫非与灵脉有关？）")
                        .importance(EVENT_IMPORTANCE)
                        .gameHour(nowSecond / 3600L)
                        .sourceKey("anomaly-weather:" + projectId + ":" + nowSecond + ":" + cond
                                + ":" + (System.nanoTime() % 100000L))
                        .build());
            }
        } catch (Exception e) {
            log.warn("[天气切换] 世界事件发布失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }

        // ③ 沙盘 SSE weather 推送（前端立即更新天气显示与粒子；含季节字段）
        if (sandboxSseHub != null) {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("region", region);
                m.put("condition", cond);
                m.put("temperature", newTemp);
                m.put("wind", textOf(entry.get("wind")));
                m.put("gameSecond", nowSecond);
                m.put("season", season);
                m.put("seasonName", SeasonService.seasonDisplayName(season));
                m.put("feelsLikeC", feelsLikeOf(projectId, season, nowSecond, newTemp));
                m.put("message", "天气变化：" + cond);
                sandboxSseHub.publish(projectId, WorldSandboxSseHub.EVENT_WEATHER, m);
            } catch (Exception e) {
                log.warn("[天气切换] SSE 推送失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }

        // ④ 环境描写 AI 润色预生成
        if (environmentContextService != null) {
            try {
                environmentContextService.warmupAsync(projectId, nowSecond);
            } catch (Exception e) {
                log.warn("[天气切换] 润色预生成挂钩失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }

        return toVO(projectId, region, cond, newTemp,
                textOf(entry.get("wind")), nowSecond, "天气已切换为「" + cond + "」");
    }

    // ==================== 事务提交后异步联动（S4 冒烟抓修，） ====================

    /** 环境刺激联动专用虚拟线程执行器（共享静态：帧提交后通知 NPC，不占用 HTTP/事务线程） */
    private static final ExecutorService LINK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 事务提交后执行任务（afterCommit）：环境刺激必须在帧落库后才可被读取到新天气。
     * <p>有事务同步（生产 @Transactional）→ 注册 afterCommit 异步执行；
     * 无事务同步（单测/非事务调用）→ 退化为同步执行（保证行为可测、不丢联动）。</p>
     */
    private void scheduleAfterCommit(Runnable task) {
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    LINK_EXECUTOR.execute(() -> runSafe(task));
                }
            });
        } catch (IllegalStateException e) {
            // 无事务同步（单测/非事务调用）：同步执行兜底
            runSafe(task);
        }
    }

    /** 环境刺激触发（隔离失败：AI/人群反应任何异常不向外扩散） */
    private void fireEnvironmentStimulus(Long projectId, String oldWeatherJson, String newWeatherJson,
                                         Long gameDay, Long nowSecond) {
        try {
            environmentStimulusService.onFrameChanged(projectId,
                    WorldFrameParse.parse("{\"weather\":" + oldWeatherJson + "}", gameDay),
                    WorldFrameParse.parse("{\"weather\":" + newWeatherJson + "}", gameDay),
                    nowSecond);
        } catch (Exception e) {
            log.warn("[天气切换] 环境刺激挂钩失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    private void runSafe(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("[天气切换] 提交后联动任务失败（隔离继续）: {}", e.getMessage());
        }
    }

    // ==================== 工具 ====================

    /** 解析 weather_json（非法/为空返回空 ObjectNode） */
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
            log.warn("[天气切换] weather_json 解析失败，按空重建: {}", e.getMessage());
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

    private static Double doubleOf(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asDouble();
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private WeatherVO toVO(Long projectId, String region, String condition, Double temperature,
                           String wind, Long gameSecond, String message) {
        WeatherVO vo = new WeatherVO();
        vo.setProjectId(projectId);
        vo.setRegion(region);
        vo.setCondition(condition);
        vo.setTemperature(temperature);
        vo.setWind(wind);
        vo.setSwitchedAtGameSecond(gameSecond);
        vo.setMessage(message);
        // 时间季节系统：季节/时刻阶段/日出日落/体感（切换响应直接可展示）
        String season = SeasonService.seasonOf(gameSecond);
        vo.setSeason(season);
        vo.setSeasonName(SeasonService.seasonDisplayName(season));
        vo.setMonth(SeasonService.monthOf(gameSecond));
        SeasonService.SeasonSkeleton sk = seasonService == null
                ? SeasonService.defaultSkeleton().get(season)
                : seasonService.skeletonOf(projectId, season);
        long gameDay = Math.floorDiv(gameSecond == null ? 0L : gameSecond, 86400L);
        int sunrise = SeasonService.sunriseMinuteOf(sk, season, gameDay);
        int sunset = SeasonService.sunsetMinuteOf(sk, season, gameDay);
        vo.setSunrise(SeasonService.minuteToText(sunrise));
        vo.setSunset(SeasonService.minuteToText(sunset));
        int minuteOfDay = (int) Math.floorMod(gameSecond == null ? 0L : gameSecond / 60L, 24 * 60L);
        String phase = EnvironmentContextService.phaseOf(minuteOfDay, sunrise, sunset);
        vo.setPhase(phase);
        vo.setPhaseName(phaseNameOf(phase));
        vo.setFeelsLikeC(feelsLikeOf(projectId, season, gameSecond, temperature));
        return vo;
    }

    /** 体感气温（气温 + 时段修正；气温缺失返回 null）。 */
    private Double feelsLikeOf(Long projectId, String season, Long gameSecond, Double temperature) {
        if (temperature == null) {
            return null;
        }
        SeasonService.SeasonSkeleton sk = seasonService == null
                ? SeasonService.defaultSkeleton().get(season)
                : seasonService.skeletonOf(projectId, season);
        long gameDay = Math.floorDiv(gameSecond == null ? 0L : gameSecond, 86400L);
        int sunrise = SeasonService.sunriseMinuteOf(sk, season, gameDay);
        int sunset = SeasonService.sunsetMinuteOf(sk, season, gameDay);
        int minuteOfDay = (int) Math.floorMod(gameSecond == null ? 0L : gameSecond / 60L, 24 * 60L);
        String phase = EnvironmentContextService.phaseOf(minuteOfDay, sunrise, sunset);
        int offset = switch (phase) {
            case "night" -> -3;
            case "dawn" -> -1;
            case "dusk" -> -2;
            default -> 0;
        };
        return Math.round((temperature + offset) * 10.0) / 10.0;
    }

    /** 阶段中文名（对齐 EnvironmentContextService.PHASE_NAMES 口径）。 */
    private static String phaseNameOf(String phase) {
        return switch (phase) {
            case "dawn" -> "清晨";
            case "dusk" -> "傍晚";
            case "night" -> "夜晚";
            default -> "白昼";
        };
    }
}
