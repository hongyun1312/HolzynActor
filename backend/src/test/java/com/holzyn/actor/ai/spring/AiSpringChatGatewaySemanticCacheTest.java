package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSpringChatGateway 语义缓存集成测试（AI 接入层重构 M5，2026-09-XX）。
 * <p>职责：验证 userId 入口（4 参 chatCompletion / chatCompletionJson）——
 * ①首次调用 miss → 调 LLM + 入缓存；②同 query 第二次命中 → 复用缓存回复（不二次调 LLM）+
 * trace 带 semantic_cache_hit=true；③chatCompletionJson 走同一缓存；④embedding 未配置 → 恒 miss 不缓存。</p>
 */
class AiSpringChatGatewaySemanticCacheTest {

    private ActorAiCallLogRepository repository;
    private AiCallTraceService traceService;
    private DynamicChatModelFactory factory;
    private OpenAiChatModel chatModel;
    private ModelApiService modelApiService;
    private DynamicEmbeddingModelFactory embeddingFactory;
    private OpenAiEmbeddingModel embeddingModel;
    private SemanticCacheService semanticCacheService;
    private AiSpringChatGateway gateway;

    private static final ProviderConfig CHAT_CONFIG =
            new ProviderConfig(11L, "https://api.example.com/v1", "sk-test", "m", false);
    private static final ProviderConfig EMB_CONFIG =
            new ProviderConfig(21L, "https://api.example.com/v1", "sk-emb", "emb", false);

