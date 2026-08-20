package com.holzyn.actor.domain.project.repository;

import com.holzyn.actor.domain.project.entity.ActorProject;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * 项目仓库（actor_project）。
 * <p>职责：提供按用户归属查询与软删除过滤的项目访问能力。</p>
 * <p>所属模块：repository（数据访问层）</p>
 */
public interface ActorProjectRepository extends JpaRepository<ActorProject, Long> {

    /**
     * 查询某用户未删除的项目列表（按更新时间倒序）。
     *
     * @param userId  归属用户 ID
     * @param deleted 软删除标记（0 正常）
     * @return 项目列表
     */
    List<ActorProject> findByUserIdAndDeletedOrderByUpdatedAtDesc(Long userId, Integer deleted);

    /**
     * 按 id + 用户 + 未删除查询（归属校验 + 软删除过滤）。
     *
     * @param id      项目主键
     * @param userId  归属用户 ID
     * @param deleted 软删除标记（0 正常）
     * @return 匹配的项目（可能为空）
     */
    Optional<ActorProject> findByIdAndUserIdAndDeleted(Long id, Long userId, Integer deleted);

    /**
     * 按项目 UUID 查询（.holzyn 导入幂等检测依据）。
     *
     * @param projectUid 项目 UUID
     * @return 匹配的项目（可能为空）
     */
    Optional<ActorProject> findByProjectUid(String projectUid);
}