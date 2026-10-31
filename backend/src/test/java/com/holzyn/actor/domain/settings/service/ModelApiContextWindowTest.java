package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.ai.spring.ChatModelProbe;
import com.holzyn.actor.ai.spring.ModelCatalogClient;
import com.holzyn.actor.common.AesCipherService;
import com.holzyn.actor.domain.settings.dto.ProviderModelDTO;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.domain.settings.vo.ModelApiVO;
import com.holzyn.actor.domain.settings.vo.ProviderModelVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型「上下文容量」字段单元测试。
 *
 * <p>职责：锁死三件容易改坏的事——① 新增时写进 models_json；② 编辑时不传=保持、传 0=清空；
 * ③ 越界值被服务层归一化（负数为未知、超上限截断）。该字段是<b>元数据</b>，任何"顺手让它参与
 * 运行时裁剪"的改动都不在这里放行。</p>
 */
class ModelApiContextWindowTest {

    /** 被测服务 */
    private ModelApiService service;

    /** 供应商仓储（mock） */
    private ModelProviderRepository repository;

    /** 当前测试供应商 */
    private ModelProvider provider;

    /**
     * 装配：供应商 1 归属用户 1（用户级，projectId=null），已有 models_json 为空（走旧字段推导）。
     */
    @BeforeEach
    void setUp() {
        repository = mock(ModelProviderRepository.class);
        AesCipherService cipher = mock(AesCipherService.class);
        ChatModelProbe probe = mock(ChatModelProbe.class);
        service = new ModelApiService(repository, cipher, probe);
        // 目录拉取客户端与本用例无关；保持 null（字段注入 required=false），避免误触发 HTTP
        ReflectionTestUtils.setField(service, "modelCatalogClient", (ModelCatalogClient) null);

        provider = new ModelProvider();
        provider.setId(1L);
        provider.setUserId(1L);
        provider.setProjectId(null);
        provider.setName("Local Qwen");
        provider.setBaseUrl("https://llm.example.com/v1");
        provider.setApiKeyCipher("cipher");
        provider.setPurpose("chat");
        provider.setSupportsStream(1);
        provider.setIsDefault(1);
        provider.setEnabled(1);

        when(repository.findByIdAndUserIdAndProjectId(anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(2) == null ? Optional.of(provider) : Optional.empty());
        when(repository.save(any(ModelProvider.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * 新增模型时把上下文容量写进 models_json，并原样回读。
     */
    @Test
    @DisplayName("新增模型：contextWindow 写入 models_json 并回读一致")
    void addModelKeepsContextWindow() {
        ProviderModelDTO dto = new ProviderModelDTO();
        dto.setName("qwen3.6-35b");
        dto.setRole("text");
        dto.setContextWindow(128000);

        ModelApiVO vo = service.addModel(1L, null, 1L, dto);

        assertTrue(provider.getModelsJson().contains("\"contextWindow\":128000"),
                "models_json 应含 contextWindow：实际=" + provider.getModelsJson());
        ProviderModelVO saved = onlyModel(vo);
        assertEquals(Integer.valueOf(128000), saved.getContextWindow());
    }

    /**
     * 编辑时不传 contextWindow（null）= 保持原值；传 0 = 清空。
     */
    @Test
    @DisplayName("编辑模型：null=保持原值，0=清空")
    void updateModelKeepsOrClearsContextWindow() {
        addModelWithContext(64000);
        String modelId = onlyModel(service.list(1L, null).get(0)).getId();

        ProviderModelDTO keep = new ProviderModelDTO();
        keep.setName("qwen3.6-35b");
        keep.setRole("text");
        service.updateModel(1L, null, 1L, modelId, keep);
        assertEquals(Integer.valueOf(64000), onlyModel(service.list(1L, null).get(0)).getContextWindow(),
                "不传 contextWindow 不得清掉既有值");

        ProviderModelDTO clear = new ProviderModelDTO();
        clear.setName("qwen3.6-35b");
        clear.setRole("text");
        clear.setContextWindow(0);
        service.updateModel(1L, null, 1L, modelId, clear);
        assertNull(onlyModel(service.list(1L, null).get(0)).getContextWindow(), "传 0 应清空为未知");
    }

    /**
     * 归一化：负数 / 0 → null（未知），超上限截到上限。
     */
    @Test
    @DisplayName("归一化：0/负数为未知，超上限截断")
    void normalizeContextWindowClamps() {
        assertNull(ModelApiService.normalizeContextWindow(null));
        assertNull(ModelApiService.normalizeContextWindow(0));
        assertNull(ModelApiService.normalizeContextWindow(-1));
        assertEquals(Integer.valueOf(8192), ModelApiService.normalizeContextWindow(8192));
        assertEquals(Integer.valueOf(ModelApiService.MAX_CONTEXT_WINDOW),
                ModelApiService.normalizeContextWindow(99_000_000));
    }

    /** 先加一个有容量的模型（后续编辑用例的基线）。 */
    private void addModelWithContext(int contextWindow) {
        ProviderModelDTO dto = new ProviderModelDTO();
        dto.setName("qwen3.6-35b");
        dto.setRole("text");
        dto.setContextWindow(contextWindow);
        service.addModel(1L, null, 1L, dto);
        when(repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(anyLong(), any()))
                .thenAnswer(inv -> List.of(provider));
    }

    /** 取视图里唯一的模型条目。 */
    private ProviderModelVO onlyModel(ModelApiVO vo) {
        assertEquals(1, vo.getModels().size(), "本用例只应有 1 个模型");
        return vo.getModels().get(0);
    }
}
