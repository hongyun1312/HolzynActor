package com.holzyn.actor.domain.worldstore;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 世界存档存储测试支撑（V1.1.2-beta.1）。
 *
 * <p>只用 {@code DriverManagerDataSource} 而不是 H2 的 {@code JdbcDataSource}：H2 依赖在
 * 本仓库是 runtime scope，测试编译期拿不到它的类；用 Spring 的这个实现既不需要 H2 类，
 * 又保持"每次 getConnection 新开连接"的语义（内存库靠 DB_CLOSE_DELAY=-1 存活）。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
final class WorldStoreTestSupport {

    static final String USER = "sa";

    static final String PASS = "";

    static final String REGISTRY = "classpath:db/migration/global";

    static final String WORLD = "classpath:db/migration/world";

    private WorldStoreTestSupport() {
    }

    /** 内存库序号：同名库在 DB_CLOSE_DELAY=-1 下会跨测试方法存活，必须每次唯一 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** 内存库 URL（与生产同参数，避免大小写/方言差异导致测试假绿） */
    static String memUrl(String name) {
        return "jdbc:h2:mem:" + name + "_" + SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    /** 建内存库并跑指定迁移，返回其上的一次性 DataSource */
    static DataSource mem(String name, String... locations) {
        String url = memUrl(name);
        migrate(url, locations);
        return new DriverManagerDataSource(url, USER, PASS);
    }

    static void migrate(String url, String... locations) {
        Flyway.configure().dataSource(url, USER, PASS).locations(locations).load().migrate();
    }

    /** 测试用存储配置（默认值，poolSize 小一点） */
    static WorldStoreProperties props(String rootDir) {
        WorldStoreProperties p = new WorldStoreProperties();
        p.setRootDir(rootDir == null ? "" : rootDir);
        p.setPoolSize(2);
        return p;
    }

    /** 以临时目录为根的路径权威 */
    static WorldPaths paths(Path tmp) {
        return new WorldPaths(props(tmp.resolve("worlds").toString()), tmp.resolve("data").toString());
    }

    static void exec(DataSource ds, String... sqls) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败：" + String.join(" ; ", sqls), e);
        }
    }

    static long count(DataSource ds, String table) {
        return scalar(ds, "select count(*) from " + table);
    }

    static long scalar(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    static String text(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }
}
