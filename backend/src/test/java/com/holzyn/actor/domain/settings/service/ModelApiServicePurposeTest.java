package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelApiService 用途分离逻辑单元测试（主 AI 与 embedding 分开配置与使用）。
 * <p>职责：验证 chat / embedding / both 三种用途的解析规则，以及旧数据（无 purpose）
 * 按 embedding_enabled 的兼容判定——保证主 AI 绝不选中 embedding 专用供应商、向量化绝不选中仅主 AI 供应商。</p>
 */
class ModelApiServicePurposeTest {

    /**
     * 构造测试供应商。
     *
     * @param purpose         用途（可空=旧数据）
     * @param embeddingEnabled 旧字段 embedding_enabled（可空）
     * @param embeddingModel   embedding 模型名（可空）
     * @return 供应商实体
     */
    private ModelProvider provider(String purpose, Integer embeddingEnabled, String embeddingModel) {
        ModelProvider p = new ModelProvider();
        p.setPurpose(purpose);
        p.setEmbeddingEnabled(embeddingEnabled);
        p.setEmbeddingModel(embeddingModel);
        return p;
    }

    /**
     * chat 用途：仅主 AI 可用，不可作向量化。
     */
    @Test
    void chatPurposeIsChatOnly() {
        ModelProvider p = provider("chat", 0, null);
        assertTrue(ModelApiService.isChatCapable(p));
        assertFalse(ModelApiService.isEmbeddingCapable(p));
    }

    /**
     * embedding 用途：仅向量化可用，绝不作主 AI（核心隔离）。
     */
    @Test
    void embeddingPurposeIsEmbeddingOnly() {
        ModelProvider p = provider("embedding", 1, "bge-m3");
        assertFalse(ModelApiService.isChatCapable(p), "embedding 专用供应商不得被选为主 AI");
        assertTrue(ModelApiService.isEmbeddingCapable(p));
    }

    /**
     * both 用途：两者兼用（需填 embedding 模型名才可向量化）。
     */
    @Test
    void bothPurposeIsDual() {
        ModelProvider p = provider("both", 1, "bge-m3");
        assertTrue(ModelApiService.isChatCapable(p));
        assertTrue(ModelApiService.isEmbeddingCapable(p));
    }

    /**
     * both 用途但未填 embedding 模型名：向量化不可用（缺模型名）。
     */
    @Test
    void bothWithoutEmbeddingModelCannotEmbed() {
        ModelProvider p = provider("both", 1, null);
        assertTrue(ModelApiService.isChatCapable(p));
        assertFalse(ModelApiService.isEmbeddingCapable(p));
    }

    /**
     * 旧数据（无 purpose）：embedding_enabled=1 → 仅向量化，不可作主 AI。
     */
    @Test
    void legacyEmbeddingEnabledIsEmbeddingOnly() {
        ModelProvider p = provider(null, 1, "text-embedding-3-small");
        assertFalse(ModelApiService.isChatCapable(p), "旧 embedding 专用配置不得被选为主 AI");
        assertTrue(ModelApiService.isEmbeddingCapable(p));
    }

    /**
     * 旧数据（无 purpose）：embedding_enabled=0 → 仅主 AI。
     */
    @Test
    void legacyNoEmbeddingIsChatOnly() {
        ModelProvider p = provider(null, 0, null);
        assertTrue(ModelApiService.isChatCapable(p));
        assertFalse(ModelApiService.isEmbeddingCapable(p));
    }

    /**
     * 旧数据 embedding_enabled=1 但未填 embedding 模型名：向量化不可用。
     */
    @Test
    void legacyEmbeddingWithoutModelCannotEmbed() {
        ModelProvider p = provider(null, 1, null);
        assertFalse(ModelApiService.isEmbeddingCapable(p));
        assertFalse(ModelApiService.isChatCapable(p));
    }
}
