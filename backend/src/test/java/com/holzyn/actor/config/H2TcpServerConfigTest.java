package com.holzyn.actor.config;

import com.holzyn.actor.common.DataDirResolver;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2TcpServerConfig（2026-08-21 H2 TCP Server）单元测试。
 * <p>职责：验证数据目录解析口径（系统属性优先 / 缺省 ./data 绝对化）与 TCP Server
 * 创建/启动/停止生命周期（端口、运行态）。</p>
 */
class H2TcpServerConfigTest {

    /**
     * 数据目录解析：系统属性优先（DataDirResolver 已在 main 归一化写入），结果绝对化。
     */
    @Test
    void resolvedDataDirPrefersSystemProperty() {
        System.setProperty(DataDirResolver.ENV_DATA_DIR, "C:/any/data");
        try {
            String dir = H2TcpServerConfig.resolvedDataDir();
            assertTrue(Path.of(dir).isAbsolute(), "必须为绝对路径");
            assertTrue(dir.replace('\\', '/').endsWith("/data"), "应使用系统属性目录");
        } finally {
            System.clearProperty(DataDirResolver.ENV_DATA_DIR);
        }
    }

    /**
     * 数据目录解析：未显式设置时回退缺省 ./data（绝对化到工作目录）。
     * <p>仅当本机未设 {@code HOLOZYN_ACTOR_DATA_DIR} 环境变量时断言默认分支
     * （环境变量无法在测试内清除，故用假设跳过）。</p>
     */
    @Test
    void resolvedDataDirDefaultsToDataDir() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(DataDirResolver.ENV_DATA_DIR) == null,
                "本机设置了 HOLOZYN_ACTOR_DATA_DIR，跳过默认分支断言");
        System.clearProperty(DataDirResolver.ENV_DATA_DIR);
        String dir = H2TcpServerConfig.resolvedDataDir();
        assertTrue(Path.of(dir).isAbsolute(), "默认 ./data 也应绝对化");
        assertTrue(dir.endsWith("data"), "缺省应指向 <工作目录>/data，实际=" + dir);
    }

    /**
     * TCP Server 生命周期：创建未启动 → start 后运行且端口正确 → stop 后停止。
     */
    @Test
    void createsAndStartsTcpServer() throws SQLException {
        Server server = new H2TcpServerConfig().h2TcpServer(19092, false);
        assertNotNull(server);
        assertFalse(server.isRunning(false), "创建后不应自动启动（由 Spring initMethod 启动）");
        server.start();
        try {
            assertTrue(server.isRunning(false), "start 后应运行");
            assertEquals(19092, server.getPort(), "监听端口应与配置一致");
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning(false), "stop 后应停止");
    }
}
