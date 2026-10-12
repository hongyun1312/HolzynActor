/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 记忆系统分层重构 一次性迁移器（，启动期幂等迁移）。
 * <p>职责：把旧记忆表结构迁移到新四层结构——<b>actor_memory 旧 kind 列 → 新 type 列</b>：
 * ① kind→type 回填（observation→episodic / reflection→reflection / plan→plan / fact·summary→semantic）；
 * ② 会话摘要 context_json 打 {@code {"subtype":"summary"}} 标记（与语义事实同 type、子类型区分）；
 * ③ source 默认 import（旧数据来源标记）；④ version 默认 1；⑤ <b>删除旧 kind 列</b>（不再沿用 kind 体系）。</p>
 * <p>执行时机：Hibernate ddl-auto=update 已加 type 列 + 已建三张新表（working/core/archive）之后、
 * 数据库结构校验器（@Order(2)）之后（@Order(3)）；<b>幂等</b>（kind 列不存在=已迁移直接跳过）、
 * <b>失败不阻断启动</b>（WARN + 下次启动重试），对齐项目既有迁移/校验器哲学。</p>
 * <p>三张新表（actor_memory_working / actor_core_memory / actor_memory_archive）由 JPA ddl-auto 自动创建，
 * 本迁移器不负责建表；已软删（deleted=1）记忆批量迁入归档为 范围（设计 §12.2 可选，本轮不做）。</p>
 */
@Slf4j
@Component
@Order(3)
public class MemoryTierMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public MemoryTierMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 启动执行入口（失败不阻断启动，幂等可重跑）。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[记忆分层迁移] 迁移失败（不阻断启动，下次启动重试）：{}", e.getMessage());
        }
    }

    /**
     * 迁移核心（可单测调用）：kind→type 回填 + 摘要标记 + 默认值 + DROP kind。
     */
    public void migrate() {
        // 幂等：kind 列不存在 = 已迁移或新库无需迁移
        if (!hasColumn("actor_memory", "kind")) {
            log.info("[记忆分层迁移] actor_memory.kind 列不存在（已迁移或新库），跳过");
            return;
        }
        int total = countRows("actor_memory");

        // ① kind→type 回填（新 type 列由 ddl-auto 添加，仅回填空值行）
        int typeUpdated = jdbcTemplate.update(
                "UPDATE actor_memory SET type = CASE kind "
                        + "WHEN 'observation' THEN '" + ActorMemoryTypes.TYPE_EPISODIC + "' "
                        + "WHEN 'reflection' THEN '" + ActorMemoryTypes.TYPE_REFLECTION + "' "
                        + "WHEN 'plan' THEN '" + ActorMemoryTypes.TYPE_PLAN + "' "
                        + "ELSE '" + ActorMemoryTypes.TYPE_SEMANTIC + "' END "
                        + "WHERE type IS NULL OR type = ''");

        // ② 会话摘要 context_json subtype 标记（fact/summary 均映射 semantic，摘要靠子类型区分）
        int summaryMarked = jdbcTemplate.update(
                "UPDATE actor_memory SET context_json = ? WHERE kind = 'summary' "
                        + "AND (context_json IS NULL OR context_json = '')",
                ActorMemoryTypes.CONTEXT_SUMMARY_JSON);

        // ③ source 默认 import（旧数据来源标记）
        int sourceUpdated = jdbcTemplate.update(
                "UPDATE actor_memory SET source = '" + ActorMemoryTypes.SOURCE_IMPORT
                        + "' WHERE source IS NULL OR source = ''");

        // ④ version 默认 1
        int versionUpdated = jdbcTemplate.update("UPDATE actor_memory SET version = 1 WHERE version IS NULL");

        // ⑤ 删除旧 kind 列（不再沿用 kind 体系）
        jdbcTemplate.execute("ALTER TABLE actor_memory DROP COLUMN kind");

        log.info("[记忆分层迁移] 完成：actor_memory 共 {} 行，type 回填 {}，摘要标记 {}，source 默认 {}，version 默认 {}；"
                + "已删除旧 kind 列（M1 起 type 取代 kind）", total, typeUpdated, summaryMarked, sourceUpdated, versionUpdated);
    }

    /**
     * 判定表是否存在指定列（information_schema，H2/MySQL 兼容）。
     */
    private boolean hasColumn(String table, String column) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    /**
     * 行数统计（迁移日志用）。
     */
    private int countRows(String table) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
