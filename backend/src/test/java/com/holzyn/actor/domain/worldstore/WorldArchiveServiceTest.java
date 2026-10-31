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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 存档导出/导入（{@link WorldArchiveService}）单元测试。
 *
 * <p>覆盖设计 §3.11 里"必须证明"的几件事：</p>
 * <ol>
 *   <li>导出包 = {@code manifest.json + world.mv.db}，且<b>包内无 {@code api_key_cipher}</b>（AC5）；</li>
 *   <li>导出<b>不改动</b>世界目录（原库里的密文还在，原目录 mtime/大小不变）；</li>
 *   <li>导入 = 新世界：uid 冲突换新 uid，世界库 88 表 {@code project_id} 全部改成本机新 id，
 *       镜像 {@code actor_project} 被清空（下次挂载整表重建），清单 {@code source.kind=import}；</li>
 *   <li>坏包（缺清单 / formatVersion=2.0）报 400，且不往 {@code worlds/} 里留半截目录；</li>
 *   <li>挂载中的世界拒绝导出（409）。</li>
 * </ol>
 *
 * <p>零 Spring 上下文（对齐仓库 350 个测试类的约定）：{@code WorldPaths} 用真实实现 +
 * {@link TempDir}，目录库/注册服务用 mock。</p>
 */
class WorldArchiveServiceTest {

    /** 世界标识（导出源） */
    private static final String UID = "11111111-2222-3333-4444-555555555555";

    /** 导出源世界的目录库主键 */
    private static final long SOURCE_ID = 7L;

    /** 导入后本机分配的新主键 */
    private static final long NEW_ID = 42L;

    /** 原库里写进去的"密钥密文"，导出后必须只存在于原库 */
    private static final String SECRET_CIPHER = "CIPHER-MUST-NOT-LEAK";

    @TempDir
    Path tmp;

    private WorldPaths paths;

    private WorldManifestService manifests;

    private WorldIndexDao indexDao;

    private WorldRegistryService registry;

    private WorldArchiveService service;

    @BeforeEach
    void setUp() throws Exception {
        WorldStoreProperties properties = new WorldStoreProperties();
        paths = new WorldPaths(properties, tmp.resolve("data").toString());
        manifests = new WorldManifestService();
        indexDao = mock(WorldIndexDao.class);
        registry = mock(WorldRegistryService.class);
        service = new WorldArchiveService(paths, registry, indexDao, manifests, "sa", "");

        // 目录里先放一个"已关闭、可导出"的世界
        seedWorld(UID, SOURCE_ID);
        when(registry.requireById(SOURCE_ID)).thenReturn(summary(SOURCE_ID, UID, false));
    }

    // ------------------------------------------------------------------ 导出

    @Test
    @DisplayName("导出：包含清单与世界库，包内 api_key_cipher 为空，原目录一个字节都不动")
    void exportScrubsSecretsWithoutTouchingSource() throws Exception {
        Path originalDb = paths.worldDbFile(UID);
        byte[] before = Files.readAllBytes(originalDb);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WorldArchiveService.ExportResult result = service.export(SOURCE_ID, out);

        List<String> entries = entryNames(out.toByteArray());
        assertTrue(entries.contains(WorldArchiveService.MANIFEST_ENTRY), "包内必须有 manifest.json：" + entries);
        assertTrue(entries.contains(WorldArchiveService.DB_ENTRY), "包内必须有 world.mv.db：" + entries);
        assertEquals(1, entries.stream().filter(WorldArchiveService.MANIFEST_ENTRY::equals).count(),
                "清单只允许出现一次");
        assertEquals(UID, result.worldUid());
        assertEquals(64, result.dbSha256().length(), "sha256 必须是 64 位十六进制");

        // 包内的库：密文必须被清空
        Path extracted = extractTo(out.toByteArray(), tmp.resolve("extracted-export"));
        Path dbInZip = extracted.resolve(WorldArchiveService.DB_ENTRY);
        assertTrue(Files.isRegularFile(dbInZip));
        try (Connection c = DriverManager.getConnection(WorldPaths.dbUrl(dbInZip), "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from actor_model_provider where api_key_cipher is not null")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getLong(1), "导出包内不得残留 api_key_cipher（AC5）");
        }

        // 原目录：字节完全一致（导出只读）
        byte[] after = Files.readAllBytes(originalDb);
        assertTrue(java.util.Arrays.equals(before, after), "导出不得改写世界库文件");

