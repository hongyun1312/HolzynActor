package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.memory.common.MemoryDedup;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆向量服务单元测试（O1 记忆内核扩展 O1a，混合检索 ≥4 用例，domain/memory/service 同包）。
 * <p>职责：验证——余弦相似度（等向量=1/正交=0/null/维度不一致=0）、确定性高斯噪声（同 seed
 * 同输出 + 归一化）、混合检索分（0.6 cosine + 0.4 Dice 权重 / 向量缺失回退纯 Dice / 0~1 钳制）。</p>
 * <p>设计依据：《HolzynActor_O1记忆内核扩展_L1动态模糊与跨层级巩固_设计方案_V1.0》§3.2 ③。</p>
 */
class MemoryVectorServiceTest {

    private MemoryVectorService service;

    @BeforeEach
    void setUp() {
        service = new MemoryVectorService(
                Mockito.mock(ModelApiService.class),
                Mockito.mock(DynamicEmbeddingModelFactory.class),
                Mockito.mock(AiCallTraceService.class),
                Mockito.mock(UsageLogService.class),
                Path.of("target", "test-data", "vector").toString(), "");
        ReflectionTestUtils.setField(service, "hybridVecWeight", 0.6);
    }

    // ==================== 余弦相似度 ====================

    /** 等向量：余弦 = 1。 */
    @Test
    void cosineIdenticalVectorsIsOne() {
        float[] a = {1f, 2f, 3f};
        assertEquals(1.0, MemoryVectorService.cosine(a, a.clone()), 1e-9);
    }

