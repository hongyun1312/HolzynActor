package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 全局镜像刷新（V1.1.2-beta.1 M2）。
 *
 * <p>要盯住的是一件事：<b>世界库里的 12 张全局表必须是目录库的完整副本</b>，
 * 而且 {@code actor_project} 只镜像"自己那一行"。前者错了，世界会用旧配置跑；
 * 后者错了，一个世界会看到别的世界的名字/摘要（世界列表在挂载后显示成别人）。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldMirrorSyncServiceTest {

    private static final String UID_A = "aaaaaaaa-0000-0000-0000-000000000001";

    private static final String UID_B = "bbbbbbbb-0000-0000-0000-000000000002";

    /** 建一个只含全局表的目录库，并塞入最小合法行（NOT NULL 列按 V1 基线给值） */
    private static DataSource registryWithRows() {
        DataSource reg = WorldStoreTestSupport.mem("mirror_reg", WorldStoreTestSupport.REGISTRY);
        WorldStoreTestSupport.exec(reg,
                "insert into sys_user (id, nickname) values (1, 'me')",
                "insert into actor_local_account (id, onboarded, created_at, updated_at, user_id, nickname) "
                        + "values (1, 1, current_timestamp, current_timestamp, 1, '我')",
                "insert into actor_setting (id, setting_key, setting_value) values (1, 'ui.theme', 'dark')",
                "insert into actor_emotion_anchor (id, code, name) values (1, 'joy', '喜')",
                "insert into actor_growth_stage_constraint (id, kind, stage, content) values (1, 'adult', 'adult', '成年')",
                "insert into actor_growth_stage_gate (id, stage, ability, allowed) values (1, 'adult', 'marry', 1)",
                "insert into actor_dialogue_config (id, max_replies, user_id) values (1, 3, 1)",
                "insert into actor_talk_player_profile (id, user_id, name) values (1, 1, '玩家')",
                "insert into actor_worldgen_workflow (id, code, name) values (1, 'worldgen_build_v1', '世界生成')",
                "insert into actor_model_provider (id, user_id, name, base_url) values (1, 1, 'ark', 'https://ark')",
                "insert into actor_prompt_template (id, user_id, code, name, template) values (1, 1, 'p1', '模板1', 't1')",
                "insert into actor_prompt_template (id, user_id, code, name, template) values (2, 1, 'p2', '模板2', 't2')",
                // 两个世界：A=7、B=8（验证 actor_project 只镜像自己那一行）
                "insert into actor_project (id, project_uid, user_id, name, status, deleted) values (7, '" + UID_A + "', 1, '青禾村', 0, 0)",
                "insert into actor_project (id, project_uid, user_id, name, status, deleted) values (8, '" + UID_B + "', 1, '浮生小镇', 0, 0)");
        return reg;
    }

    private static DataSource worldDb(Path tmp, String uid) {
        WorldPaths paths = WorldStoreTestSupport.paths(tmp);
        WorldDataSourceFactory factory = new WorldDataSourceFactory(
                paths, WorldStoreTestSupport.props(""), "sa", "");
        return factory.worldDataSource(uid);
    }

    @Test
    @DisplayName("覆盖写入：12 张表全量复制，actor_project 只留自己那一行")
    void syncsTwelveTablesAndOnlyOwnProjectRow(@TempDir Path tmp) {
        DataSource reg = registryWithRows();
        DataSource world = worldDb(tmp, UID_A);

        // 先污染世界库：写入一条属于别的世界的 actor_project，验证会被覆盖掉
        WorldStoreTestSupport.exec(world,
                "insert into actor_project (id, project_uid, user_id, name, status, deleted) values (8, '" + UID_B + "', 1, '脏行', 0, 0)");

        var report = new WorldMirrorSyncService().sync(reg, world, 7);

        assertEquals(12, report.tables(), "必须同步 12 张镜像表");
        assertEquals(1, WorldStoreTestSupport.count(world, "actor_project"),
                "actor_project 只镜像自己那一行（脏行应被清掉）");
        assertEquals("青禾村", WorldStoreTestSupport.text(world,
                "select name from actor_project where id = 7"), "应镜像本世界的行");
        assertEquals(1, WorldStoreTestSupport.count(world, "sys_user"));
        assertEquals(2, WorldStoreTestSupport.count(world, "actor_prompt_template"),
                "双性质配置整表镜像（该表 project_id 可空，全部行都进世界库）");
        assertEquals(1, WorldStoreTestSupport.count(world, "actor_model_provider"));
        assertEquals(1, WorldStoreTestSupport.count(world, "actor_setting"));
        assertEquals("dark", WorldStoreTestSupport.text(world,
                "select setting_value from actor_setting where setting_key = 'ui.theme'"), "值要真的搬过去");
    }

    @Test
    @DisplayName("幂等：连续两次刷新结果一致（不是追加）")
    void syncIsIdempotent(@TempDir Path tmp) {
        DataSource reg = registryWithRows();
        DataSource world = worldDb(tmp, UID_A);
        var service = new WorldMirrorSyncService();

        service.sync(reg, world, 7);
        long first = WorldStoreTestSupport.count(world, "actor_prompt_template");
        var report = service.sync(reg, world, 7);

        assertEquals(12, report.tables());
        assertEquals(first, WorldStoreTestSupport.count(world, "actor_prompt_template"), "重复刷新不应翻倍");
        assertEquals(1, WorldStoreTestSupport.count(world, "actor_project"), "actor_project 仍应只有一行");
    }

    @Test
    @DisplayName("配置变更随刷新生效：目录库改值后重开世界即生效（U3 的落地保证）")
    void configChangesTakeEffectOnNextSync(@TempDir Path tmp) {
        DataSource reg = registryWithRows();
        DataSource world = worldDb(tmp, UID_A);
        var service = new WorldMirrorSyncService();
        service.sync(reg, world, 7);
        assertEquals("dark", WorldStoreTestSupport.text(world, "select setting_value from actor_setting"));

        WorldStoreTestSupport.exec(reg, "update actor_setting set setting_value = 'light' where setting_key = 'ui.theme'");
        service.sync(reg, world, 7);

        assertEquals("light", WorldStoreTestSupport.text(world, "select setting_value from actor_setting"),
                "全局配置改动必须在下次 open 时生效（否则用户改设置看不到效果）");
    }

    @Test
    @DisplayName("缺表即失败：世界库基线不完整时不能静默跳过（否则查询时才炸）")
    void failsWhenWorldSchemaIsIncomplete(@TempDir Path tmp) {
        DataSource reg = registryWithRows();
        DataSource world = worldDb(tmp, UID_A);
        WorldStoreTestSupport.exec(world, "drop table actor_prompt_template");

        var service = new WorldMirrorSyncService();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.sync(reg, world, 7));
        // 期望是"迁移/镜像失败"这一类明确消息，而不是某个原始 SQL 异常
        assertEquals(true, ex.getMessage().contains("镜像表") || ex.getMessage().contains("刷新失败"),
                "异常消息应可诊断：" + ex.getMessage());
    }
}
