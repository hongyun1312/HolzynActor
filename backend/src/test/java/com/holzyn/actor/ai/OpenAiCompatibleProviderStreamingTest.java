package com.holzyn.actor.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiCompatibleProvider 流式 token 用量集成测试（mock OpenAI 兼容服务器）。
 * <p>职责：端到端验证 2026-08-18 用量修复——① 请求体携带 stream_options.include_usage；
 * ② 供应商返回 usage 时精确解析（含缓存命中/未命中）；③ 供应商不返回 usage 时本地估算兜底非 0；
 * ④ 供应商 400 拒绝 stream_options 时自动去掉重试。</p>
 */
class OpenAiCompatibleProviderStreamingTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    /** mock 收到的最近一次请求体（验证 stream_options） */
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
    /** mock：是否拒绝携带 stream_options 的请求（返回 400） */
    private final AtomicReference<Boolean> rejectStreamOptions = new AtomicReference<>(false);
    /** mock：是否在流末返回 usage 块 */
    private final AtomicReference<Boolean> includeUsage = new AtomicReference<>(true);

    /** 启动 mock OpenAI 兼容 SSE 服务器（随机端口） */
    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(body);
            boolean hasStreamOptions = body.contains("stream_options");
            if (rejectStreamOptions.get() && hasStreamOptions) {
                // 模拟严格网关：未知参数 stream_options 直接 400
                byte[] err = "{\"error\":\"unknown parameter: stream_options\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            StringBuilder sse = new StringBuilder();
            sse.append("data: ").append("{\"id\":\"x\",\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n");
            if (includeUsage.get()) {
                sse.append("data: ").append("{\"id\":\"x\",\"choices\":[],\"usage\":{")
                        .append("\"prompt_tokens\":30,\"completion_tokens\":2,")
                        .append("\"prompt_cache_hit_tokens\":10,\"prompt_cache_miss_tokens\":20}}\n\n");
            }
            sse.append("data: [DONE]\n\n");
            byte[] out = sse.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 构造指向 mock 的 Provider 配置 */
    private ProviderConfig cfg() {
        return new ProviderConfig(1L, "http://127.0.0.1:" + port, "sk-test", "test-model", true);
    }

    /** 执行一次流式调用并收集 onUsage 结果 */
    private AiUsage run(OpenAiCompatibleProvider provider) {
        AtomicReference<AiUsage> got = new AtomicReference<>();
        StringBuilder text = new StringBuilder();
        provider.chatCompletionStream(cfg(),
                new AiChatRequest("test-model",
                        List.of(new AiChatRequest.ChatMessage("user", "hi")), 0.7, 100),
                text::append, got::set);
        return got.get();
    }

    /**
     * ① 请求体携带 stream_options.include_usage；② 供应商返回 usage 时精确解析（含缓存）。
     */
    @Test
    void streamSendsStreamOptionsAndParsesRealUsage() {
        includeUsage.set(true);
        AiUsage usage = run(new OpenAiCompatibleProvider(mapper));
        // 请求体必须带 stream_options.include_usage=true（用量回传的关键）
        assertTrue(lastRequestBody.get().contains("stream_options"), "请求体应携带 stream_options");
        assertTrue(lastRequestBody.get().contains("\"include_usage\":true"), "应设置 include_usage=true");
        // 供应商返回真实 usage → 精确解析
        assertNotNull(usage);
        assertEquals(30, usage.promptTokens(), "真实 prompt_tokens 应精确解析");
        assertEquals(2, usage.completionTokens(), "真实 completion_tokens 应精确解析");
        assertEquals(10, usage.cacheHitTokens(), "缓存命中 token 应解析");
        assertEquals(20, usage.cacheMissTokens(), "缓存未命中 token 应解析");
    }

    /**
     * ③ 供应商不返回 usage（不支持/未开启）→ 本地估算兜底，保证用量非 0。
     */
    @Test
    void streamEstimatesUsageWhenProviderOmits() {
        includeUsage.set(false);
        AiUsage usage = run(new OpenAiCompatibleProvider(mapper));
        assertNotNull(usage);
        assertTrue(usage.promptTokens() > 0, "无真实 usage 时 prompt 应本地估算（非 0）");
        assertTrue(usage.completionTokens() > 0, "无真实 usage 时 completion 应本地估算（非 0）");
    }

    /**
     * ④ 供应商 400 拒绝 stream_options → 自动去掉该字段重试一次，调用成功且用量为估算值。
     */
    @Test
    void streamRetriesWithoutStreamOptionsOn400() {
        rejectStreamOptions.set(true);
        includeUsage.set(false);
        AiUsage usage = run(new OpenAiCompatibleProvider(mapper));
        assertNotNull(usage, "400 后重试成功，不应抛异常");
        assertFalse(lastRequestBody.get().contains("stream_options"),
                "重试请求应去掉 stream_options");
        assertTrue(usage.promptTokens() > 0, "重试无 usage → 本地估算兜底");
    }
}
