package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SemanticCacheService 单元测试（AI 接入层重构 M5 语义缓存，2026-09-XX）。
 * <p>职责：验证语义缓存核心逻辑——①开关/最小长度/无 user 消息/未配置 embedding/向量化失败 → 一律 miss（零阻断）；
 * ②命中：同 model+jsonMode+温度且相似度 ≥ threshold → 复用缓存回复；
 * ③未命中：模型/温度/jsonMode 任一不同或相似度不足 → miss；
 * ④record 后容量 FIFO 淘汰；空输出不入缓存；⑤余弦相似度纯逻辑。</p>
 * <p>2026-09-21 抓修追加（世界运行真实缺陷）：⑥<b>fail-closed 场景白名单</b>——总开关关闭、
 * 白名单为空、场景不在名单内 → 一律 miss 且<b>不做 query 向量化</b>（世界运行时命中即回放旧决策：
 * 实测深夜复用白天行动、不同 NPC 复用同一句行动文本）。既有用例统一以
 * {@code sceneAllow="*"} + 显式 scene 走"开发工作流"口径，业务口径另由白名单用例覆盖。</p>
 */
class SemanticCacheServiceTest {

    private final ModelApiService modelApiService = mock(ModelApiService.class);
    private final DynamicEmbeddingModelFactory embeddingFactory = mock(DynamicEmbeddingModelFactory.class);
    private final OpenAiEmbeddingModel embeddingModel = mock(OpenAiEmbeddingModel.class);
    private SemanticCacheService svc;

    /** 本测试统一使用的场景名（模拟开发工作流场景，需在 sceneAllow 名单内） */
    private static final String SCENE = "worldgen_preview";

    private static final ProviderConfig CHAT_CONFIG =
            new ProviderConfig(11L, "https://api.example.com/v1", "sk", "chat-model", false);
    private static final ProviderConfig EMB_CONFIG =
            new ProviderConfig(21L, "https://api.example.com/v1", "sk", "emb-model", false);

    @BeforeEach
    void setUp() throws Exception {
        svc = new SemanticCacheService(modelApiService, embeddingFactory);
        setField(svc, "enabled", true);
        // 白名单放行全部场景（模拟"仅开发工作流临时开启"口径）
        setField(svc, "sceneAllow", "*");
        setField(svc, "threshold", 0.9);
        setField(svc, "maxSize", 200);
        setField(svc, "minQueryLength", 1);
        when(embeddingFactory.get(EMB_CONFIG)).thenReturn(embeddingModel);
        when(modelApiService.resolveEmbeddingProvider(any(), any())).thenReturn(EMB_CONFIG);
    }

    /** 带场景查询（本测试统一入口） */
    private SemanticCacheService.Lookup lookup(AiChatRequest request) {
        return svc.lookup(1L, 2L, CHAT_CONFIG, request, SCENE);
    }

