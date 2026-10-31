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
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * 目录库 schema 迁移（程序化 Flyway，V1.1.2-beta.1 M3）。
 *
 * <p><b>为什么不再用 Boot 的 Flyway 自动装配</b>：自动装配只认 {@code spring.datasource.url}，
 * 而分档后"目录库在哪"必须由 {@link WorldPaths#registryDbUrl()} 单一派生。实测过两者会分叉：
 * {@code spring.datasource.url} 里的 {@code ${HOLOZYN_ACTOR_DATA_DIR:./data}} 与
 * {@code holzyn.actor.data-dir} 是两处独立解析，环境变量只改得动其中一处时，就会出现
 * "世界放在 A 目录、索引库在 B 目录"——这是分档方案最不能有的错（备份搬走的是一半）。</p>
 *
 * <p>于是目录库与世界库走<b>同一套</b>程序化迁移：位置在代码里写死（{@link #LOCATION}），
 * 连接串来自 {@link WorldPaths}，{@code spring.datasource.url} 降级为兜底（仅供 {@code remote}
 * 等遗留 profile 使用）。</p>
 *
 * <p>为什么 {@code baselineOnMigrate(false)}：目录库永远由本版本首次创建（空文件）。若在一个
 * <b>已有表却没有迁移历史</b>的库上打基线，等于把"旧单体库被误当目录库"这种事故悄悄盖住；
 * 宁可启动失败并提示去检查 URL（迁移器另有 {@code actor_profile} 探测兜底）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public final class RegistrySchema {

    /** 目录库迁移脚本位置（与世界库 {@code classpath:db/migration/world} 对称） */
    public static final String LOCATION = "classpath:db/migration/global";

    private static final Logger log = LoggerFactory.getLogger(RegistrySchema.class);

    private RegistrySchema() {
    }

    /**
     * 在给定连接池上执行目录库迁移（幂等：已是最新则什么都不做）。
     *
     * @param dataSource 目录库连接池
     *
     * @return 本次实际执行的迁移数
     *
     * @throws FlywayException 迁移失败（启动必须失败：schema 不对时继续跑只会写坏数据）
     */
    public static int migrate(DataSource dataSource) {
        MigrateResult result = configure(dataSource).load().migrate();
        if (result.migrationsExecuted > 0) {
            log.info("[world-store] 目录库 schema 迁移完成：执行={} 目标版本={}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        } else {
            log.info("[world-store] 目录库 schema 已是最新：版本={}", result.initialSchemaVersion);
        }
        return result.migrationsExecuted;
    }

    /**
     * 装配一个目录库 Flyway 实例（供 {@code /api/system/schema} 查询迁移状态）。
     *
     * <p>只 {@code load()} 不 {@code migrate()}：真正的迁移在建池时就做完了，
     * 暴露本实例是为了让既有 {@code SystemInfoController} 继续能报版本，而不是再开一次迁移。</p>
     *
     * @param dataSource 目录库连接池
     *
     * @return Flyway 实例
     */
    public static Flyway load(DataSource dataSource) {
        return configure(dataSource).load();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configure(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .baselineOnMigrate(false)
                .failOnMissingLocations(true);
    }
}
