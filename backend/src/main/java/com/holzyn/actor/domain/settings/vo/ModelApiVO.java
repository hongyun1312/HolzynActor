package com.holzyn.actor.domain.settings.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型 API 视图对象。
 * <p>用途：向前端返回脱敏后的 API 配置信息。api_key 以掩码形式（**** 尾 4 位）展示，
 * 明文 Key 永不下发前端。</p>
 * <p>所属模块：model/vo（视图对象层-用户 AI API 子域）</p>
 */
@Data
public class ModelApiVO {

    /** 主键 */
    private Long id;

    /** 项目 ID（NULL=用户级配置；非空=项目级配置） */
    private Long projectId;

    /** API 名称 */
    private String name;

    /** API Base URL */
    private String baseUrl;

    /** 默认模型名 */
    private String model;

    /**
     * 用途类型（分开配置与使用）：chat=主 AI 对话 / embedding=向量化专用 / both=两者兼用。
     * <p>旧数据可能为 null（按 embeddingEnabled 判定）。</p>
     */
    private String purpose;

    /** 脱敏后的 API Key（如 ****abcd；未配置时为 null） */
    private String apiKeyMasked;

    /** 是否支持流式：0 否 / 1 是 */
    private Integer supportsStream;

    /** 是否用户级默认：0 否 / 1 是 */
    private Integer isDefault;

    /** 是否启用：0 否 / 1 是 */
    private Integer enabled;

    /** 是否启用该供应商的 embedding 能力：0 否 / 1 是（P3 RAG） */
    private Integer embeddingEnabled;

    /** embedding 模型名（如 doubao-embedding / bge-m3） */
    private String embeddingModel;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
