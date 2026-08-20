package com.holzyn.actor.domain.memory.vo;

import com.holzyn.actor.domain.memory.entity.ActorMemory;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长期记忆视图对象（P4-1 记忆 API 返回）。
 * <p>职责：向前端暴露记忆条目——归属（projectId/characterId）、类型（fact/summary）、
 * 内容、重要度与创建时间；不含软删/审计字段。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
@Data
public class MemoryVO {

    /** 记忆主键 */
    private Long id;

    /** 项目级记忆归属 */
    private Long projectId;

    /** 角色 ID（空=项目级记忆） */
    private Long characterId;

    /** 类型：fact 关键事实 / summary 会话摘要 */
    private String kind;

    /** 记忆内容 */
    private String content;

    /** 重要度（1-5） */
    private Integer importance;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 实体转 VO。
     *
     * @param m 记忆实体
     * @return VO 对象
     */
    public static MemoryVO of(ActorMemory m) {
        MemoryVO vo = new MemoryVO();
        vo.setId(m.getId());
        vo.setProjectId(m.getProjectId());
        vo.setCharacterId(m.getCharacterId());
        vo.setKind(m.getKind());
        vo.setContent(m.getContent());
        vo.setImportance(m.getImportance());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }
}
