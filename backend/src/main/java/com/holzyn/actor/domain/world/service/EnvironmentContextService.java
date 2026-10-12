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
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.EnvironmentProperties;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.vo.EnvironmentContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一环境上下文服务。
 * <p>职责：
 * 按「季节 + 游戏时刻 + 当日天气帧」<b>确定性计算</b>环境状态——昼夜模型（日出日落按季节
 * 查表 + 每日波动 ±15 分钟 + 骨架平移）、时刻阶段（夜/晨/昼/昏）、体感气温（季基准或帧温 +
 * 天气修正 + 时段修正）、环境状态文本（确定性模板组合）、感官标签；供三处注入
 * （感知段/对话【当前环境】段/决策情境）与读接口共用。</p>
 * <p><b>AI 润色（environment_desc 模板，：默认开，预算内）</b>：⚠️ 遵循 S4 冒烟教训
 * （HTTP 事务内同步 AI 拖死）——润色<b>绝不在注入/读接口同步路径调用</b>，而是
 * 「帧生成/天气切换后异步预生成」：{@link #warmupAsync} 在后台虚拟线程按当日 4 个时段
 * （晨/昼/昏/夜）各润色一次并缓存（键=项目+游戏日+时段+天气指纹），注入/接口只读缓存，
 * 未命中回退确定性模板（source=template）；每日每项目预算 polishDailyCap（默认 8）耗尽
 * 或 AI 失败静默回退模板，零同步延迟、零阻断。</p>
 */
@Slf4j
@Service
public class EnvironmentContextService {

    /** AI 调用场景（trace/用量记账 scene=environment_desc，对齐内置模板编码） */
    private static final String SCENE_ENV_DESC = "environment_desc";

    /** AI 润色温度（0.5：描写类输出，适度多样性） */
    private static final double POLISH_TEMPERATURE = 0.5;

    /** AI 润色最大输出 token（256：一段 40~90 字描写） */
    private static final int POLISH_MAX_TOKENS = 256;

    /** 润色失败最大重试次数 */
    private static final int MAX_RETRY = 1;

    /** 时段阶段：晨（日出前 30 分 → 日出后 60 分） */
    private static final int DAWN_BEFORE_MIN = 30;
    /** 时段阶段：晨后延伸分钟 */
    private static final int DAWN_AFTER_MIN = 60;
    /** 时段阶段：昏前窗口分钟 */
    private static final int DUSK_BEFORE_MIN = 60;

    /** 时段阶段中文名 */
    private static final Map<String, String> PHASE_NAMES = Map.of(
            "dawn", "清晨", "day", "白昼", "dusk", "傍晚", "night", "夜晚");

    /** 时段体感修正：夜 -3 / 晨 -1 / 昏 -2 / 昼 0 */
    private static final Map<String, Integer> PHASE_FEELS_LIKE_OFFSET = Map.of(
            "night", -3, "dawn", -1, "dusk", -2, "day", 0);

    private final ActorWorldFrameRepository frameRepository;
    private final EnvironmentProperties props;
    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;

    /** 季节服务（字段注入保持构造器不变；null=未装配回退内置默认骨架） */
    @Autowired(required = false)
    private SeasonService seasonService;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 世界时钟（当前引擎推进位置解析；null=未装配） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * AI 强度闸门（微层接线， 环境润色按项目档位生效——
     * programmatic=零 AI 全模板 / light=每日预算减半 / full=标准 / max=无预算上限；
     * 字段注入避免改构造器破坏存量测试）
     */
    @Autowired(required = false)
    private AiIntensityGate aiIntensityGate;

    /**
     * 世界氛围片段读取服务（story-plan 工作流 _06 字段注入——保持构造器
     * 签名不变；直接 new 时为空 → 环境润色不注入世界氛围，零破坏）
     */
    @Autowired(required = false)
    private StoryAtmosphereService storyAtmosphereService;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（environment_desc 属「世界视角」）：环境润色请求前置 段1A【世界观】+
     * 段2D【当前世界动态】（`worldContextPrefix`，世界历时刻/季节/天气），描写贴合世界设定与
     * 当下世界；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /** 润色异步执行器（虚拟线程：帧生成/天气切换后预生成，不占用任何同步路径） */
    private static final ExecutorService POLISH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 润色结果缓存（键=projectId:gameDay:phase:weatherFingerprint → 润色文本；超上限清空防膨胀） */
    private final ConcurrentHashMap<String, String> polishCache = new ConcurrentHashMap<>();

    /** 每日预算计数（键=projectId → long[游戏日, 已用次数]；进程内存态，重启丢失可接受） */
    private final ConcurrentHashMap<Long, long[]> polishDailyCount = new ConcurrentHashMap<>();

    public EnvironmentContextService(ActorWorldFrameRepository frameRepository, EnvironmentProperties props,
                                     PromptTemplateService promptTemplateService,
                                     AiSpringChatGateway aiChatGateway, UsageLogService usageLogService) {
        this.frameRepository = frameRepository;
        this.props = props;
        this.promptTemplateService = promptTemplateService;
        this.aiChatGateway = aiChatGateway;
        this.usageLogService = usageLogService;
    }

    // ==================== 主入口 ====================

    /**
     * 计算项目当前（或指定时刻）的统一环境上下文。
     * <p>确定性计算 + 读润色缓存（未命中保持模板，<b>本方法零同步 AI 调用</b>）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏秒（null=引擎当前推进位置，再 null=0）
     *
     * @return 环境上下文 VO（恒非空）
     */
    public EnvironmentContextVO computeContext(Long projectId, Long gameSecond) {
        EnvironmentContextVO vo = new EnvironmentContextVO();
        Long second = resolveGameSecond(projectId, gameSecond);
        vo.setProjectId(projectId);
        vo.setGameSecond(second);
        String season = SeasonService.seasonOf(second);
        SeasonService.SeasonSkeleton skeleton = skeletonOf(projectId, season);
        vo.setMonth(SeasonService.monthOf(second));
        vo.setSeason(season);
        vo.setSeasonName(SeasonService.seasonDisplayName(season));
        vo.setHourOfDay(SeasonService.hourOfDay(second));
        vo.setColorTone(skeleton.colorTone());
        long gameDay = Math.floorDiv(second == null ? 0L : second, 86400L);
        int sunrise = SeasonService.sunriseMinuteOf(skeleton, season, gameDay);
        int sunset = SeasonService.sunsetMinuteOf(skeleton, season, gameDay);
        vo.setSunrise(SeasonService.minuteToText(sunrise));
        vo.setSunset(SeasonService.minuteToText(sunset));
        String phase = phaseOf(SeasonService.minuteOfDay(second), sunrise, sunset);
        vo.setPhase(phase);
        vo.setPhaseName(PHASE_NAMES.getOrDefault(phase, "白昼"));

        // 当日天气（active 帧主区域优先/首个区域兜底；无帧/无区域天气保持 null）
        String[] weatherArr = {null};
        String[] windArr = {null};
        Double[] tempArr = {null};
        loadFrameWeather(projectId, weatherArr, windArr, tempArr);
        String condition = weatherArr[0];
        vo.setWeather(condition);
        vo.setWind(windArr[0]);
        double mid = skeleton.baseTempMid();
        // 气温：帧温度优先；缺失=季基准+天气修正
        double temp = tempArr[0] != null ? tempArr[0] : SeasonService.seasonAdjustedTemp(season, mid, condition);
        vo.setTempC(round1(temp));
        // 体感：气温 + 时段修正（天气修正已并入气温口径）
        vo.setFeelsLikeC(round1(temp + PHASE_FEELS_LIKE_OFFSET.getOrDefault(phase, 0)));

        // 环境状态文本：AI 润色缓存 → 确定性模板
        String polished = props.isAiPolish() ? polishCache.get(polishKey(projectId, gameDay, phase, condition, tempArr[0], windArr[0])) : null;
        if (polished != null && !polished.isBlank()) {
            vo.setDesc(polished);
            vo.setSource("ai-cached");
        } else {
            vo.setDesc(templateDesc(season, phase, condition, windArr[0], vo.getFeelsLikeC()));
            vo.setSource("template");
        }
        vo.setSensory(sensoryOf(phase, condition, windArr[0], vo.getFeelsLikeC()));
        return vo;
    }

    /**
     * 注入用环境段文本（感知/对话共用形态）：
     * 「【当前环境】冬日的傍晚，天已经全黑了，北风裹着雪粒…（体感 −9℃，日落 17:30）」
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏秒（可空=当前）
     *
     * @param maxLen 总长上限（超出截断；≤0 不截断）
     *
     * @return 环境段文本（计算失败返回空串，调用方静默降级）
     */
    public String injectionSegment(Long projectId, Long gameSecond, int maxLen) {
        try {
            EnvironmentContextVO ctx = computeContext(projectId, gameSecond);
            StringBuilder sb = new StringBuilder("【当前环境】");
            sb.append(ctx.getSeasonName()).append("日").append(ctx.getPhaseName());
            if (ctx.getWeather() != null && !ctx.getWeather().isBlank()) {
                sb.append("，").append(ctx.getWeather());
            }
            if (ctx.getDesc() != null && !ctx.getDesc().isBlank()) {
                sb.append("，").append(ctx.getDesc());
            }
            if (ctx.getFeelsLikeC() != null) {
                sb.append("，体感 ").append(Math.round(ctx.getFeelsLikeC())).append("℃");
            }
            if (ctx.getSunset() != null) {
                sb.append("（日落 ").append(ctx.getSunset()).append("）");
            }
            String text = sb.toString();
            if (maxLen > 0 && text.length() > maxLen) {
                return text.substring(0, maxLen);
            }
            return text;
        } catch (Exception e) {
            log.warn("[环境] 注入环境段失败（静默降级）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    // ==================== AI 润色（异步预生成，S4 教训：同步路径零 AI） ====================

    /**
     * 触发异步润色预生成（帧生成/天气切换后调用；虚拟线程，隔离失败，绝不阻塞调用方）。
     * 按当日 4 个时段（晨/昼/昏/夜）各尝试一次：缓存命中跳过；每日预算耗尽跳过；
     * AI 失败静默跳过（保持模板）。调用方无需关心结果。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（季节/当日解析基准）
     */
    public void warmupAsync(Long projectId, Long gameSecond) {
        if (!props.isAiPolish() || projectId == null) {
            return;
        }
        // 微层接线：programmatic 档零 AI——润色整体跳过（保持确定性模板，source=template）
        if (aiIntensityGate != null && !aiIntensityGate.allowsAi(projectId)) {
            log.debug("[环境] AI 强度档位 programmatic，跳过润色预生成（零 AI）: project={}", projectId);
            return;
        }
        POLISH_EXECUTOR.execute(() -> {
            try {
                warmupPolish(projectId, gameSecond);
            } catch (Exception e) {
                log.warn("[环境] 润色预生成失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        });
    }

    /** 润色预生成实现（4 时段逐个尝试；同步供测试直接调用；开关关/无项目直接返回）。 */
    void warmupPolish(Long projectId, Long gameSecond) {
        if (!props.isAiPolish() || projectId == null) {
            return;
        }
        // 微层接线：同步入口同样受档位门控（防御直接调用路径）
        if (aiIntensityGate != null && !aiIntensityGate.allowsAi(projectId)) {
            return;
        }
        Long second = resolveGameSecond(projectId, gameSecond);
        String season = SeasonService.seasonOf(second);
        SeasonService.SeasonSkeleton skeleton = skeletonOf(projectId, season);
        long gameDay = Math.floorDiv(second == null ? 0L : second, 86400L);
        String[] weatherArr = {null};
        String[] windArr = {null};
        Double[] tempArr = {null};
        loadFrameWeather(projectId, weatherArr, windArr, tempArr);
        String condition = weatherArr[0];
        double mid = skeleton.baseTempMid();
        double temp = tempArr[0] != null ? tempArr[0] : SeasonService.seasonAdjustedTemp(season, mid, condition);
        int sunrise = SeasonService.sunriseMinuteOf(skeleton, season, gameDay);
        int sunset = SeasonService.sunsetMinuteOf(skeleton, season, gameDay);
        for (String phase : List.of("dawn", "day", "dusk", "night")) {
            String key = polishKey(projectId, gameDay, phase, condition, tempArr[0], windArr[0]);
            if (polishCache.containsKey(key)) {
                continue;
            }
            if (!tryConsumeBudget(projectId, gameDay)) {
                return; // 预算耗尽：本日不再润色
            }
            String text = callPolishAi(projectId, season, skeleton, phase, condition, temp, sunrise, sunset);
            if (text != null && !text.isBlank()) {
                if (polishCache.size() >= props.getPolishCacheMaxEntries()) {
                    polishCache.clear();
                }
                polishCache.put(key, text);
            }
        }
    }

    /**
     * 每日预算消耗（同游戏日计数；跨日重置）。返回是否还有额度。
     * 微层接线：生效上限按 AI 强度档位折算——light=ceil(原值×0.5) / max=不限 /
     * programmatic=0（配合 warmup 入口门控双保险）/ full=原值。
     */
    private synchronized boolean tryConsumeBudget(Long projectId, Long gameDay) {
        int baseCap = Math.max(1, props.getPolishDailyCap());
        int cap = aiIntensityGate == null ? baseCap
                : Math.max(0, aiIntensityGate.effectiveCap(projectId, baseCap));
        long[] entry = polishDailyCount.computeIfAbsent(projectId, k -> new long[]{gameDay, 0});
        if (entry[0] != gameDay) {
            entry[0] = gameDay;
            entry[1] = 0;
        }
        if (entry[1] >= cap) {
            return false;
        }
        entry[1]++;
        return true;
    }

    /** 单时段 AI 润色调用（environment_desc 模板；失败返回 null，调用方保持模板）。 */
    private String callPolishAi(Long projectId, String season, SeasonService.SeasonSkeleton skeleton,
                                String phase, String condition, Double temp, int sunrise, int sunset) {
        try {
            Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
            return AiCallContext.run(SCENE_ENV_DESC, null, 1, () -> {
                Map<String, Object> placeholders = new LinkedHashMap<>();
                placeholders.put("season_name", SeasonService.seasonDisplayName(season));
                placeholders.put("season", season);
                placeholders.put("phase_name", PHASE_NAMES.getOrDefault(phase, "白昼"));
                placeholders.put("phase", phase);
                placeholders.put("sunrise", SeasonService.minuteToText(sunrise));
                placeholders.put("sunset", SeasonService.minuteToText(sunset));
                placeholders.put("weather", condition == null || condition.isBlank() ? "无" : condition);
                placeholders.put("temp_c", temp == null ? "?" : String.valueOf(Math.round(temp)));
                placeholders.put("feels_like_c", temp == null ? "?" : String.valueOf(Math.round(temp
                        + PHASE_FEELS_LIKE_OFFSET.getOrDefault(phase, 0))));
                placeholders.put("skeleton_hint", skeleton.tempRangeText() + "，色调" + skeleton.colorTone());
                // story-plan（_06）：世界氛围片段注入（无档案/未装配 → 空串，模板残留占位符自动抹除）
                placeholders.put("atmosphere",
                        storyAtmosphereService == null ? "" : storyAtmosphereService.atmosphereOf(projectId));
                for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                    try {
                        String prompt = promptTemplateService.render(userId, projectId,
                                PromptTemplateService.CODE_ENVIRONMENT_DESC, placeholders);
                        // 批6 收口（环境描述润色属「世界视角」）：前置 段1A【世界观】+ 段2D 当前世界动态（零阻断）
                        String prefix = aiContextInjector == null ? ""
                                : aiContextInjector.worldContextPrefix(projectId, null);
                        String finalPrompt = prefix.isBlank() ? prompt : prefix + "\n\n" + prompt;
                        String sys = promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_ENVIRONMENT_DESC, null);
                        if (sys == null || sys.isBlank()) {
                            sys = "你是一位环境描述者，只输出描写文本。";
                        }
                        AiChatRequest req = new AiChatRequest(null, List.of(
                                new AiChatRequest.ChatMessage("system", sys),
                                new AiChatRequest.ChatMessage("user", finalPrompt)),
                                POLISH_TEMPERATURE, POLISH_MAX_TOKENS, false);
                        long startMs = System.currentTimeMillis();
                        AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                        usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                                SCENE_ENV_DESC, result.promptTokens(), result.completionTokens(),
                                result.cacheHitTokens(), result.cacheMissTokens(),
                                (int) (System.currentTimeMillis() - startMs));
                        String content = result.content();
                        if (content == null || content.isBlank()) {
                            throw new IllegalStateException("AI 未返回文本");
                        }
                        // 描写类输出：取整段文本（容错去除首尾空白/引号包裹）
                        String text = content.trim();
                        if (text.length() > 2 && text.startsWith("\"") && text.endsWith("\"")) {
                            text = text.substring(1, text.length() - 1).trim();
                        }
                        return text;
                    } catch (Exception e) {
                        log.warn("[环境] 润色第 {} 次失败（回退模板）: project={} : {}", attempt, projectId, e.getMessage());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[环境] 润色调用异常（回退模板）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    // ==================== 确定性计算（纯静态可单测） ====================

    /**
     * 时刻阶段判定（夜/晨/昼/昏）：
     * 晨 = [日出-30, 日出+60)；昏 = [日落-60, 日落)；其余=昼；跨午夜前=夜。
     *
     * @param minuteOfDay 当日分钟（0-1439）
     *
     * @param sunrise 日出分钟
     *
     * @param sunset 日落分钟
     */
    static String phaseOf(int minuteOfDay, int sunrise, int sunset) {
        int m = minuteOfDay;
        if (m < 0 || m >= 24 * 60) {
            m = Math.floorMod(m, 24 * 60);
        }
        if (m >= sunrise - DAWN_BEFORE_MIN && m < sunrise + DAWN_AFTER_MIN) {
            return "dawn";
        }
        if (m >= sunset - DUSK_BEFORE_MIN && m < sunset) {
            return "dusk";
        }
        if (m >= sunrise && m < sunset) {
            return "day";
        }
        return "night";
    }

    /**
     * 确定性环境状态文本（模板组合，零 AI）：季节×时段基句 + 天气补句 + 风 + 体感尾注。
     * 示例：冬·昏·雪 → 「冬日的傍晚，天已经全黑了，飘着雪，路上行人寥寥，冷得缩手缩脚」。
     */
    static String templateDesc(String season, String phase, String condition, String wind, Double feelsLikeC) {
        String base = switch (season + ":" + phase) {
            case "spring:dawn" -> "春日的清晨，薄雾笼着田野，草叶上挂着露珠";
            case "spring:day" -> "春日的白昼，和风送暖，田野里一片新绿";
            case "spring:dusk" -> "春日的傍晚，天色渐暗，归鸟掠过村头";
            case "spring:night" -> "春夜微凉，蛙声从田间隐隐传来";
            case "summer:dawn" -> "夏日的清晨，天色亮得早，蝉鸣已起";
            case "summer:day" -> "夏日的白昼，日头高悬，热浪蒸腾";
            case "summer:dusk" -> "夏日傍晚，晚霞烧红了半边天，蝉鸣不绝";
            case "summer:night" -> "夏夜闷热，偶有凉风";
            case "autumn:dawn" -> "秋日的清晨，天高气爽，草尖结着薄霜";
            case "autumn:day" -> "秋日的白昼，天高云淡，风里带着收获的气息";
            case "autumn:dusk" -> "秋日傍晚，落日熔金，天色很快暗了下来";
            case "autumn:night" -> "秋夜清冷，月光洒在空旷的街巷";
            case "winter:dawn" -> "冬日的清晨，呵气成霜，天光熹微";
            case "winter:day" -> "冬日的正午，阳光晒在身上暖洋洋的";
            case "winter:dusk" -> "冬日的傍晚，天已经全黑了，路上行人寥寥";
            case "winter:night" -> "冬夜漫长，寒风呼啸，屋里的灯火显得格外温暖";
            default -> "天色如常";
        };
        StringBuilder sb = new StringBuilder(base);
        String c = condition == null ? "" : condition.trim();
        if (c.contains("雪")) {
            sb.append("，飘着雪");
        } else if (c.contains("雨")) {
            sb.append("，下着雨");
        } else if ("阴".equals(c)) {
            sb.append("，天色阴沉");
        }
        if (wind != null && !wind.isBlank()) {
            sb.append("，").append(wind.trim());
        }
        if (feelsLikeC != null) {
            if (feelsLikeC < -15) {
                sb.append("，寒冷刺骨");
            } else if (feelsLikeC < 0) {
                sb.append("，冷得缩手缩脚");
            } else if (feelsLikeC >= 33) {
                sb.append("，酷热难当");
            } else if (feelsLikeC >= 28 && "day".equals(phase)) {
                sb.append("，热浪逼人");
            }
        }
        return sb.toString();
    }

    /** 感官标签（寒冷刺骨/天色全黑/有风/下着雪…；恒非空）。 */
    static List<String> sensoryOf(String phase, String condition, String wind, Double feelsLikeC) {
        List<String> out = new ArrayList<>();
        if (feelsLikeC != null) {
            if (feelsLikeC < -10) {
                out.add("寒冷刺骨");
            } else if (feelsLikeC < 5) {
                out.add("有些冷");
            } else if (feelsLikeC >= 30) {
                out.add("炎热");
            } else if (feelsLikeC >= 15) {
                out.add("暖和");
            }
        }
        if ("night".equals(phase)) {
            out.add("天色全黑");
        } else if ("dusk".equals(phase)) {
            out.add("天快黑了");
        } else if ("dawn".equals(phase)) {
            out.add("天刚亮");
        }
        if (wind != null && !wind.isBlank()) {
            out.add("有风");
        }
        String c = condition == null ? "" : condition;
        if (c.contains("雪")) {
            out.add("下着雪");
        } else if (c.contains("雨")) {
            out.add("下着雨");
        }
        if (out.isEmpty()) {
            out.add("天气平稳");
        }
        return out;
    }

    // ==================== 私有辅助 ====================

    /** 解析计算基准游戏秒（null=引擎推进位置，再 null=0）。 */
    private Long resolveGameSecond(Long projectId, Long gameSecond) {
        if (gameSecond != null) {
            return gameSecond < 0 ? 0L : gameSecond;
        }
        try {
            if (worldClockService != null) {
                Long s = worldClockService.currentEngineGameSecond(projectId);
                if (s != null) {
                    return s < 0 ? 0L : s;
                }
            }
        } catch (Exception e) {
            log.debug("[环境] 时钟读取失败（回退 0）: {}", e.getMessage());
        }
        return 0L;
    }

    /** 项目季节骨架（未装配回退内置默认）。 */
    private SeasonService.SeasonSkeleton skeletonOf(Long projectId, String season) {
        if (seasonService != null) {
            return seasonService.skeletonOf(projectId, season);
        }
        return SeasonService.defaultSkeleton().get(season);
    }

    /** 读取 active 帧主区域（优先）/首个区域天气（condition/wind/temperature 出参）。 */
    private void loadFrameWeather(Long projectId, String[] condition, String[] wind, Double[] temperature) {
        if (frameRepository == null || projectId == null) {
            return;
        }
        try {
            ActorWorldFrame frame = frameRepository.findByProjectIdAndStatus(projectId, "active").stream()
                    .findFirst().orElse(null);
            if (frame == null || frame.getWeatherJson() == null || frame.getWeatherJson().isBlank()) {
                return;
            }
            WorldFrameParse.FrameJson parsed = WorldFrameParse.parse(
                    "{\"weather\":" + frame.getWeatherJson() + "}", frame.getGameDay());
            Map<String, WorldFrameParse.WeatherEntry> weather = parsed.weather();
            if (weather.isEmpty()) {
                return;
            }
            WorldFrameParse.WeatherEntry entry;
            if (weather.containsKey("主区域")) {
                entry = weather.get("主区域");
            } else {
                entry = weather.values().iterator().next();
            }
            if (entry == null) {
                return;
            }
            condition[0] = entry.condition();
            wind[0] = entry.wind();
            temperature[0] = entry.temperature();
        } catch (Exception e) {
            log.warn("[环境] 帧天气读取失败（保持空）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 润色缓存键（项目+游戏日+时段+天气指纹）。 */
    private static String polishKey(Long projectId, long gameDay, String phase, String condition,
                                    Double temperature, String wind) {
        return projectId + ":" + gameDay + ":" + phase + ":"
                + (condition == null ? "-" : condition) + "|"
                + (temperature == null ? "-" : String.valueOf(temperature)) + "|"
                + (wind == null ? "-" : wind);
    }

    /** 气温保留 1 位小数。 */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
