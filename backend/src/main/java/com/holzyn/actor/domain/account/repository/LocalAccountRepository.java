package com.holzyn.actor.domain.account.repository;

import com.holzyn.actor.domain.account.entity.LocalAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 本地个人账户数据访问接口。
 * <p>职责：提供 LocalAccount 实体的数据库访问，按归属用户查询（本地单用户 id=1）。</p>
 * <p>所属模块：domain/account/repository（本地账户功能域-仓储层）</p>
 */
public interface LocalAccountRepository extends JpaRepository<LocalAccount, Long> {

    /**
     * 按归属用户查询本地账户（单用户模式恒查 userId=1）。
     *
     * @param userId 归属用户 ID
     * @return 本地账户（不存在时为空）
     */
    Optional<LocalAccount> findByUserId(Long userId);
}
