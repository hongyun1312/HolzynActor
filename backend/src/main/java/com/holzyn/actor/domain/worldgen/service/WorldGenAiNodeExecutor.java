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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.model.WorldGenDimensions;
import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 真实 Agent 节点执行器。
 * <p>职责：对 agent 节点执行「提示词模板落库 + 局部上下文组装 + json_mode 结构化输出 +
 * Schema 校验/自动修复/定向重试」：</p>
 * <ul>
 * <li><b>提示词合并策略</b>（方案 §3.3）：模板 = 节点 first_message 扩展——本执行器把
 * {@code actor_prompt_template} 的维度模板（worldgen_*，含 role/schema/JSON 铁律）渲染后
 * 拼接到节点 first_message（已含题材创意/前序维度，由 TaskRunner 填充）之前，
 * 局部上下文 = 前序维度产物注入【前序维度】段（TaskRunner 已完成）；</li>
 * <li><b>AI 调用</b>：经 {@link AiProviderRouter#chatCompletion}（jsonMode=true，温度/上限取节点参数或全局配置），
 * {@link AiCallContext#withTrace}（trace_id=taskId，节点/尝试贯穿）+ {@link AiCallContext#run}
 * （scene=worldgen，attempt=定向重试序号）记账 + trace；用量经 {@link UsageLogService} 落库；</li>
 * <li><b>输出校验</b>：{@link WorldGenSchemaValidator} 解析/校验/自动修复；失败定向重试
 * （schema-retry 次），并把上次错误作为提示回传；</li>
 * <li><b>检查点</b>：成功后按节点 outputFilePath 写 {@code world/*.json}（开关 checkpoint-enabled）；</li>
 * <li><b>开关</b>：总开关/真实 AI 关闭时回退桩占位输出（离线/空跑环境，链仍可跑通）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldGenAiNodeExecutor implements WorldGenNodeExecutor {

    /** Agent 语义名 → 提示词模板编码（对齐 agents.json 的 agentType 与方案 §5 模板落点） */
    static final Map<String, String> AGENT_TYPE_TO_PROMPT_CODE = Map.of(
            "novel-worldbuilder-corelaws", PromptTemplateService.CODE_WORLDGEN_CORELAWS,
            "novel-worldbuilder-spacetime", PromptTemplateService.CODE_WORLDGEN_SPACETIME,
            "novel-worldbuilder-society", PromptTemplateService.CODE_WORLDGEN_SOCIETY,
            "novel-worldbuilder-historyculture", PromptTemplateService.CODE_WORLDGEN_HISTORYCULTURE,
            "novel-worldbuilder-existence", PromptTemplateService.CODE_WORLDGEN_EXISTENCE,
            "novel-worldbuilder-information", PromptTemplateService.CODE_WORLDGEN_INFORMATION,
            // character 工作流（_05）：阵容 + 逐角色深度
            "holzyn-character-roster", PromptTemplateService.CODE_WORLDGEN_CHARACTER_ROSTER,
            "holzyn-character-depth", PromptTemplateService.CODE_WORLDGEN_CHARACTER_DEPTH,
            // story-plan 工作流（_06）：故事引擎（三层力学·世界版）+ 风格档案（风格宪法）
            "novel-story-planner", PromptTemplateService.CODE_WORLDGEN_STORY_PLAN,
            "novel-style-profiler", PromptTemplateService.CODE_WORLDGEN_STYLE_PROFILE);

    /** character 阵容 Agent 语义名 */
    static final String AGENT_ROSTER = "holzyn-character-roster";

    /** character 逐角色深度 Agent 语义名（batch 节点：对阵容每角色一次 AI 调用） */
    static final String AGENT_CHARACTER_DEPTH = "holzyn-character-depth";

    /** story-plan 故事引擎 Agent 语义名（novel-story-planner，三层力学引擎·世界版） */
    static final String AGENT_STORY_PLANNER = "novel-story-planner";

    /** story-plan 风格档案 Agent 语义名（novel-style-profiler，世界风格宪法） */
    static final String AGENT_STYLE_PROFILER = "novel-style-profiler";

    /** AI 输出上限兜底（超 8192 部分供应商不接受） */
    private static final int MAX_TOKENS_CAP = 8192;

    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService promptTemplateService;
    private final CurrentUserProvider currentUserProvider;
    private final UsageLogService usageLogService;
    private final WorldGenSchemaValidator schemaValidator;
    private final LocalArchiveService archiveService;
    private final WorldGenProperties props;

    /**
     * 本执行器精确接管 agent 类型节点。
     */
    @Override
    public String supportedNodeType() {
        return WorldGenStepType.AGENT;
    }

    /**
     * 执行 agent 节点（真实 AI 或开关关闭时桩占位）。
     *
     * @param ctx 节点执行上下文
     *
     * @return 执行结果（成功=校验通过的维度 JSON + Token 账本；失败=错误信息）
     */
    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        long start = System.currentTimeMillis();
        try {
            // 模拟失败钩子
            if (ctx.simulateFailNodeIds() != null && ctx.simulateFailNodeIds().contains(ctx.node().getId())) {
                String msg = "模拟失败（simulateFailNodeIds 命中）：node=" + ctx.node().getId();
                log.info("[worldgen] {}", msg);
                return NodeExecutionResult.failure(msg);
            }
            // 总开关 / 真实 AI 关闭 → 桩占位输出（空跑冒烟/离线环境）
            if (!props.isEnabled() || !props.isAiEnabled()) {
                return stubPlaceholder(ctx, start);
            }
            // ：batch 节点（逐角色深度生成）→ 独立执行路径
            if (isBatchNode(ctx)) {
                return executeBatch(ctx, start);
            }
            String promptCode = AGENT_TYPE_TO_PROMPT_CODE.get(ctx.node().getAgentType());
            if (promptCode == null) {
                return NodeExecutionResult.failure("未配置维度提示词模板（agentType=" + ctx.node().getAgentType() + "）");
            }

            Long userId = currentUserProvider.currentUserId();
            Long projectId = ctx.projectId();
            // 语言（任务输入缺省中文；模板/首消息统一按此约束产出语言）
            String language = ctx.taskInputs() == null ? "中文"
                    : ctx.taskInputs().getOrDefault("language", "中文");
            String languageVal = language == null || language.isBlank() ? "中文" : language;

            // 合并策略：模板（role/schema/JSON 铁律，渲染 {{language}}）+ 节点 first_message（题材/前序维度）
            String templatePart = promptTemplateService.render(userId, projectId, promptCode,
                    Map.of("language", languageVal));
            String firstMessage = ctx.filledInput() == null ? "" : ctx.filledInput();
            String systemPrompt = promptTemplateService.systemMessage(userId, projectId, promptCode,
                    Map.of("language", languageVal));

            // 节点模型参数（温度/输出上限；缺省取全局配置）
            double temperature = numParam(ctx.node().getNodeParams(), "temperature", props.getTemperature());
            int maxTokens = (int) Math.min(MAX_TOKENS_CAP,
                    Math.max(1024, numParam(ctx.node().getNodeParams(), "max_tokens", props.getMaxTokens())));

            // 定向重试循环：首次调用 + schemaRetry 次修复重试（重试时把上次校验错误回传提示词）
            int totalAttempts = Math.max(1, props.getSchemaRetry() + 1);
            String lastError = null;
            for (int attempt = 1; attempt <= totalAttempts; attempt++) {
                String prompt = attempt == 1 ? (templatePart + "\n\n" + firstMessage)
                        : (templatePart + "\n\n" + firstMessage + "\n\n【上次输出未通过校验，请修正后重新输出】\n"
                        + (lastError == null ? "" : lastError));
                AiChatResult result = callAi(userId, projectId, systemPrompt, prompt, temperature, maxTokens,
                        ctx.taskId(), attempt, start);
                try {
                    String validated = validateAgentOutput(ctx, result.content());
                    // 成功：写检查点文件 + 返回成功（带 Token 账本）
                    writeCheckpoint(ctx, validated);
                    long ms = System.currentTimeMillis() - start;
                    int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                    int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                    log.info("[worldgen] 维度生成成功：task={}, node={}, attempt={}, {}ms, tokens={}/{}",
                            ctx.taskId(), ctx.node().getId(), attempt, ms, tokenIn, tokenOut);
                    return NodeExecutionResult.success(validated, tokenIn, tokenOut, ms);
                } catch (BizException e) {
                    lastError = e.getMessage();
                    log.warn("[worldgen] 维度校验失败（第 {}/{} 次）：task={}, node={}, error={}",
                            attempt, totalAttempts, ctx.taskId(), ctx.node().getId(), lastError);
                }
            }
            return NodeExecutionResult.failure("维度「" + ctx.node().getLabel() + "」输出校验失败（已重试 "
                    + (totalAttempts - 1) + " 次）：" + (lastError == null ? "未知错误" : lastError));
        } catch (Exception e) {
            log.warn("[worldgen] Agent 节点执行异常：task={}, node={}, error={}",
                    ctx.taskId(), ctx.node().getId(), e.getMessage());
            return NodeExecutionResult.failure("Agent 节点执行异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /**
     * 调用 AI（json_mode 结构化输出；trace_id=taskId 贯穿 + scene=worldgen 记账 + 用量落库）。
     */
    private AiChatResult callAi(Long userId, Long projectId, String systemPrompt, String prompt,
                                double temperature, int maxTokens, Long taskId, int attempt, long start) {
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", systemPrompt),
                new AiChatRequest.ChatMessage("user", prompt)), temperature, maxTokens, true);
        AiChatResult result = AiCallContext.withTrace(String.valueOf(taskId), () ->
                AiCallContext.run("worldgen", null, attempt, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req)));
        usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "worldgen",
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - start));
        return result;
    }

    /**
     * 成功产物写检查点文件（开关 checkpoint-enabled + 节点 saveOutputToFile/outputFilePath）。
     */
    private void writeCheckpoint(NodeExecutionContext ctx, String validatedJson) {
        if (!props.isCheckpointEnabled() || ctx.projectId() == null) {
            return;
        }
        Boolean saveFile = ctx.node().getSaveOutputToFile();
        String outputPath = ctx.node().getOutputFilePath();
        if (saveFile == null || !saveFile || outputPath == null || outputPath.isBlank()) {
            return;
        }
        Path workspace = archiveService.taskWorkspace(ctx.projectId(), ctx.taskId());
        archiveService.writeJson(workspace, outputPath, validatedJson);
        log.info("[worldgen] 检查点已写盘：task={}, node={}, file={}", ctx.taskId(), ctx.node().getId(), outputPath);
    }

    /**
     * 维度键（由 Agent 语义名经 {@link WorldGenDimensions} 显式映射，如
     * novel-worldbuilder-corelaws → core_laws；注意语义名末段无下划线，不可字符串切分推导）。
     */
    private String dimKeyOf(NodeExecutionContext ctx) {
        String dimKey = WorldGenDimensions.dimKeyOf(ctx.node().getAgentType());
        return dimKey != null ? dimKey : ctx.node().getId();
    }

    /**
     * 开关关闭时的桩占位输出。
     */
    private NodeExecutionResult stubPlaceholder(NodeExecutionContext ctx, long start) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("stub", true);
        out.put("dimension", ctx.node().getLabel());
        out.put("nodeId", ctx.node().getId());
        out.put("premise", ctx.taskInputs() == null ? "" : ctx.taskInputs().getOrDefault("premise", ""));
        out.put("genre", ctx.taskInputs() == null ? "" : ctx.taskInputs().getOrDefault("genre", ""));
        out.put("language", ctx.taskInputs() == null ? "" : ctx.taskInputs().getOrDefault("language", "中文"));
        out.put("upstreamDimensions", new java.util.ArrayList<>(ctx.upstreamOutputs().keySet()));
        out.put("outputVariable", ctx.node().getOutputVariable());
        out.put("outputFilePath", ctx.node().getOutputFilePath());
        try {
            return NodeExecutionResult.success(new tools.jackson.databind.ObjectMapper().writeValueAsString(out),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return NodeExecutionResult.success("{\"stub\":true}", System.currentTimeMillis() - start);
        }
    }

    /**
     * 取节点参数数值（缺省回退默认值）。
     */
    private double numParam(Map<String, Object> params, String key, double def) {
        if (params == null) {
            return def;
        }
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignored) {
                // 非数值按默认
            }
        }
        return def;
    }

    // ==================== character 工作流：batch 逐角色深度生成（_05） ====================

    /**
     * 是否 batch 节点（nodeParams.batch=true：对上游阵容数组逐角色循环 AI 调用）。
     *
     * @param ctx 节点执行上下文
     *
     * @return true=batch 节点
     */
    private boolean isBatchNode(NodeExecutionContext ctx) {
        Object batch = ctx.node().getNodeParams() == null ? null : ctx.node().getNodeParams().get("batch");
        return Boolean.TRUE.equals(batch);
    }

    /**
     * Agent 输出校验分发：character roster 走阵容校验；build 维度走维度 Schema 校验；
     * story-plan 引擎/风格走对应校验。
     *
     * @param ctx 节点执行上下文
     *
     * @param content 模型输出原始文本
     *
     * @return 规范化后的 JSON 文本
     *
     * @throws BizException 校验失败
     */
    private String validateAgentOutput(NodeExecutionContext ctx, String content) {
        if (AGENT_ROSTER.equals(ctx.node().getAgentType())) {
            return schemaValidator.validateRoster(content);
        }
        if (AGENT_STORY_PLANNER.equals(ctx.node().getAgentType())) {
            return schemaValidator.validateStoryPlan(content);
        }
        if (AGENT_STYLE_PROFILER.equals(ctx.node().getAgentType())) {
            return schemaValidator.validateStyleProfile(content);
        }
        return schemaValidator.validateAndRepair(dimKeyOf(ctx), content);
    }

    /**
     * batch 节点执行（agent_character_depth）：对阵容（上游 batchInputVariable 输出）逐角色
     * 渲染深度提示词并调用 AI，每角色独立校验 + 检查点落盘。
     * <p>要点：</p>
     * <ul>
     * <li><b>检查点跳过</b>：角色产物文件（batchOutputPattern 模板，如 characters/{name}.json）
     * 已存在 → 该角色跳过（节点重试不重复付 AI 费，防半截重跑白付）；</li>
     * <li><b>阵容截断</b>：超出 {@code holzyn.actor.worldgen.roster-limit}（默认 20）截断；</li>
     * <li><b>定向重试</b>：每角色 schemaRetry 次修复重试（与维度节点一致）；单角色失败 → 节点失败
     * （已成功角色检查点保留，重试时自动跳过）；</li>
     * <li><b>汇总输出</b>：{@code {"characters": [{name, skipped?, depth}]}} + Token 汇总。</li>
     * </ul>
     *
     * @param ctx 节点执行上下文
     *
     * @param start 节点开始时刻（耗时记账）
     *
     * @return 执行结果
     */
    private NodeExecutionResult executeBatch(NodeExecutionContext ctx, long start) {
        Long userId = currentUserProvider.currentUserId();
        Long projectId = ctx.projectId();
        String language = ctx.taskInputs() == null ? "中文"
                : ctx.taskInputs().getOrDefault("language", "中文");
        String languageVal = language == null || language.isBlank() ? "中文" : language;
        String promptCode = AGENT_TYPE_TO_PROMPT_CODE.get(ctx.node().getAgentType());
        if (promptCode == null) {
            return NodeExecutionResult.failure("未配置 batch 提示词模板（agentType=" + ctx.node().getAgentType() + "）");
        }
        // 上游阵容（batchInputVariable 指定的输出变量）
        String rosterVar = strParam(ctx.node().getNodeParams(), "batchInputVariable", null);
        String rosterJson = rosterVar == null ? null : ctx.upstreamOutputs().get(rosterVar);
        if (rosterJson == null || rosterJson.isBlank()) {
            return NodeExecutionResult.failure("batch 节点缺少上游阵容输出（batchInputVariable=" + rosterVar + "）");
        }
        List<tools.jackson.databind.JsonNode> roster = schemaValidator.readRoster(rosterJson);
        int limit = props.getRosterLimit();
        if (limit > 0 && roster.size() > limit) {
            int before = roster.size();
            roster = roster.subList(0, limit);
            log.info("[worldgen] batch 阵容截断：task={}, 角色 {} → {}", ctx.taskId(), before, limit);
        }
        if (roster.isEmpty()) {
            return NodeExecutionResult.failure("角色阵容为空，无法生成深度档案");
        }
        String batchPattern = strParam(ctx.node().getNodeParams(), "batchOutputPattern", null);
        Path workspace = archiveService.taskWorkspace(projectId, ctx.taskId());
        String templatePart = promptTemplateService.render(userId, projectId, promptCode,
                Map.of("language", languageVal));
        String systemPrompt = promptTemplateService.systemMessage(userId, projectId, promptCode,
                Map.of("language", languageVal));
        double temperature = numParam(ctx.node().getNodeParams(), "temperature", props.getTemperature());
        int maxTokens = (int) Math.min(MAX_TOKENS_CAP,
                Math.max(1024, numParam(ctx.node().getNodeParams(), "max_tokens", props.getMaxTokens())));
        int totalAttempts = Math.max(1, props.getSchemaRetry() + 1);

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int tokenIn = 0, tokenOut = 0;
        int generated = 0, skipped = 0;
        for (tools.jackson.databind.JsonNode ch : roster) {
            String name = ch.path("name").asText("").trim();
            // 检查点跳过：角色文件已存在 → 跳过（重试防重复付 AI 费）
            String relFile = null;
            if (batchPattern != null && !batchPattern.isBlank()) {
                relFile = batchPattern.replace("{name}", safeFileName(name));
                if (props.isCheckpointEnabled() && projectId != null
                        && java.nio.file.Files.isRegularFile(archiveService.safeResolve(workspace, relFile))) {
                    results.add(Map.of("name", name, "skipped", true));
                    skipped++;
                    log.info("[worldgen] batch 角色检查点已存在，跳过：task={}, 角色={}", ctx.taskId(), name);
                    continue;
                }
            }
            // 本角色 prompt（模板 + 角色信息 + 深度生成指令）
            String rolePrompt = "【本角色】\n" + ch.toString() + "\n\n请为角色「" + name + "」生成深度档案（骨架/信念/深层/声线 4 维），skeleton.name 必须与上方 name 完全一致。";
            String lastError = null;
            boolean ok = false;
            for (int attempt = 1; attempt <= totalAttempts; attempt++) {
                String prompt = attempt == 1
                        ? (templatePart + "\n\n" + rolePrompt)
                        : (templatePart + "\n\n" + rolePrompt + "\n\n【上次输出未通过校验，请修正后重新输出】\n"
                        + (lastError == null ? "" : lastError));
                AiChatResult result = callAi(userId, projectId, systemPrompt, prompt, temperature, maxTokens,
                        ctx.taskId(), attempt, start);
                try {
                    String validated = schemaValidator.validateCharacterDepth(result.content());
                    if (relFile != null && props.isCheckpointEnabled() && projectId != null) {
                        archiveService.writeJson(workspace, relFile, validated);
                    }
                    tokenIn += result.promptTokens() == null ? 0 : result.promptTokens();
                    tokenOut += result.completionTokens() == null ? 0 : result.completionTokens();
                    results.add(Map.of("name", name, "skipped", false, "depth",
                            new tools.jackson.databind.ObjectMapper().readTree(validated)));
                    generated++;
                    ok = true;
                    break;
                } catch (BizException e) {
                    lastError = e.getMessage();
                    log.warn("[worldgen] batch 角色校验失败（第 {}/{} 次）：task={}, 角色={}, error={}",
                            attempt, totalAttempts, ctx.taskId(), name, lastError);
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }
            if (!ok) {
                return NodeExecutionResult.failure("角色「" + name + "」深度生成失败（已重试 "
                        + (totalAttempts - 1) + " 次）：" + (lastError == null ? "未知错误" : lastError));
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("characters", results);
        long ms = System.currentTimeMillis() - start;
        log.info("[worldgen] batch 节点成功：task={}, node={}, 生成 {} 个（跳过 {} 个），{}ms, tokens={}/{}",
                ctx.taskId(), ctx.node().getId(), generated, skipped, ms, tokenIn, tokenOut);
        try {
            return NodeExecutionResult.success(new tools.jackson.databind.ObjectMapper().writeValueAsString(out),
                    tokenIn, tokenOut, ms);
        } catch (Exception e) {
            return NodeExecutionResult.failure("batch 汇总序列化失败：" + e.getMessage());
        }
    }

    /**
     * 取节点参数字符串（缺省回退默认值）。
     */
    private String strParam(Map<String, Object> params, String key, String def) {
        if (params == null) {
            return def;
        }
        Object v = params.get(key);
        return v == null ? def : String.valueOf(v);
    }

    /**
     * 角色名 → 安全文件名（路径分隔/非法字符替换为 _）。
     */
    static String safeFileName(String name) {
        if (name == null) {
            return "unnamed";
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
