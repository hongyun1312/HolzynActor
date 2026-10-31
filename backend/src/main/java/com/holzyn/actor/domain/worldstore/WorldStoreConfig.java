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
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 世界存档存储的 Spring 装配（V1.1.2-beta.1）。
 *
 * <p>把原来"Boot 自动装配的一个 Hikari"换成"目录库池 + 路由数据源"：</p>
 * <pre>
 * dataSource(@Primary, 路由)  ──┬─▶ registryDataSource（目录库，直连）
 *                              └─▶ 世界库池（按 uid 懒建，WorldDataSourceFactory）
 * </pre>
 *
 * <p>四个刻意的决定：</p>
 * <ol>
 *   <li><b>路由数据源必须是 {@code @Primary}</b>：JPA/JdbcTemplate/Flyway 全都按类型取
 *       {@code DataSource}，不标 primary 就会继续拿到目录库，路由形同虚设（而且不会报错）。</li>
 *   <li><b>目录库池单独成 bean 并命名</b>：{@link WorldIndexDao}、镜像同步需要"物理上一定
 *       打目录库"的连接，绕过路由。用 {@code @Qualifier} 显式指名，不靠"当前上下文恰好是全局"。</li>
 *   <li><b>路由数据源不实现 close</b>：{@code AbstractRoutingDataSource} 没有 {@code close()}，
 *       池子的生命周期归 {@code registryDataSource} 与 {@link WorldDataSourceFactory} 各自管。</li>
 *   <li><b>写守卫通过 {@code HibernatePropertiesCustomizer} 注册实例</b>：这样守卫能拿到
 *       Spring 注入的 {@link MountedWorld}；若改用 yml 里的类名，Hibernate 会自己 {@code new}
 *       一个，那个实例的 {@code MountedWorld} 永远是空，守卫等于没装（且不会报错）。</li>
 * </ol>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Configuration
public class WorldStoreConfig {

    /** Hibernate 事件插槽名（{@code StatementInspector}） */
    private static final String STATEMENT_INSPECTOR_KEY = "hibernate.session_factory.statement_inspector";

    /**
     * 目录库连接池（直连，不参与路由）。
     *
     * <p>连接串来自 {@link WorldPaths#registryDbUrl()} 而不是 {@code spring.datasource.url}：
     * 分档后"世界在哪个目录"与"索引库在哪个目录"必须同一个根，而这个根只允许
     * {@code holzyn.actor.data-dir} 一处解析（M3 修的同源问题，见设计 §3.14）。</p>
     *
     * <p>建池后<b>立刻</b>跑目录库 Flyway：这样任何拿到这个池的 Bean（JPA、索引 DAO、
     * 镜像同步、迁移器）都不可能看到一个空 schema。反过来把迁移交给独立的 Bean，
     * 就只能靠 Bean 创建顺序保证，而这个顺序在"没有任何 {@code @SpringBootTest}"的本仓库里
     * 只会在真实启动时暴露。</p>
     *
     * @param paths    路径权威
     * @param username 用户名
     * @param password 密码
     *
     * @return 目录库数据源
     */
    @Bean(name = "registryDataSource", destroyMethod = "close")
    public DataSource registryDataSource(WorldPaths paths,
                                         @Value("${spring.datasource.username:sa}") String username,
                                         @Value("${spring.datasource.password:}") String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(paths.registryDbUrl());
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setPoolName("registry");
        // 目录库只承载索引与全局配置：读写都短、并发低，8 条足够且不至于把 H2 的写锁打满
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        cfg.setAutoCommit(true);
        cfg.setConnectionTimeout(10_000L);
        HikariDataSource dataSource = new HikariDataSource(cfg);
        log.info("[world-store] 目录库连接池已创建 pool=registry url={}", paths.registryDbUrl());
        // 建池即迁移：空文件在这一步建出 16 张表（V100 基线 + V101 索引列）
        RegistrySchema.migrate(dataSource);
        return dataSource;
    }

    /**
     * 目录库 Flyway 实例（供 {@code SystemInfoController} 报迁移状态）。
     *
     * <p>只装配不执行：真正的 migrate 已在 {@link #registryDataSource} 建池时完成。
     * 显式提供该 Bean 也让 Boot 的 Flyway 自动装配因 {@code @ConditionalOnMissingBean} 退出，
     * 避免"两个 Flyway 打同一个库"。</p>
     *
     * @param registry 目录库数据源
     *
     * @return Flyway 实例
     */
    @Bean
    public Flyway registryFlyway(@Qualifier("registryDataSource") DataSource registry) {
        return RegistrySchema.load(registry);
    }

    /**
     * 主数据源：两态路由（目录库 / 当前挂载世界库）。
     *
     * @param registry    目录库数据源
     * @param factory     世界库池工厂（懒建/懒毁）
     * @param mountedWorld 当前挂载世界
     * @param properties  配置（用于启动期安全断言）
     *
     * @return 路由数据源
     */
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("registryDataSource") DataSource registry,
                                 WorldDataSourceFactory factory,
                                 MountedWorld mountedWorld,
                                 WorldStoreProperties properties) {
        if (properties.isAllowMultiMount()) {
            // 单活动世界是"丢上下文也写不错库"的唯一前提，配错必须启动即失败，不能带着跑
            throw new IllegalStateException("holzyn.actor.world-store.allow-multi-mount 必须为 false："
                    + "并行挂载多个世界会让默认路由（当前挂载世界）失去唯一性，"
                    + "此时丢失线程上下文就可能把数据写进另一个世界。");
        }
        return new WorldRoutingDataSource(registry, mountedWorld, factory::peek);
    }

    /**
     * 挂载会话服务（挂载/卸载的唯一入口）。
     *
     * @param factory     世界库池工厂
     * @param mountedWorld 当前挂载世界
     * @param paths       路径权威
     * @param manifests   清单读写
     * @param mirrors     镜像刷新
     * @param properties  存储配置
     * @param indexDao    目录库索引（提供 uid → id）
     * @param registry    目录库数据源（镜像同步的读源）
     *
     * @return 会话服务
     */
    @Bean
    public WorldSessionService worldSessionService(WorldDataSourceFactory factory,
                                                   MountedWorld mountedWorld,
                                                   WorldPaths paths,
                                                   WorldManifestService manifests,
                                                   WorldMirrorSyncService mirrors,
                                                   WorldStoreProperties properties,
                                                   WorldIndexDao indexDao,
                                                   @Qualifier("registryDataSource") DataSource registry) {
        WorldSessionService service = new WorldSessionService(factory, mountedWorld, paths, manifests,
                mirrors, properties, indexDao::findIdByUid);
        service.setRegistryDataSource(registry);
        return service;
    }

    /**
     * 注册全局镜像写守卫（Hibernate {@code StatementInspector}）。
     *
     * @param guard 守卫实例（Spring 管理，带 {@link MountedWorld} 依赖）
     *
     * @return Hibernate 属性定制器
     */
    @Bean
    public HibernatePropertiesCustomizer globalMirrorWriteGuardCustomizer(GlobalMirrorWriteGuard guard) {
        return properties -> properties.put(STATEMENT_INSPECTOR_KEY, guard);
    }
}
