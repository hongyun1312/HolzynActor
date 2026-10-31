package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.settings.vo.ProviderModelVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型角色选择逻辑单元测试（文本 / 向量 / 决策）。
 *
 * <p>职责：锁死"每个角色只选自己的模型、默认优先、停用跳过、决策未配置回退文本"的解析规则——
 * 这三条规则一旦被改坏，表现是"设置里明明配了模型，世界却静默用错模型"，很难在页面上发现。</p>
 */
class ModelApiRoleTest {

    /**
     * 造一个模型条目。
     *
     * @param name      模型名
     * @param role      角色
     * @param isDefault 是否默认
     * @param enabled   是否启用
     * @return 模型条目
     */
    private ProviderModelVO model(String name, String role, int isDefault, int enabled) {
        ProviderModelVO m = new ProviderModelVO();
        m.setId("id-" + name + "-" + role);
        m.setName(name);
        m.setRole(role);
        m.setIsDefault(isDefault);
        m.setEnabled(enabled);
        return m;
    }

    /**
     * 角色归一化：空/非法值按文本，大小写不敏感。
     */
    @Test
    void normalizeRoleFallsBackToText() {
        assertEquals("text", ModelApiService.normalizeRole(null));
        assertEquals("text", ModelApiService.normalizeRole(" "));
        assertEquals("text", ModelApiService.normalizeRole("chat"));
        assertEquals("embedding", ModelApiService.normalizeRole("EMBEDDING"));
        assertEquals("decision", ModelApiService.normalizeRole(" Decision "));
    }

    /**
     * 默认模型优先：同角色有默认时取默认，而不是列表第一个。
     */
    @Test
    void defaultModelWinsWithinRole() {
        List<ProviderModelVO> models = List.of(
                model("first-text", "text", 0, 1),
                model("default-text", "text", 1, 1),
                model("emb", "embedding", 1, 1));
        assertEquals("default-text", ModelApiService.pickModelName(models, "text"));
        assertEquals("emb", ModelApiService.pickModelName(models, "embedding"));
    }

    /**
     * 停用模型不参与选择；全部停用则视为该角色不可用。
     */
    @Test
    void disabledModelIsSkipped() {
        List<ProviderModelVO> models = List.of(
                model("off-text", "text", 1, 0),
                model("on-text", "text", 0, 1));
        assertEquals("on-text", ModelApiService.pickModelName(models, "text"));
        assertNull(ModelApiService.pickModelName(List.of(model("off", "text", 1, 0)), "text"));
    }

    /**
     * 角色隔离：决策模型不会被当作文本模型。
     */
    @Test
    void rolesAreIsolated() {
        List<ProviderModelVO> models = List.of(
                model("chat", "text", 1, 1),
                model("decide", "decision", 1, 1));
        assertEquals("chat", ModelApiService.pickModelName(models, "text"));
        assertEquals("decide", ModelApiService.pickModelName(models, "decision"));
        assertNull(ModelApiService.pickModelName(models, "embedding"));
    }

    /**
     * 决策未配置：pick 返回 null（由 ModelApiService 决定回退文本模型）。
     */
    @Test
    void decisionMissingReturnsNull() {
        List<ProviderModelVO> models = List.of(model("chat", "text", 1, 1));
        assertNull(ModelApiService.pickModelName(models, "decision"));
    }

    /**
     * 角色中文名（错误提示用）。
     */
    @Test
    void roleLabelsAreChinese() {
        assertEquals("文本模型", ModelApiService.roleLabel("text"));
        assertEquals("向量模型", ModelApiService.roleLabel("embedding"));
        assertEquals("决策模型", ModelApiService.roleLabel("decision"));
    }

    /**
     * 旧库兼容：purpose=chat + model → 推导出一个默认文本模型（升级后无需回填即可对话）。
     */
    @Test
    void legacyChatProviderDerivesTextModel() {
        ModelProvider p = new ModelProvider();
        p.setModel("gpt-4o-mini");
        p.setPurpose("chat");
        p.setEmbeddingEnabled(0);
        List<ProviderModelVO> models = ModelApiService.legacyModels(p);
        assertEquals(1, models.size());
        assertEquals("gpt-4o-mini", models.get(0).getName());
        assertEquals("text", models.get(0).getRole());
        assertEquals(1, models.get(0).getIsDefault());
    }

    /**
     * 旧库兼容：purpose=both → 文本 + 向量两条模型（embeddingModel 非空才算向量可用）。
     */
    @Test
    void legacyBothProviderDerivesTextAndEmbedding() {
        ModelProvider p = new ModelProvider();
        p.setModel("deepseek-chat");
        p.setPurpose("both");
        p.setEmbeddingEnabled(1);
        p.setEmbeddingModel("bge-m3");
        List<ProviderModelVO> models = ModelApiService.legacyModels(p);
        assertEquals(2, models.size());
        assertEquals("text", models.get(0).getRole());
        assertEquals("embedding", models.get(1).getRole());
        assertEquals("bge-m3", models.get(1).getName());
    }

    /**
     * 旧库兼容：purpose=embedding 只推导向量模型——绝不能把 embedding 供应商当文本模型用。
     */
    @Test
    void legacyEmbeddingOnlyProviderDerivesOnlyEmbedding() {
        ModelProvider p = new ModelProvider();
        p.setPurpose("embedding");
        p.setEmbeddingEnabled(1);
        p.setEmbeddingModel("text-embedding-3-small");
        List<ProviderModelVO> models = ModelApiService.legacyModels(p);
        assertEquals(1, models.size());
        assertEquals("embedding", models.get(0).getRole());
        assertNull(ModelApiService.pickModelName(models, "text"));
    }

    /**
     * 旧库兼容：purpose=embedding 但 model 字段有残值（旧前端误填）时，不得兜底成文本模型。
     */
    @Test
    void legacyEmbeddingPurposeNeverFallsBackToText() {
        ModelProvider p = new ModelProvider();
        p.setPurpose("embedding");
        p.setEmbeddingEnabled(1);
        p.setModel("some-chat-model");
        assertTrue(ModelApiService.legacyModels(p).isEmpty());
    }
}
