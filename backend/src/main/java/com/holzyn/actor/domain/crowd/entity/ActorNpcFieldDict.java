package com.holzyn.actor.domain.crowd.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 普通型 NPC 标准字段数据实体，对应表 actor_npc_field_dict（2026-08-19 分类体系重构新增）。
 * <p>职责：承载普通型 NPC 的「标准字段数据」字典（AI 依据世界观拟定，每条含出处、严格符合世界观，
 * 禁止编造）——生成普通 NPC 时 AI 必须从这些字段值中选取，保证符合世界观、避免 OOC。</p>
 * <ul>
 *   <li>field：字段名。race=种族（两级：level1 种族大类如 人族/妖精，level2 次级种族如 汉族/猫妖）；
 *       affiliation=归属（level1 归属名，level2 空）；occupation=职业（level1 职业名，level2 空）。</li>
 *   <li>source：出处（引用世界观的具体字段/段落，如「取自世界观【种族】设定的猫妖」）。</li>
 *   <li>所在地（location）不在此表：复用世界观地点表 actor_world_location（可额外合理补充）。</li>
 * </ul>
 * <p>主/次分类字段（AI 选出、供 AI 调度聚合）存 actor_crowd_runtime.primary_field/secondary_field。</p>
 * <p>所属模块：model/entity（实体层-普通型人群子域）</p>
 */
@Data
@Entity
@Table(name = "actor_npc_field_dict",
        uniqueConstraints = @UniqueConstraint(name = "uk_npc_field_dict", columnNames = {"project_id", "field", "level1", "level2"}),
        indexes = @Index(name = "idx_npc_field_dict_project_field", columnList = "project_id, field"))
public class ActorNpcFieldDict {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 ID（关联 actor_project.id，数据隔离） */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 字段名：race 种族 / affiliation 归属 / occupation 职业 */
    @Column(nullable = false, length = 20)
    private String field;

    /** 一级值（race=种族大类 / affiliation=归属名 / occupation=职业名） */
    @Column(nullable = false, length = 50)
    private String level1;

    /** 二级值（仅 race 用：次级种族；其余字段留空） */
    @Column(length = 50)
    private String level2;

    /** 出处（引用世界观的具体字段/段落，禁止编造） */
    @Column(columnDefinition = "TEXT")
    private String source;

    /** 排序 */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入前回调：填充创建/更新时间与默认排序。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (sortOrder == null) sortOrder = 0;
    }

    /**
     * 更新前回调：刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
