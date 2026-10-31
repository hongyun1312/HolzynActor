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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档生命周期编排（V1.1.2-beta.1 M2）——整条链路真跑。
 *
 * <p>这条链路是"打开一个世界"的全部动作：校验清单 → 建世界库并迁移（113 张表）→ 从目录库
 * 重建 12 张镜像 → 挂载 → 回写索引；关闭则反过来：读摘要 → 关库（COMPACT）→ 回写摘要与体积。
 * 单测分别测过每一段，但<b>段与段的顺序</b>只有整体跑才能验证——顺序错了不会编译失败，
 * 只会在真实使用中表现为"世界时间归零"或"配置是旧的"。</p>
 *
 * <p>同时验证 AC2 的可测部分：空世界打开/关闭的耗时、关闭后锁文件不残留、库文件保留。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldLifecycleServiceTest {

    private static final String UID_A = "41057f92-c7d9-4dc5-9176-b621be0d0466";

    private static final String UID_B = "30df4814-2ff6-43bf-9412-37cdda983e12";

    @TempDir
    Path tmp;

    private WorldPaths paths;

    private WorldManifestService manifests;

    private MountedWorld mounted;

    private WorldDataSourceFactory factory;

    private WorldSessionService sessions;

    private WorldIndexDao indexDao;

    private WorldLifecycleService lifecycle;

    private DataSource registry;

    @BeforeEach
    void setUp() {
        paths = WorldStoreTestSupport.paths(tmp);
        WorldStoreProperties props = WorldStoreTestSupport.props(paths.worldsRoot().toString());
        manifests = new WorldManifestService();
        mounted = new MountedWorld();
        factory = new WorldDataSourceFactory(paths, props, "sa", "");
        registry = WorldStoreTestSupport.mem("lifecycle", WorldStoreTestSupport.REGISTRY);
        indexDao = new WorldIndexDao(registry);
        sessions = new WorldSessionService(factory, mounted, paths, manifests,
                new WorldMirrorSyncService(), props, indexDao::findIdByUid);
        sessions.setRegistryDataSource(registry);
        lifecycle = new WorldLifecycleService(sessions,
                new WorldRegistryService(indexDao, paths, mounted),
                indexDao, factory, new WorldSummaryReader(), paths, manifests);
    }

    @AfterEach
    void tearDown() {
        try {
            sessions.close("test");
        } catch (RuntimeException ignored) {
            // 测试收尾不关心
        }
        factory.releaseAll();
        mounted.unmount();
        WorldContext.clear();
    }

    @Test
    @DisplayName("打开空世界：建 113 张表 + 刷 12 张镜像 + 挂载 + 回写索引（AC2 耗时）")
    void openEmptyWorld() {
        long id = createWorld(UID_A, "青禾村");

        long start = System.currentTimeMillis();
        WorldLifecycleService.OpenOutcome outcome = lifecycle.open(id);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(outcome.newlyOpened());
        assertEquals(12, outcome.mirrorTables(), "12 张全局镜像必须全部刷新");
        assertTrue(elapsed < 5000, "打开空世界耗时 " + elapsed + "ms（AC2 目标 ≤3s，测试留余量）");
        assertEquals(UID_A, sessions.current().orElseThrow(), "打开后必须成为当前世界");
        assertTrue(Files.isRegularFile(paths.worldDbFile(UID_A)), "世界库文件必须已创建");
        assertEquals(116, worldTableCount(UID_A), "世界库必须是 116 张表（113 基线 + V207 三张）");

        WorldIndexDao.WorldIndexRow row = indexDao.readById(id).orElseThrow();
        assertEquals(WorldStatus.MOUNTED, row.status());
        assertNotNull(row.lastOpenedAt(), "打开时间要能显示在存档列表");
    }

    @Test
    @DisplayName("关闭空世界：摘要回写 + 体积 + 锁文件不残留 + 库文件保留")
    void closeEmptyWorld() {
        long id = createWorld(UID_A, "青禾村");
        lifecycle.open(id);

        WorldLifecycleService.CloseOutcome outcome = lifecycle.close(id);

        assertEquals(UID_A, outcome.worldUid());
        assertFalse(outcome.lockResidual(), "关闭后不得残留 H2 锁文件（残留 = 没干净落盘）");
        assertFalse(Files.exists(paths.worldLockFile(UID_A)), "锁文件必须已消失");
        assertTrue(Files.isRegularFile(paths.worldDbFile(UID_A)), "关库不能删掉世界库文件");
        assertTrue(outcome.sizeBytes() != null && outcome.sizeBytes() > 0, "必须回写体积快照");
        assertTrue(sessions.current().isEmpty(), "关闭后不得再有当前世界");

        WorldIndexDao.WorldIndexRow row = indexDao.readById(id).orElseThrow();
        assertEquals(WorldStatus.CLOSED, row.status());
        assertNotNull(row.lastClosedAt());
        assertEquals(outcome.sizeBytes(), row.sizeBytes());
    }

    @Test
    @DisplayName("摘要取自世界库：游戏内时间与角色数在关闭时回写（不是 0、不是 NULL）")
    void writesSummaryFromWorldDatabase() {
        long id = createWorld(UID_A, "青禾村");
        lifecycle.open(id);
        DataSource world = factory.peek(UID_A);
        WorldStoreTestSupport.exec(world,
                "insert into actor_world_clock (project_id, paused, rate, dropped_game_seconds, "
                        + "last_game_hour, world_start_game_hour, last_game_second) values (" + id + ", 1, 4, 0, 0, 0, 13044230551)",
                "insert into actor_profile (project_id, type, name, deleted) values (" + id + ", 'npc', '阿禾', 0)",
                "insert into actor_profile (project_id, type, name, deleted) values (" + id + ", 'npc', '阿土', 0)");

        WorldLifecycleService.CloseOutcome outcome = lifecycle.close(id);

        assertEquals(13_044_230_551L, outcome.gameSecond(), "世界时间必须取自 actor_world_clock.last_game_second");
        assertEquals(2L, outcome.characterCount(), "角色数必须只算未删除的");

        WorldIndexDao.WorldIndexRow row = indexDao.readById(id).orElseThrow();
        assertEquals(13_044_230_551L, row.gameSecond());
        assertEquals(2L, row.characterCount());
    }

    @Test
    @DisplayName("单活动世界：已有世界挂载时打开另一个 → 409 WORLD_BUSY，且当前世界不受影响")
    void rejectsSecondWorld() {
        long a = createWorld(UID_A, "青禾村");
        long b = createWorld(UID_B, "浮生小镇");
        lifecycle.open(a);

        BizException e = assertThrows(BizException.class, () -> lifecycle.open(b));

        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("WORLD_BUSY"), e.getMessage());
        assertEquals(UID_A, sessions.current().orElseThrow(), "被拒绝的打开不得把当前世界关掉");
    }

    @Test
    @DisplayName("关闭未挂载的世界 → 409 WORLD_NOT_OPEN")
    void rejectsCloseWhenNothingMounted() {
        long a = createWorld(UID_A, "青禾村");
        BizException e = assertThrows(BizException.class, () -> lifecycle.close(a));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("WORLD_NOT_OPEN"), e.getMessage());
    }

    @Test
    @DisplayName("关闭另一个世界 → 409 WORLD_MISMATCH（不能把当前世界的库当目标关掉）")
    void rejectsCloseMismatch() {
        long a = createWorld(UID_A, "青禾村");
        long b = createWorld(UID_B, "浮生小镇");
        lifecycle.open(a);

        BizException e = assertThrows(BizException.class, () -> lifecycle.close(b));

        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("WORLD_MISMATCH"), e.getMessage());
        assertEquals(UID_A, sessions.current().orElseThrow());
    }

    @Test
    @DisplayName("目录不存在 → 409 WORLD_BROKEN 且消息指出路径")
    void rejectsOpenWhenDirMissing() {
        long id = indexDao.insertIndexRow(UID_A, "青禾村", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());

        BizException e = assertThrows(BizException.class, () -> lifecycle.open(id));

        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("WORLD_BROKEN"), e.getMessage());
        assertTrue(e.getMessage().contains("目录"), e.getMessage());
        assertTrue(sessions.current().isEmpty());
    }

    @Test
    @DisplayName("状态不可挂载（目录缺失/不兼容）→ 直接 409，不去碰磁盘")
    void rejectsOpenWhenStatusNotMountable() {
        long id = createWorld(UID_A, "青禾村");
        indexDao.markStatus(id, WorldStatus.MISSING, LocalDateTime.now());

        BizException e = assertThrows(BizException.class, () -> lifecycle.open(id));

        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("目录缺失"), e.getMessage());
        assertFalse(mounted.isMounted(), "状态不可挂载时不能打开");
    }

    // ------------------------------------------------------------------ 工具

    @Test
    @DisplayName("封面上传：写入世界目录并回写相对文件名；移除后文件与索引一起清掉")
    void uploadsAndRemovesCover() throws Exception {
        long id = createWorld(UID_A, "青禾村");

        WorldSummaryVO uploaded = lifecycle.uploadCover(id, pngUpload(120, 80));

        assertEquals(WorldPaths.COVER_REL_PREFIX + WorldLifecycleService.COVER_PNG, uploaded.coverUrl(),
                "索引只存相对路径，不存绝对 URL");
        Path file = paths.worldUploadsDir(UID_A).resolve(WorldLifecycleService.COVER_PNG);
        assertTrue(Files.isRegularFile(file), "封面必须落在世界的 uploads/ 里（导出/导入只搬这个资源目录才带得走）");
        BufferedImage image = ImageIO.read(file.toFile());
        assertNotNull(image, "落盘文件必须是可解码的图片");
        assertEquals(120, image.getWidth());
        assertEquals(80, image.getHeight());

        WorldSummaryVO removed = lifecycle.removeCover(id);

        assertNull(removed.coverUrl(), "移除封面必须清空索引（否则前端一直请求 404 的封面）");
        assertFalse(Files.exists(file), "移除封面必须删掉世界目录里的图片");
        assertNull(lifecycle.coverFile(UID_A));
    }

    @Test
    @DisplayName("封面校验：非图片 → 400，且不在世界目录里留下垃圾文件")
    void rejectsInvalidCover() {
        long id = createWorld(UID_A, "青禾村");

        BizException e = assertThrows(BizException.class, () -> lifecycle.uploadCover(id,
                new MockMultipartFile("file", "a.txt", "text/plain", "不是图片".getBytes())));

        assertEquals(400, e.getCode());
        assertNull(lifecycle.coverFile(UID_A));
    }

    /** 造一张可解码的 PNG 上传体 */
    private static MockMultipartFile pngUpload(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.decode("#1d2946"));
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "cover.png", "image/png", out.toByteArray());
    }

    private long createWorld(String uid, String name) {
        manifests.write(paths.worldDir(uid), WorldManifest.fresh(uid, name, "1.1.2-beta.1", "2026-10-24T22:00:00"));
        return indexDao.insertIndexRow(uid, name, 1L, WorldStatus.CLOSED, "1.1.2-beta.1", null, LocalDateTime.now());
    }

    private long worldTableCount(String uid) {
        DataSource world = factory.peek(uid);
        assertNotNull(world, "世界库未打开");
        return WorldStoreTestSupport.scalar(world,
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name not like 'flyway%'");
    }
}
