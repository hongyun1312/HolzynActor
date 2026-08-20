package com.holzyn.actor.domain.world.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 世界观地点视图 VO。
 * <p>职责：向前端返回地点列表/详情（含排序与时间），供「地点详情」表格渲染
 * 与对话创建「对话所在地」候选下拉使用。</p>
 * <p>所属模块：domain/world（世界域-地点子域）</p>
 */
@Data
public class WorldLocationVO {

    /** 主键 */
    private Long id;

    /** 归属项目 ID */
    private Long projectId;

    /** 地点名称 */
    private String name;

    /** 地点类型 */
    private String type;

    /** 详细简介 */
    private String intro;

    /** 重要度（1-5） */
    private Integer importance;

    /** 排序 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
