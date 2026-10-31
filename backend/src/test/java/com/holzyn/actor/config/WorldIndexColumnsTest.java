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

package com.holzyn.actor.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录库 / 世界库的「存档索引列」增量迁移校验（V1.1.2-beta.1 M2）。
 *
 * <p>为什么必须真跑数据库：V101/V201 是手写的 {@code alter table ... add column if not exists}，
 * 语法错、列名少写、默认值写反都不会被任何编译期检查发现；而它们的失败方式极其难受——
 * 目录库缺列 → 存档列表 500；世界库缺列 → 进世界后随便点一个页面就 500（读实体缺列）。</p>
 *
 * <p>三条断言：</p>
 * <ol>
 *   <li>V100+V101 跑完后 12 个索引列齐全，{@code world_status} 默认 {@code closed}，
 *       {@code uk_project_uid} 是唯一索引；</li>
 *   <li>V200+V201 跑完后，两库 {@code actor_project} 的列集合<b>逐列相同</b>
 *       （同一个 Hibernate 实体在两个库里都要能读，缺一列就是运行期 500）；</li>
 *   <li>不指定 {@code world_status} 插入的行落到 {@code closed}（历史行被默认值补齐）。</li>
 * </ol>
 *
 * <p>所属模块：test/config（系统底层 schema 治理）</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldIndexColumnsTest {

    private static final String DB_REGISTRY = "ws_index_registry";

    private static final String DB_WORLD = "ws_index_world";

    /** V101/V201 新增的 12 列 */
    private static final List<String> INDEX_COLUMNS = List.of(
            "data_dir_name", "last_opened_at", "last_closed_at", "game_second", "character_count",
            "size_bytes", "world_status", "manifest_json", "token_in_total", "token_out_total",
            "ai_call_count", "schema_version");

    private static String url(String name) {
        return "jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    @BeforeAll
    void applyMigrations() {
        // 不带 target：跑到最新（含 V101 / V201），与真实启动一致
        Flyway.configure().dataSource(url(DB_REGISTRY), "sa", "")
                .locations("classpath:db/migration/global").load().migrate();
        Flyway.configure().dataSource(url(DB_WORLD), "sa", "")
                .locations("classpath:db/migration/world").load().migrate();
    }

    @Test
    @DisplayName("目录库 V100+V101：12 个索引列齐全 + world_status 默认 closed + uk_project_uid 唯一")
    void registryHasIndexColumns() throws SQLException {
        Set<String> cols = columns(DB_REGISTRY, "actor_project");
        for (String c : INDEX_COLUMNS) {
            assertTrue(cols.contains(c), "目录库 actor_project 缺列 " + c + "（V101 未生效），实际列=" + cols);
        }
        assertEquals("'closed'", columnDefault(DB_REGISTRY, "actor_project", "world_status"),
                "world_status 默认值必须是 'closed'：历史行迁移后应直接可用");

        List<String> uniqueIndexes = uniqueIndexes(DB_REGISTRY, "actor_project");
        assertTrue(uniqueIndexes.stream().anyMatch(k -> k.contains("project_uid")),
                "project_uid 必须有唯一索引（否则同一目录会被收编成两行），实际唯一索引=" + uniqueIndexes);
    }

    @Test
    @DisplayName("世界库 V200+V201：actor_project 与目录库逐列同构（缺列 = 进世界后读实体 500）")
    void worldMirrorColumnsMatchRegistry() throws SQLException {
        Set<String> registry = columns(DB_REGISTRY, "actor_project");
        Set<String> world = columns(DB_WORLD, "actor_project");
        assertEquals(new TreeSet<>(registry), new TreeSet<>(world),
                "两库 actor_project 列必须完全相同；差集 目录库多=" + minus(registry, world)
                        + " 世界库多=" + minus(world, registry));
        for (String c : INDEX_COLUMNS) {
            assertTrue(world.contains(c), "世界库 actor_project 缺列 " + c + "（V201 未生效）");
        }
    }

    @Test
    @DisplayName("默认值生效：不指定 world_status 插入的行落到 closed")
    void worldStatusDefaultsToClosed() throws SQLException {
        try (Connection c = DriverManager.getConnection(url(DB_REGISTRY), "sa", "");
             Statement st = c.createStatement()) {
            st.executeUpdate("insert into actor_project (user_id, name, project_uid) "
                    + "values (1, '默认值测试世界', 'uid-default-status')");
        }
        try (Connection c = DriverManager.getConnection(url(DB_REGISTRY), "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select world_status from actor_project where project_uid = 'uid-default-status'")) {
            assertTrue(rs.next(), "插入的行读不回来");
            assertEquals("closed", rs.getString(1), "world_status 默认值未生效（历史行会显示成什么状态都说不准）");
        }
    }

    // ------------------------------------------------------------------ 元数据工具

    private static Set<String> columns(String db, String table) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(url(db), "sa", "");
             ResultSet rs = c.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return out;
    }

    private static String columnDefault(String db, String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(db), "sa", "");
             ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next() ? rs.getString("COLUMN_DEF") : null;
        }
    }

    /** 唯一索引的"列序列"描述（忽略 H2 自动生成的索引名） */
    private static List<String> uniqueIndexes(String db, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url(db), "sa", "")) {
            DatabaseMetaData md = c.getMetaData();
            java.util.Map<String, List<String>> byName = new java.util.LinkedHashMap<>();
            java.util.Map<String, Boolean> unique = new java.util.LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(null, null, table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name == null) {
                        continue;
                    }
                    unique.put(name, !rs.getBoolean("NON_UNIQUE"));
                    byName.computeIfAbsent(name, k -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
                }
            }
            byName.forEach((name, cols) -> {
                if (Boolean.TRUE.equals(unique.get(name))) {
                    out.add(name + "(" + String.join(",", cols) + ")");
                }
            });
        }
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }
}
