package com.holzyn.actor.domain.crowd.repository;

import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 普通型 NPC 标准字段数据仓库（actor_npc_field_dict，2026-08-19 分类体系重构新增）。
 * <p>职责：提供按项目 + 字段查询字典（race 种族[两级]/affiliation 归属/occupation 职业）、
 * 整项目替换、删除与去重能力，支撑 AI 拟定/预览确认/手动管理/居民生成选取。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorNpcFieldDictRepository extends JpaRepository<ActorNpcFieldDict, Long> {

    /**
     * 查询某项目全部字段字典（按字段/排序稳定排序）。
     *
     * @param projectId 项目 ID
     * @return 字段字典列表
     */
    List<ActorNpcFieldDict> findByProjectIdOrderByFieldAscSortOrderAscIdAsc(Long projectId);

    /**
     * 查询某项目某字段的字典（按排序）。
     *
     * @param projectId 项目 ID
     * @param field     字段名（race/affiliation/occupation）
     * @return 字典列表
     */
    List<ActorNpcFieldDict> findByProjectIdAndFieldOrderBySortOrderAscIdAsc(Long projectId, String field);

    /**
     * 删除某项目全部字段字典（重新拟定/导入覆盖用）。
     *
     * @param projectId 项目 ID
     */
    void deleteByProjectId(Long projectId);
}