        // 清单也写进了包，且 formatVersion = 3.0
        WorldManifest packed = manifests.read(extracted);
        assertNotNull(packed);
        assertEquals(WorldManifest.CURRENT_FORMAT_VERSION, packed.formatVersion());
        assertEquals(UID, packed.worldUid());
        assertEquals(result.dbSha256(), packed.checksums().get(WorldArchiveService.DB_ENTRY));
    }

    @Test
    @DisplayName("导出：挂载中的世界拒绝（409 WORLD_OPEN）")
    void exportRejectsMountedWorld() {
        when(registry.requireById(SOURCE_ID)).thenReturn(summary(SOURCE_ID, UID, true));
        BizException e = assertThrows(BizException.class,
                () -> service.export(SOURCE_ID, new ByteArrayOutputStream()));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("WORLD_OPEN"), e.getMessage());
    }

    // ------------------------------------------------------------------ 导入

    @Test
    @DisplayName("导入：uid 冲突换新 uid，project_id 全改写，镜像行清空，清单标记 import")
    void importRewritesOwnershipAsNewWorld() throws Exception {
        byte[] zip = exportBytes();

        // 源 uid 已存在 → 必须换新
        when(indexDao.readByUid(UID)).thenReturn(Optional.of(new WorldIndexDao.WorldIndexRow(
                SOURCE_ID, UID, "老世界", null, null, WorldStatus.CLOSED, null, null, null,
                null, null, LocalDateTime.now(), LocalDateTime.now(), WorldManifest.SCHEMA_VERSION,
                null, null, null, "仙侠", "standard")));
        when(indexDao.insertIndexRow(anyString(), anyString(), anyLong(), any(), anyString(), any(), any()))
                .thenReturn(NEW_ID);

        WorldArchiveService.ImportResult r = service.importPackage(new ByteArrayInputStream(zip), "demo.holzyn", 1L);

        assertEquals(NEW_ID, r.worldId());
        assertTrue(r.uidRewritten(), "uid 冲突必须换新身份");
        assertFalse(UID.equals(r.worldUid()), "落位目录不能与源 uid 同名");
        assertEquals("测试世界", r.name(), "世界名取包内镜像行");
        assertEquals(12345L, r.gameSecond(), "世界时间取包内 actor_world_clock");
        assertEquals(3L, r.characterCount(), "角色数取包内镜像行");

        Path landedDir = paths.worldDir(r.worldUid());
        assertTrue(Files.isDirectory(landedDir), "必须落位到 worlds/<uid>");
        Path landedDb = paths.worldDbFile(r.worldUid());

        try (Connection c = DriverManager.getConnection(WorldPaths.dbUrl(landedDb), "sa", "");
             Statement st = c.createStatement()) {
            // 所有带 project_id 的表都指向本机新 id
            try (ResultSet rs = st.executeQuery("select count(*) from actor_world_clock where project_id <> " + NEW_ID)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong(1), "actor_world_clock.project_id 必须改写");
            }
            try (ResultSet rs = st.executeQuery("select count(*) from actor_character where project_id <> " + NEW_ID)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong(1), "actor_character.project_id 必须改写");
            }
            // 镜像行清空（下次挂载由 WorldMirrorSyncService 用目录库数据整表重建）
            try (ResultSet rs = st.executeQuery("select count(*) from actor_project")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong(1), "镜像 actor_project 必须清空");
            }
        }

        WorldManifest landed = manifests.read(landedDir);
        assertEquals(WorldManifest.CURRENT_FORMAT_VERSION, landed.formatVersion());
        assertEquals(r.worldUid(), landed.worldUid());
        assertNotNull(landed.source());
        assertEquals("import", landed.source().kind());
        assertEquals("demo.holzyn", landed.source().from());

        // 索引摘要回写（列表卡片不打开世界库就要能显示时间/角色数）
        org.mockito.Mockito.verify(indexDao).updateMeta(eq(NEW_ID), eq("测试世界"), eq("摘要-原文"), eq("cover.png"), any());
        org.mockito.Mockito.verify(indexDao).markClosed(eq(NEW_ID), any());
    }

    @Test
    @DisplayName("导入：包缺 manifest.json → 400，且不在 worlds/ 里留下任何目录")
    void importRejectsPackageWithoutManifest() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("world.mv.db"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        BizException e = assertThrows(BizException.class,
                () -> service.importPackage(new ByteArrayInputStream(out.toByteArray()), "bad.holzyn", 1L));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("manifest.json"), e.getMessage());
        assertNoWorldDirsLeft();
    }

    @Test
    @DisplayName("导入：formatVersion=2.0（旧逐表 JSON 包）→ 400，并指出不再支持 v2")
    void importRejectsLegacyFormatVersion() throws Exception {
        byte[] zip = exportBytes();
        byte[] patched = rewriteManifest(zip, "3.0", "2.0");
        BizException e = assertThrows(BizException.class,
                () -> service.importPackage(new ByteArrayInputStream(patched), "old.holzyn", 1L));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("2.0"), e.getMessage());
        assertNoWorldDirsLeft();
    }

    // ------------------------------------------------------------------ 辅助

    /** 造一个"关闭状态"的世界目录：库 + 清单（库里有密文、时钟、角色、镜像行） */
    private void seedWorld(String uid, long projectId) throws Exception {
        Path dir = paths.worldDir(uid);
        Files.createDirectories(dir);
        Path dbFile = paths.worldDbFile(uid);
        try (Connection c = DriverManager.getConnection(WorldPaths.dbUrl(dbFile), "sa", "");
             Statement st = c.createStatement()) {
            st.execute("create table actor_project (id bigint primary key, project_uid varchar(64), "
                    + "name varchar(255), summary clob, cover_url varchar(512), character_count int)");
            st.execute("create table actor_world_clock (id bigint primary key, project_id bigint, "
                    + "last_game_second bigint)");
            st.execute("create table actor_character (id bigint primary key, project_id bigint, name varchar(100))");
            st.execute("create table actor_model_provider (id bigint primary key, project_id bigint, api_key_cipher clob)");
            st.execute("insert into actor_project values (" + projectId + ", '" + uid + "', '测试世界', '摘要-原文', 'cover.png', 3)");
            st.execute("insert into actor_world_clock values (1, " + projectId + ", 12345)");
            st.execute("insert into actor_character values (1, " + projectId + ", '甲')");
            st.execute("insert into actor_character values (2, " + projectId + ", '乙')");
            st.execute("insert into actor_model_provider values (1, " + projectId + ", '" + SECRET_CIPHER + "')");
        }
        manifests.write(dir, new WorldManifest(WorldManifest.CURRENT_FORMAT_VERSION, WorldManifest.SCHEMA_VERSION,
                uid, "测试世界", LocalDateTime.now().toString(), null, null,
                new WorldManifest.Source("new", null, null, LocalDateTime.now().toString()),
                java.util.Map.of("gameSecond", 12345L), java.util.Map.of(),
                WorldPaths.RESOURCE_DIR_NAMES, WorldManifest.DEFAULT_DB_FILE));
    }

    private WorldSummaryVO summary(long id, String uid, boolean mounted) {
        return new WorldSummaryVO(id, uid, "测试世界", "摘要-原文", "cover.png",
                mounted ? "mounted" : "closed", mounted ? "运行中" : "可继续",
                !mounted, mounted, true, true, false, 12345L, 3L, 1024L,
                null, null, LocalDateTime.now(), LocalDateTime.now(), WorldManifest.SCHEMA_VERSION,
                null, null, null, "仙侠", "standard");
    }

    private byte[] exportBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(SOURCE_ID, out);
        return out.toByteArray();
    }

    /** zip 条目名（保序） */
    private static List<String> entryNames(byte[] zipBytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    /** 解到目录（测试自己的解包，不复用被测代码，避免"用同一份 bug 验证自己"） */
    private static Path extractTo(byte[] zipBytes, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path target = dest.resolve(e.getName()).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dest;
    }

    /** 把包内 manifest.json 的 formatVersion 换掉（模拟旧包） */
    private byte[] rewriteManifest(byte[] zipBytes, String from, String to) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                byte[] data = zip.readAllBytes();
                if (WorldArchiveService.MANIFEST_ENTRY.equals(e.getName())) {
                    data = new String(data, StandardCharsets.UTF_8)
                            .replace("\"formatVersion\":\"" + from + "\"", "\"formatVersion\":\"" + to + "\"")
                            .getBytes(StandardCharsets.UTF_8);
                }
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(data);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 失败导入不得留下目录（否则启动收编会把它登记成打不开的僵尸存档）。
     *
     * <p>允许存在的只有测试自己预置的源世界 {@link #UID}——"没有新增目录"才是这里要证明的。</p>
     */
    private void assertNoWorldDirsLeft() throws IOException {
        Path root = paths.worldsRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            List<String> left = children.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of(UID), left, "导入失败后 worlds/ 下只应剩下预置的源世界");
        }
    }
}
