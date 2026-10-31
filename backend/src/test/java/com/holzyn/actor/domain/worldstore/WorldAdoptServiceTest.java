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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动收编（V1.1.2-beta.1 M2）。
 *
 * <p>收编是"把整个 worlds/&lt;uid&gt;/ 拷到另一台机器就能用"的唯一实现路径，它的四个分支
 * （收编 / 校验 / 缺目录 / 清单坏）都必须真跑一遍：任何一个分支写错，用户看到的是
 * "存档列表空着"或"存档在列表里但永远打不开"，而这两种现象都无法从日志反推到收编逻辑。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldAdoptServiceTest {

    @TempDir
    Path tmp;

    private WorldPaths paths;

    private WorldManifestService manifests;

    private WorldIndexDao indexDao;

    private WorldAdoptService adopt;

    private static final String UID = "41057f92-c7d9-4dc5-9176-b621be0d0466";

    @BeforeEach
    void setUp() {
        paths = WorldStoreTestSupport.paths(tmp);
        manifests = new WorldManifestService();
        DataSource registry = WorldStoreTestSupport.mem("adopt", WorldStoreTestSupport.REGISTRY);
        indexDao = new WorldIndexDao(registry);
        adopt = new WorldAdoptService(paths, manifests, indexDao);
    }

    @Test
    @DisplayName("收编：磁盘有世界、索引无行 → 插入索引行（不自动挂载）")
    void adoptsWorldOnDisk() {
        writeManifest(UID, WorldManifest.fresh(UID, "青禾村", "1.1.2-beta.1", now()));

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(1, report.scanned());
        assertEquals(1, report.adopted());
        assertTrue(report.clean(), "不应有问题：" + report.problems());
        WorldIndexDao.WorldIndexRow row = indexDao.readByUid(UID).orElseThrow();
        assertEquals("青禾村", row.name());
        assertEquals(WorldStatus.CLOSED, row.status(), "收编后是可挂载状态，但不得自动打开");
        assertEquals("1.1.2-beta.1", row.schemaVersion());
        assertTrue(row.id() > 0);
    }

    @Test
    @DisplayName("幂等：连续收编两次只插一行（反复重启不会长出一堆重复存档）")
    void adoptIsIdempotent() {
        writeManifest(UID, WorldManifest.fresh(UID, "青禾村", "1.1.2-beta.1", now()));
        assertEquals(1, adopt.adopt().adopted());
        WorldAdoptService.AdoptReport second = adopt.adopt();
        assertEquals(0, second.adopted(), "第二次不应再收编");
        assertEquals(1, indexDao.readAll().size());
    }

    @Test
    @DisplayName("版本不兼容：标记 incompatible 且记录原因，不静默升级")
    void marksIncompatibleVersion() {
        WorldManifest old = new WorldManifest("2.0", "1.1.0-beta.1", UID, "旧版世界", now(), null, null,
                null, Map.of(), Map.of(), List.of(), "world.mv.db");
        writeManifest(UID, old);

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(1, report.incompatible());
        assertEquals(WorldStatus.INCOMPATIBLE, indexDao.readByUid(UID).orElseThrow().status());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("版本不兼容")), report.problems().toString());
    }

    @Test
    @DisplayName("清单损坏：标记 broken（不是当成普通目录忽略）")
    void marksBrokenManifest() throws IOException {
        Path dir = paths.worldDir(UID);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(WorldManifestService.FILE_NAME), "{not json", StandardCharsets.UTF_8);

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(WorldStatus.BROKEN, indexDao.readAll().get(0).status());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("清单损坏")), report.problems().toString());
    }

    @Test
    @DisplayName("目录缺失：索引有行、磁盘没目录 → 标 missing，且不删行")
    void marksMissingDirWithoutDeleting() throws IOException {
        Files.createDirectories(paths.worldsRoot());
        long id = indexDao.insertIndexRow("uid-gone", "被移走的世界", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(1, report.missing());
        WorldIndexDao.WorldIndexRow row = indexDao.readById(id).orElseThrow();
        assertEquals(WorldStatus.MISSING, row.status());
        assertEquals("被移走的世界", row.name(), "行必须还在（uid 与用量统计都挂在行上）");
    }

    @Test
    @DisplayName("worlds/ 整个不存在：不做缺失判定（M2 过渡期不许把现有世界全标成 missing）")
    void doesNothingWhenWorldsRootAbsent() {
        // 不建 worlds/，只插一行索引（模拟升级前玩家已有世界）
        long id = indexDao.insertIndexRow(UID, "青禾村", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(0, report.missing(), "worlds/ 不存在时不能判定目录缺失");
        assertEquals(WorldStatus.CLOSED, indexDao.readById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("上次异常退出：残留 mounted 归零为 closed")
    void resetsStaleMounted() {
        writeManifest(UID, WorldManifest.fresh(UID, "青禾村", "1.1.2-beta.1", now()));
        adopt.adopt();
        long id = indexDao.readByUid(UID).orElseThrow().id();
        indexDao.markMounted(id, UID, LocalDateTime.now());

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(1, report.resetMounted());
        assertEquals(WorldStatus.CLOSED, indexDao.readByUid(UID).orElseThrow().status());
    }

    @Test
    @DisplayName("兼容状态修正：上次标了 incompatible，换回兼容版本后恢复 closed")
    void recoversFromIncompatible() {
        writeManifest(UID, WorldManifest.fresh(UID, "青禾村", "1.1.2-beta.1", now()));
        adopt.adopt();
        long id = indexDao.readByUid(UID).orElseThrow().id();
        indexDao.markStatus(id, WorldStatus.INCOMPATIBLE, LocalDateTime.now());

        adopt.adopt();

        assertEquals(WorldStatus.CLOSED, indexDao.readByUid(UID).orElseThrow().status(),
                "版本修好后应恢复可挂载，否则存档永远打不开");
    }

    @Test
    @DisplayName("无 manifest 的子目录忽略（resources/ 之类不会被误认成世界）")
    void ignoresNonWorldDirs() throws IOException {
        Files.createDirectories(paths.worldsRoot().resolve("resources"));
        Files.createDirectories(paths.worldsRoot().resolve("tmp-scratch"));

        WorldAdoptService.AdoptReport report = adopt.adopt();

        assertEquals(0, report.scanned());
        assertEquals(0, report.adopted());
        assertTrue(indexDao.readAll().isEmpty());
    }

    private void writeManifest(String uid, WorldManifest manifest) {
        manifests.write(paths.worldDir(uid), manifest);
        assertNotNull(paths.manifestFile(uid).getParent());
    }

    private static String now() {
        return LocalDateTime.now().format(WorldSessionService.TIME_FORMAT);
    }
}
