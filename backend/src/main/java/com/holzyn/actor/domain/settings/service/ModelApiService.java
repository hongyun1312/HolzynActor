package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.dto.ModelApiDTO;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.settings.vo.ModelApiVO;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.OpenAiCompatibleProvider;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.common.AesCipherService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 用户级/项目级 AI 模型 API 配置服务（后端项目化改造 V2.0）。
 * <p>职责：承载 /api/model-apis 全部业务逻辑——按「用户 + 项目」隔离的增删改查、
 * api_key AES 加密入库/解密供调用、列表脱敏、默认 API 互斥维护、连通性测试，
 * 以及向 AiProviderRouter 提供「运行时凭据解析」（解密 Key）。</p>
 * <p>项目化归属（V2.0）：配置支持两级——项目级（project_id 非空，随 .holzyn 包导入导出）
 * 与用户级（project_id NULL，作为未配置项目级时的回退默认）。运行时解析规则：
 * 「项目级优先、用户级回退」——先查项目级（project_id=当前项目），未命中再查用户级（project_id IS NULL），
 * 保证旧数据（用户级 API）无需迁移即可继续驱动 AI。</p>
 * <p>安全约束：所有按主键的操作均以 id + userId（+projectId）双重条件定位（防越权）；
 * api_key 明文仅存在于服务端内存（解密后立即使用），永不下发前端。</p>
 * <p>所属模块：service/settings（用户设置子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelApiService {

    /** 归属用户默认值（演示模式兜底用户 ID） */
    private static final long DEMO_USER_ID = 1L;

    private final ModelProviderRepository repository;
    private final AesCipherService aesCipherService;
    private final OpenAiCompatibleProvider openAiCompatibleProvider;

    /**
     * 查询某归属（用户 + 项目，projectId 空=用户级）的全部 API 列表（脱敏后返回）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @return 脱敏后的 API 视图列表
     */
    public List<ModelApiVO> list(Long userId, Long projectId) {
        return repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, projectId).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 新增 API 配置。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param dto       请求体（apiKey 必填）
     * @return 新增后的视图对象
     */
    @Transactional
    public ModelApiVO create(Long userId, Long projectId, ModelApiDTO dto) {
        if (dto.getApiKey() == null || dto.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        ModelProvider entity = new ModelProvider();
        entity.setUserId(userId);
        entity.setProjectId(projectId);
        entity.setName(dto.getName());
        entity.setBaseUrl(dto.getBaseUrl().trim());
        // API Key 立即 AES 加密入库，杜绝明文落库
        entity.setApiKeyCipher(aesCipherService.encrypt(dto.getApiKey().trim()));
        entity.setModel(dto.getModel());
        entity.setSupportsStream(Boolean.TRUE.equals(dto.getSupportsStream()) ? 1 : 0);
        // 用途类型（默认主 AI 对话；embedding/both 时同步 embedding_enabled 与 embedding_model）
        String purpose = normalizePurpose(dto.getPurpose());
        entity.setPurpose(purpose);
        entity.setEmbeddingEnabled(isEmbeddingPurpose(purpose) ? 1 : 0);
        entity.setEmbeddingModel(dto.getEmbeddingModel());
        entity.setRemark(dto.getRemark());
        entity.setIsDefault(0);
        entity.setPriority(0);
        entity.setEnabled(1);
        return toVO(repository.save(entity));
    }

    /**
     * 编辑 API 配置（apiKey 传空=保持原 Key 不变）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param id        配置主键
     * @param dto       请求体
     * @return 更新后的视图对象
     */
    @Transactional
    public ModelApiVO update(Long userId, Long projectId, Long id, ModelApiDTO dto) {
        ModelProvider entity = requireOwned(userId, projectId, id);
        entity.setName(dto.getName());
        entity.setBaseUrl(dto.getBaseUrl().trim());
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            // 用户填写了新 Key：重新加密替换
            entity.setApiKeyCipher(aesCipherService.encrypt(dto.getApiKey().trim()));
        }
        entity.setModel(dto.getModel());
        if (dto.getSupportsStream() != null) {
            entity.setSupportsStream(Boolean.TRUE.equals(dto.getSupportsStream()) ? 1 : 0);
        }
        // 用途变更时同步 embedding_enabled（旧前端传 embeddingEnabled 也兼容）
        if (dto.getPurpose() != null && !dto.getPurpose().isBlank()) {
            String purpose = normalizePurpose(dto.getPurpose());
            entity.setPurpose(purpose);
            entity.setEmbeddingEnabled(isEmbeddingPurpose(purpose) ? 1 : 0);
        } else if (dto.getEmbeddingEnabled() != null) {
            // 兼容旧前端：仅切换 embedding_enabled，purpose 未显式给时保持旧逻辑
            entity.setEmbeddingEnabled(Boolean.TRUE.equals(dto.getEmbeddingEnabled()) ? 1 : 0);
        }
        if (dto.getEmbeddingModel() != null) {
            entity.setEmbeddingModel(dto.getEmbeddingModel());
        }
        entity.setRemark(dto.getRemark());
        return toVO(repository.save(entity));
    }

    /**
     * 删除 API 配置（归属校验，返回 404 当不存在或无权限）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param id        配置主键
     */
    @Transactional
    public void delete(Long userId, Long projectId, Long id) {
        long deleted = repository.deleteByIdAndUserIdAndProjectId(id, userId, projectId);
        if (deleted == 0) {
            throw new EntityNotFoundException("API 配置不存在");
        }
    }

    /**
     * 设为默认（同归属内互斥：先清空再置位，事务保证原子性）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param id        配置主键
     * @return 置为默认后的视图对象
     */
    @Transactional
    public ModelApiVO setDefault(Long userId, Long projectId, Long id) {
        ModelProvider entity = requireOwned(userId, projectId, id);
        // 先取消该归属所有 API 的默认标记，再置目标为默认（保证唯一）
        List<ModelProvider> defaults = repository.findByUserIdAndProjectIdAndIsDefault(userId, projectId, 1);
        defaults.forEach(d -> d.setIsDefault(0));
        repository.saveAll(defaults);
        entity.setIsDefault(1);
        return toVO(repository.save(entity));
    }

    /**
     * 查询某归属的默认 API（未设置时返回 null）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @return 默认 API 视图对象或 null
     */
    public ModelApiVO getDefault(Long userId, Long projectId) {
        return repository.findByUserIdAndProjectIdAndIsDefault(userId, projectId, 1).stream()
                .findFirst()
                .map(this::toVO)
                .orElse(null);
    }

    /**
     * 未保存前连通性测试（新增表单内测试：使用用户输入的明文 Key，不入库）。
     *
     * @param dto 请求体（含 baseUrl/apiKey/model）
     * @return 测试结果 Map
     */
    public Map<String, Object> testConnection(ModelApiDTO dto) {
        if (dto.getApiKey() == null || dto.getApiKey().isBlank()) {
            throw new IllegalArgumentException("请先填写 API Key 再测试连接");
        }
        return openAiCompatibleProvider.testConnection(dto.getBaseUrl(), dto.getApiKey().trim(), dto.getModel());
    }

    /**
     * 已保存配置连通性测试（使用解密后的真实 Key）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param id        配置主键
     * @return 测试结果 Map
     */
    public Map<String, Object> testConnection(Long userId, Long projectId, Long id) {
        ProviderConfig config = resolveProviderConfig(userId, projectId, id);
        return openAiCompatibleProvider.testConnection(config.baseUrl(), config.apiKey(), config.model());
    }

    /**
     * 解析运行时调用凭据（AiProviderRouter 入口，主 AI 场景：对话/角色卡/行动/事件/群聊）。
     * <p>规则：显式指定 providerId 时先按「用户+项目」定位，未命中再按「用户+用户级」定位（项目可显式使用用户级 API）；
     * 未指定时「项目级优先、用户级回退」——从项目级（project_id=projectId）取默认 chat 用途 →
     * 回退用户级（project_id IS NULL）取默认 → 依次取各自优先级最高且启用的 chat 用途；
     * <b>绝不会选中 embedding 专用供应商</b>（分开配置与使用）。</p>
     *
     * @param userId     归属用户 ID
     * @param projectId  项目 ID（NULL=仅用户级）
     * @param providerId 配置主键（可空）
     * @return 含解密 Key 的运行时配置
     */
    public ProviderConfig resolveProviderConfig(Long userId, Long projectId, Long providerId) {
        ModelProvider entity;
        if (providerId != null) {
            // 显式指定：先项目级，再用户级（允许项目显式使用用户级 API）
            entity = repository.findByIdAndUserIdAndProjectId(providerId, userId, projectId)
                    .or(() -> repository.findByIdAndUserIdAndProjectId(providerId, userId, null))
                    .orElseThrow(() -> new EntityNotFoundException("API 配置不存在"));
        } else {
            entity = resolveDefaultForChat(userId, projectId);
            if (entity == null) {
                throw new AiCallException("未配置可用的主 AI API：请先在「设置-API 配置」添加并启用对话用途的 API（项目级或用户级）");
            }
        }
        String apiKey = aesCipherService.decrypt(entity.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiCallException("API Key 解密失败，请编辑该 API 重新填写 Key");
        }
        return new ProviderConfig(entity.getId(), entity.getBaseUrl(), apiKey, entity.getModel(),
                Integer.valueOf(1).equals(entity.getSupportsStream()));
    }

    /**
     * 解析该用户启用的 embedding 供应商运行时凭据（P3 RAG，向量化专用）。
     * <p>规则：仅从「向量化用途（embedding/both）且已填 embedding 模型名」的启用的配置中，
     * 按「项目级优先、用户级回退」取优先级最高；<b>绝不会选中仅主 AI（chat）的供应商</b>。
     * 未配置返回 null（调用方降级文本检索 + 提示）。</p>
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=仅用户级）
     * @return 含解密 Key 的运行时配置（无则 null）
     */
    public ProviderConfig resolveEmbeddingProvider(Long userId, Long projectId) {
        ModelProvider entity = repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, projectId).stream()
                .filter(p -> Integer.valueOf(1).equals(p.getEnabled()))
                .filter(ModelApiService::isEmbeddingCapable)
                .findFirst()
                .or(() -> repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, null).stream()
                        .filter(p -> Integer.valueOf(1).equals(p.getEnabled()))
                        .filter(ModelApiService::isEmbeddingCapable)
                        .findFirst())
                .orElse(null);
        if (entity == null) {
            return null;
        }
        String apiKey = aesCipherService.decrypt(entity.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        // embedding 场景复用 baseUrl/apiKey，模型名取 embeddingModel
        return new ProviderConfig(entity.getId(), entity.getBaseUrl(), apiKey, entity.getEmbeddingModel(),
                false);
    }

    /**
     * 解析主 AI 默认供应商（项目级优先、用户级回退）。
     * <p>步骤：① 项目级取默认 chat 用途 → ② 项目级取优先级最高且启用的 chat 用途 →
     * ③ 用户级取默认 chat 用途 → ④ 用户级取优先级最高且启用的 chat 用途。</p>
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=仅用户级）
     * @return 选中的供应商（无则 null）
     */
    private ModelProvider resolveDefaultForChat(Long userId, Long projectId) {
        // ① 项目级默认
        if (projectId != null) {
            ModelProvider projectDefault = repository.findByUserIdAndProjectIdAndIsDefault(userId, projectId, 1).stream()
                    .filter(ModelApiService::isChatCapable)
                    .findFirst().orElse(null);
            if (projectDefault != null) {
                return projectDefault;
            }
            // ② 项目级优先级最高且启用
            ModelProvider projectTop = repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, projectId).stream()
                    .filter(p -> Integer.valueOf(1).equals(p.getEnabled()))
                    .filter(ModelApiService::isChatCapable)
                    .findFirst().orElse(null);
            if (projectTop != null) {
                return projectTop;
            }
        }
        // ③ 用户级默认
        ModelProvider userDefault = repository.findByUserIdAndProjectIdAndIsDefault(userId, null, 1).stream()
                .filter(ModelApiService::isChatCapable)
                .findFirst().orElse(null);
        if (userDefault != null) {
            return userDefault;
        }
        // ④ 用户级优先级最高且启用
        return repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, null).stream()
                .filter(p -> Integer.valueOf(1).equals(p.getEnabled()))
                .filter(ModelApiService::isChatCapable)
                .findFirst().orElse(null);
    }

    /**
     * 判断供应商是否可作主 AI（对话等）用途（静态纯逻辑，可单测）。
     * <p>规则：显式 purpose 为 chat/both 可用；旧数据（purpose 空）按 embedding_enabled 判定
     * （embedding_enabled=1 视为 embedding 专用，不可作主 AI）。</p>
     *
     * @param p 供应商实体
     * @return true 表示可用作主 AI
     */
    static boolean isChatCapable(ModelProvider p) {
        String purpose = p.getPurpose();
        if (purpose == null || purpose.isBlank()) {
            // 旧数据：embedding 专用不算主 AI
            return !Integer.valueOf(1).equals(p.getEmbeddingEnabled());
        }
        return "chat".equals(purpose) || "both".equals(purpose);
    }

    /**
     * 判断供应商是否可作 embedding（向量化）用途（静态纯逻辑，可单测）。
     * <p>规则：必须已填 embedding 模型名；显式 purpose 为 embedding/both 可用；
     * 旧数据（purpose 空）按 embedding_enabled=1 判定。</p>
     *
     * @param p 供应商实体
     * @return true 表示可用作 embedding
     */
    static boolean isEmbeddingCapable(ModelProvider p) {
        if (p.getEmbeddingModel() == null || p.getEmbeddingModel().isBlank()) {
            return false;
        }
        String purpose = p.getPurpose();
        if (purpose == null || purpose.isBlank()) {
            return Integer.valueOf(1).equals(p.getEmbeddingEnabled());
        }
        return "embedding".equals(purpose) || "both".equals(purpose);
    }

    /**
     * 归一化用途类型（chat/embedding/both，缺省 chat）。
     *
     * @param purpose 原始用途（可空）
     * @return 归一化后的用途
     */
    private String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return "chat";
        }
        String p = purpose.trim().toLowerCase();
        if ("embedding".equals(p) || "both".equals(p)) {
            return p;
        }
        return "chat";
    }

    /**
     * 用途是否为向量化相关（embedding/both）。
     *
     * @param purpose 归一化后的用途
     * @return true 表示含 embedding 能力
     */
    private boolean isEmbeddingPurpose(String purpose) {
        return "embedding".equals(purpose) || "both".equals(purpose);
    }

    /**
     * 按 id+userId+projectId 查询归属实体（不存在或越权时抛出 404）。
     * 先按项目级定位，未命中回退用户级（允许对用户级 API 操作）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param id        主键
     * @return 归属实体
     */
    private ModelProvider requireOwned(Long userId, Long projectId, Long id) {
        return repository.findByIdAndUserIdAndProjectId(id, userId, projectId)
                .or(() -> repository.findByIdAndUserIdAndProjectId(id, userId, null))
                .orElseThrow(() -> new EntityNotFoundException("API 配置不存在"));
    }

    /**
     * 实体转视图对象：api_key 解密后取末 4 位脱敏展示（明文不落 VO）。
     *
     * @param entity 实体
     * @return 视图对象
     */
    private ModelApiVO toVO(ModelProvider entity) {
        ModelApiVO vo = new ModelApiVO();
        vo.setId(entity.getId());
        vo.setProjectId(entity.getProjectId());
        vo.setName(entity.getName());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setModel(entity.getModel());
        vo.setPurpose(entity.getPurpose());
        vo.setSupportsStream(entity.getSupportsStream());
        vo.setIsDefault(entity.getIsDefault());
        vo.setEnabled(entity.getEnabled());
        vo.setEmbeddingEnabled(entity.getEmbeddingEnabled());
        vo.setEmbeddingModel(entity.getEmbeddingModel());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        // 脱敏：仅保留明文末 4 位（解密仅在服务端内存完成）
        String plain = aesCipherService.decrypt(entity.getApiKeyCipher());
        vo.setApiKeyMasked(maskKey(plain));
        return vo;
    }

    /**
     * 生成 Key 脱敏串（**** 尾 4 位）。
     *
     * @param plain 明文 Key（可为空）
     * @return 脱敏串或 null
     */
    private String maskKey(String plain) {
        if (plain == null || plain.isBlank()) return null;
        int tail = Math.min(4, plain.length());
        return "****" + plain.substring(plain.length() - tail);
    }
}
