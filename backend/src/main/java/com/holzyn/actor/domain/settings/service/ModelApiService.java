/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.dto.ModelApiDTO;
import com.holzyn.actor.domain.settings.dto.ProviderModelDTO;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.settings.vo.ModelApiVO;
import com.holzyn.actor.domain.settings.vo.ProviderModelVO;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.spring.ChatModelProbe;
import com.holzyn.actor.ai.spring.ModelCatalogClient;
import com.holzyn.actor.common.AesCipherService;
import com.holzyn.actor.common.BizException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户级/项目级 AI 模型 API 配置服务。
 * <p>职责：承载 /api/model-apis 全部业务逻辑——按「用户 + 项目」隔离的增删改查、
 * api_key AES 加密入库/解密供调用、列表脱敏、默认 API 互斥维护、连通性测试，
 * 以及向 AiSpringChatGateway 提供「运行时凭据解析」（解密 Key）。</p>
 * <p>项目化归属：配置支持两级——项目级（project_id 非空，随 .holzyn 包导入导出）
 * 与用户级（project_id NULL，作为未配置项目级时的回退默认）。运行时解析规则：
 * 「项目级优先、用户级回退」——先查项目级（project_id=当前项目），未命中再查用户级（project_id IS NULL），
 * 保证旧数据（用户级 API）无需迁移即可继续驱动 AI。</p>
 * <p>安全约束：所有按主键的操作均以 id + userId（+projectId）双重条件定位（防越权）；
 * api_key 明文仅存在于服务端内存（解密后立即使用），永不下发前端。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelApiService {

    /** 角色：文本模型（对话 / 角色卡 / 生成） */
    public static final String ROLE_TEXT = "text";

    /** 角色：向量模型（RAG / 记忆向量化） */
    public static final String ROLE_EMBEDDING = "embedding";

    /** 角色：决策模型（实时决策 / 判定类文本调用；未配置时回退文本模型） */
    public static final String ROLE_DECISION = "decision";

    /** 合法角色（顺序即前端展示顺序） */
    public static final List<String> ROLES = List.of(ROLE_TEXT, ROLE_EMBEDDING, ROLE_DECISION);

    /** 归属用户默认值（演示模式兜底用户 ID） */
    private static final long DEMO_USER_ID = 1L;

    /** models_json 序列化（配置体量小，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelProviderRepository repository;
    private final AesCipherService aesCipherService;
    /** AI 供应商连通性探测 */
    private final ChatModelProbe chatModelProbe;

    /**
     * 供应商模型目录拉取（{@code GET /models}）。
     *
     * <p>字段注入而非进构造器：本类是既有服务，构造器签名不动（AGENTS §8.1.2）；
     * {@code required=false} 让只测静态判定的单测不必装配 HTTP 客户端。</p>
     */
    @Autowired(required = false)
    private ModelCatalogClient modelCatalogClient;

    /**
     * 查询某归属（用户 + 项目，projectId 空=用户级）的全部 API 列表（脱敏后返回）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @return 脱敏后的 API 视图列表
     */
    public List<ModelApiVO> list(Long userId, Long projectId) {
        return repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, projectId).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * API 配置。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @param dto 请求体（apiKey 必填）
     *
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
        entity.setSupportsStream(Boolean.TRUE.equals(dto.getSupportsStream()) ? 1 : 0);
        entity.setRemark(dto.getRemark());
        entity.setIsDefault(0);
        entity.setPriority(0);
        entity.setEnabled(1);
        // 兼容"只传单个模型名"的旧调用（首启向导 / 脚本）：按角色播种，建完即可用
        List<ProviderModelVO> seeds = seedModels(dto);
        if (!seeds.isEmpty()) {
            persistModels(entity, seeds);
        } else {
            entity.setPurpose(normalizePurpose(dto.getPurpose()));
            entity.setEmbeddingEnabled(0);
        }
        return toVO(repository.save(entity));
    }

    /**
     * 编辑 API 配置（apiKey 传空=保持原 Key 不变）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @param id 配置主键
     *
     * @param dto 请求体
     *
     * @return 更新后的视图对象
     */
    @Transactional
    public ModelApiVO update(Long userId, Long projectId, Long id, ModelApiDTO dto) {
        ModelProvider entity = requireOwned(userId, projectId, id);
        // 先把旧字段物化成模型清单，再只改供应商级字段：这样编辑供应商（改名/换 Key）
        // 不会把已有模型清空；旧数据在第一次编辑时顺带完成迁移。
        List<ProviderModelVO> models = effectiveModels(entity);
        entity.setName(dto.getName());
        entity.setBaseUrl(dto.getBaseUrl().trim());
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            // 用户填写了新 Key：重新加密替换
            entity.setApiKeyCipher(aesCipherService.encrypt(dto.getApiKey().trim()));
        }
        if (dto.getSupportsStream() != null) {
            entity.setSupportsStream(Boolean.TRUE.equals(dto.getSupportsStream()) ? 1 : 0);
        }
        entity.setRemark(dto.getRemark());
        persistModels(entity, models);
        return toVO(repository.save(entity));
    }

    /**
     * 删除 API 配置（归属校验，返回 404 当不存在或无权限）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @param id 配置主键
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
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @param id 配置主键
     *
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
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @return 默认 API 视图对象或 null
     */
    public ModelApiVO getDefault(Long userId, Long projectId) {
        return repository.findByUserIdAndProjectIdAndIsDefault(userId, projectId, 1).stream()
                .findFirst()
                .map(this::toVO)
                .orElse(null);
    }

    // ==================== 供应商下的模型（文本 / 向量 / 决策） ====================

    /**
     * 在供应商下新增一个模型。
     *
     * <p>默认模型规则：显式 isDefault=true，或该角色下还没有任何默认模型时，自动成为该角色默认；
     * 同一供应商同一角色至多一个默认（新增默认时清掉其它默认）。</p>
     *
     * @param userId     归属用户
     * @param projectId  项目（NULL=用户级）
     * @param providerId 供应商主键
     * @param dto        模型请求体
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @Transactional
    public ModelApiVO addModel(Long userId, Long projectId, Long providerId, ProviderModelDTO dto) {
        ModelProvider entity = requireOwned(userId, projectId, providerId);
        List<ProviderModelVO> models = new ArrayList<>(effectiveModels(entity));
        String role = normalizeRole(dto.getRole());
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        requireNoDuplicate(models, role, name, null);
        ProviderModelVO model = new ProviderModelVO();
        model.setId(UUID.randomUUID().toString());
        model.setName(name);
        model.setRole(role);
        model.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
        model.setRemark(dto.getRemark());
        model.setContextWindow(normalizeContextWindow(dto.getContextWindow()));
        boolean makeDefault = Boolean.TRUE.equals(dto.getIsDefault())
                || models.stream().noneMatch(m -> role.equals(normalizeRole(m.getRole()))
                && Integer.valueOf(1).equals(m.getIsDefault()));
        if (makeDefault) {
            clearRoleDefault(models, role);
        }
        model.setIsDefault(makeDefault ? 1 : 0);
        models.add(model);
        persistModels(entity, models);
        return toVO(repository.save(entity));
    }

    /**
     * 修改供应商下的一个模型（可改名 / 改角色 / 改默认 / 启停）。
     *
     * @param userId     归属用户
     * @param projectId  项目（NULL=用户级）
     * @param providerId 供应商主键
     * @param modelId    模型条目 id
     * @param dto        模型请求体
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @Transactional
    public ModelApiVO updateModel(Long userId, Long projectId, Long providerId, String modelId,
                                  ProviderModelDTO dto) {
        ModelProvider entity = requireOwned(userId, projectId, providerId);
        List<ProviderModelVO> models = new ArrayList<>(effectiveModels(entity));
        ProviderModelVO model = findModel(models, modelId)
                .orElseThrow(() -> new EntityNotFoundException("模型不存在"));
        String role = normalizeRole(dto.getRole());
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        requireNoDuplicate(models, role, name, modelId);
        boolean roleChanged = !role.equals(normalizeRole(model.getRole()));
        model.setName(name);
        model.setRole(role);
        if (dto.getEnabled() != null) {
            model.setEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        }
        if (dto.getRemark() != null) {
            model.setRemark(dto.getRemark());
        }
        // 上下文容量：null=保持原值不变，0/负数=清空（前端"清空"就发 0）
        if (dto.getContextWindow() != null) {
            model.setContextWindow(normalizeContextWindow(dto.getContextWindow()));
        }
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            clearRoleDefault(models, role);
            model.setIsDefault(1);
        } else if (Boolean.FALSE.equals(dto.getIsDefault())) {
            // 用户显式取消默认：清 0 后由 ensureRoleDefault 在必要时提级其它模型
            model.setIsDefault(0);
        } else if (roleChanged && Integer.valueOf(1).equals(model.getIsDefault())) {
            model.setIsDefault(0);
        }
        ensureRoleDefault(models, role);
        persistModels(entity, models);
        return toVO(repository.save(entity));
    }

    /**
     * 删除供应商下的一个模型（若删掉的是默认模型，自动把该角色第一个启用模型提为默认）。
     *
     * @param userId     归属用户
     * @param projectId  项目（NULL=用户级）
     * @param providerId 供应商主键
     * @param modelId    模型条目 id
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @Transactional
    public ModelApiVO deleteModel(Long userId, Long projectId, Long providerId, String modelId) {
        ModelProvider entity = requireOwned(userId, projectId, providerId);
        List<ProviderModelVO> models = new ArrayList<>(effectiveModels(entity));
        ProviderModelVO removed = findModel(models, modelId)
                .orElseThrow(() -> new EntityNotFoundException("模型不存在"));
        models.remove(removed);
        ensureRoleDefault(models, normalizeRole(removed.getRole()));
        persistModels(entity, models);
        return toVO(repository.save(entity));
    }

    /**
     * 把某个模型设为它所属角色的默认模型（同角色互斥）。
     *
     * @param userId     归属用户
     * @param projectId  项目（NULL=用户级）
     * @param providerId 供应商主键
     * @param modelId    模型条目 id
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @Transactional
    public ModelApiVO setDefaultModel(Long userId, Long projectId, Long providerId, String modelId) {
        ModelProvider entity = requireOwned(userId, projectId, providerId);
        List<ProviderModelVO> models = new ArrayList<>(effectiveModels(entity));
        ProviderModelVO model = findModel(models, modelId)
                .orElseThrow(() -> new EntityNotFoundException("模型不存在"));
        String role = normalizeRole(model.getRole());
        clearRoleDefault(models, role);
        model.setIsDefault(1);
        persistModels(entity, models);
        return toVO(repository.save(entity));
    }

    /**
     * 拉取某供应商接口上的全部模型（用已保存的 Key）。
     *
     * @param userId     归属用户
     * @param projectId  项目（NULL=用户级）
     * @param providerId 供应商主键
     *
     * @return 模型 ID 列表（排序去重）
     */
    public List<String> fetchRemoteModels(Long userId, Long projectId, Long providerId) {
        ModelProvider entity = requireOwned(userId, projectId, providerId);
        String apiKey = aesCipherService.decrypt(entity.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(400, "该供应商的 API Key 无法解密，请重新填写 Key");
        }
        return fetchRemoteModels(entity.getBaseUrl(), apiKey);
    }

    /**
     * 拉取模型清单（未保存的供应商表单：用用户刚输入的 Base URL / Key）。
     *
     * @param baseUrl 供应商 Base URL
     * @param apiKey  明文 Key（只在服务端内存中使用）
     *
     * @return 模型 ID 列表（排序去重）
     */
    public List<String> fetchRemoteModels(String baseUrl, String apiKey) {
        if (modelCatalogClient == null) {
            throw new BizException(503, "模型目录客户端未就绪（ModelCatalogClient 未注入）");
        }
        return modelCatalogClient.listModels(baseUrl, apiKey);
    }

    /**
     * 测试供应商下某个具体模型（而不是供应商的默认文本模型）。
     *
     * @param userId     归属用户
     * @param projectId  项目（NULL=用户级）
     * @param providerId 供应商主键
     * @param modelId    模型条目 id
     *
     * @return 探测结果 Map
     */
    public Map<String, Object> testProviderModel(Long userId, Long projectId, Long providerId, String modelId) {
        ModelProvider entity = requireOwned(userId, projectId, providerId);
        ProviderModelVO model = findModel(effectiveModels(entity), modelId)
                .orElseThrow(() -> new EntityNotFoundException("模型不存在"));
        String apiKey = aesCipherService.decrypt(entity.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiCallException("API Key 解密失败，请编辑该供应商重新填写 Key");
        }
        ProviderConfig config = new ProviderConfig(entity.getId(), entity.getBaseUrl(), apiKey,
                model.getName(), Integer.valueOf(1).equals(entity.getSupportsStream()));
        return chatModelProbe.testConnection(config);
    }

    /**
     * 未保存前连通性测试（新增表单内测试：使用用户输入的明文 Key，不入库）。
     *
     * @param dto 请求体（含 baseUrl/apiKey/model）
     *
     * @return 测试结果 Map
     */
    public Map<String, Object> testConnection(ModelApiDTO dto) {
        if (dto.getApiKey() == null || dto.getApiKey().isBlank()) {
            throw new IllegalArgumentException("请先填写 API Key 再测试连接");
        }
        // 未保存配置（id=null）：ChatModelProbe 直接构建模型探测（不进动态工厂缓存）
        ProviderConfig config = new ProviderConfig(null, dto.getBaseUrl().trim(),
                dto.getApiKey().trim(), dto.getModel(), false);
        return chatModelProbe.testConnection(config);
    }

    /**
     * 已保存配置连通性测试（使用解密后的真实 Key）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @param id 配置主键
     *
     * @return 测试结果 Map
     */
    public Map<String, Object> testConnection(Long userId, Long projectId, Long id) {
        ProviderConfig config = resolveProviderConfig(userId, projectId, id);
        return chatModelProbe.testConnection(config);
    }

    /**
     * 解析运行时调用凭据（AiSpringChatGateway 入口，主 AI 场景：对话/角色卡/行动/事件/群聊）。
     * <p>规则：显式指定 providerId 时先按「用户+项目」定位，未命中再按「用户+用户级」定位（项目可显式使用用户级 API）；
     * 未指定时「项目级优先、用户级回退」——从项目级（project_id=projectId）取默认 chat 用途 →
     * 回退用户级（project_id IS NULL）取默认 → 依次取各自优先级最高且启用的 chat 用途；
     * <b>绝不会选中 embedding 专用供应商</b>（分开配置与使用）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级）
     *
     * @param providerId 配置主键（可空）
     *
     * @return 含解密 Key 的运行时配置
     */
    public ProviderConfig resolveProviderConfig(Long userId, Long projectId, Long providerId) {
        return resolveProviderConfig(userId, projectId, providerId, ROLE_TEXT);
    }

    /**
     * 解析指定角色的运行时凭据（AiSpringChatGateway 入口）。
     *
     * <p><b>角色语义</b>：text=主 AI（对话/角色卡/行动/事件）；decision=实时决策/判定类文本调用，
     * 供应商没有配决策模型时自动回退该供应商的文本模型；embedding 走
     * {@link #resolveEmbeddingProvider}（向量化是独立调用链，不复用 chat provider）。</p>
     *
     * <p>供应商选择：显式 providerId（项目级→用户级）优先；否则按「项目默认 → 项目优先 → 用户默认 →
     * 用户优先」找第一个具备该角色模型的启用供应商。模型选择：该角色默认模型 → 该角色第一个启用模型。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级）
     *
     * @param providerId 配置主键（可空）
     *
     * @param role 角色（text / decision；非法值按 text）
     *
     * @return 含解密 Key 的运行时配置
     */
    public ProviderConfig resolveProviderConfig(Long userId, Long projectId, Long providerId, String role) {
        String wanted = normalizeRole(role);
        ModelProvider entity;
        if (providerId != null) {
            // 显式指定：先项目级，再用户级（允许项目显式使用用户级 API）
            entity = repository.findByIdAndUserIdAndProjectId(providerId, userId, projectId)
                    .or(() -> repository.findByIdAndUserIdAndProjectId(providerId, userId, null))
                    .orElseThrow(() -> new EntityNotFoundException("API 配置不存在"));
        } else {
            entity = resolveDefaultForRole(userId, projectId, wanted);
        }
        String model = modelNameFor(entity, wanted);
        if (model == null || model.isBlank()) {
            throw new AiCallException("供应商「" + entity.getName() + "」下没有可用的" + roleLabel(wanted)
                    + "，请先在「设置-API 配置」里为该供应商添加模型");
        }
        String apiKey = aesCipherService.decrypt(entity.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiCallException("API Key 解密失败，请编辑该 API 重新填写 Key");
        }
        return new ProviderConfig(entity.getId(), entity.getBaseUrl(), apiKey, model,
                Integer.valueOf(1).equals(entity.getSupportsStream()));
    }

    /**
     * 解析该用户启用的 embedding 供应商运行时凭据。
     * <p>规则：只在「有启用的向量模型」的供应商里选，按「项目级优先、用户级回退、默认优先、优先级降序」；
     * <b>绝不会选中只有文本模型的供应商</b>。未配置返回 null（调用方降级文本检索 + 提示）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级）
     *
     * @return 含解密 Key 的运行时配置（无则 null）
     */
    public ProviderConfig resolveEmbeddingProvider(Long userId, Long projectId) {
        ModelProvider entity = findDefaultProvider(userId, projectId, ROLE_EMBEDDING);
        if (entity == null) {
            return null;
        }
        String model = pickModelName(effectiveModels(entity), ROLE_EMBEDDING);
        if (model == null || model.isBlank()) {
            return null;
        }
        String apiKey = aesCipherService.decrypt(entity.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        // embedding 场景复用 baseUrl/apiKey，模型名取该供应商的默认向量模型
        return new ProviderConfig(entity.getId(), entity.getBaseUrl(), apiKey, model, false);
    }

    /**
     * 解析某角色的默认供应商（项目级优先、用户级回退；默认标记优先、优先级降序）。
     * <p>决策模型是"锦上添花"：一个供应商都没配决策模型时回退文本模型，绝不因此让世界跑不动。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级）
     *
     * @param role 角色
     *
     * @return 选中的供应商（必有；无可用时抛业务异常）
     */
    private ModelProvider resolveDefaultForRole(Long userId, Long projectId, String role) {
        ModelProvider entity = findDefaultProvider(userId, projectId, role);
        if (entity == null && ROLE_DECISION.equals(role)) {
            entity = findDefaultProvider(userId, projectId, ROLE_TEXT);
        }
        if (entity == null) {
            throw new AiCallException("未配置可用的" + roleLabel(role)
                    + "：请先在「设置-API 配置」添加供应商，并在其下添加"
                    + roleLabel(ROLE_TEXT) + (ROLE_DECISION.equals(role) ? "或决策模型" : ""));
        }
        return entity;
    }

    /**
     * 找第一个「启用且具备某角色模型」的供应商：项目默认 → 项目优先 → 用户默认 → 用户优先。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户级）
     *
     * @param role 角色
     *
     * @return 命中的供应商（无则 null）
     */
    private ModelProvider findDefaultProvider(Long userId, Long projectId, String role) {
        if (projectId != null) {
            ModelProvider projectDefault = repository
                    .findByUserIdAndProjectIdAndIsDefault(userId, projectId, 1).stream()
                    .filter(p -> supportsRole(p, role))
                    .findFirst().orElse(null);
            if (projectDefault != null) {
                return projectDefault;
            }
            ModelProvider projectTop = repository
                    .findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, projectId).stream()
                    .filter(p -> supportsRole(p, role))
                    .findFirst().orElse(null);
            if (projectTop != null) {
                return projectTop;
            }
        }
        ModelProvider userDefault = repository.findByUserIdAndProjectIdAndIsDefault(userId, null, 1).stream()
                .filter(p -> supportsRole(p, role))
                .findFirst().orElse(null);
        if (userDefault != null) {
            return userDefault;
        }
        return repository.findByUserIdAndProjectIdOrderByPriorityDescIdAsc(userId, null).stream()
                .filter(p -> supportsRole(p, role))
                .findFirst().orElse(null);
    }

    /**
     * 供应商是否启用且具备该角色可用模型（决策回退只发生在最终解析处，不在这里放宽）。
     *
     * @param p 供应商
     *
     * @param role 角色
     *
     * @return true 表示可承接该角色调用
     */
    private boolean supportsRole(ModelProvider p, String role) {
        return Integer.valueOf(1).equals(p.getEnabled())
                && pickModelName(effectiveModels(p), role) != null;
    }

    /**
     * 供应商内该角色的模型名：默认模型 → 第一个启用模型；决策无则回退文本；文本无则回退旧 model 字段。
     *
     * @param entity 供应商
     *
     * @param role 角色
     *
     * @return 模型名（无则 null）
     */
    private String modelNameFor(ModelProvider entity, String role) {
        List<ProviderModelVO> models = effectiveModels(entity);
        String name = pickModelName(models, role);
        if (name == null && ROLE_DECISION.equals(role)) {
            name = pickModelName(models, ROLE_TEXT);
        }
        if (name == null && ROLE_TEXT.equals(role)) {
            name = entity.getModel();
        }
        return name;
    }

    /**
     * 角色中文名（错误/提示文案用）。
     *
     * @param role 角色
     *
     * @return 中文名
     */
    static String roleLabel(String role) {
        return switch (normalizeRole(role)) {
            case ROLE_EMBEDDING -> "向量模型";
            case ROLE_DECISION -> "决策模型";
            default -> "文本模型";
        };
    }

    /**
     * 该用户是否「已接上模型」（世界画廊「还没接上模型」横幅的唯一判据）。
     *
     * <p><b>为什么要有这个方法</b>：横幅此前只读前端 localStorage 的「关掉了」标记，
     * 与真实配置完全无关——用户填完 API 再进画廊，横幅照样弹（实测缺陷）。现在由后端
     * 统一判定「有没有任何一处能跑 AI 的配置」，前端不再自己猜。</p>
     *
     * <p><b>范围</b>：该用户<b>所有</b>供应商（用户级 project_id=NULL 与各世界项目级一起算）。
     * 画廊是全局页，此时没有「当前世界」上下文，而运行时规则是「项目级优先、用户级回退」，
     * 所以只要任意一层有可用文本/决策模型，世界就能跑 AI，横幅就该消失。</p>
     *
     * <p><b>不看连通性</b>：只校验「已启用 + 有 base_url + 存了 Key（密文非空）+ 至少一个
     * 文本或决策模型」，不发任何外部请求——进画廊不该等一次网络探测，Key 填错是另一个问题
     * （由设置页的「测试」按钮暴露）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @return true 表示至少有一处可用配置
     */
    public boolean isAiReady(Long userId) {
        return repository.findByUserIdOrderByPriorityDescIdAsc(userId).stream().anyMatch(ModelApiService::usableForAi);
    }

    /**
     * 单个供应商是否足以跑 AI（静态纯逻辑，可单测）。
     *
     * <p>判据：已启用（enabled=1）+ base_url 非空 + api_key 密文非空 + 该供应商下
     * <b>有启用的文本模型或决策模型</b>。只有向量模型的供应商不算——向量化是独立调用链，
     * 世界跑起来要的是文本/决策模型。</p>
     *
     * @param p 供应商实体
     *
     * @return true 表示可用于 AI 调用
     */
    static boolean usableForAi(ModelProvider p) {
        if (p == null || !Integer.valueOf(1).equals(p.getEnabled())) {
            return false;
        }
        if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
            return false;
        }
        if (p.getApiKeyCipher() == null || p.getApiKeyCipher().isBlank()) {
            return false;
        }
        List<ProviderModelVO> models = effectiveModels(p);
        return pickModelName(models, ROLE_TEXT) != null || pickModelName(models, ROLE_DECISION) != null;
    }

    // ==================== 模型清单（models_json）读写与旧数据推导 ====================

    /**
     * 造一个模型条目（id 为空时生成随机句柄）。
     *
     * @param id 条目 id（旧数据传 legacy-* 保持稳定）
     *
     * @param name 模型名
     *
     * @param role 角色
     *
     * @param isDefault 是否默认
     *
     * @return 模型条目
     */
    private static ProviderModelVO model(String id, String name, String role, int isDefault) {
        ProviderModelVO m = new ProviderModelVO();
        m.setId(id == null || id.isBlank() ? UUID.randomUUID().toString() : id);
        m.setName(name == null ? null : name.trim());
        m.setRole(role);
        m.setIsDefault(isDefault);
        m.setEnabled(1);
        return m;
    }

    /**
     * 旧调用（只传单个 model / embeddingModel）→ 播种模型清单。
     *
     * @param dto 请求体
     *
     * @return 模型条目（无模型名时返回空列表）
     */
    private static List<ProviderModelVO> seedModels(ModelApiDTO dto) {
        List<ProviderModelVO> out = new ArrayList<>();
        String purpose = dto.getPurpose() == null ? "chat" : dto.getPurpose().trim().toLowerCase();
        boolean embeddingOnly = "embedding".equals(purpose);
        if (!embeddingOnly && notBlank(dto.getModel())) {
            out.add(model(null, dto.getModel(), ROLE_TEXT, 1));
        }
        if (("embedding".equals(purpose) || "both".equals(purpose)) && notBlank(dto.getEmbeddingModel())) {
            out.add(model(null, dto.getEmbeddingModel(), ROLE_EMBEDDING, 1));
        }
        // 防御：purpose=embedding 但旧前端把模型名填在 model 字段
        if (out.isEmpty() && embeddingOnly && notBlank(dto.getModel())) {
            out.add(model(null, dto.getModel(), ROLE_EMBEDDING, 1));
        }
        return out;
    }

    /**
     * 读取供应商模型清单：有 JSON 用 JSON，否则按旧字段推导（只读视图，首次写入时才物化）。
     *
     * <p>静态是因为它只依赖静态解析/推导：让「AI 是否已接上」这类纯判定（
     * {@link #usableForAi}）能在不持有 Spring Bean 的单测里直接跑。</p>
     *
     * @param p 供应商
     *
     * @return 模型条目列表（可能为空）
     */
    private static List<ProviderModelVO> effectiveModels(ModelProvider p) {
        List<ProviderModelVO> parsed = parseModelsJson(p.getModelsJson());
        return parsed.isEmpty() ? legacyModels(p) : parsed;
    }

    /**
     * 解析 models_json（损坏时降级为空列表并告警，不让配置页 500）。
     *
     * @param json JSON 文本
     *
     * @return 模型条目列表
     */
    private static List<ProviderModelVO> parseModelsJson(String json) {
        List<ProviderModelVO> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            List<ProviderModelVO> parsed = MAPPER.readValue(json, new TypeReference<List<ProviderModelVO>>() {
            });
            if (parsed == null) {
                return out;
            }
            for (ProviderModelVO m : parsed) {
                if (m == null || m.getName() == null || m.getName().isBlank()) {
                    continue;
                }
                if (m.getId() == null || m.getId().isBlank()) {
                    m.setId(UUID.randomUUID().toString());
                }
                m.setRole(normalizeRole(m.getRole()));
                if (m.getIsDefault() == null) {
                    m.setIsDefault(0);
                }
                if (m.getEnabled() == null) {
                    m.setEnabled(1);
                }
                out.add(m);
            }
        } catch (RuntimeException e) {
            log.warn("[AI 配置] models_json 解析失败（按旧字段推导）：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 旧数据推导：purpose=chat/both 的 model → 文本模型；embedding_model → 向量模型。
     *
     * <p>包级可见是为了让单测能直接覆盖旧库（无 models_json）的兼容推导——这是升级时
     * 唯一"用户无感但不能出错"的路径。</p>
     *
     * @param p 供应商
     *
     * @return 模型条目列表
     */
    static List<ProviderModelVO> legacyModels(ModelProvider p) {
        List<ProviderModelVO> out = new ArrayList<>();
        if (isChatCapable(p) && notBlank(p.getModel())) {
            out.add(model("legacy-text", p.getModel(), ROLE_TEXT, 1));
        }
        if (isEmbeddingCapable(p)) {
            out.add(model("legacy-embedding", p.getEmbeddingModel(), ROLE_EMBEDDING, 1));
        }
        // 兜底：老库有的行 purpose 为空且 model 有值（purpose=embedding 的空用途不在此兜底，
        // 否则会把 embedding 专用供应商误当成文本模型）
        if (out.isEmpty() && notBlank(p.getModel())
                && (p.getPurpose() == null || p.getPurpose().isBlank())) {
            out.add(model("legacy-text", p.getModel(), ROLE_TEXT, 1));
        }
        return out;
    }

    /**
     * 写回模型清单：稳定排序 → 序列化 JSON → 同步旧字段（供旧调用方与镜像/归档继续可用）。
     *
     * @param p 供应商
     *
     * @param models 模型条目（会被排序）
     */
    private static void persistModels(ModelProvider p, List<ProviderModelVO> models) {
        models.sort(Comparator
                .comparingInt((ProviderModelVO m) -> ROLES.indexOf(normalizeRole(m.getRole())))
                .thenComparing(m -> m.getName() == null ? "" : m.getName(), String.CASE_INSENSITIVE_ORDER));
        try {
            p.setModelsJson(MAPPER.writeValueAsString(models));
        } catch (RuntimeException e) {
            throw new IllegalStateException("模型清单序列化失败：" + e.getMessage(), e);
        }
        ProviderModelVO text = firstOfRole(models, ROLE_TEXT);
        ProviderModelVO embedding = firstOfRole(models, ROLE_EMBEDDING);
        p.setModel(text == null ? null : text.getName());
        p.setEmbeddingModel(embedding == null ? null : embedding.getName());
        p.setEmbeddingEnabled(embedding == null ? 0 : 1);
        p.setPurpose(embedding != null && text != null ? "both" : embedding != null ? "embedding" : "chat");
    }

    /**
     * 取某角色「默认优先」的第一个条目（旧字段同步用）。
     *
     * @param models 模型条目
     *
     * @param role 角色
     *
     * @return 条目（无则 null）
     */
    private static ProviderModelVO firstOfRole(List<ProviderModelVO> models, String role) {
        ProviderModelVO fallback = null;
        for (ProviderModelVO m : models) {
            if (m == null || !role.equals(normalizeRole(m.getRole()))) {
                continue;
            }
            if (Integer.valueOf(1).equals(m.getIsDefault())) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    /**
     * 选某角色的模型名（静态纯逻辑，可单测）：启用 → 默认优先 → 第一个。
     *
     * @param models 模型条目
     *
     * @param role 角色
     *
     * @return 模型名（无则 null）
     */
    static String pickModelName(List<ProviderModelVO> models, String role) {
        if (models == null || models.isEmpty()) {
            return null;
        }
        String wanted = normalizeRole(role);
        ProviderModelVO fallback = null;
        for (ProviderModelVO m : models) {
            if (m == null || m.getName() == null || m.getName().isBlank()) {
                continue;
            }
            if (!wanted.equals(normalizeRole(m.getRole()))) {
                continue;
            }
            if (Integer.valueOf(0).equals(m.getEnabled())) {
                continue;
            }
            if (Integer.valueOf(1).equals(m.getIsDefault())) {
                return m.getName();
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback == null ? null : fallback.getName();
    }

    /**
     * 按条目 id 找模型。
     *
     * @param models 模型条目
     *
     * @param modelId 条目 id
     *
     * @return 条目（无则 empty）
     */
    private static Optional<ProviderModelVO> findModel(List<ProviderModelVO> models, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        return models.stream().filter(m -> modelId.equals(m.getId())).findFirst();
    }

    /**
     * 同角色下不允许同名模型（改名时排除自身）。
     *
     * @param models 模型条目
     *
     * @param role 角色
     *
     * @param name 模型名
     *
     * @param excludeId 排除的条目 id（新增传 null）
     */
    private static void requireNoDuplicate(List<ProviderModelVO> models, String role, String name,
                                           String excludeId) {
        for (ProviderModelVO m : models) {
            if (m == null || (excludeId != null && excludeId.equals(m.getId()))) {
                continue;
            }
            if (role.equals(normalizeRole(m.getRole())) && m.getName() != null
                    && name.equalsIgnoreCase(m.getName().trim())) {
                throw new IllegalArgumentException("该角色下已存在同名模型：" + name);
            }
        }
    }

    /**
     * 清掉某角色下的默认标记（默认互斥）。
     *
     * @param models 模型条目
     *
     * @param role 角色
     */
    private static void clearRoleDefault(List<ProviderModelVO> models, String role) {
        for (ProviderModelVO m : models) {
            if (m != null && role.equals(normalizeRole(m.getRole()))) {
                m.setIsDefault(0);
            }
        }
    }

    /**
     * 保证某角色至少有一个默认（删掉默认模型/改角色后自动提级第一个启用模型）。
     *
     * @param models 模型条目
     *
     * @param role 角色
     */
    private static void ensureRoleDefault(List<ProviderModelVO> models, String role) {
        boolean hasDefault = models.stream().anyMatch(m -> m != null
                && role.equals(normalizeRole(m.getRole()))
                && Integer.valueOf(1).equals(m.getIsDefault()));
        if (hasDefault) {
            return;
        }
        models.stream()
                .filter(m -> m != null && role.equals(normalizeRole(m.getRole())))
                .filter(m -> !Integer.valueOf(0).equals(m.getEnabled()))
                .findFirst()
                .ifPresent(m -> m.setIsDefault(1));
    }

    /**
     * 归一化角色（text / embedding / decision，非法值按 text）。
     *
     * @param role 原始角色
     *
     * @return 归一化后的角色
     */
    static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_TEXT;
        }
        String r = role.trim().toLowerCase();
        return ROLE_EMBEDDING.equals(r) || ROLE_DECISION.equals(r) ? r : ROLE_TEXT;
    }

    /** 上下文容量上限（tokens）：只是元数据的护栏，防止输入天文数字撑爆界面 */
    public static final int MAX_CONTEXT_WINDOW = 10_000_000;

    /**
     * 归一化上下文容量（<b>纯元数据，不参与运行时裁剪</b>）。
     *
     * <p>规则：null / 0 / 负数 → null（=未知）；超过 {@link #MAX_CONTEXT_WINDOW} 截到上限。
     * 服务层再归一一次而不是只靠 DTO 校验：{@code addModel}/{@code updateModel} 也会被内部的
     * 导入流程直接调用，绕过控制器校验。</p>
     *
     * @param value 原始值（tokens）
     *
     * @return 归一化后的值（null=未知）
     */
    static Integer normalizeContextWindow(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        return Math.min(value, MAX_CONTEXT_WINDOW);
    }

    /** 非空判断（去空白后非空）。 */
    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 判断供应商是否可作主 AI（对话等）用途（静态纯逻辑，可单测）。
     * <p>规则：显式 purpose 为 chat/both 可用；旧数据（purpose 空）按 embedding_enabled 判定
     * （embedding_enabled=1 视为 embedding 专用，不可作主 AI）。</p>
     *
     * @param p 供应商实体
     *
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
     *
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
     *
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
     * 按 id+userId+projectId 查询归属实体（不存在或越权时抛出 404）。
     * 先按项目级定位，未命中回退用户级（允许对用户级 API 操作）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级）
     *
     * @param id 主键
     *
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
     *
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
        // 模型清单：旧数据在此按字段推导成三种角色的视图（不写库）
        List<ProviderModelVO> models = effectiveModels(entity);
        vo.setModels(models);
        vo.setModelCount(models.size());
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
     *
     * @return 脱敏串或 null
     */
    private String maskKey(String plain) {
        if (plain == null || plain.isBlank()) return null;
        int tail = Math.min(4, plain.length());
        return "****" + plain.substring(plain.length() - tail);
    }
}
