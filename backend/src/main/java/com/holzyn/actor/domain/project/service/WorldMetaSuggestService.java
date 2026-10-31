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

package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.TokenEstimator;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.vo.WorldMetaSuggestVO;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.Supplier;

/**
 * 世界名与世界简介 AI 建议（新建世界向导「世界名与简介」步）。
 *
 * <p><b>为什么需要它</b>：创意发散生成的世界观是一大段设定（六个分节、上万字），
 * 但世界画廊卡片只有两个短字段——世界名与一句话简介。让用户从一万字里自己憋一句
 * 简介是把 AI 的产物成本转嫁给用户；这里由 AI 反推两个字段并<b>硬约束字数</b>
 * （名字 6-14 字、简介 60-100 字），用户仍可在表单里改写，落库取用户最终值。</p>
 *
 * <p><b>场景码</b>：{@code world_meta_suggest}（PromptSpecRegistry 已登记 + PromptTemplateService
 * 内置模板版本留痕；本服务按内置模板口径内联组装，运行时零模板库依赖，
 * 与 {@code PlayerProfileGenService} 同模式）。</p>
 *
 * <p><b>失败语义</b>：世界观设定缺失 → 400 中文提示；AI 失败/输出不可解析 → 抛
 * {@link AiCallException}（全局处理器转中文提示），由前端提示用户手填。</p>
 *
 * <p><b>架构不变量</b>：含 AI 调用，<b>不得</b>带 {@code @Transactional}（AGENTS §8.1.1）。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 */
@Slf4j
@Service
public class WorldMetaSuggestService {

    /** 场景码（trace / 路由 / 模板版本留痕） */
    public static final String SCENE_WORLD_META_SUGGEST = "world_meta_suggest";

    /** 世界名最短字数 */
    public static final int NAME_MIN = 6;

    /** 世界名最长字数 */
    public static final int NAME_MAX = 14;

    /** 世界简介最短字数 */
    public static final int SUMMARY_MIN = 60;

    /** 世界简介最长字数 */
    public static final int SUMMARY_MAX = 100;

    /** 送入 AI 的单字段最大长度（防止 prompt 撑爆；设定正文本来就长千字量级） */
    private static final int FIELD_MAX = 1200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** AI 网关（非流式） */
    @Lazy
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;

    /** 用量日志（scene=world_meta_suggest 记账） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** 世界观设定（唯一输入源） */
    @Autowired(required = false)
    private ActorWorldSettingRepository worldSettingRepository;

