package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界库连接池生命周期（V1.1.2-beta.1 M2）。
 *
 * <p>这里用**真实文件库**而不是内存库，因为要验证的正是文件层面的行为：
 * 目录自动创建、V200 基线在空文件上真的建出 113 张表、关池后文件还在（存档不丢）、
 * 关池后旧引用不可再用（防止引擎线程拿着已关闭的池继续写）。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldDataSourceFactoryTest {

    private static final String UID = "41057f92-c7d9-4dc5-9176-b621be0d0466";

    private WorldDataSourceFactory factory(Path tmp) {
        WorldPaths paths = WorldStoreTestSupport.paths(tmp);
        return new WorldDataSourceFactory(paths, WorldStoreTestSupport.props(""), "sa", "");
    }

    private static long userTables(DataSource ds) {
        return WorldStoreTestSupport.scalar(ds,
                "select count(*) from information_schema.tables "
                        + "where upper(table_schema) = 'PUBLIC' and lower(table_name) <> 'flyway_schema_history'");
    }

    @Test
    @DisplayName("首次打开：自动建目录 + 在空文件上跑 V200，得到 116 张表")
    void createsWorldDatabaseWithFullSchema(@TempDir Path tmp) {
        WorldPaths paths = WorldStoreTestSupport.paths(tmp);
        WorldDataSourceFactory factory = factory(tmp);

        DataSource ds = factory.worldDataSource(UID);

        assertTrue(Files.isRegularFile(paths.worldDbFile(UID)),
                "世界库文件应落在 worlds/<uid>/world.mv.db：" + paths.worldDbFile(UID));
        assertEquals(116, userTables(ds),
                "世界库应含 116 张表（113 基线 + V207 的布局覆盖/角色坐标/移动轨迹 3 张）");
        assertEquals(1, WorldStoreTestSupport.count(ds,
                "information_schema.tables where upper(table_schema)='PUBLIC' and lower(table_name)='actor_world_clock'"),
                "世界库必须有自己的世界时钟表");
        assertEquals(1, factory.openCount());
        assertTrue(factory.openWorlds().contains(UID));
    }

    @Test
    @DisplayName("幂等：同一世界重复打开返回同一个池，不会重复建库")
    void worldDataSourceIsIdempotent(@TempDir Path tmp) {
        WorldDataSourceFactory factory = factory(tmp);
        DataSource first = factory.worldDataSource(UID);
        DataSource second = factory.worldDataSource(UID);
        assertSame(first, second, "同一 uid 必须复用同一个池（否则 H2 文件锁冲突）");
        assertEquals(1, factory.openCount());
    }

    @Test
    @DisplayName("关闭：池移除、旧连接不可用、文件保留（存档不能因为关世界而消失）")
    void releaseClosesPoolButKeepsFile(@TempDir Path tmp) {
        WorldPaths paths = WorldStoreTestSupport.paths(tmp);
        WorldDataSourceFactory factory = factory(tmp);
        DataSource ds = factory.worldDataSource(UID);

        factory.release(UID);

        assertEquals(0, factory.openCount(), "关闭后不应再有打开的池");
        assertFalse(factory.openWorlds().contains(UID));
        assertThrows(Exception.class, () -> {
            try (var c = ds.getConnection()) {
                // 池已关闭：Hikari 会抛 SQLException
            }
        }, "已释放的池不得再给出连接（否则引擎会写进已关闭的库）");
        assertTrue(Files.isRegularFile(paths.worldDbFile(UID)), "关闭世界库不得删除存档文件");
    }

    @Test
    @DisplayName("非法 uid：建池前就拦住（uid 直接参与路径拼接）")
    void rejectsUnsafeUid(@TempDir Path tmp) {
        WorldDataSourceFactory factory = factory(tmp);
        assertThrows(IllegalArgumentException.class, () -> factory.worldDataSource("../escape"));
        assertThrows(IllegalArgumentException.class, () -> factory.worldDataSource(""));
        assertEquals(0, factory.openCount());
    }

    @Test
    @DisplayName("releaseAll：应用退出时清空全部世界池")
    void releaseAllClosesEveryPool(@TempDir Path tmp) {
        WorldDataSourceFactory factory = factory(tmp);
        factory.worldDataSource("w1");
        factory.worldDataSource("w2");
        assertEquals(2, factory.openCount());
        factory.releaseAll();
        assertEquals(0, factory.openCount());
    }
}
