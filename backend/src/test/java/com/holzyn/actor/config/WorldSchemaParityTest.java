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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界数据分存档：基线与原 schema 的**逐列一致**校验（V1.1.2-beta.1 M0）。
 *
 * <p>为什么必须真跑数据库：两份基线是从 {@code db/legacy/V1~V7} 过滤生成的，最危险的失败
 * 不是"文件少一行"，而是"某条 alter 被漏掉"或"某张表被分错库"——前者让世界库少一列
 * （首次写入该列时 500），后者让世界库根本没有那张表。纯文本断言看不出这类问题。</p>
 *
 * <p>做法：三条独立的 H2 内存库，分别跑
 * ①legacy V1~V7 ②目录库 V100 ③世界库 V200，再用 JDBC 元数据逐表比对
 * 列（名/类型/长度/可空/默认值/自增）与索引（表+唯一性+列组合的多重集）。
 * 索引按"表+唯一性+列序"归一化比较，忽略 H2 自动生成的索引名
 * （{@code PRIMARY_KEY_n} 的 n 依赖建表顺序，直接比名字会产生假失败）。</p>
 *
 * <p>所属模块：test/config（系统底层 schema 治理）</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSchemaParityTest {

    /** 原 schema：V1~V7 全量跑一遍 */
    private static final String DB_LEGACY = "ws_parity_legacy";

    /** 目录库基线 */
    private static final String DB_GLOBAL = "ws_parity_global";

    /** 世界库基线 */
    private static final String DB_WORLD = "ws_parity_world";

    private Map<String, Map<String, String>> legacyColumns;
    private Map<String, Map<String, String>> globalColumns;
    private Map<String, Map<String, String>> worldColumns;

    private Map<String, Integer> legacyIndexes;
    private Map<String, Integer> globalIndexes;
    private Map<String, Integer> worldIndexes;

    private static String url(String name) {
        return "jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static void migrate(String db, String... locations) {
        Flyway.configure()
                .dataSource(url(db), "sa", "")
                .locations(locations)
                .load()
                .migrate();
    }

    /**
     * 迁移到指定版本为止。
     *
     * <p>V1.1.2-beta.1 起，目录库/世界库基线之后还有本版本自己的增量（V101 给 actor_project
     * 补存档索引列、V201 同步世界库镜像）。这些增量<b>故意</b>让两库与 legacy 不再逐列相等，
     * 而本测试的价值恰恰是"生成的基线与 legacy 逐列一致"。故这里显式把目标版本钉在基线上：
     * 基线漂移由本测试发现，增量由 {@code WorldIndexColumnsTest} 单独守护。</p>
     *
     * @param db        内存库名
     * @param target    目标版本（{@code 100} / {@code 200}）
     * @param locations 迁移位置
     */
    private static void migrateTo(String db, String target, String... locations) {
        Flyway.configure()
                .dataSource(url(db), "sa", "")
                .locations(locations)
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    @BeforeAll
    void applyAllThree() throws SQLException {
        migrate(DB_LEGACY, "classpath:db/legacy");
        migrateTo(DB_GLOBAL, "100", "classpath:db/migration/global");
        migrateTo(DB_WORLD, "200", "classpath:db/migration/world");

        legacyColumns = columns(DB_LEGACY);
        globalColumns = columns(DB_GLOBAL);
        worldColumns = columns(DB_WORLD);

        legacyIndexes = indexes(DB_LEGACY);
        globalIndexes = indexes(DB_GLOBAL);
        worldIndexes = indexes(DB_WORLD);
    }

    /** 表 → (列名 → 列特征串) */
    private static Map<String, Map<String, String>> columns(String db) throws SQLException {
        Map<String, Map<String, String>> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(url(db), "sa", "")) {
            DatabaseMetaData md = c.getMetaData();
            for (String t : userTables(md)) {
                out.put(t, new TreeMap<>());
            }
            for (String t : out.keySet()) {
                try (ResultSet rs = md.getColumns(null, null, t, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME").toLowerCase();
                        out.get(t).put(col, describeColumn(rs));
                    }
                }
            }
        }
        return out;
    }

    /** 只取 PUBLIC schema 下的用户表（H2 的 INFORMATION_SCHEMA 也会被 getTables 一并返回） */
    private static List<String> userTables(DatabaseMetaData md) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String t = rs.getString("TABLE_NAME").toLowerCase();
                if (schema == null || !schema.equalsIgnoreCase("public")) {
                    continue;
                }
                if (t.startsWith("flyway_")) {
                    continue;
                }
                out.add(t);
            }
        }
        return out;
    }

    private static String describeColumn(ResultSet rs) throws SQLException {
        return rs.getString("TYPE_NAME")
                + "(" + rs.getInt("COLUMN_SIZE") + "," + rs.getInt("DECIMAL_DIGITS") + ")"
                + " nullable=" + rs.getInt("NULLABLE")
                + " default=" + rs.getString("COLUMN_DEF")
                + " autoinc=" + rs.getString("IS_AUTOINCREMENT");
    }

    /** 索引多重集：key = 表|唯一性|列序列，value = 出现次数（忽略 H2 自动生成的索引名） */
    private static Map<String, Integer> indexes(String db) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(url(db), "sa", "")) {
            DatabaseMetaData md = c.getMetaData();
            List<String> tables = userTables(md);
            for (String t : tables) {
                Map<String, Boolean> uniqueByName = new LinkedHashMap<>();
                Map<String, List<String>> colsByName = new LinkedHashMap<>();
                try (ResultSet rs = md.getIndexInfo(null, null, t, false, false)) {
                    while (rs.next()) {
                        String idx = rs.getString("INDEX_NAME");
                        if (idx == null) {
                            continue;
                        }
                        uniqueByName.put(idx, !rs.getBoolean("NON_UNIQUE"));
                        colsByName.computeIfAbsent(idx, k -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
                    }
                }
                for (var e : colsByName.entrySet()) {
                    String key = t + "|unique=" + uniqueByName.get(e.getKey()) + "|" + String.join(",", e.getValue());
                    out.merge(key, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("表集合：legacy 113 张 = 目录库 16 + 世界库 113 的并集（且两库共 16 张）")
    void tableSetsAddUp() {
        assertEquals(113, legacyColumns.size(), "legacy 应有 113 张表");
        assertEquals(16, globalColumns.size(), "目录库应有 16 张表");
        assertEquals(113, worldColumns.size(), "世界库应有 113 张表");

        Set<String> union = new LinkedHashSet<>(globalColumns.keySet());
        union.addAll(worldColumns.keySet());
        assertEquals(new LinkedHashSet<>(legacyColumns.keySet()), union,
                "目录库∪世界库必须等于 legacy 全表集合，多=" + diff(union, legacyColumns.keySet())
                        + " 少=" + diff(legacyColumns.keySet(), union));

        Set<String> both = new LinkedHashSet<>(globalColumns.keySet());
        both.retainAll(worldColumns.keySet());
        assertEquals(16, both.size(), "两库共有的表应为 16 张（12 镜像 + 3 观测 + actor_schema_meta），实际=" + both);
        assertTrue(worldColumns.keySet().containsAll(globalColumns.keySet()),
                "目录库每张表都必须也在世界库里（世界库要能独立还原世界）");
    }

    @Test
    @DisplayName("逐列一致：每张表在其归属库里的列定义与 legacy 完全相同")
    void columnsMatchLegacyExactly() {
        List<String> problems = new ArrayList<>();
        for (String table : legacyColumns.keySet()) {
            Map<String, String> expected = legacyColumns.get(table);
            Map<String, String> fromGlobal = globalColumns.get(table);
            Map<String, String> fromWorld = worldColumns.get(table);
            if (fromGlobal != null) {
                collectDiff(problems, "目录库", table, expected, fromGlobal);
            }
            if (fromWorld != null) {
                collectDiff(problems, "世界库", table, expected, fromWorld);
            }
            if (fromGlobal == null && fromWorld == null) {
                problems.add(table + " 在两个基线里都不存在");
            }
        }
        assertTrue(problems.isEmpty(), "列定义漂移 " + problems.size() + " 处：\n  " + String.join("\n  ", problems));
    }

    private static void collectDiff(List<String> problems, String where, String table,
                                    Map<String, String> expected, Map<String, String> actual) {
        for (var e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) {
                problems.add(where + "." + table + " 缺列 " + e.getKey());
            } else if (!got.equals(e.getValue())) {
                problems.add(where + "." + table + "." + e.getKey() + " 期望[" + e.getValue() + "] 实际[" + got + "]");
            }
        }
        for (String extra : actual.keySet()) {
            if (!expected.containsKey(extra)) {
                problems.add(where + "." + table + " 多出列 " + extra);
            }
        }
    }

    @Test
    @DisplayName("索引一致：每张表的索引组合（唯一性+列序）与 legacy 完全相同")
    void indexesMatchLegacyExactly() {
        Set<String> tables = legacyIndexes.keySet().stream()
                .map(k -> k.split("\\|", 2)[0])
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertFalse(tables.isEmpty(), "未解析到任何索引（元数据读取失效）");

        List<String> problems = new ArrayList<>();
        for (String table : tables) {
            Map<String, Integer> expected = slice(legacyIndexes, table);
            Map<String, Integer> fromGlobal = slice(globalIndexes, table);
            Map<String, Integer> fromWorld = slice(worldIndexes, table);
            if (!globalColumns.containsKey(table) && !worldColumns.containsKey(table)) {
                continue;
            }
            if (globalColumns.containsKey(table)) {
                compareIndexSet(problems, "目录库", table, expected, fromGlobal);
            }
            if (worldColumns.containsKey(table)) {
                compareIndexSet(problems, "世界库", table, expected, fromWorld);
            }
        }
        assertTrue(problems.isEmpty(), "索引漂移 " + problems.size() + " 处：\n  " + String.join("\n  ", problems));
    }

    private static Map<String, Integer> slice(Map<String, Integer> all, String table) {
        Map<String, Integer> out = new TreeMap<>();
        String prefix = table + "|";
        all.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k.substring(prefix.length()), v);
            }
        });
        return out;
    }

    private static void compareIndexSet(List<String> problems, String where, String table,
                                        Map<String, Integer> expected, Map<String, Integer> actual) {
        expected.forEach((k, v) -> {
            Integer got = actual.get(k);
            if (got == null) {
                problems.add(where + "." + table + " 缺索引 " + k);
            } else if (!got.equals(v)) {
                problems.add(where + "." + table + " 索引次数不符 " + k + " 期望" + v + " 实际" + got);
            }
        });
        actual.forEach((k, v) -> {
            if (!expected.containsKey(k)) {
                problems.add(where + "." + table + " 多出索引 " + k);
            }
        });
    }

    private static <T> Set<T> diff(Set<T> a, Set<T> b) {
        Set<T> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
