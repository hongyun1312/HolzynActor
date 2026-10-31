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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动自检 + 收编 + 关停落盘（V1.1.2-beta.1）。
 *
 * <p>三件事必须在这里做，因为它们都要求"Flyway 已跑完、但还没有请求打进来"：</p>
 * <ol>
 *   <li><b>自检</b>：把路由态、世界根目录、目录库文件、镜像表数、写守卫状态打到日志第一屏。
 *       本项目没有任何 {@code @SpringBootTest}（AGENTS §8.4.3），装配错误只会在真实启动时炸，
 *       所以这几行日志就是"启动后第一件要确认的事"；</li>
 *   <li><b>收编</b>：扫 {@code worlds/} 重建索引（见 {@link WorldAdoptService}）；</li>
 *   <li><b>关停落盘</b>：{@link #destroy()} 把挂载中的世界正常关掉（COMPACT + 回写摘要 + 释放池）。
 *       进程被强杀时这段不会执行，这正是"启动时扫锁文件"存在的原因。</li>
 * </ol>
 *
 * <p>顺序 {@code @Order(1)}：早于 {@code DatabaseStructureValidator}(2) 与各类种子/回填 Runner，
 * 让"存档列表里有什么"在其它启动逻辑之前先确定下来。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
@Order(1)
public class WorldStartupRunner implements ApplicationRunner, DisposableBean {

    private final WorldAdoptService adoptService;

    private final WorldLifecycleService lifecycle;

    private final WorldDataSourceFactory dataSources;

    private final WorldPaths paths;

    private final WorldStoreProperties properties;

    private final MountedWorld mountedWorld;

    public WorldStartupRunner(WorldAdoptService adoptService, WorldLifecycleService lifecycle,
                              WorldDataSourceFactory dataSources, WorldPaths paths,
                              WorldStoreProperties properties, MountedWorld mountedWorld) {
        this.adoptService = adoptService;
        this.lifecycle = lifecycle;
        this.dataSources = dataSources;
        this.paths = paths;
        this.properties = properties;
        this.mountedWorld = mountedWorld;
    }

    /**
     * 启动自检 + 收编（失败不阻断启动，但必须留下 ERROR）。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("[world-store] 启动自检：数据目录={} 世界根={}（存在={}） 目录库={}（存在={}）",
                paths.dataDir(), paths.worldsRoot(), Files.isDirectory(paths.worldsRoot()),
                paths.registryDbFile(), Files.isRegularFile(paths.registryDbFile()));
        log.info("[world-store] 启动自检：镜像表={} 张 目录库表={} 张 当前挂载={} 世界库池={} 个 双开={} 关时压缩={}",
                MirrorTables.ALL.size(), RegistryTables.ALL.size(),
                mountedWorld.uid() == null ? "无" : mountedWorld.uid(),
                dataSources.openCount(), properties.isAllowMultiMount(), properties.isCompactOnClose());
        warnStaleLocks();
        try {
            adoptService.adopt();
        } catch (RuntimeException e) {
            // 收编失败不能让应用起不来：没有世界比"起不来"好得多（用户还能看到错误去排查）
            log.error("[world-store] 启动收编失败（应用继续启动，存档列表可能不完整）", e);
        }
    }

    /**
     * 关停：正常关闭挂载中的世界（等价于用户点"退出世界"）。
     */
    @Override
    public void destroy() {
        try {
            lifecycle.closeCurrentQuietly("shutdown")
                    .ifPresent(o -> log.info("[world-store] 关停已保存世界 uid={} 耗时={}ms", o.worldUid(), o.elapsedMs()));
        } catch (RuntimeException e) {
            log.warn("[world-store] 关停时保存世界失败（不影响退出）：{}", e.toString());
        }
        // 兜底：理论上 closeCurrentQuietly 已释放，这里防"关到一半出错"留下未关的池
        dataSources.releaseAll();
    }

    /** 扫残留锁文件（上次异常退出的痕迹） */
    private void warnStaleLocks() {
        List<Path> dirs = paths.listWorldDirs();
        int stale = 0;
        for (Path dir : dirs) {
            String uid = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (uid.isBlank()) {
                continue;
            }
            Path lock = paths.worldLockFile(uid);
            if (Files.exists(lock)) {
                stale++;
                log.warn("[world-store] 发现残留世界锁文件（上次可能被强杀，H2 会自行回收）：{}", lock);
            }
        }
        if (stale > 0) {
            log.warn("[world-store] 启动自检：{} 个世界的锁文件残留", stale);
        }
    }
}
