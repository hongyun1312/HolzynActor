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

import com.holzyn.actor.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 存档列表与摘要（V1.1.2-beta.1，只读）。
 *
 * <p>职责：把"目录库的索引行"与"磁盘上的目录事实"合成一张卡片。它<b>不写任何东西</b>——
 * 写索引进 {@link WorldIndexDao}，写世界名/概要进 {@code ProjectService}。</p>
 *
 * <p>为什么要在这一层合并磁盘事实：分档后最容易出现的用户困惑是"列表里有这个存档，
 * 但点开说找不到目录"。把 {@code dirPresent}/{@code dbPresent}/{@code lockPresent}
 * 一起返回，界面才能直接说清"目录被移走了"/"上次没干净退出"，而不是让用户猜。</p>
 *
 * <p>体积列：优先用关闭时回写的快照（{@code size_bytes}），只有从未回写过（新收编的世界）
 * 才现场遍历目录——列表页每刷新一次就递归统计几十 MB 的目录是不可接受的。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Service
public class WorldRegistryService {

    private final WorldIndexDao indexDao;

    private final WorldPaths paths;

    private final MountedWorld mountedWorld;

    public WorldRegistryService(WorldIndexDao indexDao, WorldPaths paths, MountedWorld mountedWorld) {
        this.indexDao = indexDao;
        this.paths = paths;
        this.mountedWorld = mountedWorld;
    }

    /**
     * 存档列表（按 id 升序，顺序稳定）。
     *
     * @return 卡片列表（无存档返回空列表，不返回 null）
     */
    public List<WorldSummaryVO> list() {
        List<WorldSummaryVO> out = new ArrayList<>();
        for (WorldIndexDao.WorldIndexRow row : indexDao.readAll()) {
            out.add(toVO(row));
        }
        return out;
    }

    /**
     * 按主键查卡片。
     *
     * @param id 主键
     *
     * @return 卡片；不存在返回空
     */
    public Optional<WorldSummaryVO> findById(long id) {
        return indexDao.readById(id).map(this::toVO);
    }

    /**
     * 按主键查卡片，不存在抛 404。
     *
     * @param id 主键
     *
     * @return 卡片
     *
     * @throws BizException 404
     */
    public WorldSummaryVO requireById(long id) {
        return findById(id).orElseThrow(() -> new BizException(404, "存档不存在：" + id));
    }

    /**
     * 当前挂载世界的卡片（未挂载返回空）。
     *
     * @return 卡片
     */
    public Optional<WorldSummaryVO> current() {
        String uid = mountedWorld.uid();
        if (uid == null) {
            return Optional.empty();
        }
        return indexDao.readByUid(uid).map(this::toVO);
    }

    /**
     * 世界目录体积（现场统计，仅用于关闭时回写快照）。
     *
     * @param worldUid 世界 uid
     *
     * @return 字节数
     */
    public long measureSize(String worldUid) {
        return paths.worldSizeBytes(worldUid);
    }

    /** 索引行 + 磁盘事实 → 卡片 */
    private WorldSummaryVO toVO(WorldIndexDao.WorldIndexRow row) {
        String uid = row.worldUid();
        boolean dirPresent = false;
        boolean dbPresent = false;
        boolean lockPresent = false;
        long measured = 0L;
        if (uid != null && !uid.isBlank()) {
            try {
                dirPresent = Files.isDirectory(paths.worldDir(uid));
                dbPresent = Files.isRegularFile(paths.worldDbFile(uid));
                lockPresent = Files.exists(paths.worldLockFile(uid));
                if (row.sizeBytes() == null) {
                    measured = paths.worldSizeBytes(uid);
                }
            } catch (RuntimeException e) {
                // uid 非法（历史脏数据）只影响这张卡片，不该让整个列表 500
                log.debug("[world-store] 卡片磁盘探测失败 uid={}：{}", uid, e.toString());
            }
        }
        WorldStatus status = row.status();
        boolean mounted = uid != null && mountedWorld.isMounted(uid);
        Long sizeBytes = row.sizeBytes() != null ? row.sizeBytes() : (measured > 0 ? measured : null);
        // 封面以文件为准：列里有值但文件不在（导入包旧值 / 手工删文件）就当没有封面，
        // 否则前端会拿一个必然 404 的地址去铺卡片，显示成一块空白而不是预备背面
        String coverUrl = row.coverUrl();
        if (coverUrl != null && !coverUrl.isBlank()) {
            try {
                if (uid == null || paths.coverFile(uid) == null) {
                    coverUrl = null;
                }
            } catch (RuntimeException e) {
                coverUrl = null;
            }
        }
        return new WorldSummaryVO(
                row.id(), uid, row.name(), row.summary(), coverUrl,
                status.code(), status.label(),
                status.mountable() && !mounted, mounted,
                dirPresent, dbPresent, lockPresent,
                row.gameSecond(), row.characterCount(), sizeBytes,
                row.lastOpenedAt(), row.lastClosedAt(), row.createdAt(), row.updatedAt(),
                row.schemaVersion(), row.initStatus(), row.initDone(), row.initTotal(),
                row.genre(), row.tone());
    }

    /**
     * 回写世界初始化完成度快照（画廊卡片角标的唯一写入口转发）。
     *
     * <p>为什么本类只做转发：索引列的写入口固定是 {@link WorldIndexDao}（AGENTS §8.1.4），
     * 这里不另写 SQL，只把"项目侧算出来的结论"落到目录库。</p>
     *
     * @param worldId 世界 ID（= 项目 ID）
     * @param status  完成度：NOT_STARTED / PARTIAL / DONE / DEGRADED
     * @param done    已完成步骤数
     * @param total   步骤总数
     */
    public void markInitSnapshot(long worldId, String status, Integer done, Integer total) {
        indexDao.updateInitSummary(worldId, status, done, total);
    }
}
