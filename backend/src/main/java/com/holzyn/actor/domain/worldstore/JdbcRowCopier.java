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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跨库逐行复制（裸 JDBC），世界镜像刷新与一次性迁移<b>共用同一份实现</b>。
 *
 * <p>为什么不各写一份：真正会出错的地方不是"循环 select/insert"，而是<b>类型绑定</b>——
 * H2 的 {@code LONGTEXT}（JDBC LONGVARCHAR）、{@code JSON}、{@code BLOB}、{@code TIMESTAMP(6)}
 * 用 {@code setObject} 绑定会因驱动差异丢精度或报类型不匹配。这套 {@code bind(...)} 一旦出现
 * 两个副本，就必然出现"镜像同步好使、迁移报错"这类只在真实库上才暴露的分裂。</p>
 *
 * <p>列集合取<b>交集</b>（以目标库列序为准）：目录库可能因版本演进多出新列（V101+），
 * 按交集插入时新列留默认值——比"插入时报列数不符"可诊断得多。</p>
 *
 * <p><b>可空性漂移的处理</b>（M3 真实库踩出来的）：目标库（Flyway 基线，手工写的 DDL）可能把
 * 某列声明为 {@code NOT NULL}，而源库（历史 ddl-auto 建出）里同列可空、并且<b>确实存了 NULL</b>。
 * 实测命中一处：{@code actor_world_clock.dropped_game_seconds}（实体写死 {@code nullable=false}
 * 且默认 0，但旧行是 NULL）。直接把 NULL 绑进 NOT NULL 列会让整次迁移在最后一步失败。
 * 处理方式是"<b>数值/布尔列补 0 并记账，其余类型直接失败</b>"：</p>
 * <ul>
 *   <li>补 0 有实体默认值背书（{@code = 0L}，语义"正常稳态恒为 0"），不是凭空发明数据；</li>
 *   <li>每次补值都计入 {@link CopyResult#coercedNulls()}，最终写进迁移报告 —— 不静默改写；</li>
 *   <li>文本/时间列不允许猜（补 {@code ''} 或"当前时间"都是编造），一律抛错让人决定。</li>
 * </ul>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
final class JdbcRowCopier {

    private JdbcRowCopier() {
    }

    /**
     * 复制结果。
     *
     * @param rows         复制的行数
     * @param coercedNulls 因"目标库 NOT NULL 而源库为 NULL"被补成 0/false 的列 → 次数
     */
    record CopyResult(long rows, Map<String, Long> coercedNulls) {

        /** 合并两批结果（多表累加用） */
        CopyResult plus(CopyResult other) {
            long sum = rows + other.rows;
            if (coercedNulls.isEmpty()) {
                return new CopyResult(sum, other.coercedNulls);
            }
            if (other.coercedNulls.isEmpty()) {
                return new CopyResult(sum, coercedNulls);
            }
            Map<String, Long> merged = new LinkedHashMap<>(coercedNulls);
            other.coercedNulls.forEach((k, v) -> merged.merge(k, v, Long::sum));
            return new CopyResult(sum, merged);
        }
    }

    /**
     * 在目标库执行 {@code delete from <table>}（清空用；调用方负责事务）。
     *
     * @param target 目标连接
     * @param table  表名
     *
     * @return 被删除的行数（JDBC 不保证，仅作日志参考）
     *
     * @throws SQLException 执行失败
     */
    static int deleteAll(Connection target, String table) throws SQLException {
        try (Statement del = target.createStatement()) {
            return del.executeUpdate("delete from " + table);
        }
    }

    /**
     * 按一条 select 把行复制进目标表（不负责事务与清空）。
     *
     * @param source    读源连接
     * @param selectSql 读源 SQL（列集合由 {@link #commonColumns} 决定，必须是同一批列）
     * @param target    目标连接
     * @param table     目标表名
     * @param columns   目标列名（顺序与 select 的列顺序必须一致）
     *
     * @return 复制结果（行数 + 补值记账）
     *
     * @throws SQLException 读写失败
     * @throws IllegalStateException 非数值列出现"源 NULL / 目标 NOT NULL"（必须人工决定）
     */
    static CopyResult copy(Connection source, String selectSql, Connection target,
                           String table, List<String> columns) throws SQLException {
        return copy(source, selectSql, null, target, table, columns);
    }

    /**
     * 同上，但把读源 SQL 里唯一的 {@code ?} 绑成世界 id（切分规则统一只有一个占位参数）。
     *
     * @param source      读源连接
     * @param selectSql   读源 SQL（含一个 {@code ?}）
     * @param sourceParam {@code ?} 的绑定值（null = 无占位参数）
     * @param target      目标连接
     * @param table       目标表名
     * @param columns     目标列名（顺序与 select 的列顺序必须一致）
     *
     * @return 复制结果（行数 + 补值记账）
     *
     * @throws SQLException 读写失败
     */
    static CopyResult copy(Connection source, String selectSql, Long sourceParam, Connection target,
                           String table, List<String> columns) throws SQLException {
        if (columns.isEmpty()) {
            throw new SQLException("复制的列集合为空：" + table);
        }
        Set<String> strict = notNullWithoutDefault(target, table);
        Map<String, Long> coerced = new LinkedHashMap<>();
        String insert = "insert into " + table
                + " (" + String.join(", ", columns) + ") values ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        long count = 0;
        try (PreparedStatement selectSt = source.prepareStatement(selectSql);
             PreparedStatement ps = target.prepareStatement(insert)) {
            if (sourceParam != null) {
                selectSt.setLong(1, sourceParam);
            }
            try (ResultSet rs = selectSt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    for (int i = 1; i <= columns.size(); i++) {
                        bind(ps, i, rs, meta, i, table, columns.get(i - 1), strict, coerced);
                    }
                    ps.addBatch();
                    count++;
                }
            }
            if (count > 0) {
                ps.executeBatch();
            }
        }
        return new CopyResult(count, coerced);
    }

    /**
     * 取某表在两个库里的公共列（以目标库列序为准）。
     *
     * @param target 目标连接（决定列序）
     * @param source 读源连接
     * @param table  表名
     *
     * @return 公共列；目标库没有该表时返回空表
     *
     * @throws SQLException 元数据读取失败
     * @throws IllegalStateException 目标库有该表但读源没有（schema 不匹配，必须大声失败）
     */
    static List<String> commonColumns(Connection target, Connection source, String table) throws SQLException {
        List<String> targetCols = columnsOf(target, table);
        if (targetCols.isEmpty()) {
            return List.of();
        }
        List<String> sourceCols = columnsOf(source, table);
        if (sourceCols.isEmpty()) {
            throw new IllegalStateException("读源库缺少表：" + table + "（schema 与该版本不匹配）");
        }
        List<String> out = new ArrayList<>(targetCols.size());
        for (String c : targetCols) {
            if (sourceCols.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 读某表的列名（按 ordinal_position 升序）。
     *
     * @param conn  连接
     * @param table 表名（大小写不敏感）
     *
     * @return 小写列名；表不存在返回空表
     *
     * @throws SQLException 元数据读取失败
     */
    static List<String> columnsOf(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        String sql = "select column_name from information_schema.columns "
                + "where upper(table_schema) = 'PUBLIC' and lower(table_name) = ? "
                + "order by ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return cols;
    }

    /** 目标表里"NOT NULL 且无默认值、非自增"的列（这些列不能接收 NULL） */
    private static Set<String> notNullWithoutDefault(Connection target, String table) throws SQLException {
        Set<String> out = new TreeSet<>();
        String sql = "select column_name from information_schema.columns "
                + "where upper(table_schema) = 'PUBLIC' and lower(table_name) = ? "
                + "and is_nullable = 'NO' and is_identity = 'NO' and column_default is null";
        try (PreparedStatement ps = target.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /**
     * 目标表里"NOT NULL 且无默认值"的<b>数值</b>列——也就是复制时会把源 NULL 补成 0 的列。
     *
     * <p>给校验和用：补值是刻意的策略变更，源库那一格是 NULL、世界库是 0，
     * 若不共享同一份名单，校验和会把"已记账的补值"报成"复制出错"。名单必须与
     * {@link #bind} 的补值分支同源，否则两处口径一漂就变成误报。</p>
     *
     * @param target 目标库连接
     * @param table  表名
     *
     * @return 列名集合（小写）
     *
     * @throws SQLException 元数据读取失败
     */
    static Set<String> numericNotNullWithoutDefault(Connection target, String table) throws SQLException {
        Set<String> out = new TreeSet<>();
        String sql = "select column_name from information_schema.columns "
                + "where upper(table_schema) = 'PUBLIC' and lower(table_name) = ? "
                + "and is_nullable = 'NO' and is_identity = 'NO' and column_default is null "
                + "and data_type in ('BIGINT','INTEGER','SMALLINT','TINYINT',"
                + "'NUMERIC','DECIMAL','DOUBLE','FLOAT','REAL')";
        try (PreparedStatement ps = target.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /**
     * 按列类型选择绑定方式；顺带处理"源 NULL / 目标 NOT NULL"的漂移。
     *
     * @param ps        目标语句
     * @param index     目标参数下标（1 起）
     * @param rs        读源游标
     * @param meta      读源元数据
     * @param col       读源列下标（1 起）
     * @param table     表名（错误信息用）
     * @param column    列名（错误信息与记账用）
     * @param strict    目标库 NOT NULL 列集合
     * @param coerced   补值记账（列名 → 次数）
     *
     * @throws SQLException 绑定失败
     */
    private static void bind(PreparedStatement ps, int index, ResultSet rs, ResultSetMetaData meta, int col,
                             String table, String column, Set<String> strict, Map<String, Long> coerced)
            throws SQLException {
        int type = meta.getColumnType(col);
        switch (type) {
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                 Types.VARCHAR, Types.NVARCHAR, Types.CHAR, Types.NCHAR -> {
                String value = rs.getString(col);
                if (value == null && strict.contains(column)) {
                    throw notNullDrift(table, column, "文本");
                }
                ps.setString(index, value);
            }
            case Types.BLOB, Types.LONGVARBINARY, Types.VARBINARY, Types.BINARY -> {
                byte[] value = rs.getBytes(col);
                if (value == null && strict.contains(column)) {
                    throw notNullDrift(table, column, "二进制");
                }
                ps.setBytes(index, value);
            }
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT,
                 Types.NUMERIC, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.REAL -> {
                Object value = rs.getObject(col);
                if (value == null && strict.contains(column)) {
                    // 数值列补 0：实体侧默认值就是 0（如 dropped_game_seconds 的 "= 0L"），
                    // 补值语义安全且必须记账，不能静默
                    coerced.merge(column, 1L, Long::sum);
                    ps.setObject(index, 0);
                } else {
                    ps.setObject(index, value);
                }
            }
            case Types.BOOLEAN, Types.BIT -> {
                Object value = rs.getObject(col);
                if (value == null && strict.contains(column)) {
                    coerced.merge(column, 1L, Long::sum);
                    ps.setObject(index, Boolean.FALSE);
                } else {
                    ps.setObject(index, value);
                }
            }
            default -> {
                Object value = rs.getObject(col);
                if (value == null && strict.contains(column)) {
                    throw notNullDrift(table, column, "其它类型");
                }
                ps.setObject(index, value);
            }
        }
    }

    private static IllegalStateException notNullDrift(String table, String column, String kind) {
        return new IllegalStateException("列 " + table + "." + column + " 在目标库是 NOT NULL，"
                + "但源库这一行是 NULL（" + kind + "列不做自动补值：补 '' 或时间都是编造数据）。"
                + "请先决定该列的空值语义，再补一条迁移（V2xx）或清洗源数据后重试。");
    }
}
