package com.holzyn.actor.domain.crowd.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 普通型 NPC 实体，对应表 actor_ordinary_npc（普通型人群完全重构 V2 后的单表）。
 * <p>职责：承载普通型 NPC（路人/群像/平民）的扁平化个体档案，不再按「人群组」组织——
 * 每位普通 NPC 独立一行，字段覆盖完整档案：名称/性别/种族/次级种族/年龄/归属/当前所在地/职业/
 * 角色详情，并保留状态机字段（state/lastAction）供定时调度推进。
 * <b>2026-08-19 分类体系重构</b>：废弃「AI 职业分类 l1/l2」字段（category_l1/category_l2），
 * 新增「次级种族」（sub_race，与种族构成两级：人族→汉族、妖精→猫妖）；
 * 分类改为由 AI 从 归属/职业/种族/所在地 中选 2 个字段（一主一次）聚合，
 * 字段标准数据存 actor_npc_field_dict，NPC 生成时从字典中选取；关系单独存 actor_character_relation。</p>
 * <p>所属模块：model/entity（实体层-普通型人群子域）</p>
 */
@Data
@Entity
@Table(name = "actor_ordinary_npc", indexes = {
        @Index(name = "idx_ordinary_npc_project", columnList = "project_id")
})
public class ActorOrdinaryNpc {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id，数据隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 姓名（AI 生成，严格符合世界观命名习惯，项目内唯一） */
    @Column(nullable = false, length = 50)
    private String name;

    /** 性别（如：男/女/无性/自定义，依世界观种族而定） */
    @Column(length = 20)
    private String gender;

    /** 种族（一级，取自字段字典 race.level1，如：人族/妖精） */
    @Column(length = 50)
    private String race;

    /** 次级种族（二级，取自字段字典 race.level2，如：汉族/猫妖；与种族构成两级） */
    @Column(name = "sub_race", length = 50)
    private String subRace;

    /** 年龄（依世界观种族寿命而定） */
    private Integer age;

    /** 归属（势力/组织/村落/家族等，取自字段字典 affiliation） */
    @Column(length = 100)
    private String affiliation;

    /** 当前所在地（地点名，优先取自世界观地点表，可合理补充） */
    @Column(length = 100)
    private String location;

    /** 职业（取自字段字典 occupation，可空） */
    @Column(length = 50)
    private String occupation;

    /** 角色详情（120-200 字：背景/性格/谋生方式/家庭/与世界观联系，AI 生成） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 当前状态：idle 空闲/walk 行走/stop 停留/talk 交谈/rest 休息（作息状态机） */
    @Column(length = 20, nullable = false)
    private String state = "idle";

    /** 最近行动描述（状态机推进时更新，供动态流展示） */
    @Column(name = "last_action", length = 255)
    private String lastAction;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充创建/更新时间与默认状态。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (state == null) state = "idle";
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
