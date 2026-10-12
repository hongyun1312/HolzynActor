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
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信息认知失真改写服务。
 * <p>职责：三层过滤「认知层」的失真变形——同一信息不同 NPC 听到的版本不同（主方案 §4.4 认知失真）：
 * 按失真规则（distortion_rules，AI 帧携带，如「距离越远越夸大」「职业刻意隐瞒」）与传播上下文
 * （渠道/距离/经手）改写信息文本。<b>失真文本随注入生成，不落库</b>（主方案明确）；改写成本由
 * <b>（受众 × 信息包 × 游戏日）内存缓存</b>收敛——同一受众同一包同一天只调一次 AI。</p>
 * <p>双受众维度：</p>
 * <ul>
 * <li><b>NPC 视角</b>（{@link #distortForNpc}）：感知【世界动态】段注入用——受众=具体 NPC，
 * 改写结合该 NPC 身份（姓名/头衔/职业），缓存键 npc:{npcRef}:{packetId}:{gameDay}；</li>
 * <li><b>区域视角</b>（{@link #distortForRegion}）：扩散到达事件「区域传闻版」用——受众=某区域的
 * 居民群体（转述者），缓存键 region:{packetId}:{region}:{gameDay}；</li>
 * </ul>
 * <p>触发条件：受众可见（调用方保证）+ 失真规则非空 + 开关开（distortion-ai-enabled），
 * 且缓存未命中才调 AI（info_distortion 模板，scene=info_distortion 记账，trace/用量可观测）；
 * AI 失败/超时/开关关 → <b>规则兜底</b>（{@link #ruleFallback}：按可信度/距离/渠道加传闻前缀，
 * 确定性零成本）。缓存上限 distortion-cache-max-entries（默认 20000），超限清空防内存膨胀
 * （单实例运行约定，重启丢失可接受）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfoDistortionService {

    /** AI 调用场景（trace/用量记账 scene=info_distortion，对齐内置模板编码） */
    private static final String SCENE_DISTORTION = "info_distortion";

    /** AI 改写失败最大重试次数 */
    private static final int MAX_RETRY = 1;

    /** 失真改写缓存：受众（NPC/区域）+ 信息包 + 游戏日 → 改写文本（进程内存态，重启丢失可接受） */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private final PromptTemplateService promptTemplateService;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final WorldInfoProperties props;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /**
     * 统一上下文注入器（V2.0 段位装配；字段注入保持构造器不变）。
     * <p>批 6 收口（info_distortion 属「世界视角」）：失真改写请求前置 段1A【世界观】+
     * 段2D【当前世界动态】（`worldContextPrefix`，世界历时刻/季节/天气），转述贴合世界设定与
     * 当下世界；null=未装配 → 原样渲染（fail-open 零阻断）。</p>
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.agent.context.AiContextInjector aiContextInjector;

    /**
     * NPC 视角失真改写（感知注入用）：缓存键 npc:{npcRef}:{packetId}:{gameDay}。
     * <p>触发条件：失真规则非空 + distortion-ai-enabled + 缓存未命中；AI 失败/关断回退
     * {@link #ruleFallback}（不抛异常不阻断感知主流程）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcLabel NPC 身份标签（如「沈砚秋·村长」/「货郎·赵六」）
     *
     * @param packetId 信息包 ID
     *
     * @param content 信息原文
     *
     * @param distortionRules 失真规则列表（空/空列表 = 无需失真，返回原文）
     *
     * @param spreadContext 传播上下文（如「消息经商队从渡口传到集市，相距约 0.3 里」）
     *
     * @param gameDay 当前游戏日
     *
     * @return 失真后文本（无失真规则/未装配/失败 → 原文）
     */
    public String distortForNpc(Long projectId, String npcRef, String npcLabel, Long packetId,
                                String content, List<String> distortionRules, String spreadContext, Long gameDay) {
        if (content == null || content.isBlank() || distortionRules == null || distortionRules.isEmpty()) {
            return content;
        }
        String key = "npc:" + npcRef + ":" + packetId + ":" + gameDay;
        return distort(projectId, key, npcLabel, content, distortionRules, spreadContext);
    }

    /**
     * 区域视角失真改写（扩散到达事件「区域传闻版」用）：缓存键 region:{packetId}:{region}:{gameDay}。
     * 同区域同包同日共享同一传闻版。
     */
    public String distortForRegion(Long projectId, String region, Long packetId, String content,
                                   List<String> distortionRules, String spreadContext, Long gameDay) {
        if (content == null || content.isBlank() || distortionRules == null || distortionRules.isEmpty()) {
            return content;
        }
        String key = "region:" + packetId + ":" + region + ":" + gameDay;
        String audience = "「" + (region == null ? "当地" : region) + "」的居民与过往商旅（转述者视角）";
        return distort(projectId, key, audience, content, distortionRules, spreadContext);
    }

    /** 命中缓存条数（调试/单测可观测）。 */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * 失真改写公共骨架：缓存命中直接返回 → AI 改写（info_distortion 模板，scene 记账）→
     * 失败/关断回退规则兜底。
     */
    private String distort(Long projectId, String cacheKey, String audienceLabel, String content,
                           List<String> distortionRules, String spreadContext) {
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String result = null;
        if (props.isDistortionAiEnabled() && promptTemplateService != null
                && aiChatGateway != null && usageLogService != null) {
            result = tryAiDistortion(projectId, audienceLabel, content, distortionRules, spreadContext);
        }
        if (result == null || result.isBlank()) {
            result = ruleFallback(content, distortionRules, spreadContext);
        }
        // 缓存写入 + 上限保护
        if (cache.size() >= props.getDistortionCacheMaxEntries()) {
            cache.clear();
            log.debug("[信息失真] 缓存超上限清空: 上限={}", props.getDistortionCacheMaxEntries());
        }
        cache.put(cacheKey, result);
        return result;
    }

    /**
     * AI 失真改写（info_distortion 模板 → 文本输出，scene=info_distortion 记账）：
     * 输入=受众身份 + 信息原文 + 失真规则 + 传播上下文；输出=失真后文本（≤1 次重试）。
     *
     * @return 失真文本（失败返回 null，调用方回退规则兜底）
     */
    private String tryAiDistortion(Long projectId, String audienceLabel, String content,
                                   List<String> distortionRules, String spreadContext) {
        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return AiCallContext.run(SCENE_DISTORTION, null, attempt, () -> {
                    Map<String, Object> placeholders = new LinkedHashMap<>();
                    placeholders.put("audience", audienceLabel == null ? "（未知受众）" : audienceLabel);
                    placeholders.put("info_content", content);
                    placeholders.put("distortion_rules", String.join("；", distortionRules));
                    placeholders.put("spread_context", spreadContext == null ? "（无传播上下文）" : spreadContext);
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_INFO_DISTORTION, placeholders);
                    // 批6 收口（信息失真改写属「世界视角」）：前置 段1A【世界观】+ 段2D 当前世界动态（零阻断）
                    String prefix = aiContextInjector == null ? ""
                            : aiContextInjector.worldContextPrefix(projectId, null);
                    String finalPrompt = prefix.isBlank() ? prompt : prefix + "\n\n" + prompt;
                    String sys = promptTemplateService.systemMessage(userId, projectId,
                            PromptTemplateService.CODE_INFO_DISTORTION, null);
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", sys == null || sys.isBlank()
                                    ? "你是一位消息转述者。" : sys),
                            new AiChatRequest.ChatMessage("user", finalPrompt)),
                            props.getDistortionTemperature(), props.getDistortionMaxTokens(), false);
                    long startMs = System.currentTimeMillis();
                    AiChatResult result = aiChatGateway.chatCompletion(userId, projectId, null, req);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                            SCENE_DISTORTION, result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - startMs));
                    return cleanText(result.content());
                });
            } catch (Exception e) {
                log.warn("[信息失真] AI 改写第 {} 次失败（回退规则兜底）: project={} : {}",
                        attempt, projectId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 规则兜底失真（零 AI 成本，确定性可单测）：按失真规则关键词 + 可信度 + 传播上下文生成
     * 「传闻版」——夸大规则 → 「据说/传言」+ 程度副词；隐瞒规则 → 模糊化（省略细节）；
     * 缩小规则 → 弱化程度词；无规则命中按可信度加前缀（<0.4 风闻 / <0.7 据说 / 其余原样），
     * 并附传播渠道来源（「据{渠道}传来的消息」）。
     */
    static String ruleFallback(String content, List<String> distortionRules, String spreadContext) {
        if (content == null) {
            return null;
        }
        if (content.isBlank()) {
            return "";
        }
        String text = content.trim();
        String prefix = "";
        String suffix = "";
        if (distortionRules != null && !distortionRules.isEmpty()) {
            String joined = String.join("，", distortionRules);
            if (joined.contains("夸大") || joined.contains("添油加醋") || joined.contains("夸张")) {
                prefix = "据说事情闹得比传言还大——";
            } else if (joined.contains("隐瞒") || joined.contains("藏") || joined.contains("讳莫如深")) {
                prefix = "有人讳莫如深地提到，";
                suffix = "（细节语焉不详）";
            } else if (joined.contains("缩小") || joined.contains("淡化")) {
                prefix = "听人说那事没那么要紧，";
            } else {
                prefix = "据传";
            }
        } else {
            prefix = "听人说起，";
        }
        StringBuilder sb = new StringBuilder(prefix);
        if (spreadContext != null && !spreadContext.isBlank()) {
            sb.append(spreadContext).append("，");
        }
        sb.append(text);
        if (!suffix.isBlank()) {
            sb.append(suffix);
        }
        return sb.toString();
    }

    /** 清理 AI 输出文本（去首尾空白/引号/Markdown 代码块包裹）。 */
    static String cleanText(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastIdx = t.lastIndexOf("```");
            if (firstNewline >= 0 && lastIdx > firstNewline) {
                t = t.substring(firstNewline + 1, lastIdx).trim();
            }
        }
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("「") && t.endsWith("」"))
                || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
