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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界库连接池的创建 / 迁移 / 销毁（V1.1.2-beta.1）。
 *
 * <p>职责边界刻意收得很窄：只管"把某个世界的 {@code world.mv.db} 打开成可用 DataSource"，
 * 不判断该不该开（那是 {@link WorldSessionService} 的事），也不做镜像同步。</p>
 *
 * <p>三个必须在这里做的决定：</p>
 * <ol>
 *   <li><b>建池即迁移</b>：新世界目录第一次打开时库是空的，就地跑
 *       {@code classpath:db/migration/world}（V200 → V201…）。若把迁移交给别的时机，
 *       就会出现"世界已挂载但表还没建"的窗口，引擎第一拍直接 500。</li>
 *   <li><b>关闭时必须 {@code SHUTDOWN COMPACT}</b>：H2 关闭时默认只压缩 200ms，
 *       超时后直接保留未压缩页。世界存档动辄几十 MB，不压缩会导致"删了数据文件不变小"，
 *       搬存档时白白多传几十 MB（官方文档已写明该默认值）。</li>
 *   <li><b>池名带 uid</b>：排查泄漏时日志里直接能看出是哪个世界的池没关。</li>
 * </ol>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
public class WorldDataSourceFactory {

    /** 世界库迁移脚本位置（目录库用 classpath:db/migration/global，由 Spring Flyway 自动装配执行） */
    public static final String WORLD_MIGRATION_LOCATION = "classpath:db/migration/world";

    private final WorldPaths paths;

    private final WorldStoreProperties properties;

    private final String username;

    private final String password;

    /** uid → 已打开的池 */
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public WorldDataSourceFactory(WorldPaths paths,
                                  WorldStoreProperties properties,
                                  @Value("${spring.datasource.username:sa}") String username,
                                  @Value("${spring.datasource.password:}") String password) {
        this.paths = paths;
        this.properties = properties;
        this.username = username;
        this.password = password;
    }

    /**
     * 打开（必要时先创建并迁移）某世界的库。
     *
     * <p>幂等：已打开则直接返回同一个池。并发安全：同一 uid 只建一次。</p>
     *
     * @param worldUid 世界 uid
     *
     * @return 该世界的连接池
     */
    public DataSource worldDataSource(String worldUid) {
        WorldPaths.requireSafeUid(worldUid);
        return pools.computeIfAbsent(worldUid, this::createAndMigrate);
    }

    /**
     * 已打开的世界库（不触发创建）。
     *
     * @param worldUid 世界 uid
     *
     * @return 连接池；未打开时 {@code null}
     */
    public DataSource peek(String worldUid) {
        return pools.get(worldUid);
    }

    /** 当前已打开的世界数（诊断/自检用） */
    public int openCount() {
        return pools.size();
    }

    /** 已打开的世界 uid 集合（只读快照） */
    public Set<String> openWorlds() {
        return Set.copyOf(pools.keySet());
    }

    /**
     * 关闭某世界的库：先压缩落盘，再关池。
     *
     * @param worldUid 世界 uid
     */
    public void release(String worldUid) {
        HikariDataSource ds = pools.remove(worldUid);
        if (ds == null) {
            return;
        }
        if (properties.isCompactOnClose()) {
            compact(worldUid, ds);
        }
        ds.close();
        log.info("[world-store] 已关闭世界库 uid={}", worldUid);
    }

    /** 关闭全部世界库（应用退出时调用） */
    public void releaseAll() {
        for (String uid : Set.copyOf(pools.keySet())) {
            release(uid);
        }
    }

    /** 建池 + 跑世界库迁移 */
    private HikariDataSource createAndMigrate(String worldUid) {
        Path dbFile = paths.worldDbFile(worldUid);
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("创建世界目录失败：" + dbFile.getParent(), e);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(paths.worldDbUrl(worldUid));
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setPoolName("world-" + worldUid);
        cfg.setMaximumPoolSize(Math.max(1, properties.getPoolSize()));
        cfg.setMinimumIdle(0);
        cfg.setAutoCommit(true);
        // 库文件是独占打开的：连接超时给足，避免引擎长事务把挂载流程卡死
        cfg.setConnectionTimeout(10_000L);
        HikariDataSource ds = new HikariDataSource(cfg);

        boolean fresh = !Files.exists(dbFile);
        try {
            migrate(worldUid, ds);
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
        log.info("[world-store] 世界库已打开 uid={} 新建={} 池={}", worldUid, fresh, cfg.getPoolName());
        return ds;
    }

    /** 程序化 Flyway：每库独立迁移（目录库由 Spring 自动装配另行迁移） */
    private void migrate(String worldUid, DataSource ds) {
        try {
            var result = org.flywaydb.core.Flyway.configure()
                    .dataSource(ds)
                    .locations(WORLD_MIGRATION_LOCATION)
                    // 世界库永远由本版本首次创建；若出现"已有表却无迁移历史"，说明库被外部改过，
                    // 宁可直接失败也不要自动打基线把问题盖住。
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();
            if (result.migrationsExecuted > 0) {
                log.info("[world-store] 世界库迁移完成 uid={} 执行={} 版本={}",
                        worldUid, result.migrationsExecuted, result.targetSchemaVersion);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("世界库迁移失败：" + worldUid + "（" + paths.worldDbFile(worldUid) + "）", e);
        }
    }

    /** 落盘压缩（失败只告警：压缩失败不该阻止关世界，否则用户永远退不出） */
    private void compact(String worldUid, DataSource ds) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("SHUTDOWN COMPACT");
        } catch (SQLException e) {
            // SHUTDOWN 会主动断开连接，驱动常以异常形式返回"连接已关闭"，不算失败
            log.debug("[world-store] 关闭压缩返回异常（通常为连接被主动断开）uid={}：{}", worldUid, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[world-store] 关闭压缩失败 uid={}（不阻止关闭）：{}", worldUid, e.toString());
        }
    }
}
