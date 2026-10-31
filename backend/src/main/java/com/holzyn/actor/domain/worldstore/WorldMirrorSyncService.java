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

package com.holzyn.actor.domain.worldstore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 全局镜像刷新：把目录库的 12 张全局表覆盖写入世界库（V1.1.2-beta.1）。
 *
 * <p>为什么必须每次 open 都刷：世界库里的镜像就是"上次关世界那一刻的全局快照"。不刷新的话，
 * 把一个世界拷到另一台机器后，它会带着<strong>上一台机器</strong>的模型 provider、提示词模板、
 * 账户行运行——比"打不开世界"严重得多的静默错误。同时也正好实现了"全局配置改动
 * 在重开世界时生效"这条约定（U3）。</p>
 *
 * <p>为什么用裸 JDBC 而不是 JPA：镜像表同时被 Hibernate 实体映射（{@code ModelProvider}、
 * {@code PromptTemplate} 等），走 JPA 会牵涉一级缓存/脏检查，还要跟写守卫打架；而这里只是
 * 一次性的整表搬运，裸 JDBC 最直接，也最容易写成"删除+插入同一事务"。</p>
 *
 * <p>事务边界：写入端整体一个事务（删 12 张表 + 插回），失败回滚并抛出让挂载流程中断——
 * 宁可"世界打不开"，也不要"世界打开了但配置是空的"。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Service
public class WorldMirrorSyncService {

    /**
     * 需要按 {@code project_id} 过滤的双性质配置表（其余镜像表全量复制）。
     *
     * <p>这两张表的 {@code project_id} 可空：NULL = 用户级默认，非空 = 某世界的覆盖配置。
     * 世界库里只允许看到"用户级 + 自己"，否则会读到别的世界的覆盖配置。</p>
     */
    private static final java.util.Set<String> OWN_WORLD_SCOPED =
            java.util.Set.of("actor_model_provider", "actor_prompt_template");

    /**
     * 刷新镜像。
     *
     * @param registry  目录库数据源（权威）
     *
     * @param world     世界库数据源（被覆盖）
     *
     * @param projectId 该世界在 {@code actor_project} 里的 id（决定镜像哪一行）
     *
     * @return 同步统计
     */
    public MirrorSyncReport sync(DataSource registry, DataSource world, long projectId) {
        int tables = 0;
        long rows = 0;
        try (Connection rc = registry.getConnection(); Connection wc = world.getConnection()) {
            boolean oldAutoCommit = wc.getAutoCommit();
            wc.setAutoCommit(false);
            try {
                for (String table : MirrorTables.ALL) {
                    List<String> columns = commonColumns(wc, rc, table);
                    Long onlyId = MirrorTables.OWN_ROW_TABLE.equals(table) ? Long.valueOf(projectId) : null;
                    rows += copyTable(rc, wc, table, columns, onlyId, projectId);
                    tables++;
                }
                wc.commit();
            } catch (SQLException | RuntimeException e) {
                wc.rollback();
                throw e;
            } finally {
                wc.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("全局镜像刷新失败（projectId=" + projectId + "）", e);
        }
        log.info("[world-store] 镜像刷新完成 projectId={} 表={} 行={}", projectId, tables, rows);
        return new MirrorSyncReport(tables, rows);
    }

    /**
     * 复制一张表：先清空，再从目录库逐行插入。
     *
     * <p>双性质配置（{@code actor_model_provider}/{@code actor_prompt_template}）按设计 §3.6
     * 只镜像「用户级（{@code project_id is null}）+ 本世界覆盖」的行：别的世界写在本表里的
     * 覆盖配置不许串进这个世界，否则会出现"世界 A 的自定义供应商改掉了世界 B 的模型"。</p>
     *
     * @param onlyId 非 null 时只复制 {@code id = onlyId} 的行（{@code actor_project} 用）
     */
    private long copyTable(Connection regConn, Connection worldConn, String table,
                           List<String> columns, Long onlyId, long projectId) throws SQLException {
        String columnList = String.join(", ", columns);
        StringBuilder where = new StringBuilder();
        if (onlyId != null) {
            where.append(" where id = ").append(onlyId);
        } else if (OWN_WORLD_SCOPED.contains(table)) {
            where.append(" where project_id is null or project_id = ").append(projectId);
        }
        String select = "select " + columnList + " from " + table + where;

        JdbcRowCopier.deleteAll(worldConn, table);
        return JdbcRowCopier.copy(regConn, select, worldConn, table, columns).rows();
    }

    /**
     * 取该表在两边的公共列（以世界库列序为准）。
     *
     * <p>取交集而不是直接用世界库列：目录库可能因版本演进多了列（V101+），此时按交集插入
     * 仍能成功、新列留默认值——这比"插入时报列数不符"可诊断得多。</p>
     */
    private List<String> commonColumns(Connection worldConn, Connection regConn, String table) throws SQLException {
        List<String> columns = JdbcRowCopier.commonColumns(worldConn, regConn, table);
        if (columns.isEmpty()) {
            // 世界库缺这张表：说明基线不完整（V200 应含全部 113 张）。直接失败更好，
            // 否则会留下"镜像表不存在"的隐性缺口，等某条查询时才炸。
            throw new IllegalStateException("世界库缺少镜像表：" + table
                    + "（世界库 schema 与该版本不匹配）");
        }
        return columns;
    }

    /**
     * 镜像同步统计。
     *
     * @param tables 已同步表数（应为 12）
     * @param rows   已同步总行数
     */
    public record MirrorSyncReport(int tables, long rows) {
    }
}
