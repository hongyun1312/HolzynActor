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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 世界挂载 / 卸载状态机（V1.1.2-beta.1 的唯一入口）。
 *
 * <p>全仓库只有这一处能把一个世界"打开"。任何别的代码路径自己去建连接池、自己改
 * {@link MountedWorld}，都会绕过镜像刷新与迁移，造出"能读但配置是旧的"世界。</p>
 *
 * <p>挂载顺序（每一步的顺序都有理由，不能调换）：</p>
 * <ol>
 *   <li>校验 manifest（格式/身份/可搬移性）——在最贵的操作之前失败；</li>
 *   <li>打开世界库（建池 + Flyway 迁移）——空库在这一步建出 113 张表；</li>
 *   <li>刷新 12 张全局镜像——必须在挂载标记之前完成，否则引擎线程会在"配置还没写全"时开始读；</li>
 *   <li>回写 manifest 时间；</li>
 *   <li>最后才 {@code mount(uid)}——此刻世界才对所有线程可见。</li>
 * </ol>
 *
 * <p>卸载顺序反过来：<b>先</b>摘掉挂载标记<b>再</b>关池。反过来会出现"池已关、标记还在"
 * 的窗口，落在窗口里的请求会拿到一个刚被关掉的连接池。摘标记后路由立刻回落到目录库
 * （返回空集），用户看到的是"世界关闭了"，而不是一堆 500。</p>
 *
 * <p>单活动世界：{@link #open} 一个不同 uid 时会先把当前世界正常关掉再开新的，
 * 不做双开（{@code allow-multi-mount} 固定 false）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
public class WorldSessionService {

    /** 时间戳格式（与 manifest 里其余时间字段一致，本地时间、秒级） */
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ReentrantLock lock = new ReentrantLock();

    private final WorldDataSourceFactory dataSources;

    private final MountedWorld mountedWorld;

    private final WorldPaths paths;

    private final WorldManifestService manifests;

    private final WorldMirrorSyncService mirrors;

    private final WorldStoreProperties properties;

    /** uid → 该世界在目录库 {@code actor_project.id}（挂载时解析，解耦目录库访问方式） */
    private final ProjectIdSource projectIds;

    public WorldSessionService(WorldDataSourceFactory dataSources,
                               MountedWorld mountedWorld,
                               WorldPaths paths,
                               WorldManifestService manifests,
                               WorldMirrorSyncService mirrors,
                               WorldStoreProperties properties,
                               ProjectIdSource projectIds) {
        this.dataSources = dataSources;
        this.mountedWorld = mountedWorld;
        this.paths = paths;
        this.manifests = manifests;
        this.mirrors = mirrors;
        this.properties = properties;
        this.projectIds = projectIds;
    }

    /**
     * 打开世界。已打开同一世界时幂等返回。
     *
     * @param worldUid 世界 uid
     *
     * @return 打开结果（是否新打开、镜像同步统计）
     *
     * @throws WorldManifestException manifest 缺失/不兼容
     * @throws IllegalStateException  目录库无该世界索引、世界库迁移失败、镜像刷新失败
     */
    public OpenResult open(String worldUid) {
        WorldPaths.requireSafeUid(worldUid);
        Path worldDir = paths.worldDir(worldUid);
        lock.lock();
        try {
            if (mountedWorld.isMounted(worldUid)) {
                log.debug("[world-store] 世界已挂载，跳过重复打开 uid={}", worldUid);
                return new OpenResult(worldUid, false, null);
            }
            if (mountedWorld.isMounted()) {
                closeLocked("switch");
            }
            if (!Files.isDirectory(worldDir)) {
                throw new IllegalStateException("世界目录不存在：" + worldDir);
            }

            // 1. 校验清单（最便宜、也最该先失败的一步）
            WorldManifest manifest = manifests.readCompatible(worldDir);

            // 2. 目录库索引 → 本地 id
            OptionalLong projectId = projectIds.find(worldUid);
            if (projectId.isEmpty()) {
                throw new IllegalStateException("目录库里没有该世界的索引（uid=" + worldUid
                        + "）：请先在存档列表里「收编」或重新导入。");
            }

            // 3. 打开世界库（建池 + 迁移）
            var worldDataSource = dataSources.worldDataSource(worldUid);

            // 4. 刷新全局镜像（必须早于挂载标记）
            WorldMirrorSyncService.MirrorSyncReport report = null;
            if (properties.isMirrorRefreshOnOpen()) {
                report = mirrors.sync(registryDataSource(), worldDataSource, projectId.getAsLong());
            }

            // 5. 回写清单时间
            String now = LocalDateTime.now().format(TIME_FORMAT);
            manifests.write(worldDir, manifest.withTimes(now, manifest.lastClosedAt()));

            // 6. 最后才对全线程可见
            mountedWorld.mount(worldUid);
            log.info("[world-store] 世界已打开 uid={} name={} 镜像={}",
                    worldUid, manifest.worldName(), report == null ? "跳过" : report.tables() + " 张/" + report.rows() + " 行");
            return new OpenResult(worldUid, true, report);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭当前世界（未挂载时为空操作）。
     *
     * @param reason 关闭原因（日志用：user / switch / shutdown）
     */
    public void close(String reason) {
        lock.lock();
        try {
            closeLocked(reason);
        } finally {
            lock.unlock();
        }
    }

    /** 当前挂载的世界 uid */
    public Optional<String> current() {
        return Optional.ofNullable(mountedWorld.uid());
    }

    /** 是否有世界处于挂载状态 */
    public boolean isOpen() {
        return mountedWorld.isMounted();
    }

    /** 已调用方持锁的关闭实现 */
    private void closeLocked(String reason) {
        String uid = mountedWorld.uid();
        if (uid == null) {
            return;
        }
        // 先摘标记：之后的新请求立刻回落到目录库，不会打到即将关闭的池上
        mountedWorld.unmount();
        try {
            Path worldDir = paths.worldDir(uid);
            WorldManifest manifest = manifests.read(worldDir);
            if (manifest != null) {
                String now = LocalDateTime.now().format(TIME_FORMAT);
                manifests.write(worldDir, manifest.withTimes(manifest.lastOpenedAt(), now));
            }
        } catch (RuntimeException e) {
            // 清单回写失败不该阻止关库：否则用户会卡在"关不掉"的状态，反而更危险
            log.warn("[world-store] 关闭时回写清单失败 uid={}（继续关库）：{}", uid, e.toString());
        } finally {
            dataSources.release(uid);
        }
        log.info("[world-store] 世界已关闭 uid={} reason={}", uid, reason);
    }

    /** 目录库数据源（镜像同步的读源）；具体装配在 Spring 接线时注入 */
    private javax.sql.DataSource registryDataSource;

    /**
     * 注入目录库数据源（由 Spring 装配调用；单测可直接 set）。
     *
     * <p>用 setter 而不是构造器参数：目录库数据源与路由数据源在装配上有先后关系，
     * 构造器注入会形成环（AGENTS §8.1.5 的教训）。</p>
     *
     * @param dataSource 目录库数据源
     */
    public void setRegistryDataSource(javax.sql.DataSource dataSource) {
        this.registryDataSource = dataSource;
    }

    private javax.sql.DataSource registryDataSource() {
        if (registryDataSource == null) {
            throw new IllegalStateException("目录库数据源未装配：WorldSessionService.setRegistryDataSource 未被调用");
        }
        return registryDataSource;
    }

    /**
     * uid → 目录库 {@code actor_project.id}。
     *
     * <p>抽成函数式接口是为了让本类不依赖具体的数据访问方式（JPA 仓储 or 裸 JDBC）：
     * 单测里给个 lambda，生产装配时给 JPA 实现。</p>
     */
    @FunctionalInterface
    public interface ProjectIdSource {

        /**
         * 查世界在目录库里的本地 id。
         *
         * @param worldUid 世界 uid
         *
         * @return id；不存在返回 {@link OptionalLong#empty()}
         */
        OptionalLong find(String worldUid);
    }

    /**
     * 打开结果。
     *
     * @param worldUid     世界 uid
     * @param newlyOpened  是否本次真正打开（false = 之前已打开，幂等返回）
     * @param mirrorReport 镜像同步统计（关闭镜像刷新时为 null）
     */
    public record OpenResult(String worldUid, boolean newlyOpened, WorldMirrorSyncService.MirrorSyncReport mirrorReport) {
    }
}
