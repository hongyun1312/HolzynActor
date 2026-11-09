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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.prompt.PromptResources;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 巡检：把全部内置模板按真实结构渲染一遍（可选逐个真实调用一次），用于回答三个问题。
 *
 * <p><b>为什么需要它</b>：缓存命中率只有在"不同 Prompt 的第一段逐字相同"时才成立，而这件事
 * 靠读代码是看不出来的——必须把每条模板的最终输入（system + 渲染后的 user）摆在一起，量出
 * 它们的最长公共前缀。这一层同时提供"每条模板真跑一次"的入口：不按 NPC 数量放大，只跑一遍，
 * 用真实的 token/cache_hit 数据验证前缀设计是否生效。</p>
 *
 * <p><b>结构约定</b>（{@code PromptTemplateService.render} 负责）：</p>
 * <ol>
 *   <li>system = 统一 system（{@code prompts/_shared/system.md}），所有场景逐字相同；</li>
 *   <li>user 首段 = 全局契约（{@code prompts/_shared/contract.md}），逐字相同；</li>
 *   <li>user 次段 = 本世界设定（项目级，逐字相同）；</li>
 *   <li>user 中段 = 模板正文（各场景不同，是唯一"分叉点"）；</li>
 *   <li>user 末段 = 输出要求（{@code prompts/_shared/requirements.md}）。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/prompts")
@RequiredArgsConstructor
public class PromptAuditController {

    /** 占位符语法（与 PromptTemplateService 一致）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /** 内置模板服务（渲染 + 统一 system + 前缀装配）。 */
    private final PromptTemplateService promptTemplateService;

    /** AI 网关（真跑一遍时使用）。 */
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 世界设定（给占位符填真实世界观，避免结构巡检出现空段）。 */
    @Autowired(required = false)
    private ActorWorldSettingRepository worldSettingRepository;

