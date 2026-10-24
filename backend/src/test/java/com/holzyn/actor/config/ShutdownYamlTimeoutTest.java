package com.holzyn.actor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停机阶段上限（{@code spring.lifecycle.timeout-per-shutdown-phase}）配置校验（2026-10-23）。
 *
 * <p>背景：本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错/占位符写坏不会被既有套件
 * 发现，只会在真实启动时炸（见 AGENTS.md §8.4-3）。本次新增的这个键决定**关闭流程**是否
 * 干净：桌面端退出时窗口里仍挂着世界实时 SSE 长连接，Tomcat 优雅停机停在
 * 「Waiting for active requests to complete」，桌面端 8s 超时后 taskkill 进程树 → H2 不是干净落盘
 * （真实日志：20:18:13 收到关闭请求 → 20:18:21 强杀）。把停机阶段上限压到 3s 后实测
 * 「19:28:29.223 请求 → 19:28:33.113 进程退出（exitCode=0）」，Hikari/H2 依次干净关闭。</p>
 *
 * <p>锁定不变量：①键必须存在且可解析（拼错就被本测试拦住）；②解析值必须显著小于桌面端
 * 等待超时（8s），否则桌面端又会走强杀分支。</p>
 *
 * <p>所属模块：test/config（系统底层配置校验）</p>
 */
class ShutdownYamlTimeoutTest {

    /** Spring 的停机阶段超时键（SpringApplication 生命周期，非 server.* 段） */
    private static final String KEY = "spring.lifecycle.timeout-per-shutdown-phase";

    /** 桌面端等待后端退出的超时（desktop/main.js：waitForProcExit(backend, 8000)） */
    private static final long DESKTOP_EXIT_TIMEOUT_MS = 8_000L;

    /** 必须留给桌面端的余量：上下文销毁（Hikari/H2/EntityManagerFactory）与进程退出开销 */
    private static final long HEADROOM_MS = 3_000L;

    /** 只加载 application.yml（该键属系统底层段，在主文件里） */
    private static StandardEnvironment env() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertTrue(!sources.isEmpty(), "application.yml 加载结果为空");
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    /** 解析 Spring 时长写法（3s / 3000ms / 3m / 纯数字=毫秒）为毫秒；无法解析返回 -1 */
    static long parseMillis(String raw) {
        if (raw == null) {
            return -1L;
        }
        String text = raw.trim().toLowerCase();
        if (text.isEmpty()) {
            return -1L;
        }
        try {
            if (text.endsWith("ms")) {
                return Long.parseLong(text.substring(0, text.length() - 2).trim());
            }
            if (text.endsWith("s")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 1000L;
            }
            if (text.endsWith("m")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 60_000L;
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @Test
    @DisplayName("yml 可加载：停机阶段上限存在、可解析，且比桌面端 8s 超时至少早 3s")
    void shutdownPhaseTimeoutResolvesFromYaml() throws IOException {
        StandardEnvironment env = env();
        String raw = env.getProperty(KEY);
        assertNotNull(raw, "缺失或键名拼错：" + KEY);

        long millis = parseMillis(raw);
        assertTrue(millis > 0, "无法解析停机阶段上限：" + raw);
        assertTrue(millis <= DESKTOP_EXIT_TIMEOUT_MS - HEADROOM_MS,
                "停机阶段上限 " + millis + "ms 太大：桌面端 " + DESKTOP_EXIT_TIMEOUT_MS
                        + "ms 后会强杀进程树（H2 不再干净落盘），必须 ≤ "
                        + (DESKTOP_EXIT_TIMEOUT_MS - HEADROOM_MS) + "ms");
    }

    @Test
    @DisplayName("时长解析：3s/2500ms/1m/纯数字 都按毫秒解析")
    void parseMillisHandlesSpringDurationStyles() {
        assertTrue(parseMillis("3s") == 3_000L, "3s 解析错误");
        assertTrue(parseMillis(" 2500ms ") == 2_500L, "2500ms 解析错误");
        assertTrue(parseMillis("1m") == 60_000L, "1m 解析错误");
        assertTrue(parseMillis("1500") == 1_500L, "纯数字按毫秒解析");
        assertTrue(parseMillis(null) == -1L, "null 应返回 -1");
        assertTrue(parseMillis("abc") == -1L, "非法时长应返回 -1");
    }
}
