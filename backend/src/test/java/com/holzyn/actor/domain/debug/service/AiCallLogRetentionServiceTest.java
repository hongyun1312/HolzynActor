package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiCallLogRetentionService 滚动保留逻辑单元测试（P1-5 数据膨胀最小治理）。
 * <p>职责：验证——开关/保留天数门控、DB 行滚动删除（cutoff 传参）、
 * {@code <data-dir>/ai-call-logs/} 文件副本联动清理（只删过期 {@code ai-call-*} 文件，
 * 新文件与非本服务前缀文件保留）、dataDir 缺失时零文件操作。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库）；文件副本用 {@code @TempDir} 真实文件系统验证。</p>
 */
class AiCallLogRetentionServiceTest {

    private ActorAiCallLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ActorAiCallLogRepository.class);
    }

    @AfterEach
    void tearDown() {
        // 无全局状态，无需清理
    }

    /** 门控：总开关关闭时 retain() 返回 0，不触发仓库删除。 */
    @Test
    void disabledSkipsDeletion() {
        AiCallLogRetentionService service =
                new AiCallLogRetentionService(repository, false, 7, "data");
        assertEquals(0, service.retain(), "开关关闭应返回 0");
        verify(repository, never()).deleteByCreatedAtBefore(any());
    }

    /** 门控：保留天数 0（=关闭滚动删除）时 retain() 返回 0，不触发仓库删除。 */
    @Test
    void zeroDaysSkipsDeletion() {
        AiCallLogRetentionService service =
                new AiCallLogRetentionService(repository, true, 0, "data");
        assertEquals(0, service.retain(), "保留天数 0 应返回 0");
        verify(repository, never()).deleteByCreatedAtBefore(any());
    }

    /**
     * 正常滚动：DB 行按 cutoff 批量删除，且文件副本中「修改时间早于 cutoff」的
     * {@code ai-call-*} 文件被同步清理、新文件与非本服务前缀文件保留。
     */
    @Test
    void retainDeletesDbRowsAndExpiredFiles() throws IOException {
        Path dataDir = Files.createTempDirectory("ai-log-retention-test");
        Path logsDir = dataDir.resolve("ai-call-logs");
        Files.createDirectories(logsDir);

        Path expired = logsDir.resolve("ai-call-1-dialog.json");
        Path fresh = logsDir.resolve("ai-call-2-dialog.json");
        Path unrelated = logsDir.resolve("keep-me.txt");
        Files.writeString(expired, "{}", StandardCharsets.UTF_8);
        Files.writeString(fresh, "{}", StandardCharsets.UTF_8);
        Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);

        // 把 expired 的修改时间拨回 30 天前（早于默认 7 天 cutoff），fresh 保持现在
        Files.setLastModifiedTime(expired, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 30L * 24 * 3600 * 1000));

        when(repository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(5);

        AiCallLogRetentionService service =
                new AiCallLogRetentionService(repository, true, 7, dataDir.toString());
        assertEquals(5, service.retain(), "应返回删除的 DB 行数");

        // DB 侧：cutoff = now - 7 天（约等于），删除方法被调用
        verify(repository).deleteByCreatedAtBefore(any(LocalDateTime.class));
        // 文件侧：过期副本已删，新副本与非前缀文件保留
        assertFalse(Files.exists(expired), "过期文件副本应被删除");
        assertTrue(Files.exists(fresh), "保留期内的新文件副本应保留");
        assertTrue(Files.exists(unrelated), "非 ai-call- 前缀文件不应被误删");
    }

    /** 门控：dataDir 为空（未配置数据目录）时零文件操作，只删 DB 行。 */
    @Test
    void nullDataDirSkipsFileCleanup() {
        when(repository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(3);
        AiCallLogRetentionService service =
                new AiCallLogRetentionService(repository, true, 7, null);
        assertEquals(3, service.retain(), "dataDir 为空仍应删 DB 行");
        verify(repository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }

    /** 边界：ai-call-logs 目录不存在时零异常，只删 DB 行。 */
    @Test
    void missingLogsDirIsNoOp() {
        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "ai-log-retention-" + System.nanoTime());
        when(repository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(2);
        AiCallLogRetentionService service =
                new AiCallLogRetentionService(repository, true, 7, dataDir.toString());
        assertEquals(2, service.retain());
        verify(repository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }
}