    /**
     * 根据项目最新世界观设定，生成世界名与世界简介建议（不写库）。
     *
     * @param userId    用户 ID（用量记账归属）
     * @param projectId 项目 ID
     *
     * @return 建议结果（name / summary 已按字数约束规整）
     *
     * @throws BizException    世界观设定不存在
     * @throws AiCallException AI 未配置 / 调用失败 / 输出不可解析
     */
    public WorldMetaSuggestVO suggest(Long userId, Long projectId) {
        if (worldSettingRepository == null || aiChatGateway == null) {
            throw new AiCallException("AI 能力未装配，请手动填写世界名与世界简介");
        }
        ActorWorldSetting setting = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> new BizException(400, "该项目还没有世界观设定，无法自动生成世界名与简介"));
        String userPrompt = userPrompt(setting);
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_WORLD_META_SUGGEST);
        AiChatRequest request = new AiChatRequest(null,
                List.of(new AiChatRequest.ChatMessage("system", systemPrompt()),
                        new AiChatRequest.ChatMessage("user", userPrompt)),
                spec.temperature(), spec.maxOutputTokens(), false);
        AiChatResult result;
        try {
            result = AiCallContext.run(SCENE_WORLD_META_SUGGEST, null, (Supplier<AiChatResult>) () ->
                    aiChatGateway.chatCompletion(userId, projectId, null, request));
        } catch (AiCallException e) {
            throw new AiCallException("世界名与简介生成失败：" + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("[世界命名] AI 调用异常（{}）：{}", SCENE_WORLD_META_SUGGEST, e.getMessage());
            throw new AiCallException("世界名与简介生成失败：" + e.getMessage(), e);
        }
        String text = result == null || result.content() == null ? "" : result.content().trim();
        WorldMetaSuggestVO vo = parse(text, setting);
        if (vo == null) {
            throw new AiCallException("AI 未返回有效的世界名与简介，请手动填写");
        }
        recordUsage(userId, result, request, text);
        return vo;
    }

    /** 系统提示（与 PromptTemplateService.CODE_WORLD_META_SUGGEST 内置模板同口径）。 */
    private String systemPrompt() {
        return """
                你是世界命名师：为虚构世界取名并写画廊简介，严格遵守字数与输出 JSON 的约束。
                只输出一个 JSON 对象：{"name":"世界名（6-14 字）","summary":"世界简介（60-100 字）"}
                """;
    }

    /** 用户提示：把世界观六个分节压成可读输入。 */
    private String userPrompt(ActorWorldSetting s) {
        StringBuilder sb = new StringBuilder();
        sb.append("【世界设定】\n");
        appendField(sb, "世界观名称", s.getName());
        appendField(sb, "题材", s.getGenre());
        appendField(sb, "时代", s.getEra());
        appendField(sb, "地理", s.getGeography());
        appendField(sb, "势力", s.getFactions());
        appendField(sb, "规则体系", s.getMagicSystem());
        appendField(sb, "文化", s.getCulture());
        appendField(sb, "历史", s.getHistory());
        appendField(sb, "补充", s.getFreeText());
        sb.append("\n【任务】\n");
        sb.append("请给出这个世界的名字（").append(NAME_MIN).append("-").append(NAME_MAX)
                .append(" 个汉字，有辨识度，可带短副题）与一句话简介（").append(SUMMARY_MIN).append("-")
                .append(SUMMARY_MAX).append(" 个汉字，说清「这是什么世界 + 最独特的冲突」），")
                .append("只输出 {\"name\":\"…\",\"summary\":\"…\"}，不要 Markdown 代码块。\n");
        return sb.toString();
    }

    /** 追加一个分节（空值跳过，超长截断） */
    private void appendField(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        if (v.length() > FIELD_MAX) {
            v = v.substring(0, FIELD_MAX) + "…";
        }
        sb.append(label).append("：").append(v).append('\n');
    }

    /**
     * 解析 AI 输出并按字数约束规整；不可解析返回 null。
     *
     * <p>字数不达标时的处理：名字超长截断、简介超长按句读截断；短于下限不补写
     * （补写只会得到注水文案），仍返回给用户改写。</p>
     */
    private WorldMetaSuggestVO parse(String text, ActorWorldSetting setting) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode root = tryRead(text);
        if (root == null) {
            int s = text.indexOf('{');
            int e = text.lastIndexOf('}');
            if (s >= 0 && e > s) {
                root = tryRead(text.substring(s, e + 1));
            }
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        String name = root.path("name").asString("").trim();
        String summary = root.path("summary").asString("").trim();
        name = normalizeName(name, setting);
        summary = normalizeSummary(summary);
        if (name.isBlank() || summary.isBlank()) {
            return null;
        }
        return new WorldMetaSuggestVO(name, summary);
    }

    /** 名字规整：去引号/换行，超长截断；模型没给名字时回退世界观名称 */
    private static String normalizeName(String name, ActorWorldSetting setting) {
        String v = oneLine(name);
        if (v.isBlank()) {
            v = oneLine(setting == null ? null : setting.getName());
        }
        if (v.length() > NAME_MAX) {
            v = v.substring(0, NAME_MAX);
        }
        return v;
    }

    /** 简介规整：单行、超长按句读截断到上限 */
    private static String normalizeSummary(String summary) {
        String v = oneLine(summary);
        if (v.length() <= SUMMARY_MAX) {
            return v;
        }
        String cut = v.substring(0, SUMMARY_MAX);
        int lastBreak = Math.max(cut.lastIndexOf('。'), Math.max(cut.lastIndexOf('；'), cut.lastIndexOf('，')));
        return lastBreak >= SUMMARY_MIN ? cut.substring(0, lastBreak + 1) : cut;
    }

    /** 去首尾引号、压缩空白为单行 */
    private static String oneLine(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.replace("\r", "").replace("\n", " ").trim();
        while (v.startsWith("\"") || v.startsWith("“") || v.startsWith("「")) {
            v = v.substring(1).trim();
        }
        while (v.endsWith("\"") || v.endsWith("”") || v.endsWith("」")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return v.replaceAll("\\s{2,}", " ").trim();
    }

    private static JsonNode tryRead(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 用量记账（scene=world_meta_suggest；失败仅告警不阻断） */
    private void recordUsage(Long userId, AiChatResult result, AiChatRequest request, String text) {
        if (usageLogService == null) {
            return;
        }
        try {
            Integer in = result.promptTokens() != null ? result.promptTokens()
                    : TokenEstimator.estimatePromptTokens(request.messages());
            Integer out = result.completionTokens() != null ? result.completionTokens()
                    : TokenEstimator.estimateTokens(text);
            usageLogService.record(userId, null, null,
                    result.providerId(), result.model(), SCENE_WORLD_META_SUGGEST, in, out,
                    result.cacheHitTokens(), result.cacheMissTokens(), null);
        } catch (Exception e) {
            log.warn("[世界命名] 用量记账失败（忽略）：{}", e.getMessage());
        }
    }
}
