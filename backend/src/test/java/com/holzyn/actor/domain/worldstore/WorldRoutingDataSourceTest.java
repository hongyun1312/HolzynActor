package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两态路由数据源行为（V1.1.2-beta.1 M2）。
 *
 * <p>这些断言直接对应"数据会写错库"的四条真实失败路径：</p>
 * <ol>
 *   <li>未挂载世界时访问数据库 → 必须回落目录库而不是抛错（否则应用起不来：启动到打开
 *       第一个世界之间，定时任务/健康检查都会碰库）；</li>
 *   <li>挂载后未显式声明全局 → 必须打到世界库（否则世界数据被写进目录库，而目录库
 *       没有那些表）；</li>
 *   <li>显式全局 → 即使世界已挂载也必须打到目录库（否则"读世界列表"会读到只有 1 行的
 *       镜像表，前端只显示当前世界）；</li>
 *   <li>路由键指向一个已被关掉的世界 → 必须显式抛错，绝不能悄悄回落目录库
 *       （那会把世界数据写进目录库）。</li>
 * </ol>
 *
 * <p>验证手段用"只有某个库才有的数据"来区分连接落点，而不是看路由键字符串——
 * 键对了但解析错了才是真正危险的情况。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldRoutingDataSourceTest {

    private static final String UID = "w1";

    private final DataSource registry = WorldStoreTestSupport.mem("route_reg", WorldStoreTestSupport.REGISTRY);

    private final DataSource world = WorldStoreTestSupport.mem("route_world", WorldStoreTestSupport.WORLD);

    private final MountedWorld mounted = new MountedWorld();

    private final WorldRoutingDataSource routing =
            new WorldRoutingDataSource(registry, mounted, uid -> UID.equals(uid) ? world : null);

    @AfterEach
    void clear() {
        WorldContext.clear();
        mounted.unmount();
    }

    /** 只在目录库存在的标记行（世界库的 actor_project 是空的） */
    private long registryMarker() throws SQLException {
        try (Connection c = routing.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from actor_project where id = 999")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 只在世界库存在的表（目录库没有 actor_profile） */
    private long worldOnlyTable() throws SQLException {
        try (Connection c = routing.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from actor_profile")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("未挂载：一切落到目录库（应用可空转启动）")
    void unmountedFallsBackToRegistry() throws SQLException {
        WorldStoreTestSupport.exec(registry, "insert into actor_project (id, user_id, name, status, deleted) "
                + "values (999, 1, 'registry-only', 0, 0)");

        assertEquals(1, registryMarker(), "未挂载时应能读到目录库的标记行");
        assertThrows(SQLException.class, this::worldOnlyTable,
                "未挂载时不应连上世界库（actor_character 只存在于世界库）");
        assertEquals(WorldRoutingDataSource.KEY_GLOBAL, routing.currentKey());
    }

    @Test
    @DisplayName("已挂载 + 非全局：打到世界库")
    void mountedRoutesToWorld() throws SQLException {
        // 目录库存一条只有目录库才有的行（世界库的 actor_project 为空）
        WorldStoreTestSupport.exec(registry, "insert into actor_project (id, user_id, name, status, deleted) "
                + "values (999, 1, 'registry-only', 0, 0)");
        mounted.mount(UID);

        assertEquals(WorldRoutingDataSource.KEY_WORLD_PREFIX + UID, routing.currentKey());
        assertEquals(0, worldOnlyTable(), "挂载后 actor_profile 应可查（说明连的是世界库）");
        assertEquals(0, registryMarker(),
                "挂载后不应再看到目录库的标记行（若为 1 说明路由还停在目录库）");
    }

    @Test
    @DisplayName("已挂载 + 显式全局：仍打到目录库")
    void globalContextOverridesMount() throws SQLException {
        WorldStoreTestSupport.exec(registry, "insert into actor_project (id, user_id, name, status, deleted) "
                + "values (999, 1, 'registry-only', 0, 0)");
        mounted.mount(UID);

        try (var ignored = WorldContext.global()) {
            assertEquals(WorldRoutingDataSource.KEY_GLOBAL, routing.currentKey());
            assertEquals(1, registryMarker(), "全局上下文必须覆盖挂载状态");
        }
        // 退出后立刻回到世界库
        assertEquals(WorldRoutingDataSource.KEY_WORLD_PREFIX + UID, routing.currentKey());
    }

    @Test
    @DisplayName("竞态：路由键指向已被关闭的世界 → 显式抛错，绝不回落目录库")
    void missingWorldDataSourceFailsLoudly() {
        mounted.mount("w2");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            try (Connection ignored = routing.getConnection()) {
                // 不应走到这里
            }
        });
        assertTrue(ex.getMessage().contains("w2"), "异常应点出是哪个世界：" + ex.getMessage());
    }

    @Test
    @DisplayName("目录库数据源可直接取用（迁移器/镜像同步需要绕过路由）")
    void registryDataSourceIsExposed() {
        assertNotNull(routing.registryDataSource());
        assertEquals(0, WorldStoreTestSupport.count(routing.registryDataSource(), "actor_project"),
                "直连目录库应是同一个库（全新库时 actor_project 为空）");
        WorldStoreTestSupport.exec(routing.registryDataSource(),
                "insert into actor_project (id, user_id, name, status, deleted) values (7, 1, 'x', 0, 0)");
        assertEquals(1, WorldStoreTestSupport.count(routing.registryDataSource(), "actor_project"),
                "直连写入后应能读到（证明两次拿的是同一个库）");
    }
}
