package com.holzyn.actor.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议 Provider（非流式）。
 * <p>职责：按 OpenAI Chat Completions 协议与各类兼容服务（DeepSeek / OpenAI / 通义 / 豆包等）
 * 通信，提供「连通性测试」（轻量校验 Key 与 Base URL）与「对话补全」（chatCompletion）能力。
 * 流式（SSE）调用留给对话子任务，本类仅实现非流式路径。</p>
 * <p>错误处理：所有外部调用失败均包装为带中文提示的 AiCallException，
 * 由全局异常处理器统一返回，不向调用方泄漏堆栈。</p>
 * <p>所属模块：service/ai（AI 接入层）</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleProvider {

    /**
     * 连接建立超时（TCP 握手 / TLS，与供应商响应速度无关）。
     * <p>说明：此前统一 10 秒超时导致「模型生成本身较慢」时被误报为超时
     * （如火山方舟 Coding Plan 端点、长上下文非流式调用），此处按场景分级：</p>
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 连通性测试请求超时（GET /models 与回退最小对话，15 秒足够判断连通性） */
    private static final Duration TEST_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** 非流式对话补全请求超时（120 秒）：模型生成可能较慢，过短会误报「AI 请求超时」 */
    private static final Duration CHAT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** 流式（SSE）首响应头超时（30 秒）：首包到达后按行持续读取，不再受总超时限制 */
    private static final Duration STREAM_HEADER_TIMEOUT = Duration.ofSeconds(30);

    /** 连通性测试的最小请求最大 token（避免消耗额度） */
    private static final int TEST_MAX_TOKENS = 1;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数：初始化共享 HttpClient 与 Jackson 解析器。
     *
     * @param objectMapper Spring 注入的 Jackson ObjectMapper
     */
    public OpenAiCompatibleProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 跟随重定向：部分网关（如 vLLM/OneAPI）的 /models 会 302
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 连通性测试：优先调用 {@code GET {base_url}/models}（零 token 消耗、最轻量），
     * 失败（认证错误/不支持 /models）时回退最小 chat completion；两种方式均失败则返回失败结果。
     *
     * @param baseUrl API Base URL（如 https://api.deepseek.com/v1）
     * @param apiKey  明文 API Key
     * @param model   默认模型名（用于 chat 回退路径）
     * @return 结果 Map：connected(boolean) / method(models|chat) / status / message / latencyMs
     */
    public Map<String, Object> testConnection(String baseUrl, String apiKey, String model) {
        String base = normalizeBaseUrl(baseUrl);
        Map<String, Object> result = new HashMap<>();

        // 路径一：GET /models（多数兼容服务支持，最轻量）
        long start = System.currentTimeMillis();
        HttpResponse<String> modelsResp = httpRequest("GET", base + "/models", apiKey, null, TEST_REQUEST_TIMEOUT);
        long latency = System.currentTimeMillis() - start;
        if (isSuccess(modelsResp)) {
            result.put("connected", true);
            result.put("method", "models");
            result.put("status", modelsResp.statusCode());
            result.put("latencyMs", latency);
            result.put("message", "连接成功（已通过 /models 校验）");
            return result;
        }

        // 路径二：回退最小 chat completion（max_tokens=1，消耗极低）
        String fallbackModel = (model == null || model.isBlank()) ? "default" : model;
        HttpResponse<String> chatResp = postChatCompletion(base, apiKey, fallbackModel, "ping", TEST_MAX_TOKENS);
        long chatLatency = System.currentTimeMillis() - start;
        if (isSuccess(chatResp)) {
            result.put("connected", true);
            result.put("method", "chat");
            result.put("status", chatResp.statusCode());
            result.put("latencyMs", chatLatency);
            result.put("message", "连接成功（已通过最小对话校验）");
            return result;
        }

        // 两种方式均失败：解析供应商返回的错误信息（如 401 认证失败 / 404 地址错误）
        result.put("connected", false);
        result.put("method", "chat");
        result.put("status", chatResp.statusCode());
        result.put("latencyMs", chatLatency);
        result.put("message", buildErrorMessage(chatResp));
        return result;
    }

    /**
     * 非流式对话补全调用。
     *
     * @param config  已解密的供应商运行时配置（Base URL + Key + 默认模型）
     * @param request 对话请求（模型 / 消息 / 采样参数）
     * @return 解析后的回复内容与用量统计
     */
    public AiChatResult chatCompletion(ProviderConfig config, AiChatRequest request) {
        String base = normalizeBaseUrl(config.baseUrl());
        String model = (request.model() == null || request.model().isBlank())
                ? config.model() : request.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未指定模型：请在该 API 配置中填写默认模型名");
        }
        HttpResponse<String> resp = postChatCompletion(base, config.apiKey(), model,
                request.messages() != null ? request.messages() : java.util.List.of(),
                request.temperature(), request.maxTokens(), request.jsonMode());
        if (!isSuccess(resp)) {
            throw new AiCallException("AI 调用失败：" + buildErrorMessage(resp));
        }
        return parseChatResult(resp, model, config.id());
    }

    /**
     * 发起 chat/completions 请求（测试用重载：单条 user 消息 + 固定 max_tokens）。
     *
     * @param base      规范化后的 Base URL
     * @param apiKey    明文 Key
     * @param model     模型名
     * @param userText  用户消息内容
     * @param maxTokens 最大输出 token
     * @return HTTP 响应
     */
    private HttpResponse<String> postChatCompletion(String base, String apiKey, String model,
                                                    String userText, Integer maxTokens) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", userText);
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        return httpRequest("POST", base + "/chat/completions", apiKey, body.toString(), TEST_REQUEST_TIMEOUT);
    }

    /**
     * 发起 chat/completions 请求（对话用重载：完整消息序列 + 采样参数）。
     *
     * @param base        规范化后的 Base URL
     * @param apiKey      明文 Key
     * @param model       模型名
     * @param messages    消息序列
     * @param temperature 温度（可空）
     * @param maxTokens   最大 token（可空）
     * @return HTTP 响应
     */
    private HttpResponse<String> postChatCompletion(String base, String apiKey, String model,
                                                    java.util.List<AiChatRequest.ChatMessage> messages,
                                                    Double temperature, Integer maxTokens, boolean jsonMode) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        for (AiChatRequest.ChatMessage m : messages) {
            msgs.addObject().put("role", m.role()).put("content", m.content());
        }
        if (temperature != null) body.put("temperature", temperature);
        if (jsonMode) {
            // 结构化输出：请求 json_object 模式，降低非法 JSON 概率；部分供应商不支持时由上层文本解析兜底
            body.putObject("response_format").put("type", "json_object");
        }
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        return httpRequest("POST", base + "/chat/completions", apiKey, body.toString(), CHAT_REQUEST_TIMEOUT);
    }

    /**
     * 通用 HTTP 请求执行器：设置认证头与 JSON 头，按场景超时，返回字符串响应。
     *
     * @param method         HTTP 方法（GET/POST）
     * @param url            请求地址
     * @param apiKey         明文 Key（Bearer 认证）
     * @param jsonBody       请求体（GET 传 null）
     * @param requestTimeout 请求超时（连通性测试 15s / 非流式对话 120s / 流式首包 30s）
     * @return HTTP 响应（含状态码与响应体）
     */
    private HttpResponse<String> httpRequest(String method, String url, String apiKey, String jsonBody, Duration requestTimeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey);
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.GET();
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            // 超时：返回 599 模拟状态，由调用方给出友好提示（记录超时秒数便于定位慢在哪一步）
            log.warn("AI 请求超时（{} 秒）: {} -> {}", requestTimeout.toSeconds(), method, url);
            return new SimpleResponse(599, "请求超时（" + requestTimeout.toSeconds() + " 秒）");
        } catch (Exception e) {
            log.warn("AI 请求异常: {} -> {} : {}", method, url, e.getMessage());
            return new SimpleResponse(-1, "网络错误：" + e.getMessage());
        }
    }

    /**
     * 解析 chat/completions 成功响应：提取回复内容与 token 用量。
     *
     * @param resp   成功响应（2xx）
     * @param model  实际模型名
     * @param configId 配置主键（写入结果供用量日志关联）
     * @return 对话补全结果
     */
    private AiChatResult parseChatResult(HttpResponse<String> resp, String model, Long configId) {
        try {
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            Integer prompt = nullableInt(usage, "prompt_tokens");
            Integer completion = nullableInt(usage, "completion_tokens");
            Integer total = nullableInt(usage, "total_tokens");
            // 上下文缓存命中/未命中 token：DeepSeek 返回 prompt_cache_hit_tokens / prompt_cache_miss_tokens；
            // OpenAI 风格接口返回 prompt_tokens_details.cached_tokens，这里做双兼容解析
            Integer hit = nullableInt(usage, "prompt_cache_hit_tokens");
            if (hit == null) {
                hit = nullableInt(usage.path("prompt_tokens_details"), "cached_tokens");
            }
            Integer miss = nullableInt(usage, "prompt_cache_miss_tokens");
            return new AiChatResult(content, model, prompt, completion, total, hit, miss, configId);
        } catch (Exception e) {
            throw new AiCallException("AI 响应解析失败", e);
        }
    }

    /**
     * 安全读取 JSON 数值字段（节点缺失/非数值/为 null 时返回 null）。
     * <p>2026-08-17 修复：原 `cond ? node.asInt() : null` 在「int 与 Integer 混用」的嵌套条件表达式中，
     * 若引用分支为 null，javac 会按 JLS 数值提升对引用分支自动拆箱（调用 Integer.intValue()）→ NPE。
     * 当供应商响应同时缺省 prompt_cache_hit_tokens 与 prompt_tokens_details.cached_tokens 时必然触发。
     * 统一改为「先判存在再取值」，彻底规避拆箱陷阱。</p>
     *
     * @param node  父节点（可为 missing/null）
     * @param field 数值字段名
     * @return 数值或 null
     */
    private static Integer nullableInt(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isMissingNode() || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asInt();
    }

    /**
     * 从失败响应中提取供应商返回的中文友好错误信息。
     *
     * @param resp 非 2xx 响应
     * @return 错误描述（优先取响应体 error.message）
     */
    private String buildErrorMessage(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status == 401 || status == 403) return "认证失败（HTTP " + status + "）：API Key 无效或无权限";
        if (status == 404) return "接口不存在（HTTP 404）：请检查 Base URL 是否正确";
        if (status == 429) return "请求过于频繁（HTTP 429）：触发限流";
        if (status == 599) {
            // 本地兜底超时（非供应商返回）：提示排查方向——网络 / Base URL / 模型名（部分专用端点只认套餐内模型）
            return "请求超时：模型服务响应过慢或网络不可达。请检查网络 / Base URL / 模型名（专用端点如火山 Coding Plan 需使用套餐内模型名，如 ark-code-latest）";
        }
        if (status < 0) return resp.body();
        // 尝试解析供应商 error 字段
        try {
            JsonNode root = objectMapper.readTree(resp.body());
            String err = root.path("error").path("message").asText(null);
            if (err != null && !err.isBlank()) return "供应商返回（HTTP " + status + "）：" + err;
        } catch (Exception ignored) {
            // 忽略解析失败，走默认提示
        }
        return "请求失败（HTTP " + status + "）";
    }

    /**
     * 判断 HTTP 状态码是否为成功（2xx）。
     *
     * @param resp HTTP 响应
     * @return true 表示成功
     */
    private boolean isSuccess(HttpResponse<?> resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }

    /**
     * 规范化 Base URL：去除尾部斜杠，便于拼接待调用的 /models、/chat/completions 路径。
     *
     * @param baseUrl 原始 Base URL
     * @return 规范化结果
     */
    private String normalizeBaseUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new AiCallException("Base URL 不能为空");
        }
        return base;
    }

    /**
     * 简易 HTTP 响应实现（用于超时/网络错误的本地兜底响应）。
     * <p>仅用于错误路径，避免依赖真实 HttpResponse 构造。</p>
     */


    /**
     * 流式对话补全调用（SSE，P1-4 单聊核心）。
     * <p>协议：POST {base_url}/chat/completions，body 带 stream:true；
     * 通过 {@code data: {choices:[{delta:{content}}]}} 逐行解析增量 token，
     * 逐段回调 onToken（供 SSE 转发）；结束时通过 onUsage 回传 token 用量。</p>
     * <p>token 用量（2026-08-18 修复）：OpenAI 兼容协议流式响应<b>默认不返回 usage</b>，
     * 需在请求体带 {@code stream_options: {"include_usage": true}}，供应商（OpenAI/DeepSeek/
     * 火山方舟/通义等）才会在最后一块（choices 为空 + usage 对象）返回真实用量；
     * 对不识别该字段的网关（400 拒绝未知参数）自动去掉后重试一次；
     * 若供应商最终仍不返回 usage，则按消息内容与生成文本<b>本地估算兜底</b>，
     * 保证用量页/日志不再是 0/0（估算值仅为近似，真实 usage 优先）。</p>
     *
     * @param config  已解密的供应商运行时配置（Base URL + Key + 默认模型）
     * @param request 对话请求（模型/消息/采样参数）
     * @param onToken 每个增量 token 的回调（流式转发）
     * @param onUsage 结束时的用量回调（AiUsage：prompt/completion/cacheHit/cacheMiss token）
     */
    public void chatCompletionStream(ProviderConfig config, AiChatRequest request,
                                     Consumer<String> onToken, Consumer<AiUsage> onUsage) {
        String base = normalizeBaseUrl(config.baseUrl());
        String model = (request.model() == null || request.model().isBlank())
                ? config.model() : request.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未指定模型：请在该 API 配置中填写默认模型名");
        }
        // 请求体组装：流式 + 采样 + 结构化输出兼容 + stream_options 请求用量回传
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        ArrayNode msgs = body.putArray("messages");
        for (AiChatRequest.ChatMessage m : request.messages()) {
            msgs.addObject().put("role", m.role()).put("content", m.content());
        }
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        // 关键：OpenAI 兼容协议默认不在流式响应返回 usage，设置后最后一块才携带真实用量
        body.putObject("stream_options").put("include_usage", true);

        HttpRequest httpReq = buildStreamRequest(base, config.apiKey(), body);
        try {
            HttpResponse<Stream<String>> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofLines());
            // 兼容兜底：个别严格校验的 OpenAI 兼容网关不识别 stream_options（未知参数直接 400），
            // 去掉该字段重试一次（重试后拿不到 usage 由本地估算兜底，不影响对话本身）
            if (resp.statusCode() == 400) {
                resp.body().close(); // 关闭首个未消费的响应流，避免连接泄漏
                body.remove("stream_options");
                resp = httpClient.send(buildStreamRequest(base, config.apiKey(), body), HttpResponse.BodyHandlers.ofLines());
            }
            if (!isSuccess(resp)) {
                String firstLine = resp.body().findFirst().orElse("");
                throw new AiCallException("AI 调用失败：" + buildErrorMessage(new SimpleResponse(resp.statusCode(), firstLine)));
            }
            // 用量收集：流式响应的最后一块（stream_options 开启后）通常携带 usage 字段
            int[] usage = new int[4]; // 0=prompt 1=completion 2=cacheHit 3=cacheMiss
            // 生成文本聚合：用于本地估算兜底
            StringBuilder streamed = new StringBuilder();
            try (Stream<String> lines = resp.body()) {
                lines.forEach(line -> {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) return;
                    String data = trimmed.substring(5).trim();
                    if ("[DONE]".equals(data)) return;
                    try {
                        JsonNode root = objectMapper.readTree(data);
                        String delta = root.path("choices").path(0).path("delta").path("content").asText(null);
                        if (delta != null && !delta.isBlank()) {
                            streamed.append(delta);
                            onToken.accept(delta);
                        }
                        JsonNode usageNode = root.path("usage");
                        if (usageNode.isObject()) {
                            if (usageNode.path("prompt_tokens").isInt()) usage[0] = usageNode.path("prompt_tokens").asInt();
                            if (usageNode.path("completion_tokens").isInt()) usage[1] = usageNode.path("completion_tokens").asInt();
                            if (usageNode.path("prompt_cache_hit_tokens").isInt()) usage[2] = usageNode.path("prompt_cache_hit_tokens").asInt();
                            if (usageNode.path("prompt_cache_miss_tokens").isInt()) usage[3] = usageNode.path("prompt_cache_miss_tokens").asInt();
                        }
                    } catch (RuntimeException e) {
                        // 回调（onToken/onUsage）抛出的业务异常（如 SSE 连接已断开）：
                        // 原样向上抛，立即终止本次流式生成，避免继续接收 token 刷 WARN/白耗
                        throw e;
                    } catch (Exception e) {
                        log.warn("SSE 行解析失败，忽略: {}", data);
                    }
                });
            }
            // 本地估算兜底：供应商未返回真实 usage（不支持/被中断/未开启）时，
            // 按「请求消息 → prompt、生成文本 → completion」启发式估算，保证用量非 0。
            if (usage[0] <= 0 || usage[1] <= 0) {
                if (usage[0] <= 0) {
                    usage[0] = estimatePromptTokens(request.messages());
                }
                if (usage[1] <= 0) {
                    usage[1] = estimateTokens(streamed.toString());
                }
                if (usage[0] <= 0 && usage[1] <= 0) {
                    log.warn("[AI] 供应商未返回流式 usage，已用本地估算兜底（估算值近似）");
                }
            }
            onUsage.accept(new AiUsage(usage[0], usage[1], usage[2], usage[3]));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiCallException("AI 请求超时（首响应 " + STREAM_HEADER_TIMEOUT.toSeconds() + " 秒）：请检查网络或模型响应速度");
        } catch (Exception e) {
            // 友好提示：null 消息时回退到异常类名，并指导用户检查配置
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new AiCallException("AI 流式调用失败：请检查 API 地址、Key 与网络（" + detail + "）");
        }
    }

    /**
     * 构造流式补全 HTTP 请求（携带 Authorization / Content-Type / 超时）。
     *
     * @param base   规范化 Base URL
     * @param apiKey 供应商 API Key
     * @param body   请求体（ObjectNode）
     * @return HTTP 请求
     */
    private HttpRequest buildStreamRequest(String base, String apiKey, ObjectNode body) {
        return HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(STREAM_HEADER_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 本地 token 估算（兜底用，仅当供应商未返回真实 usage 时启用）。
     * <p>启发式：CJK 字符约 1 token ≈ 1.8 字符（按 0.55/字符），ASCII 字母数字约 1 token ≈ 3.5 字符
     * （按 0.28/字符）另加每个连续单词 1 token 开销；与常见中文 tokenizer 同量级，供用量展示近似参考。</p>
     *
     * @param text 文本（可空）
     * @return 估算 token 数（非空文本至少 1）
     */
    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int ascii = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk++;
                if (inWord) {
                    words++;
                    inWord = false;
                }
            } else if (Character.isLetterOrDigit(ch)) {
                ascii++;
                inWord = true;
            } else if (inWord) {
                words++;
                inWord = false;
            }
        }
        if (inWord) {
            words++;
        }
        int tokens = (int) Math.ceil(cjk * 0.55) + (int) Math.ceil(ascii * 0.28) + words;
        return Math.max(1, tokens);
    }

    /**
     * 估算整组消息的 prompt token（含每条消息的系统开销，对齐 OpenAI 约 4 token/条惯例）。
     *
     * @param messages 消息序列（可空）
     * @return 估算 prompt token 数
     */
    static int estimatePromptTokens(List<AiChatRequest.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 1;
        }
        int total = 0;
        for (AiChatRequest.ChatMessage m : messages) {
            total += 4 + estimateTokens(m.content());
        }
        return Math.max(1, total);
    }

    /**
     * 是否 CJK 字符（汉字/日文假名/韩文/中文标点等，覆盖中文全角标点）。
     *
     * @param ch 字符
     * @return true=CJK
     */
    private static boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO;
    }
    private static class SimpleResponse implements HttpResponse<String> {
        private final int status;
        private final String body;

        SimpleResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(new java.util.HashMap<>(), (a, b) -> true); }
        @Override public String body() { return body; }
        @Override public java.net.URI uri() { return null; }
        @Override public HttpClient.Version version() { return null; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
    }
}
