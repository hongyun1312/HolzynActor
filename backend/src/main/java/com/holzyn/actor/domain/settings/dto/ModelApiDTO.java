package com.holzyn.actor.domain.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 模型 API 新增/编辑请求 DTO。
 * <p>用途：承载「新增 API」与「编辑 API」两种场景的请求体（同一 DTO 复用）。
 * 创建时 apiKey 必填；编辑时 apiKey 传空表示「保持原 Key 不变」。</p>
 * <p>所属模块：model/dto（数据传输层-用户 AI API 子域）</p>
 */
@Data
public class ModelApiDTO {

    /** 供应商/API 名称（用户自定义标识） */
    @NotBlank(message = "API 名称不能为空")
    @Size(max = 50, message = "API 名称长度不能超过 50")
    private String name;

    /** API Base URL（OpenAI 兼容协议地址） */
    @NotBlank(message = "Base URL 不能为空")
    @Size(max = 255, message = "Base URL 长度不能超过 255")
    private String baseUrl;

    /** API Key（创建必填；编辑传空=不修改；明文仅用于入库加密与连通性测试，不下发） */
    @Size(max = 512, message = "API Key 长度不能超过 512")
    private String apiKey;

    /** 默认模型名（可空，如 deepseek-chat） */
    @Size(max = 100, message = "模型名长度不能超过 100")
    private String model;

    /**
     * 用途类型（分开配置与使用）：chat=主 AI 对话（默认）；embedding=向量化专用；both=两者兼用。
     * <p>主 AI（对话/角色卡/行动）只从 chat/both 中选择；向量化（知识库 RAG）只从 embedding/both 中选择。</p>
     */
    @Size(max = 20, message = "用途类型长度不能超过 20")
    private String purpose;

    /** 是否支持流式（缺省 true，供后续 SSE 对话链路选择） */
    private Boolean supportsStream;

    /** 是否启用该供应商的 embedding 能力（P3 RAG；开启后复用 baseUrl/apiKey 调 /embeddings） */
    private Boolean embeddingEnabled;

    /** embedding 模型名（开启 embedding 时必填，如 doubao-embedding / bge-m3 / text-embedding-3-small） */
    @Size(max = 100, message = "embedding 模型名长度不能超过 100")
    private String embeddingModel;

    /** 备注 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
