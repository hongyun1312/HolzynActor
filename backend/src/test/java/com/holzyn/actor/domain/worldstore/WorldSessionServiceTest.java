package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挂载 / 卸载状态机（V1.1.2-beta.1 M2）。
 *
 * <p>这是"世界里到底能不能正常跑"的总闸。测试覆盖的是顺序与边界，而不是实现细节：</p>
 * <ol>
 *   <li>打开 = 建库 + 迁移 + 刷镜像 + 才有挂载标记；</li>
 *   <li>重复打开同一世界是幂等（前端重复点"继续"不能把库关掉）；</li>
 *   <li>打开另一个世界要先正常关掉前一个（单活动世界，不允许两个池同时开着）；</li>
 *   <li>关闭后池被释放、标记消失、清单时间回写；</li>
 *   <li>目录不存在 / 目录库无索引 / 清单格式不符 → 各自明确失败，且失败后**不留残状态**。</li>
 * </ol>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldSessionServiceTest {

    private static final String UID_A = "aaaaaaaa-0000-0000-0000-000000000001";

    private static final String UID_B = "bbbbbbbb-0000-0000-0000-000000000002";

    private static final Map<String, Long> IDS = Map.of(UID_A, 7L, UID_B, 8L);

    @TempDir
    Path tmp;

    private WorldPaths paths;

    private WorldManifestService manifests;

    private WorldDataSourceFactory factory;

    private MountedWorld mounted;

    private WorldSessionService session;

    private DataSource registry;

    @BeforeEach
    void setUp() throws Exception {
        paths = WorldStoreTestSupport.paths(tmp);
        manifests = new WorldManifestService();
        factory = new WorldDataSourceFactory(paths, WorldStoreTestSupport.props(""), "sa", "");
        mounted = new MountedWorld();
        session = new WorldSessionService(factory, mounted, paths, manifests,
                new WorldMirrorSyncService(), WorldStoreTestSupport.props(""),
                uid -> IDS.containsKey(uid) ? OptionalLong.of(IDS.get(uid)) : OptionalLong.empty());

        registry = WorldStoreTestSupport.mem("session_reg", WorldStoreTestSupport.REGISTRY);
        session.setRegistryDataSource(registry);
        WorldStoreTestSupport.exec(registry,
                "insert into actor_project (id, project_uid, user_id, name, status, deleted) values (7, '" + UID_A + "', 1, '青禾村', 0, 0)",
                "insert into actor_project (id, project_uid, user_id, name, status, deleted) values (8, '" + UID_B + "', 1, '浮生小镇', 0, 0)");

        for (String uid : IDS.keySet()) {
            Files.createDirectories(paths.worldDir(uid));
            manifests.write(paths.worldDir(uid), WorldManifest.fresh(uid, "世界-" + uid.substring(0, 4), "1.1.2-beta.1", "2026-10-24T20:00:00"));
        }
    }

    private WorldManifest manifestOf(String uid) {
        return manifests.read(paths.worldDir(uid));
    }

    @Test
    @DisplayName("打开：建库 + 迁移 + 刷镜像 + 挂载标记，清单回写打开时间")
    void openBuildsMigratesMirrorsAndMounts() {
        WorldSessionService.OpenResult result = session.open(UID_A);

        assertTrue(result.newlyOpened());
        assertNotNull(result.mirrorReport(), "默认应刷新镜像（U3：全局配置重开生效）");
        assertEquals(12, result.mirrorReport().tables());
        assertTrue(session.isOpen());
        assertEquals(UID_A, session.current().orElseThrow());
        assertEquals(1, factory.openCount(), "只应开一个世界池");
        assertEquals(1, WorldStoreTestSupport.count(factory.worldDataSource(UID_A), "actor_project"),
                "世界库的 actor_project 应已镜像自己那一行");
        assertNotNull(manifestOf(UID_A).lastOpenedAt(), "打开时间应回写 manifest");
    }

    @Test
    @DisplayName("幂等：重复打开同一世界不重建、不关库")
    void openSameWorldTwiceIsIdempotent() {
        session.open(UID_A);
        WorldSessionService.OpenResult again = session.open(UID_A);

        assertFalse(again.newlyOpened(), "第二次不应视为新打开");
        assertEquals(1, factory.openCount());
        assertEquals(UID_A, session.current().orElseThrow());
    }

    @Test
    @DisplayName("切换：打开另一个世界会先正常关闭前一个（单活动世界）")
    void openingAnotherWorldClosesPrevious() {
        session.open(UID_A);
        session.open(UID_B);

        assertEquals(UID_B, session.current().orElseThrow());
        assertEquals(1, factory.openCount(), "同时只允许一个世界库打开");
        assertFalse(factory.openWorlds().contains(UID_A), "A 应已被关闭");
        assertNotNull(manifestOf(UID_A).lastClosedAt(), "A 的关闭时间应回写");
    }

    @Test
    @DisplayName("关闭：释放池、清标记、回写关闭时间；未挂载时为空操作")
    void closeReleasesAndIsNoOpWhenIdle() {
        session.close("test");
        assertFalse(session.isOpen(), "未挂载时 close 应为空操作");

        session.open(UID_A);
        session.close("user");

        assertFalse(session.isOpen());
        assertEquals(0, factory.openCount());
        assertEquals(0, session.current().stream().count());
        assertNotNull(manifestOf(UID_A).lastClosedAt());
        // 再次 close 不应抛
        session.close("again");
    }

    @Test
    @DisplayName("失败路径：目录不存在 → 明确异常，且不留挂载标记与泄漏的池")
    void openMissingDirectoryFailsCleanly() {
        String ghost = "cccccccc-0000-0000-0000-000000000003";
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> session.open(ghost));
        assertTrue(ex.getMessage().contains("世界目录不存在"), ex.getMessage());
        assertFalse(session.isOpen());
        assertEquals(0, factory.openCount());
    }

    @Test
    @DisplayName("失败路径：目录库无索引 → 拒绝挂载（不猜 id，不建半套库）")
    void openWithoutRegistryEntryFails() throws Exception {
        String orphan = "dddddddd-0000-0000-0000-000000000004";
        Files.createDirectories(paths.worldDir(orphan));
        manifests.write(paths.worldDir(orphan), WorldManifest.fresh(orphan, "孤儿", "1.1.2-beta.1", "2026-10-24T20:00:00"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> session.open(orphan));
        assertTrue(ex.getMessage().contains("目录库"), ex.getMessage());
        assertFalse(session.isOpen());
        assertEquals(0, factory.openCount(), "失败时不应把库开着");
    }

    @Test
    @DisplayName("失败路径：清单格式不兼容 → 拒绝，且不建库")
    void openIncompatibleManifestFails() throws Exception {
        String uid = "eeeeeeee-0000-0000-0000-000000000005";
        Files.createDirectories(paths.worldDir(uid));
        Files.writeString(paths.manifestFile(uid),
                "{\"formatVersion\":\"2.0\",\"worldUid\":\"" + uid + "\",\"worldName\":\"旧档\",\"dbFile\":\"world.mv.db\"}",
                java.nio.charset.StandardCharsets.UTF_8);

        assertThrows(WorldManifestException.class, () -> session.open(uid));
        assertFalse(session.isOpen());
        assertEquals(0, factory.openCount());
    }
}
