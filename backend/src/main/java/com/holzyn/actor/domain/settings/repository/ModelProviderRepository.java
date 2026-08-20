package com.holzyn.actor.domain.settings.repository;

import com.holzyn.actor.domain.settings.entity.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * AI 模型 API 配置仓库。
 * <p>职责：提供 actor_model_provider 表的持久化访问，所有查询均按 userId 归属隔离
 * （防止用户 A 读取/操作用户 B 的 API 配置）。</p>
 * <p>所属模块：repository（数据访问层-用户 AI API 子域）</p>
 */
@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {

    /**
     * 按归属用户查询全部 API（优先级降序、ID 升序稳定排序）——用户级（project_id IS NULL）。
     *
     * @param userId 归属用户 ID
     * @return 该用户的 API 列表（含停用项，由服务层决定是否过滤）
     */
    List<ModelProvider> findByUserIdOrderByPriorityDescIdAsc(Long userId);

    /**
     * 按归属用户 + 项目查询全部 API（优先级降序、ID 升序稳定排序）——项目级。
     * projectId 传 null 时等价于用户级查询（project_id IS NULL）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @return 该归属的 API 列表
     */
    List<ModelProvider> findByUserIdAndProjectIdOrderByPriorityDescIdAsc(Long userId, Long projectId);

    /**
     * 按归属用户 + 主键查询单个 API（归属校验核心方法，避免越权）。
     *
     * @param id     主键
     * @param userId 归属用户 ID
     * @return 命中返回实体，否则为空
     */
    Optional<ModelProvider> findByIdAndUserId(Long id, Long userId);

    /**
     * 按归属用户 + 项目 + 主键查询单个 API（项目级归属校验）。
     *
     * @param id        主键
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @return 命中返回实体，否则为空
     */
    Optional<ModelProvider> findByIdAndUserIdAndProjectId(Long id, Long userId, Long projectId);

    /**
     * 按归属用户 + 默认标记查询（用于「设为默认」前的互斥清理与默认读取）。
     *
     * @param userId    归属用户 ID
     * @param isDefault 默认标记（1 表示默认）
     * @return 匹配的 API 列表
     */
    List<ModelProvider> findByUserIdAndIsDefault(Long userId, Integer isDefault);

    /**
     * 按归属用户 + 项目 + 默认标记查询（项目级互斥清理与默认读取）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param isDefault 默认标记（1 表示默认）
     * @return 匹配的 API 列表
     */
    List<ModelProvider> findByUserIdAndProjectIdAndIsDefault(Long userId, Long projectId, Integer isDefault);

    /**
     * 按归属用户 + 主键删除（归属校验，防止删除他人配置）。
     *
     * @param id     主键
     * @param userId 归属用户 ID
     * @return 删除条数（0 表示不存在或无权限）
     */
    long deleteByIdAndUserId(Long id, Long userId);

    /**
     * 按归属用户 + 项目 + 主键删除（项目级归属校验）。
     *
     * @param id        主键
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @return 删除条数（0 表示不存在或无权限）
     */
    long deleteByIdAndUserIdAndProjectId(Long id, Long userId, Long projectId);
}
