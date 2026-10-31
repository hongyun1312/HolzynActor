package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界存档路径唯一权威的契约（V1.1.2-beta.1 M1）。
 *
 * <p>锁定的都是"错了会在真实搬存档时才暴露"的点：</p>
 * <ol>
 *   <li>默认派生：{@code root-dir} 留空时必须落到 {@code <data-dir>/worlds}，
 *       且 data-dir 被绝对化（相对路径会在不同工作目录下解析成两个不同的 worlds 目录）；</li>
 *   <li>布局形状：{@code worlds/<uid>/{manifest.json, world.mv.db, resources}}、
 *       目录库 {@code <data-dir>/holzyn-registry.mv.db}——这是"拷文件夹即带走存档"的物理前提；</li>
 *   <li>uid 安全：uid 参与路径拼接且来源含 URL 参数，必须拦住 {@code ../}、分隔符、超长；</li>
 *   <li>H2 连接串：绝对路径 + 正斜杠 + 与主库同一套方言参数（反斜杠/缺参数会让
 *       世界库以不同大小写语义打开，迁移后表名对不上）；</li>
 *   <li>扫描：只认含 {@code manifest.json} 的目录，避免把 {@code resources/} 之类误当世界。</li>
 * </ol>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldPathsTest {

    private static WorldStoreProperties props(String rootDir) {
        WorldStoreProperties p = new WorldStoreProperties();
        p.setRootDir(rootDir);
        return p;
    }

    @Test
    @DisplayName("默认派生：root-dir 留空 → <data-dir>/worlds，且 data-dir 绝对化")
    void defaultRootIsDerivedUnderDataDir() {
        WorldPaths paths = new WorldPaths(props(""), "./data");
        assertTrue(paths.dataDir().isAbsolute(), "data-dir 必须绝对化（相对路径会随工作目录漂移）");
        assertEquals(paths.dataDir().resolve("worlds").normalize(), paths.worldsRoot().normalize(),
                "root-dir 留空时应派生为 <data-dir>/worlds");
        assertEquals(paths.dataDir().resolve("archive").normalize(), paths.archiveRoot().normalize());
    }

    @Test
    @DisplayName("显式 root-dir 覆盖派生值")
    void explicitRootOverridesDerivation() {
        WorldPaths paths = new WorldPaths(props("./saves"), "./data");
        assertTrue(paths.worldsRoot().isAbsolute());
        assertTrue(paths.worldsRoot().endsWith("saves"), "显式 root-dir 应生效：" + paths.worldsRoot());
        assertFalse(paths.worldsRoot().equals(paths.dataDir().resolve("worlds")),
                "显式 root-dir 不应再派生");
    }

    @Test
    @DisplayName("布局形状：目录库/世界库/四类资源/manifest 各就各位（M3 定稿）")
    void layoutShapeMatchesDesign() {
        WorldPaths paths = new WorldPaths(props(""), "./data");
        Path data = paths.dataDir();

        assertEquals(data.resolve("holzyn-registry.mv.db"), paths.registryDbFile());

        String uid = "41057f92-c7d9-4dc5-9176-b621be0d0466";
        Path worldDir = paths.worldDir(uid);
        assertEquals(paths.worldsRoot().resolve(uid), worldDir);
        assertEquals(worldDir.resolve("manifest.json"), paths.manifestFile(uid));
        assertEquals(worldDir.resolve("world.mv.db"), paths.worldDbFile(uid));

        // 四类资源必须与 manifest.resources 的写法逐字一致（否则导入/导出会漏资源）
        assertEquals(worldDir.resolve("vector-store"), paths.worldVectorStoreDir(uid));
        assertEquals(worldDir.resolve("worldmap"), paths.worldWorldmapDir(uid));
        assertEquals(worldDir.resolve("worldgen"), paths.worldWorldgenDir(uid));
        assertEquals(worldDir.resolve("uploads"), paths.worldUploadsDir(uid));
        assertEquals(List.of("vector-store", "worldmap", "worldgen", "uploads"), WorldPaths.RESOURCE_DIR_NAMES);
        assertEquals(4, WorldPaths.RESOURCE_DIR_NAMES.size());
    }

    @Test
    @DisplayName("旧单库与旧资源根的路径也由本类派生（迁移期唯一读源）")
    void legacyPathsAreDerivedHere() {
        WorldPaths paths = new WorldPaths(props(""), "./data");
        Path data = paths.dataDir();

        assertEquals(data.resolve("holzyn-actor.mv.db"), paths.legacyDbFile());
        assertTrue(paths.legacyDbUrl().endsWith("holzyn-actor" + WorldPaths.H2_PARAMS),
                "旧库连接串必须是文件基名 + 同一套方言参数：" + paths.legacyDbUrl());
        assertEquals(data.resolve("vector-store"), paths.legacyVectorStoreRoot());
        assertEquals(data.resolve("worldmap"), paths.legacyWorldmapRoot());
        assertEquals(data.resolve("worldgen"), paths.legacyWorldgenRoot());
        assertEquals(data.resolve("uploads"), paths.legacyUploadsRoot());
    }

    @Test
    @DisplayName("archive-dir 显式配置生效，且同样绝对化")
    void archiveDirCanBeConfigured() {
        WorldStoreProperties p = props("");
        p.setArchiveDir("./backups/archive");
        WorldPaths paths = new WorldPaths(p, "./data");
        assertTrue(paths.archiveRoot().isAbsolute(), "archive-dir 必须绝对化");
        assertTrue(paths.archiveRoot().endsWith("archive"), "显式 archive-dir 应生效：" + paths.archiveRoot());
        assertFalse(paths.archiveRoot().equals(paths.dataDir().resolve("archive")),
                "显式 archive-dir 不应再派生");
    }

    @Test
    @DisplayName("H2 连接串：绝对路径 + 正斜杠 + 与主库同一套方言参数")
    void h2UrlsAreAbsoluteAndShareDialect() {
        WorldPaths paths = new WorldPaths(props(""), "./data");
        String worldUrl = paths.worldDbUrl("w1");
        String registryUrl = paths.registryDbUrl();

        assertTrue(worldUrl.startsWith("jdbc:h2:file:"), worldUrl);
        assertFalse(worldUrl.contains("\\"), "Windows 反斜杠会破坏 H2 URL 解析：" + worldUrl);
        assertTrue(worldUrl.contains(WorldPaths.H2_PARAMS), "世界库必须与主库同参数（否则大小写语义不一致）：" + worldUrl);
        assertTrue(registryUrl.contains(WorldPaths.H2_PARAMS));
        assertFalse(worldUrl.contains(".mv.db"), "H2 连接串用文件基名，不能带 .mv.db 后缀：" + worldUrl);
    }

    @Test
    @DisplayName("uid 安全：拦住路径穿越/分隔符/空白/超长")
    void uidIsValidated() {
        WorldPaths paths = new WorldPaths(props(""), "./data");
        String[] bad = {"", " ", "../evil", "a/b", "a\\b", "..", "x y", "a".repeat(65), "a.b"};
        for (String uid : bad) {
            assertThrows(IllegalArgumentException.class, () -> paths.worldDir(uid),
                    "非法 uid 必须被拒绝：" + uid);
        }
        assertThrows(IllegalArgumentException.class, () -> paths.worldDir(null), "null uid 必须被拒绝");

        // 合法形态：UUID、下划线、连字符
        assertEquals("41057f92-c7d9-4dc5-9176-b621be0d0466",
                WorldPaths.requireSafeUid("41057f92-c7d9-4dc5-9176-b621be0d0466"));
        assertEquals("w_1", WorldPaths.requireSafeUid("w_1"));
        assertEquals("w-1", WorldPaths.requireSafeUid(" w-1 "), "应容忍首尾空白");
    }

    @Test
    @DisplayName("扫描：只认含 manifest.json 的目录，按名字排序，worlds 不存在时返回空表")
    void listWorldDirsOnlyReturnsManifestDirs(@TempDir Path tmp) throws IOException {
        WorldPaths paths = new WorldPaths(props(tmp.resolve("worlds").toString()), tmp.resolve("data").toString());
        assertTrue(paths.listWorldDirs().isEmpty(), "worlds 目录不存在时应返回空表而不是抛异常");

        Path w2 = paths.worldDir("b-world");
        Path w1 = paths.worldDir("a-world");
        Files.createDirectories(w2);
        Files.createDirectories(w1);
        Files.writeString(paths.manifestFile("a-world"), "{}", java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(paths.manifestFile("b-world"), "{}", java.nio.charset.StandardCharsets.UTF_8);
        // 干扰项：没有 manifest 的目录（如 resources/）不应被当成世界
        Files.createDirectories(paths.worldsRoot().resolve("not-a-world"));

        List<Path> found = paths.listWorldDirs();
        assertEquals(2, found.size(), "只应识别含 manifest.json 的目录：" + found);
        assertEquals("a-world", found.get(0).getFileName().toString(), "应按目录名排序");
        assertEquals("b-world", found.get(1).getFileName().toString());
    }

    @Test
    @DisplayName("归档目录：<data-dir>/archive/<tag>，tag 同样受安全校验")
    void archiveDirIsUnderDataDir() {
        WorldPaths paths = new WorldPaths(props(""), "./data");
        assertEquals(paths.archiveRoot().resolve("pre-worldsplit-20261024"), paths.archiveDir("pre-worldsplit-20261024"));
        assertThrows(IllegalArgumentException.class, () -> paths.archiveDir("../escape"));
    }
}