    /**
     * 内置模板清单（编码 + 显示名 + 正文/占位符规模）。
     *
     * @return 模板清单
     */
    @GetMapping("/codes")
    public R<List<Map<String, Object>>> codes() {
        List<Map<String, Object>> out = new ArrayList<>();
        PromptTemplateService.builtinCodes().stream().sorted().forEach(code -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", PromptTemplateService.builtinName(code));
            row.put("category", PromptTemplateService.categoryOf(code));
            row.put("userChars", PromptResources.user(code).length());
            row.put("placeholders", placeholdersOf(PromptResources.user(code)));
            out.add(row);
        });
        return R.ok(out);
    }

    /**
     * 只渲染不调用：把每条模板的最终输入摆出来，并量出与上一条的最长公共前缀。
     *
     * <p>返回的 {@code lcpChars} 是「本条与上一条 system+user 的逐字相同前缀长度」，
     * {@code estHitRatio} = 1 - 512/估算token，用于判断前缀设计是否够长（≥0.9 即达标）。</p>
     *
     * @param body {projectId, userId, codes?, maxChars?}
     *
     * @return 渲染结果
     */
    @PostMapping("/dump")
    public R<Map<String, Object>> dump(@RequestBody(required = false) Map<String, Object> body) {
        Long projectId = longOf(body, "projectId");
        Long userId = userIdOf(body);
        int maxChars = body != null && body.get("maxChars") != null ? intOf(body, "maxChars") : 4000;
        List<String> codes = codesOf(body);
        Map<String, Object> ctx = placeholderValues(projectId);

        List<Map<String, Object>> items = new ArrayList<>();
        String prev = null;
        String first = null;
        long lcpSum = 0;
        int lcpMin = Integer.MAX_VALUE;
        for (String code : codes) {
            String system = promptTemplateService.systemMessage(userId, projectId, code, null);
            String user = promptTemplateService.render(userId, projectId, code, new LinkedHashMap<>(ctx));
            String full = (system == null ? "" : system) + "\n" + user;
            if (first == null) {
                first = full;
            }
            int lcp = prev == null ? 0 : commonPrefix(prev, full);
            int lcpFirst = commonPrefix(first, full);
            if (prev != null) {
                lcpSum += lcp;
                lcpMin = Math.min(lcpMin, lcp);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", PromptTemplateService.builtinName(code));
            row.put("category", PromptTemplateService.categoryOf(code));
            row.put("systemChars", system == null ? 0 : system.length());
            row.put("userChars", user.length());
            row.put("lcpChars", lcp);
            row.put("lcpFirstChars", lcpFirst);
            row.put("estTokens", Math.round(user.length() / 1.8));
            row.put("estHitRatio", estHit(lcpFirst));
            row.put("userHead", user.substring(0, Math.min(160, user.length())));
            row.put("userTail", user.length() > maxChars ? user.substring(user.length() - Math.min(160, user.length())) : "");
            row.put("user", user.length() > maxChars ? user.substring(0, maxChars) : user);
            items.add(row);
            prev = full;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", items.size());
        summary.put("avgLcpChars", items.size() > 1 ? lcpSum / (items.size() - 1) : 0);
        summary.put("minLcpChars", lcpMin == Integer.MAX_VALUE ? 0 : lcpMin);
        summary.put("note", "lcpChars=与上一条的逐字相同前缀；lcpFirstChars=与首条的逐字相同前缀（跨场景共享前缀的真实长度）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("items", items);
        return R.ok(out);
    }

    /**
     * 真跑一遍（SSE）：每条模板按真实结构调用一次 AI，逐条回传 token / 缓存命中 / 耗时。
     *
     * <p><b>串行</b>：本地单实例模型并发无益（预填吞吐固定），并行只会互相抢队列；
     * 串行 + 统一前缀才能让第 2 条起命中缓存。</p>
     *
     * @param projectId 项目 ID（世界设定来源）
     *
     * @param userId 归属用户（默认 1）
     *
     * @param codes 逗号分隔模板编码（空=全部）
     *
     * @param delayMs 每条之间的间隔（毫秒，默认 300）
     *
     * @return SSE 事件流（item / done / error）
     */
    @GetMapping(value = "/run-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@RequestParam Long projectId,
                                @RequestParam(required = false) Long userId,
                                @RequestParam(required = false) String codes,
                                @RequestParam(required = false, defaultValue = "300") long delayMs) {
        SseEmitter emitter = new SseEmitter(0L);
        Long uid = userId == null ? 1L : userId;
        List<String> targets = codes == null || codes.isBlank()
                ? PromptTemplateService.builtinCodes().stream().sorted().toList()
                : List.of(codes.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        Map<String, Object> ctx = placeholderValues(projectId);
        Thread.ofVirtual().name("prompt-audit").start(() -> {
            int index = 0;
            try {
                for (String code : targets) {
                    index++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("index", index);
                    row.put("total", targets.size());
                    row.put("code", code);
                    row.put("name", PromptTemplateService.builtinName(code));
                    long start = System.currentTimeMillis();
                    try {
                        if (aiChatGateway == null) {
                            throw new IllegalStateException("AI 网关未装配");
                        }
                        String system = promptTemplateService.systemMessage(uid, projectId, code, null);
                        String user = promptTemplateService.render(uid, projectId, code, new LinkedHashMap<>(ctx));
                        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
                        if (system != null && !system.isBlank()) {
                            messages.add(new AiChatRequest.ChatMessage("system", system));
                        }
                        messages.add(new AiChatRequest.ChatMessage("user", user));
                        boolean json = PromptContractTypeRef.isJson(code);
                        AiChatRequest request = new AiChatRequest(null, messages,
                                PromptSpecRegistry.temperatureOf(code), PromptSpecRegistry.maxOutputTokensOf(code), json);
                        AiChatResult result = AiCallContext.run(code, null, 1,
                                () -> aiChatGateway.chatCompletion(uid, projectId, null, request));
                        int promptTokens = result.promptTokens() == null ? 0 : result.promptTokens();
                        int hit = result.cacheHitTokens() == null ? 0 : result.cacheHitTokens();
                        row.put("status", "success");
                        row.put("tokenIn", promptTokens);
                        row.put("cacheHit", hit);
                        row.put("cacheMiss", result.cacheMissTokens() == null ? 0 : result.cacheMissTokens());
                        row.put("hitRatio", promptTokens == 0 ? 0 : Math.round(1000.0 * hit / promptTokens) / 1000.0);
                        row.put("durationMs", System.currentTimeMillis() - start);
                        row.put("preview", abbreviate(result.content(), 300));
                    } catch (Exception e) {
                        row.put("status", "failed");
                        row.put("durationMs", System.currentTimeMillis() - start);
                        row.put("error", e.getMessage());
                        log.warn("[Prompt巡检] {} 调用失败：{}", code, e.getMessage());
                    }
                    emitter.send(SseEmitter.event().name("item").data(row, MediaType.APPLICATION_JSON));
                    Thread.sleep(Math.max(0L, delayMs));
                }
                emitter.send(SseEmitter.event().name("done").data(Map.of("count", targets.size()),
                        MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** PromptContractType 引用隔离（避免控制器直接依赖枚举细节）。 */
    private static final class PromptContractTypeRef {
        private PromptContractTypeRef() {
        }

        static boolean isJson(String code) {
            return PromptSpecRegistry.spec(code) != null
                    && PromptSpecRegistry.contractOf(code)
                    == com.holzyn.actor.domain.agent.prompt.PromptContractType.SCHEMA_JSON;
        }
    }

    /**
     * 占位符取值：能取真实值就取真实值（世界设定），取不到的按「待填」占位，
     * 保证结构上每个插入位都可见，而不是被静默删除。
     *
     * @param projectId 项目 ID
     *
     * @return 占位符映射
     */
    private Map<String, Object> placeholderValues(Long projectId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        String worldName = "南溪镇";
        String worldAll = "";
        if (projectId != null && worldSettingRepository != null) {
            try {
                ActorWorldSetting s = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
                if (s != null) {
                    if (s.getName() != null && !s.getName().isBlank()) {
                        worldName = s.getName();
                    }
                    worldAll = settingText(s);
                }
            } catch (Exception e) {
                log.debug("[Prompt巡检] 世界设定读取失败：{}", e.getMessage());
            }
        }
        ctx.put("world_setting", worldAll);
        ctx.put("world_name", worldName);
        ctx.put("world_context", worldAll);
        ctx.put("game_time", "南溪镇历 2015年09月16日 01时30分");
        ctx.put("game_day", "2015-09-16");
        ctx.put("game_hour", "1");
        ctx.put("hour", "1");
        ctx.put("season_context", "初秋，江南仍热，夜里凉");
        ctx.put("language", "简体中文");
        ctx.put("player_name", "无");
        ctx.put("npc_name", "周慧兰");
        ctx.put("character_name", "周慧兰");
        ctx.put("character_info", "周慧兰，52 岁，南溪镇居委会主任，做事细、说话慢，遇事先压情绪");
        ctx.put("character_skeleton", "{\"name\":\"周慧兰\",\"role\":\"居委会主任\",\"importance\":\"主角\"}");
        ctx.put("age", "52");
        ctx.put("name", "周慧兰");
        ctx.put("community_name", "南溪镇");
        ctx.put("affiliation", "居委会");
        ctx.put("occupation", "主任");
        ctx.put("locations", "南溪老街、永安桥、渡口、南溪小学、卫生院");
        ctx.put("relations", "丈夫陈国梁（河运承包户）、族老陈茂林（远房叔公）");
        ctx.put("characters", "周慧兰、陈茂林、陈伯、周德水、李老师、王阿婆、老赵、小刘");
        ctx.put("goals", "把老街的排水改造争取到今年入冬前动工");
        ctx.put("current_state", "刚开完居委会碰头会，手里还捏着改造申请");
        ctx.put("context", "2015 年秋，南方小镇，丝绸厂倒闭后的第三年");
        ctx.put("situation", "老街积水，几户人家找了居委会");
        ctx.put("stimulus", "有人拍门说巷子又淹了");
        ctx.put("scene", "南溪老街居委会办公室");
        ctx.put("scene_background", "午后阳光斜进屋里，风扇吱呀响，桌上摊着图纸");
        ctx.put("atmosphere", "闷热、琐碎、彼此都认识");
        ctx.put("title", "关于老街排水改造的申请");
        ctx.put("count", "3");
        ctx.put("history", "丝绸厂 2012 年停产，青壮年外出务工，留守老人增多");
        ctx.put("context_kind", "日常");
        ctx.put("user_message", "帮我把这周的安排捋一下");
        ctx.put("files_content", "（附件：老街排水现状示意图）");
        return ctx;
    }

    /** 世界设定拼成一段文本（与 PromptTemplateService 的世界设定段同源字段）。 */
    private static String settingText(ActorWorldSetting s) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "世界", s.getName());
        appendLine(sb, "题材", s.getGenre());
        appendLine(sb, "年代", s.getEra());
        appendLine(sb, "地理", s.getGeography());
        appendLine(sb, "势力", s.getFactions());
        appendLine(sb, "规则", s.getMagicSystem());
        appendLine(sb, "文化", s.getCulture());
        appendLine(sb, "历史", s.getHistory());
        appendLine(sb, "设定", s.getFreeText());
        return sb.toString();
    }

    /** 追加一行（空值省略，保证同项目多次渲染逐字一致）。 */
    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append("：").append(value.trim()).append('\n');
    }

    /** 最长公共前缀长度。 */
    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** 估算命中率：1 - 516/该前缀折算 token（与网关实测的 512/516 token 重算地板同口径）。 */
    private static double estHit(int prefixChars) {
        double tokens = Math.max(1.0, prefixChars / 1.8);
        double ratio = 1.0 - 516.0 / tokens;
        return Math.round(Math.max(0.0, ratio) * 1000.0) / 1000.0;
    }

    /** 占位符名提取。 */
    private static List<String> placeholdersOf(String template) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** 目标模板编码：默认全部，按编码排序保证可重复。 */
    private static List<String> codesOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("codes");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return PromptTemplateService.builtinCodes().stream().sorted().toList();
    }

    /** 用户 ID（默认 1）。 */
    private static Long userIdOf(Map<String, Object> body) {
        Long id = longOf(body, "userId");
        return id == null ? 1L : id;
    }

    /** 取 Long 参数。 */
    private static Long longOf(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(body.get(key)).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 int 参数。 */
    private static int intOf(Map<String, Object> body, String key) {
        Long v = longOf(body, key);
        return v == null ? 0 : v.intValue();
    }

    /** 截断预览。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
