package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AiCallTraceService 埋点逻辑单元测试（2026-08-20 调试前端 + 2026-08-24 detail_json 扩展）。
 * <p>职责：验证——上下文（scene/characterId）关联、异步落库、截断、降级标记、
 * 开关关闭跳过、兜底路径（无上下文）、未发起调用不落库；
 * 2026-08-24 起追加 detail_json 组装（完整结构/流式 data 行/失败形态/超长 raw 截断）与
 * 详情 JSON 文件输出（fileEnabled=true 时写入 &lt;data-dir&gt;/ai-call-logs/）。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库）；{@link AiCallContext} 的 sink 手动注册到被测服务。</p>
 */
class AiCallTraceServiceTest {

    /** Jackson 解析器（detail_json 断言用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActorAiCallLogRepository repository;
    private AiCallTraceService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        service = new AiCallTraceService(repository, true, 200);
        AiCallContext.setSink(service::submitAsync);
    }

    @AfterEach
    void tearDown() {
        AiCallContext.setSink(null);
    }

    /**
     * 上下文内成功调用：scene/角色/项目/完整 prompt/输出/token 正确落库。
     */
    @Test
    void contextRunCapturesSceneAndCharacter() {
        AiCallContext.run("dialog", 5L, () -> {
            // 模拟 Router 埋点：begin 标记 invoked 并回填 projectId
            AiCallTrace trace = service.begin(9L);
            assertNotNull(trace, "上下文内应能取到当前 trace");
            trace.fillMessages(new AiChatRequest("model-x", List.of(
                    new AiChatRequest.ChatMessage("system", "系统提示"),
                    new AiChatRequest.ChatMessage("user", "用户消息")), null, null));
            trace.markSuccess("模型回复", "model-x", 10, 20, 3, 7, 1L);
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("dialog", saved.getScene());
        assertEquals(5L, saved.getCharacterId());
        assertEquals(9L, saved.getProjectId());
        assertEquals("系统提示", saved.getSystemPrompt());
        assertEquals("用户消息", saved.getUserPrompt());
        assertEquals("模型回复", saved.getResponse());
        assertEquals("success", saved.getStatus());
        assertEquals(10, saved.getTokenIn());
        assertEquals(20, saved.getTokenOut());
        assertEquals(3, saved.getCacheHit());
        assertEquals(7, saved.getCacheMiss());
        assertEquals(1L, saved.getProviderId());
    }

    /**
     * 失败 + 程序化兜底：markDegraded → 落库 status=degraded。
     */
    @Test
    void markDegradedYieldsDegradedStatus() {
        AiCallContext.run("action", 3L, () -> {
            AiCallTrace trace = service.begin(7L);
            trace.markFailure(new RuntimeException("AI 调用失败"));
            AiCallContext.markDegraded();
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        assertEquals("degraded", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getErrorMessage());
        assertEquals(3L, captor.getValue().getCharacterId());
    }

    /**
     * 失败（未降级）：status=failed + 错误信息。
     */
    @Test
    void failureYieldsFailedStatus() {
        AiCallContext.run("memory", null, () -> {
            AiCallTrace trace = service.begin(2L);
            trace.markFailure(new RuntimeException("网络错误"));
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
        assertEquals("网络错误", captor.getValue().getErrorMessage());
    }

    /**
     * 截断：超长 Prompt/Response 按 maxPromptChars 截断。
     */
    @Test
    void longContentIsTruncated() {
        String longPrompt = "长".repeat(1000);
        AiCallContext.run("dialog", null, () -> {
            AiCallTrace trace = service.begin(null);
            trace.fillMessages(new AiChatRequest("m", List.of(
                    new AiChatRequest.ChatMessage("user", longPrompt)), null, null));
            trace.markSuccess("回复".repeat(500), "m", 1, 1, 0, 0, 1L);
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        // 截断后：明显小于原始 1000 字符，且带截断标记（上限 200 + 后缀 ~20 字符）
        assertTrue(saved.getUserPrompt().length() < 500, "userPrompt 应被截断（原始 1000 字符）");
        assertTrue(saved.getUserPrompt().contains("已截断"), "截断标记应存在");
        assertTrue(saved.getResponse().length() < 500, "response 应被截断（原始 1000 字符）");
    }

    /**
     * 开关关闭：即使已 invoked 也不落库。
     */
    @Test
    void disabledSkipsRecording() {
        AiCallTraceService disabled = new AiCallTraceService(repository, false, 200);
        AiCallContext.setSink(disabled::submitAsync);
        AiCallContext.run("dialog", null, () -> {
            AiCallTrace trace = disabled.begin(1L);
            trace.markSuccess("ok", "m", 1, 1, 0, 0, 1L);
            return null;
        });
        disabled.awaitIdle(2000);
        verify(repository, never()).save(Mockito.any());
        AiCallContext.setSink(service::submitAsync);
    }

    /**
     * 无上下文兜底路径（Router 未包裹场景）：begin/end 提交，scene=chat、projectId 回填。
     */
    @Test
    void fallbackWithoutContextSubmitsWithGenericScene() {
        AiCallTrace trace = service.begin(11L);
        trace.fillMessages(new AiChatRequest("m", List.of(
                new AiChatRequest.ChatMessage("user", "hi")), null, null));
        trace.markSuccess("hello", "m", 5, 3, 0, 5, 1L);
        service.end(trace);
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("chat", saved.getScene());
        assertEquals(11L, saved.getProjectId());
        assertNull(saved.getCharacterId());
        assertEquals("success", saved.getStatus());
    }

    /**
     * 未发起 Provider 调用（invoked=false）的上下文：不落库（避免空 trace 噪音）。
     */
    @Test
    void nonInvokedTraceIsSkipped() {
        AiCallContext.run("dialog", null, () -> null);
        service.awaitIdle(500);
        verify(repository, never()).save(Mockito.any());
    }

    // ===== 2026-08-24 AI 调用详情 JSON（docs/AI调用详情JSON格式设计_V1.0.md） =====

    /**
     * 完整对话调用：detail_json 结构正确（时间轴 +08:00 / 供应商 / 请求参数 / 输入输出 / 用量 / 状态）。
     */
    @Test
    void detailJsonCapturesFullDialogStructure() {
        AiCallContext.run("dialog", 5L, () -> {
            AiCallTrace trace = service.begin(9L);
            trace.setBaseUrl("https://api.deepseek.com/v1");
            trace.setStream(false);
            trace.fillMessages(new AiChatRequest("model-x", List.of(
                    new AiChatRequest.ChatMessage("system", "系统提示"),
                    new AiChatRequest.ChatMessage("user", "今天天气怎么样？")), 0.8, 100, false));
            // 模拟 Provider 发送前截取的原始请求体 / 解析前截取的原始响应体
            trace.setInputRaw("{\"model\":\"model-x\",\"messages\":[{\"role\":\"system\",\"content\":\"系统提示\"},"
                    + "{\"role\":\"user\",\"content\":\"今天天气怎么样？\"}],\"temperature\":0.8,\"max_tokens\":100}");
            trace.setOutputRaw("{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"阳光正好\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}");
            trace.markSuccess("阳光正好", "model-x", 10, 20, 3, 7, 1L);
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        String detail = captor.getValue().getDetailJson();
        assertNotNull(detail, "detail_json 应落库");
        JsonNode root = MAPPER.readTree(detail);
        // ===== 时间轴：ISO 8601 +08:00 =====
        assertTrue(root.path("time").asText().contains("+08:00"), "time 应为 +08:00 偏移时间");
        assertTrue(root.path("end_time").asText().contains("+08:00"), "end_time 应为 +08:00 偏移时间");
        assertTrue(root.path("duration_ms").isNumber(), "duration_ms 应为数值");
        // ===== 业务上下文 =====
        assertEquals(9L, root.path("project_id").asLong());
        assertEquals(5L, root.path("character_id").asLong());
        assertEquals("dialog", root.path("scene").asText());
        assertEquals(1, root.path("attempt").asInt());
        assertEquals("ai_call", root.path("step_type").asText());
        // ===== 供应商 =====
        assertEquals("https://api.deepseek.com/v1", root.path("provider").path("base_url").asText());
        assertEquals("model-x", root.path("provider").path("model").asText());
        assertEquals(1L, root.path("provider").path("provider_id").asLong());
        // ===== 请求参数 =====
        assertEquals(0.8, root.path("request_params").path("temperature").asDouble(), 0.001);
        assertEquals(100, root.path("request_params").path("max_tokens").asInt());
        assertFalse(root.path("request_params").path("stream").asBoolean());
        assertFalse(root.path("request_params").path("json_mode").asBoolean());
        // ===== 输入：raw 对象 + messages 全序列 + 摘要 =====
        assertEquals("model-x", root.path("input").path("raw").path("model").asText(), "input.raw 应为请求体对象");
        JsonNode msgs = root.path("input").path("messages");
        assertEquals(2, msgs.size(), "messages 应保留全序列");
        assertEquals("system", msgs.get(0).path("role").asText());
        assertEquals("user", msgs.get(1).path("role").asText());
        assertTrue(root.path("input").path("summary").asText().startsWith("system:"), "input.summary 应自动生成");
        // ===== 输出：raw 对象 + text 全文 + 摘要 =====
        assertEquals("chatcmpl-1", root.path("output").path("raw").path("id").asText(), "output.raw 应为响应体对象");
        assertEquals("阳光正好", root.path("output").path("text").asText());
        assertTrue(root.path("output").path("summary").asText().contains("阳光正好"), "output.summary 应含回复摘要");
        // ===== 用量 =====
        assertEquals(10, root.path("usage").path("token_in").asInt());
        assertEquals(20, root.path("usage").path("token_out").asInt());
        assertEquals(3, root.path("usage").path("cache_hit").asInt());
        assertEquals(7, root.path("usage").path("cache_miss").asInt());
        // ===== 状态/错误/context/retry =====
        assertEquals("success", root.path("status").asText());
        assertTrue(root.path("error").isNull(), "成功调用 error 应为 null");
        // O4 P2-2 trace 数据流补全：context 由恒 null 升级为对象（data_sources/prompt_version 可空）
        assertTrue(root.path("context").isObject(), "context 应为对象（O4 数据流补全）");
        assertTrue(root.path("context").path("data_sources").isNull(), "无数据源时 data_sources 为 null");
        assertEquals(0, root.path("retry_count").asInt());
        assertEquals("", root.path("remark").asText());
    }

    /**
     * 流式调用：request_params.stream=true，output.raw 为收集的全部 data 行组成的 JSON 数组。
     */
    @Test
    void detailJsonStreamingCollectsDataLinesAsArray() {
        AiCallContext.run("dialog", null, () -> {
            AiCallTrace trace = service.begin(1L);
            trace.setStream(true);
            trace.fillMessages(new AiChatRequest("m", List.of(new AiChatRequest.ChatMessage("user", "hi")), null, null));
            // 模拟 Provider 收集的全部 data 行（排除 [DONE]）组成的 JSON 数组
            trace.setOutputRaw("[{\"choices\":[{\"delta\":{\"content\":\"你\"}}]},{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}]");
            trace.markSuccess("你好", "m", 5, 3, 0, 5, 1L);
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        JsonNode root = MAPPER.readTree(captor.getValue().getDetailJson());
        assertTrue(root.path("request_params").path("stream").asBoolean(), "流式调用 stream 应为 true");
        JsonNode raw = root.path("output").path("raw");
        assertTrue(raw.isArray(), "流式 output.raw 应为 data 行数组");
        assertEquals(2, raw.size(), "应收集全部 data 行");
        assertEquals("你", raw.get(0).path("choices").path(0).path("delta").path("content").asText());
        assertEquals("你好", root.path("output").path("text").asText());
    }

    /**
     * 失败 + 降级：status=degraded、error 对象带 message、output.summary 占位文案、失败响应体仍保留。
     */
    @Test
    void detailJsonFailureCarriesErrorAndSummaryPlaceholder() {
        AiCallContext.run("action", 3L, () -> {
            AiCallTrace trace = service.begin(7L);
            trace.setBaseUrl("https://x");
            // 模拟解析前截取的错误响应体（非 2xx）
            trace.setOutputRaw("{\"error\":{\"message\":\"Rate limit reached\"}}");
            trace.markFailure(new RuntimeException("Rate limit reached"));
            AiCallContext.markDegraded();
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        JsonNode root = MAPPER.readTree(captor.getValue().getDetailJson());
        assertEquals("degraded", root.path("status").asText(), "降级标记优先");
        assertEquals("Rate limit reached", root.path("error").path("message").asText());
        assertTrue(root.path("error").path("code").isNull(), "本期无结构化错误码");
        assertEquals("（调用失败，无输出）", root.path("output").path("summary").asText());
        assertTrue(root.path("output").path("text").isNull());
        assertEquals("Rate limit reached", root.path("output").path("raw").path("error").path("message").asText(),
                "失败响应体应保留在 output.raw");
    }

    /**
     * 超长 raw：按 maxPromptChars 截断为字符串存储，且 detail_json 整体仍是合法 JSON。
     */
    @Test
    void detailJsonRawTruncationKeepsJsonValid() {
        String longContent = "长".repeat(500);
        String longRaw = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + longContent + "\"}]}";
        AiCallContext.run("dialog", null, () -> {
            AiCallTrace trace = service.begin(1L);
            trace.setInputRaw(longRaw);
            trace.fillMessages(new AiChatRequest("m", List.of(new AiChatRequest.ChatMessage("user", longContent)), null, null));
            trace.markSuccess("ok", "m", 1, 1, 0, 0, 1L);
            return null;
        });
        service.awaitIdle(2000);

        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        String detail = captor.getValue().getDetailJson();
        JsonNode root = MAPPER.readTree(detail); // 能解析 = detail_json 始终合法 JSON
        JsonNode raw = root.path("input").path("raw");
        assertTrue(raw.isTextual(), "超长 raw 应截断为字符串存储（保证整体合法 JSON）");
        assertTrue(raw.asText().length() < 300, "截断后应远小于原始 ~500 字符");
        assertTrue(raw.asText().contains("已截断"), "截断标记应存在");
    }

    /**
     * 详情 JSON 文件输出（fileEnabled=true + dataDir）：落库同时写 &lt;data-dir&gt;/ai-call-logs/ai-call-*.json。
     */
    @Test
    void detailJsonWrittenToFileWhenFileEnabled() throws Exception {
        Path tmp = Files.createTempDirectory("ai-call-log-test");
        AiCallTraceService fs = new AiCallTraceService(repository, true, 200, true, tmp.toString());
        AiCallContext.setSink(fs::submitAsync);
        AiCallContext.run("dialog", null, () -> {
            AiCallTrace trace = fs.begin(1L);
            trace.fillMessages(new AiChatRequest("m", List.of(new AiChatRequest.ChatMessage("user", "hi")), null, null));
            trace.markSuccess("hello", "m", 1, 1, 0, 0, 1L);
            return null;
        });
        fs.awaitIdle(2000);
        AiCallContext.setSink(service::submitAsync);

        Path dir = tmp.resolve("ai-call-logs");
        assertTrue(Files.isDirectory(dir), "应创建 ai-call-logs 目录");
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> files = list.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            assertEquals(1, files.size(), "应输出 1 个详情 JSON 文件");
            String content = Files.readString(files.get(0), StandardCharsets.UTF_8);
            assertTrue(content.contains("\"dialog\""), "文件应含场景信息");
            MAPPER.readTree(content); // pretty 打印内容仍应合法 JSON
        }
    }
}
