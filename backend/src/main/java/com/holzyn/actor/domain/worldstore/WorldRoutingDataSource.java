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

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Function;

/**
 * 两态路由数据源：目录库 / 当前挂载世界库（V1.1.2-beta.1）。
 *
 * <p>路由规则只有一条：</p>
 * <pre>
 * 线程处于全局上下文（WorldContext.isGlobal()）  → 目录库
 * 否则当前有没有挂载世界：
 *     有  → 世界库
 *     没有 → 目录库（未挂载时一切只读目录库，不报错，保证应用能空转启动）
 * </pre>
 *
 * <p><b>为什么不用 Hibernate 的多租户 DATABASE 策略</b>：那需要两个（或每世界一个）
 * EntityManagerFactory，而本仓库 350 个测试类全部直接 {@code new} 服务、无
 * {@code @SpringBootTest}，改 EMF 装配方式会连带打断所有构造点（AGENTS §8.1.2 明令
 * 禁止改既有构造器签名）。单 EMF + 路由数据源是此处唯一低风险路径。</p>
 *
 * <p><b>为什么世界库不在启动时全部建池</b>：世界可能很多，且单活动世界下同时只需要
 * 一个世界池。按 uid 懒建、卸载即销毁，既省连接也省内存，还顺手保证"没挂载的世界
 * 一定没被打开"（H2 文件库被第二个连接池打开会锁冲突）。</p>
 *
 * <p><b>为什么未挂载时回落到目录库而不是抛错</b>：应用启动到第一个世界被打开之间，
 * 定时任务、SSE 心跳、健康检查都会碰数据库。这个窗口抛错会让应用根本起不来；
 * 回落到目录库则所有查询返回空集，语义正确（没有世界 = 没有世界数据）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
public class WorldRoutingDataSource extends AbstractRoutingDataSource {

    /** 目录库路由键 */
    public static final String KEY_GLOBAL = "GLOBAL";

    /** 世界库路由键前缀（{@code WORLD:<uid>}） */
    public static final String KEY_WORLD_PREFIX = "WORLD:";

    /** 目录库数据源（全局上下文与未挂载时的落点） */
    private final DataSource registryDataSource;

    /** 当前挂载世界的读取入口（不持有会话服务，避免 Bean 环） */
    private final MountedWorld mountedWorld;

    /** uid → 世界库数据源（不存在返回 null） */
    private final Function<String, DataSource> worldResolver;

    public WorldRoutingDataSource(DataSource registryDataSource,
                                  MountedWorld mountedWorld,
                                  Function<String, DataSource> worldResolver) {
        this.registryDataSource = registryDataSource;
        this.mountedWorld = mountedWorld;
        this.worldResolver = worldResolver;
        // 只登记目录库：世界库走 determineTargetDataSource 懒解析，
        // 因为 AbstractRoutingDataSource 的 targetDataSources 要求启动时全量已知。
        setDefaultTargetDataSource(registryDataSource);
        setTargetDataSources(Map.of(KEY_GLOBAL, registryDataSource));
        afterPropertiesSet();
    }

    /**
     * 本次访问该打哪个库。
     *
     * @return {@link #KEY_GLOBAL} 或 {@code WORLD:<uid>}
     */
    @Override
    protected Object determineCurrentLookupKey() {
        if (WorldContext.isGlobal()) {
            return KEY_GLOBAL;
        }
        String uid = mountedWorld.uid();
        return uid == null ? KEY_GLOBAL : KEY_WORLD_PREFIX + uid;
    }

    /**
     * 解析目标数据源（覆盖父类，以支持"世界库按需建池"）。
     *
     * @return 目标数据源
     *
     * @throws IllegalStateException 路由到某世界但其数据源不存在（挂载/卸载竞态）
     */
    @Override
    protected DataSource determineTargetDataSource() {
        Object key = determineCurrentLookupKey();
        if (KEY_GLOBAL.equals(key)) {
            return registryDataSource;
        }
        String uid = ((String) key).substring(KEY_WORLD_PREFIX.length());
        DataSource ds = worldResolver.apply(uid);
        if (ds == null) {
            // 竞态：读路由键时该世界还挂着，取池时已被关闭。抛明确异常优于"悄悄写目录库"。
            throw new IllegalStateException("世界已卸载但仍有访问（uid=" + uid + "）："
                    + "该请求落在一个正在关闭的世界库上，请重试。");
        }
        return ds;
    }

    /**
     * 当前路由键（诊断/日志/健康检查用；不做连接）。
     *
     * @return 路由键字符串
     */
    public String currentKey() {
        Object key = determineCurrentLookupKey();
        return key == null ? KEY_GLOBAL : key.toString();
    }

    /**
     * 目录库数据源（迁移器、镜像同步、启动自检需要绕过路由直连目录库时用）。
     *
     * @return 目录库数据源
     */
    public DataSource registryDataSource() {
        return registryDataSource;
    }
}
