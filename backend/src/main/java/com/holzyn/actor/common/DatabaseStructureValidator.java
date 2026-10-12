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

package com.holzyn.actor.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.SelectableConsumer;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 数据库结构校验器（轮，用户需求：校验数据库结构是否符合最新结构，
 * 不符合则修改、符合则不作修改，并输出日志）。
 * <p>职责：应用启动时（Hibernate ddl-auto=update 已建表之后）把<b>当前数据库实际结构</b>
 * 与<b>最新实体映射结构</b>（Hibernate 运行时映射元模型 {@code MappingMetamodel} = 权威「最新结构」）比对：</p>
 * <ul>
 * <li><b>符合</b>：输出 {@code [数据库] 结构校验通过：N 张实体表结构与最新版本一致，无需修改}；</li>
 * <li><b>不符合</b>：输出差异清单（缺失表 / 缺失列），并<b>自动修复</b>——
 * 缺失列执行 {@code ALTER TABLE ... ADD COLUMN}（列类型取 Hibernate 方言 SQL 类型）、
 * 缺失表执行 {@code CREATE TABLE}（列 + 主键取自实体映射），修复后<b>复检</b>并输出结果；</li>
 * <li><b>多余表/列</b>：仅 INFO 提示（不删除——废弃清理由 {@code OldCrowdTableCleaner} 处理已知废弃项，
 * ddl-auto=update 本就不删列，保持兼容）；</li>
 * <li><b>失败</b>：WARN 且<b>不阻断启动</b>（幂等，下次启动重试，对齐 OldCrowdTableCleaner 语义）。</li>
 * </ul>
 * <p>实际结构经 JDBC {@code information_schema} 读取（H2 MODE=MySQL 与远程 MySQL 均兼容）；
 * 期望结构经 {@code SessionFactory.getMetamodel()}（Hibernate 7.4 运行时映射元模型）读取，
 * 读取失败时降级跳过（WARN），不影响启动。</p>
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class DatabaseStructureValidator implements ApplicationRunner {

    private final DataSource dataSource;
    private final SessionFactory sessionFactory;

    /**
     * 期望的表结构（实体映射 = 最新版本权威来源）。
     *
     * @param name 表名
     *
     * @param columns 期望列（列名 → 列定义[类型/nullable]）
     *
     * @param pk 主键列名（有序；仅用于缺失表 CREATE TABLE）
     */
    record ExpectedTable(String name, LinkedHashMap<String, ExpectedColumn> columns, List<String> pk) {
    }

    /**
     * 期望列定义。
     *
     * @param name 列名
     *
     * @param sqlType Hibernate 方言 SQL 类型（如 varchar(255)/bigint/timestamp(6)）
     *
     * @param nullable 是否可空（仅修复 ALTER 时附加 NOT NULL）
     */
    record ExpectedColumn(String name, String sqlType, boolean nullable) {
    }

    /** 项（缺失列/缺失表统一描述，便于日志与执行） */
    record Repair(String type, String table, String column, String sqlType, String ddl) {
    }

    /**
     * 启动时校验并（必要时）修复数据库结构。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            Map<String, ExpectedTable> expected = loadExpectedSchema();
            if (expected == null || expected.isEmpty()) {
                log.warn("[数据库] 结构校验跳过：未能读取实体映射元数据（不影响启动）");
                return;
            }
            Map<String, Set<String>> actual = loadActualColumns();
            List<Repair> repairs = diff(expected, actual);
            if (repairs.isEmpty()) {
                log.info("[数据库] 结构校验通过：{} 张实体表结构与最新版本一致，无需修改", expected.size());
                logObsolete(expected, actual);
                return;
            }
            // 存在差异 → 输出差异并自动修复
            log.warn("[数据库] 结构校验发现差异 {} 处（缺失表 {} 张 / 缺失列 {} 处），开始自动修复...",
                    repairs.size(),
                    repairs.stream().filter(r -> "table".equals(r.type())).count(),
                    repairs.stream().filter(r -> "column".equals(r.type())).count());
            for (Repair r : repairs) {
                log.warn("[数据库]   差异：{} {}", r.type().equals("table") ? "缺失表" : "缺失列",
                        r.type().equals("table") ? r.table() : (r.table() + "." + r.column()));
            }
            int fixed = applyRepairs(repairs);
            // 后复检：重新读取实际结构，确认差异已消除
            Map<String, Set<String>> actualAfter = loadActualColumns();
            List<Repair> remaining = diff(expected, actualAfter);
            if (remaining.isEmpty()) {
                log.info("[数据库] 结构校验完成：已修复 {} 处差异，复检通过（数据库结构已与最新版本一致）", fixed);
            } else {
                log.warn("[数据库] 结构校验完成：修复 {} 处差异，仍有 {} 处未能自动修复（{}），请人工检查",
                        fixed, remaining.size(),
                        remaining.stream().map(r -> r.type().equals("table") ? r.table()
                                : r.table() + "." + r.column()).reduce((a, b) -> a + "," + b).orElse("-"));
            }
            logObsolete(expected, actualAfter);
        } catch (Exception e) {
            // 校验失败不阻断启动（幂等，下次启动重试）
            log.warn("[数据库] 结构校验失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 读取期望结构：Hibernate 运行时映射元模型（{@code SessionFactory.getMetamodel()} →
     * {@code MappingMetamodel.forEachEntityDescriptor} → 实体持久化描述符：
     * 表名 + 主键列 + 全部属性列（经 {@code forEachSelectable} 取 SelectableMapping 列名与 JDBC 类型码）。
     * <p>注：Hibernate 7.4 不支持 {@code unwrap(MetadataImplementor.class)}（运行时返回
     * "cannot unwrap EntityManagerFactory as MetadataImplementor"），故改走公开的运行时映射元模型。</p>
     *
     * @return 表名（小写） → 期望表结构；元数据不可用时返回空 Map
     */
    Map<String, ExpectedTable> loadExpectedSchema() {
        // SessionFactory 接口 getMetamodel() 编译期返回 jakarta Metamodel；Hibernate 实现运行时即
        // MappingMetamodel（公开 API），显式转型后走 forEachEntityDescriptor
        MappingMetamodel metamodel = (MappingMetamodel) sessionFactory.getMetamodel();
        if (metamodel == null) {
            return Collections.emptyMap();
        }
        Map<String, ExpectedTable> result = new LinkedHashMap<>();
        metamodel.forEachEntityDescriptor(persister -> {
            String tableName = persister.getTableName();
            if (tableName == null || tableName.isBlank()) {
                return;
            }
            LinkedHashMap<String, ExpectedColumn> cols = new LinkedHashMap<>();
            List<String> pk = new ArrayList<>();
            // 主键列（根表）
            for (String idCol : persister.getIdentifierColumnNames()) {
                pk.add(idCol);
                cols.putIfAbsent(idCol.toLowerCase(), new ExpectedColumn(idCol, null, false));
            }
            // 属性列（仅归属根表；经 SelectableMapping 取列名 + JDBC 类型码供修复）
            SelectableConsumer collect = (index, selectable) -> {
                if (selectable.isFormula()) {
                    return;
                }
                String colName = selectable.getSelectableName();
                if (colName == null || colName.isBlank()) {
                    return;
                }
                cols.putIfAbsent(colName.toLowerCase(),
                        new ExpectedColumn(colName, resolveSqlType(selectable), true));
            };
            persister.forEachAttributeMapping(attr -> {
                String containing = attr.getContainingTableExpression();
                if (containing != null && !containing.equalsIgnoreCase(tableName)) {
                    return; // 次级表（本仓库无 @SecondaryTable，防御性跳过）
                }
                attr.forEachSelectable(collect);
            });
            result.put(tableName.toLowerCase(), new ExpectedTable(tableName, cols, pk));
        });
        return result;
    }

    /**
     * 解析列 SQL 类型：从 SelectableMapping 的 JDBC 类型码映射 H2/MySQL 通用类型（修复 DDL 用）。
     *
     * @param selectable 可选项映射
     *
     * @return 可执行的 SQL 类型串（永不为 null；映射失败回退 varchar(255)）
     */
    private static String resolveSqlType(SelectableMapping selectable) {
        try {
            JdbcMapping jm = selectable.getJdbcMapping();
            if (jm != null) {
                JdbcType jdbcType = jm.getJdbcType();
                if (jdbcType != null) {
                    String mapped = JDBC_TYPE_FALLBACK.get(jdbcType.getDdlTypeCode());
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        } catch (Exception ignored) {
            // 个别列类型解析失败 → 通用兜底
        }
        return "varchar(255)";
    }

    /** JDBC 类型码 → H2/MySQL 通用兜底类型（SelectableMapping 类型解析失败时用） */
    private static final Map<Integer, String> JDBC_TYPE_FALLBACK = Map.ofEntries(
            Map.entry(java.sql.Types.BIGINT, "bigint"),
            Map.entry(java.sql.Types.INTEGER, "integer"),
            Map.entry(java.sql.Types.SMALLINT, "smallint"),
            Map.entry(java.sql.Types.BOOLEAN, "boolean"),
            Map.entry(java.sql.Types.DOUBLE, "double precision"),
            Map.entry(java.sql.Types.FLOAT, "double precision"),
            Map.entry(java.sql.Types.NUMERIC, "numeric(19,2)"),
            Map.entry(java.sql.Types.DECIMAL, "decimal(19,2)"),
            Map.entry(java.sql.Types.VARCHAR, "varchar(255)"),
            Map.entry(java.sql.Types.CHAR, "char(1)"),
            Map.entry(java.sql.Types.CLOB, "clob"),
            Map.entry(java.sql.Types.LONGVARCHAR, "clob"),
            Map.entry(java.sql.Types.DATE, "date"),
            Map.entry(java.sql.Types.TIME, "time"),
            Map.entry(java.sql.Types.TIMESTAMP, "timestamp(6)"),
            Map.entry(java.sql.Types.BLOB, "blob"),
            Map.entry(java.sql.Types.VARBINARY, "varbinary(255)")
    );

    /**
     * 读取实际结构：information_schema（H2/MySQL 兼容，仅 BASE TABLE 用户表）。
     *
     * @return 表名（小写） → 列名集合（小写）
     */
    Map<String, Set<String>> loadActualColumns() {
        Map<String, Set<String>> result = new TreeMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT table_name, column_name, table_schema FROM information_schema.columns")) {
            while (rs.next()) {
                String schema = rs.getString("table_schema");
                if (schema != null && schema.toUpperCase().contains("INFORMATION_SCHEMA")) {
                    continue;
                }
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                if (table == null || column == null) {
                    continue;
                }
                result.computeIfAbsent(table.toLowerCase(), k -> new LinkedHashSet<>())
                        .add(column.toLowerCase());
            }
        } catch (Exception e) {
            // 读取失败抛给上层统一降级
            throw new IllegalStateException("读取数据库实际结构失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 差异计算：期望表/列 与 实际表/列 比对，产出缺失修复项。
     * <p>多余表/列不在此（单独 INFO 提示）；期望列与实际列均按小写归一比较（H2 DATABASE_TO_LOWER）。</p>
     *
     * @param expected 期望结构（实体映射）
     *
     * @param actual 实际结构（information_schema）
     *
     * @return 缺失修复项列表（空=完全符合）
     */
    List<Repair> diff(Map<String, ExpectedTable> expected, Map<String, Set<String>> actual) {
        List<Repair> repairs = new ArrayList<>();
        for (Map.Entry<String, ExpectedTable> e : expected.entrySet()) {
            ExpectedTable et = e.getValue();
            Set<String> actualCols = actual.get(e.getKey());
            if (actualCols == null || actualCols.isEmpty()) {
                // 整表缺失 → CREATE TABLE
                repairs.add(new Repair("table", et.name(), null, null, buildCreateTable(et)));
                continue;
            }
            for (ExpectedColumn ec : et.columns().values()) {
                if (!actualCols.contains(ec.name().toLowerCase())) {
                    String ddl = "ALTER TABLE " + et.name() + " ADD COLUMN " + ec.name() + " " + typeOrFallback(ec.sqlType())
                            + (ec.nullable() ? "" : " NOT NULL");
                    repairs.add(new Repair("column", et.name(), ec.name(), ec.sqlType(), ddl));
                }
            }
        }
        return repairs;
    }

    /**
     * 生成缺失表的 CREATE TABLE DDL（列 + 主键取自实体映射）。
     *
     * @param et 期望表结构
     *
     * @return CREATE TABLE 语句
     */
    private static String buildCreateTable(ExpectedTable et) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(et.name()).append(" (");
        boolean first = true;
        for (ExpectedColumn ec : et.columns().values()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(ec.name()).append(' ').append(typeOrFallback(ec.sqlType()));
            if (!ec.nullable()) {
                sb.append(" NOT NULL");
            }
        }
        if (!et.pk().isEmpty()) {
            sb.append(", PRIMARY KEY (");
            sb.append(String.join(", ", et.pk()));
            sb.append(')');
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * 列类型兜底：主键列（实体 id）在映射元模型中常无 JDBC 类型可解析（为 null），
     * 统一回退 bigint（本仓库主键均为 Long）。避免生成非法 DDL（如 {@code id null NOT NULL}）。
     *
     * @param sqlType 解析出的类型（可能 null/空）
     *
     * @return 可用类型串
     */
    private static String typeOrFallback(String sqlType) {
        return (sqlType == null || sqlType.isBlank()) ? "bigint" : sqlType;
    }

    /**
     * 执行修复（逐条 SQL，失败单条隔离不中断后续，均输出日志）。
     *
     * @param repairs 修复项列表
     *
     * @return 成功修复条数
     */
    int applyRepairs(List<Repair> repairs) {
        int fixed = 0;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (Repair r : repairs) {
                try {
                    st.execute(r.ddl());
                    fixed++;
                    log.info("[数据库]   已修复：{}（{}）", r.type().equals("table") ? "创建表 " + r.table()
                            : "补列 " + r.table() + "." + r.column(), r.ddl());
                } catch (Exception e) {
                    log.warn("[数据库]   修复失败（单条隔离继续）: {} : {}", r.ddl(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[数据库] 修复执行异常（连接/语句）: {}", e.getMessage());
        }
        return fixed;
    }

    /**
     * 输出多余表/列提示（INFO；不删除——废弃清理由 OldCrowdTableCleaner 处理已知项）。
     *
     * @param expected 期望结构
     *
     * @param actual 实际结构
     */
    private void logObsolete(Map<String, ExpectedTable> expected, Map<String, Set<String>> actual) {
        List<String> extraTables = new ArrayList<>();
        Map<String, List<String>> extraCols = new TreeMap<>();
        for (Map.Entry<String, Set<String>> a : actual.entrySet()) {
            if (!expected.containsKey(a.getKey())) {
                extraTables.add(a.getKey());
                continue;
            }
            List<String> cols = new ArrayList<>();
            for (String c : a.getValue()) {
                boolean found = false;
                for (ExpectedColumn ec : expected.get(a.getKey()).columns().values()) {
                    if (ec.name().equalsIgnoreCase(c)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    cols.add(c);
                }
            }
            if (!cols.isEmpty()) {
                extraCols.put(a.getKey(), cols);
            }
        }
        if (!extraTables.isEmpty()) {
            log.info("[数据库] 提示：数据库中多余表（不在当前实体映射，未删除）: {}", String.join(", ", extraTables));
        }
        for (Map.Entry<String, List<String>> e : extraCols.entrySet()) {
            log.info("[数据库] 提示：表 {} 存在多余列（不在当前实体映射，未删除）: {}", e.getKey(), String.join(", ", e.getValue()));
        }
    }
}
