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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 AI 调用的 trace 数据载体（可变构建器， 调试前端埋点 + B5 决策链路立基扩展）。
 * <p>职责：在一次 AI 调用生命周期内承载 scene/项目/角色/完整 Prompt/输出/状态/token/耗时，
 * 由 {@link AiProviderRouter}（埋点）填充，调用方可经 {@link AiCallContext#markDegraded()} 标记降级，
 * 最终由 {@link AiCallTraceService#submitAsync(AiCallTrace)} 异步落库为 {@link ActorAiCallLog}。</p>
 * <p><b>B5</b>：新增 traceId（业务动作级决策链路贯穿，经 {@link AiCallContext#withTrace} 包裹后
 * 块内全部 AI 调用共享）、attempt、stepType（环节类型：ai_call 缺省 /
 * perception/action/event 等非 AI 环节，content 存环节摘要）。</p>
 * <p><b>AI 调用详情 JSON</b>：新增
 * inputRaw/outputRaw（Provider 发送前/解析前截取的原始请求/响应体）、baseUrl（供应商地址）、
 * stream（是否流式）、temperature/maxTokens/jsonMode/messages（fillMessages 从 AiChatRequest 捕获）等字段，
 * {@link #toDetailJson(int)} 按设计文档 §1~§8 组装整条调用详情 JSON（含 ISO+08:00 时间轴、
 * provider/request_params/input/output/usage/status/error/retry_count/context/remark），
 * {@link #toEntity(int)} 写入 detail_json 列；context（RAG/记忆引用）本期恒 null（预留，
 * 等六组件决策链路接入时再注入）。</p>
 * <p>归属两条路径：<ul>
 * 包裹创建，块结束（finally）统一提交；</li>
 * <li><b>contextOwned=false</b>：调用方未包裹，Router 在 {@code end()} 时兜底提交（scene 记通用值）。</li>
 * </ul></p>
 */
public class AiCallTrace {

    /** 调用场景（AiCallContext.run 传入；无上下文兜底=chat/chat_stream） */
    private final String scene;

    /** 角色 ID（可空） */
    private final Long characterId;

    /** 调用开始时刻（毫秒，用于耗时统计） */
    private final long startMs = System.currentTimeMillis();

    /** 决策链路 trace_id（B5：withTrace 包裹时设置；可空） */
    private String traceId;

    /** 重试尝试序号 */
    private int attempt = 1;

    /** 环节类型（B5：ai_call 缺省 / perception / action / event 等） */
    private String stepType = "ai_call";

    /** 环节摘要（非 AI 环节存描述+耗时；AI 调用为空） */
    private String content;

    /** 项目 ID（Router 在 resolve 后回填） */
    private Long projectId;

    /** API 配置 ID（结果/用量回填） */
    private Long providerId;

    /** 实际模型名 */
    private String model;

    /** 系统提示词全文（拼接） */
    private String systemPrompt;

    /** 用户消息全文（拼接） */
    private String userPrompt;

    /** 模型输出全文 */
    private String response;

    /** 调用状态：success / failed / degraded */
    private String status = "success";

    /** 失败原因（截断） */
    private String errorMessage;

    /** 重试次数（预留，当前 Provider 内部重试不暴露，恒 0） */
    private int retryCount = 0;

    /** 输入 token */
    private int tokenIn = 0;

    /** 输出 token */
    private int tokenOut = 0;

    /** 缓存命中 token */
    private int cacheHit = 0;

    /** 缓存未命中 token */
    private int cacheMiss = 0;

    /** 是否确实发起了 Provider 调用（未调用=空 trace，提交时跳过，避免噪音记录） */
    private boolean invoked = false;

    /** 是否由 AiCallContext 创建（块尾统一提交） */
    private boolean contextOwned = false;

    /** 降级标记（AI 失败后调用方做了程序化兜底） */
    private boolean degraded = false;

    /** 是否已提交（防双提交） */
    private boolean submitted = false;

    /**
     * 是否已提交「长跑测试用量明细」（防双写 actor_test_usage_log）。
     * <p>与 {@link #submitted} 分开的原因：测试明细的开关独立于调试埋点
     * 埋点关闭时 {@code submitted} 不会被置位，因此需要本标志单独保证「一次调用最多一条测量记录」。
     */
    private boolean usageRecorded = false;

    /** 显式耗时（非 AI 环节 recordStep 设置；null=用 startMs 差值） */
    private Integer fixedDurationMs;

    // ===== AI 调用详情 JSON 素材 =====

    /** 原始请求体 JSON 字符串（Provider 发送前截取，OpenAI 兼容协议完整请求体；可空） */
    private String inputRaw;

    /** 原始响应体 JSON 字符串（Provider 解析前截取；流式=收集的全部 data 行组成的 JSON 数组；可空） */
    private String outputRaw;

    /** 供应商 Base URL（Router resolve 配置后回填） */
    private String baseUrl;

    /** 是否流式（SSE）调用（Router 按入口标记） */
    private boolean stream;

    /** 采样温度（fillMessages 从 AiChatRequest 捕获；未指定=null） */
    private Double temperature;

    /** 最大输出 token（fillMessages 从 AiChatRequest 捕获；未指定=null） */
    private Integer maxTokens;

    /** 是否请求 JSON 结构化输出（fillMessages 从 AiChatRequest 捕获） */
    private boolean jsonMode;

    /** 结构化消息序列 [{role, content}]（fillMessages 捕获，保留 system/user/assistant 全序列；embedding 无 messages 时为空） */
    private List<Map<String, String>> messages = new ArrayList<>();

    /** 输入摘要覆盖（embedding 等场景 Router 预设，格式随场景；null=按 system/user 自动生成） */
    private String inputSummaryOverride;

    /** 备注（预留，默认空串） */
    private String remark = "";

    /** 语义缓存命中标记 */
    private boolean semanticCacheHit = false;

    // ===== N 轨道：决策模型 判定调用参数快照 =====

    /**
     * 决策模型 判定调用参数快照（"states=N, questions=N, paths=N"；非 决策模型 调用为 null；
     * N 轨道「调度记录完整」验收项：paramsSnapshot 写入 detail_json.request_params）
     */
    private String decisionParams;

    // ===== Prompt 工程 + trace 数据流补全 =====

    /**
     * 感知组装数据源：本次调用读取了哪些帧/记忆 memoryId/实体/刺激
     * ——异常输出（角色明显 OOC）可回溯完整数据链路；AiCallContext.addDataSource 累积，可空
     */
    private java.util.List<String> dataSources;

    /** Prompt 模板版本号 */
    private Integer promptVersion;

    /** 格式合规标记 */
    private boolean contractCompliant = true;

    AiCallTrace(String scene, Long characterId) {
        this.scene = scene;
        this.characterId = characterId;
    }

    // ===== 只读访问（供 Controller 直出/测试断言）=====

    public String getScene() { return scene; }
    public Long getCharacterId() { return characterId; }
    public String getTraceId() { return traceId; }
    public int getAttempt() { return attempt; }
    public String getStepType() { return stepType; }
    public String getContent() { return content; }
    public Long getProjectId() { return projectId; }
    public Long getProviderId() { return providerId; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public String getResponse() { return response; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public int getTokenIn() { return tokenIn; }
    public int getTokenOut() { return tokenOut; }
    public int getCacheHit() { return cacheHit; }
    public int getCacheMiss() { return cacheMiss; }
    public long getDurationMs() {
        return fixedDurationMs != null ? fixedDurationMs : System.currentTimeMillis() - startMs;
    }
    /** 调用起始时刻 */
    public long getStartMs() { return startMs; }
    /** 供应商地址（测试用量明细 actor_test_usage_log 用于区分官方/内网网关） */
    public String getBaseUrl() { return baseUrl; }
    public boolean isInvoked() { return invoked; }
    public boolean isContextOwned() { return contextOwned; }
    public boolean isDegraded() { return degraded; }
    public boolean isSubmitted() { return submitted; }
    /** 是否已提交长跑测试用量明细（包内使用：AiCallTraceService 防双写） */
    boolean isUsageRecorded() { return usageRecorded; }
    public boolean isSemanticCacheHit() { return semanticCacheHit; }
    public java.util.List<String> getDataSources() { return dataSources; }
    public Integer getPromptVersion() { return promptVersion; }
    public boolean isContractCompliant() { return contractCompliant; }

    // ===== 包内修改（Service/Router 使用）=====

    void setContextOwned(boolean contextOwned) { this.contextOwned = contextOwned; }
    void setProjectId(Long projectId) { this.projectId = projectId; }
    void setSubmitted(boolean submitted) { this.submitted = submitted; }
    /** 置位「已提交测试用量明细」（包内使用；一次调用最多写一条） */
    void setUsageRecorded(boolean usageRecorded) { this.usageRecorded = usageRecorded; }
    void setDegraded(boolean degraded) { this.degraded = degraded; }
    void markInvoked() { this.invoked = true; }

    /** 设置决策链路 trace_id（B5：AiCallContext.withTrace/run 自动设置，包内） */
    void setTraceId(String traceId) { this.traceId = traceId; }

    /** 设置重试尝试序号（B5 -4：AiCallContext.run(attempt) 传入） */
    void setAttempt(int attempt) { this.attempt = Math.max(1, attempt); }

    /** 设置环节类型（B5：非 AI 环节由 recordStep 设置） */
    void setStepType(String stepType) {
        this.stepType = stepType == null || stepType.isBlank() ? "ai_call" : stepType;
    }

    /** 设置非 AI 环节摘要（recordStep 使用） */
    void setContent(String content) { this.content = content; }

    /** 设置环节耗时（recordStep 使用，非 AI 环节不依赖 startMs 差值） */
    void setDurationMs(int durationMs) { this.fixedDurationMs = Math.max(0, durationMs); }

    // ===== AI 调用详情 JSON 素材 setter（；Router/Provider 埋点填充，均公开供 ai 包调用） =====

    /** 回填供应商 Base URL（Router resolve 配置后调用） */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /** 标记是否流式（SSE）调用（Router 按入口标记：chatCompletion=false / chatCompletionStream=true / embedding=false） */
    public void setStream(boolean stream) { this.stream = stream; }

    /** 回填原始请求体 JSON（Provider 发送前截取，经 Router 的 raw sink 回调写入） */
    public void setInputRaw(String inputRaw) { this.inputRaw = inputRaw; }

    /** 回填原始响应体 JSON（Provider 解析前截取；流式=收集的全部 data 行组成的 JSON 数组；经 Router 的 raw sink 回调写入） */
    public void setOutputRaw(String outputRaw) { this.outputRaw = outputRaw; }

    /** 预设输入摘要覆盖（embedding 等非标准消息场景由 Router 设置；null=toDetailJson 按 system/user 自动生成） */
    public void setInputSummary(String inputSummaryOverride) { this.inputSummaryOverride = inputSummaryOverride; }

    /** 设置备注（预留；null 归一为空串） */
    public void setRemark(String remark) { this.remark = remark == null ? "" : remark; }

    /** 标记语义缓存命中 */
    public void setSemanticCacheHit(boolean semanticCacheHit) { this.semanticCacheHit = semanticCacheHit; }

    /** 回填 决策模型 判定调用参数快照。 */
    public void setDecisionParams(String decisionParams) { this.decisionParams = decisionParams; }

    // ===== Prompt 工程 + trace 数据流补全 =====

    /** 一条感知组装数据源。 */
    public void addDataSource(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        if (this.dataSources == null) {
            this.dataSources = new ArrayList<>();
        }
        if (this.dataSources.size() >= 20) {
            return; // 上限保护（防撑爆 detail_json）
        }
        this.dataSources.add(source);
    }

    /** 回填 Prompt 模板版本号（：PromptSpecRegistry.versionOf(scene)，网关/解析器埋点）。 */
    public void setPromptVersion(Integer promptVersion) { this.promptVersion = promptVersion; }

    /** 标记输出契约合规/不合规。 */
    public void setContractCompliant(boolean contractCompliant) { this.contractCompliant = contractCompliant; }

    /** 回填 API 配置 ID（Router 流式路径使用；公开供 ai 包埋点调用） */
    public void setProviderId(Long providerId) { this.providerId = providerId; }

    /** 回填模型名（Router 流式路径使用；公开供 ai 包埋点调用） */
    public void setModel(String model) { this.model = model; }

    /**
     * 从请求提取 system/user 消息全文（多个同角色消息以分隔符拼接）。
     * <p>同步捕获请求参数（temperature/maxTokens/jsonMode）与结构化消息序列
     * （[{role, content}]，保留全序列）——detail_json 的 request_params 与 input.messages 素材。</p>
     *
     * @param request AI 对话请求
     */
    public void fillMessages(AiChatRequest request) {
        if (request == null) {
            return;
        }
        this.temperature = request.temperature();
        this.maxTokens = request.maxTokens();
        this.jsonMode = request.jsonMode();
        List<Map<String, String>> msgs = new ArrayList<>();
        if (request.messages() != null) {
            for (AiChatRequest.ChatMessage m : request.messages()) {
                if (m == null) {
                    continue;
                }
                Map<String, String> mm = new LinkedHashMap<>();
                mm.put("role", m.role() == null ? "" : m.role());
                mm.put("content", m.content() == null ? "" : m.content());
                msgs.add(mm);
            }
        }
        this.messages = msgs;
        StringBuilder sys = new StringBuilder();
        StringBuilder usr = new StringBuilder();
        for (AiChatRequest.ChatMessage m : request.messages()) {
            if (m == null || m.content() == null) {
                continue;
            }
            String role = m.role();
            if ("system".equals(role)) {
                if (sys.length() > 0) sys.append("\n---\n");
                sys.append(m.content());
            } else if ("user".equals(role)) {
                if (usr.length() > 0) usr.append("\n---\n");
                usr.append(m.content());
            }
        }
        this.systemPrompt = sys.toString();
        this.userPrompt = usr.toString();
    }

    /**
     * 标记成功：回填模型/输出/token 用量（数值可空→0）。
     *
     * @param responseText 模型输出全文
     *
     * @param modelName 实际模型名
     *
     * @param in 输入 token（可空）
     *
     * @param out 输出 token（可空）
     *
     * @param hit 缓存命中 token（可空）
     *
     * @param miss 缓存未命中 token（可空）
     *
     * @param providerId 命中的 API 配置主键（可空）
     */
    /**
     * 标记成功：回填模型/输出/token 用量（数值可空→0）。公开供 ai 包 Router 埋点调用。
     *
     * @param responseText 模型输出全文
     *
     * @param modelName 实际模型名
     *
     * @param in 输入 token（可空）
     *
     * @param out 输出 token（可空）
     *
     * @param hit 缓存命中 token（可空）
     *
     * @param miss 缓存未命中 token（可空）
     *
     * @param providerId 命中的 API 配置主键（可空）
     */
    public void markSuccess(String responseText, String modelName, Integer in, Integer out,
                            Integer hit, Integer miss, Long providerId) {
        this.status = "success";
        this.response = responseText;
        this.model = modelName;
        this.tokenIn = nz(in);
        this.tokenOut = nz(out);
        this.cacheHit = nz(hit);
        this.cacheMiss = nz(miss);
        this.providerId = providerId;
    }

    /**
     * 标记失败：回填错误信息与状态（degraded 由调用方在 catch 后 markDegraded 覆盖）。
     * 公开供 ai 包 Router 埋点调用。
     *
     * @param e 异常（提取消息文本，超长截断）
     */
    public void markFailure(Exception e) {
        this.status = "failed";
        String msg = e == null ? null : e.getMessage();
        if (msg == null) msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        // 失败原因截断（避免异常堆栈撑爆列）
        this.errorMessage = msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }

    /**
     * 转实体（带 Prompt/输出单段截断，防超长内容撑爆库）。
     * <p>一并组装 AI 调用详情 JSON（{@link #toDetailJson(int)}）写入 detail_json 列。</p>
     *
     * @param maxChars 单段截断上限（<=0 表示不截断）
     *
     * @return 待落库的实体
     */
    ActorAiCallLog toEntity(int maxChars) {
        ActorAiCallLog e = new ActorAiCallLog();
        e.setProjectId(projectId);
        e.setCharacterId(characterId);
        e.setTraceId(traceId);
        e.setAttempt(attempt);
        e.setStepType(stepType);
        e.setContent(content);
        e.setScene(scene);
        e.setProviderId(providerId);
        e.setModel(model);
        e.setSystemPrompt(truncate(systemPrompt, maxChars));
        e.setUserPrompt(truncate(userPrompt, maxChars));
        e.setResponse(truncate(response, maxChars));
        e.setStatus(degraded ? "degraded" : status);
        e.setErrorMessage(errorMessage);
        e.setRetryCount(retryCount);
        e.setTokenIn(tokenIn);
        e.setTokenOut(tokenOut);
        e.setCacheHit(cacheHit);
        e.setCacheMiss(cacheMiss);
        e.setDurationMs((int) Math.min(Integer.MAX_VALUE, getDurationMs()));
        e.setDetailJson(toDetailJson(maxChars));
        return e;
    }

    // ===== AI 调用详情 JSON 组装 =====

    /** Jackson 序列化器（tools.jackson，Jackson 3；与项目 ai 包一致） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** detail_json 时间戳时区（与 application.yml jackson.time-zone 一致，示例格式 +08:00） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 输入/输出摘要截断上限（设计文档 §4/§5：摘要 ~200 字） */
    private static final int SUMMARY_MAX = 200;

    /**
     * 组装整条 AI 调用详情 JSON（设计文档 §1~§8 字段，命名英文 snake_case）。
     * <p>要点：<ul>
     * <li>时间轴：time/end_time 为 ISO 8601（+08:00），duration_ms 为耗时；</li>
     * <li>input.raw/output.raw：原始请求/响应体——合法 JSON 且未超长时以对象/数组嵌套存储，
     * 超长或非法时按字符串截断（保证 detail_json 始终是合法 JSON）；</li>
     * <li>input.messages：保留 system/user/assistant 全序列（embedding 场景为空数组）；</li>
     * <li>output.text：模型输出全文（embedding 无文本输出=null）；output.summary 截断至 ~200 字；</li>
     * <li>context：本期恒 null（RAG/记忆引用预留，六组件决策链路接入后再注入）；</li>
     * <li>status：degraded 标记优先（与 toEntity 口径一致）。</li>
     * </ul></p>
     *
     * @param maxChars raw/text 单段截断上限（<=0 不截断）
     *
     * @return 详情 JSON 字符串（序列化失败返回 null，不阻断落库）
     */
    String toDetailJson(int maxChars) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            long duration = getDurationMs();
            // ===== 时间轴 =====
            root.put("time", formatInstant(startMs));
            root.put("end_time", formatInstant(startMs + duration));
            root.put("duration_ms", duration);
            // ===== 业务上下文 =====
            putNumber(root, "project_id", projectId);
            putNumber(root, "character_id", characterId);
            root.put("scene", scene == null ? "" : scene);
            putText(root, "trace_id", traceId);
            root.put("attempt", attempt);
            root.put("step_type", stepType == null ? "ai_call" : stepType);
            // ===== 供应商与模型 =====
            ObjectNode provider = root.putObject("provider");
            putNumber(provider, "provider_id", providerId);
            putText(provider, "base_url", baseUrl);
            putText(provider, "model", model);
            // ===== 请求参数 =====
            ObjectNode rp = root.putObject("request_params");
            putNumber(rp, "temperature", temperature);
            putNumber(rp, "max_tokens", maxTokens);
            rp.put("stream", stream);
            rp.put("json_mode", jsonMode);
            // N 轨道：决策模型 判定调用参数快照（states/questions/paths）
            if (decisionParams != null && !decisionParams.isBlank()) {
                rp.put("params_snapshot", decisionParams);
            }
            // ===== 输入 =====
            ObjectNode in = root.putObject("input");
            putNode(in, "raw", rawAsNode(inputRaw, maxChars));
            ArrayNode msgs = in.putArray("messages");
            boolean embedding = "embedding".equals(scene);
            if (!embedding && messages != null) {
                for (Map<String, String> m : messages) {
                    ObjectNode mn = msgs.addObject();
                    mn.put("role", m.getOrDefault("role", ""));
                    mn.put("content", m.getOrDefault("content", ""));
                }
            }
            in.put("summary", buildInputSummary());
            // ===== 输出 =====
            ObjectNode out = root.putObject("output");
            putNode(out, "raw", rawAsNode(outputRaw, maxChars));
            putText(out, "text", embedding ? null : response);
            putText(out, "summary", buildOutputSummary(embedding));
            // ===== 用量（不含成本） =====
            ObjectNode usage = root.putObject("usage");
            usage.put("token_in", tokenIn);
            usage.put("token_out", tokenOut);
            usage.put("cache_hit", cacheHit);
            usage.put("cache_miss", cacheMiss);
            // ===== 状态与错误 =====
            String effectiveStatus = degraded ? "degraded" : status;
            root.put("status", effectiveStatus == null ? "success" : effectiveStatus);
            if (errorMessage != null && !errorMessage.isBlank()) {
                ObjectNode err = root.putObject("error");
                putText(err, "code", null);   // 本期无结构化错误码（HTTP 状态码未上抛），预留
                err.put("message", errorMessage);
            } else {
                root.putNull("error");
            }
            root.put("retry_count", retryCount);
            // ===== RAG/记忆上下文 =====
            ObjectNode ctx = root.putObject("context");
            if (dataSources != null && !dataSources.isEmpty()) {
                ArrayNode ds = ctx.putArray("data_sources");
                dataSources.forEach(ds::add);
            } else {
                ctx.putNull("data_sources");
            }
            if (promptVersion != null) {
                ctx.put("prompt_version", promptVersion);
            } else {
                ctx.putNull("prompt_version");
            }
            // ===== 语义缓存命中标记 =====
            root.put("semantic_cache_hit", semanticCacheHit);
            root.put("remark", remark);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 详情 JSON 组装失败仅影响该列，不阻断落库主流程
            return null;
        }
    }

    /**
     * 组装 input.raw / output.raw 的 JSON 节点：
     * 原始串为合法 JSON 且长度未超上限 → 以对象/数组节点嵌套存储（保真）；
     * 超长或非法 → 按字符串截断存储（保证 detail_json 整体仍是合法 JSON）。
     *
     * @param raw 原始 JSON 字符串（可空）
     *
     * @param maxChars 单段截断上限（<=0 不截断）
     *
     * @return JSON 节点（null 表示无原始体）
     */
    private static JsonNode rawAsNode(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (maxChars > 0 && raw.length() > maxChars) {
                return MAPPER.getNodeFactory().textNode(truncate(raw, maxChars));
            }
            return node;
        } catch (Exception e) {
            // 非 JSON（异常路径等）：按字符串截断保存
            return MAPPER.getNodeFactory().textNode(truncate(raw, maxChars));
        }
    }

    /**
     * 组装输入摘要（~200 字）：Router 预设覆盖（如 embedding 的「供应商/模型/输入段数」，符合设计文档 §10）
     * 优先；未预设时按「system: 前段 / user: 前段」自动生成。
     *
     * @return 输入摘要
     */
    private String buildInputSummary() {
        if (inputSummaryOverride != null && !inputSummaryOverride.isBlank()) {
            return truncate(inputSummaryOverride, SUMMARY_MAX);
        }
        String sys = truncate(systemPrompt, 90);
        String usr = truncate(userPrompt, 110);
        String summary = "system: " + (sys == null ? "" : sys) + " / user: " + (usr == null ? "" : usr);
        return truncate(summary, SUMMARY_MAX);
    }

    /**
     * 组装输出摘要（~200 字）：embedding 存「已生成 N 条向量（维度 D）」；失败无输出存占位文案。
     *
     * @param embedding 是否 embedding 场景
     *
     * @return 输出摘要
     */
    private String buildOutputSummary(boolean embedding) {
        if (embedding) {
            return response == null ? null : truncate(response, SUMMARY_MAX);
        }
        if (response == null || response.isBlank()) {
            String effectiveStatus = degraded ? "degraded" : status;
            return "success".equals(effectiveStatus) ? null : "（调用失败，无输出）";
        }
        return truncate(response, SUMMARY_MAX);
    }

    /**
     * 毫秒时间戳 → ISO 8601（Asia/Shanghai，如 T14:32:05.123+08:00）。
     *
     * @param epochMs 毫秒时间戳
     *
     * @return ISO 偏移时间串
     */
    private static String formatInstant(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 写 Long 字段（null → JSON null） */
    private static void putNumber(ObjectNode n, String name, Long v) {
        if (v == null) n.putNull(name); else n.put(name, v.longValue());
    }

    /** 写 Integer 字段（null → JSON null） */
    private static void putNumber(ObjectNode n, String name, Integer v) {
        if (v == null) n.putNull(name); else n.put(name, v.intValue());
    }

    /** 写 Double 字段（null → JSON null） */
    private static void putNumber(ObjectNode n, String name, Double v) {
        if (v == null) n.putNull(name); else n.put(name, v.doubleValue());
    }

    /** 写 String 字段（null → JSON null） */
    private static void putText(ObjectNode n, String name, String v) {
        if (v == null) n.putNull(name); else n.put(name, v);
    }

    /** 写 JsonNode 字段（null → JSON null，避免 ObjectNode.set 对 null 抛 NPE） */
    private static void putNode(ObjectNode n, String name, JsonNode v) {
        if (v == null) n.putNull(name); else n.set(name, v);
    }

    
    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 单段截断（按字符数，避免截断代理对导致乱码——以 codePoint 边界处理） */
    private static String truncate(String s, int maxChars) {
        if (s == null || s.isEmpty() || maxChars <= 0 || s.length() <= maxChars) {
            return s;
        }
        // 按码点安全截断：先按 char 截，若末尾落在代理对则退一格
        int end = s.offsetByCodePoints(0, maxChars);
        return s.substring(0, end) + "\n…[已截断，超长 " + (s.length() - end) + " 字符]";
    }
}
