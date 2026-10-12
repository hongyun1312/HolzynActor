package com.holzyn.actor.common;

import com.holzyn.actor.common.DatabaseStructureValidator.ExpectedColumn;
import com.holzyn.actor.common.DatabaseStructureValidator.ExpectedTable;
import com.holzyn.actor.common.DatabaseStructureValidator.Repair;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DatabaseStructureValidator 单元测试（2026-09-03 追加轮）。
 * <p>职责：验证「数据库结构校验 → 差异计算 → 自动修复 → 复检」闭环——内存 H2 建表后，
 * 构造合成期望结构（匹配表 / 缺列表 / 缺失表三种），断言 diff 产出正确修复项、
 * applyRepairs 执行 ALTER/CREATE 后复检通过；并验证信息表读取（H2 MODE=MySQL 兼容）。</p>
 */
class DatabaseStructureValidatorTest {

    private DataSource dataSource;
    private DatabaseStructureValidator validator;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:structtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        // loadExpectedSchema 走 Hibernate 元数据（真实上下文冒烟覆盖），此处 mock SessionFactory 避免依赖
        validator = new DatabaseStructureValidator(dataSource, Mockito.mock(SessionFactory.class));
    }

    @AfterEach
    void tearDown() {
        // 释放内存库
    }

    /** 构造期望表结构 helper */
    private static ExpectedTable table(String name, String[][] cols, List<String> pk) {
        LinkedHashMap<String, ExpectedColumn> m = new LinkedHashMap<>();
        for (String[] c : cols) {
            m.put(c[0].toLowerCase(), new ExpectedColumn(c[0], c[1], Boolean.parseBoolean(c[2])));
        }
        return new ExpectedTable(name, m, pk);
    }

    /**
     * 匹配表：期望与实际一致 → 无修复项。
     */
    @Test
    void diffMatchesWhenIdentical() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE actor_test_match (id bigint NOT NULL, name varchar(255), PRIMARY KEY (id))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, ExpectedTable> expected = Map.of("actor_test_match",
                table("actor_test_match",
                        new String[][]{{"id", "bigint", "false"}, {"name", "varchar(255)", "true"}},
                        List.of("id")));

        Map<String, Set<String>> actual = validator.loadActualColumns();
        List<Repair> repairs = validator.diff(expected, actual);

        assertTrue(repairs.isEmpty(), "结构一致应无修复项");
    }

    /**
     * 缺列表：期望有而实际无 → ALTER TABLE ADD COLUMN 修复项。
     */
    @Test
    void diffFindsMissingColumn() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE actor_test_col (id bigint NOT NULL, PRIMARY KEY (id))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, ExpectedTable> expected = Map.of("actor_test_col",
                table("actor_test_col",
                        new String[][]{{"id", "bigint", "false"}, {"note", "varchar(255)", "true"}},
                        List.of("id")));

        List<Repair> repairs = validator.diff(expected, validator.loadActualColumns());

        assertEquals(1, repairs.size());
        Repair r = repairs.get(0);
        assertEquals("column", r.type());
        assertEquals("actor_test_col", r.table());
        assertEquals("note", r.column());
        assertTrue(r.ddl().contains("ADD COLUMN note varchar(255)"), "DDL 应含补列语句");
    }

    /**
     * 缺失表：期望有而实际无 → CREATE TABLE 修复项（含列 + 主键）。
     */
    @Test
    void diffFindsMissingTable() {
        Map<String, ExpectedTable> expected = Map.of("actor_test_tbl",
                table("actor_test_tbl",
                        new String[][]{{"id", "bigint", "false"}, {"label", "varchar(64)", "true"}},
                        List.of("id")));

        List<Repair> repairs = validator.diff(expected, validator.loadActualColumns());

        assertEquals(1, repairs.size());
        Repair r = repairs.get(0);
        assertEquals("table", r.type());
        assertEquals("actor_test_tbl", r.table());
        assertTrue(r.ddl().startsWith("CREATE TABLE actor_test_tbl ("), "DDL 应为 CREATE TABLE");
        assertTrue(r.ddl().contains("PRIMARY KEY (id)"), "DDL 应含主键");
    }

    /**
     * 修复闭环：缺列 + 缺失表 → applyRepairs 执行后复检通过。
     */
    @Test
    void repairThenRevalidatePasses() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE actor_test_rep (id bigint NOT NULL, PRIMARY KEY (id))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, ExpectedTable> expected = new LinkedHashMap<>();
        expected.put("actor_test_rep", table("actor_test_rep",
                new String[][]{{"id", "bigint", "false"}, {"extra", "varchar(100)", "true"}}, List.of("id")));
        expected.put("actor_test_new", table("actor_test_new",
                new String[][]{{"id", "bigint", "false"}, {"val", "integer", "true"}}, List.of("id")));

        List<Repair> repairs = validator.diff(expected, validator.loadActualColumns());
        assertEquals(2, repairs.size(), "应发现缺列 + 缺失表共 2 处");
        int fixed = validator.applyRepairs(repairs);
        assertEquals(2, fixed, "应成功修复 2 处");

        List<Repair> remaining = validator.diff(expected, validator.loadActualColumns());
        assertTrue(remaining.isEmpty(), "修复后复检应通过");
    }

    /**
     * 多余表/列不产生修复项（仅 INFO 提示路径，diff 不含）。
     */
    @Test
    void diffIgnoresObsoleteTablesAndColumns() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE actor_test_obs (id bigint NOT NULL, old_col varchar(10), PRIMARY KEY (id))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, ExpectedTable> expected = Map.of("actor_test_obs",
                table("actor_test_obs", new String[][]{{"id", "bigint", "false"}}, List.of("id")));

        List<Repair> repairs = validator.diff(expected, validator.loadActualColumns());

        assertTrue(repairs.isEmpty(), "多余列不应触发修复");
    }
}
