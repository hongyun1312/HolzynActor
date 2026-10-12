package com.holzyn.actor.ai.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关并发限流配置段「可加载性 + 默认值 + 环境变量覆盖」校验（2026-09-29_07）。
 * <p>背景：本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错/占位符写坏不会被既有套件发现，
 * 只会在真实启动时炸。本测试用 {@link YamlPropertySourceLoader} + {@link StandardEnvironment}
 * 直接加载 application.yml（gateway-concurrency 属系统底层 ai 段，在 application.yml 主文件），
 * 不启 Spring 上下文、不连 H2、不调 AI。</p>
 * <p>锁定不变量：①默认关闭（max-concurrent=0，DeepSeek API 主用场景零影响）；
 * ②等待超时默认 45s；③同名环境变量可覆盖（本地模型测试逃生门）。</p>
 * <p>所属模块：test/ai/spring（AI 接入层-网关并发限流配置校验）</p>
 */
class GatewayConcurrencyYamlTest {

    private static final String P = "holzyn.actor.ai.gateway-concurrency.";

    private static StandardEnvironment env() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertTrue(!sources.isEmpty(), "application.yml 加载结果为空");
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static int intAt(StandardEnvironment env, String key) {
        String raw = env.getProperty(key);
        assertNotNull(raw, "缺失或键名拼错：" + key);
        return Integer.parseInt(raw.trim());
    }

    @Test
    @DisplayName("yml 可加载：默认关闭（max-concurrent=0）+ 等待超时 45s")
    void gatewayConcurrencyResolvesFromYaml() throws IOException {
        StandardEnvironment env = env();
        assertEquals(0, intAt(env, P + "max-concurrent"), "默认应不限流（0=DeepSeek API 主用零影响）");
        assertEquals(45, intAt(env, P + "wait-timeout-seconds"), "排队等待超时默认 45s");
    }

    @Test
    @DisplayName("环境变量覆盖：本地模型测试可经 env 开启并发上限")
    void gatewayConcurrencyOverridableByEnvName() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_MAX_CONCURRENT", "8");
        System.setProperty("HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_WAIT_TIMEOUT_SECONDS", "60");
        try {
            StandardEnvironment env = env();
            assertEquals(8, intAt(env, P + "max-concurrent"), "环境变量未覆盖 max-concurrent");
            assertEquals(60, intAt(env, P + "wait-timeout-seconds"), "环境变量未覆盖 wait-timeout-seconds");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_MAX_CONCURRENT");
            System.clearProperty("HOLOZYN_ACTOR_AI_GATEWAY_CONCURRENCY_WAIT_TIMEOUT_SECONDS");
        }
        // 清理后回到默认值（避免污染同 JVM 的其它测试）
        assertEquals(0, intAt(env(), P + "max-concurrent"));
        assertEquals(45, intAt(env(), P + "wait-timeout-seconds"));
    }
}
