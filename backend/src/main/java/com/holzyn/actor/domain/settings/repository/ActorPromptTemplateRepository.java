package com.holzyn.actor.domain.settings.repository;

import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Prompt 模板仓库（actor_prompt_template）。
 * <p>职责：提供按用户（user_id）与模板编码（code）的查询/删除能力；
 * user_id=0 为内置模板、>0 为用户覆盖，二者以 (user_id, code) 唯一约束区分。</p>
 * <p>所属模块：repository（数据访问层-模板子域）</p>
 */
@Repository
public interface ActorPromptTemplateRepository extends JpaRepository<ActorPromptTemplate, Long> {

    /**
     * 按归属用户 + 模板编码查询（用户覆盖或内置，project_id IS NULL）。
     *
     * @param userId 归属用户 ID
     * @param code   模板编码
     * @return 命中的模板（可能为空）
     */
    Optional<ActorPromptTemplate> findByUserIdAndCode(Long userId, String code);

    /**
     * 按归属用户 + 项目 + 模板编码查询（项目级覆盖 / 用户覆盖 / 内置共用）。
     * projectId 传 null 时等价于 findByUserIdAndCode（project_id IS NULL）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param code      模板编码
     * @return 命中的模板（可能为空）
     */
    Optional<ActorPromptTemplate> findByUserIdAndProjectIdAndCode(Long userId, Long projectId, String code);

    /**
     * 按归属用户查询全部模板。
     *
     * @param userId 归属用户 ID
     * @return 该用户的全部模板（含启用/停用）
     */
    List<ActorPromptTemplate> findByUserIdOrderByCodeAsc(Long userId);

    /**
     * 按归属用户 + 项目查询全部模板（项目级覆盖；projectId=null 等价用户级）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @return 该归属的全部模板
     */
    List<ActorPromptTemplate> findByUserIdAndProjectIdOrderByCodeAsc(Long userId, Long projectId);

    /**
     * 查询多个归属用户（内置 + 指定用户）的全部启用模板，用于合并展示「用户覆盖 ∪ 内置」。
     *
     * @param userIds 归属用户 ID 集合（如 [0, 当前用户]）
     * @param enabled 启用标记（1）
     * @return 启用模板列表
     */
    List<ActorPromptTemplate> findByUserIdInAndEnabledOrderByCodeAsc(List<Long> userIds, Integer enabled);

    /**
     * 删除用户覆盖（重置回退内置）；内置模板（user_id=0）不参与删除。
     *
     * @param userId 归属用户 ID
     * @param code   模板编码
     */
    void deleteByUserIdAndCode(Long userId, String code);

    /**
     * 删除项目级/用户级覆盖（重置回退低一级）；内置模板不参与删除。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=用户级）
     * @param code      模板编码
     */
    void deleteByUserIdAndProjectIdAndCode(Long userId, Long projectId, String code);
}