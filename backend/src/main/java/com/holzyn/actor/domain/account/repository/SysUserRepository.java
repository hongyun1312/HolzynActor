package com.holzyn.actor.domain.account.repository;

import com.holzyn.actor.domain.account.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 系统用户仓库。
 * <p>职责：提供 SysUser 实体的数据库访问，支持按 Casdoor 用户唯一标识查询（登录同步用）。</p>
 */
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    /** 按 Casdoor 用户唯一标识查询（登录 upsert 时使用） */
    Optional<SysUser> findByCasdoorUserId(String casdoorUserId);
}