    /** 反射设置私有 @Value 字段（测试专用） */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** mock embed 返回指定向量 */
    private void mockEmbed(float[] vector) {
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(vector)));
    }

    /** 构造带最后一条 user 消息的请求 */
    private static AiChatRequest request(String query, Double temperature, boolean jsonMode) {
        return new AiChatRequest(null,
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", query)),
                temperature, 128, jsonMode);
    }

    /**
     * 开关关闭：直接 miss，不产生任何 embedding 调用。
     */
    @Test
    void disabledAlwaysMiss() throws Exception {
        setField(svc, "enabled", false);
        SemanticCacheService.Lookup r = lookup(request("这是一段足够长的查询文本", 0.7, false));
        assertNull(r.hit());
        verify(embeddingFactory, never()).get(any());
    }

    /**
     * 查询过短（< minQueryLength）：不参与缓存（miss）。
     */
    @Test
    void shortQueryMiss() throws Exception {
        setField(svc, "minQueryLength", 8);
        SemanticCacheService.Lookup r = lookup(request("你好", 0.7, false));
        assertNull(r.hit());
    }

    /**
     * 无 user 消息：miss。
     */
    @Test
    void noUserMessageMiss() {
        AiChatRequest req = new AiChatRequest(null, List.of(new ChatMessage("system", "sys")), 0.7, 128, false);
        SemanticCacheService.Lookup r = lookup(req);
        assertNull(r.hit());
    }

    /**
     * 未配置 embedding 供应商：miss（零阻断）。
     */
    @Test
    void noEmbeddingProviderMiss() {
        when(modelApiService.resolveEmbeddingProvider(any(), any())).thenReturn(null);
        SemanticCacheService.Lookup r = lookup(request("这是一段足够长的查询文本", 0.7, false));
        assertNull(r.hit());
    }

    /**
     * 向量化失败：miss（零阻断，不抛错）。
     */
    @Test
    void embedFailureMiss() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embed api down"));
        SemanticCacheService.Lookup r = lookup(request("这是一段足够长的查询文本", 0.7, false));
        assertNull(r.hit());
    }

    // ==================== 2026-09-21 抓修：fail-closed 场景白名单 ====================

    /**
     * 白名单为空（缺省）：任何场景都不参与缓存，且<b>连 query 向量化都不做</b>（零成本）——
     * 这是世界运行期的缺省口径：NPC 的决策/情绪/行动永远不会被"缓存回放"。
     */
    @Test
    void emptySceneAllowDeniesEverySceneWithoutEmbedding() throws Exception {
        setField(svc, "sceneAllow", "");
        // 先塞一条，确认即使缓存里有内容也不会被命中
        mockEmbed(new float[]{1f, 0f, 0f});
        setField(svc, "sceneAllow", "*");
        SemanticCacheService.Lookup first = lookup(request("若木是什么？它有什么功效？", 0.7, false));
        svc.record(first, request("若木是什么？它有什么功效？", 0.7, false),
                new AiChatResult("若木是神树。", "chat-model", 10, 5, 15, 0, 10, 11L));
        clearInvocations(embeddingModel);
        setField(svc, "sceneAllow", "");

        SemanticCacheService.Lookup r = lookup(request("若木是什么？它有什么功效？", 0.7, false));
        assertNull(r.hit(), "白名单为空 → 恒 miss");
        verify(embeddingModel, never()).embed(anyString());
        assertEquals(1L, svc.stats().get("deniedByScene"), "拒绝计数可观测");
    }

    /**
     * 场景不在白名单内：miss + 不向量化（世界运行的 npc_emotion_eval / action 等场景即此口径）。
     */
    @Test
    void sceneNotInAllowListIsMissWithoutEmbedding() throws Exception {
        setField(svc, "sceneAllow", "worldgen_preview,card_gen");
        clearInvocations(embeddingModel);

        SemanticCacheService.Lookup r =
                svc.lookup(1L, 2L, CHAT_CONFIG, request("深夜在青禾村塾整理书案，准备次日授课教材", 0.7, true),
                        "npc_crowd_decision");
        assertNull(r.hit(), "未点名场景 → 不参与缓存（决策不会被回放）");
        verify(embeddingModel, never()).embed(anyString());
    }

    /**
     * 白名单前缀匹配：条目 {@code worldgen} 覆盖 {@code worldgen_build_v1} 等派生场景。
     */
    @Test
    void sceneAllowMatchesByPrefix() throws Exception {
        setField(svc, "sceneAllow", "worldgen");
        mockEmbed(new float[]{1f, 0f, 0f});
        String q = "生成青禾村的世界观分段文本，包含地理与势力。";
        SemanticCacheService.Lookup first =
                svc.lookup(1L, 2L, CHAT_CONFIG, request(q, 0.7, false), "worldgen_build_v1");
        assertNotNull(first.queryVector(), "命中白名单 → 正常走向量化");
        svc.record(first, request(q, 0.7, false), new AiChatResult("段A", "chat-model", 10, 5, 15, 0, 10, 11L));
        SemanticCacheService.Lookup second =
                svc.lookup(1L, 2L, CHAT_CONFIG, request(q, 0.7, false), "worldgen_build_v1");
        assertNotNull(second.hit(), "同场景同 query → 命中");
    }

    /**
     * 场景为空（null/空白，无 {@code AiCallContext} 上下文）：fail-closed 不参与缓存。
     */
    @Test
    void nullSceneIsDeniedEvenWithWildcardRuleForNamedScenes() throws Exception {
        setField(svc, "sceneAllow", "worldgen");
        // 兼容重载（无 scene 参数）→ 恒 miss，且不向量化
        SemanticCacheService.Lookup r =
                svc.lookup(1L, 2L, CHAT_CONFIG, request("这是一段足够长的查询文本", 0.7, false));
        assertNull(r.hit());
        verify(embeddingModel, never()).embed(anyString());
    }

    /**
     * 空缓存 miss → record → 同 query 第二次命中（语义相近复用回复 + 缓存 token 账本）。
     */
    @Test
    void recordThenSemanticHitReusesReply() {
        mockEmbed(new float[]{1f, 0f, 0f});
        String q = "若木是什么？它有什么功效？请详细说明。";
        // 第一次：miss + record
        SemanticCacheService.Lookup first = lookup(request(q, 0.7, false));
        assertNull(first.hit());
        AiChatResult result = new AiChatResult("若木是神树，可医百病。", "chat-model", 120, 60, 180, 30, 90, 11L);
        svc.record(first, request(q, 0.7, false), result);
        // 第二次：语义命中（同向量，余弦=1）
        SemanticCacheService.Lookup second = lookup(request(q, 0.7, false));
        assertNotNull(second.hit(), "同 query 应命中");
        assertEquals("若木是神树，可医百病。", second.hit().content());
        assertEquals(120, second.hit().promptTokens());
        assertEquals(60, second.hit().completionTokens());
        assertEquals(30, second.hit().cacheHitTokens());
        assertEquals(90, second.hit().cacheMissTokens());
        assertEquals(11L, second.hit().providerId());
    }

    /**
     * 相似度不足（低于 threshold）：miss。
     */
    @Test
    void belowThresholdMiss() {
        // 已缓存 [1,0,0]；新 query 向量 [0,1,0] → 余弦 0 < 0.9 → miss
        mockEmbed(new float[]{1f, 0f, 0f});
        SemanticCacheService.Lookup first = lookup(request("第一段足够长的查询文本内容", 0.7, false));
        svc.record(first, request("第一段足够长的查询文本内容", 0.7, false),
                new AiChatResult("A", "chat-model", 10, 5, 15, 0, 10, 11L));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0f, 1f, 0f})));
        SemanticCacheService.Lookup second = lookup(request("完全不同的第二段查询内容", 0.7, false));
        assertNull(second.hit());
    }

    /**
     * 模型不同：即使向量相同也 miss（防跨模型误命中）。
     */
    @Test
    void differentModelMiss() {
        mockEmbed(new float[]{1f, 0f, 0f});
        String q = "这是一段足够长的查询文本内容";
        SemanticCacheService.Lookup first = lookup(request(q, 0.7, false));
        svc.record(first, request(q, 0.7, false), new AiChatResult("A", "chat-model", 10, 5, 15, 0, 10, 11L));
        // 不同模型（请求指定 model）
        AiChatRequest otherModel = new AiChatRequest("other-model",
                List.of(new ChatMessage("user", q)), 0.7, 128, false);
        SemanticCacheService.Lookup second = lookup(otherModel);
        assertNull(second.hit());
    }

    /**
     * 温度不同：miss（同 query 不同采样参数不互相命中）。
     */
    @Test
    void differentTemperatureMiss() {
        mockEmbed(new float[]{1f, 0f, 0f});
        String q = "这是一段足够长的查询文本内容";
        SemanticCacheService.Lookup first = lookup(request(q, 0.7, false));
        svc.record(first, request(q, 0.7, false), new AiChatResult("A", "chat-model", 10, 5, 15, 0, 10, 11L));
        SemanticCacheService.Lookup second = lookup(request(q, 1.3, false));
        assertNull(second.hit());
    }

    /**
     * jsonMode 不同：miss（结构化输出与自由文本不互相命中）。
     */
    @Test
    void differentJsonModeMiss() {
        mockEmbed(new float[]{1f, 0f, 0f});
        String q = "这是一段足够长的查询文本内容";
        SemanticCacheService.Lookup first = lookup(request(q, 0.7, false));
        svc.record(first, request(q, 0.7, false), new AiChatResult("A", "chat-model", 10, 5, 15, 0, 10, 11L));
        SemanticCacheService.Lookup second = lookup(request(q, 0.7, true));
        assertNull(second.hit());
    }

    /**
     * FIFO 淘汰：容量上限 1 时，记录第 2 条后最旧的被淘汰。
     */
    @Test
    void fifoEvictsOldest() throws Exception {
        setField(svc, "maxSize", 1);
        mockEmbed(new float[]{1f, 0f, 0f});
        SemanticCacheService.Lookup first = lookup(request("第一条足够长的查询文本", 0.7, false));
        svc.record(first, request("第一条足够长的查询文本", 0.7, false),
                new AiChatResult("A", "chat-model", 10, 5, 15, 0, 10, 11L));
        assertEquals(1, svc.cachedCount());
        SemanticCacheService.Lookup second = lookup(request("第二条足够长的查询文本", 0.7, false));
        svc.record(second, request("第二条足够长的查询文本", 0.7, false),
                new AiChatResult("B", "chat-model", 20, 10, 30, 0, 20, 11L));
        assertEquals(1, svc.cachedCount(), "FIFO 应只保留最新一条");
        // 第一条已淘汰 → 不再命中；第二条可命中
        SemanticCacheService.Lookup hitSecond = lookup(request("第二条足够长的查询文本", 0.7, false));
        assertNotNull(hitSecond.hit());
        assertEquals("B", hitSecond.hit().content());
    }

    /**
     * 空输出不入缓存（异常/受限输出不污染缓存）。
     */
    @Test
    void emptyContentNotRecorded() {
        mockEmbed(new float[]{1f, 0f, 0f});
        SemanticCacheService.Lookup first = lookup(request("这是一段足够长的查询文本内容", 0.7, false));
        svc.record(first, request("这是一段足够长的查询文本内容", 0.7, false),
                new AiChatResult("", "chat-model", 10, 5, 15, 0, 10, 11L));
        assertEquals(0, svc.cachedCount(), "空输出不应入缓存");
    }

    /**
     * 余弦相似度纯逻辑：相同=1、正交=0、维度不同=0、同向不同模长=1。
     */
    @Test
    void cosineBasics() {
        assertEquals(1.0, SemanticCacheService.cosine(new float[]{1, 2, 3}, new float[]{1, 2, 3}), 1e-9);
        assertEquals(0.0, SemanticCacheService.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertEquals(0.0, SemanticCacheService.cosine(new float[]{1, 0, 0}, new float[]{1, 0}), 1e-9);
        assertEquals(1.0, SemanticCacheService.cosine(new float[]{1, 2}, new float[]{2, 4}), 1e-9);
        assertTrue(SemanticCacheService.cosine(new float[]{1, 1, 0}, new float[]{1, 1, 0})
                > SemanticCacheService.cosine(new float[]{1, 1, 0}, new float[]{0, 0, 1}));
    }
}
