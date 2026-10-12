package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆系统分层重构 M1 迁移器单元测试（2026-09-08）。
 * <p>职责：验证 {@link MemoryTierMigrationRunner#migrate()}——内存 H2 建「旧结构 actor_memory
 * （含 kind + type + 新列）」→ 插入五类 kind 行 → 迁移 → 断言 type 回填（fact/summary→semantic、
 * observation→episodic、reflection→reflection、plan→plan）/ 会话摘要 context_json 标记
 * {@code {"subtype":"summary"}} / source 默认 import / version 默认 1 / kind 列删除；
 * 再跑一次验证<b>幂等跳过</b>（kind 列不存在=已迁移直接跳过，不破坏数据）。</p>
 */
class MemoryTierMigrationRunnerTest {

    private DataSource dataSource;
    private MemoryTierMigrationRunner runner;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migtier;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        runner = new MemoryTierMigrationRunner(jdbc);
        // 旧结构 actor_memory：含 kind 列（待迁移）+ 新 type/context_json/source/supersedes_id/version 列
        // （M1 起点 = ddl-auto 已加新列、kind 列尚未删除的中间态）
        // DB_CLOSE_DELAY=-1 内存库跨测试存活 → 先清理上次用例的表，保证用例独立
        jdbc.execute("DROP TABLE IF EXISTS actor_memory");
        jdbc.execute("CREATE TABLE actor_memory ("
                + "id bigint NOT NULL AUTO_INCREMENT, "
                + "project_id bigint, character_id bigint, character_ref varchar(50), "
                + "kind varchar(20), type varchar(20), content TEXT, importance integer, "
                + "context_json TEXT, source varchar(20), supersedes_id bigint, version integer, "
                + "deleted integer, last_access_at timestamp, access_count integer, evidence_ids TEXT, "
                + "created_at timestamp, updated_at timestamp, "
                + "PRIMARY KEY (id))");
    }

    @AfterEach
    void tearDown() {
        // 内存库随连接关闭释放；显式清理用例表（DB_CLOSE_DELAY=-1 跨用例存活）
        try {
            jdbc.execute("DROP TABLE IF EXISTS actor_memory");
        } catch (Exception ignored) {
            // 忽略清理异常
        }
    }

    private void insert(String kind, String content) {
        jdbc.update("INSERT INTO actor_memory (project_id, character_id, character_ref, kind, content, importance, deleted) "
                + "VALUES (1, 2, 'special:2', ?, ?, 3, 0)", kind, content);
    }

    /**
     * 迁移核心：kind→type 回填（五类映射）+ 会话摘要 context_json 标记 + source/version 默认 + DROP kind 列。
     */
    @Test
    void migrateBackfillsTypeMarksSummaryAndDropsKind() {
        insert("fact", "事实1：李雷在城东市场开药材铺");
        insert("summary", "摘要1：本次对话围绕药材采购展开");
        insert("observation", "经历1：前往城东市场采购药材");
        insert("reflection", "反思1：发现目标与行动存在矛盾");
        insert("plan", "计划1：{\"date_game_day\":1,\"items\":[]}");
        // 已有 context_json 的 summary：迁移不应覆盖既有上下文
        jdbc.update("INSERT INTO actor_memory (project_id, character_id, character_ref, kind, content, context_json, deleted) "
                + "VALUES (1, 2, 'special:2', 'summary', '既有摘要', '{\"note\":\"keep\"}', 0)");

        runner.migrate();

        // ① type 回填：fact/summary→semantic、observation→episodic、reflection→reflection、plan→plan
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT content, type, context_json, source, version FROM actor_memory WHERE deleted = 0 ORDER BY id");
        assertEquals(6, rows.size());

        Map<String, Object> fact = rows.get(0);
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC, fact.get("type"), "fact → semantic");
        assertEquals(ActorMemoryTypes.SOURCE_IMPORT, fact.get("source"), "source 默认 import");
        assertEquals(1, ((Number) fact.get("version")).intValue(), "version 默认 1");

        Map<String, Object> summary = rows.get(1);
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC, summary.get("type"), "summary → semantic");
        assertEquals(ActorMemoryTypes.CONTEXT_SUMMARY_JSON, summary.get("context_json"),
                "会话摘要 context_json 打 subtype=summary 标记");

        assertEquals(ActorMemoryTypes.TYPE_EPISODIC, rows.get(2).get("type"), "observation → episodic");
        assertEquals(ActorMemoryTypes.TYPE_REFLECTION, rows.get(3).get("type"), "reflection → reflection");
        assertEquals(ActorMemoryTypes.TYPE_PLAN, rows.get(4).get("type"), "plan → plan");

        Map<String, Object> existingSummary = rows.get(5);
        assertEquals("{\"note\":\"keep\"}", existingSummary.get("context_json"),
                "已有 context_json 的 summary 不被覆盖（仅补空值）");
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC, existingSummary.get("type"), "既有摘要 type 回填 semantic");

        // ② kind 列已删除（不再沿用 kind 体系）
        assertFalse(hasColumn("actor_memory", "kind"), "迁移后 actor_memory.kind 列应删除");
    }

    /**
     * 幂等：kind 列不存在（已迁移或新库）→ migrate() 直接跳过，不报错不破坏数据。
     */
    @Test
    void migrateIdempotentWhenKindColumnAbsent() {
        insert("fact", "事实1");
        // 先迁移一次（kind → type + DROP kind）
        runner.migrate();
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC,
                jdbc.queryForObject("SELECT type FROM actor_memory WHERE id = 1", String.class));

        // 再跑一次：kind 列已不存在 → 幂等跳过（无异常，数据不变）
        runner.migrate();
        assertEquals(ActorMemoryTypes.TYPE_SEMANTIC,
                jdbc.queryForObject("SELECT type FROM actor_memory WHERE id = 1", String.class),
                "幂等跳过不破坏已迁移数据");
        assertFalse(hasColumn("actor_memory", "kind"), "幂等跳过不重建 kind 列");
    }

    /**
     * 幂等判定辅助（与 runner 同口径，information_schema 查询）。
     */
    private boolean hasColumn(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }
}
