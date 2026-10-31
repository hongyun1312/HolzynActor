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

package com.holzyn.actor.domain.settings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型 API 配置实体（用户级）。
 * <p>职责：存储用户自行配置的 OpenAI 兼容模型 API（供应商地址 / 加密 Key / 模型等），
 * 每个用户的 API 列表相互隔离（user_id 归属），供后续对话 / 角色卡等 AI 场景选用。</p>
 * <p>安全说明：api_key 以 AES-GCM 密文存储于 api_key_cipher，明文仅存在于服务端解密调用时，
 * 永不下发前端。</p>
 */
@Data
@Entity
@Table(name = "actor_model_provider")
public class ModelProvider {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户 ID（关联 sys_user.id；1=演示用户） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 项目 ID（NULL=用户级默认配置；非空=项目级配置，随 .holzyn 包导入导出） */
    @Column(name = "project_id")
    private Long projectId;

    /** 供应商/API 名称（用户自定义标识，如「DeepSeek 主用」「智谱备用」） */
    @Column(nullable = false, length = 50)
    private String name;

    /** API Base URL（OpenAI 兼容协议地址，如 https://api.deepseek.com/v1） */
    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    /** 加密后的 API Key（AES-GCM 密文，格式 v1:ivBase64:dataBase64；明文永不下发） */
    @Column(name = "api_key_cipher", columnDefinition = "TEXT")
    private String apiKeyCipher;

    /** 默认模型名（如 deepseek-chat / gpt-4o-mini，可空则由调用方指定） */
    @Column(length = 100)
    private String model;

    /**
     * 用途类型（分开配置与使用：主 AI 与 embedding 向量化分离）。
     * <p>取值：chat=主 AI 对话（角色卡/对话/行动/事件/群聊）；embedding=向量化专用（知识库 RAG）；
     * both=两者兼用。旧数据为空时按 embedding_enabled 判定（embedding_enabled=1 视为 embedding 专用）。</p>
     */
    @Column(length = 20)
    private String purpose = "chat";

    /** 是否启用该供应商的 embedding 能力：0 否 / 1 是 */
    @Column(name = "embedding_enabled")
    private Integer embeddingEnabled = 0;

    /** 该供应商的 embedding 模型名（如 doubao-embedding / bge-m3 / text-embedding-3-small） */
    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    /**
     * 该供应商下的模型清单（JSON 数组，元素字段：id/name/role/isDefault/enabled/remark）。
     *
     * <p>role：{@code text}=文本模型 / {@code embedding}=向量模型 / {@code decision}=决策模型。
     * 为 NULL 或空数组时按旧字段（{@code model} / {@code embedding_model} / {@code purpose}）
     * 推导出一份只读视图——升级前已有的单模型配置因此无需回填即可继续工作。</p>
     *
     * <p>为什么是 JSON 列而不是子表：本表同时受"目录库 + 世界库 + 镜像同步 + 归档脱敏"四套机制约束，
     * 新增子表要改两库 schema、注册表清单与镜像/脱敏逻辑；而供应商配置是整行读写的单用户小对象，
     * 模型数量有限，JSON 列的收益/风险比明显更好（见 V103 迁移注释）。</p>
     */
    @Column(name = "models_json", columnDefinition = "TEXT")
    private String modelsJson;

    /** 用户级默认标记：0 否 / 1 是（同一用户至多一个 1，业务层事务维护互斥） */
    @Column(name = "is_default")
    private Integer isDefault = 0;

    /** 是否支持流式：0 否 / 1 是 */
    @Column(name = "supports_stream")
    private Integer supportsStream = 1;

    /** 路由优先级（越大越优先，保留字段，暂未使用） */
    private Integer priority = 0;

    /** 是否启用：0 否 / 1 是（停用后不被默认路由选中） */
    private Integer enabled = 1;

    /** 备注（用户标注用途 / 供应商说明） */
    @Column(length = 255)
    private String remark;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 入库前初始化默认值（与表结构默认值保持一致，避免空字段）。
     */
    @PrePersist
    void prePersist() {
        if (isDefault == null) isDefault = 0;
        if (supportsStream == null) supportsStream = 1;
        if (priority == null) priority = 0;
        if (enabled == null) enabled = 1;
        if (embeddingEnabled == null) embeddingEnabled = 0;
        if (purpose == null || purpose.isBlank()) purpose = "chat";
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    /**
     * 前刷新更新时间戳。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