    @BeforeEach
    void setUp() throws Exception {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
        traceService = new AiCallTraceService(repository);
        factory = Mockito.mock(DynamicChatModelFactory.class);
        chatModel = Mockito.mock(OpenAiChatModel.class);
        modelApiService = Mockito.mock(ModelApiService.class);
        embeddingFactory = Mockito.mock(DynamicEmbeddingModelFactory.class);
        embeddingModel = Mockito.mock(OpenAiEmbeddingModel.class);
        semanticCacheService = new SemanticCacheService(modelApiService, embeddingFactory);
        setField(semanticCacheService, "enabled", true);
        // 2026-09-21 抓修：语义缓存改为 fail-closed 场景白名单——本测试验证缓存机制本身，故放行全部场景
        setField(semanticCacheService, "sceneAllow", "*");
        setField(semanticCacheService, "threshold", 0.9);
        setField(semanticCacheService, "maxSize", 200);
        setField(semanticCacheService, "minQueryLength", 1);
        gateway = new AiSpringChatGateway(factory, traceService, modelApiService, semanticCacheService,
                new HolzynTraceAdvisor());

        when(modelApiService.resolveProviderConfig(any(), any(), any(), any())).thenReturn(CHAT_CONFIG);
        when(modelApiService.resolveEmbeddingProvider(any(), any())).thenReturn(EMB_CONFIG);
        when(embeddingFactory.get(EMB_CONFIG)).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{1f, 0f, 0f})));
        when(factory.get(any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(buildResponse("若木是神树，可医百病。", usage(10, 5)));
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().model("m").build();
        when(chatModel.getOptions()).thenReturn(defaultOptions);
        when(chatModel.getDefaultOptions()).thenReturn(defaultOptions);
    }

    @AfterEach
    void tearDown() {
        com.holzyn.actor.domain.debug.service.AiCallContext.setSink(null);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static AiChatRequest request(String query) {
        return new AiChatRequest(null,
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", query)), 0.7, 128, false);
    }

    /**
     * 首次 miss 调 LLM + 入缓存；同 query 第二次命中（不二次调 LLM）且 trace 带 semantic_cache_hit=true。
     */
    @Test
    void missThenHitReusesCachedReply() {
        String q = "若木是什么？它有什么功效？请详细说明。";
        AiChatResult first = gateway.chatCompletion(1L, 2L, null, request(q));
        assertEquals("若木是神树，可医百病。", first.content());
        verify(chatModel, times(1)).call(any(Prompt.class));
        // 首条 trace 异步写库：先排空在途写库任务，再清空 mock 计数（防首条落库晚于第二次命中）
        traceService.awaitIdle(2000);

        Mockito.clearInvocations(repository, chatModel);
        AiChatResult second = gateway.chatCompletion(1L, 2L, null, request(q));
        assertEquals("若木是神树，可医百病。", second.content());
        verify(chatModel, times(0)).call(any(Prompt.class));
        // 缓存命中 trace 落库：semantic_cache_hit=true + status=success + 复用 token 账本
        traceService.awaitIdle(2000);
        ArgumentCaptor<ActorAiCallLog> captor = ArgumentCaptor.forClass(ActorAiCallLog.class);
        verify(repository).save(captor.capture());
        ActorAiCallLog saved = captor.getValue();
        assertEquals("success", saved.getStatus());
        assertTrue(saved.getDetailJson() != null && saved.getDetailJson().contains("\"semantic_cache_hit\":true"),
                "命中 trace 的 detail_json 应含 semantic_cache_hit=true");
        assertTrue(saved.getDetailJson().contains("\"remark\":\"语义缓存命中（未调用 LLM）\""));
        assertEquals(10, saved.getTokenIn(), "命中复用缓存 token 账本（prompt=10）");
        assertEquals(5, saved.getTokenOut(), "命中复用缓存 token 账本（completion=5）");
    }

    /**
     * chatCompletionJson（jsonMode）走同一语义缓存：首次 miss 调 LLM，第二次命中不调。
     */
    @Test
    void jsonModeCachedViaChatCompletionJson() {
        String q = "请生成角色设定的 JSON 结构。";
        AiChatResult first = gateway.chatCompletionJson(1L, 2L, null, request(q));
        assertEquals("若木是神树，可医百病。", first.content());
        verify(chatModel, times(1)).call(any(Prompt.class));

        Mockito.clearInvocations(chatModel);
        AiChatResult second = gateway.chatCompletionJson(1L, 2L, null, request(q));
        assertEquals("若木是神树，可医百病。", second.content());
        verify(chatModel, times(0)).call(any(Prompt.class));
    }

    /**
     * embedding 未配置：两次调用均 miss（恒调 LLM，不缓存不阻断）。
     */
    @Test
    void noEmbeddingProviderAlwaysMiss() {
        when(modelApiService.resolveEmbeddingProvider(any(), any())).thenReturn(null);
        String q = "这是一段足够长的查询文本内容";
        gateway.chatCompletion(1L, 2L, null, request(q));
        gateway.chatCompletion(1L, 2L, null, request(q));
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    /**
     * 2026-09-21 抓修（世界运行真实缺陷）：<b>世界运行场景即使开启缓存也不参与</b>——同 query 两次都
     * 必须真调 LLM，且<b>一次 query 向量化都不发生</b>（零成本）。原实现按 Q8「全部非流式统一生效」，
     * 实测世界运行 15 分钟内 43% 的决策/情绪调用命中缓存被回放（深夜复用白天行动、不同 NPC 复用同一
     * 句行动文本）。本用例用场景上下文锁定该回归点。
     */
    @Test
    void worldRunSceneIsNeverCachedEvenWhenCacheEnabled() throws Exception {
        // 只放行开发工作流场景：世界运行的 npc_crowd_decision 不在名单内
        setField(semanticCacheService, "sceneAllow", "worldgen_preview");
        Mockito.clearInvocations(chatModel, embeddingModel);
        String q = "深夜在青禾村塾整理书案，准备次日授课所需教材与讲义。";
        // 场景上下文（AiCallContext）：世界运行的人群决策
        com.holzyn.actor.domain.debug.service.AiCallContext.run("npc_crowd_decision", 9L,
                () -> gateway.chatCompletionJson(1L, 2L, null, request(q)));
        com.holzyn.actor.domain.debug.service.AiCallContext.run("npc_crowd_decision", 9L,
                () -> gateway.chatCompletionJson(1L, 2L, null, request(q)));
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(embeddingModel, times(0)).embed(anyString());
    }

    private static ChatResponse buildResponse(String text, Usage usage) {
        Generation gen = new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("m").usage(usage).build();
        return new ChatResponse(List.of(gen), meta);
    }

    private static Usage usage(Integer prompt, Integer completion) {
        return new DefaultUsage(prompt, completion, prompt + completion);
    }
}
