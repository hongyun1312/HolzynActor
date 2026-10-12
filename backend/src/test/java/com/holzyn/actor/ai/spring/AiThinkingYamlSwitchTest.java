package com.holzyn.actor.ai.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考模式开关的「可加载性 + 默认值 + 逃生门可覆盖性」校验
 * （2026-09-27 输出 token 优化轮；2026-09-29 配置文件拆分后适配）。
 *
 * <p><b>为什么必须有这个测试</b>：本仓库<b>没有任何 {@code @SpringBootTest}</b>（253 个测试类都不加载
 * application.yml）⇒ yml 语法错误、键名拼错、占位符写坏既不会被既有套件发现，也不会被
 * {@code AiSpringChatGatewayThinkingTest}（它用 {@code ReflectionTestUtils} 直接注入字段）发现，
 * 只会在真实启动时炸。思考开关的键是新增的 {@code holzyn.actor.ai.thinking.mode}，一旦真实启动时
 * 解析不到，代码侧会**静默**按 auto 兜底（不下发 thinking 字段）——即「以为关了思考、其实没关」，
 * 这是最危险的失败模式，必须由 yml 级测试兜住。</p>
 *
 * <p>用法与 {@code RoutingYamlMaxTokensTest} 一致：{@link YamlPropertySourceLoader} +
 * {@link StandardEnvironment} 直接加载 application.yml（2026-09-29 拆分后：thinking 属系统底层段，
 * 在 application.yml 主文件，另合并加载 application-actor.yml 模拟真实启动合并环境），不启 Spring
 * 上下文、不连 H2、不调 AI。</p>
 *
 * <p>所属模块：test/ai/spring（AI 接入层-思考模式配置校验）</p>
 */
class AiThinkingYamlSwitchTest {

    private static final String P = "holzyn.actor.ai.thinking.";
    /** yml 中允许的三个合法取值（网关侧对非法值 fail-soft 兜底为 auto，但 yml 本身不该写错）。 */
    private static final Set<String> LEGAL_MODES = Set.of("disabled", "enabled", "auto");

    /** 合并加载 application.yml + application-actor.yml 到 StandardEnvironment（占位符可解析）。 */
    private static StandardEnvironment env() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertTrue(!sources.isEmpty(), "application.yml 加载结果为空");
        List<PropertySource<?>> actorSources = new YamlPropertySourceLoader()
                .load("application-actor", new ClassPathResource("application-actor.yml"));
        assertTrue(!actorSources.isEmpty(), "application-actor.yml 加载结果为空");
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        actorSources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    @Test
    @DisplayName("yml 可加载：thinking.mode 默认 disabled、effort 默认为空（键名未拼错）")
    void thinkingSwitchDefaultsFromYaml() throws IOException {
        StandardEnvironment env = env();

        String mode = env.getProperty(P + "mode");
        assertNotNull(mode, "缺失或键名拼错：" + P + "mode —— 真实启动会静默按 auto 兜底（=没关思考）");
        assertEquals("disabled", mode.trim(),
                "默认必须是 disabled：第 3 轮长跑 66.7% 输出 token 是隐藏思考，默认档位就是省钱档");

        String effort = env.getProperty(P + "effort");
        assertNotNull(effort, "缺失或键名拼错：" + P + "effort");
        assertTrue(effort.trim().isEmpty(), "effort 默认必须为空（关思考时下发它无意义；非空只在 enabled 时使用）");
    }

    @Test
    @DisplayName("不变量：yml 里的 mode 必须是三个合法取值之一（笔误兜底为 auto 会悄悄放开思考）")
    void yamlModeIsOneOfLegalValues() throws IOException {
        String mode = env().getProperty(P + "mode");
        assertNotNull(mode);
        assertTrue(LEGAL_MODES.contains(mode.trim().toLowerCase(java.util.Locale.ROOT)),
                "思考模式取值非法：" + mode + "，合法值=" + LEGAL_MODES
                        + "（网关会 fail-soft 成 auto，即不再下发 thinking、思考照旧开启）");
    }

    @Test
    @DisplayName("逃生门：HOLOZYN_ACTOR_AI_THINKING_MODE= 可覆盖（注意无连字符，Spring 松弛绑定会删连字符）")
    void modeOverridableByEnvName() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_AI_THINKING_MODE", "enabled");
        try {
            assertEquals("enabled", env().getProperty(P + "mode"), "环境变量未覆盖思考模式");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_AI_THINKING_MODE");
        }
        // 清理后回到默认值（避免污染同 JVM 的其它测试）
        assertEquals("disabled", env().getProperty(P + "mode"));
    }

    @Test
    @DisplayName("逃生门：HOLOZYN_ACTOR_AI_THINKING_EFFORT= 可覆盖（原默认空串）")
    void effortOverridableByEnvName() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_AI_THINKING_EFFORT", "high");
        try {
            assertEquals("high", env().getProperty(P + "effort"), "环境变量未覆盖思考强度");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_AI_THINKING_EFFORT");
        }
        assertTrue(env().getProperty(P + "effort").trim().isEmpty(), "清理后应回到空默认值");
    }

    @Test
    @DisplayName("键位正确：thinking 处于 holzyn.actor.ai 之下（同 ai 其它配置并列，非顶层）")
    void thinkingSwitchLivesUnderAiPrefix() throws IOException {
        StandardEnvironment env = env();
        // 与既有 ai 配置并列出现即可证明层级正确（若误缩进到别处，这两个键会有其一读不到）
        assertNotNull(env.getProperty("holzyn.actor.ai.timeout-read-seconds"),
                "holzyn.actor.ai 前缀读取异常（yml 缩进可能被破坏）");
        assertNotNull(env.getProperty(P + "mode"));
        assertNotNull(env.getProperty(P + "effort"));
    }
}