    /** 正交向量：余弦 = 0；null/维度不一致 = 0。 */
    @Test
    void cosineOrthogonalAndEdgeCases() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0.0, MemoryVectorService.cosine(a, b), 1e-9);
        assertEquals(0.0, MemoryVectorService.cosine(null, b), 1e-9);
        assertEquals(0.0, MemoryVectorService.cosine(a, null), 1e-9);
        assertEquals(0.0, MemoryVectorService.cosine(new float[0], b), 1e-9);
        assertEquals(0.0, MemoryVectorService.cosine(a, new float[]{1f, 2f, 3f}), 1e-9);
    }

    /** 相似向量：夹角小 → 余弦接近 1。 */
    @Test
    void cosineSimilarVectorsHigh() {
        float[] a = {1f, 2f, 3f, 4f};
        float[] b = {1.1f, 1.9f, 3.2f, 3.8f};
        double c = MemoryVectorService.cosine(a, b);
        assertTrue(c > 0.99, "cosine 应接近 1，实际 " + c);
    }

    // ==================== 确定性噪声 ====================

    /** 噪声确定性：同 seed 同输出；叠加后归一化（模长 ≈1）。 */
    @Test
    void noiseDeterministicAndNormalized() {
        float[] base = {1f, 0f, 0f};
        float[] n1 = MemoryVectorService.noise(base, 0.12, 42L);
        float[] n2 = MemoryVectorService.noise(base, 0.12, 42L);
        float[] n3 = MemoryVectorService.noise(base, 0.12, 43L);
        assertNotNull(n1);
        assertEquals(n1.length, base.length);
        // 同 seed 输出一致
        for (int i = 0; i < n1.length; i++) {
            assertEquals(n1[i], n2[i], 1e-9);
        }
        // 不同 seed 输出不同（确定性扰动）
        boolean diff = false;
        for (int i = 0; i < n1.length; i++) {
            if (Math.abs(n1[i] - n3[i]) > 1e-9) {
                diff = true;
                break;
            }
        }
        assertTrue(diff, "不同 seed 应产生不同噪声");
        // 归一化：模长 ≈ 1
        double norm = Math.sqrt(n1[0] * n1[0] + n1[1] * n1[1] + n1[2] * n1[2]);
        assertEquals(1.0, norm, 1e-6);
    }

    /** 噪声幅度：σ 越大偏离越明显（检索难度上升）。 */
    @Test
    void noiseSigmaScalesDistortion() {
        float[] base = {1f, 0f, 0f, 0f};
        float[] small = MemoryVectorService.noise(base, 0.02, 7L);
        float[] large = MemoryVectorService.noise(base, 0.3, 7L);
        double dSmall = Math.abs(small[0] - 1f);
        double dLarge = Math.abs(large[0] - 1f);
        assertTrue(dLarge > dSmall, "σ 越大扰动应越大");
    }

    // ==================== 混合检索分（hybrid ≥4） ====================

    /** 混合分：0.6×cosine + 0.4×Dice。 */
    @Test
    void hybridRelevanceWeightsVecAndDice() {
        float[] q = {1f, 0f, 0f};
        float[] m = {1f, 0f, 0f};
        double d = MemoryDedup.overlapScore("集市卖鱼", "集市卖鱼");
        double expect = 0.6 * 1.0 + 0.4 * d;
        assertEquals(expect, service.hybridRelevance(q, m, "集市卖鱼", "集市卖鱼"), 1e-9);
    }

    /** 混合分：向量缺失（未配置供应商）→ 回退纯 Dice。 */
    @Test
    void hybridRelevanceFallsBackToDiceWithoutVectors() {
        double d = MemoryDedup.overlapScore("昨天在集市卖鱼", "昨天在集市卖鱼");
        double score = service.hybridRelevance(null, null, "昨天在集市卖鱼", "昨天在集市卖鱼");
        assertEquals(0.4 * d, score, 1e-9);
    }

    /** 混合分：语义相近但字面不同（向量命中）高于字面重叠。 */
    @Test
    void hybridRelevanceVectorRescuesSemanticMatch() {
        // 向量完全相同（语义同）但文本不同 → cosine=1 拉高总分
        float[] q = {0.6f, 0.8f};
        float[] m = {0.6f, 0.8f};
        double score = service.hybridRelevance(q, m, "昨日与赵屠户议价", "找张屠户谈肉价");
        assertTrue(score >= 0.6, "向量通道应显著贡献，实际 " + score);
    }

    /** 混合分：0~1 钳制（越界输入不越界输出）。 */
    @Test
    void hybridRelevanceClampedToUnit() {
        double score = service.hybridRelevance(new float[]{1f, 0f}, new float[]{1f, 0f},
                "同一文本", "同一文本");
        assertTrue(score >= 0.0 && score <= 1.0);
        assertEquals(1.0, score, 1e-9);
    }

    /** 混合分：空文本但向量通道仍贡献（cosine 不受文本影响）；向量缺失时纯 Dice=0。 */
    @Test
    void hybridRelevanceBlankTextBehavior() {
        // 向量存在：0.6×cosine(1) + 0.4×Dice(0) = 0.6
        assertEquals(0.6, service.hybridRelevance(new float[]{1f}, new float[]{1f}, "", ""), 1e-9);
        // 向量缺失 + 空文本：纯 Dice=0（防 NaN 传染）
        assertEquals(0.0, service.hybridRelevance(null, null, "", ""), 1e-9);
        assertEquals(0.0, service.hybridRelevance(null, null, null, null), 1e-9);
    }

    // ==================== 2026-09-21 抓修：同文本向量复用 ====================

    /**
     * 同一文本第二次写入必须复用向量、不再调用 embedding 接口（世界运行实测同一句记忆文本被重复
     * 向量化 16 次）；不同文本仍各自调用一次。
     */
    @Test
    void sameTextReusesVectorWithoutReembedding() {
        ModelApiService modelApiService = Mockito.mock(ModelApiService.class);
        DynamicEmbeddingModelFactory factory = Mockito.mock(DynamicEmbeddingModelFactory.class);
        AiCallTraceService traceService = Mockito.mock(AiCallTraceService.class);
        UsageLogService usageLogService = Mockito.mock(UsageLogService.class);
        dev.langchain4j.model.openai.OpenAiEmbeddingModel embModel =
                Mockito.mock(dev.langchain4j.model.openai.OpenAiEmbeddingModel.class);
        com.holzyn.actor.ai.ProviderConfig cfg =
                new com.holzyn.actor.ai.ProviderConfig(67L, "https://x/v1", "sk", "qwen3-embedding", false);
        Mockito.when(modelApiService.resolveEmbeddingProvider(Mockito.any(), Mockito.any())).thenReturn(cfg);
        Mockito.when(factory.get(cfg)).thenReturn(embModel);
        // trace 服务返回可用的 trace 替身（真实实现里 beginScene 恒非空；mock 不 stub 会返回 null → NPE 被吞）
        Mockito.when(traceService.beginScene(Mockito.any(), Mockito.anyString()))
                .thenReturn(Mockito.mock(com.holzyn.actor.domain.debug.service.AiCallTrace.class));
        Mockito.when(embModel.embed(Mockito.anyString())).thenReturn(
                dev.langchain4j.model.output.Response.from(
                        new dev.langchain4j.data.embedding.Embedding(new float[]{0.1f, 0.2f})));
        MemoryVectorService svc = new MemoryVectorService(modelApiService, factory, traceService,
                usageLogService, Path.of("target", "test-data", "vector-cache").toString(), "");

        String text = "竹林茅舍 rest（歇息就寝）";
        assertTrue(svc.addVector(1L, 2L, 100L, text), "首次写入向量成功");
        assertTrue(svc.addVector(1L, 2L, 101L, text), "第二次同文本写入同样成功（复用向量）");
        assertTrue(svc.addVector(1L, 2L, 102L, "青禾村塾 rest（歇息就寝）"), "不同文本正常向量化");

        Mockito.verify(embModel, Mockito.times(2)).embed(Mockito.anyString());
        java.util.Map<String, Object> stats = svc.vectorStats();
        assertEquals(1L, stats.get("textCacheHits"), "同文本复用命中 1 次");
        assertEquals(2L, stats.get("embedCalls"), "真实嵌入调用 2 次（同文本只算一次）");
    }

    /** 开关关闭：退回抓修前行为（每次写入都真实调用 embedding）。 */
    @Test
    void textCacheDisabledAlwaysEmbeds() {
        ModelApiService modelApiService = Mockito.mock(ModelApiService.class);
        DynamicEmbeddingModelFactory factory = Mockito.mock(DynamicEmbeddingModelFactory.class);
        dev.langchain4j.model.openai.OpenAiEmbeddingModel embModel =
                Mockito.mock(dev.langchain4j.model.openai.OpenAiEmbeddingModel.class);
        com.holzyn.actor.ai.ProviderConfig cfg =
                new com.holzyn.actor.ai.ProviderConfig(67L, "https://x/v1", "sk", "qwen3-embedding", false);
        Mockito.when(modelApiService.resolveEmbeddingProvider(Mockito.any(), Mockito.any())).thenReturn(cfg);
        Mockito.when(factory.get(cfg)).thenReturn(embModel);
        Mockito.when(embModel.embed(Mockito.anyString())).thenReturn(
                dev.langchain4j.model.output.Response.from(
                        new dev.langchain4j.data.embedding.Embedding(new float[]{0.1f, 0.2f})));
        com.holzyn.actor.domain.debug.service.AiCallTraceService traceService =
                Mockito.mock(com.holzyn.actor.domain.debug.service.AiCallTraceService.class);
        Mockito.when(traceService.beginScene(Mockito.any(), Mockito.anyString()))
                .thenReturn(Mockito.mock(com.holzyn.actor.domain.debug.service.AiCallTrace.class));
        MemoryVectorService svc = new MemoryVectorService(modelApiService, factory,
                traceService, Mockito.mock(UsageLogService.class),
                Path.of("target", "test-data", "vector-cache-off").toString(), "");
        ReflectionTestUtils.setField(svc, "textCacheEnabled", false);

        svc.addVector(1L, 2L, 200L, "同一句文本");
        svc.addVector(1L, 2L, 201L, "同一句文本");
        Mockito.verify(embModel, Mockito.times(2)).embed(Mockito.anyString());
        assertEquals(0L, svc.vectorStats().get("textCacheHits"));
    }
}
