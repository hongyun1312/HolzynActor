package com.holzyn.actor.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 废弃旧表/旧列清理（普通型人群重构的启动迁移）。
 * <p>职责：应用启动时（Hibernate ddl-auto=update 已建好新表之后）幂等清理已废弃的
 * 旧人群结构与旧分类字段——</p>
 * <ul>
 *   <li>2026-08-18 重构一：废弃 <code>actor_crowd</code> / <code>actor_crowd_member</code>（人群组+程序化池子）；
 *       新结构为单表 <code>actor_ordinary_npc</code>。</li>
 *   <li>2026-08-19 重构二（分类体系重构）：废弃「AI 职业分类」——删表 <code>actor_crowd_category</code>、
 *       删除 <code>actor_ordinary_npc</code> 的 category_l1/category_l2 旧列；
 *       分类改为由 AI 从 归属/职业/种族 中选 2 个字段聚合，字段标准数据存
 *       <code>actor_npc_field_dict</code>，主/次分类字段存 <code>actor_crowd_runtime</code>。</li>
 * </ul>
 * <p>幂等：<code>DROP ... IF EXISTS</code>，首次执行清理，后续为空操作；失败不阻断启动（告警放行）。</p>
 * <p>所属模块：common（通用-启动迁移）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OldCrowdTableCleaner implements ApplicationRunner {

    private final DataSource dataSource;

    /**
     * 启动时清理废弃旧表与旧列。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // 重构一：废弃人群组 + 程序化池子旧表
            st.execute("DROP TABLE IF EXISTS actor_crowd_member");
            st.execute("DROP TABLE IF EXISTS actor_crowd");
            // 重构二：废弃「AI 职业分类字典」表
            st.execute("DROP TABLE IF EXISTS actor_crowd_category");
            // 重构二：删除普通型 NPC 旧分类列前，先删旧实体声明的组合索引（idx_ordinary_npc_category
            // 引用了 category_l1/category_l2，Hibernate ddl-auto 不会删旧索引，直接 DROP COLUMN 会报
            // "Column may be referenced by index"；H2/MySQL 均支持 DROP INDEX IF EXISTS）
            st.execute("DROP INDEX IF EXISTS idx_ordinary_npc_category");
            st.execute("ALTER TABLE actor_ordinary_npc DROP COLUMN IF EXISTS category_l1");
            st.execute("ALTER TABLE actor_ordinary_npc DROP COLUMN IF EXISTS category_l2");
            log.info("[迁移] 已清理废弃旧表 actor_crowd / actor_crowd_member / actor_crowd_category 与普通型 NPC 旧分类列（分类体系重构为字段字典 + 主次分类字段）");
        } catch (Exception e) {
            // 清理失败不阻断启动（幂等，下次启动重试；仅记录）
            log.warn("[迁移] 清理废弃旧表失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }
}
