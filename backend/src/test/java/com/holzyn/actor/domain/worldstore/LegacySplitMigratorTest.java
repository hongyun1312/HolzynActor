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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性迁移器：合成旧库（2 个世界 + 全部规则类型 + 脏数据）→ 真实拆库（V1.1.2-beta.1 M3）。
 *
 * <p>为什么必须是"合成旧库整条跑通"而不是给每个方法写单测：迁移的错法都很安静——
 * 少搬一张子表不会编译失败、回填漏一步只会让某几个角色"没记忆"、自增主键没重置要到
 * 用户建下一个角色时才撞车。只有把两套世界数据同时灌进一个旧库、跑完整迁移，
 * 再逐表对照行数与内容，才能证明"每行只进一个世界、无孤儿、无重复"。</p>
 *
 * <p>覆盖的验收点：AC3（逐表行数 + 关键表校验和 + 时钟三字段）、AC8（旧库归档可回读）、
 * 以及幂等（二次运行不再拆库）与半成品防护（失败状态必须阻断启动）。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class LegacySplitMigratorTest {

    private static final String UID_A = "41057f92-c7d9-4dc5-9176-b621be0d0466";

    private static final String UID_B = "30df4814-2ff6-43bf-9412-37cdda983e12";

    @TempDir
    Path tmp;

    private WorldStoreProperties props;

    private WorldPaths paths;

    private DataSource legacy;

    private DataSource registry;

    private WorldDataSourceFactory factory;

    private WorldManifestService manifests;

    private LegacyWorldSplitMigrator migrator;

    @BeforeEach
    void setUp() {
        props = new WorldStoreProperties();
        props.setPoolSize(2);
        paths = new WorldPaths(props, tmp.resolve("data").toString());

        // 旧库（文件库）：跑 legacy V1~V7 得到 113 张表，再灌两套世界数据
        String legacyUrl = paths.legacyDbUrl();
        Flyway.configure().dataSource(legacyUrl, "sa", "").locations("classpath:db/legacy").load().migrate();
        legacy = new DriverManagerDataSource(legacyUrl, "sa", "");

        // 目录库（文件库）：跑 V100/V101/V102，得到 18 张表（与生产同一套程序化迁移）
        registry = new DriverManagerDataSource(paths.registryDbUrl(), "sa", "");
        RegistrySchema.migrate(registry);

        factory = new WorldDataSourceFactory(paths, props, "sa", "");
        manifests = new WorldManifestService();
        migrator = new LegacyWorldSplitMigrator(paths, props, registry, factory,
                new WorldMirrorSyncService(), manifests, new WorldIndexDao(registry), "sa", "");
        // 迁移结束后把世界的用量总量抄进目录库（M4）：单测里手动装配同一实现，避免两套逻辑
        ReflectionTestUtils.setField(migrator, "summaryReader", new WorldSummaryReader());

        seedLegacy();
    }

    @AfterEach
    void tearDown() {
        factory.releaseAll();
        WorldContext.clear();
    }

    @Test
    @DisplayName("整条链路：两个世界拆库 + 逐表行数 + 时钟逐位 + 校验和 + 归档 + 报告")
    void migratesTwoSyntheticWorlds() throws IOException {
        LegacyWorldSplitMigrator.Outcome outcome = migrator.migrateIfNeeded();

        assertTrue(outcome.migrated(), "应判定为需要迁移并真的执行");
        assertTrue(outcome.pass(), "迁移必须整体 PASS，实际错误=" + outcome.errors());
        assertEquals(2, outcome.worlds().size(), "两个世界都要迁");

        // ---- 世界库存在且是 116 张（113 基线 + V207 的布局覆盖/角色坐标/移动轨迹）
        for (String uid : List.of(UID_A, UID_B)) {
            assertTrue(Files.isRegularFile(paths.worldDbFile(uid)), "世界库必须存在：" + uid);
        }
        assertEquals(116, tableCount(world(UID_A)), "世界库必须是 116 张表");
        assertEquals(116, tableCount(world(UID_B)), "世界库必须是 116 张表");

        // ---- 逐表行数：各世界只拿到自己的行
        DataSource wa = world(UID_A);
        DataSource wb = world(UID_B);
        assertEquals(2, count(wa, "actor_profile"), "世界 A 的角色数");
        assertEquals(2, count(wb, "actor_profile"), "世界 B 的角色数");
        assertEquals(1, count(wa, "actor_event"));
        assertEquals(1, count(wb, "actor_event"));
        assertEquals(2, count(wa, "actor_action_log"), "世界 A 的 2 条行动日志（父角色 1/2）");
        assertEquals(1, count(wb, "actor_action_log"), "世界 B 的 1 条行动日志");
        assertEquals(1, count(wa, "actor_dialogue_message"));
        assertEquals(1, count(wb, "actor_dialogue_message"));
        assertEquals(1, count(wa, "actor_worldgen_node_attempt"));
        assertEquals(1, count(wb, "actor_worldgen_node_attempt"));

        // ---- 回填：源库里 project_id 为空的行，进世界库后必须被补成本世界 id
        assertEquals(3, count(wa, "actor_memory"), "世界 A 的记忆 3 条（含 2 条原 project_id 为空的回填行）");
        assertEquals(0, scalar(wa, "select count(*) from actor_memory where project_id is null"),
                "回填桶在写入世界库后不允许还有空 project_id");
        assertEquals(1, count(wa, "actor_profile_revision"));
        assertEquals(1, count(wa, "actor_worldgen_task"));

        // ---- 观测表：非空行进世界库，NULL 行留目录库
        assertEquals(1, count(wa, "actor_usage_log"));
        assertEquals(1, count(wb, "actor_test_usage_log"));
        assertEquals(1, count(wb, "actor_ai_call_log"));
        assertEquals(1, count(registry, "actor_usage_log"), "project_id 为空的用量行必须留在目录库");
        assertEquals(1, count(registry, "actor_test_usage_log"), "project_id 为空的测试用量行必须留在目录库");
        assertEquals(1, count(registry, "actor_ai_call_log"), "project_id 为空的 AI 日志必须留在目录库");

        // ---- 时钟三字段逐位相同（AC3 的硬要求）
        assertEquals(13044230551L, scalar(wa,
                "select last_game_second from actor_world_clock where project_id = 1"));
        assertEquals(1L, scalar(wa, "select paused from actor_world_clock where project_id = 1"));
        assertEquals(4L, scalar(wa, "select rate from actor_world_clock where project_id = 1"));
        assertEquals(32628096000L, scalar(wb,
                "select last_game_second from actor_world_clock where project_id = 33"));
        assertEquals(24L, scalar(wb, "select rate from actor_world_clock where project_id = 33"));

        // ---- 12 张镜像表：世界库里要有全局配置（否则挂载后读空）
        assertEquals(1, count(wa, "actor_model_provider"));
        assertEquals(1, count(wa, "actor_prompt_template"));
        assertEquals(1, count(wa, "actor_project"), "世界库里 actor_project 只镜像自己那一行");
        assertEquals(1, scalar(wa, "select count(*) from actor_project where project_uid = '" + UID_A + "'"));

        // ---- 目录库：2 行索引，状态 closed，data_dir_name 已写、且目录库不含任何世界表
        assertEquals(2, count(registry, "actor_project"));
        assertEquals("closed", text(registry, "select world_status from actor_project where id = 1"));
        assertEquals(UID_A, text(registry, "select data_dir_name from actor_project where id = 1"));
        assertEquals("closed", text(registry, "select world_status from actor_project where id = 33"));
        assertEquals(0, scalar(registry, "select count(*) from information_schema.tables "
                + "where upper(table_schema) = 'PUBLIC' and lower(table_name) = 'actor_profile'"),
                "目录库不允许出现世界表（分档失败的标志）");
        assertEquals(18, tableCount(registry),
                "目录库必须恰好 18 张表（V100/V101 的 16 张 + V102 开发者控制台的 actor_dev_param / actor_dev_audit）");
        // 用量摘要（M4）：迁移后就地算一次，世界 A 有 1 条用例行 → ai_call_count=1
        assertEquals(1L, scalar(registry, "select ai_call_count from actor_project where id = 1"),
                "迁移结束必须回写 ai_call_count（首页用量 Tab 不打开世界库也要有数）");
        assertEquals(0L, scalar(registry, "select token_in_total from actor_project where id = 1"),
                "种子行的 token_in 默认 0，摘要必须按世界库实际值抄");

        // ---- 归档：旧库必须完整留档（AC8）
        // 注意不能断言"归档副本 == 当前源文件逐字节一致"：迁移要读旧库，H2 关闭时会把
        // mv.db 的元数据块重写一遍，源文件体积会变（实测 270336 → 372736）。归档的正确口径是
        // "副本 sha256 == 归档清单记录的那个值（复制前后未变）"，外加"副本能被 H2 重新打开、
        // 读出与迁移前一致的世界/角色行数"——后者才是"可回读"的实证。
        Path archive = Path.of(outcome.archiveDir());
        assertTrue(Files.isDirectory(archive), "归档目录必须存在：" + archive);
        Path archivedDb = archive.resolve(paths.legacyDbFile().getFileName().toString());
        assertTrue(Files.isRegularFile(archivedDb), "归档里必须有旧库文件");
        var manifestJson = new tools.jackson.databind.ObjectMapper().readTree(
                Files.readString(archive.resolve(LegacyWorldSplitMigrator.ARCHIVE_MANIFEST), StandardCharsets.UTF_8));
        assertEquals(sha256Of(archivedDb), manifestJson.path("legacyDb").path("sha256").asText(),
                "归档副本的 sha256 必须与清单记录一致（复制过程不许改字节）");
        assertEquals(Files.size(archivedDb), manifestJson.path("legacyDb").path("bytes").asLong(),
                "清单记录的字节数必须与归档副本一致");
        DataSource archived = new DriverManagerDataSource(h2Url(archivedDb), "sa", "");
        assertEquals(2, count(archived, "actor_project"), "归档旧库必须能重新打开并读出两个世界");
        assertEquals(4, count(archived, "actor_profile"), "归档旧库的角色行数必须与迁移前一致");

        // ---- manifest：格式版本 + 行数快照 + 校验和
        WorldManifest manifestA = manifests.read(paths.worldDir(UID_A));
        assertNotNull(manifestA);
        assertEquals(WorldManifest.CURRENT_FORMAT_VERSION, manifestA.formatVersion());
        assertEquals(UID_A, manifestA.worldUid());
        assertEquals("青禾村", manifestA.worldName());
        assertEquals("legacy-split", manifestA.source().kind());
        assertEquals(2L, manifestA.counts().get("actor_profile"), "manifest 要留行数快照");
        assertTrue(manifestA.checksums().containsKey("actor_world_clock"), "manifest 要留关键表校验和");

        // ---- 报告
        assertNotNull(outcome.reportJson(), "迁移必须写出报告");
        String report = Files.readString(outcome.reportJson(), StandardCharsets.UTF_8);
        assertTrue(report.contains("\"status\" : \"PASS\""), "报告总体状态必须是 PASS");
        assertTrue(report.contains("actor_world_clock"), "报告必须逐表列出（含关键表）");

        // ---- 孤儿行必须留证（不静默丢弃）：孤儿 action_log + 无主 memory
        assertTrue(outcome.warnings().stream().anyMatch(x -> x.contains("孤儿行") && x.contains("actor_action_log")),
                "孤儿行动日志必须进报告告警：" + outcome.warnings());
        assertTrue(outcome.warnings().stream().anyMatch(x -> x.contains("孤儿行") && x.contains("actor_memory")),
                "无主记忆必须进报告告警：" + outcome.warnings());

        // ---- 可空性漂移：旧库 NULL → 世界库 NOT NULL，必须补 0 并记账（不能整批失败、也不能不吭声）
        assertEquals(0L, scalar(wa, "select dropped_game_seconds from actor_world_clock where project_id = 1"),
                "旧库为 NULL 的 dropped_game_seconds 必须按实体默认值补 0");
        String worldAAlerts = String.join(" | ", outcome.worlds().get(0).warnings());
        assertTrue(worldAAlerts.contains("dropped_game_seconds") && worldAAlerts.contains("补 0"),
                "补值必须进该世界的告警（不静默改写）：" + worldAAlerts);
        assertTrue(report.contains("补 0"), "补值必须出现在落盘报告里（事后可核对）：" + report);

        // ---- 下次启动不再迁（幂等）
        LegacyWorldSplitMigrator.Outcome again = migrator.migrateIfNeeded();
        assertFalse(again.migrated(), "二次运行必须跳过（状态文件 done）");
    }

    @Test
    @DisplayName("dry-run：只统计、不建库、不归档、不写状态")
    void dryRunWritesNothing() {
        LegacyWorldSplitMigrator.Outcome outcome = migrator.migrate(true);

        assertTrue(outcome.dryRun());
        assertTrue(outcome.pass(), "dry-run 不应报错：" + outcome.errors());
        assertEquals(2, outcome.worlds().size());
        assertFalse(Files.exists(paths.worldsRoot()), "dry-run 不允许创建 worlds/");
        assertFalse(Files.exists(paths.archiveRoot()), "dry-run 不允许创建 archive/");
        assertEquals(0, count(registry, "actor_project"), "dry-run 不允许写目录库");
        assertNull(outcome.reportJson(), "dry-run 不写报告");
        assertFalse(Files.exists(paths.dataDir().resolve(LegacyWorldSplitMigrator.STATE_FILE)),
                "dry-run 不允许写迁移状态");
        // 每个世界都能算出"预计行数"（这里只验证非空，具体数字随规则变化）
        assertTrue(outcome.worlds().get(0).tables().stream().anyMatch(t -> t.sourceRows() > 0),
                "dry-run 应当统计出源行数");
    }

    @Test
    @DisplayName("半成品防护：状态为 failed/in-progress 时必须阻断启动")
    void failedStateBlocksStartup() throws IOException {
        Files.writeString(paths.dataDir().resolve(LegacyWorldSplitMigrator.STATE_FILE),
                "failed 2026-10-25T10:00:00 boom", StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> migrator.migrateIfNeeded());
        assertTrue(e.getMessage().contains("未完成或失败"), "错误信息要说明是上次迁移没完成：" + e.getMessage());
        assertFalse(Files.exists(paths.worldsRoot()), "阻断时不允许开始建世界库");
    }

    @Test
    @DisplayName("拒绝把旧库当目录库：目录库出现 actor_profile 时必须启动失败")
    void refusesWhenRegistryLooksLikeLegacyDb() {
        exec(registry, "create table if not exists actor_profile (id bigint primary key)");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> migrator.migrateIfNeeded());
        assertTrue(e.getMessage().contains("其实还是旧单体库"),
                "错误信息要指出 URL 配错：" + e.getMessage());
    }

    @Test
    @DisplayName("幂等：状态为 done 时永远不再拆库（即使用户删了目录库）")
    void doneStateSkipsForever() throws IOException {
        Files.writeString(paths.dataDir().resolve(LegacyWorldSplitMigrator.STATE_FILE),
                "done 2026-10-25T10:00:00", StandardCharsets.UTF_8);

        LegacyWorldSplitMigrator.Outcome outcome = migrator.migrateIfNeeded();
        assertFalse(outcome.migrated(), "done 状态必须跳过");
        assertEquals(0, count(registry, "actor_project"), "跳过时不得写目录库");
    }

    // ------------------------------------------------------------------ 造数

    /**
     * 灌入两个世界的合成数据：覆盖 75 张可筛表的代表、8 张回填表、14 张子表、3 张观测表，
     * 外加两类脏数据（孤儿子表行、无主回填行）。
     */
    private void seedLegacy() {
        exec(legacy,
                // 复刻真实库的"可空性漂移"：本机 data/holzyn-actor.mv.db 里这一列可空（历史 ddl-auto 建出），
                // 而 Flyway 基线写的是 NOT NULL。真实库 world 1 那一行确实是 NULL，迁移必须能处理。
                "alter table actor_world_clock alter column dropped_game_seconds set null",
                // ---- 世界索引
                "insert into actor_project (id, user_id, name, project_uid, status, deleted, code, summary, cover_url, created_at, updated_at) values "
                        + "(1, 1, '青禾村', '" + UID_A + "', 0, 0, 'qh', '山村', '/c1.png', timestamp '2026-08-01 10:00:00', timestamp '2026-10-01 10:00:00')",
                "insert into actor_project (id, user_id, name, project_uid, status, deleted, code, summary, cover_url, created_at, updated_at) values "
                        + "(33, 1, '浮屿镇', '" + UID_B + "', 0, 0, 'fy', '海岛镇', '/c2.png', timestamp '2026-08-02 10:00:00', timestamp '2026-10-02 10:00:00')",

                // ---- 10 张全局镜像表
                "insert into sys_user (id, name, nickname) values (1, 'local', '本地用户')",
                "insert into actor_local_account (id, user_id, onboarded, guide_completed, layout_tour_seen, created_at, updated_at) values "
                        + "(1, 1, 1, 1, 1, timestamp '2026-08-01 10:00:00', timestamp '2026-08-01 10:00:00')",
                "insert into actor_setting (id, setting_key, setting_value) values (1, 'k1', 'v1')",
                "insert into actor_emotion_anchor (id, code) values (1, 'joy')",
                "insert into actor_growth_stage_constraint (id, stage, kind) values (1, 'child', 'c1')",
                "insert into actor_growth_stage_gate (id, stage, ability) values (1, 'child', 'walk')",
                "insert into actor_dialogue_config (id, user_id, max_replies) values (1, 1, 3)",
                "insert into actor_talk_player_profile (id, user_id, name) values (1, 1, '玩家')",
                "insert into actor_worldgen_workflow (id, code, name) values (1, 'wf1', '默认工作流')",

                // ---- 2 张双性质配置（project_id 为空 = 用户级；不许按世界搬，只走镜像）
                "insert into actor_model_provider (id, user_id, name, base_url, api_key_cipher) values "
                        + "(1, 1, 'p1', 'http://localhost:1', 'SECRET')",
                "insert into actor_prompt_template (id, user_id, code, name, template) values "
                        + "(1, 1, 't1', '模板1', 'hello')",

                // ---- 世界时钟（三字段是 AC3 的对照基准）
                // dropped_game_seconds：真实库里 world 1 这一行是 NULL（历史 ddl-auto 建出的列可空），
                // 而 Flyway 基线/实体都声明 NOT NULL + 默认 0 → 迁移必须"补 0 且记账"，不许整批失败
                "insert into actor_world_clock (id, project_id, last_game_hour, world_start_game_hour, last_game_second, paused, rate, dropped_game_seconds) values "
                        + "(1, 1, 100, 0, 13044230551, 1, 4, null)",
                "insert into actor_world_clock (id, project_id, last_game_hour, world_start_game_hour, last_game_second, paused, rate, dropped_game_seconds) values "
                        + "(33, 33, 200, 0, 32628096000, 0, 24, 0)",

                // ---- 角色（4 个，两个世界各 2 个）
                "insert into actor_profile (id, project_id, name, type) values (1, 1, '沈守拙', 'special')",
                "insert into actor_profile (id, project_id, name, type) values (2, 1, '林听竹', 'special')",
                "insert into actor_profile (id, project_id, name, type) values (100, 33, '陆青', 'special')",
                "insert into actor_profile (id, project_id, name, type) values (101, 33, '温言', 'special')",

                // ---- 回填桶：3 条属于 A（其中 2 条 project_id 为空、1 条只剩 character_ref），1 条属于 B，1 条孤儿
                "insert into actor_memory (id, project_id, character_id, character_ref, type, content) values (1, 1, 1, 'npc:1', 'episodic', 'a-own')",
                "insert into actor_memory (id, project_id, character_id, character_ref, type, content) values (2, null, 2, 'npc:2', 'episodic', 'a-by-cid')",
                "insert into actor_memory (id, project_id, character_id, character_ref, type, content) values (3, null, null, 'npc:1', 'episodic', 'a-by-ref')",
                "insert into actor_memory (id, project_id, character_id, character_ref, type, content) values (4, 33, 100, 'npc:100', 'episodic', 'b-own')",
                "insert into actor_memory (id, project_id, character_id, character_ref, type, content) values (5, null, null, 'npc:9999', 'episodic', 'orphan')",
                "insert into actor_profile_revision (id, profile_id, project_id, persona_version) values (1, 1, null, 1)",
                "insert into actor_worldgen_task (id, project_id, status, workflow_id) values (1000, 1, 'succeeded', 1)",
                "insert into actor_worldgen_task (id, project_id, status, workflow_id) values (2000, 33, 'succeeded', 1)",

                // ---- 可筛表的代表行
                "insert into actor_event (id, project_id, kind, source, content) values (1, 1, 'k', 's', 'a-event')",
                "insert into actor_event (id, project_id, kind, source, content) values (2, 33, 'k', 's', 'b-event')",
                "insert into actor_world_chronicle (id, project_id, game_day, title, content) values (1, 1, 1, 'a-chron', 'c')",
                "insert into actor_world_chronicle (id, project_id, game_day, title, content) values (2, 33, 2, 'b-chron', 'c')",
                "insert into actor_world_location (id, project_id, name) values (1, 1, '守脉堂')",
                "insert into actor_world_location (id, project_id, name) values (2, 33, '码头')",
                "insert into actor_state (id, project_id) values (1, 1)",
                "insert into actor_state (id, project_id) values (2, 33)",

                // ---- 观测表：非空行进世界、NULL 行留目录库
                "insert into actor_usage_log (id, project_id, user_id, scene) values (1, 1, 1, 'dialog')",
                "insert into actor_usage_log (id, project_id, user_id, scene) values (2, null, 1, 'global')",
                "insert into actor_test_usage_log (id, project_id, run_label) values (1, 33, 'run-b')",
                "insert into actor_test_usage_log (id, project_id, run_label) values (2, null, 'run-global')",
                "insert into actor_ai_call_log (id, project_id, scene) values (1, 33, 'dialog')",
                "insert into actor_ai_call_log (id, project_id, scene) values (2, null, 'bootstrap')",

                // ---- 14 张子表：每张各给 A/B 各一行（+ 一条孤儿）
                "insert into actor_action_log (id, character_id, log_time, summary) values (1, 1, timestamp '2026-10-01 10:00:00', 'a1')",
                "insert into actor_action_log (id, character_id, log_time, summary) values (4, 2, timestamp '2026-10-01 11:00:00', 'a2')",
                "insert into actor_action_log (id, character_id, log_time, summary) values (2, 100, timestamp '2026-10-01 10:00:00', 'b1')",
                "insert into actor_action_log (id, character_id, log_time, summary) values (3, 999, timestamp '2026-10-01 10:00:00', 'orphan')",
                "insert into actor_plan (id, character_id, status, trigger_type) values (1, 1, 'planned', 'schedule')",
                "insert into actor_plan (id, character_id, status, trigger_type) values (2, 100, 'planned', 'schedule')",
                "insert into actor_profile_brain (id, agent_type, agent_ref_id, brain_level, decision_frequency, model_tier) values "
                        + "(1, 'profile', 1, 'L1', 'hourly', 'small')",
                "insert into actor_profile_brain (id, agent_type, agent_ref_id, brain_level, decision_frequency, model_tier) values "
                        + "(2, 'profile', 100, 'L1', 'hourly', 'small')",
                "insert into actor_dialogue (id, project_id, user_id, mode) values (1, 1, 1, 'single')",
                "insert into actor_dialogue (id, project_id, user_id, mode) values (2, 33, 1, 'single')",
                "insert into actor_dialogue_member (id, conversation_id, character_id) values (1, 1, 1)",
                "insert into actor_dialogue_member (id, conversation_id, character_id) values (2, 2, 100)",
                "insert into actor_dialogue_message (id, conversation_id, role, status, type) values (1, 1, 'user', 'ok', 'text')",
                "insert into actor_dialogue_message (id, conversation_id, role, status, type) values (2, 2, 'user', 'ok', 'text')",
                "insert into actor_evolution (id, project_id, mode, status) values (1, 1, 'auto', 'done')",
                "insert into actor_evolution (id, project_id, mode, status) values (2, 33, 'auto', 'done')",
                "insert into actor_evolution_participant (id, evolution_id, character_id, status) values (1, 1, 1, 'ok')",
                "insert into actor_evolution_participant (id, evolution_id, character_id, status) values (2, 2, 100, 'ok')",
                "insert into actor_evolution_turn (id, evolution_id, content, role, type) values (1, 1, 'c', 'npc', 'speak')",
                "insert into actor_evolution_turn (id, evolution_id, content, role, type) values (2, 2, 'c', 'npc', 'speak')",
                "insert into actor_eval_run (id, project_id) values (1, 1)",
                "insert into actor_eval_run (id, project_id) values (2, 33)",
                "insert into actor_eval_sample (id, eval_run_id) values (1, 1)",
                "insert into actor_eval_sample (id, eval_run_id) values (2, 2)",
                "insert into actor_eval_score (id, eval_run_id, sample_id) values (1, 1, 1)",
                "insert into actor_eval_score (id, eval_run_id, sample_id) values (2, 2, 2)",
                // 这两张表在旧库里是历史表名，迁移器会先做「旧库表名升级」再搬表
                "insert into actor_nanojev_ab_run (id, project_id) values (1, 1)",
                "insert into actor_nanojev_ab_run (id, project_id) values (2, 33)",
                "insert into actor_nanojev_ab_sample (id, ab_run_id) values (1, 1)",
                "insert into actor_nanojev_ab_sample (id, ab_run_id) values (2, 2)",
                "insert into actor_talk_session (id, project_id) values (1, 1)",
                "insert into actor_talk_session (id, project_id) values (2, 33)",
                "insert into actor_talk_group_member (id, session_id, character_ref) values (1, 1, 'npc:1')",
                "insert into actor_talk_group_member (id, session_id, character_ref) values (2, 2, 'npc:100')",
                "insert into actor_talk_message (id, session_id) values (1, 1)",
                "insert into actor_talk_message (id, session_id) values (2, 2)",
                "insert into actor_test_run (id, project_id) values (1, 1)",
                "insert into actor_test_run (id, project_id) values (2, 33)",
                "insert into actor_test_run_event (id, run_id) values (1, 1)",
                "insert into actor_test_run_event (id, run_id) values (2, 2)",
                "insert into actor_worldgen_node_attempt (id, task_id, node_id, node_order, retry_count, status, step_type) values "
                        + "(1, 1000, 'n1', 1, 0, 'ok', 'llm')",
                "insert into actor_worldgen_node_attempt (id, task_id, node_id, node_order, retry_count, status, step_type) values "
                        + "(2, 2000, 'n1', 1, 0, 'ok', 'llm')");
    }

    // ------------------------------------------------------------------ 工具

    private DataSource world(String uid) {
        return new DriverManagerDataSource(paths.worldDbUrl(uid), "sa", "");
    }

    private static void exec(DataSource ds, String... sqls) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : sqls) {
                execOne(st, sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("造数连接失败", e);
        }
    }

    /** 单条执行：失败只把这一条打进异常，否则整套 SQL 拼成一行会把真正出错的那条埋掉。 */
    private static void execOne(Statement st, String sql) {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("造数失败：" + sql, e);
        }
    }

    private static long count(DataSource ds, String table) {
        return scalar(ds, "select count(*) from " + table);
    }

    private static long scalar(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    private static String text(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    /** 表数（排除 Flyway 自己的历史表） */
    private static long tableCount(DataSource ds) {
        return scalar(ds, "select count(*) from information_schema.tables "
                + "where upper(table_schema) = 'PUBLIC' and lower(table_name) <> 'flyway_schema_history'");
    }

    /** 文件 sha256（1 MB 缓冲，避免整文件进内存） */
    private static String sha256Of(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * {@code *.mv.db} 的 H2 文件库 URL（H2 只认不带 .mv.db 后缀的库名）。
     *
     * <p>必须带 {@link WorldPaths#H2_PARAMS}：旧库是用 {@code MODE=MySQL;DATABASE_TO_LOWER=TRUE;...}
     * 建出来的，不带同一套参数去打开归档副本，H2 会按另一套标识符规则重放元数据并报
     * {@code Schema "public" not found}（实测，纯属"打开方式不对"，不是副本坏了）。</p>
     */
    private static String h2Url(Path mvDb) {
        String name = mvDb.toAbsolutePath().toString();
        if (name.endsWith(".mv.db")) {
            name = name.substring(0, name.length() - ".mv.db".length());
        }
        return "jdbc:h2:file:" + name.replace('\\', '/') + WorldPaths.H2_PARAMS;
    }
}
