package com.holzyn.actor.domain.worldstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界清单读写与兼容性校验（V1.1.2-beta.1 M1）。
 *
 * <p>这些断言对应四类真实事故：</p>
 * <ol>
 *   <li><b>半截清单</b>：写盘过程中进程被杀 → 下次启动把世界判成损坏。故写入走 tmp+move，
 *       测试断言写完不留 {@code .tmp} 残留；</li>
 *   <li><b>中文世界名</b>：中文/emoji 经过 JSON 转义后必须原样回来（历史上出现过 GBK
 *       控制台写文件导致中文永久损坏，见 AGENTS §8.2）；</li>
 *   <li><b>格式不兼容</b>：旧格式存档必须被**明确拒绝**并给出修法，而不是打开后缺列 500；</li>
 *   <li><b>可搬移性</b>：{@code dbFile} 一旦含路径/盘符就必须拒绝——绝对路径会让
 *       整个世界目录与某台机器绑定，拷走即失效。</li>
 * </ol>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldManifestTest {

    private final WorldManifestService service = new WorldManifestService();

    private static WorldManifest sample(String uid) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("actor_profile", 24L);
        counts.put("actor_event", 1385L);
        Map<String, String> sums = new LinkedHashMap<>();
        sums.put("actor_world_clock", "sha256:abc123");
        return new WorldManifest(
                WorldManifest.CURRENT_FORMAT_VERSION,
                "1.1.2-beta.1",
                uid,
                "青禾村",
                "2026-08-01T10:00:00",
                "2026-10-24T21:00:00",
                "2026-10-24T23:10:00",
                new WorldManifest.Source("legacy-split", "holzyn-actor.mv.db", "sha256:deadbeef", "2026-10-24T22:00:00"),
                counts, sums, List.of("vector-store", "worldmap"), WorldManifest.DEFAULT_DB_FILE);
    }

    @Test
    @DisplayName("往返：所有字段（含中文世界名/计数/校验和/来源）原样读回")
    void roundTripPreservesEveryField(@TempDir Path tmp) {
        Path worldDir = tmp.resolve("41057f92-c7d9-4dc5-9176-b621be0d0466");
        WorldManifest in = sample("41057f92-c7d9-4dc5-9176-b621be0d0466");
        service.write(worldDir, in);

        assertTrue(Files.isRegularFile(worldDir.resolve(WorldManifestService.FILE_NAME)), "应写出 manifest.json");
        assertFalse(Files.exists(worldDir.resolve(WorldManifestService.FILE_NAME + ".tmp")), "写入后不应残留 .tmp");

        WorldManifest out = service.read(worldDir);
        assertNotNull(out);
        assertEquals(in, out, "往返后清单必须完全相等（含中文名与 Map 顺序无关语义）");
        assertEquals("青禾村", out.worldName(), "中文世界名不得损坏");
        assertEquals(1385L, out.counts().get("actor_event").longValue());
        assertEquals("legacy-split", out.source().kind());
        assertEquals("sha256:deadbeef", out.source().fromSha256());
    }

    @Test
    @DisplayName("缺文件：read 返回 null（不是抛异常）——调用方据此判定不是世界目录")
    void missingManifestReturnsNull(@TempDir Path tmp) {
        assertNull(service.read(tmp), "无 manifest.json 时应返回 null");
        WorldManifestException ex = assertThrows(WorldManifestException.class,
                () -> service.readCompatible(tmp), "readCompatible 对非世界目录必须抛");
        assertTrue(ex.getMessage().contains("不是世界目录"), ex.getMessage());
    }

    @Test
    @DisplayName("格式不兼容：旧 formatVersion 明确拒绝，消息含当前版本与修法")
    void incompatibleFormatIsRejectedWithActionableMessage(@TempDir Path tmp) throws IOException {
        Path worldDir = tmp.resolve("w1");
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve(WorldManifestService.FILE_NAME),
                "{\"formatVersion\":\"2.0\",\"worldUid\":\"w1\",\"worldName\":\"旧世界\",\"dbFile\":\"world.mv.db\"}",
                StandardCharsets.UTF_8);

        WorldManifest m = service.read(worldDir);
        assertNotNull(m, "旧格式也要能读出字段，好让 UI 展示「由哪个版本创建」");
        assertEquals("2.0", m.formatVersion());

        WorldManifestException ex = assertThrows(WorldManifestException.class,
                () -> service.readCompatible(worldDir), "旧格式必须被 readCompatible 拒绝");
        assertTrue(ex.getMessage().contains("3.0"), "消息应说明需要的格式版本：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("2.0"), "消息应说明实际版本：" + ex.getMessage());
    }

    @Test
    @DisplayName("目录名与 worldUid 不一致：拒绝（拷贝时手动改目录名的典型症状）")
    void uidMustMatchDirectoryName(@TempDir Path tmp) {
        Path worldDir = tmp.resolve("renamed-dir");
        service.write(worldDir, sample("original-uid"));
        WorldManifestException ex = assertThrows(WorldManifestException.class,
                () -> service.readCompatible(worldDir));
        assertTrue(ex.getMessage().contains("目录名"), ex.getMessage());
    }

    @Test
    @DisplayName("可搬移性：dbFile 含路径/盘符/反斜杠一律拒绝")
    void dbFileMustBeRelativeName(@TempDir Path tmp) {
        WorldManifest base = sample("w1");
        for (String bad : new String[]{"C:\\data\\world.mv.db", "/abs/world.mv.db", "sub/world.mv.db", "..\\x.mv.db"}) {
            Path worldDir = tmp.resolve("w1");
            WorldManifest m = new WorldManifest(base.formatVersion(), base.schemaVersion(), base.worldUid(),
                    base.worldName(), base.createdAt(), base.lastOpenedAt(), base.lastClosedAt(),
                    base.source(), base.counts(), base.checksums(), base.resources(), bad);
            service.write(worldDir, m);
            WorldManifestException ex = assertThrows(WorldManifestException.class,
                    () -> service.readCompatible(worldDir), "应拒绝 dbFile=" + bad);
            assertTrue(ex.getMessage().contains("dbFile"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("缺必填字段：消息点名是哪个字段（而不是笼统的反序列化失败）")
    void missingRequiredFieldNamesTheField(@TempDir Path tmp) throws IOException {
        Path worldDir = tmp.resolve("w1");
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve(WorldManifestService.FILE_NAME),
                "{\"formatVersion\":\"3.0\",\"worldName\":\"无 uid\"}", StandardCharsets.UTF_8);
        WorldManifestException ex = assertThrows(WorldManifestException.class, () -> service.read(worldDir));
        assertTrue(ex.getMessage().contains("worldUid"), "消息应点名缺失字段：" + ex.getMessage());
    }

    @Test
    @DisplayName("非法 JSON：抛出带文件路径的清单异常（不让底层解析异常直接冒到启动流程）")
    void brokenJsonWrappedWithPath(@TempDir Path tmp) throws IOException {
        Path worldDir = tmp.resolve("w1");
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve(WorldManifestService.FILE_NAME), "{not json", StandardCharsets.UTF_8);
        WorldManifestException ex = assertThrows(WorldManifestException.class, () -> service.read(worldDir));
        assertTrue(ex.getMessage().contains("manifest.json"), ex.getMessage());
    }

    @Test
    @DisplayName("fresh/withTimes 便捷构造：新建世界字段自洽，时间可派生")
    void freshAndWithTimesHelpers() {
        WorldManifest m = WorldManifest.fresh("w1", "新世界", "1.1.2-beta.1", "2026-10-24T22:00:00");
        assertEquals(WorldManifest.CURRENT_FORMAT_VERSION, m.formatVersion());
        assertEquals("new", m.source().kind());
        assertEquals(WorldManifest.DEFAULT_DB_FILE, m.dbFile());
        assertTrue(m.counts().isEmpty(), "新建世界的计数应留空（挂载后回写）");
        assertTrue(m.resources().contains("uploads"), "新建世界应带 uploads 资源目录");

        WorldManifest moved = m.withTimes("2026-10-25T09:00:00", null);
        assertEquals("2026-10-25T09:00:00", moved.lastOpenedAt());
        assertNull(moved.lastClosedAt());
        assertEquals(m.createdAt(), moved.createdAt(), "withTimes 不应改动创建时间");
    }
}
