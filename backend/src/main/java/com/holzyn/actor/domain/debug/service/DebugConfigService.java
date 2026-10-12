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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.entity.ModelProvider;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import com.holzyn.actor.domain.settings.repository.ModelProviderRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调试配置查看服务（调试前端配置查看页）。
 * <p>职责：以<b>只读 + 脱敏</b>方式提供模型 API 配置列表（baseUrl/model/purpose，
 * key 一律打码为 ******，明文/密文永不下发）与 Prompt 模板列表
 * （code/名称/system_message 是否存在/启用/是否项目或用户覆盖），不提供任何编辑能力
 * （编辑仍走主前端设置页）。</p>
 */
@Service
public class DebugConfigService {

    /** API Key 脱敏占位（绝不下发明文/密文） */
    private static final String KEY_MASK = "******";

    private final ModelProviderRepository providerRepository;
    private final ActorPromptTemplateRepository templateRepository;

    public DebugConfigService(ModelProviderRepository providerRepository,
                              ActorPromptTemplateRepository templateRepository) {
        this.providerRepository = providerRepository;
        this.templateRepository = templateRepository;
    }

    /**
     * 配置查看数据（API 脱敏列表 + Prompt 模板列表）。
     */
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providers());
        out.put("promptTemplates", promptTemplates());
        return out;
    }

    /**
     * 模型 API 配置脱敏列表（key 打码，不含 apiKeyCipher 密文）。
     *
     * @return 脱敏后的配置列表
     */
    private List<Map<String, Object>> providers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModelProvider p : providerRepository.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("baseUrl", p.getBaseUrl());
            m.put("model", p.getModel());
            m.put("purpose", p.getPurpose());
            m.put("embeddingModel", p.getEmbeddingModel());
            m.put("isDefault", p.getIsDefault());
            m.put("enabled", p.getEnabled());
            m.put("supportsStream", p.getSupportsStream());
            m.put("projectId", p.getProjectId());
            m.put("userId", p.getUserId());
            m.put("apiKeyMasked", KEY_MASK);
            list.add(m);
        }
        return list;
    }

    /**
     * Prompt 模板列表（code/名称/system_message 是否存在/启用/覆盖情况；不含模板正文避免刷屏）。
     *
     * @return 模板列表
     */
    private List<Map<String, Object>> promptTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActorPromptTemplate t : templateRepository.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("code", t.getCode());
            m.put("name", t.getName());
            m.put("version", t.getVersion());
            m.put("enabled", t.getEnabled());
            m.put("hasSystemMessage", t.getSystemMessage() != null && !t.getSystemMessage().isBlank());
            // 覆盖情况：projectId 非空=项目级覆盖；userId>0=用户覆盖；否则内置
            String scope = t.getProjectId() != null ? "project"
                    : (t.getUserId() != null && t.getUserId() > 0L ? "user" : "builtin");
            m.put("scope", scope);
            m.put("projectId", t.getProjectId());
            m.put("userId", t.getUserId());
            m.put("templateLength", t.getTemplate() == null ? 0 : t.getTemplate().length());
            list.add(m);
        }
        return list;
    }
}
