package com.holzyn.actor.domain.world.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 世界观地点新增/编辑请求 DTO。
 * <p>用途：承载「地点详情」页增删改查与编辑模式全量保存（batch）的请求体；
 * 新建项目解析流程的「地点提取」结果（LocationPart）复用同构字段。</p>
 * <p>所属模块：domain/world（世界域-地点子域）</p>
 */
@Data
public class WorldLocationDTO {

    /** 地点名称（必填） */
    @NotBlank(message = "地点名称不能为空")
    @Size(max = 100, message = "地点名称长度不能超过 100")
    private String name;

    /** 地点类型（城市/城镇/酒馆/森林…） */
    @Size(max = 50, message = "地点类型长度不能超过 50")
    private String type;

    /** 详细简介（≤2000 字） */
    @Size(max = 2000, message = "地点简介长度不能超过 2000")
    private String intro;

    /** 重要度（1-5，默认 3） */
    private Integer importance;

    /** 排序（越小越靠前，默认 0） */
    private Integer sortOrder;
}
