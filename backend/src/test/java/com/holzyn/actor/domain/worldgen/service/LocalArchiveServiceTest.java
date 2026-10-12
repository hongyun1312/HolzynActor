package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalArchiveService（M2：检查点存档语义，移植 local_archive.py prepare/checkpoint）单元测试。
 * <p>职责：验证——工作区路径解析（data/worldgen/&lt;project&gt;/&lt;task&gt;）、相对路径安全
 * （拒绝绝对路径/.. 越权）、JSON 写盘合法化、checkpoint 校验（存在且非空 / 缺失 / 空文件）。</p>
 */
class LocalArchiveServiceTest {

    private LocalArchiveService service;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("worldgen-archive-test");
        service = new LocalArchiveService();
        ReflectionTestUtils.setField(service, "dataDir", dataDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        // 清理临时目录（递归）
        if (dataDir != null && Files.exists(dataDir)) {
            try (var walk = Files.walk(dataDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 忽略清理失败
                    }
                });
            }
        }
    }

    @Test
    void taskWorkspaceResolvesUnderDataDirWorldgen() {
        Path ws = service.taskWorkspace(36L, 42L);
        assertTrue(ws.toString().contains("worldgen"));
        assertTrue(ws.toString().contains("36"));
        assertTrue(ws.toString().contains("42"));
    }

    @Test
    void safeResolveRejectsTraversalAndAbsolute() {
        Path ws = service.taskWorkspace(1L, 1L);
        assertThrows(BizException.class, () -> service.safeResolve(ws, "../evil.json"));
        assertThrows(BizException.class, () -> service.safeResolve(ws, "a/../../evil.json"));
        assertThrows(BizException.class, () -> service.safeResolve(ws, "C:/windows/evil.json"));
        assertThrows(BizException.class, () -> service.safeResolve(ws, "/abs/evil.json"));
        assertThrows(BizException.class, () -> service.safeResolve(ws, ""));
    }

    @Test
    void writeJsonValidatesAndWrites() throws IOException {
        Path ws = service.taskWorkspace(2L, 3L);
        service.writeJson(ws, "world/core_laws.json", "{\"core_laws\":{\"essence\":\"法则\"}}");
        Path f = ws.resolve("world/core_laws.json");
        assertTrue(Files.exists(f));
        assertTrue(Files.size(f) > 0);
        // 非法 JSON 拒绝写盘
        assertThrows(BizException.class, () -> service.writeJson(ws, "world/bad.json", "{not json"));
    }

    @Test
    void checkpointValidatesFilesExistAndNonEmpty() throws IOException {
        Path ws = service.taskWorkspace(2L, 3L);
        Files.createDirectories(ws.resolve("world"));
        Files.writeString(ws.resolve("world/a.json"), "{\"a\":1}");
        Files.writeString(ws.resolve("world/b.json"), "{\"b\":2}");
        // 全部存在 → 通过，计数 2
        assertEquals(2, service.checkpoint(ws, java.util.List.of("world/a.json", "world/b.json")));
        // 缺失 → 400
        BizException missing = assertThrows(BizException.class,
                () -> service.checkpoint(ws, java.util.List.of("world/a.json", "world/none.json")));
        assertTrue(missing.getMessage().contains("world/none.json"));
        // 空文件 → 400
        Files.writeString(ws.resolve("world/empty.json"), "");
        BizException empty = assertThrows(BizException.class,
                () -> service.checkpoint(ws, java.util.List.of("world/empty.json")));
        assertTrue(empty.getMessage().contains("为空"));
    }
}
